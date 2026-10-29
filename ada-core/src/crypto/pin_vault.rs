//! `pin_vault.rs` — Sealed protocol envelope for quick-unlock PIN credentials.
//!
//! ## Threat Model & Architectural Invariant
//! A standard 4–8 digit PIN has low raw entropy (10^4 – 10^8 combinations). If
//! an attacker dumps device storage, offline brute-force against a purely PIN-derived
//! key takes mere seconds.
//!
//! To make the PIN cryptographically impenetrable:
//! 1. The PIN envelope is strictly bound to a 256-bit protocol device anchor (`protocol_anchor`).
//! 2. Key derivation uses Argon2id (memory-hard, resistant to GPU/ASIC attacks)
//!    combined via HKDF-SHA256 with the protocol anchor:
//!    ```text
//!    pin_stretched = Argon2id(pin, salt, mem=64MiB, t=3, p=1)
//!    ikm           = protocol_anchor (32B) || pin_stretched (32B)
//!    vault_key     = HKDF-SHA256(
//!                        ikm  = ikm (64B),
//!                        salt = salt (16B),
//!                        info = "ada/pin-vault-protocol/v1"
//!                    )
//!    ```
//! 3. Authenticated encryption: AES-256-GCM with associated authenticated data (AAD)
//!    binding the protocol magic header, version, and salt.
//!
//! Result: To crack the PIN, an adversary MUST first extract or decrypt the entire
//! 256-bit protocol anchor ($2^{256}$ search space). Offline brute-force of the PIN
//! alone is mathematically impossible without breaking the underlying device protocol secret.

use crate::error::{ADAError, Result};
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Key, Nonce,
};
use argon2::{Argon2, Params as Argon2Params, Version as Argon2Version};
use hkdf::Hkdf;
use rand::Rng;
use sha2::Sha256;
use zeroize::{Zeroize, Zeroizing};

pub const PIN_VAULT_MAGIC: [u8; 4] = *b"ADPV"; // ADA Protocol Vault
pub const PIN_VAULT_VERSION: u8 = 1;
pub const PIN_VAULT_SALT_LEN: usize = 16;
pub const PIN_VAULT_NONCE_LEN: usize = 12;
pub const PIN_VAULT_TAG_LEN: usize = 16;
pub const PIN_VAULT_HEADER_LEN: usize = 4 + 1 + PIN_VAULT_SALT_LEN + PIN_VAULT_NONCE_LEN; // 33 bytes

const ARGON2_MEM_KIB: u32 = 64 * 1024; // 64 MiB
const ARGON2_ITERS: u32 = 3;
const ARGON2_PARALLEL: u32 = 1;
const HKDF_INFO: &[u8] = b"ada/pin-vault-protocol/v1";

/// Seal sensitive plaintext (e.g. 32-byte pattern cells) into an ADPV protocol envelope.
///
/// Requires the user's PIN and the device's 256-bit protocol anchor.
///
/// Output envelope structure:
/// `[ MAGIC (4B) | VERSION (1B) | SALT (16B) | NONCE (12B) | CIPHERTEXT + TAG (N + 16B) ]`
pub fn seal(pin: &str, protocol_anchor: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>> {
    if pin.trim().is_empty() {
        return Err(ADAError::Crypto("PIN cannot be empty".to_string()));
    }
    if plaintext.is_empty() {
        return Err(ADAError::Crypto("Plaintext payload cannot be empty".to_string()));
    }

    let salt: [u8; PIN_VAULT_SALT_LEN] = rand::rngs::OsRng.gen();
    let nonce_bytes: [u8; PIN_VAULT_NONCE_LEN] = rand::rngs::OsRng.gen();

    let vault_key = derive_vault_key(pin.as_bytes(), protocol_anchor, &salt)?;

    // Authenticated Associated Data binds magic, version, and salt
    let mut aad = Vec::with_capacity(4 + 1 + PIN_VAULT_SALT_LEN);
    aad.extend_from_slice(&PIN_VAULT_MAGIC);
    aad.push(PIN_VAULT_VERSION);
    aad.extend_from_slice(&salt);

    let key = Key::<Aes256Gcm>::from_slice(&*vault_key);
    let cipher = Aes256Gcm::new(key);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext,
                aad: &aad,
            },
        )
        .map_err(|e| ADAError::Crypto(format!("PIN vault encryption failed: {e}")))?;

    let mut envelope = Vec::with_capacity(PIN_VAULT_HEADER_LEN + ciphertext.len());
    envelope.extend_from_slice(&PIN_VAULT_MAGIC);
    envelope.push(PIN_VAULT_VERSION);
    envelope.extend_from_slice(&salt);
    envelope.extend_from_slice(&nonce_bytes);
    envelope.extend_from_slice(&ciphertext);

    Ok(envelope)
}

