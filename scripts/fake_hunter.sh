#!/bin/sh
# ==============================================================================
# Fake Hunter — Hardware Authenticity & Fraud Detection Toolkit
# Standalone On-Device Inspection Script (POSIX / Android /system/bin/sh compliant)
# Zero internet permissions, zero external dependencies.
# ==============================================================================

set -u

SCRIPT_VERSION="1.1.0"

USE_COLOR=1
WAKE_TEST=0
ROOT_AUDIT=0
DO_STORAGE_TEST=0
STORAGE_TEST_MB=100
TARGET_LANG=""
OUTPUT_FILE=""
JSON_FILE=""

CLEANUP_FILES=""
WAKE_PIDS=""

cleanup_on_exit() {
    if [ -n "$WAKE_PIDS" ]; then
        for pid in $WAKE_PIDS; do
            kill "$pid" 2>/dev/null || true
        done
        wait 2>/dev/null || true
        WAKE_PIDS=""
    fi
    if [ -n "$CLEANUP_FILES" ]; then
        for f in $CLEANUP_FILES; do
            [ -n "$f" ] && rm -f "$f" 2>/dev/null || true
        done
        CLEANUP_FILES=""
    fi
}

trap cleanup_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

print_usage() {
    printf "%s\n" "Fake Hunter Toolkit v$SCRIPT_VERSION"
    printf "%s\n" "Usage: fake_hunter.sh [options]"
    printf "%s\n" "Options:"
    printf "%s\n" "  --lang <tr|en>         Force language (Turkish or English)"
    printf "%s\n" "  --wake                 Run CPU core wake-up test"
    printf "%s\n" "  --root                 Perform deep root inspection"
    printf "%s\n" "  --storage-test [mb]    Run physical storage verification test (default: 100MB)"
    printf "%s\n" "  --output <file>        Save plain-text report to file"
    printf "%s\n" "  --json <file>          Export JSON report to file"
    printf "%s\n" "  --no-color             Disable ANSI terminal colors"
    printf "%s\n" "  --help, -h             Show this help message"
}

die_arg() {
    printf "Error: %s\n" "$1" >&2
    printf "Run 'fake_hunter.sh --help' for usage.\n" >&2
    exit 2
}

is_positive_int() {
    case "${1:-}" in
        ''|*[!0-9]*) return 1 ;;
        *) [ "$1" -gt 0 ] 2>/dev/null ;;
    esac
}

# Parse and validate arguments strictly
while [ $# -gt 0 ]; do
    case "$1" in
        --lang)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--lang' requires an argument (tr or en)."
            case "$2" in
                tr|TR) TARGET_LANG="tr" ;;
                en|EN) TARGET_LANG="en" ;;
                *) die_arg "Invalid language '$2'. Supported values: tr, en." ;;
            esac
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
            if [ $# -ge 2 ] && [ -n "${2:-}" ]; then
                case "$2" in
                    -*)
                        STORAGE_TEST_MB=100
                        shift
                        ;;
                    *)
                        is_positive_int "$2" || die_arg "Invalid storage test size '$2'. Must be a positive integer in MB."
                        STORAGE_TEST_MB="$2"
                        shift 2
                        ;;
                esac
            else
                STORAGE_TEST_MB=100
                shift
            fi
            ;;
        --storage-mb)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--storage-mb' requires a positive integer in MB."
            is_positive_int "$2" || die_arg "Invalid storage test size '$2'. Must be a positive integer in MB."
            DO_STORAGE_TEST=1
            STORAGE_TEST_MB="$2"
            shift 2
            ;;
        --output)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--output' requires a target file path."
            OUTPUT_FILE="$2"
            shift 2
            ;;
        --json)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || die_arg "Option '--json' requires a target file path."
            JSON_FILE="$2"
            shift 2
            ;;
        --no-color)
            USE_COLOR=0
            shift
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            die_arg "Unknown option '$1'."
            ;;
    esac
done

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

# Integer-based GB formatter (immune to locale decimal point differences)
calc_gb() {
    mb_val="$1"
    gb_int=$((mb_val / 1024))
    gb_dec=$(( (mb_val % 1024) * 10 / 1024 ))
    printf "%s.%s" "$gb_int" "$gb_dec"
}

# Escape arbitrary string safely for RFC 8259 JSON
json_escape() {
    if command -v awk >/dev/null 2>&1; then
        printf "%s" "$1" | awk 'BEGIN { ORS="" }
        {
            if (NR > 1) printf "\\n"
            gsub(/\\/, "\\\\")
            gsub(/"/, "\\\"")
            gsub(/\r/, "\\r")
            gsub(/\t/, "\\t")
            gsub(/\f/, "\\f")
            gsub(/\b/, "\\b")
            for (i = 1; i <= 31; i++) {
                if (i != 8 && i != 9 && i != 10 && i != 12 && i != 13) {
                    c = sprintf("%c", i)
                    gsub(c, sprintf("\\u%04x", i))
                }
            }
            print
        }'
    else
        printf "%s" "$1" | tr '\001-\010\013\014\016-\037' ' ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/	/\\t/g' | tr '\r\n' '  '
    fi
}

to_lower() {
    printf "%s" "$1" | tr 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' 'abcdefghijklmnopqrstuvwxyz'
}

