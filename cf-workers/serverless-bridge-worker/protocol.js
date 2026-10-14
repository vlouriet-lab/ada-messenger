// ADA serverless bridge — wire protocol helpers.
//
// This module is intentionally free of any `cloudflare:workers` import so it can
// be unit-tested under plain Node (`npm test`). Everything here is pure: no I/O,
// no storage, no globals mutated.
//
// The byte layout produced here is pinned on the Rust side by
// `ada-core/src/bridge/mailbox.rs::http_auth_challenges_match_worker_manual_bincode_layout`.
// `test/protocol.test.mjs` re-creates that same golden vector independently, so a
// drift between the Rust signer and this verifier fails a test on one side or the
// other instead of silently breaking bridge auth in the field.

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export const REGISTER_CONTEXT = encoder.encode("ada/bridge-register/v1");
export const HTTP_PUSH_CONTEXT = encoder.encode("ada/bridge-http-push/v1");
export const HTTP_PULL_CONTEXT = encoder.encode("ada/bridge-http-pull/v1");
export const HTTP_ACK_CONTEXT = encoder.encode("ada/bridge-http-ack/v1");

/** Auth freshness window; must match `BRIDGE_AUTH_MAX_SKEW_MS` in Rust. */
export const BRIDGE_AUTH_MAX_SKEW_MS = 5 * 60 * 1000;

/**
 * Fallback mailbox lifetime when an envelope carries no `expires_at`
 * (epoch seconds). Mirrors `OFFLINE_MESSAGE_TTL_SECS` on the Rust side (48 h).
 */
export const DEFAULT_MAILBOX_TTL_MS = 48 * 3600 * 1000;

export const DEFAULT_RATE_LIMITS = {
  mailboxIp: { capacity: 60, refillTokensPerSec: 10 },
  mailboxPeer: { capacity: 30, refillTokensPerSec: 5 },
  wsRegisterIp: { capacity: 12, refillTokensPerSec: 1 },
  wsRegisterPeer: { capacity: 12, refillTokensPerSec: 1 },
};

/** Serde discriminant order of `BridgeDeliveryLane` in Rust. */
const DELIVERY_LANE_DISCRIMINANTS = {
  TextDm: 0,
  FileMetadata: 1,
  FileChunk: 2,
  CallSignaling: 3,
  MaintenanceRetry: 4,
};

export function toHex(bytes) {
  let out = "";
  for (const b of bytes) {
    out += b.toString(16).padStart(2, "0");
  }
  return out;
}

export function fromHex(hex) {
  if (typeof hex !== "string" || hex.length % 2 !== 0) {
    throw new Error("hex string must have an even length");
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i += 1) {
    const byte = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    if (!Number.isInteger(byte)) {
      throw new Error("hex string contains a non-hex character");
    }
    out[i] = byte;
  }
  return out;
}

export function fromArray(value, expectedLength, label) {
  if (!Array.isArray(value) || value.length !== expectedLength) {
    throw new Error(`${label} must be an array of ${expectedLength} bytes`);
  }
  return Uint8Array.from(value);
}

export function fromByteVector(value, label) {
  if (!Array.isArray(value)) {
    throw new Error(`${label} must be a byte array`);
  }
  return Uint8Array.from(value);
}

export function u64LittleEndian(value) {
  const out = new Uint8Array(8);
  const view = new DataView(out.buffer);
  view.setBigUint64(0, BigInt(value), true);
  return out;
}

export function u32LittleEndian(value) {
  const out = new Uint8Array(4);
  const view = new DataView(out.buffer);
  view.setUint32(0, value >>> 0, true);
  return out;
}

