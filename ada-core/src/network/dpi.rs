//! DPI (Deep Packet Inspection) obfuscation layer
//!
//! Provides traffic camouflage to bypass network censorship.
//! Different modes add varying levels of obfuscation at varying bandwidth cost.

use serde::{Deserialize, Serialize};

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
            ObfuscationMode::WebSocketTLS { .. } => 14, // WS frame header
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
}
