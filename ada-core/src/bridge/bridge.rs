//! Bridge protocol for censorship-resistant connectivity
//!
//! When direct P2P and relay connections fail (e.g. in heavily filtered networks),
//! ADA can use bridge nodes to route traffic through less-blocked paths.
//!
//! Bridge strategies:
//! 1. Domain fronting via HTTPS CDN (Cloudflare, Fastly)
//! 2. Meek (TLS+HTTPS camouflage)
//! 3. WebSocket tunneling (looks like normal WebSocket traffic)
//! 4. Custom obfs4-style obfuscated transports
//! 5. SMS/Bluetooth mesh fallback (local-first when internet blocked)

use crate::error::{ADAError, Result};
use crate::network::dpi::{BridgeNode, ObfuscationMode};
use crate::network::relay_reputation;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use zeroize::Zeroize;

fn validate_bridge_endpoint_field(value: &str, field_name: &str) -> Result<()> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Err(ADAError::Bridge(format!(
            "bridge {} must not be empty",
            field_name
        )));
    }
    if trimmed
        .chars()
        .any(|ch| ch.is_control() || ch.is_whitespace())
    {
        return Err(ADAError::Bridge(format!(
            "bridge {} must not contain whitespace or control characters",
            field_name
        )));
    }
    Ok(())
}

/// Connectivity probe targets used by [`detect_censorship()`].
/// Centralised here so operators only need to change one place for
/// regional deployments where these standard IPs are monitored/blocked.
const PROBE_IP_80: &str = "1.1.1.1:80"; // Cloudflare DNS — direct IP, port 80
const PROBE_IP_443: &str = "8.8.8.8:443"; // Google DNS — direct IP, port 443
const PROBE_CDN: &str = "cdn.cloudflare.com:443"; // CDN hostname — tests DNS + whitelist
const PROBE_IP2_80: &str = "8.8.4.4:80"; // Google DNS (secondary) — direct IP, port 80

fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Bridge transport protocol
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum BridgeProtocol {
    /// Direct obfs4-style obfuscated TCP
    Obfs4,
    /// WebSocket over HTTPS (hard to block)
    WebSocketTLS,
    /// Domain fronting via CDN
    DomainFronting { front_domain: String },
    /// Meek (HTTPS camouflage)
    Meek { front_url: String },
    /// Raw TCP fallback
    TcpDirect,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum BridgeSource {
    Manual,
    Manifest,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BridgeWireFormat {
    Bincode,
    Json,
}

impl BridgeWireFormat {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Bincode => "bincode",
            Self::Json => "json",
        }
    }
}

impl Default for BridgeWireFormat {
    fn default() -> Self {
        Self::Bincode
    }
}

/// A configured bridge connection
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BridgeConfig {
    pub id: String,
    pub address: String,
    pub port: u16,
    pub protocol: BridgeProtocol,
    pub fingerprint: [u8; 32],
    pub shared_secret: Option<[u8; 32]>,
    pub priority: u8,
    pub is_active: bool,
    pub hostname: Option<String>,
    pub insecure: bool,
    #[serde(default)]
    pub wire_format: BridgeWireFormat,
    pub source: BridgeSource,
}

/// Zeroize the shared secret on drop so it does not linger in heap memory.
impl Drop for BridgeConfig {
    fn drop(&mut self) {
        if let Some(ref mut s) = self.shared_secret {
            s.zeroize();
        }
    }
}

