#!/bin/sh
# ==============================================================================
# Fake Hunter — Hardware Authenticity & Fraud Detection Toolkit
# Standalone On-Device Inspection Script (POSIX / Android /system/bin/sh compliant)
# Zero internet permissions, zero external dependencies.
# ==============================================================================

set -u

SCRIPT_VERSION="1.0.0"

# Colors (ANSI)
COLOR_RESET="\033[0m"
COLOR_BOLD="\033[1m"
COLOR_GREEN="\033[32m"
COLOR_YELLOW="\033[33m"
COLOR_RED="\033[31m"
COLOR_CYAN="\033[36m"
COLOR_GRAY="\033[90m"

USE_COLOR=1
WAKE_TEST=0
TARGET_LANG=""
OUTPUT_FILE=""
JSON_FILE=""

# Parse arguments
while [ $# -gt 0 ]; do
    case "$1" in
        --lang)
            TARGET_LANG="$2"
            shift 2
            ;;
        --wake)
            WAKE_TEST=1
            shift
            ;;
        --output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        --json)
            JSON_FILE="$2"
            shift 2
            ;;
        --no-color)
            USE_COLOR=0
            shift
            ;;
        --help|-h)
            echo "Fake Hunter Toolkit v$SCRIPT_VERSION"
            echo "Usage: fake_hunter.sh [options]"
            echo "Options:"
            echo "  --lang <tr|en>     Force language (Turkish or English)"
            echo "  --wake             Run CPU core wake-up test"
            echo "  --output <file>    Save plain-text report to file"
            echo "  --json <file>      Export JSON report to file"
            echo "  --no-color         Disable ANSI terminal colors"
            echo "  --help, -h         Show this help message"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

if [ "$USE_COLOR" -eq 0 ]; then
    COLOR_RESET=""
    COLOR_BOLD=""
    COLOR_GREEN=""
    COLOR_YELLOW=""
    COLOR_RED=""
    COLOR_CYAN=""
    COLOR_GRAY=""
fi

# Determine Language (Default to TR if system language starts with tr, else EN)
if [ -z "$TARGET_LANG" ]; then
    SYS_LOCALE=""
    if command -v getprop >/dev/null 2>&1; then
        SYS_LOCALE="$(getprop persist.sys.locale 2>/dev/null || true)"
        if [ -z "$SYS_LOCALE" ]; then
            SYS_LOCALE="$(getprop ro.product.locale 2>/dev/null || true)"
        fi
    fi
    if [ -z "$SYS_LOCALE" ] && [ -n "${LANG:-}" ]; then
        SYS_LOCALE="$LANG"
    fi

    case "$SYS_LOCALE" in
        tr*|TR*) TARGET_LANG="tr" ;;
        *) TARGET_LANG="en" ;;
    esac
fi

