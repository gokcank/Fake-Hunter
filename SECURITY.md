# Security Policy / Güvenlik Politikası

## Offline-First Security Model / Sıfır İnternet Güvenlik Modeli

Fake Hunter is designed from the ground up as a **100% offline** hardware inspection toolkit:
- **Zero Network Permissions:** Neither flavor (`Modern` or `Classic`) declares `android.permission.INTERNET` or `ACCESS_NETWORK_STATE`. This guarantee is enforced automatically in CI on every merged manifest.
- **No Telemetry or Cloud Sync:** Android cloud backup and device-to-device data extraction are disabled (`allowBackup="false"`, `data_extraction_rules.xml`).
- **Forensic Archive Privacy Notice:** When you export a forensic `.zip` evidence package, raw system dumps (`system_properties_getprop.txt`, `system_logs_logcat.txt`, `kernel_logs_dmesg.txt`) are saved locally under your control and may contain hardware serial numbers or build identifiers. Review those files before sharing archives publicly.

## Supported Versions / Desteklenen Sürümler

| Version | Supported |
| :--- | :--- |
| `1.1.x` | ✅ Yes |
| `< 1.1.0` | ❌ Upgrade to latest release |

## Reporting a Vulnerability / Güvenlik Açığı Bildirimi

Please **do not** post exploitable security vulnerabilities in public issues.
Instead, use **GitHub Private Vulnerability Reporting** (`Security` -> `Report a vulnerability` on the repository page) so we can investigate and ship a fix promptly.
