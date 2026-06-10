//! Sender Keys protocol for group messaging
//!
//! Each group member has a "sender key" — a symmetric ratchet chain key.
//! Messages are encrypted once with the sender key and broadcast.
//! New members receive the current sender key via pairwise E2E channels.
//! When a member leaves, all remaining members rotate their keys.

use crate::crypto::symmetric::{decrypt, encrypt, generate_key, hkdf_derive, EncryptedData};
use crate::error::{ADAError, Result};
use crate::identity::PeerId;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use zeroize::ZeroizeOnDrop;

#[allow(dead_code)]
const SENDER_KEY_INFO: &[u8] = b"ADA-SenderKey-v1";

/// A sender's key state for a specific group
#[derive(Serialize, Deserialize, ZeroizeOnDrop)]
pub struct SenderKey {
    /// Group ID
    #[zeroize(skip)]
    pub group_id: [u8; 16],
    /// Owner of this sender key
    #[zeroize(skip)]
    pub sender: PeerId,
    /// Current chain key
    pub chain_key: [u8; 32],
    /// Current chain iteration (for ordering)
    pub iteration: u32,
    /// Signature key for sender authentication
    pub signing_key: [u8; 32],
    #[zeroize(skip)]
    pub signing_public: [u8; 32],
}

impl SenderKey {
    /// Generate a new sender key
    pub fn generate(group_id: [u8; 16], sender: PeerId) -> Self {
        use ed25519_dalek::SigningKey;
        let chain_key = generate_key();
        let signing = SigningKey::generate(&mut OsRng);
        SenderKey {
            group_id,
            sender,
            chain_key,
            iteration: 0,
            signing_key: signing.to_bytes(),
            signing_public: *signing.verifying_key().as_bytes(),
        }
    }

    /// Advance the ratchet and return message key
    pub fn advance(&mut self) -> [u8; 32] {
        let mut message_key = [0u8; 32];
        let mut new_chain_key = [0u8; 32];

        hkdf_derive(&self.chain_key, None, b"ada-sender-msg", &mut message_key);
        hkdf_derive(
            &self.chain_key,
            None,
            b"ada-sender-chain",
            &mut new_chain_key,
        );

        self.chain_key = new_chain_key;
        self.iteration += 1;
        message_key
    }

    /// Encrypt a group message
    pub fn encrypt_message(&mut self, plaintext: &[u8], aad: &[u8]) -> Result<SenderKeyMessage> {
        let mk = self.advance();
        let ciphertext = encrypt(&mk, plaintext, Some(aad))?;
        let iteration = self.iteration - 1;

        // Sign (iteration || ciphertext nonce || ciphertext body) for authenticity.
        // Including the full ciphertext binds the signature to the exact message bytes,
        // not just the counter and nonce (C-2 audit fix).
        use ed25519_dalek::SigningKey;
        let sk = SigningKey::from_bytes(&self.signing_key);
        use ed25519_dalek::Signer;
        let mut to_sign = iteration.to_le_bytes().to_vec();
        to_sign.extend_from_slice(&ciphertext.nonce);
        to_sign.extend_from_slice(&ciphertext.ciphertext);
        let sig = sk.sign(&to_sign).to_bytes().to_vec();

        Ok(SenderKeyMessage {
            sender: self.sender.clone(),
            group_id: self.group_id,
            iteration,
            ciphertext,
            signing_public: self.signing_public,
            signature: sig,
        })
    }
}

/// Encrypted group message using sender keys
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SenderKeyMessage {
    pub sender: PeerId,
    pub group_id: [u8; 16],
    pub iteration: u32,
    pub ciphertext: EncryptedData,
    pub signing_public: [u8; 32],
    pub signature: Vec<u8>,
}

impl SenderKeyMessage {
    /// Verify sender authenticity
    pub fn verify(&self) -> Result<()> {
        use ed25519_dalek::{Signature, Verifier, VerifyingKey};
        let vk = VerifyingKey::from_bytes(&self.signing_public)
            .map_err(|e| ADAError::Crypto(e.to_string()))?;
        let sig_bytes: [u8; 64] = self
            .signature
            .as_slice()
            .try_into()
            .map_err(|_| ADAError::InvalidSignature)?;
        let sig = Signature::from_bytes(&sig_bytes);
        let mut to_verify = self.iteration.to_le_bytes().to_vec();
        to_verify.extend_from_slice(&self.ciphertext.nonce);
        to_verify.extend_from_slice(&self.ciphertext.ciphertext);
        vk.verify(&to_verify, &sig)
            .map_err(|_| ADAError::InvalidSignature)
    }
}

/// Serializable form of a sender key for distribution to group members
#[derive(Clone, Debug, Serialize, Deserialize, ZeroizeOnDrop)]
pub struct SenderKeyDistribution {
    pub group_id: [u8; 16],
    #[zeroize(skip)]
    pub sender: PeerId,
    pub chain_key: [u8; 32],
    pub iteration: u32,
    pub signing_public: [u8; 32],
}

/// A session holding sender keys for all group members
pub struct SenderKeySession {
    group_id: [u8; 16],
    /// Our own sender key for this group
    our_key: Option<SenderKey>,
    /// Received sender keys from other members
    /// (sender_peer_id -> (iteration, chain_key))
    received_keys: HashMap<PeerId, SenderKeyState>,
}

