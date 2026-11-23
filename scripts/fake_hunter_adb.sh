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

usage() {
    echo "Fake Hunter ADB Runner"
    echo "Usage: ./fake_hunter_adb.sh [options]"
    echo ""
    echo "Options:"
    echo "  -s <serial>        Target specific device serial / IP"
    echo "  --lang <tr|en>     Force language (Turkish or English)"
    echo "  --wake             Run CPU core wake-up test on device"
    echo "  --save <file>      Save terminal report to host file"
    echo "  --json <file>      Export JSON report to host file"
    echo "  --no-color         Disable ANSI colors"
    echo "  -h, --help         Show this help message"
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s)
            TARGET_SERIAL="$2"
            shift 2
            ;;
        --save)
            SAVE_FILE="$2"
            shift 2
            ;;
        --json)
            JSON_FILE="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            PASSTHROUGH_ARGS+=("$1")
            shift
            ;;
    esac
done

# Ensure ADB is available
if ! command -v adb >/dev/null 2>&1; then
    echo "Error: 'adb' command not found. Please install Android platform-tools or add them to PATH." >&2
    exit 1
fi

# Ensure device script exists
if [ ! -f "$DEVICE_SCRIPT" ]; then
    echo "Error: Device inspection script not found at $DEVICE_SCRIPT" >&2
    exit 1
fi

# Query connected devices
DEVICES_OUTPUT=$(adb devices | grep -v "^List of devices attached" | grep -v "^$" || true)

if [ -z "$DEVICES_OUTPUT" ]; then
    echo "Error: No connected Android devices or emulators found via ADB." >&2
    exit 1
fi

DEVICE_COUNT=$(echo "$DEVICES_OUTPUT" | wc -l)

if [ -z "$TARGET_SERIAL" ]; then
    if [ "$DEVICE_COUNT" -eq 1 ]; then
        TARGET_SERIAL=$(echo "$DEVICES_OUTPUT" | awk '{print $1}')
    else
        echo "Multiple devices detected:"
        echo "$DEVICES_OUTPUT"
        echo ""
        echo "Please specify a device with: ./fake_hunter_adb.sh -s <serial>" >&2
        exit 1
    fi
fi

ADB_CMD=(adb -s "$TARGET_SERIAL")

# Temporary path on Android device
REMOTE_TMP="/data/local/tmp/fake_hunter.sh"

echo "Connecting to device: $TARGET_SERIAL..."

# Push script to device
if ! "${ADB_CMD[@]}" push "$DEVICE_SCRIPT" "$REMOTE_TMP" >/dev/null 2>&1; then
    echo "Error: Failed to push inspection script to $TARGET_SERIAL. Ensure the device is connected and authorized." >&2
    exit 1
fi
"${ADB_CMD[@]}" shell "chmod +x $REMOTE_TMP" >/dev/null 2>&1 || true

# Build remote execution arguments
REMOTE_ARGS=()
if [ -n "$JSON_FILE" ]; then
    REMOTE_ARGS+=("--json" "/data/local/tmp/fake_hunter_result.json")
fi
if [ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]; then
    REMOTE_ARGS+=("${PASSTHROUGH_ARGS[@]}")
fi

# Execute script on device
OUTPUT=$("${ADB_CMD[@]}" shell "sh $REMOTE_TMP ${REMOTE_ARGS[*]:-}")

# Print output
echo "$OUTPUT"

# Save plain text report on host if requested
if [ -n "$SAVE_FILE" ]; then
    echo "$OUTPUT" | sed 's/\x1b\[[0-9;]*m//g' > "$SAVE_FILE"
    echo "Report saved to $SAVE_FILE"
fi

# Pull JSON report if requested
if [ -n "$JSON_FILE" ]; then
    "${ADB_CMD[@]}" pull "/data/local/tmp/fake_hunter_result.json" "$JSON_FILE" >/dev/null 2>&1 || true
    "${ADB_CMD[@]}" shell "rm -f /data/local/tmp/fake_hunter_result.json" >/dev/null 2>&1 || true
    echo "JSON report pulled to $JSON_FILE"
fi

# Cleanup remote script
"${ADB_CMD[@]}" shell "rm -f $REMOTE_TMP" >/dev/null 2>&1 || true
