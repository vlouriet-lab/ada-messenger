use crate::error::{ADAError, Result};
use crate::identity::PeerId;
use crate::messaging::types::{Message, WireMessage};
use crate::network::relay_reputation;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tokio::sync::mpsc;

/// Routing decision for an outbound message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum RoutingDecision {
    /// Send directly to peer via P2P
    Direct(PeerId),
    /// Send via relay node
    Relay { relay: PeerId, recipient: PeerId },
    /// Peer is online locally (mDNS)
    Local(PeerId),
    /// Broadcast to group members (iroh unicast fan-out)
    GroupBroadcast { group_id: [u8; 16] },
}

impl RoutingDecision {
    pub fn target_peer(&self) -> Option<&PeerId> {
        match self {
            RoutingDecision::Direct(p) | RoutingDecision::Local(p) => Some(p),
            RoutingDecision::Relay { recipient, .. } => Some(recipient),
            RoutingDecision::GroupBroadcast { .. } => None,
        }
    }

    pub fn is_group_broadcast(&self) -> bool {
        matches!(self, RoutingDecision::GroupBroadcast { .. })
    }
}

/// Delivery status of an outbound message.
#[derive(Clone, Debug, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum DeliveryStatus {
    Pending,
    Sending,
    Delivered,
    /// All retry attempts exhausted.
    Failed,
    Cancelled,
}

/// Outbound message wrapped with retry metadata.
/// Inspired by Plex's `OutboundMessageEnvelope`.
///
/// Flow:
/// 1. Create with `OutboundEnvelope::new()`
/// 2. Attempt send; on failure call `prepare_retry()`
/// 3. Check `should_give_up()` — if true, mark Failed
/// 4. On next tick check `can_retry_now()` before re-sending
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct OutboundEnvelope {
    /// Hex-encoded message ID for dedup and tracking
    pub message_id: String,
    /// Serialised wire payload
    pub payload: Vec<u8>,
    /// How to deliver this message
    pub routing: RoutingDecision,
    /// Current attempt number (0 = first try)
    pub attempt: u32,
    /// Unix timestamp (secs) when the envelope was created
    pub created_at: u64,
    /// Unix timestamp (secs) before which we must not retry. None = retry immediately.
    pub backoff_until: Option<u64>,
    /// Maximum number of delivery attempts (default: 5)
    pub max_attempts: u32,
}

impl OutboundEnvelope {
    pub fn new(message_id: String, payload: Vec<u8>, routing: RoutingDecision) -> Self {
        OutboundEnvelope {
            message_id,
            payload,
            routing,
            attempt: 0,
            created_at: unix_now(),
            backoff_until: None,
            max_attempts: 5,
        }
    }

    /// `true` if we have exceeded the retry budget.
    pub fn should_give_up(&self) -> bool {
        self.attempt >= self.max_attempts
    }

    /// `true` if the backoff period has elapsed and we may attempt delivery now.
    pub fn can_retry_now(&self) -> bool {
        self.backoff_until.is_none_or(|t| unix_now() >= t)
    }

    /// Compute exponential backoff in seconds: `2^attempt`, capped at 300s (5 min).
    pub fn backoff_for_attempt(attempt: u32) -> u64 {
        (1u64 << attempt.min(16)).min(300)
    }

    /// Advance attempt counter and set the next backoff window.
    /// Call this after a failed delivery attempt.
    pub fn prepare_retry(&mut self) {
        self.attempt += 1;
        let secs = Self::backoff_for_attempt(self.attempt);
        self.backoff_until = Some(unix_now() + secs);
        tracing::info!(
            "[router] msg {} retry {} scheduled in {}s",
            self.message_id,
            self.attempt,
            secs
        );
    }

    pub fn size_bytes(&self) -> usize {
        self.payload.len()
    }
}

/// Outbound delivery request (legacy, kept for internal channel use)
#[derive(Debug)]
pub struct OutboundMessage {
    pub wire: WireMessage,
    pub routing: RoutingDecision,
    pub attempt: u32,
}

/// Inbound decrypted message ready for UI
#[derive(Debug)]
pub struct InboundMessage {
    pub message: Message,
    pub raw_wire: WireMessage,
}

/// Message router: decides where to send messages and tracks delivery
pub struct MessageRouter {
    /// Channel to network sender task
    outbound_tx: mpsc::Sender<OutboundMessage>,
    /// Channel from network receiver task
    inbound_rx: mpsc::Receiver<InboundMessage>,
    /// Peer online/offline status
    peer_status: HashMap<PeerId, PeerStatus>,
    /// Retry queue: (message, unix_secs when next attempt is allowed)
    retry_queue: Vec<(OutboundMessage, u64)>,
    /// Known relay peers → reputation score (0..=100, default 50).
    /// Updated via `register_relay` / `update_relay_reputation`.
    relay_peers: HashMap<PeerId, i64>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum PeerStatus {
    Online,
    LocalOnly,
    Offline,
    Unknown,
}

impl MessageRouter {
    pub fn new(
        outbound_tx: mpsc::Sender<OutboundMessage>,
        inbound_rx: mpsc::Receiver<InboundMessage>,
    ) -> Self {
        MessageRouter {
            outbound_tx,
            inbound_rx,
            peer_status: HashMap::new(),
            retry_queue: Vec::new(),
            relay_peers: HashMap::new(),
        }
    }

