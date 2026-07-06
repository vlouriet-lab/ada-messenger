# ADA Messenger — Дорожная карта: Windows-клиент

> Создано: 26 мая 2026  
> Статус: **ПЛАНИРОВАНИЕ**

---

## Обоснование и приоритет

ADA уже имеет зрелое Rust-ядро (`ada-core`) с криптографией, сетью, хранилищем и C FFI.  
Android-клиент производственно стабилен. Windows — следующий приоритетный рынок:  

- Активисты, журналисты и НКО часто работают на Windows-ноутбуках в условиях ограниченного интернета  
- Desktop — основная рабочая среда для координации под цензурой  
- iroh/QUIC уже компилируется под `x86_64-pc-windows-msvc` без изменений  
- `ada-core` уже содержит `ada-node` бинарник с полноценным CLI — готовый backbone  

---

## Архитектурные решения

### Выбор UI-фреймворка: Tauri 2.0

Рассмотренные варианты:

| Фреймворк | Плюсы | Минусы | Оценка |
|-----------|-------|--------|--------|
| **Tauri 2.0** | Rust-нативный backend; WebView2 UI; малый размер (3–6 МБ); нативный трей, уведомления, DPAPI | WebView2 — системная зависимость (Win10+) | ✅ Выбран |
| egui/iced | Чистый Rust; работает офлайн без WebView | Требует ручной реализации каждого UI-элемента; эстетика 2010х | ⚠️ Резервный |
| Flutter | Красивый UI; кросс-платформа | Dart-рантайм; большой бинарник; нет нативного Rust-FFI | ❌ |
| WinUI 3 / C# | Лучший Windows Look&Feel | Dart... нет — C#; долгая разработка; плохой FFI с Rust | ❌ |
| Electron | Зрелая экосистема | 200+ МБ; медленный запуск; нет нативного crypto isolation | ❌ |

**Итог:** Tauri 2.0 с React/TypeScript на фронте.  
Весь бизнес-логики остаётся в Rust: `ada-core` вызывается через Tauri команды (Invoke API),  
а не через внешний FFI в JS. WebView отвечает только за рендеринг.

### Архитектура процессов

```
┌─────────────────────────────────────────────────────┐
│                 ada-windows (процесс)               │
│                                                     │
│   ┌──────────────┐   Tauri Invoke   ┌────────────┐ │
│   │  WebView2    │ ◄─────────────── │  Tauri     │ │
│   │  (React UI)  │ ──────────────► │  Backend   │ │
│   └──────────────┘  (JSON/IPC)     │  (Rust)    │ │
│                                    │            │ │
│                                    │  ada-core  │ │
│                                    │  (в том же │ │
│                                    │  процессе) │ │
│                                    └────────────┘ │
│                                          │        │
│                      ┌───────────────────┼──────┐ │
│                      │ SQLite (messages) │      │ │
│                      │ iroh Endpoint     │      │ │
│                      │ Windows Notif.    │      │ │
│                      └───────────────────┴──────┘ │
└─────────────────────────────────────────────────────┘
         ▲
         │ System Tray (работает в фоне)
```

### Хранение данных на Windows

