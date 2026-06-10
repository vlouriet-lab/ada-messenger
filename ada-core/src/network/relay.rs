//! Relay node support for offline message delivery
//!
//! When a recipient peer is offline, the sender deposits a sealed (encrypted)
//! message envelope at a relay node. The relay holds it until the recipient
//! comes online and retrieves it. This provides asynchronous delivery without
//! any relay node being able to read the message content.

use crate::error::{ADAError, Result};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

/// Offline message TTL shared by the sender queue and the receiver's acceptance window.
pub const OFFLINE_MESSAGE_TTL_SECS: u64 = 48 * 3600;
/// Maximum messages queued per recipient
const MAX_QUEUE_PER_PEER: usize = 500;

pub fn next_offline_expiry_ts() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
        + OFFLINE_MESSAGE_TTL_SECS
}

/// A sealed (fully encrypted) message stored by a relay node.
/// The relay cannot read the message — it only knows the recipient's PeerId.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SealedMessage {
    /// Opaque 16-byte message ID (set by sender)
    pub message_id: [u8; 16],
    /// Intended recipient
    pub recipient_id: [u8; 32],
    /// Encrypted WireEnvelope bytes (opaque to relay)
    pub payload: Vec<u8>,
    /// Unix timestamp after which the relay MAY discard this message
    pub expires_at: u64,
    /// Number of relay hops traversed so far (anti-loop)
    pub hops: u32,
}

impl SealedMessage {
    /// Create a new SealedMessage with the default TTL
    pub fn new(recipient_id: [u8; 32], payload: Vec<u8>) -> Self {
        let mut message_id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut message_id);
        let expires_at = next_offline_expiry_ts();
        SealedMessage {
            message_id,
            recipient_id,
            payload,
            expires_at,
            hops: 0,
        }
    }

    /// Whether this message has exceeded its TTL
    pub fn is_expired(&self) -> bool {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        now >= self.expires_at
    }
}

/// In-process relay manager (used both by relay nodes and as a local offline-message
/// buffer before network transmission is re-established).
pub struct RelayManager {
    /// Random delivery token -> queued offline messages.
    /// The token is exchanged in-band (via X3DH initial handshake) so the
    /// relay cannot link a token to a recipient's public identity.
    token_queues: RwLock<HashMap<[u8; 32], Vec<SealedMessage>>>,
    /// Optional path for persisting offline message queues across restarts
    storage_path: Option<std::path::PathBuf>,
    /// Set to `true` when the queue has been modified since the last `save_to_disk`.
    /// `flush_if_dirty()` checks this and only writes to disk when necessary,
    /// avoiding a synchronous fsync on every single `enqueue_offline` call.
    dirty: std::sync::atomic::AtomicBool,
}

impl RelayManager {
    pub fn new() -> Self {
        RelayManager {
            token_queues: RwLock::new(HashMap::new()),
            storage_path: None,
            dirty: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// Create with persistence
    pub fn with_storage(path: std::path::PathBuf) -> Self {
        let mut mgr = RelayManager {
            token_queues: RwLock::new(HashMap::new()),
            storage_path: Some(path.clone()),
            dirty: std::sync::atomic::AtomicBool::new(false),
        };
        mgr.load_from_disk();
        mgr
    }

    fn save_to_disk(&self) {
        if let Some(path) = &self.storage_path {
            match bincode::serialize(&*self.token_queues.read()) {
                Ok(bytes) => {
                    if let Err(e) = std::fs::write(path, bytes) {
                        // Log the error so operators see it; the in-memory queue is still
                        // intact for the current process lifetime, but a restart will lose
                        // any messages queued since the last successful save.
                        tracing::error!(
                            "relay: failed to persist offline queue to {:?}: {} — \
                             queued messages may be lost on restart",
                            path,
                            e
                        );
                    }
                }
                Err(e) => {
                    tracing::warn!("Failed to serialize relay queues: {}", e);
                }
            }
        }
    }

    /// Write the queue to disk only if it has been modified since the last save.
    ///
    /// Call this from the periodic maintenance loop (every 60 s) instead of
    /// calling `save_to_disk()` after every single `enqueue_offline`.  This
    /// reduces disk I/O from O(N) per burst to O(1) per maintenance cycle.
    pub fn flush_if_dirty(&self) {
        if self.dirty.swap(false, std::sync::atomic::Ordering::AcqRel) {
            self.save_to_disk();
        }
    }

    fn load_from_disk(&mut self) {
        if let Some(path) = &self.storage_path {
            if let Ok(bytes) = std::fs::read(path) {
                match bincode::deserialize::<HashMap<[u8; 32], Vec<SealedMessage>>>(&bytes) {
                    Ok(queues) => {
                        *self.token_queues.get_mut() = queues;
                        tracing::info!("Loaded offline message queues from disk");
                    }
                    Err(e) => tracing::warn!("Failed to deserialize relay queues: {}", e),
                }
            }
        }
    }

    /// Evict all expired messages across all queues
    pub fn evict_expired(&self) {
        let mut tq = self.token_queues.write();
        let old_len = tq.values().map(|q| q.len()).sum::<usize>();
        for queue in tq.values_mut() {
            queue.retain(|m| !m.is_expired());
        }
        tq.retain(|_, q| !q.is_empty());
        let new_len = tq.values().map(|q| q.len()).sum::<usize>();
        drop(tq);

        if old_len != new_len {
            self.save_to_disk();
        }
    }

    // ── Token-based offline delivery (privacy-preserving) ─────────────────

    /// Deposit a message under a per-contact delivery token.
    ///
    /// The token is a random 32-byte value exchanged in-band during the first
    /// X3DH handshake.  The relay stores the blob without knowing the
    /// recipient's identity — only the token is visible in the index.
    ///
    /// This method only marks the queue as dirty instead of writing to disk
    /// immediately.  The periodic maintenance loop calls `flush_if_dirty()` to
    /// batch multiple enqueues into a single disk write.
    pub fn enqueue_offline(&self, token: [u8; 32], msg: SealedMessage) -> Result<()> {
        let mut tq = self.token_queues.write();
        let queue = tq.entry(token).or_default();
        if queue.len() >= MAX_QUEUE_PER_PEER {
            return Err(ADAError::Network("Relay token queue full".into()));
        }
        queue.push(msg);
        drop(tq);
        self.dirty.store(true, std::sync::atomic::Ordering::Release);
        Ok(())
    }

    /// Retrieve and remove all messages stored under a delivery token.
    /// Called by the recipient when they come online or on app resume.
    pub fn drain_offline(&self, token: &[u8; 32]) -> Vec<SealedMessage> {
        let drained = self.token_queues.write().remove(token).unwrap_or_default();
        if !drained.is_empty() {
            self.save_to_disk();
        }
        drained
    }

    /// Number of offline messages queued under a token.
    pub fn offline_count(&self, token: &[u8; 32]) -> usize {
        self.token_queues
            .read()
            .get(token)
            .map(|q| q.len())
            .unwrap_or(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enqueue_and_drain_offline() {
        let relay = RelayManager::new();
        let token = [1u8; 32];
        let msg = SealedMessage::new([2u8; 32], b"encrypted data".to_vec());

        relay.enqueue_offline(token, msg).unwrap();
        assert_eq!(relay.offline_count(&token), 1);

        let drained = relay.drain_offline(&token);
        assert_eq!(drained.len(), 1);
        assert_eq!(relay.offline_count(&token), 0);
    }
}
