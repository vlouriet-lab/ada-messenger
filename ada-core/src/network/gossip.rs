//! Gossip layer using `iroh-gossip` for group chats and call signaling.
//!
//! Provides topic-based pub/sub over bounded overlay trees (HyParView + PlumTree),
//! enabling group messaging scaling to 100+ peers without $O(N)$ unicast mesh overhead.
//!
//! Real-time media streams (Opus voice, VP8/H.264 video) remain on direct full-mesh
//! connections (WebRTC / Iroh QUIC streams) with a hard cap of 6 active participants.

use anyhow::{anyhow, Result};
use bytes::Bytes;
use futures::StreamExt;
use iroh::EndpointId;
use iroh_gossip::api::{Event, GossipSender};
use iroh_gossip::net::Gossip;
use iroh_gossip::proto::TopicId;
pub use iroh_gossip::ALPN as GOSSIP_ALPN;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};

use crate::identity::{Identity, PeerId};

/// Maximum allowed participants in a single real-time group call session.
/// Enforces $< 150$ ms low latency over direct mesh without relay or multi-hop jitter.
pub const MAX_GROUP_CALL_PARTICIPANTS: usize = 6;

/// Derives a blind Gossip TopicId from a 16-byte group ID.
///
/// Ensures the real `group_id` is never exposed in cleartext in the network overlay headers:
/// `TopicId = BLAKE3(group_id || "ada-gossip-topic-v1")`
pub fn derive_blind_topic(group_id: &[u8; 16]) -> TopicId {
    let mut hasher = blake3::Hasher::new();
    hasher.update(group_id);
    hasher.update(b"ada-gossip-topic-v1");
    let hash = hasher.finalize();
    TopicId::from_bytes(*hash.as_bytes())
}

/// Signals exchanged over the gossip topic for group call session setup and management.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum GroupCallSignal {
    /// A peer requests to join or start a group call.
    JoinRequest {
        call_id: [u8; 16],
    },
    /// Announcement of active participants in the call.
    ParticipantList {
        call_id: [u8; 16],
        participants: Vec<[u8; 32]>,
    },
    /// WebRTC / QUIC SDP Offer targeted to a specific peer in the call.
    Offer {
        call_id: [u8; 16],
        target_peer_id: [u8; 32],
        sdp: String,
    },
    /// WebRTC / QUIC SDP Answer targeted to a specific peer in the call.
    Answer {
        call_id: [u8; 16],
        target_peer_id: [u8; 32],
        sdp: String,
    },
    /// Trickle ICE candidate targeted to a specific peer in the call.
    IceCandidate {
        call_id: [u8; 16],
        target_peer_id: [u8; 32],
        candidate: String,
        sdp_mid: Option<String>,
        sdp_mline_index: Option<u16>,
    },
    /// Audio / Video / Screenshare state change announcement.
    MediaState {
        call_id: [u8; 16],
        audio_muted: bool,
        video_muted: bool,
        screenshare: bool,
    },
    /// Peer left the call.
    Leave {
        call_id: [u8; 16],
    },
}

/// Payload carried inside a signed `GossipEnvelope`.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub enum GossipPayload {
    /// Text or file metadata encrypted with the group's SenderKey.
    ChatMessage {
        ciphertext: Vec<u8>,
        sequence_number: u64,
    },
    /// SenderKey rotation or distribution message.
    SenderKeyDistribution {
        sender_key_bytes: Vec<u8>,
        epoch: u64,
    },
    /// Signaling for real-time group calls (presence, SDP, ICE).
    CallSignal(GroupCallSignal),
    /// Typing indicator or member presence.
    Presence {
        status: u8, // 0 = offline, 1 = online, 2 = typing
    },
}

/// Authenticated and signed container for all gossip transmissions.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct GossipEnvelope {
    /// 16-byte group identifier.
    pub group_id: [u8; 16],
    /// 32-byte Ed25519 public key of the sending peer.
    pub sender_peer_id: [u8; 32],
    /// Unix millisecond timestamp when the envelope was created.
    pub timestamp: u64,
    /// Inner application payload.
    pub payload: GossipPayload,
    /// Ed25519 signature over `BLAKE3(group_id || sender || timestamp || payload)`.
    pub signature: Vec<u8>,
}

impl GossipEnvelope {
    /// Computes the cryptographic hash that is signed by the sender.
    pub fn compute_signature_hash(
        group_id: &[u8; 16],
        sender: &[u8; 32],
        timestamp: u64,
        payload_bytes: &[u8],
    ) -> [u8; 32] {
        let mut hasher = blake3::Hasher::new();
        hasher.update(b"ada-gossip-envelope-v1");
        hasher.update(group_id);
        hasher.update(sender);
        hasher.update(&timestamp.to_le_bytes());
        hasher.update(payload_bytes);
        *hasher.finalize().as_bytes()
    }

