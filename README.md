# ADA Messenger

<p align="center">
  <img src="android-app/desktopApp/src/desktopMain/resources/ada_icon.ico" alt="ADA Logo" width="112" height="112">
</p>

<p align="center">
  <strong>Децентрализованный P2P-мессенджер: постквантовое сквозное шифрование, маскировка трафика и автономная радио-меш связь.</strong><br>
  <em>Decentralized P2P messenger: post-quantum E2EE, anti-censorship camouflage, and zero-infrastructure radio mesh networking.</em>
</p>

<p align="center">
  <a href="https://github.com/vlouriet-lab/ada-messenger/releases"><img src="https://img.shields.io/github/v/release/vlouriet-lab/ada-messenger?include_prereleases&label=Release&color=blue&style=flat-square" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-green.svg?style=flat-square" alt="License: AGPL-3.0"></a>
  <img src="https://img.shields.io/badge/Post--Quantum-ML--KEM--768-purple.svg?style=flat-square" alt="ML-KEM-768">
  <img src="https://img.shields.io/badge/Transport-Iroh_QUIC_+_Gossip-orange.svg?style=flat-square" alt="Iroh QUIC">
  <img src="https://img.shields.io/badge/Local_Network-LAN_+_Mesh_Beta-blue.svg?style=flat-square" alt="Local Network">
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

**ADA Messenger** разрабатывается как инструмент приватного прямого общения в условиях интернет-цензуры, сетевых ограничений и отсутствия централизованной инфраструктуры. 

Основные архитектурные принципы:
* **Без привязки к личности:** не используются номера телефонов, адреса электронной почты, SMS-верификация или централизованные телефонные книги.
* **Бессерверная P2P-связь:** обмен сообщениями 1:1 осуществляется напрямую между устройствами по сетевому протоколу **Iroh (QUIC)**.
* **Постквантовое сквозное шифрование:** защита от ретроактивной расшифровки записанного трафика («harvest now, decrypt later») будущими квантовыми компьютерами (NIST FIPS 203 ML-KEM-768).
* **Работа в неблагоприятных сетях:** встроенный полиморфный обфускатор **DpiMorpher** маскирует P2P-трафик под структуру записей TLS 1.3 и квантует размеры пакетов для защиты от пассивного сигнатурного анализа; при жесткой цензуре предусмотрен резервный мост **Cloudflare Worker Bridge** с доверенным TLS-сертификатом.
* **Локальная связь (LAN & Mesh):** обмен сообщениями в локальной сети (LAN Multicast) и прямая передача данных без интернета (Local Mesh на стадии тестирования).

---

## 🏛️ Архитектура системы

```
┌────────────────────────────────────────────────────────────────────────┐
│                   ADA Client UI (Kotlin / Compose)                     │
│       Android (Jetpack Compose)    │    Windows Desktop (Compose KMP)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI / C ABI (ffi.rs)
┌───────────────────────────────────▼────────────────────────────────────┐
│                    ada-core (Rust Security Engine)                     │
│  • ML-KEM-768 (PQXDH) + SPQR Ratchet  • Argon2id (5-Color Pattern Auth)│
│  • Ed25519 Identities + TOFU Cards v4 • SQLCipher Encrypted Storage    │
│  • iroh-gossip Overlay (Groups)       • Anti-Deanon IP Shield          │
│  • DPI Morpher (Polymorphic Framing)  • Cloudflare Serverless Bridge   │
│  • Mailbox Store-and-Forward Queue    • Desktop LAN Multicast P2P      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
       ┌────────────────────────────┼────────────────────────────┐
       ▼                            ▼                            ▼
  [ Iroh QUIC ]               [ iroh-gossip ]           [ Local Network / Mesh ]
 Direct 1:1 P2P Streams     Epidemic Overlay for        UDP LAN Multicast,
NAT Traversal / Relays       Group Messaging            Local Mesh (Beta)
```

---

## 🔑 Ключевые компоненты и особенности реализации