| Что | Где |
|-----|-----|
| `ada.db` (SQLite/зашифровано) | `%APPDATA%\ADA\data\` |
| `identity.json`, `config.json` | `%APPDATA%\ADA\` |
| Мастер-ключ базы | Windows DPAPI (через `windows-dpapi` crate) |
| Кэш передачи файлов | `%APPDATA%\ADA\transfers\` |
| Логи | `%LOCALAPPDATA%\ADA\logs\` |

---

## Структура нового компонента

```
windows-app/
├── src-tauri/                  — Rust Tauri backend
│   ├── Cargo.toml
│   ├── src/
│   │   ├── main.rs             — Tauri app entry, window setup
│   │   ├── commands/           — Tauri команды (IPC handlers)
│   │   │   ├── mod.rs
│   │   │   ├── identity.rs     — login, register, pattern auth
│   │   │   ├── messaging.rs    — send_message, get_messages, search
│   │   │   ├── contacts.rs     — add_contact, list_contacts
│   │   │   ├── groups.rs       — create/join/send group
│   │   │   ├── calls.rs        — initiate_call, answer, hangup
│   │   │   ├── transfers.rs    — send_file, accept_file
│   │   │   └── settings.rs     — config, obfuscation, profiles
│   │   ├── events.rs           — ADAEvent → Tauri emit (push к UI)
│   │   ├── storage.rs          — Windows DPAPI key wrap/unwrap
│   │   ├── notifications.rs    — Windows Action Center
│   │   ├── tray.rs             — System tray icon + меню
│   │   └── autostart.rs        — HKCU\Software\Microsoft\Windows\
│   │                             CurrentVersion\Run
│   └── tauri.conf.json
├── src/                        — React + TypeScript UI
│   ├── main.tsx
│   ├── App.tsx
│   ├── screens/
│   │   ├── LoginScreen.tsx
│   │   ├── ConversationList.tsx
│   │   ├── ChatScreen.tsx
│   │   ├── CallScreen.tsx
│   │   ├── ContactsScreen.tsx
│   │   ├── GroupScreen.tsx
│   │   └── SettingsScreen.tsx
│   ├── hooks/
│   │   ├── useAda.ts           — обёртка над invoke()
│   │   └── useEvents.ts        — listen() для ADAEvent
│   ├── components/
│   │   ├── MessageBubble.tsx
│   │   ├── ContactItem.tsx
│   │   ├── PatternGrid.tsx     — паттерн-ввод пароля (как на Android)
│   │   └── FileTransferBar.tsx
│   └── styles/
│       └── theme.css           — тёмная тема (идентично Android)
├── build/                      — скрипты сборки
│   ├── build-windows.ps1
│   └── sign-and-package.ps1    — signtool + MSIX упаковка
└── installers/
    ├── ada-setup.nsi           — NSIS installer (fallback)
    └── ada.wix.xml             — WiX MSI (основной)
```

---

## Фазы разработки

---

### Фаза W0 — Scaffold & Build Pipeline

> **Цель:** `cargo build` Tauri backend компилируется; пустое окно запускается.  
> **Оценка:** 1 неделя

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W0.1 | Инициализировать `windows-app/` через `cargo tauri init` | `windows-app/` | ⬜ |
| W0.2 | Добавить `ada-core` как path-зависимость в `src-tauri/Cargo.toml` | `Cargo.toml` | ⬜ |
| W0.3 | Отключить `jni-bindings` feature для Windows-сборки | `Cargo.toml` | ⬜ |
| W0.4 | Убедиться что `cargo build` ada-core проходит под `x86_64-pc-windows-msvc` | CI | ⬜ |
| W0.5 | Создать PowerShell build-script `build-windows.ps1` | `build/` | ⬜ |
| W0.6 | Настроить Tauri `tauri.conf.json`: app ID, иконка, окно без рамки (acrylic/mica) | config | ⬜ |
| W0.7 | Создать scaffolding React (Vite + TypeScript) в `src/` | `src/` | ⬜ |
| W0.8 | Верифицировать: `pnpm tauri dev` открывает пустое окно | — | ⬜ |

**Критические зависимости Cargo для Windows:**
```toml
[dependencies]
tauri = { version = "2", features = ["tray-icon", "notification"] }
ada-core = { path = "../../ada-core", default-features = false,
             features = ["proto", "bundled-sqlite"] }
