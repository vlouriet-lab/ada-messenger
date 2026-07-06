# ADA Baseline Status

Date: 2026-05-14

## Summary

Baseline is green for the fast local/CI test profile after introducing the `mobile-dev` feature set. The original `mobile` test command was blocked locally by the SQLCipher/OpenSSL build path (`rusqlite/bundled-sqlcipher-vendored-openssl` -> `openssl-sys`). Release Android/native builds still use `mobile`; local and CI tests now use `mobile-dev` to keep JNI/FFI/mobile bindings enabled while using bundled SQLite.

## Rust Core

Feature set:

```powershell
--no-default-features --features mobile-dev
```

Results:

| Check | Result | Notes |
|---|---:|---|
| `cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev --lib --tests -- --nocapture` | PASS | 130 passed, 0 failed, 2 ignored; integration target also passed: 18 passed, 0 failed |
| `cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev --test integration -- --nocapture` | PASS | 18 passed, 0 failed |
| `cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev --test integration bridge_manifest_import_rejects_invalid_fields_without_replacing_existing_config -- --nocapture` | PASS | Invalid signed manifest is rejected without replacing the existing bridge config |

## Android

Results:

| Check | Result | Notes |
|---|---:|---|
| `./android-app/gradlew.bat -p android-app --console=plain --no-daemon testDebugUnitTest` | PASS | Gradle JVM unit test task completed successfully |
| `./android-app/gradlew.bat -p android-app --console=plain --no-daemon :app:assembleDebug` | PASS | Debug APK assembly completed successfully: 0.3.40, versionCode 3040 |

## Post-Baseline Implementation Notes

First transport/status hardening pass completed after the baseline:

| Area | Result | Verification |
|---|---|---|
| Android route outcome state | `MessageRouteChanged` and bridge status snapshots now preserve the last transport outcome, including `local_mesh`. Mesh-only delivery contributes `ORANGE` limited connectivity instead of disappearing from the status model. | `testDebugUnitTest` PASS, `:app:assembleDebug` PASS |
| Shared connection status bar | Main and chat screens now include a concise last-route suffix when available: live iroh, bridge, local mesh, mailbox, queued, relay-only queued, or failed. | `ConnectionStatusBarTest` covered by `testDebugUnitTest` |
| Bridge runtime copy | Bridge screen now renders `local_mesh` as a first-class last-delivery outcome. | `:app:assembleDebug` PASS |
| Peer presence UI | Direct-chat headers now describe live iroh presence explicitly instead of absolute online/offline, and chat-list avatars no longer draw a false offline dot for peers that simply lack live presence. | `testDebugUnitTest` PASS, `:app:assembleDebug` PASS |
| Bridge manifest/manual-line validation | Signed manifests now validate payload metadata, bridge IDs, endpoints, protocol-specific fields, nonzero fingerprints, secrets, wire format, duplicate IDs, and TTL before replacing runtime bridge config. Manual bridge lines now reject unknown protocols, invalid/zero ports, missing or zero fingerprints, and missing domain-front/meek fields instead of silently falling back. | `cargo test --no-default-features --features mobile-dev --lib --tests -- --nocapture` PASS: 130 passed, 0 failed, 2 ignored; integration target PASS: 18 passed |
| Bridge error feedback plumbing | FFI now records and exposes the last detailed bridge/manifest import error via `ada_take_last_error_message`; JNI/Kotlin read it as `BridgeOperationResult`, and Android snackbars include the core rejection reason while preserving generic fallback text. | Focused FFI regression PASS; `:app:compileDebugKotlin` PASS; Android native debug build PASS and copied `.so` to `jniLibs`; `:app:assembleDebug` PASS |
| Manual bridge add UX | Android bridge-add dialog disables empty/oversized input, uses a parser-valid WebSocket example, and shows snackbar feedback when core accepts or rejects the bridge line. Rejections now include specific parser/manifest reasons when available. | `testDebugUnitTest` PASS, `:app:compileDebugKotlin` PASS, `:app:assembleDebug` PASS |

## Follow-Up

1. Keep `mobile` for release/native builds that require SQLCipher.
2. Use `mobile-dev` for local and CI test loops unless the task specifically validates SQLCipher/OpenSSL packaging.
3. Next implementation focus: device/APK bridge-mailbox validation with logcat, then a deeper per-peer reachability model if core starts exposing peer-scoped bridge/mailbox/last-seen data.