# Localization helper
t() {
    key="$1"
    if [ "$TARGET_LANG" = "tr" ]; then
        case "$key" in
            TITLE) printf "%s" "=== FAKE HUNTER — DONANIM DOĞRULAMA RAPORU ===" ;;
            VERSION) printf "%s" "Araç Sürümü" ;;
            MODEL_LABEL) printf "%s" "Model" ;;
            SYSTEM_INFO) printf "%s" "1. SİSTEM VE KİMLİK BİLGİLERİ" ;;
            RAM_INFO) printf "%s" "2. BELLEK (RAM) VE TAKAS ANALİZİ" ;;
            CPU_INFO) printf "%s" "3. İŞLEMCİ (CPU) VE ÇEKİRDEK FREKANS ANALİZİ" ;;
            STORAGE_INFO) printf "%s" "4. DEPOLAMA (ROM / DATA) ANALİZİ" ;;
            ROOT_INFO) printf "%s" "5. ROOT VE ÇEKİRDEK GÜVENLİK ANALİZİ" ;;
            STORAGE_TEST_INFO) printf "%s" "6. FİZİKSEL DEPOLAMA VE YAZMA TESTİ" ;;
            VERDICT_HEADER) printf "%s" "=== TEFTİŞ VE DOĞRULAMA KARARI ===" ;;
            FINDINGS_HEADER) printf "%s" "Bulgular:" ;;
            VERDICT_CLEAN) printf "%s" "DOĞRULANDI: Donanım parametreleri tutarlı görünüyor." ;;
            VERDICT_SUSPICIOUS) printf "%s" "ŞÜPHELİ: Bazı donanım değerlerinde uyuşmazlıklar tespit edildi." ;;
            VERDICT_FAKE) printf "%s" "SAHTE DONANIM TESPİT EDİLDİ: Cihaz parametreleri manipüle edilmiş!" ;;
            PHYSICAL_RAM) printf "%s" "Fiziksel RAM (MemTotal)" ;;
            SWAP_TOTAL) printf "%s" "Takas / ZRAM Alanı" ;;
            SWAP_INFLATION) printf "%s" "Takas Değerlendirmesi" ;;
            SWAP_INFLATION_DETECTED) printf "%s" "Yüksek takas alanı: Düşük bütçeli üniteler takas belleğini fiziksel RAM ile birleştirip şişirilmiş gösterebilir" ;;
            VIRTUAL_RAM_INFO) printf "%s" "Sanal RAM / ZRAM genişletmesi etkin (Android 11+ cihazlarda yaygındır; satıcı beyanını fiziksel RAM ile karşılaştırın)" ;;
            SWAP_NORMAL) printf "%s" "Takas alanı normal aralıkta." ;;
            CORES_REPORTED) printf "%s" "Bildirilen Çekirdek Sayısı" ;;
            CORES_ACTIVE) printf "%s" "Frekans Üreten Aktif Çekirdek" ;;
            CPU_FREQ_RESTRICTED) printf "%s" "Sistem güvenlik kısıtı nedeniyle çekirdek çalışma frekansları okunamadı" ;;
            CORE_FAKE_WARNING) printf "%s" "Sahte çekirdek uyarısı: Bildirilen bazı çekirdekler frekans üretmiyor" ;;
            STORAGE_TOTAL) printf "%s" "Gerçek Kullanıcı Bölümü (/data)" ;;
            STORAGE_FREE) printf "%s" "Kullanılabilir Boş Alan" ;;
            PHYSICAL_FLASH) printf "%s" "Dahili Fiziksel Flash / eMMC" ;;
            EXTERNAL_FLASH) printf "%s" "Harici USB / SD Birimleri (dahiliden ayrı)" ;;
            FLASH_INCOMPLETE_NOTE) printf "%s" "Fiziksel çip boyutu tam doğrulanamadı (SELinux kısıtı; tam okuma için --root deneyin)" ;;
            FLASH_INCOMPLETE_ROOT_NOTE) printf "%s" "Fiziksel çip boyutu çekirdek üzerinden okunamadı" ;;
            STORAGE_SPOOFED_WARNING) printf "%s" "Sahte depolama uyarısı: /data bölümü dahili fiziksel çipten daha büyük gösteriliyor" ;;
            PLATFORM) printf "%s" "Platform / Yonga Seti" ;;
            BOARD) printf "%s" "Anakart (Board)" ;;
            HARDWARE) printf "%s" "Donanım (Hardware)" ;;
            ANDROID_REPORTED) printf "%s" "Görünen Android Sürümü" ;;
            ANDROID_API) printf "%s" "Gerçek Android API Seviyesi" ;;
            API_MISMATCH) printf "%s" "Android sürüm sahteciliği: Görünen sürüm gerçek çekirdek API seviyesiyle çelişiyor" ;;
            ROOT_STATUS) printf "%s" "Root / Yetkili Kullanıcı Durumu" ;;
            ROOT_GRANTED) printf "%s" "Root yetkisi mevcut ve teyit edildi" ;;
            ROOT_BINARY_ONLY) printf "%s" "su ikili dosyası mevcut fakat yetki istenmedi / verilmedi" ;;
            ROOT_NONE) printf "%s" "Root erişimi yok" ;;
            SELINUX_STATUS) printf "%s" "SELinux Durumu" ;;
            KERNEL_PARTITIONS) printf "%s" "Çekirdek Bölümleri (/proc/partitions)" ;;
            TEST_BLOCK_SIZE) printf "%s" "Test Boyutu" ;;
            STATUS_LABEL) printf "%s" "Durum" ;;
            STORAGE_TEST_PASSED) printf "%s" "DOĞRULANDI: Tüm bloklar ve sağlama toplamı eşleşti. Veri bozulması yok." ;;
            STORAGE_TEST_CORRUPTED) printf "%s" "VERİ BOZULMASI TESPİT EDİLDİ: Okunan veri yazılan benzersiz bloklarla eşleşmedi! Sahte depolama." ;;
            STORAGE_TEST_SKIPPED_SPACE) printf "%s" "Depolama testi atlandı: Yetersiz boş alan" ;;
            STORAGE_TEST_NO_DIR) printf "%s" "Depolama testi yapılamadı: Yazılabilir klasör bulunamadı." ;;
            STORAGE_TEST_NO_HASH) printf "%s" "Depolama testi yapılamadı: Sağlama toplamı aracı (sha256sum/sha1sum/md5sum/cksum) bulunamadı." ;;
            STORAGE_TEST_WRITE_ERR) printf "%s" "Depolama testi tamamlanamadı: Yazma veya senkronizasyon hatası (G/Ç veya izin sorunu)." ;;
            STORAGE_TEST_CACHE_NOTE) printf "%s" "Not: Test boyutu fiziksel RAM'den küçük; okuma hızı kısmen sistem önbelleğini yansıtabilir." ;;
            WRITE_SPEED) printf "%s" "Yazma Hızı" ;;
            READ_SPEED) printf "%s" "Okuma Hızı" ;;
            REPORT_SAVED) printf "%s" "Rapor kaydedildi" ;;
            JSON_SAVED) printf "%s" "JSON raporu kaydedildi" ;;
            *) printf "%s" "$key" ;;
        esac
    else
        case "$key" in
            TITLE) printf "%s" "=== FAKE HUNTER — HARDWARE AUDIT REPORT ===" ;;
            VERSION) printf "%s" "Toolkit Version" ;;
            MODEL_LABEL) printf "%s" "Model" ;;
            SYSTEM_INFO) printf "%s" "1. SYSTEM & HARDWARE IDENTITY" ;;
            RAM_INFO) printf "%s" "2. MEMORY (RAM) & SWAP ANALYSIS" ;;
            CPU_INFO) printf "%s" "3. PROCESSOR (CPU) & CORE FREQUENCY ANALYSIS" ;;
            STORAGE_INFO) printf "%s" "4. STORAGE (ROM / DATA) ANALYSIS" ;;
            ROOT_INFO) printf "%s" "5. ROOT & KERNEL SECURITY AUDIT" ;;
            STORAGE_TEST_INFO) printf "%s" "6. PHYSICAL STORAGE INTEGRITY TEST" ;;
            VERDICT_HEADER) printf "%s" "=== AUDIT VERDICT & SUMMARY ===" ;;
            FINDINGS_HEADER) printf "%s" "Findings:" ;;
            VERDICT_CLEAN) printf "%s" "VERIFIED: Hardware metrics appear genuine and consistent." ;;
            VERDICT_SUSPICIOUS) printf "%s" "SUSPICIOUS: Inconsistencies found in hardware properties." ;;
            VERDICT_FAKE) printf "%s" "FAKE HARDWARE DETECTED: Hardware specs are spoofed/manipulated!" ;;
            PHYSICAL_RAM) printf "%s" "Physical RAM (MemTotal)" ;;
            SWAP_TOTAL) printf "%s" "Swap / ZRAM Allocation" ;;
            SWAP_INFLATION) printf "%s" "Swap Evaluation" ;;
            SWAP_INFLATION_DETECTED) printf "%s" "High swap allocation: Budget head units often combine swap with physical RAM to advertise inflated specs" ;;
            VIRTUAL_RAM_INFO) printf "%s" "Virtual RAM / ZRAM expansion active (common on Android 11+ devices; verify seller claims against physical RAM)" ;;
            SWAP_NORMAL) printf "%s" "Swap space allocation is within standard operating range." ;;
            CORES_REPORTED) printf "%s" "Reported Core Count" ;;
            CORES_ACTIVE) printf "%s" "Active Cores with Frequency" ;;
            CPU_FREQ_RESTRICTED) printf "%s" "System security restrictions prevented reading core clock frequencies" ;;
            CORE_FAKE_WARNING) printf "%s" "Fake core warning: Reported cores fail to generate active frequency" ;;
            STORAGE_TOTAL) printf "%s" "Actual User Partition (/data)" ;;
            STORAGE_FREE) printf "%s" "Available Free Space" ;;
            PHYSICAL_FLASH) printf "%s" "Internal Physical Flash / eMMC" ;;
            EXTERNAL_FLASH) printf "%s" "External USB / SD Devices (excluded)" ;;
            FLASH_INCOMPLETE_NOTE) printf "%s" "Physical flash size not fully verifiable (SELinux restriction; try --root)" ;;
            FLASH_INCOMPLETE_ROOT_NOTE) printf "%s" "Physical flash size could not be read from kernel block nodes" ;;
            STORAGE_SPOOFED_WARNING) printf "%s" "Fake storage warning: /data partition reports larger capacity than internal physical chip" ;;
            PLATFORM) printf "%s" "Platform / Chipset" ;;
            BOARD) printf "%s" "Board" ;;
            HARDWARE) printf "%s" "Hardware" ;;
            ANDROID_REPORTED) printf "%s" "Reported Android Version" ;;
            ANDROID_API) printf "%s" "Actual Android API Level" ;;
            API_MISMATCH) printf "%s" "Android version spoofing: Displayed version contradicts actual kernel API level" ;;
            ROOT_STATUS) printf "%s" "Root Access Status" ;;
            ROOT_GRANTED) printf "%s" "Root access verified and granted" ;;
            ROOT_BINARY_ONLY) printf "%s" "su binary detected but permission not requested / granted" ;;
            ROOT_NONE) printf "%s" "No root access" ;;
            SELINUX_STATUS) printf "%s" "SELinux Status" ;;
            KERNEL_PARTITIONS) printf "%s" "Kernel Partitions (/proc/partitions)" ;;
            TEST_BLOCK_SIZE) printf "%s" "Test Block Size" ;;
            STATUS_LABEL) printf "%s" "Status" ;;
            STORAGE_TEST_PASSED) printf "%s" "VERIFIED: All unique blocks and checksum matched. No corruption." ;;
            STORAGE_TEST_CORRUPTED) printf "%s" "CORRUPTION DETECTED: Read-back checksum mismatched written unique blocks! Fake storage suspected." ;;
            STORAGE_TEST_SKIPPED_SPACE) printf "%s" "Storage test skipped: Insufficient free space" ;;
            STORAGE_TEST_NO_DIR) printf "%s" "Storage test could not run: No writable directory found." ;;
            STORAGE_TEST_NO_HASH) printf "%s" "Storage test could not run: No checksum utility (sha256sum/sha1sum/md5sum/cksum) available." ;;
            STORAGE_TEST_WRITE_ERR) printf "%s" "Storage test incomplete: Write or fsync failed (I/O or permission error)." ;;
            STORAGE_TEST_CACHE_NOTE) printf "%s" "Note: Test size is smaller than physical RAM; read speed may reflect OS cache." ;;
            WRITE_SPEED) printf "%s" "Write Speed" ;;
            READ_SPEED) printf "%s" "Read Speed" ;;
            REPORT_SAVED) printf "%s" "Report saved to" ;;
            JSON_SAVED) printf "%s" "JSON report saved to" ;;
            *) printf "%s" "$key" ;;
        esac
    fi
}

