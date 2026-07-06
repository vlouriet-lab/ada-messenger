# План превращения ADA в продукт для жесточайшей цензуры

> Дата: 13 мая 2026  
> Актуализировано: 14 мая 2026  
> Статус документа: living plan; разделы 2.2, 8.3, 9 и 12 отражают текущее состояние, а фазовые чеклисты ниже сохранены как historical rollout record.
> Цель: зафиксировать отдельный, реалистичный план развития ADA для сетей с жесткой цензурой, DPI и белыми списками, где пропускается только ограниченный набор доменов и протоколов.

---

## 1. Зачем нужен отдельный план

Текущий проект уже содержит сильный задел по антицензурной части:

- iroh-only транспорт с relay-first логикой
- UI и API для bridge-режимов
- клиентские туннели WebSocket TLS, Domain Fronting, Meek и obfs4-style
- детектор уровня цензуры
- offline queue для повторной доставки

Но в текущем состоянии ADA еще не является продуктом, который можно честно назвать устойчивым к жесточайшей цензуре. Сейчас это iroh-мессенджер с уже включённым bridge/mailbox fallback для текста и подготовленной антицензурной инфраструктурой; оставшийся разрыв находится в production deployment, canary/device-matrix validation, bridge fleet operations и деградации звонков/файлов.

Этот документ нужен, чтобы:

- отделить реальную текущую функциональность от потенциальной
- описать целевую архитектуру без самообмана
- зафиксировать последовательность внедрения, а не набор разрозненных идей
- довести систему до состояния, где ADA работоспособна в сетях типа “запрещено все, кроме заранее разрешенного”

---

## 2. Текущее состояние

### 2.1 Что уже работает сейчас

1. Основной боевой транспорт — iroh-only.
2. Используются relay-first и pkarr/DNS discovery.
3. При неудачной доставке сообщение уходит в offline queue.
4. Есть UI-экран управления антицензурой и мостами.
5. Есть API для `set_bridge_mode`, `get_bridge_status_json`, `detect_censorship_json`, `set_relay_only`.
6. Есть клиентские реализации транспортов:
   - WebSocket over TLS
   - Domain Fronting
   - Meek
   - obfs4-style stream
7. Есть `BridgeManager` с probing и выбором лучшего моста.
8. Есть `ObfuscationMode` и примитивы padding/shaping.

### 2.2 Что уже закрыто кодом, а что ещё остаётся довести до production

1. Боевой send path уже использует `TransportRouter` и route ladder `LocalMesh -> IrohLive -> Bridge -> Mailbox/OfflineQueue`.
2. WebSocket TLS / fronting / meek / obfs4 и mailbox/store-and-forward уже встроены в реальную доставку текста; незакрытый хвост здесь теперь относится к device-matrix/canary validation, а не к отсутствию самого пути. WebSocket TLS + mailbox являются primary production candidates; fronting/meek/obfs4 остаются canary/field-validation routes.
3. `relay_only` работает как реальная transport policy, а не как UI-флаг; direct path подавляется политикой и hostile-network тестами.
4. Автоматический anti-censorship/runtime status уже экспортирует route telemetry и bridge/mailbox status, но recovery-policy ещё требует operational tuning на реальных устройствах.
5. Bridge-assisted inbound/mailbox path уже присутствует в core и reference backend; remaining work здесь — масштабирование, abuse-hardening и эксплуатационная обкатка.
6. Серверная control plane плоскость уже существует в виде signed manifest bootstrap/cache, trust anchors и reference backend endpoints; остаётся rollout/rotation discipline в проде.
7. Референсный bridge/mailbox backend уже есть в репозитории и собирается; production-grade вопрос теперь в canary rollout и fleet observability.
8. Для файлов и звонков уже есть capability-based degradation, но calls/files остаются самым слабым слоем под жёсткой цензурой и требуют отдельной e2e матрицы.
9. WARP по-прежнему рассматривается только как optional bootstrap/helper, а не как архитектурный transport layer ADA.

### 2.3 Главный честный вывод

Для сетей умеренной цензуры текущая система уже может работать, если сеть пропускает iroh relay и нужный discovery.

Для сетей с жестким allowlist-режимом этого недостаточно.

Причина простая: одних клиентских туннелей мало. Нужна еще архитектура доставки, в которой оба клиента могут жить через разрешенные мосты, а сообщения не требуют прямой одновременной сетевой достижимости обоих пиров.

Иными словами:

- для “умеренно режут P2P” достаточно усилить существующий fallback
- для “запрещено почти все” нужен уже bridge-assisted store-and-forward слой

---

## 3. Целевая продуктовая формулировка

ADA должна уметь работать в трех классах сетей:

### Класс A — обычная сеть

- direct iroh / relay iroh
- минимальная задержка
- без лишней маскировки

### Класс B — жесткая цензура, но не белый список

- direct трафик режется
- QUIC может быть нестабилен
- часть TLS/WebSocket/HTTPS еще проходит
- ADA должна автоматически уходить в relay-only и bridge-транспорт

### Класс C — белый список / почти полная блокировка

- разрешены только конкретные домены, CDN, enterprise proxy, HTTPS-паттерны
- прямой P2P неработоспособен
- UDP/QUIC может быть полностью запрещен
- ADA должна уметь жить через разрешенные HTTPS-совместимые мосты и mailbox/store-and-forward

Целевой продуктовый результат:

1. Текстовые сообщения доставляются даже когда прямой P2P невозможен.
2. Приложение не требует от пользователя понимания сетевой инженерии.
3. Переключение между маршрутами происходит автоматически или почти автоматически.
4. End-to-end encryption остается неизменной: мосты и relay видят только opaque envelopes.
5. У системы есть деградация по функциям, а не бинарное “работает / не работает”.

---

## 4. Принципы проектирования

1. Не ломать E2EE ради проходимости.
2. Не притворяться, что client-only схема покроет extreme censorship.
3. Сначала сделать надежную доставку текста и вложений, потом медиа.
4. Делать транспорт абстракцией, а не веткой внутри `send_message()` на сотни строк.
5. Автоматический режим должен быть объяснимым и наблюдаемым.
6. Режим белого списка должен иметь серверную плоскость управления и ротации мостов.
7. Каждая следующая ступень маршрутизации должна иметь явную стоимость:
   - задержка
   - риск блокировки
   - расход трафика
   - уровень приватности

---

## 5. Целевая архитектура

## 5.1 Новый транспортный контракт

Нужно ввести единый транспортный слой поверх текущего iroh-only send path.

Минимальные сущности:

- `TransportRoute`
- `TransportPolicy`
- `TransportOutcome`
- `RouteCapabilities`
- `BridgeEnvelope`
- `MailboxEnvelope`

`TransportRoute` должен описывать, через какой путь идет трафик:

1. `IrohDirect`
2. `IrohRelay`
3. `BridgeWebSocketTls`
4. `BridgeDomainFront`
5. `BridgeMeek`
6. `BridgeObfs4`
7. `MailboxBridge`
8. `OfflineQueue`

Текущее ядро должно перестать думать в терминах “либо iroh, либо очередь” и начать думать в терминах маршрутизатора доставки.

## 5.2 Новый порядок маршрутизации

Рекомендуемая цепочка для DM:

1. Попытка прямого iroh, если политика это допускает.
2. Попытка iroh через relay.
3. Попытка bridge transport по текущей политике.
4. Если живого канала нет, отправка в mailbox/store-and-forward через bridge.
5. Если и это недоступно, локальная offline queue.

Для extreme censorship пользователь в идеале должен видеть не “ошибка сети”, а одну из честных формулировок:

- отправлено напрямую
- отправлено через защищенный мост
- сохранено и будет доставлено при появлении мостового канала

## 5.3 Bridge-assisted store-and-forward

Это главный недостающий элемент.

Если оба клиента сидят за жестким allowlist, им нельзя требовать одновременного прямого соединения. Нужен мостовой mailbox-уровень:

1. Клиент A устанавливает разрешенное HTTPS-подобное соединение с мостом.
2. Клиент A загружает зашифрованный envelope для клиента B.
3. Клиент B периодически или по long-poll/WebSocket получает свой mailbox.
4. Мост не знает содержимое сообщений и не может их расшифровать.
5. TTL, deduplication, replay-protection и rate limiting реализуются на серверной стороне.

Это не отменяет P2P-архитектуру, а становится цензуроустойчивым fallback-слоем.

## 5.4 Control plane мостов

Нужен подписанный способ доставки bridge-конфигурации.

Минимум:

1. `bridge-manifest.json`
2. Ed25519-подпись manifest
3. несколько зеркал публикации
4. TTL и версия manifest
5. ротация bridge endpoints без релиза APK

Manifest должен содержать:

- тип транспорта
- front host / ws host / meek endpoint
- fingerprint pinning
- приоритет
- региональность
- ограничения по трафику
- флаг для text-only / attachments / calls

## 5.5 Деградация функциональности

Продукт должен честно деградировать по слоям:

1. Текстовые сообщения — highest priority, должны жить первыми.
2. Малые вложения — вторыми.
3. Большие файлы — через отдельный resumable bridge path или explicit deferred transfer.
4. Звонки — optional; для extreme censorship должны уметь автоматически отключаться или переходить в “недоступно в текущей сети”.

## 5.6 Где в этой архитектуре место WARP и WebSocket

### WebSocket

1. Это лучший первый production bridge path.
2. Он уже частично реализован в коде и лучше всего ложится на allowlist-сети, где еще проходят обычные `wss://` профили.
3. Он должен стать не только bridge transport для live-доставки, но и базовым каналом для mailbox push/pull, long-poll и control plane.
4. Именно WebSocket логично сделать первым реальным fallback после iroh relay, а не откладывать его в “экзотику”.

### WARP

1. WARP не должен считаться основным transport layer ADA.
2. Его правильная роль — optional bootstrap/evasion helper.
3. Практические сценарии WARP:
   - первый запуск в полностью заблокированной сети
   - получение bridge manifest
   - восстановление первого канала к mailbox/control plane
   - emergency mode, когда встроенные маршруты ADA еще не поднялись
4. WARP нельзя делать архитектурным фундаментом, потому что:
   - это зависимость от внешнего оператора
   - он может блокироваться как класс сервиса
   - это device-wide VPN semantics, а не app-native transport
   - он усложняет permissions, battery и UX
5. Вывод:
   - WebSocket должен быть встроен в core routing как основной bridge fallback.
   - WARP должен быть в плане как опциональный вспомогательный слой, а не как обязательная основа продукта.

---

## 6. Что можно переиспользовать из уже существующего кода

Без переписывания с нуля уже можно опереться на:

