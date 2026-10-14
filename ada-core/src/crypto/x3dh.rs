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

use crate::crypto::pq::{MlKem768, MlKem768Ciphertext, MlKem768PublicKey, MlKem768SecretKey};
use crate::error::{ADAError, Result};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use x25519_dalek::{PublicKey, StaticSecret};

/// KDF info string — domain separation label for HKDF-Expand (classical X3DH)
const X3DH_INFO: &[u8] = b"ADA-X3DH-v1";
/// KDF salt for classical X3DH per RFC 5869 §3.1
const X3DH_SALT: &[u8] = b"ADA-X3DH-salt-v1";

/// KDF info string for hybrid Post-Quantum Extended Triple Diffie-Hellman (PQXDH)
const PQXDH_INFO: &[u8] = b"ADA-PQXDH-v1";
/// KDF salt for hybrid PQXDH
const PQXDH_SALT: &[u8] = b"ADA-PQXDH-salt-v1";

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
    /// Optional post-quantum ML-KEM-768 signed pre-key (public key bytes, 1184 bytes).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pq_spk_public: Option<Vec<u8>>,
    /// Ed25519 signature of pq_spk_public by Bob's signing key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pq_spk_signature: Option<Vec<u8>>,
}

impl PreKeyBundle {
    /// Verify that `spk_public` (and `pq_spk_public` if present) was genuinely signed
    /// by the owner of `signing_public`.
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
            .map_err(|_| ADAError::InvalidSignature)?;

        if let Some(pq_spk) = &self.pq_spk_public {
            let pq_sig = self
                .pq_spk_signature
                .as_deref()
                .ok_or(ADAError::InvalidSignature)?;
            verify_pq_spk_signature(signing_public, pq_spk, pq_sig)?;
        }
        Ok(())
    }
}

// ── X3DH initiator (Alice) ────────────────────────────────────────────────────

/// Result of Alice performing the X3DH / PQXDH send step.
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
    /// Optional post-quantum ML-KEM-768 ciphertext (if PQXDH was performed)
    #[zeroize(skip)]
    pub pq_ciphertext: Option<Vec<u8>>,
}

/// Alice performs the X3DH or hybrid PQXDH send step.
///
/// If `bundle.pq_spk_public` is present, encapsulates against Bob's ML-KEM-768
/// pre-key, generating a hybrid shared secret and attaching `pq_ciphertext`.
/// Otherwise falls back gracefully to classical X3DH.
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

    // PQXDH encapsulation if Bob provided PQ-SPK
    let (pq_ciphertext, pq_ss) = if let Some(pq_spk_bytes) = &bundle.pq_spk_public {
        let (ct, ss) = MlKem768::encapsulate(&MlKem768PublicKey(pq_spk_bytes.clone()))?;
        (Some(ct.0), Some(ss.0))
    } else {
        (None, None)
    };

    let shared_secret = kdf_pqxdh(
        dh1.as_bytes(),
        dh2.as_bytes(),
        dh3.as_bytes(),
        dh4_bytes.as_ref().map(|b| b.as_slice()),
        pq_ss.as_ref().map(|s| s.as_slice()),
    );

    Ok(X3DHSendResult {
        shared_secret,
        ephemeral_public: ek_public,
        opk_id_used,
        pq_ciphertext,
    })
}

// ── X3DH / PQXDH responder (Bob) ─────────────────────────────────────────────

/// Bob performs the hybrid PQXDH receive step.
pub fn pqxdh_receive(
    bob_ik_secret: &StaticSecret,
    bob_spk_secret: &StaticSecret,
    bob_opk_secret: Option<&StaticSecret>,
    bob_pq_spk_secret: Option<&MlKem768SecretKey>,
    alice_ik_public: [u8; 32],
    alice_ek_public: [u8; 32],
    pq_ciphertext: Option<&[u8]>,
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

    // PQXDH decapsulation if ciphertext was provided
    let pq_ss = match (pq_ciphertext, bob_pq_spk_secret) {
        (Some(ct_bytes), Some(dk)) => {
            let ss = MlKem768::decapsulate(dk, &MlKem768Ciphertext(ct_bytes.to_vec()))?;
            Some(ss.0)
        }
        (Some(_), None) => {
            return Err(ADAError::Crypto(
                "PQ ciphertext received but recipient has no PQ SPK key".into(),
            ));
        }
        (None, _) => None,
    };

    Ok(kdf_pqxdh(
        dh1.as_bytes(),
        dh2.as_bytes(),
        dh3.as_bytes(),
        dh4_bytes.as_ref().map(|b| b.as_slice()),
        pq_ss.as_ref().map(|s| s.as_slice()),
    ))
}

