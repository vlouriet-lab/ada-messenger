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
  <img src="https://img.shields.io/badge/Radio_Mesh-BLE_Coded_+_Wi--Fi_NAN-blue.svg?style=flat-square" alt="Radio Mesh">
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
* **Постквантовое сквозное шифрование:** защита от ретроактивной расшифровки записанного трафика («harvest now, decrypt later») будущими квантовыми компьютерами.
* **Работа в неблагоприятных сетях:** встроенный модуль маскировки трафика обходит блокировки DPI, имитируя стандартный TLS 1.3 / HTTPS.
* **Автономная радиосвязь (Off-Grid):** обмен сообщениями на физическом уровне (Bluetooth Long Range, Wi-Fi Aware NAN, LAN Multicast) без сотовой связи, интернета и роутеров.

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
│  • iroh-gossip Overlay (100+ peers)   • CompactFrame Radio Mesh Framing│
│  • DPI Morpher (TLS 1.3 Framing)      • DTN Store-and-Forward Queue    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
       ┌────────────────────────────┼────────────────────────────┐
       ▼                            ▼                            ▼
  [ Iroh QUIC ]               [ iroh-gossip ]           [ Off-Grid Radio Mesh ]
 Direct 1:1 P2P Streams     Epidemic Overlay for       BLE Coded PHY, L2CAP CoC,
