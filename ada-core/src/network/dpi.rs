//! DPI (Deep Packet Inspection) obfuscation layer
//!
//! Provides traffic camouflage to bypass network censorship.
//! Different modes add varying levels of obfuscation at varying bandwidth cost.

use crate::error::{ADAError, Result};
use hkdf::Hkdf;
use rand::{rngs::OsRng, Rng, RngCore};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// A known bridge node in the ADA network
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BridgeNode {
    /// "host:port" address
    pub address: String,
    /// Expected TLS fingerprint / public key of the bridge (32 bytes)
    pub fingerprint: [u8; 32],
    /// Supported obfuscation protocols
    pub protocols: Vec<String>,
    /// Whether the node was reachable in the last probe
    pub reachable: bool,
}

/// Traffic obfuscation mode
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub enum ObfuscationMode {
    /// No obfuscation – raw QUIC traffic
    None,
    /// Random-length padding on each packet (mild obfuscation)
    RandomPadding {
        /// Maximum extra bytes to add per packet
        max_padding: usize,
    },
    /// Inter-packet delay jitter to defeat timing analysis
    TrafficShaping { target_rate_bps: u32 },
    /// TLS 1.3 Record mimicry with discrete bucket padding & HKDF stream masking
    TlsMorpher {
        /// Network passphrase / secret key used for stream masking
        passphrase: String,
    },
    /// Encapsulate all traffic as WebSocket frames over TLS
    WebSocketTLS {
        /// SNI hostname to present during TLS handshake
        hostname: String,
    },
    /// Domain fronting through a CDN
    DomainFronting {
        /// CDN-visible hostname (e.g. "cdn.cloudflare.com")
        front_domain: String,
        /// Real backend destination host header
        real_host: String,
    },
    /// Let the BridgeManager choose the best mode automatically
    #[default]
    Auto,
}

impl ObfuscationMode {
    /// Whether this mode actively modifies traffic patterns
    pub fn is_active(&self) -> bool {
        !matches!(self, ObfuscationMode::None)
    }

    /// Estimated per-packet overhead in bytes
    pub fn overhead_bytes(&self) -> usize {
        match self {
            ObfuscationMode::None => 0,
            ObfuscationMode::RandomPadding { max_padding } => max_padding / 2,
            ObfuscationMode::TrafficShaping { .. } => 0,
            ObfuscationMode::TlsMorpher { .. } => 5 + 16 + 4 + 256, // Header (5) + Salt (16) + Len (4) + avg padding
            ObfuscationMode::WebSocketTLS { .. } => 14,             // WS frame header
            ObfuscationMode::DomainFronting { .. } => 0,
            ObfuscationMode::Auto => 0,
        }
    }
}

/// Apply padding obfuscation to a packet payload.
///
/// Returns the padded payload. The receiver must strip padding before processing.
///
/// Wire format:
///   bytes [0..4]        — BE u32: actual plaintext length (supports up to 4 GiB)
///   bytes [4..4+len]    — actual payload
///   bytes [4+len..end]  — random padding (0..=max_padding bytes)
///
/// # Panics
/// Panics if `payload.len()` exceeds `u32::MAX` (~4 GiB), which is far above any
/// realistic message size.  The `MAX_MSG_BYTES` cap (4 MiB) applied upstream
/// makes this unreachable in practice; the assertion documents the invariant.
pub fn apply_padding(payload: &[u8], max_padding: usize) -> Vec<u8> {
    use rand::{Rng, RngCore};
    // Guard: u16 previously silently truncated payloads > 65535 bytes (КРИТ-11 fix).
    assert!(
        payload.len() <= u32::MAX as usize,
        "apply_padding: payload length {} exceeds u32::MAX",
        payload.len()
    );
    let extra = rand::thread_rng().gen_range(0..=max_padding);
    let mut out = Vec::with_capacity(payload.len() + 4 + extra);
    // 4-byte big-endian original length prefix (was 2-byte / u16 — КРИТ-11 fix)
    let orig_len = payload.len() as u32;
    out.extend_from_slice(&orig_len.to_be_bytes());
    out.extend_from_slice(payload);
    // random padding — fill_bytes is a single CSPRNG call, not N separate calls
    if extra > 0 {
        let start = out.len();
        out.resize(start + extra, 0u8);
        rand::rngs::OsRng.fill_bytes(&mut out[start..]);
    }
    out
}