    /// Creates and signs a new `GossipEnvelope`.
    pub fn new_signed(
        identity: &Identity,
        group_id: [u8; 16],
        payload: GossipPayload,
    ) -> Result<Self> {
        let payload_bytes = bincode::serialize(&payload)
            .map_err(|e| anyhow!("Failed to serialize gossip payload: {}", e))?;
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let sender_peer_id = identity.peer_id.0;
        let hash = Self::compute_signature_hash(
            &group_id,
            &sender_peer_id,
            timestamp,
            &payload_bytes,
        );
        let signature = identity.sign(&hash);

        Ok(Self {
            group_id,
            sender_peer_id,
            timestamp,
            payload,
            signature,
        })
    }

    /// Verifies the authenticity and integrity of the envelope using the sender's PeerId.
    pub fn verify(&self) -> bool {
        let Ok(payload_bytes) = bincode::serialize(&self.payload) else {
            return false;
        };
        let hash = Self::compute_signature_hash(
            &self.group_id,
            &self.sender_peer_id,
            self.timestamp,
            &payload_bytes,
        );
        let peer_id = PeerId(self.sender_peer_id);
        peer_id.verify_signature(&hash, &self.signature)
    }

    /// Serializes envelope to binary wire format.
    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        bincode::serialize(self).map_err(|e| anyhow!("Failed to serialize envelope: {}", e))
    }

    /// Deserializes envelope from binary wire format.
    pub fn from_bytes(slice: &[u8]) -> Result<Self> {
        bincode::deserialize(slice).map_err(|e| anyhow!("Failed to deserialize envelope: {}", e))
    }
}

/// Internal handle for an active topic subscription.
#[allow(dead_code)]
struct TopicSubscription {
    topic_id: TopicId,
    sender: GossipSender,
    abort_handle: tokio::task::AbortHandle,
}

/// Service managing iroh-gossip subscriptions and message dissemination for ADA groups.
#[derive(Clone)]
pub struct AdaGossipService {
    gossip: Gossip,
    local_identity: Arc<Identity>,
    topics: Arc<RwLock<HashMap<[u8; 16], TopicSubscription>>>,
    incoming_tx: mpsc::Sender<GossipEnvelope>,
}

impl AdaGossipService {
    /// Creates a new `AdaGossipService` wrapping an underlying `Gossip` instance.
    pub fn new(
        gossip: Gossip,
        local_identity: Arc<Identity>,
        incoming_tx: mpsc::Sender<GossipEnvelope>,
    ) -> Self {
        Self {
            gossip,
            local_identity,
            topics: Arc::new(RwLock::new(HashMap::new())),
            incoming_tx,
        }
    }

    /// Returns a reference to the underlying `Gossip` instance.
    pub fn gossip(&self) -> &Gossip {
        &self.gossip
    }

    /// Subscribes to a group's gossip overlay topic and establishes neighbor connections
    /// with known active bootstrap peers.
    pub async fn subscribe_group(
        &self,
        group_id: [u8; 16],
        bootstrap_peers: Vec<[u8; 32]>,
    ) -> Result<()> {
        let mut topics = self.topics.write().await;
        if topics.contains_key(&group_id) {
            tracing::debug!("Already subscribed to group gossip {:?}", group_id);
            return Ok(());
        }

        let topic_id = derive_blind_topic(&group_id);
        let my_peer_id = self.local_identity.peer_id.0;
        let bootstrap_endpoints: Vec<EndpointId> = bootstrap_peers
            .into_iter()
            .filter(|p| *p != my_peer_id)
            .filter_map(|p| EndpointId::from_bytes(&p).ok())
            .collect();

        tracing::info!(
            "Subscribing to gossip topic for group {:?} with {} bootstrap endpoints",
            hex::encode(group_id),
            bootstrap_endpoints.len()
        );

        let topic = self
            .gossip
            .subscribe(topic_id, bootstrap_endpoints)
            .await
            .map_err(|e| anyhow!("Failed to subscribe to gossip topic: {}", e))?;

        let (sender, mut receiver) = topic.split();
        let incoming_tx = self.incoming_tx.clone();
        let expected_group_id = group_id;

        let task = tokio::spawn(async move {
            while let Some(event_res) = receiver.next().await {
                match event_res {
                    Ok(Event::Received(msg)) => {
                        match GossipEnvelope::from_bytes(&msg.content) {
                            Ok(envelope) => {
                                if envelope.group_id != expected_group_id {
                                    tracing::warn!(
                                        "Gossip envelope group_id mismatch: expected {:?}, got {:?}",
                                        expected_group_id,
                                        envelope.group_id
                                    );
                                    continue;
                                }
                                if !envelope.verify() {
                                    tracing::warn!(
                                        "Gossip envelope signature verification failed from peer {:?}",
                                        envelope.sender_peer_id
                                    );
                                    continue;
                                }
                                if let Err(e) = incoming_tx.send(envelope).await {
                                    tracing::debug!("Gossip incoming channel closed: {}", e);
                                    break;
                                }
                            }
                            Err(e) => {
                                tracing::debug!("Failed to parse gossip envelope bytes: {}", e);
                            }
                        }
                    }
                    Ok(Event::NeighborUp(node_id)) => {
                        tracing::debug!(
                            "Gossip neighbor up on group {:?}: {}",
                            hex::encode(expected_group_id),
                            node_id
                        );
                    }
                    Ok(Event::NeighborDown(node_id)) => {
                        tracing::debug!(
                            "Gossip neighbor down on group {:?}: {}",
                            hex::encode(expected_group_id),
                            node_id
                        );
                    }
                    Ok(Event::Lagged) => {
                        tracing::warn!(
                            "Gossip receiver lagged on group {:?}",
                            hex::encode(expected_group_id)
                        );
                    }
                    Err(e) => {
                        tracing::debug!(
                            "Gossip receiver event error on group {:?}: {}",
                            hex::encode(expected_group_id),
                            e
                        );
                        break;
                    }
                }
            }
        });

        topics.insert(
            group_id,
            TopicSubscription {
                topic_id,
                sender,
                abort_handle: task.abort_handle(),
            },
        );

        Ok(())
    }

