# Production Release Checklist

This checklist captures what must be true before claiming the bridge/mailbox implementation is production-ready. Cloudflare and WARP are optional external helpers, not release dependencies for the messenger itself.

## Required App Behavior

- Fresh install works without embedded `ADA_BUILTIN_MANIFEST_URLS` or `ADA_BUILTIN_MANIFEST_PUBLIC_KEYS`.
- Android Settings exposes user-provided manifest URL and trusted public key import.
- Successful user import persists the manifest URL and public key for later refresh.
- `Auto` remains the default connection profile.
- Strict profiles can use a verified bridge/mailbox when the user has configured one.

## Optional Managed Cloudflare Inputs

- Manifest signing seed exists only in the operator environment: `ADA_BRIDGE_SIGNING_SEED`.
- APK build environment provides `ADA_BUILTIN_MANIFEST_URLS` only for managed builds that intentionally embed bootstrap.
- APK build environment provides `ADA_BUILTIN_MANIFEST_PUBLIC_KEYS` only for managed builds that intentionally embed bootstrap.
- Serverless bridge Worker has a nonzero `BRIDGE_FINGERPRINT_HEX`.
- Signed manifest bridge entries use the same `fingerprint_hex` as the deployed bridge Worker.
- Manifest Worker secret `SIGNED_MANIFEST_JSON` contains the signed manifest JSON, not the unsigned payload.

## Local Validation

```powershell
.\scripts\New-ProductionBootstrapSecrets.ps1
.\scripts\New-ProductionSignedManifest.ps1 -BridgeHost '<bridge-worker-host>'
cargo run --manifest-path ada-core\Cargo.toml --bin verify_manifest -- production\signed-manifest.json $env:ADA_BUILTIN_MANIFEST_PUBLIC_KEYS
.\scripts\Test-ProductionBootstrap.ps1 -SignedManifestPath 'production\signed-manifest.json' -Strict
cargo check --manifest-path ada-core\Cargo.toml --message-format short
cargo test --manifest-path ada-core\Cargo.toml -q mailbox
cargo test --manifest-path ada-core\Cargo.toml -q allowlist
Push-Location cf-workers\serverless-bridge-worker; npm install; npm run check; npm run dry-run; Pop-Location
Push-Location cf-workers\manifest-worker; npm install; npm run check; npm run dry-run; Pop-Location
.\android-app\build-android.ps1 -Release
.\android-app\gradlew.bat -p android-app --console=plain --no-daemon :app:assembleRelease
```

For managed builds that intentionally embed a first-party bootstrap, run `build-android.ps1 -Release -RequireBootstrapManifest` with `ADA_BUILTIN_MANIFEST_URLS` and `ADA_BUILTIN_MANIFEST_PUBLIC_KEYS` set.

## Device Acceptance

- Fresh install without embedded bootstrap still opens normally.
- User-configured bootstrap imports a signed manifest from Settings and updates bridge status.
- Managed build with embedded bootstrap fetches the embedded manifest URL.
- `/ops/status` shows the bridge Worker in `ok` state.
- WebSocket route delivers a text message when `/ada` upgrade is allowed.
- HTTP mailbox push/pull/ack delivers a text message when WebSocket upgrade is blocked but HTTPS POST is allowed.
- `AllowlistOnly` profile exposes `mailbox_delivery=true` and `mailbox_pull=true`.
- Calls are blocked with a clear reason when only mailbox delivery is available.
- Large attachments are limited on censorship-safe routes according to `max_censored_attachment_bytes`.

## Cannot Be Completed Without Deployment

- Real Cloudflare custom-domain and `workers.dev` reachability testing.
- Worker secret installation.
- Production manifest signing with the real offline seed.
- Managed APK build with real embedded manifest URL and public key.
- Canary telemetry thresholds for bridge saturation, mailbox lag, and rate limiting.