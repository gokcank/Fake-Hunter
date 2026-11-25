# Fake Hunter — Sürüm Notları / Release Notes

[🇹🇷 Türkçe](#-türkçe-sürüm-notları) | [🇬🇧 English](#-english-release-notes)

---

## 🇹🇷 Türkçe Sürüm Notları

### Sürüm 1.1.0 (2026-11-25)
**Büyük Saha Güncellemesi: Otomatik Adli Teftiş Zinciri, ZIP Kanıt Dışa Aktarma, QR Kod ve Ayarlar**

Gerçek bir oto multimedya mağazasında yapılan ilk saha testinin ardından, sahada teftişi hızlandırmak ve elde edilen donanım sahteciliği kanıtlarını anında kayıt altına alabilmek için kapsamlı bir güncelleme yayınlandı.

#### 🌟 Yeni Özellikler & İyileştirmeler:
* **Hepsi-Bir-Arada Otomatik Teftiş:**
  * Uygulama açıldığında veya "Tekrar Teftiş Et" dendiğinde tüm analizler kullanıcı müdahalesine gerek kalmadan sırayla icra edilir.
  * Teftiş fazları ve ilerleme yüzdesi (Sistem Bilgileri → Çekirdek Uyandırma → Root Denetimi → eMMC/Flash Taraması → Depolama Testi) ekranda anlık olarak gösterilir.
* **Adli Teftiş ZIP Arşivi (Storage Access Framework - SAF):**
  * "Raporu Kaydet (ZIP)" butonuyla, ekrandaki mevcut teftiş sonuçları ve 11 adet düşük seviye sistem dökümü doğrudan USB belleğe veya depolama alanına `.zip` arşivi olarak kaydedilir.
  * Pakete dahil edilen adli dosyalar:
    * `summary_report.txt` ve `summary_report.json`
    * `system_properties_getprop.txt`
    * `proc_cpuinfo.txt`, `proc_meminfo.txt`, `proc_partitions.txt`, `proc_mounts.txt`
    * `sys_block_sizes.txt` (donanım sektör dökümü)
    * `gpu_info.txt`
    * `system_logs_logcat.txt` ve `kernel_logs_dmesg.txt`
* **Ekranda Çevrimdışı QR Kod Özeti:**
  * USB veya dosya aktarımıyla uğraşmadan, "QR Kod" butonuyla teftiş özeti ekranda ZXing ile QR koda dönüştürülür.
  * Telefon kamerasıyla saniyeler içinde taranarak cihazın sahte/orijinal raporu doğrudan cebe aktarılabilir.
* **Yeni Ayarlar Menüsü:**
  * **Uygulama Dili:** Türkçe, English veya Sistem Varsayılanı arasında dinamik geçiş.
  * **Otomatik Root İsteme:** Root ikili dosyası (`su`) bulunan cihazlarda teftiş sırasında otomatik yetki isteme seçeneği.
  * **Depolama Test Boyutu:** Fiziksel doğrulama blok boyutu (100 MB, 250 MB veya 500 MB) olarak özelleştirilebilir.
* **Donanımsal eMMC/UFS Sektör Boyutu Taraması:**
  * `/sys/block/*/size` çekirdek sektör dökümü (512 bayt sektör) taranarak silikon çipin gerçek fiziksel boyutu okunur.
  * Yazılımla partition tablosu şişirilmiş depolamalar (örn. 16 GB çipe sahipken 64/128 GB gösterilen cihazlar) doğrudan donanım seviyesinde ifşa edilir.
* **İki Sıralı Responsive Buton Tasarımı:**
  * Modern (Compose) ve Klasik (XML) varyantlarında butonlar iki satırlı esnek düzene kavuşturuldu; hem yatay multimedya ekranlarında hem dikey telefon/tabletlerde taşma ve kaydırma ihtiyacı olmadan tüm fonksiyonlar erişilebilir hale getirildi.
* **Bağımsız Terminal Betiği Güncellemesi (`fake_hunter.sh` v1.1.0):**
  * Donanımsal flash sektör taraması eklendi, dil ve raporlama yetenekleri güncellendi.

---

### Sürüm 1.0.0 (2026-11-24)
**İlk Resmi Sürüm: Çift Varyantlı Donanım Doğrulama ve Sahtecilik Tespit Araç Seti**

Android multimedya teypleri, Android TV kutuları ve tabletlerdeki sahte donanım beyanlarını ifşa etmek için geliştirilen temel sürüm.

#### 🌟 Özellikler:
* **Fiziksel RAM ve Takas (SWAP/ZRAM) Analizi:** `/proc/meminfo` çekirdek dökümü doğrudan okunarak gerçek fiziksel RAM ayrıştırılır ve RAM şişirmesi tespit edilir.
* **İşlemci ve Çekirdek Frekans Taraması:** Çekirdeklerin frekans üretip üretmediği taranır, çekirdek uyandırma testi ile sahte çekirdekler tespit edilir.
* **Fiziksel Depolama Bütünlük Testi:** Sahte NAND yongalarını açığa çıkarmak için rastgele veri blokları yazılıp CRC32 ile doğrulanır, gerçek yazma ve okuma hızları ölçülür.
* **İsteğe Bağlı Derin Root ve Çekirdek Analizi:** Root erişimi mevcutsa çekirdek blok bölümleri (`/proc/partitions`), sistem günlükleri ve SELinux durumu analiz edilir.
* **Bilinen Sahte Platform Veritabanı:** AC8227L, Allwinner T3/T3L, MT6580, Rockchip RK3128/3228 gibi yaygın sahte yonga seti imzaları çevrimdışı tanınır.
* **Sıfır İnternet İzni Politikası:** %100 çevrimdışı çalışma ve tam gizlilik.
* **Çift Varyant Mimarisi:** Modern (Jetpack Compose) ve Klasik (XML Views) varyantları.
* **Bağımsız Betikler:** Cihaz üzerinde çalışan `fake_hunter.sh` ve bilgisayardan yönetilen `fake_hunter_adb.sh`.

---

## 🇬🇧 English Release Notes

### Version 1.1.0 (2026-11-25)
**Major Field Update: All-in-One Automated Audit, Forensic ZIP Evidence Export, On-Screen QR Code & Settings**

Following the first real-world field verification at an automotive multimedia store, this major update delivers an end-to-end automated audit chain, one-click forensic evidence packaging, and on-screen QR sharing for rapid in-store inspections.

#### 🌟 New Features & Enhancements:
* **All-in-One Automated Audit Chain:**
  * Runs automatically upon app launch or when tapping *Re-inspect*.
  * Progress is displayed in real-time with visual phase indicators (System Identity → CPU Wake Test → Root Audit → eMMC/Flash Sizing → Storage Test).
* **Forensic Evidence ZIP Export (SAF):**
  * One-click export to a single `.zip` archive via Android's Storage Access Framework (file picker) directly to USB drives or storage without re-running tests.
  * Archives 11 low-level diagnostic dumps:
    * `summary_report.txt` and `summary_report.json`
    * `system_properties_getprop.txt`
    * `proc_cpuinfo.txt`, `proc_meminfo.txt`, `proc_partitions.txt`, `proc_mounts.txt`
    * `sys_block_sizes.txt` (raw sector dump)
    * `gpu_info.txt`
    * `system_logs_logcat.txt` and `kernel_logs_dmesg.txt`
* **On-Screen Offline QR Code Summary:**
  * Generates an offline QR code (powered by ZXing) displaying the audit verdict and key hardware specs.
  * Allows users to instantly scan and transfer proof to their personal smartphone camera without needing USB drives or cables.
* **New Settings Dialog:**
  * **Language:** Switch between Turkish, English, and System Default dynamically with context-level locale switching.
  * **Auto-Root Escalation:** Configure whether the app automatically requests root permissions if `su` binary is present.
  * **Storage Test Size:** Customize write/read test block sizes (100 MB, 250 MB, 500 MB).
* **Raw Flash Silicon Sizing (`/sys/block/*/size`):**
  * Reads kernel sector counts (512-byte sectors) to determine true physical eMMC/UFS silicon capacity.
  * Exposes ROMs where partition boundaries (`/data`) are artificially inflated beyond physical chip sizes.
* **Responsive 2-Row Action Bar:**
  * Re-architected top buttons in both Modern (Compose) and Classic (XML Views) flavors into a clean, 2-row layout. Guarantees zero clipping across all screen sizes and orientations.
* **Standalone Shell Script (`fake_hunter.sh` v1.1.0):**
  * Updated with hardware sector sizing, enhanced localization, and robust execution.

---

### Version 1.0.0 (2026-11-24)
**Initial Release: Dual-Flavor Hardware Verification & Fraud Detection Toolkit**

Foundational release targeting fraudulent Android head units, streaming TV boxes, tablets, and phones.

#### 🌟 Features:
* **Physical RAM & Swap Inflation Detection:** Parses `/proc/meminfo` directly to separate physical RAM from Swap/ZRAM.
* **CPU Core Frequency & Wake Testing:** Scans active frequencies per core; tests wake-up capabilities under load.
* **Physical Storage Verification:** Detects counterfeit NAND flash by writing pseudo-random blocks, calculating CRC32 checksums, and benchmarking I/O speeds.
* **Optional Deep Root & Kernel Security:** Inspects `/proc/partitions`, dmesg, and SELinux enforcement status when root is granted.
* **Known Counterfeit Chipset Database:** Offline recognition of AC8227L, Allwinner T3/T3L, MediaTek MT6580, Rockchip RK3128/3228.
* **Zero Internet Permissions:** 100% offline and secure operation.
* **Dual UI Flavors:** Modern (Jetpack Compose) and Classic (XML Views).
* **CLI & ADB Tooling:** Standalone `fake_hunter.sh` and host-side `fake_hunter_adb.sh`.
