# ADA Messenger — Дорожная карта реализации

> Обновлено: 14 мая 2026

---

## Архитектура

**ADA** — децентрализованный защищённый мессенджер с цензуроустойчивостью.

```
android-app/        — Kotlin/Compose Android приложение
ada-core/           — Ядро на Rust (компилируется как .so через JNI/FFI)
  src/
    crypto/         — X3DH, Double Ratchet, AES-GCM, ключи
    messaging/      — Сообщения, роутер, хранилище
    group/          — Групповые чаты (Sender Keys)
    media/          — WebRTC звонки
    transfer.rs     — Зашифрованная передача файлов
    bridge/         — Обход цензуры (obfs4, WS-TLS, domain fronting)
    network/        — iroh QUIC транспорт, relay, DPI
    storage/        — SQLite KV-хранилище
    ffi.rs          — C ABI для iOS/Android
    jni.rs          — JNI биндинги для Android
    api.rs          — Публичный API (ADACore, ADAEvent)
```

**Крипто-стек:** Ed25519 (identity) · X25519 (DH) · X3DH (key agreement) · Double Ratchet · AES-256-GCM · HKDF-SHA256  
**Сеть:** iroh 0.31 (единственный транспорт: QUIC + relay NAT traversal + pkarr DNS discovery) · группы через iroh unicast fan-out  
**Протокол:** Protocol Buffers (prost) для wire-форматов

---

## Фаза 0 — Исправление фундамента

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

### Задачи

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 0.1 | Добавить отсутствующие крейты в Cargo.toml | `Cargo.toml` | ✅ |
| 0.2 | Дополнить `ADAError` недостающими вариантами | `error.rs` | ✅ |
| 0.3 | Реализовать `Identity::export_secret()` / `import_secret()` | `identity.rs` | ✅ |
| 0.4 | Создать заглушки модулей `network/` | `network/` | ✅ |
| 0.5 | Создать заглушку `crypto/ratchet.rs` | `crypto/ratchet.rs` | ✅ |
| 0.6 | Исправить `mod.rs` зависимости | `storage/mod.rs` | ✅ |

**Добавленные крейты:**
- `base64 = "0.21"` ✅
- `parking_lot = "0.12"` ✅
- `blake3 = "1.5"` ✅
- `mime_guess = "2.0"` ✅
- `uuid = "1.6"` ✅
- `rusqlite` / SQLCipher — реальный persisted storage backend с миграциями, pagination/search и FTS; прежний pure in-memory MVP больше не является актуальным состоянием проекта

---

## Фаза 1 — Криптографический уровень

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

### Задачи

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 1.1 | Полная реализация X3DH (initiator + responder) | `crypto/x3dh.rs` | ✅ |
| 1.2 | Double Ratchet (KDF-chain + DH ratchet) | `crypto/ratchet.rs` | ✅ |
| 1.3 | Хранение пропущенных ключей (skipped message keys) | `crypto/ratchet.rs` | ✅ |
| 1.4 | Генерация/ротация SignedPreKey | `crypto/prekeys.rs` | ✅ |
| 1.5 | Генерация пачки OneTimePreKey (100+) | `crypto/prekeys.rs` | ✅ |
| 1.6 | Сериализация PreKey состояния | `crypto/prekeys.rs` | ✅ |

**Реализовано:**
- X3DH: `x3dh_send` + `x3dh_receive` с верификацией подписи SPK
- Double Ratchet: `RatchetState` с полным KDF-chain, DH ratchet, скипнутыми ключами (MAX_SKIP=1000)
- `PreKeyManager::signed_prekey_bundle()` — удобный метод для тестов и FFI
- `hkdf_derive`, `encrypt`/`decrypt` (AES-256-GCM) — готово

---

## Фаза 2b — Сетевой рефакторинг: iroh как единственный транспорт

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

### Мотивация

iroh даёт кратно лучший NAT traversal — relay-first + automatic hole punching, QUIC без overhead TCP/Yamux/Noise. Принято решение оставить только iroh.

### Архитектура (iroh-only)

```
iroh  ──→ unicast:   DM · звонки · передача файлов
      ──→ группы:    unicast fan-out (каждому участнику отдельно)
      ──→ discovery: pkarr DNS
      ──→ relay:     n0 публичные relay (порт 443)
```

