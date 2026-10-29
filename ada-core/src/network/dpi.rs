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

    /// Calculate the Shannon entropy in bits/byte (0.0 to 8.0) of any byte buffer.
    pub fn shannon_entropy(data: &[u8]) -> f64 {
        calculate_shannon_entropy(data)
    }

    /// Process an inbound packet with active probing defense, returning authentic traffic
    /// or a realistic decoy response to confound active network scanners.
    pub fn process_with_probe_defense(
        &self,
        packet: &[u8],
        decoy_server: &str,
    ) -> ProbeDefenseResult {
        let defense = DpiProbeDefense::new(self.clone(), decoy_server);
        defense.process_inbound(packet)
    }
}

/// Computes the Shannon entropy in bits per byte (0.0 to 8.0) of a byte slice.
///
/// Plaintext and ASCII text typically scores < 5.0.
/// Encrypted or pseudorandom data scores close to 8.0 (~ 7.8 - 7.99).
pub fn calculate_shannon_entropy(data: &[u8]) -> f64 {
    if data.is_empty() {
        return 0.0;
    }
    let mut counts = [0usize; 256];
    for &b in data {
        counts[b as usize] += 1;
    }
    let len_f = data.len() as f64;
    let mut entropy = 0.0;
    for &c in &counts {
        if c > 0 {
            let p = c as f64 / len_f;
            entropy -= p * p.log2();
        }
    }
    entropy
}

/// Detected protocol classification produced by DPI traffic sniffer.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum DetectedProtocol {
    /// Valid TLS 1.2 / 1.3 Record (0x14..=0x18, 0x03, 0x01..=0x03)
    TlsRecord {
        content_type: u8,
        version: (u8, u8),
        record_len: usize,
    },
    /// Plaintext HTTP request (GET, POST, HEAD, OPTIONS)
    HttpPlaintext {
        method: String,
        path: String,
    },
    /// WireGuard handshake initiation packet (type 0x01, 3 zero bytes, 148 bytes total)
    WireguardHandshake,
    /// BitTorrent P2P handshake (starts with \x13BitTorrent protocol)
    BitTorrentP2p,
    /// Raw unmasked QUIC Initial packet (0xC0..=0xFF with QUIC version)
    RawQuicInitial,
    /// High entropy (> 7.5 bits/byte) without valid TLS framing (Shadowsocks/VMess signature)
    UnstructuredHighEntropy,
    /// Structured or plain binary / unknown low-entropy payload
    UnknownPlaintext,
}

impl DetectedProtocol {
    /// Sniff protocol characteristics and heuristics from a raw packet.
    pub fn sniff(data: &[u8]) -> Self {
        if data.is_empty() {
            return DetectedProtocol::UnknownPlaintext;
        }

        // Check for TLS record: [content_type, 0x03, 0x01..=0x03, len_hi, len_lo]
        if data.len() >= 5 {
            let ct = data[0];
            let v_major = data[1];
            let v_minor = data[2];
            // Valid TLS content types: 20 (ChangeCipherSpec), 21 (Alert), 22 (Handshake), 23 (Application Data), 24 (Heartbeat)
            if (20..=24).contains(&ct) && v_major == 0x03 && (1..=3).contains(&v_minor) {
                let declared_len = u16::from_be_bytes([data[3], data[4]]) as usize;
                return DetectedProtocol::TlsRecord {
                    content_type: ct,
                    version: (v_major, v_minor),
                    record_len: declared_len,
                };
            }
        }

        // Check for WireGuard initiation (148 bytes, byte[0] == 0x01, bytes[1..4] == 0)
        if data.len() == 148
            && data[0] == 0x01
            && data[1] == 0x00
            && data[2] == 0x00
            && data[3] == 0x00
        {
            return DetectedProtocol::WireguardHandshake;
        }

        // Check for BitTorrent P2P handshake
        if data.len() >= 20 && data.starts_with(b"\x13BitTorrent protocol") {
            return DetectedProtocol::BitTorrentP2p;
        }

        // Check for HTTP plaintext
        if data.starts_with(b"GET ")
            || data.starts_with(b"POST ")
            || data.starts_with(b"HEAD ")
            || data.starts_with(b"OPTIONS ")
        {
            let line = String::from_utf8_lossy(&data[..data.len().min(128)]);
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                return DetectedProtocol::HttpPlaintext {
                    method: parts[0].to_string(),
                    path: parts[1].to_string(),
                };
            }
        }

