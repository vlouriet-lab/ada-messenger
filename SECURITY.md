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

