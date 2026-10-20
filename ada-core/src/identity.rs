use crate::crypto::pq::{MlKem768, MlKem768PublicKey, MlKem768SecretKey};
use crate::crypto::x3dh::{sign_pq_spk, sign_spk, PreKeyBundle};
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

/// Derive a deterministic ML-KEM-768 key pair for a given rotation epoch from the Ed25519
/// signing key seed via HKDF-SHA256.
fn derive_pq_spk_for_epoch(seed: &[u8; 32], epoch: u32) -> (MlKem768PublicKey, MlKem768SecretKey) {
    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(None, seed);
    let info = format!("ada/pq-spk-derive/epoch/{}", epoch);
    let mut pq_seed = [0u8; 32];
    let _ = hk.expand(info.as_bytes(), &mut pq_seed);
    MlKem768::keygen_from_seed(&pq_seed).expect("ML-KEM-768 deterministic keygen")
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
    /// Current ML-KEM-768 secret key (zeroized on drop).
    pub pq_secret: MlKem768SecretKey,
    /// Current ML-KEM-768 public key.
    pub pq_public: MlKem768PublicKey,
    /// Ed25519 signature of `pq_public` by the identity signing key.
    pub pq_signature: Vec<u8>,
}

impl Drop for SpkBundle {
    fn drop(&mut self) {
        // StaticSecret in x25519-dalek 2.x and MlKem768SecretKey both implement
        // ZeroizeOnDrop internally, so key bytes are zeroed automatically.
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

    /// Verify an Ed25519 signature over a message using this PeerId's public key.
    pub fn verify_signature(&self, message: &[u8], signature_bytes: &[u8]) -> bool {
        if signature_bytes.len() != 64 {
            return false;
        }
        let Ok(vk) = VerifyingKey::from_bytes(&self.0) else {
            return false;
        };
        let mut sig_arr = [0u8; 64];
        sig_arr.copy_from_slice(signature_bytes);
        let sig = Signature::from_bytes(&sig_arr);
        vk.verify(message, &sig).is_ok()
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
        let (pq_public, pq_secret) = derive_pq_spk_for_epoch(&seed, 0);
        let pq_signature = sign_pq_spk(&signing_key, &pq_public.0);
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
                pq_secret,
                pq_public,
                pq_signature,
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

    /// Current ML-KEM-768 public key.
    #[inline]
    pub fn pq_spk_public(&self) -> MlKem768PublicKey {
        self.spk.read().pq_public.clone()
    }

    /// Current ML-KEM-768 secret key.
    #[inline]
    pub fn pq_spk_secret(&self) -> MlKem768SecretKey {
        self.spk.read().pq_secret.clone()
    }

    /// Current ML-KEM-768 Ed25519 signature.
    #[inline]
    pub fn pq_spk_signature(&self) -> Vec<u8> {
        self.spk.read().pq_signature.clone()
    }

    /// Rotate the signed pre-key (both classical X25519 and ML-KEM-768) to a new epoch.
    ///
    /// Call this approximately every 7–30 days. After rotation, call
    /// `ADACore::republish_bundle()` to update the DHT with the new SPK.
    /// The old SPKs are zeroized when the `SpkBundle` is replaced.
    pub fn rotate_spk(&self) {
        let seed = self.signing_key.to_bytes();
        let mut w = self.spk.write();
        let new_epoch = w.epoch.saturating_add(1);
        let new_secret = derive_spk_for_epoch(&seed, new_epoch);
        let new_public = x25519_dalek::PublicKey::from(&new_secret).to_bytes();
        let new_sig = sign_spk(&self.signing_key, &new_public);
        let (new_pq_pub, new_pq_sec) = derive_pq_spk_for_epoch(&seed, new_epoch);
        let new_pq_sig = sign_pq_spk(&self.signing_key, &new_pq_pub.0);
        *w = SpkBundle {
            epoch: new_epoch,
            secret: new_secret,
            public: new_public,
            signature: new_sig,
            pq_secret: new_pq_sec,
            pq_public: new_pq_pub,
            pq_signature: new_pq_sig,
        };
        tracing::info!("[identity] SPK and PQ-SPK rotated to epoch {}", new_epoch);
    }

    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        self.signing_key.sign(message).to_bytes().to_vec()
    }

    /// Sign a message and return the 64-byte Ed25519 signature array.
    pub fn sign_64(&self, message: &[u8]) -> [u8; 64] {
        self.signing_key.sign(message).to_bytes()
    }

    pub fn public_bundle(&self) -> PublicBundle {
        let spk = self.spk.read();
        let dh_public = x25519_dalek::PublicKey::from(&self.dh_key).to_bytes();
        PublicBundle {
            peer_id: self.peer_id.clone(),
            dh_public,
            display_name: self.display_name.clone(),
            spk_public: spk.public,
            spk_signature: spk.signature.clone(),
            // Bind the X3DH identity key to this Ed25519 identity.  Without it a
            // rewritten bundle could swap `dh_public` while keeping a valid
            // `spk_signature` and impersonate us to the recipient.
            ik_signature: Some(crate::crypto::verification::sign_ik(
                &self.signing_key,
                &dh_public,
            )),
            opk_public: None,
            opk_id: None,
            relay_url: None,
            pq_spk_public: Some(spk.pq_public.0.clone()),
            pq_spk_signature: Some(spk.pq_signature.clone()),
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
        let (pq_public, pq_secret) =
            derive_pq_spk_for_epoch(&export.signing_key_bytes, export.spk_epoch);
        let pq_signature = sign_pq_spk(&signing_key, &pq_public.0);
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
                pq_secret,
                pq_public,
                pq_signature,
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
    /// Ed25519 signature by `peer_id` over `dh_public` — see
    /// [`crate::crypto::verification`].
    ///
    /// `None` for bundles imported from contact cards older than v4, which never
    /// bound the X3DH identity key to the identity.  Such a bundle is only
    /// authenticated by TOFU, and a man-in-the-middle present at first contact
    /// cannot be detected locally — only by comparing safety numbers.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ik_signature: Option<Vec<u8>>,
    /// One-time pre-key public (X25519), if available
    #[serde(default)]
    pub opk_public: Option<[u8; 32]>,
    /// One-time pre-key id
    #[serde(default)]
    pub opk_id: Option<u32>,
    /// Optional iroh relay URL hint captured during contact exchange.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub relay_url: Option<String>,
    /// Optional post-quantum ML-KEM-768 signed pre-key (public key bytes, 1184 bytes).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pq_spk_public: Option<Vec<u8>>,
    /// Ed25519 signature of pq_spk_public by peer's signing key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pq_spk_signature: Option<Vec<u8>>,
}

impl PublicBundle {
    /// Convert this `PublicBundle` to a `PreKeyBundle` for X3DH / PQXDH key agreement.
    pub fn to_prekey_bundle(&self) -> PreKeyBundle {
        PreKeyBundle {
            ik_public: self.dh_public,
            spk_public: self.spk_public,
            spk_signature: self.spk_signature.clone(),
            opk_public: self.opk_public,
            opk_id: self.opk_id,
            pq_spk_public: self.pq_spk_public.clone(),
            pq_spk_signature: self.pq_spk_signature.clone(),
        }
    }
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

    /// Verify that `dh_public` — the key X3DH actually uses — is bound to `peer_id`.
    ///
    /// Returns `Ok(false)` when the bundle carries no binding signature at all
    /// (cards older than v4): that is a *downgrade*, not an error, and callers
    /// should surface it as "keys not authenticated" rather than reject the contact.
    pub fn ik_binding_verified(&self) -> Result<bool> {
        let Some(signature) = self.ik_signature.as_deref() else {
            return Ok(false);
        };
        crate::crypto::verification::verify_ik_signature(
            &self.dh_public,
            &self.peer_id.0,
            signature,
        )
        .map(|()| true)
    }

    /// Strict variant of [`Self::ik_binding_verified`] for freshly imported bundles,
    /// where an absent or invalid binding must be rejected outright.
    pub fn require_ik_binding(&self) -> Result<()> {
        let signature = self
            .ik_signature
            .as_deref()
            .ok_or(ADAError::InvalidSignature)?;
        crate::crypto::verification::verify_ik_signature(
            &self.dh_public,
            &self.peer_id.0,
            signature,
        )
    }
}