### Удалённые файлы и зависимости
- `libp2p 0.53` — полностью удалён из Cargo.toml
- `network/node.rs` — libp2p Swarm, Gossipsub, Kademlia, mDNS (удалён)
- `network/dht.rs` — Kademlia DHT обёртка (удалён)
- `network/connection_pool.rs` — libp2p connection pool (удалён)
- Feature flag `iroh-transport` — удалён (iroh теперь всегда включён)
- Все `#[cfg(feature = "iroh-transport")]` guards — удалены

### Изменения в API
- `add_bootstrap_node()` → `add_relay_node()` (заглушка — n0 relay по умолчанию)
- `set_always_relay_dm()` → `set_relay_only()`
- `send_group_message()` — gossipsub broadcast → iroh unicast fan-out
- `handle_network_event()` — удалён (все сетевые события)
- `run_maintenance()` — удалены DHT publish и bootstrap re-dial

---

## Фаза 2 — Сетевой уровень

> **Статус: ✅ 100% — ЗАВЕРШЕНА (перенесена на iroh-only)**

### Задачи (исторические — реализованы через iroh)

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 2.1 | QUIC транспорт (iroh Endpoint + relay + hole-punching) | `network/iroh_transport.rs` | ✅ |
| 2.2 | pkarr DNS discovery | `network/iroh_transport.rs` | ✅ |
| 2.4 | Relay-узлы: хранение RelayEnvelope с TTL | `network/relay.rs` | ✅ |
| 2.5 | DPI-обфускация (padding, traffic shaping) | `network/dpi.rs` | ✅ |
| 2.6 | `ObfuscationMode` enum + транспортный враппер | `network/dpi.rs` | ✅ |
| 2.7 | Групповые сообщения через iroh unicast fan-out | `api.rs` | ✅ |

**Реализовано:** iroh 0.31 (QUIC, n0 relay, pkarr), DPI-padding, `ObfuscationMode`

**Маршрутизация:**
1. Local mesh (если peer доступен через BLE/Wi-Fi Direct и режим включён)
2. iroh live QUIC / n0 relay (direct unicast, pkarr DNS discovery)
3. Bridge live delivery (WebSocket TLS как основной production fallback; остальные bridge protocols проходят canary/field validation)
4. Mailbox bridge / local offline queue (store-and-forward, auto-retry)

`relay_only` отключает исходящий live iroh для unicast и оставляет bridge/mailbox/offline/local-mesh пути.

---

## Фаза 3 — Слой сообщений

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

### Задачи

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 3.1 | Session Manager: полный Double Ratchet вместо заглушки | `messaging/session.rs` | ✅ |
| 3.2 | Инициализация сессии через X3DH | `messaging/session.rs` | ✅ |
| 3.3 | Хранение RatchetState per-peer | `messaging/session.rs` | ✅ |
| 3.4 | Message Router: подключить к сетевой ноде (iroh) | `messaging/router.rs` | ✅ |
| 3.5 | Retry-очередь с экспоненциальным backoff | `messaging/router.rs` | ✅ |
| 3.6 | Message Store: SQLite/SQLCipher + FTS + pagination API | `messaging/store.rs` | ✅ |
| 3.7 | Список диалогов, счётчик непрочитанных, поиск | `messaging/store.rs` | ✅ |
| 3.8 | Пагинация истории сообщений (limit + offset) | `messaging/store.rs` | ✅ |

> ✅ SQLite/SQLCipher-бэкенд подключён как актуальное persisted storage; pure in-memory MVP остался только историческим этапом.

---

## Фаза 4 — Групповые чаты

> **Статус: ✅ код реализован; production readiness разделена по протоколам**

### Задачи

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 4.1 | Завершить Sender Keys: полный encrypt/decrypt с KDF-chain | `group/sender_keys.rs` | ✅ |
| 4.2 | SenderKeyDistribution при добавлении участника | `group/manager.rs` | ✅ |
| 4.3 | Rotate sender key при удалении участника | `group/manager.rs` | ✅ |
| 4.4 | Invite/join flow через прямые зашифрованные сообщения | `group/manager.rs` | ✅ |
| 4.5 | Синхронизация member list | `group/manager.rs` | ✅ |

**Реализовано:** `create_group`, `join_group_and_init`, `install_peer_key`, `encrypt_group_message`, `decrypt_group_message`; `GroupInvite`/`GroupJoinAccept` message kinds

---

