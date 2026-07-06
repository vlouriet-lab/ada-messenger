# Connection Profiles Plan

## Goal

ADA should not enable every anti-censorship feature for every user. Transport behavior must follow the current network environment, battery constraints, and operator risk tolerance.

## Profiles

| Profile | Primary use | Core behavior |
| --- | --- | --- |
| `auto` | Default adaptive mode | Keep current adaptive routing; enable mailbox pull when `relay_only` is active. |
| `normal` | Ordinary internet | Prefer local mesh and iroh live; keep bridge/mailbox as fallback; no periodic HTTP mailbox polling. |
| `mobile_saver` | Battery-sensitive normal networks | Prefer low-chatter live routes; avoid frequent probes and mailbox polling. |
| `censored_light` | DNS or partial blocking | Keep iroh with fast failover; prefer WebSocket TLS bridge when needed. |
| `censored_heavy` | QUIC/relay/DPI blocking | Force relay-only semantics; use bridge and HTTP mailbox polling. |
| `allowlist_only` | HTTPS-only / whitelist networks | Treat HTTP mailbox push/pull/ack as the primary text receive path; disable live iroh sends. |
| `incident_safe` | Bridge fleet degradation or rollout rollback | Prefer store-and-forward; reduce live features and background pressure. |

## Implemented First Slice

1. Added `connection_profile` to `NetworkConfig` with profile values serialized as snake_case.
2. Made `censored_heavy`, `allowlist_only`, and `incident_safe` imply relay-only routing.
3. Added `allow_mailbox_pull` to `TransportPolicy`.
4. Added client-side HTTP mailbox `pull` and `ack` with bridge fingerprint verification.
5. Added a profile-gated background mailbox pull loop.
6. Exposed `connection_profile`, `mailbox_pull_interval_secs`, and `capabilities.mailbox_pull` in bridge status JSON.
7. Added an integration test proving `allowlist_only` can receive and ack text through HTTP mailbox without a WebSocket listener.
8. Added build-time HTTPS manifest bootstrap hooks so production APKs can embed Cloudflare Worker/custom-domain manifest URLs and trusted manifest signer public keys without hardcoding deployment secrets in source.

## Next Slices

1. Add Android profile selector: `Auto`, `Normal`, `Battery saver`, `Censored network`, `Whitelist/HTTPS-only`; `Auto` must be the default for fresh installs and missing legacy settings.
2. Persist runtime profile overrides in Android settings and pass them into `ADAConfig` before core startup.
3. Add bridge health TTL/background probes so strict profiles do not probe every route attempt.
4. Add WebSocket connect/read/write timeouts and half-open detection.
5. Extend signed bridge manifests with optional `profiles`, `capabilities`, and `canary` fields.
6. Keep domain fronting, meek, and lightweight obfuscation behind canary profile gates until field validation proves them.
7. Field-test `workers.dev` and Cloudflare custom domains separately under target allowlists; keep non-Cloudflare fallback providers in the manifest rotation.

## Validation Matrix

| Scenario | Required result |
| --- | --- |
| Normal network | iroh live works; mailbox polling remains inactive. |
| Relay-only auto | iroh live sends are skipped; bridge/mailbox paths remain active. |
| WebSocket blocked | HTTP mailbox push succeeds. |
| Allowlist-only receive | HTTP mailbox pull delivers encrypted envelope and HTTP ack removes it. |
| Fresh install with Cloudflare bootstrap | Embedded HTTPS manifest URL/key fetches and verifies the signed manifest, then installs Cloudflare Worker bridge/mailbox entries. |
| Mailbox-only calls | Calls fail with a clear live-bridge-required reason. |
| Bridge incident | Messages fall back to mailbox/offline queue without false-online UI. |