get_prop_clean() {
    val="$(getprop "$1" 2>/dev/null || true)"
    val="$(printf "%s" "$val" | tr -d '\r')"
    if [ -n "$val" ]; then
        printf "%s" "$val"
    else
        printf "%s" "Unknown"
    fi
}

# 1. Collect System Properties
MODEL="Unknown"
MANUFACTURER="Unknown"
PLATFORM="Unknown"
BOARD="Unknown"
HARDWARE="Unknown"
BUILD_ID="Unknown"
OS_RELEASE="Unknown"
OS_SDK="Unknown"
IS_ANDROID=0

if command -v getprop >/dev/null 2>&1; then
    IS_ANDROID=1
    MODEL="$(get_prop_clean ro.product.model)"
    MANUFACTURER="$(get_prop_clean ro.product.manufacturer)"
    PLATFORM="$(get_prop_clean ro.board.platform)"
    BOARD="$(get_prop_clean ro.product.board)"
    HARDWARE="$(get_prop_clean ro.hardware)"
    BUILD_ID="$(get_prop_clean ro.build.display.id)"
    OS_RELEASE="$(get_prop_clean ro.build.version.release)"
    OS_SDK="$(get_prop_clean ro.build.version.sdk)"
else
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

case "$OS_SDK" in
    ''|*[!0-9]*) OS_SDK_NUM=0 ;;
    *) OS_SDK_NUM="$OS_SDK" ;;
esac

OS_RELEASE_MAJOR=0
OS_RELEASE_DIGITS="$(printf "%s" "$OS_RELEASE" | sed 's/^\([0-9][0-9]*\).*/\1/')"
case "$OS_RELEASE_DIGITS" in
    ''|*[!0-9]*) OS_RELEASE_MAJOR=0 ;;
    *) OS_RELEASE_MAJOR="$OS_RELEASE_DIGITS" ;;
esac

# Structured findings tracking (separates actual issue count from weighted fraud score)
ISSUE_COUNT=0
FRAUD_SCORE=0
IS_FAKE=0
IS_SUSPICIOUS=0
FINDING_RECORDS=""
JSON_ISSUES=""
NL='
'

add_finding() {
    sev="$1" # FAKE, WARN, INFO
    msg="$2"
    ISSUE_COUNT=$((ISSUE_COUNT + 1))
    if [ "$sev" = "FAKE" ]; then
        IS_FAKE=1
        FRAUD_SCORE=$((FRAUD_SCORE + 2))
    elif [ "$sev" = "WARN" ]; then
        IS_SUSPICIOUS=1
        FRAUD_SCORE=$((FRAUD_SCORE + 1))
    fi

    if [ -z "$FINDING_RECORDS" ]; then
        FINDING_RECORDS="${sev}|${msg}"
    else
        FINDING_RECORDS="${FINDING_RECORDS}${NL}${sev}|${msg}"
    fi

    esc_msg="$(json_escape "$msg")"
    item="{\"severity\":\"${sev}\",\"message\":\"${esc_msg}\"}"
    if [ -z "$JSON_ISSUES" ]; then
        JSON_ISSUES="$item"
    else
        JSON_ISSUES="${JSON_ISSUES},${item}"
    fi
}

# 2. Root & Kernel Security Check (runs BEFORE storage/flash check so root block sizing works!)
ROOT_BINARY_FOUND=0
ROOT_GRANTED=0
ROOT_BLOCKS_SNAPSHOT=""
PARTITION_DATA=""

if [ "$IS_ANDROID" -eq 1 ]; then
    for su_path in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /product/bin/su /system_ext/bin/su /odm/bin/su; do
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
    SELINUX_MODE="$(getenforce 2>/dev/null | tr -d '\r' || true)"
    [ -z "$SELINUX_MODE" ] && SELINUX_MODE="Unknown"
fi

# Single batched su session when --root is requested
if [ "$ROOT_BINARY_FOUND" -eq 1 ] && [ "$ROOT_AUDIT" -eq 1 ]; then
    BATCHED_ROOT_CMD='echo "===FH_ID==="; id; echo "===FH_PARTS==="; cat /proc/partitions 2>/dev/null || true; echo "===FH_BLOCKS==="; for d in /sys/block/*; do [ -e "$d" ] || continue; n="${d##*/}"; s=$(cat "$d/size" 2>/dev/null || echo 0); r=$(cat "$d/removable" 2>/dev/null || echo ""); t=$(cat "$d/device/type" 2>/dev/null || echo ""); p=$(readlink -f "$d" 2>/dev/null || readlink "$d" 2>/dev/null || echo ""); echo "BLK|${n}|${s}|${r}|${t}|${p}"; done; exit'
    ROOT_RAW="$(printf "%s\n" "$BATCHED_ROOT_CMD" | su 2>/dev/null || su 0 sh -c "$BATCHED_ROOT_CMD" 2>/dev/null || true)"
    case "$ROOT_RAW" in
        *uid=0*)
            ROOT_GRANTED=1
            PARTITION_DATA="$(printf "%s\n" "$ROOT_RAW" | sed -n '/^===FH_PARTS===$/,/^===FH_BLOCKS===$/{/^===FH_/d;p;}')"
            ROOT_BLOCKS_SNAPSHOT="$(printf "%s\n" "$ROOT_RAW" | sed -n 's/^BLK|//p' | tr -d '\r')"
            ;;
    esac
fi

if [ -z "$PARTITION_DATA" ]; then
    PARTITION_DATA="$(cat /proc/partitions 2>/dev/null || true)"
fi

PARTITION_COUNT=0
if [ -n "$PARTITION_DATA" ]; then
    line_no=0
    while IFS= read -r pline; do
        line_no=$((line_no + 1))
        [ "$line_no" -le 2 ] && continue
        set -- $pline
        if [ $# -ge 4 ]; then
            PARTITION_COUNT=$((PARTITION_COUNT + 1))
        fi
    done <<EOF
$PARTITION_DATA
EOF
fi

# 3. RAM & Swap Analysis
MEM_TOTAL_KB=0
SWAP_TOTAL_KB=0
if [ -f /proc/meminfo ]; then
    while IFS= read -r mline; do
        case "$mline" in
            MemTotal:*)
                set -- $mline
                case "${2:-}" in ''|*[!0-9]*) ;; *) MEM_TOTAL_KB="$2" ;; esac
                ;;
            SwapTotal:*)
                set -- $mline
                case "${2:-}" in ''|*[!0-9]*) ;; *) SWAP_TOTAL_KB="$2" ;; esac
                ;;
        esac
    done < /proc/meminfo
fi

SWAP_ZRAM_ONLY=0
if [ -f /proc/swaps ]; then
    swap_entries=0
    zram_entries=0
    sline_no=0
    while IFS= read -r sline; do
        sline_no=$((sline_no + 1))
        [ "$sline_no" -le 1 ] && continue
        set -- $sline
        [ $# -ge 1 ] || continue
        swap_entries=$((swap_entries + 1))
        case "$(to_lower "$1")" in
            *zram*) zram_entries=$((zram_entries + 1)) ;;
        esac
    done < /proc/swaps
    if [ "$swap_entries" -gt 0 ] && [ "$swap_entries" -eq "$zram_entries" ]; then
        SWAP_ZRAM_ONLY=1
    fi
fi

MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
SWAP_TOTAL_MB=$((SWAP_TOTAL_KB / 1024))

SWAP_INFLATED=0
VIRTUAL_RAM_NOTICE=0
if [ "$SWAP_TOTAL_MB" -gt 0 ] && [ "$MEM_TOTAL_MB" -gt 0 ]; then
    SWAP_RATIO=$((SWAP_TOTAL_MB * 100 / MEM_TOTAL_MB))
    VERY_HIGH_SWAP=0
    if [ "$SWAP_RATIO" -gt 100 ]; then
        VERY_HIGH_SWAP=1
    elif [ "$SWAP_TOTAL_MB" -ge 3000 ] && [ "$MEM_TOTAL_MB" -le 4200 ]; then
        VERY_HIGH_SWAP=1
    fi

    if [ "$VERY_HIGH_SWAP" -eq 1 ]; then
        if [ "$OS_SDK_NUM" -ge 30 ] && [ "$SWAP_ZRAM_ONLY" -eq 1 ]; then
            VIRTUAL_RAM_NOTICE=1
            add_finding "INFO" "$(t VIRTUAL_RAM_INFO) (${SWAP_TOTAL_MB}MB ZRAM / ${MEM_TOTAL_MB}MB RAM)"
        else
            SWAP_INFLATED=1
            add_finding "WARN" "$(t SWAP_INFLATION_DETECTED) (${SWAP_TOTAL_MB}MB Swap / ${MEM_TOTAL_MB}MB RAM, %${SWAP_RATIO})"
        fi
    elif [ "$SWAP_RATIO" -gt 85 ] && [ "$OS_SDK_NUM" -gt 0 ] && [ "$OS_SDK_NUM" -lt 30 ] && [ "$SWAP_ZRAM_ONLY" -eq 0 ]; then
        SWAP_INFLATED=1
        add_finding "WARN" "$(t SWAP_INFLATION_DETECTED) (${SWAP_TOTAL_MB}MB Swap / ${MEM_TOTAL_MB}MB RAM, %${SWAP_RATIO})"
    fi