    /// Register a peer as a known relay node with a neutral starting score.
    pub fn register_relay(&mut self, peer: PeerId) {
        self.relay_peers
            .entry(peer)
            .or_insert(relay_reputation::DEFAULT_REPUTATION);
    }

    /// Update a relay's reputation after a delivery attempt.
    pub fn update_relay_reputation(&mut self, peer: &PeerId, success: bool) {
        if let Some(score) = self.relay_peers.get_mut(peer) {
            *score = relay_reputation::score_after_event(*score, success);
        }
    }

    pub fn update_peer_status(&mut self, peer: PeerId, status: PeerStatus) {
        self.peer_status.insert(peer, status);
    }

    pub fn peer_status(&self, peer: &PeerId) -> PeerStatus {
        self.peer_status
            .get(peer)
            .cloned()
            .unwrap_or(PeerStatus::Unknown)
    }

    /// Determine how to route a message to a given peer
    pub fn routing_for(&self, peer: &PeerId) -> RoutingDecision {
        match self.peer_status(peer) {
            PeerStatus::LocalOnly => RoutingDecision::Local(peer.clone()),
            PeerStatus::Online | PeerStatus::Unknown => RoutingDecision::Direct(peer.clone()),
            PeerStatus::Offline => RoutingDecision::Relay {
                relay: self.best_relay(),
                recipient: peer.clone(),
            },
        }
    }

    /// Choose the best available relay peer, ranked by reputation score.
    /// Returns a zero PeerId (and logs a warning) only when no relays are registered.
    fn best_relay(&self) -> PeerId {
        self.relay_peers
            .iter()
            .filter(|(_, &score)| !relay_reputation::should_avoid(score))
            .max_by_key(|(_, &score)| score)
            .map(|(peer, _)| peer.clone())
            .unwrap_or_else(|| {
                tracing::warn!("[router] no relay peers registered — offline delivery unavailable");
                PeerId([0u8; 32])
            })
    }

    pub async fn send(&self, msg: OutboundMessage) -> Result<()> {
        self.outbound_tx
            .send(msg)
            .await
            .map_err(|_| ADAError::Network("Outbound channel closed".into()))
    }

    pub async fn recv(&mut self) -> Option<InboundMessage> {
        self.inbound_rx.recv().await
    }

    /// Enqueue a failed outbound message for retry with exponential backoff.
    ///
    /// Messages that have already reached `max_attempts` (5) are dropped
    /// and a warning is logged instead of being re-queued indefinitely.
    pub fn enqueue_retry(&mut self, mut msg: OutboundMessage) {
        const MAX_ATTEMPTS: u32 = 5;
        msg.attempt += 1;
        if msg.attempt > MAX_ATTEMPTS {
            tracing::warn!(
                "[router] dropping outbound msg to {} — exceeded {} retry attempts",
                msg.wire.recipient,
                MAX_ATTEMPTS
            );
            return;
        }
        let backoff_secs = OutboundEnvelope::backoff_for_attempt(msg.attempt);
        let next_at = unix_now() + backoff_secs;
        tracing::info!(
            "[router] scheduled retry {}/{} for {} in {}s",
            msg.attempt,
            MAX_ATTEMPTS,
            msg.wire.recipient,
            backoff_secs
        );
        self.retry_queue.push((msg, next_at));
    }

    /// Re-send any queued messages whose backoff window has elapsed.
    /// Call this on every maintenance tick (~60s) to ensure eventual delivery.
    pub async fn process_retries(&mut self) -> Result<()> {
        if self.retry_queue.is_empty() {
            return Ok(());
        }
        let now = unix_now();

        // Drain the full queue and split into ready vs. still-waiting.
        let all: Vec<(OutboundMessage, u64)> = self.retry_queue.drain(..).collect();
        let mut remaining = Vec::with_capacity(all.len());

        for (msg, next_at) in all {
            if now >= next_at {
                tracing::info!(
                    "[router] retrying delivery to {} (attempt {})",
                    msg.wire.recipient,
                    msg.attempt
                );
                if let Err(e) = self.outbound_tx.send(msg).await {
                    // Channel closed — log and discard rather than re-queue.
                    tracing::error!("[router] outbound channel closed during retry: {:?}", e);
                }
            } else {
                remaining.push((msg, next_at));
            }
        }
        self.retry_queue = remaining;
        Ok(())
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