1. `BridgeManager` как точку выбора транспорта.
2. `WsTunnel`, `DomainFrontTunnel`, `MeekSession`, `ObfsStream` как клиентские low-level транспорты.
3. `network/relay.rs` и offline queue как основу для mailbox semantics.
4. `detect_censorship()` как исходный сигнал для policy engine.
5. `BridgeScreen` и bridge status JSON как основу пользовательского управления.
6. `ObfuscationMode` как основу для transport policy, даже если реализация пока неполная.
7. `MessageStatusChanged` и event pipeline как базу для честного UX статусов доставки.

---

## 7. Что нужно реализовать дополнительно

## 7.1 Core routing refactor

Задачи:

1. Выделить отдельный `TransportRouter` в ядре.
2. Перевести `send_message`, `deliver_wire`, `try_deliver_wire`, файловую и call-сигнализацию на единый routing API.
3. Добавить outcome-модель доставки, чтобы UI видел путь и причину деградации.
4. Убрать знания о fallback-логике из UI и оставить их только в core.

Definition of done:

- DM, file metadata, reactions, delete requests и call signaling идут через один router.
- Router возвращает не bool, а структурированный результат.

## 7.2 Честная реализация relay-only

Статус на 2026-05-14: закрыто кодом и hostile-network тестами. Ниже сохранён исходный checklist как historical rollout record.

Нужно:

1. Явно отключить direct dial там, где это требуется политикой.
2. Явно запрещать маршруты, раскрывающие IP.
3. Привязать UI-переключатель к реальному transport policy.
4. Протестировать утечки прямого соединения.

Definition of done:

- при `relay_only = true` никакой direct path не используется
- это подтверждено сетевыми тестами, а не только логами

## 7.3 Интеграция bridge transport в боевой send path

Задачи:

1. Встроить `connect_via_best_transport()` в `TransportRouter`.
2. Поддержать bridge send/recv для зашифрованных wire envelopes.
3. Ввести fallback ladder: iroh -> bridge -> mailbox -> offline queue.
4. Добавить per-route timeout policy.
5. Добавить backoff и circuit breaker для плохих мостов.

Definition of done:

- текстовые сообщения реально доставляются через WebSocket TLS или fronted bridge без участия iroh

## 7.4 Mailbox/store-and-forward backend

Это обязательная часть для true whitelist resilience.

Нужен отдельный backend-слой:

1. Прием opaque envelopes по HTTPS/WebSocket.
2. Хранение mailbox по recipient key.
3. TTL, dedup, quota, anti-abuse.
4. Long-poll или push-like канал для выдачи новых envelopes.
5. Наблюдаемость и ротация ключей сервиса.

Минимальная серверная функциональность:

- `POST /mailbox/push`
- `GET /mailbox/pull`
- `ACK /mailbox/ack`
- auth на основе подписи отправителя или токена, привязанного к peer-id
- rate limit

Definition of done:

- два клиента могут обмениваться E2EE текстом, не устанавливая прямого P2P соединения друг с другом

## 7.5 Bridge manifest и bootstrap

Нужно:

1. подписанный manifest мостов
2. несколько каналов получения manifest
3. встроенный bootstrap manifest в APK
4. обновление manifest без релиза клиента
5. fallback на локально закэшированный manifest

Дополнительно:

- import bridge config через QR, deeplink, файл, текст
- региональные и тематические наборы мостов

## 7.6 Obfuscation как реальный транспортный слой

Сейчас padding/shaping существуют как примитивы. Их надо довести до маршрута.

Нужно:

1. сделать padding применяемым к bridge envelopes
2. добавить jitter/timing-shaping на клиентских транспортных туннелях
3. поддержать профили трафика:
   - burst-safe
   - low-and-slow
   - browser-like
4. ввести ограничения, чтобы shaping не убивал батарею и UX

## 7.7 Отдельная стратегия для файлов и звонков

Текст и медиа не должны делить одну и ту же продуктовую судьбу.

Нужно:

1. Для файлов:
   - text-first routing
   - metadata отдельно от payload
   - deferred attachment fetch
   - resumable upload/download через mailbox/bridge
2. Для звонков:
   - explicit detection, что текущая сеть не годится для realtime
   - fallback на message saying “voice/video unavailable in this network”
   - при возможности — TURN-like HTTPS-friendly media relay как отдельный будущий этап

## 7.8 Android UX и onboarding

Нужно:

1. объяснимый режим “Сеть с жесткой цензурой”.
2. один безопасный quick action: “Использовать мосты”.
3. честные статусы доставки:
   - прямое соединение
   - relay
   - защищенный мост
   - mailbox
   - сохранено локально
4. импорт bridge manifests без копания в настройках.
5. safe defaults для стран/сетей, где пользователь почти наверняка под цензурой.

## 7.9 Security и abuse control

В жесткой цензуре мосты станут объектом атаки.

Нужно:

1. fingerprint pinning для мостов
2. подписанные manifests
3. rate limiting и quotas
4. anti-enumeration mailbox API
5. replay protection на mailbox envelope level
6. abuse isolation по мостам и регионам
7. ротация bridge infrastructure без поломки клиентов

## 7.10 Observability и операции

Нужно:

1. метрики по каждому транспортному маршруту
2. success rate по странам/ASN/типам сети
3. bridge saturation metrics
4. mailbox lag metrics
5. alerting на массовое выпадение мостов
6. безопасные логи без утечек пользовательских данных

## 7.11 WARP как optional bootstrap/evasion layer

Нужно:

1. определить модель поддержки WARP:
   - внешнее приложение по deep link
   - системный companion mode через Android VpnService
   - только операторская рекомендация, без встроенной интеграции
