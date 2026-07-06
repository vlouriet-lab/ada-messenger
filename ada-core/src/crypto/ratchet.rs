//! Double Ratchet Algorithm
//!
//! Implements the Signal Double Ratchet as described in:
//! https://signal.org/docs/specifications/doubleratchet/
//!
//! Each session between two peers has:
//! - A symmetric-key ratchet (sending chain + receiving chain)
//! - A Diffie-Hellman ratchet (rotates chain keys when new DH keys appear)
//!
//! Properties provided:
//! - Forward secrecy: compromising current keys doesn't expose past messages
//! - Break-in recovery: new DH ratchet steps restore security after compromise
//! - Out-of-order message tolerance: skipped message keys are stored up to MAX_SKIP

use crate::crypto::symmetric::{decrypt, encrypt, hkdf_derive, EncryptedData};
use crate::error::{ADAError, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

/// Maximum number of skipped message keys to store per ratchet state.
/// Prevents unbounded memory growth from very out-of-order or maliciously
/// gapped messages.  Lower than the original 1000 to limit memory use on
/// mobile (each slot = 32 B key + map overhead).
const MAX_SKIP: u32 = 256;

/// Global cap on the total number of skipped keys stored across all ratchet
/// steps in a single session.  When the map exceeds this size, the oldest
/// quarter of entries are evicted (LRU-approximated by insertion order via
/// iteration — good enough for mobile where this path is rarely hit).
const MAX_SKIPPED_KEYS_TOTAL: usize = 1024;

/// Root KDF constant
const ROOT_KDF_INFO: &[u8] = b"ADA-RootKDF-v1";
/// Chain KDF constant
const CHAIN_KDF_INFO: &[u8] = b"ADA-ChainKDF-v1";
/// Message KDF constant
const MSG_KDF_INFO: &[u8] = b"ADA-MessageKDF-v1";

/// A header prepended to every ratchet-encrypted message.
/// The receiver uses it to advance the ratchet and derive the message key.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct MessageHeader {
    /// Sender's current DH ratchet public key
    pub dh_public: [u8; 32],
    /// Number of messages sent in the *previous* sending chain (for gap detection)
    pub prev_chain_len: u32,
    /// Message number within the current sending chain (0-indexed)
    pub message_number: u32,
}

/// A complete ratchet-encrypted message (header + ciphertext).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RatchetMessage {
    pub header: MessageHeader,
    pub ciphertext: EncryptedData,
}

/// Persistent ratchet session state between two peers.
/// Must be stored encrypted at rest.
///
/// # Memory safety
/// A manual `Drop` impl zeroizes all sensitive key material, including the
/// `skipped_keys` HashMap values, which `ZeroizeOnDrop` cannot handle
/// automatically (HashMap<K,V> does not implement `Zeroize`).
#[derive(Serialize, Deserialize)]
pub struct RatchetState {
    // --- DH ratchet ---
    /// Our current DH ratchet secret key
    pub dh_self_secret: [u8; 32],
    /// Our current DH ratchet public key (derived from dh_self_secret)
    pub dh_self_public: [u8; 32],
    /// Remote peer's current DH ratchet public key
    pub dh_remote_public: [u8; 32],

    // --- Root key ---
    pub root_key: [u8; 32],

    // --- Sending chain ---
    pub sending_chain_key: [u8; 32],
    /// Index of the next message to send
    pub send_count: u32,

    // --- Receiving chain ---
    pub receiving_chain_key: [u8; 32],
    /// Index of the next expected incoming message
    pub recv_count: u32,

    // --- Previous sending chain length (for gap detection on receiver) ---
    pub prev_send_count: u32,

    // --- Skipped message keys: (dh_public_hex, message_number) -> message_key ---
    // Values are sensitive key material; zeroized in our Drop impl.
    pub skipped_keys: HashMap<(String, u32), [u8; 32]>,

    // --- Whether we have performed the initial DH step ---
    pub initialised: bool,
}

/// Manual `Drop` that explicitly zeroizes all sensitive key bytes including
/// the values stored inside `skipped_keys`.
impl Drop for RatchetState {
    fn drop(&mut self) {
        self.dh_self_secret.zeroize();
        self.root_key.zeroize();
        self.sending_chain_key.zeroize();
        self.receiving_chain_key.zeroize();
        for v in self.skipped_keys.values_mut() {
            v.zeroize();
        }
        self.skipped_keys.clear();
    }
}

