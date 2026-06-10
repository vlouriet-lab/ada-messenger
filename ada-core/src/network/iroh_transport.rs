//! iroh unicast transport for cross-network DM delivery.
//!
//! ## Why iroh?
//!
//! - **Public relay infra** by n0 — no own server required
//! - **QUIC transport** — lower latency, runs on 443 (stealth)
//! - **Relay-first + hole-punch** — works through symmetric NAT
//! - **NodeId = Ed25519 public key** = ADA `PeerId` bytes — no extra exchange
//! - **Automatic discovery** via `PkarrPublisher` + `DnsDiscovery` on n0's
//!   public pkarr/DNS — peers find each other by NodeId alone
//!
//! ## Connection lifecycle
//!
//! iroh QUIC connections are long-lived and multiplexed.  Sending many messages
//! to the same peer should reuse the same underlying QUIC connection rather than
//! performing a new handshake + relay lookup each time.  `IrohTransport` keeps a
//! `conn_cache` keyed by NodeId bytes.  On cache hit the message is sent over an
//! already-established stream in ~1 ms; on cache miss the full connect (relay
//! lookup + QUIC handshake) runs once and the connection is cached.  On a stale
//! connection error the entry is evicted and exactly one reconnect is attempted.
//!
//! ## Fallback
//! If iroh send fails after retry, the message is queued in the offline relay
//! for automatic retry when the peer next comes online.

use crate::error::{ADAError, Result};
use iroh::address_lookup::{DnsAddressLookup, MdnsAddressLookup, PkarrPublisher};
use iroh::endpoint::Connection;
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayUrl, SecretKey, TransportAddr};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{mpsc, RwLock};

/// ALPN protocol identifier for ADA direct messages over iroh.
/// Both DM (uni-stream) and Blob (bidi-stream) traffic share this connection;
/// the stream direction is how the two sub-protocols are distinguished.
pub const ADA_DM_ALPN: &[u8] = b"ada/dm/1.0";

/// Maximum size of a single DM message delivered over a uni-stream.
/// 4 MiB is sufficient for any text message + signaling payload.
/// A remote peer sending a larger frame is either buggy or malicious;
/// rejecting it prevents a single QUIC stream from exhausting mobile RAM.
const MAX_MSG_BYTES: usize = 4 * 1024 * 1024; // 4 MiB max DM size

/// Maximum blob size accepted over a bidi-stream.
/// 512 MiB is a generous upper bound for file attachments on mobile.
/// Raise at the operator level if large-file sharing is required.
const MAX_BLOB_BYTES: usize = 512 * 1024 * 1024; // 512 MiB

/// Timeout for reading blob length prefix from remote peer.
const BLOB_FETCH_HEADER_TIMEOUT: Duration = Duration::from_secs(30);
/// Lower/upper bounds for adaptive blob payload read timeout.
const BLOB_FETCH_MIN_TIMEOUT: Duration = Duration::from_secs(60);
const BLOB_FETCH_MAX_TIMEOUT: Duration = Duration::from_secs(1800); // 30 minutes
const BLOB_FETCH_ATTEMPTS: usize = 10;

/// Content-addressed in-memory blob store.
/// Keys are 32-byte blake3 hashes; values are raw file bytes.
/// Blobs are retained until the sender evicts them (e.g. after delivery confirmation).
#[derive(Clone)]
pub enum BlobData {
    Memory(Vec<u8>),
    File(std::path::PathBuf),
}

pub type BlobStore = Arc<RwLock<HashMap<[u8; 32], BlobData>>>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DiscoveryMode {
    N0Dns { mdns: bool },
    Disabled,
}

impl DiscoveryMode {
    fn label(self) -> &'static str {
        match self {
            Self::N0Dns { mdns: true } => "n0-dns+mdns",
            Self::N0Dns { mdns: false } => "n0-dns",
            Self::Disabled => "disabled",
        }
    }
}

/// Timeout for establishing an iroh QUIC connection on cache miss.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// Timeout covering `write_all` + `finish` on an already-open stream.
const WRITE_TIMEOUT: Duration = Duration::from_secs(15);

/// Timeout for `read_to_end` on the receiver side.
const READ_TIMEOUT: Duration = Duration::from_secs(60);

/// A message received on the iroh endpoint.
pub struct IrohMessage {
    /// Ed25519 public key of the sender (= their ADA `PeerId` bytes).
    /// Verified by iroh's TLS layer — not self-reported.
    pub from: [u8; 32],
    /// Raw wire bytes (bincode-encoded `EncryptedWire`).
    pub data: Vec<u8>,
}