/// Unseal an ADPV protocol envelope using the user's PIN and the device's 256-bit protocol anchor.
///
/// Returns the decrypted plaintext or an error if the envelope is invalid, corrupted,
/// or if either the PIN or the protocol anchor is incorrect.
pub fn unseal(pin: &str, protocol_anchor: &[u8; 32], envelope: &[u8]) -> Result<Vec<u8>> {
    if envelope.len() < PIN_VAULT_HEADER_LEN + PIN_VAULT_TAG_LEN {
        return Err(ADAError::Crypto(format!(
            "Envelope truncated: length {} is less than minimum required {}",
            envelope.len(),
            PIN_VAULT_HEADER_LEN + PIN_VAULT_TAG_LEN
        )));
    }

    if &envelope[0..4] != PIN_VAULT_MAGIC {
        return Err(ADAError::Crypto(
            "Invalid PIN vault header: magic mismatch".to_string(),
        ));
    }

    let version = envelope[4];
    if version != PIN_VAULT_VERSION {
        return Err(ADAError::Crypto(format!(
            "Unsupported PIN vault version: {version}"
        )));
    }

    let salt_end = 5 + PIN_VAULT_SALT_LEN;
    let nonce_end = salt_end + PIN_VAULT_NONCE_LEN;

    let salt: [u8; PIN_VAULT_SALT_LEN] = envelope[5..salt_end]
        .try_into()
        .map_err(|_| ADAError::Crypto("Invalid salt slice".to_string()))?;
    let nonce_bytes: [u8; PIN_VAULT_NONCE_LEN] = envelope[salt_end..nonce_end]
        .try_into()
        .map_err(|_| ADAError::Crypto("Invalid nonce slice".to_string()))?;
    let ciphertext = &envelope[nonce_end..];

    let vault_key = derive_vault_key(pin.as_bytes(), protocol_anchor, &salt)?;

    let mut aad = Vec::with_capacity(4 + 1 + PIN_VAULT_SALT_LEN);
    aad.extend_from_slice(&PIN_VAULT_MAGIC);
    aad.push(PIN_VAULT_VERSION);
    aad.extend_from_slice(&salt);

    let key = Key::<Aes256Gcm>::from_slice(&*vault_key);
    let cipher = Aes256Gcm::new(key);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let plaintext = cipher
        .decrypt(
            nonce,
            Payload {
                msg: ciphertext,
                aad: &aad,
            },
        )
        .map_err(|e| ADAError::Crypto(format!("PIN vault unseal authentication failed: {e}")))?;

    Ok(plaintext)
}

/// Derive the 256-bit vault encryption key using memory-hard Argon2id and protocol anchor HKDF.
fn derive_vault_key(
    pin_bytes: &[u8],
    protocol_anchor: &[u8; 32],
    salt: &[u8; PIN_VAULT_SALT_LEN],
) -> Result<Zeroizing<[u8; 32]>> {
    let params = Argon2Params::new(ARGON2_MEM_KIB, ARGON2_ITERS, ARGON2_PARALLEL, Some(32))
        .map_err(|e| ADAError::Crypto(format!("Argon2 params: {e}")))?;
    let argon2 = Argon2::new(argon2::Algorithm::Argon2id, Argon2Version::V0x13, params);

    let mut pin_stretched = [0u8; 32];
    argon2
        .hash_password_into(pin_bytes, salt, &mut pin_stretched)
        .map_err(|e| ADAError::Crypto(format!("Argon2id PIN stretch failed: {e}")))?;

    // Combine 256-bit protocol anchor with stretched PIN
    let mut ikm = [0u8; 64];
    ikm[..32].copy_from_slice(protocol_anchor);
    ikm[32..].copy_from_slice(&pin_stretched);
    pin_stretched.zeroize();

    let hk = Hkdf::<Sha256>::new(Some(salt), &ikm);
    ikm.zeroize();

    let mut vault_key = [0u8; 32];
    hk.expand(HKDF_INFO, &mut vault_key)
        .map_err(|e| ADAError::Crypto(format!("HKDF expand failed: {e}")))?;

    Ok(Zeroizing::new(vault_key))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_seal_and_unseal_roundtrip() {
        let pin = "4920";
        let anchor = [0x5au8; 32];
        let pattern_cells = (0u8..32).collect::<Vec<_>>();

        let sealed = seal(pin, &anchor, &pattern_cells).expect("seal must succeed");
        assert_eq!(&sealed[0..4], &PIN_VAULT_MAGIC);
        assert_eq!(sealed[4], PIN_VAULT_VERSION);
        assert_eq!(sealed.len(), PIN_VAULT_HEADER_LEN + 32 + PIN_VAULT_TAG_LEN);

        let unsealed = unseal(pin, &anchor, &sealed).expect("unseal must succeed");
        assert_eq!(unsealed, pattern_cells);
    }

    #[test]
    fn test_wrong_pin_fails_unseal() {
        let pin = "1234";
        let wrong_pin = "4321";
        let anchor = [0x33u8; 32];
        let data = b"super_secret_payload_bytes_32bit";

        let sealed = seal(pin, &anchor, data).expect("seal");
        let result = unseal(wrong_pin, &anchor, &sealed);
        assert!(result.is_err(), "unseal with wrong PIN must fail");
    }

    #[test]
    fn test_wrong_anchor_fails_unseal() {
        let pin = "7788";
        let anchor = [0x11u8; 32];
        let wrong_anchor = [0x22u8; 32];
        let data = b"pattern_key_cells_16_cubes_color";

        let sealed = seal(pin, &anchor, data).expect("seal");
        let result = unseal(pin, &wrong_anchor, &sealed);
        assert!(result.is_err(), "unseal with wrong anchor must fail");
    }

    #[test]
    fn test_corrupted_envelope_fails() {
        let pin = "9999";
        let anchor = [0xaa; 32];
        let data = b"short_data";

        let mut sealed = seal(pin, &anchor, data).expect("seal");
        // Corrupt magic
        sealed[0] ^= 0xff;
        assert!(unseal(pin, &anchor, &sealed).is_err());

        // Restore magic, corrupt ciphertext
        sealed[0] ^= 0xff;
        let last = sealed.len() - 1;
        sealed[last] ^= 0x01;
        assert!(unseal(pin, &anchor, &sealed).is_err());
    }

    #[test]
    fn test_truncated_envelope_fails() {
        let pin = "0000";
        let anchor = [0x42; 32];
        assert!(unseal(pin, &anchor, &[0u8; 20]).is_err());
    }
}