        // Check for raw QUIC Initial packet (Long header: 0x80 bit set, version 1 or 2)
        if data.len() >= 9 && (data[0] & 0x80) != 0 {
            let version = u32::from_be_bytes([data[1], data[2], data[3], data[4]]);
            if version == 0x00000001 || version == 0x6b333e36 || version == 0x709a50c4 {
                return DetectedProtocol::RawQuicInitial;
            }
        }

        // Entropy-based detection for unstructured proxies
        let entropy = calculate_shannon_entropy(data);
        if entropy > 7.5 && data.len() >= 32 {
            DetectedProtocol::UnstructuredHighEntropy
        } else {
            DetectedProtocol::UnknownPlaintext
        }
    }
}

/// Verdict issued by the DPI censorship emulator when inspecting a packet.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum CensorVerdict {
    /// Packet passes inspection
    Pass {
        protocol: DetectedProtocol,
        entropy: f64,
    },
    /// Packet is blocked / dropped by the censor
    Block {
        reason: String,
        detected: DetectedProtocol,
        entropy: f64,
    },
    /// Censor marks endpoint as suspicious and dispatches an active probe
    TriggerActiveProbe {
        probe_type: ActiveProbeType,
        reason: String,
    },
}

/// Category of active probe dispatched by a censor middlebox (e.g. TSPU / GFW).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ActiveProbeType {
    /// Send an HTTP GET probe to test if the port hosts a real website
    HttpGetProbe,
    /// Send a TLS ClientHello probe to test if port performs standard TLS negotiation
    TlsClientHelloProbe,
    /// Send random bytes to test if the service crashes, resets, or hangs
    RandomGarbageProbe,
}

/// Policy configuration for DPI censorship middlebox emulation.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CensorPolicy {
    /// Block known VPN/P2P protocol signatures (WireGuard, BitTorrent, Raw QUIC)
    pub block_known_signatures: bool,
    /// Block high entropy (> 7.5) packets lacking standard TLS record framing (anti-Shadowsocks)
    pub block_unstructured_high_entropy: bool,
    /// Enforce standard bucket quantum lengths (flags unusual packet sizes)
    pub enforce_packet_size_quantization: bool,
    /// Minimum entropy threshold to trigger suspicion
    pub high_entropy_threshold: f64,
    /// Trigger active probing on unclassified or suspicious high-entropy traffic
    pub active_probing_enabled: bool,
}

impl Default for CensorPolicy {
    fn default() -> Self {
        Self {
            block_known_signatures: true,
            block_unstructured_high_entropy: true,
            enforce_packet_size_quantization: false,
            high_entropy_threshold: 7.5,
            active_probing_enabled: true,
        }
    }
}

/// DPI censorship emulator modeling state-of-the-art middlebox behavior
/// (TSPU / GFW / Iranian firewalls) with passive inspection, entropy scoring,
/// and active probe triggers.
#[derive(Clone, Debug, Default)]
pub struct DpiCensorEmulator {
    policy: CensorPolicy,
}

impl DpiCensorEmulator {
    /// Create emulator with a custom censorship policy.
    pub fn new(policy: CensorPolicy) -> Self {
        Self { policy }
    }