/// Manual `Clone` implementation for RatchetState.
///
/// We cannot use `#[derive(Clone)]` here because a derived `Clone` on a type
/// with a non-trivial `Drop` can mislead static analysers into thinking the
/// clone shares memory with the original.  This explicit impl makes the deep
/// copy semantics unambiguous.  The clone gets its own `Drop`, so key bytes
/// are zeroized independently when the clone is dropped.
///
/// Used by `SessionManager::export_sessions()` (СРЕД-21 fix).
impl Clone for RatchetState {
    fn clone(&self) -> Self {
        RatchetState {
            dh_self_secret:      self.dh_self_secret,
            dh_self_public:      self.dh_self_public,
            dh_remote_public:    self.dh_remote_public,
            root_key:            self.root_key,
            sending_chain_key:   self.sending_chain_key,
            send_count:          self.send_count,
            receiving_chain_key: self.receiving_chain_key,
            recv_count:          self.recv_count,
            prev_send_count:     self.prev_send_count,
            skipped_keys:        self.skipped_keys.clone(),
            initialised:         self.initialised,
        }
    }
}

impl RatchetState {
    /// Initialise ratchet as the session *initiator* (Alice).
    ///
    /// `shared_secret` comes from X3DH.
    /// `their_ratchet_public` is Bob's initial ratchet key (= spk_public from his bundle).
    pub fn init_sender(shared_secret: [u8; 32], their_ratchet_public: [u8; 32]) -> Self {
        // Generate our first DH ratchet key pair
        let dh_self_secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let dh_self_public = PublicKey::from(&dh_self_secret).to_bytes();

        // Perform the first DH step
        let dh_out = dh_self_secret.diffie_hellman(&PublicKey::from(their_ratchet_public));

        // Derive root key and sending chain key from the shared secret + DH output
        let (root_key, sending_ck) = kdf_rk(&shared_secret, dh_out.as_bytes());

        RatchetState {
            dh_self_secret: dh_self_secret.to_bytes(),
            dh_self_public,
            dh_remote_public: their_ratchet_public,
            root_key,
            sending_chain_key: sending_ck,
            send_count: 0,
            receiving_chain_key: [0u8; 32],
            recv_count: 0,
            prev_send_count: 0,
            skipped_keys: HashMap::new(),
            initialised: true,
        }
    }

    /// Initialise ratchet as the session *responder* (Bob).
    ///
    /// `shared_secret` comes from X3DH.
    /// `our_ratchet_secret` is Bob's SPK secret (same key Alice used for the DH step).
    pub fn init_receiver(shared_secret: [u8; 32], our_ratchet_secret: [u8; 32]) -> Self {
        let our_public = PublicKey::from(&StaticSecret::from(our_ratchet_secret)).to_bytes();
        RatchetState {
            dh_self_secret: our_ratchet_secret,
            dh_self_public: our_public,
            dh_remote_public: [0u8; 32],
            root_key: shared_secret,
            sending_chain_key: [0u8; 32],
            send_count: 0,
            receiving_chain_key: [0u8; 32],
            recv_count: 0,
            prev_send_count: 0,
            skipped_keys: HashMap::new(),
            initialised: false,
        }
    }

    /// Encrypt `plaintext` and advance the sending chain.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<RatchetMessage> {
        // Advance sending chain → get message key
        let (new_ck, mut mk) = kdf_ck(&self.sending_chain_key);
        self.sending_chain_key = new_ck;

        let header = MessageHeader {
            dh_public: self.dh_self_public,
            prev_chain_len: self.prev_send_count,
            message_number: self.send_count,
        };
        self.send_count += 1;

        // Use the header bytes as AAD for authenticated encryption.
        // Evaluate result before zeroizing so mk is alive during encrypt.
        let aad = header_aad(&header);
        let result = encrypt(&mk, plaintext, Some(&aad));
        mk.zeroize(); // zero regardless of success/failure
        let ciphertext = result?;

