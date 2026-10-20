# ADA Messenger

<p align="center">
  <img src="android-app/desktopApp/src/desktopMain/resources/ada_icon.ico" alt="ADA Logo" width="112" height="112">
</p>

<p align="center">
  <strong>Децентрализованный P2P-мессенджер нового поколения: постквантовое шифрование, защита от блокировок и нулевой сбор метаданных.</strong><br>
  <em>Next-generation decentralized P2P messenger: post-quantum encryption, anti-censorship camouflage, and zero metadata collection.</em>
</p>

<p align="center">
  <a href="https://github.com/vlouriet-lab/ada-messenger/releases"><img src="https://img.shields.io/github/v/release/vlouriet-lab/ada-messenger?include_prereleases&label=Release&color=blue&style=flat-square" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-green.svg?style=flat-square" alt="License: AGPL-3.0"></a>
  <img src="https://img.shields.io/badge/Post--Quantum-ML--KEM--768-purple.svg?style=flat-square" alt="ML-KEM-768">
  <img src="https://img.shields.io/badge/Transport-Iroh_QUIC_+_Gossip-orange.svg?style=flat-square" alt="Iroh QUIC">
  <img src="https://img.shields.io/badge/VoIP-WebRTC_+_Opus-red.svg?style=flat-square" alt="WebRTC">
  <img src="https://img.shields.io/badge/Android-API_26+-3DDC84.svg?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Windows-10_%2F_11-0078D4.svg?style=flat-square&logo=windows&logoColor=white" alt="Windows">
</p>

<p align="center">
  <a href="https://github.com/vlouriet-lab/ada-messenger/releases/latest">
    <img src="https://img.shields.io/badge/Android_APK-Скачать_v0.5.0-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Скачать APK">
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/vlouriet-lab/ada-messenger/releases/latest">
    <img src="https://img.shields.io/badge/Windows_Setup-Скачать_v0.5.0-0078D4?style=for-the-badge&logo=windows&logoColor=white" alt="Скачать Windows Setup">
  </a>
</p>

---

## 📖 О проекте / Overview

**ADA** создавался для обеспечения приватности и устойчивой связи в условиях экстремальной интернет-цензуры, сетевых шатдаунов и массовой слежки. В отличие от традиционных мессенджеров (Telegram, WhatsApp, Signal), в ADA:
* **Нет привязки к личности:** не запрашиваются номера телефонов, email, контакты телефонной книги или SMS.
* **Нет единого центра:** архитектура полностью бессерверная, пиры связываются напрямую через современный сетевой протокол **Iroh (QUIC)**.
* **Постквантовая защита:** переписка защищена от атак «сохрани сейчас — расшифруй на квантовом компьютере в будущем».
* **Работа в опасных сетях:** встроенные механизмы обхода DPI маскируют P2P-трафик под стандартный TLS 1.3 / HTTPS.

---

## 🏛️ Ключевые возможности и архитектура

```
┌────────────────────────────────────────────────────────────────────────┐
│                   ADA Client UI (Kotlin / Compose)                     │
│       Android (Jetpack Compose)    │    Windows Desktop (Compose KMP)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI / C ABI (ffi.rs)
┌───────────────────────────────────▼────────────────────────────────────┐
│                    ada-core (Rust Security Engine)                     │
│  • ML-KEM-768 (PQXDH) + SPQR Ratchet  • Argon2id (5-Color Pattern Auth)│
│  • Ed25519 Identities + TOFU Cards v4 • SQLCipher Encrypted DB + Wiping│
│  • iroh-gossip Overlay (100+ peers)   • DPI Morpher (TLS 1.3 Framing)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
       ┌────────────────────────────┼────────────────────────────┐
       ▼                            ▼                            ▼
  [ Iroh QUIC ]               [ iroh-gossip ]             [ WebRTC Media ]
 Direct 1:1 P2P Streams       Epidemic Overlay for       Direct Full-Mesh Calls
NAT Traversal / Fallback   Groups & Call Signaling        (Hard Limit: ≤ 6 peers)
```

