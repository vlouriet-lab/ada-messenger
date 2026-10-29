// ADA serverless bridge — Cloudflare Worker + Durable Object.
//
// Serves the ADA bridge protocol:
//   * WebSocket  <BRIDGE_PATH>  — live delivery + mailbox listener (JSON frames)
//   * HTTP POST  /mailbox/push  — store-and-forward submission
//   * HTTP POST  /mailbox/pull  — drain the mailbox
//   * HTTP POST  /mailbox/ack   — confirm delivery
//   * GET        /healthz       — liveness/degradation probe
//   * GET        /ops/status    — counters and queue gauges
//
// Behavioural fixes over the previous revision, all of which were observed as
// concrete failure modes rather than theoretical concerns:
//   * a full mailbox no longer blocks *live* delivery (previously the quota was
//     enforced before the live hand-off, so one stuck envelope wedged the whole
//     mailbox and the peer stopped receiving anything at all);
//   * envelopes are evicted by their own TTL, so an envelope the client can never
//     consume (a wire kind it does not understand, and therefore never acks) ages
//     out instead of being redelivered on every reconnect;
//   * replay nonces are persisted, so the replay guard no longer depends on the
//     Durable Object staying warm;
//   * a mailbox larger than one batch drains immediately (register + after each
//     ack) instead of stalling until the next reconnect.
//
// Wire byte layout lives in ./protocol.js; storage lives in ./mailbox-store.js.
// Both are pure and unit-tested (`npm test`), including auth challenges pinned
// against the Rust golden vector in `ada-core/src/bridge/mailbox.rs`.

import { DurableObject } from "cloudflare:workers";
import * as ed from "@noble/ed25519";
import {
  BRIDGE_AUTH_MAX_SKEW_MS,
  DEFAULT_MAILBOX_TTL_MS,
  DEFAULT_RATE_LIMITS,
  bridgeFingerprintFromHex,
  encodeFrame,
  fromArray,
  fromByteVector,
  httpAckChallenge,
  httpPullChallenge,
  httpPushChallenge,
  parseFrame,
  registerChallenge,
  toHex,
  validateEnvelope,
} from "./protocol.js";
import { MailboxStore, planLiveDrain } from "./mailbox-store.js";

const MAILBOX_PATHS = new Set(["/mailbox/push", "/mailbox/pull", "/mailbox/ack"]);
const RATE_LIMIT_IDLE_TTL_MS = 15 * 60 * 1000;

const DEFAULT_MAX_QUEUE_PER_PEER = 256;
const DEFAULT_MAX_PULL_PER_REQUEST = 64;
const DEFAULT_MAX_LIVE_BATCH = 64;
const MAX_ACK_IDS_PER_REQUEST = 256;
/**
 * DoS ceiling for a single envelope. Must stay comfortably above the largest
 * frame ADA legitimately sends on the bridge lane (a 512 KiB file chunk plus
 * serialisation overhead), otherwise real transfers start failing.
 */
const DEFAULT_MAX_WIRE_BYTES = 4 * 1024 * 1024;

const LEGACY_MIGRATION_FLAG = "schema:mailbox_v2";
const LEGACY_QUEUE_PREFIX = "queue:";
/** Upper bound on rows scanned when topping up a live listener's window. */
const MAX_LIVE_SCAN_WINDOW = 512;

function bridgePath(env) {
  return (env.BRIDGE_PATH || "/ada").trim() || "/ada";
}

