//! Off-Grid Multi-Radio Mesh Engine for ADA Messenger.
//!
//! Provides compact binary framing (14-byte header), fragmentation, reassembly,
//! score-based multi-path radio routing, and DTN (Store-and-Forward) caching
//! for infrastructure-less communication (BLE Coded PHY, Wi-Fi Aware NAN, 5G Sidelink).

use bytes::{Buf, BufMut, Bytes, BytesMut};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;
use std::time::{Duration, Instant};
use thiserror::Error;

/// Compact frame header size:
/// [0]      : Version (4 bits) | FrameType (4 bits)
/// [1..5]   : Sender short ID (4 bytes Blake3)
/// [5..9]   : Recipient short ID (4 bytes, 0xFFFFFFFF for broadcast)
/// [9..11]  : Message ID (u16)
/// [11]     : Fragment Index (u8)
/// [12]     : Fragment Total (u8)
/// [13]     : TTL / Hop limit (u8)
pub const COMPACT_HEADER_SIZE: usize = 14;

/// Broadcast short ID representing all peers in radio range.
pub const BROADCAST_SHORT_ID: u32 = 0xFFFF_FFFF;

/// Default expiration time for stale fragment reassembly buffers.
pub const FRAGMENT_EXPIRY_SECS: u64 = 60;

/// Default expiration time for inactive radio routes.
pub const ROUTE_EXPIRY_SECS: u64 = 30;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum WireError {
    #[error("Packet smaller than compact header size (14 bytes)")]
    PacketTooShort,
    #[error("Unsupported protocol version: {0}")]
    InvalidVersion(u8),
    #[error("Unknown frame type: {0}")]
    UnknownFrameType(u8),
    #[error("Payload exceeds maximum fragment capacity (255 fragments)")]
    PayloadTooLarge,
    #[error("Invalid fragment parameters: index {0} >= total {1}")]
    InvalidFragmentIndex(u8, u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[repr(u8)]
pub enum RadioTransportKind {
    /// 2.4 GHz Long Range BLE 5.0+ with S=8 coding (L2CAP CoC / Extended Adv)
    BleCodedPhy = 1,
    /// Wi-Fi Aware (NAN) / Apple AWDL / Windows WFDS P2P channel
    WifiAwareNan = 2,
    /// 3GPP Rel-16/17 PC5 (Sidelink / C-V2X / ProSe) direct modem interface
    CellularSidelink = 3,
    /// Local subnet / link-local UDP multicast
    LanMulticast = 4,
}

impl RadioTransportKind {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            1 => Some(Self::BleCodedPhy),
            2 => Some(Self::WifiAwareNan),
            3 => Some(Self::CellularSidelink),
            4 => Some(Self::LanMulticast),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::BleCodedPhy => "ble_coded_phy",
            Self::WifiAwareNan => "wifi_aware_nan",
            Self::CellularSidelink => "cellular_sidelink",
            Self::LanMulticast => "lan_multicast",
        }
    }

    pub fn default_mtu(&self) -> usize {
        LinkMetrics::default_for(*self).mtu
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkMetrics {
    /// RSSI in dBm (-30..-105 dBm)
    pub rssi_dbm: i8,
    /// Round-trip latency estimate in milliseconds
    pub rtt_ms: u32,
    /// Maximum transmission unit without fragmentation
    pub mtu: usize,
    /// Estimated bandwidth in kbps
    pub bandwidth_kbps: u32,
}

impl LinkMetrics {
    pub fn new(rssi_dbm: i8, rtt_ms: u32, mtu: usize, bandwidth_kbps: u32) -> Self {
        Self {
            rssi_dbm,
            rtt_ms,
            mtu,
            bandwidth_kbps,
        }
    }

    /// Default baseline metrics for each transport kind.
    pub fn default_for(kind: RadioTransportKind) -> Self {
        match kind {
            RadioTransportKind::BleCodedPhy => Self {
                rssi_dbm: -85,
                rtt_ms: 100,
                mtu: 180,           // Safe L2CAP frame for BLE S=8
                bandwidth_kbps: 100, // Effective throughput on 125 kbps PHY
            },
            RadioTransportKind::WifiAwareNan => Self {
                rssi_dbm: -65,
                rtt_ms: 10,
                mtu: 1450,
                bandwidth_kbps: 25_000, // 25+ Mbps direct peer-to-peer
            },
            RadioTransportKind::CellularSidelink => Self {
                rssi_dbm: -75,
                rtt_ms: 15,
                mtu: 1350,
                bandwidth_kbps: 2_000,  // 2 Mbps in 5.9 GHz ITS band
            },
            RadioTransportKind::LanMulticast => Self {
                rssi_dbm: -50,
                rtt_ms: 5,
                mtu: 1400,
                bandwidth_kbps: 50_000,
            },
        }
    }

    /// Scores the route for a given payload size. Higher score = better candidate.
    ///
    /// Small payloads (e.g. text or beacons) score higher on long-range low-power BLE.
    /// Large payloads (e.g. voice chunks, attachments) prioritize wideband Wi-Fi Aware.
    pub fn score_for_payload(&self, payload_len: usize) -> i64 {
        let signal_factor = (self.rssi_dbm as i64 + 110) * 10;
        let tx_time_ms = (payload_len as i64 * 8) / (self.bandwidth_kbps.max(1) as i64);
        signal_factor - tx_time_ms - (self.rtt_ms as i64 / 2)
    }
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FrameType {
    /// Peer discovery beacon broadcasting the full 32-byte Ed25519 PubKey
    Beacon = 0x01,
    /// Cryptographic handshake (PQXDH / Noise)
    Handshake = 0x02,
    /// Data requiring link-layer confirmation (ACK)
    DataConfirm = 0x03,
    /// Unreliable data streaming (realtime voice / telemetry)
    DataNonConf = 0x04,
    /// Link-layer acknowledgement
    Ack = 0x05,
}

impl FrameType {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0x01 => Some(Self::Beacon),
            0x02 => Some(Self::Handshake),
            0x03 => Some(Self::DataConfirm),
            0x04 => Some(Self::DataNonConf),
            0x05 => Some(Self::Ack),
            _ => None,
        }
    }
}

/// 14-byte compact physical/data-link frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompactFrame {
    pub version: u8,
    pub frame_type: FrameType,
    pub sender_short_id: u32,
    pub recipient_short_id: u32,
    pub msg_id: u16,
    pub frag_index: u8,
    pub frag_total: u8,
    pub ttl: u8,
    pub payload: Bytes,
}