        Ok(RatchetMessage { header, ciphertext })
    }

    /// Decrypt a received `RatchetMessage` and advance the receiving ratchet.
    pub fn decrypt(&mut self, msg: &RatchetMessage) -> Result<Vec<u8>> {
        let header = &msg.header;

        // --- Check skipped keys first ---
        let key_id = (hex::encode(header.dh_public), header.message_number);
        if let Some(mut mk) = self.skipped_keys.remove(&key_id) {
            let aad = header_aad(header);
            let result = decrypt(&mk, &msg.ciphertext, Some(&aad));
            mk.zeroize(); // zero before returning
            return result;
        }

        // --- If DH ratchet key has changed, perform a DH ratchet step ---
        if header.dh_public != self.dh_remote_public {
            // Skip messages in the current receiving chain (if any)
            self.skip_message_keys(header.prev_chain_len)?;

            // Perform DH ratchet
            self.dh_ratchet_step(header.dh_public)?;
        }

        // Skip any messages in the current receiving chain before this one
        self.skip_message_keys(header.message_number)?;

        // Advance the receiving chain to get the message key
        let (new_ck, mut mk) = kdf_ck(&self.receiving_chain_key);
        self.receiving_chain_key = new_ck;
        self.recv_count += 1;

        let aad = header_aad(header);
        let result = decrypt(&mk, &msg.ciphertext, Some(&aad));
        mk.zeroize();
        result
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /// Advance the receiving chain, storing skipped message keys until we reach `until`.
    fn skip_message_keys(&mut self, until: u32) -> Result<()> {
        if self.recv_count + MAX_SKIP < until {
            return Err(ADAError::Crypto("Too many skipped messages".into()));
        }
        if self.receiving_chain_key == [0u8; 32] {
            return Ok(());
        }
        while self.recv_count < until {
            let (new_ck, mk) = kdf_ck(&self.receiving_chain_key);
            self.receiving_chain_key = new_ck;
            let k = (hex::encode(self.dh_remote_public), self.recv_count);
            self.skipped_keys.insert(k, mk);
            self.recv_count += 1;
        }
        // Evict oldest entries if we've exceeded the global cap.
        // This prevents a slow DoS via many small messages in rapid succession
        // across many DH ratchet steps.
        if self.skipped_keys.len() > MAX_SKIPPED_KEYS_TOTAL {
            let evict_count = MAX_SKIPPED_KEYS_TOTAL / 4;
            let keys_to_evict: Vec<_> = self
                .skipped_keys
                .keys()
                .take(evict_count)
                .cloned()
                .collect();
            for k in keys_to_evict {
                if let Some(mut v) = self.skipped_keys.remove(&k) {
                    v.zeroize();
                }
            }
        }
        Ok(())
    }

    /// Perform one DH ratchet step upon receiving a new remote ratchet key.
    fn dh_ratchet_step(&mut self, their_new_public: [u8; 32]) -> Result<()> {
        self.prev_send_count = self.send_count;
        self.send_count = 0;
        self.recv_count = 0;
        self.dh_remote_public = their_new_public;

        // Receiving chain: DH(our current secret, their new public)
        let dh_recv = StaticSecret::from(self.dh_self_secret)
            .diffie_hellman(&PublicKey::from(their_new_public));
        let (rk2, recv_ck) = kdf_rk(&self.root_key, dh_recv.as_bytes());

        // Generate new DH ratchet key for sending
        let new_secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let new_public = PublicKey::from(&new_secret).to_bytes();

        // Sending chain: DH(new secret, their new public)
        let dh_send = new_secret.diffie_hellman(&PublicKey::from(their_new_public));
        let (rk3, send_ck) = kdf_rk(&rk2, dh_send.as_bytes());

        self.root_key = rk3;
        self.receiving_chain_key = recv_ck;
        self.sending_chain_key = send_ck;
        self.dh_self_secret = StaticSecret::from(new_secret.to_bytes()).to_bytes();
        self.dh_self_public = new_public;

        Ok(())
    }

    /// Returns the number of entries in the skipped-key cache.
    pub fn skipped_key_count(&self) -> usize {
        self.skipped_keys.len()
    }
}

// ────────────────────────────────────────────────────────────────────────────
// KDF helpers
// ────────────────────────────────────────────────────────────────────────────

