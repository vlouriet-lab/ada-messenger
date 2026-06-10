# ADA Messenger

ADA Messenger is a **decentralized, end-to-end encrypted, censorship‑resistant**
messenger. It runs **peer‑to‑peer** over an [iroh](https://www.iroh.computer/)
QUIC transport (with optional bridge/mailbox fallback for hostile networks) and
ships a Rust security core shared by the Android app and the Windows desktop app.

> Status: active development — current version **0.3** (`VERSION_CODE` 55).

---

## ✨ Features

- **End‑to‑end encryption** — X3DH key agreement + Double Ratchet, Ed25519 identities.
- **Serverless by default** — direct P2P via iroh QUIC with hole‑punching.
- **Censorship resistance** — pluggable bridges, store‑and‑forward mailbox, obfuscated transports.
- **Voice & video calls** — 1:1 and group calls over WebRTC.
- **Groups, replies, reactions, edits, disappearing messages, file transfer.**
- **Cross‑device** — Android phone ⇄ Windows desktop account sync.
- **Incognito chats** — per‑contact ephemeral identities.

---

## 📥 Install (end users)

Pre‑built binaries are published on the [Releases](../../releases) page.

### Android
1. Download the latest `ada-messenger-*.apk` from [Releases](../../releases).
2. On your device enable *Install unknown apps* for your browser/file manager.
3. Open the APK to install.

> Or via ADB: `adb install -r ada-messenger-debug.apk`

### Windows
1. Download the latest `ADA-Messenger-Setup-*.exe` from [Releases](../../releases).
2. Run the installer. Windows SmartScreen may show a one‑time
   *More info → Run anyway* prompt (the build is not EV‑code‑signed).

---

## 🛠️ Build from source

Full instructions are in [docs/BUILDING.md](docs/BUILDING.md). Quick start:

### Prerequisites
- [Rust](https://rustup.rs/) (stable)
- JDK 17
- Android SDK + NDK r26b (Android builds)
- [NSIS](https://nsis.sourceforge.io/) (Windows installer)

### Android (APK)
```bash
# Build the native core for Android ABIs, then the app
cd android-app
./gradlew :app:assembleQuasiRelease     # or assembleDebug
```

### Windows (installer)
```powershell
# Builds ada_core.dll + Compose Desktop app + NSIS installer
./build-installer-windows.ps1 -SkipSigning
```

---

## 🧩 Repository layout

| Path | Description |
|------|-------------|
| `ada-core/` | Rust security & networking core (P2P, crypto, transport). Exposes a C/JNI FFI. |
| `android-app/app/` | Android application (Kotlin + Jetpack Compose). |
| `android-app/desktopApp/` | Windows/desktop application (Compose Multiplatform). |
| `cf-workers/` | Optional Cloudflare Workers (bridge & manifest infrastructure). |
| `scripts/` | Operator tooling (manifest signing, bootstrap secrets). |
| `.github/workflows/` | CI: build, test, security audit, release. |

---

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) and our
[Code of Conduct](CODE_OF_CONDUCT.md) before opening a pull request.

## 🔐 Security

Found a vulnerability? **Do not open a public issue.** See [SECURITY.md](SECURITY.md)
for responsible disclosure instructions.

## 📄 License

Released under the [MIT License](LICENSE).
