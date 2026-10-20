// Mailbox + replay-nonce storage for the ADA serverless bridge.
//
// Everything here is plain SQL executed through a handle that exposes
// `exec(query, ...bindings) -> { toArray(), rowsWritten }`, i.e. the Cloudflare
// Durable Object SQLite API. It deliberately does NOT import `cloudflare:workers`,
// so the exact statements below can be exercised against real SQLite
// (`node:sqlite`) in `test/mailbox-store.test.mjs`. The SQL is the part most
// likely to rot silently, so it is the part that gets a real database in tests.
//
// Why rows and not one JSON array per recipient: the previous layout rewrote the
// whole array on every push (O(n^2) writes) and hit the per-value size limit after
// a handful of envelopes, which made the configured queue cap unreachable.

import {
  DEFAULT_MAILBOX_TTL_MS,
  envelopeExpiryMs,
  fromHex,
  validateEnvelope,
} from "./protocol.js";

export const SCHEMA_STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS mailbox (
    recipient_hex       TEXT    NOT NULL,
    message_id_hex      TEXT    NOT NULL,
    sender_hex          TEXT    NOT NULL,
    lane                TEXT    NOT NULL,
    wire_hex            TEXT    NOT NULL,
    created_at_ms       INTEGER NOT NULL,
    expires_at_ms       INTEGER NOT NULL,
    envelope_expires_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (recipient_hex, message_id_hex)
  )`,
  "CREATE INDEX IF NOT EXISTS mailbox_expiry ON mailbox (expires_at_ms)",
  `CREATE TABLE IF NOT EXISTS seen_nonces (
    peer_hex      TEXT    NOT NULL,
    nonce_hex     TEXT    NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    PRIMARY KEY (peer_hex, nonce_hex)
  )`,
  "CREATE INDEX IF NOT EXISTS seen_nonces_expiry ON seen_nonces (expires_at_ms)",
];

/** Row -> envelope, restoring the exact JSON shape the Rust client expects. */
export function rowToEnvelope(row) {
  return {
    message_id: Array.from(fromHex(row.message_id_hex)),
    sender: Array.from(fromHex(row.sender_hex)),
    recipient: Array.from(fromHex(row.recipient_hex)),
    lane: row.lane,
    wire_bytes: Array.from(fromHex(row.wire_hex)),
    created_at_ms: Number(row.created_at_ms),
    expires_at: Number(row.envelope_expires_at || 0),
  };
}

export const DEFAULT_LIMITS = {
  maxQueuePerPeer: 256,
  maxPullPerRequest: 64,
  maxWireBytes: 4 * 1024 * 1024,
  mailboxTtlMs: DEFAULT_MAILBOX_TTL_MS,
};

/**
 * Decide which queued envelopes to push to a live listener right now.
 *
 * Kept pure so the windowing rules are testable: the naive alternative — sending
 * a fresh batch for every single ack — amplifies a slow-acking peer into a
 * delivery storm, and re-sending everything already in flight just makes the
 * client decrypt duplicates.
 *
 * @param {string[]} pendingIds message ids in delivery order (oldest first)
 * @param {Iterable<string>} inflightIds ids already handed over but not acked
 * @param {number} limit size of the in-flight window
 * @returns {{toSend: string[], remainingInflight: string[]}}
 *   `toSend` tops the window up to `limit`; `remainingInflight` drops ids that
 *   are no longer queued (acked or TTL-evicted) so a message the client never
 *   acks cannot permanently shrink the window.
 */
export function planLiveDrain(pendingIds, inflightIds, limit) {
  const pending = new Set(pendingIds);
  const inflight = new Set(inflightIds);

  const remainingInflight = [...inflight].filter((id) => pending.has(id));
  const stillInflight = new Set(remainingInflight);
  const budget = Math.max(0, limit - stillInflight.size);

  const toSend = [];
  for (const id of pendingIds) {
    if (toSend.length >= budget) {
      break;
    }
    if (stillInflight.has(id)) {
      continue;
    }
    toSend.push(id);
  }

  return { toSend, remainingInflight };
}

export class MailboxStore {
  constructor(sql, limits = {}) {
    this.sql = sql;
    this.limits = { ...DEFAULT_LIMITS, ...limits };
  }

  initSchema() {
    for (const statement of SCHEMA_STATEMENTS) {
      this.sql.exec(statement);
    }
  }

  /**
   * Insert one envelope. Idempotent by (recipient, message_id).
   * @returns {number} 1 when a new row was written, 0 when it already existed.
   */
  insertEnvelope(recipientHex, envelope, nowMs = Date.now()) {
    const fields = validateEnvelope(envelope);
    if (fields.recipientHex !== recipientHex) {
      throw new Error("envelope recipient does not match mailbox key");
    }
    if (fields.wireHex.length / 2 > this.limits.maxWireBytes) {
      throw Object.assign(new Error("bridge envelope exceeds size limit"), { status: 413 });
    }
    const cursor = this.sql.exec(
      `INSERT OR IGNORE INTO mailbox
         (recipient_hex, message_id_hex, sender_hex, lane, wire_hex,
          created_at_ms, expires_at_ms, envelope_expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      recipientHex,
      fields.messageIdHex,
      fields.senderHex,
      envelope.lane,
      fields.wireHex,
      fields.createdAtMs > 0 ? fields.createdAtMs : nowMs,
      envelopeExpiryMs(envelope, nowMs, this.limits.mailboxTtlMs),
      fields.expiresAt,
    );
    return cursor.rowsWritten ?? 0;
  }

  hasEnvelope(recipientHex, messageIdHex) {
    return (
      this.sql
        .exec(
          "SELECT 1 AS hit FROM mailbox WHERE recipient_hex = ? AND message_id_hex = ? LIMIT 1",
          recipientHex,
          messageIdHex,
        )
        .toArray().length > 0
    );
  }

  /**
   * Number of messages the recipient can actually pull.
   *
   * Expired rows are excluded, so `queued_count` / `remaining` reported to the
   * client match what a pull would return. `evictExpired()` still runs
   * opportunistically to keep storage tidy, but correctness never depends on it.
   */
  queueDepth(recipientHex, nowMs = Date.now()) {
    const row = this.sql
      .exec(
        "SELECT COUNT(*) AS depth FROM mailbox WHERE recipient_hex = ? AND expires_at_ms > ?",
        recipientHex,
        nowMs,
      )
      .toArray()[0];
    return Number(row?.depth || 0);
  }

  /** Drop envelopes whose TTL has passed. Runs opportunistically on write/pull. */
  evictExpired(nowMs = Date.now()) {
    const cursor = this.sql.exec("DELETE FROM mailbox WHERE expires_at_ms <= ?", nowMs);
    return cursor.rowsWritten ?? 0;
  }

  pendingEnvelopes(recipientHex, limit, nowMs = Date.now()) {
    const rows = this.sql
      .exec(
        `SELECT * FROM mailbox
          WHERE recipient_hex = ? AND expires_at_ms > ?
          ORDER BY created_at_ms ASC, message_id_hex ASC
          LIMIT ?`,
        recipientHex,
        nowMs,
        limit,
      )
      .toArray();
    return rows.map(rowToEnvelope);
  }

  deleteEnvelopes(recipientHex, messageIdHexes) {
    if (messageIdHexes.length === 0) {
      return 0;
    }
    const placeholders = messageIdHexes.map(() => "?").join(", ");
    const cursor = this.sql.exec(
      `DELETE FROM mailbox WHERE recipient_hex = ? AND message_id_hex IN (${placeholders})`,
      recipientHex,
      ...messageIdHexes,
    );
    return cursor.rowsWritten ?? 0;
  }

  stats(nowMs = Date.now()) {
    const totals = this.sql
      .exec(
        `SELECT COUNT(*) AS total, COUNT(DISTINCT recipient_hex) AS peers
           FROM mailbox WHERE expires_at_ms > ?`,
        nowMs,
      )
      .toArray()[0];
    const deepest = this.sql
      .exec(
        `SELECT recipient_hex, COUNT(*) AS depth FROM mailbox
          WHERE expires_at_ms > ?
          GROUP BY recipient_hex ORDER BY depth DESC LIMIT 1`,
        nowMs,
      )
      .toArray();
    const oldest = this.sql
      .exec("SELECT MIN(created_at_ms) AS oldest FROM mailbox WHERE expires_at_ms > ?", nowMs)
      .toArray()[0];

    const maxQueueDepth = deepest.length > 0 ? Number(deepest[0].depth) : 0;
    const oldestCreatedAtMs = Number(oldest?.oldest || 0);
    const maxQueue = this.limits.maxQueuePerPeer;
    return {
      activeMailboxPeers: Number(totals?.peers || 0),
      totalQueuedEnvelopes: Number(totals?.total || 0),
      maxQueueDepth,
      oldestMailboxAgeMs: oldestCreatedAtMs > 0 ? Math.max(0, nowMs - oldestCreatedAtMs) : 0,
      maxQueueUtilizationPct: maxQueue > 0 ? (maxQueueDepth / maxQueue) * 100 : 0,
    };
  }

  // ── replay protection ────────────────────────────────────────────────────

  nonceAlreadySeen(peerHex, nonceHex) {
    return (
      this.sql
        .exec(
          "SELECT 1 AS hit FROM seen_nonces WHERE peer_hex = ? AND nonce_hex = ? LIMIT 1",
          peerHex,
          nonceHex,
        )
        .toArray().length > 0
    );
  }

  recordNonce(peerHex, nonceHex, expiresAtMs) {
    this.sql.exec(
      "INSERT OR REPLACE INTO seen_nonces (peer_hex, nonce_hex, expires_at_ms) VALUES (?, ?, ?)",
      peerHex,
      nonceHex,
      expiresAtMs,
    );
  }

  cleanupNonces(nowMs = Date.now()) {
    const cursor = this.sql.exec("DELETE FROM seen_nonces WHERE expires_at_ms <= ?", nowMs);
    return cursor.rowsWritten ?? 0;
  }
}
