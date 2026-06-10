use crate::crypto::x3dh::sign_spk;
use crate::error::{ADAError, Result};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use parking_lot::RwLock;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use zeroize::{ZeroizeOnDrop, Zeroizing};

/// Derive a deterministic X25519 SPK for a given rotation epoch from the Ed25519
/// signing key seed via HKDF-SHA256.
///
/// Epoch 0 is backward-compatible with the original `ada/spk-derive/v1` derivation.
/// Each subsequent epoch produces a completely independent key.
fn derive_spk_for_epoch(seed: &[u8; 32], epoch: u32) -> x25519_dalek::StaticSecret {
    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(None, seed);
    // Embed the epoch in the info string so each epoch gets a distinct key.
    // Epoch 0 keeps the original label for backward compatibility.
    let info = if epoch == 0 {
        "ada/spk-derive/v1".to_string()
    } else {
        format!("ada/spk-derive/epoch/{}", epoch)
    };
    let mut spk_bytes = [0u8; 32];
    // 32-byte output — infallible (see hkdf_derive comment in symmetric.rs).
    let _ = hk.expand(info.as_bytes(), &mut spk_bytes);
    x25519_dalek::StaticSecret::from(spk_bytes)
}

/// The current signed pre-key state. Wrapped in `RwLock` inside `Identity`
/// so the SPK can be rotated without requiring `mut` on the `Arc<Identity>`.
pub struct SpkBundle {
    /// Rotation epoch counter. Persisted in `IdentityExport` and incremented
    /// each time `Identity::rotate_spk()` is called.
    pub epoch: u32,
    /// Current X25519 SPK secret (zeroized on drop via manual Drop).
    pub secret: x25519_dalek::StaticSecret,
    /// Current X25519 SPK public key.
    pub public: [u8; 32],
    /// Ed25519 signature of `public` by the identity signing key.
    pub signature: Vec<u8>,
}

impl Drop for SpkBundle {
    fn drop(&mut self) {
        // StaticSecret in x25519-dalek 2.x implements ZeroizeOnDrop when the
        // `zeroize` feature is enabled, so the DH secret bytes are zeroed
        // automatically.  No manual action needed here; this impl exists only
        // as a documentation anchor for reviewers.
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct PeerId(pub [u8; 32]);

impl PeerId {
    pub fn from_base64(s: &str) -> Result<Self> {
        use base64::Engine;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(s)
            .map_err(|e| ADAError::Unknown(e.to_string()))?;
        if bytes.len() != 32 {
            return Err(ADAError::Unknown("Invalid PeerId length".into()));
        }
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&bytes);
        Ok(PeerId(arr))
    }

    pub fn to_base64(&self) -> String {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(self.0)
    }
}

impl std::fmt::Display for PeerId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.to_base64())
    }
}

/// Serialisable secret export used for identity persistence
#[derive(Serialize, Deserialize, ZeroizeOnDrop)]
pub struct IdentityExport {
    pub signing_key_bytes: [u8; 32],
    pub dh_key_bytes: [u8; 32],
    #[zeroize(skip)]
    pub display_name: String,
    /// SPK rotation epoch — 0 = original, increments each rotation.
    /// `#[serde(default)]` so older exports (without this field) deserialise
    /// as epoch 0 for backward compatibility.
    #[serde(default)]
    #[zeroize(skip)]
    pub spk_epoch: u32,
}

pub struct Identity {
    pub peer_id: PeerId,
    pub display_name: String,
    pub signing_key: SigningKey,
    /// Long-term X25519 identity DH key (IK_A / IK_B in X3DH)
    pub dh_key: x25519_dalek::StaticSecret,
    /// Current signed pre-key — wrapped for atomic rotation support.
    /// Callers use accessor methods: `spk_public()`, `spk_secret_bytes()`,
    /// `spk_signature()`, `rotate_spk()`.
    spk: RwLock<SpkBundle>,
}

impl Identity {
    pub fn generate(display_name: &str) -> Self {
        let signing_key = SigningKey::generate(&mut OsRng);
        let dh_key = x25519_dalek::StaticSecret::random_from_rng(OsRng);
        let seed = signing_key.to_bytes();
        let spk_secret = derive_spk_for_epoch(&seed, 0);
        let spk_public = x25519_dalek::PublicKey::from(&spk_secret).to_bytes();
        let spk_signature = sign_spk(&signing_key, &spk_public);
        let peer_id = PeerId(*signing_key.verifying_key().as_bytes());
        Identity {
            peer_id,
            display_name: display_name.to_string(),
            signing_key,
            dh_key,
            spk: RwLock::new(SpkBundle {
                epoch: 0,
                secret: spk_secret,
                public: spk_public,
                signature: spk_signature,
            }),
        }
    }

    // ── SPK accessors ────────────────────────────────────────────────────────────────

    /// Current SPK public key bytes.
    #[inline]
    pub fn spk_public(&self) -> [u8; 32] {
        self.spk.read().public
    }
    /// Current SPK secret as raw bytes (zeroized on drop).
    #[inline]
    pub fn spk_secret_bytes(&self) -> Zeroizing<[u8; 32]> {
        Zeroizing::new(self.spk.read().secret.to_bytes())
    }
    /// Current SPK Ed25519 signature.
    #[inline]
    pub fn spk_signature(&self) -> Vec<u8> {
        self.spk.read().signature.clone()
    }
    /// Current SPK rotation epoch.
    #[inline]
    pub fn spk_epoch(&self) -> u32 {
        self.spk.read().epoch
    }