fi

# 4. CPU & Core Frequency Analysis
REPORTED_CORES=0
if [ -f /proc/cpuinfo ]; then
    while IFS= read -r cline; do
        case "$cline" in
            processor*) REPORTED_CORES=$((REPORTED_CORES + 1)) ;;
        esac
    done < /proc/cpuinfo
fi
[ "$REPORTED_CORES" -le 0 ] && REPORTED_CORES=1

# Wake cores BEFORE sampling cpufreq and wait cleanly afterwards
if [ "$WAKE_TEST" -eq 1 ]; then
    w_idx=0
    while [ "$w_idx" -lt "$REPORTED_CORES" ]; do
        (
            spin=0
            while [ "$spin" -lt 60000 ]; do
                spin=$((spin + 1))
            done
        ) >/dev/null 2>&1 &
        WAKE_PIDS="${WAKE_PIDS} $!"
        w_idx=$((w_idx + 1))
    done
fi

read_cpuinfo_mhz() {
    target_core="$1"
    [ -f /proc/cpuinfo ] || { printf "0"; return; }
    cur_proc=-1
    found_mhz=0
    while IFS= read -r line; do
        case "$line" in
            processor*)
                val="${line#*:}"
                set -- $val
                case "${1:-}" in ''|*[!0-9]*) cur_proc=-1 ;; *) cur_proc="$1" ;; esac
                ;;
            "cpu MHz"*)
                if [ "$cur_proc" -eq "$target_core" ]; then
                    val="${line#*:}"
                    set -- $val
                    int_part="${1%%.*}"
                    case "$int_part" in ''|*[!0-9]*) found_mhz=0 ;; *) found_mhz="$int_part" ;; esac
                    break
                fi
                ;;
        esac
    done < /proc/cpuinfo
    printf "%s" "$found_mhz"
}

ACTIVE_CORES=0
FREQ_READABLE=0
CORE_RECORDS=""
idx=0
while [ "$idx" -lt "$REPORTED_CORES" ]; do
    CORE_DIR="/sys/devices/system/cpu/cpu${idx}"
    CUR_FREQ=0
    MAX_FREQ=0
    IS_ONLINE=1

    if [ -f "${CORE_DIR}/online" ]; then
        raw_on="$(cat "${CORE_DIR}/online" 2>/dev/null || true)"
        case "$raw_on" in
            0*) IS_ONLINE=0 ;;
            *) IS_ONLINE=1 ;;
        esac
    fi

    if [ -f "${CORE_DIR}/cpufreq/scaling_cur_freq" ]; then
        CUR_FREQ="$(cat "${CORE_DIR}/cpufreq/scaling_cur_freq" 2>/dev/null || printf 0)"
    elif [ -f "${CORE_DIR}/cpufreq/cpuinfo_cur_freq" ]; then
        CUR_FREQ="$(cat "${CORE_DIR}/cpufreq/cpuinfo_cur_freq" 2>/dev/null || printf 0)"
    fi
    case "$CUR_FREQ" in ''|*[!0-9]*) CUR_FREQ=0 ;; esac

    if [ -f "${CORE_DIR}/cpufreq/cpuinfo_max_freq" ]; then
        MAX_FREQ="$(cat "${CORE_DIR}/cpufreq/cpuinfo_max_freq" 2>/dev/null || printf 0)"
    fi
    case "$MAX_FREQ" in ''|*[!0-9]*) MAX_FREQ=0 ;; esac

    if [ "$CUR_FREQ" -eq 0 ]; then
        CPUINFO_MHZ="$(read_cpuinfo_mhz "$idx")"
        if [ "$CPUINFO_MHZ" -gt 0 ] 2>/dev/null; then
            CUR_FREQ=$((CPUINFO_MHZ * 1000))
            [ "$MAX_FREQ" -eq 0 ] && MAX_FREQ="$CUR_FREQ"
        fi
    fi

    if [ "$CUR_FREQ" -gt 0 ] || [ "$MAX_FREQ" -gt 0 ]; then
        FREQ_READABLE=1
    fi

    if [ "$IS_ONLINE" -eq 1 ] && [ "$CUR_FREQ" -gt 0 ]; then
        ACTIVE_CORES=$((ACTIVE_CORES + 1))
        rec="ON|$idx|$((CUR_FREQ / 1000))|$((MAX_FREQ / 1000))"
    elif [ "$IS_ONLINE" -eq 1 ] && [ "$MAX_FREQ" -gt 0 ]; then
        rec="IDLE|$idx|0|$((MAX_FREQ / 1000))"
    elif [ "$IS_ONLINE" -eq 1 ]; then
        rec="RESTRICTED|$idx|0|0"
    else
        rec="OFF|$idx|0|0"
    fi

    if [ -z "$CORE_RECORDS" ]; then
        CORE_RECORDS="$rec"
    else
        CORE_RECORDS="${CORE_RECORDS}${NL}${rec}"
    fi
    idx=$((idx + 1))
done

if [ -n "$WAKE_PIDS" ]; then
    wait 2>/dev/null || true
    WAKE_PIDS=""
fi

if [ "$FREQ_READABLE" -eq 0 ]; then
    add_finding "INFO" "$(t CPU_FREQ_RESTRICTED) (${REPORTED_CORES})"
elif [ "$REPORTED_CORES" -gt "$ACTIVE_CORES" ]; then
    add_finding "FAKE" "$(t CORE_FAKE_WARNING) (${REPORTED_CORES} -> ${ACTIVE_CORES})"
fi

# 5. Storage Analysis (/data & Internal vs External Physical Flash)
read_df_kb() {
    target_path="$1"
    df_out="$(df -Pk "$target_path" 2>/dev/null || df "$target_path" 2>/dev/null || true)"
    [ -n "$df_out" ] || { printf "0 0"; return; }
    last_line="$(printf "%s\n" "$df_out" | tail -n 1)"
    set -- $last_line
    # POSIX df -Pk columns: Filesystem 1024-blocks Used Available Capacity Mounted
    tot="${2:-0}"
    avail="${4:-0}"
    case "$tot" in ''|*[!0-9]*) tot=0 ;; esac
    case "$avail" in ''|*[!0-9]*) avail=0 ;; esac
    printf "%s %s" "$tot" "$avail"
}

DATA_TOTAL_MB=0
DATA_FREE_MB=0
if [ -d /data ]; then
    DF_VALS="$(read_df_kb /data)"
    set -- $DF_VALS
    DATA_TOTAL_MB=$(( ${1:-0} / 1024 ))
    DATA_FREE_MB=$(( ${2:-0} / 1024 ))
fi

lookup_root_block() {
    bname="$1"
    [ -n "$ROOT_BLOCKS_SNAPSHOT" ] || return 1
    printf "%s\n" "$ROOT_BLOCKS_SNAPSHOT" | sed -n "s/^${bname}|//p" | head -n 1
}

TOTAL_FLASH_MB=0
EXTERNAL_FLASH_MB=0
FLASH_COMPLETE=1
CANDIDATE_FOUND=0
INTERNAL_CHIP_RECORDS=""
EXTERNAL_CHIP_RECORDS=""
JSON_FLASH_CHIPS=""
JSON_EXTERNAL_CHIPS=""

BLOCK_NAMES=""
if [ -n "$ROOT_BLOCKS_SNAPSHOT" ]; then
    while IFS='|' read -r rn _rest; do
        [ -n "$rn" ] || continue
        [ -z "$BLOCK_NAMES" ] && BLOCK_NAMES="$rn" || BLOCK_NAMES="${BLOCK_NAMES} ${rn}"
    done <<EOF
$ROOT_BLOCKS_SNAPSHOT
EOF
fi
if [ -z "$BLOCK_NAMES" ] && [ -d /sys/block ]; then
    for dev_path in /sys/block/*; do
        [ -e "$dev_path" ] || continue
        dn="${dev_path##*/}"
        [ -z "$BLOCK_NAMES" ] && BLOCK_NAMES="$dn" || BLOCK_NAMES="${BLOCK_NAMES} ${dn}"
    done
fi