/// iroh transport handle.
///
/// Wraps `iroh::Endpoint` plus a connection cache so that messages to the same
/// peer reuse the existing QUIC connection instead of re-doing handshake each time.
///
/// ## Sub-protocols over a single QUIC connection
/// - **DM (uni-stream)**: small messages, fire-and-forget.
/// - **Blob (bidi-stream)**: large files, content-addressed by blake3 hash.
///   Sender stores bytes via `store_blob()`, receiver pulls them via `fetch_blob()`.
pub struct IrohTransport {
    endpoint: Endpoint,
    /// Cached live connections keyed by remote EndpointId bytes.
    /// On `send()` we do a fast read-lock check first; only on cache miss (or stale
    /// connection) do we acquire a write lock and establish a new connection.
    conn_cache: RwLock<HashMap<[u8; 32], Connection>>,
    /// Content-addressed blob store for Iroh Blobs transfers.
    /// Populated by `store_blob()`; consumed remotely via bidi-stream requests;
    /// entries evicted by `evict_blob()` after the receiver confirms receipt.
    pub blob_store: BlobStore,
    /// Direct socket addresses registered via `add_peer_addr()`.
    /// Used in `get_or_connect()` to build a targeted `EndpointAddr` so iroh can reach
    /// peers without address lookup (required in tests and LAN-only environments).
    peer_addrs: RwLock<HashMap<[u8; 32], std::net::SocketAddr>>,
    /// Relay hints learned from peers via `IrohHint` / saved bundles.
    /// Used to bypass slow DNS-based address lookup on the first cold connect.
    peer_relays: RwLock<HashMap<[u8; 32], RelayUrl>>,
}

impl IrohTransport {
    fn connection_is_alive(connection: &Connection) -> bool {
        connection.close_reason().is_none()
    }

    async fn invalidate_connection(&self, peer_id: &[u8; 32]) {
        self.conn_cache.write().await.remove(peer_id);
    }

    async fn bind_endpoint(
        secret_key: SecretKey,
        discovery_mode: DiscoveryMode,
    ) -> Result<Endpoint> {
        let mut builder = Endpoint::builder()
            .secret_key(secret_key)
            .alpns(vec![ADA_DM_ALPN.to_vec()])
            .clear_address_lookup();

        let bind_result = match discovery_mode {
            DiscoveryMode::N0Dns { mdns } => {
                builder = builder
                    .address_lookup(PkarrPublisher::n0_dns())
                    .address_lookup(DnsAddressLookup::n0_dns());
                if mdns {
                    builder = builder.address_lookup(MdnsAddressLookup::builder());
                    tracing::info!("iroh: mDNS address lookup enabled");
                }
                builder.bind().await
            }
            DiscoveryMode::Disabled => builder.bind().await,
        };

        bind_result.map_err(|e| {
            ADAError::Network(format!(
                "iroh bind (discovery={}): {}",
                discovery_mode.label(),
                e
            ))
        })
    }

    async fn start_internal(
        secret_key: SecretKey,
        discovery_mode: DiscoveryMode,
    ) -> Result<(Self, mpsc::Receiver<IrohMessage>)> {
        let (tx, rx) = mpsc::channel::<IrohMessage>(256);

        let endpoint = Self::bind_endpoint(secret_key, discovery_mode).await?;

        // Accept loop — handles all inbound QUIC connections for the lifetime of
        // the endpoint.  Each accepted connection spawns its own task which loop
        // over incoming uni-directional streams from that peer.
        // blob_store must be created before the accept loop so the server half
        // can look up blobs from incoming bidi-stream requests.
        let blob_store: BlobStore = Arc::new(RwLock::new(HashMap::new()));
        let ep = endpoint.clone();
        let blob_store_srv = blob_store.clone();
        tokio::spawn(async move {
            while let Some(incoming) = ep.accept().await {
                let tx = tx.clone();
                let blob_store = blob_store_srv.clone();
                tokio::spawn(async move {
                    let conn = match incoming.await {
                        Ok(c) => c,
                        Err(e) => {
                            tracing::debug!("iroh incoming conn error: {}", e);
                            return;
                        }
                    };
                    let node_id_bytes = *conn.remote_id().as_bytes();
                    // Loop over uni- and bi-directional streams from this peer.
                    // Uni-streams carry DMs; bidi-streams carry blob fetch requests.
                    loop {
                        tokio::select! {
                            result = conn.accept_uni() => match result {
                                Ok(mut recv) => {
                                    let tx2 = tx.clone();
                                    // Per-stream task: one slow stream doesn't block others.
                                    tokio::spawn(async move {
                                        match tokio::time::timeout(
                                            READ_TIMEOUT,
                                            recv.read_to_end(MAX_MSG_BYTES),
                                        ).await {
                                            Ok(Ok(data)) if !data.is_empty() => {
                                                tracing::debug!(
                                                    "iroh recv {} B from {}",
                                                    data.len(),
                                                    hex::encode(node_id_bytes)
                                                );
                                                let _ = tx2.send(IrohMessage {
                                                    from: node_id_bytes,
                                                    data,
                                                }).await;
                                            }
                                            Ok(Ok(_)) => {}
                                            Ok(Err(e)) => {
                                                tracing::debug!("iroh stream read error: {}", e);
                                            }
                                            Err(_) => {
                                                tracing::debug!(
                                                    "iroh read_to_end timeout from {}",
                                                    hex::encode(node_id_bytes)
                                                );
                                            }
                                        }
                                    });
                                }
                                Err(_) => break, // connection closed or endpoint shutting down
                            },
                            result = conn.accept_bi() => match result {
                                Ok((mut send, mut recv)) => {
                                    let blob_store2 = blob_store.clone();
                                    tokio::spawn(async move {
                                        // Read 32-byte hash request from the fetching peer.
                                        let mut hash_buf = [0u8; 32];
                                        if tokio::time::timeout(
                                            Duration::from_secs(10),
                                            recv.read_exact(&mut hash_buf),
                                        ).await.is_err() {
                                            return;
                                        }
                                        match blob_store2.read().await.get(&hash_buf).cloned() {
                                            Some(BlobData::Memory(data)) => {
                                                let _ = send.write_all(&(data.len() as u64).to_le_bytes()).await;
                                                let _ = send.write_all(&data).await;
                                                let _ = send.finish();
                                                tracing::debug!(
                                                    "iroh blob served {} B hash={}",
                                                    data.len(), hex::encode(hash_buf)
                                                );
                                            }
                                            Some(BlobData::File(path)) => {
                                                if let Ok(mut file) = tokio::fs::File::open(&path).await {
                                                    if let Ok(metadata) = file.metadata().await {
                                                        let len = metadata.len();
                                                        let _ = send.write_all(&len.to_le_bytes()).await;
                                                        let _ = tokio::io::copy(&mut file, &mut send).await;
                                                        let _ = send.finish();
                                                        tracing::debug!(
                                                            "iroh blob served file {} B hash={}",
                                                            len, hex::encode(hash_buf)
                                                        );
                                                    }
                                                } else {
                                                    let _ = send.write_all(&0u64.to_le_bytes()).await;
                                                    let _ = send.finish();
                                                }
                                            }
                                            None => {
                                                // Blob not found: send length=0 so fetcher
                                                // gets "blob not found on remote peer" error.
                                                let _ = send.write_all(&0u64.to_le_bytes()).await;
                                                let _ = send.finish();
                                                tracing::debug!(
                                                    "iroh blob not found hash={}",
                                                    hex::encode(hash_buf)
                                                );
                                            }
                                        }
                                    });
                                }
                                Err(_) => break,
                            },
                        }
                    }
                });
            }
        });

        let node_id = *endpoint.id().as_bytes();
        tracing::info!(
            "iroh endpoint ready - NodeId {} discovery={}",
            hex::encode(node_id),
            discovery_mode.label()
        );

        Ok((
            IrohTransport {
                endpoint,
                conn_cache: RwLock::new(HashMap::new()),
                blob_store,
                peer_addrs: RwLock::new(HashMap::new()),
                peer_relays: RwLock::new(HashMap::new()),
            },
            rx,
        ))
    }

