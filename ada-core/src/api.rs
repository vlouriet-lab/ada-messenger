//! ADA Core Public API
//!
//! ADACore is the main entry point. It owns and coordinates all subsystems.
//! Designed to be embedded in mobile apps via FFI or used as a Rust library.

use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tokio::task::JoinHandle;

use crate::{
    bridge::BridgeManager,
    bridge::{
        mailbox::{
            fresh_bridge_auth, http_ack_challenge, http_pull_challenge, http_push_challenge,
            recv_frame, register_challenge, send_frame, verify_bridge_fingerprint,
            BridgeDeliveryLane, BridgeEnvelope, BridgeFrame, BridgePushDisposition, HttpAckRequest,
            HttpAckResponse, HttpPullRequest, HttpPullResponse, HttpPushRequest, HttpPushResponse,
        },
        manifest::{BridgeManifestPayload, SignedBridgeManifest},
        BridgeConfig, BridgeProtocol,
    },
    config::{ADAConfig, ConnectionProfile},
    crypto::prekeys::PreKeyManager,
    error::{ADAError, Result},
    group::GroupManager,
    identity::{EphemeralContactKey, Identity, PeerId, PublicBundle},
    media::{
        call::{CallId, IceCandidate},
        CallManager,
    },
    messaging::{
        session::{EncryptedWire, SessionManager, WireEnvelope},
        store::{ConversationId, MessageStore},
        types::{Message, MessageKind, MessageStatus},
    },
    network::{dpi::ObfuscationMode, iroh_transport::IrohTransport, relay::RelayManager},
    storage::{IdentityStore, KeyValueStore},
    transfer::{TransferEvent, TransferManager},
    transport::{
        route_from_bridge_protocol, DeliveryClass, RouteAttempt, RouteCapabilities,
        TransportOutcome as RoutedTransportOutcome, TransportPolicy, TransportRoute,
        TransportRouter,
    },
};

const MESSAGE_TIMESTAMP_PAST_GRACE_SECS: u64 = 10 * 60;
const MESSAGE_TIMESTAMP_FUTURE_SKEW_SECS: u64 = 5 * 60;
const IROH_START_RETRY_SECS: u64 = 5;
/// After this many consecutive iroh send failures the core emits
/// `NetworkDisconnected` so the UI downgrades the connectivity indicator.
/// Number of consecutive iroh QUIC send failures before emitting a
/// `NetworkDisconnected` event.  Raised to 5 (from 3) to avoid spurious
/// disconnection events during brief WiFi↔LTE handoffs where 1-2 sends
/// may fail before `notify_network_available` re-opens the path.
const IROH_CONSECUTIVE_FAIL_THRESHOLD: u32 = 5;
/// Safety cap for concurrent background iroh warmups when many contacts are added.
const CONTACT_WARMUP_INFLIGHT_LIMIT: u32 = 16;
/// Minimum interval between automatic sync requests to the same peer.
const SYNC_REQUEST_COOLDOWN_MS: u64 = 15_000;
/// Max known IDs included in one sync request.
const SYNC_KNOWN_IDS_MAX: usize = 256;
/// Max missing messages included in one sync response.
const SYNC_MESSAGES_PER_RESPONSE_MAX: usize = 128;
/// Max window scanned locally when building sync diff.
const SYNC_MESSAGES_SCAN_WINDOW: usize = SYNC_MESSAGES_PER_RESPONSE_MAX * 4;
/// Timeout budget for quick iroh attempt before switching to bridge path.
const IROH_FAST_FAILOVER_TIMEOUT_MS: u64 = 1_500;
const BRIDGE_HTTP_MAILBOX_TIMEOUT_SECS: u64 = 15;

fn spawn_background_task<F>(task_name: &'static str, future: F)
where
    F: std::future::Future<Output = ()> + Send + 'static,
{
    if let Ok(handle) = tokio::runtime::Handle::try_current() {
        handle.spawn(future);
        return;
    }

    let thread_name = format!("ada-{}", task_name);
    let spawn_result = std::thread::Builder::new()
        .name(thread_name)
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    tracing::warn!("{} runtime init failed: {}", task_name, error);
                    return;
                }
            };
            runtime.block_on(future);
        });

    if let Err(error) = spawn_result {
        tracing::warn!("{} thread spawn failed: {}", task_name, error);
    }
}

/// Events emitted by ADACore to the UI layer
#[derive(Debug, Clone)]
pub enum ADAEvent {
    /// Connected to the P2P network
    NetworkConnected,
    /// Disconnected from P2P network
    NetworkDisconnected,
    /// A peer came online
    PeerOnline(PeerId),
    /// A peer went offline
    PeerOffline(PeerId),
    /// A new message was received
    MessageReceived(Message),
    /// A message delivery status changed
    MessageStatusChanged {
        message_id: [u8; 16],
        status: crate::messaging::types::MessageStatus,
    },
    /// A previously sent text message was edited.
    MessageEdited { target_message_id: [u8; 16] },
    /// The transport route chosen for a message changed or became known.
    MessageRouteChanged {
        message_id: [u8; 16],
        route: String,
        queue_depth: Option<u32>,
        latency_ms: Option<u64>,
    },
    /// Incoming call
    IncomingCall {
        call_id: CallId,
        from: PeerId,
        has_video: bool,
        offer_sdp: String,
        room: Option<GroupCallRoomSnapshot>,
    },
    /// ICE restart offer received for an existing call (offerer sent fresh ICE credentials)
    IceRestartOffer {
        call_id: CallId,
        peer: PeerId,
        offer_sdp: String,
    },
    /// ICE candidate received from remote peer
    IceCandidate {
        call_id: CallId,
        peer: PeerId,
        candidate: String,
        sdp_mid: Option<String>,
        sdp_mline_index: Option<u16>,
    },
    /// Call state changed
    CallStateChanged {
        call_id: CallId,
        peer: PeerId,
        has_video: bool,
        state: crate::media::call::CallState,
        answer_sdp: Option<String>,
    },
    /// File transfer event
    TransferEvent(TransferEvent),
    /// A peer's public bundle was discovered
    PeerDiscovered(PublicBundle),
    /// A group invite was received
    GroupInviteReceived {
        group_id: [u8; 16],
        group_name: String,
        from: PeerId,
    },
    /// We successfully joined a group
    GroupJoined {
        group_id: [u8; 16],
        group_name: String,
    },
    /// A contact's profile was added/updated
    ContactUpdated(PublicBundle),
    /// A peer offered a large file via Iroh Blobs.
    /// Call `ADACore::fetch_file_blob(from, hash)` to download the bytes.
    BlobAvailable {
        from: PeerId,
        file_id: [u8; 16],
        file_name: String,
        file_size: u64,
        mime_type: String,
        /// Blake3 hash to pass to `fetch_file_blob()`
        hash: [u8; 32],
    },

    // --- Local Mesh Events ---
    /// Sent when Rust needs Android to push bytes over BLE / Wi-Fi Direct to a nearby peer
    SendViaLocalMesh { peer: PeerId, payload: Vec<u8> },

    /// An error occurred
    Error(String),
}

#[derive(Clone, Debug)]
pub struct GroupCallRoomSnapshot {
    pub group_id: [u8; 16],
    pub session_id: [u8; 16],
    pub has_video: bool,
    pub participants: Vec<PeerId>,
}

#[derive(Clone, Debug)]
pub struct ActiveCallSnapshot {
    pub call_id: CallId,
    pub peer: PeerId,
    pub has_video: bool,
    pub is_outgoing: bool,
    pub state: crate::media::call::CallState,
    pub room: Option<GroupCallRoomSnapshot>,
}

#[derive(Clone, Debug)]
struct GroupCallRoomState {
    group_id: [u8; 16],
    session_id: [u8; 16],
    has_video: bool,
    participants: Vec<PeerId>,
    call_ids: std::collections::HashSet<CallId>,
}

// ─── Device snapshot (Wi-Fi desktop link sync) ────────────────────────────────

/// A full device snapshot for syncing from mobile to a secondary desktop device.
///
/// Contains identity secret material, all contact public bundles, per-peer
/// ratchet session states (as plaintext bincode — the snapshot is transmitted
/// over a local, token-authenticated network channel), and recent message
/// history.  Schema version 1.
#[derive(serde::Serialize, serde::Deserialize)]
pub struct DeviceSnapshot {
    /// Schema version — always 1.
    pub version: u8,
    /// Unix timestamp (seconds) when the snapshot was created.
    pub exported_at: u64,
    /// The mobile identity to import on the desktop.
    pub identity: crate::identity::IdentityExport,
    /// All known contact public bundles.
    pub peer_bundles: Vec<crate::identity::PublicBundle>,
    /// Per-peer double-ratchet session state (plaintext serialised bytes).
    pub ratchet_sessions: Vec<RatchetEntry>,
    /// Recent messages across all direct conversations (last 1000 per peer, blobs stripped).
    pub recent_messages: Vec<crate::storage::ChatMessage>,
    /// 32-byte shared symmetric key for the device sync channel.
    /// Generated randomly by the phone at export time and shared to the desktop
    /// inside this snapshot.  Both sides use it for AEAD-authenticated sync pushes.
    /// `None` for old snapshots (backward compat); treated as no-sync-channel.
    #[serde(default)]
    pub link_key: Option<[u8; 32]>,
}

/// One ratchet session entry inside a [`DeviceSnapshot`].
#[derive(serde::Serialize, serde::Deserialize)]
pub struct RatchetEntry {
    /// Remote peer id (base64-encoded).
    pub peer_id_b64: String,
    /// `bincode`-serialised [`crate::crypto::ratchet::RatchetState`] (plaintext).
    pub state_bytes: Vec<u8>,
}

/// Key used to store a linked (snapshot) identity override in the encrypted KV.
///
/// When the desktop is paired from a phone snapshot with
/// [`ADACore::from_snapshot_with_pattern`], the snapshot's `IdentityExport` is
/// stored under this key inside the pattern-encrypted `keys.db`.  Subsequent
/// calls to [`ADACore::from_pattern`] detect this key and use the stored
/// identity instead of re-deriving one from the pattern.
const LINKED_IDENTITY_KV_KEY: &str = "ada/linked_identity.v1";
/// KV key for the 32-byte link key shared between this device and its paired peer.
const DEVICE_LINK_KEY_KV: &str = "ada/device_link_key.v1";
/// KV key for the HTTP sync server URL of the linked device.
const LINKED_DEVICE_SYNC_URL_KV: &str = "ada/linked_device_sync_url.v1";
/// Magic prefix prepended to every sync push payload before AEAD encryption.
const SYNC_MAGIC: &[u8] = b"ADASYN\x01";
/// KV key prefix for idempotency/replay protection markers.
/// Full key: `ada/sync_seen/<message_id>`.  Value is always `b"1"`.
const SYNC_SEEN_KV_PREFIX: &str = "ada/sync_seen/";

/// The main ADA Core instance
pub struct ADACore {
    /// B-2: `pub(crate)` — external callers must use accessor methods (peer_id(),
    /// display_name(), public_bundle()). Direct access to `signing_key` is
    /// intentionally restricted to crate-internal code.
    pub(crate) identity: Arc<Identity>,
    pub(crate) config: ADAConfig,

    // Subsystems
    sessions: Arc<SessionManager>,
    messages: Arc<MessageStore>,
    groups: Arc<GroupManager>,
    call_mgr: Arc<CallManager>,
    transfer_mgr: Arc<TransferManager>,
    id_store: Arc<IdentityStore>,
    bridges: Arc<RwLock<BridgeManager>>,
    prekeys: Arc<RwLock<PreKeyManager>>,

    /// Offline relay queue: stores sealed wire bytes when the peer is unreachable.
    /// On `PeerDiscovered`, pending messages are retried automatically.
    relay_mgr: Arc<RelayManager>,
    /// Delivery tokens per peer (base64 peer_id → [u8;32] token).
    /// The token is the privacy-preserving key for the relay offline queue.
    delivery_tokens: parking_lot::RwLock<std::collections::HashMap<String, [u8; 32]>>,
    /// Ephemeral per-contact X25519 IK aliases (ephemeral_pub → ConversationId).
    /// Used by incognito chats to prevent cross-contact session correlation.
    ephemeral_aliases: parking_lot::RwLock<std::collections::HashMap<[u8; 32], ConversationId>>,

    /// WebRTC UDP Proxies for routing calls through the ADA core network wrapper
    media_proxies: parking_lot::RwLock<
        std::collections::HashMap<PeerId, Arc<crate::media::webrtc_proxy::WebRtcProxy>>,
    >,

    /// Set of actively connected local Mesh peers
    active_mesh_peers: parking_lot::RwLock<std::collections::HashSet<PeerId>>,

    /// Group-call room registry keyed by shared room/session id.
    group_call_rooms_by_session:
        parking_lot::RwLock<std::collections::HashMap<[u8; 16], GroupCallRoomState>>,
    /// Reverse lookup from pairwise call-id to group room/session id.
    group_call_session_by_call: parking_lot::RwLock<std::collections::HashMap<CallId, [u8; 16]>>,

    /// iroh QUIC endpoint for all P2P communication — DMs, group fan-out,
    /// blob transfers.  Uses n0's public relay infrastructure on port 443
    /// (stealth HTTPS) with automatic hole-punching for direct P2P.
    iroh: RwLock<Option<Arc<IrohTransport>>>,

    // Event channel to UI
    event_tx: mpsc::Sender<ADAEvent>,
    /// B-2: `pub(crate)` — external callers use `take_events()` instead of
    /// accessing the receiver directly to avoid breaking the single-consumer MPSC.
    pub(crate) event_rx: RwLock<Option<mpsc::Receiver<ADAEvent>>>,
    /// Internal TransferManager event receiver — consumed once in start() to
    /// bridge TransferEvent → ADAEvent::TransferEvent on the main channel.
    transfer_rx: RwLock<Option<mpsc::Receiver<crate::transfer::TransferEvent>>>,

    // Background tasks
    tasks: RwLock<Vec<JoinHandle<()>>>,

    /// Is the core running?
    running: Arc<std::sync::atomic::AtomicBool>,
    /// Relay-only routing policy.
    ///
    /// Public iroh 0.31 does not expose a stable, production-ready switch that
    /// lets ADA prove a live censorship-safe route on its own. When
    /// `relay_only` is enabled, ADA disables live outgoing iroh for unicast and
    /// allows only bridge, mailbox, or local offline-queue routes.
    ///
    /// Initialised from `config.network.relay_only`; toggled at runtime via
    /// `set_relay_only()`.
    relay_only: std::sync::atomic::AtomicBool,
    /// Runtime transport profile. Initialised from `config.network.connection_profile`
    /// and may be updated by mobile settings without rebuilding the core.
    connection_profile: parking_lot::RwLock<ConnectionProfile>,
    /// Last transport outcome exposed to bridge status / UI.
    last_transport_outcome: parking_lot::RwLock<Option<LastTransportOutcome>>,
    /// Peers currently considered online (have a cached iroh QUIC connection).
    /// Updated every maintenance cycle; changes emit PeerOnline/PeerOffline events.
    online_peers: parking_lot::RwLock<std::collections::HashSet<[u8; 32]>>,
    /// Last verified bridge manifest currently applied to runtime bridge config.
    bridge_manifest: parking_lot::RwLock<Option<BridgeManifestPayload>>,
    /// Origin of the currently applied bridge manifest: cache, bootstrap, etc.
    bridge_manifest_source: parking_lot::RwLock<Option<String>>,
    /// Whether the background bridge listener currently has a live session.
    bridge_listener_connected: std::sync::atomic::AtomicBool,
    /// Last active bridge listener route.
    bridge_listener_route: parking_lot::RwLock<Option<String>>,
    /// Approximate queued mailbox depth reported by the bridge backend.
    bridge_mailbox_depth: std::sync::atomic::AtomicU32,
    /// High-water mark for bridge mailbox depth observed during runtime.
    bridge_mailbox_depth_high_watermark: std::sync::atomic::AtomicU32,
    /// Per-route transport outcome counters for operational telemetry.
    transport_route_totals: parking_lot::RwLock<std::collections::BTreeMap<String, u64>>,
    /// Total successful transport outcomes.
    transport_success_total: std::sync::atomic::AtomicU64,
    /// Total failed transport outcomes.
    transport_failure_total: std::sync::atomic::AtomicU64,
    /// Sum of observed transport latencies for average-latency telemetry.
    transport_latency_total_ms: std::sync::atomic::AtomicU64,
    /// Number of transport outcomes that carried a latency sample.
    transport_latency_samples: std::sync::atomic::AtomicU64,
    /// Start timestamp for the active recovery window (0 means healthy).
    connection_recovering_since_ms: std::sync::atomic::AtomicU64,
    /// Total accumulated connection recovery time in milliseconds.
    connection_recovery_total_ms: std::sync::atomic::AtomicU64,
    /// Number of completed recovery windows.
    connection_recovery_events_total: std::sync::atomic::AtomicU64,
    /// Number of detected route transitions between consecutive outcomes.
    connection_route_flaps_total: std::sync::atomic::AtomicU64,
    /// Cases where iroh looked available but bridge fallback was needed immediately.
    connection_false_online_detected_total: std::sync::atomic::AtomicU64,
    /// Last observed backlog size when starting fast-resync after reconnect.
    connection_resync_backlog_count: std::sync::atomic::AtomicU64,
    /// Current connection health state used by the self-healing supervisor.
    connection_health_state: std::sync::atomic::AtomicU8,
    /// Number of transitions in the connection health state machine.
    connection_state_transitions_total: std::sync::atomic::AtomicU64,
    /// Consecutive iroh delivery failures.  When this counter exceeds
    /// [IROH_CONSECUTIVE_FAIL_THRESHOLD] a `NetworkDisconnected` event is
    /// emitted so the UI can downgrade the connection indicator.  Resets on
    /// any successful iroh send.
    iroh_consecutive_failures: std::sync::atomic::AtomicU32,
    /// Atomic flag mirroring whether `self.iroh` contains `Some`.
    /// Used by FFI `get_bridge_status_json` to avoid async RwLock reads
    /// inside `block_on()`, which can return stale `false` when the tokio
    /// write happened on a different worker thread.
    iroh_started: std::sync::atomic::AtomicBool,
    /// Number of background iroh warmup tasks currently running.
    contact_warmup_inflight: Arc<std::sync::atomic::AtomicU32>,
    /// Last sync-request timestamp per peer (ms since epoch).
    sync_last_request_ms: parking_lot::RwLock<std::collections::HashMap<[u8; 32], u64>>,
    /// Optional sync cursor per peer for paginated backlog catch-up.
    sync_peer_cursor_before_ts:
        parking_lot::RwLock<std::collections::HashMap<[u8; 32], Option<u64>>>,
    /// Number of sync rounds initiated or responded to.
    sync_rounds_total: std::sync::atomic::AtomicU64,
    /// Number of missing messages applied from sync responses.
    sync_messages_applied_total: std::sync::atomic::AtomicU64,
    /// Number of sync messages skipped because they were already present.
    sync_duplicates_skipped_total: std::sync::atomic::AtomicU64,
    /// Timestamp (ms since epoch) of the last time the bridge-listener "connect
    /// skipped" message was logged.  Used to throttle the repeated log line that
    /// otherwise fires every 5 s when no bridge is reachable.
    bridge_skip_last_logged_ms: std::sync::atomic::AtomicU64,
    /// Is the app in the background (battery optimization mode)?
    is_background: std::sync::atomic::AtomicBool,

    // ── Call observability counters ────────────────────────────────────────
    /// Number of outgoing calls initiated (call_audio / call_video).
    calls_initiated_total: std::sync::atomic::AtomicU64,
    /// Number of incoming call invites successfully registered.
    calls_received_total: std::sync::atomic::AtomicU64,
    /// Number of calls that reached the Active (connected) state.
    calls_connected_total: std::sync::atomic::AtomicU64,
    /// Number of calls that ended normally (hangup or remote hangup).
    calls_ended_total: std::sync::atomic::AtomicU64,
    /// Number of calls that ended with an error / timeout.
    calls_failed_total: std::sync::atomic::AtomicU64,
    /// Number of ICE restart offers received.
    ice_restart_total: std::sync::atomic::AtomicU64,
    /// Number of call-signaling messages that exhausted all delivery retries.
    call_signaling_failures_total: std::sync::atomic::AtomicU64,
}

#[derive(Debug, Clone)]
struct LastTransportOutcome {
    message_id: [u8; 16],
    route: String,
    queue_depth: Option<u32>,
    latency_ms: Option<u64>,
    updated_at_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ConnectionHealthState {
    Healthy,
    Degraded,
    Recovering,
}

impl ConnectionHealthState {
    fn to_u8(self) -> u8 {
        match self {
            Self::Healthy => 0,
            Self::Degraded => 1,
            Self::Recovering => 2,
        }
    }

    fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::Degraded,
            2 => Self::Recovering,
            _ => Self::Healthy,
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Healthy => "healthy",
            Self::Degraded => "degraded",
            Self::Recovering => "recovering",
        }
    }
}

#[derive(Debug, Clone)]
struct RuntimeCensorshipEvidence {
    iroh_ready: bool,
    online_iroh_peers: usize,
    relay_only: bool,
    bridge_listener_connected: bool,
    realtime_calls: bool,
    large_attachments: bool,
    last_successful_live_route: Option<String>,
}

fn reconcile_censorship_level(
    probe_level: crate::bridge::bridge::CensorshipLevel,
    evidence: &RuntimeCensorshipEvidence,
) -> crate::bridge::bridge::CensorshipLevel {
    use crate::bridge::bridge::CensorshipLevel;

    let iroh_live_working = evidence.iroh_ready
        && !evidence.relay_only
        && (evidence.online_iroh_peers > 0
            || matches!(
                evidence.last_successful_live_route.as_deref(),
                Some("iroh_live")
            ));

    // If the actual runtime proves that iroh is alive, peers are reachable,
    // and realtime calls + large attachments remain available, treat the
    // network as effectively uncensored for ADA's purposes. This avoids false
    // positives from a single failed CDN/IP probe on otherwise healthy networks.
    if iroh_live_working && evidence.realtime_calls && evidence.large_attachments {
        return CensorshipLevel::None;
    }

    // Partial runtime evidence should soften a raw Moderate/Heavy probe result.
    if iroh_live_working
        && (evidence.realtime_calls || evidence.large_attachments)
        && matches!(
            probe_level,
            CensorshipLevel::Moderate | CensorshipLevel::Heavy
        )
    {
        return CensorshipLevel::Light;
    }

    probe_level
}

/// Return current Unix timestamp in seconds (i64 for SQLite compatibility).
#[inline]
fn unix_now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

#[inline]
fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn paginate_sync_candidates(
    mut candidates: Vec<Message>,
    limit: usize,
) -> (Vec<Message>, bool, Option<u64>) {
    candidates.sort_by_key(|m| m.timestamp);

    let start = candidates.len().saturating_sub(limit);
    let has_more = start > 0;
    let page = candidates[start..].to_vec();
    let next_cursor_before_ts = if has_more {
        page.first().map(|m| m.timestamp)
    } else {
        None
    };
    (page, has_more, next_cursor_before_ts)
}

fn route_implies_iroh_peer_online(route: &TransportRoute) -> bool {
    matches!(route, TransportRoute::IrohLive)
}

impl ADACore {
    /// Create a new ADACore with a freshly generated identity
    pub async fn new(config: ADAConfig, display_name: &str) -> Result<Arc<Self>> {
        let identity = Arc::new(Identity::generate(display_name));
        Self::with_identity(config, identity).await
    }

    /// Create ADACore with an existing identity loaded from storage
    pub async fn load(config: ADAConfig, passphrase: &[u8]) -> Result<Arc<Self>> {
        let kv = Arc::new(KeyValueStore::open(&format!(
            "{}/keys.db",
            config.storage.data_dir
        ))?);
        let id_store = Arc::new(IdentityStore::new(kv));
        let identity = Arc::new(id_store.load_identity(passphrase)?);
        Self::with_identity(config, identity).await
    }

    /// Public version of `with_identity` used by FFI pattern-auth path.
    pub async fn with_identity_pub(
        config: ADAConfig,
        identity: Arc<Identity>,
    ) -> Result<Arc<Self>> {
        Self::with_identity(config, identity).await
    }

    async fn with_identity(config: ADAConfig, identity: Arc<Identity>) -> Result<Arc<Self>> {
        let kv = Arc::new(KeyValueStore::open(&format!(
            "{}/keys.db",
            config.storage.data_dir
        ))?);
        Self::with_identity_and_kv(config, identity, kv, None).await
    }

    /// Create ADACore from a visual pattern.
    ///
    /// Runs a single Argon2id pass to derive the identity **and** the 32-byte
    /// SQLCipher key, then opens the encrypted database before constructing
    /// the core.  When the `sqlcipher` feature is not enabled the db_key is
    /// derived but ignored and the database is opened unencrypted.
    pub async fn from_pattern(
        config: ADAConfig,
        pattern: &crate::pattern_auth::PatternKey,
        display_name: &str,
    ) -> Result<Arc<Self>> {
        let keys = crate::pattern_auth::derive_all_from_pattern(
            pattern,
            display_name,
            &config.storage.data_dir,
        )?;
        let kv = Arc::new(KeyValueStore::open_encrypted(
            format!("{}/keys.db", config.storage.data_dir),
            &keys.db_key,
        )?);
        // If this account was created from a phone snapshot (`from_snapshot_with_pattern`),
        // the identity is stored in the encrypted KV rather than derived from the pattern.
        let identity = match kv.get_json::<crate::identity::IdentityExport>(LINKED_IDENTITY_KV_KEY) {
            Ok(Some(linked_export)) => {
                tracing::info!("[startup] from_pattern: using linked (snapshot) identity");
                Arc::new(Identity::import_secret(linked_export)?)
            }
            _ => Arc::new(keys.identity),
        };
        Self::with_identity_and_kv(config, identity, kv, Some(&keys.db_key)).await
    }

    /// Create a new [`ADACore`] from a phone snapshot, encrypted with a user pattern.
    ///
    /// - Derives the database key from `pattern` (same Argon2id as [`from_pattern`]).
    /// - Stores the snapshot identity in the encrypted KV so that subsequent
    ///   [`from_pattern`] logins use the snapshot peer-id, not the pattern-derived one.
    /// - Imports all contacts, ratchet sessions, and recent messages from the snapshot.
    pub async fn from_snapshot_with_pattern(
        config: ADAConfig,
        snapshot_json: &str,
        pattern: &crate::pattern_auth::PatternKey,
    ) -> Result<Arc<Self>> {
        let snapshot: DeviceSnapshot =
            serde_json::from_str(snapshot_json).map_err(ADAError::Json)?;
        if snapshot.version != 1 {
            return Err(ADAError::Unknown(format!(
                "unsupported snapshot version: {}",
                snapshot.version
            )));
        }

        let display_name = snapshot.identity.display_name.clone();

        // Derive the same database encryption key as from_pattern would.
        let keys = crate::pattern_auth::derive_all_from_pattern(
            pattern,
            &display_name,
            &config.storage.data_dir,
        )?;
        let kv = Arc::new(KeyValueStore::open_encrypted(
            format!("{}/keys.db", config.storage.data_dir),
            &keys.db_key,
        )?);

        // Save the snapshot identity so that from_pattern re-uses it on future logins.
        kv.set_json(LINKED_IDENTITY_KV_KEY, &snapshot.identity)
            .map_err(|e| ADAError::Storage(format!("save linked identity: {e}")))?;

        // Persist the link key from the snapshot so that the desktop sync server
        // can authenticate incoming push messages from the phone.
        if let Some(link_key) = &snapshot.link_key {
            let _ = kv.set(DEVICE_LINK_KEY_KV, link_key.to_vec());
            tracing::info!("[snapshot] link_key stored for sync channel");
        }

        // Pre-populate storage BEFORE constructing the core, so that
        // with_identity_and_kv can reload everything on startup.
        {
            let id_store = crate::storage::IdentityStore::new(kv.clone());
            for bundle in &snapshot.peer_bundles {
                if let Err(e) = id_store.save_peer_bundle(bundle) {
                    tracing::warn!("[snapshot] failed to save bundle for {}: {e}", bundle.peer_id);
                }
            }
            for entry in &snapshot.ratchet_sessions {
                match PeerId::from_base64(&entry.peer_id_b64) {
                    Ok(peer_id) => {
                        match bincode::deserialize::<crate::crypto::ratchet::RatchetState>(
                            &entry.state_bytes,
                        ) {
                            Ok(state) => {
                                if let Err(e) = id_store.save_ratchet_state(&peer_id, &state) {
                                    tracing::warn!(
                                        "[snapshot] failed to save ratchet for {}: {e}",
                                        entry.peer_id_b64
                                    );
                                }
                            }
                            Err(e) => tracing::warn!(
                                "[snapshot] ratchet deserialize failed for {}: {e}",
                                entry.peer_id_b64
                            ),
                        }
                    }
                    Err(e) => tracing::warn!(
                        "[snapshot] invalid peer_id_b64 '{}': {e}",
                        entry.peer_id_b64
                    ),
                }
            }
            for msg in &snapshot.recent_messages {
                if let Err(e) = kv.upsert_chat_message(msg) {
                    tracing::warn!("[snapshot] failed to upsert message {}: {e}", msg.message_id);
                }
            }
        }

        let identity = Arc::new(Identity::import_secret(snapshot.identity)?);
        let peer_id = identity.peer_id.clone();
        let core =
            Self::with_identity_and_kv(config, identity, kv, Some(&keys.db_key)).await?;

        tracing::info!(
            "[snapshot] created desktop core from snapshot: peer_id={}",
            peer_id
        );
        Ok(core)
    }

