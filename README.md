# Fake Hunter Toolkit 🛡️

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B%20(API%2021%2B)-green.svg)](https://developer.android.com)
[![Permissions](https://img.shields.io/badge/Permissions-Zero%20Internet-brightgreen.svg)](#privacy--security)
[![Architecture](https://img.shields.io/badge/Architecture-Dual%20Flavor%20(Modern%20%26%20Classic)-orange.svg)](#flavors)

**Fake Hunter** is an open-source hardware verification and fraud detection toolkit designed for Android Head Units (car multimedia systems), Android TV boxes, tablets, and smartphones.

---

## 🇹🇷 Türkçe Açıklama

### Problem: Sahte Donanım Tuzağı
Pazardaki birçok Android multimedya teybi ve TV kutusu (özellikle AC8227L, Allwinner T3 veya MT6580 yongalı cihazlar), yazılımsal modifikasyonlarla yanıltıcı bilgiler sunar:
* **Sahte 8 Çekirdek:** Aslında 4 çekirdekli eski bir yonga olmasına rağmen sistem dosyaları 8 çekirdek gösterecek şekilde modifiye edilir; sahte çekirdekler frekans üretmez.
* **Şişirilmiş 8 GB RAM:** Cihazda fiziksel olarak 1 GB veya 2 GB RAM bulunurken, takas alanı (SWAP/ZRAM) fiziksel bellek gibi gösterilerek 4 GB veya 8 GB olarak pazarlanır.
* **Sahte Android Sürümü:** Cihazın arka planda Android 8.1 (Oreo) veya Android 10 (Q) çalıştırmasına rağmen arayüzde "Android 13" veya "Android 14" yazdırılır.

### Fake Hunter Nasıl Tespit Eder?
1. **Ham Bellek Denetimi:** `/proc/meminfo` çekirdek dökümünü doğrudan okuyarak gerçek fiziksel RAM (`MemTotal`) ile takas alanını (`SwapTotal`) ayrıştırır ve şişirmeyi ifşa eder.
2. **Çekirdek Frekans Taraması:** `/sys/devices/system/cpu/` ve `/proc/cpuinfo` üzerinden tüm çekirdekleri gerçek zamanlı tarar. Frekans üretmeyen sahte çekirdekleri yakalar (isteğe bağlı çekirdek uyandırma testi mevcuttur).
3. **Gerçek Depolama Boyutu:** `/data` kullanıcı bölümünü donanım seviyesinde sorgulayarak şişirilmiş depolama boyutunu açığa çıkarır.
4. **Fiziksel Depolama Doğrulama Testi:** Sahte hafıza çiplerini (ör. 16 GB iken yazılımla 64 GB/128 GB gösterilen depolama) yakalamak için rastgele veri blokları yazıp geri okur, CRC32 sağlama toplamı ile bozulmayı tespit eder ve yazma/okuma hızını ölçer.
5. **İsteğe Bağlı Root ve Çekirdek Denetimi:** Cihaz root edilmişse çekirdek blok bölümlerini (`/proc/partitions`), dmesg sistem günlüklerini ve SELinux durumunu derinlemesine analiz eder.
6. **Bilinen Sahte Platform İmzaları:** AC8227L, Allwinner T3/T3L/T5, MT6580 ve Rockchip RK3128/3228 gibi meşhur sahte platformları anakart ve yonga imzalarından doğrudan tanır.
7. **Sürüm Tutarsızlığı Kontrolü:** Gerçek çekirdek API seviyesi (`SDK_INT`) ile arayüzdeki sürüm dizgisini karşılaştırır.

### Araç Seti Bileşenleri
1. **Android Uygulaması (`app/`):**
   * **Modern Varyant (Jetpack Compose):** 1024x600 ve üzeri multimedya ekranlarına uygun modern, reaktif kontrol paneli.
   * **Klasik Varyant (XML Views):** En zayıf donanımlı (Mali-400 GPU, 1 GB RAM) teyplerde bile sıfır gecikmeyle çalışan ultra hafif arayüz.
   * **Sıfır İnternet İzni:** Dışarıya tek bir bayt dahi veri göndermez, tam gizlilik ve güvenlik sağlar.
2. **Bağımsız Komut Satırı Araçları (`scripts/`):**
   * `scripts/fake_hunter.sh`: Cihazın kendi terminalinden veya harici USB bellekten doğrudan çalışan kurulumsuz betik.
   * `scripts/fake_hunter_adb.sh`: Bilgisayardan USB/Wi-Fi ADB üzerinden tek komutla teftiş yapan betik.

---

## 🇬🇧 English Description

### The Problem: Hardware Fraud
Budget Android Head Units and TV streaming boxes from various marketplaces frequently deceive buyers with spoofed firmware:
* **Spoofed 8 Cores:** Hardware is physically a 4-core CPU, but ROM is hacked to report 8 cores. The fake cores generate 0 Hz.
* **Inflated RAM:** Physical RAM is only 1GB–2GB, but system Swap/ZRAM space is added to the count to falsely claim "8GB RAM".
* **Fake Android Version:** The OS reports "Android 13/14" in system settings while kernel API level is actually Android 8.1 or 10.

### How Fake Hunter Detects Fraud
1. **Direct Memory Parsing:** Reads `/proc/meminfo` directly, calculating real physical RAM (`MemTotal`) vs Swap (`SwapTotal`).
2. **Core Frequency Auditing:** Scans `/sys/devices/system/cpu/` and `/proc/cpuinfo` to verify active clock frequencies per core.
3. **Storage Sizing:** Queries actual partition boundaries on `/data` using `StatFs`.
4. **Physical Storage Verification Test:** Detects spoofed flash storage (e.g. 16GB chips flashed to report 64GB/128GB) by writing pseudo-random blocks, verifying integrity with CRC32, and benchmarking read/write throughput.
5. **Optional Deep Root & Kernel Audit:** Inspects raw kernel block partitions (`/proc/partitions`), dmesg logs, and SELinux state when root access is present.
6. **Chipset Signature Database:** Identifies notorious counterfeit chips (AC8227L, Allwinner T3, MT6580, Rockchip RK3128/3228).
7. **API Level Discrepancy:** Verifies reported OS release strings against actual `Build.VERSION.SDK_INT`.

---

## 🚀 Kullanım Rehberi (Türkçe)

### Yöntem 1: Android Uygulaması ile Teftiş (Tavsiye Edilen - Bilgisayar Gerektirmez)
[GitHub Releases](https://github.com/gokcank/Fake-Hunter/releases) sayfasından en güncel APK'yı indirin veya bir USB belleğe kopyalayarak cihaza takın:
* **Modern Varyant (`FakeHunter-v1.1.0-Modern.apk`):** Jetpack Compose ile geliştirilmiş modern, reaktif arayüz. Güncel teypler, tabletler ve telefonlar için önerilir.
* **Klasik Varyant (`FakeHunter-v1.1.0-Classic.apk`):** Eski Android sürümleri (Android 5+) ve zayıf donanımlı teypler (Mali-400 GPU, 1-2 GB RAM) için optimize edilmiş ultra hafif XML Views arayüzü.

**Teftiş Süreci ve Özellikler:**
1. **Otomatik Hepsi-Bir-Arada Teftiş:** Uygulama açıldığı anda veya *Tekrar Teftiş Et* butonuna basıldığında RAM, SWAP şişirmesi, CPU çekirdek uyandırma testi, `/sys/block/*/size` fiziksel flash çip boyutu ve 100 MB rastgele veri yazma/okuma doğrulama testini sırayla otomatik icra eder.
2. **Adli Teftiş ZIP Arşivi (SAF):** *Raporu Kaydet (ZIP)* butonuna dokunarak mevcut teftiş raporunu ve 11 ayrı sistem dökümünü (`getprop`, `cpuinfo`, `meminfo`, `partitions`, `mounts`, `logcat`, `dmesg`, vb.) doğrudan USB belleğe veya yerel hafızaya `.zip` arşivi olarak kaydedebilirsiniz.
3. **Ekranda Çevrimdışı QR Kod:** *QR Kod* butonuna basarak teftiş özetini ekranda QR koda dökebilir, telefonunuzun kamerasıyla saniyeler içinde tarayarak yanınıza alabilirsiniz.
4. **Ayarlar:** Uygulama dilini (Türkçe / English / Sistem Varsayılanı), otomatik root yetkisi isteme tercihini ve depolama test blok boyutunu (100 MB, 250 MB, 500 MB) ayarlayabilirsiniz.

### Yöntem 2: Bilgisayardan ADB ile Teftiş (Host Script)
Cihazı USB kablosuyla veya Wi-Fi ADB üzerinden bilgisayarınıza bağlayın:

```bash
# Otomatik cihaz tespiti ve varsayılan teftiş
./scripts/fake_hunter_adb.sh

# Belirli bir cihazı hedefleyerek Türkçe dilde ve çekirdek uyandırmayla çalıştırma
./scripts/fake_hunter_adb.sh -s <cihaz_seri_no> --lang tr --wake --storage-test 100

# Raporları metin ve JSON olarak bilgisayara kaydetme
./scripts/fake_hunter_adb.sh --save teftis_raporu.txt --json teftis_raporu.json
```

### Yöntem 3: Cihaz Üzerinde Terminal Betiği (Kurulumsuz)
Cihazda Termux veya Terminal Emulator yüklüyse `scripts/fake_hunter.sh` dosyasını USB bellekten veya dahili hafızadan doğrudan çalıştırabilirsiniz:

```sh
# Doğrudan çalıştırma (Türkçe dilde)
sh fake_hunter.sh --lang tr

# Tam teşekküllü teftiş (çekirdek uyandırma + root sorgusu + 100 MB depolama testi)
sh fake_hunter.sh --lang tr --wake --root --storage-test 100 --output rapor.txt --json rapor.json
```

---

## 🚀 Usage Guide (English)

### Option 1: Android Application (Recommended - No PC Required)
Download the latest release APK from [GitHub Releases](https://github.com/gokcank/Fake-Hunter/releases) or transfer it via a USB flash drive:
* **Modern Flavor (`FakeHunter-v1.1.0-Modern.apk`):** Built with Jetpack Compose. Responsive, modern UI designed for high-resolution multimedia screens, tablets, and phones.
* **Classic Flavor (`FakeHunter-v1.1.0-Classic.apk`):** Ultra lightweight XML Views implementation optimized for entry-level automotive head units (Mali-400 GPU, 1-2 GB RAM, Android 5.0+).

**Audit Workflow & Features:**
1. **All-in-One Automated Audit:** On startup or by clicking *Re-inspect*, the app automatically runs physical RAM analysis, Swap inflation detection, CPU core wake-up benchmarking, `/sys/block/*/size` physical flash silicon capacity detection, and 100 MB physical storage verification.
2. **Forensic Evidence ZIP Export (SAF):** Click *Save Evidence (ZIP)* to package the on-screen audit results together with 11 low-level diagnostic dumps (`getprop`, `cpuinfo`, `meminfo`, `partitions`, `mounts`, `logcat`, `dmesg`, etc.) into a forensic `.zip` archive via Android's Storage Access Framework file picker.
3. **On-Screen QR Code:** Click *QR Code* to render an offline ZXing QR code containing the full audit summary, ready for instant camera capture with your phone.
4. **Settings Dialog:** Toggle language dynamically (Türkçe / English / System Default), enable/disable automatic root escalation, and customize the physical storage test block size (100 MB, 250 MB, 500 MB).

### Option 2: ADB Runner from Host PC
Connect your Android target via USB debugging or Wireless ADB:

```bash
# Auto-detect connected device and run default audit
./scripts/fake_hunter_adb.sh

# Target specific device with English language, CPU wake test, and 100MB storage test
./scripts/fake_hunter_adb.sh -s <device_serial> --lang en --wake --storage-test 100

# Export plain-text and JSON reports directly to host machine
./scripts/fake_hunter_adb.sh --save audit_report.txt --json audit_report.json
```

### Option 3: Standalone On-Device Shell Script
If the device has Termux or Terminal Emulator installed, run `scripts/fake_hunter.sh` directly without installing any APK:

```sh
# Basic run
sh fake_hunter.sh --lang en

# Comprehensive audit with CPU wake, root check, and physical storage test
sh fake_hunter.sh --lang en --wake --root --storage-test 100 --output report.txt --json report.json
```

---

## 🔒 Privacy & Security

* **Zero Internet Permissions:** `android.permission.INTERNET` is not included in the manifest.
* **Crash-Proof Design:** Employs defensive `safeRun` handlers and optional hardware features (`uses-feature required="false"`), preventing crashes on stripped-down automotive ROMs.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
