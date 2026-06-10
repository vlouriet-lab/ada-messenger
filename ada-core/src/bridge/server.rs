use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, State,
    },
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use futures::{SinkExt, StreamExt};
use tokio::sync::{mpsc, Mutex};

use crate::{
    bridge::{
        mailbox::{
            http_ack_challenge, http_pull_challenge, http_push_challenge, register_challenge,
            BridgeAuth, BridgeEnvelope, BridgeFrame, BridgePushDisposition, HttpAckRequest,
            HttpAckResponse, HttpPullRequest, HttpPullResponse, HttpPushRequest, HttpPushResponse,
            BRIDGE_AUTH_MAX_SKEW_MS,
        },
        manifest::SignedBridgeManifest,
    },
    error::{ADAError, Result},
};

const RATE_LIMIT_IDLE_TTL_MS: u64 = 15 * 60 * 1000;
const MAILBOX_IP_RATE_LIMIT: RateLimitSpec = RateLimitSpec {
    capacity: 60.0,
    refill_tokens_per_sec: 10.0,
};
const MAILBOX_PEER_RATE_LIMIT: RateLimitSpec = RateLimitSpec {
    capacity: 30.0,
    refill_tokens_per_sec: 5.0,
};
const WS_REGISTER_IP_RATE_LIMIT: RateLimitSpec = RateLimitSpec {
    capacity: 12.0,
    refill_tokens_per_sec: 1.0,
};
const WS_REGISTER_PEER_RATE_LIMIT: RateLimitSpec = RateLimitSpec {
    capacity: 12.0,
    refill_tokens_per_sec: 1.0,
};

#[derive(Clone, Copy)]
struct RateLimitSpec {
    capacity: f64,
    refill_tokens_per_sec: f64,
}

#[derive(Clone, Copy)]
struct RateBucketState {
    available_tokens: f64,
    last_refill_ms: u64,
}

impl RateBucketState {
    fn new(spec: RateLimitSpec, now_ms: u64) -> Self {
        Self {
            available_tokens: spec.capacity,
            last_refill_ms: now_ms,
        }
    }

    fn allow(&mut self, spec: RateLimitSpec, now_ms: u64) -> bool {
        let elapsed_ms = now_ms.saturating_sub(self.last_refill_ms);
        let refill = (elapsed_ms as f64 / 1000.0) * spec.refill_tokens_per_sec;
        self.available_tokens = (self.available_tokens + refill).min(spec.capacity);
        self.last_refill_ms = now_ms;
        if self.available_tokens < 1.0 {
            return false;
        }
        self.available_tokens -= 1.0;
        true
    }
}

#[derive(Clone)]
pub struct BridgeServerState {
    inner: Arc<BridgeServerInner>,
}

struct BridgeServerInner {
    manifest: SignedBridgeManifest,
    bridge_fingerprint: [u8; 32],
    max_queue_per_peer: usize,
    queues: Mutex<HashMap<[u8; 32], Vec<BridgeEnvelope>>>,
    live_peers: Mutex<HashMap<[u8; 32], mpsc::UnboundedSender<BridgeFrame>>>,
    ws_register_total: AtomicU64,
    ws_register_rejected_total: AtomicU64,
    ws_push_total: AtomicU64,
    ws_ack_total: AtomicU64,
    http_push_total: AtomicU64,
    http_pull_total: AtomicU64,
    http_ack_total: AtomicU64,
    live_delivery_total: AtomicU64,
    mailbox_enqueue_total: AtomicU64,
    acked_message_total: AtomicU64,
    auth_failures_total: AtomicU64,
    rate_limited_total: AtomicU64,
    http_rate_limited_total: AtomicU64,
    ws_rate_limited_total: AtomicU64,
    seen_auth_nonces: Mutex<HashMap<[u8; 32], HashMap<[u8; 16], u64>>>,
    rate_limit_buckets: Mutex<HashMap<String, RateBucketState>>,
}

fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