## Фаза 5 — Медиа (звонки)

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

**Подход:** Сигнальный уровень в Rust, WebRTC media нативно в Android.

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 5.1 | Обработка CallEvent (Invite/Answer/Candidate/Hangup) | `media/call.rs` | ✅ |
| 5.2 | `active_calls_info()` — список активных звонков | `media/call.rs` | ✅ |
| 5.3 | FFI: `ada_answer_call`, `ada_call_video`, `ada_get_active_calls_json` | `ffi.rs` | ✅ |
| 5.4 | JNI: `nativeAnswerCall`, `nativeCallVideo`, `nativeGetActiveCallsJson` | `jni.rs` | ✅ |
| 5.5 | `AdaCore.kt`: `answerCall`, `callVideo`, `getActiveCallsJson` | `AdaCore.kt` | ✅ |
| 5.6 | ViewModel: `_incomingCall`/`_activeCall` StateFlow, обработчики событий | `AdaCoreViewModel.kt` | ✅ |
| 5.7 | `CallScreen.kt`: входящий звонок (Accept/Decline) + активный (Mute/Speaker/Hangup) | `CallScreen.kt` | ✅ |
| 5.8 | Навигация: маршрут `"call"` в `MainActivity.kt` | `MainActivity.kt` | ✅ |
| 5.9 | Маршрутизация SDP/ICE через зашифрованные сообщения | `api.rs` | ✅ |
| 5.10 | `handle_incoming_call_event()` — Invite→IncomingCall, Answer→set_remote_sdp, Candidate→add_ice_candidate | `api.rs` | ✅ |
| 5.11 | `send_ice_candidate()` — отправка ICE кандидата пиру | `api.rs` | ✅ |
| 5.12 | `WebRTCBridge.kt` — Android PeerConnection API (stub + routing) | `core/WebRTCBridge.kt` | ✅ |
| 5.13 | ViewModel: интеграция WebRTCBridge во все методы + `IceCandidate` событие | `AdaCoreViewModel.kt` | ✅ |
| 5.14 | DTLS-SRTP: реальный `PeerConnectionFactory` + аудио/видео треки + ICE | `WebRTCBridge.kt` | ✅ |
| 5.15 | Адаптивный битрейт: `BitrateController` (4 уровня, hysteresis, cooldown) | `media/audio.rs` | ✅ |
| 5.16 | FFI/JNI `ada_send_ice_candidate` / `nativeSendIceCandidate` | `ffi.rs`, `jni.rs` | ✅ |
| 5.17 | Уведомление о входящем звонке (background), `setFullScreenIntent`, deeplink | `AdaForegroundService.kt`, `MainActivity.kt` | ✅ |
| 5.18 | `setMuted` + `setSpeaker` подключены к WebRTCBridge через ViewModel | `CallScreen.kt`, `AdaCoreViewModel.kt` | ✅ |
| 5.19 | `SurfaceViewRenderer` для видео в `CallScreen.kt` (Compose + AndroidView) | `CallScreen.kt` | ✅ |

---

## Фаза 6 — Передача файлов

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 6.1 | `active_transfers_info()` — список активных передач | `transfer.rs` | ✅ |
| 6.2 | FFI: `ada_send_file_bytes`, `ada_get_transfers_json`, `ada_cancel_transfer` | `ffi.rs` | ✅ |
| 6.3 | JNI: `nativeSendFileBytes`, `nativeGetTransfersJson`, `nativeCancelTransfer` | `jni.rs` | ✅ |
| 6.4 | `AdaCore.kt`: `sendFileBytes`, `getTransfersJson`, `cancelTransfer` | `AdaCore.kt` | ✅ |
| 6.5 | ViewModel: `_transfers` StateFlow, `sendFile`, `cancelTransfer`, `refreshTransfers` | `AdaCoreViewModel.kt` | ✅ |
| 6.6 | `ChatScreen.kt`: кнопка звонка, интеграция с ViewModel | `ChatScreen.kt` | ✅ |
| 6.7 | `MessageKind::FileChunk` и `MessageKind::ChunkRequest` wire-типы | `messaging/types.rs` | ✅ |
| 6.8 | `MessageKind::File` расширен: `encryption_key`, `chunk_count` | `messaging/types.rs` | ✅ |
| 6.9 | `receive_encrypted_wire` обрабатывает File/FileChunk/ChunkRequest | `api.rs` | ✅ |
| 6.10 | `pump_outbound_chunks()` — фоновый цикл отправки чанков (50ms) | `api.rs` | ✅ |
| 6.11 | `request_missing_chunks()` — resume: запрос пропущенных чанков | `api.rs` | ✅ |
| 6.12 | `send_file_streaming<R: AsyncRead>()` — стриминг с диска без RAM | `transfer.rs` | ✅ |
| 6.13 | Blake3 checksum реальная верификация при сборке файла | `transfer.rs` | ✅ |

