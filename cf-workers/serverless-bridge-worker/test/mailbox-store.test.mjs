// Storage-layer tests for the serverless bridge.
//
// These run the *real* SQL statements from ../mailbox-store.js against real
// SQLite (node:sqlite, built into Node 22+), using a thin adapter that mimics the
// Cloudflare Durable Object SQL API: `exec(query, ...bindings)` returning
// `{ toArray(), rowsWritten }`. `rowsWritten` maps to SQLite's `changes`, which is
// the same signal the worker relies on for "did this INSERT actually add a row".
//
// This is the layer that used to be untestable, and it is where the interesting
// failure modes live: duplicate suppression, TTL, ordering, quota and replay state.

import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";

import { MailboxStore, planLiveDrain } from "../mailbox-store.js";
import { DEFAULT_MAILBOX_TTL_MS } from "../protocol.js";

const RECIPIENT = "aa".repeat(32);
const SENDER = "bb".repeat(32);

/** Mimic the Durable Object SQLite handle on top of node:sqlite. */
function sqlHandle(db) {
  return {
    exec(query, ...bindings) {
      const statement = db.prepare(query);
      if (/^\s*(select|with|pragma)/i.test(query)) {
        const rows = statement.all(...bindings);
        return { toArray: () => rows, rowsWritten: 0 };
      }
      const info = statement.run(...bindings);
      return { toArray: () => [], rowsWritten: Number(info.changes || 0) };
    },
  };
}

function newStore(limits = {}) {
  const db = new DatabaseSync(":memory:");
  const store = new MailboxStore(sqlHandle(db), limits);
  store.initSchema();
  return { db, store };
}

let nextId = 0;
function envelope(overrides = {}) {
  nextId += 1;
  const id = (overrides.message_id ?? [nextId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]).slice();
  return {
    message_id: id,
    sender: Array(32).fill(0xbb),
    recipient: Array(32).fill(0xaa),
    lane: "TextDm",
    wire_bytes: [1, 2, 3],
    created_at_ms: 1_700_000_000_000,
    expires_at: Math.floor((Date.now() + 3600_000) / 1000),
    ...overrides,
  };
}

test("insert is idempotent on (recipient, message_id)", () => {
  const { store } = newStore();
  const env = envelope();

  assert.equal(store.insertEnvelope(RECIPIENT, env), 1);
  assert.equal(store.insertEnvelope(RECIPIENT, env), 0, "duplicate insert must not write a row");
  assert.equal(store.queueDepth(RECIPIENT), 1);
  assert.equal(store.hasEnvelope(RECIPIENT, env.message_id.map((b) => b.toString(16).padStart(2, "0")).join("")), true);
});

test("envelopes round-trip through storage unchanged", () => {
  const { store } = newStore();
  const env = envelope({ lane: "FileChunk", wire_bytes: [9, 8, 7, 6], expires_at: 1_800_000_000 });
  store.insertEnvelope(RECIPIENT, env);

  const [restored] = store.pendingEnvelopes(RECIPIENT, 10);
  assert.deepEqual(restored, env);
});

test("expired envelopes are evicted and never handed out", () => {
  const { store } = newStore();
  const past = envelope({ expires_at: Math.floor(Date.now() / 1000) - 60 });
  const future = envelope({ expires_at: Math.floor(Date.now() / 1000) + 3600 });
  store.insertEnvelope(RECIPIENT, past);
  store.insertEnvelope(RECIPIENT, future);

  assert.equal(store.queueDepth(RECIPIENT), 1, "depth must ignore expired rows");
  assert.equal(store.pendingEnvelopes(RECIPIENT, 10).length, 1);
  assert.equal(store.evictExpired(), 1, "eviction should drop exactly the expired row");
  assert.equal(store.pendingEnvelopes(RECIPIENT, 10).length, 1);
});

