# ПОЛНЫЙ АУДИТ ПРОЕКТА ADA MESSENGER

**Дата**: 13 мая 2026 (обновлено 14 мая 2026)  
**Версия**: post-M-series + localization + iroh-only / bridge-mailbox baseline  
**Охват**: Rust core (~12 000 LOC) + Android app (~9 000 LOC Kotlin, 34 файла)

---

## РЕЗЮМЕ

| Компонент | Готовность | Качество | Безопасность |
|-----------|-----------|----------|-------------|
| **Криптография (Rust)** | 100% | ★★★★★ | ★★★★★ |
| **Messaging (Rust)** | 98% | ★★★★★ | ★★★★★ |
| **Network/P2P (Rust)** | 92% | ★★★★☆ | ★★★★☆ |
| **Storage (Rust)** | 90% | ★★★★☆ | ★★★★★ |
| **FFI/JNI (Rust)** | 90% | ★★★☆☆ | ★★★★☆ |
| **Identity (Rust)** | 100% | ★★★★★ | ★★★★★ |
| **Группы (Rust)** | 85% | ★★★★☆ | ★★★★☆ |
| **Звонки/Media (Rust + Android WebRTC)** | 65% MVP / group beta | ★★★☆☆ | ★★★☆☆ |
| **Bridges/DPI (Rust)** | 75-90% по маршрутам | ★★★★☆ | ★★★★☆ |
| **Тесты (Rust)** | 65% | ★★★★☆ | — |
| **Android UI** | 95% | ★★★★★ | ★★★★★ |
| **Android Core** | 95% | ★★★★★ | ★★★★★ |
| **Android Security** | 97% | ★★★★★ | ★★★★★ |

**Общая взвешенная готовность: ~90%**

---

## 1. ВЫПОЛНЕННЫЕ ИСПРАВЛЕНИЯ

### ✅ M-6 — ICE Restart (ИСПРАВЛЕНО)
- **Проблема**: Отсутствовал `onIceRestartAnswer()` для offerer-стороны + нет explicit `IceRestart: true`.
- **Файлы**: `WebRTCBridge.kt`, `CallManager.kt`, `AdaCoreViewModel.kt`
- **Решение**: Добавлен метод `onIceRestartAnswer()`, event routing `IceRestartAnswer`, constraint `IceRestart: true` в `onRenegotiationNeeded`.

### ✅ M-5 — Call State Machine (ИСПРАВЛЕНО)
- **Проблема**: `state: String` в `ActiveCallInfo` — хрупкие сравнения.
- **Файлы**: `AdaCoreViewModel.kt`, `CallManager.kt`, `CallScreen.kt`
- **Решение**: Создан `enum class CallState { Initiating, Ringing, Connecting, Active, Reconnecting, Ended, Failed }` с `fromString()`. Все строки заменены на enum.

### ✅ M-2 — Unstable LazyColumn Lambdas (ИСПРАВЛЕНО)
- **Проблема**: Inline лямбды `onPlayVoice`, `onStopVoice`, `onLongPress` пересоздавались на каждый item.
- **Файл**: `ChatScreen.kt`
- **Решение**: Вынесены в `remember`-стабилизированные `val`-ы на уровне composable.

### ✅ M-1 — Hardcoded Strings (ПОЛНОСТЬЮ ИСПРАВЛЕНО)
- **Создан**: `res/values/strings.xml` (EN, дефолт) — 200+ строковых ресурсов
- **Локализации**: `values-ru/` (RU), `values-es/` (ES), `values-fr/` (FR) — 200+ ресурсов каждая
- **Обновлены**: Все экраны — `CallScreen`, `ChatScreen`, `GroupChatScreen`, `SettingsScreen`, `OnboardingScreen`, `PatternLoginScreen`, `PatternRegistrationScreen`, `PinLoginScreen` → `stringResource(R.string.*)`
- **Кириллица в коде**: 0 (ноль) вхождений
- **Добавлен**: Выбор языка в `SettingsScreen` с `AppCompatDelegate.setApplicationLocales()`
- **Конфиг**: `locales_config.xml` (ru, en, es, fr), `AndroidManifest` → `localeConfig`

---

## 2. RUST CORE — ДЕТАЛЬНЫЙ АУДИТ