/// Bob performs the classical X3DH receive step.
pub fn x3dh_receive(
    bob_ik_secret: &StaticSecret,
    bob_spk_secret: &StaticSecret,
    bob_opk_secret: Option<&StaticSecret>,
    alice_ik_public: [u8; 32],
    alice_ek_public: [u8; 32],
) -> crate::error::Result<[u8; 32]> {
    pqxdh_receive(
        bob_ik_secret,
        bob_spk_secret,
        bob_opk_secret,
        None,
        alice_ik_public,
        alice_ek_public,
        None,
    )
}

// ── KDF ──────────────────────────────────────────────────────────────────────

/// Derive a 32-byte shared secret via HKDF-SHA256 with optional post-quantum shared secret.
fn kdf_pqxdh(
    dh1: &[u8],
    dh2: &[u8],
    dh3: &[u8],
    dh4: Option<&[u8]>,
    pq_ss: Option<&[u8]>,
) -> [u8; 32] {
    use hkdf::Hkdf;
    use sha2::Sha256;

    if let Some(ss) = pq_ss {
        // PQXDH: IKM = F || DH1 || DH2 || DH3 [|| DH4] || PQ_SS
        let mut ikm = Vec::with_capacity(32 * 6);
        ikm.extend_from_slice(&F);
        ikm.extend_from_slice(dh1);
        ikm.extend_from_slice(dh2);
        ikm.extend_from_slice(dh3);
        if let Some(d4) = dh4 {
            ikm.extend_from_slice(d4);
        }
        ikm.extend_from_slice(ss);

        let h = Hkdf::<Sha256>::new(Some(PQXDH_SALT), &ikm);
        let mut out = [0u8; 32];
        h.expand(PQXDH_INFO, &mut out).expect("HKDF expand PQXDH");
        out
    } else {
        kdf_x3dh(dh1, dh2, dh3, dh4)
    }
}

/// Concatenate DH outputs and derive a 32-byte shared secret via HKDF-SHA256 (classical X3DH).
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

    let h = Hkdf::<Sha256>::new(Some(X3DH_SALT), &ikm);
    let mut out = [0u8; 32];
    h.expand(X3DH_INFO, &mut out).expect("HKDF expand X3DH");
    out
}

// ── Pre-key bundle builder ───────────────────────────────────────────────────

/// Domain tag prepended to `spk_public` before signing.
const SPK_SIGN_PREFIX: &[u8] = b"ADA-SPK-v1:";

/// Domain tag prepended to `pq_spk_public` before signing.
const PQ_SPK_SIGN_PREFIX: &[u8] = b"ADA-PQSPK-v1:";

/// Sign a `spk_public` key with `signing_key` and return the signature bytes.
pub fn sign_spk(signing_key: &SigningKey, spk_public: &[u8; 32]) -> Vec<u8> {
    let mut msg = Vec::with_capacity(SPK_SIGN_PREFIX.len() + 32);
    msg.extend_from_slice(SPK_SIGN_PREFIX);
    msg.extend_from_slice(spk_public);
    signing_key.sign(&msg).to_bytes().to_vec()
}

/// Sign a `pq_spk_public` key with `signing_key` and return the signature bytes.
pub fn sign_pq_spk(signing_key: &SigningKey, pq_spk_public: &[u8]) -> Vec<u8> {
    let mut msg = Vec::with_capacity(PQ_SPK_SIGN_PREFIX.len() + pq_spk_public.len());
    msg.extend_from_slice(PQ_SPK_SIGN_PREFIX);
    msg.extend_from_slice(pq_spk_public);
    signing_key.sign(&msg).to_bytes().to_vec()
}