impl CompactFrame {
    /// Encodes the frame into a compact wire buffer.
    pub fn encode(&self) -> Bytes {
        let mut buf = BytesMut::with_capacity(COMPACT_HEADER_SIZE + self.payload.len());
        let ver_type = ((self.version & 0x0F) << 4) | (self.frame_type as u8 & 0x0F);

        buf.put_u8(ver_type);
        buf.put_u32(self.sender_short_id);
        buf.put_u32(self.recipient_short_id);
        buf.put_u16(self.msg_id);
        buf.put_u8(self.frag_index);
        buf.put_u8(self.frag_total);
        buf.put_u8(self.ttl);
        buf.put_slice(&self.payload);

        buf.freeze()
    }

    /// Decodes a compact frame from wire bytes.
    pub fn decode(mut raw: Bytes) -> Result<Self, WireError> {
        if raw.len() < COMPACT_HEADER_SIZE {
            return Err(WireError::PacketTooShort);
        }

        let ver_type = raw.get_u8();
        let version = (ver_type >> 4) & 0x0F;
        if version != 1 {
            return Err(WireError::InvalidVersion(version));
        }

        let frame_type = FrameType::from_u8(ver_type & 0x0F)
            .ok_or(WireError::UnknownFrameType(ver_type & 0x0F))?;

        let sender_short_id = raw.get_u32();
        let recipient_short_id = raw.get_u32();
        let msg_id = raw.get_u16();
        let frag_index = raw.get_u8();
        let frag_total = raw.get_u8();
        let ttl = raw.get_u8();

        if frag_total == 0 || frag_index >= frag_total {
            return Err(WireError::InvalidFragmentIndex(frag_index, frag_total));
        }

        let payload = raw;

        Ok(Self {
            version,
            frame_type,
            sender_short_id,
            recipient_short_id,
            msg_id,
            frag_index,
            frag_total,
            ttl,
            payload,
        })
    }