impl BridgeConfig {
    /// Parse a bridge line (like Tor bridge format)    /// Format: "obfs4 1.2.3.4:1234 fingerprint=... secret=..."
    pub fn from_bridge_line(line: &str) -> Result<Self> {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 2 {
            return Err(ADAError::Bridge("Invalid bridge line".into()));
        }
        let protocol_name = parts[0];

        let addr_port: Vec<&str> = parts[1].rsplitn(2, ':').collect();
        let address = if addr_port.len() == 2 {
            addr_port[1]
        } else {
            addr_port[0]
        };
        validate_bridge_endpoint_field(address, "address")?;
        let port = if addr_port.len() == 2 {
            addr_port[0]
                .parse::<u16>()
                .map_err(|_| ADAError::Bridge("bridge port must be a valid u16".into()))?
        } else {
            443
        };
        if port == 0 {
            return Err(ADAError::Bridge(
                "bridge port must be greater than zero".into(),
            ));
        }

        let mut fingerprint = [0u8; 32];
        let mut fingerprint_set = false;
        let mut shared_secret = None;
        let mut hostname = None;
        let mut insecure = false;
        let mut priority = 128u8;
        let mut front_domain = None;
        let mut front_url = None;
        let mut wire_format = BridgeWireFormat::Bincode;

        for kv in &parts[2..] {
            let kv: Vec<&str> = kv.splitn(2, '=').collect();
            if kv.len() == 2 {
                match kv[0] {
                    "fingerprint" | "fp" => {
                        let bytes = hex::decode(kv[1].replace(':', ""))
                            .map_err(|_| ADAError::Bridge("invalid fingerprint hex".into()))?;
                        // M-5: Reject fingerprints that are not exactly 32 bytes —
                        // silently padding/truncating would accept a mismatched identity.
                        if bytes.len() != 32 {
                            return Err(ADAError::Bridge(format!(
                                "fingerprint must be 32 bytes, got {}",
                                bytes.len()
                            )));
                        }
                        fingerprint.copy_from_slice(&bytes);
                        fingerprint_set = true;
                    }
                    "secret" | "password" => {
                        // B-4 fix: never silently truncate/pad the secret.
                        // Accept a 64-char hex string as a 32-byte raw key, or
                        // derive a 32-byte key from an arbitrary passphrase via
                        // HKDF-SHA256 so every byte of the input contributes to
                        // the output key.
                        let raw = kv[1];
                        let sec = if raw.len() == 64 && raw.chars().all(|c| c.is_ascii_hexdigit()) {
                            let decoded = hex::decode(raw).map_err(|_| {
                                ADAError::Bridge("secret: invalid hex encoding".into())
                            })?;
                            let mut arr = [0u8; 32];
                            arr.copy_from_slice(&decoded);
                            arr
                        } else {
                            // Derive a 32-byte key from the passphrase via HKDF-SHA256.
                            // This is safer than ASCII-slice because all passphrase bytes
                            // contribute to the output regardless of length.
                            use hkdf::Hkdf;
                            use sha2::Sha256;
                            let hk = Hkdf::<Sha256>::new(None, raw.as_bytes());
                            let mut arr = [0u8; 32];
                            hk.expand(b"ada/bridge-secret/v1", &mut arr).map_err(|_| {
                                ADAError::Bridge("HKDF expand failed for bridge secret".into())
                            })?;
                            arr
                        };
                        shared_secret = Some(sec);
                    }
                    "host" | "hostname" | "sni" => {
                        hostname = Some(kv[1].to_string());
                    }
                    "front" | "front_domain" => {
                        front_domain = Some(kv[1].to_string());
                    }
                    "front_url" | "url" => {
                        front_url = Some(kv[1].to_string());
                    }
                    "insecure" => {
                        insecure = matches!(kv[1], "1" | "true" | "yes");
                    }
                    "priority" => {
                        priority = kv[1].parse().unwrap_or(priority);
                    }
                    "wire" | "wire_format" => {
                        wire_format = match kv[1] {
                            "bincode" => BridgeWireFormat::Bincode,
                            "json" => BridgeWireFormat::Json,
                            other => {
                                return Err(ADAError::Bridge(format!(
                                    "unsupported bridge wire format {}",
                                    other
                                )));
                            }
                        };
                    }
                    _ => {}
                }
            }
        }

        let protocol = match protocol_name {
            "obfs4" => BridgeProtocol::Obfs4,
            "meek" => BridgeProtocol::Meek {
                front_url: front_url
                    .ok_or_else(|| ADAError::Bridge("meek bridge requires front_url".into()))?,
            },
            "websocket" | "websocket_tls" => BridgeProtocol::WebSocketTLS,
            "fronting" | "domain_fronting" => BridgeProtocol::DomainFronting {
                front_domain: front_domain.or_else(|| hostname.clone()).ok_or_else(|| {
                    ADAError::Bridge("domain-front bridge requires front_domain or hostname".into())
                })?,
            },
            "tcp" | "tcp_direct" => BridgeProtocol::TcpDirect,
            other => {
                return Err(ADAError::Bridge(format!(
                    "unsupported bridge protocol {}",
                    other
                )));
            }
        };

        if !fingerprint_set {
            return Err(ADAError::Bridge("bridge fingerprint is required".into()));
        }
        if fingerprint == [0u8; 32] {
            return Err(ADAError::Bridge(
                "bridge fingerprint must not be all zero".into(),
            ));
        }

        if let Some(hostname) = &hostname {
            validate_bridge_endpoint_field(hostname, "hostname")?;
        }
        if let BridgeProtocol::DomainFronting { front_domain } = &protocol {
            validate_bridge_endpoint_field(front_domain, "front_domain")?;
        }
        if let BridgeProtocol::Meek { front_url } = &protocol {
            if !front_url.starts_with("https://") {
                return Err(ADAError::Bridge("meek front_url must use https".into()));
            }
            validate_bridge_endpoint_field(front_url, "front_url")?;
        }

        Ok(BridgeConfig {
            id: uuid::Uuid::new_v4().to_string(),
            address: address.to_string(),
            port,
            protocol,
            fingerprint,
            shared_secret,
            priority,
            is_active: true,
            hostname,
            insecure,
            wire_format,
            source: BridgeSource::Manual,
        })
    }

    pub fn to_bridge_node(&self) -> BridgeNode {
        BridgeNode {
            address: format!("{}:{}", self.address, self.port),
            fingerprint: self.fingerprint,
            protocols: vec![format!("{:?}", self.protocol)],
            reachable: self.is_active,
        }
    }
}

