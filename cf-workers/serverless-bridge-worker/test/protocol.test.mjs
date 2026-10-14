// Dependency-free tests for the serverless bridge worker.
//
// Run with: npm test   (node --test test/)
//
// The auth-challenge tests deliberately reconstruct the expected bytes with
// hand-written little-endian primitives instead of reusing the helpers under
// test, and mirror the Rust golden vector in
// `ada-core/src/bridge/mailbox.rs::http_auth_challenges_match_worker_manual_bincode_layout`.
// If either side changes the signed byte layout, one of the two suites fails
// instead of bridge auth silently breaking in the field.

import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_MAILBOX_TTL_MS,
  HTTP_ACK_CONTEXT,
  HTTP_PULL_CONTEXT,
  HTTP_PUSH_CONTEXT,
  REGISTER_CONTEXT,
  bridgeFingerprintFromHex,
  encodeFrame,
  envelopeExpiryMs,
  fromHex,
  httpAckChallenge,
  httpPullChallenge,
  httpPushChallenge,
  parseFrame,
  registerChallenge,
  toHex,
  validateEnvelope,
} from "../protocol.js";

function le32(value) {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value, true);
  return out;
}

function le64(value) {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(value), true);
  return out;
}

function fill(length, byte) {
  return new Uint8Array(length).fill(byte);
}