# Localization helper
t() {
    key="$1"
    if [ "$TARGET_LANG" = "tr" ]; then
        case "$key" in
            TITLE) echo "=== FAKE HUNTER — DONANIM DOĞRULAMA RAPORU ===" ;;
            VERSION) echo "Araç Sürümü" ;;
            SYSTEM_INFO) echo "1. SİSTEM VE KİMLİK BİLGİLERİ" ;;
            RAM_INFO) echo "2. BELLEK (RAM) VE SWAP ANALİZİ" ;;
            CPU_INFO) echo "3. İŞLEMCİ (CPU) VE ÇEKİRDEK FREKANS ANALİZİ" ;;
            STORAGE_INFO) echo "4. DEPOLAMA (ROM / DATA) ANALİZİ" ;;
            VERDICT_HEADER) echo "=== TEFTİŞ VE DOĞRULAMA KARARI ===" ;;
            VERDICT_CLEAN) echo "DOĞRULANDI: Donanım parametreleri tutarlı görünüyor." ;;
            VERDICT_SUSPICIOUS) echo "ŞÜPHELİ: Bazı donanım değerlerinde uyuşmazlıklar tespit edildi." ;;
            VERDICT_FAKE) echo "SAHTE DONANIM TESPİT EDİLDİ: Cihaz parametreleri manipüle edilmiş!" ;;
            PHYSICAL_RAM) echo "Fiziksel RAM (MemTotal)" ;;
            SWAP_TOTAL) echo "Takas / ZRAM Alanı" ;;
            SWAP_INFLATION) echo "SWAP Şişirmesi Tespiti" ;;
            SWAP_INFLATION_DETECTED) echo "Fiziksel RAM takas alanı (SWAP/ZRAM) eklenerek şişirilmiş gösteriliyor!" ;;
            SWAP_NORMAL) echo "Takas alanı normal aralıkta." ;;
            CORES_REPORTED) echo "Bildirilen Çekirdek Sayısı" ;;
            CORES_ACTIVE) echo "Frekans Üreten Aktif Çekirdek" ;;
            CORE_FAKE_WARNING) echo "Sahte çekirdek uyarısı: Bildirilen bazı çekirdekler frekans üretmiyor!" ;;
            STORAGE_TOTAL) echo "Gerçek Kullanıcı Bölümü (/data)" ;;
            STORAGE_FREE) echo "Kullanılabilir Boş Alan" ;;
            PLATFORM) echo "Platform / Yonga Seti" ;;
            BOARD) echo "Anakart (Board)" ;;
            HARDWARE) echo "Donanım (Hardware)" ;;
            ANDROID_REPORTED) echo "Görünen Android Sürümü" ;;
            ANDROID_API) echo "Gerçek Android API Seviyesi" ;;
            API_MISMATCH) echo "Sürüm Sahteciliği: Görünen Android sürümü gerçek API seviyesiyle uyuşmuyor!" ;;
            KNOWN_FAKE_CHIPSET) echo "BİLİNEN SAHTE YONGA SETİ TESPİT EDİLDİ" ;;
            RECOMMENDATION) echo "Tavsiye" ;;
            *) echo "$key" ;;
        esac
    else
        case "$key" in
            TITLE) echo "=== FAKE HUNTER — HARDWARE AUDIT REPORT ===" ;;
            VERSION) echo "Toolkit Version" ;;
            SYSTEM_INFO) echo "1. SYSTEM & HARDWARE IDENTITY" ;;
            RAM_INFO) echo "2. MEMORY (RAM) & SWAP ANALYSIS" ;;
            CPU_INFO) echo "3. PROCESSOR (CPU) & CORE FREQUENCY ANALYSIS" ;;
            STORAGE_INFO) echo "4. STORAGE (ROM / DATA) ANALYSIS" ;;
            VERDICT_HEADER) echo "=== AUDIT VERDICT & SUMMARY ===" ;;
            VERDICT_CLEAN) echo "VERIFIED: Hardware metrics appear genuine and consistent." ;;
            VERDICT_SUSPICIOUS) echo "SUSPICIOUS: Inconsistencies found in hardware properties." ;;
            VERDICT_FAKE) echo "FAKE HARDWARE DETECTED: Hardware specs are spoofed/manipulated!" ;;
            PHYSICAL_RAM) echo "Physical RAM (MemTotal)" ;;
            SWAP_TOTAL) echo "Swap / ZRAM Allocation" ;;
            SWAP_INFLATION) echo "Swap Inflation Detection" ;;
            SWAP_INFLATION_DETECTED) echo "Physical RAM is artificially inflated using Swap/ZRAM!" ;;
            SWAP_NORMAL) echo "Swap space allocation is within standard operating range." ;;
            CORES_REPORTED) echo "Reported Core Count" ;;
            CORES_ACTIVE) echo "Active Cores with Frequency" ;;
            CORE_FAKE_WARNING) echo "Fake core warning: Reported cores fail to generate active frequency!" ;;
            STORAGE_TOTAL) echo "Actual User Partition (/data)" ;;
            STORAGE_FREE) echo "Available Free Space" ;;
            PLATFORM) echo "Platform / Chipset" ;;
            BOARD) echo "Board" ;;
            HARDWARE) echo "Hardware" ;;
            ANDROID_REPORTED) echo "Reported Android Version" ;;
            ANDROID_API) echo "Actual Android API Level" ;;
            API_MISMATCH) echo "Version Spoofing: Displayed Android version contradicts actual API level!" ;;
            KNOWN_FAKE_CHIPSET) echo "KNOWN FAKE CHIPSET SIGNATURE IDENTIFIED" ;;
            RECOMMENDATION) echo "Recommendation" ;;
            *) echo "$key" ;;
        esac
    fi
}

