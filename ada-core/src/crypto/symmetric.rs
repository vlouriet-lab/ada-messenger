use crate::error::{ADAError, Result};
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Key, Nonce,
};
use hkdf::Hkdf;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::Sha256;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EncryptedData {
    pub nonce: [u8; 12],
    pub ciphertext: Vec<u8>,
}

pub fn generate_key() -> [u8; 32] {
    let mut key = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut key);
    key
}

pub fn encrypt(key_bytes: &[u8], plaintext: &[u8], aad: Option<&[u8]>) -> Result<EncryptedData> {
    let key = Key::<Aes256Gcm>::from_slice(key_bytes);
    let cipher = Aes256Gcm::new(key);
    let mut nonce_bytes = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let payload = if let Some(a) = aad {
        aes_gcm::aead::Payload {
            msg: plaintext,
            aad: a,
        }
    } else {
        aes_gcm::aead::Payload {
            msg: plaintext,
            aad: &[],
        }
    };

    let ciphertext = cipher
        .encrypt(nonce, payload)
        .map_err(|e| ADAError::Crypto(e.to_string()))?;

    Ok(EncryptedData {
        nonce: nonce_bytes,
        ciphertext,
    })
}

pub fn decrypt(key_bytes: &[u8], data: &EncryptedData, aad: Option<&[u8]>) -> Result<Vec<u8>> {
    let key = Key::<Aes256Gcm>::from_slice(key_bytes);
    let cipher = Aes256Gcm::new(key);
    let nonce = Nonce::from_slice(&data.nonce);

    let payload = if let Some(a) = aad {
        aes_gcm::aead::Payload {
            msg: &data.ciphertext,
            aad: a,
        }
    } else {
        aes_gcm::aead::Payload {
            msg: &data.ciphertext,
            aad: &[],
        }
    };

    cipher
        .decrypt(nonce, payload)
        .map_err(|e| ADAError::Crypto(e.to_string()))
}

/// Derive exactly 32 bytes of key material via HKDF-SHA256.
///
/// The `output` parameter is a fixed-size `[u8; 32]` array so that the HKDF
/// `expand()` call is structurally incapable of failing (it can only fail when
/// `output.len() > 255 * HashLen = 8160 bytes`, which is impossible here).
/// This removes the previous `.expect("HKDF expand failed")` panic path entirely.
pub fn hkdf_derive(salt: &[u8], ikm: Option<&[u8]>, info: &[u8], output: &mut [u8; 32]) {
    let h = Hkdf::<Sha256>::new(Some(salt), ikm.unwrap_or(&[]));
    // 32 ≤ 8160 — expand with a fixed-size buffer is infallible.
    let _ = h.expand(info, output.as_mut_slice());
}