/// Root KDF: given root key and DH output, produce (new_root_key, chain_key).
fn kdf_rk(root_key: &[u8; 32], dh_output: &[u8]) -> ([u8; 32], [u8; 32]) {
    let mut out = [0u8; 64];
    hkdf_derive_64(root_key, Some(dh_output), ROOT_KDF_INFO, &mut out);
    let mut rk = [0u8; 32];
    let mut ck = [0u8; 32];
    rk.copy_from_slice(&out[..32]);
    ck.copy_from_slice(&out[32..]);
    (rk, ck)
}

/// Chain KDF: given chain key, produce (new_chain_key, message_key).
///
/// Per Signal Double Ratchet §2.2, the chain key is used as the HKDF **IKM**
/// (input key material), not as the salt.  Previously chain_key was passed as
/// salt with ikm=[], which is not wrong cryptographically but diverges from the
/// spec and confuses external reviewers.  Fixed: chain_key → IKM, salt=&[] (КРИТ-2).
fn kdf_ck(chain_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut new_ck = [0u8; 32];
    let mut mk = [0u8; 32];
    // Pass chain_key as IKM (not salt) to match the Signal spec.
    hkdf_derive(&[], Some(chain_key.as_slice()), CHAIN_KDF_INFO, &mut new_ck);
    hkdf_derive(&[], Some(chain_key.as_slice()), MSG_KDF_INFO, &mut mk);
    (new_ck, mk)
}

fn hkdf_derive_64(salt: &[u8], ikm: Option<&[u8]>, info: &[u8], out: &mut [u8; 64]) {
    use hkdf::Hkdf;
    use sha2::Sha256;
    let h = Hkdf::<Sha256>::new(Some(salt), ikm.unwrap_or(&[]));
    // 64 ≤ 8160 — expand with a 64-byte fixed-size buffer is infallible.
    let _ = h.expand(info, out.as_mut_slice());
}

/// Serialise a `MessageHeader` into bytes for use as AEAD additional data.
fn header_aad(h: &MessageHeader) -> Vec<u8> {
    let mut b = Vec::with_capacity(32 + 8);
    b.extend_from_slice(&h.dh_public);
    b.extend_from_slice(&h.prev_chain_len.to_le_bytes());
    b.extend_from_slice(&h.message_number.to_le_bytes());
    b
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_shared_secret() -> [u8; 32] {
        [42u8; 32]
    }

    #[test]
    fn encrypt_decrypt_basic() {
        let ss = make_shared_secret();
        let bob_secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let bob_public = PublicKey::from(&bob_secret).to_bytes();

        let mut alice = RatchetState::init_sender(ss, bob_public);
        let mut bob = RatchetState::init_receiver(ss, bob_secret.to_bytes());

        // Alice sends message 0
        let plaintext = b"Hello Bob!";
        let msg = alice.encrypt(plaintext).unwrap();

        // Bob triggers his DH ratchet upon receiving Alice's DH key
        let decrypted = bob.decrypt(&msg).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn multiple_messages_in_order() {
        let ss = make_shared_secret();
        let bob_secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let bob_public = PublicKey::from(&bob_secret).to_bytes();

        let mut alice = RatchetState::init_sender(ss, bob_public);
        let mut bob = RatchetState::init_receiver(ss, bob_secret.to_bytes());

        for i in 0u8..10 {
            let pt = vec![i; 16];
            let msg = alice.encrypt(&pt).unwrap();
            let dec = bob.decrypt(&msg).unwrap();
            assert_eq!(dec, pt);
        }
    }

    #[test]
    fn bidirectional() {
        let ss = make_shared_secret();
        let bob_secret = StaticSecret::random_from_rng(rand::rngs::OsRng);
        let bob_public = PublicKey::from(&bob_secret).to_bytes();

        let mut alice = RatchetState::init_sender(ss, bob_public);
        let mut bob = RatchetState::init_receiver(ss, bob_secret.to_bytes());

        // Alice → Bob
        let enc1 = alice.encrypt(b"Hello Bob!").unwrap();
        assert_eq!(bob.decrypt(&enc1).unwrap(), b"Hello Bob!");

        // Bob → Alice (DH ratchet step)
        let enc2 = bob.encrypt(b"Hello Alice!").unwrap();
        assert_eq!(alice.decrypt(&enc2).unwrap(), b"Hello Alice!");

        // Alice → Bob (another DH ratchet step)
        let enc3 = alice.encrypt(b"How are you?").unwrap();
        assert_eq!(bob.decrypt(&enc3).unwrap(), b"How are you?");
    }
}