for dev_name in $BLOCK_NAMES; do
    case "$dev_name" in
        loop*|ram*|zram*|dm-*|md*|sr*|nbd*|vnd*|fd*|*boot*|*rpmb*)
            continue
            ;;
    esac
    CANDIDATE_FOUND=1
    dev_path="/sys/block/${dev_name}"

    SECTOR_COUNT=""
    REM_FLAG=""
    MMC_TYPE=""
    REAL_PATH=""
    VIA_ROOT=0

    if [ -r "${dev_path}/size" ]; then
        SECTOR_COUNT="$(cat "${dev_path}/size" 2>/dev/null || true)"
    fi
    if [ -r "${dev_path}/removable" ]; then
        REM_FLAG="$(cat "${dev_path}/removable" 2>/dev/null || true)"
    fi
    if [ -r "${dev_path}/device/type" ]; then
        MMC_TYPE="$(cat "${dev_path}/device/type" 2>/dev/null || true)"
    fi
    if command -v readlink >/dev/null 2>&1 && [ -e "$dev_path" ]; then
        REAL_PATH="$(readlink -f "$dev_path" 2>/dev/null || readlink "$dev_path" 2>/dev/null || true)"
    fi

    case "$SECTOR_COUNT" in ''|*[!0-9]*) SECTOR_COUNT=0 ;; esac

    if [ "$SECTOR_COUNT" -eq 0 ] && [ -n "$ROOT_BLOCKS_SNAPSHOT" ]; then
        root_hit="$(lookup_root_block "$dev_name" || true)"
        if [ -n "$root_hit" ]; then
            r_sec="${root_hit%%"|"*}"
            rest1="${root_hit#*"|"}"
            r_rem="${rest1%%"|"*}"
            rest2="${rest1#*"|"}"
            r_typ="${rest2%%"|"*}"
            r_pth="${rest2#*"|"}"
            case "$r_sec" in ''|*[!0-9]*) r_sec=0 ;; esac
            if [ "$r_sec" -gt 0 ]; then
                SECTOR_COUNT="$r_sec"
                VIA_ROOT=1
            fi
            [ -z "$REM_FLAG" ] && REM_FLAG="$r_rem"
            [ -z "$MMC_TYPE" ] && MMC_TYPE="$r_typ"
            [ -z "$REAL_PATH" ] && REAL_PATH="$r_pth"
        fi
    fi

    IS_REMOVABLE=0
    case "$REM_FLAG" in 1*) IS_REMOVABLE=1 ;; esac
    case "$dev_name" in
        mmcblk*)
            case "$(to_lower "$MMC_TYPE")" in sd*) IS_REMOVABLE=1 ;; esac
            ;;
    esac
    case "$(to_lower "$REAL_PATH")" in
        */usb*) IS_REMOVABLE=1 ;;
    esac

    if [ "$IS_REMOVABLE" -eq 1 ]; then
        if [ "$SECTOR_COUNT" -gt 0 ]; then
            DEV_MB=$((SECTOR_COUNT / 2048))
            if [ "$DEV_MB" -gt 0 ]; then
                EXTERNAL_FLASH_MB=$((EXTERNAL_FLASH_MB + DEV_MB))
                rec="${dev_name}|${DEV_MB}|${VIA_ROOT}"
                [ -z "$EXTERNAL_CHIP_RECORDS" ] && EXTERNAL_CHIP_RECORDS="$rec" || EXTERNAL_CHIP_RECORDS="${EXTERNAL_CHIP_RECORDS}${NL}${rec}"
                jchip="{\"name\":\"$(json_escape "$dev_name")\",\"size_mb\":${DEV_MB},\"via_root\":$( [ "$VIA_ROOT" -eq 1 ] && printf true || printf false ),\"removable\":true}"
                [ -z "$JSON_EXTERNAL_CHIPS" ] && JSON_EXTERNAL_CHIPS="$jchip" || JSON_EXTERNAL_CHIPS="${JSON_EXTERNAL_CHIPS},${jchip}"
            fi
        fi
    else
        if [ "$SECTOR_COUNT" -gt 0 ]; then
            DEV_MB=$((SECTOR_COUNT / 2048))
            if [ "$DEV_MB" -gt 0 ]; then
                TOTAL_FLASH_MB=$((TOTAL_FLASH_MB + DEV_MB))
                rec="${dev_name}|${DEV_MB}|${VIA_ROOT}"
                [ -z "$INTERNAL_CHIP_RECORDS" ] && INTERNAL_CHIP_RECORDS="$rec" || INTERNAL_CHIP_RECORDS="${INTERNAL_CHIP_RECORDS}${NL}${rec}"
                jchip="{\"name\":\"$(json_escape "$dev_name")\",\"size_mb\":${DEV_MB},\"via_root\":$( [ "$VIA_ROOT" -eq 1 ] && printf true || printf false ),\"removable\":false}"
                [ -z "$JSON_FLASH_CHIPS" ] && JSON_FLASH_CHIPS="$jchip" || JSON_FLASH_CHIPS="${JSON_FLASH_CHIPS},${jchip}"
            else
                FLASH_COMPLETE=0
            fi
        else
            FLASH_COMPLETE=0
        fi
    fi
done

if [ "$CANDIDATE_FOUND" -eq 0 ] || [ "$TOTAL_FLASH_MB" -eq 0 ]; then
    FLASH_COMPLETE=0
fi

if [ "$FLASH_COMPLETE" -eq 1 ] && [ "$TOTAL_FLASH_MB" -gt 0 ] && [ "$DATA_TOTAL_MB" -gt 0 ]; then
    MARGIN=$((TOTAL_FLASH_MB / 50))
    if [ "$DATA_TOTAL_MB" -gt $((TOTAL_FLASH_MB + MARGIN)) ]; then
        add_finding "FAKE" "$(t STORAGE_SPOOFED_WARNING) (/data: ${DATA_TOTAL_MB}MB, Flash: ${TOTAL_FLASH_MB}MB)"
    fi
fi

# 6. Known Fraud Platform Signatures (token-bounded, matching HardwareDetector.kt)
normalize_tokens() {
    printf " %s " "$(to_lower "$1" | tr -c 'a-z0-9' ' ')"
}

HW_TOKENS="$(normalize_tokens "${PLATFORM} ${BOARD} ${HARDWARE}")"
MODEL_TOKENS="$(normalize_tokens "${MODEL}")"

CONTRADICTS_LEGACY=0
if [ "$REPORTED_CORES" -gt 4 ] || [ "$OS_RELEASE_MAJOR" -ge 11 ] || [ "$MEM_TOTAL_MB" -gt 4200 ] || [ "$SWAP_INFLATED" -eq 1 ]; then
    CONTRADICTS_LEGACY=1
fi

MATCHED_CHIPSET=""
case "$HW_TOKENS" in
    *" ac8227 "*|*" ac8227l "*|*" mt8227 "*|*" mt8227l "*|*" atc8227 "*|*" 8227l "*)
        MATCHED_CHIPSET="AC8227L"
        ;;
esac
if [ -z "$MATCHED_CHIPSET" ]; then
    case "$MODEL_TOKENS" in
        *" ac8227 "*|*" ac8227l "*|*" mt8227 "*|*" mt8227l "*)
            MATCHED_CHIPSET="AC8227L"
            ;;
    esac
fi
if [ -z "$MATCHED_CHIPSET" ]; then
    case "$HW_TOKENS" in
        *" sun8i "*|*" sun8iw"*|*" allwinner t3 "*|*" t3l "*|*" t3 p"*)
            MATCHED_CHIPSET="ALLWINNER_T3"
            ;;
        *" mt6580 "*)
            MATCHED_CHIPSET="MT6580"
            ;;
        *" rk3128 "*|*" rk3228 "*|*" rk3229 "*)
            MATCHED_CHIPSET="ROCKCHIP"
            ;;
    esac
fi

if [ -n "$MATCHED_CHIPSET" ]; then
    if [ "$CONTRADICTS_LEGACY" -eq 1 ]; then
        CHIP_SEV="FAKE"
    else
        CHIP_SEV="INFO"
    fi
    if [ "$TARGET_LANG" = "tr" ]; then
        case "$MATCHED_CHIPSET" in
            AC8227L)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "AC8227L/MT8227L (4 çekirdekli eski yonga) bildirilen çekirdek, RAM veya Android sürümüyle çelişiyor!" \
                    || add_finding "INFO" "AC8227L/MT8227L 4 çekirdekli eski platform tespit edildi; satıcı beyanının 8 çekirdek / Android 11+ olmadığından emin olun."
                ;;
            ALLWINNER_T3)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "Allwinner sun8i/T3 (4 çekirdekli eski yonga) bildirilen çekirdek, RAM veya Android sürümüyle çelişiyor!" \
                    || add_finding "INFO" "Allwinner sun8i/T3 4 çekirdekli platform tespit edildi; satıcı beyanını kontrol edin."
                ;;
            MT6580)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "MediaTek MT6580 (2015 4 çekirdekli yonga) bildirilen donanım değerleriyle çelişiyor!" \
                    || add_finding "INFO" "MediaTek MT6580 4 çekirdekli eski platform tespit edildi."
                ;;
            ROCKCHIP)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "Giriş seviyesi Rockchip RK3128/RK3228/RK3229 4 çekirdekli yonga bildirilen özelliklerle çelişiyor!" \
                    || add_finding "INFO" "Giriş seviyesi Rockchip 4 çekirdekli platform tespit edildi; sıklıkla şişirilmiş özelliklerle satılır."
                ;;
        esac
    else
        case "$MATCHED_CHIPSET" in
            AC8227L)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "AutoChips/MediaTek AC8227L (32-bit Quad-Core) contradicts reported cores, RAM, or Android version!" \
                    || add_finding "INFO" "Legacy AutoChips/MediaTek AC8227L Quad-Core platform identified; verify seller claims."
                ;;
            ALLWINNER_T3)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "Allwinner sun8i/T3 (32-bit Quad-Core) contradicts reported cores, RAM, or Android version!" \
                    || add_finding "INFO" "Legacy Allwinner sun8i/T3 Quad-Core platform identified; verify seller claims."
                ;;
            MT6580)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "MediaTek MT6580 (2015 Quad-Core) contradicts reported specs!" \
                    || add_finding "INFO" "Legacy MediaTek MT6580 Quad-Core platform identified."
                ;;
            ROCKCHIP)
                [ "$CHIP_SEV" = "FAKE" ] && add_finding "FAKE" "Entry-level Rockchip RK3128/RK3228/RK3229 Quad-Core chipset contradicts reported specs!" \
                    || add_finding "INFO" "Entry-level Rockchip Quad-Core platform identified; frequently resold with inflated specs."
                ;;
        esac
    fi