impl BridgeServerState {
    pub fn new(
        manifest: SignedBridgeManifest,
        bridge_fingerprint: [u8; 32],
        max_queue_per_peer: usize,
    ) -> Self {
        Self {
            inner: Arc::new(BridgeServerInner {
                manifest,
                bridge_fingerprint,
                max_queue_per_peer,
                queues: Mutex::new(HashMap::new()),
                live_peers: Mutex::new(HashMap::new()),
                ws_register_total: AtomicU64::new(0),
                ws_register_rejected_total: AtomicU64::new(0),
                ws_push_total: AtomicU64::new(0),
                ws_ack_total: AtomicU64::new(0),
                http_push_total: AtomicU64::new(0),
                http_pull_total: AtomicU64::new(0),
                http_ack_total: AtomicU64::new(0),
                live_delivery_total: AtomicU64::new(0),
                mailbox_enqueue_total: AtomicU64::new(0),
                acked_message_total: AtomicU64::new(0),
                auth_failures_total: AtomicU64::new(0),
                rate_limited_total: AtomicU64::new(0),
                http_rate_limited_total: AtomicU64::new(0),
                ws_rate_limited_total: AtomicU64::new(0),
                seen_auth_nonces: Mutex::new(HashMap::new()),
                rate_limit_buckets: Mutex::new(HashMap::new()),
            }),
        }
    }

    pub fn router(&self) -> Router {
        Router::new()
            .route("/manifest", get(get_manifest))
            .route("/healthz", get(healthz))
            .route("/ops/status", get(ops_status))
            .route("/mailbox/push", post(http_push))
            .route("/mailbox/pull", post(http_pull))
            .route("/mailbox/ack", post(http_ack))
            .route("/ada", get(ws_upgrade))
            .with_state(self.clone())
    }