windows = { version = "0.58", features = [
    "Win32_Security_Cryptography",   # DPAPI
    "Win32_System_Registry",         # autostart
] }
tokio = { version = "1", features = ["full"] }
```

---

### Фаза W1 — Идентификация и хранение ключей

> **Цель:** Пользователь может зарегистрироваться / войти. Ключ шифрует БД через DPAPI.  
> **Оценка:** 1–2 недели

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W1.1 | Реализовать `storage.rs`: `dpapi_protect()` / `dpapi_unprotect()` | `storage.rs` | ⬜ |
| W1.2 | При первом запуске генерировать DB-ключ → защитить DPAPI → сохранить | `storage.rs` | ⬜ |
| W1.3 | Команда `create_identity(display_name, pin)` — вызывает `ADACore::new()` | `commands/identity.rs` | ⬜ |
| W1.4 | Команда `login_with_pin(pin)` — unprotect DPAPI → open DB | `commands/identity.rs` | ⬜ |
| W1.5 | Команда `export_identity()` / `import_identity()` — для бэкапа | `commands/identity.rs` | ⬜ |
| W1.6 | PatternGrid компонент в React (10×10 или 6×6, идентично Android) | `components/PatternGrid.tsx` | ⬜ |
| W1.7 | LoginScreen — выбор: PIN или паттерн | `screens/LoginScreen.tsx` | ⬜ |
| W1.8 | Состояние `ADACore` в Tauri State (Arc\<Mutex\<Option\<ADACore\>\>>) | `main.rs` | ⬜ |

**Безопасность:**
- DPAPI привязывает ключ к Windows-аккаунту + машине (аналог Keystore на Android)
- Нет ни одной точки, где DB-ключ лежит в plaintext на диске
- После X неудачных попыток PIN → Kill Mode (тот же механизм, что `executeKillCode` на Android)

---

### Фаза W2 — ADAEvent → UI (Event Bus)

> **Цель:** Rust события реального времени передаются во фронт без polling.  
> **Оценка:** 3–5 дней

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W2.1 | Запустить event-loop в `events.rs`: `core.take_events()` → `app.emit()` | `events.rs` | ⬜ |
| W2.2 | Маппинг `ADAEvent` → JSON payload для Tauri emit | `events.rs` | ⬜ |
| W2.3 | `useEvents.ts` hook: `listen("ada_event", handler)` | `hooks/useEvents.ts` | ⬜ |
| W2.4 | Диспатч событий в React state: новые сообщения, статусы, звонки | `hooks/useAda.ts` | ⬜ |
| W2.5 | Тест: отправить тестовое событие из backend → увидеть в UI | — | ⬜ |

---

### Фаза W3 — Контакты и чат

> **Цель:** Полнофункциональный DM-чат: добавить контакт, отправить/получить сообщение.  
> **Оценка:** 2 недели

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W3.1 | Команда `add_contact(peer_id_hex, display_name)` | `commands/contacts.rs` | ⬜ |
| W3.2 | Команда `list_contacts()` → JSON массив | `commands/contacts.rs` | ⬜ |
| W3.3 | Команда `send_message(peer_id, text)` | `commands/messaging.rs` | ⬜ |
| W3.4 | Команда `get_messages(peer_id, limit, offset)` → JSON | `commands/messaging.rs` | ⬜ |
| W3.5 | Команда `search_messages(query)` → FTS5 | `commands/messaging.rs` | ⬜ |
| W3.6 | Команда `mark_read(conversation_id)` | `commands/messaging.rs` | ⬜ |
| W3.7 | ConversationList экран (список диалогов, бейдж непрочитанных) | `screens/ConversationList.tsx` | ⬜ |
| W3.8 | ChatScreen (пузыри сообщений, input bar, статусы delivered/read) | `screens/ChatScreen.tsx` | ⬜ |
| W3.9 | Drag-and-drop файлов в чат-окно → отправка файла | `screens/ChatScreen.tsx` | ⬜ |
| W3.10 | Emoji reactions (MessageKind::Reaction поддержан в ada-core) | `components/MessageBubble.tsx` | ⬜ |

---

### Фаза W4 — Системный трей и фоновая работа

> **Цель:** ADA работает в фоне (трей), принимает сообщения при закрытом окне.  
> **Оценка:** 1 неделя

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W4.1 | `tray.rs`: иконка в трее, меню (Открыть / Новое сообщение / Выйти) | `tray.rs` | ⬜ |
| W4.2 | Закрытие окна → `prevent_close` → скрыть в трей | `main.rs` | ⬜ |
| W4.3 | Клик на трей → показать/скрыть окно | `tray.rs` | ⬜ |
| W4.4 | `notifications.rs`: Windows Action Center через `windows-rs` | `notifications.rs` | ⬜ |
| W4.5 | Новое входящее сообщение → уведомление с именем и превью | `notifications.rs` | ⬜ |
| W4.6 | Клик на уведомление → открыть окно на нужном чате | `notifications.rs` | ⬜ |
| W4.7 | `autostart.rs`: запись в `HKCU\...\Run` (опционально, по настройке) | `autostart.rs` | ⬜ |
| W4.8 | Настройка: "Запускать с Windows" toggle в UI | `screens/SettingsScreen.tsx` | ⬜ |

---

### Фаза W5 — Звонки (Audio)

> **Цель:** Голосовые звонки peer-to-peer через iroh + opus.  
> **Оценка:** 2–3 недели

**Подход:** На Android WebRTC media нативный. На Windows — собственный audio pipeline:  
`cpal` (cross-platform audio) + `opus` (уже в Cargo.toml) + ICE через iroh.

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W5.1 | Добавить `cpal = "0.15"` в `src-tauri/Cargo.toml` | `Cargo.toml` | ⬜ |
| W5.2 | `src-tauri/src/audio.rs`: capture mic → opus encode | `audio.rs` | ⬜ |
| W5.3 | Отправка opus-фреймов через `ada-core` media channel | `audio.rs` | ⬜ |
| W5.4 | Приём opus-фреймов → decode → cpal playback | `audio.rs` | ⬜ |
| W5.5 | Команда `initiate_call(peer_id)` / `answer_call(call_id)` / `hangup(call_id)` | `commands/calls.rs` | ⬜ |
| W5.6 | ADAEvent::IncomingCall → Windows notification "Входящий звонок от X" | `notifications.rs` | ⬜ |
| W5.7 | CallScreen в React: принять / отклонить / завершить / mute | `screens/CallScreen.tsx` | ⬜ |
| W5.8 | Список аудио-устройств: microphone + speaker selector в настройках | `screens/SettingsScreen.tsx` | ⬜ |
| W5.9 | Push-to-talk режим (опционально для слабых каналов) | `commands/calls.rs` | ⬜ |

> **Видеозвонки (V2):** Видео на Windows потребует отдельного решения (libwebrtc или медиапайп).  
> Голос через cpal+opus — продакшн-реалистичная первая версия.

---

### Фаза W6 — Групповые чаты

> **Цель:** Создание/вступление в группы, отправка/получение зашифрованных групповых сообщений.  
> **Оценка:** 1 неделя (код в ada-core уже есть)

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W6.1 | Команда `create_group(name, member_peer_ids[])` | `commands/groups.rs` | ⬜ |
| W6.2 | Команда `join_group(invite_blob)` | `commands/groups.rs` | ⬜ |
| W6.3 | Команда `send_group_message(group_id, text)` | `commands/groups.rs` | ⬜ |
| W6.4 | Команда `list_group_members(group_id)` | `commands/groups.rs` | ⬜ |
| W6.5 | GroupScreen в React (идентично ChatScreen, с заголовком группы) | `screens/GroupScreen.tsx` | ⬜ |
| W6.6 | Приглашение: копировать invite-blob → передать вне ADA | `screens/GroupScreen.tsx` | ⬜ |

---

### Фаза W7 — Обход цензуры (Anti-Censorship)

> **Цель:** Все bridge-профили из ada-core доступны на Windows.  
> **Оценка:** 1 неделя

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W7.1 | Команда `set_connection_profile(profile)` (Direct/Tor/Bridge/RelayOnly) | `commands/settings.rs` | ⬜ |
| W7.2 | Команда `set_obfuscation_mode(mode)` | `commands/settings.rs` | ⬜ |
| W7.3 | Команда `add_relay_node(url)` / `set_relay_only(bool)` | `commands/settings.rs` | ⬜ |
| W7.4 | Команда `import_bridge_manifest(signed_json)` | `commands/settings.rs` | ⬜ |
| W7.5 | SettingsScreen: раздел "Сеть и цензуроустойчивость" | `screens/SettingsScreen.tsx` | ⬜ |
| W7.6 | Декодирование стегано-контейнеров (.png → invite) | `commands/settings.rs` | ⬜ |
| W7.7 | Импорт контакта через QR-код (через файл .png) | `screens/ContactsScreen.tsx` | ⬜ |

---

### Фаза W8 — Безопасность и физическая защита

> **Цель:** Kill PIN, Clean Mode, исчезающие сообщения — идентично Android.  
> **Оценка:** 1 неделя

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W8.1 | Kill PIN: команда `trigger_kill_mode()` → удаление БД + ключей | `commands/identity.rs` | ⬜ |
| W8.2 | Clean Mode (Decoy PIN) → пустой профиль | `commands/identity.rs` | ⬜ |
| W8.3 | Auto-wipe после N дней неактивности (configurable) | `main.rs` | ⬜ |
| W8.4 | Блокировка при сворачивании (lock-on-minimize, опция) | `main.rs` | ⬜ |
| W8.5 | Ephemeral messages: TTL-счётчик + команда `set_message_ttl(conv_id, secs)` | `commands/messaging.rs` | ⬜ |
| W8.6 | Защита окна: `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` → нет скриншотов | `main.rs` | ⬜ |
| W8.7 | Память: zeroize sensitive буферов после use (уже в ada-core через `zeroize` crate) | — | ✅ |

**Примечание к W8.6:** `WDA_EXCLUDEFROMCAPTURE` блокирует PrintScreen и OBS на Win10 2004+.  
Реализуется через `windows-rs`:  
```rust
unsafe { SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) };
```

---

### Фаза W9 — Упаковка и дистрибуция

> **Цель:** Готовый установщик. Распространение без магазина (прямая загрузка).  
> **Оценка:** 1 неделя

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| W9.1 | Иконки приложения (256×256, 128×128, 64×64, 32×32, 16×16 ICO) | `assets/` | ⬜ |
| W9.2 | NSIS installer: тихая установка, кастомные actions | `installers/ada-setup.nsi` | ⬜ |
| W9.3 | WiX MSI (опционально, для корпоративного развёртывания) | `installers/ada.wix.xml` | ⬜ |
| W9.4 | `sign-and-package.ps1`: signtool → .exe подписан | `build/` | ⬜ |
| W9.5 | Встроенный механизм обновлений (Tauri updater через signed JSON manifest) | `tauri.conf.json` | ⬜ |
| W9.6 | Self-update через зашифрованный канал (update manifest подписан) | `tauri.conf.json` | ⬜ |
| W9.7 | CI: GitHub Actions build matrix (windows-latest, x86_64) | `.github/workflows/` | ⬜ |
| W9.8 | Artефакт: `ADA-Setup-x.y.z-windows-x64.exe` | CI | ⬜ |

---

### Фаза W10 — Тестирование и QA

> **Цель:** Покрытие критических путей; интеграционные тесты.  
> **Оценка:** ongoing

| # | Задача | Описание | Статус |
|---|--------|---------|--------|
| W10.1 | Unit-тесты Tauri команд (mock ada-core) | `src-tauri/tests/` | ⬜ |
| W10.2 | E2E тест: два экземпляра ADA на одном PC обмениваются сообщениями | `tests/e2e_local.rs` | ⬜ |
| W10.3 | Тест Kill PIN: ключи и БД действительно уничтожены | `tests/security.rs` | ⬜ |
| W10.4 | Тест DPAPI: ключ не читается без аутентификации пользователя | `tests/security.rs` | ⬜ |
| W10.5 | Матрица совместимости: Windows 10 (21H2), Windows 11 (23H2) | QA | ⬜ |
| W10.6 | Проверка: нет утечки plaintext в Windows Event Log / minidump | QA | ⬜ |
| W10.7 | Проверка: нет plaintext в Windows prefetch и pagefile | QA | ⬜ |
| W10.8 | Совместимость с Android-пиром: cross-platform DM и звонки | QA | ⬜ |

---

## Временная шкала (оценка)

```
Неделя 1:   W0 — Scaffold & Build
Неделя 2:   W1 — Идентификация + DPAPI
Неделя 3:   W2 + W3 — Events + Контакты/Чат (начало)
Неделя 4:   W3 — Контакты/Чат (финал)
Неделя 5:   W4 — Трей + Уведомления
Недели 6–7: W5 — Голосовые звонки
Неделя 8:   W6 — Группы
Неделя 9:   W7 — Anti-censorship settings
Неделя 10:  W8 — Физическая безопасность
Недели 11:  W9 — Упаковка и дистрибуция
Ongoing:    W10 — QA
─────────────────────────────────────
Итого MVP:  ~11 недель до первого релизного билда
```

---

## Технический долг и риски

### Риски

| Риск | Вероятность | Митигация |
|------|------------|-----------|
| WebView2 не установлен на целевой системе | Низкая (Win10/11 имеют его) | NSIS: проверить и установить WebView2 Runtime |
| opus.dll зависимости на Windows | Средняя | Статическая линковка через `libopus-sys` с `vendored` feature |
| cpal + Windows WASAPI эксклюзивный режим | Средняя | Использовать shared mode; fallback на DirectSound |
| Tauri updater требует подписанный executable | Высокая | Нужен code signing certificate ($$$) или self-hosted update server без sig |
| Антивирусы флагируют self-written Rust-бинари | Средняя | Отправить на white-listing; использовать подпись |

### Технический долг

- **`android_logger`** не нужен на Windows: убедиться в `[features]` разделении
- **`jni`** crate уже optional — проверить что feature не тянется в Windows-сборку
- **`opus = "0.3.1"`**: на Windows требует opus lib; рассмотреть `audiopus` с vendored feature
- **`rusqlite/bundled`**: уже включён в `bundled-sqlite` feature — работает ✅

---

## Соображения по безопасности (специфика Windows)

1. **Memory isolation:** Windows не гарантирует очистку памяти после free. `zeroize` crate  
   уже применён в ada-core ко всем секретным структурам — этого достаточно.

2. **Pagefile:** Секретные буферы (ключи, расшифрованные сообщения) могут попасть в pagefile.sys.  
   Частичное решение: `VirtualLock()` для критических буферов.  
   Полное решение: рекомендовать пользователям шифровать диск (BitLocker).

3. **Dump-защита:** `SetProcessMitigationPolicy(ProcessDynamicCodePolicy)` + Heap-защита  
   через `HeapSetInformation(NULL, HeapEnableTerminationOnCorruption, ...)`.

4. **UAC:** Приложение не требует elevated privileges. NSIS installer — per-user install  
   (`HKCU` вместо `HKLM`). Нет driver, нет service.

5. **DLL Hijacking:** Статическая линковка где возможно. Для системных DLL — absolute paths.

6. **Windows Defender:** Добавить exclusion через NSIS installer (спорно).  
   Лучше: подписать сертификатом → auto-whitelist.

---

## Связь с существующим кодом

```
ada-core/src/ffi.rs        — НЕ используется напрямую (не C-FFI)
ada-core/src/api.rs        — ADACore::new(), start(), take_events() — ПРЯМОЙ вызов из Rust
ada-core/src/lib.rs        — pub use ADACore, ADAEvent, ADAConfig — ПРЯМОЙ импорт
ada-core/src/bin/main.rs   — показывает паттерн использования ADACore в standalone Rust
```

Windows-backend — это Rust-приложение, которое **импортирует `ada-core` как crate**,  
а не вызывает его через FFI. FFI нужен только для не-Rust хостов (Android JNI, iOS Swift).

---

## Следующий немедленный шаг

```powershell
# 1. Установить Tauri CLI
cargo install tauri-cli --version "^2.0"

# 2. Создать windows-app scaffold
mkdir windows-app
cd windows-app
cargo tauri init

# 3. Добавить ada-core как зависимость в src-tauri/Cargo.toml
# [dependencies]
# ada-core = { path = "../../ada-core", default-features = false, features = ["proto", "bundled-sqlite"] }

# 4. cargo build в src-tauri
cargo tauri build --debug
```