    /// Start the iroh endpoint and return the transport handle plus a channel
    /// that yields every incoming `IrohMessage`.
    ///
    /// - Publishes our `NodeId → RelayURL` to n0's pkarr/DNS so peers can
    ///   reach us by NodeId alone (`PkarrPublisher::n0_dns`).
    /// - Resolves peers' relay URLs from the same DNS (`DnsDiscovery::n0_dns`).
    /// - Spawns an accept loop that demultiplexes all inbound uni-streams.
    pub async fn start(
        secret_key: SecretKey,
        enable_mdns: bool,
    ) -> Result<(Self, mpsc::Receiver<IrohMessage>)> {
        match Self::start_internal(
            secret_key.clone(),
            DiscoveryMode::N0Dns { mdns: enable_mdns },
        )
        .await
        {
            Ok(started) => Ok(started),
            Err(discovery_error) => {
                tracing::warn!(
                    "iroh startup with pkarr/DNS discovery failed: {}. retrying with discovery disabled",
                    discovery_error
                );
                Self::start_internal(secret_key, DiscoveryMode::Disabled)
                    .await
                    .map_err(|fallback_error| {
                        ADAError::Network(format!(
                            "iroh startup failed with discovery ({}) and without discovery ({})",
                            discovery_error, fallback_error
                        ))
                    })
            }
        }
    }

    // -- Test helpers ---------------------------------------------------------

    /// Start an iroh endpoint **without** pkarr/DNS discovery.
    ///
    /// Use in unit/integration tests running without network access.  Peers
    /// exchange their direct socket addresses out-of-band and register them
    /// via [`IrohTransport::add_peer_addr`].
    #[cfg(any(test, feature = "test-helpers"))]
    pub async fn start_no_discovery(
        secret_key: SecretKey,
    ) -> crate::error::Result<(Self, tokio::sync::mpsc::Receiver<IrohMessage>)> {
        Self::start_internal(secret_key, DiscoveryMode::Disabled).await
    }

    /// Returns the 32-byte NodeId (= ADA PeerId bytes) for this endpoint.
    pub fn node_id_bytes(&self) -> [u8; 32] {
        *self.endpoint.id().as_bytes()
    }

    /// Returns the local UDP socket address bound by this endpoint.
    ///
    /// Useful in tests to obtain the direct address for [`add_peer_addr`].
    pub fn bound_socket(&self) -> std::net::SocketAddr {
        let bound = self.endpoint.bound_sockets();
        bound
            .iter()
            .copied()
            .find(|addr| addr.is_ipv4())
            .or_else(|| bound.first().copied())
            .expect("iroh endpoint should bind at least one socket")
    }