2. использовать WARP только для:
   - bootstrap bridge manifest
   - первого соединения с mailbox/control plane
   - аварийного выхода из полной блокировки
3. не завязывать штатную доставку сообщений на постоянное наличие WARP
4. добавить telemetry:
   - был ли задействован WARP-assisted bootstrap
   - помог ли он получить bridge manifest и рабочий маршрут
5. продумать UX:
   - “попробовать через WARP”
   - “после bootstrap вернуться на собственные мосты ADA”

Definition of done:

- ADA умеет использовать WARP как optional helper для входа в сеть, но после bootstrap продолжает жить на собственных bridge-маршрутах.

## 7.12 Масштаб контактов, self-healing сети и немедленная синхронизация

Первичный фокус этого блока: устранить деградацию при большом числе контактов и сценарий "оба устройства в сети, но доставка застряла".

Нужно:

1. Ввести `ConnectionSupervisor` как отдельный runtime-компонент.
2. Ввести state machine канала доставки: `Healthy` -> `Degraded` -> `Recovering` -> `Healthy`.
3. Убрать N-соединений на N-контактов: держать активные сессии только для "горячего" множества диалогов, остальные — по требованию.
4. Перевести синхронизацию списков диалогов и контактов на дельта-модель (watermark/revision), а не full refresh.
5. После любого reconnect выполнять fast-resync по недостающим диапазонам сообщений с idempotent dedup на клиенте.
6. Разделить очереди на приоритеты: текст/ACK/control выше файлов и фоновых синков.
7. Добавить адаптивные таймауты и jittered backoff по маршрутам с учетом качества сети и истории ошибок.
8. Добавить "быструю смену маршрута" при признаках ложного онлайна: параллельный probe альтернативного route без долгого ожидания полного timeout.
9. Ввести quorum-детекцию "сеть есть, но путь мертв":
   - есть локальная сеть (OS says online)
   - но handshake/ACK не проходят в SLA
   - и не отвечает минимум один fallback route
10. Ввести session-resume для bridge/WebSocket и отдельный keepalive-профиль для constrained сетей.

Детализация реализации:

1. Контакты и диалоги:
   - хранить `contacts_revision` и `dialogs_revision`
   - получать изменения пачками (paging + cursor)
   - применять merge локально без полной перестройки списка
2. Канал доставки:
   - при `Degraded` запускать короткий recovery-loop: probe relay, probe bridge, затем mailbox
   - при `Recovering` разрешать dual-path на коротком окне, чтобы не терять исходящие ACK/control кадры
3. Синхронизация сторон после восстановления:
   - обмен `last_applied_seq`/`last_acked_seq` по каждому диалогу
   - pull недостающих сообщений диапазонами
   - клиентская dedup-защита по `message_id` + anti-replay окну
4. UX и статус:
   - статус "восстанавливаем канал" вместо немой ошибки
   - статус "синхронизация диалогов" с прогрессом по диапазонам
   - приоритетная доставка новых текстовых сообщений даже во время catch-up

Definition of done:

1. При большом контакт-листе reconnect не инициирует полный ресинк всех диалогов по умолчанию.
2. Для деградированной сети существует автоматический recovery-loop без ручного вмешательства пользователя.
3. После восстановления пути обе стороны приходят к согласованному состоянию по `last_acked_seq` без дублирования сообщений.
4. Падение одного маршрута не блокирует отправку текста дольше, чем требуется на быстрый переход к следующему маршруту.
5. В telemetry есть явные метрики: `recovery_time_ms`, `resync_backlog_count`, `false_online_detected_total`, `route_flaps_total`.

---

## 8. Пошаговый план реализации

Ниже фазы описаны уже не как обзорный roadmap, а как рабочая фазировка. Для каждой фазы зафиксированы:

1. входные зависимости
2. основные потоки работ
3. артефакты на выходе
4. gate, без которого нельзя считать фазу завершенной

Текущий статус на 2026-04-13:

1. Фаза 0 уже реализована и валидирована.
2. Поверх фаз 1-5 уже влит единый вертикальный срез: `TransportRouter`, live bridge delivery, mailbox/store-and-forward, signed manifest bootstrap/cache, capability-based degradation для звонков и больших вложений, а также расширенный Android runtime status.
3. Референсный bridge/mailbox backend и бинарь `ada-bridge-node` уже присутствуют в репозитории и собираются.
4. Android-side bootstrap/import для bridge manifest теперь существует как реальный путь: paste/link import, file import и QR import ведут в один и тот же verify/apply flow.
5. Trust anchors и manifest URLs, добавленные через bootstrap, теперь сохраняются локально в `data_dir`, поэтому после перезапуска клиент может продолжать cache-restore и последующий manifest refresh без повторного ручного ввода.
6. В рамках фазы 5 уже добавлены unit-level проверки для manifest signing/verification, mailbox fingerprint semantics и базового поведения reference backend.
7. В рамках фазы 5 теперь появился и первый hostile-network harness на уровне Rust integration tests: blocked-QUIC -> live bridge, allowlist-only -> mailbox/store-and-forward и fallback с нерабочего bridge-профиля на рабочий.
8. Для локального прогона harness добавлен runner `run-hostile-network-harness.ps1`, который запускает только hostile-network сценарии поверх `cargo test --test integration`, а CI теперь гоняет hostile-network набор отдельным явным шагом.
9. Bridge/client observability для phase 5 теперь закрыта кодом: client-side `get_bridge_status_json()` экспортирует route success telemetry и mailbox high-water mark, а reference bridge backend отдаёт `/ops/status` и `/healthz`.
10. Repo-side phase 5 артефакты теперь присутствуют явно: [SECURITY_REVIEW_MAILBOX_MANIFEST.md](SECURITY_REVIEW_MAILBOX_MANIFEST.md), [OPS_DASHBOARDS_ALERTING.md](OPS_DASHBOARDS_ALERTING.md) и [CANARY_ROLLBACK_PLAYBOOK.md](CANARY_ROLLBACK_PLAYBOOK.md).
11. Оставшийся operational tail относится уже не к отсутствующим артефактам в репозитории, а к выполнению реального canary/device-matrix на живой fleet и устройствах.
12. Replay-risk из security review закрыт кодом: bridge register/push/pull/ack теперь подписывают fresh nonce/timestamp auth payload, а backend отклоняет stale auth и seen nonce; валидация после hardening: `cargo test --lib` -> `84 passed`, hostile-network harness -> `3 passed`, `cargo build --bin ada-bridge-node` -> success.
13. Explicit mailbox/register rate limiting теперь тоже закрыт кодом: reference bridge backend применяет per-IP и per-peer token bucket до auth verify, экспортирует `rate_limited_total`/`http_rate_limited_total`/`ws_rate_limited_total` в `/ops/status`, а дальнейший хвост по теме сводится уже к production calibration на canary telemetry.
14. В рамках блока 7.12 в core уже внедрены базовые runtime-механизмы устойчивости: connection health state machine (`healthy/degraded/recovering`), fast-resync после bridge reconnect, детекция false-online при fallback `iroh -> bridge`, throttling контактных warmup-подключений при большом контакт-листе и первичная peer-to-peer delta sync (`SyncRequest`/`SyncResponse`) с dedup и telemetry.
15. Для больших диалогов и dead-route сценариев 7.12 дополнительно закрыт следующий слой: cursor/watermark pagination в `SyncRequest`/`SyncResponse` (без раздувания `known_message_ids` на каждой итерации) и quick iroh failover timeout, который ускоряет переключение на bridge path при живой сети и мертвом текущем маршруте.
16. Следующие фазовые блоки ниже теперь нужно читать как historical rollout/checklist: пункты про `TransportRouter`, `connect_via_best_transport()`, `relay_only`, mailbox backend и route outcome уже закрыты кодом и тестами; незакрытый хвост сместился в bridge-level tests, device/canary validation, calls/files degradation и operational calibration.

## Фаза 0 — Честная стабилизация текущего антицензурного слоя

> Historical rollout record: фаза уже в основном закрыта кодом. Актуальный хвост — не реализация `relay_only`/router, а регулярная canary/device validation, operational calibration и расширение degraded calls/files сценариев.

Цель: перестать считать готовым то, что еще не включено в боевой маршрут.

Вход в фазу:

1. Текущий iroh-only send path уже работает в прод-пути.
2. В коде уже есть `BridgeManager`, bridge UI и bridge tunnel primitives.
3. Документация и код пока расходятся по степени готовности антицензурного слоя.

Задачи:

1. Обновить документацию под реальное состояние iroh-only.
2. Убраны устаревшие сетевые fallback сценарии.
3. Реализовать настоящую семантику `relay_only`.
4. Добавить transport-level logging и route outcome.
5. Зафиксировать модель WARP: внешний helper, optional companion или отказ от встроенной интеграции.

Потоки работ:

1. Core:
   - ввести единый `TransportOutcome`
   - пометить все текущие исходы как direct, relay или offline queue
   - реализовать запрет direct path при `relay_only = true`
2. Android:
   - показать честные route statuses без маркетинговых формулировок
   - привязать UI relay-only к реальной transport policy
3. Docs/ADR:
   - выпустить короткий ADR по роли WARP
   - зафиксировать, что fronting/meek пока не являются production-path
4. QA:
   - собрать сетевые regression-сценарии на direct leak
   - проверить, что route outcome действительно отражает реальный путь

Артефакты фазы:

1. Обновленный план и сопутствующая документация без расхождения с кодом.
2. Реальная реализация `relay_only`.
3. Единая outcome-модель доставки, видимая в логах и UI.
4. Принятое решение по WARP-support model.

Gate выхода:

1. Любая отправка текста помечается route outcome, а не просто success/failure.
2. При `relay_only = true` direct route не наблюдается в сетевых тестах.
3. Команда согласовала, поддерживается ли WARP как external helper, companion mode или не поддерживается вообще.

Вне рамок фазы:

1. Mailbox backend.
2. Production bridge delivery через WebSocket.
3. Деградация файлов и звонков.

Выход фазы:

- продукт честно понимает, где он direct, где relay, где offline queue

## Фаза 1 — Bridge transport для текста

> Historical rollout record: WebSocket TLS bridge fallback, route ladder and mailbox-oriented bridge delivery now exist in code. Read this section as implementation history; current work is canary/device validation and fleet operations.

Цель фазы была сделать текстовые DM реально проходящими через bridge transports.

Вход в фазу:

1. Введен `TransportOutcome` и есть route-level logging.
2. `relay_only` работает как реальная policy, а не как UI-флаг.
3. Принято решение, что WebSocket — первый production bridge path.

Итог по задачам:

1. `TransportRouter` и route ladder вынесены в core.
2. WebSocket TLS bridge подключен как первый боевой fallback.
3. Domain Fronting / Meek / obfs4 подключены как дополнительные canary profiles, not primary production paths.
4. Bridge send/recv для message envelopes и mailbox delivery присутствуют.
5. Route outcome, retries and status telemetry присутствуют.
6. WebSocket route подготовлен к mailbox use-case: auth, keepalive, session reuse.
7. Recovery-loop теперь остаётся областью device/canary validation и operational tuning.
8. Приоритет control/text кадров остаётся tuning-задачей, если canary покажет head-of-line blocking при reconnect.

Потоки работ:

1. Core:
   - `TransportRouter` вынесен из текущего send path
   - `connect_via_best_transport()` встроен в route ladder
   - wire envelope send/recv через WebSocket TLS доведён для text path
2. Android:
   - показать route status для bridge delivery
   - добавить безопасный ручной override только если он нужен для диагностики
3. Security:
   - ввести базовый fingerprint pinning для WebSocket bridge endpoints
   - зафиксировать auth-модель bridge session
4. QA:
   - тесты на сценарий “iroh недоступен, bridge доступен”
   - тесты на timeout, retry, circuit breaker и возврат в offline queue

Артефакты фазы:

1. `TransportRouter` как единая точка принятия route-решений.
2. Реально работающий WebSocket TLS fallback для текстовых DM.
3. Базовая модель retries, backoff и circuit breaker.
4. Набор интеграционных тестов на bridge-text delivery.

Gate выхода:

1. Текстовые DM проходят через WebSocket bridge при недоступном iroh.
2. Пользовательский статус различает relay, bridge и offline queue.
3. Повторные отправки не создают бесконтрольного storm-поведения при плохом bridge.

Вне рамок фазы:

1. Полный mailbox/store-and-forward.
2. Полноценная эксплуатационная ротация мостов.
3. Большие файлы и звонки.

Выход фазы:

- текстовые сообщения работают через мост даже если iroh недоступен

## Фаза 2 — Mailbox/store-and-forward для extreme censorship

Цель: убрать требование одновременной прямой доступности обоих клиентов.

Вход в фазу:

1. `TransportRouter` уже умеет bridge fallback для текстовых сообщений.
2. Формат bridge-envelope стабилизирован хотя бы для DM.
3. Принято решение, что продукт допускает серверную bridge-plane.

Задачи:

1. Спроектировать mailbox API.
2. Реализовать референсный bridge backend.
3. Встроить push/pull/ack в core.
4. Добавить TTL, quotas, anti-abuse.
5. Протестировать сценарий “оба клиента только через allowlisted HTTPS”.
6. Добавить fast-resync после reconnect: watermark-based pull диапазонов, dedup и подтверждение согласованного состояния сторон.

Потоки работ:

1. Backend:
   - реализовать `push`, `pull`, `ack`
   - хранить opaque envelopes по mailbox key
   - добавить TTL, dedup, quota, anti-abuse
2. Core:
   - поддержать `MailboxBridge` как полноценный route
   - добавить polling или long-poll/WebSocket pull semantics
   - корректно синхронизировать ack, retries и local queue
3. Android:
   - показать статус “доставляется через mailbox”
   - предусмотреть экономный фоновой режим poll/refresh
4. Security/QA:
   - replay-protection
   - anti-enumeration проверки
   - e2e сценарий “оба клиента без direct P2P”

Артефакты фазы:

1. Спецификация mailbox API.
2. Референсный backend mailbox/store-and-forward.
3. Новый route `MailboxBridge` в core.
4. Интеграционные e2e тесты для allowlist-only текстового обмена.

Gate выхода:

1. Два клиента обмениваются текстом без прямого P2P и без одновременной online-доступности.
2. Mailbox envelopes имеют TTL, dedup и ack semantics.
3. При полном отсутствии live route сообщение остается доставляемым через mailbox path.

Вне рамок фазы:

1. Ротация bridge manifests.
2. Большие вложения как штатный mailbox-path.
3. Realtime calls.

Выход фазы:

- ADA доставляет текст даже в почти полностью закрытой сети

## Фаза 3 — Bridge control plane и ротация инфраструктуры

Цель: сделать систему эксплуатационно живой.

Вход в фазу:

1. Уже существует хотя бы один рабочий bridge path.
2. Mailbox backend или его API-форма уже стабилизированы.
3. Команда готова поддерживать server-side manifest publishing и key rotation.

Задачи:

1. Подписанный bridge manifest.
2. Несколько каналов доставки manifest.
3. Кэширование и versioning.
4. Ротация мостов без релиза APK.
5. Bridge import через QR/link/file.
6. WARP-assisted bootstrap для получения manifest в полностью закрытых сетях.

Потоки работ:

1. Control plane/backend:
   - схема `bridge-manifest.json`
   - подпись, versioning, TTL, revoke-процедуры
   - несколько каналов публикации и зеркала
2. Core/Android:
   - валидация подписи manifest
   - локальный кэш, fallback на прошлую версию
   - import manifest по QR, deeplink, файлу
3. WARP/bootstrap:
   - реализовать или формально описать WARP-assisted bootstrap flow
   - не смешивать WARP с постоянной доставкой сообщений
4. Ops/SRE:
   - процесс ротации bridge endpoints
   - emergency replacement при блокировке или компрометации мостов

Артефакты фазы:

1. Подписанный manifest-формат.
2. Пайплайн публикации и ротации bridge endpoints.
3. Клиентский import/cache/verify путь для manifest.
4. Документированный emergency bootstrap flow, включая WARP если он принят.

