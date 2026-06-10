# ADA Messenger

A decentralized, end-to-end encrypted, censorship-resistant messenger. It runs
peer-to-peer over an [iroh](https://www.iroh.computer/) QUIC transport, with a
shared Rust security core used by both the Android app and the Windows desktop app.

> Status: active development — current version **0.3**.

## What it does

- **End-to-end encryption** — X3DH key agreement, Double Ratchet, Ed25519 identities.
- **Peer-to-peer** — direct connections over iroh QUIC with hole-punching; no central server required.
- **Works on hostile networks** — optional bridge and store-and-forward mailbox fallback when direct P2P is blocked.
- **Messaging** — groups, replies, reactions, edits, disappearing messages, file transfer.
- **Calls** — 1:1 and group voice/video over WebRTC.
- **Cross-device** — Android ⇄ Windows account sync.
- **Incognito chats** — per-contact ephemeral identities.

This is independent software under active development. It has not undergone a
formal third-party security audit; use it accordingly.

## Install

Pre-built binaries are on the [Releases](../../releases) page.

**Android** — download `ada-messenger-*.apk`, enable *Install unknown apps* for
your browser/file manager, and open the APK. (Or `adb install -r ada-messenger-debug.apk`.)

**Windows** — download `ADA-Messenger-Setup-*.exe` and run it. SmartScreen may
show a one-time *More info → Run anyway* prompt (the build is not EV-code-signed).

See [docs/INSTALL.md](docs/INSTALL.md) for details.

## Build from source

Full instructions: [docs/BUILDING.md](docs/BUILDING.md).

```bash
# Android APK
cd android-app
./gradlew :app:assembleQuasiRelease   # or assembleDebug
```

```powershell
# Windows installer (ada_core.dll + Compose Desktop app + NSIS installer)
./build-installer-windows.ps1 -SkipSigning
```

Prerequisites: [Rust](https://rustup.rs/) (stable), JDK 17, Android SDK + NDK r26b
(for Android), and [NSIS](https://nsis.sourceforge.io/) (for the Windows installer).

## Repository layout

| Path | Description |
|------|-------------|
| `ada-core/` | Rust security & networking core (P2P, crypto, transport). Exposes a C/JNI FFI. |
| `android-app/app/` | Android application (Kotlin + Jetpack Compose). |
| `android-app/desktopApp/` | Windows/desktop application (Compose Multiplatform). |
| `cf-workers/` | Optional Cloudflare Workers (bridge & manifest infrastructure). |
| `scripts/` | Operator tooling (manifest signing, bootstrap secrets). |
| `docs/audits/` | Internal security audits and test results. |
| `.github/workflows/` | CI: build, test, release. |

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md) before opening a pull request.

## Security

Found a vulnerability? **Do not open a public issue.** See [SECURITY.md](SECURITY.md)
for responsible disclosure instructions.

## License

Released under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).
This is a strong copyleft license: anyone who distributes the software or runs a
modified version as a network service must make the complete corresponding source
code available under the same terms.
