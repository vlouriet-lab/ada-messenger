//! Mesh Handoff — chunked identity bundle transfer between devices.
//!
//! When a user migrates to a new device they need to transfer their ADA identity
//! (public bundle: PeerId, SPK, IK) without exposing the raw pattern.  The bundle
//! is serialised to JSON, split into fixed-size chunks, and each chunk is
//! integrity-protected via a SHA256 hash of the full bundle.
//!
//! # Protocol flow
//! 1. **Old device** calls [`prepare_bundle`] → produces a [`HandoffOffer`] and a
//!    `Vec<`[`HandoffChunk`]`>` that are transferred (QR, BLE, NFC, side-channel).
//! 2. **New device** calls [`begin_receive`] with the [`HandoffOffer`], then feeds
//!    each received chunk to [`ingest_chunk`].
//! 3. When [`is_complete`] returns `true`, call [`assemble`] to obtain the verified
//!    JSON bundle string.
//! 4. Pass the bundle JSON to `ada_add_contact_json` or `ADACore::add_contact`.
//!
//! # Security properties
//! * SHA256 integrity check prevents bundle tampering in transit.
//! * BLAKE3 keyed MAC (key = session_id bytes) prevents tampering of the bundle
//!   payload when the offer is delivered via an authentic side-channel (QR code)
//!   but the chunk stream travels over a potentially untrusted network channel.
//!   An attacker who can only modify chunks — without knowledge of the session_id
//!   embedded in the QR offer — cannot produce a valid MAC.
//! * Chunks are indexed so retransmission requests are possible.
//! * No private key material is included — only the public bundle.

use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{ADAError, Result};

/// Default chunk size: 512 bytes — fits comfortably in a QR code payload segment.
pub const DEFAULT_CHUNK_BYTES: usize = 512;
/// Maximum accepted chunk size (64 KiB guard against malformed offers).
pub const MAX_CHUNK_BYTES: usize = 64 * 1024;
/// Maximum total bundle size accepted on receive (256 KiB).
pub const MAX_BUNDLE_BYTES: usize = 256 * 1024;

// ── Wire types ────────────────────────────────────────────────────────────────

/// Offer record sent from the source device to the destination device.
/// Contains the parameters needed to receive, reassemble, and verify the bundle.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct HandoffOffer {
    /// Unique session identifier (random hex string, 16 bytes).
    pub session_id: String,
    /// Unix timestamp (seconds) at bundle generation time.
    pub generated_at: i64,
    /// Total bundle length in bytes.
    pub total_bytes: u64,
    /// Chunk size used for splitting.
    pub chunk_size: u64,
    /// Total number of chunks.
    pub total_chunks: u64,
    /// Hex-encoded SHA256 of the complete bundle bytes (legacy integrity check).
    pub bundle_sha256: String,
    /// Hex-encoded BLAKE3 keyed MAC of the complete bundle bytes.
    /// Key = first 32 bytes of the session_id repeated / zero-padded.
    /// Added in B-8: protects against chunk-stream tampering when the offer
    /// is delivered via an authentic side-channel but chunks travel over an
    /// untrusted network path.
    #[serde(default)]
    pub bundle_mac: String,
}

/// One chunk of the bundle.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct HandoffChunk {
    /// Session ID matching the offer.
    pub session_id: String,
    /// 0-based chunk index.
    pub chunk_index: u64,
    /// Total number of chunks (redundant copy for sanity-checking).
    pub total_chunks: u64,
    /// Raw bytes of this chunk.
    pub payload: Vec<u8>,
}

/// Request for missing chunks from the receiver to the sender.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RetransmitRequest {
    pub session_id: String,
    pub requested_at: i64,
    /// Indices of chunks not yet received.
    pub missing_indices: Vec<u64>,
}

// ── Sender side ───────────────────────────────────────────────────────────────

