# Cloudflare Bootstrap

## Current Operational Status: Standby & Field Reality

> [!NOTE]
> **Автономия P2P и статус развёртывания:**
> В настоящее время мессенджер ADA **не требует наличия развернутых Cloudflare Workers или собственных VPS-нод** для полноценной работы.
> 
> * **Полевые испытания (включая РФ):** Прямой P2P-стек Iroh (QUIC, STUN, NAT hole-punching, pkarr DNS discovery) обеспечивает стабильную доставку текстовых сообщений 1:1, масштабируемый gossip в группах и сквозные WebRTC аудио/видео звонки даже в условиях жестких сетевых фильтров и цензуры (включая РФ) без использования VPN.
> * **Статус воркеров (Cold Standby):** Код в папке `cf-workers/` (`serverless-bridge-worker`, `ws-bridge-worker`, `manifest-worker`) на 100% написан, протестирован и находится в состоянии «горячего резерва».
> * **Триггеры для физического развёртывания:**
>   1. **Масштабирование оффлайн-доставки (Mailbox):** Когда потребуется гарантированная доставка сообщений адресатам, чьи устройства глубоко спят или выключены часами/днями (асинхронная очередь на Durable Objects SQLite).
>   2. **Эскалация цензуры (Блокировка QUIC/n0):** Если регуляторы начнут точечно глушить протокол Iroh QUIC или блокировать стандартные discovery-домены, развертывание Cloudflare Workers мгновенно активирует камуфляжный щит (Domain Fronting / WebSocket TLS over Anycast CDN).

## Goal

Support Cloudflare as an optional bring-your-own bootstrap lane for allowlist and aggressive censorship networks. ADA can import a signed bridge manifest over normal HTTPS, and that manifest can point at Cloudflare Worker/custom-domain bridge endpoints that expose `/ada`, `/mailbox/push`, `/mailbox/pull`, and `/mailbox/ack`.

The app does not require an ADA-operated Cloudflare deployment to ship. Users or organizations that already have their own trusted bridge operator can add a manifest URL and signer public key in Android Settings.

This is not a guarantee that every Cloudflare hostname is reachable in every allowlist. Treat `workers.dev`, custom domains, and any fronting assumptions as separate field-test targets. For production, prefer at least one custom domain on Cloudflare plus one fallback manifest URL.

## Optional Embedded Bootstrap

For first-party or managed deployments, the APK can optionally embed public bootstrap material:

1. `ADA_BUILTIN_MANIFEST_URLS` - one or more HTTPS manifest URLs.
2. `ADA_BUILTIN_MANIFEST_PUBLIC_KEYS` - one or more Ed25519 manifest signer public keys in 32-byte hex.

Never embed the manifest signing seed or any Worker admin token.

Generate local production bootstrap material once per environment:

```powershell
.\scripts\New-ProductionBootstrapSecrets.ps1
```

