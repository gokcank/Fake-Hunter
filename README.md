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
4. **Bilinen Sahte Platform İmzaları:** AC8227L, Allwinner T3/T3L/T5, MT6580 ve Rockchip RK3128/3228 gibi meşhur sahte platformları anakart ve yonga imzalarından doğrudan tanır.
5. **Sürüm Tutarsızlığı Kontrolü:** Gerçek çekirdek API seviyesi (`SDK_INT`) ile arayüzdeki sürüm dizgisini karşılaştırır.

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
4. **Chipset Signature Database:** Identifies notorious counterfeit chips (AC8227L, Allwinner T3, MT6580, Rockchip RK3128/3228).
5. **API Level Discrepancy:** Verifies reported OS release strings against actual `Build.VERSION.SDK_INT`.

---

## 🚀 Usage / Kullanım

### Option 1: Android Application (No PC required)
Download the latest APK from [GitHub Releases](https://github.com/gokcank/Fake-Hunter/releases):
* **Modern Flavor:** Recommended for modern head units, tablets, and phones.
* **Classic Flavor:** Recommended for ultra low-spec units (Mali-400 GPU, 1GB RAM).

### Option 2: ADB Runner (From Computer)
Connect your Android device via USB debugging or Wi-Fi ADB, then run:

```bash
# Automatic detection
./scripts/fake_hunter_adb.sh

# Target specific device with Turkish language & wake test
./scripts/fake_hunter_adb.sh -s <device_serial> --lang tr --wake

# Export reports
./scripts/fake_hunter_adb.sh --save audit_report.txt --json audit_report.json
```

### Option 3: On-Device Terminal Script
Copy `scripts/fake_hunter.sh` to your device (via USB stick or download):

```sh
sh fake_hunter.sh
# or with options:
sh fake_hunter.sh --lang tr --wake --json /sdcard/report.json
```

---

## 🔒 Privacy & Security

* **Zero Internet Permissions:** `android.permission.INTERNET` is not included in the manifest.
* **Crash-Proof Design:** Employs defensive `safeRun` handlers and optional hardware features (`uses-feature required="false"`), preventing crashes on stripped-down automotive ROMs.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
