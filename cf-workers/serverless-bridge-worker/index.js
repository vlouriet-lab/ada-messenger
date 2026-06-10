import { DurableObject } from "cloudflare:workers";
import * as ed from "@noble/ed25519";

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const REGISTER_CONTEXT = encoder.encode("ada/bridge-register/v1");
const HTTP_PUSH_CONTEXT = encoder.encode("ada/bridge-http-push/v1");
const HTTP_PULL_CONTEXT = encoder.encode("ada/bridge-http-pull/v1");
const HTTP_ACK_CONTEXT = encoder.encode("ada/bridge-http-ack/v1");
const BRIDGE_AUTH_MAX_SKEW_MS = 5 * 60 * 1000;
const MAILBOX_PATHS = new Set(["/mailbox/push", "/mailbox/pull", "/mailbox/ack"]);
const RATE_LIMIT_IDLE_TTL_MS = 15 * 60 * 1000;
const DEFAULT_RATE_LIMITS = {
  mailboxIp: { capacity: 60, refillTokensPerSec: 10 },
  mailboxPeer: { capacity: 30, refillTokensPerSec: 5 },
  wsRegisterIp: { capacity: 12, refillTokensPerSec: 1 },
  wsRegisterPeer: { capacity: 12, refillTokensPerSec: 1 },
};

function bridgePath(env) {
  return (env.BRIDGE_PATH || "/ada").trim() || "/ada";
}