# Collect System Properties
MODEL="Unknown"
MANUFACTURER="Unknown"
PLATFORM="Unknown"
BOARD="Unknown"
HARDWARE="Unknown"
BUILD_ID="Unknown"
OS_RELEASE="Unknown"
OS_SDK="Unknown"

if command -v getprop >/dev/null 2>&1; then
    MODEL="$(getprop ro.product.model 2>/dev/null || echo 'Unknown')"
    MANUFACTURER="$(getprop ro.product.manufacturer 2>/dev/null || echo 'Unknown')"
    PLATFORM="$(getprop ro.board.platform 2>/dev/null || echo 'Unknown')"
    BOARD="$(getprop ro.product.board 2>/dev/null || echo 'Unknown')"
    HARDWARE="$(getprop ro.hardware 2>/dev/null || echo 'Unknown')"
    BUILD_ID="$(getprop ro.build.display.id 2>/dev/null || echo 'Unknown')"
    OS_RELEASE="$(getprop ro.build.version.release 2>/dev/null || echo 'Unknown')"
    OS_SDK="$(getprop ro.build.version.sdk 2>/dev/null || echo 'Unknown')"
else
    # Linux host fallback
    if [ -f /etc/os-release ]; then
        MODEL="Linux Host ($(uname -m))"
        MANUFACTURER="Local"
        PLATFORM="$(uname -m)"
        BOARD="$(uname -s)"
        HARDWARE="$(uname -r)"
        OS_RELEASE="$(uname -r)"
        OS_SDK="N/A"
    fi
fi

# Findings and Scores
TOTAL_ISSUES=0
IS_FAKE=0
IS_SUSPICIOUS=0
FINDINGS=""

add_finding() {
    severity="$1" # FAKE or WARN or INFO
    msg="$2"
    if [ "$severity" = "FAKE" ]; then
        IS_FAKE=1
        TOTAL_ISSUES=$((TOTAL_ISSUES + 2))
        FINDINGS="${FINDINGS}  ${COLOR_RED}[X] $msg${COLOR_RESET}\n"
    elif [ "$severity" = "WARN" ]; then
        IS_SUSPICIOUS=1
        TOTAL_ISSUES=$((TOTAL_ISSUES + 1))
        FINDINGS="${FINDINGS}  ${COLOR_YELLOW}[!] $msg${COLOR_RESET}\n"
    else
        FINDINGS="${FINDINGS}  ${COLOR_GREEN}[✓] $msg${COLOR_RESET}\n"
    fi
}

# 1. RAM & SWAP Analysis
MEM_TOTAL_KB=0
SWAP_TOTAL_KB=0
if [ -f /proc/meminfo ]; then
    MEM_TOTAL_KB=$(grep -m 1 "^MemTotal:" /proc/meminfo | awk '{print $2}')
    SWAP_TOTAL_KB=$(grep -m 1 "^SwapTotal:" /proc/meminfo | awk '{print $2}')
    [ -z "$MEM_TOTAL_KB" ] && MEM_TOTAL_KB=0
    [ -z "$SWAP_TOTAL_KB" ] && SWAP_TOTAL_KB=0
