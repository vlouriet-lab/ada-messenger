//! Session Manager — per-peer Double Ratchet sessions
//!
//! Each pair of peers shares exactly one `RatchetState`, which is keyed
//! by the remote `PeerId`. Sessions are initialised via X3DH on the first
//! message and persisted to storage between restarts.

use crate::crypto::{
    prekeys::PreKeyManager,
    ratchet::{RatchetMessage, RatchetState},
    x3dh::{x3dh_receive, x3dh_send, PreKeyBundle},
};
use crate::error::{ADAError, Result};
use crate::identity::{Identity, PeerId};
use crate::storage::IdentityStore;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use x25519_dalek::StaticSecret;

/// Maximum number of X3DH session initiations accepted from a single
/// peer within `X3DH_RATE_WINDOW`. Prevents OPK exhaustion attacks while
/// allowing both sides to complete mutual first-contact + retries.
const MAX_X3DH_PER_WINDOW: u32 = 10;
/// Rolling window for the X3DH rate limiter (5 minutes).
const X3DH_RATE_WINDOW: Duration = Duration::from_secs(300);

/// Top-level wire envelope sent over iroh QUIC streams.
///
/// Before the iroh-only migration, DMs and group messages travelled on
/// separate gossipsub topics so the receiver could disambiguate by topic
/// name.  Now everything goes through the same QUIC uni-stream, so we
/// need a tagged enum to tell the receiver which payload it is reading.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub enum WireEnvelope {
    /// Direct message (X3DH + Double Ratchet encrypted).
    Dm(EncryptedWire),
    /// Group message (Sender Key encrypted fan-out).
    Group(crate::group::sender_keys::SenderKeyMessage),
    /// Encapsulated WebRTC UDP Proxy traffic.
    WebRtcProxy(Vec<u8>),
}

/// A `WireMessage` enriched with the ratchet header bytes so the receiver
/// can advance its ratchet state.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct EncryptedWire {
    pub sender: PeerId,
    pub recipient: PeerId,
    /// Serialised `RatchetMessage` (header + AES-GCM ciphertext)
    pub ratchet_message: Vec<u8>,
    /// Alice's ephemeral public key — only present on the very first message
    /// that initialises the X3DH handshake
    pub x3dh_ephemeral: Option<[u8; 32]>,
    /// Alice's identity public IK key — needed by Bob to complete X3DH
    pub x3dh_ik_public: Option<[u8; 32]>,
    /// OPK ID consumed from Bob's bundle (so Bob can delete it)
    pub opk_id_used: Option<u32>,
    /// The IK_B that the sender targeted in their X3DH handshake.
    ///
    /// For incognito (v3) contacts this is the per-contact ephemeral X25519 public
    /// key, NOT the long-term identity IK.  The receiver uses this field to look up
    /// the matching ephemeral secret via `ephemeral_aliases` so decryption produces
    /// the same shared secret as the sender computed.
    ///
    /// `None` for regular (non-incognito) sessions → receiver uses `identity.dh_key`.
    ///
    /// NOTE: `skip_serializing_if` must NOT be used here because bincode serialises
    /// by field position (not name).  Skipping a field shifts all subsequent fields
    /// and causes UnexpectedEof on the receiver side.
    pub target_ik_public: Option<[u8; 32]>,
}

/// Maximum size of a serialised `RatchetMessage` from the network.
/// 512 KiB is generous for any real message; larger values are a DoS vector
/// (a remote peer could force 4 MB allocations per decryption attempt).
const MAX_RATCHET_MSG_BYTES: usize = 512 * 1024;

// ── Metadata protection: message-size bucketing ───────────────────────────
//
// Pad every plaintext to a fixed-size bucket before encryption so that a
// passive observer cannot infer message length (and therefore likely content
// type) from ciphertext size.  Buckets are powers-of-two up to 64 KiB, then
// multiples of 64 KiB above that.
//
// Wire format of padded payload:
//   bytes [0..4]  — LE u32: actual plaintext length
//   bytes [4..4+len] — actual plaintext
//   bytes [4+len..bucket] — zero padding

const BUCKETS: &[usize] = &[256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536];

fn bucket_size(n: usize) -> usize {
    for &b in BUCKETS {
        if n <= b {
            return b;
        }
    }
    (n + 65535) / 65536 * 65536
}