### 1. Вход и генерация ключей (5-Color Pattern Auth & ADPV PIN Vault)
* Регистрация и аутентификация выполняются расстановкой 16 кубиков пяти цветов на сетке 8×8 (64 позиции).
* **Обязательное требование сложности узора:** при регистрации новые пользователи обязаны использовать **как минимум 3 разных цвета** из 5 доступных (`MIN_REGISTRATION_DISTINCT_COLORS = 3`). Узоры из 1 или 2 цветов отклоняются интерфейсом с предупреждением и блокируются ядром (`PatternKey::validate_registration`).
* **Кнопка генерации случайного узора:** для исключения человеческого фактора (склонности людей выбирать предсказуемые симметричные формы или простые линии) в интерфейс регистрации добавлена кнопка генерации криптографически случайного узора со всеми 5 цветами (гарантирует полную теоретическую энтропию **$7.45 \times 10^{25}$ комбинаций ($\approx 85.9$ бит)**).
* Мастер-сид вычисляется через функцию **Argon2id** ($m=64$ МиБ, $t=3, p=1$) с 32-байтной уникальной солью (`ada_identity.salt`).
* **Быстрый доступ по PIN-коду (ADPV Vault):** для удобства PIN-код изолирован в криптографическом модуле `ada-core::crypto::pin_vault`. PIN связывается через Argon2id и HKDF-SHA256 с 256-битным протокольным якорем устройства (`protocol_anchor`). На Android якорь сохраняется в `EncryptedSharedPreferences` (Android Keystore TEE); на Windows Desktop файл якоря лежит в каталоге приложения. Подробнее о границах стойкости и компромиссах см. [AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md](docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md).

### 2. Постквантовая криптография (NIST FIPS 203 ML-KEM-768)
* **PQXDH:** гибридный протокол согласования ключей объединяет эллиптическую криптографию X25519 и квантово-стойкий алгоритм **ML-KEM-768** (Kyber).
* **SPQR (Signal Post-Quantum Ratchet):** двухсторонний храповик Double Ratchet с регулярной ротацией квантовых ключей внутри диалога.
* Все ключи выводятся детерминированно из мастер-сида графического ключа.

### 3. Групповые чаты на `iroh-gossip`
* Групповые чаты и доставка групповых ключей (Sender Keys) работают на эпидемическом оверлее **HyParView** (поддержание случайного графа связности) и **PlumTree** (оптимальное дерево рассылки) без лавинной нагрузки на отправителя.
* **Слепые топики (Blind Topics):** идентификатор топика хешируется как `BLAKE3(group_id || "ada-gossip-topic-v1")`. Названия групп и открытые идентификаторы не передаются в заголовках оверлея.

### 4. Прямые аудио- и видеозвонки (WebRTC P2P)
* Аудио (Opus) и видео (VP8) передаются по прямому P2P-каналу WebRTC через QUIC/UDP (реализовано в Android-клиенте).
* В групповых звонках действует протокольное ограничение **до 6 участников** в режиме Full-Mesh (находится в экспериментальном статусе). В десктопной версии совершение звонков в текущей версии не поддерживается.

### 5. Обход цензуры и маскировка трафика: DpiMorpher и Cloudflare Bridge
* **1-й эшелон (P2P и реле): Полиморфный `DpiMorpher` (`ada-core/src/network/dpi.rs`)**
  * Для повседневной P2P-связи без серверов каждый пакет инкапсулируется в структуру записей **TLS 1.3 Application Data** (`0x17 0x03 0x03`).
  * **Потоковое маскирование:** длина и тело полезной нагрузки маскируются через XOR ключевым потоком **HKDF-SHA256** со свежей 16-байтной солью (`OsRng`). В сети отсутствуют константные сигнатуры протокола ADA, а энтропия потока максимальна ($\approx 7.99$ бит/байт).
  * **Дискретные корзины длин (Bucket Padding):** размеры пакетов выравниваются по фиксированным границам (512, 1024, 2048, 4096 байт) с заполнением шумом для защиты от статистического анализа.
* **2-й эшелон (Жесткая цензура): Cloudflare Worker Bridge (`cf-workers/serverless-bridge-worker`)**
  * Резервный мост WebSocket over TLS (`wss://`) и HTTPS Mailbox к пограничным серверам Cloudflare CDN с доверенным TLS 1.3 сертификатом на случай блокировок UDP/QUIC. Мессенджер работает автономно и не требует развертывания воркеров при доступности прямого P2P/реле.