/// Unified connection handle for any bridge transport.
///
/// Created by [`BridgeManager::connect_via_best_transport()`].
/// Provides a common send/recv interface regardless of underlying protocol.
pub enum BridgeConnection {
    /// Obfs4-style obfuscated TCP stream
    Obfs4(crate::bridge::obfs4::ObfsStream),
    /// WebSocket over TLS tunnel
    WebSocket(crate::bridge::ws_tunnel::WsTunnel),
    /// Domain-fronted TLS tunnel
    DomainFront(crate::bridge::domain_front::DomainFrontTunnel),
    /// Meek (HTTPS POST) session — request/response, not streaming
    Meek(crate::bridge::domain_front::MeekSession),
    /// Raw TCP fallback
    Tcp(tokio::net::TcpStream),
}

pub struct ConnectedBridge {
    pub bridge: BridgeConfig,
    pub connection: BridgeConnection,
}

impl BridgeConnection {
    /// Send data through the bridge tunnel.
    pub async fn send(&mut self, data: &[u8]) -> Result<()> {
        match self {
            Self::Obfs4(s) => s.send(data).await,
            Self::WebSocket(s) => s.send(data).await,
            Self::DomainFront(s) => s.send(data).await,
            Self::Meek(s) => {
                s.round_trip(data).await?;
                Ok(())
            }
            Self::Tcp(s) => {
                use tokio::io::AsyncWriteExt;
                s.write_all(data)
                    .await
                    .map_err(|e| ADAError::Network(e.to_string()))
            }
        }
    }

    /// Receive data from the bridge tunnel.
    pub async fn recv(&mut self) -> Result<Vec<u8>> {
        match self {
            Self::Obfs4(s) => s.recv().await,
            Self::WebSocket(s) => {
                let mut frame = Vec::with_capacity(65536);
                loop {
                    let mut buf = vec![0u8; 65536];
                    let n = s.recv(&mut buf).await;
                    if n == 0 {
                        if frame.is_empty() {
                            return Err(ADAError::Network("WebSocket tunnel closed".into()));
                        }
                        return Err(ADAError::Network(
                            "WebSocket tunnel closed mid-frame".into(),
                        ));
                    }
                    frame.extend_from_slice(&buf[..n]);
                    if !s.has_buffered_bytes() {
                        return Ok(frame);
                    }
                }
            }
            Self::DomainFront(s) => {
                let mut buf = vec![0u8; 65536];
                let n = s.recv(&mut buf).await?;
                if n == 0 {
                    return Err(ADAError::Network("DomainFront tunnel closed".into()));
                }
                buf.truncate(n);
                Ok(buf)
            }
            Self::Meek(s) => s.round_trip(&[]).await,
            Self::Tcp(s) => {
                use tokio::io::AsyncReadExt;
                let mut buf = vec![0u8; 65536];
                let n = s
                    .read(&mut buf)
                    .await
                    .map_err(|e| ADAError::Network(e.to_string()))?;
                if n == 0 {
                    return Err(ADAError::Network("TCP connection closed".into()));
                }
                buf.truncate(n);
                Ok(buf)
            }
        }
    }

    /// Which bridge protocol this connection uses.
    pub fn protocol(&self) -> BridgeProtocol {
        match self {
            Self::Obfs4(_) => BridgeProtocol::Obfs4,
            Self::WebSocket(_) => BridgeProtocol::WebSocketTLS,
            Self::DomainFront(_) => BridgeProtocol::DomainFronting {
                front_domain: String::new(),
            },
            Self::Meek(_) => BridgeProtocol::Meek {
                front_url: String::new(),
            },
            Self::Tcp(_) => BridgeProtocol::TcpDirect,
        }
    }
}

/// Bridge manager with connectivity probing and fallback
pub struct BridgeManager {
    bridges: Vec<BridgeConfig>,
    /// Which bridges are currently reachable
    reachability: HashMap<String, bool>,
    /// Repeated failures temporarily trip a simple circuit breaker.
    failure_counts: HashMap<String, u8>,
    cooldown_until_ms: HashMap<String, u64>,
    /// Current obfuscation mode
    current_mode: ObfuscationMode,
    /// Persistent delivery reputation scores for each bridge (0–100).
    /// Updated on every delivery outcome via `mark_reachable` / `mark_failed`.
    reputation_scores: HashMap<String, i64>,
    /// Unix-second timestamp of the last reputation update per bridge.
    /// Used by `apply_decay` to account for elapsed time when loading scores.
    reputation_updated_at: HashMap<String, i64>,
}

impl BridgeManager {
    pub fn new() -> Self {
        BridgeManager {
            bridges: Vec::new(),
            reachability: HashMap::new(),
            failure_counts: HashMap::new(),
            cooldown_until_ms: HashMap::new(),
            current_mode: ObfuscationMode::Auto,
            reputation_scores: HashMap::new(),
            reputation_updated_at: HashMap::new(),
        }
    }

    pub fn add_bridge(&mut self, bridge: BridgeConfig) {
        self.upsert_bridge(bridge);
    }

    pub fn add_bridge_line(&mut self, line: &str) -> Result<()> {
        let bridge = BridgeConfig::from_bridge_line(line)?;
        self.upsert_bridge(bridge);
        Ok(())
    }

