# Changelog

All notable changes to ADA Messenger are documented here.

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
- X3DH + Double Ratchet encryption on all DMs
- Sender Key encryption for group messages
- SQLCipher-encrypted local message store
- XChaCha20-Poly1305 device-sync link encryption
- Ed25519 bridge manifest signing with TTL enforcement

### Notes
- No independent third-party security audit performed yet
- Android APK signed with self-generated keystore (not EV code-signed)
- Windows installer not EV code-signed (SmartScreen prompt expected)