    /// Inspect an inbound or outbound packet and issue a censorship verdict.
    pub fn inspect_packet(&self, packet: &[u8]) -> CensorVerdict {
        if packet.is_empty() {
            return CensorVerdict::Pass {
                protocol: DetectedProtocol::UnknownPlaintext,
                entropy: 0.0,
            };
        }

        let entropy = calculate_shannon_entropy(packet);
        let detected = DetectedProtocol::sniff(packet);

        // 1. Signature checks
        if self.policy.block_known_signatures {
            match &detected {
                DetectedProtocol::WireguardHandshake => {
                    return CensorVerdict::Block {
                        reason: "Signature match: WireGuard Handshake blocked by DPI".into(),
                        detected,
                        entropy,
                    };
                }
                DetectedProtocol::BitTorrentP2p => {
                    return CensorVerdict::Block {
                        reason: "Signature match: BitTorrent P2P protocol blocked by DPI".into(),
                        detected,
                        entropy,
                    };
                }
                DetectedProtocol::RawQuicInitial => {
                    return CensorVerdict::Block {
                        reason: "Signature match: Raw uncamouflaged QUIC protocol blocked by DPI".into(),
                        detected,
                        entropy,
                    };
                }
                _ => {}
            }
        }

        // 2. High-entropy unstructured checks (Anti-Shadowsocks / Anti-VMess rule used by TSPU/GFW)
        if self.policy.block_unstructured_high_entropy {
            if let DetectedProtocol::UnstructuredHighEntropy = detected {
                if self.policy.active_probing_enabled {
                    return CensorVerdict::TriggerActiveProbe {
                        probe_type: ActiveProbeType::TlsClientHelloProbe,
                        reason: format!(
                            "Unstructured high entropy ({:.2} bits/byte) without TLS record header -> trigger active probe",
                            entropy
                        ),
                    };
                } else {
                    return CensorVerdict::Block {
                        reason: format!(
                            "Unstructured high entropy ({:.2} bits/byte) blocked (suspected raw proxy)",
                            entropy
                        ),
                        detected,
                        entropy,
                    };
                }
            }
        }

        // 3. TLS record validation (if it claims to be TLS)
        if let DetectedProtocol::TlsRecord { record_len, .. } = detected {
            // Check length consistency
            if packet.len() != 5 + record_len {
                return CensorVerdict::Block {
                    reason: format!(
                        "TLS record framing corrupted: declared {}, packet body {}",
                        record_len,
                        packet.len().saturating_sub(5)
                    ),
                    detected,
                    entropy,
                };
            }

            // Packet size quantization checks (if policy enforces discrete bucket sizing)
            if self.policy.enforce_packet_size_quantization {
                let valid_bucket = [517, 1029, 2053, 3077, 4101]
                    .iter()
                    .any(|&b| b == packet.len())
                    || (packet.len() > 2053 && (packet.len() - 5) % 1024 == 0);
                if !valid_bucket {
                    return CensorVerdict::Block {
                        reason: format!(
                            "Packet length {} violates discrete bucket quantum policy",
                            packet.len()
                        ),
                        detected,
                        entropy,
                    };
                }
            }

            // Valid TLS passes!
            return CensorVerdict::Pass {
                protocol: detected,
                entropy,
            };
        }

        // 4. Default: pass plain traffic (or HTTP)
        CensorVerdict::Pass {
            protocol: detected,
            entropy,
        }
    }
}

/// Result of evaluating an inbound connection or packet with active probe defense.
#[derive(Clone, Debug, PartialEq)]
pub enum ProbeDefenseResult {
    /// Inbound packet is authentic ADA DpiMorpher traffic; unmasked payload is returned.
    ValidTraffic(Vec<u8>),
    /// Inbound packet is an active probe or scanner; a decoy response should be returned.
    DecoyResponse {
        probe_detected: String,
        response_bytes: Vec<u8>,
    },
    /// Packet is malformed or invalid non-probe traffic.
    Drop {
        reason: String,
    },
}

/// Active probing defense system: deceives censor probes (GFW / TSPU active scanners)
/// by providing realistic decoy web responses (Nginx / Cloudflare) or standard TLS Alerts
/// rather than silently terminating connections or exposing proxy signatures.
#[derive(Clone, Debug)]
pub struct DpiProbeDefense {
    morpher: DpiMorpher,
    decoy_server_name: String,
}

impl DpiProbeDefense {
    /// Create defense instance with a backing morpher and server identity header.
    pub fn new(morpher: DpiMorpher, decoy_server_name: &str) -> Self {
        Self {
            morpher,
            decoy_server_name: decoy_server_name.to_string(),
        }
    }

    /// Process an inbound packet on a listening socket.
    ///
    /// If authentic DpiMorpher frame: unmasks and returns `ValidTraffic`.
    /// If active scanner probe: generates a plausible decoy response.
    pub fn process_inbound(&self, packet: &[u8]) -> ProbeDefenseResult {
        // Attempt normal DpiMorpher unwrapping
        if let Ok(unwrapped) = self.morpher.unwrap_bytes(packet) {
            return ProbeDefenseResult::ValidTraffic(unwrapped);
        }

        // Not valid DpiMorpher traffic — check if it is an active probe!
        let detected = DetectedProtocol::sniff(packet);
        match detected {
            DetectedProtocol::HttpPlaintext { method, path } => {
                let decoy_http = self.generate_http_decoy(&method, &path);
                ProbeDefenseResult::DecoyResponse {
                    probe_detected: format!("HTTP {} {}", method, path),
                    response_bytes: decoy_http,
                }
            }
            DetectedProtocol::TlsRecord { .. }
            | DetectedProtocol::UnknownPlaintext
            | DetectedProtocol::UnstructuredHighEntropy => {
                // If it looks like a TLS probe or unknown probe, return a realistic TLS Alert
                // RFC 5246 / RFC 8446: Record Type 0x15 (Alert), Version 0x03 0x03, Length 0x00 0x02, Fatal (0x02), Handshake Failure (0x28)
                let tls_alert_decoy = vec![0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28];
                ProbeDefenseResult::DecoyResponse {
                    probe_detected: "Active TLS/Binary Probe".into(),
                    response_bytes: tls_alert_decoy,
                }
            }
            _ => ProbeDefenseResult::Drop {
                reason: "Unrecognized non-probe traffic".into(),
            },
        }
    }