test("an envelope without expires_at falls back to the configured TTL", () => {
  const { store } = newStore({ mailboxTtlMs: 60_000 });
  const now = Date.now();
  const env = envelope({ expires_at: 0, created_at_ms: now });
  store.insertEnvelope(RECIPIENT, env, now);

  assert.equal(store.evictExpired(now + 30_000), 0, "must survive inside the TTL window");
  assert.equal(store.evictExpired(now + 60_001), 1, "must expire after the TTL window");
});

test("pending envelopes come out oldest-first and respect the limit", () => {
  const { store } = newStore();
  const base = 1_700_000_000_000;
  for (let i = 0; i < 5; i += 1) {
    store.insertEnvelope(RECIPIENT, envelope({ created_at_ms: base + i * 1000 }));
  }

  const page = store.pendingEnvelopes(RECIPIENT, 3);
  assert.equal(page.length, 3);
  const timestamps = page.map((item) => item.created_at_ms);
  assert.deepEqual(timestamps, [...timestamps].sort((a, b) => a - b));
  assert.equal(timestamps[0], base, "the oldest envelope must be delivered first");
});

test("queues are isolated per recipient", () => {
  const { store } = newStore();
  const other = "cc".repeat(32);
  store.insertEnvelope(RECIPIENT, envelope());
  store.insertEnvelope(other, envelope({ recipient: Array(32).fill(0xcc) }));

  assert.equal(store.queueDepth(RECIPIENT), 1);
  assert.equal(store.queueDepth(other), 1);
  assert.equal(store.deleteEnvelopes(RECIPIENT, [
    store.pendingEnvelopes(RECIPIENT, 1)[0].message_id
      .map((b) => b.toString(16).padStart(2, "0"))
      .join(""),
  ]), 1);
  assert.equal(store.queueDepth(RECIPIENT), 0);
  assert.equal(store.queueDepth(other), 1, "acking one peer must not touch another mailbox");
});

test("ack deletes only the listed ids and reports how many were removed", () => {
  const { store } = newStore();
  const first = envelope();
  const second = envelope();
  store.insertEnvelope(RECIPIENT, first);
  store.insertEnvelope(RECIPIENT, second);

  const toHexId = (env) => env.message_id.map((b) => b.toString(16).padStart(2, "0")).join("");
  assert.equal(store.deleteEnvelopes(RECIPIENT, [toHexId(first), "ff".repeat(16)]), 1);
  assert.equal(store.deleteEnvelopes(RECIPIENT, [toHexId(first)]), 0, "second ack is a no-op");
  assert.equal(store.queueDepth(RECIPIENT), 1);
});

test("oversize envelopes are rejected with 413", () => {
  const { store } = newStore({ maxWireBytes: 4 });
  assert.throws(
    () => store.insertEnvelope(RECIPIENT, envelope({ wire_bytes: [1, 2, 3, 4, 5] })),
    (error) => error.status === 413,
  );
});

test("an envelope addressed to a different mailbox is rejected", () => {
  const { store } = newStore();
  assert.throws(
    () => store.insertEnvelope("dd".repeat(32), envelope()),
    /recipient does not match/,
  );
});

test("replay state is persisted and eventually reclaimed", () => {
  const { store } = newStore();
  const peer = SENDER;
  const nonce = "0f".repeat(16);
  const now = Date.now();

  assert.equal(store.nonceAlreadySeen(peer, nonce), false);
  store.recordNonce(peer, nonce, now + 1000);
  assert.equal(store.nonceAlreadySeen(peer, nonce), true, "replay must be detected");
  assert.equal(
    store.nonceAlreadySeen("ee".repeat(32), nonce),
    false,
    "nonces are scoped per peer",
  );

  assert.equal(store.cleanupNonces(now + 2000), 1, "expired nonce must be reclaimed");
  assert.equal(
    store.nonceAlreadySeen(peer, nonce),
    false,
    "after the freshness window the nonce is forgettable again",
  );
});

test("recordNonce is idempotent for the same peer and nonce", () => {
  const { store } = newStore();
  const expiry = Date.now() + 1000;
  store.recordNonce(SENDER, "11".repeat(16), expiry);
  store.recordNonce(SENDER, "11".repeat(16), expiry);
  assert.equal(store.nonceAlreadySeen(SENDER, "11".repeat(16)), true);
  assert.equal(store.cleanupNonces(expiry + 1), 1, "still a single row");
});