Gate выхода:

1. Мост можно заменить без релиза APK.
2. Клиент корректно переживает недоступность одного канала доставки manifest.
3. Команда умеет аварийно обновить bridge fleet и довести manifest до клиентов.

Вне рамок фазы:

1. Полная оптимизация больших вложений.
2. Media relay для звонков.

Выход фазы:

- инфраструктура мостов может меняться быстрее, чем цикл релиза клиента

## Фаза 4 — Вложения и деградация продукта

Цель: довести продукт до usable состояния под цензурой, а не только транспорт до demo.

Вход в фазу:

1. Текстовый обмен уже стабильно работает через bridge или mailbox.
2. Route statuses и manifest-ротация уже существуют.
3. Команда готова честно урезать функциональность там, где сеть realtime не тянет.

Задачи:

1. Text-first UX.
2. Deferred attachments.
3. Ограничение больших файлов в жестких сетях.
4. Явная деградация звонков.
5. Прозрачные статусы и понятные подсказки пользователю.

Потоки работ:

1. Core:
   - разделить metadata и payload route-поведение для файлов
   - добавить deferred/resumable semantics для вложений
   - ввести capability flags для звонков и больших файлов
2. Android UX:
   - показать понятные статусы “текст пройдет, медиа ограничены”
   - добавить onboarding для режима жесткой цензуры
   - убрать silent-hang поведение в отправке файлов и call startup
3. Backend:
   - если выбран attachment bridge path, ввести quotas и явные лимиты
   - отделить text mailbox от тяжелого payload traffic
4. QA/Product:
   - сценарии с деградацией функций на плохой сети
   - проверка copy и UX на понятность без сетевых терминов

Артефакты фазы:

1. Text-first UX для жестко цензурируемой сети.
2. Deferred attachment flow.
3. Явные capability/availability статусы для звонков.
4. Продуктовая матрица деградации по типам сети.

Gate выхода:

1. Пользователь не сталкивается с молчаливым зависанием отправки.
2. Текст всегда имеет приоритет над медиа.
3. Звонки либо работают в поддерживаемой сети, либо честно отключаются.

Вне рамок фазы:

1. Массовый rollout.
2. Финальный security sign-off всей серверной плоскости.

Выход фазы:

- продукт ведет себя предсказуемо, а не “тихо висит”

## Фаза 5 — Тестирование и эксплуатационная готовность

Цель: превратить систему в поддерживаемый продукт.

Вход в фазу:

1. Есть рабочие пути direct, relay, bridge и mailbox хотя бы для текста.
2. Есть control plane, manifest rotation и базовая telemetry.
3. UX-деградация для тяжелых функций уже определена.

Задачи:

1. Сетевой test harness для allowlist-сценариев.
2. Device matrix тестов.
3. Chaos testing bridge outage.
4. Latency / throughput profiling.
5. Security review mailbox API.
6. Canary rollout и мониторинг.

Потоки работ:

1. QA/Infra:
   - построить эмуляцию allowlist-сетей, blocked QUIC и hostile proxy profiles
   - собрать device/network matrix
2. Security:
   - review mailbox API
   - review manifest signing, pinning и replay-protection
3. SRE/Ops:
   - дашборды route success rate, mailbox lag, bridge saturation
   - alerting и incident response playbooks
4. Release:
   - canary rollout
   - контролируемое расширение bridge fleet
   - критерии rollback при массовой блокировке

Артефакты фазы:

1. Test harness для цензурных сценариев. Первая воспроизводимая версия уже есть в Rust integration tests, локальном runner `run-hostile-network-harness.ps1` и отдельном CI-шаге hostile-network harness.
2. Security review report: [SECURITY_REVIEW_MAILBOX_MANIFEST.md](SECURITY_REVIEW_MAILBOX_MANIFEST.md).
3. Операционные дашборды и alerting: [OPS_DASHBOARDS_ALERTING.md](OPS_DASHBOARDS_ALERTING.md).
4. Canary/rollback playbook: [CANARY_ROLLBACK_PLAYBOOK.md](CANARY_ROLLBACK_PLAYBOOK.md).

Gate выхода:

1. Route success и mailbox lag наблюдаемы в эксплуатации.
2. Есть документированный план действий при массовом блоке мостов.
3. Продукт проходит canary без критических regressions по доставке текста.

Вне рамок фазы:

1. Новые экспериментальные транспорты.
2. Расширение scope beyond censorship-resilient messaging.

Выход фазы:

- можно выпускать как anti-censorship продукт, а не как исследовательский прототип

## 8.1 Межфазовые зависимости

1. Фаза 0 является жестким prerequisite для Фазы 1, потому что без честного `relay_only` и route outcome невозможно проверить реальную маршрутизацию.
2. Фаза 1 является жестким prerequisite для Фазы 2, потому что mailbox должен встраиваться в уже существующий `TransportRouter`, а не жить отдельным костылем.
3. Фазы 2 и 3 частично допускают параллелизацию после фиксации envelope format и API boundary.
4. Фаза 4 должна начинаться только после стабилизации текста через bridge или mailbox, иначе UX будет строиться поверх нестабильной сетевой модели.
5. Фаза 5 начинается подготовительно раньше, но финальный sign-off имеет смысл только после завершения Фазы 4.

## 8.2 Что можно делать параллельно