---

## Фаза 7 — Bridge / Цензуроустойчивость

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 7.1 | `status_list()` — список мостов с reachability | `bridge/bridge.rs` | ✅ |
| 7.2 | `api.rs`: `get_bridge_status_json`, `detect_censorship_json`, `set_bridge_mode_str` | `api.rs` | ✅ |
| 7.3 | FFI: `ada_get_bridge_status_json`, `ada_detect_censorship_json`, `ada_set_bridge_mode` | `ffi.rs` | ✅ |
| 7.4 | JNI: `nativeAddBridge`, `nativeGetBridgeStatusJson`, `nativeDetectCensorshipJson`, `nativeSetBridgeMode` | `jni.rs` | ✅ |
| 7.5 | `AdaCore.kt`: `addBridge`, `getBridgeStatusJson`, `detectCensorshipJson`, `setBridgeMode` | `AdaCore.kt` | ✅ |
| 7.6 | ViewModel: `_bridgeStatus`, `_censorshipLevel` StateFlow + все методы управления | `AdaCoreViewModel.kt` | ✅ |
| 7.7 | `BridgeScreen.kt`: карточка цензуры, селектор режима (6 режимов), список мостов, диалог добавления | `BridgeScreen.kt` | ✅ |
| 7.8 | Навигация: маршрут `"bridge"`, кнопка в `MainScreen` TopAppBar | `MainActivity.kt` | ✅ |
| 7.9 | WebSocket TLS транспорт (`tokio-tungstenite`, bidirectional mpsc) | `bridge/ws_tunnel.rs` | ✅ |
| 7.10 | obfs4-style: SHA256-keystream + XOR + random padding headers | `bridge/obfs4.rs` | ✅ |
| 7.11 | Domain fronting: HTTP CONNECT через CDN | `bridge/domain_front.rs` | ✅ |
| 7.12 | Meek: HTTPS POST camouflage (X-Real-Host) | `bridge/domain_front.rs` | ✅ |
| 7.13 | `detect_censorship()`: реальные 4-probe TCP тесты → уровень None/Light/Moderate/Heavy/Extreme | `bridge/bridge.rs` | ✅ |
| 7.14 | `probe_bridge()` — диспетчеризация по протоколу | `bridge/bridge.rs` | ✅ |
| 7.15 | `probe_bridges()` + `connect_via_best_transport()` | `bridge/bridge.rs` | ✅ |

**Актуальный operational статус:** relay_only, WebSocket TLS bridge, mailbox delivery, status JSON and hostile-network tests are functional. `obfs4`, `domain_fronting` and `meek` are implemented transport options, but remain canary/field-validation routes; ADA `obfs4` is a lightweight authenticated obfuscator, not full Tor obfs4.

---

## Фаза 8 — Мобильная интеграция

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

### 8.1 FFI / JNI

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 8.1 | `nativePollEventJson` — poll event queue | `jni.rs` | ✅ |
| 8.2 | `nativeGetConversationsJson` — список диалогов (JSON) | `jni.rs` | ✅ |
| 8.3 | `nativeGetMessagesJson` — история сообщений (JSON) | `jni.rs` | ✅ |
| 8.4 | `nativeMarkRead`, `nativeSendText` | `jni.rs` | ✅ |
| 8.5 | `nativeCallAudio` / `nativeHangup` | `jni.rs` | ✅ |
| 8.6 | Lifecycle: `nativeCreate` / `nativeFree` | `ffi.rs`, `jni.rs` | ✅ |
| 8.7 | `nativeGetDisplayName`, `nativeGetPeerId` | `jni.rs` | ✅ |
| 8.8 | C headers через cbindgen для iOS | `cbindgen.toml` | ✅ |

### 8.2 Android UI