fi

MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
SWAP_TOTAL_MB=$((SWAP_TOTAL_KB / 1024))
TOTAL_VIRTUAL_MB=$((MEM_TOTAL_MB + SWAP_TOTAL_MB))

SWAP_INFLATED=0
# SWAP Inflation check: If Swap >= 80% of Physical RAM, or Swap >= 3500MB when RAM <= 4000MB
if [ "$SWAP_TOTAL_MB" -ge 3000 ] && [ "$MEM_TOTAL_MB" -le 4200 ]; then
    SWAP_INFLATED=1
    add_finding "FAKE" "$(t SWAP_INFLATION_DETECTED) (${MEM_TOTAL_MB}MB RAM + ${SWAP_TOTAL_MB}MB Swap)"
elif [ "$SWAP_TOTAL_MB" -gt 0 ] && [ "$MEM_TOTAL_MB" -gt 0 ]; then
    ratio=$((SWAP_TOTAL_MB * 100 / MEM_TOTAL_MB))
    if [ "$ratio" -gt 85 ]; then
        SWAP_INFLATED=1
        add_finding "WARN" "$(t SWAP_INFLATION_DETECTED) (Swap/RAM ratio: ${ratio}%)"
    fi
fi

# 2. CPU & Core Frequency Analysis
REPORTED_CORES=0
if [ -f /proc/cpuinfo ]; then
    REPORTED_CORES=$(grep -c "^processor" /proc/cpuinfo || true)
fi
[ "$REPORTED_CORES" -eq 0 ] && REPORTED_CORES=1

# Wake test (if requested)
if [ "$WAKE_TEST" -eq 1 ]; then
    # Light background calculation to wake cores
    for i in $(seq 1 "$REPORTED_CORES"); do
        ( awk 'BEGIN{for(i=0;i<200000;i++){x+=i}}' >/dev/null 2>&1 ) &
    done
    sleep 1
fi