1. Во время Фазы 1 backend-команда уже может черново проектировать mailbox API.
2. Во время Фазы 2 можно параллельно делать schema и signing pipeline для bridge manifest.
3. Во время Фазы 3 Android-команда может готовить import UX и onboarding под будущую деградацию.
4. Во время Фазы 4 SRE может заранее собирать route-level dashboards и outage playbooks.

## 8.3 Минимальный срез до первого боевого релиза

Если нужен не полный стек, а первый реально полезный релиз для жесткой цензуры, то обязательный минимум такой:

1. Фаза 0 закрыта кодом; нужен регулярный regression/canary прогон.
2. Фаза 1 закрыта для WebSocket TLS как primary production bridge; fronting/meek/obfs4 остаются canary/field-validation routes.
3. Фаза 2 закрыта для текстовых DM через bridge/mailbox; production gate — device/fleet telemetry.
4. Из Фазы 3 — только manifest signing, cache и emergency rotation.
5. Из Фазы 4 — только text-first UX и честная деградация звонков/больших файлов.

Все остальное можно докручивать уже после первого боевого запуска.

---

## 9. Приоритетность по бизнес-ценности

Если делать не все сразу, а в правильном порядке, то приоритет должен быть таким:

1. Регулярный hostile-network + device/canary matrix для text delivery, mailbox lag и bridge failover.
2. WebSocket TLS bridge fleet rollout, monitoring, abuse/rate-limit calibration and manifest rotation discipline.
3. Текст-first UX: честно показывать route degradation, mailbox lag, relay_only scope and realtime availability.
4. Degraded files/calls matrix: capability gating, retry/recovery loops, user-facing limitations.
5. Fronting / Meek / obfs4 как дополнительные профили только после WebSocket TLS/mailbox baseline.
6. WARP-assisted bootstrap как optional helper, not ADA transport layer.

Старые пункты `TransportRouter`, `relay_only`, WebSocket fallback, mailbox backend and signed manifests now exist in code; remaining business value is proving and operating them.

---

## 10. Критические продуктовые решения, которые надо принять заранее

### Решение 1 — Готовы ли мы к серверной плоскости мостов

Для true extreme censorship ответ должен быть “да”.

Если ответ “нет”, то продукт нужно честно позиционировать как:

- устойчивый к умеренной цензуре
- но не гарантированно работающий в настоящем allowlist-режиме

### Решение 2 — Поддерживаем ли мы domain fronting как основную ставку

Классический fronting у многих крупных CDN давно ограничен. Поэтому основная ставка должна быть не на магию fronting, а на:

1. first-party HTTPS-friendly bridge endpoints
2. WebSocket TLS под разрешенным доменом
3. Meek-like camouflage
4. ротацию и разнообразие каналов

### Решение 3 — Поддерживаем ли мы WARP как опциональный bootstrap-режим

Рекомендуемый ответ: “да, как optional helper, но не как обязательную зависимость продукта”.

Правильная роль WARP:

1. emergency bootstrap
2. загрузка bridge manifests
3. восстановление первого канала к mailbox/control plane

Неправильная роль WARP:

1. основной transport ADA
2. обязательный для всех пользователей путь
3. замена собственным bridge-маршрутам

### Решение 4 — Что является минимально достаточным продуктом

Рекомендуемый честный MVP для extreme censorship:

1. текстовые DM
2. delivery receipts
3. малые вложения по желанию
4. без обязательства realtime calls

---

## 11. Определение готовности продукта

ADA можно считать реально готовой к жесточайшей цензуре, когда выполнены все условия ниже.

### Сетевые критерии

1. Два клиента могут обмениваться текстом без прямого P2P.
2. Хотя бы один bridge transport работает поверх стандартного HTTPS-подобного профиля.
3. Bridge infrastructure может ротироваться без релиза клиента.
4. Relay-only не допускает случайного direct leak.

### Продуктовые критерии

1. Пользователь не обязан вручную разбираться в протоколах.
2. Приложение честно сообщает, каким способом идет доставка.
3. В жесткой сети сообщения не “висят молча”.
4. Звонки и большие файлы либо работают, либо отключаются честно и явно.

### Операционные критерии

1. Есть telemetry по маршрутам.
2. Есть ротация мостов.
3. Есть наблюдаемость mailbox lag и bridge health.
4. Есть документированный incident response при массовой блокировке мостов.

---

## 12. Рекомендуемый ближайший следующий шаг

Не начинать следующий этап с Meek, fronting и экзотики одновременно.

Правильный ближайший шаг:

1. Зафиксировать canary/device matrix для WebSocket TLS bridge + mailbox text delivery.
2. Прогнать relay_only/hostile-network сценарии на реальных Android 12/14 устройствах и сохранить baselines.
3. Настроить bridge fleet observability: route success, mailbox lag, rate limits, auth failures, saturation.
4. После этого расширять degraded files/calls и только затем включать fronting/meek/obfs4 в canary cohorts.
5. Параллельно зафиксировать модель WARP как external helper, а не как core dependency.

Именно после появления mailbox-слоя можно будет утверждать, что ADA движется от “антицензурного P2P-мессенджера” к “продукту для белых списков и почти полной блокировки”.

---

## 13. Короткая формула проекта

Если сформулировать задачу в одном абзаце:

> ADA должна перестать зависеть от предположения, что два клиента могут установить прямое соединение, и перейти к модели, где direct P2P — это лучший случай, а bridge-assisted encrypted delivery через разрешенные HTTPS-совместимые маршруты — полноценный штатный режим работы.
