# Security Policy

ADA Messenger is a privacy and security tool. We take vulnerabilities seriously
and appreciate responsible disclosure.

## Reporting a vulnerability

**Please do NOT open a public GitHub issue for security problems.**

Instead, use **GitHub Security Advisories**:
*Repository → Security → Report a vulnerability* (private disclosure).

Include where possible:
- A description of the issue and its impact.
- Steps to reproduce or a proof of concept.
- Affected component (`ada-core`, Android app, desktop app) and version.

We aim to acknowledge reports within a few days and will keep you updated on
remediation progress. Please give us reasonable time to ship a fix before any
public disclosure.

## Scope

In scope:
- Cryptographic weaknesses in `ada-core` (key agreement, ratchet, signatures).
- Transport/metadata leaks, authentication bypass, remote code execution.
- Issues that leak message content, identities, or key material.

Out of scope:
- Vulnerabilities requiring a rooted/compromised device or physical access.
- Social‑engineering and third‑party infrastructure not in this repository.

## Post-Quantum Cryptography & Pattern Authentication

ADA Messenger incorporates NIST FIPS 203 ML-KEM-768 for quantum-resistant
key encapsulation alongside classical X25519 Diffie-Hellman in a hybrid
configuration:
- **PQXDH Key Agreement**: Hybrid pre-key bundles combine classical X25519 with
  ML-KEM-768 encapsulation, defeating Harvest-Now-Decrypt-Later (HNDL) attacks.
- **SPQR (Signal Post-Quantum Ratchet)**: Asymmetric ratchet steps rotate both
  X25519 and ML-KEM-768 keys to provide quantum forward secrecy.
- **Pattern Authentication Entropy**: The visual pattern password uses 16 cells
  chosen from an 8×8 grid with 5 distinct colors ($C(64, 16) \times 5^{16} \approx 7.45 \times 10^{25} \approx 85.9\text{ bits}$
  of raw entropy), derived via Argon2id (64 MiB, $t=3, p=1$).
For detailed cryptographic specifications, see
[`docs/architecture/POST_QUANTUM_AND_DPI.md`](docs/architecture/POST_QUANTUM_AND_DPI.md).

## Group Gossip & Real-Time Call Security

Group messaging, SenderKey distributions, and call signaling utilize an authenticated
`iroh-gossip` overlay (HyParView + PlumTree), while real-time media streams remain strictly
isolated:
- **Blind Topic Derivation**: Gossip topic identifiers are computed as
  `TopicId = BLAKE3(group_id || "ada-gossip-topic-v1")`. The cleartext `group_id` is never
  exposed in network headers or relay discovery metadata.
- **Signed Envelopes (`GossipEnvelope`)**: Every gossip frame is authenticated with an
  Ed25519 signature over `BLAKE3(group_id || sender || timestamp || payload)`. Unsigned or
  tampered packets are dropped immediately before entering application state.
- **Strict Media Plane Isolation**: Real-time voice and video streams (WebRTC / QUIC) never
  pass through the gossip overlay tree to eliminate multi-hop jitter and latency. Media sessions
  are capped at a hard limit of 6 participants (`MAX_GROUP_CALL_PARTICIPANTS = 6`).
For detailed architectural specifications, see
[`docs/architecture/IROH_GOSSIP_INTEGRATION_PLAN.md`](docs/architecture/IROH_GOSSIP_INTEGRATION_PLAN.md).

## Key verification and its limits

Identity keys are bound to contact cards by signature and pinned on first use;
users can compare a 60-digit safety number out-of-band. What that does and, more
importantly, does not detect is documented in
[`docs/architecture/CONTACT_VERIFICATION.md`](docs/architecture/CONTACT_VERIFICATION.md)
— please read it before reporting a "key substitution" issue, since a
man-in-the-middle present at *first* contact is only detectable by comparing safety
numbers, not locally.

## Handling of secrets

This repository must never contain real secrets. Signing keystores
(`*.jks`), `local.properties`, and deployment credentials are intentionally
excluded via `.gitignore`. Release signing keys are stored only as encrypted
**GitHub Actions secrets** (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`).

## GitHub Actions secrets required for releases

To enable the automated [release workflow](.github/workflows/release.yml) to
build and publish **signed** releases, configure the following secrets in
**Settings → Secrets and variables → Actions** of your GitHub repository:

| Secret name | What it contains |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The `ada-release-key.jks` file, base64-encoded (`base64 -w0 ada-release-key.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore (store) password |
| `ANDROID_KEY_PASSWORD` | Key password (for PKCS12 equals the store password) |

**The key alias is `ada-key`** — it is hardcoded in the workflow and matches
the keystore created for this project. Do not change it without updating the
workflow accordingly.

These secrets are never printed in logs. The keystore file decoded from
`ANDROID_KEYSTORE_BASE64` is written to a temporary path inside the runner
and is not persisted anywhere.