    /// Splits a payload into fragments bounded by `mtu`.
    pub fn fragment_payload(
        frame_type: FrameType,
        sender_id: u32,
        recipient_id: u32,
        msg_id: u16,
        payload: Bytes,
        mtu: usize,
        ttl: u8,
    ) -> Result<Vec<Self>, WireError> {
        let max_chunk = mtu.saturating_sub(COMPACT_HEADER_SIZE).max(16);
        let total_chunks = (payload.len() + max_chunk - 1) / max_chunk;
        let total_chunks = total_chunks.max(1);

        if total_chunks > u8::MAX as usize {
            return Err(WireError::PayloadTooLarge);
        }

        let hop_limit = ttl.max(1);

        if payload.is_empty() {
            return Ok(vec![Self {
                version: 1,
                frame_type,
                sender_short_id: sender_id,
                recipient_short_id: recipient_id,
                msg_id,
                frag_index: 0,
                frag_total: 1,
                ttl: hop_limit,
                payload: Bytes::new(),
            }]);
        }

        let mut frames = Vec::with_capacity(total_chunks);
        for (idx, chunk) in payload.chunks(max_chunk).enumerate() {
            frames.push(Self {
                version: 1,
                frame_type,
                sender_short_id: sender_id,
                recipient_short_id: recipient_id,
                msg_id,
                frag_index: idx as u8,
                frag_total: total_chunks as u8,
                ttl: hop_limit,
                payload: Bytes::copy_from_slice(chunk),
            });
        }

        Ok(frames)
    }

    /// Creates a discovery beacon frame holding the full 32-byte Ed25519 identity key.
    pub fn new_beacon(local_short_id: u32, local_pubkey: &[u8; 32], msg_id: u16) -> Self {
        Self {
            version: 1,
            frame_type: FrameType::Beacon,
            sender_short_id: local_short_id,
            recipient_short_id: BROADCAST_SHORT_ID,
            msg_id,
            frag_index: 0,
            frag_total: 1,
            ttl: 1,
            payload: Bytes::copy_from_slice(local_pubkey),
        }
    }

    /// Creates a link-layer acknowledgement frame for a given message.
    pub fn new_ack(local_short_id: u32, recipient_short_id: u32, msg_id: u16, ttl: u8) -> Self {
        Self {
            version: 1,
            frame_type: FrameType::Ack,
            sender_short_id: local_short_id,
            recipient_short_id,
            msg_id,
            frag_index: 0,
            frag_total: 1,
            ttl: ttl.max(1),
            payload: Bytes::new(),
        }
    }
}