    /// Pre-register a peer's direct socket address so connections can be
    /// established without DNS/pkarr discovery.
    ///
    /// Call this with the address returned by the remote endpoint's
    /// [`bound_socket`] before calling [`send`].
    pub fn add_peer_addr(
        &self,
        peer_id: &[u8; 32],
        addr: std::net::SocketAddr,
    ) -> crate::error::Result<()> {
        EndpointId::from_bytes(peer_id)
            .map_err(|e| crate::error::ADAError::Network(format!("invalid EndpointId: {}", e)))?;
        // Store the address locally so get_or_connect() can pass it
        // explicitly to endpoint.connect(), bypassing address lookup.
        if let Ok(mut map) = self.peer_addrs.try_write() {
            map.insert(*peer_id, addr);
        }
        Ok(())
    }

    // -- Connectivity helpers --------------------------------------------------

    /// Returns `true` if a live QUIC connection to `peer_id` is cached.
    /// Cache hit > `send()` will be near-instant (no new DNS lookup/handshake).
    pub async fn is_connected(&self, peer_id: &[u8; 32]) -> bool {
        {
            let cache = self.conn_cache.read().await;
            if let Some(connection) = cache.get(peer_id) {
                if Self::connection_is_alive(connection) {
                    return true;
                }
            } else {
                return false;
            }
        }

        self.invalidate_connection(peer_id).await;
        false
    }

    /// Returns the set of peer IDs that currently have a cached QUIC connection.
    pub async fn connected_peer_ids(&self) -> std::collections::HashSet<[u8; 32]> {
        let stale_peer_ids = {
            let cache = self.conn_cache.read().await;
            cache
                .iter()
                .filter_map(|(peer_id, connection)| {
                    (!Self::connection_is_alive(connection)).then_some(*peer_id)
                })
                .collect::<Vec<_>>()
        };

        if !stale_peer_ids.is_empty() {
            let mut cache = self.conn_cache.write().await;
            for peer_id in &stale_peer_ids {
                cache.remove(peer_id);
            }
        }

        self.conn_cache.read().await.keys().copied().collect()
    }

    /// Returns the local relay URL assigned by the n0 relay infrastructure, if any.
    /// Call this to populate the `relay_hint` field in outgoing `BlobRef` messages
    /// so receivers can contact us directly without waiting for pkarr DNS propagation.
    pub fn home_relay_url(&self) -> Option<String> {
        let relay_url = self.endpoint.addr().relay_urls().next().cloned();
        if relay_url.is_none() {
            tracing::debug!("iroh: home relay URL not yet assigned");
        }
        relay_url.map(|url| url.to_string())
    }

    /// Pre-register a peer's iroh relay URL so `get_or_connect` can reach them
    /// without a pkarr DNS lookup.
    ///
    /// Call this when a `BlobRef` message carries a `relay_hint` from the sender.
    /// The endpoint will use this hint on the next `connect()` call, bypassing the
    /// 15�60 s pkarr DNS round-trip that would otherwise block the first connection.
    pub fn add_peer_relay(
        &self,
        peer_id: &[u8; 32],
        relay_url_str: &str,
    ) -> crate::error::Result<()> {
        EndpointId::from_bytes(peer_id)
            .map_err(|e| ADAError::Network(format!("invalid EndpointId: {}", e)))?;
        let relay_url: RelayUrl = relay_url_str.parse().map_err(|e| {
            ADAError::Network(format!("invalid relay URL '{}': {}", relay_url_str, e))
        })?;
        if let Ok(mut map) = self.peer_relays.try_write() {
            map.insert(*peer_id, relay_url);
        }
        Ok(())
    }

    /// Proactively open (and cache) a QUIC connection to `peer_id`.
    ///
    /// Should be called in a background task right after we receive the first DM
    /// from a new peer � so by the time a `BlobAvailable` event fires, the
    /// connection is already cached and `fetch_blob` can skip the DNS lookup.
    /// Errors are silently swallowed (DNS may not be ready yet; the caller retries later).
    pub async fn warmup_connection(&self, peer_id: &[u8; 32]) {
        if self.is_connected(peer_id).await {
            return; // already connected; no-op
        }
        match self.get_or_connect(peer_id).await {
            Ok(_) => tracing::debug!("iroh warmup: connection to {} cached", hex::encode(peer_id)),
            Err(e) => tracing::debug!(
                "iroh warmup: {} not yet reachable ({})",
                hex::encode(peer_id),
                e
            ),
        }
    }

    // -- Blob API --------------------------------------------------------------

    /// Store `data` in the local blob cache and return its blake3 hash.
    ///
    /// The hash is used as the blob store key AND as the integrity check on the
    /// receiver side.  The caller should send a `MessageKind::BlobRef { hash, .. }`
    /// DM so the receiver can pull the bytes via `fetch_blob()`.
    ///
    /// Blobs stay in the store until `evict_blob()` is called.  On mobile, the
    /// application should evict after receiving an acknowledgment from the peer.
    pub async fn store_blob(&self, data: Vec<u8>) -> [u8; 32] {
        let hash = *blake3::hash(&data).as_bytes();
        let len = data.len();
        self.blob_store
            .write()
            .await
            .insert(hash, BlobData::Memory(data));
        tracing::debug!("iroh blob stored hash={} bytes={}", hex::encode(hash), len);
        hash
    }