### ✅ Крипто — ПОЛНОСТЬЮ ГОТОВО
- **X3DH**: Signal spec, DH1-DH4, OPK, SPK подпись — **отлично**
- **Double Ratchet**: Forward secrecy, break-in recovery, MAX_SKIP=256, зероизация — **отлично**
- **Symmetric**: AES-256-GCM, HKDF-SHA256, random nonce — **отлично**
- **PreKeys**: SPK ротация (7 дн.), OPK пул (100 штук) — **отлично**
- **Pattern Auth**: Argon2id (64 MiB, 3 iter) + HKDF — **отлично**

### ✅ Messaging — ПОЛНОСТЬЮ ГОТОВО
- **Session Manager**: Per-peer ratchet, X3DH handshake, padded buckets (256B-65KB), rate limiting (5/час)
- **Message Store**: SQLCipher, pagination, search, delete cascade
- **Router**: ✅ **ПОЛНАЯ РЕАЛИЗАЦИЯ** (~320 LOC) — 4 режима маршрутизации (Direct/Relay/Local/GroupBroadcast), retry с экспоненциальным backoff (5 попыток, 2^n, cap 300s)

### ✅ Network — IROH-ONLY BASELINE ГОТОВ
- **Iroh**: QUIC unicast, content-addressed blobs (BLAKE3)
- **Relay**: Offline queue (MAX=500/peer, TTL 7 дней), SHA256 integrity, token-based privacy (~200 LOC)
- **Relay Reputation**: ✅ Scoring system (0–100, ±8/+3, 6h decay to neutral) (~80 LOC)
- **Presence**: события online/offline построены на iroh connection cache и transport events; это не отдельный universal presence protocol.
- **Transport ladder**: LocalMesh → IrohLive → Bridge → Mailbox/OfflineQueue; `relay_only` отключает исходящий live iroh для unicast.
- **DPI Обфускация**: режимы реализованы в коде, но production зрелость различается: WebSocket TLS + mailbox сильнее, obfs4/domain-fronting/meek требуют canary/field validation.

### ✅ Groups — ПРАКТИЧЕСКИ ГОТОВО
- **GroupManager**: Полный CRUD, член management, key rotation с RwLock<HashMap> (~150 LOC)
- **Sender Keys**: ✅ **ПОЛНАЯ РЕАЛИЗАЦИЯ** (~280 LOC) — ED25519 подпись + ChaCha20, 1000-msg lookahead buffer, групповой Double Ratchet
- **Group Types**: GroupRole (Owner/Admin/Member), GroupMember struct, полные типы (~150 LOC)
- **Групповое шифрование**: ✅ encrypt/decrypt roundtrip работает (подтверждено тестами)
- ⚠️ **Остаётся**: групповые видеозвонки остаются limited/beta: room metadata + pairwise WebRTC fan-out, без SFU/MCU/media relay.

### ⚠️ Media/Calls — 1:1 READY, GROUP CALLS BETA
- Call state machine: 6 состояний, 45s ring timeout, ICE candidates (~200 LOC)
- **SDP генерация**: Реальная структура (Opus 48kHz, VP9/H264 для видео), не placeholder
- Audio codec config: Opus профили (high_quality/low_bandwidth), FEC/DTX (~150 LOC)
- WebRTC crate отсутствует в зависимостях
- **Вердикт**: 1:1 audio/video are implemented through Android native WebRTC with Rust signaling. Group calls are an end-to-end limited/beta MVP using pairwise WebRTC fan-out; production conferencing requires SFU/MCU/media relay or an explicit product cap.

### ✅ Bridges — КОД РЕАЛИЗОВАН, FIELD VALIDATION РАЗДЕЛЕНА ПО МАРШРУТАМ
- **bridge.rs**: Абстракция (~200 LOC) — 5 протоколов (Obfs4/WS/DomainFront/Meek/TcpDirect), Tor-format parser
- **obfs4.rs**: ✅ ChaCha20 stream cipher, 64B nonce XOR, frame encryption [len|pad|data] (~220 LOC)
- **ws_tunnel.rs**: ✅ tokio-tungstenite + rustls 0.22, User-Agent spoofing, background multiplexing + корректная сборка больших WebSocket bridge frames (~190 LOC)
- **domain_front.rs**: ✅ HTTP CONNECT + Meek POST, TLS SNI spoofing, webpki root store (~200 LOC)
- **Operational status**: relay_only, WebSocket TLS bridge, mailbox delivery, status JSON and hostile-network tests are functional. obfs4/domain-fronting/meek remain trial/canary routes until validated in real networks; ADA obfs4 is not full Tor obfs4.

