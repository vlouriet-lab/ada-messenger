# ADR: WARP Support Positioning

## Status

Accepted for planning. Not implemented as a core transport.

## Context

ADA needs a realistic posture for extreme-censorship environments. During planning, WARP was considered as one possible way to improve bootstrap reachability in networks where direct access to discovery or relay infrastructure is unreliable.

Hex Decensor's legacy WARP path was reviewed as a reference implementation. It provisions a Cloudflare WARP device at runtime through the Cloudflare client API, extracts WireGuard keys, addresses, peer public key, and endpoint metadata, then routes selected web traffic through a sing-box WireGuard endpoint tagged `warp`. This is useful as a soft-filtering fallback, but it still depends on live access to Cloudflare's WARP registration/API path and to the WireGuard endpoint transport.

At the same time, WARP is not a substitute for the product's own transport strategy:

- it introduces an external dependency and platform-specific operational assumptions
- it can fail under strict positive allowlists that permit HTTPS to selected Cloudflare-hosted sites but do not permit WARP registration or WireGuard transport
- it does not replace application-level delivery semantics
- it should not become a hidden mandatory requirement for normal message delivery

## Decision

WARP is accepted only as an optional helper layer for bootstrap, diagnostics, or emergency reachability.

Specifically:

- WARP is not the default ADA transport.
- WARP is not required for normal operation.
- WARP must not be presented to users as the primary censorship-resistance story.
- Core implementation priority remains application-owned transport improvements such as WebSocket-based bridge paths, mailbox/store-and-forward, and explicit runtime route reporting.

## Consequences

- Product and engineering documentation should describe WARP as optional and situational.
- Runtime behavior must remain correct when WARP is absent.
- Any future WARP integration should be additive, easy to disable, and clearly separated from the core message transport pipeline.

## Follow-up

- Keep WARP in the implementation plan as an optional bootstrap or emergency path.
- Do not block Phase 0 or Phase 1 delivery on WARP support.