/// Verify that `pq_spk_public` was signed by `signing_public`.
pub fn verify_pq_spk_signature(
    signing_public: &[u8; 32],
    pq_spk_public: &[u8],
    signature: &[u8],
) -> Result<()> {
    let vk =
        VerifyingKey::from_bytes(signing_public).map_err(|_| ADAError::InvalidSignature)?;
    let sig_bytes: [u8; 64] = signature
        .try_into()
        .map_err(|_| ADAError::InvalidSignature)?;
    let sig = Signature::from_bytes(&sig_bytes);
    let mut msg = Vec::with_capacity(PQ_SPK_SIGN_PREFIX.len() + pq_spk_public.len());
    msg.extend_from_slice(PQ_SPK_SIGN_PREFIX);
    msg.extend_from_slice(pq_spk_public);
    vk.verify(&msg, &sig)
        .map_err(|_| ADAError::InvalidSignature)
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
            pq_spk_public: None,
            pq_spk_signature: None,
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
            pq_spk_public: None,
            pq_spk_signature: None,
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

    #[test]
    fn pqxdh_shared_secret_matches() {
        use crate::crypto::pq::MlKem768;

        // Bob generates classical keys
        let bob_ik = StaticSecret::random_from_rng(OsRng);
        let bob_ik_pub = PublicKey::from(&bob_ik).to_bytes();
        let bob_spk = StaticSecret::random_from_rng(OsRng);
        let bob_spk_pub = PublicKey::from(&bob_spk).to_bytes();
        let bob_signing = SigningKey::generate(&mut OsRng);
        let spk_sig = sign_spk(&bob_signing, &bob_spk_pub);

        // Bob generates ML-KEM-768 key pair
        let (bob_pq_pub, bob_pq_sec) = MlKem768::keygen().unwrap();
        let pq_spk_sig = sign_pq_spk(&bob_signing, &bob_pq_pub.0);

        let bundle = PreKeyBundle {
            ik_public: bob_ik_pub,
            spk_public: bob_spk_pub,
            spk_signature: spk_sig,
            opk_public: None,
            opk_id: None,
            pq_spk_public: Some(bob_pq_pub.0),
            pq_spk_signature: Some(pq_spk_sig),
        };

        // Alice generates her identity
        let alice_ik = StaticSecret::random_from_rng(OsRng);

        // Alice sends via PQXDH
        let result = x3dh_send(&alice_ik, &bundle, bob_signing.verifying_key().as_bytes()).unwrap();
        assert!(result.pq_ciphertext.is_some(), "PQXDH must produce pq_ciphertext");

        // Bob receives via PQXDH
        let alice_ik_pub = PublicKey::from(&alice_ik).to_bytes();
        let bob_ss = pqxdh_receive(
            &bob_ik,
            &bob_spk,
            None,
            Some(&bob_pq_sec),
            alice_ik_pub,
            result.ephemeral_public,
            result.pq_ciphertext.as_deref(),
        ).unwrap();

        assert_eq!(
            result.shared_secret, bob_ss,
            "PQXDH hybrid shared secret must match between initiator and receiver"
        );
    }

    #[test]
    fn pqxdh_tampered_sig_rejected() {
        use crate::crypto::pq::MlKem768;

        let bob_ik = StaticSecret::random_from_rng(OsRng);
        let bob_ik_pub = PublicKey::from(&bob_ik).to_bytes();
        let bob_spk = StaticSecret::random_from_rng(OsRng);
        let bob_spk_pub = PublicKey::from(&bob_spk).to_bytes();
        let bob_signing = SigningKey::generate(&mut OsRng);
        let spk_sig = sign_spk(&bob_signing, &bob_spk_pub);

        let (bob_pq_pub, _bob_pq_sec) = MlKem768::keygen().unwrap();
        let mut corrupted_pq_sig = sign_pq_spk(&bob_signing, &bob_pq_pub.0);
        corrupted_pq_sig[0] ^= 0xFF; // tamper with signature

        let bundle = PreKeyBundle {
            ik_public: bob_ik_pub,
            spk_public: bob_spk_pub,
            spk_signature: spk_sig,
            opk_public: None,
            opk_id: None,
            pq_spk_public: Some(bob_pq_pub.0),
            pq_spk_signature: Some(corrupted_pq_sig),
        };

        let alice_ik = StaticSecret::random_from_rng(OsRng);
        assert!(
            x3dh_send(&alice_ik, &bundle, bob_signing.verifying_key().as_bytes()).is_err(),
            "Tampered PQ-SPK signature must be rejected"
        );
    }
}