fn pad_to_bucket(plaintext: &[u8]) -> Vec<u8> {
    let real_len = plaintext.len();
    let header = (real_len as u32).to_le_bytes();
    let content_size = 4 + real_len;
    let total = bucket_size(content_size);
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&header);
    out.extend_from_slice(plaintext);
    out.resize(total, 0u8);
    out
}

fn unpad_from_bucket(data: &[u8]) -> crate::error::Result<Vec<u8>> {
    if data.len() < 4 {
        return Err(ADAError::Message("bucket unpad: payload too short".into()));
    }
    let real_len = u32::from_le_bytes(data[..4].try_into().unwrap()) as usize;
    if data.len() < 4 + real_len {
        return Err(ADAError::Message(format!(
            "bucket unpad: declared len {} exceeds payload {}",
            real_len,
            data.len()
        )));
    }
    Ok(data[4..4 + real_len].to_vec())
}

/// Per-peer session state
struct PeerSession {
    ratchet: RatchetState,
    /// Did Alice already receive Bob's first response (confirming mutual ratchet)?
    #[allow(dead_code)]
    handshake_complete: bool,
}

/// Manages all pairwise encrypted sessions
pub struct SessionManager {
    identity: Arc<Identity>,
    /// PeerId (base64) -> session
    sessions: RwLock<HashMap<String, PeerSession>>,
    /// Optional persistent storage — when set, ratchet states are saved after
    /// every encrypt/decrypt so they survive process restarts.
    storage: Option<Arc<IdentityStore>>,
    /// Rate-limit tracking for incoming X3DH initiations.
    /// Maps `PeerId.to_base64()` to `(count, window_start)`.
    /// Resets `count` whenever `window_start` is older than `X3DH_RATE_WINDOW`.
    x3dh_rate: RwLock<HashMap<String, (u32, Instant)>>,
}

impl SessionManager {
    /// Create a session manager without persistence (used in tests).
    pub fn new(identity: Arc<Identity>) -> Self {
        SessionManager {
            identity,
            sessions: RwLock::new(HashMap::new()),
            storage: None,
            x3dh_rate: RwLock::new(HashMap::new()),
        }
    }