    pub fn replace_manifest_bridges(&mut self, bridges: Vec<BridgeConfig>) {
        self.bridges
            .retain(|bridge| bridge.source != BridgeSource::Manifest);
        for mut bridge in bridges {
            bridge.source = BridgeSource::Manifest;
            self.upsert_bridge(bridge);
        }
    }

    pub fn bridges(&self) -> &[BridgeConfig] {
        &self.bridges
    }

    fn upsert_bridge(&mut self, bridge: BridgeConfig) {
        if let Some(existing) = self.bridges.iter_mut().find(|candidate| {
            candidate.id == bridge.id
                || (candidate.address == bridge.address
                    && candidate.port == bridge.port
                    && std::mem::discriminant(&candidate.protocol)
                        == std::mem::discriminant(&bridge.protocol))
        }) {
            *existing = bridge;
        } else {
            self.bridges.push(bridge);
        }
    }

    fn can_attempt(&self, bridge_id: &str) -> bool {
        self.cooldown_until_ms
            .get(bridge_id)
            .copied()
            .map(|until| until <= unix_now_ms())
            .unwrap_or(true)
    }

    /// Get the best available bridge
    pub fn best_bridge(&self) -> Option<&BridgeConfig> {
        self.bridges
            .iter()
            .filter(|b| b.is_active)
            .filter(|b| self.reachability.get(&b.id).copied().unwrap_or(true))
            .filter(|b| self.can_attempt(&b.id))
            .max_by_key(|b| b.priority)
    }

    /// Mark a bridge as unreachable
    pub fn mark_failed(&mut self, id: &str) {
        self.reachability.insert(id.to_string(), false);
        let failures = self.failure_counts.entry(id.to_string()).or_insert(0);
        *failures = failures.saturating_add(1);
        if *failures >= 3 {
            self.cooldown_until_ms
                .insert(id.to_string(), unix_now_ms() + 30_000);
        }
        // Update reputation score.
        let now_secs = (unix_now_ms() / 1000) as i64;
        let prev = self.reputation_score_decayed(id, now_secs);
        let updated = relay_reputation::score_after_event(prev, false);
        self.reputation_scores.insert(id.to_string(), updated);
        self.reputation_updated_at.insert(id.to_string(), now_secs);
    }

    /// Mark a bridge as reachable
    pub fn mark_reachable(&mut self, id: &str) {
        self.reachability.insert(id.to_string(), true);
        self.failure_counts.remove(id);
        self.cooldown_until_ms.remove(id);
        // Update reputation score.
        let now_secs = (unix_now_ms() / 1000) as i64;
        let prev = self.reputation_score_decayed(id, now_secs);
        let updated = relay_reputation::score_after_event(prev, true);
        self.reputation_scores.insert(id.to_string(), updated);
        self.reputation_updated_at.insert(id.to_string(), now_secs);
    }

    /// Return the current reputation score for `id`, applying time-decay.
    fn reputation_score_decayed(&self, id: &str, now_secs: i64) -> i64 {
        let score = self
            .reputation_scores
            .get(id)
            .copied()
            .unwrap_or(relay_reputation::DEFAULT_REPUTATION);
        let last_update = self
            .reputation_updated_at
            .get(id)
            .copied()
            .unwrap_or(now_secs);
        let elapsed = (now_secs - last_update).max(0);
        relay_reputation::apply_decay(score, elapsed)
    }

    /// Return the reputation score (0–100) for a bridge, for status reporting.
    pub fn reputation_score(&self, id: &str) -> i64 {
        let now_secs = (unix_now_ms() / 1000) as i64;
        self.reputation_score_decayed(id, now_secs)
    }

    /// Check if we have any working bridge
    pub fn has_working_bridge(&self) -> bool {
        self.best_bridge().is_some()
    }

    /// Get the appropriate obfuscation mode given current network conditions.
    ///
    /// For Heavy/Extreme levels the hostname/front_domain is taken from the
    /// first matching configured bridge so the mode reflects reality rather
    /// than a hardcoded placeholder that may not exist in the operator's config.
    pub fn recommended_mode(&self, censorship_level: CensorshipLevel) -> ObfuscationMode {
        match censorship_level {
            CensorshipLevel::None => ObfuscationMode::None,
            CensorshipLevel::Light => ObfuscationMode::RandomPadding { max_padding: 256 },
            CensorshipLevel::Moderate => ObfuscationMode::TrafficShaping {
                target_rate_bps: 100_000,
            },
            CensorshipLevel::Heavy => {
                // Prefer real hostname from a configured WebSocketTLS bridge; fallback to CDN default.
                let hostname = self
                    .bridges
                    .iter()
                    .find(|b| b.protocol == BridgeProtocol::WebSocketTLS)
                    .and_then(|b| b.hostname.clone())
                    .unwrap_or_else(|| "cdn.cloudflare.com".to_string());
                ObfuscationMode::WebSocketTLS { hostname }
            }
            CensorshipLevel::Extreme => {
                // Prefer real domains from a configured DomainFronting bridge; fallback to CDN default.
                let (front_domain, real_host) = self
                    .bridges
                    .iter()
                    .find_map(|b| {
                        if let BridgeProtocol::DomainFronting { front_domain } = &b.protocol {
                            let real = b.hostname.clone().unwrap_or_else(|| b.address.clone());
                            Some((front_domain.clone(), real))
                        } else {
                            None
                        }
                    })
                    .unwrap_or_else(|| {
                        ("cdn.cloudflare.com".to_string(), "ada.network".to_string())
                    });
                ObfuscationMode::DomainFronting {
                    front_domain,
                    real_host,
                }
            }
        }
    }