    pub async fn store_blob_from_path(
        &self,
        path: std::path::PathBuf,
    ) -> crate::error::Result<[u8; 32]> {
        let mut file = tokio::fs::File::open(&path)
            .await
            .map_err(|e| crate::error::ADAError::Network(format!("Failed to open file: {}", e)))?;
        let mut hasher = blake3::Hasher::new();
        let mut buffer = [0u8; 65536];
        use tokio::io::AsyncReadExt;
        loop {
            let n = file.read(&mut buffer).await.map_err(|e| {
                crate::error::ADAError::Network(format!("Failed to read file: {}", e))
            })?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }
        let hash = *hasher.finalize().as_bytes();
        let len = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        self.blob_store
            .write()
            .await
            .insert(hash, BlobData::File(path));
        tracing::debug!(
            "iroh file blob stored hash={} bytes={}",
            hex::encode(hash),
            len
        );
        Ok(hash)
    }

    /// Remove a blob from the local store once the receiver confirms receipt.
    pub async fn evict_blob(&self, hash: &[u8; 32]) {
        self.blob_store.write().await.remove(hash);
        tracing::debug!("iroh blob evicted hash={}", hex::encode(hash));
    }

    /// Fetch a blob from a remote peer by its blake3 hash.
    ///
    /// Opens a bidi-stream on the existing (or new) QUIC connection, sends the
    /// 32-byte hash as a request, and reads back `[8-byte u64 LE size][data]`.
    /// Returns `Err` if the peer doesn't have the hash or the transfer fails.
    ///
    /// The caller should verify `blake3::hash(&result) == hash` for integrity.
    pub async fn fetch_blob(
        &self,
        peer_id: &[u8; 32],
        hash: &[u8; 32],
    ) -> crate::error::Result<Vec<u8>> {
        let mut last_err: Option<ADAError> = None;

        for attempt in 0..BLOB_FETCH_ATTEMPTS {
            let outcome: crate::error::Result<Vec<u8>> = async {
                let conn = self.get_or_connect(peer_id).await?;

                let (mut send, mut recv) = tokio::time::timeout(CONNECT_TIMEOUT, conn.open_bi())
                    .await
                    .map_err(|_| ADAError::Network("iroh blob open_bi timeout".into()))?
                    .map_err(|e| ADAError::Network(format!("iroh blob open_bi: {}", e)))?;

                // Send 32-byte hash request, then half-close the send side
                tokio::time::timeout(WRITE_TIMEOUT, async {
                    send.write_all(hash)
                        .await
                        .map_err(|e| ADAError::Network(format!("iroh blob req write: {}", e)))?;
                    send.finish()
                        .map_err(|e| ADAError::Network(format!("iroh blob req finish: {}", e)))?;
                    Ok::<(), ADAError>(())
                })
                .await
                .map_err(|_| ADAError::Network("iroh blob req timeout".into()))??;

                // Read length prefix first (short timeout), then apply an adaptive data
                // timeout based on announced payload size.
                let mut len_buf = [0u8; 8];
                tokio::time::timeout(BLOB_FETCH_HEADER_TIMEOUT, recv.read_exact(&mut len_buf))
                    .await
                    .map_err(|_| ADAError::Network("iroh blob len timeout".into()))?
                    .map_err(|e| ADAError::Network(format!("iroh blob len read: {}", e)))?;

                let size = u64::from_le_bytes(len_buf) as usize;
                if size == 0 {
                    return Err(ADAError::Network("blob not found on remote peer".into()));
                }
                if size > MAX_BLOB_BYTES {
                    return Err(ADAError::Network(format!(
                        "blob too large: {} bytes (max {})",
                        size, MAX_BLOB_BYTES
                    )));
                }

                // Budget ~2s per MiB + base, clamped to [60s, 15min].
                let size_mib = ((size as u64) + (1024 * 1024 - 1)) / (1024 * 1024);
                let adaptive = Duration::from_secs(30 + size_mib.saturating_mul(2));
                let payload_timeout =
                    adaptive.clamp(BLOB_FETCH_MIN_TIMEOUT, BLOB_FETCH_MAX_TIMEOUT);

                let data = tokio::time::timeout(payload_timeout, recv.read_to_end(size))
                    .await
                    .map_err(|_| {
                        ADAError::Network(format!(
                            "iroh blob data timeout after {:?}",
                            payload_timeout
                        ))
                    })?
                    .map_err(|e| ADAError::Network(format!("iroh blob data read: {}", e)))?;

                // Integrity check: verify blake3 hash matches what the sender promised
                let actual_hash = *blake3::hash(&data).as_bytes();
                if actual_hash != *hash {
                    return Err(ADAError::Network(format!(
                        "blob integrity failure: expected {} got {}",
                        hex::encode(hash),
                        hex::encode(actual_hash)
                    )));
                }

                Ok(data)
            }
            .await;

            match outcome {
                Ok(data) => {
                    tracing::debug!(
                        "iroh blob fetched {} B hash={}",
                        data.len(),
                        hex::encode(hash)
                    );
                    return Ok(data);
                }
                Err(e) => {
                    self.invalidate_connection(peer_id).await;
                    tracing::warn!(
                        "iroh fetch_blob attempt {}/{} failed hash={}: {}",
                        attempt + 1,
                        BLOB_FETCH_ATTEMPTS,
                        hex::encode(hash),
                        e
                    );
                    last_err = Some(e);
                    if attempt + 1 < BLOB_FETCH_ATTEMPTS {
                        let backoff = Duration::from_millis(800 * (attempt as u64 + 1));
                        tokio::time::sleep(backoff).await;
                    }
                }
            }
        }

        Err(last_err.unwrap_or_else(|| ADAError::Network("blob fetch failed".into())))
    }

