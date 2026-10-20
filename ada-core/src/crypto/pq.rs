//! Post-Quantum Cryptography Module (NIST FIPS 203 ML-KEM)
//!
//! Provides ML-KEM-768 key encapsulation mechanisms for hybrid PQXDH
//! key agreement and hybrid Double Ratchet (SPQR).

use crate::error::{ADAError, Result};
use fips203::ml_kem_768;
use fips203::traits::{Decaps, Encaps, KeyGen, SerDes};
use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, ZeroizeOnDrop};

pub const ML_KEM_768_EK_LEN: usize = ml_kem_768::EK_LEN;
pub const ML_KEM_768_DK_LEN: usize = ml_kem_768::DK_LEN;
pub const ML_KEM_768_CT_LEN: usize = ml_kem_768::CT_LEN;
pub const ML_KEM_768_SS_LEN: usize = 32;

/// Serialisable ML-KEM-768 encapsulation public key (1184 bytes).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MlKem768PublicKey(pub Vec<u8>);

/// Secret decapsulation key with zeroization on drop (2400 bytes).
#[derive(Clone, Zeroize, ZeroizeOnDrop, Serialize, Deserialize)]
pub struct MlKem768SecretKey(pub Vec<u8>);

/// Serialisable ML-KEM-768 ciphertext (1088 bytes).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MlKem768Ciphertext(pub Vec<u8>);

/// Shared secret produced by encapsulation / decapsulation (32 bytes).
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct MlKemSharedSecret(pub [u8; ML_KEM_768_SS_LEN]);

pub struct MlKem768;

impl MlKem768 {
    /// Generate a fresh ML-KEM-768 encapsulation / decapsulation key pair using OsRng.
    pub fn keygen() -> Result<(MlKem768PublicKey, MlKem768SecretKey)> {
        let (ek, dk) = ml_kem_768::KG::try_keygen()
            .map_err(|e| ADAError::Crypto(format!("ML-KEM-768 keygen failed: {e:?}")))?;

        let ek_bytes = ek.into_bytes().to_vec();
        let dk_bytes = dk.into_bytes().to_vec();

        Ok((MlKem768PublicKey(ek_bytes), MlKem768SecretKey(dk_bytes)))
    }

    /// Generate an ML-KEM-768 key pair deterministically from a 32-byte master seed.
    /// Used by Pattern Auth to derive the user's post-quantum identity.
    pub fn keygen_from_seed(seed: &[u8; 32]) -> Result<(MlKem768PublicKey, MlKem768SecretKey)> {
        use rand_chacha::rand_core::SeedableRng;
        use rand_chacha::ChaCha20Rng;

        let mut rng = ChaCha20Rng::from_seed(*seed);
        let (ek, dk) = ml_kem_768::KG::try_keygen_with_rng(&mut rng)
            .map_err(|e| ADAError::Crypto(format!("ML-KEM-768 keygen from seed failed: {e:?}")))?;

        let ek_bytes = ek.into_bytes().to_vec();
        let dk_bytes = dk.into_bytes().to_vec();

        Ok((MlKem768PublicKey(ek_bytes), MlKem768SecretKey(dk_bytes)))
    }

    /// Encapsulate a shared secret against recipient's public encapsulation key.
    pub fn encapsulate(
        ek: &MlKem768PublicKey,
    ) -> Result<(MlKem768Ciphertext, MlKemSharedSecret)> {
        if ek.0.len() != ML_KEM_768_EK_LEN {
            return Err(ADAError::Crypto(format!(
                "Invalid ML-KEM-768 EK length: expected {}, got {}",
                ML_KEM_768_EK_LEN,
                ek.0.len()
            )));
        }

        let ek_array: [u8; ML_KEM_768_EK_LEN] = ek.0.as_slice().try_into().map_err(|_| {
            ADAError::Crypto("Failed to convert EK slice to fixed array".to_string())
        })?;

        let encaps_key = ml_kem_768::EncapsKey::try_from_bytes(ek_array)
            .map_err(|e| ADAError::Crypto(format!("Invalid ML-KEM-768 EK bytes: {e:?}")))?;

        let (ss, ct) = encaps_key
            .try_encaps()
            .map_err(|e| ADAError::Crypto(format!("ML-KEM-768 encaps failed: {e:?}")))?;

        let ct_bytes = ct.into_bytes().to_vec();
        let ss_bytes: [u8; ML_KEM_768_SS_LEN] = ss.into_bytes();

        Ok((MlKem768Ciphertext(ct_bytes), MlKemSharedSecret(ss_bytes)))
    }