/// Computes the canonical 4-byte short ID from a 32-byte Ed25519 public key via Blake3.
pub fn compute_short_id(peer_id_bytes: &[u8; 32]) -> u32 {
    let hash = blake3::hash(peer_id_bytes);
    let b = hash.as_bytes();
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

/// An active radio path discovered for a given peer.
#[derive(Debug, Clone)]
pub struct ActiveRoute {
    pub transport: RadioTransportKind,
    pub hardware_addr: String,
    pub metrics: LinkMetrics,
    pub last_seen: Instant,
}

/// DTN (Delay/Disruption Tolerant Networking) Store-and-Forward bundle.
#[derive(Debug, Clone)]
pub struct DtnBundle {
    pub msg_id: u16,
    pub recipient: [u8; 32],
    pub encrypted_payload: Bytes,
    pub created_at: Instant,
    pub ttl: Duration,
}

#[derive(Debug, Clone)]
pub struct FragmentBuffer {
    pub total: u8,
    pub chunks: BTreeMap<u8, Bytes>,
    pub created_at: Instant,
}

/// Outcome of processing an incoming compact frame.
#[derive(Debug)]
pub enum MeshProcessResult {
    /// A new or existing peer announced itself via a Beacon frame.
    BeaconDiscovered {
        peer_id: [u8; 32],
        short_id: u32,
        transport: RadioTransportKind,
        hardware_addr: String,
    },
    /// A full multi-fragment payload has completed reassembly.
    PayloadComplete {
        sender_id: Option<[u8; 32]>,
        sender_short_id: u32,
        msg_id: u16,
        payload: Bytes,
        needs_ack: bool,
    },
    /// A link-layer ACK was received for an earlier sent message.
    AckReceived {
        sender_short_id: u32,
        msg_id: u16,
    },
    /// An intermediate fragment was absorbed; waiting for remaining chunks.
    FragmentBuffered {
        sender_short_id: u32,
        msg_id: u16,
        received_chunks: usize,
        total_chunks: usize,
    },
    /// Multi-hop epidemic mesh packet forwarded to next hops.
    Forward {
        forward_frame: Bytes,
        recipient_short_id: u32,
        ttl: u8,
        jitter_ms: u64,
    },
    /// Duplicate frame dropped to suppress broadcast storm or routing loop.
    DuplicateSuppressed,
    /// The frame was ignored (addressed to another peer with TTL <= 1, or malformed).
    Ignored,
}

/// Thread-safe multi-transport radio mesh router and DTN coordinator.
pub struct MeshRadioRouter {
    local_peer_id: [u8; 32],
    local_short_id: u32,
    peer_routes: RwLock<HashMap<[u8; 32], Vec<ActiveRoute>>>,
    short_id_map: RwLock<HashMap<u32, [u8; 32]>>,
    dtn_queue: RwLock<HashMap<[u8; 32], Vec<DtnBundle>>>,
    reassembly_buf: RwLock<HashMap<(u32, u16), FragmentBuffer>>,
    seen_cache: RwLock<HashMap<(u32, u16, u8), Instant>>,
    persistent_store: RwLock<Option<Arc<crate::storage::KeyValueStore>>>,
}

impl MeshRadioRouter {
    pub fn new(local_peer_id: [u8; 32]) -> Self {
        let local_short_id = compute_short_id(&local_peer_id);
        Self {
            local_peer_id,
            local_short_id,
            peer_routes: RwLock::new(HashMap::new()),
            short_id_map: RwLock::new(HashMap::new()),
            dtn_queue: RwLock::new(HashMap::new()),
            reassembly_buf: RwLock::new(HashMap::new()),
            seen_cache: RwLock::new(HashMap::new()),
            persistent_store: RwLock::new(None),
        }
    }

    /// Attaches the persistent SQLite encrypted database for offline DTN storage.
    pub fn set_persistent_store(&self, store: Arc<crate::storage::KeyValueStore>) {
        *self.persistent_store.write() = Some(store);
    }

    pub fn local_peer_id(&self) -> &[u8; 32] {
        &self.local_peer_id
    }

    pub fn local_short_id(&self) -> u32 {
        self.local_short_id
    }

    /// Registers a peer's 4-byte short ID to their 32-byte public key.
    pub fn register_peer(&self, short_id: u32, peer_id: [u8; 32]) {
        self.short_id_map.write().insert(short_id, peer_id);
    }

    /// Updates or inserts an active route discovered from a packet or scan.
    pub fn update_route(
        &self,
        peer_id: [u8; 32],
        transport: RadioTransportKind,
        hw_addr: String,
        rssi: i8,
    ) {
        let short_id = compute_short_id(&peer_id);
        self.short_id_map.write().insert(short_id, peer_id);

        let mut metrics = LinkMetrics::default_for(transport);
        metrics.rssi_dbm = rssi;

        let mut guard = self.peer_routes.write();
        let routes = guard.entry(peer_id).or_default();

        if let Some(existing) = routes.iter_mut().find(|r| r.transport == transport) {
            existing.hardware_addr = hw_addr;
            existing.metrics = metrics;
            existing.last_seen = Instant::now();
        } else {
            routes.push(ActiveRoute {
                transport,
                hardware_addr: hw_addr,
                metrics,
                last_seen: Instant::now(),
            });
        }
    }

    /// Selects the highest-scoring live route for a given payload size.
    pub fn select_best_route(
        &self,
        recipient: &[u8; 32],
        payload_len: usize,
    ) -> Option<ActiveRoute> {
        let guard = self.peer_routes.read();
        let routes = guard.get(recipient)?;
        let now = Instant::now();

        routes
            .iter()
            .filter(|r| now.duration_since(r.last_seen) < Duration::from_secs(ROUTE_EXPIRY_SECS))
            .max_by_key(|r| r.metrics.score_for_payload(payload_len))
            .cloned()
    }

    /// Enqueues an encrypted message in the DTN Store-and-Forward queue.
    pub fn enqueue_dtn(
        &self,
        recipient: [u8; 32],
        msg_id: u16,
        encrypted_payload: Bytes,
        ttl: Duration,
    ) {
        let now_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let bundle_id = format!("{}_{}_{}", hex::encode(recipient), msg_id, now_secs);

        if let Some(store) = self.persistent_store.read().as_ref() {
            let _ = store.save_dtn_bundle(
                &bundle_id,
                &recipient,
                msg_id,
                &encrypted_payload,
                now_secs,
                ttl.as_secs(),
                1,
            );
        }

        let bundle = DtnBundle {
            msg_id,
            recipient,
            encrypted_payload,
            created_at: Instant::now(),
            ttl,
        };
        self.dtn_queue.write().entry(recipient).or_default().push(bundle);
    }

    /// Drains all unexpired DTN bundles queued for the specified peer.
    pub fn drain_dtn(&self, recipient: &[u8; 32]) -> Vec<DtnBundle> {
        let in_mem = self.dtn_queue.write().remove(recipient).unwrap_or_default();
        let now = Instant::now();
        let mut valid_bundles: Vec<DtnBundle> = in_mem
            .into_iter()
            .filter(|b| now.duration_since(b.created_at) <= b.ttl)
            .collect();

        if let Some(store) = self.persistent_store.read().as_ref() {
            if let Ok(persisted) = store.load_dtn_bundles_for_recipient(recipient) {
                let now_secs = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs();
                for p in persisted {
                    if (p.created_at_secs + p.ttl_secs) < now_secs {
                        // Purge expired bundle from persistent storage
                        let _ = store.delete_dtn_bundle(&p.bundle_id);
                    } else if !valid_bundles.iter().any(|b| b.msg_id == p.msg_id) {
                        valid_bundles.push(DtnBundle {
                            msg_id: p.msg_id,
                            recipient: p.recipient_id,
                            encrypted_payload: Bytes::from(p.payload),
                            created_at: Instant::now(),
                            ttl: Duration::from_secs(p.ttl_secs),
                        });
                    }
                }
            }
        }

        valid_bundles
    }

    /// Resolves a 4-byte short ID back to the canonical 32-byte Ed25519 identity key.
    pub fn resolve_peer_id(&self, short_id: u32) -> Option<[u8; 32]> {
        self.short_id_map.read().get(&short_id).copied()
    }

    /// Processes an incoming raw frame and advances fragmentation/DTN/multi-hop state.
    pub fn process_incoming_frame(
        &self,
        raw_bytes: Bytes,
        transport: RadioTransportKind,
        hw_addr: String,
        rssi: i8,
    ) -> Result<MeshProcessResult, WireError> {
        let frame = CompactFrame::decode(raw_bytes)?;

        // 1. Process Beacon frames (Peer discovery)
        if frame.frame_type == FrameType::Beacon {
            if frame.payload.len() == 32 {
                let mut peer_id = [0u8; 32];
                peer_id.copy_from_slice(&frame.payload);
                // Verify sender_short_id matches payload to prevent route poisoning
                if compute_short_id(&peer_id) == frame.sender_short_id {
                    self.update_route(peer_id, transport, hw_addr.clone(), rssi);

                    return Ok(MeshProcessResult::BeaconDiscovered {
                        peer_id,
                        short_id: frame.sender_short_id,
                        transport,
                        hardware_addr: hw_addr,
                    });
                }
            }
            return Ok(MeshProcessResult::Ignored);
        }

        // Loop detection / Broadcast storm mitigation via SeenCache
        {
            let key = (frame.sender_short_id, frame.msg_id, frame.frag_index);
            let mut seen = self.seen_cache.write();
            if seen.len() > 512 {
                let now = Instant::now();
                seen.retain(|_, t| now.duration_since(*t) < Duration::from_secs(120));
            }
            if let Some(seen_at) = seen.get(&key) {
                if seen_at.elapsed() < Duration::from_secs(120) {
                    return Ok(MeshProcessResult::DuplicateSuppressed);
                }
            }
            seen.insert(key, Instant::now());
        }

        // Multi-hop routing: if packet is not for us and not broadcast, forward if TTL > 1
        if frame.recipient_short_id != self.local_short_id && frame.recipient_short_id != BROADCAST_SHORT_ID {
            if frame.ttl > 1 {
                let mut forwarded = frame.clone();
                forwarded.ttl = frame.ttl.saturating_sub(1);
                let forward_frame = forwarded.encode();
                // Truly random per-node jitter (50..250ms) to prevent simultaneous broadcast collisions
                let jitter_ms = 50 + (rand::random::<u64>() % 200);
                return Ok(MeshProcessResult::Forward {
                    forward_frame,
                    recipient_short_id: frame.recipient_short_id,
                    ttl: forwarded.ttl,
                    jitter_ms,
                });
            } else {
                return Ok(MeshProcessResult::Ignored);
            }
        }

        // Update routing metrics if the sender identity is already indexed
        if let Some(peer_id) = self.resolve_peer_id(frame.sender_short_id) {
            self.update_route(peer_id, transport, hw_addr, rssi);
        }

        // 2. Process ACK frames
        if frame.frame_type == FrameType::Ack {
            return Ok(MeshProcessResult::AckReceived {
                sender_short_id: frame.sender_short_id,
                msg_id: frame.msg_id,
            });
        }

        // 3. Process Data frames (fragment reassembly)
        if frame.frame_type == FrameType::DataConfirm || frame.frame_type == FrameType::DataNonConf {
            if frame.frag_total == 0 || frame.frag_index >= frame.frag_total {
                return Ok(MeshProcessResult::Ignored);
            }
            let key = (frame.sender_short_id, frame.msg_id);
            let mut complete_bytes = None;
            let total = frame.frag_total as usize;
            let received_chunks;

            {
                let mut reassembly = self.reassembly_buf.write();
                // Purge expired entries if buffer grows
                if reassembly.len() > 128 {
                    let now = Instant::now();
                    reassembly.retain(|_, v| {
                        now.duration_since(v.created_at) < Duration::from_secs(FRAGMENT_EXPIRY_SECS)
                    });
                }

                let entry = reassembly.entry(key).or_insert_with(|| FragmentBuffer {
                    total: frame.frag_total,
                    chunks: BTreeMap::new(),
                    created_at: Instant::now(),
                });

                if entry.total != frame.frag_total {
                    return Ok(MeshProcessResult::Ignored);
                }

                entry.chunks.insert(frame.frag_index, frame.payload);
                received_chunks = entry.chunks.len();

                if received_chunks == total {
                    let mut combined = BytesMut::new();
                    for (_, chunk) in entry.chunks.iter() {
                        combined.extend_from_slice(chunk);
                    }
                    complete_bytes = Some(combined.freeze());
                }
            }

            if let Some(payload) = complete_bytes {
                self.reassembly_buf.write().remove(&key);
                let sender_id = self.resolve_peer_id(frame.sender_short_id);
                let needs_ack = frame.frame_type == FrameType::DataConfirm;

                return Ok(MeshProcessResult::PayloadComplete {
                    sender_id,
                    sender_short_id: frame.sender_short_id,
                    msg_id: frame.msg_id,
                    payload,
                    needs_ack,
                });
            }

            return Ok(MeshProcessResult::FragmentBuffered {
                sender_short_id: frame.sender_short_id,
                msg_id: frame.msg_id,
                received_chunks,
                total_chunks: total,
            });
        }

        Ok(MeshProcessResult::Ignored)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_short_id_deterministic() {
        let peer_a = [7u8; 32];
        let peer_b = [8u8; 32];
        let id_a1 = compute_short_id(&peer_a);
        let id_a2 = compute_short_id(&peer_a);
        let id_b = compute_short_id(&peer_b);

        assert_eq!(id_a1, id_a2);
        assert_ne!(id_a1, id_b);
    }

    #[test]
    fn test_compact_frame_encode_decode() {
        let frame = CompactFrame {
            version: 1,
            frame_type: FrameType::DataConfirm,
            sender_short_id: 0x12345678,
            recipient_short_id: 0x87654321,
            msg_id: 42,
            frag_index: 2,
            frag_total: 5,
            ttl: 3,
            payload: Bytes::from_static(b"ADA Offgrid Mesh"),
        };

        let encoded = frame.encode();
        assert_eq!(encoded.len(), COMPACT_HEADER_SIZE + 16);

        let decoded = CompactFrame::decode(encoded).expect("decode failed");
        assert_eq!(decoded, frame);
    }

    #[test]
    fn test_fragmentation_and_reassembly() {
        let router = MeshRadioRouter::new([1u8; 32]);
        let sender_pub = [2u8; 32];
        let sender_short = compute_short_id(&sender_pub);

        // Pre-register sender route via Beacon
        let beacon = CompactFrame::new_beacon(sender_short, &sender_pub, 1);
        let res = router
            .process_incoming_frame(
                beacon.encode(),
                RadioTransportKind::BleCodedPhy,
                "AA:BB:CC:DD:EE:FF".into(),
                -70,
            )
            .unwrap();

        match res {
            MeshProcessResult::BeaconDiscovered { peer_id, .. } => {
                assert_eq!(peer_id, sender_pub);
            }
            _ => panic!("expected BeaconDiscovered"),
        }

        // Create large payload: 450 bytes -> with BLE MTU 180 (166 payload per chunk) -> 3 fragments
        let large_payload = Bytes::from(vec![42u8; 450]);
        let fragments = CompactFrame::fragment_payload(
            FrameType::DataConfirm,
            sender_short,
            router.local_short_id(),
            100,
            large_payload.clone(),
            180,
            4,
        )
        .unwrap();

        assert_eq!(fragments.len(), 3);

        // Feed fragments out of order: 1, 0, 2
        let f1 = fragments[1].encode();
        let r1 = router
            .process_incoming_frame(f1, RadioTransportKind::BleCodedPhy, "addr".into(), -75)
            .unwrap();
        matches!(r1, MeshProcessResult::FragmentBuffered { .. });

        let f0 = fragments[0].encode();
        let r0 = router
            .process_incoming_frame(f0, RadioTransportKind::BleCodedPhy, "addr".into(), -75)
            .unwrap();
        matches!(r0, MeshProcessResult::FragmentBuffered { .. });

        let f2 = fragments[2].encode();
        let r2 = router
            .process_incoming_frame(f2, RadioTransportKind::BleCodedPhy, "addr".into(), -75)
            .unwrap();

        match r2 {
            MeshProcessResult::PayloadComplete {
                sender_id,
                msg_id,
                payload,
                needs_ack,
                ..
            } => {
                assert_eq!(sender_id, Some(sender_pub));
                assert_eq!(msg_id, 100);
                assert_eq!(payload, large_payload);
                assert!(needs_ack);
            }
            _ => panic!("expected PayloadComplete"),
        }
    }

    #[test]
    fn test_link_metrics_route_scoring() {
        let router = MeshRadioRouter::new([1u8; 32]);
        let peer = [9u8; 32];

        // Register two active routes:
        // 1. BLE Coded PHY: weak signal (-90 dBm), low bandwidth (100 kbps)
        // 2. Wi-Fi Aware: strong signal (-60 dBm), high bandwidth (25 Mbps)
        router.update_route(peer, RadioTransportKind::BleCodedPhy, "ble-mac".into(), -90);
        router.update_route(peer, RadioTransportKind::WifiAwareNan, "nan-handle".into(), -60);

        // For large payload (100 KB), Wi-Fi Aware should win
        let best_for_large = router.select_best_route(&peer, 100_000).unwrap();
        assert_eq!(best_for_large.transport, RadioTransportKind::WifiAwareNan);

        // For tiny text message (20 bytes), both are evaluated and the highest score is picked
        let best_for_small = router.select_best_route(&peer, 20).unwrap();
        assert_eq!(best_for_small.transport, RadioTransportKind::WifiAwareNan);
    }

    #[test]
    fn test_dtn_queue_drain() {
        let router = MeshRadioRouter::new([1u8; 32]);
        let peer = [5u8; 32];

        router.enqueue_dtn(peer, 1, Bytes::from_static(b"Offline Message 1"), Duration::from_secs(60));
        router.enqueue_dtn(peer, 2, Bytes::from_static(b"Offline Message 2"), Duration::from_secs(60));

        let drained = router.drain_dtn(&peer);
        assert_eq!(drained.len(), 2);
        assert_eq!(drained[0].msg_id, 1);
        assert_eq!(drained[1].msg_id, 2);

        // Queue is now empty
        assert!(router.drain_dtn(&peer).is_empty());
    }

    #[test]
    fn test_multihop_forwarding_and_ttl() {
        let node_a_pub = [10u8; 32];
        let node_b_pub = [20u8; 32];
        let node_c_pub = [30u8; 32];

        let short_a = compute_short_id(&node_a_pub);
        let short_c = compute_short_id(&node_c_pub);

        let router_b = MeshRadioRouter::new(node_b_pub);

        let frame = CompactFrame {
            version: 1,
            frame_type: FrameType::DataNonConf,
            sender_short_id: short_a,
            recipient_short_id: short_c,
            msg_id: 777,
            frag_index: 0,
            frag_total: 1,
            ttl: 3,
            payload: Bytes::from_static(b"Multi-hop Payload"),
        };

        let res = router_b
            .process_incoming_frame(
                frame.encode(),
                RadioTransportKind::BleCodedPhy,
                "mac-a".into(),
                -70,
            )
            .unwrap();

        match res {
            MeshProcessResult::Forward {
                forward_frame,
                recipient_short_id,
                ttl,
                jitter_ms,
            } => {
                assert_eq!(recipient_short_id, short_c);
                assert_eq!(ttl, 2);
                assert!(jitter_ms >= 50 && jitter_ms <= 250);

                let decoded = CompactFrame::decode(forward_frame).unwrap();
                assert_eq!(decoded.ttl, 2);
                assert_eq!(decoded.sender_short_id, short_a);
                assert_eq!(decoded.recipient_short_id, short_c);
                assert_eq!(decoded.payload, b"Multi-hop Payload"[..]);
            }
            _ => panic!("Expected MeshProcessResult::Forward"),
        }

        let dup = router_b
            .process_incoming_frame(
                frame.encode(),
                RadioTransportKind::BleCodedPhy,
                "mac-a".into(),
                -70,
            )
            .unwrap();
        assert!(matches!(dup, MeshProcessResult::DuplicateSuppressed));
    }
}