ACTIVE_CORES=0
CORE_DETAILS=""
for idx in $(seq 0 $((REPORTED_CORES - 1))); do
    CORE_DIR="/sys/devices/system/cpu/cpu${idx}"
    CUR_FREQ=0
    MAX_FREQ=0
    IS_ONLINE=1

    if [ -f "${CORE_DIR}/online" ]; then
        IS_ONLINE=$(cat "${CORE_DIR}/online" 2>/dev/null || echo 1)
    fi

    if [ -f "${CORE_DIR}/cpufreq/scaling_cur_freq" ]; then
        CUR_FREQ=$(cat "${CORE_DIR}/cpufreq/scaling_cur_freq" 2>/dev/null || echo 0)
    elif [ -f "${CORE_DIR}/cpufreq/cpuinfo_cur_freq" ]; then
        CUR_FREQ=$(cat "${CORE_DIR}/cpufreq/cpuinfo_cur_freq" 2>/dev/null || echo 0)
    fi

    if [ -f "${CORE_DIR}/cpufreq/cpuinfo_max_freq" ]; then
        MAX_FREQ=$(cat "${CORE_DIR}/cpufreq/cpuinfo_max_freq" 2>/dev/null || echo 0)
    fi

    # Fallback to /proc/cpuinfo if cpufreq nodes do not exist (e.g. x86 architecture / virtualization)
    if [ "$CUR_FREQ" -eq 0 ] && [ -f /proc/cpuinfo ]; then
        CPUINFO_MHZ=$(awk -v core="$idx" '
            $1 == "processor" && $3 == core { in_core=1 }
            in_core && ($1 == "cpu" && $2 == "MHz") { print int($4); exit }
            in_core && $1 == "processor" && $3 != core { exit }
        ' /proc/cpuinfo)
        if [ -n "$CPUINFO_MHZ" ] && [ "$CPUINFO_MHZ" -gt 0 ] 2>/dev/null; then
            CUR_FREQ=$((CPUINFO_MHZ * 1000))
            [ "$MAX_FREQ" -eq 0 ] && MAX_FREQ="$CUR_FREQ"
        fi
    fi

    if [ "$IS_ONLINE" -eq 1 ] && [ "$CUR_FREQ" -gt 0 ]; then
        ACTIVE_CORES=$((ACTIVE_CORES + 1))
        CORE_DETAILS="${CORE_DETAILS}    Core ${idx}: ${COLOR_GREEN}ONLINE${COLOR_RESET} ($((CUR_FREQ / 1000)) MHz / max: $((MAX_FREQ / 1000)) MHz)\n"
    elif [ "$IS_ONLINE" -eq 1 ] && [ "$MAX_FREQ" -gt 0 ]; then
        ACTIVE_CORES=$((ACTIVE_CORES + 1))
        CORE_DETAILS="${CORE_DETAILS}    Core ${idx}: ${COLOR_GREEN}ONLINE${COLOR_RESET} (idle / max: $((MAX_FREQ / 1000)) MHz)\n"
    else
        CORE_DETAILS="${CORE_DETAILS}    Core ${idx}: ${COLOR_RED}OFFLINE/INACTIVE${COLOR_RESET} (Freq: 0 MHz)\n"
    fi
done

if [ "$REPORTED_CORES" -gt "$ACTIVE_CORES" ] && [ "$ACTIVE_CORES" -gt 0 ]; then
    add_finding "FAKE" "$(t CORE_FAKE_WARNING) ($REPORTED_CORES reported, only $ACTIVE_CORES active)"
fi

# 3. Storage Analysis (/data)
DATA_TOTAL_MB=0
DATA_FREE_MB=0
if [ -d /data ]; then
    # Use df in 1K-blocks or standard output
    DF_LINE=$(df /data 2>/dev/null | tail -n 1 || true)
    if [ -n "$DF_LINE" ]; then
        DATA_TOTAL_KB=$(echo "$DF_LINE" | awk '{print $2}')
        DATA_FREE_KB=$(echo "$DF_LINE" | awk '{print $4}')
        case "$DATA_TOTAL_KB" in
            ''|*[!0-9]*) DATA_TOTAL_KB=0 ;;
        esac
        case "$DATA_FREE_KB" in
            ''|*[!0-9]*) DATA_FREE_KB=0 ;;
        esac
        DATA_TOTAL_MB=$((DATA_TOTAL_KB / 1024))
        DATA_FREE_MB=$((DATA_FREE_KB / 1024))
    fi
fi

# 4. Known Fraud Platform Signatures
KNOWN_FRAUD_MSG=""
CHECK_STR="${PLATFORM}_${BOARD}_${HARDWARE}_${MODEL}"
case "$CHECK_STR" in
    *[aA][cC]8227*|*[mM][tT]8227*|*[aA][tT][cC]8227*)
        KNOWN_FRAUD_MSG="AC8227L / MT8227L: AutoChips/MediaTek 32-bit Quad-Core Cortex-A7 chipset. Often falsely advertised as Octa-Core, Android 12/13/14, and 8GB RAM."
        add_finding "FAKE" "AC8227L: $KNOWN_FRAUD_MSG"
        ;;
    *[sS][uU][nN]8[iI]*|*[tT]3[lL]*|*[aA][lL][lL][wW][iI][nN][nN][eE][rR]*[tT]3*)
        KNOWN_FRAUD_MSG="Allwinner T3 / T3L: Quad-Core Cortex-A7 platform. Frequently faked as 8-core multimedia head unit."
        add_finding "FAKE" "Allwinner T3: $KNOWN_FRAUD_MSG"
        ;;
    *[mM][tT]6580*)
        KNOWN_FRAUD_MSG="MediaTek MT6580: Ancient 32-bit Quad-Core Cortex-A7 (2015 era). Falsely reported as modern 8-core CPU."
        add_finding "FAKE" "MT6580: $KNOWN_FRAUD_MSG"
        ;;
    *[rR][kK]3128*|*[rR][kK]3228*)
        KNOWN_FRAUD_MSG="Rockchip RK3128/RK3228: Quad-Core entry chipset. Frequently faked as 8-core 4K TV Box."
        add_finding "WARN" "Rockchip: $KNOWN_FRAUD_MSG"
        ;;