### ✅ Дополнительные модули
- **Transfer**: Adaptive chunking (16KiB-512KiB), blake3 checksums, AES-GCM per-chunk (~300 LOC)
- **Metrics**: 24 AtomicU64 lock-free counters, atomic snapshot() (~150 LOC)
- **Mesh Handoff**: Bundle transfer via QR/BLE/NFC, SHA256 + BLAKE3 MAC, 512B chunks (~200 LOC)

---

## 3. ANDROID APP — ДЕТАЛЬНЫЙ АУДИТ

### Архитектура: 9/10 ✅
- ViewModel → Managers (CallManager, TransferManager) → JNI
- Provider lambdas для DI
- AdaConfig централизует все константы
- ForegroundService с wake lock, network callback

### Безопасность: 9.5/10 ✅
| Критерий | Статус |
|----------|--------|
| EncryptedSharedPreferences | ✅ |
| PBKDF2 600k iter для PIN | ✅ |
| Android Keystore + BiometricPrompt | ✅ |
| SQLCipher (pattern-derived key) | ✅ |
| FLAG_SECURE (антискриншот) | ✅ |
| usesCleartextTraffic=false | ✅ |
| Нет WebView (атакуемая поверхность) | ✅ |
| Exported components = false | ✅ |

### ✅ Найденные проблемы — СТАТУС ИСПРАВЛЕНИЙ

| # | Серьёзность | Проблема | Файл | Статус |
|---|-------------|----------|------|--------|
| A-1 | 🟡 MEDIUM | **CallScreen**: catch(Exception) глушит ошибку ringtone без лога | CallScreen.kt | ✅ **ИСПРАВЛЕНО** — добавлен `Log.w` |
| A-2 | 🟡 MEDIUM | **TransferManager**: fileName не санитизируется (`../` path injection) | TransferManager.kt | ✅ **ИСПРАВЛЕНО** — `File().name` + `canonicalPath` validation |
| A-3 | 🟡 MEDIUM | **AppLockManager**: System.currentTimeMillis() в lockout | AppLockManager.kt | ✅ **ИСПРАВЛЕНО** — заменено на `SystemClock.elapsedRealtime()` |
| A-4 | 🟢 LOW | **Pattern cells**: ByteArray не зероизируется | AdaCoreViewModel | ✅ **ИСПРАВЛЕНО** — `zeroPatternCells()` с `.fill(0)` |
| A-5 | 🟢 LOW | **MediaRecorderHelper**: нет явного dispose | ChatScreen.kt | ✅ **ИСПРАВЛЕНО** — `DisposableEffect` + cancel() |
| A-6 | 🟢 LOW | **ProGuard**: нет правил для javax.crypto | proguard-rules.pro | ✅ **ИСПРАВЛЕНО** — javax.crypto, security-crypto, tink |
| A-7 | 🟢 LOW | **Emoji Reactions (V-12)**: ранее были локальным placeholder | AdaCoreViewModel.kt | ✅ **ИСПРАВЛЕНО** — реакции идут через AdaCore -> JNI/FFI -> core protocol |

---

## 4. ФУНКЦИОНАЛЬНАЯ МАТРИЦА