    /// Create a session manager backed by persistent storage.
    /// Ratchet states are written to `store` after every encrypt/decrypt.
    pub fn new_with_storage(identity: Arc<Identity>, store: Arc<IdentityStore>) -> Self {
        SessionManager {
            identity,
            sessions: RwLock::new(HashMap::new()),
            storage: Some(store),
            x3dh_rate: RwLock::new(HashMap::new()),
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sending
    // ──────────────────────────────────────────────────────────────────────

    /// Encrypt `plaintext` for `peer`.
    ///
    /// If no session exists yet, performs an X3DH key agreement using
    /// `bundle` (Bob's public pre-key bundle from the DHT) to seed the ratchet.
    pub fn encrypt_to(
        &self,
        peer: &PeerId,
        plaintext: &[u8],
        bundle: Option<&PreKeyBundle>,
        _aad: &[u8],
    ) -> Result<EncryptedWire> {
        let key = peer.to_base64();

        // Take a single write lock for the entire operation.
        // This eliminates the read → drop → write pattern that caused a race:
        // two concurrent callers could both see `contains_key == false`,
        // both run X3DH, and insert two ratchet states — the second overwriting the first.
        let mut sessions = self.sessions.write();

        if !sessions.contains_key(&key) {
            // No existing session — perform X3DH to create one.
            let b = bundle
                .ok_or_else(|| ADAError::Message("No session and no bundle for X3DH".into()))?;
            let alice_ik = StaticSecret::from(self.identity.dh_key.to_bytes());
            let result = x3dh_send(&alice_ik, b, &peer.0)?;
            let ratchet = RatchetState::init_sender(result.shared_secret, b.spk_public);

            sessions.insert(
                key.clone(),
                PeerSession {
                    ratchet,
                    handshake_complete: false,
                },
            );

            let session = sessions
                .get_mut(&key)
                .ok_or_else(|| ADAError::Message("session disappeared after insert".into()))?;
            let padded = pad_to_bucket(plaintext);
            let ratchet_msg = session.ratchet.encrypt(&padded)?;
            let ratchet_bytes =
                bincode::serialize(&ratchet_msg).map_err(ADAError::Serialization)?;

            // Persist ratchet state after successful encrypt.
            if let Some(store) = &self.storage {
                if let Err(e) = store.save_ratchet_state(peer, &session.ratchet) {
                    tracing::warn!("[session] failed to persist ratchet for {}: {}", peer, e);
                }
            }

            // C-2 fix: include the IK_B that was used in the X3DH handshake so
            // the receiver can route incognito sessions to the right ephemeral key.
            let target_ik_public = Some(b.ik_public);

            return Ok(EncryptedWire {
                sender: self.identity.peer_id.clone(),
                recipient: peer.clone(),
                ratchet_message: ratchet_bytes,
                x3dh_ephemeral: Some(result.ephemeral_public),
                x3dh_ik_public: Some(x25519_dalek::PublicKey::from(&alice_ik).to_bytes()),
                opk_id_used: result.opk_id_used,
                target_ik_public,
            });
        }

        let session = sessions
            .get_mut(&key)
            .ok_or_else(|| ADAError::Message("Session disappeared".into()))?;

        let padded = pad_to_bucket(plaintext);
        let ratchet_msg = session.ratchet.encrypt(&padded)?;
        let ratchet_bytes = bincode::serialize(&ratchet_msg).map_err(ADAError::Serialization)?;

        // Persist ratchet state after successful encrypt.
        if let Some(store) = &self.storage {
            if let Err(e) = store.save_ratchet_state(peer, &session.ratchet) {
                tracing::warn!("[session] failed to persist ratchet for {}: {}", peer, e);
            }
        }

        Ok(EncryptedWire {
            sender: self.identity.peer_id.clone(),
            recipient: peer.clone(),
            ratchet_message: ratchet_bytes,
            x3dh_ephemeral: None,
            x3dh_ik_public: None,
            opk_id_used: None,
            target_ik_public: None,
        })
    }

    // ──────────────────────────────────────────────────────────────────────
    // Receiving
    // ──────────────────────────────────────────────────────────────────────

    /// Decrypt a received `EncryptedWire`.
    ///
    /// If this is an initial X3DH message (x3dh_ephemeral is set), Bob bootstraps
    /// a new `RatchetState` using his identity SPK (deterministically derived).
    /// `prekeys` is still needed to consume any one-time pre-key (OPK).
    ///
    /// `ik_override` — when `Some`, replaces `identity.dh_key` as the IK_B secret
    /// used in X3DH receive.  The caller (`ADACore::receive_encrypted_wire`) checks
    /// `wire.target_ik_public` against `ephemeral_aliases` and passes the matching
    /// ephemeral secret so incognito sessions produce the correct shared secret.
    pub fn decrypt_from(
        &self,
        wire: &EncryptedWire,
        prekeys: &mut PreKeyManager,
        ik_override: Option<&StaticSecret>,
    ) -> Result<Vec<u8>> {
        let key = wire.sender.to_base64();

        // --- Bootstrap session if this is the first X3DH message ---
        if let Some(ek_pub) = wire.x3dh_ephemeral {
            // ── B-13: Rate-limit X3DH initiations to prevent OPK exhaustion ──
            {
                let mut rate = self.x3dh_rate.write();
                let now = Instant::now();
                let entry = rate.entry(key.clone()).or_insert((0u32, now));
                // Reset counter if the window has expired.
                if now.duration_since(entry.1) >= X3DH_RATE_WINDOW {
                    *entry = (0, now);
                }
                entry.0 = entry.0.saturating_add(1);
                if entry.0 > MAX_X3DH_PER_WINDOW {
                    tracing::warn!(
                        "[session] X3DH rate limit exceeded for peer {} ({} initiations in window)",
                        wire.sender,
                        entry.0,
                    );
                    return Err(ADAError::Message(format!(
                        "X3DH rate limit exceeded: {} fresh sessions in {} s (max {})",
                        entry.0,
                        X3DH_RATE_WINDOW.as_secs(),
                        MAX_X3DH_PER_WINDOW,
                    )));
                }
            }
            let ik_pub = wire
                .x3dh_ik_public
                .ok_or_else(|| ADAError::Message("X3DH IK missing".into()))?;

            // Consume OPK if indicated
            let opk_secret = if let Some(opk_id) = wire.opk_id_used {
                prekeys
                    .consume_opk(opk_id)
                    .map(|opk| StaticSecret::from(opk.secret))
            } else {
                None
            };

            // C-2 fix: use the caller-supplied ephemeral key if this message was
            // addressed to one of our per-contact incognito IKs.
            let bob_ik: StaticSecret = match ik_override {
                Some(eph) => StaticSecret::from(eph.to_bytes()),
                None => StaticSecret::from(self.identity.dh_key.to_bytes()),
            };
            let bob_spk = StaticSecret::from(*self.identity.spk_secret_bytes());

            let shared_secret =
                x3dh_receive(&bob_ik, &bob_spk, opk_secret.as_ref(), ik_pub, ek_pub);

            let ratchet =
                RatchetState::init_receiver(shared_secret, *self.identity.spk_secret_bytes());

            let mut sessions = self.sessions.write();
            sessions.insert(
                key.clone(),
                PeerSession {
                    ratchet,
                    handshake_complete: false,
                },
            );
            // Decrypt in the same write lock to avoid a second write() deadlock
            let session = sessions
                .get_mut(&key)
                .ok_or_else(|| ADAError::Message("Session insert failed".into()))?;
            if wire.ratchet_message.len() > MAX_RATCHET_MSG_BYTES {
                return Err(ADAError::Message(
                    "Ratchet message exceeds size limit".into(),
                ));
            }
            let ratchet_msg: RatchetMessage =
                bincode::deserialize(&wire.ratchet_message).map_err(ADAError::Serialization)?;
            let plaintext = session
                .ratchet
                .decrypt(&ratchet_msg)
                .and_then(|b| unpad_from_bucket(&b))?;
            // Persist ratchet state after successful X3DH-init decrypt.
            if let Some(store) = &self.storage {
                if let Err(e) = store.save_ratchet_state(&wire.sender, &session.ratchet) {
                    tracing::warn!(
                        "[session] failed to persist ratchet for {}: {}",
                        wire.sender,
                        e
                    );
                }
            }
            return Ok(plaintext);
        }

        let mut sessions = self.sessions.write();
        let session = sessions
            .get_mut(&key)
            .ok_or_else(|| ADAError::Message("No session for peer".into()))?;

        if wire.ratchet_message.len() > MAX_RATCHET_MSG_BYTES {
            return Err(ADAError::Message(
                "Ratchet message exceeds size limit".into(),
            ));
        }
        let ratchet_msg: RatchetMessage =
            bincode::deserialize(&wire.ratchet_message).map_err(ADAError::Serialization)?;

        let plaintext = session
            .ratchet
            .decrypt(&ratchet_msg)
            .and_then(|b| unpad_from_bucket(&b))?;
        // Persist ratchet state after successful decrypt.
        if let Some(store) = &self.storage {
            if let Err(e) = store.save_ratchet_state(&wire.sender, &session.ratchet) {
                tracing::warn!(
                    "[session] failed to persist ratchet for {}: {}",
                    wire.sender,
                    e
                );
            }
        }
        Ok(plaintext)
    }

    /// Load a persisted ratchet state for a peer (called at startup).
    pub fn load_session(&self, peer: &PeerId, state: RatchetState) {
        let key = peer.to_base64();
        self.sessions.write().insert(
            key,
            PeerSession {
                ratchet: state,
                handshake_complete: true,
            },
        );
    }

    /// Export all ratchet states for persistence.
    pub fn export_sessions(&self) -> Vec<(PeerId, RatchetState)> {
        // We need to consume the sessions; instead, we re-serialise from stored state.
        // For now return an empty list — full persistence is handled via storage layer.
        vec![]
    }

    /// Whether a live session exists for the given peer.
    pub fn has_session(&self, peer: &PeerId) -> bool {
        self.sessions.read().contains_key(&peer.to_base64())
    }

    /// Drop the in-memory ratchet session for a peer (called when re-adding a
    /// contact so the stale session is never used to decrypt the new peer's messages).
    pub fn clear_session(&self, peer: &PeerId) {
        self.sessions.write().remove(&peer.to_base64());
    }
}