esac

# 5. Android Version Spoofing Check
if [ "$OS_SDK" != "Unknown" ] && [ "$OS_SDK" != "N/A" ]; then
    case "$OS_SDK" in
        ''|*[!0-9]*) OS_SDK_NUM=0 ;;
        *) OS_SDK_NUM="$OS_SDK" ;;
    esac

    # SDK <= 27 is Android 8.1 or older. SDK <= 29 is Android 10 or older.
    if [ "$OS_SDK_NUM" -gt 0 ] && [ "$OS_SDK_NUM" -le 27 ]; then
        case "$OS_RELEASE" in
            1[0-9]*|[9]*)
                add_finding "FAKE" "$(t API_MISMATCH) (Reported: Android $OS_RELEASE, Actual API: $OS_SDK_NUM - Android 8.1 or older)"
                ;;
        esac
    elif [ "$OS_SDK_NUM" -gt 0 ] && [ "$OS_SDK_NUM" -le 29 ]; then
        case "$OS_RELEASE" in
            1[2-9]*)
                add_finding "FAKE" "$(t API_MISMATCH) (Reported: Android $OS_RELEASE, Actual API: $OS_SDK_NUM - Android 10 or older)"
                ;;
        esac
    fi
fi

# Helper for integer-based GB calculation (immune to locale decimal point issues)
calc_gb() {
    mb_val="$1"
    gb_int=$((mb_val / 1024))
    gb_dec=$(( (mb_val % 1024) * 10 / 1024 ))
    echo "${gb_int}.${gb_dec}"
}