export function concatBytes(...parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function encodeVecBytes(value, label) {
  const bytes = fromByteVector(value, label);
  return concatBytes(u64LittleEndian(bytes.length), bytes);
}

function encodeMessageIdVec(value) {
  if (!Array.isArray(value)) {
    throw new Error("message_ids must be an array");
  }
  return concatBytes(
    u64LittleEndian(value.length),
    ...value.map((id) => fromArray(id, 16, "message_id")),
  );
}

function encodeAuth(auth) {
  return concatBytes(
    fromArray(auth?.nonce, 16, "auth.nonce"),
    u64LittleEndian(auth?.timestamp_ms || 0),
  );
}

function deliveryLaneDiscriminant(lane) {
  if (!(lane in DELIVERY_LANE_DISCRIMINANTS)) {
    throw new Error(`unsupported bridge delivery lane ${lane}`);
  }
  return u32LittleEndian(DELIVERY_LANE_DISCRIMINANTS[lane]);
}

/**
 * bincode-compatible encoding of `BridgeEnvelope` (see the Rust struct).
 * Field order is positional — never reorder without a wire version bump.
 */
export function encodeBridgeEnvelope(envelope) {
  return concatBytes(
    fromArray(envelope?.message_id, 16, "envelope.message_id"),
    fromArray(envelope?.sender, 32, "envelope.sender"),
    fromArray(envelope?.recipient, 32, "envelope.recipient"),
    deliveryLaneDiscriminant(envelope?.lane),
    encodeVecBytes(envelope?.wire_bytes, "envelope.wire_bytes"),
    u64LittleEndian(envelope?.created_at_ms || 0),
    u64LittleEndian(envelope?.expires_at || 0),
  );
}

export function registerChallenge(peerId, auth) {
  const peer = fromArray(peerId, 32, "peer_id");
  return concatBytes(REGISTER_CONTEXT, peer, encodeAuth(auth));
}

export function httpPushChallenge(envelope, auth) {
  return concatBytes(HTTP_PUSH_CONTEXT, encodeBridgeEnvelope(envelope), encodeAuth(auth));
}

export function httpPullChallenge(peerId, auth) {
  return concatBytes(HTTP_PULL_CONTEXT, fromArray(peerId, 32, "peer_id"), encodeAuth(auth));
}

export function httpAckChallenge(peerId, messageIds, auth) {
  return concatBytes(
    HTTP_ACK_CONTEXT,
    fromArray(peerId, 32, "peer_id"),
    encodeMessageIdVec(messageIds),
    encodeAuth(auth),
  );
}

export function parseFrame(raw) {
  const text = typeof raw === "string" ? raw : decoder.decode(raw);
  const value = JSON.parse(text);
  if (typeof value === "string") {
    return { kind: value, payload: null };
  }
  const [kind, payload] = Object.entries(value)[0] || [];
  if (!kind) {
    throw new Error("invalid bridge frame");
  }
  return { kind, payload };
}

export function encodeFrame(kind, payload) {
  if (payload == null) {
    return encoder.encode(JSON.stringify(kind));
  }
  return encoder.encode(JSON.stringify({ [kind]: payload }));
}

export function bridgeFingerprintFromHex(hex, { strict = false } = {}) {
  const trimmed = (hex || "").trim();
  if (!trimmed || trimmed.length !== 64) {
    if (strict) {
      throw new Error("BRIDGE_FINGERPRINT_HEX must be 64 hex characters");
    }
    return new Array(32).fill(0);
  }
  return Array.from(fromHex(trimmed));
}

/**
 * Validate the identity-bearing (and ack-bearing) fields of an inbound envelope.
 * Throws on anything the Rust client could not have produced.
 */
export function validateEnvelope(envelope) {
  const messageId = fromArray(envelope?.message_id, 16, "envelope.message_id");
  const sender = fromArray(envelope?.sender, 32, "envelope.sender");
  const recipient = fromArray(envelope?.recipient, 32, "envelope.recipient");
  const wireBytes = fromByteVector(envelope?.wire_bytes, "envelope.wire_bytes");
  deliveryLaneDiscriminant(envelope?.lane);
  const createdAtMs = Number(envelope?.created_at_ms || 0);
  const expiresAt = Number(envelope?.expires_at || 0);
  if (!Number.isFinite(createdAtMs) || createdAtMs < 0) {
    throw new Error("envelope.created_at_ms must be a non-negative integer");
  }
  if (!Number.isFinite(expiresAt) || expiresAt < 0) {
    throw new Error("envelope.expires_at must be a non-negative integer");
  }
  return {
    messageIdHex: toHex(messageId),
    senderHex: toHex(sender),
    recipientHex: toHex(recipient),
    wireHex: toHex(wireBytes),
    createdAtMs,
    expiresAt,
  };
}

/**
 * Normalise an envelope's expiry to epoch milliseconds.
 *
 * Rust sets `expires_at` in **epoch seconds** (`next_offline_expiry_ts()`), but
 * a zero/absent value means "unspecified" — in that case fall back to the
 * default TTL measured from `created_at_ms`, and if that is absent too, from now.
 */
export function envelopeExpiryMs(envelope, nowMs, defaultTtlMs = DEFAULT_MAILBOX_TTL_MS) {
  const expiresAt = Number(envelope?.expires_at || 0);
  if (Number.isFinite(expiresAt) && expiresAt > 0) {
    return Math.floor(expiresAt * 1000);
  }
  const createdAtMs = Number(envelope?.created_at_ms || 0);
  const base = Number.isFinite(createdAtMs) && createdAtMs > 0 ? createdAtMs : nowMs;
  return base + defaultTtlMs;
}