### 1. 🔑 Авторизация без паспорта (5-Color Pattern Auth)
* Регистрация и вход выполняются графическим рисунком по сетке 8×8 с использованием **5 цветов**.
* Энтропия ключа составляет **85.9 бит** (в 3545 раз выше стойкость, чем у классических 3-цветных паттернов).
* Мастер-ключ формируется через стойкую функцию хэширования **Argon2id** (64 MiB, $t=3, p=1$). На серверах не хранится никаких учётных записей.

### 2. 🛡️ Постквантовая криптография (NIST FIPS 203 ML-KEM-768)
* **PQXDH:** гибридный обмен ключами сочетает классический X25519 с квантово-устойчивым алгоритмом **ML-KEM-768** (Kyber).
* **SPQR (Signal Post-Quantum Ratchet):** храповик Double Ratchet с регулярной ротацией постквантовых ключей предотвращает расшифровку записанного трафика будущими квантовыми компьютерами.
* Постквантовые ключи детерминированно выводятся из мастер-сида паттерна — пользователю не нужно запоминать дополнительные пароли.

### 3. 🌐 Масштабируемые группы на `iroh-gossip`
* Групповые чаты и распределение групповых ключей (Sender Keys) построены на оверлее **HyParView** (активное поддержание топологии) и **PlumTree** (эпидемическая рассылка).
* Группы масштабируются на **100+ участников** без $O(N)$ лавинной нагрузки на отправителя.
* **Слепые топики (Blind Topics):** тема вычисляется как `BLAKE3(group_id || "ada-gossip-topic-v1")`. Название и состав группы никогда не передаются в заголовках оверлея.

### 4. 📞 Прямой медиа-план звонков (WebRTC Full-Mesh)
* Аудио (Opus) и видео (VP8/H.264) передаются исключительно по прямым P2P-каналам WebRTC/QUIC — задержка составляет менее 150 мс без промежуточных релеев.
* Протокольно и в интерфейсе зафиксирован **лимит до 6 участников** в групповом звонке — это предотвращает перегрев процессора и перегрузку мобильного канала.

### 5. 🕵️ Цифровой камуфляж против DPI (`DpiMorpher`)
* Для обхода систем глубокого анализа пакетов трафик упаковывается в заголовки **TLS 1.3 Application Data** (`0x17 0x03 0x03`).
* Применяется дискретный бакетный паддинг (512, 1024, 2048 байт) и HKDF-SHA256 маскирование потока — анализ энтропии и длин пакетов не позволяет отличить трафик ADA от обычного HTTPS-трафика.
* Поддерживаются резервные транспорты: WebSocket TLS, Domain Fronting, Meek и оффлайн-очереди (Mailbox).

### 6. 🚨 Экстренная защита устройства (Kill PIN)
* Локальная база данных полностью зашифрована на лету алгоритмом **SQLCipher** (AES-256-CBC).
* Ввод ложного **Kill PIN** мгновенно стирает криптографические ключи из защищённой памяти, делая базу данных безвозвратно недоступной даже в криминалистической лаборатории.

### 7. 📡 Автономный радио-меш (Zero-Infrastructure Off-Grid Radio Mesh)
* **BLE Coded PHY (Bluetooth 5.0+ Long Range S=8):** дальность связи **до 500–800 метров** со скоростью 125 кбит/с и помехоустойчивым кодированием.
* **L2CAP CoC (Connection-Oriented Channels):** прямые сокеты в обход накладных расходов GATT со стримингом на уровне ядра ОС.
* **Wi-Fi Aware (NAN P2P):** скоростная прямая передача файлов (до 50–100 Мбит/с) без отключения от домашнего роутера и без диалогов Group Owner.
* **Бинарный фрейминг `CompactFrame` (14 байт):** компактный заголовок, фрагментация под узкий MTU и сборка пакетов в произвольном порядке.
* **DTN Store-and-Forward:** накопление и передача отложенных зашифрованных сообщений через проходящие узлы-курьеры при полном блэкауте.
* **Desktop P2P LAN Mesh:** встроенный многоадресный движок (LAN Multicast `239.255.42.99:45824`) для взаимодействия Windows ПК со смартфонами.

---

## 📊 Сравнение криптографического стека

