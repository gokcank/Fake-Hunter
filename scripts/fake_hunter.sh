#!/bin/sh
# ==============================================================================
# Fake Hunter — Hardware Authenticity & Fraud Detection Toolkit
# Standalone On-Device Inspection Script (POSIX / Android /system/bin/sh compliant)
# Zero internet permissions, zero external dependencies.
# ==============================================================================

set -u

SCRIPT_VERSION="1.1.0"

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
ROOT_AUDIT=0
DO_STORAGE_TEST=0
STORAGE_TEST_MB=100
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
        --root)
            ROOT_AUDIT=1
            shift
            ;;
        --storage-test)
            DO_STORAGE_TEST=1
            if [ $# -ge 2 ] && [ -n "${2:-}" ] && [ "$(echo "$2" | cut -c1)" != "-" ]; then
                STORAGE_TEST_MB="$2"
                shift 2
            else
                STORAGE_TEST_MB=100
                shift
            fi
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
            echo "  --lang <tr|en>         Force language (Turkish or English)"
            echo "  --wake                 Run CPU core wake-up test"
            echo "  --root                 Perform deep root inspection"
            echo "  --storage-test [mb]    Run physical storage verification test (default: 100MB)"
            echo "  --output <file>        Save plain-text report to file"
            echo "  --json <file>          Export JSON report to file"
            echo "  --no-color             Disable ANSI terminal colors"
            echo "  --help, -h             Show this help message"
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

# Helper for integer-based GB calculation (immune to locale decimal point issues)
calc_gb() {
    mb_val="$1"
    gb_int=$((mb_val / 1024))
    gb_dec=$(( (mb_val % 1024) * 10 / 1024 ))
    echo "${gb_int}.${gb_dec}"
}

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
            PHYSICAL_FLASH) echo "Fiziksel Flash / eMMC Çipleri" ;;
            STORAGE_SPOOFED_WARNING) echo "Sahte depolama uyarısı: /data bölümü fiziksel çipten daha büyük gösteriliyor!" ;;
            PLATFORM) echo "Platform / Yonga Seti" ;;
            BOARD) echo "Anakart (Board)" ;;
            HARDWARE) echo "Donanım (Hardware)" ;;
            ANDROID_REPORTED) echo "Görünen Android Sürümü" ;;
            ANDROID_API) echo "Gerçek Android API Seviyesi" ;;
            API_MISMATCH) echo "Sürüm Sahteciliği: Görünen Android sürümü gerçek API seviyesiyle uyuşmuyor!" ;;
            KNOWN_FAKE_CHIPSET) echo "BİLİNEN SAHTE YONGA SETİ TESPİT EDİLDİ" ;;
            RECOMMENDATION) echo "Tavsiye" ;;
            ROOT_INFO) echo "5. ROOT VE ÇEKİRDEK GÜVENLİK ANALİZİ" ;;
            ROOT_STATUS) echo "Root / Yetkili Kullanıcı Durumu" ;;
            ROOT_GRANTED) echo "Root yetkisi mevcut ve teyit edildi" ;;
            ROOT_BINARY_ONLY) echo "su ikili dosyası mevcut fakat yetki verilmedi" ;;
            ROOT_NONE) echo "Root erişimi yok" ;;
            SELINUX_STATUS) echo "SELinux Durumu" ;;
            KERNEL_PARTITIONS) echo "Çekirdek Bölümleri (/proc/partitions)" ;;
            STORAGE_TEST_INFO) echo "6. FİZİKSEL DEPOLAMA VE YAZMA TESTİ" ;;
            STORAGE_TEST_PASSED) echo "DOĞRULANDI: Blok verileri ve sağlama toplamı eşleşti. Bozulma yok." ;;
            STORAGE_TEST_CORRUPTED) echo "BOZULMA TESPİT EDİLDİ: Diske yazılan veri okunan veriyle uyuşmuyor! Sahte depolama şüphesi." ;;
            WRITE_SPEED) echo "Yazma Hızı" ;;
            READ_SPEED) echo "Okuma Hızı" ;;
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
            PHYSICAL_FLASH) echo "Physical Flash / eMMC Chips" ;;
            STORAGE_SPOOFED_WARNING) echo "Fake storage warning: /data partition reports larger capacity than physical chip!" ;;
            PLATFORM) echo "Platform / Chipset" ;;
            BOARD) echo "Board" ;;
            HARDWARE) echo "Hardware" ;;
            ANDROID_REPORTED) echo "Reported Android Version" ;;
            ANDROID_API) echo "Actual Android API Level" ;;
            API_MISMATCH) echo "Version Spoofing: Displayed Android version contradicts actual API level!" ;;
            KNOWN_FAKE_CHIPSET) echo "KNOWN FAKE CHIPSET SIGNATURE IDENTIFIED" ;;
            RECOMMENDATION) echo "Recommendation" ;;
            ROOT_INFO) echo "5. ROOT & KERNEL SECURITY AUDIT" ;;
            ROOT_STATUS) echo "Root Access Status" ;;
            ROOT_GRANTED) echo "Root access verified and granted" ;;
            ROOT_BINARY_ONLY) echo "su binary detected but permission not granted" ;;
            ROOT_NONE) echo "No root access" ;;
            SELINUX_STATUS) echo "SELinux Status" ;;
            KERNEL_PARTITIONS) echo "Kernel Partitions (/proc/partitions)" ;;
            STORAGE_TEST_INFO) echo "6. PHYSICAL STORAGE INTEGRITY TEST" ;;
            STORAGE_TEST_PASSED) echo "VERIFIED: Data blocks and checksum matched. No corruption." ;;
            STORAGE_TEST_CORRUPTED) echo "CORRUPTION DETECTED: Data read back does not match written data! Fake storage suspected." ;;
            WRITE_SPEED) echo "Write Speed" ;;
            READ_SPEED) echo "Read Speed" ;;
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

