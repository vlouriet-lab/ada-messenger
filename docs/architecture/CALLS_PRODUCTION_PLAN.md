# ADA Calls Production Plan

Дата: 2026-05-13

## Цель

Довести 1:1 аудио/видеозвонки до production-grade уровня: core должен честно решать, когда realtime возможен, Android не должен запускать WebRTC в неподходящей сети, а вся цепочка signaling/media должна быть наблюдаемой, тестируемой и предсказуемо деградировать.

## Текущий срез

1. Rust core уже ведет call state, валидирует SDP и отправляет signaling как `CallSignaling` через transport router.
2. Android уже использует нативный WebRTC SDK для media, ICE, restart и renderer lifecycle.
3. Bridge status уже содержит `capabilities.realtime_calls`, но Android мог получить только `null` call id и закрыть звонок без ясной причины.

## Фазы доведения

### P0. Честная доступность звонков

Статус: начато.

Definition of done:

1. Core отдает стабильный JSON `get_call_availability_json()`.
2. FFI/JNI/Android могут проверить availability до создания PeerConnection.
3. Пользователь получает явную ошибку, если текущий route поддерживает только mailbox/store-and-forward.
4. Hostile-network тест проверяет, что relay-only без live bridge запрещает звонки с причиной.

### P1. Signaling reliability и call failure semantics

1. Добавить явные `CallFailed`/`CallUnavailable` events вместо silent background warnings при failed invite/answer/candidate delivery.
2. Сохранять call log для `timeout`, `network_unavailable`, `signaling_failed`, `declined`.
3. Развести retry policy: invite/answer, ICE candidates, hangup имеют разные SLA и route fallback.
4. Покрыть тестами invite delivery failure, answer delivery failure и late hangup over reliable routes.

### P2. Android media lifecycle hardening

1. Закрыть все ветки cleanup: failed createOffer, failed core prepare, failed answer, notification decline, ViewModel dispose.
2. Ограничить одновременные 1:1 звонки и входящие race-сценарии explicit state machine-ом.
3. Добавить UI state для unavailable/reconnecting/failed без молчаливого возврата в чат.
4. Расширить JVM/instrumentation тесты на call manager state transitions.

### P3. Observability

1. Core counters: `call_attempts_total`, `call_unavailable_total`, `call_signaling_failures_total`, `call_connected_total`, `ice_restart_total`, call duration buckets.
2. Android logs: short call id, route availability reason, WebRTC ICE state changes без SDP/PII.
3. Bridge/ops dashboard: realtime availability, ICE restart rate, failed setup rate.

### P4. Device/network matrix

1. Android 12/14 Wi-Fi and LTE 1:1 audio/video.
2. Relay-only + live bridge positive path.
3. Relay-only + mailbox-only negative path.
4. Network handoff Wi-Fi -> LTE with ICE restart.
5. App background incoming call notification and full-screen intent.

### P5. Group calls decision

Production 1:1 calls should not wait for group video. Group video needs a separate SFU/media-relay decision or explicit product cap. До этого групповые видеозвонки остаются limited/beta.

## Первый реализованный срез

1. Добавлен core availability JSON.
2. Availability протянут через FFI/JNI/Android.
3. Android preflight не создает WebRTC offer, если core говорит, что realtime недоступен.
4. Добавлен hostile-network assertion на причину `live_bridge_required`.