| Параметр | ADA Messenger | Signal | Telegram | Briar |
| :--- | :---: | :---: | :---: | :---: |
| **Привязка к номеру телефона** | ❌ **Нет (Графический ключ)** | ⚠️ Обязательна | ⚠️ Обязательна | ❌ Нет |
| **Постквантовый E2EE** | ✅ **NIST ML-KEM-768 + SPQR** | ✅ PQXDH (Kyber) | ❌ Нет | ❌ Нет |
| **P2P Архитектура (без серверов)** | ✅ **Iroh QUIC + Gossip** | ❌ Централизовано | ❌ Централизовано | ✅ Tor P2P |
| **Автономный радио-меш (Off-Grid)** | ✅ **BLE Coded PHY (800м) + Wi-Fi Aware NAN + DTN** | ❌ Нет | ❌ Нет | ⚠️ Только обычный Bluetooth (до 40м) |
| **Групповой оверлей** | ✅ **iroh-gossip (100+ пиров)** | ❌ Серверный фан-аут | ❌ Серверные группы | ⚠️ Bramble (медленно) |
| **Звонки** | ✅ **Direct WebRTC (до 6)** | ✅ Серверный SFU | ⚠️ Только 1:1 в Secret | ❌ Нет звонков |
| **Анти-DPI камуфляж** | ✅ **DpiMorpher (TLS 1.3) + WS** | ⚠️ Domain Fronting | ⚠️ MTProto Proxy | ⚠️ Tor bridges |
| **Аварийный сброс (Kill PIN)** | ✅ **SQLCipher + Zeroize** | ❌ Нет | ❌ Нет | ❌ Нет |

---

## 📁 Структура репозитория

```
ada-messenger/
├── ada-core/               # Ядро на Rust: криптография, P2P сеть, FFI, хранилище
│   ├── src/
│   │   ├── crypto/         # X3DH, PQXDH, ML-KEM-768, Double Ratchet, Sender Keys
│   │   ├── network/        # Iroh QUIC транспорт, iroh-gossip, DPI morpher
│   │   ├── bridge/         # Мосты обхода блокировок, Mailbox, манифесты
│   │   ├── media/          # WebRTC сигналинг и управление медиапотоками
│   │   ├── storage/        # SQLCipher локальное зашифрованное хранилище
│   │   ├── ffi.rs          # C ABI интерфейс для мобильных и десктопных платформ
│   │   └── api.rs          # Высокоуровневый API ядра ADA
├── android-app/            # Клиентские приложения
│   ├── app/                # Android-приложение на Jetpack Compose
│   └── desktopApp/         # Windows Desktop приложение на Compose Multiplatform
├── cf-workers/             # Серверлесс-мосты для Cloudflare (Durable Objects SQLite)
├── docs/                   # Техническая документация и архитектурные спецификации
│   ├── architecture/       # Детальные описания криптографии, gossip и DPI
│   ├── audits/             # Отчёты о внутреннем аудите безопасности
│   ├── BUILDING.md         # Руководство по компиляции из исходников
│   └── INSTALL.md          # Инструкция по установке
└── scripts/                # Скрипты локальной сборки, подписи и релизов
```

---

## 🛠️ Сборка из исходников / Building

Подробные инструкции по установке зависимостей и компиляции описаны в [docs/BUILDING.md](docs/BUILDING.md).

### Сборка Android APK:
```bash
cd android-app
./gradlew :app:assembleQuasiRelease
```

### Сборка Windows Desktop инсталлятора:
```powershell
.\build-installer-windows.ps1
```

### Запуск тестов ядра Rust:
```bash
cd ada-core
cargo test --no-default-features --features mobile-dev
```

---

## 🔒 Безопасность и раскрытие уязвимостей

Безопасность является фундаментальным приоритетом проекта. Если вы обнаружили уязвимость:
* Пожалуйста, **не создавайте публичные issues**.
* Воспользуйтесь приватной формой [GitHub Security Advisory](https://github.com/vlouriet-lab/ada-messenger/security/advisories/new).
* Подробные правила и область раскрытия описаны в [SECURITY.md](SECURITY.md).

---

## 📄 Лицензия / License

Проект ADA Messenger распространяется под лицензией **GNU Affero General Public License v3.0 (AGPL-3.0)**.  
Исходный код ядра, клиентских приложений и сопутствующих сервисов полностью открыт. См. файл [LICENSE](LICENSE).