    pub fn set_mode(&mut self, mode: ObfuscationMode) {
        self.current_mode = mode;
    }

    pub fn current_mode(&self) -> &ObfuscationMode {
        &self.current_mode
    }

    /// Return JSON-serialisable status for each configured bridge.
    pub fn status_list(&self) -> Vec<serde_json::Value> {
        let now_secs = (unix_now_ms() / 1000) as i64;
        self.bridges
            .iter()
            .map(|b| {
                let reachable = self.reachability.get(&b.id).copied().unwrap_or(true);
                let reputation = self.reputation_score_decayed(&b.id, now_secs);
                serde_json::json!({
                    "id": b.id,
                    "address": b.address,
                    "port": b.port,
                    "protocol": format!("{:?}", b.protocol),
                    "reachable": reachable,
                    "is_active": b.is_active,
                    "priority": b.priority,
                    "hostname": b.hostname,
                    "insecure": b.insecure,
                    "wire_format": b.wire_format.as_str(),
                    "reputation": reputation,
                    "preferred": relay_reputation::is_preferred(reputation),
                    "source": match b.source {
                        BridgeSource::Manual => "manual",
                        BridgeSource::Manifest => "manifest",
                    },
                })
            })
            .collect()
    }

    /// Probe all bridges and update reachability table.
    /// All probes run concurrently — worst-case latency equals one probe timeout,
    /// not N × timeout as with sequential probing.
    pub async fn probe_bridges(&mut self) {
        let tasks: Vec<_> = self
            .bridges
            .iter()
            .map(|b| {
                let b = b.clone();
                async move {
                    let reachable = probe_bridge(&b).await;
                    (b.id.clone(), reachable)
                }
            })
            .collect();

        for (id, reachable) in futures::future::join_all(tasks).await {
            self.reachability.insert(id.clone(), reachable);
            tracing::debug!("Bridge {} reachable={}", id, reachable);
        }
    }

    /// Connect to the best available bridge, returning a usable byte tunnel.
    ///
    /// Probes all bridges first, then connects through the highest-priority
    /// reachable one. Returns a [`BridgeConnection`] that provides a unified
    /// `send()`/`recv()` interface regardless of the underlying protocol.
    pub async fn connect_via_best_transport(&mut self) -> Result<ConnectedBridge> {
        self.probe_bridges().await;
        let mut candidates = self
            .bridges
            .iter()
            .filter(|bridge| bridge.is_active)
            .filter(|bridge| self.reachability.get(&bridge.id).copied().unwrap_or(true))
            .filter(|bridge| self.can_attempt(&bridge.id))
            // GAP-3: Skip unobfuscated TcpDirect when operating under heavy/extreme
            // censorship (WebSocketTLS or DomainFronting mode). Plain TCP is trivially
            // detectable by DPI and defeats the purpose of obfuscated transport.
            .filter(|bridge| {
                if matches!(&bridge.protocol, BridgeProtocol::TcpDirect) {
                    !matches!(
                        self.current_mode,
                        ObfuscationMode::WebSocketTLS { .. }
                            | ObfuscationMode::DomainFronting { .. }
                    )
                } else {
                    true
                }
            })
            .cloned()
            .collect::<Vec<_>>();
        // GAP-9: Prefer bridges whose protocol matches current_mode.
        // A mode-matching bridge is tried before lower-priority non-matching ones
        // while still respecting operator-assigned priority within the same score tier.
        let mode_ref = &self.current_mode;
        let now_secs = (unix_now_ms() / 1000) as i64;
        candidates.sort_by(|a, b| {
            let sa = protocol_score_for_mode(&a.protocol, mode_ref);
            let sb = protocol_score_for_mode(&b.protocol, mode_ref);
            // Primary: protocol score; secondary: reputation; tertiary: operator priority.
            let rep_a = self.reputation_score_decayed(&a.id, now_secs);
            let rep_b = self.reputation_score_decayed(&b.id, now_secs);
            sb.cmp(&sa)
                .then(rep_b.cmp(&rep_a))
                .then(b.priority.cmp(&a.priority))
        });

        let mut last_error = None;
        for bridge in candidates {
            let addr = bridge.address.clone();
            let port = bridge.port;
            let protocol = bridge.protocol.clone();
            let secret = bridge.shared_secret.unwrap_or([0u8; 32]);

            let connection = match &protocol {
                BridgeProtocol::Obfs4 => {
                    crate::bridge::obfs4::ObfsStream::connect_client(&addr, port, &secret)
                        .await
                        .map(BridgeConnection::Obfs4)
                }
                BridgeProtocol::WebSocketTLS => {
                    let hostname = bridge.hostname.clone().unwrap_or_else(|| addr.clone());
                    crate::bridge::ws_tunnel::WsTunnel::connect_with_options(
                        &addr,
                        port,
                        &hostname,
                        bridge.insecure,
                    )
                    .await
                    .map(BridgeConnection::WebSocket)
                }
                BridgeProtocol::DomainFronting { front_domain } => {
                    crate::bridge::domain_front::DomainFrontTunnel::connect(
                        front_domain,
                        &addr,
                        port,
                    )
                    .await
                    .map(BridgeConnection::DomainFront)
                }
                BridgeProtocol::Meek { front_url } => {
                    crate::bridge::domain_front::MeekSession::new(front_url, &addr)
                        .map(BridgeConnection::Meek)
                }
                BridgeProtocol::TcpDirect => {
                    tokio::net::TcpStream::connect(format!("{}:{}", addr, port))
                        .await
                        .map(BridgeConnection::Tcp)
                        .map_err(|e| ADAError::Network(format!("TCP connect: {}", e)))
                }
            };

            match connection {
                Ok(connection) => {
                    self.mark_reachable(&bridge.id);
                    tracing::info!("[bridge] connected via {:?} to {}:{}", protocol, addr, port);
                    return Ok(ConnectedBridge { bridge, connection });
                }
                Err(error) => {
                    self.mark_failed(&bridge.id);
                    last_error = Some(error);
                }
            }
        }

        Err(last_error.unwrap_or_else(|| ADAError::Bridge("No reachable bridge available".into())))
    }
}