This writes the secret signing seed to `production\manifest-signing-seed.hex` and writes public values to `production\manifest-public-key.hex` and `production\bridge-fingerprint.hex`. The `production\` directory is ignored by git.

Example PowerShell build environment:

```powershell
$env:ADA_BUILTIN_MANIFEST_URLS = 'https://ada-manifest.example.workers.dev/manifest.json;https://manifest.example.com/manifest.json'
$env:ADA_BUILTIN_MANIFEST_PUBLIC_KEYS = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
.\android-app\build-android.ps1 -Release -RequireBootstrapManifest
```

For the general release build, leave those variables empty and do not pass `-RequireBootstrapManifest`. Users can later add their own manifest URL and trusted public key from Settings -> Communication -> Custom bridge bootstrap.

## Multiple Operators (Federated Manifests)

Manifests are **additive**. Several independent operators can each publish their own
signed manifest and every one of them contributes bridges to the same runtime set:

1. `fetch_bridge_manifest()` requests **every** configured URL in one pass and installs
   each manifest that verifies. One operator being unreachable (censored, black-holed,
   temporarily down) no longer hides the other operators' bridges. The call fails only
   when no source could be installed at all.
2. Each verified manifest is stored under the **publisher key** that signed it, so
   republishing one operator's manifest only replaces that operator's own bridges.
   Bridges a new version drops are retired; bridges advertised by another operator are
   untouched, and a manifest can never remove bridges configured by hand.
3. A manifest whose `version` is lower than the version already stored for the same
   publisher is ignored, so replaying a captured older manifest cannot retire live
   bridges.
4. The bootstrap cache (`bridge_manifest_cache.json` in the data directory) holds one
   signed document per publisher. Files written by older builds (a single document) are
   still read.

Trust is explicit per operator: add every publisher you want to follow to
`ADA_BUILTIN_MANIFEST_PUBLIC_KEYS` at build time, or import each manifest from
Settings -> Communication -> Custom bridge bootstrap. Following a second operator is a
deliberate act; a manifest cannot add a publisher for itself.

`get_bridge_status_json()` reports:

- `manifest` — conservative aggregate over every manifest in force: `count`, the largest
  `version`, the **smallest** `ttl_secs` and `max_attachment_bytes`, comma-separated
  `source`, and `supports_realtime_calls` true when any operator supports it.
- `manifests` — one entry per publisher (`publisher_key`, `version`, `bridge_count`,
  `source`, `applied_at_ms`), plus `manifest_count`.

Operators remain separate trust domains: merging bridge sets does not merge operator
identity, and one operator's signature never vouches for another operator's bridges.
Bridges from different operators compete only through `priority`, exactly as if they
had been configured by hand.

## Worker Setup

1. Deploy `cf-workers/serverless-bridge-worker`.
2. Set `BRIDGE_FINGERPRINT_HEX` to a nonzero 32-byte hex fingerprint. This exact value must be present in bridge manifest entries.
3. Keep `BRIDGE_PATH=/ada` unless the manifest uses another path-compatible deployment.
4. Tune `MAX_QUEUE_PER_PEER` for mailbox capacity and abuse resistance.
5. Tune the token-bucket limits if canary telemetry shows legitimate reconnect bursts are being throttled:
  - `MAILBOX_IP_RATE_CAPACITY` / `MAILBOX_IP_RATE_REFILL_PER_SEC`
  - `MAILBOX_PEER_RATE_CAPACITY` / `MAILBOX_PEER_RATE_REFILL_PER_SEC`
  - `WS_REGISTER_IP_RATE_CAPACITY` / `WS_REGISTER_IP_RATE_REFILL_PER_SEC`
  - `WS_REGISTER_PEER_RATE_CAPACITY` / `WS_REGISTER_PEER_RATE_REFILL_PER_SEC`
6. Optional mailbox knobs: `MAX_PULL_PER_REQUEST` (envelopes returned by one pull, default 64), `MAX_LIVE_BATCH` (envelopes handed to a live listener per drain, default 64), `MAX_WIRE_BYTES` (per-envelope ceiling in bytes, default 4 MiB) and `MAILBOX_TTL_MS` (fallback lifetime used only when an envelope carries no `expires_at`, default 48 h).

The serverless bridge worker supports live WebSocket bridge delivery and HTTP mailbox fallback. If a network blocks WebSocket upgrade but allows HTTPS POST, text delivery should still work through mailbox push/pull/ack.
`/ops/status.counters` exposes `rate_limited_total`, `http_rate_limited_total`, and `ws_rate_limited_total` for alerting alongside mailbox depth and delivery split metrics.

Local validation before deploy:

```powershell
Push-Location cf-workers\serverless-bridge-worker
npm test          # protocol + storage tests, no dependencies required
npm install
npm run check
npm run dry-run
Pop-Location
```

`npm test` covers two things that are easy to break silently and hard to notice in
the field:

- **Auth byte layout.** The signed challenge bytes are pinned against the Rust
  golden vector in `ada-core/src/bridge/mailbox.rs`
  (`http_auth_challenges_match_worker_manual_bincode_layout`). If either side
  changes the layout — including the `BridgeDeliveryLane` discriminant order —
  one of the two suites fails instead of bridge auth quietly rejecting every
  request.
- **Mailbox SQL.** `mailbox-store.js` runs against a real SQLite database
  (`node:sqlite`, built into Node 22+), so duplicate suppression, TTL eviction,
  delivery order, per-recipient isolation, ack semantics and replay-nonce
  reclamation are executed rather than assumed.

## Storage, TTL and delivery semantics

The bridge keeps one row per envelope in the Durable Object's SQLite storage, keyed
by `(recipient, message_id)`. Points worth knowing when operating it:

1. **Queue capacity is real.** `MAX_QUEUE_PER_PEER` used to be unreachable: the old
   layout rewrote one JSON array per recipient on every push, hitting the per-value
   size limit after a handful of envelopes (and costing O(n²) writes). Queue depth,
   `max_queue_depth` and `max_queue_utilization_pct` in `/ops/status` now report
   actual row counts.
2. **The server evicts by TTL.** The envelope's own `expires_at` drives eviction on
   every push and pull. An envelope the client can never consume — for example a
   wire kind it does not understand and therefore never acks — ages out instead of
   being redelivered on every reconnect. This is the safety valve that keeps one
   unackable envelope from wedging a mailbox.
3. **A full mailbox no longer blocks live delivery.** When the recipient is
   connected over WebSocket the envelope is handed over even at the cap; only a peer
   with no live session receives `429`.
4. **Replay nonces are persisted.** A replayed nonce is rejected across Durable
   Object restarts, not only while the instance stays warm. Nonces are recorded only
   after the signature verifies, so an unauthenticated caller cannot burn a
   legitimate peer's nonces and lock it out.
5. **Batches drain eagerly, within a window.** On register and after every ack the
   object tops a live listener up to `MAX_LIVE_BATCH` unacked envelopes, so a
   mailbox larger than one batch does not stall until the next reconnect. It only
   refills free slots — never re-pushing what is already in flight — and releases
   slots whose envelope was acked or TTL-evicted, so one unackable envelope cannot
   starve the window.

### Upgrading an existing deployment

The Durable Object class keeps its name and its SQLite storage backend, so no
wrangler migration directive is required. On first start after this change the
object moves any envelopes still stored under the old `queue:<recipient>` key layout
into the new table and records the `schema:mailbox_v2` marker, so already-queued
mail survives the upgrade. The marker makes the migration one-shot; deleting it
re-runs the migration.

### Cost note

The Worker fits the Workers free tier, and the Durable Object uses the SQLite
storage backend (`new_sqlite_classes` in `wrangler.toml`), which is the
free-plan-eligible configuration. Durable Objects *without* the SQLite backend
require a paid plan. Verify current Cloudflare limits and pricing before a
production rollout — they change over time.

## Manifest Setup

Create a `BridgeManifestPayload` that points at the Cloudflare bridge. Serverless Worker bridge entries must use JSON wire format.

The helper script can generate the payload skeleton from the deployed Worker host and bridge fingerprint:

```powershell
.\scripts\New-ProductionSignedManifest.ps1 `
  -BridgeHost 'ada-serverless-bridge.example.workers.dev'
```

