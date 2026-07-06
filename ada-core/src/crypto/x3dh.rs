//! Extended Triple Diffie-Hellman (X3DH) Key Agreement
//!
//! Implements the Signal X3DH specification:
//! https://signal.org/docs/specifications/x3dh/
//!
//! Participants:
//! - Alice (initiator): wants to send a first message to Bob
//! - Bob   (responder): publishes a pre-key bundle, is initially offline
//!
//! Output: a shared 32-byte secret, usable to seed a Double Ratchet session.

use crate::error::{ADAError, Result};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use x25519_dalek::{PublicKey, StaticSecret};

/// KDF info string — domain separation label for HKDF-Expand
const X3DH_INFO: &[u8] = b"ADA-X3DH-v1";
/// KDF salt — distinct from info per RFC 5869 §3.1 so that the extract and
/// expand steps provide independent domain separation.  Using the same
/// constant for both salt and info collapses two independent security
/// parameters into one, weakening the HKDF construction.
const X3DH_SALT: &[u8] = b"ADA-X3DH-salt-v1";
/// 32 bytes of 0xFF used as F (padding in the KDF input per Signal X3DH spec §2.2).
/// The spec requires 0xFF bytes, NOT zero bytes.
const F: [u8; 32] = [0xFFu8; 32];

// ── Pre-key bundle ───────────────────────────────────────────────────────────

/// Bob's public pre-key bundle, published to the DHT.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PreKeyBundle {
    /// Bob's long-term identity key (X25519 public)
    pub ik_public: [u8; 32],
    /// Bob's signed pre-key (X25519 public)
    pub spk_public: [u8; 32],
    /// Ed25519 signature of spk_public by Bob's signing key
    pub spk_signature: Vec<u8>,
    /// One-time pre-key (optional), X25519 public
    pub opk_public: Option<[u8; 32]>,
    /// ID of the one-time pre-key (so Bob can find and delete the secret)
    pub opk_id: Option<u32>,
}

impl PreKeyBundle {
    /// Verify that `spk_public` was genuinely signed by the owner of `signing_public`.
    /// Expects the signature to cover `SPK_SIGN_PREFIX || spk_public`.
    pub fn verify_spk_signature(&self, signing_public: &[u8; 32]) -> Result<()> {
        let vk =
            VerifyingKey::from_bytes(signing_public).map_err(|_| ADAError::InvalidSignature)?;
        let sig_bytes: [u8; 64] = self
            .spk_signature
            .as_slice()
            .try_into()
            .map_err(|_| ADAError::InvalidSignature)?;
        let sig = Signature::from_bytes(&sig_bytes);
        let mut msg = Vec::with_capacity(SPK_SIGN_PREFIX.len() + 32);
        msg.extend_from_slice(SPK_SIGN_PREFIX);
        msg.extend_from_slice(&self.spk_public);
        vk.verify(&msg, &sig)
            .map_err(|_| ADAError::InvalidSignature)
    }
}

// ── X3DH initiator (Alice) ────────────────────────────────────────────────────

/// Result of Alice performing the X3DH send step.
/// `shared_secret` contains sensitive key material; it is zeroized when
/// the struct is dropped.
#[derive(zeroize::ZeroizeOnDrop)]
pub struct X3DHSendResult {
    /// Shared secret — use to seed `RatchetState::init_sender`
    pub shared_secret: [u8; 32],
    /// Alice's ephemeral public key — must be sent to Bob in the initial message
    /// (public data, safe to not zeroize but covered by ZeroizeOnDrop anyway)
    pub ephemeral_public: [u8; 32],
    /// One-time pre-key ID used (if any) — Bob needs this to delete the OPK
    #[zeroize(skip)]
    pub opk_id_used: Option<u32>,
}