/// Score how well a bridge protocol matches the current obfuscation mode.
///
/// Used by [`BridgeManager::connect_via_best_transport`] to prefer protocol-compatible
/// bridges (GAP-9: mode enum now actually influences which bridge is tried first).
///
/// Score table:
///   3 = exact match (optimal)
///   2 = compatible (related protocol)
///   1 = acceptable (no obfuscation needed)
///   0 = mismatch (avoid, but still usable if nothing better exists)
fn protocol_score_for_mode(protocol: &BridgeProtocol, mode: &ObfuscationMode) -> u8 {
    match (protocol, mode) {
        (BridgeProtocol::DomainFronting { .. }, ObfuscationMode::DomainFronting { .. }) => 3,
        (BridgeProtocol::WebSocketTLS, ObfuscationMode::WebSocketTLS { .. }) => 3,
        (BridgeProtocol::Meek { .. }, ObfuscationMode::DomainFronting { .. }) => 2,
        (
            BridgeProtocol::Obfs4,
            ObfuscationMode::RandomPadding { .. } | ObfuscationMode::TrafficShaping { .. },
        ) => 2,
        (BridgeProtocol::TcpDirect, ObfuscationMode::None) => 1,
        _ => 0,
    }
}

/// Probe a single bridge to check reachability.
async fn probe_bridge(bridge: &BridgeConfig) -> bool {
    match &bridge.protocol {
        BridgeProtocol::WebSocketTLS => {
            crate::bridge::ws_tunnel::probe_with_options(
                &bridge.address,
                bridge.port,
                bridge.hostname.as_deref().unwrap_or(&bridge.address),
                bridge.insecure,
            )
            .await
        }
        BridgeProtocol::Obfs4 => {
            let key = bridge.shared_secret.unwrap_or([0u8; 32]);
            crate::bridge::obfs4::probe(&bridge.address, bridge.port, &key).await
        }
        BridgeProtocol::DomainFronting { front_domain } => {
            crate::bridge::domain_front::probe_domain_front(
                front_domain,
                &bridge.address,
                bridge.port,
            )
            .await
        }
        BridgeProtocol::Meek { front_url } => {
            crate::bridge::domain_front::probe_meek(front_url, &bridge.address).await
        }
        BridgeProtocol::TcpDirect => {
            tokio::net::TcpStream::connect(format!("{}:{}", bridge.address, bridge.port))
                .await
                .is_ok()
        }
    }
}