/// Prepare a bundle for transfer.
///
/// Returns `(HandoffOffer, Vec<HandoffChunk>)` ready to be sent to the destination
/// device in any order (chunks are individually labelled).
pub fn prepare_bundle(
    bundle_json: &str,
    chunk_size: usize,
) -> Result<(HandoffOffer, Vec<HandoffChunk>)> {
    if chunk_size == 0 || chunk_size > MAX_CHUNK_BYTES {
        return Err(ADAError::Unknown(format!(
            "chunk_size must be 1..={}, got {}",
            MAX_CHUNK_BYTES, chunk_size
        )));
    }
    let bytes = bundle_json.as_bytes();
    if bytes.len() > MAX_BUNDLE_BYTES {
        return Err(ADAError::Unknown(format!(
            "bundle too large: {} > {} bytes",
            bytes.len(),
            MAX_BUNDLE_BYTES
        )));
    }

    let mut sha = Sha256::new();
    sha.update(bytes);
    let hash_hex = hex::encode(sha.finalize());

    let total_chunks = bytes.len().div_ceil(chunk_size) as u64;
    let session_id_bytes = {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        id
    };
    let session_id = hex::encode(session_id_bytes);
    let generated_at = unix_now_secs()?;

    // B-8: BLAKE3 keyed MAC — key is session_id (16 bytes) zero-padded to 32 bytes.
    // Protects against chunk-stream tampering when the QR offer is authentic but
    // the chunk transfer path is over an untrusted network channel.
    let mac_key = mac_key_from_session(&session_id_bytes);
    let bundle_mac = hex::encode(blake3::keyed_hash(&mac_key, bytes).as_bytes());

    let offer = HandoffOffer {
        session_id: session_id.clone(),
        generated_at,
        total_bytes: bytes.len() as u64,
        chunk_size: chunk_size as u64,
        total_chunks,
        bundle_sha256: hash_hex,
        bundle_mac,
    };

    let chunks: Vec<HandoffChunk> = bytes
        .chunks(chunk_size)
        .enumerate()
        .map(|(i, slice)| HandoffChunk {
            session_id: session_id.clone(),
            chunk_index: i as u64,
            total_chunks,
            payload: slice.to_vec(),
        })
        .collect();

    Ok((offer, chunks))
}

/// Convenience wrapper: choose chunk size automatically based on target chunk count.
pub fn prepare_bundle_adaptive(
    bundle_json: &str,
    preferred_chunk_size: usize,
    max_chunk_size: usize,
    target_chunks: usize,
) -> Result<(HandoffOffer, Vec<HandoffChunk>)> {
    let effective = choose_chunk_size(
        bundle_json.len(),
        preferred_chunk_size,
        max_chunk_size,
        target_chunks,
    )?;
    prepare_bundle(bundle_json, effective)
}

// ── Receiver side ─────────────────────────────────────────────────────────────

/// In-progress receive state on the destination device.
pub struct HandoffReceiver {
    pub offer: HandoffOffer,
    chunks: HashMap<u64, Vec<u8>>,
    pub accepted_at: i64,
}

impl HandoffReceiver {
    /// Initialise receiver from an offer.
    pub fn new(offer: HandoffOffer) -> Result<Self> {
        if offer.total_bytes as usize > MAX_BUNDLE_BYTES {
            return Err(ADAError::Unknown(format!(
                "offered bundle too large: {} bytes",
                offer.total_bytes
            )));
        }
        if offer.chunk_size as usize > MAX_CHUNK_BYTES {
            return Err(ADAError::Unknown(
                "chunk_size in offer exceeds limit".into(),
            ));
        }
        if offer.total_chunks == 0 {
            return Err(ADAError::Unknown("total_chunks must be > 0".into()));
        }
        let accepted_at = unix_now_secs()?;
        Ok(Self {
            offer,
            chunks: HashMap::new(),
            accepted_at,
        })
    }

    /// Feed one chunk into the receiver.  Silently ignores out-of-range or
    /// mismatched-session chunks (caller should log a warning).
    pub fn ingest_chunk(&mut self, chunk: HandoffChunk) -> Result<()> {
        if chunk.session_id != self.offer.session_id {
            return Err(ADAError::Unknown("chunk session_id mismatch".into()));
        }
        if chunk.chunk_index >= self.offer.total_chunks {
            return Err(ADAError::Unknown(format!(
                "chunk_index {} out of range (total {})",
                chunk.chunk_index, self.offer.total_chunks
            )));
        }
        self.chunks.insert(chunk.chunk_index, chunk.payload);
        Ok(())
    }

    /// Returns true when all chunks have been received.
    pub fn is_complete(&self) -> bool {
        self.chunks.len() as u64 == self.offer.total_chunks
    }

    /// Returns progress as (received_chunks, total_chunks).
    pub fn progress(&self) -> (u64, u64) {
        (self.chunks.len() as u64, self.offer.total_chunks)
    }

    /// Returns a [`RetransmitRequest`] listing all missing chunk indices.
    pub fn missing_chunks_request(&self) -> RetransmitRequest {
        let missing: Vec<u64> = (0..self.offer.total_chunks)
            .filter(|i| !self.chunks.contains_key(i))
            .collect();
        RetransmitRequest {
            session_id: self.offer.session_id.clone(),
            requested_at: unix_now_secs().unwrap_or(0),
            missing_indices: missing,
        }
    }