    pub async fn fetch_blob_to_file(
        &self,
        peer_id: &[u8; 32],
        hash: &[u8; 32],
        dest_path: &std::path::Path,
    ) -> crate::error::Result<()> {
        let mut last_err: Option<ADAError> = None;
        use tokio::io::AsyncWriteExt;

        for attempt in 0..BLOB_FETCH_ATTEMPTS {
            let outcome: crate::error::Result<()> = async {
                let conn = self.get_or_connect(peer_id).await?;

                let (mut send, mut recv) = tokio::time::timeout(CONNECT_TIMEOUT, conn.open_bi())
                    .await
                    .map_err(|_| ADAError::Network("iroh blob open_bi timeout".into()))?
                    .map_err(|e| ADAError::Network(format!("iroh blob open_bi: {}", e)))?;

                tokio::time::timeout(WRITE_TIMEOUT, async {
                    send.write_all(hash)
                        .await
                        .map_err(|e| ADAError::Network(format!("iroh blob req write: {}", e)))?;
                    send.finish()
                        .map_err(|e| ADAError::Network(format!("iroh blob req finish: {}", e)))?;
                    Ok::<(), ADAError>(())
                })
                .await
                .map_err(|_| ADAError::Network("iroh blob req timeout".into()))??;

                let mut len_buf = [0u8; 8];
                tokio::time::timeout(BLOB_FETCH_HEADER_TIMEOUT, recv.read_exact(&mut len_buf))
                    .await
                    .map_err(|_| ADAError::Network("iroh blob len timeout".into()))?
                    .map_err(|e| ADAError::Network(format!("iroh blob len read: {}", e)))?;

                let size = u64::from_le_bytes(len_buf) as usize;
                if size == 0 {
                    return Err(ADAError::Network("blob not found on remote peer".into()));
                }
                if size > MAX_BLOB_BYTES {
                    return Err(ADAError::Network(format!(
                        "blob too large: {} bytes (max {})",
                        size, MAX_BLOB_BYTES
                    )));
                }

                let mut dest_file = tokio::fs::File::create(dest_path)
                    .await
                    .map_err(|e| ADAError::Network(format!("failed to create dest file: {}", e)))?;

                let mut hasher = blake3::Hasher::new();
                let mut buffer = [0u8; 65536];
                let mut remaining = size;

                // adaptive chunk timeout based on chunk size and base
                while remaining > 0 {
                    let to_read = std::cmp::min(remaining, buffer.len());
                    let chunk_res = tokio::time::timeout(
                        Duration::from_secs(15),
                        recv.read(&mut buffer[..to_read]),
                    )
                    .await
                    .map_err(|_| ADAError::Network("blob chunk read timeout".into()))?
                    .map_err(|e| ADAError::Network(format!("blob chunk read err: {}", e)))?;

                    let n = match chunk_res {
                        Some(bytes_read) => bytes_read,
                        None => 0,
                    };

                    if n == 0 {
                        return Err(ADAError::Network("early EOF".into()));
                    }

                    hasher.update(&buffer[..n]);
                    dest_file
                        .write_all(&buffer[..n])
                        .await
                        .map_err(|e| ADAError::Network(format!("dest file write err: {}", e)))?;
                    remaining -= n;
                }

                let actual_hash = *hasher.finalize().as_bytes();
                if actual_hash != *hash {
                    return Err(ADAError::Network(format!(
                        "blob integrity failure: expected {} got {}",
                        hex::encode(hash),
                        hex::encode(actual_hash)
                    )));
                }

                dest_file
                    .flush()
                    .await
                    .map_err(|e| ADAError::Network(format!("flush: {}", e)))?;
                Ok(())
            }
            .await;

            match outcome {
                Ok(()) => {
                    tracing::debug!("iroh blob fetched to file hash={}", hex::encode(hash));
                    return Ok(());
                }
                Err(e) => {
                    self.invalidate_connection(peer_id).await;
                    tracing::warn!(
                        "iroh fetch_blob_to_file attempt {}/{} failed hash={}: {}",
                        attempt + 1,
                        BLOB_FETCH_ATTEMPTS,
                        hex::encode(hash),
                        e
                    );
                    last_err = Some(e);
                    if attempt + 1 < BLOB_FETCH_ATTEMPTS {
                        let backoff = Duration::from_millis(800 * (attempt as u64 + 1));
                        tokio::time::sleep(backoff).await;
                    }
                }
            }
        }

        Err(last_err.unwrap_or_else(|| ADAError::Network("blob fetch_to_file failed".into())))
    }