fi

# 7. Full Android Version Spoofing Check (SDK 21-36 + legacy 1-20)
expected_major_for_sdk() {
    case "$1" in
        21|22) printf "5" ;;
        23) printf "6" ;;
        24|25) printf "7" ;;
        26|27) printf "8" ;;
        28) printf "9" ;;
        29) printf "10" ;;
        30) printf "11" ;;
        31|32) printf "12" ;;
        33) printf "13" ;;
        34) printf "14" ;;
        35) printf "15" ;;
        36) printf "16" ;;
        *) printf "" ;;
    esac
}

if [ "$OS_SDK_NUM" -gt 0 ] && [ "$OS_RELEASE_MAJOR" -gt 0 ]; then
    EXP_MAJOR="$(expected_major_for_sdk "$OS_SDK_NUM")"
    if [ -n "$EXP_MAJOR" ] && [ "$OS_RELEASE_MAJOR" -gt "$EXP_MAJOR" ]; then
        add_finding "FAKE" "$(t API_MISMATCH) (Android ${OS_RELEASE} vs API ${OS_SDK_NUM} / Android ${EXP_MAJOR})"
    elif [ "$OS_SDK_NUM" -le 20 ] && [ "$OS_RELEASE_MAJOR" -ge 5 ]; then
        add_finding "FAKE" "$(t API_MISMATCH) (Android ${OS_RELEASE} vs API ${OS_SDK_NUM})"
    fi
fi

# 8. Hardened Physical Storage Verification Test
STORAGE_TEST_RUN=0
STORAGE_TEST_STATUS="NOT_RUN" # NOT_RUN, PASSED, CORRUPTED, SKIPPED, ERROR
STORAGE_TEST_PASSED=0
STORAGE_TEST_SKIPPED=0
STORAGE_TEST_ERROR=0
STORAGE_TEST_CORRUPTED=0
STORAGE_TEST_CACHE_WARN=0
STORAGE_TEST_DETAIL=""
WRITE_SPEED_MBPS=0
READ_SPEED_MBPS=0

detect_hash_tool() {
    for tool in sha256sum sha1sum md5sum cksum; do
        if command -v "$tool" >/dev/null 2>&1; then
            printf "%s" "$tool"
            return 0
        fi
    done
    printf ""
    return 1
}

compute_stream_hash() {
    htool="$1"
    out="$("$htool" 2>/dev/null || true)"
    set -- $out
    if [ "$htool" = "cksum" ]; then
        printf "%s_%s" "${1:-}" "${2:-}"
    else
        printf "%s" "${1:-}"
    fi
}

gen_test_stream() {
    mb_count="$1"
    seed_tag="$2"
    seed_file="$3"
    if command -v awk >/dev/null 2>&1; then
        awk -v mb="$mb_count" -v seed="$seed_tag" 'BEGIN {
            base = ""
            for (j = 0; j < 252; j++) {
                base = base sprintf("%016x", (j * 2654435761 + 97531) % 4294967296)
            }
            # base is 4032 bytes; header is 64 bytes -> exact 4096-byte unique page
            for (b = 0; b < mb; b++) {
                for (p = 0; p < 256; p++) {
                    hdr = sprintf("FH%-26.26s|B%010d|P%03d|X%016x|", seed, b, p, (b * 104729 + p * 7919))
                    printf "%s%s", substr(hdr, 1, 64), base
                }
            }
        }'
    else
        b=0
        while [ "$b" -lt "$mb_count" ]; do
            printf "FHBLK_%-18.18s_%07d" "$seed_tag" "$b"
            dd if="$seed_file" bs=1048544 count=1 2>/dev/null
            b=$((b + 1))
        done
    fi
}

if [ "$DO_STORAGE_TEST" -eq 1 ]; then
    STORAGE_TEST_RUN=1
    HASH_TOOL="$(detect_hash_tool || true)"

    if [ -z "$HASH_TOOL" ]; then
        STORAGE_TEST_STATUS="ERROR"
        STORAGE_TEST_ERROR=1
        STORAGE_TEST_DETAIL="$(t STORAGE_TEST_NO_HASH)"
        add_finding "WARN" "$STORAGE_TEST_DETAIL"
    else
        TARGET_DIR=""
        if [ "$IS_ANDROID" -eq 1 ]; then
            CANDIDATE_DIRS="/sdcard/Download /sdcard /storage/emulated/0 /data/local/tmp"
        else
            CANDIDATE_DIRS="."
        fi
        for d in $CANDIDATE_DIRS; do
            if [ -d "$d" ] && [ -w "$d" ]; then
                probe="${d}/.fh_probe_$$"
                if ( : > "$probe" ) 2>/dev/null; then
                    rm -f "$probe" 2>/dev/null || true
                    TARGET_DIR="$d"
                    break
                fi
            fi
        done

        if [ -z "$TARGET_DIR" ]; then
            STORAGE_TEST_STATUS="ERROR"
            STORAGE_TEST_ERROR=1
            STORAGE_TEST_DETAIL="$(t STORAGE_TEST_NO_DIR)"
            add_finding "WARN" "$STORAGE_TEST_DETAIL"
        else
            DF_TARGET="$(read_df_kb "$TARGET_DIR")"
            set -- $DF_TARGET
            AVAIL_MB=$(( ${2:-0} / 1024 ))
            REQUIRED_MB=$((STORAGE_TEST_MB + 50))

            if [ "$AVAIL_MB" -lt "$REQUIRED_MB" ]; then
                STORAGE_TEST_STATUS="SKIPPED"
                STORAGE_TEST_SKIPPED=1
                STORAGE_TEST_DETAIL="$(t STORAGE_TEST_SKIPPED_SPACE) (${AVAIL_MB}MB < ${REQUIRED_MB}MB)"
                add_finding "WARN" "$STORAGE_TEST_DETAIL"
            else
                TEST_FILE="${TARGET_DIR}/.fake_hunter_test_$$_${STORAGE_TEST_MB}m.bin"
                SEED_FILE="${TARGET_DIR}/.fake_hunter_seed_$$.bin"
                CLEANUP_FILES="${TEST_FILE} ${SEED_FILE}"
                RUN_SEED="$$_$(date +%s 2>/dev/null || printf 1)"

                if ! command -v awk >/dev/null 2>&1; then
                    dd if=/dev/urandom of="$SEED_FILE" bs=1048544 count=1 >/dev/null 2>&1 || dd if=/dev/zero of="$SEED_FILE" bs=1048544 count=1 >/dev/null 2>&1 || true
                fi

                EXPECTED_BYTES=$((STORAGE_TEST_MB * 1048576))
                T_START="$(date +%s)"
                WRITE_OK=0

                if gen_test_stream "$STORAGE_TEST_MB" "$RUN_SEED" "$SEED_FILE" | dd of="$TEST_FILE" bs=1048576 conv=fsync >/dev/null 2>&1; then
                    WRITE_OK=1
                elif gen_test_stream "$STORAGE_TEST_MB" "$RUN_SEED" "$SEED_FILE" > "$TEST_FILE" 2>/dev/null; then
                    sync 2>/dev/null || true
                    WRITE_OK=1
                fi
                sync 2>/dev/null || true
                T_WRITE_END="$(date +%s)"

                ACTUAL_BYTES=0
                if [ -f "$TEST_FILE" ]; then
                    ACTUAL_BYTES="$(wc -c < "$TEST_FILE" 2>/dev/null | tr -d ' \t\r\n' || printf 0)"
                    case "$ACTUAL_BYTES" in ''|*[!0-9]*) ACTUAL_BYTES=0 ;; esac
                fi

                if [ "$WRITE_OK" -ne 1 ] || [ "$ACTUAL_BYTES" -ne "$EXPECTED_BYTES" ]; then
                    rm -f "$TEST_FILE" "$SEED_FILE" 2>/dev/null || true
                    STORAGE_TEST_STATUS="ERROR"
                    STORAGE_TEST_ERROR=1
                    STORAGE_TEST_DETAIL="$(t STORAGE_TEST_WRITE_ERR)"
                    add_finding "WARN" "$STORAGE_TEST_DETAIL"
                else
                    WRITE_DUR=$((T_WRITE_END - T_START))
                    [ "$WRITE_DUR" -le 0 ] && WRITE_DUR=1
                    WRITE_SPEED_MBPS=$((STORAGE_TEST_MB / WRITE_DUR))

                    if [ "$ROOT_GRANTED" -eq 1 ]; then
                        printf "echo 3 > /proc/sys/vm/drop_caches\nexit\n" | su >/dev/null 2>&1 || true
                    fi

                    T_READ_START="$(date +%s)"
                    ACTUAL_HASH="$(compute_stream_hash "$HASH_TOOL" < "$TEST_FILE")"
                    T_READ_END="$(date +%s)"
                    READ_DUR=$((T_READ_END - T_READ_START))
                    [ "$READ_DUR" -le 0 ] && READ_DUR=1
                    READ_SPEED_MBPS=$((STORAGE_TEST_MB / READ_DUR))

                    EXPECTED_HASH="$(gen_test_stream "$STORAGE_TEST_MB" "$RUN_SEED" "$SEED_FILE" | compute_stream_hash "$HASH_TOOL")"

                    rm -f "$TEST_FILE" "$SEED_FILE" 2>/dev/null || true
                    CLEANUP_FILES=""

                    if [ -z "$ACTUAL_HASH" ] || [ -z "$EXPECTED_HASH" ]; then
                        STORAGE_TEST_STATUS="ERROR"
                        STORAGE_TEST_ERROR=1
                        STORAGE_TEST_DETAIL="$(t STORAGE_TEST_NO_HASH)"
                        add_finding "WARN" "$STORAGE_TEST_DETAIL"
                    elif [ "$ACTUAL_HASH" = "$EXPECTED_HASH" ]; then
                        STORAGE_TEST_STATUS="PASSED"
                        STORAGE_TEST_PASSED=1
                        STORAGE_TEST_DETAIL="$(t STORAGE_TEST_PASSED)"
                        if [ "$MEM_TOTAL_MB" -gt 0 ] && [ "$STORAGE_TEST_MB" -lt "$MEM_TOTAL_MB" ]; then
                            STORAGE_TEST_CACHE_WARN=1
                        fi
                    else
                        STORAGE_TEST_STATUS="CORRUPTED"
                        STORAGE_TEST_CORRUPTED=1
                        STORAGE_TEST_DETAIL="$(t STORAGE_TEST_CORRUPTED)"
                        add_finding "FAKE" "$STORAGE_TEST_DETAIL"
                    fi
                fi
            fi
        fi
    fi