    async fn with_identity_and_kv(
        config: ADAConfig,
        identity: Arc<Identity>,
        kv: Arc<KeyValueStore>,
        // R1: 32-byte key for messages.db SQLCipher encryption.
        // None for code paths without pattern-derived keys (legacy / test paths).
        msg_db_key: Option<&[u8; 32]>,
    ) -> Result<Arc<Self>> {
        let (event_tx, event_rx) = mpsc::channel(4096);
        let id_store = Arc::new(IdentityStore::new(kv));

        let sessions = Arc::new(SessionManager::new_with_storage(
            identity.clone(),
            id_store.clone(),
        ));
        // R1: Open messages.db with SQLCipher when a key is available.
        let messages = Arc::new(match msg_db_key {
            Some(key) => {
                MessageStore::open_encrypted(format!("{}/messages", config.storage.data_dir), key)?
            }
            None => MessageStore::open(format!("{}/messages", config.storage.data_dir))?,
        });

        // Restore known contacts into the in-memory conversation list and reload
        // persisted ratchet sessions so they survive process restarts.
        for bundle in id_store.list_peer_bundles() {
            let conv_id = crate::messaging::store::ConversationId::Direct(bundle.peer_id.clone());
            messages.upsert_conversation(&conv_id, &bundle.display_name);
            // Reload persisted ratchet state for this peer (if any).
            match id_store.load_ratchet_state(&bundle.peer_id) {
                Ok(Some(state)) => {
                    sessions.load_session(&bundle.peer_id, state);
                    tracing::debug!("[startup] restored ratchet session for {}", bundle.peer_id);
                }
                Ok(None) => {}
                Err(e) => {
                    tracing::warn!(
                        "[startup] could not load ratchet session for {}: {}",
                        bundle.peer_id,
                        e
                    );
                }
            }
        }

        // Also restore ratchet sessions for peers who sent us messages but whose
        // bundle was never saved (user never scanned their QR code). Without this,
        // replies to such peers fail after every restart with
        // "No session and no bundle for X3DH".
        {
            let bundle_peer_ids: std::collections::HashSet<String> = id_store
                .list_peer_bundles()
                .iter()
                .map(|b| b.peer_id.to_base64())
                .collect();
            for peer_b64 in id_store.list_ratchet_peer_ids() {
                if bundle_peer_ids.contains(&peer_b64) {
                    continue; // already loaded above
                }
                let peer = match crate::identity::PeerId::from_base64(&peer_b64) {
                    Ok(p) => p,
                    Err(_) => continue,
                };
                match id_store.load_ratchet_state(&peer) {
                    Ok(Some(state)) => {
                        sessions.load_session(&peer, state);
                        tracing::debug!(
                            "[startup] restored orphan ratchet session for {}",
                            peer_b64
                        );
                    }
                    Ok(None) | Err(_) => {}
                }
            }
        }

        let groups = Arc::new(GroupManager::new(identity.clone()));

        let stun = config.network.stun_servers.clone();
        let turn = config
            .network
            .turn_servers
            .iter()
            .map(|t| crate::media::call::TurnServer {
                url: t.url.clone(),
                username: t.username.clone(),
                credential: t.credential.clone(),
            })
            .collect();
        let (call_mgr, _call_rx) = CallManager::new(stun, turn);

        let (transfer_mgr, transfer_rx) = TransferManager::new();

        let prekeys = Arc::new(RwLock::new(PreKeyManager::new(&identity.signing_key)));

        let bridges = Arc::new(RwLock::new({
            let mut mgr = BridgeManager::new();
            for bridge in &config.bridge.bridges {
                let _ = mgr.add_bridge_line(bridge);
            }
            // Load builtin bootstrap bridges as a last-resort fallback.
            // These are compiled into the binary so the app has at least one
            // path to initial connectivity on first install under whitelist censorship,
            // before the signed manifest can be fetched.  Set priority=1 (lowest) so
            // operator-configured and manifest-resolved bridges always take precedence.
            if mgr.bridges().is_empty() {
                for &line in crate::config::BUILTIN_BOOTSTRAP_BRIDGES {
                    match crate::bridge::bridge::BridgeConfig::from_bridge_line(line) {
                        Ok(mut b) => {
                            b.priority = 1; // lowest — always prefer manifest/manual bridges
                            mgr.add_bridge(b);
                        }
                        Err(e) => tracing::debug!("[bootstrap] builtin bridge parse failed: {}", e),
                    }
                }
                if !crate::config::BUILTIN_BOOTSTRAP_BRIDGES.is_empty() {
                    tracing::info!(
                        "[bootstrap] {} builtin bridge(s) loaded as last-resort fallback",
                        crate::config::BUILTIN_BOOTSTRAP_BRIDGES.len()
                    );
                }
            }
            mgr
        }));

        let relay_only_init = config.network.relay_only;
        let connection_profile_init = config.network.connection_profile;
        let data_dir = config.storage.data_dir.clone();
        let core = Arc::new(ADACore {
            identity,
            config,
            sessions,
            messages,
            groups,
            call_mgr: Arc::new(call_mgr),
            transfer_mgr: Arc::new(transfer_mgr),
            id_store,
            bridges,
            prekeys,
            relay_mgr: Arc::new(RelayManager::with_storage(std::path::PathBuf::from(
                format!("{}/offline_queue.bin", data_dir),
            ))),
            delivery_tokens: parking_lot::RwLock::new(std::collections::HashMap::new()),
            ephemeral_aliases: parking_lot::RwLock::new(std::collections::HashMap::new()),
            media_proxies: parking_lot::RwLock::new(std::collections::HashMap::new()),
            active_mesh_peers: parking_lot::RwLock::new(std::collections::HashSet::new()),
            group_call_rooms_by_session: parking_lot::RwLock::new(std::collections::HashMap::new()),
            group_call_session_by_call: parking_lot::RwLock::new(std::collections::HashMap::new()),
            iroh: RwLock::new(None),
            event_tx,
            event_rx: RwLock::new(Some(event_rx)),
            transfer_rx: RwLock::new(Some(transfer_rx)),
            tasks: RwLock::new(Vec::new()),
            running: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            relay_only: std::sync::atomic::AtomicBool::new(relay_only_init),
            connection_profile: parking_lot::RwLock::new(connection_profile_init),
            last_transport_outcome: parking_lot::RwLock::new(None),
            online_peers: parking_lot::RwLock::new(std::collections::HashSet::new()),
            bridge_manifest: parking_lot::RwLock::new(None),
            bridge_manifest_source: parking_lot::RwLock::new(None),
            bridge_listener_connected: std::sync::atomic::AtomicBool::new(false),
            bridge_listener_route: parking_lot::RwLock::new(None),
            bridge_mailbox_depth: std::sync::atomic::AtomicU32::new(0),
            bridge_mailbox_depth_high_watermark: std::sync::atomic::AtomicU32::new(0),
            transport_route_totals: parking_lot::RwLock::new(std::collections::BTreeMap::new()),
            transport_success_total: std::sync::atomic::AtomicU64::new(0),
            transport_failure_total: std::sync::atomic::AtomicU64::new(0),
            transport_latency_total_ms: std::sync::atomic::AtomicU64::new(0),
            transport_latency_samples: std::sync::atomic::AtomicU64::new(0),
            connection_recovering_since_ms: std::sync::atomic::AtomicU64::new(0),
            connection_recovery_total_ms: std::sync::atomic::AtomicU64::new(0),
            connection_recovery_events_total: std::sync::atomic::AtomicU64::new(0),
            connection_route_flaps_total: std::sync::atomic::AtomicU64::new(0),
            connection_false_online_detected_total: std::sync::atomic::AtomicU64::new(0),
            connection_resync_backlog_count: std::sync::atomic::AtomicU64::new(0),
            connection_health_state: std::sync::atomic::AtomicU8::new(
                ConnectionHealthState::Healthy.to_u8(),
            ),
            connection_state_transitions_total: std::sync::atomic::AtomicU64::new(0),
            iroh_consecutive_failures: std::sync::atomic::AtomicU32::new(0),
            iroh_started: std::sync::atomic::AtomicBool::new(false),
            contact_warmup_inflight: Arc::new(std::sync::atomic::AtomicU32::new(0)),
            sync_last_request_ms: parking_lot::RwLock::new(std::collections::HashMap::new()),
            sync_peer_cursor_before_ts: parking_lot::RwLock::new(std::collections::HashMap::new()),
            sync_rounds_total: std::sync::atomic::AtomicU64::new(0),
            sync_messages_applied_total: std::sync::atomic::AtomicU64::new(0),
            sync_duplicates_skipped_total: std::sync::atomic::AtomicU64::new(0),
            bridge_skip_last_logged_ms: std::sync::atomic::AtomicU64::new(0),
            is_background: std::sync::atomic::AtomicBool::new(false),
            calls_initiated_total: std::sync::atomic::AtomicU64::new(0),
            calls_received_total: std::sync::atomic::AtomicU64::new(0),
            calls_connected_total: std::sync::atomic::AtomicU64::new(0),
            calls_ended_total: std::sync::atomic::AtomicU64::new(0),
            calls_failed_total: std::sync::atomic::AtomicU64::new(0),
            ice_restart_total: std::sync::atomic::AtomicU64::new(0),
            call_signaling_failures_total: std::sync::atomic::AtomicU64::new(0),
        });

        // ── Restore incognito ephemeral aliases from KV ─────────────────────
        // On restart the in-memory ephemeral_aliases map is empty.  Without this
        // repopulation, incoming messages targeting an incognito IK fail to
        // decrypt because the override lookup in receive_encrypted_wire() misses.
        {
            let ik_keys = core.id_store.kv().keys_with_prefix("incognito/ik/");
            for kv_key in &ik_keys {
                // kv_key = "incognito/ik/<peer_b64>"
                let peer_b64 = match kv_key.strip_prefix("incognito/ik/") {
                    Some(s) => s,
                    None => continue,
                };
                let peer = match crate::identity::PeerId::from_base64(peer_b64) {
                    Ok(p) => p,
                    Err(_) => continue,
                };
                let bytes = match core.id_store.kv().get(kv_key) {
                    Some(b) => b,
                    None => continue,
                };
                let arr: [u8; 32] = match bytes.try_into() {
                    Ok(a) => a,
                    Err(_) => continue,
                };
                let eph_key = EphemeralContactKey::from_secret_bytes(arr);
                core.ephemeral_aliases
                    .write()
                    .insert(eph_key.public, ConversationId::Direct(peer));
            }
            if !ik_keys.is_empty() {
                tracing::debug!("[startup] restored {} incognito alias(es)", ik_keys.len());
            }
        }

        // ── Restore delivery tokens from KV ───────────────────────────────────
        // On restart, offline queues were loaded from disk, but we need
        // the delivery_tokens map to be memory-ready for the background
        // maintenance tasks to be able to iterate over and retry them.
        {
            let token_keys = core.id_store.kv().keys_with_prefix("relay/token/");
            for kv_key in &token_keys {
                let peer_b64 = match kv_key.strip_prefix("relay/token/") {
                    Some(s) => s,
                    None => continue,
                };
                let bytes = match core.id_store.kv().get(kv_key) {
                    Some(b) => b,
                    None => continue,
                };
                if let Ok(arr) = bytes.try_into() as std::result::Result<[u8; 32], _> {
                    core.delivery_tokens
                        .write()
                        .insert(peer_b64.to_string(), arr);
                }
            }
            if !token_keys.is_empty() {
                tracing::debug!("[startup] restored {} delivery token(s)", token_keys.len());
            }
        }

        let _ = core.restore_cached_bridge_manifest().await;

        Ok(core)
    }

    /// Start the ADA Core (begins networking, background tasks)
    async fn start_iroh_endpoint(self: &Arc<Self>, secret_key: &iroh::SecretKey) -> Result<()> {
        if self.iroh.read().await.is_some() {
            tracing::debug!(
                "[startup] start_iroh_endpoint: transport already initialized for peer_id={}",
                self.identity.peer_id
            );
            return Ok(());
        }

        let start_started_at = std::time::Instant::now();
        let known_contacts = self.id_store.list_peer_bundles();
        tracing::info!(
            "[startup] start_iroh_endpoint: begin peer_id={} mdns={} known_contacts={}",
            self.identity.peer_id,
            self.config.network.mdns,
            known_contacts.len()
        );
        let (transport, mut iroh_rx) =
            IrohTransport::start(secret_key.clone(), self.config.network.mdns).await?;
        tracing::info!(
            "[startup] start_iroh_endpoint: transport ready for peer_id={} in {:?}",
            self.identity.peer_id,
            start_started_at.elapsed()
        );
        for bundle in known_contacts {
            if let Some(relay_url) = bundle.relay_url.as_deref() {
                if let Err(error) = transport.add_peer_relay(&bundle.peer_id.0, relay_url) {
                    tracing::debug!(
                        "saved relay hint preload failed for {}: {}",
                        bundle.peer_id,
                        error
                    );
                }
            }
        }
        // Restore custom relay node configured via add_relay_node(), applying it
        // as a routing hint for all known peers so connections prefer that relay.
        if let Some(custom_relay_bytes) = self.id_store.kv().get("config/relay_node_url") {
            if let Ok(custom_relay_url) = std::str::from_utf8(&custom_relay_bytes) {
                let mut applied = 0usize;
                for bundle in self.id_store.list_peer_bundles() {
                    if transport
                        .add_peer_relay(&bundle.peer_id.0, custom_relay_url)
                        .is_ok()
                    {
                        applied += 1;
                    }
                }
                tracing::info!(
                    "[startup] custom relay '{}' applied as hint for {} peer(s)",
                    custom_relay_url,
                    applied
                );
            }
        }
        *self.iroh.write().await = Some(Arc::new(transport));
        self.iroh_started
            .store(true, std::sync::atomic::Ordering::Release);

        let weak = Arc::downgrade(self);
        let iroh_task = tokio::spawn(async move {
            while let Some(msg) = iroh_rx.recv().await {
                if let Some(core) = weak.upgrade() {
                    core.handle_iroh_message(msg).await;
                } else {
                    break;
                }
            }
        });
        self.tasks.write().await.push(iroh_task);
        self.iroh_consecutive_failures
            .store(0, std::sync::atomic::Ordering::Relaxed);
        let _ = self.event_tx.send(ADAEvent::NetworkConnected).await;
        Ok(())
    }

    /// Notify the core that the device network has been restored.
    ///
    /// Call this from the Android `onAvailable` network callback (or equivalent
    /// on other platforms) immediately when connectivity is re-established.
    ///
    /// Effect:
    /// - Clears stale QUIC connection cache (all paths are invalid after Doze/airplane-mode).
    /// - Forces iroh to re-probe interfaces and triggers an immediate pkarr republish,
    ///   bypassing the exponential backoff that would otherwise delay peer discovery
    ///   by up to 30+ seconds after each network-restore event.
    /// - Resets the iroh consecutive-failure counter.
    pub async fn notify_network_available(self: &Arc<Self>) {
        if let Some(iroh) = self.iroh.read().await.as_ref() {
            iroh.notify_network_available().await;
        }
        self.iroh_consecutive_failures
            .store(0, std::sync::atomic::Ordering::Relaxed);
        // Kick the bridge listener retry cycle immediately instead of waiting for its timer.
        self.mark_connection_recovering();
    }

    /// Notify the core that the current network interface has been lost
    /// (e.g. WiFi dropped before LTE takes over).
    ///
    /// Clears stale QUIC connections so the next send attempt doesn't try to
    /// reuse a path that no longer exists.  `notify_network_available` must be
    /// called when the new interface is ready.
    pub async fn notify_network_lost(self: &Arc<Self>) {
        if let Some(iroh) = self.iroh.read().await.as_ref() {
            iroh.notify_network_lost().await;
        }
    }

    pub async fn start(self: &Arc<Self>) -> Result<()> {
        let start_started_at = std::time::Instant::now();
        let manifest_urls = self.manifest_fetch_urls();
        tracing::info!(
            "[startup] ADACore::start begin peer_id={} profile={} relay_only={} manifest_urls={} data_dir={}",
            self.identity.peer_id,
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
            manifest_urls.len(),
            self.config.storage.data_dir
        );
        self.running
            .store(true, std::sync::atomic::Ordering::SeqCst);

        // ── Start iroh QUIC endpoint (best-effort) ─────────────────────────
        // If the live transport cannot bind immediately, keep the core alive so
        // bridge/mailbox delivery and the offline queue still work, then retry
        // iroh startup in the background until it recovers.
        let secret_key = self.identity.iroh_secret_key();
        let iroh_start_started_at = std::time::Instant::now();
        match self.start_iroh_endpoint(&secret_key).await {
            Ok(()) => {
                tracing::info!(
                    "[startup] ADACore::start: start_iroh_endpoint returned in {:?}",
                    iroh_start_started_at.elapsed()
                );
            }
            Err(error) => {
                tracing::warn!(
                    "iroh endpoint startup deferred after {:?}: {} — bridge/mailbox/offline delivery remain active",
                    iroh_start_started_at.elapsed(),
                    error
                );
                let _ = self.event_tx.send(ADAEvent::NetworkDisconnected).await;

                let weak = Arc::downgrade(self);
                let retry_secret_key = secret_key.clone();
                tracing::info!(
                    "[startup] ADACore::start: scheduling iroh retry loop every {}s for peer_id={}",
                    IROH_START_RETRY_SECS,
                    self.identity.peer_id
                );
                let iroh_retry = tokio::spawn(async move {
                    let retry_delay = tokio::time::Duration::from_secs(IROH_START_RETRY_SECS);
                    let mut attempt = 0u32;

                    loop {
                        tokio::time::sleep(retry_delay).await;
                        attempt = attempt.saturating_add(1);

                        let Some(core) = weak.upgrade() else {
                            tracing::debug!("[startup] iroh retry loop exiting: core dropped");
                            break;
                        };
                        if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                            tracing::debug!(
                                "[startup] iroh retry loop exiting for peer_id={}: core stopped",
                                core.identity.peer_id
                            );
                            break;
                        }
                        if core.iroh.read().await.is_some() {
                            tracing::debug!(
                                "[startup] iroh retry loop exiting for peer_id={}: endpoint already available",
                                core.identity.peer_id
                            );
                            break;
                        }

                        tracing::debug!(
                            "[startup] iroh retry attempt {} begin for peer_id={}",
                            attempt,
                            core.identity.peer_id
                        );
                        match core.start_iroh_endpoint(&retry_secret_key).await {
                            Ok(()) => {
                                tracing::info!(
                                    "iroh endpoint recovered after {} retry attempt(s)",
                                    attempt
                                );
                                core.retry_queued_offline_messages().await;
                                core.update_peer_presence().await;
                                break;
                            }
                            Err(error) => {
                                if attempt == 1 || attempt % 6 == 0 {
                                    tracing::warn!("iroh endpoint retry {} failed: {}", attempt, error);
                                } else {
                                    tracing::debug!(
                                        "iroh endpoint retry {} failed: {}",
                                        attempt,
                                        error
                                    );
                                }
                            }
                        }
                    }
                });
                self.tasks.write().await.push(iroh_retry);
            }
        }

