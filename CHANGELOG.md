# Changelog

All notable changes to ADA Messenger are documented here.

## [0.5.0-beta] — 2026-10-15

### Added
- `iroh-gossip` integration (`ada-core/src/network/gossip.rs`): High-scale topic-based
  pub/sub overlay (HyParView membership + PlumTree broadcast) for group chats,
  SenderKey distribution, presence, and call signaling. Eliminates $O(N)$ unicast
  fan-out overhead, enabling groups to scale to 100+ peers seamlessly.
- Blind Gossip Topics: Topics are derived as `BLAKE3(group_id || "ada-gossip-topic-v1")`,
  ensuring group IDs and memberships are never revealed in network overlay headers.
- Cryptographic `GossipEnvelope`: All gossip messages are signed with Ed25519 identity
  keys and verified against group membership prior to processing.
- Direct Media Plane with 6-Participant Hard Cap: Voice (Opus) and video (VP8/H.264)
  streams remain on direct Full-Mesh WebRTC / Iroh QUIC streams to guarantee $< 150$ ms
  low latency without multi-hop jitter, enforced by a strict protocol and UI limit of
  $\le 6$ active participants (`MAX_GROUP_CALL_PARTICIPANTS = 6`).
- Post-quantum hybrid encryption: NIST FIPS 203 ML-KEM-768 integration,
  PQXDH key agreement (`PreKeyBundle` with signed `pq_spk_public`), and hybrid
  Double Ratchet (SPQR) with per-step KEM rotation to protect against
  Harvest-Now-Decrypt-Later (HNDL) attacks (`docs/architecture/POST_QUANTUM_AND_DPI.md`).
- Deterministic post-quantum key derivation: ML-KEM-768 keypairs are derived
  deterministically from the user's Argon2id pattern master seed via
  `ada/pq-spk-derive/epoch/{N}`, requiring no extra passphrases or user intervention.
- Advanced DPI Morpher (`DpiMorpher`): TLS 1.3 Record framing (`0x17 0x03 0x03`) with
  discrete bucket padding (512, 1024, 2048, ...) and per-frame HKDF-SHA256 stream
  masking to defeat deep packet inspection and traffic fingerprinting.
- Pattern Authentication entropy upgrade: Increased color space from 3 to 5 colors
  (`PATTERN_COLORS = 5`), boosting pattern entropy from 74.2 bits to 85.9 bits
  ($3545\times$ harder to brute-force) while retaining 32-byte canonical serialization.
- Color Palette selector (`PatternColorPalette`): Intuitive palette-based UI across
  Android and Desktop apps supporting 1-tap painting, 1-tap recoloring, and 1-tap
  erasing instead of multi-tap cycling.
- Contact key verification: trust-on-first-use pinning of each contact's X3DH
  identity key, Signal-style 60-digit safety numbers, and a `ContactKeyChanged`
  warning when a contact presents a different key. The new key is held as *pending*
  until the user confirms it out-of-band, so a substituted card cannot silently
  redirect a conversation (`docs/architecture/CONTACT_VERIFICATION.md`).
- Contact cards v4 bind the X3DH identity key to the Ed25519 identity with a new
  `ik_sig` signature (present in the QR JSON, the compact `ada://s/...` encoding
  and the FFI import path). Cards v2/v3 carry no such binding and are accepted as
  TOFU-only, with `ik_authenticated: false` in the contact security report.
- FFI: `ada_get_contact_security_json`, `ada_list_contact_security_json`,
  `ada_mark_contact_verified`, `ada_clear_contact_verified`,
  `ada_accept_contact_key_change`, `ada_reject_contact_key_change`.
- Federated bridge manifests: several independent operators can publish signed
  manifests at the same time, and their bridges are merged instead of competing.
  Each manifest is keyed by the publisher key that signed it, so republishing one
  operator's manifest cannot affect another operator's bridges
  (`bridge_manifest_cache.json` now stores one document per publisher; the old
  single-document format is still read).
- `get_bridge_status_json()` now also reports `manifests` (per-publisher breakdown)
  and `manifest_count`; `manifest` keeps its existing shape as the conservative
  aggregate over every manifest in force.

### Fixed
- Group messages were silently dropped over the bridge/mailbox lane and left in the
  server queue to be redelivered on every reconnect. `handle_bridge_delivery()` now
  routes group envelopes through the same path as DMs.
- The Cloudflare bridge worker stored its queue as one JSON array per recipient,
  which hit the per-value size limit after a handful of envelopes and ignored
  envelope TTLs. Storage moved to one row per envelope in Durable Object SQLite, with
  server-side TTL eviction, persisted replay nonces, and a windowed live drain.

## [0.4.0-beta] — 2026-06-10

### Added
- Public release under AGPL-3.0
- GitHub Actions CI/CD: Android APK + Windows installer
- Bridge manifest: signed JSON for censorship-circumvention bridge config
- HTTP mailbox transport: store-and-forward delivery
- Group call rooms: multi-party voice/video
- Incognito chats: per-contact ephemeral X25519 identity keys
- Replay protection: application-layer message-ID deduplication
- Ed25519 signature verification on all inbound messages
- Sync pagination: cursor-based chunked history sync
- Windows desktop app: Compose Multiplatform with ada_core.dll
- NSIS installer for Windows

### Security
- Contact cards only signed the signed pre-key, never the X3DH identity key, so a
  rewritten card could substitute `ik` and man-in-the-middle the conversation while
  every signature check still passed. Cards v4 sign that key (`ik_sig`) and imports
  reject v4 cards without a valid binding.
- X3DH + Double Ratchet encryption on all DMs
- Sender Key encryption for group messages
- SQLCipher-encrypted local message store
- XChaCha20-Poly1305 device-sync link encryption
- Ed25519 bridge manifest signing with TTL enforcement

### Notes
- No independent third-party security audit performed yet
- Android APK signed with self-generated keystore (not EV code-signed)
- Windows installer not EV code-signed (SmartScreen prompt expected)
