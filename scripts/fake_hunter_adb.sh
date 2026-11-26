#!/usr/bin/env bash
# ==============================================================================
# Fake Hunter — Host-Side ADB Hardware Inspection Script
# Runs hardware audit on a connected Android device over ADB from the host PC.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_SCRIPT="${SCRIPT_DIR}/fake_hunter.sh"

TARGET_SERIAL=""
PASSTHROUGH_ARGS=()
SAVE_FILE=""
JSON_FILE=""

ADB_CMD=()
REMOTE_TMP=""
REMOTE_JSON=""

cleanup() {
    if [ "${#ADB_CMD[@]}" -gt 0 ] && { [ -n "$REMOTE_TMP" ] || [ -n "$REMOTE_JSON" ]; }; then
        "${ADB_CMD[@]}" shell "rm -f '$REMOTE_TMP' '$REMOTE_JSON'" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

usage() {
    cat <<'EOF'
Fake Hunter ADB Runner
Usage: ./fake_hunter_adb.sh [options]

Options:
  -s <serial>            Target specific device serial / IP
  --lang <tr|en>         Force language (Turkish or English)
  --wake                 Run CPU core wake-up test on device
  --root                 Perform deep root inspection
  --storage-test [mb]    Run physical storage verification test (default: 100MB)
  --save <file>          Save plain-text terminal report to host file
  --json <file>          Export JSON report to host file
  --no-color             Disable ANSI colors
  -h, --help             Show this help message
EOF
}

die_arg() {
    printf "Error: %s\n" "$1" >&2
    printf "Run './fake_hunter_adb.sh --help' for usage.\n" >&2
    exit 2
}

is_positive_int() {
    [[ "${1:-}" =~ ^[0-9]+$ ]] && [ "$1" -gt 0 ]
}

quote_sh() {
    local s="$1"
    s="${s//\'/\'\\\'\'}"
    printf "'%s'" "$s"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '-s' requires a device serial."
            TARGET_SERIAL="$2"
            shift 2
            ;;
        --save)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--save' requires a host file path."
            SAVE_FILE="$2"
            shift 2
            ;;
        --json)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--json' requires a host file path."
            JSON_FILE="$2"
            shift 2
            ;;
        --lang)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--lang' requires an argument (tr or en)."
            case "$2" in
                tr|TR|en|EN) ;;
                *) die_arg "Invalid language '$2'. Supported values: tr, en." ;;
            esac
            PASSTHROUGH_ARGS+=("--lang" "$2")
            shift 2
            ;;
        --wake|--root|--no-color)
            PASSTHROUGH_ARGS+=("$1")
            shift
            ;;
        --storage-test)
            if [ $# -ge 2 ] && [ -n "${2:-}" ] && [[ "$2" != -* ]]; then
                is_positive_int "$2" || die_arg "Invalid storage test size '$2'. Must be a positive integer in MB."
                PASSTHROUGH_ARGS+=("--storage-test" "$2")
                shift 2
            else
                PASSTHROUGH_ARGS+=("--storage-test")
                shift
            fi
            ;;
        --storage-mb)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--storage-mb' requires a positive integer in MB."
            is_positive_int "$2" || die_arg "Invalid storage test size '$2'. Must be a positive integer in MB."
            PASSTHROUGH_ARGS+=("--storage-test" "$2")
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die_arg "Unknown option '$1'."
            ;;
    esac
done

# Locate ADB binary
ADB_BIN=""
if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/platform-tools/adb" ]; then
    ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]; then
    ADB_BIN="${ANDROID_SDK_ROOT}/platform-tools/adb"
fi

if [ -z "$ADB_BIN" ]; then
    echo "Error: 'adb' command not found. Please install Android platform-tools or add them to PATH." >&2
    exit 1
fi

if [ ! -f "$DEVICE_SCRIPT" ]; then
    echo "Error: Device inspection script not found at $DEVICE_SCRIPT" >&2
    exit 1
fi

# Parse connected ADB devices strictly by state
READY_SERIALS=()
UNAUTH_SERIALS=()
OFFLINE_SERIALS=()
ALL_DEVICE_LINES=()

while IFS= read -r line; do
    line="${line%$'\r'}"
    [ -z "$line" ] && continue
    case "$line" in
        "List of devices attached"*|\**) continue ;;
    esac
    ALL_DEVICE_LINES+=("$line")
    serial="$(awk '{print $1}' <<<"$line")"
    state="$(awk '{print $2}' <<<"$line")"
    case "$state" in
        device) READY_SERIALS+=("$serial") ;;
        unauthorized) UNAUTH_SERIALS+=("$serial") ;;
        offline|*) OFFLINE_SERIALS+=("${serial} (${state})") ;;
    esac
done < <("$ADB_BIN" devices)