| # | Экран / компонент | Что сделано | Статус |
|---|-------|-----------|--------|
| 8.9 | `AdaCore.kt` | Все native-методы (+10 новых: звонки, файлы, bridge) | ✅ |
| 8.10 | `AdaModels.kt` | `ConversationItem`, `ChatMessage`, `AdaEvent`, JSON-парсинг | ✅ |
| 8.11 | `AdaCoreViewModel.kt` | StateFlow для звонков, трансферов, bridge; обработка всех событий | ✅ |
| 8.12 | `OnboardingScreen` | Ввод имени → `viewModel.create()` → навигация, спиннер | ✅ |
| 8.13 | `MainScreen` | Диалоги, аватар, unread-бейдж, кнопки Call/Bridge в TopAppBar | ✅ |
| 8.14 | `ChatScreen` | Пузыри сообщений, автоскролл, статусы, кнопка звонка | ✅ |
| 8.15 | `CallScreen` | Входящий звонок (Accept/Decline), активный (Mute/Speaker/Hangup/таймер) | ✅ |
| 8.16 | `BridgeScreen` | Уровень цензуры, режим obfuscation, список мостов, диалог добавления | ✅ |
| 8.17 | `MainActivity` | Единый ViewModel, nav `call`, `bridge`, `chat/{convId}?name={name}` | ✅ |
| 8.18 | Gradle build config | `settings.gradle.kts`, `build.gradle.kts`, `libs.versions.toml` | ✅ |
| 8.19 | `AndroidManifest.xml` | INTERNET, RECORD_AUDIO, READ_MEDIA_* permissions | ✅ |
| 8.20 | `build-android.ps1` | `cargo ndk` → `app/src/main/jniLibs/` | ✅ |
| 8.21 | Push уведомления | `ADANotificationService.kt` — WorkManager 15мин, MessageReceived + IncomingCall | ✅ |
| 8.22 | Foreground service | `AdaForegroundService.kt` — lifecycle foreground service + notification channel | ✅ |
| 8.23 | `PatternBoardView.kt` | Визуальная сетка 8×8, выбор цвета (3 цвета, drag-to-draw) | ✅ |
| 8.24 | `PatternRegistrationScreen.kt` | Экран регистрации через визуальный паттерн | ✅ |
| 8.25 | `PatternLoginScreen.kt` | Экран входа через паттерн (кросс-девайс верификация) | ✅ |
| 8.26 | `PinLoginScreen.kt` | PIN-код вход (альтернатива паттерну) | ✅ |
| 8.27 | `SettingsScreen.kt` | Настройки: профиль, безопасность, уведомления (860 стр.) | ✅ |
| 8.28 | `QrScannerScreen.kt` | Сканер QR кодов для добавления контактов | ✅ |
| 8.29 | `SecureWebViewScreen.kt` | Встроенный защищённый браузер (WebView) | ✅ |
| 8.30 | `AdaBootReceiver.kt` | BroadcastReceiver: автозапуск сервиса после перезагрузки | ✅ |
| 8.31 | `AdaCoreHolder.kt` | Singleton holder для `ADACore` (lifecycle-safe) | ✅ |
| 8.32 | `QrCodeImage.kt` | Compose компонент генерации QR-кода | ✅ |
| 8.33 | `MediaRecorderHelper.kt` | Вспомогательный класс для записи аудио/видео | ✅ |
| 8.34 | `AvatarView.kt` | Compose компонент аватара с инициалами | ✅ |
| 8.35 | Forensic APK size audit | Найден root cause: `libada_core.so` в release был неострипан (405.9 MB, 87% APK) | ✅ |
| 8.36 | Release size fix (NDK strip) | В `app/build.gradle.kts` зафиксирован `ndkVersion=28.0.13004108`; release APK уменьшен с 461.87 MB до 74.34 MB | ✅ |
| 8.37 | JNI libs hygiene | `build-android.ps1`: очистка stale `.so` по ABI + сохранение только `libada_core.so` (без дублирующих `libif_watch-*`) | ✅ |

---

## Фаза A — Визуальная аутентификация (Pattern Auth)

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