/// Estimated level of internet censorship in the current network
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum CensorshipLevel {
    /// Free internet
    None,
    /// Some ports/protocols blocked
    Light,
    /// DPI, SNI blocking, most P2P blocked
    Moderate,
    /// Aggressive DPI, whitelist-based filtering
    Heavy,
    /// Near-complete blocking (only specific domains allowed)
    Extreme,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CensorshipProbeReport {
    pub tcp80_ok: bool,
    pub tcp443_ok: bool,
    pub cdn_ok: bool,
    pub google_ok: bool,
    pub level: CensorshipLevel,
}

/// Censorship detection via real connectivity probes.
///
/// Test matrix:
///   1. TCP:80 to 1.1.1.1 → direct IP, port 80
///   2. TCP:443 to 8.8.8.8 → direct IP, port 443
///   3. TCP:443 to cdn.cloudflare.com → DNS-resolved CDN hostname
///   4. TCP:80 to 8.8.4.4 → second direct-IP probe
///
/// Decision logic (in order of pattern match):
///   All work                           → None
///   google_ok missing                  → Light  (minor/transient)
///   CDN blocked, direct IPs intact     → Light  (single CDN failure too weak for Moderate)
///   tcp443 direct-IP blocked, CDN ok   → Moderate (SNI/IP filtering, CDN still usable)
///   tcp443 + CDN both blocked          → Heavy  (aggressive port/DPI filtering)
///   All direct IPs fail + CDN works    → Extreme (whitelist — only whitelisted CDN allowed)
///   Everything blocked                 → Extreme
///   Asymmetric / partial               → Heavy  (safe upper bound)
pub async fn detect_censorship_probes() -> CensorshipProbeReport {
    use tokio::net::TcpStream;
    use tokio::time::{timeout, Duration};

    let probe_t = Duration::from_secs(4);

    // Run all 4 probes concurrently — worst-case latency is max(probe_t) = 4s,
    // not 4 × 4s = 16s as it was with sequential awaits.
    // Probe targets are defined as module-level constants (PROBE_IP_80 etc.)
    // so operators can adjust them without hunting through function bodies.
    let (tcp80_ok, tcp443_ok, cdn_ok, google_ok) = tokio::join!(
        async {
            timeout(probe_t, TcpStream::connect(PROBE_IP_80))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        },
        async {
            timeout(probe_t, TcpStream::connect(PROBE_IP_443))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        },
        async {
            timeout(probe_t, TcpStream::connect(PROBE_CDN))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        },
        async {
            timeout(probe_t, TcpStream::connect(PROBE_IP2_80))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        },
    );

    let level = match (tcp80_ok, tcp443_ok, cdn_ok, google_ok) {
        // ── Free or lightly filtered ──────────────────────────────────────────
        (true, true, true, true) => CensorshipLevel::None,
        (true, true, true, false) => CensorshipLevel::Light,

        // ── One hostname/CDN probe failed while direct IP connectivity is intact.
        //    This is too weak to claim real censorship: it can be transient DNS,
        //    hotspot interception, local firewalling, or CDN-specific issues.
        (true, true, false, _) => CensorshipLevel::Light,

        // ── Moderate: tcp80 direct-IP works, tcp443 direct-IP blocked, but CDN
        //    hostname on 443 is still reachable — classic SNI/IP filtering on
        //    port 443 without full whitelist mode (Turkey-style, or selective
        //    operator restrictions). CDN-fronted transports remain viable.
        (true, false, true, _) => CensorshipLevel::Moderate,

        // ── Heavy filtering: tcp80 reachable but both 443 paths are blocked
        (true, false, false, _) => CensorshipLevel::Heavy,

        // ── Extreme: all direct IPs blocked, only CDN DNS-resolved host allowed.
        //    Classic whitelist-based filtering — regime allows whitelisted domains
        //    (e.g. Cloudflare CDN) but blocks all direct-IP connections.
        (false, false, true, _) => CensorshipLevel::Extreme,

        // ── Extreme: nothing at all reachable
        (false, false, false, _) => CensorshipLevel::Extreme,

        // ── Any other asymmetric combination (partial/selective blocking) → Heavy
        //    (conservative upper bound rather than masking as Moderate).
        _ => CensorshipLevel::Heavy,
    };

    CensorshipProbeReport {
        tcp80_ok,
        tcp443_ok,
        cdn_ok,
        google_ok,
        level,
    }
}

pub async fn detect_censorship() -> CensorshipLevel {
    detect_censorship_probes().await.level
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bridge(id: &str, protocol: BridgeProtocol, priority: u8) -> BridgeConfig {
        BridgeConfig {
            id: id.to_string(),
            address: format!("{}.example", id),
            port: 443,
            protocol,
            fingerprint: [0x11; 32],
            shared_secret: None,
            priority,
            is_active: true,
            hostname: None,
            insecure: false,
            wire_format: BridgeWireFormat::Bincode,
            source: BridgeSource::Manual,
        }
    }

    #[test]
    fn bridge_line_parses_json_wire_format() {
        let bridge = BridgeConfig::from_bridge_line(
            "websocket ada-edge.example.workers.dev:443 fp=1111111111111111111111111111111111111111111111111111111111111111 host=ada-edge.example.workers.dev wire=json priority=220",
        )
        .expect("bridge line should parse");

        assert_eq!(bridge.address, "ada-edge.example.workers.dev");
        assert_eq!(bridge.port, 443);
        assert_eq!(
            bridge.hostname.as_deref(),
            Some("ada-edge.example.workers.dev")
        );
        assert_eq!(bridge.wire_format, BridgeWireFormat::Json);
        assert_eq!(bridge.priority, 220);
        assert!(matches!(bridge.protocol, BridgeProtocol::WebSocketTLS));
    }

    #[test]
    fn bridge_line_rejects_unknown_wire_format() {
        let err = BridgeConfig::from_bridge_line(
            "websocket edge.example:443 fp=1111111111111111111111111111111111111111111111111111111111111111 wire=cbor",
        )
        .expect_err("unknown wire format should fail");

        assert!(
            matches!(err, ADAError::Bridge(message) if message.contains("unsupported bridge wire format"))
        );
    }

    #[test]
    fn bridge_line_rejects_unknown_protocol() {
        let err = BridgeConfig::from_bridge_line(
            "mystery edge.example:443 fp=1111111111111111111111111111111111111111111111111111111111111111",
        )
        .expect_err("unknown bridge protocol should fail");

        assert!(
            matches!(err, ADAError::Bridge(message) if message.contains("unsupported bridge protocol"))
        );
    }

    #[test]
    fn bridge_line_rejects_invalid_port() {
        let err = BridgeConfig::from_bridge_line(
            "websocket edge.example:notaport fp=1111111111111111111111111111111111111111111111111111111111111111",
        )
        .expect_err("invalid bridge port should fail");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("port")));
    }

    #[test]
    fn bridge_line_requires_domain_front_field() {
        let err = BridgeConfig::from_bridge_line(
            "domain_fronting ada-origin.example:443 fp=1111111111111111111111111111111111111111111111111111111111111111",
        )
        .expect_err("domain-front bridge without front domain should fail");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("front_domain")));
    }

    #[test]
    fn bridge_line_requires_nonzero_fingerprint() {
        let missing = BridgeConfig::from_bridge_line("websocket edge.example:443")
            .expect_err("missing fingerprint should fail");
        assert!(matches!(missing, ADAError::Bridge(message) if message.contains("fingerprint")));

        let zero = BridgeConfig::from_bridge_line(
            "websocket edge.example:443 fp=0000000000000000000000000000000000000000000000000000000000000000",
        )
        .expect_err("zero fingerprint should fail");
        assert!(matches!(zero, ADAError::Bridge(message) if message.contains("fingerprint")));
    }

    #[test]
    fn recommended_mode_uses_configured_bridge_hosts() {
        let mut manager = BridgeManager::new();
        let mut websocket = bridge("ws", BridgeProtocol::WebSocketTLS, 100);
        websocket.hostname = Some("edge.example.workers.dev".into());
        manager.add_bridge(websocket);
        let mut domain_front = bridge(
            "front",
            BridgeProtocol::DomainFronting {
                front_domain: "cdn.example".into(),
            },
            90,
        );
        domain_front.hostname = Some("ada-origin.example".into());
        manager.add_bridge(domain_front);

        assert_eq!(
            manager.recommended_mode(CensorshipLevel::Heavy),
            ObfuscationMode::WebSocketTLS {
                hostname: "edge.example.workers.dev".into(),
            }
        );
        assert_eq!(
            manager.recommended_mode(CensorshipLevel::Extreme),
            ObfuscationMode::DomainFronting {
                front_domain: "cdn.example".into(),
                real_host: "ada-origin.example".into(),
            }
        );
    }

    #[test]
    fn circuit_breaker_trips_after_three_failures_and_recovers() {
        let mut manager = BridgeManager::new();
        manager.add_bridge(bridge("primary", BridgeProtocol::WebSocketTLS, 100));

        assert!(manager.can_attempt("primary"));
        manager.mark_failed("primary");
        manager.mark_failed("primary");
        assert!(manager.can_attempt("primary"));
        manager.mark_failed("primary");

        assert!(!manager.can_attempt("primary"));
        assert!(manager.best_bridge().is_none());

        manager.mark_reachable("primary");

        assert!(manager.can_attempt("primary"));
        assert_eq!(
            manager.best_bridge().map(|bridge| bridge.id.as_str()),
            Some("primary")
        );
    }

    #[test]
    fn protocol_score_prefers_mode_compatible_bridges() {
        let websocket_score = protocol_score_for_mode(
            &BridgeProtocol::WebSocketTLS,
            &ObfuscationMode::WebSocketTLS {
                hostname: "edge.example".into(),
            },
        );
        let domain_front_score = protocol_score_for_mode(
            &BridgeProtocol::DomainFronting {
                front_domain: "cdn.example".into(),
            },
            &ObfuscationMode::DomainFronting {
                front_domain: "cdn.example".into(),
                real_host: "ada.example".into(),
            },
        );
        let meek_score = protocol_score_for_mode(
            &BridgeProtocol::Meek {
                front_url: "https://cdn.example/meek".into(),
            },
            &ObfuscationMode::DomainFronting {
                front_domain: "cdn.example".into(),
                real_host: "ada.example".into(),
            },
        );
        let tcp_score = protocol_score_for_mode(&BridgeProtocol::TcpDirect, &ObfuscationMode::None);
        let mismatch_score = protocol_score_for_mode(
            &BridgeProtocol::TcpDirect,
            &ObfuscationMode::WebSocketTLS {
                hostname: "edge.example".into(),
            },
        );

        assert_eq!(websocket_score, 3);
        assert_eq!(domain_front_score, 3);
        assert_eq!(meek_score, 2);
        assert_eq!(tcp_score, 1);
        assert_eq!(mismatch_score, 0);
        assert!(websocket_score > meek_score);
        assert!(meek_score > mismatch_score);
    }
}
