# Phase 5 Ops Dashboards and Alerting

Дата: 2026-04-14  
Актуализировано: 2026-05-14

## Data Sources

1. Client-side transport telemetry из `get_bridge_status_json()`:
   - `telemetry.route_success_total`
   - `telemetry.route_failure_total`
   - `telemetry.route_success_rate`
   - `telemetry.avg_latency_ms`
   - `telemetry.route_totals`
   - `bridge_mailbox_depth`
   - `telemetry.mailbox_depth_high_watermark`
2. Bridge backend ops endpoints:
   - `GET /ops/status`
   - `GET /healthz`
   - `counters.rate_limited_total`
   - `counters.http_rate_limited_total`
   - `counters.ws_rate_limited_total`
3. Hostile-network CI/harness:
   - `cargo test --test integration hostile_network_ -- --nocapture`
   - `run-hostile-network-harness.ps1`

## Required Dashboards

### 1. Route Success

Панели:

1. total outcome rate;
2. success vs failure;
3. breakdown by route:
   - `iroh_live`
   - `bridge_websocket_tls`
   - `bridge_domain_front`
   - `bridge_meek`
   - `bridge_obfs4`
   - `mailbox_bridge`
   - `offline_queue`
   - `failed`

Operational maturity note: `bridge_websocket_tls`, `mailbox_bridge`, `offline_queue` and `iroh_live` are the primary production signals. `bridge_domain_front`, `bridge_meek` and `bridge_obfs4` must stay visible in dashboards, but they are trial/field-validation routes until canary evidence proves them in real networks. The current `obfs4` implementation is ADA's lightweight obfuscator, not full Tor obfs4.

Primary signals:

1. `telemetry.route_success_rate`
2. `telemetry.route_totals`

### 2. Mailbox Lag and Depth

Панели:

1. current `bridge_mailbox_depth` on clients;
2. `telemetry.mailbox_depth_high_watermark`;
3. bridge-side `oldest_mailbox_age_ms`;
4. bridge-side `total_queued_envelopes` and `active_mailbox_peers`.

Primary signals:

1. `/ops/status.oldest_mailbox_age_ms`
2. `/ops/status.total_queued_envelopes`
3. `/ops/status.active_mailbox_peers`

### 3. Bridge Saturation and Health

Панели:

1. `/ops/status.max_queue_utilization_pct`
2. `/ops/status.delivery.live_delivery_rate`
3. `/ops/status.delivery.mailbox_offload_rate`
4. `/ops/status.counters.auth_failures_total`
5. `/ops/status.counters.rate_limited_total`, `/ops/status.counters.http_rate_limited_total`, `/ops/status.counters.ws_rate_limited_total`
6. `/healthz.status` and `/healthz.mailbox_lag_state`

## Alert Thresholds

### P1

1. `route_success_rate < 0.80` for 10 minutes on canary cohort.
2. `/healthz.status = degraded` on 30%+ bridge fleet.
3. `auth_failures_total` burst above baseline by 5x for 5 minutes.
4. `rate_limited_total` burst above baseline by 5x for 5 minutes.

### P2

1. `oldest_mailbox_age_ms > 300000` on any production bridge.
2. `max_queue_utilization_pct > 90` for 10 minutes.
3. mailbox offload rate spikes unexpectedly while live delivery rate collapses.
4. `ws_rate_limited_total` grows while route success stays flat or declines.

### P3

1. `bridge_mailbox_depth_high_watermark` grows release-over-release.
2. average transport latency regresses by 30%.

## Device and Network Matrix

Минимальный matrix для каждого release candidate:

1. Android 12 device, Wi-Fi, normal network.
2. Android 12 device, LTE, relay_only enabled.
3. Android 14 device, Wi-Fi, blocked QUIC hostile harness path.
4. Android 14 device, LTE, allowlist-only mailbox path.
5. Cross-device A12 <-> A14 text delivery under hostile-network harness.

Operational note:

1. Для device diagnostics сохраняется clean cycle: uninstall app, install fresh APK, run test/log capture.

## Chaos Testing Procedure

1. Поднять `ada-bridge-node` и убедиться, что `/healthz` возвращает `ok`.
2. Запустить hostile-network harness.
3. Во время live bridge scenario остановить bridge node.
4. Проверить, что:
   - client route shifts from live bridge to mailbox/offline semantics predictably;
   - `/healthz.status` меняется на `degraded` или bridge становится unreachable;
   - canary alert fires within SLA.
5. Поднять bridge обратно и проверить recovery mailbox lag.

## Latency and Throughput Profiling

### Text path

1. Run hostile-network harness three times подряд.
2. Capture:
   - `telemetry.avg_latency_ms`
   - route mix
   - `/ops/status.oldest_mailbox_age_ms`

### Bridge backend

1. Start `ada-bridge-node`.
2. Sample `GET /ops/status` every 5-10 seconds during load.
3. Record:
   - `push_total`
   - `live_delivery_rate`
   - `mailbox_offload_rate`
   - `max_queue_utilization_pct`

## Operator Commands

```powershell
./run-hostile-network-harness.ps1
```

Если PowerShell policy блокирует прямой запуск файла:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-hostile-network-harness.ps1
```

Bridge ops checks:

```powershell
Invoke-RestMethod http://127.0.0.1:8787/healthz
Invoke-RestMethod http://127.0.0.1:8787/ops/status
```