NAT Traversal / Relays     Groups & Call Signaling     Wi-Fi Aware NAN, LAN P2P
```

---

## 🔑 Ключевые компоненты и особенности реализации

### 1. Вход и генерация ключей (5-Color Pattern Auth & ADPV PIN Vault)
* Регистрация и аутентификация выполняются расстановкой 16 кубиков пяти цветов на сетке 8×8 (64 позиции).
* **Обязательное требование сложности узора:** при регистрации новые пользователи обязаны использовать **как минимум 3 разных цвета** из 5 доступных (`MIN_REGISTRATION_DISTINCT_COLORS = 3`). Узоры из 1 или 2 цветов отклоняются интерфейсом с предупреждением и блокируются ядром (`PatternKey::validate_registration`).
* Теоретическое пространство ключа составляет **$7.45 \times 10^{25}$ комбинаций ($\approx 85.9$ бит энтропии)**.
* Мастер-сид вычисляется через функцию **Argon2id** ($m=64$ МиБ, $t=3, p=1$) с 32-байтной уникальной солью (`ada_identity.salt`).
* **Непрошибаемый протокольный модуль PIN (ADPV):** для быстрого доступа PIN-код изолирован в криптографическом модуле `ada-core::crypto::pin_vault`. PIN связывается через Argon2id и HKDF-SHA256 с 256-битным аппаратным протокольным якорем устройства (`protocol_anchor`). Вскрытие PIN оффлайн-брутфорсом невозможно без предварительной полной расшифровки протокольного ключа устройства ($2^{256}$ комбинаций). Подробнее см. [AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md](docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md).


### 2. Постквантовая криптография (NIST FIPS 203 ML-KEM-768)
* **PQXDH:** гибридный протокол согласования ключей объединяет эллиптическую криптографию X25519 и квантово-стойкий алгоритм **ML-KEM-768** (Kyber).
* **SPQR (Signal Post-Quantum Ratchet):** двухсторонний храповик Double Ratchet с регулярной ротацией квантовых ключей внутри диалога.
* Все ключи выводятся детерминированно из мастер-сида графического ключа.

### 3. Масштабируемые группы на `iroh-gossip`
* Групповые чаты и доставка групповых ключей (Sender Keys) работают на эпидемическом оверлее **HyParView** (поддержание случайного графа связности) и **PlumTree** (оптимальное дерево рассылки).
* Группы масштабируются до **100+ участников** без лавинной нагрузки на отправителя ($O(\log N)$ вместо $O(N)$).
* **Слепые топики (Blind Topics):** идентификатор топика хешируется как `BLAKE3(group_id || "ada-gossip-topic-v1")`. Названия групп и открытые идентификаторы не передаются в заголовках оверлея.

### 4. Прямой медиа-план звонков (WebRTC Full-Mesh)
* Аудио (Opus) и видео (VP8/H.264) передаются по прямым P2P-каналам WebRTC через QUIC/UDP.
* В групповых звонках действует протокольное ограничение: **до 6 участников** в одном звонке. Это осознанный инженерный лимит для снижения нагрузки на процессор и аккумулятор мобильного устройства в топологии Full-Mesh без промежуточного сервера-микшера (SFU).

### 5. Модуль обхода DPI (`DpiMorpher`)
* Для обхода систем глубокого анализа пакетов P2P-трафик инкапсулируется в заголовки **TLS 1.3 Application Data** (`0x17 0x03 0x03`).
* Используется дискретный блочный паддинг (512, 1024, 2048 байт) и HKDF-маскирование потока для нейтрализации статистического анализа длин и энтропии пакетов.
* Поддерживаются резервные транспорты: WebSocket TLS, Domain Fronting, асинхронный Mailbox.

### 6. Локальное хранение и компромисс безопасности PIN-кода
* Локальная база данных зашифрована алгоритмом **SQLCipher** (AES-256-CBC). Дополнительно таблица состояний сессий зашифрована вторым слоем (XChaCha20-Poly1305).
* **Режим «только паттерн» (Zero-Secret-At-Rest):** секрет графического ключа **не сохраняется на накопителе**. При закрытии приложения ключи стираются из памяти (`zeroize`). При физическом изъятии выключенного устройства расшифровать базу без знания оригинального паттерна невозможно.
* **Режим быстрого доступа по PIN-коду (Security Trade-off):**
  * Для удобства пользователь может включить разблокировку по PIN-коду (от 4 знаков). При этом секрет паттерна шифруется ключом из PIN через PBKDF2-HMAC-SHA256 (600 000 итераций) и сохраняется в защищённом хранилище приложения (`EncryptedSharedPreferences`).
  * На Android хранилище защищено аппаратным модулем **Android Keystore** (TEE / StrongBox).
  * > ⚠️ **Важное предупреждение о физическом изъятии и Desktop:**  
    > Если устройство подверглось низкоуровневому аппаратному считыванию (дамп памяти флеш-чипа / уязвимость загрузчика) или используется на Windows Desktop (где нет аппаратного TEE модуля Android Keystore), злоумышленник с копией файлов может атаковать зашифрованный контейнер PIN-кода.  
    > В этом случае **стойкость всей базы данных снижается до стойкости выбранного PIN** (4-значный PIN перебирается за считанные минуты). Для бескомпромиссной защиты от физического изъятия PIN следует выключать.
* **Kill PIN:** ввод специального тревожного PIN-кода на экране разблокировки немедленно уничтожает ключи в памяти и удаляет локальные базы данных и конфигурации.
* Подробный математический анализ и векторы атак описаны в [docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md](docs/architecture/AUTHENTICATION_AND_STORAGE_THREAT_MODEL.md).

### 7. Автономный радио-меш (Off-Grid Radio Mesh)
* **BLE Coded PHY (Bluetooth 5.0+ Long Range S=8):** радиосвязь с помехоустойчивым прямым исправлением ошибок (FEC) на дистанциях до 500–800 м при прямой видимости.
* **L2CAP CoC (Connection-Oriented Channels):** прямые сокеты на уровне ядра ОС в обход накладных расходов GATT.
* **Wi-Fi Aware (NAN P2P):** прямое высокоскоростное соединение (до 50–100 Мбит/с) без роутера и без разрыва подключения к внешней сети.
* **Бинарный фрейминг `CompactFrame` (14 байт):** микрокадры с последовательной нумерацией, фрагментацией под узкий радио-MTU и подтверждением доставки (ACK).
* **DTN Store-and-Forward:** очередь накопления и транзитной передачи отложенных сообщений при отсутствии прямого маршрута.
* **Desktop LAN P2P:** встроенный многоадресный движок (UDP Multicast `239.255.42.99:45824`) для взаимодействия Windows ПК с мобильными клиентами в локальной сети.

---

## 🧪 Статус проекта, тестирование и аудит

### Независимый аудит безопасности
* **Статус: НЕ ПРОВОДИЛСЯ (Pending / Planned).**
* Проект **не проходил** коммерческий независимый аудит в специализированных компаниях (Trail of Bits, Cure53, NCC Group и др.).
* Документы в папке `docs/audits/` являются **внутренней архитектурной самопроверкой разработчиков**, а не сторонним сертификатом безопасности. Исходный код открыт для публичного аудита сообществом.

### Верификация и испытания
1. **Автоматизированные тесты:** **186 модульных тестов** ядра Rust (`ada-core`) и 20 интеграционных сценариев для моделирования сбоев сети и атак повтора.
2. **Статический анализ безопасности:** автоматическое сканирование **GitHub CodeQL** (правила CWE-312, CWE-321, CWE-798; 0 открытых предупреждений).
3. **Аппаратные стендовые испытания:** сборка, запуск и стабильность интерфейса проверены на физическом смартфоне Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Android 14, One UI 6.1, BLE 5.3 / Wi-Fi 7), Windows 11 x86_64 и эмуляторах Android (API 26–34).
4. **Полевые испытания радиосвязи:** полномасштабное тестирование дальности BLE Long Range и Wi-Fi Aware NAN в условиях плотной городской радиообстановки находится на стадии бета-тестирования.

---

## 📊 Сравнение технических характеристик

| Характеристика | ADA Messenger | Signal | Telegram | Briar |
| :--- | :---: | :---: | :---: | :---: |
| **Регистрация без номера телефона** | **Да (Графический ключ)** | Нет (SMS / номер) | Нет (SMS / номер) | Да (Логин / пароль) |
| **Постквантовое шифрование (PQ E2EE)** | **ML-KEM-768 + SPQR** | PQXDH (Kyber-1024) | Нет | Нет |
| **Архитектура доставки сообщений** | **P2P (Iroh QUIC / Gossip)** | Централизованный сервер | Централизованный сервер | P2P через Tor |
| **Автономный радио-меш (без интернета)** | **BLE Long Range + Wi-Fi Aware** | Нет | Нет | Bluetooth (Classic/LE, до 30-40 м) |
| **Скрытие IP-адреса собеседников** | ⚠️ **Только через Relay/Мосты** *(в прямом P2P IP открыт пиру)* | Да *(скрыт сервером Signal)* | Да *(скрыт сервером Telegram)* | Да *(скрыт оверлеем Tor)* |
| **Маскировка трафика (Anti-DPI)** | **TLS 1.3 Framing + Padding** | Ограниченно (Domain Fronting) | Ограниченно (MTProto Proxy) | Мосты Tor (obfs4) |
| **Звонки** | **WebRTC P2P (до 6 чел.)** | WebRTC через сервер SFU | WebRTC через серверные релеи | Нет звонков |
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