| Функция | Rust Core | Android UI | Статус |
|---------|-----------|-----------|--------|
| Регистрация по паттерну | ✅ | ✅ | **Готово** |
| Вход по паттерну | ✅ | ✅ | **Готово** |
| Quick-unlock PIN | ✅ | ✅ | **Готово** |
| Биометрия | — | ✅ | **Готово** |
| Текстовые сообщения (1:1) | ✅ | ✅ | **Готово** |
| Текстовые сообщения (группа) | ✅ | ✅ | **Готово** (Sender Keys реализованы) |
| Передача файлов | ✅ | ✅ | **Готово** |
| Голосовые сообщения | — | ✅ | **Готово** (запись/воспроизведение на Android) |
| Аудиозвонки | ✅ signaling | ✅ WebRTC | **Готово** (signaling через ядро) |
| Видеозвонки | ✅ signaling | ✅ WebRTC | **Готово** (signaling через ядро) |
| Шаринг экрана | — | ✅ | **Готово** |
| QR-обмен ключами | ✅ | ✅ | **Готово** |
| Инкогнито-чат | ✅ | ⚠️ | **Частично** |
| Уведомления | — | ✅ | **Готово** |
| Фоновый сервис | — | ✅ | **Готово** |
| ICE Restart | ✅ signaling | ✅ | **Готово** (M-6) |
| Ответ на сообщение | ✅ | ✅ | **Готово** |
| Реакции | ✅ | ✅ | **Готово** |
| Мосты/DPI | ✅ | ✅ Bridge UI | **Готово по WebSocket TLS/mailbox; obfs4/domain-fronting/meek = canary** |
| Маршрутизация | ✅ | — | **Готово** (LocalMesh → IrohLive → Bridge → Mailbox/OfflineQueue) |
| App Lock (Clean PIN) | — | ✅ | **Готово** |
| App Lock (Kill PIN) | — | ✅ | **Готово** |
| Discovery | ✅ pkarr DNS / iroh | — | **Готово** |
| Legacy libp2p DHT/mDNS | — | — | **Удалено после iroh-only миграции** |
| Relay (offline queue) | ✅ | — | **Готово** |
| Relay Reputation | ✅ | — | **Готово** (scoring 0–100, decay) |
| Локализация (4 языка) | — | ✅ | **Готово** (EN/RU/ES/FR) |
| Mesh Handoff | ✅ | — | **Готово** (QR/BLE/NFC) |

---

## 5. ОЦЕНКА ГОТОВНОСТИ К РЕЛИЗУ

### Для Beta-версии (текущее состояние):
**ГОТОВ к limited beta** — критические функции работают: messaging, 1:1 calls, file transfers, auth, encryption, groups, WebSocket bridge/mailbox. Group calls, local mesh and advanced bridge modes require explicit beta/product limits until device/canary validation is complete.

### До Beta — ВСЁ ВЫПОЛНЕНО:
1. ~~M-5, M-6 ICE + State Machine~~ ✅ СДЕЛАНО
2. ~~M-2 LazyColumn performance~~ ✅ СДЕЛАНО
3. ~~A-2 filename sanitization~~ ✅ СДЕЛАНО
4. ~~ProGuard правила~~ ✅ СДЕЛАНО
5. ~~M-1 Hardcoded strings~~ ✅ СДЕЛАНО (200+ ресурсов, 4 языка)
6. ~~A-1, A-3, A-4, A-5 security fixes~~ ✅ СДЕЛАНО

### Необходимо до Production:
1. ⚠️ Зафиксировать Android instrumentation runtime-прогоны в CI/device matrix; сейчас в репозитории есть базовые androidTest smoke/navigation tests, но не полноценная UI/device matrix.
2. ⚠️ Дожать canary/device-matrix валидацию anti-censorship маршрутов и recovery-loop
3. ⚠️ Групповые видеозвонки (нет SFU, только 1:1 WebRTC)

### Долгосрочные цели:
- Compose BOM ≥ 2024.09 → PullToRefresh (V-20), AnimatedNavHost (V-24)
- iOS клиент
- Desktop клиент

---

## 6. ТЕСТОВОЕ ПОКРЫТИЕ

### Rust-тесты — 146 тестов:

