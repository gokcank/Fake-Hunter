<!-- Project card. Lives in the project repo. Max ~100 lines. No personal data: repos may be public. -->
# Fake Hunter

Hardware authenticity and fraud detection toolkit for Android Head Units (car multimedia), Android TV boxes, tablets, and smartphones. Zero internet permissions.

## Build & test
- Requirements: JDK 17 / 21, Android SDK (compileSdk 35, minSdk 21).
- Flavors: `modern` (Jetpack Compose) and `classic` (XML Views).
- Build debug: `./gradlew assembleModernDebug assembleClassicDebug`
- Build release: `./gradlew assembleModernRelease assembleClassicRelease`
- Scripts: `scripts/fake_hunter.sh` (on-device) and `scripts/fake_hunter_adb.sh` (host via ADB).

## Layout
- `app/src/main/`: Core detection engine (`HardwareDetector.kt`, `SafeRunner.kt`), shared models, manifest, icons.
- `app/src/modern/`: Jetpack Compose UI implementation.
- `app/src/classic/`: XML Views / ViewBinding UI implementation.
- `scripts/`: Standalone shell and ADB scripts.

## Project rules
- Zero internet permissions in AndroidManifest.xml (must remain completely offline).
- Defensive programming: Wrap hardware reads in `safeRun` to prevent crashes on stripped-down automotive ROMs.
- Dual flavor parity: New detection metrics must be visible in both Modern and Classic variants.

## Status (as of 2026-11-26)
- Project status: **active** (set 2026-11-24; source of truth: the brain page).
- Version: 1.1.0. Automated audit chain with lifecycle-safe single-flight coordinator, forensic ZIP evidence export via SAF (plus app-dir fallback), ASCII QR summary, full TR/EN/System localization with instant system-locale restoration, internal vs removable `/sys/block/*/size` flash sizing, hardened POSIX/ADB scripts, JUnit4 unit test suite, and CI zero-internet manifest gate.

## Open items
- [ ] Add connected Android instrumentation UI tests.
- [ ] Expand known counterfeit chipset signatures in the offline database.
- [ ] Configure production release keystore and R8 minification when preparing the next official release.

## More context
Background, decisions and history are in the second brain: `/home/gokcank/ikinci-beyin/projects/fake-hunter.md` (read on demand).