test("stats aggregate peers, depth and age", () => {
  const { store } = newStore();
  const other = "cc".repeat(32);
  const now = 1_700_000_000_000;

  store.insertEnvelope(RECIPIENT, envelope({ created_at_ms: now - 5000 }), now);
  store.insertEnvelope(RECIPIENT, envelope({ created_at_ms: now - 1000 }), now);
  store.insertEnvelope(other, envelope({ recipient: Array(32).fill(0xcc) }), now);

  const stats = store.stats(now);
  assert.equal(stats.activeMailboxPeers, 2);
  assert.equal(stats.totalQueuedEnvelopes, 3);
  assert.equal(stats.maxQueueDepth, 2);
  assert.equal(stats.oldestMailboxAgeMs, 5000);
});

test("stats ignore expired rows and stay zero-safe when empty", () => {
  const { store } = newStore();
  const empty = store.stats(1_700_000_000_000);
  assert.deepEqual(
    {
      activeMailboxPeers: empty.activeMailboxPeers,
      totalQueuedEnvelopes: empty.totalQueuedEnvelopes,
      maxQueueDepth: empty.maxQueueDepth,
      oldestMailboxAgeMs: empty.oldestMailboxAgeMs,
    },
    { activeMailboxPeers: 0, totalQueuedEnvelopes: 0, maxQueueDepth: 0, oldestMailboxAgeMs: 0 },
  );

  store.insertEnvelope(RECIPIENT, envelope({ expires_at: Math.floor(Date.now() / 1000) - 5 }));
  assert.equal(store.stats().totalQueuedEnvelopes, 0);
});

test("schema creation is idempotent", () => {
  const { store } = newStore();
  assert.doesNotThrow(() => store.initSchema(), "re-running migrations must be safe");
  assert.equal(DEFAULT_MAILBOX_TTL_MS, 48 * 3600 * 1000);
});

// ── live-drain windowing ────────────────────────────────────────────────────
// The DO calls drainToLive() on register and after every ack. Sending a fresh
// batch per ack would amplify a slow-acking peer into a storm, and re-sending
// in-flight envelopes makes the client decrypt duplicates — hence the window.

test("live drain fills the window oldest-first when nothing is in flight", () => {
  const plan = planLiveDrain(["a", "b", "c", "d"], [], 3);
  assert.deepEqual(plan.toSend, ["a", "b", "c"]);
  assert.deepEqual(plan.remainingInflight, []);
});

test("live drain only refills free slots of the window", () => {
  const plan = planLiveDrain(["a", "b", "c", "d", "e"], ["a", "b"], 3);
  assert.deepEqual(plan.toSend, ["c"], "only one free slot, and not a duplicate");
  assert.deepEqual(plan.remainingInflight, ["a", "b"]);
});

test("a saturated window sends nothing", () => {
  const plan = planLiveDrain(["a", "b", "c"], ["a", "b", "c"], 3);
  assert.deepEqual(plan.toSend, []);
  assert.deepEqual(plan.remainingInflight, ["a", "b", "c"]);
});

test("in-flight ids that are no longer queued free their slots", () => {
  // "x" was acked or TTL-evicted server-side, so it must not hold a slot forever.
  const plan = planLiveDrain(["a", "b"], ["x", "a"], 2);
  assert.deepEqual(plan.remainingInflight, ["a"]);
  assert.deepEqual(plan.toSend, ["b"]);
});

test("live drain is bounded by the limit and tolerates an empty queue", () => {
  const many = Array.from({ length: 50 }, (_, i) => `id-${i}`);
  assert.equal(planLiveDrain(many, [], 5).toSend.length, 5);
  assert.deepEqual(planLiveDrain([], ["a"], 5), { toSend: [], remainingInflight: [] });
  assert.deepEqual(planLiveDrain(["a"], [], 0).toSend, []);
});
