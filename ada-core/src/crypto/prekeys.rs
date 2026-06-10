//! Pre-Key Manager
//!
//! Manages Bob's pre-key material:
//! - One signed pre-key (SPK), rotated weekly
//! - A pool of one-time pre-keys (OPK), consumed one per X3DH handshake
//!
//! All secret keys are zeroized on drop.

use crate::crypto::x3dh::{sign_spk, PreKeyBundle};
use ed25519_dalek::SigningKey;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::ZeroizeOnDrop;

/// How many seconds a signed pre-key is valid before rotation (7 days)
const SPK_ROTATION_SECS: u64 = 7 * 24 * 3600;
/// Target size of the one-time pre-key pool
const OPK_POOL_TARGET: u32 = 100;

/// A signed pre-key record
#[derive(Serialize, Deserialize, ZeroizeOnDrop)]
pub struct SignedPreKey {
    pub id: u32,
    pub secret: [u8; 32],
    #[zeroize(skip)]
    pub public: [u8; 32],
    #[zeroize(skip)]
    pub signature: Vec<u8>,
    #[zeroize(skip)]
    pub created_at: u64,
}

impl SignedPreKey {
    fn generate(id: u32, signing_key: &SigningKey) -> Self {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret).to_bytes();
        let signature = sign_spk(signing_key, &public);
        SignedPreKey {
            id,
            secret: secret.to_bytes(),
            public,
            signature,
            created_at: now_secs(),
        }
    }

    pub fn is_expired(&self) -> bool {
        now_secs().saturating_sub(self.created_at) >= SPK_ROTATION_SECS
    }
}

/// A one-time pre-key record
#[derive(Serialize, Deserialize, ZeroizeOnDrop)]
pub struct OneTimePreKey {
    pub id: u32,
    pub secret: [u8; 32],
    #[zeroize(skip)]
    pub public: [u8; 32],
}

impl OneTimePreKey {
    fn generate(id: u32) -> Self {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret).to_bytes();
        OneTimePreKey {
            id,
            secret: secret.to_bytes(),
            public,
        }
    }
}

/// Pre-Key Manager
pub struct PreKeyManager {
    /// Counter used to assign unique IDs to pre-keys
    next_id: u32,
    /// Current signed pre-key
    pub spk: SignedPreKey,
    /// Pool of unconsumed one-time pre-keys: id -> OPK
    pub opks: HashMap<u32, OneTimePreKey>,
    /// Identity signing key (used to sign new SPKs).
    /// Zeroized on drop via the manual `impl Drop` below.
    signing_key_bytes: [u8; 32],
}

impl Drop for PreKeyManager {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.signing_key_bytes.zeroize();
    }
}

impl PreKeyManager {
    /// Create a new manager and generate the initial SPK + OPK pool.
    pub fn new(signing_key: &SigningKey) -> Self {
        let signing_key_bytes = signing_key.to_bytes();
        let spk = SignedPreKey::generate(1, signing_key);
        let mut mgr = PreKeyManager {
            next_id: 2,
            spk,
            opks: HashMap::new(),
            signing_key_bytes,
        };
        mgr.refill_opks();
        mgr
    }

    /// Generate a fresh SPK if the current one is expired.
    pub fn rotate_spk_if_needed(&mut self) {
        if self.spk.is_expired() {
            let sk = SigningKey::from_bytes(&self.signing_key_bytes);
            let id = self.next_id;
            self.next_id += 1;
            self.spk = SignedPreKey::generate(id, &sk);
            tracing::info!("Rotated signed pre-key (new ID: {})", id);
        }
    }

    /// Ensure the OPK pool is at the target level.
    /// IDs are drawn from a CSPRNG to prevent observers from predicting which
    /// OPKs will be generated next, making selective-exhaustion attacks harder.
    pub fn refill_opks(&mut self) {
        let needed = OPK_POOL_TARGET.saturating_sub(self.opks.len() as u32);
        for _ in 0..needed {
            // Generate a random 32-bit ID; retry on the rare collision.
            let id = loop {
                let mut buf = [0u8; 4];
                rand::RngCore::fill_bytes(&mut OsRng, &mut buf);
                let candidate = u32::from_le_bytes(buf);
                if !self.opks.contains_key(&candidate) {
                    break candidate;
                }
            };
            self.opks.insert(id, OneTimePreKey::generate(id));
        }
    }

    /// Consume and return a one-time pre-key by ID (called when Bob processes an incoming X3DH).
    pub fn consume_opk(&mut self, id: u32) -> Option<OneTimePreKey> {
        self.opks.remove(&id)
    }

    /// Returns the current signed pre-key as a public X25519 key.
    pub fn spk_public(&self) -> [u8; 32] {
        self.spk.public
    }

    /// Returns the current signed pre-key secret for X3DH receive.
    pub fn spk_secret(&self) -> StaticSecret {
        StaticSecret::from(self.spk.secret)
    }

    /// Build a `PreKeyBundle` for publication to the DHT.
    /// If `include_opk` is true and the pool is non-empty, attach one OPK.
    pub fn build_bundle(&self, ik_public: [u8; 32], include_opk: bool) -> PreKeyBundle {
        let (opk_public, opk_id) = if include_opk {
            if let Some(opk) = self.opks.values().next() {
                (Some(opk.public), Some(opk.id))
            } else {
                (None, None)
            }
        } else {
            (None, None)
        };

        PreKeyBundle {
            ik_public,
            spk_public: self.spk.public,
            spk_signature: self.spk.signature.clone(),
            opk_public,
            opk_id,
        }
    }

    /// How many OPKs remain in the pool.
    pub fn opk_count(&self) -> usize {
        self.opks.len()
    }

    /// Peek at the first available OPK without consuming it.
    /// Returns `(public_bytes, id)` or `None` if the pool is empty.
    pub fn opks_peek_first(&self) -> Option<([u8; 32], u32)> {
        self.opks.values().next().map(|opk| (opk.public, opk.id))
    }

    /// Convenience: build a bundle using the identity's DH public key and
    /// identity-level SPK (deterministically derived from signing seed).
    /// Use this when the identity is available — it ensures `decrypt_from`
    /// on the remote side can use `identity.spk_secret` to verify.
    pub fn signed_prekey_bundle(&self, identity: &crate::identity::Identity) -> PreKeyBundle {
        let ik_public = x25519_dalek::PublicKey::from(&identity.dh_key).to_bytes();
        let (opk_public, opk_id) = if let Some(opk) = self.opks.values().next() {
            (Some(opk.public), Some(opk.id))
        } else {
            (None, None)
        };
        PreKeyBundle {
            ik_public,
            spk_public: identity.spk_public(),
            spk_signature: identity.spk_signature(),
            opk_public,
            opk_id,
        }
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_pool_size() {
        let sk = SigningKey::generate(&mut OsRng);
        let mgr = PreKeyManager::new(&sk);
        assert_eq!(mgr.opk_count(), OPK_POOL_TARGET as usize);
    }

    #[test]
    fn consume_opk() {
        let sk = SigningKey::generate(&mut OsRng);
        let mut mgr = PreKeyManager::new(&sk);
        let id = *mgr.opks.keys().next().unwrap();
        let opk = mgr.consume_opk(id).unwrap();
        assert_eq!(opk.id, id);
        assert_eq!(mgr.opk_count(), (OPK_POOL_TARGET - 1) as usize);
        assert!(mgr.consume_opk(id).is_none());
    }
}