    pub async fn serve(self, listener: tokio::net::TcpListener) -> Result<()> {
        axum::serve(
            listener,
            self.router()
                .into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .map_err(|e| ADAError::Bridge(format!("bridge server: {}", e)))
    }

    async fn enqueue(&self, envelope: BridgeEnvelope) -> Result<u32> {
        let mut queues = self.inner.queues.lock().await;
        let queue = queues.entry(envelope.recipient).or_default();
        if !queue
            .iter()
            .any(|item| item.message_id == envelope.message_id)
        {
            if queue.len() >= self.inner.max_queue_per_peer {
                return Err(ADAError::Bridge("mailbox quota exceeded".into()));
            }
            queue.push(envelope);
        }
        Ok(queue.len() as u32)
    }

    async fn pending(&self, peer_id: [u8; 32]) -> Vec<BridgeEnvelope> {
        self.inner
            .queues
            .lock()
            .await
            .get(&peer_id)
            .cloned()
            .unwrap_or_default()
    }

    async fn ack(&self, peer_id: [u8; 32], message_ids: &[[u8; 16]]) -> u32 {
        let mut queues = self.inner.queues.lock().await;
        let queue = queues.entry(peer_id).or_default();
        queue.retain(|item| !message_ids.contains(&item.message_id));
        queue.len() as u32
    }

    async fn send_live(&self, envelope: &BridgeEnvelope) -> bool {
        let tx = self
            .inner
            .live_peers
            .lock()
            .await
            .get(&envelope.recipient)
            .cloned();
        if let Some(tx) = tx {
            tx.send(BridgeFrame::Deliver {
                envelope: envelope.clone(),
            })
            .is_ok()
        } else {
            false
        }
    }

    async fn register_live_peer(&self, peer_id: [u8; 32], tx: mpsc::UnboundedSender<BridgeFrame>) {
        self.inner.live_peers.lock().await.insert(peer_id, tx);
    }

    async fn unregister_live_peer(&self, peer_id: [u8; 32]) {
        self.inner.live_peers.lock().await.remove(&peer_id);
    }

    async fn ops_status_value(&self) -> serde_json::Value {
        let now_ms = unix_now_ms();
        let (active_mailbox_peers, total_queued_envelopes, max_queue_depth, oldest_mailbox_age_ms) = {
            let queues = self.inner.queues.lock().await;
            let active_mailbox_peers =
                queues.values().filter(|queue| !queue.is_empty()).count() as u64;
            let total_queued_envelopes =
                queues.values().map(|queue| queue.len() as u64).sum::<u64>();
            let max_queue_depth = queues
                .values()
                .map(|queue| queue.len() as u64)
                .max()
                .unwrap_or_default();
            let oldest_created_at = queues
                .values()
                .flat_map(|queue| queue.iter().map(|envelope| envelope.created_at_ms))
                .min();
            (
                active_mailbox_peers,
                total_queued_envelopes,
                max_queue_depth,
                oldest_created_at.map(|created_at_ms| now_ms.saturating_sub(created_at_ms)),
            )
        };
        let live_peer_count = self.inner.live_peers.lock().await.len() as u64;
        let max_queue_utilization_pct = if self.inner.max_queue_per_peer == 0 {
            0.0
        } else {
            (max_queue_depth as f64 / self.inner.max_queue_per_peer as f64) * 100.0
        };
        let push_total = self.inner.ws_push_total.load(Ordering::Relaxed)
            + self.inner.http_push_total.load(Ordering::Relaxed);
        let live_delivery_total = self.inner.live_delivery_total.load(Ordering::Relaxed);
        let mailbox_enqueue_total = self.inner.mailbox_enqueue_total.load(Ordering::Relaxed);

        serde_json::json!({
            "bridge_fingerprint": hex::encode(self.inner.bridge_fingerprint),
            "max_queue_per_peer": self.inner.max_queue_per_peer,
            "live_peer_count": live_peer_count,
            "active_mailbox_peers": active_mailbox_peers,
            "total_queued_envelopes": total_queued_envelopes,
            "max_queue_depth": max_queue_depth,
            "max_queue_utilization_pct": max_queue_utilization_pct,
            "oldest_mailbox_age_ms": oldest_mailbox_age_ms,
            "delivery": {
                "push_total": push_total,
                "live_delivery_total": live_delivery_total,
                "mailbox_enqueue_total": mailbox_enqueue_total,
                "live_delivery_rate": if push_total == 0 {
                    None::<f64>
                } else {
                    Some(live_delivery_total as f64 / push_total as f64)
                },
                "mailbox_offload_rate": if push_total == 0 {
                    None::<f64>
                } else {
                    Some(mailbox_enqueue_total as f64 / push_total as f64)
                },
                "acked_message_total": self.inner.acked_message_total.load(Ordering::Relaxed),
            },
            "counters": {
                "ws_register_total": self.inner.ws_register_total.load(Ordering::Relaxed),
                "ws_register_rejected_total": self.inner.ws_register_rejected_total.load(Ordering::Relaxed),
                "ws_push_total": self.inner.ws_push_total.load(Ordering::Relaxed),
                "ws_ack_total": self.inner.ws_ack_total.load(Ordering::Relaxed),
                "http_push_total": self.inner.http_push_total.load(Ordering::Relaxed),
                "http_pull_total": self.inner.http_pull_total.load(Ordering::Relaxed),
                "http_ack_total": self.inner.http_ack_total.load(Ordering::Relaxed),
                "auth_failures_total": self.inner.auth_failures_total.load(Ordering::Relaxed),
                "rate_limited_total": self.inner.rate_limited_total.load(Ordering::Relaxed),
                "http_rate_limited_total": self.inner.http_rate_limited_total.load(Ordering::Relaxed),
                "ws_rate_limited_total": self.inner.ws_rate_limited_total.load(Ordering::Relaxed),
            },
            "health": {
                "status": if max_queue_utilization_pct >= 95.0 || oldest_mailbox_age_ms.unwrap_or_default() >= 300_000 {
                    "degraded"
                } else {
                    "ok"
                },
                "mailbox_backlog_present": total_queued_envelopes > 0,
                "mailbox_lag_state": match oldest_mailbox_age_ms {
                    Some(age_ms) if age_ms >= 300_000 => "critical",
                    Some(age_ms) if age_ms >= 60_000 => "warning",
                    Some(_) => "ok",
                    None => "empty",
                },
            },
        })
    }
}

async fn get_manifest(State(state): State<BridgeServerState>) -> Json<SignedBridgeManifest> {
    Json(state.inner.manifest.clone())
}

async fn healthz(State(state): State<BridgeServerState>) -> Json<serde_json::Value> {
    let ops = state.ops_status_value().await;
    Json(serde_json::json!({
        "status": ops["health"]["status"],
        "mailbox_lag_state": ops["health"]["mailbox_lag_state"],
        "live_peer_count": ops["live_peer_count"],
        "total_queued_envelopes": ops["total_queued_envelopes"],
        "max_queue_utilization_pct": ops["max_queue_utilization_pct"],
        "oldest_mailbox_age_ms": ops["oldest_mailbox_age_ms"],
    }))
}

async fn ops_status(State(state): State<BridgeServerState>) -> Json<serde_json::Value> {
    Json(state.ops_status_value().await)
}

fn ip_rate_limit_key(scope: &str, remote_addr: SocketAddr) -> String {
    format!("{}:ip:{}", scope, remote_addr.ip())
}

fn peer_rate_limit_key(scope: &str, peer_id: &[u8; 32]) -> String {
    format!("{}:peer:{}", scope, hex::encode(peer_id))
}

async fn enforce_rate_limit(
    state: &BridgeServerState,
    key: String,
    spec: RateLimitSpec,
) -> Result<()> {
    let now_ms = unix_now_ms();
    let mut buckets = state.inner.rate_limit_buckets.lock().await;
    buckets
        .retain(|_, bucket| now_ms.saturating_sub(bucket.last_refill_ms) <= RATE_LIMIT_IDLE_TTL_MS);

    let bucket = buckets
        .entry(key)
        .or_insert_with(|| RateBucketState::new(spec, now_ms));
    if bucket.allow(spec, now_ms) {
        return Ok(());
    }
    Err(ADAError::Bridge("bridge rate limit exceeded".into()))
}

async fn enforce_mailbox_rate_limits(
    state: &BridgeServerState,
    remote_addr: SocketAddr,
    peer_id: &[u8; 32],
) -> Result<()> {
    enforce_rate_limit(
        state,
        ip_rate_limit_key("mailbox", remote_addr),
        MAILBOX_IP_RATE_LIMIT,
    )
    .await?;
    enforce_rate_limit(
        state,
        peer_rate_limit_key("mailbox", peer_id),
        MAILBOX_PEER_RATE_LIMIT,
    )
    .await
}

async fn enforce_ws_register_rate_limits(
    state: &BridgeServerState,
    remote_addr: SocketAddr,
    peer_id: &[u8; 32],
) -> Result<()> {
    enforce_rate_limit(
        state,
        ip_rate_limit_key("ws-register", remote_addr),
        WS_REGISTER_IP_RATE_LIMIT,
    )
    .await?;
    enforce_rate_limit(
        state,
        peer_rate_limit_key("ws-register", peer_id),
        WS_REGISTER_PEER_RATE_LIMIT,
    )
    .await
}

fn record_http_rate_limit(state: &BridgeServerState) {
    state
        .inner
        .rate_limited_total
        .fetch_add(1, Ordering::Relaxed);
    state
        .inner
        .http_rate_limited_total
        .fetch_add(1, Ordering::Relaxed);
}

fn record_ws_rate_limit(state: &BridgeServerState) {
    state
        .inner
        .rate_limited_total
        .fetch_add(1, Ordering::Relaxed);
    state
        .inner
        .ws_rate_limited_total
        .fetch_add(1, Ordering::Relaxed);
}

async fn http_push(
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    State(state): State<BridgeServerState>,
    Json(request): Json<HttpPushRequest>,
) -> std::result::Result<Json<HttpPushResponse>, axum::http::StatusCode> {
    state.inner.http_push_total.fetch_add(1, Ordering::Relaxed);
    enforce_mailbox_rate_limits(&state, remote_addr, &request.sender)
        .await
        .map_err(|_| {
            record_http_rate_limit(&state);
            axum::http::StatusCode::TOO_MANY_REQUESTS
        })?;
    let verify_result = verify_peer_signature_with_auth(
        &state,
        &request.sender,
        &http_push_challenge(&request.envelope, &request.auth)
            .map_err(|_| axum::http::StatusCode::BAD_REQUEST)?,
        &request.signature,
        &request.auth,
    )
    .await;
    verify_result.map_err(|_| {
        state
            .inner
            .auth_failures_total
            .fetch_add(1, Ordering::Relaxed);
        axum::http::StatusCode::UNAUTHORIZED
    })?;

    let queue_depth = state
        .enqueue(request.envelope.clone())
        .await
        .map_err(|_| axum::http::StatusCode::TOO_MANY_REQUESTS)?;
    let live = state.send_live(&request.envelope).await;
    if live {
        state
            .inner
            .live_delivery_total
            .fetch_add(1, Ordering::Relaxed);
    } else {
        state
            .inner
            .mailbox_enqueue_total
            .fetch_add(1, Ordering::Relaxed);
    }
    Ok(Json(HttpPushResponse {
        disposition: if live {
            BridgePushDisposition::LiveBridge
        } else {
            BridgePushDisposition::MailboxQueued
        },
        queue_depth,
        bridge_fingerprint: Some(state.inner.bridge_fingerprint),
    }))
}

async fn http_pull(
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    State(state): State<BridgeServerState>,
    Json(request): Json<HttpPullRequest>,
) -> std::result::Result<Json<HttpPullResponse>, axum::http::StatusCode> {
    state.inner.http_pull_total.fetch_add(1, Ordering::Relaxed);
    enforce_mailbox_rate_limits(&state, remote_addr, &request.peer_id)
        .await
        .map_err(|_| {
            record_http_rate_limit(&state);
            axum::http::StatusCode::TOO_MANY_REQUESTS
        })?;
    let verify_result = verify_peer_signature_with_auth(
        &state,
        &request.peer_id,
        &http_pull_challenge(&request.peer_id, &request.auth)
            .map_err(|_| axum::http::StatusCode::BAD_REQUEST)?,
        &request.signature,
        &request.auth,
    )
    .await;
    verify_result.map_err(|_| {
        state
            .inner
            .auth_failures_total
            .fetch_add(1, Ordering::Relaxed);
        axum::http::StatusCode::UNAUTHORIZED
    })?;
    Ok(Json(HttpPullResponse {
        envelopes: state.pending(request.peer_id).await,
        bridge_fingerprint: Some(state.inner.bridge_fingerprint),
    }))
}

async fn http_ack(
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    State(state): State<BridgeServerState>,
    Json(request): Json<HttpAckRequest>,
) -> std::result::Result<Json<HttpAckResponse>, axum::http::StatusCode> {
    state.inner.http_ack_total.fetch_add(1, Ordering::Relaxed);
    enforce_mailbox_rate_limits(&state, remote_addr, &request.peer_id)
        .await
        .map_err(|_| {
            record_http_rate_limit(&state);
            axum::http::StatusCode::TOO_MANY_REQUESTS
        })?;
    let verify_result = verify_peer_signature_with_auth(
        &state,
        &request.peer_id,
        &http_ack_challenge(&request.peer_id, &request.message_ids, &request.auth)
            .map_err(|_| axum::http::StatusCode::BAD_REQUEST)?,
        &request.signature,
        &request.auth,
    )
    .await;
    verify_result.map_err(|_| {
        state
            .inner
            .auth_failures_total
            .fetch_add(1, Ordering::Relaxed);
        axum::http::StatusCode::UNAUTHORIZED
    })?;

    let remaining = state.ack(request.peer_id, &request.message_ids).await;
    state
        .inner
        .acked_message_total
        .fetch_add(request.message_ids.len() as u64, Ordering::Relaxed);
    Ok(Json(HttpAckResponse {
        remaining,
        bridge_fingerprint: Some(state.inner.bridge_fingerprint),
    }))
}

async fn ws_upgrade(
    ws: WebSocketUpgrade,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    State(state): State<BridgeServerState>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_ws(socket, state, remote_addr))
}