# Print Report
build_report() {
    printf "\n%b%s%b\n" "$COLOR_CYAN" "$(t TITLE)" "$COLOR_RESET"
    printf "%s: %s\n\n" "$(t VERSION)" "$SCRIPT_VERSION"

    printf "%b%s%b\n" "$COLOR_BOLD" "$(t SYSTEM_INFO)" "$COLOR_RESET"
    printf "  • %s: %s (%s)\n" "Model" "$MODEL" "$MANUFACTURER"
    printf "  • %s: %s\n" "$(t PLATFORM)" "$PLATFORM"
    printf "  • %s: %s\n" "$(t BOARD)" "$BOARD"
    printf "  • %s: %s\n" "$(t HARDWARE)" "$HARDWARE"
    printf "  • %s: %s\n" "$(t ANDROID_REPORTED)" "$OS_RELEASE"
    printf "  • %s: %s\n\n" "$(t ANDROID_API)" "$OS_SDK"

    printf "%b%s%b\n" "$COLOR_BOLD" "$(t RAM_INFO)" "$COLOR_RESET"
    printf "  • %s: %d MB (~%s GB)\n" "$(t PHYSICAL_RAM)" "$MEM_TOTAL_MB" "$(calc_gb "$MEM_TOTAL_MB")"
    printf "  • %s: %d MB (~%s GB)\n" "$(t SWAP_TOTAL)" "$SWAP_TOTAL_MB" "$(calc_gb "$SWAP_TOTAL_MB")"
    if [ "$SWAP_INFLATED" -eq 1 ]; then
        printf "  • %s: %b%s%b\n\n" "$(t SWAP_INFLATION)" "$COLOR_RED" "$(t SWAP_INFLATION_DETECTED)" "$COLOR_RESET"
    else
        printf "  • %s: %b%s%b\n\n" "$(t SWAP_INFLATION)" "$COLOR_GREEN" "$(t SWAP_NORMAL)" "$COLOR_RESET"
    fi

    printf "%b%s%b\n" "$COLOR_BOLD" "$(t CPU_INFO)" "$COLOR_RESET"
    printf "  • %s: %d\n" "$(t CORES_REPORTED)" "$REPORTED_CORES"
    printf "  • %s: %d\n" "$(t CORES_ACTIVE)" "$ACTIVE_CORES"
    printf "%b\n" "$CORE_DETAILS"

    if [ "$DATA_TOTAL_MB" -gt 0 ]; then
        printf "%b%s%b\n" "$COLOR_BOLD" "$(t STORAGE_INFO)" "$COLOR_RESET"
        printf "  • %s: %d MB (~%s GB)\n" "$(t STORAGE_TOTAL)" "$DATA_TOTAL_MB" "$(calc_gb "$DATA_TOTAL_MB")"
        printf "  • %s: %d MB (~%s GB)\n\n" "$(t STORAGE_FREE)" "$DATA_FREE_MB" "$(calc_gb "$DATA_FREE_MB")"
    fi

    printf "%b%s%b\n" "$COLOR_CYAN" "$(t VERDICT_HEADER)" "$COLOR_RESET"
    if [ "$IS_FAKE" -eq 1 ]; then
        printf "%b%s%b\n\n" "${COLOR_BOLD}${COLOR_RED}" "$(t VERDICT_FAKE)" "$COLOR_RESET"
    elif [ "$IS_SUSPICIOUS" -eq 1 ]; then
        printf "%b%s%b\n\n" "${COLOR_BOLD}${COLOR_YELLOW}" "$(t VERDICT_SUSPICIOUS)" "$COLOR_RESET"
    else
        printf "%b%s%b\n\n" "${COLOR_BOLD}${COLOR_GREEN}" "$(t VERDICT_CLEAN)" "$COLOR_RESET"
    fi

    if [ -n "$FINDINGS" ]; then
        printf "Bulgular / Findings:\n"
        printf "%b\n" "$FINDINGS"
    fi
}

# Display report
build_report

# Save plain text report if requested
if [ -n "$OUTPUT_FILE" ]; then
    # Strip ANSI colors when saving to file
    build_report | sed 's/\x1b\[[0-9;]*m//g' > "$OUTPUT_FILE"
    echo "Report saved to $OUTPUT_FILE"
fi

# Export JSON if requested
if [ -n "$JSON_FILE" ]; then
    VERDICT_STR="CLEAN"
    [ "$IS_SUSPICIOUS" -eq 1 ] && VERDICT_STR="SUSPICIOUS"
    [ "$IS_FAKE" -eq 1 ] && VERDICT_STR="FAKE"

    cat <<EOF > "$JSON_FILE"
{
  "version": "$SCRIPT_VERSION",
  "system": {
    "model": "$MODEL",
    "manufacturer": "$MANUFACTURER",
    "platform": "$PLATFORM",
    "board": "$BOARD",
    "hardware": "$HARDWARE",
    "build_id": "$BUILD_ID",
    "os_release": "$OS_RELEASE",
    "os_sdk": "$OS_SDK"
  },
  "ram": {
    "physical_mb": $MEM_TOTAL_MB,
    "swap_mb": $SWAP_TOTAL_MB,
    "swap_inflated": $( [ "$SWAP_INFLATED" -eq 1 ] && echo "true" || echo "false" )
  },
  "cpu": {
    "reported_cores": $REPORTED_CORES,
    "active_cores": $ACTIVE_CORES
  },
  "storage": {
    "data_total_mb": $DATA_TOTAL_MB,
    "data_free_mb": $DATA_FREE_MB
  },
  "verdict": "$VERDICT_STR",
  "issues_count": $TOTAL_ISSUES
}
EOF
    echo "JSON report saved to $JSON_FILE"
fi