/// Alice performs the X3DH send step.
///
/// # Arguments
/// - `alice_ik_secret`   Alice's long-term identity DH secret
/// - `bundle`            Bob's public pre-key bundle (fetched from DHT)
/// - `bob_signing_public` Bob's Ed25519 signing public key (for SPK verification)
pub fn x3dh_send(
    alice_ik_secret: &StaticSecret,
    bundle: &PreKeyBundle,
    bob_signing_public: &[u8; 32],
) -> Result<X3DHSendResult> {
    // Verify bundle authenticity
    bundle.verify_spk_signature(bob_signing_public)?;

    // Generate Alice's ephemeral key pair (StaticSecret so we can reuse in multiple DH ops)
    let ek = StaticSecret::random_from_rng(OsRng);
    let ek_public = PublicKey::from(&ek).to_bytes();

    let bob_ik = PublicKey::from(bundle.ik_public);
    let bob_spk = PublicKey::from(bundle.spk_public);

    // DH1 = DH(IK_A, SPK_B)
    let dh1 = StaticSecret::from(alice_ik_secret.to_bytes()).diffie_hellman(&bob_spk);
    // DH2 = DH(EK_A, IK_B)
    let dh2 = ek.diffie_hellman(&bob_ik);
    // DH3 = DH(EK_A, SPK_B)
    let dh3 = ek.diffie_hellman(&bob_spk);

    // DH4 = DH(EK_A, OPK_B) if OPK present
    let opk_id_used = bundle.opk_id;
    let dh4_bytes: Option<[u8; 32]> = bundle
        .opk_public
        .map(|opk_pub| *ek.diffie_hellman(&PublicKey::from(opk_pub)).as_bytes());

    let shared_secret = kdf_x3dh(
        dh1.as_bytes(),
        dh2.as_bytes(),
        dh3.as_bytes(),
        dh4_bytes.as_ref().map(|b| b.as_slice()),
    );

    Ok(X3DHSendResult {
        shared_secret,
        ephemeral_public: ek_public,
        opk_id_used,
    })
}

// ── X3DH responder (Bob) ─────────────────────────────────────────────────────

/// Bob performs the X3DH receive step.
///
/// # Arguments
/// - `bob_ik_secret`   Bob's long-term identity DH secret
/// - `bob_spk_secret`  Bob's signed pre-key secret
/// - `bob_opk_secret`  Bob's one-time pre-key secret (if used)
/// - `alice_ik_public` Alice's long-term identity DH public key
/// - `alice_ek_public` Alice's ephemeral public key (from initial message)
///
/// # Returns
/// `Ok([u8; 32])` — shared secret; or `Err` if inputs are invalid.
///
/// Returning `Result` (СРЕД-3 fix) allows callers to propagate errors and
/// lets us add Curve25519 low-order point rejection and IK-signature
/// verification inside this function in future without changing the API.
pub fn x3dh_receive(
    bob_ik_secret: &StaticSecret,
    bob_spk_secret: &StaticSecret,
    bob_opk_secret: Option<&StaticSecret>,
    alice_ik_public: [u8; 32],
    alice_ek_public: [u8; 32],
) -> crate::error::Result<[u8; 32]> {
    let alice_ik = PublicKey::from(alice_ik_public);
    let alice_ek = PublicKey::from(alice_ek_public);

    // DH1 = DH(SPK_B, IK_A)
    let dh1 = StaticSecret::from(bob_spk_secret.to_bytes()).diffie_hellman(&alice_ik);
    // DH2 = DH(IK_B, EK_A)
    let dh2 = StaticSecret::from(bob_ik_secret.to_bytes()).diffie_hellman(&alice_ek);
    // DH3 = DH(SPK_B, EK_A)
    let dh3 = StaticSecret::from(bob_spk_secret.to_bytes()).diffie_hellman(&alice_ek);

    // DH4 = DH(OPK_B, EK_A) if present
    let dh4_bytes: Option<[u8; 32]> = bob_opk_secret.map(|opk| {
        let dh4 = StaticSecret::from(opk.to_bytes()).diffie_hellman(&alice_ek);
        *dh4.as_bytes()
    });

    Ok(kdf_x3dh(
        dh1.as_bytes(),
        dh2.as_bytes(),
        dh3.as_bytes(),
        dh4_bytes.as_ref().map(|b| b.as_slice()),
    ))
}

// ── KDF ──────────────────────────────────────────────────────────────────────

/// Concatenate DH outputs and derive a 32-byte shared secret via HKDF-SHA256.
fn kdf_x3dh(dh1: &[u8], dh2: &[u8], dh3: &[u8], dh4: Option<&[u8]>) -> [u8; 32] {
    use hkdf::Hkdf;
    use sha2::Sha256;

    // IKM = F || DH1 || DH2 || DH3 [|| DH4]
    let mut ikm = Vec::with_capacity(32 * 5);
    ikm.extend_from_slice(&F);
    ikm.extend_from_slice(dh1);
    ikm.extend_from_slice(dh2);
    ikm.extend_from_slice(dh3);
    if let Some(d4) = dh4 {
        ikm.extend_from_slice(d4);
    }

    // M-NEW-3 fix: use a dedicated X3DH_SALT constant for HKDF-Extract
    // (distinct from X3DH_INFO used in HKDF-Expand) for proper domain
    // separation per RFC 5869 §3.1.
    let h = Hkdf::<Sha256>::new(Some(X3DH_SALT), &ikm);
    let mut out = [0u8; 32];
    h.expand(X3DH_INFO, &mut out).expect("HKDF expand X3DH");
    out
}