/// Strip padding added by `apply_padding`, returning the original payload.
///
/// Returns `None` if the buffer is too short or the declared length is inconsistent.
pub fn strip_padding(padded: &[u8]) -> Option<Vec<u8>> {
    // Need at least 4 bytes for the u32 length prefix (was 2 — КРИТ-11 fix)
    if padded.len() < 4 {
        return None;
    }
    let orig_len = u32::from_be_bytes([padded[0], padded[1], padded[2], padded[3]]) as usize;
    if 4 + orig_len > padded.len() {
        return None;
    }
    Some(padded[4..4 + orig_len].to_vec())
}

/// Advanced DPI obfuscator providing TLS 1.3 Application Data mimicry,
/// discrete bucket-size padding, and HKDF-SHA256 stream XOR masking.
#[derive(Clone, Debug)]
pub struct DpiMorpher {
    obfs_key: [u8; 32],
}

impl DpiMorpher {
    /// Derive a DpiMorpher key from a human-readable network passphrase.
    pub fn new(network_passphrase: &str) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(b"ADA_DPI_TLS_MORPH_KEY:");
        hasher.update(network_passphrase.as_bytes());
        let key: [u8; 32] = hasher.finalize().into();
        Self { obfs_key: key }
    }

    /// Construct DpiMorpher directly from a 32-byte key.
    pub fn from_key(key: [u8; 32]) -> Self {
        Self { obfs_key: key }
    }

    /// Access the underlying 32-byte obfuscation key.
    pub fn key(&self) -> &[u8; 32] {
        &self.obfs_key
    }

    /// Calculate the discrete bucket size for a given raw payload length.
    ///
    /// Buckets: 512, 1024, 2048, and multiples of 1024 thereafter.
    /// Each bucket accommodates 16 bytes salt + 4 bytes length + payload + padding.
    pub fn calculate_bucket_size(raw_payload_len: usize) -> usize {
        let min_len = 16 + 4 + raw_payload_len;
        if min_len <= 512 {
            512
        } else if min_len <= 1024 {
            1024
        } else if min_len <= 2048 {
            2048
        } else {
            ((min_len + 1023) / 1024) * 1024
        }
    }

    /// Wrap raw bytes into a polymorphic pseudo-TLS 1.3 Application Data record.
    ///
    /// Wire layout:
    ///   - `[0x17, 0x03, 0x03]` : TLS 1.3 / legacy TLS 1.2 record header (Application Data)
    ///   - `[u16 BE]`           : Record length (body length)
    ///   - `body`:
    ///       - `[16 bytes]`     : Cleartext CSPRNG salt
    ///       - `[XOR masked]`   :
    ///           - `[u32 BE]`   : Original payload length
    ///           - `[payload]`  : Raw payload bytes
    ///           - `[padding]`  : Random CSPRNG padding bytes up to bucket size
    pub fn wrap_bytes(&self, raw_payload: &[u8]) -> Result<Vec<u8>> {
        let bucket_size = Self::calculate_bucket_size(raw_payload.len());
        if bucket_size > u16::MAX as usize {
            return Err(ADAError::Network(format!(
                "Payload size {} exceeds maximum TLS record size ({})",
                raw_payload.len(),
                u16::MAX
            )));
        }

        let salt: [u8; 16] = OsRng.gen();

        let mut body = Vec::with_capacity(bucket_size);
        body.extend_from_slice(&salt);
        body.extend_from_slice(&(raw_payload.len() as u32).to_be_bytes());
        body.extend_from_slice(raw_payload);

        let pad_len = bucket_size - body.len();
        if pad_len > 0 {
            let start = body.len();
            body.resize(bucket_size, 0u8);
            OsRng.fill_bytes(&mut body[start..]);
        }

        let mask = self.derive_stream_mask(&salt, body.len() - 16)?;
        for (b, m) in body[16..].iter_mut().zip(mask.iter()) {
            *b ^= *m;
        }

        let record_len = body.len() as u16;
        let mut tls_frame = Vec::with_capacity(5 + body.len());
        tls_frame.push(0x17);
        tls_frame.push(0x03);
        tls_frame.push(0x03);
        tls_frame.extend_from_slice(&record_len.to_be_bytes());
        tls_frame.extend_from_slice(&body);

        Ok(tls_frame)
    }

    /// Unwrap a pseudo-TLS 1.3 record frame back to the original raw payload.
    pub fn unwrap_bytes(&self, tls_frame: &[u8]) -> Result<Vec<u8>> {
        if tls_frame.len() < 5 + 20 {
            return Err(ADAError::Network("TLS record framing too short".into()));
        }
        if tls_frame[0] != 0x17 || tls_frame[1] != 0x03 || tls_frame[2] != 0x03 {
            return Err(ADAError::Network(
                "Invalid TLS record header (expected 0x17 0x03 0x03)".into(),
            ));
        }

        let declared_len = u16::from_be_bytes([tls_frame[3], tls_frame[4]]) as usize;
        if tls_frame.len() != 5 + declared_len {
            return Err(ADAError::Network(format!(
                "TLS record length mismatch: declared {}, actual {}",
                declared_len,
                tls_frame.len() - 5
            )));
        }

        let salt = &tls_frame[5..21];
        let mut masked_rest = tls_frame[21..].to_vec();
        let mask = self.derive_stream_mask(salt, masked_rest.len())?;
        for (b, m) in masked_rest.iter_mut().zip(mask.iter()) {
            *b ^= *m;
        }

        if masked_rest.len() < 4 {
            return Err(ADAError::Network("Corrupted unmasked TLS record body".into()));
        }

        let payload_len = u32::from_be_bytes([
            masked_rest[0],
            masked_rest[1],
            masked_rest[2],
            masked_rest[3],
        ]) as usize;

        if 4 + payload_len > masked_rest.len() {
            return Err(ADAError::Network(format!(
                "Declared payload length {} exceeds decrypted body buffer {}",
                payload_len,
                masked_rest.len() - 4
            )));
        }

        Ok(masked_rest[4..4 + payload_len].to_vec())
    }

    /// Wrap any serializable data structure into a TLS record using bincode.
    pub fn wrap_record<T: Serialize>(&self, payload: &T) -> Result<Vec<u8>> {
        let raw = bincode::serialize(payload)?;
        self.wrap_bytes(&raw)
    }

    /// Unwrap a TLS record frame and deserialize into the target type using bincode.
    pub fn unwrap_record<T: DeserializeOwned>(&self, tls_frame: &[u8]) -> Result<T> {
        let bytes = self.unwrap_bytes(tls_frame)?;
        let val = bincode::deserialize(&bytes)?;
        Ok(val)
    }

    fn derive_stream_mask(&self, salt: &[u8], len: usize) -> Result<Vec<u8>> {
        let hk = Hkdf::<Sha256>::new(Some(salt), &self.obfs_key);
        let mut mask = vec![0u8; len];
        for (chunk_idx, chunk) in mask.chunks_mut(4096).enumerate() {
            let mut info = Vec::with_capacity(b"ADA_DPI_MORPH_STREAM".len() + 4);
            info.extend_from_slice(b"ADA_DPI_MORPH_STREAM");
            info.extend_from_slice(&(chunk_idx as u32).to_be_bytes());
            hk.expand(&info, chunk)
                .map_err(|e| ADAError::Crypto(format!("HKDF expand failed for DPI morph mask: {e}")))?;
        }
        Ok(mask)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn padding_roundtrip() {
        let payload = b"hello ADA";
        let padded = apply_padding(payload, 64);
        let recovered = strip_padding(&padded).unwrap();
        assert_eq!(recovered, payload);
    }

    #[test]
    fn test_dpi_morpher_roundtrip_bytes() {
        let morpher = DpiMorpher::new("test-passphrase-ada-2026");

        // Test empty payload
        let empty_payload = b"";
        let wrapped_empty = morpher.wrap_bytes(empty_payload).unwrap();
        let unwrapped_empty = morpher.unwrap_bytes(&wrapped_empty).unwrap();
        assert_eq!(unwrapped_empty, empty_payload);

        // Test small payload
        let small_payload = b"Top secret decentralised chat message";
        let wrapped_small = morpher.wrap_bytes(small_payload).unwrap();
        let unwrapped_small = morpher.unwrap_bytes(&wrapped_small).unwrap();
        assert_eq!(unwrapped_small, small_payload);

        // Test medium payload (~1.5 KB)
        let medium_payload = vec![0x42u8; 1500];
        let wrapped_med = morpher.wrap_bytes(&medium_payload).unwrap();
        let unwrapped_med = morpher.unwrap_bytes(&wrapped_med).unwrap();
        assert_eq!(unwrapped_med, medium_payload);

        // Test large payload (~10 KB)
        let large_payload = vec![0xA5u8; 10_000];
        let wrapped_large = morpher.wrap_bytes(&large_payload).unwrap();
        let unwrapped_large = morpher.unwrap_bytes(&wrapped_large).unwrap();
        assert_eq!(unwrapped_large, large_payload);
    }

    #[test]
    fn test_dpi_morpher_bucket_sizes() {
        let morpher = DpiMorpher::new("bucket-test");

        // Payload 10 bytes -> min_len = 16 + 4 + 10 = 30 <= 512 -> bucket 512 + 5 TLS header = 517
        let w1 = morpher.wrap_bytes(&vec![0u8; 10]).unwrap();
        assert_eq!(w1.len(), 5 + 512);

        // Payload 500 bytes -> min_len = 16 + 4 + 500 = 520 <= 1024 -> bucket 1024 + 5 = 1029
        let w2 = morpher.wrap_bytes(&vec![0u8; 500]).unwrap();
        assert_eq!(w2.len(), 5 + 1024);

        // Payload 1500 bytes -> min_len = 16 + 4 + 1500 = 1520 <= 2048 -> bucket 2048 + 5 = 2053
        let w3 = morpher.wrap_bytes(&vec![0u8; 1500]).unwrap();
        assert_eq!(w3.len(), 5 + 2048);

        // Payload 2500 bytes -> min_len = 16 + 4 + 2500 = 2520 -> bucket 3072 + 5 = 3077
        let w4 = morpher.wrap_bytes(&vec![0u8; 2500]).unwrap();
        assert_eq!(w4.len(), 5 + 3072);
    }

    #[test]
    fn test_dpi_morpher_roundtrip_record() {
        #[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
        struct TestPacket {
            id: u64,
            sender: String,
            data: Vec<u8>,
        }

        let morpher = DpiMorpher::new("record-passphrase");
        let packet = TestPacket {
            id: 123456789,
            sender: "alice_peer".to_string(),
            data: vec![1, 2, 3, 4, 5, 6, 7, 8],
        };

        let wrapped = morpher.wrap_record(&packet).unwrap();
        let unwrapped: TestPacket = morpher.unwrap_record(&wrapped).unwrap();
        assert_eq!(unwrapped, packet);
    }

    #[test]
    fn test_dpi_morpher_wrong_key() {
        let alice_morpher = DpiMorpher::new("alice-secret");
        let eve_morpher = DpiMorpher::new("eve-different-secret");

        let payload = b"Sensitive text";
        let wrapped = alice_morpher.wrap_bytes(payload).unwrap();

        // Eve tries to unwrap with wrong key: HKDF mask will not match, length prefix or payload will mismatch
        let res = eve_morpher.unwrap_bytes(&wrapped);
        if let Ok(unwrapped) = res {
            assert_ne!(unwrapped, payload);
        }
    }

    #[test]
    fn test_dpi_morpher_corrupt_frame() {
        let morpher = DpiMorpher::new("corrupt-test");
        let payload = b"Sample data";
        let mut wrapped = morpher.wrap_bytes(payload).unwrap();

        // Corrupt TLS header
        wrapped[0] = 0x16; // Handshake instead of Application Data
        assert!(morpher.unwrap_bytes(&wrapped).is_err());

        // Restore header but truncate frame
        wrapped[0] = 0x17;
        let truncated = &wrapped[..wrapped.len() - 10];
        assert!(morpher.unwrap_bytes(truncated).is_err());
    }
}