Визуальный паттерн (16 ячеек × 3 цвета, 74 бита энтропии) заменяет пароль. Argon2id деривация → Identity + db_key. Кросс-девайс верификация по публичному ключу.

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| A.1 | `PatternKey` — 16 ячеек × 3 цвета, валидация уникальности и границ | `pattern_auth.rs` | ✅ |
| A.2 | `derive_identity_from_pattern()` — детерминированная деривация через Argon2id | `pattern_auth.rs` | ✅ |
| A.3 | `derive_all_from_pattern()` — один проход → identity key + db_key | `pattern_auth.rs` | ✅ |
| A.4 | `verify_pattern()` — верификация паттерна по известному peer_id (кросс-девайс) | `pattern_auth.rs` | ✅ |
| A.5 | `PatternContactCard` — QR JSON payload (только публичный ключ, без паттерна) | `pattern_auth.rs` | ✅ |
| A.6 | `ADACore::from_pattern()` — публичный API создания ноды по паттерну | `api.rs` | ✅ |
| A.7 | `PatternBoardView.kt` — компонент сетка 8×8 (Android UI) | `PatternBoardView.kt` | ✅ |
| A.8 | `PatternRegistrationScreen.kt` + `PatternLoginScreen.kt` (Android UI) | screens | ✅ |
| A.9 | JNI биндинги для `from_pattern` / `verify_pattern` методов | `jni.rs` | ✅ |

**Параметры Argon2id:** 64 МиБ · 3 итерации · p=1  
**HKDF expansion:** отдельные info-строки для signing key / DH key / db key

---

## Фаза B — Перенос identity между устройствами (Mesh Handoff)

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

Чанковый перенос публичного identity-бандла (PeerId + SPK + IK) через QR/NFC/side-channel. SHA256 integrity + BLAKE3 keyed MAC противодействуют подмене бандла в транзите.

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| B.1 | `HandoffOffer` + `HandoffChunk` типы | `mesh_handoff.rs` | ✅ |
| B.2 | `prepare_bundle()` — сериализация + разбивка на чанки | `mesh_handoff.rs` | ✅ |
| B.3 | `begin_receive` / `ingest_chunk` / `is_complete` / `assemble` | `mesh_handoff.rs` | ✅ |
| B.4 | SHA256 + BLAKE3-MAC верификация бандла | `mesh_handoff.rs` | ✅ |
| B.5 | Unit-тесты (4 теста: roundtrip, integrity, chunk ordering) | `mesh_handoff.rs` | ✅ |

---

## Фаза C — Блокировка приложения (AppLock)

> **Статус: ✅ 100% — ЗАВЕРШЕНА**

Многоуровневая блокировка: biometric (BIOMETRIC_STRONG) → PIN → Pattern. Ключи хранятся в Android Keystore через `EncryptedSharedPreferences` (AES256-SIV + AES256-GCM).

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| C.1 | `AppLockManager.kt` — biometric + PIN + EncryptedSharedPreferences | `AppLockManager.kt` | ✅ |
| C.2 | `PinLoginScreen.kt` — экран ввода PIN | `PinLoginScreen.kt` | ✅ |
| C.3 | Интеграция AppLock в `MainActivity` (проверка при resume) | `MainActivity.kt` | ✅ |

---

## Фаза 9 — Тесты и безопасность

> **Статус: ✅ 100% — ЗАВЕРШЕНА** | 85 тестов ✅ (9.13 fuzz добавлен)

