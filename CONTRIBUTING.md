# Contributing to ADA Messenger

Thanks for your interest in improving ADA Messenger! This document explains how
to get set up and how to submit changes.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you agree to uphold it.

## Getting started

1. **Fork** the repository and clone your fork.
2. Read [docs/BUILDING.md](docs/BUILDING.md) to set up your toolchain
   (Rust, JDK 17, Android SDK/NDK, NSIS for Windows).
3. Create a feature branch: `git checkout -b feature/my-change`.

## Project structure

- `ada-core/` — Rust core. Most security‑critical logic lives here.
- `android-app/app/` — Android UI (Kotlin/Compose).
- `android-app/desktopApp/` — Windows/desktop UI (Compose Multiplatform).

## Development workflow

### Rust core
```bash
cd ada-core
cargo build --no-default-features --features mobile-dev
cargo test  --no-default-features --features mobile-dev
cargo fmt
cargo clippy --all-targets -- -D warnings
```

### Android / Desktop
```bash
cd android-app
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :desktopApp:compileKotlinDesktop
```

## Pull request checklist

- [ ] Code builds and existing tests pass.
- [ ] New behaviour is covered by tests where practical.
- [ ] `cargo fmt` / `cargo clippy` are clean for Rust changes.
- [ ] No secrets, keystores, or `local.properties` are committed.
- [ ] The PR description explains **what** changed and **why**.

## Commit messages

Use clear, imperative messages, e.g. `core: fix double-ratchet replay window`.
Reference issues with `#123` where applicable.

## Reporting bugs & requesting features

Use the GitHub issue templates. For **security vulnerabilities**, do **not** open
a public issue — follow [SECURITY.md](SECURITY.md).