    /// Rotate the signed pre-key to a new epoch.
    ///
    /// Call this approximately every 7–30 days. After rotation, call
    /// `ADACore::republish_bundle()` to update the DHT with the new SPK.
    /// The old SPK is zeroized when the `SpkBundle` is replaced.
    pub fn rotate_spk(&self) {
        let seed = self.signing_key.to_bytes();
        let mut w = self.spk.write();
        let new_epoch = w.epoch.saturating_add(1);
        let new_secret = derive_spk_for_epoch(&seed, new_epoch);
        let new_public = x25519_dalek::PublicKey::from(&new_secret).to_bytes();
        let new_sig = sign_spk(&self.signing_key, &new_public);
        *w = SpkBundle {
            epoch: new_epoch,
            secret: new_secret,
            public: new_public,
            signature: new_sig,
        };
        tracing::info!("[identity] SPK rotated to epoch {}", new_epoch);
    }

    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        self.signing_key.sign(message).to_bytes().to_vec()
    }

    pub fn public_bundle(&self) -> PublicBundle {
        let spk = self.spk.read();
        PublicBundle {
            peer_id: self.peer_id.clone(),
            dh_public: x25519_dalek::PublicKey::from(&self.dh_key).to_bytes(),
            display_name: self.display_name.clone(),
            spk_public: spk.public,
            spk_signature: spk.signature.clone(),
            opk_public: None,
            opk_id: None,
            relay_url: None,
        }
    }

    /// Export secret key material for encrypted persistence
    pub fn export_secret(&self) -> IdentityExport {
        IdentityExport {
            signing_key_bytes: self.signing_key.to_bytes(),
            dh_key_bytes: self.dh_key.to_bytes(),
            display_name: self.display_name.clone(),
            spk_epoch: self.spk.read().epoch,
        }
    }

    /// Restore identity from an export
    pub fn import_secret(export: IdentityExport) -> Result<Self> {
        let signing_key = SigningKey::from_bytes(&export.signing_key_bytes);
        let dh_key = x25519_dalek::StaticSecret::from(export.dh_key_bytes);
        let spk_secret = derive_spk_for_epoch(&export.signing_key_bytes, export.spk_epoch);
        let spk_public = x25519_dalek::PublicKey::from(&spk_secret).to_bytes();
        let spk_signature = sign_spk(&signing_key, &spk_public);
        let peer_id = PeerId(*signing_key.verifying_key().as_bytes());
        Ok(Identity {
            peer_id,
            display_name: export.display_name.clone(),
            signing_key,
            dh_key,
            spk: RwLock::new(SpkBundle {
                epoch: export.spk_epoch,
                secret: spk_secret,
                public: spk_public,
                signature: spk_signature,
            }),
        })
    }

    /// Derive an iroh `SecretKey` from this identity's Ed25519 signing key seed.
    ///
    /// Both ADA and iroh use Ed25519 (32-byte scalar → 32-byte public key), so the
    /// keypairs are identical — the `PeerId` bytes are also the iroh `NodeId` bytes.
    /// This means no additional key exchange or storage is needed.
    pub fn iroh_secret_key(&self) -> iroh::SecretKey {
        iroh::SecretKey::from_bytes(&self.signing_key.to_bytes())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PublicBundle {
    pub peer_id: PeerId,
    /// Long-term X25519 identity DH public key (IK)
    pub dh_public: [u8; 32],
    pub display_name: String,
    /// Signed pre-key public (X25519)
    pub spk_public: [u8; 32],
    /// Ed25519 signature of spk_public by peer's signing key
    pub spk_signature: Vec<u8>,
    /// One-time pre-key public (X25519), if available
    #[serde(default)]
    pub opk_public: Option<[u8; 32]>,
    /// One-time pre-key id
    #[serde(default)]
    pub opk_id: Option<u32>,
    /// Optional iroh relay URL hint captured during contact exchange.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub relay_url: Option<String>,
}

/// Per-contact ephemeral X25519 identity key.
/// Generated once when opening an incognito conversation — never reused
/// across different contacts.  The main Ed25519 identity (PeerId) is
/// unchanged; only the X3DH layer uses this key so session correlation
/// across different chats is prevented.
#[derive(Clone)]
pub struct EphemeralContactKey {
    pub secret: x25519_dalek::StaticSecret,
    pub public: [u8; 32],
}

impl EphemeralContactKey {
    /// Generate a fresh random ephemeral key for one incognito chat.
    pub fn generate() -> Self {
        let secret = x25519_dalek::StaticSecret::random_from_rng(OsRng);
        let public = x25519_dalek::PublicKey::from(&secret).to_bytes();
        EphemeralContactKey { secret, public }
    }

    /// Restore from persisted 32-byte secret.
    pub fn from_secret_bytes(bytes: [u8; 32]) -> Self {
        let secret = x25519_dalek::StaticSecret::from(bytes);
        let public = x25519_dalek::PublicKey::from(&secret).to_bytes();
        EphemeralContactKey { secret, public }
    }

    /// Return the raw secret bytes for persistent storage (store encrypted).
    pub fn to_secret_bytes(&self) -> [u8; 32] {
        self.secret.to_bytes()
    }
}

impl PublicBundle {
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> Result<()> {
        let vk =
            VerifyingKey::from_bytes(&self.peer_id.0).map_err(|_| ADAError::InvalidSignature)?;
        let sig_bytes: [u8; 64] = signature
            .try_into()
            .map_err(|_| ADAError::InvalidSignature)?;
        let sig = Signature::from_bytes(&sig_bytes);
        vk.verify(message, &sig)
            .map_err(|_| ADAError::InvalidSignature)
    }
}