if [ -n "$TARGET_SERIAL" ]; then
    FOUND_STATE=""
    for line in "${ALL_DEVICE_LINES[@]:-}"; do
        [ -z "$line" ] && continue
        s="$(awk '{print $1}' <<<"$line")"
        st="$(awk '{print $2}' <<<"$line")"
        if [ "$s" = "$TARGET_SERIAL" ]; then
            FOUND_STATE="$st"
            break
        fi
    done

    if [ -z "$FOUND_STATE" ]; then
        echo "Error: Target device '$TARGET_SERIAL' was not found in 'adb devices'." >&2
        exit 1
    elif [ "$FOUND_STATE" = "unauthorized" ]; then
        echo "Error: Device '$TARGET_SERIAL' is UNAUTHORIZED." >&2
        echo "Please unlock the device screen and tap 'Allow' on the USB debugging prompt." >&2
        exit 1
    elif [ "$FOUND_STATE" != "device" ]; then
        echo "Error: Device '$TARGET_SERIAL' is not ready (current state: '$FOUND_STATE')." >&2
        exit 1
    fi
else
    if [ "${#READY_SERIALS[@]}" -eq 0 ]; then
        if [ "${#UNAUTH_SERIALS[@]}" -gt 0 ]; then
            echo "Error: Connected device(s) are UNAUTHORIZED: ${UNAUTH_SERIALS[*]}" >&2
            echo "Please unlock the device screen and accept the 'Allow USB debugging' dialog." >&2
            exit 1
        elif [ "${#OFFLINE_SERIALS[@]}" -gt 0 ]; then
            echo "Error: Connected device(s) are offline or unavailable: ${OFFLINE_SERIALS[*]}" >&2
            exit 1
        else
            echo "Error: No connected Android devices or emulators found via ADB." >&2
            exit 1
        fi
    elif [ "${#ALL_DEVICE_LINES[@]}" -eq 1 ] && [ "${#READY_SERIALS[@]}" -eq 1 ]; then
        TARGET_SERIAL="${READY_SERIALS[0]}"
    else
        echo "Multiple ADB entries detected:" >&2
        printf "  %s\n" "${ALL_DEVICE_LINES[@]}" >&2
        echo "" >&2
        echo "Please specify the target device explicitly: ./scripts/fake_hunter_adb.sh -s <serial>" >&2
        exit 1
    fi
fi

ADB_CMD=("$ADB_BIN" -s "$TARGET_SERIAL")
REMOTE_TMP="/data/local/tmp/fake_hunter_$$.sh"
REMOTE_JSON="/data/local/tmp/fake_hunter_result_$$.json"

echo "Connecting to device: $TARGET_SERIAL..."

if ! "${ADB_CMD[@]}" push "$DEVICE_SCRIPT" "$REMOTE_TMP" >/dev/null 2>&1; then
    echo "Error: Failed to push inspection script to $TARGET_SERIAL." >&2
    exit 1
fi
"${ADB_CMD[@]}" shell "chmod 755 $(quote_sh "$REMOTE_TMP")" >/dev/null 2>&1 || true

REMOTE_CMD="sh $(quote_sh "$REMOTE_TMP")"
if [ -n "$JSON_FILE" ]; then
    REMOTE_CMD+=" --json $(quote_sh "$REMOTE_JSON")"
fi
if [ "${#PASSTHROUGH_ARGS[@]}" -gt 0 ]; then
    for arg in "${PASSTHROUGH_ARGS[@]}"; do
        REMOTE_CMD+=" $(quote_sh "$arg")"
    done
fi

set +e
OUTPUT="$("${ADB_CMD[@]}" shell "$REMOTE_CMD")"
REMOTE_RC=$?
set -e

printf "%s\n" "$OUTPUT"

if [ "$REMOTE_RC" -ne 0 ]; then
    echo "Error: On-device inspection exited with status $REMOTE_RC." >&2
    exit "$REMOTE_RC"
fi

if [ -n "$SAVE_FILE" ]; then
    ESC="$(printf '\033')"
    printf "%s\n" "$OUTPUT" | tr -d '\r' | sed "s/${ESC}\[[0-9;]*m//g" > "$SAVE_FILE"
    if [ ! -s "$SAVE_FILE" ]; then
        echo "Error: Saved report file '$SAVE_FILE' is empty." >&2
        exit 1
    fi
    echo "Report saved to $SAVE_FILE"
fi

if [ -n "$JSON_FILE" ]; then
    rm -f "$JSON_FILE" 2>/dev/null || true
    if "${ADB_CMD[@]}" pull "$REMOTE_JSON" "$JSON_FILE" >/dev/null 2>&1 && [ -s "$JSON_FILE" ]; then
        echo "JSON report pulled to $JSON_FILE"
    else
        echo "Error: Failed to pull JSON report from $TARGET_SERIAL ($REMOTE_JSON -> $JSON_FILE)." >&2
        exit 1
    fi
fi
