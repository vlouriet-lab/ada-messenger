# ADA Messenger

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/vlouriet-lab/ada-messenger?include_prereleases&label=release)](https://github.com/vlouriet-lab/ada-messenger/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/vlouriet-lab/ada-messenger/build-android.yml?branch=main&label=android%20CI)](https://github.com/vlouriet-lab/ada-messenger/actions)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-brightgreen)](https://github.com/vlouriet-lab/ada-messenger/releases)

A decentralized, end-to-end encrypted, censorship-resistant messenger. It runs
peer-to-peer over an [iroh](https://www.iroh.computer/) QUIC transport, with a
shared Rust security core used by both the Android app and the Windows desktop app.

> **Status:** active development — beta. Not yet externally audited; use accordingly.

## What it does

- **End-to-end encryption** — X3DH key agreement, Double Ratchet, Ed25519 identities.
- **Peer-to-peer** — direct connections over iroh QUIC with hole-punching; no central server required.
- **Works on hostile networks** — optional bridge and store-and-forward mailbox fallback when direct P2P is blocked.
- **Messaging** — groups, replies, reactions, edits, disappearing messages, file transfer.
- **Calls** — 1:1 and group voice/video over WebRTC.
- **Cross-device** — Android and Windows account sync.
- **Incognito chats** — per-contact ephemeral identities.

This is independent software under active development. It has not undergone a
formal third-party security audit; use it accordingly.

## Install

### Стабильные релизы

[<img src="https://img.shields.io/badge/Android_APK-Скачать-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Скачать APK">](https://github.com/vlouriet-lab/ada-messenger/releases/latest)
[<img src="https://img.shields.io/badge/Windows_Installer-Скачать-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Скачать Windows Installer">](https://github.com/vlouriet-lab/ada-messenger/releases/latest)

Стабильные сборки публикуются на странице [Releases](../../releases) при каждом теге версии.

### Последняя сборка из `main` (nightly)

Актуальные артефакты каждого коммита доступны без входа в GitHub:

| Платформа | Ссылка |
|-----------|--------|
| Android (подписанный APK) | [ada-messenger-signed.zip](https://nightly.link/vlouriet-lab/ada-messenger/workflows/build-android/main/ada-messenger-signed.zip) |
| Windows (NSIS installer) | [ada-messenger-windows-installer.zip](https://nightly.link/vlouriet-lab/ada-messenger/workflows/build-windows/main/ada-messenger-windows-installer.zip) |

> Nightly-сборки не проходят дополнительного QA. Для production использования берите релиз.

**Android** — скачайте APK, включите *Установку из неизвестных источников* для браузера или файлового менеджера, откройте файл.

**Windows** — запустите `ADA-Messenger-Setup-*.exe`. SmartScreen может показать предупреждение *Подробнее → Выполнить в любом случае* (сборка без EV code-signing сертификата).

See [docs/INSTALL.md](docs/INSTALL.md) for details.

## Build from source

Full instructions: [docs/BUILDING.md](docs/BUILDING.md).

Prerequisites: [Rust](https://rustup.rs/) (stable), JDK 17, Android SDK + NDK r26b
(for Android), and [NSIS](https://nsis.sourceforge.io/) (for the Windows installer).

## Repository layout

| Path | Description |
|------|-------------|
| ada-core/ | Rust security and networking core. Exposes a C/JNI FFI. |
| android-app/app/ | Android application (Kotlin + Jetpack Compose). |
| android-app/desktopApp/ | Windows/desktop application (Compose Multiplatform). |
| cf-workers/ | Optional Cloudflare Workers (bridge and manifest infrastructure). |
| scripts/ | Operator tooling (manifest signing, bootstrap secrets). |
| docs/audits/ | Internal security audits and test results. |
| .github/workflows/ | CI: build, test, release. |

## Changelog and Roadmap

See [CHANGELOG.md](CHANGELOG.md) for release notes and [ROADMAP.md](ROADMAP.md) for planned features.

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