async fn handle_ws(socket: WebSocket, state: BridgeServerState, remote_addr: SocketAddr) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<BridgeFrame>();

    let writer = tokio::spawn(async move {
        while let Some(frame) = rx.recv().await {
            let bytes = match bincode::serialize(&frame) {
                Ok(bytes) => bytes,
                Err(_) => break,
            };
            if sink.send(Message::Binary(bytes)).await.is_err() {
                break;
            }
        }
    });

    let mut registered_peer: Option<[u8; 32]> = None;
    let mut listen_for_mailbox = false;

    while let Some(message) = stream.next().await {
        let Ok(message) = message else {
            break;
        };
        let Message::Binary(bytes) = message else {
            continue;
        };
        let Ok(frame) = bincode::deserialize::<BridgeFrame>(&bytes) else {
            continue;
        };

        match frame {
            BridgeFrame::Register {
                peer_id,
                signature,
                listen_for_mailbox: listen,
                auth,
            } => {
                state
                    .inner
                    .ws_register_total
                    .fetch_add(1, Ordering::Relaxed);
                if enforce_ws_register_rate_limits(&state, remote_addr, &peer_id)
                    .await
                    .is_err()
                {
                    state
                        .inner
                        .ws_register_rejected_total
                        .fetch_add(1, Ordering::Relaxed);
                    record_ws_rate_limit(&state);
                    let _ = tx.send(BridgeFrame::Error {
                        message: "rate limit exceeded".into(),
                    });
                    break;
                }
                let register_payload = match register_challenge(&peer_id, &auth) {
                    Ok(payload) => payload,
                    Err(_) => {
                        state
                            .inner
                            .ws_register_rejected_total
                            .fetch_add(1, Ordering::Relaxed);
                        state
                            .inner
                            .auth_failures_total
                            .fetch_add(1, Ordering::Relaxed);
                        let _ = tx.send(BridgeFrame::Error {
                            message: "unauthorized register".into(),
                        });
                        break;
                    }
                };
                if verify_peer_signature_with_auth(
                    &state,
                    &peer_id,
                    &register_payload,
                    &signature,
                    &auth,
                )
                .await
                .is_err()
                {
                    state
                        .inner
                        .ws_register_rejected_total
                        .fetch_add(1, Ordering::Relaxed);
                    state
                        .inner
                        .auth_failures_total
                        .fetch_add(1, Ordering::Relaxed);
                    let _ = tx.send(BridgeFrame::Error {
                        message: "unauthorized register".into(),
                    });
                    break;
                }

                registered_peer = Some(peer_id);
                listen_for_mailbox = listen;
                if listen {
                    state.register_live_peer(peer_id, tx.clone()).await;
                }
                let _ = tx.send(BridgeFrame::RegisterOk {
                    bridge_fingerprint: state.inner.bridge_fingerprint,
                    queued_count: state.pending(peer_id).await.len() as u32,
                });
                if listen {
                    for envelope in state.pending(peer_id).await {
                        let _ = tx.send(BridgeFrame::Deliver { envelope });
                    }
                }
            }
            BridgeFrame::Push { envelope } => {
                let Some(peer_id) = registered_peer else {
                    break;
                };
                state.inner.ws_push_total.fetch_add(1, Ordering::Relaxed);
                if envelope.sender != peer_id {
                    let _ = tx.send(BridgeFrame::Error {
                        message: "sender mismatch".into(),
                    });
                    continue;
                }

                let queue_depth = match state.enqueue(envelope.clone()).await {
                    Ok(depth) => depth,
                    Err(e) => {
                        let _ = tx.send(BridgeFrame::Error {
                            message: e.to_string(),
                        });
                        continue;
                    }
                };
                let live = state.send_live(&envelope).await;
                if live {
                    state
                        .inner
                        .live_delivery_total
                        .fetch_add(1, Ordering::Relaxed);
                } else {
                    state
                        .inner
                        .mailbox_enqueue_total
                        .fetch_add(1, Ordering::Relaxed);
                }
                let _ = tx.send(BridgeFrame::PushAck {
                    disposition: if live {
                        BridgePushDisposition::LiveBridge
                    } else {
                        BridgePushDisposition::MailboxQueued
                    },
                    queue_depth,
                });
            }
            BridgeFrame::Ack { message_ids } => {
                let Some(peer_id) = registered_peer else {
                    break;
                };
                state.inner.ws_ack_total.fetch_add(1, Ordering::Relaxed);
                state
                    .inner
                    .acked_message_total
                    .fetch_add(message_ids.len() as u64, Ordering::Relaxed);
                let _ = state.ack(peer_id, &message_ids).await;
            }
            BridgeFrame::Ping => {
                let _ = tx.send(BridgeFrame::Pong);
            }
            BridgeFrame::Pong
            | BridgeFrame::RegisterOk { .. }
            | BridgeFrame::PushAck { .. }
            | BridgeFrame::Deliver { .. }
            | BridgeFrame::Error { .. } => {}
        }
    }

    if let Some(peer_id) = registered_peer.filter(|_| listen_for_mailbox) {
        state.unregister_live_peer(peer_id).await;
    }
    writer.abort();
}