#[derive(ZeroizeOnDrop)]
struct SenderKeyState {
    chain_key: [u8; 32],
    iteration: u32,
    #[zeroize(skip)]
    signing_public: [u8; 32],
    /// Buffered future message keys
    #[zeroize(skip)]
    buffered: HashMap<u32, [u8; 32]>,
}

impl SenderKeySession {
    pub fn new(group_id: [u8; 16]) -> Self {
        SenderKeySession {
            group_id,
            our_key: None,
            received_keys: HashMap::new(),
        }
    }

    /// Initialize our own sender key
    pub fn init_sender_key(&mut self, our_peer_id: PeerId) -> SenderKeyDistribution {
        let sk = SenderKey::generate(self.group_id, our_peer_id.clone());
        let dist = SenderKeyDistribution {
            group_id: self.group_id,
            sender: our_peer_id,
            chain_key: sk.chain_key,
            iteration: sk.iteration,
            signing_public: sk.signing_public,
        };
        self.our_key = Some(sk);
        dist
    }

    /// Install a received sender key distribution
    pub fn install_sender_key(&mut self, dist: SenderKeyDistribution) {
        self.received_keys.insert(
            dist.sender.clone(),
            SenderKeyState {
                chain_key: dist.chain_key,
                iteration: dist.iteration,
                signing_public: dist.signing_public,
                buffered: HashMap::new(),
            },
        );
    }

    /// Encrypt a message to the group
    pub fn encrypt(&mut self, plaintext: &[u8], aad: &[u8]) -> Result<SenderKeyMessage> {
        let key = self
            .our_key
            .as_mut()
            .ok_or(ADAError::Crypto("No sender key initialized".into()))?;
        key.encrypt_message(plaintext, aad)
    }

    /// Decrypt a message from another group member
    pub fn decrypt(&mut self, msg: &SenderKeyMessage, aad: &[u8]) -> Result<Vec<u8>> {
        msg.verify()?;

        let state = self
            .received_keys
            .get_mut(&msg.sender)
            .ok_or(ADAError::Crypto("No sender key for this peer".into()))?;

        // Check if we have a buffered key for this iteration
        if let Some(mk) = state.buffered.remove(&msg.iteration) {
            return decrypt(&mk, &msg.ciphertext, Some(aad));
        }

        // Advance chain to reach the needed iteration
        if msg.iteration < state.iteration {
            return Err(ADAError::Crypto("Message iteration in the past".into()));
        }

        let max_lookahead = 1000u32;
        if msg.iteration > state.iteration + max_lookahead {
            return Err(ADAError::Crypto("Too many skipped group messages".into()));
        }

        // Advance and buffer intermediate keys
        while state.iteration < msg.iteration {
            let mut mk = [0u8; 32];
            let mut new_ck = [0u8; 32];
            hkdf_derive(&state.chain_key, None, b"ada-sender-msg", &mut mk);
            hkdf_derive(&state.chain_key, None, b"ada-sender-chain", &mut new_ck);
            state.buffered.insert(state.iteration, mk);
            state.chain_key = new_ck;
            state.iteration += 1;
        }

        // Now at the right iteration
        let mut mk = [0u8; 32];
        let mut new_ck = [0u8; 32];
        hkdf_derive(&state.chain_key, None, b"ada-sender-msg", &mut mk);
        hkdf_derive(&state.chain_key, None, b"ada-sender-chain", &mut new_ck);
        state.chain_key = new_ck;
        state.iteration += 1;

        decrypt(&mk, &msg.ciphertext, Some(aad))
    }

    /// Get our current sender key distribution (for new members)
    pub fn our_distribution(&self) -> Option<SenderKeyDistribution> {
        let key = self.our_key.as_ref()?;
        Some(SenderKeyDistribution {
            group_id: self.group_id,
            sender: key.sender.clone(),
            chain_key: key.chain_key,
            iteration: key.iteration,
            signing_public: key.signing_public,
        })
    }

    /// Rotate our sender key (called when a member leaves)
    pub fn rotate_sender_key(&mut self, our_peer_id: PeerId) -> SenderKeyDistribution {
        self.init_sender_key(our_peer_id)
    }

    pub fn has_key_for(&self, peer: &PeerId) -> bool {
        self.received_keys.contains_key(peer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::Identity;

    #[test]
    fn test_sender_key_group_chat() {
        let alice_id = Identity::generate("Alice");
        let bob_id = Identity::generate("Bob");
        let group_id = [1u8; 16];

        let mut alice_session = SenderKeySession::new(group_id);
        let mut bob_session = SenderKeySession::new(group_id);

        // Alice initializes her sender key and shares with Bob
        let alice_dist = alice_session.init_sender_key(alice_id.peer_id.clone());
        bob_session.install_sender_key(alice_dist);

        // Bob initializes his sender key and shares with Alice
        let bob_dist = bob_session.init_sender_key(bob_id.peer_id.clone());
        alice_session.install_sender_key(bob_dist);

        // Alice sends a message
        let plaintext = b"Hello group!";
        let aad = b"group-aad";
        let msg = alice_session.encrypt(plaintext, aad).unwrap();

        // Bob decrypts it
        let decrypted = bob_session.decrypt(&msg, aad).unwrap();
        assert_eq!(decrypted, plaintext);
    }
}
