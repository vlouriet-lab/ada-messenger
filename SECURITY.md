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
