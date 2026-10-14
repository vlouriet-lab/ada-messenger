# Contact Key Verification

How ADA decides that the key it is encrypting to really belongs to the person you
think you are talking to, and what it can and cannot detect on its own.

## The problem this closes

X3DH needs the peer's long-term X25519 identity key (`ik_public`) and a signed
pre-key. A contact card carried both, but **only the pre-key was signed**:

```
PatternContactCard (v2/v3)
  id         Ed25519 public key = PeerId
  ik         X25519 identity key used as IK_B in X3DH      ← not signed
  spk        X25519 signed pre-key
  spk_sig    Ed25519 signature over spk                    ← signed, verified
```

Anyone able to rewrite a card in transit could therefore replace `ik`, keep the
valid `spk_sig`, answer the X3DH handshake with their own key and decrypt
everything — while every signature check kept passing. A safety number computed
from the Ed25519 identities alone would **not** catch this, because the identity
key was never part of what the peer signed.

## What now happens

### 1. The X3DH identity key is bound to the identity (cards v4+)

The card generator signs the key the recipient will actually use — the long-term
`ik` for a normal card, the per-contact `ephemeral_ik` for an incognito card:

```
ik_sig = Ed25519(id) over "ADA-IK-v1:" || (the X3DH identity key)
```

Import rules in `ada_add_contact_json`:

| Card version | `ik_sig` | Result |
| --- | --- | --- |
| v4+ | missing | **rejected** — a v4 card that drops the binding is a downgrade attempt |
| v4+ | present but invalid | **rejected** — someone tried to substitute the key |
| v4+ | valid | accepted, contact marked *keys authenticated* |
| v2 / v3 | absent | accepted, contact marked *TOFU only* (see below) |

The compact deeplink encoding (`ada://s/...`) carries the signature too, so
sharing a contact as a link does not silently downgrade it.

### 2. Trust on first use (TOFU) with pinning

Every contact gets a pin in the encrypted KV (`contact.verification.<peer_id>`)
recording the X3DH identity key currently in force, when it was first seen,
whether it was authenticated at the time, and whether the user has verified it.

When a key arrives after that:

- **same key** — nothing happens (an unauthenticated card that later arrives with a
  valid binding is upgraded in place);
- **different key** — the new bundle is **not** applied. It is stored as *pending*
  and a `ContactKeyChanged` event fires, while the previously accepted key stays in
  force. Messages keep going to the old key, so a substituted card cannot redirect
  the conversation. The user decides:
  - `ada_accept_contact_key_change` — adopt it (drops the old ratchet, clears any
    previous verification);
  - `ada_reject_contact_key_change` — drop it;
- a manifest of a **lower `version`** for the same publisher is unrelated, but the
  same idea applies to manifests — see `CLOUDFLARE_BOOTSTRAP.md`.

Pins are created for contacts that already existed the first time the app starts
after this change, so nobody loses a contact and no false warning appears.

## Safety numbers

`safety_number(our_peer_id, their_peer_id)` is a 60-digit value in 12 groups of 5,
derived from SHA-512 over `"ada/safety-number/v1" || version || key_a || key_b` with
the keys sorted first, so both sides compute the same value regardless of who asks.
It follows Signal's construction; the sorting and domain separation are what make it
order-independent and non-reusable.

Compare it over a channel the attacker does not control (in person, a phone call you
recognise). Matching digits mean both devices agree on the two identities.

### What each mechanism detects

| Attack | Detected by |
| --- | --- |
| Key substituted in transit, after first contact | Pinning → `ContactKeyChanged` warning |
| Key substituted in the card itself (pre-v4 formats) | Nothing locally — safety number comparison only |
| MITM present at *first* contact | Nothing locally (TOFU has no earlier value) — safety number comparison only |
| Malicious rewrite of a v4 card in transit | `ik_sig` verification at import (rejected outright) |
| Server/relay lying about the peer's key | Pinning + safety numbers (the relay cannot sign `ik_sig`) |

Verifying a safety number is the only way to rule out a man-in-the-middle who was
there from the very beginning; pinning then keeps that trust intact afterwards.

## Core API

Rust: `ADACore::get_contact_security_json`, `list_contact_security_json`,
`mark_contact_verified`, `clear_contact_verified`, `accept_contact_key_change`,
`reject_contact_key_change`, `safety_number_for`, plus the `ContactKeyChanged` event.

FFI (C): `ada_get_contact_security_json`, `ada_list_contact_security_json`,
`ada_mark_contact_verified`, `ada_clear_contact_verified`,
`ada_accept_contact_key_change`, `ada_reject_contact_key_change`.

The security JSON looks like this:

```json
{
  "peer_id": "…",
  "display_name": "Bob",
  "safety_number": "1234512345…",
  "safety_number_groups": ["12345", "…"],
  "safety_number_formatted": "12345 12345 …",
  "identity_key": "…",
  "ik_authenticated": true,
  "pinned": true,
  "verified": false,
  "verified_at_ms": null,
  "verified_safety_number": null,
  "key_changed": false,
  "pending": null
}
```

`ik_authenticated: false` means the contact's key is known only by first-use trust —
worth a visible hint in the UI, and worth verifying out-of-band.

## Limits (stated plainly)

- A safety number confirms *identity keys*, not that the person is who they claim to
  be; that still comes from how you met the contact.
- Pinning is local. Reinstalling the app, importing a device snapshot from another
  device, or clearing app data starts from a clean slate, so the first key seen
  afterwards is trusted.
- None of this is a substitute for an independent security audit; ADA has not had one.