    /// Broadcasts a signed message to all members subscribed to the given group.
    pub async fn broadcast(&self, group_id: &[u8; 16], payload: GossipPayload) -> Result<()> {
        let envelope = GossipEnvelope::new_signed(&self.local_identity, *group_id, payload)?;
        let wire_bytes = envelope.to_bytes()?;

        let topics = self.topics.read().await;
        let sub = topics
            .get(group_id)
            .ok_or_else(|| anyhow!("Not subscribed to group {:?}", group_id))?;

        sub.sender
            .broadcast(Bytes::from(wire_bytes))
            .await
            .map_err(|e| anyhow!("Failed to broadcast gossip message: {}", e))?;

        Ok(())
    }

    /// Unsubscribes from a group and halts the background receiver task.
    pub async fn unsubscribe_group(&self, group_id: &[u8; 16]) -> Result<()> {
        let mut topics = self.topics.write().await;
        if let Some(sub) = topics.remove(group_id) {
            sub.abort_handle.abort();
            tracing::info!("Unsubscribed from gossip group {:?}", hex::encode(group_id));
        }
        Ok(())
    }

    /// Checks if the service is currently subscribed to a group.
    pub async fn is_subscribed(&self, group_id: &[u8; 16]) -> bool {
        self.topics.read().await.contains_key(group_id)
    }

    /// Returns a list of currently active group subscriptions.
    pub async fn active_groups(&self) -> Vec<[u8; 16]> {
        self.topics.read().await.keys().cloned().collect()
    }
}

/// Helper function to validate call participant bounds.
pub fn validate_call_participant_count(count: usize) -> Result<(), &'static str> {
    if count > MAX_GROUP_CALL_PARTICIPANTS {
        Err("Group call participant limit exceeded (maximum 6 participants)")
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_blind_topic_deterministic() {
        let group_id = [42u8; 16];
        let t1 = derive_blind_topic(&group_id);
        let t2 = derive_blind_topic(&group_id);
        assert_eq!(t1, t2);

        let other_group = [43u8; 16];
        let t3 = derive_blind_topic(&other_group);
        assert_ne!(t1, t3);
    }

    #[test]
    fn test_envelope_signing_and_verification() {
        let identity = Identity::generate("Alice");
        let group_id = [7u8; 16];
        let payload = GossipPayload::Presence { status: 1 };

        let envelope = GossipEnvelope::new_signed(&identity, group_id, payload.clone()).unwrap();
        assert!(envelope.verify());

        // Tamper with payload
        let mut tampered = envelope.clone();
        tampered.payload = GossipPayload::Presence { status: 2 };
        assert!(!tampered.verify());

        // Tamper with sender
        let mut tampered_sender = envelope.clone();
        tampered_sender.sender_peer_id = [99u8; 32];
        assert!(!tampered_sender.verify());
    }

    #[test]
    fn test_call_participant_limit() {
        assert!(validate_call_participant_count(1).is_ok());
        assert!(validate_call_participant_count(6).is_ok());
        assert!(validate_call_participant_count(7).is_err());
    }

    #[tokio::test]
    async fn test_gossip_service_lifecycle() {
        let identity = Arc::new(Identity::generate("Bob"));
        let secret = identity.iroh_secret_key();
        let endpoint = iroh::Endpoint::builder()
            .secret_key(secret)
            .alpns(vec![GOSSIP_ALPN.to_vec()])
            .bind()
            .await
            .unwrap();

        let gossip = Gossip::builder().spawn(endpoint);
        let (tx, _rx) = mpsc::channel(32);
        let service = AdaGossipService::new(gossip, identity, tx);

        let group_id = [12u8; 16];
        assert!(!service.is_subscribed(&group_id).await);

        service.subscribe_group(group_id, vec![]).await.unwrap();
        assert!(service.is_subscribed(&group_id).await);

        service.broadcast(&group_id, GossipPayload::Presence { status: 1 }).await.unwrap();

        service.unsubscribe_group(&group_id).await.unwrap();
        assert!(!service.is_subscribed(&group_id).await);
    }
}