    /// Generates a realistic HTTP 200/404 response mimicking standard Nginx or Cloudflare web servers.
    pub fn generate_http_decoy(&self, _method: &str, path: &str) -> Vec<u8> {
        let (status_code, body) = if path == "/" || path == "/index.html" {
            (
                "200 OK",
                "<!DOCTYPE html>\n<html>\n<head><title>Welcome</title></head>\n<body>\n<h1>Welcome to the server.</h1>\n<p>System operational.</p>\n</body>\n</html>\n",
            )
        } else {
            (
                "404 Not Found",
                "<!DOCTYPE html>\n<html>\n<head><title>404 Not Found</title></head>\n<body>\n<center><h1>404 Not Found</h1></center>\n<hr><center>nginx</center>\n</body>\n</html>\n",
            )
        };

        let response = format!(
            "HTTP/1.1 {}\r\nServer: {}\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            status_code,
            self.decoy_server_name,
            body.len(),
            body
        );

        response.into_bytes()
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

    #[test]
    fn test_shannon_entropy() {
        // Plain text has low entropy (< 5.0)
        let plain = b"The quick brown fox jumps over the lazy dog repeatedly and consistently.";
        let entropy_plain = calculate_shannon_entropy(plain);
        assert!(entropy_plain < 5.0, "Plaintext entropy {} should be < 5.0", entropy_plain);

        // Pseudorandom bytes have high entropy (~ 7.99 for 16KB)
        let mut rand_buf = vec![0u8; 16384];
        OsRng.fill_bytes(&mut rand_buf);
        let entropy_rand = calculate_shannon_entropy(&rand_buf);
        assert!(entropy_rand > 7.8, "Random entropy {} should be > 7.8", entropy_rand);
    }

    #[test]
    fn test_dpi_censor_emulator_blocks_signatures() {
        let emulator = DpiCensorEmulator::new(CensorPolicy::default());

        // WireGuard Handshake (148 bytes, 0x01, 0x00, 0x00, 0x00)
        let mut wg_packet = vec![0u8; 148];
        wg_packet[0] = 0x01;
        let v1 = emulator.inspect_packet(&wg_packet);
        assert!(matches!(v1, CensorVerdict::Block { .. }));

        // BitTorrent P2P handshake
        let mut bt_packet = b"\x13BitTorrent protocol\x00\x00\x00\x00\x00\x00\x00\x00".to_vec();
        bt_packet.extend_from_slice(&[42u8; 20]);
        let v2 = emulator.inspect_packet(&bt_packet);
        assert!(matches!(v2, CensorVerdict::Block { .. }));

        // Raw QUIC Initial packet (0xC0 with version 1)
        let mut quic_packet = vec![0u8; 1200];
        quic_packet[0] = 0xC0;
        quic_packet[1] = 0x00;
        quic_packet[2] = 0x00;
        quic_packet[3] = 0x00;
        quic_packet[4] = 0x01; // QUIC v1
        let v3 = emulator.inspect_packet(&quic_packet);
        assert!(matches!(v3, CensorVerdict::Block { .. }));
    }

    #[test]
    fn test_dpi_censor_emulator_blocks_unstructured_high_entropy() {
        let emulator = DpiCensorEmulator::new(CensorPolicy {
            active_probing_enabled: false,
            ..Default::default()
        });

        // Unstructured random bytes without TLS header (Shadowsocks style)
        let mut raw_proxy_packet = vec![0u8; 512];
        OsRng.fill_bytes(&mut raw_proxy_packet);
        // Ensure byte 0 is not a TLS record
        raw_proxy_packet[0] = 0x88;

        let verdict = emulator.inspect_packet(&raw_proxy_packet);
        assert!(matches!(verdict, CensorVerdict::Block { .. }));
    }

    #[test]
    fn test_dpi_censor_emulator_passes_dpi_morpher() {
        let emulator = DpiCensorEmulator::new(CensorPolicy {
            enforce_packet_size_quantization: true,
            ..Default::default()
        });
        let morpher = DpiMorpher::new("ada-network-secret");

        let secret_msg = b"Confidential mesh message across active DPI firewalls";
        let morphed_packet = morpher.wrap_bytes(secret_msg).unwrap();

        // Check entropy of morphed packet: must be high like real encrypted TLS (> 7.5 for 512-byte bucket)
        let entropy = calculate_shannon_entropy(&morphed_packet);
        assert!(entropy > 7.5, "DpiMorpher packet entropy {} must look like TLS ciphertext (> 7.5)", entropy);

        let large_msg = vec![0x33u8; 1500];
        let large_morphed = morpher.wrap_bytes(&large_msg).unwrap();
        let large_entropy = calculate_shannon_entropy(&large_morphed);
        assert!(large_entropy > 7.8, "Large DpiMorpher packet entropy {} must exceed 7.8", large_entropy);

        // Morphed packet must pass censor inspection as a valid TLS record with discrete bucket size
        let verdict = emulator.inspect_packet(&morphed_packet);
        match verdict {
            CensorVerdict::Pass { protocol, .. } => {
                assert!(matches!(protocol, DetectedProtocol::TlsRecord { content_type: 0x17, .. }));
            }
            other => panic!("DpiMorpher packet was unexpectedly rejected by emulator: {:?}", other),
        }
    }

    #[test]
    fn test_dpi_probe_defense_valid_traffic() {
        let morpher = DpiMorpher::new("defense-passphrase");
        let defense = DpiProbeDefense::new(morpher.clone(), "nginx/1.24.0");

        let payload = b"Authentic encrypted ADA telemetry";
        let frame = morpher.wrap_bytes(payload).unwrap();

        let result = defense.process_inbound(&frame);
        assert_eq!(result, ProbeDefenseResult::ValidTraffic(payload.to_vec()));
    }

    #[test]
    fn test_dpi_probe_defense_http_decoy() {
        let morpher = DpiMorpher::new("defense-passphrase");
        let defense = DpiProbeDefense::new(morpher, "cloudflare");

        // Active scanner sends HTTP GET probe
        let http_probe = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: CensorScanner/1.0\r\n\r\n";
        let result = defense.process_inbound(http_probe);

        match result {
            ProbeDefenseResult::DecoyResponse { response_bytes, .. } => {
                let resp_str = String::from_utf8_lossy(&response_bytes);
                assert!(resp_str.starts_with("HTTP/1.1 200 OK"));
                assert!(resp_str.contains("Server: cloudflare"));
                assert!(resp_str.contains("Welcome to the server."));
            }
            other => panic!("Expected DecoyResponse for HTTP probe, got {:?}", other),
        }

        // Active scanner probes nonexistent path -> 404
        let http_probe_404 = b"GET /admin.php HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let result_404 = defense.process_inbound(http_probe_404);
        match result_404 {
            ProbeDefenseResult::DecoyResponse { response_bytes, .. } => {
                let resp_str = String::from_utf8_lossy(&response_bytes);
                assert!(resp_str.starts_with("HTTP/1.1 404 Not Found"));
            }
            other => panic!("Expected DecoyResponse for 404 HTTP probe, got {:?}", other),
        }
    }

    #[test]
    fn test_dpi_probe_defense_tls_alert_decoy() {
        let morpher = DpiMorpher::new("defense-passphrase");
        let defense = DpiProbeDefense::new(morpher, "nginx");

        // Scanner sends TLS ClientHello probe or random binary probe
        let raw_probe = [0x16, 0x03, 0x01, 0x00, 0x30, 0x01, 0x00, 0x00];
        let result = defense.process_inbound(&raw_probe);

        match result {
            ProbeDefenseResult::DecoyResponse { response_bytes, .. } => {
                // Must return standard RFC TLS Alert (Handshake Failure: 0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28)
                assert_eq!(response_bytes, vec![0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28]);
            }
            other => panic!("Expected TLS Alert decoy response, got {:?}", other),
        }
    }
}