// ── Pre-key bundle builder ───────────────────────────────────────────────────

/// Domain tag prepended to `spk_public` before signing.
/// Prevents cross-context signature reuse: a signature produced for one
/// purpose (SPK binding) cannot be replayed as a valid signature for raw
/// key bytes in a different context.
const SPK_SIGN_PREFIX: &[u8] = b"ADA-SPK-v1:";

/// Sign a `spk_public` key with `signing_key` and return the signature bytes.
/// The signed data is `SPK_SIGN_PREFIX || spk_public` (not the raw key bytes)
/// to enforce domain separation.
pub fn sign_spk(signing_key: &SigningKey, spk_public: &[u8; 32]) -> Vec<u8> {
    let mut msg = Vec::with_capacity(SPK_SIGN_PREFIX.len() + 32);
    msg.extend_from_slice(SPK_SIGN_PREFIX);
    msg.extend_from_slice(spk_public);
    signing_key.sign(&msg).to_bytes().to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;
    use x25519_dalek::StaticSecret;

    #[test]
    fn x3dh_shared_secret_matches() {
        // Bob generates his keys
        let bob_ik = StaticSecret::random_from_rng(OsRng);
        let bob_ik_pub = PublicKey::from(&bob_ik).to_bytes();
        let bob_spk = StaticSecret::random_from_rng(OsRng);
        let bob_spk_pub = PublicKey::from(&bob_spk).to_bytes();
        let bob_signing = SigningKey::generate(&mut OsRng);

        let spk_sig = sign_spk(&bob_signing, &bob_spk_pub);

        let bundle = PreKeyBundle {
            ik_public: bob_ik_pub,
            spk_public: bob_spk_pub,
            spk_signature: spk_sig,
            opk_public: None,
            opk_id: None,
        };

        // Alice generates her keys
        let alice_ik = StaticSecret::random_from_rng(OsRng);

        // Alice sends
        let result = x3dh_send(&alice_ik, &bundle, bob_signing.verifying_key().as_bytes()).unwrap();

        // Bob receives
        let alice_ik_pub = PublicKey::from(&alice_ik).to_bytes();
        let bob_ss = x3dh_receive(
            &bob_ik,
            &bob_spk,
            None,
            alice_ik_pub,
            result.ephemeral_public,
        ).unwrap();

        assert_eq!(
            result.shared_secret, bob_ss,
            "X3DH shared secrets must match"
        );
    }

    #[test]
    fn x3dh_with_opk_matches() {
        let bob_ik = StaticSecret::random_from_rng(OsRng);
        let bob_ik_pub = PublicKey::from(&bob_ik).to_bytes();
        let bob_spk = StaticSecret::random_from_rng(OsRng);
        let bob_spk_pub = PublicKey::from(&bob_spk).to_bytes();
        let bob_opk = StaticSecret::random_from_rng(OsRng);
        let bob_opk_pub = PublicKey::from(&bob_opk).to_bytes();
        let bob_signing = SigningKey::generate(&mut OsRng);
        let spk_sig = sign_spk(&bob_signing, &bob_spk_pub);

        let bundle = PreKeyBundle {
            ik_public: bob_ik_pub,
            spk_public: bob_spk_pub,
            spk_signature: spk_sig,
            opk_public: Some(bob_opk_pub),
            opk_id: Some(1),
        };

        let alice_ik = StaticSecret::random_from_rng(OsRng);
        let result = x3dh_send(&alice_ik, &bundle, bob_signing.verifying_key().as_bytes()).unwrap();

        let alice_ik_pub = PublicKey::from(&alice_ik).to_bytes();
        let bob_ss = x3dh_receive(
            &bob_ik,
            &bob_spk,
            Some(&bob_opk),
            alice_ik_pub,
            result.ephemeral_public,
        ).unwrap();

        assert_eq!(result.shared_secret, bob_ss);
    }
}