fn verify_peer_signature(peer_id: &[u8; 32], payload: &[u8], signature: &[u8]) -> Result<()> {
    let key = VerifyingKey::from_bytes(peer_id).map_err(|_| ADAError::InvalidSignature)?;
    let sig: [u8; 64] = signature
        .try_into()
        .map_err(|_| ADAError::InvalidSignature)?;
    key.verify(payload, &Signature::from_bytes(&sig))
        .map_err(|_| ADAError::InvalidSignature)
}

fn verify_auth_timestamp(auth: &BridgeAuth) -> Result<()> {
    let now_ms = unix_now_ms();
    if now_ms.abs_diff(auth.timestamp_ms) > BRIDGE_AUTH_MAX_SKEW_MS {
        return Err(ADAError::Bridge(
            "bridge auth timestamp outside freshness window".into(),
        ));
    }
    Ok(())
}

async fn remember_auth_nonce(
    state: &BridgeServerState,
    peer_id: &[u8; 32],
    auth: &BridgeAuth,
) -> Result<()> {
    let now_ms = unix_now_ms();
    let mut seen = state.inner.seen_auth_nonces.lock().await;
    seen.retain(|_, peer_nonces| {
        peer_nonces.retain(|_, expires_at_ms| *expires_at_ms > now_ms);
        !peer_nonces.is_empty()
    });

    let peer_nonces = seen.entry(*peer_id).or_default();
    if peer_nonces.contains_key(&auth.nonce) {
        return Err(ADAError::Bridge("bridge auth replay detected".into()));
    }
    peer_nonces.insert(
        auth.nonce,
        auth.timestamp_ms.saturating_add(BRIDGE_AUTH_MAX_SKEW_MS),
    );
    Ok(())
}