function responseJson(value, status = 200) {
  return new Response(JSON.stringify(value, null, 2), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
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

function toHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function fromArray(value, expectedLength, label) {
  if (!Array.isArray(value) || value.length !== expectedLength) {
    throw new Error(`${label} must be an array of ${expectedLength} bytes`);
  }
  return Uint8Array.from(value);
}

function fromByteVector(value, label) {
  if (!Array.isArray(value)) {
    throw new Error(`${label} must be a byte array`);
  }
  return Uint8Array.from(value);
}

function u64LittleEndian(value) {
  const out = new Uint8Array(8);
  const view = new DataView(out.buffer);
  view.setBigUint64(0, BigInt(value), true);
  return out;
}

function u32LittleEndian(value) {
  const out = new Uint8Array(4);
  const view = new DataView(out.buffer);
  view.setUint32(0, value >>> 0, true);
  return out;
}

function concatBytes(...parts) {
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
  const lanes = {
    TextDm: 0,
    FileMetadata: 1,
    FileChunk: 2,
    CallSignaling: 3,
    MaintenanceRetry: 4,
  };
  if (!(lane in lanes)) {
    throw new Error(`unsupported bridge delivery lane ${lane}`);
  }
  return u32LittleEndian(lanes[lane]);
}

function encodeBridgeEnvelope(envelope) {
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

function registerChallenge(peerId, auth) {
  const peer = fromArray(peerId, 32, "peer_id");
  return concatBytes(REGISTER_CONTEXT, peer, encodeAuth(auth));
}

function httpPushChallenge(envelope, auth) {
  return concatBytes(HTTP_PUSH_CONTEXT, encodeBridgeEnvelope(envelope), encodeAuth(auth));
}

function httpPullChallenge(peerId, auth) {
  return concatBytes(HTTP_PULL_CONTEXT, fromArray(peerId, 32, "peer_id"), encodeAuth(auth));
}

function httpAckChallenge(peerId, messageIds, auth) {
  return concatBytes(
    HTTP_ACK_CONTEXT,
    fromArray(peerId, 32, "peer_id"),
    encodeMessageIdVec(messageIds),
    encodeAuth(auth),
  );
}

function parseFrame(raw) {
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

function encodeFrame(kind, payload) {
  if (payload == null) {
    return encoder.encode(JSON.stringify(kind));
  }
  return encoder.encode(JSON.stringify({ [kind]: payload }));
}

async function verifySignature(peerId, signature, message) {
  const publicKey = fromArray(peerId, 32, "peer_id");
  const sig = fromByteVector(signature, "signature");
  return ed.verify(sig, message, publicKey);
}

async function verifyRegister(peerId, signature, auth) {
  return verifySignature(peerId, signature, registerChallenge(peerId, auth));
}

function envelopeIdHex(envelope) {
  return toHex(fromArray(envelope.message_id, 16, "message_id"));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/healthz" || MAILBOX_PATHS.has(url.pathname) || url.pathname === "/ops/status") {
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
    this.seenNonces = new Map();
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
    };
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/healthz") {
      return responseJson(await this.healthValue());
    }

    if (request.method === "OPTIONS" && (MAILBOX_PATHS.has(url.pathname) || url.pathname === "/ops/status")) {
      return responseNoContent();
    }

    if (url.pathname === "/ops/status") {
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

  bridgeFingerprint() {
    const hex = (this.env.BRIDGE_FINGERPRINT_HEX || "").trim();
    if (!hex || hex.length !== 64) {
      return new Array(32).fill(0);
    }
    const out = [];
    for (let i = 0; i < hex.length; i += 2) {
      out.push(parseInt(hex.slice(i, i + 2), 16));
    }
    return out;
  }

  async queueKey(peerHex) {
    return `queue:${peerHex}`;
  }

  async loadQueue(peerHex) {
    return (await this.ctx.storage.get(await this.queueKey(peerHex))) || [];
  }

  async saveQueue(peerHex, queue) {
    await this.ctx.storage.put(await this.queueKey(peerHex), queue);
  }

  maxQueuePerPeer() {
    return envNumber(this.env, "MAX_QUEUE_PER_PEER", 256);
  }

  specs() {
    return {
      mailboxIp: rateLimitSpec(this.env, "MAILBOX_IP_RATE", DEFAULT_RATE_LIMITS.mailboxIp),
      mailboxPeer: rateLimitSpec(this.env, "MAILBOX_PEER_RATE", DEFAULT_RATE_LIMITS.mailboxPeer),
      wsRegisterIp: rateLimitSpec(this.env, "WS_REGISTER_IP_RATE", DEFAULT_RATE_LIMITS.wsRegisterIp),
      wsRegisterPeer: rateLimitSpec(this.env, "WS_REGISTER_PEER_RATE", DEFAULT_RATE_LIMITS.wsRegisterPeer),
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
      throw Object.assign(new Error("bridge rate limit exceeded"), { status: 429, rateLimited: true });
    }
    bucket.availableTokens -= 1;
    this.rateBuckets.set(key, bucket);
  }

  enforceMailboxRateLimits(request, peerId) {
    const specs = this.specs();
    const peerHex = toHex(fromArray(peerId, 32, "peer_id"));
    this.enforceRateLimit(`mailbox:ip:${clientIp(request)}`, specs.mailboxIp);
    this.enforceRateLimit(`mailbox:peer:${peerHex}`, specs.mailboxPeer);
  }

  enforceWsRegisterRateLimits(clientIpValue, peerId) {
    const specs = this.specs();
    const peerHex = toHex(fromArray(peerId, 32, "peer_id"));
    this.enforceRateLimit(`ws-register:ip:${clientIpValue || "unknown"}`, specs.wsRegisterIp);
    this.enforceRateLimit(`ws-register:peer:${peerHex}`, specs.wsRegisterPeer);
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

  async verifySignedPayload(peerId, signature, auth, challenge) {
    const now = Date.now();
    this.cleanupNonces(now);

    const peer = fromArray(peerId, 32, "peer_id");
    const peerHex = toHex(peer);
    const timestampMs = Number(auth?.timestamp_ms || 0);
    if (Math.abs(now - timestampMs) > BRIDGE_AUTH_MAX_SKEW_MS) {
      throw Object.assign(new Error("bridge auth timestamp outside freshness window"), { status: 401 });
    }

    const nonceHex = toHex(fromArray(auth?.nonce, 16, "auth.nonce"));
    const peerNonces = this.seenNonces.get(peerHex) || new Map();
    if (peerNonces.has(nonceHex)) {
      throw Object.assign(new Error("bridge auth replay detected"), { status: 401 });
    }

    const verified = await verifySignature(peerId, signature, challenge);
    if (!verified) {
      throw Object.assign(new Error("unauthorized bridge request"), { status: 401 });
    }

    peerNonces.set(nonceHex, timestampMs + BRIDGE_AUTH_MAX_SKEW_MS);
    this.seenNonces.set(peerHex, peerNonces);
    return peerHex;
  }

  async queueEnvelope(envelope) {
    const recipientHex = toHex(fromArray(envelope.recipient, 32, "envelope.recipient"));
    const queue = await this.loadQueue(recipientHex);
    const messageId = envelopeIdHex(envelope);
    let inserted = false;
    if (!queue.some((item) => envelopeIdHex(item) === messageId)) {
      const maxQueue = this.maxQueuePerPeer();
      if (queue.length >= maxQueue) {
        throw Object.assign(new Error("mailbox quota exceeded"), { status: 429 });
      }
      queue.push(envelope);
      inserted = true;
      await this.saveQueue(recipientHex, queue);
    }

    let disposition = "MailboxQueued";
    const liveRecipient = this.livePeers.get(recipientHex);
    if (liveRecipient && this.sessions.has(liveRecipient)) {
      try {
        liveRecipient.send(encodeFrame("Deliver", { envelope }));
        disposition = "LiveBridge";
      } catch {
        this.livePeers.delete(recipientHex);
      }
    }

    if (disposition === "LiveBridge") {
      this.counters.live_delivery_total += 1;
    } else if (inserted) {
      this.counters.mailbox_enqueue_total += 1;
    }

    return { disposition, queueDepth: queue.length };
  }

  async queueStats() {
    const queues = await this.ctx.storage.list({ prefix: "queue:" });
    let totalQueuedEnvelopes = 0;
    let maxQueueDepth = 0;
    let oldestCreatedAtMs = null;
    for (const queue of queues.values()) {
      const depth = Array.isArray(queue) ? queue.length : 0;
      totalQueuedEnvelopes += depth;
      maxQueueDepth = Math.max(maxQueueDepth, depth);
      if (Array.isArray(queue)) {
        for (const envelope of queue) {
          const created = Number(envelope?.created_at_ms || 0);
          if (created > 0 && (oldestCreatedAtMs == null || created < oldestCreatedAtMs)) {
            oldestCreatedAtMs = created;
          }
        }
      }
    }
    const oldestMailboxAgeMs = oldestCreatedAtMs == null ? 0 : Math.max(0, Date.now() - oldestCreatedAtMs);
    const maxQueue = this.maxQueuePerPeer();
    return {
      activeMailboxPeers: queues.size,
      totalQueuedEnvelopes,
      maxQueueDepth,
      oldestMailboxAgeMs,
      maxQueueUtilizationPct: maxQueue > 0 ? (maxQueueDepth / maxQueue) * 100 : 0,
    };
  }

  async healthValue() {
    const stats = await this.queueStats();
    const degraded = stats.oldestMailboxAgeMs > 5 * 60 * 1000 || stats.maxQueueUtilizationPct >= 90;
    return {
      ok: !degraded,
      status: degraded ? "degraded" : "ok",
      mode: "serverless-bridge",
      active_sessions: this.sessions.size,
      oldest_mailbox_age_ms: stats.oldestMailboxAgeMs,
      mailbox_lag_state: stats.oldestMailboxAgeMs > 5 * 60 * 1000 ? "degraded" : "ok",
      max_queue_utilization_pct: stats.maxQueueUtilizationPct,
    };
  }

  async statusValue() {
    const stats = await this.queueStats();
    const liveDeliveryTotal = this.counters.live_delivery_total;
    const mailboxEnqueueTotal = this.counters.mailbox_enqueue_total;
    const deliveryTotal = liveDeliveryTotal + mailboxEnqueueTotal;
    const delivery = {
      live_delivery_total: liveDeliveryTotal,
      mailbox_enqueue_total: mailboxEnqueueTotal,
      live_delivery_rate: deliveryTotal > 0 ? liveDeliveryTotal / deliveryTotal : null,
      mailbox_offload_rate: deliveryTotal > 0 ? mailboxEnqueueTotal / deliveryTotal : null,
    };
    return {
      ok: true,
      mode: "serverless-bridge",
      bridge_fingerprint: this.bridgeFingerprint(),
      active_sessions: this.sessions.size,
      live_peer_count: this.livePeers.size,
      active_mailbox_peers: stats.activeMailboxPeers,
      total_queued_envelopes: stats.totalQueuedEnvelopes,
      max_queue_depth: stats.maxQueueDepth,
      oldest_mailbox_age_ms: stats.oldestMailboxAgeMs,
      max_queue_per_peer: this.maxQueuePerPeer(),
      max_queue_utilization_pct: stats.maxQueueUtilizationPct,
      delivery,
      counters: { ...this.counters },
      rate_limit_bucket_count: this.rateBuckets.size,
    };
  }

  async handleHttpPush(request) {
    this.counters.http_push_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxRateLimits(request, payload.sender);
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
      const { disposition, queueDepth } = await this.queueEnvelope(payload.envelope);
      return responseJson({
        disposition,
        queue_depth: queueDepth,
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      return responseJson({ message: error instanceof Error ? error.message : String(error) }, error.status || 400);
    }
  }

  async handleHttpPull(request) {
    this.counters.http_pull_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxRateLimits(request, payload.peer_id);
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
      return responseJson({
        envelopes: await this.loadQueue(peerHex),
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      return responseJson({ message: error instanceof Error ? error.message : String(error) }, error.status || 400);
    }
  }

  async handleHttpAck(request) {
    this.counters.http_ack_total += 1;
    try {
      const payload = await this.parseJsonRequest(request);
      try {
        this.enforceMailboxRateLimits(request, payload.peer_id);
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
      const toRemove = new Set((payload.message_ids || []).map((id) => toHex(fromArray(id, 16, "message_id"))));
      const queue = await this.loadQueue(peerHex);
      const nextQueue = queue.filter((item) => !toRemove.has(envelopeIdHex(item)));
      this.counters.acked_message_total += Math.max(0, queue.length - nextQueue.length);
      await this.saveQueue(peerHex, nextQueue);
      return responseJson({
        remaining: nextQueue.length,
        bridge_fingerprint: this.bridgeFingerprint(),
      });
    } catch (error) {
      if (error?.status === 401) this.recordAuthFailure();
      return responseJson({ message: error instanceof Error ? error.message : String(error) }, error.status || 400);
    }
  }

  cleanupNonces(now) {
    for (const [peerHex, nonces] of this.seenNonces.entries()) {
      for (const [nonceHex, expiresAt] of nonces.entries()) {
        if (expiresAt <= now) {
          nonces.delete(nonceHex);
        }
      }
      if (nonces.size === 0) {
        this.seenNonces.delete(peerHex);
      }
    }
  }

  async handleRegister(ws, payload) {
    this.counters.ws_register_total += 1;
    const now = Date.now();
    this.cleanupNonces(now);

    const peerId = fromArray(payload.peer_id, 32, "peer_id");
    const peerHex = toHex(peerId);
    const session = this.sessions.get(ws);
    try {
      this.enforceWsRegisterRateLimits(session?.clientIp, payload.peer_id);
    } catch (error) {
      if (error?.rateLimited) this.recordWsRateLimit();
      this.counters.ws_register_rejected_total += 1;
      ws.send(encodeFrame("Error", { message: error instanceof Error ? error.message : String(error) }));
      ws.close(1008, "rate limit");
      return;
    }
    const auth = payload.auth || {};
    const timestampMs = Number(auth.timestamp_ms || 0);
    if (Math.abs(now - timestampMs) > BRIDGE_AUTH_MAX_SKEW_MS) {
      this.recordAuthFailure();
      this.counters.ws_register_rejected_total += 1;
      ws.send(encodeFrame("Error", { message: "bridge auth timestamp outside freshness window" }));
      ws.close(1008, "stale auth");
      return;
    }

    const nonce = fromArray(auth.nonce, 16, "auth.nonce");
    const nonceHex = toHex(nonce);
    const peerNonces = this.seenNonces.get(peerHex) || new Map();
    if (peerNonces.has(nonceHex)) {
      this.recordAuthFailure();
      this.counters.ws_register_rejected_total += 1;
      ws.send(encodeFrame("Error", { message: "bridge auth replay detected" }));
      ws.close(1008, "replay");
      return;
    }

    const verified = await verifyRegister(payload.peer_id, payload.signature, auth);
    if (!verified) {
      this.recordAuthFailure();
      this.counters.ws_register_rejected_total += 1;
      ws.send(encodeFrame("Error", { message: "unauthorized register" }));
      ws.close(1008, "bad signature");
      return;
    }

    peerNonces.set(nonceHex, timestampMs + BRIDGE_AUTH_MAX_SKEW_MS);
    this.seenNonces.set(peerHex, peerNonces);

    session.peerHex = peerHex;
    session.listenForMailbox = Boolean(payload.listen_for_mailbox);
    if (session.listenForMailbox) {
      this.livePeers.set(peerHex, ws);
    }

    const queue = await this.loadQueue(peerHex);
    ws.send(encodeFrame("RegisterOk", {
      bridge_fingerprint: this.bridgeFingerprint(),
      queued_count: queue.length,
    }));

    if (session.listenForMailbox) {
      for (const envelope of queue) {
        ws.send(encodeFrame("Deliver", { envelope }));
      }
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

    const { disposition, queueDepth } = await this.queueEnvelope(envelope);

    ws.send(encodeFrame("PushAck", {
      disposition,
      queue_depth: queueDepth,
    }));
  }

  async handleAck(ws, payload) {
    this.counters.ws_ack_total += 1;
    const session = this.sessions.get(ws);
    if (!session?.peerHex) {
      ws.close(1008, "register required");
      return;
    }

    const toRemove = new Set((payload.message_ids || []).map((id) => toHex(fromArray(id, 16, "message_id"))));
    const queue = await this.loadQueue(session.peerHex);
    const nextQueue = queue.filter((item) => !toRemove.has(envelopeIdHex(item)));
    this.counters.acked_message_total += Math.max(0, queue.length - nextQueue.length);
    await this.saveQueue(session.peerHex, nextQueue);
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
      ws.send(encodeFrame("Error", { message: error instanceof Error ? error.message : String(error) }));
    }
  }

  handleClose(ws) {
    const session = this.sessions.get(ws);
    if (session?.listenForMailbox && session.peerHex) {
      const current = this.livePeers.get(session.peerHex);
      if (current === ws) {
        this.livePeers.delete(session.peerHex);
      }
    }
    this.sessions.delete(ws);
    try {
      ws.close(1000, "closing");
    } catch {
      // no-op
    }
  }
}