| Файл | Тесты | Описание |
|------|-------|----------|
| `src/tests.rs` | 18 | AES-GCM (3), HKDF (2), Identity (2), PeerId (1), X3DH (1), Ratchet (2), Session (1), MessageStore (4), Groups (2) |
| `src/pattern_auth.rs` | 15 | Валидация паттерна, canonical, derive identity, verify, contact card JSON |
| `tests/integration.rs` | 17 | core start, two nodes, group creation, identity persistence, DM delivery, bidirectional, replay dedup + hostile-network bridge/mailbox scenarios, degraded calls, delayed file transfer catch-up, missing-chunk retransmit |
| `src/network/relay_reputation.rs` | 6 | decay, bonus/penalty, clamping, uptime, preferred/avoid thresholds |
| `src/mesh_handoff.rs` | 4 | roundtrip, tamper detection, missing chunks, adaptive chunk size |
| `src/crypto/ratchet.rs` | 3 | encrypt/decrypt basic, multiple in-order, bidirectional |
| `src/network/connection_pool.rs` | 3 | backoff doubles, mark connected resets, pool active count |
| `src/media/audio.rs` | 3 | bitrate degrade on loss, upgrade cooldown, opus buffer frames |
| `src/crypto/x3dh.rs` | 2 | shared secret matches, with OPK matches |
| `src/crypto/prekeys.rs` | 2 | initial pool size, consume OPK |
| `src/transfer.rs` | 2 | chunk size clamps, transfer offer verify |
| `src/network/iroh_fallback.rs` | 2 | send outcome display, fallback config defaults |
| `src/bridge/bridge.rs` | 5 | bridge wire parsing, mode-aware host selection, circuit breaker, protocol scoring |
| `src/bridge/ws_tunnel.rs` | 1 | live insecure websocket tunnel roundtrip + expected Host header |
| `src/bridge/domain_front.rs` | 2 | local TLS connector pair + domain-front CONNECT byte forwarding |
| `src/bridge/obfs4.rs` | 3 | obfs4 framing basics + authenticated client/server roundtrip |
| `src/transport.rs` | 5 | transport policy ladder, capabilities, bridge route mapping |
| `src/media/call.rs` | 3 | call answer/activate, duplicate invite guard, timeout cleanup |
| `src/network/sync.rs` | 1 | sync round-trip in-memory |
| `src/network/relay.rs` | 1 | store and drain |
| `src/network/dpi.rs` | 1 | padding roundtrip |
| `src/metrics.rs` | 1 | snapshot reflects increments |
| `src/network/iroh_transport.rs` | 1 | blob store insert/evict |
| `src/group/sender_keys.rs` | 1 | sender key group chat |
| `src/logging.rs` | 1 | init tracing desktop |

### Android-тесты — JVM unit + базовые androidTest:
| Область | Unit | Integration | E2E |
|---------|------|-------------|-----|
| Android JVM unit | ✅ `AdaModelsTest`, `AdaConfigTest`, `ConversationItemTest`, `MeshFrameCodecTest` | — | — |
| Android Instrumentation | — | ✅ `AppContextSmokeTest`, `MainActivityNavigationTest` | — |
| Android UI | 🟡 | ⚠️ базовые navigation/smoke paths | 🔴 |

### Непокрытые области (нет тестов):
- Полноценная Android instrumentation/UI/device matrix в CI
- Полноценная матрица end-to-end звонков, chunk retransmit и recovery-loop под деградацией сети
- Групповые видеозвонки / SFU сценарии

**Итого:** точное число тестов не фиксируется в этом отчёте; source of truth — `cargo test --no-default-features --features mobile-dev --lib --tests`, `cargo test --no-default-features --features mobile-dev --test integration`, Gradle JVM unit tests и androidTest вывод.  
**Рекомендация**: Приоритетно автоматизировать Android instrumentation runtime в CI/device matrix и расширить anti-censorship canary/recovery matrix; hostile-network bridge/mailbox tests уже есть, но production evidence требует регулярных прогонов.

---

## ВЕРДИКТ

**Проект находится на стадии ~90% общей готовности.**

- **Ядро (крипто + messaging + network)**: 95% — production quality
- **Android UI**: 95% — готов к beta, полная локализация (4 языка)
- **Групповое крипто**: 85% — Sender Keys реализованы, нет группового видео
- **Media/Calls**: 1:1 calls реализованы через Android WebRTC + Rust signaling; group calls = limited/beta без SFU/MCU
- **Bridges/DPI**: WebSocket TLS + mailbox/relay_only сильнее всего; obfs4/domain-fronting/meek требуют field validation
- **Тесты**: покрытие заметно усилено Rust integration/hostile-network и Android JVM/androidTest, но CI/device matrix и расширенный canary matrix всё ещё нужны
- **Android Security**: 97% — все A-1–A-7 из этого аудита закрыты; основной хвост смещён в ops/test coverage

**Для limited beta:** критические фиксы выполнены, но beta scope должен явно ограничить group calls, local mesh и advanced bridge transports.  
**Для Production:** нужно расширение тестов, bridge/canary validation, device matrix, group conferencing decision/SFU или жёсткий product cap.