| # | Задача | Файл | Статус |
|---|--------|------|--------|
| 9.1 | Unit-тесты X3DH (`x3dh_shared_secret_matches`) | `tests.rs` | ✅ |
| 9.2 | Unit-тесты Double Ratchet (basic + out-of-order) | `tests.rs`, `ratchet.rs` | ✅ |
| 9.3 | Integration тест: SessionManager send/receive | `tests.rs` | ✅ |
| 9.4 | Unit-тесты AES-GCM (roundtrip, wrong key, wrong aad) | `tests.rs` | ✅ |
| 9.5 | Unit-тесты HKDF (determinism, разные info) | `tests.rs` | ✅ |
| 9.6 | Unit-тесты Identity (sign/verify, peer_id roundtrip) | `tests.rs` | ✅ |
| 9.7 | Unit-тесты MessageStore (CRUD, dedup, search, mark_read) | `tests.rs` | ✅ |
| 9.8 | Unit-тесты GroupManager (encrypt/decrypt, multiple msgs) | `tests.rs` | ✅ |
| 9.9 | Async integration тесты: core_create, two_nodes, group_creation, identity_persistence | `integration.rs` | ✅ |
| 9.10 | Unit-тесты RelayReputation (scoring, decay, uptime, avoid — 6 тестов) | `relay_reputation.rs` | ✅ |
| 9.11 | Sync round-trip тест: build_request ↔ build_response ↔ apply | `network/sync.rs` | ✅ |
| 9.12 | Unit-тесты PatternAuth (validation, derivation, verify, JSON — 13 тестов) | `pattern_auth.rs` | ✅ |
| 9.13 | Fuzz-тест десериализации wire-форматов | — | ✅ |
| 9.18 | Unit-тесты IrohTransport: `iroh_endpoint_starts`, `blob_store_insert_evict` (`iroh-transport` feature); QUIC пиринг-тесты помечены `#[ignore]` (требуют relay) | `network/iroh_transport.rs` | ✅ |
| 9.19 | Unit-тесты MeshHandoff (bundle prepare/assemble roundtrip) | `mesh_handoff.rs` | ✅ (4 теста) |
| 9.14 | Аудит: `zeroize` на всех секретных полях (`X3DHSendResult`, `PatternDerivedKeys.db_key`) | `crypto/x3dh.rs`, `pattern_auth.rs` | ✅ |
| 9.15 | Защита от replay-атак (message ID dedup — DM + group fan-out/local delivery) | `api.rs` | ✅ |
| 9.16 | Проверка подписей перед обработкой сообщений (всегда, через PeerId) | `api.rs` | ✅ |
| 9.17 | Интеграционные тесты: полный крипто-пайплайн, двунаправленный DM, replay-dedup | `tests/integration.rs` | ✅ (7 тестов) |

---

## Фаза 10 — Production-ready

| # | Задача | Статус |
|---|--------|--------|
| 10.0 | `CoreMetrics` — 25 lock-free AtomicU64 счётчиков (pool/calls/ratchet/transfers), JSON snapshot | ✅ |
| 10.0a | `ADAConfig` — JSON конфигурация (storage, network, bridge, mobile defaults), load/save | ✅ |
| 10.1 | Battery optimization: throttle background maintenance / network retries | ✅ |
| 10.2 | Версионированные миграции SQLite схемы | ✅ |
| 10.3 | Release build: strip + LTO + panic=abort | ✅ |
| 10.4 | CI/CD: GitHub Actions → Rust tests/hostile-network → Android `.so` → APK | ✅ |
| 10.5 | Structured logging (tracing уже подключён) | ✅ |

---

## Критический путь

```
✅ Фаза 0 → ✅ Фаза 1 → ✅ Фаза 2 → ✅ Фаза 3 → ✅ Фаза 4 → ✅ Фаза 8 (100%, 34 компонента)
                                                             ↓
                          ✅ Фаза 6 · ✅ Фаза 7 · ✅ Фаза 5 · ✅ Фаза A · ✅ Фаза B · ✅ Фаза C
                                                             ↓
                                                  🟡 Фаза 9 (тесты — ~98%, 85 тестов)
                                                             ↓
                                         🟡 Фаза 2b (iroh transport — ~90%)
                                                             ↓
                                                   🟡 Фаза 10 (~10%)
```

## Сводная таблица

| Фаза | Область | Готовность | Статус |
|------|---------|-----------|--------|
| 0 | Фикс фундамента | **100%** | ✅ Завершена |
| 1 | Криптография | **100%** | ✅ Завершена |
| 2 | Сетевой слой | **100%** | ✅ Завершена |
| 3 | Messaging | **100%** | ✅ Завершена |
| 4 | Групповые чаты | **100%** | ✅ Завершена |
| 5 | Звонки | **100%** | ✅ Завершена |
| 6 | Передача файлов | **100%** | ✅ Завершена |
| 7 | Bridge/цензура | **100%** | ✅ Завершена |
| 8 | Mobile/Android | **100%** | ✅ Завершена (34 компонента) |
| A | Pattern Auth | **100%** | ✅ Завершена |
| B | Mesh Handoff | **100%** | ✅ Завершена |
| C | AppLock / Biometric | **100%** | ✅ Завершена |
| 9 | Тесты | **100%** | ✅ Завершена (все 86 тестов + libfuzzer) |
| 2b | iroh transport (комбинированный) | **~90%** | ✅ Функционально завершена (2b.8 relay_reputation, 2b.10 bloat pending) |
| 10 | Production | **100%** | ✅ Завершена (10.0–10.5 все готовы) |
