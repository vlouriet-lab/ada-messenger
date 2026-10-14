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

### Windows: `audiopus_sys` / CMake fails to configure

`ada-core` depends on `opus`, and `audiopus_sys` builds Opus's bundled sources
through CMake. On a machine with **only Visual Studio 2026** installed this breaks
before any ADA code compiles:

```
CMake Error: Could not create named generator Visual Studio 18 2026
```

`cmake-rs` auto-detects the installed Visual Studio and asks for the VS 18 2026
generator, but the standalone CMake on `PATH` (3.x, e.g. 3.29) predates that
generator. The CMake bundled with Visual Studio 4.x does know it, but then Opus's
`cmake_minimum_required(VERSION 2.8)` collides with CMake 4 removing pre-3.5
compatibility. That is why CI avoids CMake entirely and installs Opus through
vcpkg (`LIBOPUS_LIB_DIR` makes `audiopus_sys` skip CMake — see
`.github/workflows/build-windows.yml`).

The repository pins the generator in `.cargo/config.toml`:

```toml
[env]
CMAKE_GENERATOR = "Ninja"
```

With that file in place, a plain `cargo test` invoked from the repository root
works. Run cargo from the repository root or below: a crate-local
`ada-core/.cargo/config.toml` is **not** read when Cargo is invoked with
`--manifest-path ada-core/Cargo.toml`.

If Ninja or the MSVC toolchain is not resolvable in your shell, fall back to an
explicit single-config generator inside the Visual Studio developer environment:

```powershell
$env:CMAKE_GENERATOR = 'NMake Makefiles'
$vs = (& "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath).Trim()
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
cmd /c "call ""$vcvars"" >nul 2>&1 && cargo test --manifest-path ada-core\Cargo.toml --no-default-features --features mobile-dev --lib"
Remove-Item Env:CMAKE_GENERATOR
```

Notes:

- An explicitly exported `CMAKE_GENERATOR` takes precedence over
  `.cargo/config.toml` (`[env]` never overrides an already-set variable), which is
  what keeps the fallback above usable.
- `vcvars64.bat` supplies `nmake` and `cl`; without it CMake has no toolset.
- `CMAKE_GENERATOR` must not carry a trailing space (do not write
  `set CMAKE_GENERATOR=Ninja &&` inside a `cmd /c` string), or CMake reports
  `Could not create named generator` for an otherwise valid name.
- Once `audiopus_sys` has been built successfully the artifact is cached, and
  later `cargo test` runs succeed without any of this.

## 5. Continuous integration

GitHub Actions builds and tests automatically:
- `.github/workflows/build-android.yml` — Rust core, Android APK, tests, security audit.
- `.github/workflows/build-windows.yml` — Windows installer.
- `.github/workflows/publish-release.yml` — signs and publishes releases (uses repo secrets).