function concat(...parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

// Same inputs as the Rust golden vector.
const AUTH = { nonce: Array(16).fill(7), timestamp_ms: 11 };
const ENVELOPE = {
  message_id: Array(16).fill(1),
  sender: Array(32).fill(2),
  recipient: Array(32).fill(3),
  lane: "FileChunk",
  wire_bytes: [4, 5, 6],
  created_at_ms: 9,
  expires_at: 10,
};

test("http push challenge matches the Rust golden vector", () => {
  const expected = concat(
    HTTP_PUSH_CONTEXT,
    fill(16, 1), // message_id
    fill(32, 2), // sender
    fill(32, 3), // recipient
    le32(2), // lane discriminant: FileChunk
    le64(3), // wire_bytes length
    new Uint8Array([4, 5, 6]), // wire_bytes
    le64(9), // created_at_ms
    le64(10), // expires_at
    fill(16, 7), // auth.nonce
    le64(11), // auth.timestamp_ms
  );

  assert.equal(toHex(httpPushChallenge(ENVELOPE, AUTH)), toHex(expected));
});

test("http pull challenge matches the Rust golden vector", () => {
  const expected = concat(HTTP_PULL_CONTEXT, fill(32, 8), fill(16, 7), le64(11));

  assert.equal(toHex(httpPullChallenge(Array(32).fill(8), AUTH)), toHex(expected));
});

test("http ack challenge matches the Rust golden vector", () => {
  const expected = concat(
    HTTP_ACK_CONTEXT,
    fill(32, 8),
    le64(2), // two message ids
    fill(16, 1),
    fill(16, 2),
    fill(16, 7),
    le64(11),
  );

  assert.equal(
    toHex(httpAckChallenge(Array(32).fill(8), [Array(16).fill(1), Array(16).fill(2)], AUTH)),
    toHex(expected),
  );
});

test("register challenge covers context, peer id and auth", () => {
  const expected = concat(REGISTER_CONTEXT, fill(32, 5), fill(16, 7), le64(11));

  assert.equal(toHex(registerChallenge(Array(32).fill(5), AUTH)), toHex(expected));
});

test("delivery lane discriminants follow the Rust enum order", () => {
  const expected = {
    TextDm: 0,
    FileMetadata: 1,
    FileChunk: 2,
    CallSignaling: 3,
    MaintenanceRetry: 4,
  };

  for (const [lane, discriminant] of Object.entries(expected)) {
    // Offset 16 (message_id) + 32 (sender) + 32 (recipient) = 80.
    const encoded = httpPushChallenge({ ...ENVELOPE, lane }, AUTH);
    const laneBytes = toHex(encoded.slice(HTTP_PUSH_CONTEXT.length + 80, HTTP_PUSH_CONTEXT.length + 84));
    assert.equal(laneBytes, toHex(le32(discriminant)), `lane ${lane} discriminant drifted`);
  }
});

test("unknown delivery lane is rejected", () => {
  assert.throws(
    () => httpPushChallenge({ ...ENVELOPE, lane: "Telepathy" }, AUTH),
    /unsupported bridge delivery lane/,
  );
});

test("frame codec round-trips tagged and unit frames", () => {
  assert.deepEqual(parseFrame(encodeFrame("Pong", null)), { kind: "Pong", payload: null });

  const deliver = { envelope: { message_id: [1], lane: "TextDm" } };
  assert.deepEqual(parseFrame(encodeFrame("Deliver", deliver)), {
    kind: "Deliver",
    payload: deliver,
  });

  assert.deepEqual(parseFrame('{"Error":{"message":"nope"}}'), {
    kind: "Error",
    payload: { message: "nope" },
  });
});

test("invalid frames are rejected", () => {
  assert.throws(() => parseFrame("{}"), /invalid bridge frame/);
});

test("hex helpers round-trip", () => {
  const bytes = new Uint8Array([0, 1, 15, 16, 254, 255]);
  assert.equal(toHex(bytes), "00010f10feff");
  assert.deepEqual(Array.from(fromHex("00010f10feff")), [0, 1, 15, 16, 254, 255]);
  assert.throws(() => fromHex("abc"), /even length/);
  assert.throws(() => fromHex("zz"), /non-hex/);
});

test("envelope expiry prefers expires_at seconds and falls back to created_at", () => {
  const now = 1_700_000_000_000;

  // Rust sets expires_at in epoch seconds.
  assert.equal(
    envelopeExpiryMs({ expires_at: 1_700_000_500, created_at_ms: now }, now),
    1_700_000_500_000,
  );

  // Unspecified expiry -> created_at + default TTL.
  assert.equal(
    envelopeExpiryMs({ expires_at: 0, created_at_ms: now }, now),
    now + DEFAULT_MAILBOX_TTL_MS,
  );

  // No timestamps at all -> now + default TTL (never immortal).
  assert.equal(envelopeExpiryMs({}, now), now + DEFAULT_MAILBOX_TTL_MS);

  // Custom TTL override.
  assert.equal(envelopeExpiryMs({ created_at_ms: now }, now, 1000), now + 1000);
});

test("validateEnvelope accepts a well-formed envelope and rejects malformed ones", () => {
  const fields = validateEnvelope(ENVELOPE);
  assert.equal(fields.messageIdHex, toHex(fill(16, 1)));
  assert.equal(fields.senderHex, toHex(fill(32, 2)));
  assert.equal(fields.recipientHex, toHex(fill(32, 3)));
  assert.equal(fields.wireHex, "040506");
  assert.equal(fields.createdAtMs, 9);
  assert.equal(fields.expiresAt, 10);

  assert.throws(() => validateEnvelope({ ...ENVELOPE, message_id: [1] }), /message_id/);
  assert.throws(() => validateEnvelope({ ...ENVELOPE, recipient: null }), /recipient/);
  assert.throws(() => validateEnvelope({ ...ENVELOPE, wire_bytes: null }), /wire_bytes/);
  assert.throws(() => validateEnvelope({ ...ENVELOPE, lane: "Nope" }), /lane/);
});

test("bridge fingerprint parsing is zero-safe and strict on demand", () => {
  assert.deepEqual(bridgeFingerprintFromHex(""), new Array(32).fill(0));
  assert.deepEqual(bridgeFingerprintFromHex("  "), new Array(32).fill(0));

  const hex = "ab".repeat(32);
  const parsed = bridgeFingerprintFromHex(hex);
  assert.equal(parsed.length, 32);
  assert.equal(parsed[0], 0xab);

  assert.throws(() => bridgeFingerprintFromHex("abcd", { strict: true }), /64 hex/);
});