    /// Decapsulate a shared secret using secret decapsulation key and ciphertext.
    pub fn decapsulate(
        dk: &MlKem768SecretKey,
        ct: &MlKem768Ciphertext,
    ) -> Result<MlKemSharedSecret> {
        if dk.0.len() != ML_KEM_768_DK_LEN {
            return Err(ADAError::Crypto(format!(
                "Invalid ML-KEM-768 DK length: expected {}, got {}",
                ML_KEM_768_DK_LEN,
                dk.0.len()
            )));
        }
        if ct.0.len() != ML_KEM_768_CT_LEN {
            return Err(ADAError::Crypto(format!(
                "Invalid ML-KEM-768 CT length: expected {}, got {}",
                ML_KEM_768_CT_LEN,
                ct.0.len()
            )));
        }

        let dk_array: [u8; ML_KEM_768_DK_LEN] = dk.0.as_slice().try_into().map_err(|_| {
            ADAError::Crypto("Failed to convert DK slice to fixed array".to_string())
        })?;
        let ct_array: [u8; ML_KEM_768_CT_LEN] = ct.0.as_slice().try_into().map_err(|_| {
            ADAError::Crypto("Failed to convert CT slice to fixed array".to_string())
        })?;

        let decaps_key = ml_kem_768::DecapsKey::try_from_bytes(dk_array)
            .map_err(|e| ADAError::Crypto(format!("Invalid ML-KEM-768 DK bytes: {e:?}")))?;

        let ciphertext = ml_kem_768::CipherText::try_from_bytes(ct_array)
            .map_err(|e| ADAError::Crypto(format!("Invalid ML-KEM-768 CT bytes: {e:?}")))?;

        let ss = decaps_key
            .try_decaps(&ciphertext)
            .map_err(|e| ADAError::Crypto(format!("ML-KEM-768 decaps failed: {e:?}")))?;

        let ss_bytes: [u8; ML_KEM_768_SS_LEN] = ss.into_bytes();
        Ok(MlKemSharedSecret(ss_bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ml_kem_768_roundtrip() {
        let (ek, dk) = MlKem768::keygen().expect("keygen must succeed");
        assert_eq!(ek.0.len(), ML_KEM_768_EK_LEN);
        assert_eq!(dk.0.len(), ML_KEM_768_DK_LEN);

        let (ct, ss_sender) = MlKem768::encapsulate(&ek).expect("encapsulate must succeed");
        assert_eq!(ct.0.len(), ML_KEM_768_CT_LEN);

        let ss_receiver = MlKem768::decapsulate(&dk, &ct).expect("decapsulate must succeed");

        assert_eq!(ss_sender.0, ss_receiver.0);
    }

    #[test]
    fn test_ml_kem_768_deterministic_keygen() {
        let seed1 = [0x55u8; 32];
        let seed2 = [0xAAu8; 32];

        let (ek1_a, dk1_a) = MlKem768::keygen_from_seed(&seed1).unwrap();
        let (ek1_b, dk1_b) = MlKem768::keygen_from_seed(&seed1).unwrap();

        // Same seed must produce identical public and private keys
        assert_eq!(ek1_a, ek1_b);
        assert_eq!(dk1_a.0, dk1_b.0);

        // Different seed must produce different keys
        let (ek2, dk2) = MlKem768::keygen_from_seed(&seed2).unwrap();
        assert_ne!(ek1_a, ek2);
        assert_ne!(dk1_a.0, dk2.0);

        // Test encryption with deterministically derived key
        let (ct, ss1) = MlKem768::encapsulate(&ek1_a).unwrap();
        let ss2 = MlKem768::decapsulate(&dk1_b, &ct).unwrap();
        assert_eq!(ss1.0, ss2.0);
    }
}