    /// Get a live connection to `peer_id`, reusing a cached one when available.
    ///
    /// Fast path (cache hit): returns immediately with a cloned `Connection` handle.
    /// Slow path (cache miss): performs DNS lookup + QUIC connect under `CONNECT_TIMEOUT`,
    /// then stores the connection for future messages.
    async fn get_or_connect(&self, peer_id: &[u8; 32]) -> Result<Connection> {
        // Fast path — read lock only.
        {
            let cache = self.conn_cache.read().await;
            if let Some(conn) = cache.get(peer_id) {
                return Ok(conn.clone());
            }
        }

        // Slow path � full connect.  iroh resolves relay URL via address lookup,
        // then establishes a QUIC connection (direct or relay-assisted).
        //
        // If the caller previously registered a direct address for this peer via
        // `add_peer_addr()` or `add_peer_relay()`, we embed that hint in the
        // `EndpointAddr` passed to `connect()` to bypass slow lookups when possible.
        let node_id = EndpointId::from_bytes(peer_id)
            .map_err(|e| ADAError::Network(format!("iroh bad EndpointId: {}", e)))?;

        let mut transports = Vec::new();
        if let Some(relay_url) = self.peer_relays.read().await.get(peer_id).cloned() {
            transports.push(TransportAddr::Relay(relay_url));
        }
        if let Some(addr) = self.peer_addrs.read().await.get(peer_id).copied() {
            transports.push(TransportAddr::Ip(addr));
        }
        let node_addr = EndpointAddr::from_parts(node_id, transports);

        let conn = tokio::time::timeout(
            CONNECT_TIMEOUT,
            self.endpoint.connect(node_addr, ADA_DM_ALPN),
        )
        .await
        .map_err(|_| ADAError::Network("iroh connect timeout".into()))?
        .map_err(|e| ADAError::Network(format!("iroh connect: {}", e)))?;

        self.conn_cache.write().await.insert(*peer_id, conn.clone());
        Ok(conn)
    }

    /// Write `data` over a new uni-directional stream on `conn`.
    async fn send_on_conn(conn: &Connection, data: &[u8]) -> Result<()> {
        let mut send = conn
            .open_uni()
            .await
            .map_err(|e| ADAError::Network(format!("iroh open_uni: {}", e)))?;

        tokio::time::timeout(WRITE_TIMEOUT, async {
            send.write_all(data)
                .await
                .map_err(|e| ADAError::Network(format!("iroh write: {}", e)))?;
            send.finish()
                .map_err(|e| ADAError::Network(format!("iroh finish: {}", e)))?;
            Ok::<(), ADAError>(())
        })
        .await
        .map_err(|_| ADAError::Network("iroh write timeout".into()))??;

        Ok(())
    }

    /// Send `data` to the peer identified by their 32-byte Ed25519 public key.
    ///
    /// On the first message to a peer, iroh resolves their relay URL via pkarr DNS
    /// and establishes a QUIC connection (direct or relay-assisted) — this takes
    /// up to `CONNECT_TIMEOUT`.  All subsequent messages reuse the cached connection
    /// and open a new uni-directional QUIC stream on it in ~1 ms.
    ///
    /// If the cached connection turns out to be stale (peer reconnected), it is
    /// evicted and exactly one reconnect is attempted before returning an error.
    pub async fn send(&self, peer_id: &[u8; 32], data: Vec<u8>) -> Result<()> {
        let conn = self.get_or_connect(peer_id).await?;

        match Self::send_on_conn(&conn, &data).await {
            Ok(()) => Ok(()),
            Err(e) => {
                // Stale connection (peer went offline / IP changed).
                // Evict from cache and try once with a fresh connection.
                tracing::debug!("iroh: cached conn failed ({}); reconnecting", e);
                self.conn_cache.write().await.remove(peer_id);
                let conn = self.get_or_connect(peer_id).await?;
                Self::send_on_conn(&conn, &data).await
            }
        }
    }

    /// Notify iroh that the underlying network interface has been restored
    /// (e.g. WiFi reconnected after Doze or airplane mode).
    ///
    /// This does two things:
    /// 1. Clears the QUIC connection cache — all cached connections are stale
    ///    after a network change, so the next send will open a fresh one.
    /// 2. Calls `endpoint.force_network_change(true)` which forces iroh's
    ///    magicsock to re-probe all local interfaces and triggers an immediate
    ///    fresh pkarr publish, bypassing whatever backoff was accumulating.
    ///
    /// Without this, pkarr may hold a backoff of 27+ seconds after network
    /// restore, delaying peer discovery by the full backoff window.
    pub async fn notify_network_available(&self) {
        self.conn_cache.write().await.clear();
        self.endpoint.network_change().await;
        tracing::info!(
            "iroh: network restored — connection cache cleared and pkarr republish triggered"
        );
    }

    /// Notify iroh that the current network interface has been lost
    /// (e.g. WiFi disconnected before LTE takes over).
    ///
    /// Clears the QUIC connection cache so stale connections are not reused
    /// on the next send attempt.  Does NOT call `force_network_change` because
    /// there is no usable interface yet — that is done in `notify_network_available`.
    pub async fn notify_network_lost(&self) {
        self.conn_cache.write().await.clear();
        tracing::debug!("iroh: network lost — connection cache cleared");
    }

    /// Close the iroh endpoint gracefully, draining in-flight messages.
    pub async fn close(&self) {
        // Drop all cached connections before closing the endpoint so QUIC CONNECTION_CLOSE
        // frames are sent cleanly to every peer.
        self.conn_cache.write().await.clear();
        self.endpoint.close().await;
    }
}