async fn verify_peer_signature_with_auth(
    state: &BridgeServerState,
    peer_id: &[u8; 32],
    payload: &[u8],
    signature: &[u8],
    auth: &BridgeAuth,
) -> Result<()> {
    verify_auth_timestamp(auth)?;
    verify_peer_signature(peer_id, payload, signature)?;
    remember_auth_nonce(state, peer_id, auth).await
}

pub fn default_bind_addr() -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], 8787))
}

#[cfg(test)]
mod tests {
    use super::*;

    use ed25519_dalek::{Signer, SigningKey};

    use crate::bridge::{
        mailbox::{fresh_bridge_auth, register_challenge, BridgeAuth, BridgeDeliveryLane},
        manifest::BridgeManifestPayload,
    };

    fn sample_state() -> BridgeServerState {
        let signing_key = SigningKey::from_bytes(&[3u8; 32]);
        let manifest = BridgeManifestPayload {
            version: 1,
            issued_at_ms: 1,
            ttl_secs: 600,
            max_attachment_bytes: Some(8_192),
            supports_realtime_calls: false,
            bridges: Vec::new(),
        }
        .to_signed(&signing_key)
        .expect("manifest should sign");

        BridgeServerState::new(manifest, [6u8; 32], 8)
    }

    fn sample_envelope(message_id: [u8; 16], recipient: [u8; 32]) -> BridgeEnvelope {
        let now_ms = unix_now_ms();
        BridgeEnvelope {
            message_id,
            sender: [1u8; 32],
            recipient,
            lane: BridgeDeliveryLane::TextDm,
            wire_bytes: vec![1, 2, 3],
            created_at_ms: now_ms,
            expires_at: now_ms + 100,
        }
    }