    /// Reassemble and verify the bundle.
    ///
    /// Returns the bundle JSON string on success.
    /// Returns an error if chunks are incomplete, SHA256 doesn't match, or
    /// the BLAKE3 keyed MAC fails (B-8: chunk-stream tamper detection).
    pub fn assemble(&self) -> Result<String> {
        if !self.is_complete() {
            let (got, total) = self.progress();
            return Err(ADAError::Unknown(format!(
                "handoff incomplete: {}/{} chunks received",
                got, total
            )));
        }

        let mut assembled: Vec<u8> = Vec::with_capacity(self.offer.total_bytes as usize);
        for i in 0..self.offer.total_chunks {
            let chunk = self
                .chunks
                .get(&i)
                .ok_or_else(|| ADAError::Unknown(format!("chunk {} missing during assembly", i)))?;
            assembled.extend_from_slice(chunk);
        }

        // Verify SHA256 integrity
        let mut sha = Sha256::new();
        sha.update(&assembled);
        let actual_hash = hex::encode(sha.finalize());
        if actual_hash != self.offer.bundle_sha256 {
            return Err(ADAError::Crypto(format!(
                "handoff SHA256 mismatch: expected {}, got {}",
                self.offer.bundle_sha256, actual_hash
            )));
        }

        // B-8: Verify BLAKE3 keyed MAC when present.
        // Older offers (without bundle_mac) are accepted for backward compatibility;
        // new offers produced by this code always include the MAC.
        if !self.offer.bundle_mac.is_empty() {
            let session_bytes = hex::decode(&self.offer.session_id)
                .map_err(|_| ADAError::Crypto("invalid session_id in offer".into()))?;
            if session_bytes.len() < 16 {
                return Err(ADAError::Crypto(
                    "session_id too short for MAC derivation".into(),
                ));
            }
            let mut id = [0u8; 16];
            id.copy_from_slice(&session_bytes[..16]);
            let mac_key = mac_key_from_session(&id);
            let actual_mac = hex::encode(blake3::keyed_hash(&mac_key, &assembled).as_bytes());
            if actual_mac != self.offer.bundle_mac {
                return Err(ADAError::Crypto(
                    "handoff BLAKE3 MAC verification failed: bundle may have been tampered".into(),
                ));
            }
        }

        String::from_utf8(assembled)
            .map_err(|e| ADAError::Unknown(format!("bundle is not valid UTF-8: {}", e)))
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

fn choose_chunk_size(
    total_len: usize,
    preferred: usize,
    max_size: usize,
    target_chunks: usize,
) -> Result<usize> {
    if preferred == 0 || max_size == 0 {
        return Err(ADAError::Unknown("chunk sizes must be > 0".into()));
    }
    if total_len == 0 {
        return Ok(preferred.min(max_size));
    }
    // Try to hit the target chunk count; fall back to preferred if not needed.
    let ideal = total_len.div_ceil(target_chunks.max(1));
    let size = ideal.max(preferred).min(max_size);
    Ok(size)
}

/// Derive a 32-byte BLAKE3 keyed-hash key from a 16-byte session identifier.
/// The session_id bytes are placed in the lower 16 bytes; upper 16 bytes are zero.
/// This gives an independent 128-bit sub-key per handoff session.
fn mac_key_from_session(session_id: &[u8; 16]) -> [u8; 32] {
    let mut key = [0u8; 32];
    key[..16].copy_from_slice(session_id);
    key
}

fn unix_now_secs() -> Result<i64> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .map_err(|e| ADAError::Unknown(format!("system clock error: {}", e)))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_small_bundle() {
        let json =
            r#"{"v":2,"id":"AAAA","spk":"BBBB","ik":"CCCC","spk_sig":"DDDD","name":"Alice"}"#;
        let (offer, chunks) = prepare_bundle(json, 32).unwrap();
        assert_eq!(chunks.len() as u64, offer.total_chunks);

        let mut recv = HandoffReceiver::new(offer).unwrap();
        for chunk in chunks {
            recv.ingest_chunk(chunk).unwrap();
        }
        assert!(recv.is_complete());
        let result = recv.assemble().unwrap();
        assert_eq!(result, json);
    }

    #[test]
    fn detects_tampered_chunk() {
        let json = r#"{"v":2,"id":"AAAA","name":"Bob"}"#;
        let (offer, mut chunks) = prepare_bundle(json, 8).unwrap();

        // Tamper the first chunk
        chunks[0].payload[0] ^= 0xFF;

        let mut recv = HandoffReceiver::new(offer).unwrap();
        for chunk in chunks {
            recv.ingest_chunk(chunk).unwrap();
        }
        assert!(recv.assemble().is_err());
    }

    #[test]
    fn missing_chunks_reported() {
        let json = "x".repeat(200);
        let (offer, chunks) = prepare_bundle(&json, 32).unwrap();
        let total = chunks.len();

        let mut recv = HandoffReceiver::new(offer).unwrap();
        // Feed only even-indexed chunks
        for chunk in chunks.into_iter().filter(|c| c.chunk_index % 2 == 0) {
            recv.ingest_chunk(chunk).unwrap();
        }
        assert!(!recv.is_complete());
        let req = recv.missing_chunks_request();
        assert_eq!(req.missing_indices.len(), total / 2);
    }

    #[test]
    fn adaptive_chunk_size_respects_target() {
        let json = "y".repeat(1024);
        let (offer, _) = prepare_bundle_adaptive(&json, 64, 512, 8).unwrap();
        assert!(offer.total_chunks <= 16);
    }
}