### 6. Локальное хранение и компромисс безопасности PIN-кода
* Локальная база данных зашифрована алгоритмом **SQLCipher** (AES-256-CBC). Дополнительно таблица состояний сессий зашифрована вторым слоем (XChaCha20-Poly1305).
* **Режим «только паттерн» (Zero-Secret-At-Rest):** секрет графического ключа **не сохраняется на накопителе**. При закрытии приложения ключи стираются из памяти (`zeroize`). При физическом изъятии выключенного устройства расшифровать базу без знания оригинального паттерна невозможно.
* **Режим быстрого доступа по PIN-коду (Security Trade-off):**
  * Для удобства пользователь может включить разблокировку по PIN-коду (от 4 знаков). При этом секрет паттерна запечатывается в контейнер `ADPV` (Argon2id + 256-битный `protocol_anchor` + AES-256-GCM).
  * На Android якорь сохраняется в `EncryptedSharedPreferences` под защитой аппаратного модуля **Android Keystore** (TEE / StrongBox).
  * На Windows Desktop аппаратный Keystore отсутствует; файл якоря хранится на диске рядом с контейнером PIN.
  * > ⚠️ **Честное предупреждение о физическом изъятии и Desktop:**  
    > Если устройство подверглось аппаратному считыванию чипа памяти с компрометацией Keystore или используется на Windows Desktop (где файл якоря лежит на диске), злоумышленник с копией файлов считывает якорь.  
    > В этом случае **стойкость всей базы данных снижается до стойкости выбранного PIN** (4-значный PIN перебирается за считанные секунды/минуты через 10 000 вычислений Argon2id). Для бескомпромиссной защиты от физического изъятия PIN следует выключать и использовать только графический паттерн.
* **Kill PIN:** ввод специального тревожного PIN-кода на экране разблокировки немедленно уничтожает ключи в памяти и удаляет локальные базы данных и конфигурации.
* Подробный математический анализ и векторы атак описаны в [docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md](docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md).

### 7. Локальная связь (LAN P2P и Local Mesh)
* **Desktop LAN P2P:** встроенный многоадресный транспорт (UDP Multicast `239.255.42.99:45824`) для прямого обнаружения и обмена сообщениями между ПК и мобильными устройствами в рамках одной локальной сети.
* **Локальный Mesh (Android, Beta):** экспериментальная поддержка прямой передачи сообщений через Bluetooth и Wi-Fi Direct без доступа к интернету (находится на стадии активного тестирования).

### 8. Защита от деанонимизации IP (Anti-Deanon IP Shield) и реле
* **Relay-Only по умолчанию:** все первичные соединения и переписка со всеми новыми/непроверенными контактами маршрутизируются исключительно через зашифрованные DERP-реле Iroh. Реальный IP-адрес надежно скрыт **от собеседника** (защита от деанонимизации и провокаций с целью выудить IP).
* **Инфраструктура реле:** по умолчанию трафик через интернет проходит через публичные DERP-реле Iroh, поддерживаемые компанией **n0 (Number 0 Inc.)**, либо через приватные узлы оператора/сообщества. Реле и локальный провайдер видят внешний IP-адрес клиента и время сессии (метаданные), но полезная нагрузка полностью защищена сквозным шифрованием (E2EE). Для сокрытия IP от реле и провайдера рекомендуется использовать VPN/WARP/Tor.
* **Блокировка прямых IP-прощупываний:** входящие прямые соединения от недоверенных пиров отклоняются ядром (`b"direct-p2p-requires-trusted-contact"`), а адреса прямых сокетов не передаются.
* **Trusted-Only прямое P2P:** прямое соединение (UDP hole-punching / WebRTC) активируется **только после явной очной верификации контакта** (числа безопасности Safety Numbers / карточка безопасности) для минимальной задержки при звонках и максимальной скорости передачи файлов.

---

## 🧪 Статус проекта, тестирование и аудит

### Независимый аудит безопасности
* **Статус: НЕ ПРОВОДИЛСЯ (Pending / Planned).**
* Проект **не проходил** коммерческий независимый аудит в специализированных компаниях (Trail of Bits, Cure53, NCC Group и др.).
* Документы в папке `docs/audits/` являются **внутренней архитектурной самопроверкой разработчиков**, а не сторонним сертификатом безопасности. Исходный код открыт для публичного аудита сообществом.