fi

# Render report safely (use_ansi: 1 for ANSI terminal, 0 for clean plain text)
render_report() {
    use_ansi="$1"
    if [ "$use_ansi" -eq 1 ]; then
        C_RST="$(printf '\033[0m')"
        C_BLD="$(printf '\033[1m')"
        C_GRN="$(printf '\033[32m')"
        C_YLW="$(printf '\033[33m')"
        C_RED="$(printf '\033[31m')"
        C_CYN="$(printf '\033[36m')"
        C_GRY="$(printf '\033[90m')"
    else
        C_RST=""
        C_BLD=""
        C_GRN=""
        C_YLW=""
        C_RED=""
        C_CYN=""
        C_GRY=""
    fi

    printf "\n%s%s%s\n" "$C_CYN" "$(t TITLE)" "$C_RST"
    printf "%s: %s\n\n" "$(t VERSION)" "$SCRIPT_VERSION"

    printf "%s%s%s\n" "$C_BLD" "$(t SYSTEM_INFO)" "$C_RST"
    printf "  • %s: %s (%s)\n" "$(t MODEL_LABEL)" "$MODEL" "$MANUFACTURER"
    printf "  • %s: %s\n" "$(t PLATFORM)" "$PLATFORM"
    printf "  • %s: %s\n" "$(t BOARD)" "$BOARD"
    printf "  • %s: %s\n" "$(t HARDWARE)" "$HARDWARE"
    printf "  • %s: %s\n" "$(t ANDROID_REPORTED)" "$OS_RELEASE"
    printf "  • %s: %s\n\n" "$(t ANDROID_API)" "$OS_SDK"

    printf "%s%s%s\n" "$C_BLD" "$(t RAM_INFO)" "$C_RST"
    printf "  • %s: %d MB (~%s GB)\n" "$(t PHYSICAL_RAM)" "$MEM_TOTAL_MB" "$(calc_gb "$MEM_TOTAL_MB")"
    printf "  • %s: %d MB (~%s GB)\n" "$(t SWAP_TOTAL)" "$SWAP_TOTAL_MB" "$(calc_gb "$SWAP_TOTAL_MB")"
    if [ "$SWAP_INFLATED" -eq 1 ]; then
        printf "  • %s: %s%s%s\n\n" "$(t SWAP_INFLATION)" "$C_YLW" "$(t SWAP_INFLATION_DETECTED)" "$C_RST"
    elif [ "$VIRTUAL_RAM_NOTICE" -eq 1 ]; then
        printf "  • %s: %s%s%s\n\n" "$(t SWAP_INFLATION)" "$C_CYN" "$(t VIRTUAL_RAM_INFO)" "$C_RST"
    else
        printf "  • %s: %s%s%s\n\n" "$(t SWAP_INFLATION)" "$C_GRN" "$(t SWAP_NORMAL)" "$C_RST"
    fi

    printf "%s%s%s\n" "$C_BLD" "$(t CPU_INFO)" "$C_RST"
    printf "  • %s: %d\n" "$(t CORES_REPORTED)" "$REPORTED_CORES"
    if [ "$FREQ_READABLE" -eq 1 ]; then
        printf "  • %s: %d\n" "$(t CORES_ACTIVE)" "$ACTIVE_CORES"
    else
        printf "  • %s: %s%s%s\n" "$(t CORES_ACTIVE)" "$C_CYN" "$(t CPU_FREQ_RESTRICTED)" "$C_RST"
    fi
    if [ -n "$CORE_RECORDS" ]; then
        while IFS='|' read -r c_state c_idx c_cur c_max; do
            case "$c_state" in
                ON)
                    printf "    Core %s: %sONLINE%s (%s MHz / max: %s MHz)\n" "$c_idx" "$C_GRN" "$C_RST" "$c_cur" "$c_max"
                    ;;
                IDLE)
                    printf "    Core %s: %sONLINE%s (idle / max: %s MHz)\n" "$c_idx" "$C_YLW" "$C_RST" "$c_max"
                    ;;
                RESTRICTED)
                    printf "    Core %s: %sONLINE%s (freq restricted)\n" "$c_idx" "$C_CYN" "$C_RST"
                    ;;
                *)
                    printf "    Core %s: %sOFFLINE/INACTIVE%s (0 MHz)\n" "$c_idx" "$C_RED" "$C_RST"
                    ;;
            esac
        done <<EOF
$CORE_RECORDS
EOF
    fi
    printf "\n"

    printf "%s%s%s\n" "$C_BLD" "$(t STORAGE_INFO)" "$C_RST"
    if [ "$TOTAL_FLASH_MB" -gt 0 ]; then
        printf "  • %s: %d MB (~%s GB)\n" "$(t PHYSICAL_FLASH)" "$TOTAL_FLASH_MB" "$(calc_gb "$TOTAL_FLASH_MB")"
        if [ -n "$INTERNAL_CHIP_RECORDS" ]; then
            while IFS='|' read -r ch_name ch_mb ch_root; do
                root_tag=""
                [ "$ch_root" = "1" ] && root_tag=" [root]"
                printf "    • %s: %s MB (~%s GB)%s\n" "$ch_name" "$ch_mb" "$(calc_gb "$ch_mb")" "$root_tag"
            done <<EOF
$INTERNAL_CHIP_RECORDS
EOF
        fi
    fi
    if [ "$FLASH_COMPLETE" -eq 0 ]; then
        if [ "$ROOT_GRANTED" -eq 1 ]; then
            printf "  • %s: %s%s%s\n" "$(t PHYSICAL_FLASH)" "$C_YLW" "$(t FLASH_INCOMPLETE_ROOT_NOTE)" "$C_RST"
        else
            printf "  • %s: %s%s%s\n" "$(t PHYSICAL_FLASH)" "$C_YLW" "$(t FLASH_INCOMPLETE_NOTE)" "$C_RST"
        fi
    fi
    if [ "$EXTERNAL_FLASH_MB" -gt 0 ]; then
        printf "  • %s: %d MB (~%s GB)\n" "$(t EXTERNAL_FLASH)" "$EXTERNAL_FLASH_MB" "$(calc_gb "$EXTERNAL_FLASH_MB")"
        if [ -n "$EXTERNAL_CHIP_RECORDS" ]; then
            while IFS='|' read -r ex_name ex_mb ex_root; do
                printf "    • %s: %s MB (~%s GB)\n" "$ex_name" "$ex_mb" "$(calc_gb "$ex_mb")"
            done <<EOF
