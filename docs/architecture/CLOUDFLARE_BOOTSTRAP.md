# Cloudflare Bootstrap

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

The serverless bridge worker supports live WebSocket bridge delivery and HTTP mailbox fallback. If a network blocks WebSocket upgrade but allows HTTPS POST, text delivery should still work through mailbox push/pull/ack.
`/ops/status.counters` exposes `rate_limited_total`, `http_rate_limited_total`, and `ws_rate_limited_total` for alerting alongside mailbox depth and delivery split metrics.

Local validation before deploy:

```powershell
Push-Location cf-workers\serverless-bridge-worker
npm install
npm run check
npm run dry-run
Pop-Location
```

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

In the general user-configured path, Android Settings stores the user's manifest URL/public key locally, asks Rust core to fetch and verify the signed manifest, and Rust persists the verified manifest URL and trusted public key for future refreshes.

In allowlist conditions, the critical path is plain HTTPS to the manifest URL and HTTPS POST to the Worker mailbox endpoints. If both are allowed, text messaging can work without iroh, QUIC, or WebSocket.

Before building the APK, run the local bootstrap preflight:

```powershell
.\scripts\Test-ProductionBootstrap.ps1 -SignedManifestPath 'production\signed-manifest.json' -Strict
```

## Operational Notes

1. Ship more than one manifest URL when possible.
2. Use short manifest TTLs and rotate bridge entries through signed manifest updates.
3. Monitor `/healthz` and `/ops/status` for mailbox lag and queue pressure.
4. Keep QR/file/deeplink manifest import as a backup when even Cloudflare hostnames are not in a local allowlist.
5. Validate `workers.dev` and custom domains separately on target networks; banks using Cloudflare does not automatically imply every Cloudflare tenant hostname is allowlisted.