### Верификация и испытания
1. **Автоматизированные тесты:** **213 тестов** ядра Rust (`ada-core`), включая 193 модульных теста и 20 интеграционных сценариев для моделирования сбоев сети и атак повтора.
2. **Статический анализ безопасности:** автоматическое сканирование **GitHub CodeQL** (правила CWE-312, CWE-321, CWE-798; 0 открытых предупреждений).
3. **Аппаратные стендовые испытания:** сборка, запуск и стабильность интерфейса проверены на физическом смартфоне Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Android 14, One UI 6.1), Windows 11 x86_64 и эмуляторах Android (API 26–34).
4. **Полевые испытания:** базовые сценарии локального меша и аварийного восстановления сессий проходят регулярное тестирование в различных типах сетей.

---

## 📊 Сравнение технических характеристик

| Характеристика | ADA Messenger | Signal | Telegram | Briar |
| :--- | :---: | :---: | :---: | :---: |
| **Регистрация без номера телефона** | **Да (Графический ключ)** | Нет (SMS / номер) | Нет (SMS / номер) | Да (Логин / пароль) |
| **Постквантовое шифрование (PQ E2EE)** | **ML-KEM-768 + SPQR** | PQXDH (Kyber-1024) | Нет | Нет |
| **Архитектура доставки сообщений** | **Iroh QUIC / Gossip (DERP-реле n0 или свои)** | Централизованный сервер | Централизованный сервер | P2P через Tor |
| **Связь без интернета (Local / Off-grid)** | **LAN Multicast / Local Mesh (Beta)** | Нет | Нет | Bluetooth (Classic/LE, до 30-40 м) |
| **Скрытие IP-адреса собеседников** | **Да (от собеседника: Relay-Only по умолч.; для сокрытия от реле/ISP нужен VPN)** | Да *(скрыт сервером Signal)* | Да *(скрыт сервером Telegram)* | Да *(скрыт оверлеем Tor)* |
| **Маскировка трафика (Anti-DPI)** | **DpiMorpher (TLS Framing + Bucket Padding) + Cloudflare TLS Bridge** | Ограниченно (Domain Fronting) | Ограниченно (MTProto Proxy) | Мосты Tor (obfs4) |
| **Звонки** | **WebRTC 1:1 (Android)** | WebRTC через сервер SFU | WebRTC через серверные релеи | Нет звонков |
| **Аварийный сброс данных (Kill PIN)** | **Да (стирание БД + zeroize)** | Нет | Нет | Да (Panic Button) |
| **Независимый внешний аудит** | ⚠️ **Не проводился (Planned)** | Да (многократный) | Частично (MTProto) | Да (Cure53) |

---

## 📁 Структура репозитория

```
ada-messenger/
├── ada-core/               # Ядро на Rust: криптография, P2P сеть, FFI, хранилище
│   ├── src/
│   │   ├── crypto/         # X3DH, PQXDH, ML-KEM-768, Double Ratchet, Sender Keys
│   │   ├── network/        # Iroh QUIC транспорт, iroh-gossip, mesh_radio, DPI morpher
│   │   ├── bridge/         # Мосты обхода блокировок, Mailbox, манифесты
│   │   ├── media/          # WebRTC сигналинг и управление медиапотоками
│   │   ├── storage/        # SQLCipher локальное зашифрованное хранилище
│   │   ├── ffi.rs          # C ABI интерфейс для мобильных и десктопных платформ
│   │   └── api.rs          # Высокоуровневый API ядра ADA
├── android-app/            # Клиентские приложения
│   ├── app/                # Android-приложение на Jetpack Compose (BLE, Wi-Fi Aware, Keystore)
│   └── desktopApp/         # Windows Desktop приложение на Compose Multiplatform (LAN Multicast)
├── cf-workers/             # Серверлесс-мосты для Cloudflare (Durable Objects SQLite)
├── docs/                   # Архитектурная документация и спецификации
│   ├── architecture/       # Модель угроз, криптография, радио-меш, gossip и DPI
│   ├── audits/             # Отчёты о внутреннем аудите разработчиков
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

Если вы обнаружили потенциальную уязвимость в коде или архитектуре:
* Пожалуйста, **не создавайте публичные issues**.
* Воспользуйтесь приватной формой [GitHub Security Advisory](https://github.com/vlouriet-lab/ada-messenger/security/advisories/new).
* Подробные правила и область раскрытия описаны в [SECURITY.md](SECURITY.md).

---

## 📄 Лицензия / License

Проект ADA Messenger распространяется под лицензией **GNU Affero General Public License v3.0 (AGPL-3.0)**.  
Исходный код ядра, клиентских приложений и сопутствующих сервисов полностью открыт. См. файл [LICENSE](LICENSE).