$EXTERNAL_CHIP_RECORDS
EOF
        fi
    fi
    if [ "$DATA_TOTAL_MB" -gt 0 ]; then
        printf "  • %s: %d MB (~%s GB)\n" "$(t STORAGE_TOTAL)" "$DATA_TOTAL_MB" "$(calc_gb "$DATA_TOTAL_MB")"
        printf "  • %s: %d MB (~%s GB)\n" "$(t STORAGE_FREE)" "$DATA_FREE_MB" "$(calc_gb "$DATA_FREE_MB")"
    fi
    printf "\n"

    printf "%s%s%s\n" "$C_BLD" "$(t ROOT_INFO)" "$C_RST"
    if [ "$ROOT_GRANTED" -eq 1 ]; then
        printf "  • %s: %s%s%s\n" "$(t ROOT_STATUS)" "$C_GRN" "$(t ROOT_GRANTED)" "$C_RST"
    elif [ "$ROOT_BINARY_FOUND" -eq 1 ]; then
        printf "  • %s: %s%s%s\n" "$(t ROOT_STATUS)" "$C_YLW" "$(t ROOT_BINARY_ONLY)" "$C_RST"
    else
        printf "  • %s: %s%s%s\n" "$(t ROOT_STATUS)" "$C_GRY" "$(t ROOT_NONE)" "$C_RST"
    fi
    printf "  • %s: %s\n" "$(t SELINUX_STATUS)" "$SELINUX_MODE"
    if [ "$PARTITION_COUNT" -gt 0 ]; then
        printf "  • %s: %d\n\n" "$(t KERNEL_PARTITIONS)" "$PARTITION_COUNT"
    else
        printf "\n"
    fi

    if [ "$STORAGE_TEST_RUN" -eq 1 ]; then
        printf "%s%s%s\n" "$C_BLD" "$(t STORAGE_TEST_INFO)" "$C_RST"
        printf "  • %s: %d MB\n" "$(t TEST_BLOCK_SIZE)" "$STORAGE_TEST_MB"
        case "$STORAGE_TEST_STATUS" in
            PASSED)
                printf "  • %s: ~%d MB/s\n" "$(t WRITE_SPEED)" "$WRITE_SPEED_MBPS"
                printf "  • %s: ~%d MB/s\n" "$(t READ_SPEED)" "$READ_SPEED_MBPS"
                printf "  • %s: %s%s%s\n" "$(t STATUS_LABEL)" "$C_GRN" "$STORAGE_TEST_DETAIL" "$C_RST"
                if [ "$STORAGE_TEST_CACHE_WARN" -eq 1 ]; then
                    printf "  • %s\n" "$(t STORAGE_TEST_CACHE_NOTE)"
                fi
                printf "\n"
                ;;
            CORRUPTED)
                printf "  • %s: ~%d MB/s\n" "$(t WRITE_SPEED)" "$WRITE_SPEED_MBPS"
                printf "  • %s: ~%d MB/s\n" "$(t READ_SPEED)" "$READ_SPEED_MBPS"
                printf "  • %s: %s%s%s\n\n" "$(t STATUS_LABEL)" "$C_RED" "$STORAGE_TEST_DETAIL" "$C_RST"
                ;;
            SKIPPED|ERROR)
                printf "  • %s: %s%s%s\n\n" "$(t STATUS_LABEL)" "$C_YLW" "$STORAGE_TEST_DETAIL" "$C_RST"
                ;;
        esac
    fi

    printf "%s%s%s\n" "$C_CYN" "$(t VERDICT_HEADER)" "$C_RST"
    if [ "$IS_FAKE" -eq 1 ]; then
        printf "%s%s%s\n\n" "${C_BLD}${C_RED}" "$(t VERDICT_FAKE)" "$C_RST"
    elif [ "$IS_SUSPICIOUS" -eq 1 ]; then
        printf "%s%s%s\n\n" "${C_BLD}${C_YLW}" "$(t VERDICT_SUSPICIOUS)" "$C_RST"
    else
        printf "%s%s%s\n\n" "${C_BLD}${C_GRN}" "$(t VERDICT_CLEAN)" "$C_RST"
    fi

    if [ -n "$FINDING_RECORDS" ]; then
        printf "%s\n" "$(t FINDINGS_HEADER)"
        while IFS='|' read -r f_sev f_msg; do
            case "$f_sev" in
                FAKE) printf "  %s[X] %s%s\n" "$C_RED" "$f_msg" "$C_RST" ;;
                WARN) printf "  %s[!] %s%s\n" "$C_YLW" "$f_msg" "$C_RST" ;;
                *)    printf "  %s[i] %s%s\n" "$C_CYN" "$f_msg" "$C_RST" ;;
            esac
        done <<EOF
$FINDING_RECORDS
EOF
        printf "\n"
    fi
}

# Display report on stdout
render_report "$USE_COLOR"

# Save clean plain-text report without needing non-portable ANSI sed escapes
if [ -n "$OUTPUT_FILE" ]; then
    render_report 0 > "$OUTPUT_FILE"
    printf "%s %s\n" "$(t REPORT_SAVED)" "$OUTPUT_FILE"
fi

# Export RFC-8259 compliant JSON report
if [ -n "$JSON_FILE" ]; then
    VERDICT_STR="CLEAN"
    [ "$IS_SUSPICIOUS" -eq 1 ] && VERDICT_STR="SUSPICIOUS"
    [ "$IS_FAKE" -eq 1 ] && VERDICT_STR="FAKE"

    cat <<EOF > "$JSON_FILE"
{
  "version": "$(json_escape "$SCRIPT_VERSION")",
  "system": {
    "model": "$(json_escape "$MODEL")",
    "manufacturer": "$(json_escape "$MANUFACTURER")",
    "platform": "$(json_escape "$PLATFORM")",
    "board": "$(json_escape "$BOARD")",
    "hardware": "$(json_escape "$HARDWARE")",
    "build_id": "$(json_escape "$BUILD_ID")",
    "os_release": "$(json_escape "$OS_RELEASE")",
    "os_sdk": "$(json_escape "$OS_SDK")"
  },
  "ram": {
    "physical_mb": $MEM_TOTAL_MB,
    "swap_mb": $SWAP_TOTAL_MB,
    "swap_inflated": $( [ "$SWAP_INFLATED" -eq 1 ] && printf true || printf false ),
    "virtual_ram_notice": $( [ "$VIRTUAL_RAM_NOTICE" -eq 1 ] && printf true || printf false )
  },
  "cpu": {
    "reported_cores": $REPORTED_CORES,
    "active_cores": $ACTIVE_CORES,
    "frequencies_readable": $( [ "$FREQ_READABLE" -eq 1 ] && printf true || printf false )
  },
  "storage": {
    "physical_flash_mb": $TOTAL_FLASH_MB,
    "external_flash_mb": $EXTERNAL_FLASH_MB,
    "flash_complete": $( [ "$FLASH_COMPLETE" -eq 1 ] && printf true || printf false ),
    "data_total_mb": $DATA_TOTAL_MB,
    "data_free_mb": $DATA_FREE_MB,
    "flash_chips": [${JSON_FLASH_CHIPS}],
    "external_chips": [${JSON_EXTERNAL_CHIPS}]
  },
  "root": {
    "binary_found": $( [ "$ROOT_BINARY_FOUND" -eq 1 ] && printf true || printf false ),
    "granted": $( [ "$ROOT_GRANTED" -eq 1 ] && printf true || printf false ),
    "selinux": "$(json_escape "$SELINUX_MODE")",
    "partition_count": $PARTITION_COUNT
  },
  "storage_test": {
    "performed": $( [ "$STORAGE_TEST_RUN" -eq 1 ] && printf true || printf false ),
    "status": "$(json_escape "$STORAGE_TEST_STATUS")",
    "tested_mb": $( [ "$STORAGE_TEST_RUN" -eq 1 ] && printf "%d" "$STORAGE_TEST_MB" || printf 0 ),
    "passed": $( [ "$STORAGE_TEST_PASSED" -eq 1 ] && printf true || printf false ),
    "skipped": $( [ "$STORAGE_TEST_SKIPPED" -eq 1 ] && printf true || printf false ),
    "error": $( [ "$STORAGE_TEST_ERROR" -eq 1 ] && printf true || printf false ),
    "corrupted": $( [ "$STORAGE_TEST_CORRUPTED" -eq 1 ] && printf true || printf false ),
    "cache_warning": $( [ "$STORAGE_TEST_CACHE_WARN" -eq 1 ] && printf true || printf false ),
    "write_speed_mb_s": $WRITE_SPEED_MBPS,
    "read_speed_mb_s": $READ_SPEED_MBPS,
    "message": "$(json_escape "$STORAGE_TEST_DETAIL")"
  },
  "verdict": "$VERDICT_STR",
  "issues_count": $ISSUE_COUNT,
  "fraud_score": $FRAUD_SCORE,
  "issues": [${JSON_ISSUES}]
}
EOF
    printf "%s %s\n" "$(t JSON_SAVED)" "$JSON_FILE"
fi
