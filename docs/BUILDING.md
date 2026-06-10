# Building ADA Messenger from source

ADA Messenger has two build targets that share one Rust core (`ada-core`):
the **Android app** (`:app`) and the **Windows desktop app** (`:desktopApp`).

## 1. Prerequisites

| Tool | Version | Used for |
|------|---------|----------|
| [Rust](https://rustup.rs/) | stable | `ada-core` (native library) |
| JDK | 17 | Gradle / Kotlin |
| Android SDK | API 34+ | Android app |
| Android NDK | r26b | Cross‑compiling `ada-core` for Android |
| [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) | latest | Android `.so` builds |
| [NSIS](https://nsis.sourceforge.io/) | 3.x | Windows installer packaging |

Then create your local config:
```bash
cp android-app/local.properties.example android-app/local.properties
# edit local.properties: set sdk.dir and (optionally) signing credentials
```

## 2. Android (APK)

The Android Gradle build invokes the Rust build for the required ABIs.

```bash
cd android-app

# Debug build
./gradlew :app:assembleDebug

# Quasi-release build (release-optimized, locally signed)
./gradlew :app:assembleQuasiRelease
```

Output APKs land in `android-app/app/build/outputs/apk/`.

If you build the native core manually:
```bash
cd ada-core
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  build --features mobile --release
```

## 3. Windows (installer)

The helper script builds `ada_core.dll`, the Compose Desktop distributable, and
the NSIS installer in one step:

```powershell
# Full build, unsigned (no code-signing certificate required)
./build-installer-windows.ps1 -SkipSigning

# Reuse already-built DLL / distributable:
./build-installer-windows.ps1 -SkipCargoBuild -SkipGradleBuild -SkipSigning
```

The installer `.exe` is written to `releases/`.

To run the desktop app without packaging:
```bash
cd android-app
./gradlew :desktopApp:run
```

## 4. Testing the core

```bash
cd ada-core
cargo test --no-default-features --features mobile-dev
```

## 5. Continuous integration

GitHub Actions builds and tests automatically:
- `.github/workflows/build-android.yml` — Rust core, Android APK, tests, security audit.
- `.github/workflows/build-windows.yml` — Windows installer.
- `.github/workflows/publish-release.yml` — signs and publishes releases (uses repo secrets).