function responseJson(value, status = 200) {
  return new Response(JSON.stringify(value, null, 2), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function sanitizeErrorMessage(error) {
  if (error && typeof error.status === "number" && error.status < 500) {
    const raw = error instanceof Error ? error.message : String(error || "");
    const firstLine = raw.split("\n")[0].trim();
    if (firstLine.length > 0 && !firstLine.includes("at ") && !firstLine.includes("Error:")) {
      return firstLine.slice(0, 200);
    }
    if (error.status === 401) return "Unauthorized";
    if (error.status === 413) return "Payload too large";
    if (error.status === 429) return "Rate limit exceeded";
    return "Bad request";
  }
  return "Internal error";
}

function responseNoContent(status = 204) {
  return new Response(null, { status });
}

function envNumber(env, name, fallback) {
  const value = Number(env[name]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function rateLimitSpec(env, prefix, fallback) {
  return {
    capacity: envNumber(env, `${prefix}_CAPACITY`, fallback.capacity),
    refillTokensPerSec: envNumber(env, `${prefix}_REFILL_PER_SEC`, fallback.refillTokensPerSec),
  };
}

function clientIp(request) {
  const forwarded = request.headers.get("X-Forwarded-For") || "";
  return request.headers.get("CF-Connecting-IP") || forwarded.split(",")[0].trim() || "unknown";
}

async function verifySignature(peerId, signature, message) {
  const publicKey = fromArray(peerId, 32, "peer_id");
  const sig = fromByteVector(signature, "signature");
  return ed.verify(sig, message, publicKey);
}

async function verifyRegister(peerId, signature, auth) {
  return verifySignature(peerId, signature, registerChallenge(peerId, auth));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (
      url.pathname === "/healthz" ||
      MAILBOX_PATHS.has(url.pathname) ||
      url.pathname === "/ops/status"
    ) {
      const id = env.ADA_BRIDGE.idFromName("global-ada-bridge");
      const stub = env.ADA_BRIDGE.get(id);
      return stub.fetch(request);
    }

    if (url.pathname !== bridgePath(env)) {
      return new Response("Not Found", { status: 404 });
    }

    const upgrade = request.headers.get("Upgrade");
    if (!upgrade || upgrade.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket upgrade", {
        status: 426,
        headers: { Upgrade: "websocket" },
      });
    }

    const id = env.ADA_BRIDGE.idFromName("global-ada-bridge");
    const stub = env.ADA_BRIDGE.get(id);
    return stub.fetch(request);
  },
};

export class AdaBridge extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
    this.sessions = new Map();
    this.livePeers = new Map();
    // peerHex -> Set<messageIdHex> handed to that live listener but not yet acked.
    // Keeps the post-ack top-up from re-pushing envelopes that are still in flight.
    this.liveInflight = new Map();
    this.rateBuckets = new Map();
    this.counters = {
      ws_register_total: 0,
      ws_register_rejected_total: 0,
      ws_push_total: 0,
      ws_ack_total: 0,
      http_push_total: 0,
      http_pull_total: 0,
      http_ack_total: 0,
      live_delivery_total: 0,
      mailbox_enqueue_total: 0,
      acked_message_total: 0,
      auth_failures_total: 0,
      rate_limited_total: 0,
      http_rate_limited_total: 0,
      ws_rate_limited_total: 0,
      // Mailbox health counters (v2 storage model).
      expired_evicted_total: 0,
      quota_rejected_total: 0,
      oversize_rejected_total: 0,
      live_drain_total: 0,
    };

    this.limits = {
      maxQueuePerPeer: envNumber(env, "MAX_QUEUE_PER_PEER", DEFAULT_MAX_QUEUE_PER_PEER),
      maxPullPerRequest: envNumber(env, "MAX_PULL_PER_REQUEST", DEFAULT_MAX_PULL_PER_REQUEST),
      maxLiveBatch: envNumber(env, "MAX_LIVE_BATCH", DEFAULT_MAX_LIVE_BATCH),
      maxWireBytes: envNumber(env, "MAX_WIRE_BYTES", DEFAULT_MAX_WIRE_BYTES),
      mailboxTtlMs: envNumber(env, "MAILBOX_TTL_MS", DEFAULT_MAILBOX_TTL_MS),
    };

    this.store = new MailboxStore(ctx.storage.sql, {
      maxQueuePerPeer: this.limits.maxQueuePerPeer,
      maxPullPerRequest: this.limits.maxPullPerRequest,
      maxWireBytes: this.limits.maxWireBytes,
      mailboxTtlMs: this.limits.mailboxTtlMs,
    });
    this.store.initSchema();

    // Legacy KV-style queues predate the SQLite layout; migrate them once, before
    // the object answers requests, so an upgrade does not silently drop up to 48 h
    // of undelivered envelopes.
    ctx.blockConcurrencyWhile(async () => {
      try {
        await this.migrateLegacyQueue();
      } catch (error) {
        console.warn("legacy mailbox migration failed", error);
      }
    });
  }

  async migrateLegacyQueue() {
    if (await this.ctx.storage.get(LEGACY_MIGRATION_FLAG)) {
      return;
    }
    const legacy = await this.ctx.storage.list({ prefix: LEGACY_QUEUE_PREFIX });
    let moved = 0;
    for (const [key, value] of legacy) {
      if (Array.isArray(value)) {
        for (const envelope of value) {
          try {
            this.store.insertEnvelope(validateEnvelope(envelope).recipientHex, envelope);
            moved += 1;
          } catch (error) {
            console.warn("skipping unmigratable legacy envelope", error);
          }
        }
      }
      await this.ctx.storage.delete(key);
    }
    await this.ctx.storage.put(LEGACY_MIGRATION_FLAG, { migrated_at_ms: Date.now() });
    if (moved > 0) {
      console.log(`migrated ${moved} legacy mailbox envelope(s) to SQLite storage`);
    }
  }

  bridgeFingerprint() {
    return bridgeFingerprintFromHex(this.env.BRIDGE_FINGERPRINT_HEX);
  }

  // ── rate limiting ────────────────────────────────────────────────────────

  specs() {
    return {
      mailboxIp: rateLimitSpec(this.env, "MAILBOX_IP_RATE", DEFAULT_RATE_LIMITS.mailboxIp),
      mailboxPeer: rateLimitSpec(this.env, "MAILBOX_PEER_RATE", DEFAULT_RATE_LIMITS.mailboxPeer),
      wsRegisterIp: rateLimitSpec(this.env, "WS_REGISTER_IP_RATE", DEFAULT_RATE_LIMITS.wsRegisterIp),
      wsRegisterPeer: rateLimitSpec(
        this.env,
        "WS_REGISTER_PEER_RATE",
        DEFAULT_RATE_LIMITS.wsRegisterPeer,
      ),
    };
  }

  cleanupRateBuckets(now) {
    for (const [key, bucket] of this.rateBuckets.entries()) {
      if (now - bucket.lastRefillMs > RATE_LIMIT_IDLE_TTL_MS) {
        this.rateBuckets.delete(key);
      }
    }
  }

  enforceRateLimit(key, spec) {
    const now = Date.now();
    this.cleanupRateBuckets(now);
    const bucket = this.rateBuckets.get(key) || {
      availableTokens: spec.capacity,
      lastRefillMs: now,
    };
    const elapsedMs = Math.max(0, now - bucket.lastRefillMs);
    const refill = (elapsedMs / 1000) * spec.refillTokensPerSec;
    bucket.availableTokens = Math.min(spec.capacity, bucket.availableTokens + refill);
    bucket.lastRefillMs = now;
    if (bucket.availableTokens < 1) {
      this.rateBuckets.set(key, bucket);
      throw Object.assign(new Error("bridge rate limit exceeded"), {
        status: 429,
        rateLimited: true,
      });
    }
    bucket.availableTokens -= 1;
    this.rateBuckets.set(key, bucket);
  }

  enforceMailboxIpRateLimit(request) {
    const specs = this.specs();
    this.enforceRateLimit(`mailbox:ip:${clientIp(request)}`, specs.mailboxIp);
  }

  enforceMailboxPeerRateLimit(peerHex) {
    const specs = this.specs();
    this.enforceRateLimit(`mailbox:peer:${peerHex}`, specs.mailboxPeer);
  }

  enforceMailboxRateLimits(request, peerId) {
    this.enforceMailboxIpRateLimit(request);
    const peerHex = toHex(fromArray(peerId, 32, "peer_id"));
    this.enforceMailboxPeerRateLimit(peerHex);
  }

  enforceWsRegisterIpRateLimit(clientIpValue) {
    const specs = this.specs();
    this.enforceRateLimit(`ws-register:ip:${clientIpValue || "unknown"}`, specs.wsRegisterIp);
  }

  enforceWsRegisterPeerRateLimit(peerHex) {
    const specs = this.specs();
    this.enforceRateLimit(`ws-register:peer:${peerHex}`, specs.wsRegisterPeer);
  }

  enforceWsRegisterRateLimits(clientIpValue, peerId) {
    this.enforceWsRegisterIpRateLimit(clientIpValue);
    const peerHex = toHex(fromArray(peerId, 32, "peer_id"));
    this.enforceWsRegisterPeerRateLimit(peerHex);
  }

  recordHttpRateLimit() {
    this.counters.rate_limited_total += 1;
    this.counters.http_rate_limited_total += 1;
  }

  recordWsRateLimit() {
    this.counters.rate_limited_total += 1;
    this.counters.ws_rate_limited_total += 1;
  }

  recordAuthFailure() {
    this.counters.auth_failures_total += 1;
  }

  // ── auth ─────────────────────────────────────────────────────────────────

  async verifySignedPayload(peerId, signature, auth, challenge) {
    const now = Date.now();
    this.store.cleanupNonces(now);

    const peerHex = toHex(fromArray(peerId, 32, "peer_id"));
    const timestampMs = Number(auth?.timestamp_ms || 0);
    if (Math.abs(now - timestampMs) > BRIDGE_AUTH_MAX_SKEW_MS) {
      throw Object.assign(new Error("bridge auth timestamp outside freshness window"), {
        status: 401,
      });
    }

    const nonceHex = toHex(fromArray(auth?.nonce, 16, "auth.nonce"));
    if (this.store.nonceAlreadySeen(peerHex, nonceHex)) {
      throw Object.assign(new Error("bridge auth replay detected"), { status: 401 });
    }

    if (!(await verifySignature(peerId, signature, challenge))) {
      throw Object.assign(new Error("unauthorized bridge request"), { status: 401 });
    }

    // Record only after the signature checks out, otherwise an unauthenticated
    // caller could burn a legitimate peer's nonces and lock it out.
    this.store.recordNonce(peerHex, nonceHex, timestampMs + BRIDGE_AUTH_MAX_SKEW_MS);
    return peerHex;
  }

  // ── delivery ─────────────────────────────────────────────────────────────

  isLive(recipientHex) {
    const ws = this.livePeers.get(recipientHex);
    return Boolean(ws && this.sessions.has(ws));
  }

  inflightFor(recipientHex) {
    return this.liveInflight.get(recipientHex);
  }

  /** Best-effort live hand-off; clears a dead listener on failure. */
  deliverLive(recipientHex, envelope, messageIdHex = toHex(envelope.message_id)) {
    const ws = this.livePeers.get(recipientHex);
    if (!ws || !this.sessions.has(ws)) {
      return false;
    }
    try {
      ws.send(encodeFrame("Deliver", { envelope }));
    } catch (error) {
      console.warn("live deliver failed, falling back to mailbox", error);
      this.livePeers.delete(recipientHex);
      this.liveInflight.delete(recipientHex);
      return false;
    }
    const inflight = this.liveInflight.get(recipientHex) ?? new Set();
    inflight.add(messageIdHex);
    this.liveInflight.set(recipientHex, inflight);
    return true;
  }

  /**
   * Top a live listener up to `limit` unacked envelopes.
   *
   * Called on register and after each ack, so a mailbox larger than one batch
   * still drains promptly instead of stalling until the next reconnect. The
   * top-up is windowed rather than unconditional: sending a fresh batch for every
   * single ack would amplify a slow-acking peer into a delivery storm, so we only
   * refill the free slots of the in-flight window.
   */
  drainToLive(recipientHex, limit = this.limits.maxLiveBatch) {
    if (!this.isLive(recipientHex)) {
      return 0;
    }

    const scanWindow = Math.min(limit * 4, MAX_LIVE_SCAN_WINDOW);
    const rows = this.store.pendingEnvelopes(recipientHex, scanWindow);
    const idsInOrder = rows.map((envelope) => toHex(envelope.message_id));
    const plan = planLiveDrain(idsInOrder, this.liveInflight.get(recipientHex) ?? [], limit);

    if (plan.remainingInflight.length > 0) {
      this.liveInflight.set(recipientHex, new Set(plan.remainingInflight));
    } else {
      this.liveInflight.delete(recipientHex);
    }

    const byId = new Map(idsInOrder.map((id, index) => [id, rows[index]]));
    let sent = 0;
    for (const id of plan.toSend) {
      if (!this.deliverLive(recipientHex, byId.get(id), id)) {
        break;
      }
      sent += 1;
    }
    if (sent > 0) {
      this.counters.live_drain_total += sent;
    }
    return sent;
  }

  async queueEnvelope(envelope) {
    const fields = validateEnvelope(envelope);
    const recipientHex = fields.recipientHex;
    this.store.evictExpired();

    if (!this.store.hasEnvelope(recipientHex, fields.messageIdHex)) {
      const live = this.isLive(recipientHex);
      const depth = this.store.queueDepth(recipientHex);
      // A full mailbox must not lock a peer out of *live* delivery.
      if (depth >= this.limits.maxQueuePerPeer && !live) {
        this.counters.quota_rejected_total += 1;
        throw Object.assign(new Error("mailbox quota exceeded"), { status: 429 });
      }
      if (this.store.insertEnvelope(recipientHex, envelope) === 1) {
        this.counters.mailbox_enqueue_total += 1;
      }
    }

    const liveDelivered = this.deliverLive(recipientHex, envelope, fields.messageIdHex);
    if (liveDelivered) {
      this.counters.live_delivery_total += 1;
    }

    return {
      disposition: liveDelivered ? "LiveBridge" : "MailboxQueued",
      queueDepth: this.store.queueDepth(recipientHex),
    };
  }

  // ── HTTP surface ─────────────────────────────────────────────────────────

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/healthz") {
      return responseJson({ ok: true, status: "healthy" });
    }

    if (
      request.method === "OPTIONS" &&
      (MAILBOX_PATHS.has(url.pathname) || url.pathname === "/ops/status")
    ) {
      return responseNoContent();
    }

    if (url.pathname === "/ops/status") {
      const authHeader = request.headers.get("Authorization") || "";
      const queryToken = url.searchParams.get("token") || "";
      const expectedToken = (this.env?.OPS_TOKEN || "").trim();
      if (!expectedToken || (authHeader !== `Bearer ${expectedToken}` && queryToken !== expectedToken)) {
        return new Response("not found", { status: 404 });
      }
      return responseJson(await this.statusValue());
    }

    if (url.pathname === "/mailbox/push") {
      return this.handleHttpPush(request);
    }

    if (url.pathname === "/mailbox/pull") {
      return this.handleHttpPull(request);
    }

    if (url.pathname === "/mailbox/ack") {
      return this.handleHttpAck(request);
    }

    const upgrade = request.headers.get("Upgrade");
    if (!upgrade || upgrade.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket upgrade", {
        status: 426,
        headers: { Upgrade: "websocket" },
      });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();

    const session = {
      sessionId: crypto.randomUUID(),
      peerHex: null,
      listenForMailbox: false,
      clientIp: clientIp(request),
    };
    this.sessions.set(server, session);

    server.addEventListener("message", (event) => {
      this.ctx.waitUntil(this.handleMessage(server, event.data));
    });
    server.addEventListener("close", () => {
      this.handleClose(server);
    });
    server.addEventListener("error", () => {
      this.handleClose(server);
    });

    return new Response(null, { status: 101, webSocket: client });
  }

  async parseJsonRequest(request) {
    if (request.method !== "POST") {
      throw Object.assign(new Error("method not allowed"), { status: 405 });
    }
    try {
      return await request.json();
    } catch {
      throw Object.assign(new Error("invalid JSON body"), { status: 400 });
    }
  }

  async healthValue() {
    const stats = this.store.stats();
    const degraded = stats.oldestMailboxAgeMs > 5 * 60 * 1000 || stats.maxQueueUtilizationPct >= 90;
    return {
      ok: !degraded,
      status: degraded ? "degraded" : "ok",
      mode: "serverless-bridge",
      storage: "durable-object-sqlite",
      active_sessions: this.sessions.size,
      oldest_mailbox_age_ms: stats.oldestMailboxAgeMs,
      mailbox_lag_state: stats.oldestMailboxAgeMs > 5 * 60 * 1000 ? "degraded" : "ok",
      max_queue_utilization_pct: stats.maxQueueUtilizationPct,
    };
  }

  async statusValue() {
    const stats = this.store.stats();
    const liveDeliveryTotal = this.counters.live_delivery_total;
    const mailboxEnqueueTotal = this.counters.mailbox_enqueue_total;
    const deliveryTotal = liveDeliveryTotal + mailboxEnqueueTotal;
    return {
      ok: true,
      mode: "serverless-bridge",
      storage: "durable-object-sqlite",
      bridge_fingerprint: this.bridgeFingerprint(),
      active_sessions: this.sessions.size,
      live_peer_count: this.livePeers.size,
      active_mailbox_peers: stats.activeMailboxPeers,
      total_queued_envelopes: stats.totalQueuedEnvelopes,
      max_queue_depth: stats.maxQueueDepth,
      oldest_mailbox_age_ms: stats.oldestMailboxAgeMs,
      max_queue_per_peer: this.limits.maxQueuePerPeer,
      max_pull_per_request: this.limits.maxPullPerRequest,
      max_live_batch: this.limits.maxLiveBatch,
      max_wire_bytes: this.limits.maxWireBytes,
      mailbox_ttl_ms: this.limits.mailboxTtlMs,
      max_queue_utilization_pct: stats.maxQueueUtilizationPct,
      delivery: {
        live_delivery_total: liveDeliveryTotal,
        mailbox_enqueue_total: mailboxEnqueueTotal,
        live_delivery_rate: deliveryTotal > 0 ? liveDeliveryTotal / deliveryTotal : null,
        mailbox_offload_rate: deliveryTotal > 0 ? mailboxEnqueueTotal / deliveryTotal : null,
      },
      counters: { ...this.counters },
      rate_limit_bucket_count: this.rateBuckets.size,
    };
  }

  async handleHttpPush(request) {
    this.counters.http_push_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxIpRateLimit(request);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      const senderHex = toHex(fromArray(payload.sender, 32, "sender"));
      const envelopeSenderHex = toHex(fromArray(payload.envelope?.sender, 32, "envelope.sender"));
      if (senderHex !== envelopeSenderHex) {
        this.recordAuthFailure();
        return responseJson({ message: "sender mismatch" }, 401);
      }
      await this.verifySignedPayload(
        payload.sender,
        payload.signature,
        payload.auth,
        httpPushChallenge(payload.envelope, payload.auth),
      );
      try {
        this.enforceMailboxPeerRateLimit(senderHex);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      const { disposition, queueDepth } = await this.queueEnvelope(payload.envelope);
      return responseJson({
        disposition,
        queue_depth: queueDepth,
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      if (error?.status === 413) this.counters.oversize_rejected_total += 1;
      return responseJson(
        { message: sanitizeErrorMessage(error) },
        error?.status || 400,
      );
    }
  }

  async handleHttpPull(request) {
    this.counters.http_pull_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxIpRateLimit(request);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      const peerHex = await this.verifySignedPayload(
        payload.peer_id,
        payload.signature,
        payload.auth,
        httpPullChallenge(payload.peer_id, payload.auth),
      );
      try {
        this.enforceMailboxPeerRateLimit(peerHex);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      this.store.evictExpired();
      return responseJson({
        envelopes: this.store.pendingEnvelopes(peerHex, this.limits.maxPullPerRequest),
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      return responseJson(
        { message: sanitizeErrorMessage(error) },
        error?.status || 400,
      );
    }
  }

  async handleHttpAck(request) {
    this.counters.http_ack_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxIpRateLimit(request);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      const peerHex = await this.verifySignedPayload(
        payload.peer_id,
        payload.signature,
        payload.auth,
        httpAckChallenge(payload.peer_id, payload.message_ids || [], payload.auth),
      );
      try {
        this.enforceMailboxPeerRateLimit(peerHex);
      } catch (error) {
        if (error?.rateLimited) this.recordHttpRateLimit();
        throw error;
      }
      const ids = (payload.message_ids || [])
        .slice(0, MAX_ACK_IDS_PER_REQUEST)
        .map((id) => toHex(fromArray(id, 16, "message_id")));
      const removed = this.store.deleteEnvelopes(peerHex, ids);
      this.counters.acked_message_total += removed;
      if (removed > 0 && this.isLive(peerHex)) {
        // The same peer may hold a live listener while draining over HTTP; keep
        // its window topped up through the WebSocket path too.
        this.drainToLive(peerHex);
      }
      return responseJson({
        remaining: this.store.queueDepth(peerHex),
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      return responseJson(
        { message: sanitizeErrorMessage(error) },
        error?.status || 400,
      );
    }
  }

  // ── WebSocket surface ────────────────────────────────────────────────────

  async handleRegister(ws, payload) {
    this.counters.ws_register_total += 1;
    const now = Date.now();
    this.store.cleanupNonces(now);

    const peerId = fromArray(payload.peer_id, 32, "peer_id");
    const peerHex = toHex(peerId);
    const session = this.sessions.get(ws);

    const reject = (message, reason) => {
      this.counters.ws_register_rejected_total += 1;
      ws.send(encodeFrame("Error", { message }));
      ws.close(1008, reason);
    };

    try {
      this.enforceWsRegisterRateLimits(session?.clientIp, payload.peer_id);
    } catch (error) {
      if (error?.rateLimited) this.recordWsRateLimit();
      reject(error instanceof Error ? error.message : String(error), "rate limit");
      return;
    }

    const auth = payload.auth || {};
    const timestampMs = Number(auth.timestamp_ms || 0);
    if (Math.abs(now - timestampMs) > BRIDGE_AUTH_MAX_SKEW_MS) {
      this.recordAuthFailure();
      reject("bridge auth timestamp outside freshness window", "stale auth");
      return;
    }

    const nonceHex = toHex(fromArray(auth.nonce, 16, "auth.nonce"));
    if (this.store.nonceAlreadySeen(peerHex, nonceHex)) {
      this.recordAuthFailure();
      reject("bridge auth replay detected", "replay");
      return;
    }

    if (!(await verifyRegister(payload.peer_id, payload.signature, auth))) {
      this.recordAuthFailure();
      reject("unauthorized register", "bad signature");
      return;
    }

    this.store.recordNonce(peerHex, nonceHex, timestampMs + BRIDGE_AUTH_MAX_SKEW_MS);

    session.peerHex = peerHex;
    session.listenForMailbox = Boolean(payload.listen_for_mailbox);
    if (session.listenForMailbox) {
      const existing = this.livePeers.get(peerHex);
      if (existing && existing !== ws) {
        try {
          existing.close(1000, "replaced by new session");
        } catch {
          // ignore
        }
      }
      this.livePeers.set(peerHex, ws);
      this.liveInflight.delete(peerHex);
    }

    this.store.evictExpired();
    ws.send(
      encodeFrame("RegisterOk", {
        bridge_fingerprint: this.bridgeFingerprint(),
        queued_count: this.store.queueDepth(peerHex),
      }),
    );

    if (session.listenForMailbox) {
      this.drainToLive(peerHex);
    }
  }

  async handlePush(ws, payload) {
    this.counters.ws_push_total += 1;
    const session = this.sessions.get(ws);
    if (!session?.peerHex) {
      ws.close(1008, "register required");
      return;
    }

    const envelope = payload.envelope;
    const senderHex = toHex(fromArray(envelope.sender, 32, "envelope.sender"));
    if (senderHex !== session.peerHex) {
      ws.send(encodeFrame("Error", { message: "sender mismatch" }));
      return;
    }

    try {
      const { disposition, queueDepth } = await this.queueEnvelope(envelope);
      ws.send(encodeFrame("PushAck", { disposition, queue_depth: queueDepth }));
    } catch (error) {
      if (error?.status === 413) this.counters.oversize_rejected_total += 1;
      ws.send(
        encodeFrame("Error", {
          message: error instanceof Error ? error.message : String(error),
        }),
      );
    }
  }

  async handleAck(ws, payload) {
    this.counters.ws_ack_total += 1;
    const session = this.sessions.get(ws);
    if (!session?.peerHex) {
      ws.close(1008, "register required");
      return;
    }

    const ids = (payload.message_ids || [])
      .slice(0, MAX_ACK_IDS_PER_REQUEST)
      .map((id) => toHex(fromArray(id, 16, "message_id")));
    const removed = this.store.deleteEnvelopes(session.peerHex, ids);
    this.counters.acked_message_total += removed;

    // Keep draining while the peer stays connected: a mailbox larger than one
    // batch would otherwise stall until the next reconnect.
    if (removed > 0) {
      const inflight = this.liveInflight.get(session.peerHex);
      if (inflight) {
        for (const id of ids) {
          inflight.delete(id);
        }
        if (inflight.size === 0) {
          this.liveInflight.delete(session.peerHex);
        }
      }
      this.drainToLive(session.peerHex);
    }
  }

  async handleMessage(ws, rawData) {
    try {
      const data = rawData instanceof ArrayBuffer ? new Uint8Array(rawData) : rawData;
      const { kind, payload } = parseFrame(data);

      switch (kind) {
        case "Register":
          await this.handleRegister(ws, payload);
          break;
        case "Push":
          await this.handlePush(ws, payload);
          break;
        case "Ack":
          await this.handleAck(ws, payload);
          break;
        case "Ping":
          ws.send(encodeFrame("Pong", null));
          break;
        case "Pong":
        case "RegisterOk":
        case "PushAck":
        case "Deliver":
        case "Error":
          break;
        default:
          ws.send(encodeFrame("Error", { message: `unsupported frame ${kind}` }));
      }
    } catch (error) {
      const session = this.sessions.get(ws);
      if (session?.peerHex) {
        this.liveInflight.delete(session.peerHex);
      }
      try {
        ws.send(
          encodeFrame("Error", {
            message: error instanceof Error ? error.message : String(error),
          }),
        );
      } catch {
        // no-op if socket already dead
      }
    }
  }

  handleClose(ws) {
    const session = this.sessions.get(ws);
    if (session?.peerHex) {
      if (session.listenForMailbox) {
        const current = this.livePeers.get(session.peerHex);
        if (current === ws) {
          this.livePeers.delete(session.peerHex);
        }
      }
      this.liveInflight.delete(session.peerHex);
    }
    this.sessions.delete(ws);
    try {
      ws.close(1000, "closing");
    } catch {
      // no-op
    }
  }
}