For manual payload editing, use `New-BridgeManifestPayload.ps1` directly, then sign with `sign_manifest`.

```json
{
  "version": 1,
  "issued_at_ms": 1790000000000,
  "ttl_secs": 3600,
  "max_attachment_bytes": 262144,
  "supports_realtime_calls": true,
  "bridges": [
    {
      "id": "cf-worker-primary",
      "address": "ada-serverless-bridge.example.workers.dev",
      "port": 443,
      "protocol": "websocket",
      "hostname": "ada-serverless-bridge.example.workers.dev",
      "fingerprint_hex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "priority": 220,
      "wire_format": "json"
    }
  ]
}
```

Sign it offline:

```powershell
$env:ADA_BRIDGE_SIGNING_SEED = '<64-hex private signing seed>'
cargo run --manifest-path ada-core/Cargo.toml --bin sign_manifest -- manifest-payload.json signed-manifest.json
cargo run --manifest-path ada-core/Cargo.toml --bin verify_manifest -- signed-manifest.json $env:ADA_BUILTIN_MANIFEST_PUBLIC_KEYS
```

Deploy `cf-workers/manifest-worker` and store the signed manifest as a Worker secret:

```powershell
Push-Location cf-workers\manifest-worker
npm install
npm run check
npm run dry-run
wrangler secret put SIGNED_MANIFEST_JSON
Pop-Location
```

## Expected Runtime Behavior

On a fresh install, `ADAConfig::default()` and `ADAConfig::for_mobile()` include the embedded manifest URL/key only when those build variables are set. Startup then fetches the signed manifest, verifies it against the embedded public key, installs the Cloudflare bridge, and enables the bridge/mailbox path for `Auto`, relay-only, and strict profiles.

When several manifests verify — from the embedded list, the persisted bootstrap URLs, or both — their bridges are merged at runtime (see [Multiple Operators](#multiple-operators-federated-manifests)). The first attempt runs even when a manifest was restored from cache, so manifests from other operators are discovered as well.

In the general user-configured path, Android Settings stores the user's manifest URL/public key locally, asks Rust core to fetch and verify the signed manifest, and Rust persists the verified manifest URL and trusted public key for future refreshes.

In allowlist conditions, the critical path is plain HTTPS to the manifest URL and HTTPS POST to the Worker mailbox endpoints. If both are allowed, text messaging can work without iroh, QUIC, or WebSocket.

Before building the APK, run the local bootstrap preflight:

```powershell
.\scripts\Test-ProductionBootstrap.ps1 -SignedManifestPath 'production\signed-manifest.json' -Strict
```

## Operational Notes

1. Ship more than one manifest URL, ideally published by **independent operators**: manifests from different publishers are merged, while two URLs signed by the same key are still one trust domain.
2. Use short manifest TTLs and rotate bridge entries through signed manifest updates.
3. Monitor `/healthz` and `/ops/status` for mailbox lag and queue pressure.
4. Keep QR/file/deeplink manifest import as a backup when even Cloudflare hostnames are not in a local allowlist.
5. Validate `workers.dev` and custom domains separately on target networks; banks using Cloudflare does not automatically imply every Cloudflare tenant hostname is allowlisted.