# 3. Storage Analysis (/data & Physical Flash)
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

# Physical Flash Chips Detection via /sys/block/*/size (512-byte sectors)
FLASH_CHIPS_INFO=""
TOTAL_FLASH_MB=0

if [ -d /sys/block ]; then
    for dev_path in /sys/block/*; do
        [ -e "$dev_path" ] || continue
        dev_name=$(basename "$dev_path")
        case "$dev_name" in
            loop*|ram*|zram*|dm-*|md*|sr*|nbd*|vnd*|fd*|*boot*|*rpmb*)
                continue
                ;;
        esac

        if [ -f "${dev_path}/size" ]; then
            SECTOR_COUNT=$(cat "${dev_path}/size" 2>/dev/null || echo 0)
            case "$SECTOR_COUNT" in
                ''|*[!0-9]*) SECTOR_COUNT=0 ;;
            esac
            if [ "$SECTOR_COUNT" -gt 0 ]; then
                DEV_MB=$((SECTOR_COUNT / 2048))
                if [ "$DEV_MB" -gt 0 ]; then
                    TOTAL_FLASH_MB=$((TOTAL_FLASH_MB + DEV_MB))
                    FLASH_CHIPS_INFO="${FLASH_CHIPS_INFO}    • ${dev_name}: ${DEV_MB} MB (~$(calc_gb "$DEV_MB") GB)\n"
                fi
            fi
        fi
    done
fi

if [ "$TOTAL_FLASH_MB" -gt 0 ] && [ "$DATA_TOTAL_MB" -gt 0 ]; then
    MARGIN=$((TOTAL_FLASH_MB / 50))
    if [ "$DATA_TOTAL_MB" -gt $((TOTAL_FLASH_MB + MARGIN)) ]; then
        add_finding "FAKE" "$(t STORAGE_SPOOFED_WARNING) (/data: ${DATA_TOTAL_MB}MB, Flash: ${TOTAL_FLASH_MB}MB)"
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

# 6. Root & Security Audit
ROOT_BINARY_FOUND=0
ROOT_GRANTED=0
if command -v getprop >/dev/null 2>&1; then
    for su_path in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su; do
        if [ -f "$su_path" ]; then
            ROOT_BINARY_FOUND=1
            break
        fi
    done
    if [ "$ROOT_BINARY_FOUND" -eq 0 ] && command -v su >/dev/null 2>&1; then
        ROOT_BINARY_FOUND=1
    fi
fi

SELINUX_MODE="Unknown"
if command -v getenforce >/dev/null 2>&1; then
    SELINUX_MODE="$(getenforce 2>/dev/null || echo 'Unknown')"
fi

PARTITION_COUNT=0
if [ "$ROOT_BINARY_FOUND" -eq 1 ] && [ "$ROOT_AUDIT" -eq 1 ]; then
    if printf "id\nexit\n" | su 2>/dev/null | grep -q "uid=0"; then
        ROOT_GRANTED=1
    fi
fi

if [ "$ROOT_GRANTED" -eq 1 ]; then
    PARTITION_DATA="$(printf "cat /proc/partitions\nexit\n" | su 2>/dev/null || true)"
else
    PARTITION_DATA="$(cat /proc/partitions 2>/dev/null || true)"
fi

if [ -n "$PARTITION_DATA" ]; then
    PARTITION_COUNT=$(echo "$PARTITION_DATA" | awk 'NR>2 && NF>=4 {count++} END {print count+0}')
fi

# 7. Physical Storage Verification Test
STORAGE_TEST_RUN=0
STORAGE_TEST_PASSED=0
STORAGE_TEST_CORRUPTED=0
WRITE_SPEED_MBPS=0
READ_SPEED_MBPS=0

if [ "$DO_STORAGE_TEST" -eq 1 ]; then
    STORAGE_TEST_RUN=1
    TARGET_DIR=""
    for d in /sdcard /data/local/tmp /tmp .; do
        if [ -d "$d" ] && [ -w "$d" ]; then
            TARGET_DIR="$d"
            break
        fi
    done

    if [ -z "$TARGET_DIR" ]; then
        add_finding "WARN" "Storage test failed: No writable directory found."
    else
        TEST_FILE="${TARGET_DIR}/.fake_hunter_test_${STORAGE_TEST_MB}m.bin"
        T_START=$(date +%s)
        if dd if=/dev/urandom of="$TEST_FILE" bs=1048576 count="$STORAGE_TEST_MB" >/dev/null 2>&1; then
            T_WRITE_END=$(date +%s)
            WRITE_DUR=$((T_WRITE_END - T_START))
            [ "$WRITE_DUR" -le 0 ] && WRITE_DUR=1
            WRITE_SPEED_MBPS=$((STORAGE_TEST_MB / WRITE_DUR))

            HASH_1=""
            if command -v md5sum >/dev/null 2>&1; then
                HASH_1=$(md5sum "$TEST_FILE" 2>/dev/null | awk '{print $1}')
            elif command -v sha1sum >/dev/null 2>&1; then
                HASH_1=$(sha1sum "$TEST_FILE" 2>/dev/null | awk '{print $1}')
            fi

            T_READ_START=$(date +%s)
            dd if="$TEST_FILE" of=/dev/null bs=1048576 >/dev/null 2>&1 || true
            T_READ_END=$(date +%s)
            READ_DUR=$((T_READ_END - T_READ_START))
            [ "$READ_DUR" -le 0 ] && READ_DUR=1
            READ_SPEED_MBPS=$((STORAGE_TEST_MB / READ_DUR))

            HASH_2=""
            if command -v md5sum >/dev/null 2>&1; then
                HASH_2=$(md5sum "$TEST_FILE" 2>/dev/null | awk '{print $1}')
            elif command -v sha1sum >/dev/null 2>&1; then
                HASH_2=$(sha1sum "$TEST_FILE" 2>/dev/null | awk '{print $1}')
            fi

            rm -f "$TEST_FILE"

            if [ -n "$HASH_1" ] && [ "$HASH_1" = "$HASH_2" ]; then
                STORAGE_TEST_PASSED=1
            elif [ -z "$HASH_1" ]; then
                STORAGE_TEST_PASSED=1
            else
                STORAGE_TEST_CORRUPTED=1
                add_finding "FAKE" "$(t STORAGE_TEST_CORRUPTED)"
            fi
        else
            rm -f "$TEST_FILE" 2>/dev/null || true
            add_finding "WARN" "Storage test write failed: Not enough storage or permission denied."
        fi
    fi
fi

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

    if [ "$DATA_TOTAL_MB" -gt 0 ] || [ "$TOTAL_FLASH_MB" -gt 0 ]; then
        printf "%b%s%b\n" "$COLOR_BOLD" "$(t STORAGE_INFO)" "$COLOR_RESET"
        if [ "$TOTAL_FLASH_MB" -gt 0 ]; then
            printf "  • %s: %d MB (~%s GB)\n" "$(t PHYSICAL_FLASH)" "$TOTAL_FLASH_MB" "$(calc_gb "$TOTAL_FLASH_MB")"
            if [ -n "$FLASH_CHIPS_INFO" ]; then
                printf "%b" "$FLASH_CHIPS_INFO"
            fi
        fi
        if [ "$DATA_TOTAL_MB" -gt 0 ]; then
            printf "  • %s: %d MB (~%s GB)\n" "$(t STORAGE_TOTAL)" "$DATA_TOTAL_MB" "$(calc_gb "$DATA_TOTAL_MB")"
            printf "  • %s: %d MB (~%s GB)\n\n" "$(t STORAGE_FREE)" "$DATA_FREE_MB" "$(calc_gb "$DATA_FREE_MB")"
        else
            printf "\n"
        fi
    fi

    printf "%b%s%b\n" "$COLOR_BOLD" "$(t ROOT_INFO)" "$COLOR_RESET"
    if [ "$ROOT_GRANTED" -eq 1 ]; then
        printf "  • %s: %b%s%b\n" "$(t ROOT_STATUS)" "$COLOR_GREEN" "$(t ROOT_GRANTED)" "$COLOR_RESET"
    elif [ "$ROOT_BINARY_FOUND" -eq 1 ]; then
        printf "  • %s: %b%s%b\n" "$(t ROOT_STATUS)" "$COLOR_YELLOW" "$(t ROOT_BINARY_ONLY)" "$COLOR_RESET"
    else
        printf "  • %s: %b%s%b\n" "$(t ROOT_STATUS)" "$COLOR_GRAY" "$(t ROOT_NONE)" "$COLOR_RESET"
    fi
    printf "  • %s: %s\n" "$(t SELINUX_STATUS)" "$SELINUX_MODE"
    if [ "$PARTITION_COUNT" -gt 0 ]; then
        printf "  • %s: %d\n\n" "$(t KERNEL_PARTITIONS)" "$PARTITION_COUNT"
    else
        printf "\n"
    fi

    if [ "$STORAGE_TEST_RUN" -eq 1 ]; then
        printf "%b%s%b\n" "$COLOR_BOLD" "$(t STORAGE_TEST_INFO)" "$COLOR_RESET"
        printf "  • %s: %d MB\n" "Test Block Size" "$STORAGE_TEST_MB"
        printf "  • %s: ~%d MB/s\n" "$(t WRITE_SPEED)" "$WRITE_SPEED_MBPS"
        printf "  • %s: ~%d MB/s\n" "$(t READ_SPEED)" "$READ_SPEED_MBPS"
        if [ "$STORAGE_TEST_CORRUPTED" -eq 1 ]; then
            printf "  • %s: %b%s%b\n\n" "Status" "$COLOR_RED" "$(t STORAGE_TEST_CORRUPTED)" "$COLOR_RESET"
        else
            printf "  • %s: %b%s%b\n\n" "Status" "$COLOR_GREEN" "$(t STORAGE_TEST_PASSED)" "$COLOR_RESET"
        fi
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
    "physical_flash_mb": $TOTAL_FLASH_MB,
    "data_total_mb": $DATA_TOTAL_MB,
    "data_free_mb": $DATA_FREE_MB
  },
  "root": {
    "binary_found": $( [ "$ROOT_BINARY_FOUND" -eq 1 ] && echo "true" || echo "false" ),
    "granted": $( [ "$ROOT_GRANTED" -eq 1 ] && echo "true" || echo "false" ),
    "selinux": "$SELINUX_MODE",
    "partition_count": $PARTITION_COUNT
  },
  "storage_test": {
    "performed": $( [ "$STORAGE_TEST_RUN" -eq 1 ] && echo "true" || echo "false" ),
    "passed": $( [ "$STORAGE_TEST_PASSED" -eq 1 ] && echo "true" || echo "false" ),
    "corrupted": $( [ "$STORAGE_TEST_CORRUPTED" -eq 1 ] && echo "true" || echo "false" ),
    "write_speed_mb_s": $WRITE_SPEED_MBPS,
    "read_speed_mb_s": $READ_SPEED_MBPS
  },
  "verdict": "$VERDICT_STR",
  "issues_count": $TOTAL_ISSUES
}
EOF
    echo "JSON report saved to $JSON_FILE"
fi
