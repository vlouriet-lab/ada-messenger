# ADA Messenger

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/vlouriet-lab/ada-messenger?include_prereleases&label=release)](https://github.com/vlouriet-lab/ada-messenger/releases)

A decentralized, end-to-end encrypted, censorship-resistant messenger. It runs peer-to-peer over an [iroh](https://www.iroh.computer/) QUIC transport, with a shared Rust security core used by both the Android app and the Windows desktop app.

Децентрализованный, защищенный сквозным шифрованием и устойчивый к цензуре мессенджер. Работает по принципу peer-to-peer через транспорт [iroh](https://www.iroh.computer/) QUIC с общим ядром безопасности на Rust, которое используется как в приложении для Android, так и в десктопном приложении для Windows.

> **Status / Статус:** Active development — beta. Not yet externally audited; use accordingly. / Активная разработка — бета. Независимый аудит безопасности еще не проводился, используйте с осторожностью.

## Features / Возможности

- **End-to-end encryption / Сквозное шифрование** — X3DH key agreement, Double Ratchet, Ed25519 identities.
- **Peer-to-peer** — Direct connections over iroh QUIC with hole-punching / Прямые соединения через iroh QUIC с hole-punching; центральный сервер не требуется.
- **Censorship resistance / Устойчивость к блокировкам** — Optional bridge and store-and-forward mailbox fallback / Запасной вариант с бриджами и почтовыми ящиками (store-and-forward) при блокировке прямого P2P.
- **Messaging / Сообщения** — Groups, replies, reactions, disappearing messages / Группы, ответы, реакции, исчезающие сообщения, передача файлов.
- **Calls / Звонки** — 1:1 and group voice/video over WebRTC / Индивидуальные и групповые аудио/видео звонки.
- **Cross-device / Мультиустройство** — Android and Windows account sync / Синхронизация аккаунта между Android и Windows.

## Install / Установка

Stable builds are published on the [Releases](../../releases) page. / Стабильные сборки публикуются на странице [Releases](../../releases).

[<img src="https://img.shields.io/badge/Android_APK-Скачать-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Скачать APK">](https://github.com/vlouriet-lab/ada-messenger/releases/latest)
[<img src="https://img.shields.io/badge/Windows_Installer-Скачать-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Скачать Windows Installer">](https://github.com/vlouriet-lab/ada-messenger/releases/latest)

- **Android** — Download `.apk` from Releases. / Скачайте `.apk` со страницы релизов.
- **Windows** — Run `ADA-Messenger-Setup-*.exe`. / Запустите инсталлятор `ADA-Messenger-Setup-*.exe`.

## Build from source / Сборка из исходников
Full instructions / Полные инструкции: [docs/BUILDING.md](docs/BUILDING.md).

## License / Лицензия
Released under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0). / Выпущено под лицензией AGPL-3.0.