    #[tokio::test]
    async fn enqueue_deduplicates_and_ack_removes_messages() {
        let state = sample_state();
        let peer_id = [9u8; 32];
        let envelope = sample_envelope([7u8; 16], peer_id);

        let depth = state
            .enqueue(envelope.clone())
            .await
            .expect("first enqueue should succeed");
        let duplicate_depth = state
            .enqueue(envelope.clone())
            .await
            .expect("duplicate enqueue should be ignored");
        let pending = state.pending(peer_id).await;
        let remaining = state.ack(peer_id, &[envelope.message_id]).await;

        assert_eq!(depth, 1);
        assert_eq!(duplicate_depth, 1);
        assert_eq!(pending.len(), 1);
        assert_eq!(remaining, 0);
        assert!(state.pending(peer_id).await.is_empty());
    }

    #[tokio::test]
    async fn send_live_delivers_to_registered_peer() {
        let state = sample_state();
        let recipient = [5u8; 32];
        let envelope = sample_envelope([8u8; 16], recipient);
        let (tx, mut rx) = mpsc::unbounded_channel();

        state.register_live_peer(recipient, tx).await;
        let delivered = state.send_live(&envelope).await;
        let frame = rx.recv().await.expect("live peer should receive delivery");

        assert!(delivered);
        match frame {
            BridgeFrame::Deliver {
                envelope: delivered,
            } => {
                assert_eq!(delivered.message_id, envelope.message_id);
                assert_eq!(delivered.recipient, envelope.recipient);
            }
            other => panic!("unexpected frame: {:?}", other),
        }

        state.unregister_live_peer(recipient).await;
    }