        if !manifest_urls.is_empty() {
            tracing::info!(
                "[startup] ADACore::start: scheduling manifest bootstrap for {} URL(s)",
                manifest_urls.len()
            );
            let weak = Arc::downgrade(self);
            let manifest_bootstrap =
                tokio::spawn(async move {
                    // BUG-3 fix: retry manifest fetch with exponential backoff.
                    // In whitelist-based censorship the manifest URL may only become
                    // reachable once a bridge is up, or after a short network warm-up.
                    // Delays: 0 s (immediate), 15 s, 30 s, 60 s, 120 s.
                    let delays_secs: &[u64] = &[0, 15, 30, 60, 120];
                    for (attempt, &delay) in delays_secs.iter().enumerate() {
                        if delay > 0 {
                            tokio::time::sleep(tokio::time::Duration::from_secs(delay)).await;
                        }
                        let Some(core) = weak.upgrade() else {
                            break;
                        };
                        if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                            break;
                        }
                        tracing::debug!(
                            "[startup] manifest bootstrap attempt {} begin for peer_id={} after {}s delay",
                            attempt + 1,
                            core.identity.peer_id,
                            delay
                        );
                        // Skip if a previous attempt (or cache restore) already populated bridges.
                        if core.bridge_manifest.read().is_some() {
                            break;
                        }
                        match core.fetch_bridge_manifest().await {
                            Ok(()) => {
                                tracing::info!(
                                    "[manifest] bootstrap succeeded on attempt {}",
                                    attempt + 1
                                );
                                break;
                            }
                            Err(error) => {
                                if attempt + 1 < delays_secs.len() {
                                    tracing::warn!(
                                    "[manifest] bootstrap attempt {} failed (retry in {} s): {}",
                                    attempt + 1, delays_secs[attempt + 1], error
                                );
                                } else {
                                    tracing::warn!(
                                        "[manifest] bootstrap failed after {} attempts: {}",
                                        attempt + 1,
                                        error
                                    );
                                }
                            }
                        }
                    }
                });
            self.tasks.write().await.push(manifest_bootstrap);
        }

        tracing::info!(
            "[startup] ADACore::start: scheduling bridge listener reconnect_secs={} idle_ping_secs={}",
            self.config.bridge.reconnect_secs.max(1),
            self.config.bridge.idle_ping_secs.max(5)
        );
        let weak = Arc::downgrade(self);
        let bridge_listener = tokio::spawn(async move {
            let mut cycle = 0u64;
            loop {
                cycle = cycle.saturating_add(1);
                let Some(core) = weak.upgrade() else {
                    tracing::debug!("[startup] bridge listener loop exiting: core dropped");
                    break;
                };
                if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                    tracing::debug!(
                        "[startup] bridge listener loop exiting for peer_id={}: core stopped",
                        core.identity.peer_id
                    );
                    break;
                }

                let reconnect_delay =
                    tokio::time::Duration::from_secs(core.config.bridge.reconnect_secs.max(1));
                tracing::debug!(
                    "[startup] bridge listener cycle {}: connect begin for peer_id={} profile={} relay_only={}",
                    cycle,
                    core.identity.peer_id,
                    core.connection_profile().as_str(),
                    core.relay_only_enabled()
                );
                let connect_result = core
                    .bridges
                    .write()
                    .await
                    .connect_via_best_transport()
                    .await;
                let mut connected = match connect_result {
                    Ok(connected) => connected,
                    Err(error) => {
                        core.bridge_listener_connected
                            .store(false, std::sync::atomic::Ordering::Release);
                        core.mark_connection_degraded();
                        // Throttle: log only once per 60 s to avoid flooding logcat
                        // with "bridge listener connect skipped" at 5-second intervals.
                        let now_ms = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis() as u64;
                        let last = core
                            .bridge_skip_last_logged_ms
                            .load(std::sync::atomic::Ordering::Relaxed);
                        if now_ms.saturating_sub(last) >= 60_000 {
                            core.bridge_skip_last_logged_ms
                                .store(now_ms, std::sync::atomic::Ordering::Relaxed);
                            tracing::debug!("bridge listener connect skipped: {}", error);
                        }
                        tokio::time::sleep(reconnect_delay).await;
                        continue;
                    }
                };
                let wire_format = connected.bridge.wire_format;

                let register_auth = fresh_bridge_auth();
                let register_signature =
                    match register_challenge(&core.identity.peer_id.0, &register_auth) {
                        Ok(challenge) => core.identity.sign(&challenge),
                        Err(error) => {
                            core.bridge_listener_connected
                                .store(false, std::sync::atomic::Ordering::Release);
                            core.mark_connection_degraded();
                            tracing::warn!("bridge listener auth build failed: {}", error);
                            tokio::time::sleep(reconnect_delay).await;
                            continue;
                        }
                    };
                if let Err(error) = send_frame(
                    &mut connected.connection,
                    &BridgeFrame::Register {
                        peer_id: core.identity.peer_id.0,
                        signature: register_signature,
                        listen_for_mailbox: true,
                        auth: register_auth,
                    },
                    wire_format,
                )
                .await
                {
                    core.bridge_listener_connected
                        .store(false, std::sync::atomic::Ordering::Release);
                    core.mark_connection_degraded();
                    tracing::warn!("bridge listener register send failed: {}", error);
                    tokio::time::sleep(reconnect_delay).await;
                    continue;
                }

                match recv_frame(&mut connected.connection, wire_format).await {
                    Ok(BridgeFrame::RegisterOk {
                        bridge_fingerprint,
                        queued_count,
                    }) => {
                        if let Err(error) = verify_bridge_fingerprint(
                            &connected.bridge.fingerprint,
                            &bridge_fingerprint,
                        ) {
                            core.bridge_listener_connected
                                .store(false, std::sync::atomic::Ordering::Release);
                            core.mark_connection_degraded();
                            tracing::warn!("bridge listener fingerprint mismatch: {}", error);
                            tokio::time::sleep(reconnect_delay).await;
                            continue;
                        }
                        core.bridge_listener_connected
                            .store(true, std::sync::atomic::Ordering::Release);
                        core.mark_connection_recovering();
                        core.set_bridge_mailbox_depth(queued_count);
                        tracing::info!(
                            "[startup] bridge listener cycle {}: register ok for peer_id={} queued_count={}",
                            cycle,
                            core.identity.peer_id,
                            queued_count
                        );
                        *core.bridge_listener_route.write() = Some(
                            route_from_bridge_protocol(&connected.bridge.protocol, true)
                                .as_str()
                                .to_string(),
                        );

                        let resync_backlog = core.estimate_offline_backlog_count();
                        core.connection_resync_backlog_count
                            .store(resync_backlog, std::sync::atomic::Ordering::Release);
                        if resync_backlog == 0 {
                            core.mark_connection_recovered();
                        }
                        let weak_fast_resync = Arc::downgrade(&core);
                        spawn_background_task("bridge-fast-resync", async move {
                            let Some(core) = weak_fast_resync.upgrade() else {
                                return;
                            };
                            if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                                return;
                            }
                            core.retry_queued_offline_messages().await;
                            core.update_peer_presence().await;
                            core.mark_connection_recovered();
                        });
                    }
                    Ok(BridgeFrame::Error { message }) => {
                        core.bridge_listener_connected
                            .store(false, std::sync::atomic::Ordering::Release);
                        core.mark_connection_degraded();
                        tracing::warn!("bridge listener rejected: {}", message);
                        tokio::time::sleep(reconnect_delay).await;
                        continue;
                    }
                    Ok(other) => {
                        core.bridge_listener_connected
                            .store(false, std::sync::atomic::Ordering::Release);
                        core.mark_connection_degraded();
                        tracing::warn!("bridge listener unexpected register frame: {:?}", other);
                        tokio::time::sleep(reconnect_delay).await;
                        continue;
                    }
                    Err(error) => {
                        core.bridge_listener_connected
                            .store(false, std::sync::atomic::Ordering::Release);
                        core.mark_connection_degraded();
                        tracing::warn!("bridge listener register failed: {}", error);
                        tokio::time::sleep(reconnect_delay).await;
                        continue;
                    }
                }

                tracing::debug!(
                    "[startup] bridge listener cycle {}: entering receive loop for peer_id={}",
                    cycle,
                    core.identity.peer_id
                );
                let idle_ping =
                    tokio::time::Duration::from_secs(core.config.bridge.idle_ping_secs.max(5));
                loop {
                    if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }

                    match tokio::time::timeout(
                        idle_ping,
                        recv_frame(&mut connected.connection, wire_format),
                    )
                    .await
                    {
                        Ok(Ok(BridgeFrame::Deliver { envelope })) => {
                            if envelope.recipient != core.identity.peer_id.0 {
                                continue;
                            }
                            match core.handle_bridge_delivery(envelope.clone()).await {
                                Ok(()) => {
                                    let _ = send_frame(
                                        &mut connected.connection,
                                        &BridgeFrame::Ack {
                                            message_ids: vec![envelope.message_id],
                                        },
                                        wire_format,
                                    )
                                    .await;
                                }
                                Err(error) => {
                                    tracing::warn!("bridge delivery handling failed: {}", error);
                                }
                            }
                        }
                        Ok(Ok(BridgeFrame::Ping)) => {
                            let _ = send_frame(
                                &mut connected.connection,
                                &BridgeFrame::Pong,
                                wire_format,
                            )
                            .await;
                        }
                        Ok(Ok(BridgeFrame::Pong)) => {}
                        Ok(Ok(BridgeFrame::Error { message })) => {
                            tracing::warn!("bridge listener server error: {}", message);
                            break;
                        }
                        Ok(Ok(_)) => {}
                        Ok(Err(error)) => {
                            tracing::warn!("bridge listener frame error: {}", error);
                            break;
                        }
                        Err(_) => {
                            if let Err(error) = send_frame(
                                &mut connected.connection,
                                &BridgeFrame::Ping,
                                wire_format,
                            )
                            .await
                            {
                                tracing::warn!("bridge listener keepalive failed: {}", error);
                                break;
                            }
                        }
                    }
                }

                core.bridge_listener_connected
                    .store(false, std::sync::atomic::Ordering::Release);
                core.mark_connection_degraded();
                tracing::debug!(
                    "[startup] bridge listener cycle {}: disconnected for peer_id={}, retry in {:?}",
                    cycle,
                    core.identity.peer_id,
                    reconnect_delay
                );
                tokio::time::sleep(reconnect_delay).await;
            }
        });
        self.tasks.write().await.push(bridge_listener);

        // ── HTTP mailbox pull task ────────────────────────────────────────
        // Only active in relay-only / strict profiles. Normal networks keep
        // relying on iroh or the live bridge listener, avoiding extra battery
        // and bridge load.
        let weak = Arc::downgrade(self);
        let mailbox_pull = tokio::spawn(async move {
            loop {
                let Some(core) = weak.upgrade() else {
                    break;
                };
                if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                    break;
                }

                let sleep_for = core
                    .mailbox_poll_interval()
                    .unwrap_or_else(|| tokio::time::Duration::from_secs(60));
                if core.mailbox_poll_interval().is_some() {
                    match core.poll_http_mailbox_once().await {
                        Ok(handled) if handled > 0 => {
                            tracing::info!("http mailbox pull handled {} envelope(s)", handled);
                        }
                        Ok(_) => {}
                        Err(error) => tracing::debug!("http mailbox pull skipped: {}", error),
                    }
                }
                tokio::time::sleep(sleep_for).await;
            }
        });
        self.tasks.write().await.push(mailbox_pull);

        // ── Maintenance task ───────────────────────────────────────────────
        let weak = Arc::downgrade(self);
        let maintenance = tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(60));
            loop {
                interval.tick().await;
                if let Some(core) = weak.upgrade() {
                    if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }
                    core.run_maintenance().await;
                } else {
                    break;
                }
            }
        });
        self.tasks.write().await.push(maintenance);

        // ── Early-retry task: retry offline queue at 5 s, 10 s, 20 s post-start ─
        // iroh QUIC handshake and relay discovery need a few seconds to settle.
        // Messages queued during the first few seconds are retried here without
        // waiting for the 60 s maintenance cycle.
        let weak = Arc::downgrade(self);
        let early_retry = tokio::spawn(async move {
            for delay_secs in [5u64, 10, 20] {
                tokio::time::sleep(tokio::time::Duration::from_secs(delay_secs)).await;
                if let Some(core) = weak.upgrade() {
                    if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }
                    core.retry_queued_offline_messages().await;
                } else {
                    break;
                }
            }
        });
        self.tasks.write().await.push(early_retry);

        // ── Transfer event bridge (forwards TransferManager events → ADAEvent) ────
        if let Some(mut transfer_rx) = self.transfer_rx.write().await.take() {
            let event_tx = self.event_tx.clone();
            let running = Arc::clone(&self.running);
            let bridge = tokio::spawn(async move {
                while let Some(te) = transfer_rx.recv().await {
                    if !running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }
                    let _ = event_tx.send(ADAEvent::TransferEvent(te)).await;
                }
            });
            self.tasks.write().await.push(bridge);
        }

        // ── File transfer chunk pump (every 5 ms) ─────────────────────────
        let weak = Arc::downgrade(self);
        let chunk_pump = tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_millis(5));
            // BUG-010 fix: Skip missed ticks (e.g. after Doze) instead of bursting
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                if let Some(core) = weak.upgrade() {
                    if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }
                    core.tick_transfers().await;
                } else {
                    break;
                }
            }
        });
        self.tasks.write().await.push(chunk_pump);

        // ── Call timeout watchdog (every 5 s) ──────────────────────────────
        // Ringing calls are auto-ended after RING_TIMEOUT_SECS (45 s) and
        // connecting calls after RING_TIMEOUT + ICE_TIMEOUT.  The maintenance
        // cycle (60 s) is too coarse for this — a dedicated timer ensures
        // prompt cleanup.
        let weak = Arc::downgrade(self);
        let call_timeout_task = tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                interval.tick().await;
                if let Some(core) = weak.upgrade() {
                    if !core.running.load(std::sync::atomic::Ordering::SeqCst) {
                        break;
                    }
                    core.call_mgr.check_timeouts().await;
                    let timed_out = core.call_mgr.check_timeouts().await;
                    if timed_out > 0 {
                        core.calls_failed_total
                            .fetch_add(timed_out as u64, std::sync::atomic::Ordering::Relaxed);
                    }
                } else {
                    break;
                }
            }
        });
        self.tasks.write().await.push(call_timeout_task);

        let task_count = self.tasks.read().await.len();
        tracing::info!(
            "[startup] ADACore::start returned peer_id={} tasks={} elapsed={:?}",
            self.identity.peer_id,
            task_count,
            start_started_at.elapsed()
        );
        tracing::info!("ADA Core started. Peer ID: {}", self.identity.peer_id);

        Ok(())
    }

    /// Pump outbound chunk queue: for every waiting outbound transfer, send the next
    /// unacknowledged chunk to the peer as a `FileChunk` wire message.
    async fn pump_outbound_chunks(self: &Arc<Self>) {
        let transfer_ids = {
            let outbound = self.transfer_mgr.active_transfers_info().await;
            outbound
                .into_iter()
                .filter(|(_, _, is_out)| *is_out)
                .map(|(meta, _, _)| (meta.id, meta.peer.clone()))
                .collect::<Vec<_>>()
        };

        for (tid, peer) in transfer_ids {
            let Ok(Some(chunk)) = self.transfer_mgr.next_outbound_chunk(&tid).await else {
                continue;
            };
            let Ok(data_bytes) = bincode::serialize(&chunk.data) else {
                continue;
            };

            let kind = MessageKind::FileChunk {
                transfer_id: tid,
                index: chunk.index,
                total: chunk.total,
                data: data_bytes,
                chunk_checksum: chunk.chunk_checksum,
            };
            let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);
            if let Ok((peer_id, wire_bytes, _)) = self.prepare_message(msg).await {
                if let Ok(outcome) = self
                    .route_wire_with_policy(
                        &peer_id,
                        tid,
                        wire_bytes,
                        DeliveryClass::FileChunk,
                        false,
                    )
                    .await
                {
                    if !matches!(outcome.route, TransportRoute::OfflineQueue) {
                        let _ = self.transfer_mgr.ack_chunk(&tid, chunk.index).await;
                        tracing::trace!(
                            "Sent chunk {}/{} of transfer {} via {}",
                            chunk.index + 1,
                            chunk.total,
                            hex::encode(tid),
                            outcome.route.as_str()
                        );
                    }
                }
            }
        }
    }

    /// Ask peer to retransmit any chunks we haven't received yet.
    pub async fn request_missing_chunks(&self, transfer_id: [u8; 16], peer: PeerId) -> Result<()> {
        let missing = self.transfer_mgr.missing_chunks(&transfer_id).await;
        if missing.is_empty() {
            return Ok(());
        }
        let kind = MessageKind::ChunkRequest {
            transfer_id,
            missing,
        };
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer), kind);
        self.send_message(msg).await?;
        Ok(())
    }

    /// Stop the ADA Core gracefully
    pub async fn stop(&self) {
        self.running
            .store(false, std::sync::atomic::Ordering::SeqCst);
        let mut tasks = self.tasks.write().await;
        for task in tasks.drain(..) {
            task.abort();
        }
        // Close iroh endpoint cleanly
        if let Some(iroh) = self.iroh.write().await.take() {
            self.iroh_started
                .store(false, std::sync::atomic::Ordering::Release);
            iroh.close().await;
        }
        tracing::info!("ADA Core stopped.");
    }

    /// Take the event receiver (call once at startup)
    pub async fn take_events(&self) -> Option<mpsc::Receiver<ADAEvent>> {
        self.event_rx.write().await.take()
    }

    // =====================================================================
    // IDENTITY EXPORT / IMPORT
    // =====================================================================

    /// Export the running identity's secret key material as a JSON string.
    /// Used to transfer an account from phone to desktop (Bluetooth sync).
    pub async fn export_identity_json(&self) -> Result<String> {
        let export = self.identity.export_secret();
        serde_json::to_string(&export).map_err(ADAError::Json)
    }

    /// Hot-swap of an identity into a running core is not supported.
    /// Use [`ADACore::new_from_json_export`] to create a fresh core from an
    /// identity received from another device (e.g., Bluetooth phone link).
    pub async fn import_identity_json(&self, _json: &str) -> Result<()> {
        Err(ADAError::Unknown(
            "Hot-swap of identity is not supported. \
             Use the Bluetooth phone-link flow on the login screen."
                .into(),
        ))
    }

    /// Create a new [`ADACore`] from an identity JSON export received from another
    /// device (e.g., the phone app transferring an account via Bluetooth).
    ///
    /// The JSON must be a serialised [`crate::identity::IdentityExport`] produced by
    /// [`ADACore::export_identity_json`] on the sending device.
    pub async fn new_from_json_export(config: ADAConfig, json: &str) -> Result<Arc<Self>> {
        use crate::identity::IdentityExport;
        let export: IdentityExport = serde_json::from_str(json).map_err(ADAError::Json)?;
        let identity = Arc::new(Identity::import_secret(export)?);
        Self::with_identity(config, identity).await
    }

    /// Export a full device snapshot for syncing to a secondary device (desktop).
    ///
    /// The snapshot includes:
    /// - Identity secret key material
    /// - All contact public bundles
    /// - Per-peer double-ratchet session states (plaintext)
    /// - Last 200 messages per direct conversation
    ///
    /// # Security
    /// The returned JSON contains sensitive key material.
    /// Only transmit over authenticated local channels (QR-paired Wi-Fi link).
    pub async fn export_snapshot(&self) -> Result<String> {
        use std::time::{SystemTime, UNIX_EPOCH};

        let identity = self.identity.export_secret();
        let peer_bundles = self.id_store.list_peer_bundles();

        let mut ratchet_sessions: Vec<RatchetEntry> = Vec::new();
        for peer_id_b64 in self.id_store.list_ratchet_peer_ids() {
            match PeerId::from_base64(&peer_id_b64) {
                Ok(peer_id) => {
                    match self.id_store.load_ratchet_state(&peer_id) {
                        Ok(Some(state)) => {
                            match bincode::serialize(&state) {
                                Ok(state_bytes) => {
                                    ratchet_sessions.push(RatchetEntry { peer_id_b64, state_bytes });
                                }
                                Err(e) => tracing::warn!(
                                    "[snapshot] export: ratchet serialize failed for {}: {e}",
                                    peer_id_b64
                                ),
                            }
                        }
                        Ok(None) => {}
                        Err(e) => tracing::warn!(
                            "[snapshot] export: load ratchet failed for {}: {e}",
                            peer_id_b64
                        ),
                    }
                }
                Err(e) => tracing::warn!(
                    "[snapshot] export: invalid peer_id_b64 '{}': {e}",
                    peer_id_b64
                ),
            }
        }

        let mut recent_messages: Vec<crate::storage::ChatMessage> = Vec::new();
        for bundle in &peer_bundles {
            let peer_id_b64 = bundle.peer_id.to_base64();
            match self.id_store.kv().load_chat_messages(&peer_id_b64, 1000) {
                Ok(msgs) => {
                    // Strip inline media blobs before serialisation — they can be
                    // up to 256 KiB each and would make the snapshot JSON huge.
                    // The desktop will show metadata (name/size/mime) without the
                    // raw bytes; blobs can be re-transferred via a dedicated sync.
                    let stripped = msgs.into_iter().map(|mut m| {
                        m.media_blob = None;
                        m
                    });
                    recent_messages.extend(stripped);
                }
                Err(e) => tracing::warn!(
                    "[snapshot] export: load messages failed for {}: {e}",
                    peer_id_b64
                ),
            }
        }

        let snapshot = DeviceSnapshot {
            version: 1,
            exported_at: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
            identity,
            peer_bundles,
            ratchet_sessions,
            recent_messages,
            link_key: {
                // Reuse existing link key if already paired, otherwise generate a fresh one.
                let existing = self.id_store.kv().get(DEVICE_LINK_KEY_KV)
                    .and_then(|b| b.try_into().ok());
                let key: [u8; 32] = existing.unwrap_or_else(|| {
                    use rand::RngCore;
                    let mut k = [0u8; 32];
                    rand::rngs::OsRng.fill_bytes(&mut k);
                    k
                });
                // Persist so that subsequent exports reuse the same key.
                let _ = self.id_store.kv().set(DEVICE_LINK_KEY_KV, key.to_vec());
                Some(key)
            },
        };

        tracing::info!(
            "[snapshot] export: {} contacts, {} ratchets, {} messages",
            snapshot.peer_bundles.len(),
            snapshot.ratchet_sessions.len(),
            snapshot.recent_messages.len(),
        );

        serde_json::to_string(&snapshot).map_err(ADAError::Json)
    }

    /// Import snapshot data (contacts, ratchet states, messages) into this running core.
    ///
    /// Called on the desktop after creating a core from a snapshot with
    /// [`from_snapshot_with_pattern`] to populate any data that was not yet
    /// loaded by the startup sequence.
    ///
    /// The identity field of the snapshot is intentionally ignored — this
    /// instance was already created from it.
    pub async fn import_snapshot_data(&self, snapshot_json: &str) -> Result<()> {
        let snapshot: DeviceSnapshot =
            serde_json::from_str(snapshot_json).map_err(ADAError::Json)?;
        if snapshot.version != 1 {
            return Err(ADAError::Unknown(format!(
                "unsupported snapshot version: {}",
                snapshot.version
            )));
        }

        for bundle in &snapshot.peer_bundles {
            if let Err(e) = self.id_store.save_peer_bundle(bundle) {
                tracing::warn!(
                    "[snapshot] import: failed to save bundle for {}: {e}",
                    bundle.peer_id
                );
            } else {
                // Also register the conversation in the in-memory message store.
                let conv = ConversationId::Direct(bundle.peer_id.clone());
                self.messages.upsert_conversation(&conv, &bundle.display_name);
            }
        }

        for entry in &snapshot.ratchet_sessions {
            match PeerId::from_base64(&entry.peer_id_b64) {
                Ok(peer_id) => {
                    match bincode::deserialize::<crate::crypto::ratchet::RatchetState>(
                        &entry.state_bytes,
                    ) {
                        Ok(state) => {
                            if let Err(e) = self.id_store.save_ratchet_state(&peer_id, &state) {
                                tracing::warn!(
                                    "[snapshot] import: failed to save ratchet for {}: {e}",
                                    entry.peer_id_b64
                                );
                            } else {
                                self.sessions.load_session(&peer_id, state);
                            }
                        }
                        Err(e) => tracing::warn!(
                            "[snapshot] import: ratchet deserialize failed for {}: {e}",
                            entry.peer_id_b64
                        ),
                    }
                }
                Err(e) => tracing::warn!(
                    "[snapshot] import: invalid peer_id_b64 '{}': {e}",
                    entry.peer_id_b64
                ),
            }
        }

        for msg in &snapshot.recent_messages {
            if let Err(e) = self.id_store.kv().upsert_chat_message(msg) {
                tracing::warn!(
                    "[snapshot] import: failed to upsert message {}: {e}",
                    msg.message_id
                );
            }
        }

        tracing::info!(
            "[snapshot] import_snapshot_data: {} contacts, {} ratchets, {} messages",
            snapshot.peer_bundles.len(),
            snapshot.ratchet_sessions.len(),
            snapshot.recent_messages.len(),
        );

        Ok(())
    }

    // ─── Device Sync Channel ─────────────────────────────────────────────────

    /// Returns the hex-encoded 32-byte link key for the device sync channel,
    /// or `None` if this device is not paired with another device yet.
    pub fn get_link_key_hex(&self) -> Option<String> {
        self.id_store
            .kv()
            .get(DEVICE_LINK_KEY_KV)
            .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
            .map(|k| hex::encode(k))
    }

    /// Persist the HTTP sync URL of the linked device (e.g. desktop sync server).
    /// Called by the Android side after reading the pairing HTTP response.
    pub fn store_linked_device_sync_url(&self, url: &str) {
        let _ = self
            .id_store
            .kv()
            .set(LINKED_DEVICE_SYNC_URL_KV, url.as_bytes().to_vec());
        tracing::info!("[sync] linked device sync URL stored: {}", url);
    }

    /// Returns the stored sync URL of the linked device, if any.
    pub fn get_linked_device_sync_url(&self) -> Option<String> {
        self.id_store
            .kv()
            .get(LINKED_DEVICE_SYNC_URL_KV)
            .and_then(|b| String::from_utf8(b).ok())
    }

    /// Decrypt and apply an incoming sync push payload.
    ///
    /// Called by the desktop sync server when a push arrives from the phone.
    /// `encrypted_payload` is the raw bytes received over the network:
    /// `SYNC_MAGIC (7 B) | nonce (24 B) | XChaCha20-Poly1305 ciphertext`.
    ///
    /// On success the decrypted [`crate::storage::ChatMessage`] is stored in
    /// the local DB and a [`ADAEvent::MessageReceived`] event is emitted.
    pub async fn handle_sync_push(&self, link_key_hex: &str, encrypted_payload: &[u8]) -> Result<()> {
        use chacha20poly1305::{aead::Aead, KeyInit, XChaCha20Poly1305, XNonce};

        // 1. Load the locally stored link key — this is the source of truth.
        //    Using the stored key (rather than the caller-supplied hex) for AEAD
        //    prevents this function from being used as a decryption oracle with
        //    an arbitrary attacker-controlled key.
        let stored_key_bytes = self
            .id_store
            .kv()
            .get(DEVICE_LINK_KEY_KV)
            .ok_or_else(|| ADAError::Crypto("sync: no link key configured on this device".into()))?;
        let stored_key: [u8; 32] = stored_key_bytes
            .as_slice()
            .try_into()
            .map_err(|_| ADAError::Crypto("sync: stored link key has wrong length".into()))?;

        // 2. Verify the Bearer token matches our stored key before doing any
        //    cryptographic work.  Both must be 64 lower-hex chars.
        let stored_key_hex = hex::encode(&stored_key);
        if stored_key_hex != link_key_hex {
            return Err(ADAError::Crypto("sync: link_key mismatch".into()));
        }

        // 3. Validate magic prefix.
        if !encrypted_payload.starts_with(SYNC_MAGIC) {
            return Err(ADAError::Crypto("sync: missing magic prefix".into()));
        }
        let rest = &encrypted_payload[SYNC_MAGIC.len()..];
        if rest.len() < 24 {
            return Err(ADAError::Crypto("sync: payload too short".into()));
        }
        let (nonce_bytes, ciphertext) = rest.split_at(24);
        let nonce = XNonce::from_slice(nonce_bytes);

        // 4. AEAD decrypt with the *stored* key (not the caller-supplied one).
        let cipher = XChaCha20Poly1305::new((&stored_key).into());
        let plaintext = cipher
            .decrypt(nonce, ciphertext)
            .map_err(|_| ADAError::Crypto("sync: AEAD decrypt failed (tampered or replayed?)".into()))?;

        let msg: crate::storage::ChatMessage =
            serde_json::from_slice(&plaintext).map_err(ADAError::Json)?;

        // 5. Timestamp validation: reject messages older than 5 minutes or more
        //    than 60 seconds in the future (clock-drift tolerance).
        //    This bounds the replay window even if nonces happen to collide.
        let now_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let age_secs = now_secs - msg.created_at;
        if age_secs > 300 || age_secs < -60 {
            return Err(ADAError::Crypto(format!(
                "sync: message timestamp out of allowed window (age={}s)",
                age_secs
            )));
        }

        // 6. Idempotency / replay guard: skip if this message_id was already
        //    processed.  The seen-flag is written only after a successful store,
        //    so a crash mid-flight will re-process on the next push (safe because
        //    upsert_chat_message is idempotent).
        let seen_key = format!("{}{}", SYNC_SEEN_KV_PREFIX, msg.message_id);
        if self.id_store.kv().get(&seen_key).is_some() {
            tracing::debug!("[sync] message {} already processed, skipping", msg.message_id);
            return Ok(());
        }

        // Look up the contact's display name for the conversation entry.
        let peer_id = crate::identity::PeerId::from_base64(&msg.peer_id)
            .map_err(|e| ADAError::Unknown(format!("sync: bad peer_id: {e}")))?;
        let display_name = self
            .id_store
            .load_peer_bundle(&peer_id)?
            .map(|b| b.display_name.clone())
            .unwrap_or_else(|| msg.peer_id.clone());

        let conv = ConversationId::Direct(peer_id.clone());
        self.messages.upsert_conversation(&conv, &display_name);

        if let Err(e) = self.id_store.kv().upsert_chat_message(&msg) {
            tracing::warn!("[sync] handle_sync_push: store failed: {e}");
        }

        // Build a minimal Message to emit the MessageReceived event.
        use crate::messaging::types::{Message, MessageKind, MessageStatus};
        let id_bytes = hex::decode(&msg.message_id)
            .ok()
            .and_then(|b| b.try_into().ok())
            .unwrap_or([0u8; 16]);
        let kind = if let Some(text) = &msg.body_text {
            MessageKind::Text(text.clone())
        } else {
            MessageKind::Text(String::new())
        };
        let emitted = Message {
            id: id_bytes,
            sender: if msg.is_outgoing {
                self.identity.peer_id.clone()
            } else {
                peer_id.clone()
            },
            recipient: Some(if msg.is_outgoing {
                peer_id.clone()
            } else {
                self.identity.peer_id.clone()
            }),
            group_id: None,
            kind,
            timestamp: msg.created_at as u64,
            signature: vec![],
            status: if msg.is_outgoing {
                MessageStatus::Sent
            } else {
                MessageStatus::Delivered
            },
            reply_to: None,
            expires_in: None,
            expires_at: None,
        };

        let _ = self
            .event_tx
            .send(ADAEvent::MessageReceived(emitted))
            .await;

        // 7. Mark this message as seen to prevent future replays.
        let _ = self.id_store.kv().set(&seen_key, b"1".to_vec());

        tracing::debug!(
            "[sync] handle_sync_push: applied message {} peer={}",
            msg.message_id,
            msg.peer_id
        );
        Ok(())
    }

    /// Encrypt a [`crate::storage::ChatMessage`] with the link key and return
    /// the wire bytes `SYNC_MAGIC | nonce | ciphertext` ready to POST.
    /// Returns `None` if this device has no link key configured.
    pub fn seal_sync_push(&self, msg: &crate::storage::ChatMessage) -> Option<Vec<u8>> {
        use chacha20poly1305::{aead::Aead, KeyInit, XChaCha20Poly1305, XNonce};
        use rand::RngCore;

        let key_bytes = self.id_store.kv().get(DEVICE_LINK_KEY_KV)?;
        let key: [u8; 32] = key_bytes.as_slice().try_into().ok()?;

        let plaintext = serde_json::to_vec(msg).ok()?;

        let mut nonce_bytes = [0u8; 24];
        rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = XNonce::from_slice(&nonce_bytes);

        let cipher = XChaCha20Poly1305::new((&key).into());
        let ciphertext = cipher.encrypt(nonce, plaintext.as_slice()).ok()?;

        let mut out = Vec::with_capacity(SYNC_MAGIC.len() + 24 + ciphertext.len());
        out.extend_from_slice(SYNC_MAGIC);
        out.extend_from_slice(&nonce_bytes);
        out.extend_from_slice(&ciphertext);
        Some(out)
    }

    // =====================================================================
    // MESSAGING
    // =====================================================================

    /// Send a text message to a peer
    pub async fn send_text(&self, peer: &PeerId, text: String) -> Result<[u8; 16]> {
        let msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Text(text),
        );
        self.send_message(msg).await
    }

    pub async fn prepare_edit_message(
        &self,
        peer: &PeerId,
        target_msg_id: [u8; 16],
        new_text: String,
    ) -> Result<([u8; 16], Vec<u8>)> {
        let target = self
            .messages
            .get_message_by_id(&target_msg_id)
            .ok_or_else(|| ADAError::Message("Target message not found".into()))?;

        if target.sender != self.identity.peer_id {
            return Err(ADAError::Message(
                "Only the original sender may edit a message".into(),
            ));
        }
        if target.group_id.is_some() || target.recipient.as_ref() != Some(peer) {
            return Err(ADAError::Message(
                "Target message does not belong to this direct conversation".into(),
            ));
        }
        if !matches!(target.kind, MessageKind::Text(_)) {
            return Err(ADAError::Message("Only text messages can be edited".into()));
        }

        let mut msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Edit {
                target_msg_id,
                new_text,
            },
        );
        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let id = msg.id;

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;
        let bundle = self.id_store.load_peer_bundle(peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });
        let wire = self
            .sessions
            .encrypt_to(peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;
        let conv = ConversationId::Direct(peer.clone());
        self.messages.save_hidden_message(&conv, msg)?;

        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((id, wire_bytes))
    }

    /// Send an emoji reaction to a specific direct message.
    pub async fn send_reaction(
        &self,
        peer: &PeerId,
        target_msg_id: [u8; 16],
        emoji: String,
    ) -> Result<[u8; 16]> {
        let msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Reaction {
                target_msg_id,
                emoji,
            },
        );
        self.send_message(msg).await
    }

    /// Fast path: build, sign, encrypt, store — returns (id, wire_bytes).
    /// Does NOT do any network I/O.  Call `deliver_wire` from a background task.
    ///
    /// Separating storage from delivery lets the FFI layer return instantly while
    /// iroh's 15-second QUIC handshake runs off the JNI thread.
    pub async fn prepare_text_message(
        &self,
        peer: &PeerId,
        text: String,
        expires_in: Option<u32>,
    ) -> Result<([u8; 16], Vec<u8>)> {
        let mut msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Text(text),
        );
        msg.expires_in = expires_in;
        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let id = msg.id;

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;

        let bundle = self.id_store.load_peer_bundle(peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });

        let wire = self
            .sessions
            .encrypt_to(peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;

        let conv = ConversationId::Direct(peer.clone());
        self.messages.save_message_with_unread(&conv, msg, false)?;

        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((id, wire_bytes))
    }

    pub async fn prepare_ephemeral_text_message(
        &self,
        peer: &PeerId,
        text: String,
        expires_in_secs: u32,
    ) -> Result<([u8; 16], Vec<u8>)> {
        let mut msg = Message::new_ephemeral(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Text(text),
            expires_in_secs,
        );
        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let id = msg.id;

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;

        let bundle = self.id_store.load_peer_bundle(peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });

        let wire = self
            .sessions
            .encrypt_to(peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;

        let conv = ConversationId::Direct(peer.clone());
        self.messages.save_message_with_unread(&conv, msg, false)?;

        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((id, wire_bytes))
    }

    /// Same as `prepare_text_message` but sets `reply_to` on the message.
    pub async fn prepare_reply_message(
        &self,
        peer: &PeerId,
        text: String,
        reply_to_id: [u8; 16],
    ) -> Result<([u8; 16], Vec<u8>)> {
        let mut msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Text(text),
        );
        msg.reply_to = Some(reply_to_id);
        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let id = msg.id;

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;
        let bundle = self.id_store.load_peer_bundle(peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });
        let wire = self
            .sessions
            .encrypt_to(peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;
        let conv = ConversationId::Direct(peer.clone());
        self.messages.save_message_with_unread(&conv, msg, false)?;
        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((id, wire_bytes))
    }

    /// Build, sign, encrypt, store a reaction message — returns (id, wire_bytes).
    pub async fn prepare_reaction_message(
        &self,
        peer: &PeerId,
        target_msg_id: [u8; 16],
        emoji: String,
    ) -> Result<([u8; 16], Vec<u8>)> {
        let mut msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::Reaction {
                target_msg_id,
                emoji,
            },
        );
        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let id = msg.id;

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;
        let bundle = self.id_store.load_peer_bundle(peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });
        let wire = self
            .sessions
            .encrypt_to(peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;
        let conv = ConversationId::Direct(peer.clone());
        self.messages.save_message_with_unread(&conv, msg, false)?;
        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((id, wire_bytes))
    }

    /// Network delivery leg for the current iroh-only runtime.
    /// Safe to call from a spawned background task — does not block the JNI thread.
    /// On success updates message status to Sent and emits a MessageStatusChanged event.
    pub async fn deliver_wire(&self, peer: &PeerId, id: [u8; 16], wire_bytes: Vec<u8>) {
        match self
            .route_wire_with_policy(peer, id, wire_bytes, DeliveryClass::DirectMessage, true)
            .await
        {
            Ok(outcome) => {
                tracing::info!(
                    target: "ada.network",
                    peer = %peer,
                    route = outcome.route.as_str(),
                    queue_depth = outcome.queue_depth,
                    latency_ms = outcome.latency_ms,
                    live_delivery = outcome.live_delivery,
                    "message delivery outcome"
                );
                if !matches!(outcome.route, TransportRoute::OfflineQueue) {
                    if route_implies_iroh_peer_online(&outcome.route)
                        && self.online_peers.write().insert(peer.0)
                    {
                        let _ = self.event_tx.send(ADAEvent::PeerOnline(peer.clone())).await;
                    }
                    let _ = self.messages.update_status(&id, MessageStatus::Sent);
                    let _ = self
                        .event_tx
                        .send(ADAEvent::MessageStatusChanged {
                            message_id: id,
                            status: MessageStatus::Sent,
                        })
                        .await;
                    self.flush_offline_queue_for(peer).await;
                }
                self.publish_transport_outcome(
                    id,
                    outcome.route.as_str(),
                    outcome.queue_depth,
                    outcome.latency_ms,
                )
                .await;
            }
            Err(e) => {
                let reason = format!("transport delivery failed: {}", e);
                tracing::error!(
                    target: "ada.network",
                    peer = %peer,
                    error = %reason,
                    "message delivery failed"
                );
                let _ = self
                    .messages
                    .update_status(&id, MessageStatus::Failed(reason.clone()));
                let _ = self
                    .event_tx
                    .send(ADAEvent::MessageStatusChanged {
                        message_id: id,
                        status: MessageStatus::Failed(reason),
                    })
                    .await;
                self.publish_transport_outcome(id, TransportRoute::Failed.as_str(), None, None)
                    .await;
            }
        }
    }

    /// Send a text message to a group
    pub async fn send_group_text(&self, group_id: [u8; 16], text: String) -> Result<[u8; 16]> {
        let msg = Message::for_group(
            self.identity.peer_id.clone(),
            group_id,
            MessageKind::Text(text),
        );
        self.send_group_message(msg).await
    }

    pub async fn send_group_reply(
        &self,
        group_id: [u8; 16],
        text: String,
        reply_to_id: [u8; 16],
    ) -> Result<[u8; 16]> {
        let mut msg = Message::for_group(
            self.identity.peer_id.clone(),
            group_id,
            MessageKind::Text(text),
        );
        msg.reply_to = Some(reply_to_id);
        self.send_group_message(msg).await
    }

    pub async fn send_group_edit(
        &self,
        group_id: [u8; 16],
        target_msg_id: [u8; 16],
        new_text: String,
    ) -> Result<[u8; 16]> {
        let target = self
            .messages
            .get_message_by_id(&target_msg_id)
            .ok_or_else(|| ADAError::Message("Target message not found".into()))?;

        if target.sender != self.identity.peer_id {
            return Err(ADAError::Message(
                "Only the original sender may edit a message".into(),
            ));
        }
        if target.group_id != Some(group_id) {
            return Err(ADAError::Message(
                "Target message does not belong to this group".into(),
            ));
        }
        if !matches!(target.kind, MessageKind::Text(_)) {
            return Err(ADAError::Message("Only text messages can be edited".into()));
        }

        let msg = Message::for_group(
            self.identity.peer_id.clone(),
            group_id,
            MessageKind::Edit {
                target_msg_id,
                new_text,
            },
        );
        self.send_group_hidden_message(msg).await
    }

    pub async fn send_group_reaction(
        &self,
        group_id: [u8; 16],
        target_msg_id: [u8; 16],
        emoji: String,
    ) -> Result<[u8; 16]> {
        let msg = Message::for_group(
            self.identity.peer_id.clone(),
            group_id,
            MessageKind::Reaction {
                target_msg_id,
                emoji,
            },
        );
        self.send_group_message(msg).await
    }

    pub async fn announce_group_call(
        &self,
        group_id: [u8; 16],
        session_id: [u8; 16],
        has_video: bool,
    ) -> Result<[u8; 16]> {
        let msg = Message::for_group(
            self.identity.peer_id.clone(),
            group_id,
            MessageKind::GroupCallStart {
                session_id,
                has_video,
            },
        );
        self.send_group_message(msg).await
    }

    /// Send a message to a specific peer
    /// Sign, encrypt, and store `msg` locally, returning the peer and
    /// the serialised wire bytes ready for transport.  Does NOT transmit —
    /// call `try_deliver_wire` (once or in a retry loop) to actually send.
    async fn prepare_message(&self, mut msg: Message) -> Result<(PeerId, Vec<u8>, [u8; 16])> {
        let peer = msg
            .recipient
            .clone()
            .ok_or(ADAError::Message("No recipient".into()))?;
        let id = msg.id;

        let to_sign = msg.bytes_to_sign();
        msg.signature = self.identity.sign(&to_sign);

        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;

        let bundle = self.id_store.load_peer_bundle(&peer)?;
        let bundle_ref = bundle.as_ref().map(|b| crate::crypto::x3dh::PreKeyBundle {
            ik_public: b.dh_public,
            spk_public: b.spk_public,
            spk_signature: b.spk_signature.clone(),
            opk_public: b.opk_public,
            opk_id: b.opk_id,
        });

        let wire = self
            .sessions
            .encrypt_to(&peer, &plaintext, bundle_ref.as_ref(), &peer.0)?;

        let conv = ConversationId::Direct(peer.clone());
        // Only persist user-visible message kinds. Call signalling (Answer, Candidate,
        // IceRestartOffer, Hangup) must NOT be saved — each call generates 5-15 such
        // messages which appear as phantom "📞 call" entries in the chat. Only Invite saved.
        let should_save = match &msg.kind {
            MessageKind::Text(_)
            | MessageKind::File { .. }
            | MessageKind::BlobRef { .. }
            | MessageKind::GroupInvite { .. }
            | MessageKind::Reaction { .. } => true,
            MessageKind::Call(e) => matches!(e, crate::messaging::types::CallEvent::Invite { .. }),
            _ => false,
        };
        if should_save {
            self.messages.save_message_with_unread(&conv, msg, false)?;
        }

        let wire_bytes =
            bincode::serialize(&WireEnvelope::Dm(wire)).map_err(ADAError::Serialization)?;
        Ok((peer, wire_bytes, id))
    }

    #[inline]
    fn relay_only_enabled(&self) -> bool {
        self.relay_only.load(std::sync::atomic::Ordering::Acquire)
            || self.connection_profile().implies_relay_only()
    }

    fn connection_profile(&self) -> ConnectionProfile {
        *self.connection_profile.read()
    }

    fn mailbox_poll_interval(&self) -> Option<std::time::Duration> {
        self.connection_profile()
            .mailbox_poll_interval_secs(self.relay_only_enabled())
            .map(std::time::Duration::from_secs)
    }

    fn queue_offline_message(
        &self,
        peer: &PeerId,
        id: [u8; 16],
        wire_bytes: Vec<u8>,
    ) -> Result<u32> {
        let token = self.get_or_create_delivery_token(peer);
        let sealed = crate::network::relay::SealedMessage {
            message_id: id,
            recipient_id: peer.0,
            payload: wire_bytes,
            expires_at: crate::network::relay::next_offline_expiry_ts(),
            hops: 0,
        };
        self.relay_mgr.enqueue_offline(token, sealed)?;
        Ok(self.relay_mgr.offline_count(&token) as u32)
    }

    fn update_mailbox_depth_high_watermark(&self, queue_depth: u32) {
        let mut current = self
            .bridge_mailbox_depth_high_watermark
            .load(std::sync::atomic::Ordering::Acquire);
        while queue_depth > current {
            match self.bridge_mailbox_depth_high_watermark.compare_exchange(
                current,
                queue_depth,
                std::sync::atomic::Ordering::AcqRel,
                std::sync::atomic::Ordering::Acquire,
            ) {
                Ok(_) => break,
                Err(next) => current = next,
            }
        }
    }

    fn set_bridge_mailbox_depth(&self, queue_depth: u32) {
        self.bridge_mailbox_depth
            .store(queue_depth, std::sync::atomic::Ordering::Release);
        self.update_mailbox_depth_high_watermark(queue_depth);
    }

    fn decrement_bridge_mailbox_depth(&self) {
        let mut current = self
            .bridge_mailbox_depth
            .load(std::sync::atomic::Ordering::Acquire);
        loop {
            if current == 0 {
                return;
            }
            match self.bridge_mailbox_depth.compare_exchange(
                current,
                current - 1,
                std::sync::atomic::Ordering::AcqRel,
                std::sync::atomic::Ordering::Acquire,
            ) {
                Ok(_) => return,
                Err(next) => current = next,
            }
        }
    }

    fn record_transport_outcome_metrics(&self, route: &str, latency_ms: Option<u64>) {
        let mut route_totals = self.transport_route_totals.write();
        *route_totals.entry(route.to_string()).or_insert(0) += 1;
        drop(route_totals);

        if route == TransportRoute::Failed.as_str() {
            self.transport_failure_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        } else {
            self.transport_success_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }

        if let Some(latency_ms) = latency_ms {
            self.transport_latency_total_ms
                .fetch_add(latency_ms, std::sync::atomic::Ordering::Relaxed);
            self.transport_latency_samples
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    fn connection_health_state(&self) -> ConnectionHealthState {
        ConnectionHealthState::from_u8(
            self.connection_health_state
                .load(std::sync::atomic::Ordering::Acquire),
        )
    }

    fn set_connection_health_state(&self, next: ConnectionHealthState) {
        let prev_raw = self
            .connection_health_state
            .swap(next.to_u8(), std::sync::atomic::Ordering::AcqRel);
        let prev = ConnectionHealthState::from_u8(prev_raw);
        if prev != next {
            self.connection_state_transitions_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    fn mark_connection_degraded(&self) {
        let now_ms = unix_now_ms();
        let _ = self.connection_recovering_since_ms.compare_exchange(
            0,
            now_ms,
            std::sync::atomic::Ordering::AcqRel,
            std::sync::atomic::Ordering::Acquire,
        );
        self.set_connection_health_state(ConnectionHealthState::Degraded);
    }

    fn mark_connection_recovering(&self) {
        self.mark_connection_degraded();
        self.set_connection_health_state(ConnectionHealthState::Recovering);
    }

    fn mark_connection_recovered(&self) {
        let started_at = self
            .connection_recovering_since_ms
            .swap(0, std::sync::atomic::Ordering::AcqRel);
        if started_at == 0 {
            self.set_connection_health_state(ConnectionHealthState::Healthy);
            return;
        }

        let now_ms = unix_now_ms();
        let elapsed = now_ms.saturating_sub(started_at);
        self.connection_recovery_total_ms
            .fetch_add(elapsed, std::sync::atomic::Ordering::Relaxed);
        self.connection_recovery_events_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.set_connection_health_state(ConnectionHealthState::Healthy);
    }

    fn record_false_online_detected(&self) {
        self.connection_false_online_detected_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    fn estimate_offline_backlog_count(&self) -> u64 {
        let tokens = self.delivery_tokens.read();
        tokens
            .values()
            .map(|token| self.relay_mgr.offline_count(token) as u64)
            .sum()
    }

    async fn publish_transport_outcome(
        &self,
        message_id: [u8; 16],
        route: &str,
        queue_depth: Option<u32>,
        latency_ms: Option<u64>,
    ) {
        let route_string = route.to_string();
        let previous_route = self
            .last_transport_outcome
            .read()
            .as_ref()
            .map(|o| o.route.clone());
        *self.last_transport_outcome.write() = Some(LastTransportOutcome {
            message_id,
            route: route_string.clone(),
            queue_depth,
            latency_ms,
            updated_at_ms: unix_now_secs() as u64 * 1000,
        });

        if let Some(prev_route) = previous_route {
            if prev_route != route_string {
                self.connection_route_flaps_total
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            }
        }

        match route_string.as_str() {
            "failed" | "offline_queue" => self.mark_connection_degraded(),
            "mailbox_bridge" => self.mark_connection_recovering(),
            _ => self.mark_connection_recovered(),
        }

        self.record_transport_outcome_metrics(route, latency_ms);
        let _ = self
            .event_tx
            .send(ADAEvent::MessageRouteChanged {
                message_id,
                route: route_string,
                queue_depth,
                latency_ms,
            })
            .await;
    }

    fn bridge_manifest_cache_path(&self) -> std::path::PathBuf {
        std::path::PathBuf::from(&self.config.storage.data_dir).join("bridge_manifest_cache.json")
    }

    fn bridge_manifest_trusted_keys_path(&self) -> std::path::PathBuf {
        std::path::PathBuf::from(&self.config.storage.data_dir)
            .join("bridge_manifest_trusted_keys.json")
    }

    fn bridge_manifest_bootstrap_urls_path(&self) -> std::path::PathBuf {
        std::path::PathBuf::from(&self.config.storage.data_dir).join("bridge_manifest_urls.json")
    }

    fn parse_manifest_public_key_hex(hex_key: &str) -> Option<[u8; 32]> {
        let normalized = hex_key.trim().replace(':', "");
        let bytes = hex::decode(normalized).ok()?;
        bytes.try_into().ok()
    }

    fn persisted_manifest_public_keys(&self) -> Vec<[u8; 32]> {
        let path = self.bridge_manifest_trusted_keys_path();
        if !path.exists() {
            return Vec::new();
        }

        std::fs::read_to_string(path)
            .ok()
            .and_then(|json| serde_json::from_str::<Vec<String>>(&json).ok())
            .unwrap_or_default()
            .into_iter()
            .filter_map(|hex_key| Self::parse_manifest_public_key_hex(&hex_key))
            .collect()
    }

    fn persisted_manifest_urls(&self) -> Vec<String> {
        let path = self.bridge_manifest_bootstrap_urls_path();
        if !path.exists() {
            return Vec::new();
        }

        std::fs::read_to_string(path)
            .ok()
            .and_then(|json| serde_json::from_str::<Vec<String>>(&json).ok())
            .unwrap_or_default()
            .into_iter()
            .map(|url| url.trim().to_string())
            .filter(|url| !url.is_empty())
            .collect()
    }

    fn persist_manifest_public_key(&self, key: [u8; 32]) -> Result<()> {
        let path = self.bridge_manifest_trusted_keys_path();
        let mut keys = self
            .persisted_manifest_public_keys()
            .into_iter()
            .map(hex::encode)
            .collect::<Vec<_>>();
        let key_hex = hex::encode(key);
        if !keys
            .iter()
            .any(|entry| entry.eq_ignore_ascii_case(&key_hex))
        {
            keys.push(key_hex);
        }

        std::fs::create_dir_all(&self.config.storage.data_dir)?;
        std::fs::write(
            path,
            serde_json::to_string_pretty(&keys).map_err(ADAError::Json)?,
        )?;
        Ok(())
    }

    fn persist_manifest_url(&self, url: &str) -> Result<()> {
        let trimmed = url.trim();
        if trimmed.is_empty() {
            return Ok(());
        }

        let path = self.bridge_manifest_bootstrap_urls_path();
        let mut urls = self.persisted_manifest_urls();
        if !urls.iter().any(|entry| entry == trimmed) {
            urls.push(trimmed.to_string());
        }

        std::fs::create_dir_all(&self.config.storage.data_dir)?;
        std::fs::write(
            path,
            serde_json::to_string_pretty(&urls).map_err(ADAError::Json)?,
        )?;
        Ok(())
    }

    fn manifest_fetch_urls(&self) -> Vec<String> {
        let mut urls = Vec::new();
        for url in &self.config.bridge.manifest_urls {
            let trimmed = url.trim();
            if !trimmed.is_empty() && !urls.iter().any(|entry| entry == trimmed) {
                urls.push(trimmed.to_string());
            }
        }
        for url in self.persisted_manifest_urls() {
            if !urls.iter().any(|entry| entry == &url) {
                urls.push(url);
            }
        }
        urls
    }

    fn trusted_manifest_public_keys(&self) -> Vec<[u8; 32]> {
        let mut keys = Vec::new();
        for key in self
            .config
            .bridge
            .manifest_public_keys
            .iter()
            .filter_map(|hex_key| Self::parse_manifest_public_key_hex(hex_key))
        {
            if !keys.contains(&key) {
                keys.push(key);
            }
        }
        for key in self.persisted_manifest_public_keys() {
            if !keys.contains(&key) {
                keys.push(key);
            }
        }
        keys
    }

    fn combined_trusted_manifest_public_keys(&self, extra_key: Option<[u8; 32]>) -> Vec<[u8; 32]> {
        let mut keys = self.trusted_manifest_public_keys();
        if let Some(key) = extra_key {
            if !keys.contains(&key) {
                keys.push(key);
            }
        }
        keys
    }

    async fn apply_verified_bridge_manifest(
        &self,
        signed_manifest: SignedBridgeManifest,
        payload: BridgeManifestPayload,
        source: &str,
        persist_cache: bool,
    ) -> Result<()> {
        let runtime_bridges = payload
            .bridges
            .iter()
            .map(|entry| entry.to_runtime_bridge())
            .collect::<Result<Vec<_>>>()?;

        self.bridges
            .write()
            .await
            .replace_manifest_bridges(runtime_bridges);
        *self.bridge_manifest.write() = Some(payload);
        *self.bridge_manifest_source.write() = Some(source.to_string());

        if persist_cache {
            if let Ok(json) = serde_json::to_string_pretty(&signed_manifest) {
                let _ = std::fs::create_dir_all(&self.config.storage.data_dir);
                let _ = std::fs::write(self.bridge_manifest_cache_path(), json);
            }
        }

        Ok(())
    }

    async fn install_verified_bridge_manifest(
        &self,
        signed_manifest: SignedBridgeManifest,
        source: &str,
        persist_cache: bool,
    ) -> Result<()> {
        let trusted_keys = self.trusted_manifest_public_keys();
        let payload = signed_manifest.verify(&trusted_keys)?;
        self.apply_verified_bridge_manifest(signed_manifest, payload, source, persist_cache)
            .await
    }

    async fn restore_cached_bridge_manifest(&self) -> Result<()> {
        let cache_path = self.bridge_manifest_cache_path();
        if !cache_path.exists() {
            return Ok(());
        }
        let json = std::fs::read_to_string(cache_path)?;
        let signed_manifest: SignedBridgeManifest = serde_json::from_str(&json)?;

        // GAP-6: Verify and check TTL before applying — stale manifests must not
        // remain active indefinitely after the operator rotates bridge configs.
        let trusted_keys = self.trusted_manifest_public_keys();
        let payload = signed_manifest.verify(&trusted_keys)?;
        if payload.ttl_secs > 0 {
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;
            if now_ms > payload.issued_at_ms.saturating_add(payload.ttl_secs * 1000) {
                tracing::warn!(
                    "[manifest] cached manifest expired (issued_at_ms={}, ttl_secs={}) — skipping cache, fresh fetch required",
                    payload.issued_at_ms, payload.ttl_secs
                );
                return Ok(());
            }
        }

        self.apply_verified_bridge_manifest(signed_manifest, payload, "cache", false)
            .await
    }

    pub async fn fetch_bridge_manifest(&self) -> Result<()> {
        let urls = self.manifest_fetch_urls();
        if urls.is_empty() {
            return Err(ADAError::Bridge(
                "no bridge manifest URLs configured".into(),
            ));
        }

        let client = reqwest::Client::builder()
            .use_rustls_tls()
            .build()
            .map_err(|e| ADAError::Bridge(format!("manifest client: {}", e)))?;

        let mut last_error = None;

        for url in &urls {
            // ── Pass 1: plain HTTPS (works on unrestricted / lightly-filtered networks)
            let plain_result = async {
                match client.get(url).send().await {
                    Ok(response) => match response.error_for_status() {
                        Ok(ok) => match ok.text().await {
                            Ok(text) => serde_json::from_str::<SignedBridgeManifest>(&text)
                                .map_err(ADAError::Json),
                            Err(e) => Err(ADAError::Bridge(format!("manifest body: {}", e))),
                        },
                        Err(e) => Err(ADAError::Bridge(format!("manifest status: {}", e))),
                    },
                    Err(e) => Err(ADAError::Bridge(format!("manifest fetch {}: {}", url, e))),
                }
            }
            .await;

            match plain_result {
                Ok(signed_manifest) => {
                    if let Err(error) = self
                        .install_verified_bridge_manifest(signed_manifest, url, true)
                        .await
                    {
                        last_error = Some(error);
                    } else {
                        return Ok(());
                    }
                }
                Err(plain_error) => {
                    tracing::debug!("[manifest] plain HTTPS failed for {}: {}", url, plain_error);
                    last_error = Some(plain_error);

                    // ── Pass 2: domain-fronted HTTPS (whitelist / SNI-filtered networks).
                    //    Works when the manifest is hosted on a CDN whose edge domains
                    //    are in the whitelist (e.g. Cloudflare, Google).
                    let fronted_ok = false;
                    for &front_host in crate::bridge::domain_front::CDN_FRONT_HOSTS {
                        match crate::bridge::domain_front::fetch_url_via_domain_front(
                            url, front_host,
                        )
                        .await
                        {
                            Ok(text) => {
                                match serde_json::from_str::<SignedBridgeManifest>(&text)
                                    .map_err(ADAError::Json)
                                {
                                    Ok(signed_manifest) => {
                                        let source = format!("fronted:{}", front_host);
                                        if let Err(error) = self
                                            .install_verified_bridge_manifest(
                                                signed_manifest,
                                                &source,
                                                true,
                                            )
                                            .await
                                        {
                                            last_error = Some(error);
                                        } else {
                                            tracing::info!(
                                                "[manifest] bootstrap via domain-front ({}) succeeded for {}",
                                                front_host, url
                                            );
                                            return Ok(());
                                        }
                                    }
                                    Err(e) => {
                                        tracing::debug!(
                                            "[manifest] fronted parse error (front={}): {}",
                                            front_host,
                                            e
                                        );
                                        last_error = Some(e);
                                    }
                                }
                            }
                            Err(e) => {
                                tracing::debug!(
                                    "[manifest] domain-front failed (front={}): {}",
                                    front_host,
                                    e
                                );
                            }
                        }
                        if fronted_ok {
                            break;
                        }
                    }
                }
            }
        }

        Err(last_error.unwrap_or_else(|| ADAError::Bridge("no manifest URL succeeded".into())))
    }

    pub async fn import_bridge_manifest_json(
        &self,
        manifest_json: &str,
        source: &str,
        trusted_public_key_hex: Option<&str>,
    ) -> Result<()> {
        let signed_manifest: SignedBridgeManifest = serde_json::from_str(manifest_json)?;
        let extra_key = trusted_public_key_hex
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .and_then(Self::parse_manifest_public_key_hex);

        if trusted_public_key_hex
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .is_some()
            && extra_key.is_none()
        {
            return Err(ADAError::Bridge(
                "manifest public key must be 32-byte hex".into(),
            ));
        }

        let trusted_keys = self.combined_trusted_manifest_public_keys(extra_key);
        let payload = signed_manifest.verify(&trusted_keys)?;
        self.apply_verified_bridge_manifest(signed_manifest, payload, source, true)
            .await?;

        if let Some(key) = extra_key {
            if let Err(error) = self.persist_manifest_public_key(key) {
                tracing::warn!("persist manifest public key failed: {}", error);
            }
        }

        Ok(())
    }

    pub async fn import_bridge_manifest_url(
        &self,
        url: &str,
        trusted_public_key_hex: Option<&str>,
    ) -> Result<()> {
        let client = reqwest::Client::builder()
            .use_rustls_tls()
            .build()
            .map_err(|e| ADAError::Bridge(format!("manifest client: {}", e)))?;

        let response = client
            .get(url)
            .send()
            .await
            .map_err(|e| ADAError::Bridge(format!("manifest fetch {}: {}", url, e)))?;
        let body = response
            .error_for_status()
            .map_err(|e| ADAError::Bridge(format!("manifest status: {}", e)))?
            .text()
            .await
            .map_err(|e| ADAError::Bridge(format!("manifest body: {}", e)))?;

        self.import_bridge_manifest_json(&body, url, trusted_public_key_hex)
            .await?;

        if let Err(error) = self.persist_manifest_url(url) {
            tracing::warn!("persist manifest url failed: {}", error);
        }

        Ok(())
    }

    async fn current_transport_policy(
        &self,
        class: DeliveryClass,
        allow_local_offline_queue: bool,
    ) -> TransportPolicy {
        let has_bridges = !self.bridges.read().await.bridges().is_empty();
        let relay_only = self.relay_only_enabled();
        let profile = self.connection_profile();
        let require_live_delivery = matches!(class, DeliveryClass::CallSignaling);
        let allow_mailbox = has_bridges && !require_live_delivery;

        TransportPolicy {
            relay_only,
            allow_iroh: !relay_only,
            allow_bridge: has_bridges,
            allow_mailbox,
            allow_mailbox_pull: allow_mailbox && (relay_only || profile.enables_mailbox_pull()),
            allow_local_offline_queue: allow_local_offline_queue && !require_live_delivery,
            require_live_delivery,
        }
    }

    async fn current_route_capabilities(&self) -> RouteCapabilities {
        let bridge_ready = !self.bridges.read().await.bridges().is_empty();
        let router = TransportRouter::new(
            self.current_transport_policy(DeliveryClass::DirectMessage, true)
                .await,
        );
        router.capabilities(
            bridge_ready,
            self.bridge_listener_connected
                .load(std::sync::atomic::Ordering::Acquire),
            self.config.bridge.max_censored_attachment_bytes,
        )
    }

    async fn try_deliver_via_iroh(&self, peer: &PeerId, wire_bytes: Vec<u8>) -> bool {
        let guard = self.iroh.read().await;
        if let Some(iroh) = guard.as_ref() {
            if iroh.is_connected(&peer.0).await {
                match iroh.send(&peer.0, wire_bytes.clone()).await {
                    Ok(()) => {
                        self.iroh_consecutive_failures
                            .store(0, std::sync::atomic::Ordering::Relaxed);
                        return true;
                    }
                    Err(e) => {
                        tracing::debug!("iroh cached send failed ({}), trying cold connect", e);
                    }
                }
            }

            if iroh.send(&peer.0, wire_bytes).await.is_ok() {
                self.iroh_consecutive_failures
                    .store(0, std::sync::atomic::Ordering::Relaxed);
                return true;
            }
        }

        // iroh delivery failed — track consecutive failures.
        let prev = self
            .iroh_consecutive_failures
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if prev + 1 >= IROH_CONSECUTIVE_FAIL_THRESHOLD {
            tracing::warn!(
                "iroh: {} consecutive send failures — emitting NetworkDisconnected",
                prev + 1,
            );
            self.mark_connection_degraded();
            let _ = self.event_tx.send(ADAEvent::NetworkDisconnected).await;
        }

        false
    }

    async fn try_deliver_via_bridge(
        &self,
        peer: &PeerId,
        message_id: [u8; 16],
        wire_bytes: Vec<u8>,
        class: DeliveryClass,
        require_live_delivery: bool,
    ) -> Result<RoutedTransportOutcome> {
        let envelope = BridgeEnvelope {
            message_id,
            sender: self.identity.peer_id.0,
            recipient: peer.0,
            lane: BridgeDeliveryLane::from(class),
            wire_bytes,
            created_at_ms: unix_now_secs() as u64 * 1000,
            expires_at: crate::network::relay::next_offline_expiry_ts(),
        };
        let started_at = std::time::Instant::now();

        match self
            .try_deliver_via_framed_bridge(envelope.clone(), require_live_delivery, started_at)
            .await
        {
            Ok(outcome) => Ok(outcome),
            Err(framed_error) => match self
                .try_deliver_via_http_mailbox(envelope, require_live_delivery, started_at)
                .await
            {
                Ok(outcome) => Ok(outcome),
                Err(http_error) => Err(ADAError::Bridge(format!(
                    "bridge delivery failed; framed route: {}; http mailbox: {}",
                    framed_error, http_error
                ))),
            },
        }
    }

    async fn try_deliver_via_framed_bridge(
        &self,
        envelope: BridgeEnvelope,
        require_live_delivery: bool,
        started_at: std::time::Instant,
    ) -> Result<RoutedTransportOutcome> {
        let mut connected = self
            .bridges
            .write()
            .await
            .connect_via_best_transport()
            .await?;
        let wire_format = connected.bridge.wire_format;
        let register_auth = fresh_bridge_auth();
        let register_signature = self.identity.sign(&register_challenge(
            &self.identity.peer_id.0,
            &register_auth,
        )?);

        send_frame(
            &mut connected.connection,
            &BridgeFrame::Register {
                peer_id: self.identity.peer_id.0,
                signature: register_signature,
                listen_for_mailbox: false,
                auth: register_auth,
            },
            wire_format,
        )
        .await?;

        match recv_frame(&mut connected.connection, wire_format).await? {
            BridgeFrame::RegisterOk {
                bridge_fingerprint, ..
            } => {
                verify_bridge_fingerprint(&connected.bridge.fingerprint, &bridge_fingerprint)?;
            }
            BridgeFrame::Error { message } => {
                return Err(ADAError::Bridge(message));
            }
            other => {
                return Err(ADAError::Bridge(format!(
                    "unexpected bridge register response: {:?}",
                    other
                )));
            }
        }

        let message_id = envelope.message_id;
        send_frame(
            &mut connected.connection,
            &BridgeFrame::Push { envelope },
            wire_format,
        )
        .await?;

        match recv_frame(&mut connected.connection, wire_format).await? {
            BridgeFrame::PushAck {
                disposition,
                queue_depth,
            } => {
                let live_delivery = matches!(disposition, BridgePushDisposition::LiveBridge);
                if require_live_delivery && !live_delivery {
                    return Err(ADAError::Bridge(
                        "bridge route downgraded to mailbox while live delivery is required".into(),
                    ));
                }

                self.set_bridge_mailbox_depth(queue_depth);

                Ok(RoutedTransportOutcome::new(
                    message_id,
                    route_from_bridge_protocol(&connected.bridge.protocol, live_delivery),
                )
                .with_live_delivery(live_delivery)
                .with_queue_depth(queue_depth)
                .with_latency(started_at.elapsed().as_millis() as u64))
            }
            BridgeFrame::Error { message } => Err(ADAError::Bridge(message)),
            other => Err(ADAError::Bridge(format!(
                "unexpected bridge push response: {:?}",
                other
            ))),
        }
    }

    fn http_mailbox_url_for_bridge_path(bridge: &BridgeConfig, path: &str) -> Result<String> {
        let scheme = if bridge.insecure { "http" } else { "https" };
        let host = bridge.hostname.as_deref().unwrap_or(&bridge.address);
        let host = if host.parse::<std::net::Ipv6Addr>().is_ok() {
            format!("[{}]", host)
        } else {
            host.to_string()
        };
        let mut url = reqwest::Url::parse(&format!("{}://{}", scheme, host))
            .map_err(|e| ADAError::Bridge(format!("http mailbox URL: {}", e)))?;
        url.set_port(Some(bridge.port))
            .map_err(|_| ADAError::Bridge("http mailbox URL: invalid port".into()))?;
        url.set_path(path);
        Ok(url.to_string())
    }

    fn http_mailbox_url_for_bridge(bridge: &BridgeConfig) -> Result<String> {
        Self::http_mailbox_url_for_bridge_path(bridge, "/mailbox/push")
    }

    async fn http_mailbox_candidates(&self) -> Vec<BridgeConfig> {
        let mut candidates = self
            .bridges
            .read()
            .await
            .bridges()
            .iter()
            .filter(|bridge| bridge.is_active)
            .filter(|bridge| matches!(bridge.protocol, BridgeProtocol::WebSocketTLS))
            .cloned()
            .collect::<Vec<_>>();
        candidates.sort_by(|a, b| b.priority.cmp(&a.priority));
        candidates
    }

    fn http_mailbox_client() -> Result<reqwest::Client> {
        reqwest::Client::builder()
            .use_rustls_tls()
            .timeout(std::time::Duration::from_secs(
                BRIDGE_HTTP_MAILBOX_TIMEOUT_SECS,
            ))
            .connect_timeout(std::time::Duration::from_secs(
                BRIDGE_HTTP_MAILBOX_TIMEOUT_SECS,
            ))
            .build()
            .map_err(|e| ADAError::Bridge(format!("http mailbox client: {}", e)))
    }

    async fn try_deliver_via_http_mailbox(
        &self,
        envelope: BridgeEnvelope,
        require_live_delivery: bool,
        started_at: std::time::Instant,
    ) -> Result<RoutedTransportOutcome> {
        let candidates = self.http_mailbox_candidates().await;
        let client = Self::http_mailbox_client()?;

        let mut last_error = None;
        for bridge in candidates {
            let url = Self::http_mailbox_url_for_bridge(&bridge)?;
            let auth = fresh_bridge_auth();
            let signature = self.identity.sign(&http_push_challenge(&envelope, &auth)?);
            let request = HttpPushRequest {
                sender: self.identity.peer_id.0,
                signature,
                auth,
                envelope: envelope.clone(),
            };

            let result = async {
                let response = client
                    .post(&url)
                    .json(&request)
                    .send()
                    .await
                    .map_err(|e| ADAError::Bridge(format!("http mailbox push {}: {}", url, e)))?
                    .error_for_status()
                    .map_err(|e| ADAError::Bridge(format!("http mailbox status {}: {}", url, e)))?;
                let response = response.json::<HttpPushResponse>().await.map_err(|e| {
                    ADAError::Bridge(format!("http mailbox response {}: {}", url, e))
                })?;
                let bridge_fingerprint = response.bridge_fingerprint.ok_or_else(|| {
                    ADAError::Bridge("http mailbox response missing bridge fingerprint".into())
                })?;
                verify_bridge_fingerprint(&bridge.fingerprint, &bridge_fingerprint)?;
                let live_delivery =
                    matches!(response.disposition, BridgePushDisposition::LiveBridge);
                if require_live_delivery && !live_delivery {
                    return Err(ADAError::Bridge(
                        "http mailbox route downgraded to mailbox while live delivery is required"
                            .into(),
                    ));
                }
                self.set_bridge_mailbox_depth(response.queue_depth);
                Ok(
                    RoutedTransportOutcome::new(envelope.message_id, TransportRoute::MailboxBridge)
                        .with_live_delivery(live_delivery)
                        .with_queue_depth(response.queue_depth)
                        .with_latency(started_at.elapsed().as_millis() as u64),
                )
            }
            .await;

            match result {
                Ok(outcome) => {
                    self.bridges.write().await.mark_reachable(&bridge.id);
                    return Ok(outcome);
                }
                Err(error) => {
                    self.bridges.write().await.mark_failed(&bridge.id);
                    last_error = Some(error);
                }
            }
        }

        Err(last_error
            .unwrap_or_else(|| ADAError::Bridge("no HTTP mailbox bridge available".into())))
    }

    pub async fn poll_http_mailbox_once(&self) -> Result<u32> {
        let policy = self
            .current_transport_policy(DeliveryClass::MaintenanceRetry, false)
            .await;
        if !policy.allow_mailbox_pull {
            return Ok(0);
        }

        let candidates = self.http_mailbox_candidates().await;
        let client = Self::http_mailbox_client()?;
        let mut last_error = None;

        for bridge in candidates {
            let result = self.poll_http_mailbox_from_bridge(&client, &bridge).await;
            match result {
                Ok(handled) => {
                    self.bridges.write().await.mark_reachable(&bridge.id);
                    return Ok(handled);
                }
                Err(error) => {
                    self.bridges.write().await.mark_failed(&bridge.id);
                    last_error = Some(error);
                }
            }
        }

        Err(last_error
            .unwrap_or_else(|| ADAError::Bridge("no HTTP mailbox bridge available".into())))
    }

    async fn poll_http_mailbox_from_bridge(
        &self,
        client: &reqwest::Client,
        bridge: &BridgeConfig,
    ) -> Result<u32> {
        let peer_id = self.identity.peer_id.0;
        let auth = fresh_bridge_auth();
        let signature = self.identity.sign(&http_pull_challenge(&peer_id, &auth)?);
        let request = HttpPullRequest {
            peer_id,
            signature,
            auth,
        };
        let url = Self::http_mailbox_url_for_bridge_path(bridge, "/mailbox/pull")?;
        let response = client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ADAError::Bridge(format!("http mailbox pull {}: {}", url, e)))?
            .error_for_status()
            .map_err(|e| ADAError::Bridge(format!("http mailbox pull status {}: {}", url, e)))?;
        let response = response
            .json::<HttpPullResponse>()
            .await
            .map_err(|e| ADAError::Bridge(format!("http mailbox pull response {}: {}", url, e)))?;
        let bridge_fingerprint = response.bridge_fingerprint.ok_or_else(|| {
            ADAError::Bridge("http mailbox pull response missing bridge fingerprint".into())
        })?;
        verify_bridge_fingerprint(&bridge.fingerprint, &bridge_fingerprint)?;

        let pulled_count = response.envelopes.len() as u32;
        self.set_bridge_mailbox_depth(pulled_count);

        let mut ack_ids = Vec::new();
        for envelope in response.envelopes {
            if envelope.recipient != peer_id {
                tracing::warn!(
                    "http mailbox delivered envelope for unexpected recipient: {}",
                    hex::encode(envelope.recipient)
                );
                continue;
            }
            match self.handle_bridge_delivery(envelope.clone()).await {
                Ok(()) => ack_ids.push(envelope.message_id),
                Err(error) => tracing::warn!("http mailbox delivery handling failed: {}", error),
            }
        }

        if ack_ids.is_empty() {
            return Ok(0);
        }

        let remaining = self
            .ack_http_mailbox(client, bridge, peer_id, ack_ids)
            .await?;
        self.set_bridge_mailbox_depth(remaining);
        Ok(pulled_count.saturating_sub(remaining))
    }

    async fn ack_http_mailbox(
        &self,
        client: &reqwest::Client,
        bridge: &BridgeConfig,
        peer_id: [u8; 32],
        message_ids: Vec<[u8; 16]>,
    ) -> Result<u32> {
        let auth = fresh_bridge_auth();
        let signature = self
            .identity
            .sign(&http_ack_challenge(&peer_id, &message_ids, &auth)?);
        let request = HttpAckRequest {
            peer_id,
            signature,
            auth,
            message_ids,
        };
        let url = Self::http_mailbox_url_for_bridge_path(bridge, "/mailbox/ack")?;
        let response = client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ADAError::Bridge(format!("http mailbox ack {}: {}", url, e)))?
            .error_for_status()
            .map_err(|e| ADAError::Bridge(format!("http mailbox ack status {}: {}", url, e)))?;
        let response = response
            .json::<HttpAckResponse>()
            .await
            .map_err(|e| ADAError::Bridge(format!("http mailbox ack response {}: {}", url, e)))?;
        let bridge_fingerprint = response.bridge_fingerprint.ok_or_else(|| {
            ADAError::Bridge("http mailbox ack response missing bridge fingerprint".into())
        })?;
        verify_bridge_fingerprint(&bridge.fingerprint, &bridge_fingerprint)?;
        Ok(response.remaining)
    }

    async fn route_wire_with_policy(
        &self,
        peer: &PeerId,
        message_id: [u8; 16],
        wire_bytes: Vec<u8>,
        class: DeliveryClass,
        allow_local_offline_queue: bool,
    ) -> Result<RoutedTransportOutcome> {
        let policy = self
            .current_transport_policy(class, allow_local_offline_queue)
            .await;
        let bridge_ready = !self.bridges.read().await.bridges().is_empty();
        let router = TransportRouter::new(policy.clone());
        let mut last_error = None;
        let mut iroh_failed_before_bridge_success = false;

        for attempt in router.attempts(bridge_ready) {
            match attempt {
                RouteAttempt::LocalMesh => {
                    // We check if we have a BLE or Wifi Direct link established for this peer.
                    // If so, we emit `ADAEvent::SendViaLocalMesh` requesting Android
                    // to pump exactly `wire_bytes` over an open BLE socket, and return successfully.
                    if self.active_mesh_peers.read().contains(peer) {
                        let _ = self
                            .event_tx
                            .send(ADAEvent::SendViaLocalMesh {
                                peer: peer.clone(),
                                payload: wire_bytes.clone(),
                            })
                            .await;
                        return Ok(RoutedTransportOutcome::new(
                            message_id,
                            TransportRoute::LocalMesh,
                        ));
                    }
                }
                RouteAttempt::IrohLive => {
                    let started_at = std::time::Instant::now();
                    let quick_failover_enabled = bridge_ready && policy.allow_bridge;
                    if quick_failover_enabled {
                        match tokio::time::timeout(
                            tokio::time::Duration::from_millis(IROH_FAST_FAILOVER_TIMEOUT_MS),
                            self.try_deliver_via_iroh(peer, wire_bytes.clone()),
                        )
                        .await
                        {
                            Ok(true) => {
                                return Ok(RoutedTransportOutcome::new(
                                    message_id,
                                    TransportRoute::IrohLive,
                                )
                                .with_live_delivery(true)
                                .with_latency(started_at.elapsed().as_millis() as u64));
                            }
                            Ok(false) => {
                                iroh_failed_before_bridge_success = true;
                            }
                            Err(_) => {
                                self.mark_connection_degraded();
                                iroh_failed_before_bridge_success = true;
                                last_error = Some(ADAError::Network(
                                    "iroh quick attempt timed out; switching to bridge".into(),
                                ));
                            }
                        }
                    } else if self.try_deliver_via_iroh(peer, wire_bytes.clone()).await {
                        return Ok(RoutedTransportOutcome::new(
                            message_id,
                            TransportRoute::IrohLive,
                        )
                        .with_live_delivery(true)
                        .with_latency(started_at.elapsed().as_millis() as u64));
                    } else {
                        iroh_failed_before_bridge_success = true;
                    }
                }
                RouteAttempt::Bridge => {
                    match self
                        .try_deliver_via_bridge(
                            peer,
                            message_id,
                            wire_bytes.clone(),
                            class,
                            policy.require_live_delivery,
                        )
                        .await
                    {
                        Ok(outcome) => {
                            if iroh_failed_before_bridge_success {
                                self.mark_connection_recovering();
                                self.record_false_online_detected();
                            }
                            return Ok(outcome);
                        }
                        Err(error) => last_error = Some(error),
                    }
                }
                RouteAttempt::LocalOfflineQueue => {
                    let queue_depth =
                        self.queue_offline_message(peer, message_id, wire_bytes.clone())?;
                    return Ok(RoutedTransportOutcome::new(
                        message_id,
                        TransportRoute::OfflineQueue,
                    )
                    .with_queue_depth(queue_depth));
                }
            }
        }

        Err(last_error.unwrap_or_else(|| ADAError::Network("no transport route available".into())))
    }

    /// Attempt to deliver pre-serialised wire bytes via iroh QUIC.
    /// Returns `true` if the transport succeeded.
    async fn try_deliver_wire(&self, peer: &PeerId, wire_bytes: Vec<u8>) -> bool {
        if self.relay_only_enabled() {
            tracing::info!(
                target: "ada.network",
                peer = %peer,
                bytes = wire_bytes.len(),
                "relay_only active: skipping live iroh delivery"
            );
            return false;
        }

        self.try_deliver_via_iroh(peer, wire_bytes).await
    }

    pub async fn send_message(&self, msg: Message) -> Result<[u8; 16]> {
        let (peer, wire_bytes, id) = self.prepare_message(msg).await?;
        match self
            .route_wire_with_policy(&peer, id, wire_bytes, DeliveryClass::DirectMessage, true)
            .await
        {
            Ok(outcome) => {
                tracing::info!(
                    target: "ada.network",
                    peer = %peer,
                    route = outcome.route.as_str(),
                    queue_depth = outcome.queue_depth,
                    latency_ms = outcome.latency_ms,
                    live_delivery = outcome.live_delivery,
                    "message delivery outcome"
                );
                if !matches!(outcome.route, TransportRoute::OfflineQueue) {
                    let _ = self.messages.update_status(&id, MessageStatus::Sent);
                    let _ = self
                        .event_tx
                        .send(ADAEvent::MessageStatusChanged {
                            message_id: id,
                            status: MessageStatus::Sent,
                        })
                        .await;
                }
                self.publish_transport_outcome(
                    id,
                    outcome.route.as_str(),
                    outcome.queue_depth,
                    outcome.latency_ms,
                )
                .await;
            }
            Err(e) => {
                let reason = format!("transport delivery failed: {}", e);
                tracing::error!(
                    target: "ada.network",
                    peer = %peer,
                    error = %reason,
                    "message delivery failed"
                );
                let _ = self
                    .messages
                    .update_status(&id, MessageStatus::Failed(reason.clone()));
                let _ = self
                    .event_tx
                    .send(ADAEvent::MessageStatusChanged {
                        message_id: id,
                        status: MessageStatus::Failed(reason.clone()),
                    })
                    .await;
                self.publish_transport_outcome(id, TransportRoute::Failed.as_str(), None, None)
                    .await;
                return Err(ADAError::Network(reason));
            }
        }
        Ok(id)
    }

    fn build_sync_request_payload(
        &self,
        peer: &PeerId,
    ) -> (Option<u64>, Vec<[u8; 16]>, Option<u64>, Option<u32>) {
        let conv = ConversationId::Direct(peer.clone());
        let cursor_before_ts = self
            .sync_peer_cursor_before_ts
            .read()
            .get(&peer.0)
            .copied()
            .flatten();
        let recent = self
            .messages
            .get_messages(&conv, cursor_before_ts, SYNC_KNOWN_IDS_MAX);
        let latest_message_ts = recent.iter().map(|m| m.timestamp).max();
        let known_message_ids = if cursor_before_ts.is_some() {
            // Cursor mode should stay lightweight for very large dialogs.
            Vec::new()
        } else {
            recent.iter().map(|m| m.id).collect::<Vec<_>>()
        };
        (
            latest_message_ts,
            known_message_ids,
            cursor_before_ts,
            Some(SYNC_MESSAGES_PER_RESPONSE_MAX as u32),
        )
    }

    fn build_sync_response_payload(
        &self,
        peer: &PeerId,
        latest_message_ts: Option<u64>,
        known_message_ids: &[[u8; 16]],
        cursor_before_ts: Option<u64>,
        max_messages: Option<u32>,
    ) -> (Vec<Message>, bool, Option<u64>) {
        let conv = ConversationId::Direct(peer.clone());
        let known = known_message_ids
            .iter()
            .copied()
            .collect::<std::collections::HashSet<[u8; 16]>>();

        let limit = max_messages
            .unwrap_or(SYNC_MESSAGES_PER_RESPONSE_MAX as u32)
            .clamp(1, SYNC_MESSAGES_PER_RESPONSE_MAX as u32) as usize;

        let mut candidates =
            self.messages
                .get_messages(&conv, cursor_before_ts, SYNC_MESSAGES_SCAN_WINDOW);
        candidates.retain(|m| {
            let is_unknown_id = !known.contains(&m.id);
            let is_newer_than_peer = latest_message_ts
                .map(|latest| m.timestamp > latest)
                .unwrap_or(true);
            is_unknown_id || is_newer_than_peer
        });
        paginate_sync_candidates(candidates, limit)
    }

    async fn send_sync_response_to_peer(
        &self,
        peer: &PeerId,
        messages: Vec<Message>,
        has_more: bool,
        next_cursor_before_ts: Option<u64>,
    ) {
        let msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::SyncResponse {
                messages,
                has_more,
                next_cursor_before_ts,
            },
        );
        let (peer_id, wire_bytes, msg_id) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("sync response prepare failed for {}: {}", peer, e);
                return;
            }
        };

        if let Err(e) = self
            .route_wire_with_policy(
                &peer_id,
                msg_id,
                wire_bytes,
                DeliveryClass::MaintenanceRetry,
                false,
            )
            .await
        {
            tracing::debug!("sync response delivery failed for {}: {}", peer, e);
        } else {
            self.sync_rounds_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    async fn request_peer_sync(&self, peer: &PeerId, force: bool) {
        let now_ms = unix_now_ms();
        if !force {
            let last = self
                .sync_last_request_ms
                .read()
                .get(&peer.0)
                .copied()
                .unwrap_or(0);
            if now_ms.saturating_sub(last) < SYNC_REQUEST_COOLDOWN_MS {
                return;
            }
        }

        {
            let mut map = self.sync_last_request_ms.write();
            map.insert(peer.0, now_ms);
        }

        let (latest_message_ts, known_message_ids, cursor_before_ts, max_messages) =
            self.build_sync_request_payload(peer);
        let msg = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::SyncRequest {
                latest_message_ts,
                known_message_ids,
                cursor_before_ts,
                max_messages,
            },
        );
        let (peer_id, wire_bytes, msg_id) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("sync request prepare failed for {}: {}", peer, e);
                return;
            }
        };

        if let Err(e) = self
            .route_wire_with_policy(
                &peer_id,
                msg_id,
                wire_bytes,
                DeliveryClass::MaintenanceRetry,
                false,
            )
            .await
        {
            tracing::debug!("sync request delivery failed for {}: {}", peer, e);
        } else {
            self.sync_rounds_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    async fn apply_sync_response_from_peer(&self, peer: &PeerId, messages: Vec<Message>) -> usize {
        let mut inserted = 0usize;
        let mut duplicates = 0usize;
        let conv = ConversationId::Direct(peer.clone());

        for mut synced in messages {
            if self.messages.get_message_by_id(&synced.id).is_some() {
                duplicates = duplicates.saturating_add(1);
                continue;
            }

            if let MessageKind::Edit { target_msg_id, .. } = &synced.kind {
                if self
                    .messages
                    .save_hidden_message(&conv, synced.clone())
                    .is_ok()
                {
                    inserted = inserted.saturating_add(1);
                    let _ = self
                        .event_tx
                        .send(ADAEvent::MessageEdited {
                            target_message_id: *target_msg_id,
                        })
                        .await;
                }
                continue;
            }

            if synced.sender == *peer {
                synced.status = MessageStatus::Delivered;
            }

            if self
                .messages
                .save_message_with_unread(&conv, synced.clone(), synced.sender == *peer)
                .is_ok()
            {
                inserted = inserted.saturating_add(1);
                if synced.sender == *peer {
                    let _ = self.event_tx.send(ADAEvent::MessageReceived(synced)).await;
                }
            }
        }

        self.sync_messages_applied_total
            .fetch_add(inserted as u64, std::sync::atomic::Ordering::Relaxed);
        self.sync_duplicates_skipped_total
            .fetch_add(duplicates as u64, std::sync::atomic::Ordering::Relaxed);
        self.connection_resync_backlog_count
            .store(inserted as u64, std::sync::atomic::Ordering::Release);

        inserted
    }

    /// Send an encrypted message to a group (unicast fan-out via iroh)
    pub async fn send_group_message(&self, msg: Message) -> Result<[u8; 16]> {
        self.send_group_message_internal(msg, true).await
    }

    async fn send_group_hidden_message(&self, msg: Message) -> Result<[u8; 16]> {
        self.send_group_message_internal(msg, false).await
    }

    async fn send_group_message_internal(
        &self,
        mut msg: Message,
        visible: bool,
    ) -> Result<[u8; 16]> {
        let group_id = msg.group_id.ok_or(ADAError::Group("No group ID".into()))?;
        let id = msg.id;

        msg.signature = self.identity.sign(&msg.bytes_to_sign());
        let plaintext = bincode::serialize(&msg).map_err(ADAError::Serialization)?;

        // Encrypt with Sender Key
        let encrypted = self.groups.encrypt_group_message(group_id, &plaintext)?;

        // Store — always store before attempting delivery so the message is
        // visible in the UI regardless of network state.
        let conv = ConversationId::Group(group_id);
        if visible {
            self.messages.save_message_with_unread(&conv, msg, false)?;
        } else {
            self.messages.save_hidden_message(&conv, msg)?;
        }

        // Fan-out via iroh unicast to each group member (excluding ourselves).
        // Each member gets an independent delivery attempt; unreachable members
        // are queued in the offline relay for automatic retry (same as DMs).
        let envelope = WireEnvelope::Group(encrypted);
        let wire_bytes = bincode::serialize(&envelope).map_err(ADAError::Serialization)?;
        let members = self
            .groups
            .get_group(&group_id)
            .map(|g| {
                g.members
                    .iter()
                    .map(|m| m.peer_id.clone())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        for member_peer in &members {
            if member_peer == &self.identity.peer_id {
                continue;
            }
            self.deliver_wire(member_peer, id, wire_bytes.clone()).await;
        }

        Ok(id)
    }

    /// Process an incoming encrypted wire message (called by network layer)
    pub async fn receive_encrypted_wire(&self, wire: EncryptedWire) -> Result<()> {
        let mut prekeys = self.prekeys.write().await;

        // C-2 fix: if the sender targeted one of our incognito ephemeral IKs
        // (wire.target_ik_public is set and matches an entry in ephemeral_aliases),
        // look up the corresponding ephemeral secret and pass it to decrypt_from
        // so the X3DH shared secret matches what the sender computed.
        let ik_override: Option<x25519_dalek::StaticSecret> =
            wire.target_ik_public.and_then(|target| {
                let aliases = self.ephemeral_aliases.read();
                if aliases.contains_key(&target) {
                    // Load the secret from KV (keyed by the PeerId of the sender session
                    // we opened incognito for — but we only have target pub here, so
                    // iterate over aliases to find which peer it maps to).
                    // The ephemeral secret is stored under "incognito/ik/<peer_b64>" where
                    // peer_b64 is the conversation's Direct peer.
                    let conv = aliases.get(&target)?;
                    let peer_b64 = match conv {
                        crate::messaging::store::ConversationId::Direct(p) => p.to_base64(),
                        _ => return None,
                    };
                    let kv_key = format!("incognito/ik/{}", peer_b64);
                    let bytes = self.id_store.kv().get(&kv_key)?;
                    let arr: [u8; 32] = bytes.try_into().ok()?;
                    Some(x25519_dalek::StaticSecret::from(arr))
                } else {
                    None
                }
            });

        let plaintext = self
            .sessions
            .decrypt_from(&wire, &mut prekeys, ik_override.as_ref())?;

        let msg: Message = bincode::deserialize(&plaintext)
            .map_err(|e| ADAError::Message(format!("Deserialize: {}", e)))?;

        // ── Validate timestamp: reject messages claiming to be far in the
        //    past/future to prevent replay attacks and sorting exploits.
        {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();
            // Accept relay-delivered offline messages for as long as the queue retains
            // them, plus a small grace window for device clock skew / retry jitter.
            let min_ts = now.saturating_sub(
                crate::network::relay::OFFLINE_MESSAGE_TTL_SECS + MESSAGE_TIMESTAMP_PAST_GRACE_SECS,
            );
            let max_ts = now.saturating_add(MESSAGE_TIMESTAMP_FUTURE_SKEW_SECS);
            if msg.timestamp < min_ts || msg.timestamp > max_ts {
                return Err(ADAError::Message(format!(
                    "Message timestamp out of acceptable range: {}",
                    msg.timestamp
                )));
            }
        }

        // ── Validate message-kind payload sizes to prevent OOM.
        const MAX_TEXT_BYTES: usize = 64 * 1024; // 64 KiB
        if let MessageKind::Text(ref s) = msg.kind {
            if s.len() > MAX_TEXT_BYTES {
                return Err(ADAError::Message(
                    "Text message exceeds maximum allowed size".into(),
                ));
            }
        }

        // ── 9.15 Replay protection: reject messages whose ID we have already
        //    stored.  The Double Ratchet provides a first layer of protection
        //    (each counter can only be used once per session), but this
        //    application-layer dedup covers group fan-out messages and any
        //    edge cases where the same plaintext reaches us twice.
        if self.messages.get_message_by_id(&msg.id).is_some() {
            tracing::debug!(
                "replay: dropping duplicate message id={}",
                hex::encode(msg.id)
            );
            return Ok(());
        }

        // ── 9.16 Unconditional signature verification.
        //    `PeerId` == user's Ed25519 verifying key (32 bytes), so we can
        //    always verify the signature directly against `msg.sender` —
        //    no stored contact bundle needed.  This guarantees authenticity
        //    for ALL inbound messages, not only from known contacts.
        {
            use ed25519_dalek::{Signature, Verifier, VerifyingKey};
            let vk =
                VerifyingKey::from_bytes(&msg.sender.0).map_err(|_| ADAError::InvalidSignature)?;
            let sig_bytes: [u8; 64] = msg
                .signature
                .as_slice()
                .try_into()
                .map_err(|_| ADAError::InvalidSignature)?;
            let sig = Signature::from_bytes(&sig_bytes);
            vk.verify(&msg.bytes_to_sign(), &sig)
                .map_err(|_| ADAError::InvalidSignature)?;
        }

        // Look up sender bundle — used only for display_name resolution.
        let sender_bundle = self.id_store.load_peer_bundle(&wire.sender)?;

        // Store the message
        let conv = match &msg.group_id {
            Some(gid) => ConversationId::Group(*gid),
            None => ConversationId::Direct(wire.sender.clone()),
        };

        // Ensure conversation has a display_name. If we haven't scanned the sender's
        // QR yet, fall back to a truncated peer_id so the conversation list isn't blank.
        let display_name = sender_bundle
            .as_ref()
            .map(|b| b.display_name.as_str())
            .filter(|n| !n.is_empty())
            .map(|n| n.to_string())
            .unwrap_or_else(|| {
                let b64 = wire.sender.to_base64();
                format!("{}…", &b64[..8.min(b64.len())])
            });
        // Only persist and notify for user-visible messages.
        // Call signalling (Answer, Candidate, IceRestartOffer, Hangup) are NOT saved
        // — they produce 5-15 phantom "📞 call" entries per call. Only Invite saved.
        // IrohHint is internal-only and must NOT create a conversation entry (would
        // appear as a phantom empty conversation in the list).
        let should_save = match &msg.kind {
            MessageKind::Text(_)
            | MessageKind::File { .. }
            | MessageKind::BlobRef { .. }
            | MessageKind::GroupInvite { .. }
            | MessageKind::Reaction { .. } => true,
            MessageKind::Call(e) => matches!(e, crate::messaging::types::CallEvent::Invite { .. }),
            _ => false,
        };
        if should_save {
            // Update conversation metadata only for user-visible messages so that
            // internal protocol messages (IrohHint, ICE candidates, etc.) don't
            // create phantom empty conversations in the UI.
            self.messages.upsert_conversation(&conv, &display_name);
            // Fix: received messages must be stored with Delivered status, not the
            // sender's initial Sending status.  Without this every received message
            // shows a "sending" spinner on the recipient's screen.
            let mut msg_to_save = msg.clone();
            msg_to_save.status = MessageStatus::Delivered;
            self.messages
                .save_message_with_unread(&conv, msg_to_save, true)?;
            let _ = self
                .event_tx
                .send(ADAEvent::MessageReceived(msg.clone()))
                .await;
        }

        // Sender is reachable (they just sent us something) — drain any queued
        // outbound messages for them without waiting for the 60 s maintenance cycle.
        self.flush_offline_queue_for(&wire.sender).await;

        // Handle protocol messages
        match &msg.kind {
            MessageKind::Call(call_event) => {
                let ev_name = match call_event {
                    crate::messaging::types::CallEvent::Invite { .. } => "Invite",
                    crate::messaging::types::CallEvent::Answer { .. } => "Answer",
                    crate::messaging::types::CallEvent::Candidate { .. } => "Candidate",
                    crate::messaging::types::CallEvent::Hangup { .. } => "Hangup",
                    crate::messaging::types::CallEvent::IceRestartOffer { .. } => "IceRestartOffer",
                };
                tracing::info!(
                    "call message received: {} from {}",
                    ev_name,
                    hex::encode(wire.sender.0)
                );
                self.handle_incoming_call_event(wire.sender.clone(), call_event.clone())
                    .await;
            }
            MessageKind::File {
                file_id,
                name,
                size,
                mime_type,
                checksum,
                encryption_key,
                chunk_count,
            } => {
                // Peer is announcing an incoming file transfer — accept it
                let meta = crate::transfer::TransferMeta {
                    id: *file_id,
                    peer: wire.sender.clone(),
                    file_name: name.clone(),
                    file_size: *size,
                    mime_type: mime_type.clone(),
                    checksum: *checksum,
                    encryption_key: *encryption_key,
                    chunk_count: *chunk_count,
                    chunk_size: crate::CHUNK_SIZE as u32,
                    is_outbound: false,
                };
                self.transfer_mgr.accept_transfer(meta).await;
            }
            MessageKind::FileChunk {
                transfer_id,
                index,
                total,
                data,
                chunk_checksum,
            } => {
                use crate::crypto::symmetric::EncryptedData;
                use crate::transfer::FileChunk;
                // Validate chunk indices to prevent OOM or panics from malicious input.
                if *total == 0 || *index >= *total {
                    tracing::warn!("Invalid chunk index={}/total={}, dropping", index, total);
                } else if let Ok(encrypted_data) = bincode::deserialize::<EncryptedData>(data) {
                    let chunk = FileChunk {
                        transfer_id: *transfer_id,
                        index: *index,
                        total: *total,
                        data: encrypted_data,
                        chunk_checksum: *chunk_checksum,
                    };
                    if let Err(e) = self.transfer_mgr.receive_chunk(chunk).await {
                        tracing::warn!("Chunk receive error: {}", e);
                    }
                }
            }
            MessageKind::ChunkRequest {
                transfer_id,
                missing,
            } => {
                // Peer is asking us to retransmit missing chunks
                let tid = *transfer_id;
                for idx in missing.iter().copied() {
                    let Ok(Some(chunk)) =
                        self.transfer_mgr.outbound_chunk_by_index(&tid, idx).await
                    else {
                        continue;
                    };
                    let Ok(data_bytes) = bincode::serialize(&chunk.data) else {
                        continue;
                    };
                    let kind = MessageKind::FileChunk {
                        transfer_id: tid,
                        index: chunk.index,
                        total: chunk.total,
                        data: data_bytes,
                        chunk_checksum: chunk.chunk_checksum,
                    };
                    let chunk_msg = Message::new(
                        self.identity.peer_id.clone(),
                        Some(wire.sender.clone()),
                        kind,
                    );
                    let _ = self.send_message(chunk_msg).await;
                }
            }
            MessageKind::GroupInvite {
                group_id,
                group_name,
                group_topic,
                sender_dist_bytes,
            } => {
                // Deserialize the inviter's SenderKeyDistribution
                if let Ok(inviter_dist) = bincode::deserialize::<
                    crate::group::sender_keys::SenderKeyDistribution,
                >(sender_dist_bytes)
                {
                    // Build a minimal Group struct to join
                    let mut group =
                        crate::group::types::Group::new(group_name.clone(), wire.sender.clone());
                    group.id = *group_id;
                    group.topic = group_topic.clone();

                    // Join group: installs inviter's key + generates our own sender key
                    let our_dist = self.groups.join_group_and_init(group, inviter_dist);

                    // Send our distribution back to the inviter
                    if let Ok(accept_bytes) = bincode::serialize(&our_dist) {
                        let accept_kind = MessageKind::GroupJoinAccept {
                            group_id: *group_id,
                            member_dist_bytes: accept_bytes,
                        };
                        let accept_msg = Message::new(
                            self.identity.peer_id.clone(),
                            Some(wire.sender.clone()),
                            accept_kind,
                        );
                        let _ = self.send_message(accept_msg).await;
                    }

                    let _ = self
                        .event_tx
                        .send(ADAEvent::GroupJoined {
                            group_id: *group_id,
                            group_name: group_name.clone(),
                        })
                        .await;
                }
            }
            MessageKind::GroupJoinAccept {
                group_id,
                member_dist_bytes,
            } => {
                if let Ok(dist) = bincode::deserialize::<
                    crate::group::sender_keys::SenderKeyDistribution,
                >(member_dist_bytes)
                {
                    let _ = self.groups.install_peer_key(*group_id, dist);
                }
            }
            MessageKind::Edit { target_msg_id, .. } => {
                self.messages.save_hidden_message(&conv, msg.clone())?;
                let _ = self
                    .event_tx
                    .send(ADAEvent::MessageEdited {
                        target_message_id: *target_msg_id,
                    })
                    .await;
            }
            MessageKind::DeleteRequest { target_msg_id } => {
                // Only delete if the requester is the original author of that message.
                // This prevents a peer from remotely deleting someone else's messages.
                let sender_owns_msg = self
                    .messages
                    .get_message_by_id(target_msg_id)
                    .map(|m| m.sender == wire.sender)
                    .unwrap_or(false);
                if sender_owns_msg {
                    let _ = self.messages.delete_message(target_msg_id);
                }
            }
            MessageKind::BlobRef {
                file_id,
                name,
                size,
                mime_type,
                hash,
            } => {
                // Notify UI that a blob is available for download.
                let _ = self
                    .event_tx
                    .send(ADAEvent::BlobAvailable {
                        from: wire.sender.clone(),
                        file_id: *file_id,
                        file_name: name.clone(),
                        file_size: *size,
                        mime_type: mime_type.clone(),
                        hash: *hash,
                    })
                    .await;
            }
            MessageKind::IrohHint { relay_url } => {
                // Sent by the sender right after BlobRef to pre-register their relay URL.
                // Allows the receiver to connect via iroh without waiting for pkarr DNS
                // discovery (which can take 15–60 s on a new session).
                let iroh_guard = self.iroh.read().await;
                if let Some(iroh) = iroh_guard.as_ref() {
                    if let Err(e) = iroh.add_peer_relay(&wire.sender.0, relay_url) {
                        tracing::debug!("IrohHint relay register failed: {}", e);
                    } else {
                        tracing::debug!(
                            "IrohHint: pre-registered relay '{}' for {}",
                            relay_url,
                            hex::encode(wire.sender.0)
                        );
                        let iroh_clone = Arc::clone(iroh);
                        let sender_bytes = wire.sender.0;
                        tokio::spawn(async move {
                            iroh_clone.warmup_connection(&sender_bytes).await;
                        });
                    }
                }
            }
            MessageKind::SyncRequest {
                latest_message_ts,
                known_message_ids,
                cursor_before_ts,
                max_messages,
            } => {
                if known_message_ids.len() > SYNC_KNOWN_IDS_MAX {
                    tracing::warn!(
                        "sync request from {} too large: {} > {}",
                        wire.sender,
                        known_message_ids.len(),
                        SYNC_KNOWN_IDS_MAX
                    );
                } else {
                    let (missing, has_more, next_cursor_before_ts) = self
                        .build_sync_response_payload(
                            &wire.sender,
                            *latest_message_ts,
                            known_message_ids.as_slice(),
                            *cursor_before_ts,
                            *max_messages,
                        );
                    self.send_sync_response_to_peer(
                        &wire.sender,
                        missing,
                        has_more,
                        next_cursor_before_ts,
                    )
                    .await;
                }
            }
            MessageKind::SyncResponse {
                messages,
                has_more,
                next_cursor_before_ts,
            } => {
                let inserted = self
                    .apply_sync_response_from_peer(&wire.sender, messages.clone())
                    .await;
                tracing::debug!(
                    "sync response from {} applied={} has_more={} next_cursor_before_ts={:?}",
                    wire.sender,
                    inserted,
                    has_more,
                    next_cursor_before_ts,
                );

                if *has_more {
                    self.sync_peer_cursor_before_ts
                        .write()
                        .insert(wire.sender.0, *next_cursor_before_ts);
                    self.request_peer_sync(&wire.sender, true).await;
                } else {
                    self.sync_peer_cursor_before_ts
                        .write()
                        .remove(&wire.sender.0);
                }
            }
            _ => {}
        }

        // Proactive iroh connection warmup: after receiving ANY DM from a peer we haven't
        // connected to via iroh yet, spawn a background task to establish the connection.
        // By the time a BlobAvailable event fires (often immediately after this), the
        // connection will be cached and fetch_blob can skip the pkarr DNS lookup entirely.
        {
            let iroh_guard = self.iroh.read().await;
            if let Some(iroh) = iroh_guard.as_ref() {
                if !iroh.is_connected(&wire.sender.0).await {
                    let iroh_clone = Arc::clone(iroh);
                    let sender_bytes = wire.sender.0;
                    tokio::spawn(async move {
                        iroh_clone.warmup_connection(&sender_bytes).await;
                    });
                }
            }
        }

        Ok(())
    }

    // =====================================================================
    // GROUPS
    // =====================================================================

    /// Create a new group
    pub async fn create_group(&self, name: &str) -> ([u8; 16], String) {
        let (id, _dist) = self.groups.create_group(name);
        let topic = self.groups.group_topic(&id).unwrap_or_default();
        tracing::info!("Created group '{}' with ID {}", name, hex::encode(id));
        (id, topic)
    }

    /// List groups the user is part of
    pub fn list_groups(&self) -> Vec<crate::group::Group> {
        self.groups.list_groups()
    }

    /// Invite a peer to a group (sends an encrypted DM with the group info)
    pub async fn invite_to_group(&self, group_id: [u8; 16], peer: &PeerId) -> Result<()> {
        let group = self
            .groups
            .get_group(&group_id)
            .ok_or(ADAError::Group("Group not found".into()))?;

        // Get our current sender key distribution to share
        let dist = self
            .groups
            .our_distribution(group_id)
            .ok_or(ADAError::Group("No sender key for group".into()))?;
        let sender_dist_bytes = bincode::serialize(&dist).map_err(ADAError::Serialization)?;

        let kind = MessageKind::GroupInvite {
            group_id,
            group_name: group.name.clone(),
            group_topic: group.topic.clone(),
            sender_dist_bytes,
        };
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);
        self.send_message(msg).await?;

        // Add member to the group (admin is inviting)
        self.groups.add_member(
            group_id,
            peer.clone(),
            String::new(),
            &self.identity.peer_id,
        )?;
        Ok(())
    }

    /// Leave a group
    pub async fn leave_group(&self, group_id: [u8; 16]) -> Result<()> {
        self.groups
            .remove_member(group_id, &self.identity.peer_id, &self.identity.peer_id)?;
        tracing::info!("Left group {}", hex::encode(group_id));
        Ok(())
    }

    // =====================================================================
    // CALLS
    // =====================================================================

    async fn ensure_realtime_capable(&self) -> Result<()> {
        let capabilities = self.current_route_capabilities().await;
        if !capabilities.realtime_calls {
            let (_, detail) = Self::call_unavailable_detail(
                &capabilities,
                self.relay_only_enabled(),
                self.bridge_listener_connected
                    .load(std::sync::atomic::Ordering::Acquire),
            );
            return Err(ADAError::Call(detail.into()));
        }
        Ok(())
    }

    fn call_unavailable_detail(
        capabilities: &RouteCapabilities,
        relay_only: bool,
        bridge_listener_connected: bool,
    ) -> (&'static str, &'static str) {
        if capabilities.realtime_calls {
            return ("available", "voice/video calls are available");
        }
        if relay_only && !bridge_listener_connected {
            return (
                "live_bridge_required",
                "voice/video unavailable: relay-only route has no live bridge listener",
            );
        }
        if capabilities.mailbox_delivery && !capabilities.bridge_live_delivery {
            return (
                "mailbox_only",
                "voice/video unavailable: current route is store-and-forward only",
            );
        }
        (
            "no_live_route",
            "voice/video unavailable in the current network route",
        )
    }

    async fn warmup_peer_for_live_call(&self, peer: &PeerId) {
        if self.online_peers.read().contains(&peer.0) {
            return;
        }

        let Some(iroh) = ({ self.iroh.read().await.clone() }) else {
            return;
        };

        match self.id_store.load_peer_bundle(peer) {
            Ok(Some(bundle)) => {
                if let Some(relay_url) = bundle.relay_url.as_deref() {
                    if let Err(error) = iroh.add_peer_relay(&peer.0, relay_url) {
                        tracing::debug!(
                            "call warmup relay hint preload failed for {}: {}",
                            peer,
                            error
                        );
                    }
                }
            }
            Ok(None) => {}
            Err(error) => {
                tracing::debug!("call warmup peer bundle lookup failed for {}: {}", peer, error);
            }
        }

        tracing::debug!("call warmup: attempting live connection to {}", peer);
        iroh.warmup_connection(&peer.0).await;
        self.update_peer_presence().await;
    }

    /// Return whether realtime voice/video can be started on the current route.
    ///
    /// This is intentionally narrower than `get_bridge_status_json()`: clients can
    /// preflight call buttons with one stable contract and avoid creating WebRTC
    /// peer connections when core would reject the call anyway.
    pub async fn get_call_availability_json(&self) -> String {
        let capabilities = self.current_route_capabilities().await;
        let relay_only = self.relay_only_enabled();
        let bridge_listener_connected = self
            .bridge_listener_connected
            .load(std::sync::atomic::Ordering::Acquire);
        let (reason, detail) =
            Self::call_unavailable_detail(&capabilities, relay_only, bridge_listener_connected);
        let available = capabilities.realtime_calls;

        serde_json::json!({
            "available": available,
            "reason": if available { serde_json::Value::Null } else { serde_json::Value::String(reason.to_string()) },
            "detail": detail,
            "relay_only": relay_only,
            "bridge_listener_connected": bridge_listener_connected,
            "capabilities": {
                "bridge_live_delivery": capabilities.bridge_live_delivery,
                "mailbox_delivery": capabilities.mailbox_delivery,
                "realtime_calls": capabilities.realtime_calls,
                "large_attachments": capabilities.large_attachments,
                "max_attachment_bytes": capabilities.max_attachment_bytes,
            }
        }).to_string()
    }

    async fn send_live_call_signal(
        &self,
        peer: &PeerId,
        message_id: [u8; 16],
        wire_bytes: Vec<u8>,
        label: &str,
        attempts: u8,
        persist_status: bool,
    ) -> Result<()> {
        tracing::info!(
            "[call] {} begin peer={} message_id={} attempts={} profile={} relay_only={} online_iroh_peers={}",
            label,
            peer,
            hex::encode(message_id),
            attempts,
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
            self.online_peers.read().len(),
        );
        for attempt in 0..attempts {
            tracing::info!("{} delivery attempt {}/{}", label, attempt + 1, attempts);
            match self
                .route_wire_with_policy(
                    peer,
                    message_id,
                    wire_bytes.clone(),
                    DeliveryClass::CallSignaling,
                    false,
                )
                .await
            {
                Ok(outcome) => {
                    tracing::info!(
                        "{} delivered via {} (attempt {})",
                        label,
                        outcome.route.as_str(),
                        attempt + 1
                    );
                    if persist_status {
                        let _ = self
                            .messages
                            .update_status(&message_id, MessageStatus::Sent);
                        let _ = self
                            .event_tx
                            .send(ADAEvent::MessageStatusChanged {
                                message_id,
                                status: MessageStatus::Sent,
                            })
                            .await;
                    }
                    self.publish_transport_outcome(
                        message_id,
                        outcome.route.as_str(),
                        outcome.queue_depth,
                        outcome.latency_ms,
                    )
                    .await;
                    return Ok(());
                }
                Err(error) => {
                    tracing::warn!(
                        "[call] {} route attempt failed peer={} attempt={}/{} error={}",
                        label,
                        peer,
                        attempt + 1,
                        attempts,
                        error
                    );
                }
            }
            if attempt + 1 < attempts {
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            }
        }

        if persist_status {
            let _ = self.messages.update_status(
                &message_id,
                MessageStatus::Failed(format!("{} delivery failed", label)),
            );
        }
        self.call_signaling_failures_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.publish_transport_outcome(message_id, TransportRoute::Failed.as_str(), None, None)
            .await;
        tracing::warn!(
            "[call] {} failed peer={} message_id={} profile={} relay_only={}",
            label,
            peer,
            hex::encode(message_id),
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
        );
        Err(ADAError::Network(format!("{} delivery failed", label)))
    }

    /// Send a call Invite and retry delivery up to `max_attempts` times.
    /// iroh QUIC is tried on each attempt with exponential backoff.
    /// The message is signed, encrypted and stored **once** — no duplicates.
    async fn send_call_invite(
        &self,
        peer: PeerId,
        call_id: CallId,
        offer_sdp: String,
        has_video: bool,
    ) -> Result<CallId> {
        self.ensure_realtime_capable().await?;
        let room = self.get_group_call_room_for_call(call_id);
        let kind = MessageKind::Call(crate::messaging::types::CallEvent::Invite {
            call_id,
            offer_sdp: offer_sdp.clone(),
            has_video,
            group_id: room.as_ref().map(|snapshot| snapshot.group_id),
            session_id: room.as_ref().map(|snapshot| snapshot.session_id),
            participants: room
                .map(|snapshot| snapshot.participants)
                .unwrap_or_default(),
        });
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);

        // Prepare once (sign + encrypt + store).
        let (peer_id, wire_bytes, msg_id) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("call invite prepare failed: {}", e);
                return Err(e);
            }
        };

        self.send_live_call_signal(&peer_id, msg_id, wire_bytes, "call invite", 15, true)
            .await?;
        Ok(call_id)
    }

    /// Initiate an audio call; `offer_sdp` is the WebRTC offer generated by the Android layer.
    pub async fn call_audio(&self, peer: PeerId, offer_sdp: String) -> Result<CallId> {
        self.ensure_realtime_capable().await?;
        tracing::info!(
            "[call] audio start peer={} offer_sdp_len={} profile={} relay_only={} online_iroh_peers={}",
            peer,
            offer_sdp.len(),
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
            self.online_peers.read().len(),
        );
        self.warmup_peer_for_live_call(&peer).await;
        let (call_id, offer) = self
            .call_mgr
            .initiate_call(peer.clone(), offer_sdp, false)
            .await?;
        let result = self.send_call_invite(peer.clone(), call_id, offer, false).await;
        if result.is_ok() {
            self.calls_initiated_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::info!("[call] audio invite accepted peer={} call_id={}", peer, hex::encode(call_id));
        } else {
            self.call_mgr.abort_call_setup(call_id).await;
            self.call_signaling_failures_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::warn!("[call] audio invite failed peer={} call_id={}", peer, hex::encode(call_id));
        }
        result
    }

    /// Initiate a video call; `offer_sdp` is the WebRTC offer generated by the Android layer.
    pub async fn call_video(&self, peer: PeerId, offer_sdp: String) -> Result<CallId> {
        self.ensure_realtime_capable().await?;
        tracing::info!(
            "[call] video start peer={} offer_sdp_len={} profile={} relay_only={} online_iroh_peers={}",
            peer,
            offer_sdp.len(),
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
            self.online_peers.read().len(),
        );
        self.warmup_peer_for_live_call(&peer).await;
        let (call_id, offer) = self
            .call_mgr
            .initiate_call(peer.clone(), offer_sdp, true)
            .await?;
        let result = self.send_call_invite(peer.clone(), call_id, offer, true).await;
        if result.is_ok() {
            self.calls_initiated_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::info!("[call] video invite accepted peer={} call_id={}", peer, hex::encode(call_id));
        } else {
            self.call_mgr.abort_call_setup(call_id).await;
            self.call_signaling_failures_total
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::warn!("[call] video invite failed peer={} call_id={}", peer, hex::encode(call_id));
        }
        result
    }

    fn group_room_snapshot_from_state(room: &GroupCallRoomState) -> GroupCallRoomSnapshot {
        GroupCallRoomSnapshot {
            group_id: room.group_id,
            session_id: room.session_id,
            has_video: room.has_video,
            participants: room.participants.clone(),
        }
    }

    pub fn get_group_call_room_for_call(&self, call_id: CallId) -> Option<GroupCallRoomSnapshot> {
        let session_id = self
            .group_call_session_by_call
            .read()
            .get(&call_id)
            .copied()?;
        self.group_call_rooms_by_session
            .read()
            .get(&session_id)
            .map(Self::group_room_snapshot_from_state)
    }

    pub fn get_group_call_room(&self, session_id: [u8; 16]) -> Option<GroupCallRoomSnapshot> {
        self.group_call_rooms_by_session
            .read()
            .get(&session_id)
            .map(Self::group_room_snapshot_from_state)
    }

    fn register_group_call_room(
        &self,
        group_id: [u8; 16],
        session_id: [u8; 16],
        has_video: bool,
        participants: Vec<PeerId>,
        call_ids: &[CallId],
    ) {
        let unique_call_ids: std::collections::HashSet<CallId> = call_ids.iter().copied().collect();
        {
            let mut rooms = self.group_call_rooms_by_session.write();
            rooms
                .entry(session_id)
                .and_modify(|room| {
                    room.group_id = group_id;
                    room.has_video = has_video;
                    room.participants = participants.clone();
                    room.call_ids.extend(unique_call_ids.iter().copied());
                })
                .or_insert_with(|| GroupCallRoomState {
                    group_id,
                    session_id,
                    has_video,
                    participants: participants.clone(),
                    call_ids: unique_call_ids.clone(),
                });
        }
        let mut reverse = self.group_call_session_by_call.write();
        for call_id in unique_call_ids {
            reverse.insert(call_id, session_id);
        }
    }

    fn remove_group_call_mapping(&self, call_id: CallId) {
        let maybe_session_id = self.group_call_session_by_call.write().remove(&call_id);
        if let Some(session_id) = maybe_session_id {
            let mut rooms = self.group_call_rooms_by_session.write();
            if let Some(room) = rooms.get_mut(&session_id) {
                room.call_ids.remove(&call_id);
                if room.call_ids.is_empty() {
                    rooms.remove(&session_id);
                }
            }
        }
    }

    fn sync_group_call_rooms_with_active_calls(
        &self,
        active_call_ids: &std::collections::HashSet<CallId>,
    ) {
        let stale_call_ids: Vec<CallId> = {
            let reverse = self.group_call_session_by_call.read();
            reverse
                .keys()
                .filter(|call_id| !active_call_ids.contains(*call_id))
                .copied()
                .collect()
        };
        for call_id in stale_call_ids {
            self.remove_group_call_mapping(call_id);
        }
    }

    fn group_call_members(&self, group_id: [u8; 16], has_video: bool) -> Result<Vec<PeerId>> {
        let max_peers: usize = if has_video { 7 } else { 15 };
        let my_peer_id = self.identity.peer_id.clone();
        let group = self
            .list_groups()
            .into_iter()
            .find(|group| group.id == group_id)
            .ok_or_else(|| ADAError::Group("Group not found".into()))?;
        Ok(group
            .members
            .into_iter()
            .filter(|member| member.peer_id != my_peer_id)
            .map(|member| member.peer_id)
            .take(max_peers)
            .collect())
    }

    fn group_call_room_participants(
        &self,
        group_id: [u8; 16],
        has_video: bool,
    ) -> Result<Vec<PeerId>> {
        let members = self.group_call_members(group_id, has_video)?;
        let mut participants = Vec::with_capacity(members.len() + 1);
        participants.push(self.identity.peer_id.clone());
        participants.extend(members);
        Ok(participants)
    }

    async fn start_or_join_group_call_room(
        &self,
        group_id: [u8; 16],
        session_id: [u8; 16],
        _offer_sdp: String,
        has_video: bool,
        announce_room: bool,
    ) -> Result<[u8; 16]> {
        self.ensure_realtime_capable().await?;
        let participants = self.group_call_room_participants(group_id, has_video)?;

        if announce_room {
            self.announce_group_call(group_id, session_id, has_video)
                .await?;
        }

        self.register_group_call_room(group_id, session_id, has_video, participants, &[]);
        Ok(session_id)
    }

    pub async fn start_group_call_room(
        &self,
        group_id: [u8; 16],
        offer_sdp: String,
        has_video: bool,
    ) -> Result<[u8; 16]> {
        let mut session_id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut session_id);
        self.start_or_join_group_call_room(group_id, session_id, offer_sdp, has_video, true)
            .await
    }

    pub async fn join_group_call_room(
        &self,
        group_id: [u8; 16],
        session_id: [u8; 16],
        offer_sdp: String,
        has_video: bool,
    ) -> Result<[u8; 16]> {
        self.start_or_join_group_call_room(group_id, session_id, offer_sdp, has_video, false)
            .await
    }

    pub fn attach_call_to_group_room(
        &self,
        call_id: CallId,
        group_id: [u8; 16],
        session_id: [u8; 16],
        has_video: bool,
    ) -> Result<()> {
        let participants = self.group_call_room_participants(group_id, has_video)?;
        self.register_group_call_room(group_id, session_id, has_video, participants, &[call_id]);
        Ok(())
    }

    pub async fn hangup_group_call_room(&self, session_id: [u8; 16]) -> Result<()> {
        let room = self
            .group_call_rooms_by_session
            .read()
            .get(&session_id)
            .cloned();
        let Some(room) = room else {
            return Ok(());
        };

        let active_calls: std::collections::HashMap<CallId, PeerId> = self
            .call_mgr
            .active_calls_info()
            .await
            .into_iter()
            .map(|(call_id, peer, _, _, _)| (call_id, peer))
            .collect();

        for call_id in room.call_ids.iter().copied().collect::<Vec<_>>() {
            if let Some(peer) = active_calls.get(&call_id).cloned() {
                if let Err(error) = self.hangup(call_id, peer).await {
                    tracing::warn!(
                        "group room {} hangup for {} failed: {}",
                        hex::encode(session_id),
                        hex::encode(call_id),
                        error,
                    );
                }
            } else {
                self.remove_group_call_mapping(call_id);
            }
        }
        self.group_call_rooms_by_session.write().remove(&session_id);
        Ok(())
    }

    /// Phase-1 audio call initiation: validate route + register call state.
    /// Returns (call_id, offer_sdp) without sending the invite over the network.
    /// Call `deliver_call_invite_bg` from a background task to complete delivery
    /// without blocking the JNI / IO thread.
    pub async fn prepare_call_audio(
        &self,
        peer: PeerId,
        offer_sdp: String,
    ) -> Result<([u8; 16], String)> {
        self.ensure_realtime_capable().await?;
        let (call_id, offer) = self.call_mgr.initiate_call(peer, offer_sdp, false).await?;
        Ok((call_id, offer))
    }

    /// Phase-1 video call initiation — same contract as `prepare_call_audio`.
    pub async fn prepare_call_video(
        &self,
        peer: PeerId,
        offer_sdp: String,
    ) -> Result<([u8; 16], String)> {
        self.ensure_realtime_capable().await?;
        let (call_id, offer) = self.call_mgr.initiate_call(peer, offer_sdp, true).await?;
        Ok((call_id, offer))
    }

    /// Phase-1 group call initiation: register call state, bind it to the room,
    /// then let the caller deliver the invite in the background.
    pub async fn prepare_call_in_group_room(
        &self,
        peer: PeerId,
        offer_sdp: String,
        group_id: [u8; 16],
        session_id: [u8; 16],
        has_video: bool,
    ) -> Result<([u8; 16], String)> {
        self.ensure_realtime_capable().await?;
        let (call_id, offer) = self
            .call_mgr
            .initiate_call(peer, offer_sdp, has_video)
            .await?;
        self.attach_call_to_group_room(call_id, group_id, session_id, has_video)?;
        Ok((call_id, offer))
    }

    /// Phase-2: deliver a prepared call invite over the network.
    /// Intended to run inside `runtime().spawn(...)` so it never blocks the JNI thread.
    pub async fn deliver_call_invite_bg(
        &self,
        peer: PeerId,
        call_id: [u8; 16],
        offer: String,
        has_video: bool,
    ) {
        if let Err(e) = self.send_call_invite(peer, call_id, offer, has_video).await {
            tracing::warn!(
                "[call] invite bg delivery failed for {}: {}",
                hex::encode(call_id),
                e
            );
        }
    }

    /// Answer an incoming call; `answer_sdp` is the WebRTC answer generated by the Android layer.
    pub async fn answer_call(
        &self,
        call_id: CallId,
        peer: PeerId,
        answer_sdp: String,
    ) -> Result<()> {
        tracing::info!(
            "[call] answer start peer={} call_id={} answer_sdp_len={} profile={} relay_only={}",
            peer,
            hex::encode(call_id),
            answer_sdp.len(),
            self.connection_profile().as_str(),
            self.relay_only_enabled(),
        );
        let answer = self.call_mgr.answer_call(call_id, answer_sdp).await?;
        self.send_call_answer(peer, call_id, answer).await
    }

    /// Phase-1 answer: update local call state in the CallManager (fast, no network).
    /// Returns the processed answer SDP to pass to `deliver_call_answer_bg`.
    pub async fn prepare_call_answer(&self, call_id: CallId, answer_sdp: String) -> Result<String> {
        self.ensure_realtime_capable().await?;
        self.call_mgr.answer_call(call_id, answer_sdp).await
    }

    /// Phase-2: deliver the prepared call answer over the network.
    /// Intended to run inside `runtime().spawn(...)` so it never blocks the JNI thread.
    pub async fn deliver_call_answer_bg(&self, peer: PeerId, call_id: CallId, answer_sdp: String) {
        if let Err(e) = self.send_call_answer(peer, call_id, answer_sdp).await {
            tracing::warn!(
                "[call] answer bg delivery failed for {}: {}",
                hex::encode(call_id),
                e
            );
        }
    }

    /// Delete a message locally (fast, no network I/O).
    /// Pair with `deliver_delete_request_bg` to notify the peer in the background.
    pub fn delete_message_local(&self, msg_id: &[u8; 16]) -> Result<()> {
        self.messages.delete_message(msg_id)
    }

    /// Deliver a DeleteRequest to the peer in the background.
    /// Intended to run inside `runtime().spawn(...)`.
    pub async fn deliver_delete_request_bg(&self, peer: PeerId, msg_id: [u8; 16]) {
        let req = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::DeleteRequest {
                target_msg_id: msg_id,
            },
        );
        if let Err(e) = self.send_message(req).await {
            tracing::warn!("[delete_for_everyone] bg delivery failed: {}", e);
        }
    }

    /// Deliver a call Answer with the same retry strategy used for Invite:
    /// iroh QUIC up to 15 attempts (2-second gaps).
    async fn send_call_answer(
        &self,
        peer: PeerId,
        call_id: CallId,
        answer_sdp: String,
    ) -> Result<()> {
        self.ensure_realtime_capable().await?;
        tracing::info!(
            "[call] send answer peer={} call_id={} answer_sdp_len={}",
            peer,
            hex::encode(call_id),
            answer_sdp.len(),
        );
        let kind = MessageKind::Call(crate::messaging::types::CallEvent::Answer {
            call_id,
            answer_sdp: answer_sdp.clone(),
        });
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);

        // Prepare once (sign + encrypt + store); never duplicate-store on retries.
        let (peer_id, wire_bytes, _) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("call answer prepare failed: {}", e);
                return Err(e);
            }
        };

        self.send_live_call_signal(&peer_id, call_id, wire_bytes, "call answer", 15, false)
            .await
    }

    /// Hang up a call
    pub async fn hangup(&self, call_id: CallId, peer: PeerId) -> Result<()> {
        tracing::info!("[call] hangup start peer={} call_id={}", peer, hex::encode(call_id));
        // Look up has_video and connected_at before removing the call from the map.
        let call_info = self
            .call_mgr
            .active_calls_info()
            .await
            .into_iter()
            .find(|(id, ..)| *id == call_id);
        let has_video = call_info
            .as_ref()
            .map(|(_, _, hv, _, _)| *hv)
            .unwrap_or(false);
        let is_outgoing = call_info
            .as_ref()
            .map(|(_, _, _, out, _)| *out)
            .unwrap_or(false);

        let duration = self.call_mgr.call_duration(call_id).await;
        let ended_at = unix_now_secs();
        let started_at = ended_at.saturating_sub(duration as i64);

        self.call_mgr.hangup(call_id).await?;
        self.remove_group_call_mapping(call_id);

        // Persist to call log.
        self.id_store.kv().save_call_log_entry(
            &hex::encode(call_id),
            &peer.to_base64(),
            if is_outgoing { "outgoing" } else { "incoming" },
            has_video,
            duration as i64,
            started_at,
            ended_at,
            "hung_up",
        );

        // Emit local CallStateChanged so the local UI updates immediately.
        let ended_state = crate::media::call::CallState::Ended {
            duration_secs: duration,
            reason: crate::media::call::EndReason::HungUp,
        };
        self.calls_ended_total
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let _ = self
            .event_tx
            .send(ADAEvent::CallStateChanged {
                call_id,
                peer: peer.clone(),
                has_video,
                state: ended_state,
                answer_sdp: None,
            })
            .await;

        self.send_call_hangup(peer, call_id, crate::messaging::types::HangupReason::Normal)
            .await;
        Ok(())
    }

    /// Deliver a Hangup with the same retry strategy used for Invite/Answer.
    async fn send_call_hangup(
        &self,
        peer: PeerId,
        call_id: CallId,
        reason: crate::messaging::types::HangupReason,
    ) {
        let kind =
            MessageKind::Call(crate::messaging::types::CallEvent::Hangup { call_id, reason });
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);

        let (peer_id, wire_bytes, _) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("call hangup prepare failed: {}", e);
                return;
            }
        };

        // A live hangup is ideal, but losing teardown leaves the remote UI stuck in-call.
        // Try live signaling first; if that fails, allow the normal reliable routes
        // (bridge mailbox / local offline queue) to carry the hangup by call_id.
        if self
            .send_live_call_signal(
                &peer_id,
                call_id,
                wire_bytes.clone(),
                "call hangup",
                3,
                false,
            )
            .await
            .is_ok()
        {
            return;
        }

        tracing::warn!(
            "call hangup live delivery failed for {}; falling back to reliable routes",
            peer_id
        );

        match self
            .route_wire_with_policy(
                &peer_id,
                call_id,
                wire_bytes,
                DeliveryClass::DirectMessage,
                true,
            )
            .await
        {
            Ok(outcome) => {
                tracing::info!(
                    "call hangup fallback delivered via {}",
                    outcome.route.as_str()
                );
                self.publish_transport_outcome(
                    call_id,
                    outcome.route.as_str(),
                    outcome.queue_depth,
                    outcome.latency_ms,
                )
                .await;
            }
            Err(error) => {
                tracing::warn!("call hangup fallback failed: {}", error);
            }
        }
    }

    /// Decline (reject) an incoming call — sends Declined hangup reason to the peer.
    pub async fn decline_call(&self, call_id: CallId, peer: PeerId) -> Result<()> {
        tracing::info!("[call] decline start peer={} call_id={}", peer, hex::encode(call_id));
        let ended_at = unix_now_secs();
        self.id_store.kv().save_call_log_entry(
            &hex::encode(call_id),
            &peer.to_base64(),
            "incoming",
            false,
            0,
            ended_at,
            ended_at,
            "rejected",
        );

        self.call_mgr.hangup(call_id).await?;

        let ended_state = crate::media::call::CallState::Ended {
            duration_secs: 0,
            reason: crate::media::call::EndReason::Rejected,
        };
        let _ = self
            .event_tx
            .send(ADAEvent::CallStateChanged {
                call_id,
                peer: peer.clone(),
                has_video: false,
                state: ended_state,
                answer_sdp: None,
            })
            .await;

        self.send_call_hangup(
            peer,
            call_id,
            crate::messaging::types::HangupReason::Declined,
        )
        .await;
        Ok(())
    }

    // =====================================================================
    // FILE TRANSFER
    // =====================================================================

    /// Send a file to a peer.
    ///
    /// ## Hybrid routing
    /// - Files **≥ `config.network.blob_threshold_bytes`** (default 256 KiB): stored
    ///   in the Iroh blob cache and announced via a `BlobRef` DM.  The receiver
    ///   pulls the bytes on demand using `fetch_file_blob()`.
    pub async fn send_file_from_path(
        &self,
        peer: PeerId,
        file_name: &str,
        mime_type: &str,
        path: std::path::PathBuf,
    ) -> Result<[u8; 16]> {
        let metadata = std::fs::metadata(&path)
            .map_err(|e| ADAError::Network(format!("metadata err {}", e)))?;
        let size = metadata.len();
        let capabilities = self.current_route_capabilities().await;

        if !capabilities.large_attachments && size > capabilities.max_attachment_bytes {
            return Err(ADAError::Transfer(format!(
                "attachments larger than {} bytes are disabled in the current censorship-safe route",
                capabilities.max_attachment_bytes
            )));
        }

        if size < self.config.network.blob_threshold_bytes || !capabilities.large_attachments {
            let data = std::fs::read(&path)
                .map_err(|e| ADAError::Transfer(format!("read file: {}", e)))?;
            return self.send_file(peer, file_name, mime_type, data).await;
        }

        let iroh_guard = self.iroh.read().await;
        if let Some(iroh) = iroh_guard.as_ref() {
            let hash = iroh.store_blob_from_path(path).await?;

            let iroh_ref = Arc::clone(iroh);
            tokio::spawn(async move {
                tokio::time::sleep(std::time::Duration::from_secs(
                    crate::network::relay::OFFLINE_MESSAGE_TTL_SECS,
                ))
                .await;
                iroh_ref.evict_blob(&hash).await;
            });

            let mut file_id = [0u8; 16];
            rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut file_id);

            let kind = MessageKind::BlobRef {
                file_id,
                name: file_name.to_string(),
                size,
                mime_type: mime_type.to_string(),
                hash,
            };
            let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);
            let result = self.send_message(msg).await;

            if let Some(relay_url) = iroh.home_relay_url() {
                let hint_kind = MessageKind::IrohHint { relay_url };
                let hint_msg =
                    Message::new(self.identity.peer_id.clone(), Some(peer.clone()), hint_kind);
                if let Ok((hint_peer, hint_wire, _)) = self.prepare_message(hint_msg).await {
                    let _ = self.try_deliver_wire(&hint_peer, hint_wire).await;
                }
            }

            result.map(|_| file_id)
        } else {
            Err(ADAError::Network(
                "Iroh transport not available for streaming".into(),
            ))
        }
    }

    /// - Smaller files use the legacy chunked transfer (encrypted chunks in-band).
    pub async fn send_file(
        &self,
        peer: PeerId,
        file_name: &str,
        mime_type: &str,
        data: Vec<u8>,
    ) -> Result<[u8; 16]> {
        let capabilities = self.current_route_capabilities().await;
        if !capabilities.large_attachments && data.len() as u64 > capabilities.max_attachment_bytes
        {
            return Err(ADAError::Transfer(format!(
                "attachments larger than {} bytes are disabled in the current censorship-safe route",
                capabilities.max_attachment_bytes
            )));
        }

        // ── Iroh Blobs path: large files ────────────────────────────────────
        if data.len() as u64 >= self.config.network.blob_threshold_bytes {
            let iroh_guard = self.iroh.read().await;
            if let Some(iroh) = iroh_guard.as_ref() {
                let size = data.len() as u64;
                let hash = iroh.store_blob(data).await;
                // Keep blobs long enough for offline-delivered BlobRef messages
                // to be fetched by the recipient.
                let iroh_ref = Arc::clone(iroh);
                tokio::spawn(async move {
                    tokio::time::sleep(std::time::Duration::from_secs(
                        crate::network::relay::OFFLINE_MESSAGE_TTL_SECS,
                    ))
                    .await;
                    iroh_ref.evict_blob(&hash).await;
                });
                let mut file_id = [0u8; 16];
                rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut file_id);
                let kind = MessageKind::BlobRef {
                    file_id,
                    name: file_name.to_string(),
                    size,
                    mime_type: mime_type.to_string(),
                    hash,
                };
                let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);
                let result = self.send_message(msg).await;

                // Send IrohHint as a separate DM immediately after BlobRef.
                // This delivers our relay URL to the receiver without changing the BlobRef
                // wire format (backward-compatible with old clients that don't know IrohHint:
                // they will log a deserialization warning but BlobRef was already processed).
                if let Some(relay_url) = iroh.home_relay_url() {
                    let hint_kind = MessageKind::IrohHint { relay_url };
                    let hint_msg =
                        Message::new(self.identity.peer_id.clone(), Some(peer), hint_kind);
                    // Best-effort single-pass delivery — not offline-queued because
                    // IrohHint is ephemeral (only useful RIGHT NOW for the pending blob).
                    // If delivery fails, the blob queue will eventually succeed via pkarr DNS.
                    if let Ok((hint_peer, hint_wire, _)) = self.prepare_message(hint_msg).await {
                        let _ = self.try_deliver_wire(&hint_peer, hint_wire).await;
                    }
                }

                return result;
            }
        }

        // ── Legacy chunked transfer (small files or iroh unavailable) ────────
        let path = std::path::Path::new(file_name);
        let meta = self
            .transfer_mgr
            .send_file(peer.clone(), path, mime_type.to_string(), data)
            .await?;
        let transfer_id = meta.id;

        // Notify peer via message
        let kind = MessageKind::File {
            file_id: meta.id,
            name: meta.file_name.clone(),
            size: meta.file_size,
            mime_type: meta.mime_type.clone(),
            checksum: meta.checksum,
            encryption_key: meta.encryption_key,
            chunk_count: meta.chunk_count,
        };
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer), kind);
        self.send_message(msg).await?;

        Ok(transfer_id)
    }

    /// Fetch a file that a peer offered via Iroh Blobs.
    ///
    /// Call this after receiving an `ADAEvent::BlobAvailable` event.
    /// The returned bytes are blake3-verified against `hash` before returning.
    pub async fn fetch_file_blob(&self, peer: &PeerId, hash: [u8; 32]) -> Result<Vec<u8>> {
        let iroh_guard = self.iroh.read().await;
        let iroh = iroh_guard
            .as_ref()
            .ok_or_else(|| ADAError::Network("iroh transport not available".into()))?;
        let mut last_err: Option<ADAError> = None;
        // Large media over mobile relay/hole-punch can fail transiently.
        // Keep retry window longer so receive-side can recover after short
        // connectivity drops or relay path switches.
        for attempt in 0..10 {
            match iroh.fetch_blob(&peer.0, &hash).await {
                Ok(bytes) => return Ok(bytes),
                Err(e) => {
                    tracing::warn!(
                        "fetch_file_blob attempt {}/10 failed for {}: {}",
                        attempt + 1,
                        hex::encode(hash),
                        e
                    );
                    last_err = Some(e);
                    if attempt < 9 {
                        let secs = 2u64.saturating_mul((attempt + 1) as u64);
                        tokio::time::sleep(std::time::Duration::from_secs(secs)).await;
                    }
                }
            }
        }
        Err(last_err.unwrap_or_else(|| ADAError::Network("blob fetch failed".into())))
    }

    pub async fn fetch_file_blob_to_path(
        &self,
        peer: &PeerId,
        hash: [u8; 32],
        dest_path: &std::path::Path,
    ) -> Result<()> {
        let iroh_guard = self.iroh.read().await;
        let iroh = iroh_guard
            .as_ref()
            .ok_or_else(|| ADAError::Network("iroh transport not available".into()))?;
        let mut last_err: Option<ADAError> = None;
        for attempt in 0..10 {
            match iroh.fetch_blob_to_file(&peer.0, &hash, dest_path).await {
                Ok(()) => return Ok(()),
                Err(e) => {
                    tracing::warn!(
                        "fetch_file_blob_to_path attempt {}/10 failed for {}: {}",
                        attempt + 1,
                        hex::encode(hash),
                        e
                    );
                    last_err = Some(e);
                    if attempt < 9 {
                        let secs = 2u64.saturating_mul((attempt + 1) as u64);
                        tokio::time::sleep(std::time::Duration::from_secs(secs)).await;
                    }
                }
            }
        }
        Err(last_err.unwrap_or_else(|| ADAError::Network("blob fetch_to_file failed".into())))
    }

    // =====================================================================
    // IDENTITY & PEERS
    // =====================================================================

    /// Save identity to persistent storage
    pub fn save_identity(&self, passphrase: &[u8]) -> Result<()> {
        self.id_store.save_identity(&self.identity, passphrase)
    }

    /// Get our public bundle (to share with others)
    pub fn public_bundle(&self) -> PublicBundle {
        let mut bundle = self.identity.public_bundle();
        // Attach an OPK from the pool so the recipient's first X3DH
        // gets DH4 forward secrecy (one-time pre-key Diffie-Hellman).
        if let Ok(pk) = self.prekeys.try_read() {
            if let Some(opk) = pk.opks_peek_first() {
                bundle.opk_public = Some(opk.0);
                bundle.opk_id = Some(opk.1);
            }
        }
        if let Ok(guard) = self.iroh.try_read() {
            if let Some(iroh) = guard.as_ref() {
                bundle.relay_url = iroh.home_relay_url();
            }
        }
        bundle
    }

    /// Build the contact card JSON (for QR code).  Includes OPK from the prekey pool.
    pub fn contact_card_json(&self) -> Result<String> {
        let opk = self
            .prekeys
            .try_read()
            .ok()
            .and_then(|pk| pk.opks_peek_first());
        let relay_url = self
            .iroh
            .try_read()
            .ok()
            .and_then(|guard| guard.as_ref().and_then(|iroh| iroh.home_relay_url()));
        crate::pattern_auth::contact_card_json(&self.identity, opk, relay_url.as_deref())
    }

    /// Get local peer ID
    pub fn peer_id(&self) -> &PeerId {
        &self.identity.peer_id
    }

    /// Get conversation list
    pub fn conversations(&self) -> Vec<crate::messaging::store::Conversation> {
        self.messages.list_conversations()
    }

    /// Search conversations by display name or local message history.
    pub fn search_conversations(&self, query: &str) -> Vec<crate::messaging::store::Conversation> {
        self.messages.search_conversations(query)
    }

    // =====================================================================
    // INCOGNITO CHATS (ephemeral per-contact identities)
    // =====================================================================

    /// Open an incognito chat with `peer_id`.
    ///
    /// Generates a fresh X25519 ephemeral IK for this conversation only.
    /// Returns a v3 QR contact card JSON that the peer scans — their X3DH
    /// will address the ephemeral key, not the long-term IK, so this chat
    /// cannot be linked to other conversations by a passive observer.
    ///
    /// The ephemeral key is persisted in the KV store under
    /// `incognito/ik/<peer_b64>` so it survives an app restart.
    pub fn create_incognito_chat(&self, peer_id: &PeerId) -> Result<String> {
        let peer_b64 = peer_id.to_base64();
        let kv_key = format!("incognito/ik/{}", peer_b64);

        // Re-use the existing key if we already opened an incognito chat with this peer.
        let eph_key = if let Some(bytes) = self.id_store.kv().get(&kv_key) {
            let arr: [u8; 32] = bytes
                .try_into()
                .map_err(|_| ADAError::Storage("Corrupt incognito key".into()))?;
            EphemeralContactKey::from_secret_bytes(arr)
        } else {
            let key = EphemeralContactKey::generate();
            self.id_store
                .kv()
                .set(&kv_key, key.to_secret_bytes().to_vec())?;
            key
        };

        // Register ephemeral_pub → conversation in the alias map (in-memory).
        self.ephemeral_aliases
            .write()
            .insert(eph_key.public, ConversationId::Direct(peer_id.clone()));

        let relay_url = self
            .iroh
            .try_read()
            .ok()
            .and_then(|guard| guard.as_ref().and_then(|iroh| iroh.home_relay_url()));
        crate::pattern_auth::ephemeral_contact_card_json(
            &self.identity,
            &eph_key.public,
            relay_url.as_deref(),
        )
    }

    /// Return the per-peer delivery token, creating one if it doesn't exist yet.
    /// The token is stored in the KV store so it survives restart.
    fn get_or_create_delivery_token(&self, peer: &PeerId) -> [u8; 32] {
        let peer_b64 = peer.to_base64();
        {
            let guard = self.delivery_tokens.read();
            if let Some(t) = guard.get(&peer_b64) {
                return *t;
            }
        }
        // Not cached — check KV store.
        let kv_key = format!("relay/token/{}", peer_b64);
        if let Some(bytes) = self.id_store.kv().get(&kv_key) {
            if let Ok(arr) = bytes.try_into() as std::result::Result<[u8; 32], _> {
                self.delivery_tokens.write().insert(peer_b64, arr);
                return arr;
            }
        }
        // Generate fresh token.
        let mut token = [0u8; 32];
        use rand::RngCore;
        rand::rngs::OsRng.fill_bytes(&mut token);
        let _ = self.id_store.kv().set(&kv_key, token.to_vec());
        self.delivery_tokens.write().insert(peer_b64, token);
        token
    }

    /// Get messages in a conversation
    pub fn get_messages(&self, conv: &ConversationId, limit: usize) -> Vec<Message> {
        self.messages.get_messages(conv, None, limit)
    }

    /// Delete a single message by its 16-byte ID (local only).
    pub fn delete_message(&self, msg_id: &[u8; 16]) -> Result<()> {
        self.messages.delete_message(msg_id)
    }

    /// Delete an entire conversation and all its messages (local only).
    pub fn delete_conversation(&self, conv: &ConversationId) -> Result<()> {
        self.messages.delete_conversation(conv)
    }

    /// Clear all messages in a conversation, keeping the conversation entry.
    pub fn clear_conversation_messages(&self, conv: &ConversationId) -> Result<()> {
        self.messages.clear_messages(conv)
    }

    /// Delete a message locally **and** ask the peer to delete their copy.
    /// The peer will only honour the request if we are the original author of
    /// the target message (enforced on the receiving end).
    pub async fn delete_message_for_everyone(
        &self,
        peer: &PeerId,
        msg_id: &[u8; 16],
    ) -> Result<()> {
        // Delete locally first (so the UI updates immediately even if send fails)
        self.messages.delete_message(msg_id)?;
        // Send delete-request to the peer
        let req = Message::new(
            self.identity.peer_id.clone(),
            Some(peer.clone()),
            MessageKind::DeleteRequest {
                target_msg_id: *msg_id,
            },
        );
        self.send_message(req).await.map(|_| ())
    }

    /// Add a bridge for censorship circumvention
    pub async fn add_bridge(&self, bridge_line: &str) -> Result<()> {
        self.bridges.write().await.add_bridge_line(bridge_line)
    }

    /// Set obfuscation mode
    pub async fn set_obfuscation(&self, mode: ObfuscationMode) {
        self.bridges.write().await.set_mode(mode);
    }

    // =====================================================================
    // INTERNAL
    // =====================================================================

    /// Handle a message received on the iroh QUIC endpoint.
    /// Processes raw encrypted bytes received from a local offline mesh (BLE/Wi-Fi).
    pub async fn receive_mesh_bytes(&self, peer: PeerId, bytes: Vec<u8>) -> Result<()> {
        self.active_mesh_peers.write().insert(peer.clone());

        if bytes.is_empty() {
            tracing::info!(
                "local_mesh: peer {} connected, flushing offline queue",
                peer
            );
            self.flush_offline_queue_for(&peer).await;
            return Ok(());
        }

        match bincode::deserialize::<WireEnvelope>(&bytes) {
            Ok(WireEnvelope::Dm(wire)) => self.receive_encrypted_wire(wire).await,
            Ok(WireEnvelope::Group(skm)) => {
                self.receive_group_wire(peer.0, skm).await;
                Ok(())
            }
            Ok(WireEnvelope::WebRtcProxy(_)) => {
                // Ignore WebRTC proxy frames on offline mesh for now
                tracing::warn!("Ignoring WebRTC proxy on local mesh");
                Ok(())
            }
            Err(e) => {
                tracing::warn!(
                    "local_mesh: failed to deserialize WireEnvelope from {}: {}",
                    peer,
                    e
                );
                Err(ADAError::Serialization(e))
            }
        }
    }

    /// Mark a local mesh peer as disconnected
    pub fn disconnect_mesh_peer(&self, peer: &PeerId) {
        self.active_mesh_peers.write().remove(peer);
        tracing::info!("local_mesh: peer {} disconnected", peer);
    }

    /// Deserialises the `WireEnvelope` and dispatches DM vs Group messages.
    /// Starts the WebRTC local media proxy for a given peer.
    /// Returns the local UDP port that WebRTC should connect to.
    pub async fn start_webrtc_proxy(self: &Arc<Self>, peer_id: PeerId) -> Result<u16> {
        let mut proxies = self.media_proxies.write();
        if let Some(proxy) = proxies.get(&peer_id) {
            tracing::info!(
                "[call] webrtc proxy reuse peer={} port={}",
                peer_id,
                proxy.local_port()
            );
            return Ok(proxy.local_port());
        }

        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1024);

        let core_weak = Arc::downgrade(self);
        let peer_clone = peer_id.clone();
        let peer_for_log = peer_id.clone();

        // Spawn a background task to route outbound UDP packets from the proxy -> ADA network
        tokio::spawn(async move {
            while let Some(udp_payload) = rx.recv().await {
                if let Some(core) = core_weak.upgrade() {
                    let wire_bytes = bincode::serialize(&WireEnvelope::WebRtcProxy(udp_payload))
                        .unwrap_or_default();
                    if !wire_bytes.is_empty() {
                        // Deliver via iroh or bridge
                        let _ = core.try_deliver_wire(&peer_clone, wire_bytes).await;
                    }
                } else {
                    break;
                }
            }
        });

        let proxy =
            Arc::new(crate::media::webrtc_proxy::WebRtcProxy::start(peer_id.clone(), tx).await?);
        let port = proxy.local_port();
        proxies.insert(peer_id, proxy);
        tracing::info!("[call] webrtc proxy started peer={} port={}", peer_for_log, port);
        Ok(port)
    }

    /// Stops the media proxy for a peer
    pub fn stop_webrtc_proxy(&self, peer_id: &PeerId) {
        self.media_proxies.write().remove(peer_id);
    }

    async fn handle_iroh_message(&self, msg: crate::network::iroh_transport::IrohMessage) {
        tracing::debug!(
            "iroh message from {}: {} bytes",
            hex::encode(msg.from),
            msg.data.len()
        );

        // Immediately mark sender as online (we just received data from them).
        if self.online_peers.write().insert(msg.from) {
            let _ = self
                .event_tx
                .send(ADAEvent::PeerOnline(PeerId(msg.from)))
                .await;
        }
        match bincode::deserialize::<WireEnvelope>(&msg.data) {
            Ok(WireEnvelope::Dm(wire)) => {
                // Security: cross-check the TLS-authenticated iroh NodeId (msg.from)
                // against the claimed sender in the wire message (wire.sender.0).
                // iroh authenticates the transport peer via TLS using their Ed25519 key,
                // which is identical to the ADA PeerId.  A mismatch means a relay or
                // MITM forwarded someone else's encrypted blob over our channel — reject it.
                if wire.sender.0 != msg.from {
                    tracing::warn!(
                        "iroh: transport NodeId {} ≠ wire.sender {} — dropping (possible relay attack)",
                        hex::encode(msg.from),
                        hex::encode(wire.sender.0)
                    );
                    return;
                }
                if let Err(e) = self.receive_encrypted_wire(wire).await {
                    tracing::warn!("iroh: failed to decrypt DM wire message: {}", e);
                }
            }
            Ok(WireEnvelope::WebRtcProxy(payload)) => {
                if let Some(proxy) = self.media_proxies.read().get(&PeerId(msg.from)) {
                    proxy.deliver_inbound(payload);
                }
            }

            Ok(WireEnvelope::Group(skm)) => {
                self.receive_group_wire(msg.from, skm).await;
            }
            Err(e) => tracing::warn!("iroh: failed to deserialize WireEnvelope: {}", e),
        }
    }

    async fn handle_bridge_delivery(&self, envelope: BridgeEnvelope) -> Result<()> {
        self.decrement_bridge_mailbox_depth();
        match bincode::deserialize::<WireEnvelope>(&envelope.wire_bytes) {
            Ok(WireEnvelope::Dm(wire)) => self.receive_encrypted_wire(wire).await,
            Ok(WireEnvelope::WebRtcProxy(payload)) => {
                if let Some(proxy) = self.media_proxies.read().get(&PeerId(envelope.sender)) {
                    proxy.deliver_inbound(payload);
                }
                Ok(())
            }

            Ok(other) => Err(ADAError::Bridge(format!(
                "unsupported bridge-delivered wire envelope: {:?}",
                other
            ))),
            Err(error) => Err(ADAError::Bridge(format!(
                "bridge wire deserialize failed: {}",
                error
            ))),
        }
    }

    /// Process an incoming group message (Sender Key encrypted).
    async fn receive_group_wire(
        &self,
        transport_sender: [u8; 32],
        skm: crate::group::sender_keys::SenderKeyMessage,
    ) {
        // Security: verify transport sender matches claimed sender in SenderKeyMessage.
        if skm.sender.0 != transport_sender {
            tracing::warn!(
                "group: transport NodeId {} ≠ skm.sender {} — dropping",
                hex::encode(transport_sender),
                hex::encode(skm.sender.0)
            );
            return;
        }

        // Verify sender's Ed25519 signature on the ciphertext.
        if let Err(e) = skm.verify() {
            tracing::warn!("group: invalid signature: {}", e);
            return;
        }

        // Decrypt with Sender Key.
        let plaintext = match self.groups.decrypt_group_message(&skm) {
            Ok(pt) => pt,
            Err(e) => {
                tracing::debug!("group: decrypt failed (not for us?): {}", e);
                return;
            }
        };

        let msg: Message = match bincode::deserialize(&plaintext) {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!("group: message deserialise failed: {}", e);
                return;
            }
        };

        let gid = match msg.group_id {
            Some(gid) => gid,
            None => {
                tracing::warn!("group: message has no group_id");
                return;
            }
        };

        // Replay dedup.
        if self.messages.get_message_by_id(&msg.id).is_some() {
            tracing::debug!("group: duplicate message {}", hex::encode(msg.id));
            return;
        }

        // Signature verification (Ed25519 on message payload).
        {
            use ed25519_dalek::{Signature, Verifier, VerifyingKey};
            let vk = match VerifyingKey::from_bytes(&msg.sender.0) {
                Ok(v) => v,
                Err(_) => {
                    tracing::warn!("group: invalid sender key");
                    return;
                }
            };
            let sig_bytes: [u8; 64] = match msg.signature.as_slice().try_into() {
                Ok(b) => b,
                Err(_) => {
                    tracing::warn!("group: invalid signature length");
                    return;
                }
            };
            let sig = Signature::from_bytes(&sig_bytes);
            if vk.verify(&msg.bytes_to_sign(), &sig).is_err() {
                tracing::warn!("group: invalid message signature from {}", msg.sender);
                return;
            }
        }

        let conv = ConversationId::Group(gid);
        let should_save = matches!(
            &msg.kind,
            MessageKind::Text(_)
                | MessageKind::File { .. }
                | MessageKind::BlobRef { .. }
                | MessageKind::Reaction { .. }
                | MessageKind::GroupCallStart { .. }
        );

        if should_save {
            let mut msg_to_save = msg.clone();
            msg_to_save.status = MessageStatus::Delivered;
            let _ = self
                .messages
                .save_message_with_unread(&conv, msg_to_save, true);
            let _ = self
                .event_tx
                .send(ADAEvent::MessageReceived(msg.clone()))
                .await;
        }

        match &msg.kind {
            MessageKind::Edit { target_msg_id, .. } => {
                let _ = self.messages.save_hidden_message(&conv, msg.clone());
                let _ = self
                    .event_tx
                    .send(ADAEvent::MessageEdited {
                        target_message_id: *target_msg_id,
                    })
                    .await;
            }
            MessageKind::DeleteRequest { target_msg_id } => {
                let sender_owns_msg = self
                    .messages
                    .get_message_by_id(target_msg_id)
                    .map(|message| message.sender == msg.sender)
                    .unwrap_or(false);
                if sender_owns_msg {
                    let _ = self.messages.delete_message(target_msg_id);
                }
            }
            MessageKind::BlobRef {
                file_id,
                name,
                size,
                mime_type,
                hash,
            } => {
                let _ = self
                    .event_tx
                    .send(ADAEvent::BlobAvailable {
                        from: msg.sender.clone(),
                        file_id: *file_id,
                        file_name: name.clone(),
                        file_size: *size,
                        mime_type: mime_type.clone(),
                        hash: *hash,
                    })
                    .await;
            }
            _ => {}
        }

        // Sender is reachable — flush offline queue for them.
        let sender_peer = PeerId(transport_sender);
        self.flush_offline_queue_for(&sender_peer).await;
    }

    /// Flush (retry) any offline-queued messages destined for `peer`.
    ///
    /// Called opportunistically whenever we confirm the peer is reachable:
    /// - after successfully delivering a wire message to them
    /// - after receiving a decrypted message *from* them
    /// This avoids the 60-second maintenance-cycle wait after transient startup
    /// failures (iroh QUIC handshake).
    async fn flush_offline_queue_for(&self, peer: &PeerId) {
        let token = self.get_or_create_delivery_token(peer);
        let queued = self.relay_mgr.drain_offline(&token);
        if queued.is_empty() {
            return;
        }
        tracing::info!(
            "[flush] peer {} reachable — retrying {} queued message(s)",
            peer,
            queued.len()
        );
        for sealed in queued {
            let mid = sealed.message_id;
            let wire_bytes = sealed.payload;
            match self
                .route_wire_with_policy(
                    peer,
                    mid,
                    wire_bytes.clone(),
                    DeliveryClass::MaintenanceRetry,
                    false,
                )
                .await
            {
                Ok(outcome) => {
                    let _ = self.messages.update_status(&mid, MessageStatus::Sent);
                    let _ = self
                        .event_tx
                        .send(ADAEvent::MessageStatusChanged {
                            message_id: mid,
                            status: MessageStatus::Sent,
                        })
                        .await;
                    self.publish_transport_outcome(
                        mid,
                        outcome.route.as_str(),
                        outcome.queue_depth,
                        outcome.latency_ms,
                    )
                    .await;
                }
                Err(_) => {
                    // Still unreachable — put back in the offline queue.
                    let sealed_back = crate::network::relay::SealedMessage {
                        message_id: mid,
                        recipient_id: peer.0,
                        payload: wire_bytes,
                        expires_at: sealed.expires_at,
                        hops: sealed.hops,
                    };
                    let _ = self.relay_mgr.enqueue_offline(token, sealed_back);
                }
            }
        }
    }

    async fn run_maintenance(&self) {
        tracing::debug!("Running ADA maintenance cycle");

        let in_background = self
            .is_background
            .load(std::sync::atomic::Ordering::Acquire);

        // ── Rotate pre-keys if needed ─────────────────────────────────────────
        // SPK must be rotated weekly (X3DH spec); rotating in maintenance ensures
        // peers pick up fresh bundles without requiring a restart.
        self.prekeys.write().await.rotate_spk_if_needed();
        // ── Replenish one-time pre-keys if pool is low ────────────────────────
        // When all 100 OPKs are consumed X3DH falls back to no-OPK mode (weaker
        // forward secrecy).  Replenish proactively before the pool drains.
        const LOW_OPK_THRESHOLD: usize = 10;
        let opk_count = self.prekeys.read().await.opk_count();
        if opk_count < LOW_OPK_THRESHOLD {
            tracing::warn!("OPK pool low ({} keys), replenishing", opk_count);
            self.prekeys.write().await.refill_opks();
        }
        // ── Prune stale peers / clean expired relay messages ─────────────────
        self.relay_mgr.evict_expired();
        // ── Flush pending offline-queue writes to disk ────────────────────────
        // `enqueue_offline` marks the queue dirty instead of writing immediately;
        // this call batches all enqueues since the last cycle into a single fsync.
        self.relay_mgr.flush_if_dirty();

        // ── Expire ringing/connecting calls that timed out ────────────────────
        self.call_mgr.check_timeouts().await;

        // ── Periodic retry of queued offline messages ─────────────────────
        // Delivers messages that were queued during network outages even when
        // no connectivity event fires (e.g. NAT traversal completes silently).
        if !in_background {
            self.retry_queued_offline_messages().await;
        } else {
            tracing::debug!("Battery optimization: skipping offline queue retry in background.");
        }

        // ── Sweep expired ephemeral messages ──────────────────────────────────
        let expired_ids = self.messages.sweep_ephemeral();
        if !expired_ids.is_empty() {
            tracing::debug!("Swept {} expired ephemeral messages", expired_ids.len());
        }

        // ── Expire messages stuck in "Sending" for more than 5 minutes ───────
        // Covers crash-recovery, killed process, and offline-queue TTL expiry
        // scenarios where the status was never updated to Sent or Failed.
        const STALE_SENDING_SECS: u64 = 5 * 60;
        let expired = self.messages.expire_stale_sending(STALE_SENDING_SECS);
        for mid in expired {
            tracing::info!("expired stale Sending message {}", hex::encode(mid));
            let _ = self
                .event_tx
                .send(ADAEvent::MessageStatusChanged {
                    message_id: mid,
                    status: MessageStatus::Failed("expired".into()),
                })
                .await;
        }

        // ── Presence check: diff iroh conn_cache vs known online set ─────────
        // Auto-delete ephemeral messages
        let affected_convs = self.messages.prune_ephemeral_messages();
        for conv_id in affected_convs {
            tracing::debug!("ephemeral messages pruned from conv {:?}", conv_id);
        }

        self.update_peer_presence().await;

        if in_background {
            tracing::debug!("Battery optimization: maintenance cycle completed (throttled).");
        }
    }

    /// Compare iroh's live connection cache against our `online_peers` set.
    /// Emit `PeerOnline` / `PeerOffline` events for any changes.
    async fn update_peer_presence(&self) {
        let now_online: std::collections::HashSet<[u8; 32]> = {
            let guard = self.iroh.read().await;
            match guard.as_ref() {
                Some(iroh) => iroh.connected_peer_ids().await,
                None => std::collections::HashSet::new(),
            }
        };

        let prev_online = self.online_peers.read().clone();

        // Newly online peers
        for &pid in now_online.difference(&prev_online) {
            let peer_id = PeerId(pid);
            tracing::debug!("peer online: {}", peer_id);
            let _ = self
                .event_tx
                .send(ADAEvent::PeerOnline(peer_id.clone()))
                .await;
            self.request_peer_sync(&peer_id, false).await;
        }
        // Newly offline peers
        for &pid in prev_online.difference(&now_online) {
            let peer_id = PeerId(pid);
            tracing::debug!("peer offline: {}", peer_id);
            let _ = self.event_tx.send(ADAEvent::PeerOffline(peer_id)).await;
        }

        *self.online_peers.write() = now_online;
    }

    /// Called by a fast background loop (every 50 ms) to pump in-progress
    /// file-transfer chunks to their recipients.
    pub async fn tick_transfers(self: &Arc<Self>) {
        self.pump_outbound_chunks().await;
    }

    /// Sweep all pending offline queues and retry delivery for any peer whose
    /// messages have not yet been delivered.  Called once per maintenance
    /// cycle (~60 s) so messages eventually get through even when the
    /// PeerDiscovered event was missed (e.g. NAT traversal completes after
    /// a cold start without mdns discovery).
    async fn retry_queued_offline_messages(&self) {
        // Collect (peer_b64, token, peer_id_bytes) snapshot to avoid holding
        // the lock across await points.
        let snapshot: Vec<(String, [u8; 32], [u8; 32])> = {
            let tokens = self.delivery_tokens.read();
            tokens
                .iter()
                .filter_map(|(peer_b64, &token)| {
                    // Decode the base64 peer_id to get the raw bytes for transport.
                    use base64::Engine;
                    let bytes: [u8; 32] = base64::engine::general_purpose::STANDARD
                        .decode(peer_b64)
                        .ok()
                        .and_then(|v| v.try_into().ok())?;
                    // Only process tokens that actually have pending messages.
                    if self.relay_mgr.offline_count(&token) == 0 {
                        return None;
                    }
                    Some((peer_b64.clone(), token, bytes))
                })
                .collect()
        };

        for (peer_b64, token, peer_bytes) in snapshot {
            let queued = self.relay_mgr.drain_offline(&token);
            if queued.is_empty() {
                continue;
            }
            tracing::info!(
                "[maintenance] retrying {} queued message(s) for {}",
                queued.len(),
                peer_b64
            );
            for sealed in queued {
                let mid = sealed.message_id;
                let wire_bytes = sealed.payload.clone();
                let peer = PeerId(peer_bytes);
                match self
                    .route_wire_with_policy(
                        &peer,
                        mid,
                        wire_bytes,
                        DeliveryClass::MaintenanceRetry,
                        false,
                    )
                    .await
                {
                    Ok(outcome) => {
                        let _ = self.messages.update_status(&mid, MessageStatus::Sent);
                        let _ = self
                            .event_tx
                            .send(ADAEvent::MessageStatusChanged {
                                message_id: mid,
                                status: MessageStatus::Sent,
                            })
                            .await;
                        self.publish_transport_outcome(
                            mid,
                            outcome.route.as_str(),
                            outcome.queue_depth,
                            outcome.latency_ms,
                        )
                        .await;
                    }
                    Err(_) => {
                        let _ = self.relay_mgr.enqueue_offline(token, sealed);
                    }
                }
            }
        }
    }

    // =====================================================================
    // FFI HELPERS
    // =====================================================================

    /// Poll one pending event, optionally blocking with a timeout.
    /// Used by FFI/JNI polling loops. Returns None if the timeout expires or queue is empty (for timeout = 0).
    pub async fn poll_event(&self, timeout_ms: u32) -> Option<ADAEvent> {
        let mut guard = self.event_rx.write().await;
        if let Some(rx) = guard.as_mut() {
            if timeout_ms == 0 {
                return rx.try_recv().ok();
            } else {
                return tokio::time::timeout(
                    std::time::Duration::from_millis(timeout_ms.into()),
                    rx.recv(),
                )
                .await
                .ok()
                .flatten();
            }
        }
        None
    }

    /// Get own display name
    pub fn display_name(&self) -> &str {
        &self.identity.display_name
    }

    /// Look up a peer's display name from the id store (sync, used by FFI event serialiser).
    pub fn get_sender_display_name(&self, sender: &PeerId) -> String {
        self.id_store
            .load_peer_bundle(sender)
            .ok()
            .flatten()
            .map(|b| b.display_name.clone())
            .filter(|n| !n.is_empty())
            .unwrap_or_default()
    }

    /// Add a custom iroh relay URL at runtime.
    ///
    /// Persists `relay_url` to the identity store so it survives app restarts
    /// (loaded in `start_iroh_endpoint`).  At runtime, immediately registers
    /// the relay as a routing hint for every known contact so the next QUIC
    /// connection attempt to any peer prefers this relay over the default n0
    /// infrastructure.
    ///
    /// Note: iroh 0.31 does not support changing the *home* relay of the local
    /// endpoint after startup — this sets per-peer routing hints only.
    pub async fn add_relay_node(&self, relay_url: &str) -> Result<()> {
        // Validate that the URL parses before persisting anything.
        relay_url
            .parse::<iroh::RelayUrl>()
            .map_err(|e| ADAError::Network(format!("invalid relay URL '{}': {}", relay_url, e)))?;

        // Persist so the hint is restored on next app launch.
        let _ = self
            .id_store
            .kv()
            .set("config/relay_node_url", relay_url.as_bytes().to_vec());
        tracing::info!("add_relay_node: persisted custom relay URL: {}", relay_url);

        // Apply immediately as a routing hint for all known peers.
        let iroh_guard = self.iroh.read().await;
        if let Some(iroh) = iroh_guard.as_ref() {
            let bundles = self.id_store.list_peer_bundles();
            let mut applied = 0usize;
            for bundle in &bundles {
                if iroh.add_peer_relay(&bundle.peer_id.0, relay_url).is_ok() {
                    applied += 1;
                }
            }
            tracing::info!("add_relay_node: applied relay hint for {} peer(s)", applied);
        }

        Ok(())
    }

    /// Register a known peer's public bundle (from manual contact add)
    pub fn add_contact(self: &Arc<Self>, bundle: PublicBundle) -> Result<()> {
        let conv_id = crate::messaging::store::ConversationId::Direct(bundle.peer_id.clone());
        let display_name = bundle.display_name.clone();
        let peer_bytes = bundle.peer_id.0;
        let relay_url = bundle.relay_url.clone();
        // Clear any stale in-memory ratchet session and persisted ratchet state so
        // that when this peer sends the first message after re-add, the new X3DH
        // handshake is used rather than the old (now-desynchronised) ratchet.
        self.sessions.clear_session(&bundle.peer_id);
        if let Err(e) = self.id_store.delete_ratchet_state(&bundle.peer_id) {
            tracing::warn!(
                "[add_contact] failed to delete stale ratchet for {}: {}",
                bundle.peer_id,
                e
            );
        }
        self.id_store.save_peer_bundle(&bundle)?;
        self.messages.upsert_conversation(&conv_id, &display_name);

        // Warmup: preload a saved relay hint and start the iroh QUIC handshake
        // in background so the first message doesn't wait for pkarr DNS.
        if let Ok(guard) = self.iroh.try_read() {
            if let Some(iroh) = guard.clone() {
                if let Some(relay_url) = relay_url.as_deref() {
                    if let Err(error) = iroh.add_peer_relay(&peer_bytes, relay_url) {
                        tracing::debug!(
                            "contact relay hint preload failed for {}: {}",
                            bundle.peer_id,
                            error
                        );
                    }
                }
                let warmup_counter = Arc::clone(&self.contact_warmup_inflight);
                let inflight = warmup_counter.fetch_add(1, std::sync::atomic::Ordering::AcqRel) + 1;

                let in_bg = self
                    .is_background
                    .load(std::sync::atomic::Ordering::Acquire);

                if in_bg {
                    warmup_counter.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                    tracing::debug!(
                        "Battery optimization: skipping contact warmup for {}",
                        bundle.peer_id
                    );
                } else if inflight > CONTACT_WARMUP_INFLIGHT_LIMIT {
                    warmup_counter.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                    tracing::debug!(
                        "contact warmup skipped for {}: inflight={} limit={}",
                        bundle.peer_id,
                        inflight,
                        CONTACT_WARMUP_INFLIGHT_LIMIT,
                    );
                } else {
                    let core_weak = Arc::downgrade(self);
                    spawn_background_task("contact-warmup", async move {
                        iroh.warmup_connection(&peer_bytes).await;
                        warmup_counter.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                        // Warmup complete — flush any messages that landed in the offline
                        // queue because the QUIC connection wasn't ready when the user
                        // sent the first message immediately after adding the contact.
                        if let Some(core) = core_weak.upgrade() {
                            core.flush_offline_queue_for(&PeerId(peer_bytes)).await;
                        }
                    });
                }
            }
        }

        let tx = self.event_tx.clone();
        let ev = ADAEvent::ContactUpdated(bundle);
        match tx.try_send(ev.clone()) {
            Ok(()) => {}
            Err(tokio::sync::mpsc::error::TrySendError::Full(ev)) => {
                spawn_background_task("contact-updated-event", async move {
                    let _ = tx.send(ev).await;
                });
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                tracing::debug!("contact updated event dropped: channel closed");
            }
        }

        Ok(())
    }

    /// Look up a known peer bundle by base64 peer id
    pub fn get_contact(&self, peer_id_b64: &str) -> Result<Option<PublicBundle>> {
        let peer = PeerId::from_base64(peer_id_b64)?;
        self.id_store.load_peer_bundle(&peer)
    }

    /// Mark all messages in a conversation as read (resets unread counter)
    pub fn mark_read(&self, conv: &ConversationId) {
        self.messages.mark_read(conv);
    }

    // =====================================================================
    // CALLS — query helpers
    // =====================================================================

    /// List all active calls enriched with optional group room metadata.
    pub async fn get_active_calls(&self) -> Vec<ActiveCallSnapshot> {
        let active_calls = self.call_mgr.active_calls_info().await;
        let active_call_ids: std::collections::HashSet<CallId> =
            active_calls.iter().map(|(call_id, ..)| *call_id).collect();
        self.sync_group_call_rooms_with_active_calls(&active_call_ids);

        active_calls
            .into_iter()
            .map(
                |(call_id, peer, has_video, is_outgoing, state)| ActiveCallSnapshot {
                    room: self.get_group_call_room_for_call(call_id),
                    call_id,
                    peer,
                    has_video,
                    is_outgoing,
                    state,
                },
            )
            .collect()
    }

    /// Returns the persistent call log as a JSON array (most-recent first).
    pub async fn get_call_history_json(&self, limit: usize) -> String {
        self.id_store.kv().get_call_history_json(limit)
    }

    // =====================================================================
    // FILE TRANSFER — query helpers
    // =====================================================================

    /// List all active transfers: (meta, progress 0-1, is_outbound).
    pub async fn get_active_transfers(&self) -> Vec<(crate::transfer::TransferMeta, f32, bool)> {
        self.transfer_mgr.active_transfers_info().await
    }

    /// Cancel a file transfer.
    pub async fn cancel_transfer(&self, transfer_id: [u8; 16]) {
        self.transfer_mgr.cancel(&transfer_id).await;
    }

    // =====================================================================
    // BRIDGE / CENSORSHIP
    // =====================================================================

    /// Synchronous snapshot of all runtime telemetry counters as a JSON string.
    ///
    /// Unlike `get_bridge_status_json`, this does **not** acquire any async
    /// lock — all values are read directly from atomics and parking_lot
    /// RwLocks.  Safe to call from a sync context (e.g. FFI block_on).
    pub fn get_metrics_snapshot(&self) -> String {
        use std::sync::atomic::Ordering::Acquire;
        let success = self.transport_success_total.load(Acquire);
        let failure = self.transport_failure_total.load(Acquire);
        let latency_ms = self.transport_latency_total_ms.load(Acquire);
        let latency_n = self.transport_latency_samples.load(Acquire);
        let route_totals = self.transport_route_totals.read().clone();
        let mailbox_depth = self.bridge_mailbox_depth.load(Acquire);
        let mailbox_hwm = self.bridge_mailbox_depth_high_watermark.load(Acquire);
        let sync_rounds = self.sync_rounds_total.load(Acquire);
        let sync_applied = self.sync_messages_applied_total.load(Acquire);
        let sync_dupes = self.sync_duplicates_skipped_total.load(Acquire);
        let recovery_ms = self.connection_recovery_total_ms.load(Acquire);
        let recovery_events = self.connection_recovery_events_total.load(Acquire);
        let route_flaps = self.connection_route_flaps_total.load(Acquire);
        let false_online = self.connection_false_online_detected_total.load(Acquire);
        let resync_backlog = self.connection_resync_backlog_count.load(Acquire);
        let state_trans = self.connection_state_transitions_total.load(Acquire);
        let health = self.connection_health_state();
        let iroh_consec_failures = self.iroh_consecutive_failures.load(Acquire);
        let is_bg = self.is_background.load(Acquire);
        let iroh_ready = self.iroh_started.load(Acquire);
        let calls_initiated = self.calls_initiated_total.load(Acquire);
        let calls_received = self.calls_received_total.load(Acquire);
        let calls_connected = self.calls_connected_total.load(Acquire);
        let calls_ended = self.calls_ended_total.load(Acquire);
        let calls_failed = self.calls_failed_total.load(Acquire);
        let ice_restarts = self.ice_restart_total.load(Acquire);
        let signaling_failures = self.call_signaling_failures_total.load(Acquire);
        let total = success + failure;
        serde_json::json!({
            "transport": {
                "success_total": success,
                "failure_total": failure,
                "success_rate": if total == 0 { serde_json::Value::Null }
                                 else { serde_json::json!(success as f64 / total as f64) },
                "avg_latency_ms": if latency_n == 0 { serde_json::Value::Null }
                                   else { serde_json::json!(latency_ms / latency_n) },
                "route_totals": route_totals,
            },
            "bridge": {
                "mailbox_depth": mailbox_depth,
                "mailbox_depth_hwm": mailbox_hwm,
            },
            "sync": {
                "rounds_total": sync_rounds,
                "messages_applied": sync_applied,
                "duplicates_skipped": sync_dupes,
            },
            "connection": {
                "health_state": health.as_str(),
                "state_transitions_total": state_trans,
                "recovery_events_total": recovery_events,
                "avg_recovery_ms": if recovery_events == 0 { serde_json::Value::Null }
                                    else { serde_json::json!(recovery_ms / recovery_events) },
                "route_flaps_total": route_flaps,
                "false_online_detected_total": false_online,
                "resync_backlog_count": resync_backlog,
                "iroh_consecutive_failures": iroh_consec_failures,
            },
            "process": {
                "is_background": is_bg,
                "iroh_ready": iroh_ready,
            },
            "calls": {
                "initiated_total": calls_initiated,
                "received_total": calls_received,
                "connected_total": calls_connected,
                "ended_total": calls_ended,
                "failed_total": calls_failed,
                "ice_restart_total": ice_restarts,
                "signaling_failures_total": signaling_failures,
            },
        })
        .to_string()
    }

    /// Get bridge list plus current runtime transport state as a JSON string.
    pub async fn get_bridge_status_json(&self) -> String {
        let bridges = self.bridges.read().await;
        let mode = match bridges.current_mode() {
            ObfuscationMode::None => "none",
            ObfuscationMode::RandomPadding { .. } => "padding",
            ObfuscationMode::TrafficShaping { .. } => "shaping",
            ObfuscationMode::WebSocketTLS { .. } => "websocket",
            ObfuscationMode::DomainFronting { .. } => "fronting",
            ObfuscationMode::Auto => "auto",
        };
        let list = bridges.status_list();
        let relay_only = self.relay_only_enabled();
        let last_outcome = self.last_transport_outcome.read().clone();
        let capabilities = self.current_route_capabilities().await;
        let manifest = self.bridge_manifest.read().clone();
        let manifest_source = self.bridge_manifest_source.read().clone();
        let listener_connected = self
            .bridge_listener_connected
            .load(std::sync::atomic::Ordering::Acquire);
        let listener_route = self.bridge_listener_route.read().clone();
        let mailbox_depth = self
            .bridge_mailbox_depth
            .load(std::sync::atomic::Ordering::Acquire);
        let mailbox_depth_high_watermark = self
            .bridge_mailbox_depth_high_watermark
            .load(std::sync::atomic::Ordering::Acquire);
        let route_totals = self.transport_route_totals.read().clone();
        let success_total = self
            .transport_success_total
            .load(std::sync::atomic::Ordering::Acquire);
        let failure_total = self
            .transport_failure_total
            .load(std::sync::atomic::Ordering::Acquire);
        let latency_total_ms = self
            .transport_latency_total_ms
            .load(std::sync::atomic::Ordering::Acquire);
        let latency_samples = self
            .transport_latency_samples
            .load(std::sync::atomic::Ordering::Acquire);
        let recovery_total_ms = self
            .connection_recovery_total_ms
            .load(std::sync::atomic::Ordering::Acquire);
        let recovery_events_total = self
            .connection_recovery_events_total
            .load(std::sync::atomic::Ordering::Acquire);
        let route_flaps_total = self
            .connection_route_flaps_total
            .load(std::sync::atomic::Ordering::Acquire);
        let false_online_detected_total = self
            .connection_false_online_detected_total
            .load(std::sync::atomic::Ordering::Acquire);
        let resync_backlog_count = self
            .connection_resync_backlog_count
            .load(std::sync::atomic::Ordering::Acquire);
        let recovering_since_ms = self
            .connection_recovering_since_ms
            .load(std::sync::atomic::Ordering::Acquire);
        let recovery_in_progress_ms = if recovering_since_ms == 0 {
            None::<u64>
        } else {
            Some(unix_now_ms().saturating_sub(recovering_since_ms))
        };
        let contact_warmup_inflight = self
            .contact_warmup_inflight
            .load(std::sync::atomic::Ordering::Acquire);
        let sync_rounds_total = self
            .sync_rounds_total
            .load(std::sync::atomic::Ordering::Acquire);
        let sync_messages_applied_total = self
            .sync_messages_applied_total
            .load(std::sync::atomic::Ordering::Acquire);
        let sync_duplicates_skipped_total = self
            .sync_duplicates_skipped_total
            .load(std::sync::atomic::Ordering::Acquire);
        let connection_state = self.connection_health_state();
        let connection_state_transitions_total = self
            .connection_state_transitions_total
            .load(std::sync::atomic::Ordering::Acquire);
        let total_outcomes = success_total + failure_total;
        let iroh_ready = self.iroh_started.load(std::sync::atomic::Ordering::Acquire);
        let profile = self.connection_profile();
        let mailbox_pull_interval_secs = self
            .mailbox_poll_interval()
            .map(|duration| duration.as_secs());
        serde_json::json!({
            "mode": mode,
            "connection_profile": profile.as_str(),
            "bridges": list,
            "has_working": bridges.has_working_bridge(),
            "relay_only": relay_only,
            "iroh_ready": iroh_ready,
            "transport_stack": if bridges.bridges().is_empty() { "iroh_only" } else { "iroh_bridge_mailbox" },
            "route_granularity": if bridges.bridges().is_empty() {
                "iroh_live_or_offline_queue"
            } else {
                "iroh_bridge_mailbox_or_offline_queue"
            },
            "relay_only_scope": if relay_only {
                if bridges.bridges().is_empty() { "queue_only" } else { "bridge_or_mailbox_only" }
            } else {
                "disabled"
            },
            "bridge_listener_connected": listener_connected,
            "bridge_listener_route": listener_route,
            "bridge_mailbox_depth": mailbox_depth,
            "mailbox_pull_interval_secs": mailbox_pull_interval_secs,
            "connection_state": connection_state.as_str(),
            "telemetry": {
                "route_success_total": success_total,
                "route_failure_total": failure_total,
                "route_success_rate": if total_outcomes == 0 {
                    None::<f64>
                } else {
                    Some(success_total as f64 / total_outcomes as f64)
                },
                "avg_latency_ms": if latency_samples == 0 {
                    None::<u64>
                } else {
                    Some(latency_total_ms / latency_samples)
                },
                "mailbox_depth_high_watermark": mailbox_depth_high_watermark,
                "route_totals": route_totals,
                "recovery_time_ms": recovery_in_progress_ms,
                "avg_recovery_time_ms": if recovery_events_total == 0 {
                    None::<u64>
                } else {
                    Some(recovery_total_ms / recovery_events_total)
                },
                "resync_backlog_count": resync_backlog_count,
                "false_online_detected_total": false_online_detected_total,
                "route_flaps_total": route_flaps_total,
                "contact_warmup_inflight": contact_warmup_inflight,
                "sync_rounds_total": sync_rounds_total,
                "sync_messages_applied_total": sync_messages_applied_total,
                "sync_duplicates_skipped_total": sync_duplicates_skipped_total,
                "connection_state_transitions_total": connection_state_transitions_total,
            },
            "capabilities": {
                "bridge_live_delivery": capabilities.bridge_live_delivery,
                "mailbox_delivery": capabilities.mailbox_delivery,
                "mailbox_pull": mailbox_pull_interval_secs.is_some(),
                "realtime_calls": capabilities.realtime_calls,
                "large_attachments": capabilities.large_attachments,
                "max_attachment_bytes": capabilities.max_attachment_bytes,
            },
            "manifest": manifest.as_ref().map(|m| serde_json::json!({
                "version": m.version,
                "issued_at_ms": m.issued_at_ms,
                "ttl_secs": m.ttl_secs,
                "bridge_count": m.bridges.len(),
                "supports_realtime_calls": m.supports_realtime_calls,
                "max_attachment_bytes": m.max_attachment_bytes,
                "source": manifest_source,
            })),
            "last_outcome": last_outcome.as_ref().map(|o| serde_json::json!({
                "message_id": hex::encode(o.message_id),
                "route": o.route,
                "queue_depth": o.queue_depth,
                "latency_ms": o.latency_ms,
                "updated_at_ms": o.updated_at_ms,
            })),
        }).to_string()
    }

    /// Detect current censorship level; returns JSON `{"level":"None"|"Light"|...}`.
    ///
    /// Also automatically applies the recommended obfuscation mode to the
    /// BridgeManager so future deliveries immediately use the right transport
    /// without requiring a manual `set_bridge_mode_str` call.
    pub async fn detect_censorship_json(&self) -> String {
        let probe_report = crate::bridge::bridge::detect_censorship_probes().await;
        let capabilities = self.current_route_capabilities().await;
        let last_successful_live_route =
            self.last_transport_outcome
                .read()
                .as_ref()
                .and_then(|outcome| {
                    (!matches!(
                        outcome.route.as_str(),
                        "failed" | "offline_queue" | "mailbox_bridge"
                    ))
                    .then(|| outcome.route.clone())
                });
        let evidence = RuntimeCensorshipEvidence {
            iroh_ready: self.iroh_started.load(std::sync::atomic::Ordering::Acquire),
            online_iroh_peers: self.online_peers.read().len(),
            relay_only: self.relay_only_enabled(),
            bridge_listener_connected: self
                .bridge_listener_connected
                .load(std::sync::atomic::Ordering::Acquire),
            realtime_calls: capabilities.realtime_calls,
            large_attachments: capabilities.large_attachments,
            last_successful_live_route,
        };
        let level = reconcile_censorship_level(probe_report.level.clone(), &evidence);

        // Auto-apply recommended mode based on detected level (GAP-1 fix).
        let mode = {
            let guard = self.bridges.read().await;
            guard.recommended_mode(level.clone())
        };
        self.bridges.write().await.set_mode(mode);

        // BUG-2 fix: In Extreme censorship (whitelist-based IP blocking) the
        // n0 public iroh relay IPs are also blocked.  Enable relay_only so
        // route_wire_with_policy skips the IrohLive attempt (which would block
        // for up to CONNECT_TIMEOUT = 15 s) and goes straight to the bridge route.
        if matches!(level, crate::bridge::bridge::CensorshipLevel::Extreme) {
            self.relay_only
                .store(true, std::sync::atomic::Ordering::Release);
            tracing::info!(
                "[censorship] Extreme level — relay_only enabled to skip dead iroh timeout"
            );
        }

        tracing::info!(
            "[censorship] probe_level={:?} effective_level={:?} iroh_ready={} online_iroh_peers={} realtime_calls={} large_attachments={} bridge_listener_connected={}",
            probe_report.level,
            level,
            evidence.iroh_ready,
            evidence.online_iroh_peers,
            evidence.realtime_calls,
            evidence.large_attachments,
            evidence.bridge_listener_connected,
        );
        serde_json::json!({
            "level": format!("{:?}", level),
            "probe_level": format!("{:?}", probe_report.level),
            "details": {
                "probes": {
                    "tcp80_ok": probe_report.tcp80_ok,
                    "tcp443_ok": probe_report.tcp443_ok,
                    "cdn_ok": probe_report.cdn_ok,
                    "google_ok": probe_report.google_ok,
                },
                "runtime": {
                    "iroh_ready": evidence.iroh_ready,
                    "online_iroh_peers": evidence.online_iroh_peers,
                    "relay_only": evidence.relay_only,
                    "bridge_listener_connected": evidence.bridge_listener_connected,
                    "realtime_calls": evidence.realtime_calls,
                    "large_attachments": evidence.large_attachments,
                    "last_successful_live_route": evidence.last_successful_live_route,
                }
            }
        })
        .to_string()
    }

    /// Set obfuscation mode from a simple string key.
    /// Keys: "none", "padding", "shaping", "websocket", "fronting".
    /// Toggle relay-only routing at runtime.
    ///
    /// Public iroh 0.31 does not currently let ADA verify a live relay-only
    /// path via its stable API. Therefore, when `enabled = true`, ADA disables
    /// live outgoing iroh sends for unicast traffic and allows only bridge,
    /// mailbox, or local offline-queue routes.
    pub fn set_relay_only(&self, enabled: bool) {
        self.relay_only
            .store(enabled, std::sync::atomic::Ordering::Release);
        tracing::info!(
            "relay_only = {} (post-phase-1 semantics: iroh disabled; bridge/mailbox or local queue only)",
            enabled
        );
    }

    pub fn set_connection_profile(&self, profile: ConnectionProfile) {
        let old = {
            let mut guard = self.connection_profile.write();
            let old = *guard;
            *guard = profile;
            old
        };
        if old != profile {
            tracing::info!(
                "connection_profile = {} (was {})",
                profile.as_str(),
                old.as_str()
            );
        }
    }

    /// Update the background execution state.
    ///
    /// Setting `is_background = true` enables aggressive battery optimization
    /// limits: throttling DHT queries, reducing background keep-alive frequency,
    /// and deferring non-critical Iroh updates.
    pub fn set_app_background_state(&self, in_background: bool) {
        let old = self
            .is_background
            .swap(in_background, std::sync::atomic::Ordering::Release);
        if old != in_background {
            tracing::info!("App background state changed: {} -> {}", old, in_background);
            // Throttle DHT operations if moved to background
            if in_background {
                tracing::debug!(
                    "Battery optimization active: throttling DHT and delaying warmups."
                );
            }
        }
    }

    pub async fn set_bridge_mode_str(&self, mode_str: &str) {
        let mode = match mode_str {
            "none" => ObfuscationMode::None,
            "padding" => ObfuscationMode::RandomPadding { max_padding: 256 },
            "shaping" => ObfuscationMode::TrafficShaping {
                target_rate_bps: 100_000,
            },
            "websocket" => ObfuscationMode::WebSocketTLS {
                hostname: "cdn.cloudflare.com".to_string(),
            },
            "fronting" => ObfuscationMode::DomainFronting {
                front_domain: "cdn.cloudflare.com".to_string(),
                real_host: "ada.network".to_string(),
            },
            _ => ObfuscationMode::Auto,
        };
        self.bridges.write().await.set_mode(mode);
    }

    // =====================================================================
    // CALL SIGNALING — ICE candidate routing
    // =====================================================================

    /// Send an ICE candidate to the remote peer (called by WebRTC layer).
    pub async fn send_ice_candidate(
        &self,
        call_id: CallId,
        peer: PeerId,
        candidate: String,
    ) -> Result<()> {
        self.send_ice_candidate_with_sdp(call_id, peer, candidate, None, None)
            .await
    }

    /// Send an ICE candidate with full SDP metadata to the remote peer.
    pub async fn send_ice_candidate_with_sdp(
        &self,
        call_id: CallId,
        peer: PeerId,
        candidate: String,
        sdp_mid: Option<String>,
        sdp_mline_index: Option<u16>,
    ) -> Result<()> {
        self.ensure_realtime_capable().await?;
        // Record locally so both sides reflect the same state
        self.call_mgr
            .add_ice_candidate(
                call_id,
                IceCandidate {
                    candidate: candidate.clone(),
                    sdp_mid: sdp_mid.clone(),
                    sdp_mline_index,
                },
            )
            .await?;

        let kind = MessageKind::Call(crate::messaging::types::CallEvent::Candidate {
            call_id,
            candidate,
            sdp_mid: sdp_mid.clone(),
            sdp_mline_index,
        });
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);

        // Sign + encrypt once; retry delivery up to 3 times.
        // ICE candidates are time-critical so we keep the window short (3 × 2s = 6s).
        let (peer_id, wire_bytes, _) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("ice candidate prepare failed: {}", e);
                return Err(e);
            }
        };

        self.send_live_call_signal(&peer_id, call_id, wire_bytes, "ice candidate", 3, false)
            .await
    }

    // =====================================================================
    // CALL SIGNALING — internal handler for received Call messages
    // =====================================================================

    async fn handle_incoming_call_event(
        &self,
        from: PeerId,
        event: crate::messaging::types::CallEvent,
    ) {
        use crate::messaging::types::CallEvent;

        match event {
            CallEvent::Invite {
                call_id,
                offer_sdp,
                has_video,
                group_id,
                session_id,
                participants,
            } => {
                tracing::info!(
                    "handle_incoming_call_event: Invite call_id={} has_video={}",
                    hex::encode(call_id),
                    has_video
                );
                match self
                    .call_mgr
                    .handle_incoming(from.clone(), Some(call_id), offer_sdp.clone(), has_video)
                    .await
                {
                    Ok(_) => {
                        let room = match (group_id, session_id) {
                            (Some(group_id), Some(session_id)) => {
                                let mut room_participants = participants;
                                if !room_participants
                                    .iter()
                                    .any(|participant| participant == &from)
                                {
                                    room_participants.push(from.clone());
                                }
                                if !room_participants
                                    .iter()
                                    .any(|participant| participant == &self.identity.peer_id)
                                {
                                    room_participants.push(self.identity.peer_id.clone());
                                }
                                self.register_group_call_room(
                                    group_id,
                                    session_id,
                                    has_video,
                                    room_participants,
                                    &[call_id],
                                );
                                self.get_group_call_room_for_call(call_id)
                            }
                            _ => None,
                        };
                        tracing::info!("handle_incoming_call_event: emitting IncomingCall event");
                        self.calls_received_total
                            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        let _ = self
                            .event_tx
                            .send(ADAEvent::IncomingCall {
                                call_id,
                                from,
                                has_video,
                                offer_sdp,
                                room,
                            })
                            .await;
                    }
                    Err(e) => {
                        let err_str = e.to_string();
                        if err_str.contains("busy:") {
                            // Already on a call — send Busy hangup to the caller so their
                            // UI can show "busy" instead of just stalling until ring timeout.
                            tracing::info!(
                                "handle_incoming_call_event: sending Busy to {} for call {}",
                                from.to_base64(),
                                hex::encode(call_id)
                            );
                            self.call_signaling_failures_total
                                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                            self.send_call_hangup(
                                from,
                                call_id,
                                crate::messaging::types::HangupReason::Busy,
                            )
                            .await;
                        } else {
                            // Duplicate invite or other error — silent ignore.
                            tracing::warn!("handle_incoming call failed: {}", err_str);
                        }
                    }
                }
            }
            CallEvent::Answer {
                call_id,
                answer_sdp,
            } => {
                if let Err(e) = self
                    .call_mgr
                    .set_remote_sdp(call_id, answer_sdp.clone())
                    .await
                {
                    tracing::warn!("set_remote_sdp failed: {}", e);
                } else {
                    // Look up call details from the manager (already transitioned to Active).
                    let (has_video, peer) = self
                        .call_mgr
                        .active_calls_info()
                        .await
                        .into_iter()
                        .find(|(id, ..)| *id == call_id)
                        .map(|(_, p, hv, _, _)| (hv, p))
                        .unwrap_or((false, from.clone()));
                    let state = crate::media::call::CallState::Active {
                        started_at: std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs(),
                        has_video,
                    };
                    self.calls_connected_total
                        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    let _ = self
                        .event_tx
                        .send(ADAEvent::CallStateChanged {
                            call_id,
                            peer,
                            has_video,
                            state,
                            answer_sdp: Some(answer_sdp),
                        })
                        .await;
                }
            }
            CallEvent::Candidate {
                call_id,
                candidate,
                sdp_mid,
                sdp_mline_index,
            } => {
                let _ = self
                    .event_tx
                    .send(ADAEvent::IceCandidate {
                        call_id,
                        peer: from,
                        candidate,
                        sdp_mid,
                        sdp_mline_index,
                    })
                    .await;
            }
            CallEvent::Hangup { call_id, .. } => {
                let call_info = self
                    .call_mgr
                    .active_calls_info()
                    .await
                    .into_iter()
                    .find(|(id, ..)| *id == call_id);
                let has_video = call_info
                    .as_ref()
                    .map(|(_, _, hv, _, _)| *hv)
                    .unwrap_or(false);
                let is_outgoing = call_info
                    .as_ref()
                    .map(|(_, _, _, out, _)| *out)
                    .unwrap_or(false);
                let duration = self.call_mgr.call_duration(call_id).await;
                let ended_at = unix_now_secs();
                let started_at = ended_at.saturating_sub(duration as i64);
                self.id_store.kv().save_call_log_entry(
                    &hex::encode(call_id),
                    &from.to_base64(),
                    if is_outgoing { "outgoing" } else { "incoming" },
                    has_video,
                    duration as i64,
                    started_at,
                    ended_at,
                    "hung_up",
                );
                let _ = self.call_mgr.hangup(call_id).await;
                self.calls_ended_total
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let state = crate::media::call::CallState::Ended {
                    duration_secs: duration,
                    reason: crate::media::call::EndReason::HungUp,
                };
                let _ = self
                    .event_tx
                    .send(ADAEvent::CallStateChanged {
                        call_id,
                        peer: from,
                        has_video,
                        state,
                        answer_sdp: None,
                    })
                    .await;
            }
            CallEvent::IceRestartOffer { call_id, offer_sdp } => {
                tracing::info!(
                    "handle_incoming_call_event: IceRestartOffer for call {}",
                    hex::encode(call_id)
                );
                self.ice_restart_total
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                // Forward the restart offer to Android so WebRTCBridge can
                // setRemoteDescription + createAnswer + signal back.
                let _ = self
                    .event_tx
                    .send(ADAEvent::IceRestartOffer {
                        call_id,
                        peer: from,
                        offer_sdp,
                    })
                    .await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::bridge::CensorshipLevel;
    use crate::identity::PeerId;
    use crate::messaging::types::MessageKind;

    fn sync_test_message(timestamp: u64) -> Message {
        let mut message = Message::new(
            PeerId([1u8; 32]),
            Some(PeerId([2u8; 32])),
            MessageKind::Text(format!("m-{timestamp}")),
        );
        message.timestamp = timestamp;
        message
    }

    #[test]
    fn healthy_iroh_runtime_overrides_probe_to_none() {
        let evidence = RuntimeCensorshipEvidence {
            iroh_ready: true,
            online_iroh_peers: 2,
            relay_only: false,
            bridge_listener_connected: false,
            realtime_calls: true,
            large_attachments: true,
            last_successful_live_route: Some("iroh_live".to_string()),
        };

        assert_eq!(
            reconcile_censorship_level(CensorshipLevel::Moderate, &evidence),
            CensorshipLevel::None
        );
    }

    #[test]
    fn partial_runtime_softens_probe_to_light() {
        let evidence = RuntimeCensorshipEvidence {
            iroh_ready: true,
            online_iroh_peers: 1,
            relay_only: false,
            bridge_listener_connected: false,
            realtime_calls: true,
            large_attachments: false,
            last_successful_live_route: Some("iroh_live".to_string()),
        };

        assert_eq!(
            reconcile_censorship_level(CensorshipLevel::Moderate, &evidence),
            CensorshipLevel::Light
        );
    }

    #[test]
    fn relay_only_does_not_force_green() {
        let evidence = RuntimeCensorshipEvidence {
            iroh_ready: true,
            online_iroh_peers: 2,
            relay_only: true,
            bridge_listener_connected: true,
            realtime_calls: true,
            large_attachments: true,
            last_successful_live_route: Some("iroh_live".to_string()),
        };

        assert_eq!(
            reconcile_censorship_level(CensorshipLevel::Moderate, &evidence),
            CensorshipLevel::Moderate
        );
    }

    #[test]
    fn sync_cursor_mode_returns_immediately_preceding_page() {
        let candidates = (1..=488).map(sync_test_message).collect::<Vec<_>>();

        let (page, has_more, next_cursor_before_ts) = paginate_sync_candidates(candidates, 256);

        assert_eq!(page.len(), 256);
        assert_eq!(page.first().map(|m| m.timestamp), Some(233));
        assert_eq!(page.last().map(|m| m.timestamp), Some(488));
        assert!(has_more);
        assert_eq!(next_cursor_before_ts, Some(233));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn sync_backlog_recovery_pages_without_gaps() {
        let alice_dir = tempfile::tempdir().expect("alice temp dir should be created");
        let bob_dir = tempfile::tempdir().expect("bob temp dir should be created");

        let mut alice_cfg = ADAConfig::default();
        alice_cfg.storage.data_dir = alice_dir
            .path()
            .join("alice")
            .to_string_lossy()
            .into_owned();
        let mut bob_cfg = ADAConfig::default();
        bob_cfg.storage.data_dir = bob_dir.path().join("bob").to_string_lossy().into_owned();

        let alice = ADACore::new(alice_cfg, "Alice")
            .await
            .expect("alice core should initialize");
        let bob = ADACore::new(bob_cfg, "Bob")
            .await
            .expect("bob core should initialize");

        let full_history = (1..=600u64)
            .map(|timestamp| {
                let mut message = Message::new(
                    bob.peer_id().clone(),
                    Some(alice.peer_id().clone()),
                    MessageKind::Text(format!("m-{timestamp}")),
                );
                message.timestamp = timestamp;
                message.status = MessageStatus::Delivered;
                message
            })
            .collect::<Vec<_>>();

        let bob_conv = ConversationId::Direct(alice.peer_id().clone());
        for message in &full_history {
            bob.messages
                .save_message_with_unread(&bob_conv, message.clone(), false)
                .expect("bob history save should succeed");
        }

        let alice_conv = ConversationId::Direct(bob.peer_id().clone());
        for message in &full_history[344..] {
            alice
                .messages
                .save_message_with_unread(&alice_conv, message.clone(), false)
                .expect("alice seed history save should succeed");
        }

        let mut rounds = 0usize;
        let mut first_round_range = None;

        loop {
            rounds += 1;
            let (latest_message_ts, known_message_ids, cursor_before_ts, max_messages) =
                alice.build_sync_request_payload(bob.peer_id());
            let (missing, has_more, next_cursor_before_ts) = bob.build_sync_response_payload(
                alice.peer_id(),
                latest_message_ts,
                known_message_ids.as_slice(),
                cursor_before_ts,
                max_messages,
            );

            if rounds == 1 {
                first_round_range = Some((
                    missing.first().map(|m| m.timestamp),
                    missing.last().map(|m| m.timestamp),
                ));
            }

            if missing.is_empty() {
                assert!(!has_more, "empty sync page must not advertise more data");
                break;
            }

            alice
                .apply_sync_response_from_peer(bob.peer_id(), missing)
                .await;

            if has_more {
                alice
                    .sync_peer_cursor_before_ts
                    .write()
                    .insert(bob.peer_id().0, next_cursor_before_ts);
            } else {
                alice
                    .sync_peer_cursor_before_ts
                    .write()
                    .remove(&bob.peer_id().0);
                break;
            }

            assert!(
                rounds < 10,
                "sync backlog recovery should finish in bounded rounds"
            );
        }

        let recovered = alice.messages.get_messages(&alice_conv, None, 1000);
        assert_eq!(
            recovered.len(),
            600,
            "alice should recover the full backlog"
        );
        assert_eq!(first_round_range, Some((Some(217), Some(344))));

        for expected_timestamp in 1..=600u64 {
            assert!(
                recovered
                    .iter()
                    .any(|message| message.timestamp == expected_timestamp),
                "missing synced message timestamp {}",
                expected_timestamp
            );
        }
    }

    #[test]
    fn only_iroh_live_route_implies_peer_online() {
        assert!(route_implies_iroh_peer_online(&TransportRoute::IrohLive));
        assert!(!route_implies_iroh_peer_online(
            &TransportRoute::BridgeWebSocketTls
        ));
        assert!(!route_implies_iroh_peer_online(
            &TransportRoute::MailboxBridge
        ));
        assert!(!route_implies_iroh_peer_online(
            &TransportRoute::OfflineQueue
        ));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn outgoing_prepare_text_message_keeps_unread_zero() {
        let dir = tempfile::tempdir().expect("temp dir should be created");
        let mut config = ADAConfig::default();
        config.storage.data_dir = dir.path().join("alice").to_string_lossy().into_owned();

        let core = ADACore::new(config, "Alice")
            .await
            .expect("core should initialize");
        let bob = Identity::generate("Bob");

        core.add_contact(bob.public_bundle())
            .expect("contact import should succeed");
        core.prepare_text_message(&bob.peer_id, "hello".into(), None)
            .await
            .expect("message should prepare");

        let conversations = core.messages.list_conversations();
        assert_eq!(conversations.len(), 1);
        assert_eq!(conversations[0].unread_count, 0);
    }
}

impl ADACore {
    /// Send an ICE restart offer to the peer (called by the original offerer when
    /// ICE fails). The peer will respond with a CallEvent::Answer (same call_id).
    pub async fn send_ice_restart_offer(&self, call_id: CallId, peer: PeerId, offer_sdp: String) {
        if self.ensure_realtime_capable().await.is_err() {
            tracing::warn!("ice restart offer suppressed: realtime route unavailable");
            return;
        }
        let kind = MessageKind::Call(crate::messaging::types::CallEvent::IceRestartOffer {
            call_id,
            offer_sdp: offer_sdp.clone(),
        });
        let msg = Message::new(self.identity.peer_id.clone(), Some(peer.clone()), kind);
        let (peer_id, wire_bytes, _) = match self.prepare_message(msg).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!("ice restart offer prepare failed: {}", e);
                return;
            }
        };
        let _ = self
            .send_live_call_signal(&peer_id, call_id, wire_bytes, "ice restart offer", 5, false)
            .await;
    }

    /// Send an ICE restart answer (called by the answerer in response to IceRestartOffer).
    /// Reuses CallEvent::Answer so the caller’s existing answer handler updates remote SDP.
    pub async fn send_ice_restart_answer(&self, call_id: CallId, peer: PeerId, answer_sdp: String) {
        if let Err(err) = self.send_call_answer(peer, call_id, answer_sdp).await {
            tracing::warn!("ice restart answer send failed: {}", err);
        }
    }
}
