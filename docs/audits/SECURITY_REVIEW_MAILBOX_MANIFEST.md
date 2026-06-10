# Security Review: Mailbox API and Manifest Plane

Дата: 2026-04-14

## Scope

Этот review покрывает:

1. mailbox HTTP/WS API reference bridge backend;
2. signed bridge manifest bootstrap, pinning и local trust anchors;
3. replay-protection и dedup semantics по delivery plane.

## Implemented Controls

1. Manifest signing использует Ed25519, а клиент принимает manifest только от доверенных public keys.
2. Bridge runtime проверяет pinned fingerprint bridge node при register/push flow.
3. Mailbox хранит opaque encrypted wire payloads, а не plaintext messages.
4. Mailbox queue реализует TTL, dedup по message_id и quota per peer.
5. Клиент уже отбрасывает replay на уровне incoming message IDs и проверяет подписи peer payloads.
6. Bridge auth challenges теперь несут short-lived `nonce` + `timestamp_ms`, а backend отклоняет stale timestamps и повторное использование seen nonce на WS register и HTTP mailbox API.
7. Reference bridge backend теперь применяет explicit token-bucket rate limiting по IP и peer identity на `/mailbox/*` и `/ada` register path, причём лимит срабатывает до costly auth verify.
8. Ops endpoints reference bridge backend отделены от delivery plane и не участвуют в клиентском протоколе.

## Findings

### Low: Bridge auth freshness cache is node-local and restart-local

Текущие `register_challenge`, `http_push_challenge`, `http_pull_challenge` и `http_ack_challenge` уже включают `nonce` и `timestamp_ms`, а backend хранит short-lived seen-nonce state по peer identity.

Практический эффект:

1. Перезапуск reference bridge backend очищает in-memory seen-nonce cache, поэтому replay старого подписанного запроса теоретически снова возможен, но только пока он еще проходит freshness window.
2. При горизонтальном scale-out без shared nonce store или sticky peer routing возможен cross-node replay в пределах того же окна.
3. Это уже существенно уже, чем прежний детерминированный replay без bounded freshness.

Current mitigation:

1. bridge backend ограничивает auth freshness окном в 5 минут и reject'ит stale/future-skew запросы;
2. replay одного и того же auth payload режется seen-nonce cache на текущей ноде;
3. replay одной и той же mailbox push операции дополнительно режется dedup по `message_id`.

Required follow-up for horizontally scaled fleet:

1. shared nonce cache или sticky peer routing между bridge replicas;
2. отдельный alert на auth replay rejects при выходе за baseline.

### Low: Rate-limit tuning остаётся production calibration task

Базовый token bucket уже включён на mailbox HTTP и WS register path, но финальные лимиты пока откалиброваны консервативно для controlled canary, а не по production fleet telemetry.

Required follow-up:

1. откалибровать burst/refill against real canary traffic и reconnect patterns;
2. добавить alert на sustained `rate_limited_total`/`http_rate_limited_total`/`ws_rate_limited_total` выше baseline.

### Low: Ops endpoints expose fleet metadata

`/ops/status` и `/healthz` раскрывают queue depth, mailbox lag, live peer count и auth failure counters.

Required deployment rule:

1. публиковать их только во внутреннем admin ingress/VPN;
2. не отдавать эти endpoints в общий интернет.

### Low: Insecure bridge profiles remain acceptable only for local/dev harness

Флаг `insecure` полезен для local test bridge и hostile-network harness, но не должен попадать в production manifest.

Required deployment rule:

1. production manifest signing pipeline должна reject'ить `insecure=true`.

## Verdict

Текущий стек достаточен для controlled canary текста через bridge/mailbox при следующих условиях:

1. только trusted manifest signers;
2. TLS-only production bridge profiles;
3. ops endpoints доступны только из admin perimeter;
4. rollout идёт по canary playbook с мониторингом mailbox lag и route success.

## Exit Criteria For Wide Rollout

Перед широким rollout должны быть закрыты:

1. automated manifest lint against insecure bridge entries;
2. для multi-node bridge fleet: shared или sticky auth freshness cache;
3. production calibration для rate-limit thresholds по итогам canary telemetry.