    #[tokio::test]
    async fn ops_status_reports_queue_health_and_counters() {
        let state = sample_state();
        let recipient = [4u8; 32];
        let envelope = sample_envelope([2u8; 16], recipient);
        let (tx, _rx) = mpsc::unbounded_channel();

        state
            .enqueue(envelope)
            .await
            .expect("enqueue should succeed");
        state.register_live_peer(recipient, tx).await;
        state
            .inner
            .ws_register_total
            .fetch_add(1, Ordering::Relaxed);
        state
            .inner
            .live_delivery_total
            .fetch_add(1, Ordering::Relaxed);
        state
            .inner
            .mailbox_enqueue_total
            .fetch_add(1, Ordering::Relaxed);
        state
            .inner
            .rate_limited_total
            .fetch_add(2, Ordering::Relaxed);
        state
            .inner
            .http_rate_limited_total
            .fetch_add(1, Ordering::Relaxed);
        state
            .inner
            .ws_rate_limited_total
            .fetch_add(1, Ordering::Relaxed);

        let ops = state.ops_status_value().await;

        assert_eq!(ops["live_peer_count"].as_u64(), Some(1));
        assert_eq!(ops["total_queued_envelopes"].as_u64(), Some(1));
        assert_eq!(ops["max_queue_depth"].as_u64(), Some(1));
        assert_eq!(ops["delivery"]["mailbox_enqueue_total"].as_u64(), Some(1));
        assert_eq!(ops["delivery"]["live_delivery_total"].as_u64(), Some(1));
        assert_eq!(ops["counters"]["ws_register_total"].as_u64(), Some(1));
        assert_eq!(ops["counters"]["rate_limited_total"].as_u64(), Some(2));
        assert_eq!(ops["counters"]["http_rate_limited_total"].as_u64(), Some(1));
        assert_eq!(ops["counters"]["ws_rate_limited_total"].as_u64(), Some(1));
        assert_eq!(ops["health"]["status"].as_str(), Some("ok"));

        state.unregister_live_peer(recipient).await;
    }

    #[tokio::test]
    async fn token_bucket_rejects_burst_above_capacity() {
        let state = sample_state();
        let key = String::from("test:peer:burst");
        let spec = RateLimitSpec {
            capacity: 2.0,
            refill_tokens_per_sec: 0.0,
        };

        enforce_rate_limit(&state, key.clone(), spec)
            .await
            .expect("first token should be available");
        enforce_rate_limit(&state, key.clone(), spec)
            .await
            .expect("second token should be available");

        let err = enforce_rate_limit(&state, key, spec)
            .await
            .expect_err("third request should exceed the burst capacity");

        assert!(
            matches!(err, ADAError::Bridge(message) if message.contains("rate limit exceeded"))
        );
    }

    #[tokio::test]
    async fn auth_replay_cache_rejects_nonce_reuse() {
        let state = sample_state();
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let peer_id = signing_key.verifying_key().to_bytes();
        let auth = fresh_bridge_auth();
        let payload = register_challenge(&peer_id, &auth).expect("register challenge should build");
        let signature = signing_key.sign(&payload).to_bytes().to_vec();

        verify_peer_signature_with_auth(&state, &peer_id, &payload, &signature, &auth)
            .await
            .expect("first auth should verify");

        let err = verify_peer_signature_with_auth(&state, &peer_id, &payload, &signature, &auth)
            .await
            .expect_err("replayed nonce should be rejected");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("replay detected")));
    }

    #[tokio::test]
    async fn auth_rejects_stale_timestamp() {
        let state = sample_state();
        let signing_key = SigningKey::from_bytes(&[8u8; 32]);
        let peer_id = signing_key.verifying_key().to_bytes();
        let auth = BridgeAuth {
            nonce: [9u8; 16],
            timestamp_ms: unix_now_ms().saturating_sub(BRIDGE_AUTH_MAX_SKEW_MS + 10),
        };
        let payload = register_challenge(&peer_id, &auth).expect("register challenge should build");
        let signature = signing_key.sign(&payload).to_bytes().to_vec();

        let err = verify_peer_signature_with_auth(&state, &peer_id, &payload, &signature, &auth)
            .await
            .expect_err("stale auth timestamp should be rejected");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("freshness window")));
    }
}