// -- Tests ---------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use iroh::SecretKey;

    fn random_secret_key() -> SecretKey {
        let mut bytes = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut bytes);
        SecretKey::from_bytes(&bytes)
    }

    /// 9.18 basic � Verifies that IrohTransport starts, binds a UDP socket,
    /// and returns a valid NodeId.  Does NOT attempt any QUIC connection.
    #[tokio::test(flavor = "multi_thread")]
    async fn iroh_endpoint_starts() {
        let (transport, _rx) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("start_no_discovery failed");

        let node_id = transport.node_id_bytes();
        assert_ne!(node_id, [0u8; 32], "NodeId should not be all-zeros");

        let addr = transport.bound_socket();
        assert_ne!(addr.port(), 0, "bound_socket should have a non-zero port");

        transport.close().await;
    }

    /// 9.18 � IrohTransport loopback: two endpoints on localhost, one sends to the other.
    ///
    /// NOTE: iroh 0.31 QUIC connections between two endpoints on the same host
    /// require a relay server for UDP hole-punching even on localhost.  Without
    /// a relay, `endpoint.connect()` times out.  This test is marked `#[ignore]`
    /// and must be run in an environment that has a relay server reachable (or
    /// a local iroh relay started via `iroh::test_utils::run_relay_server()`).
    /// Run with: cargo test iroh_loopback -- --ignored
    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "requires iroh relay server (QUIC hole-punch not available on localhost without relay)"]
    async fn iroh_loopback_send_receive() {
        let (sender, _rx_sender) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("sender start failed");
        let (receiver, mut rx_receiver) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("receiver start failed");

        let sender_id = sender.node_id_bytes();
        let receiver_id = receiver.node_id_bytes();
        let sender_addr = sender.bound_socket();
        let receiver_addr = receiver.bound_socket();

        // Cross-register addresses.
        sender
            .add_peer_addr(&receiver_id, receiver_addr)
            .expect("add receiver addr");
        receiver
            .add_peer_addr(&sender_id, sender_addr)
            .expect("add sender addr");

        let payload = b"hello loopback".to_vec();
        sender
            .send(&receiver_id, payload.clone())
            .await
            .expect("send failed");

        let msg = tokio::time::timeout(Duration::from_secs(5), rx_receiver.recv())
            .await
            .expect("receive timed out")
            .expect("channel closed");

        assert_eq!(msg.data, payload, "payload mismatch");
        assert_eq!(msg.from, sender_id, "sender node_id mismatch");

        sender.close().await;
        receiver.close().await;
    }

    /// 2b.9 � Two distinct iroh endpoints exchange a message on localhost.
    ///
    /// NOTE: Same relay requirement as `iroh_loopback_send_receive`.
    /// Run with: cargo test iroh_two_endpoints -- --ignored
    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "requires iroh relay server (QUIC hole-punch not available on localhost without relay)"]
    async fn iroh_two_endpoints_direct() {
        let (transport_a, mut rx_a) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("start A failed");
        let (transport_b, mut rx_b) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("start B failed");

        let id_a = transport_a.node_id_bytes();
        let id_b = transport_b.node_id_bytes();
        let addr_a = transport_a.bound_socket();
        let addr_b = transport_b.bound_socket();

        // Exchange direct addresses (simulates out-of-band contact exchange)
        transport_a
            .add_peer_addr(&id_b, addr_b)
            .expect("A add B addr");
        transport_b
            .add_peer_addr(&id_a, addr_a)
            .expect("B add A addr");

        // A > B
        let msg_ab = b"from_a_to_b".to_vec();
        transport_a
            .send(&id_b, msg_ab.clone())
            .await
            .expect("A>B send failed");

        let received_b = tokio::time::timeout(Duration::from_secs(5), rx_b.recv())
            .await
            .expect("B receive timed out")
            .expect("channel closed");
        assert_eq!(received_b.data, msg_ab);
        assert_eq!(received_b.from, id_a);

        // B > A
        let msg_ba = b"from_b_to_a".to_vec();
        transport_b
            .send(&id_a, msg_ba.clone())
            .await
            .expect("B>A send failed");

        let received_a = tokio::time::timeout(Duration::from_secs(5), rx_a.recv())
            .await
            .expect("A receive timed out")
            .expect("channel closed");
        assert_eq!(received_a.data, msg_ba);
        assert_eq!(received_a.from, id_b);

        transport_a.close().await;
        transport_b.close().await;
    }

    /// 9.18 variant � Blob store: store and evict a blob.
    #[tokio::test]
    async fn blob_store_insert_evict() {
        let (transport, _rx) = IrohTransport::start_no_discovery(random_secret_key())
            .await
            .expect("start failed");

        let data = b"test blob data".to_vec();
        let hash = transport.store_blob(data.clone()).await;

        // Hash must equal blake3 of the data
        let expected = *blake3::hash(&data).as_bytes();
        assert_eq!(hash, expected);

        // Data must be retrievable
        assert!(transport.blob_store.read().await.contains_key(&hash));

        transport.evict_blob(&hash).await;
        assert!(!transport.blob_store.read().await.contains_key(&hash));

        transport.close().await;
    }
}
