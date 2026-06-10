// Integration tests for ADA Core — run as a separate Rust integration-test binary.
//
// These tests compile ada-core as a dependency (black-box) and exercise public
// APIs end-to-end.  No mocking; real crypto, real storage (tempdir), real
// tokio runtime.  No external network required: network ports are set to 0
// (OS-assigned) and network discovery is isolated.

use ada_core::{
    api::ADAEvent,
    bridge::{
        mailbox::{
            fresh_bridge_auth, recv_frame, register_challenge, send_frame,
            verify_bridge_fingerprint, HttpAckRequest, HttpAckResponse, HttpPullRequest,
            HttpPullResponse, HttpPushRequest, HttpPushResponse,
        },
        manifest::{BridgeManifestPayload, ManifestBridgeEntry, SignedBridgeManifest},
        server::BridgeServerState,
        BridgeFrame, BridgeManager, ConnectedBridge,
    },
    config::ConnectionProfile,
    crypto::x3dh::PreKeyBundle,
    identity::{Identity, PublicBundle},
    messaging::session::WireEnvelope,
    ADAConfig, ADACore,
};
use ed25519_dalek::{Signer, SigningKey};
use serde_json::Value;
use std::{
    net::{SocketAddr, TcpListener as StdTcpListener},
    sync::Arc,
    time::Duration,
};

fn decode_dm_wire(bytes: &[u8]) -> ada_core::messaging::session::EncryptedWire {
    match bincode::deserialize(bytes).expect("wire deserialize") {
        WireEnvelope::Dm(wire) => wire,
        WireEnvelope::Group(_) => panic!("expected direct-message wire envelope"),
        WireEnvelope::WebRtcProxy(_) => {
            panic!("expected direct-message wire envelope, got WebRtcProxy")
        }
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

fn test_config(suffix: &str) -> (ADAConfig, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let mut cfg = ADAConfig::default();
    cfg.storage.data_dir = dir.path().join(suffix).to_str().unwrap().to_string();
    // Use OS-assigned ports so parallel test runs never collide.
    // Keep discovery isolated from the public network.
    cfg.network.stun_servers = vec![];
    (cfg, dir)
}

fn contact_card_json_from_bundle(bundle: &PublicBundle) -> String {
    use base64::Engine;

    let b64 = base64::engine::general_purpose::STANDARD;
    serde_json::json!({
        "v": 2,
        "id": b64.encode(bundle.peer_id.0),
        "spk": b64.encode(bundle.spk_public),
        "ik": b64.encode(bundle.dh_public),
        "spk_sig": b64.encode(&bundle.spk_signature),
        "name": bundle.display_name.clone(),
    })
    .to_string()
}

fn import_contact_from_card(core: &Arc<ADACore>, contact_card_json: &str) {
    use base64::Engine;

    let b64 = base64::engine::general_purpose::STANDARD;
    let card = ada_core::pattern_auth::parse_contact_card(contact_card_json)
        .expect("contact card JSON should parse");

    let peer_id_bytes: [u8; 32] = b64
        .decode(&card.id)
        .expect("peer id base64")
        .try_into()
        .expect("peer id length");
    let spk_public: [u8; 32] = b64
        .decode(&card.spk)
        .expect("spk base64")
        .try_into()
        .expect("spk length");
    let dh_public: [u8; 32] = if card.v >= 3 && !card.ephemeral_ik.is_empty() {
        b64.decode(&card.ephemeral_ik)
            .expect("ephemeral ik base64")
            .try_into()
            .expect("ephemeral ik length")
    } else if card.ik.is_empty() {
        peer_id_bytes
    } else {
        b64.decode(&card.ik)
            .expect("ik base64")
            .try_into()
            .expect("ik length")
    };
    let spk_signature = if card.spk_sig.is_empty() {
        Vec::new()
    } else {
        b64.decode(&card.spk_sig).expect("spk_sig base64")
    };

    if card.v >= 2 {
        assert_eq!(
            spk_signature.len(),
            64,
            "v2+ contact card must carry a 64-byte SPK signature"
        );
        let bundle_for_verify = PreKeyBundle {
            ik_public: dh_public,
            spk_public,
            spk_signature: spk_signature.clone(),
            opk_public: None,
            opk_id: None,
        };
        bundle_for_verify
            .verify_spk_signature(&peer_id_bytes)
            .expect("contact card SPK signature should verify");
    }

    core.add_contact(PublicBundle {
        peer_id: ada_core::identity::PeerId(peer_id_bytes),
        dh_public,
        display_name: card.name,
        spk_public,
        spk_signature,
        opk_public: None,
        opk_id: None,
        relay_url: None,
    })
    .expect("contact import should persist peer bundle");
}

#[test]
fn add_contact_without_current_runtime_does_not_panic() {
    let (cfg, _dir) = test_config("contact_no_runtime");
    let runtime = tokio::runtime::Runtime::new().expect("tokio runtime should build");
    let core = runtime
        .block_on(ADACore::new(cfg, "Alice"))
        .expect("core should build");
    let peer = Identity::generate("Bob");
    let bundle = peer.public_bundle();
    let peer_b64 = bundle.peer_id.to_base64();
    drop(runtime);

    core.add_contact(bundle)
        .expect("contact import outside runtime should succeed");

    let saved = core
        .get_contact(&peer_b64)
        .expect("contact lookup should succeed");
    assert!(
        saved.is_some(),
        "contact should persist even without a current runtime"
    );
}

fn relay_only_config(suffix: &str) -> (ADAConfig, tempfile::TempDir) {
    let (mut cfg, dir) = test_config(suffix);
    cfg.network.relay_only = true;
    cfg.bridge.reconnect_secs = 1;
    (cfg, dir)
}

fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

struct TestBridgeServer {
    bind: SocketAddr,
    fingerprint: [u8; 32],
    handle: tokio::task::JoinHandle<()>,
}

impl Drop for TestBridgeServer {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

struct HttpOnlyMailboxBridge {
    bind: SocketAddr,
    fingerprint: [u8; 32],
    deliveries: tokio::sync::mpsc::UnboundedReceiver<ada_core::bridge::BridgeEnvelope>,
    handle: tokio::task::JoinHandle<()>,
}

impl Drop for HttpOnlyMailboxBridge {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

impl HttpOnlyMailboxBridge {
    fn manifest_entry(&self, id: &str, priority: u8) -> ManifestBridgeEntry {
        ManifestBridgeEntry {
            id: id.to_string(),
            address: self.bind.ip().to_string(),
            port: self.bind.port(),
            protocol: "websocket".to_string(),
            hostname: Some(self.bind.ip().to_string()),
            insecure: true,
            fingerprint_hex: hex::encode(self.fingerprint),
            shared_secret_hex: None,
            priority,
            is_active: true,
            front_domain: None,
            front_url: None,
            wire_format: None,
        }
    }
}

async fn start_http_only_mailbox_bridge(signing_seed: [u8; 32]) -> HttpOnlyMailboxBridge {
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("http-only bridge listener should bind");
    let bind = listener
        .local_addr()
        .expect("http-only bridge listener should have a local address");
    let signing_key = SigningKey::from_bytes(&signing_seed);
    let fingerprint = signing_key.verifying_key().to_bytes();
    let (tx, deliveries) = tokio::sync::mpsc::unbounded_channel();
    let fingerprint_for_handler = fingerprint;
    let queue = Arc::new(tokio::sync::Mutex::new(Vec::<
        ada_core::bridge::BridgeEnvelope,
    >::new()));
    let push_queue = Arc::clone(&queue);
    let pull_queue = Arc::clone(&queue);
    let ack_queue = Arc::clone(&queue);

    let app = axum::Router::new()
        .route("/healthz", axum::routing::get(|| async { "ok" }))
        .route(
            "/mailbox/push",
            axum::routing::post(move |axum::Json(request): axum::Json<HttpPushRequest>| {
                let tx = tx.clone();
                let queue = Arc::clone(&push_queue);
                async move {
                    let mut guard = queue.lock().await;
                    if !guard
                        .iter()
                        .any(|item| item.message_id == request.envelope.message_id)
                    {
                        guard.push(request.envelope.clone());
                    }
                    let queue_depth = guard.len() as u32;
                    drop(guard);
                    tx.send(request.envelope)
                        .expect("test mailbox receiver should stay open");
                    axum::Json(HttpPushResponse {
                        disposition: ada_core::bridge::BridgePushDisposition::MailboxQueued,
                        queue_depth,
                        bridge_fingerprint: Some(fingerprint_for_handler),
                    })
                }
            }),
        )
        .route(
            "/mailbox/pull",
            axum::routing::post({
                move |axum::Json(_request): axum::Json<HttpPullRequest>| {
                    let queue = Arc::clone(&pull_queue);
                    async move {
                        axum::Json(HttpPullResponse {
                            envelopes: queue.lock().await.clone(),
                            bridge_fingerprint: Some(fingerprint_for_handler),
                        })
                    }
                }
            }),
        )
        .route(
            "/mailbox/ack",
            axum::routing::post({
                move |axum::Json(request): axum::Json<HttpAckRequest>| {
                    let queue = Arc::clone(&ack_queue);
                    async move {
                        let mut guard = queue.lock().await;
                        guard.retain(|item| !request.message_ids.contains(&item.message_id));
                        axum::Json(HttpAckResponse {
                            remaining: guard.len() as u32,
                            bridge_fingerprint: Some(fingerprint_for_handler),
                        })
                    }
                }
            }),
        );
    let handle = tokio::spawn(async move {
        axum::serve(listener, app.into_make_service())
            .await
            .expect("http-only bridge server should keep serving");
    });

    HttpOnlyMailboxBridge {
        bind,
        fingerprint,
        deliveries,
        handle,
    }
}

impl TestBridgeServer {
    fn manifest_entry(&self, id: &str, priority: u8) -> ManifestBridgeEntry {
        ManifestBridgeEntry {
            id: id.to_string(),
            address: self.bind.ip().to_string(),
            port: self.bind.port(),
            protocol: "websocket".to_string(),
            hostname: Some(self.bind.ip().to_string()),
            insecure: true,
            fingerprint_hex: hex::encode(self.fingerprint),
            shared_secret_hex: None,
            priority,
            is_active: true,
            front_domain: None,
            front_url: None,
            wire_format: None,
        }
    }
}

async fn start_test_bridge(signing_seed: [u8; 32]) -> TestBridgeServer {
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("test bridge listener should bind");
    let bind = listener
        .local_addr()
        .expect("test bridge listener should have a local address");
    let signing_key = SigningKey::from_bytes(&signing_seed);
    let fingerprint = signing_key.verifying_key().to_bytes();

    let manifest = BridgeManifestPayload {
        version: 1,
        issued_at_ms: unix_now_ms(),
        ttl_secs: 600,
        max_attachment_bytes: Some(256 * 1024),
        supports_realtime_calls: false,
        bridges: vec![ManifestBridgeEntry {
            id: "bridge-test-server".to_string(),
            address: bind.ip().to_string(),
            port: bind.port(),
            protocol: "websocket".to_string(),
            hostname: Some(bind.ip().to_string()),
            insecure: true,
            fingerprint_hex: hex::encode(fingerprint),
            shared_secret_hex: None,
            priority: 200,
            is_active: true,
            front_domain: None,
            front_url: None,
            wire_format: None,
        }],
    }
    .to_signed(&signing_key)
    .expect("test bridge manifest should sign");

    let state = BridgeServerState::new(manifest, fingerprint, 128);
    let handle = tokio::spawn(async move {
        state
            .serve(listener)
            .await
            .expect("test bridge server should keep serving");
    });

    TestBridgeServer {
        bind,
        fingerprint,
        handle,
    }
}

fn signed_manifest_json(
    entries: Vec<ManifestBridgeEntry>,
    signing_seed: [u8; 32],
) -> (String, String) {
    let signing_key = SigningKey::from_bytes(&signing_seed);
    let manifest = BridgeManifestPayload {
        version: 1,
        issued_at_ms: unix_now_ms(),
        ttl_secs: 600,
        max_attachment_bytes: Some(256 * 1024),
        supports_realtime_calls: false,
        bridges: entries,
    }
    .to_signed(&signing_key)
    .expect("signed bridge manifest should build");

    (
        serde_json::to_string(&manifest).expect("signed bridge manifest JSON should serialize"),
        hex::encode(signing_key.verifying_key().to_bytes()),
    )
}

fn signed_manifest_json_unchecked(
    payload: &BridgeManifestPayload,
    signing_seed: [u8; 32],
) -> (String, String) {
    let signing_key = SigningKey::from_bytes(&signing_seed);
    let payload_json =
        serde_json::to_string(payload).expect("bridge manifest payload should serialize");
    let signature = signing_key.sign(payload_json.as_bytes());
    let manifest = SignedBridgeManifest {
        payload_json,
        signature_hex: hex::encode(signature.to_bytes()),
    };

    (
        serde_json::to_string(&manifest).expect("signed bridge manifest JSON should serialize"),
        hex::encode(signing_key.verifying_key().to_bytes()),
    )
}

async fn import_signed_manifest(
    core: &ADACore,
    entries: Vec<ManifestBridgeEntry>,
    signing_seed: [u8; 32],
    source: &str,
) {
    let (manifest_json, trusted_key_hex) = signed_manifest_json(entries, signing_seed);
    core.import_bridge_manifest_json(&manifest_json, source, Some(&trusted_key_hex))
        .await
        .expect("signed bridge manifest should import");
}

async fn bridge_status(core: &ADACore) -> Value {
    serde_json::from_str(&core.get_bridge_status_json().await)
        .expect("bridge status JSON should parse")
}

#[tokio::test(flavor = "multi_thread")]
async fn bridge_manifest_import_rejects_invalid_fields_without_replacing_existing_config() {
    let signing_seed = [0x44u8; 32];
    let (cfg, _dir) = relay_only_config("manifest_validation");
    let core = ADACore::new(cfg, "Manifest Validator").await.unwrap();

    let valid_entry = ManifestBridgeEntry {
        id: "valid-bridge".to_string(),
        address: "127.0.0.1".to_string(),
        port: 443,
        protocol: "websocket".to_string(),
        hostname: Some("edge.example".to_string()),
        insecure: true,
        fingerprint_hex: hex::encode([0x22u8; 32]),
        shared_secret_hex: None,
        priority: 200,
        is_active: true,
        front_domain: None,
        front_url: None,
        wire_format: Some("json".to_string()),
    };
    import_signed_manifest(&core, vec![valid_entry], signing_seed, "test/valid").await;
    let valid_status = bridge_status(&core).await;
    assert_eq!(valid_status["manifest"]["bridge_count"].as_u64(), Some(1));
    assert_eq!(
        valid_status["bridges"][0]["id"].as_str(),
        Some("valid-bridge")
    );

    let invalid_payload = BridgeManifestPayload {
        version: 2,
        issued_at_ms: unix_now_ms(),
        ttl_secs: 600,
        max_attachment_bytes: Some(256 * 1024),
        supports_realtime_calls: false,
        bridges: vec![ManifestBridgeEntry {
            id: "invalid-bridge".to_string(),
            address: "127.0.0.1".to_string(),
            port: 0,
            protocol: "websocket".to_string(),
            hostname: Some("edge.example".to_string()),
            insecure: true,
            fingerprint_hex: hex::encode([0x33u8; 32]),
            shared_secret_hex: None,
            priority: 200,
            is_active: true,
            front_domain: None,
            front_url: None,
            wire_format: Some("json".to_string()),
        }],
    };
    let (invalid_manifest_json, trusted_key_hex) =
        signed_manifest_json_unchecked(&invalid_payload, signing_seed);

    let err = core
        .import_bridge_manifest_json(
            &invalid_manifest_json,
            "test/invalid",
            Some(&trusted_key_hex),
        )
        .await
        .expect_err("signed manifest with invalid bridge fields should fail");
    assert!(format!("{}", err).contains("port"));

    let status_after_invalid = bridge_status(&core).await;
    assert_eq!(
        status_after_invalid["manifest"]["bridge_count"].as_u64(),
        Some(1)
    );
    assert_eq!(
        status_after_invalid["bridges"][0]["id"].as_str(),
        Some("valid-bridge")
    );

    core.stop().await;
}

async fn bridge_ops_status(server: &TestBridgeServer) -> Value {
    reqwest::get(format!("http://{}/ops/status", server.bind))
        .await
        .expect("bridge ops request should succeed")
        .json::<Value>()
        .await
        .expect("bridge ops JSON should parse")
}

async fn inbound_transfer_progress(core: &ADACore, transfer_id: [u8; 16]) -> Option<f32> {
    core.get_active_transfers()
        .await
        .into_iter()
        .find_map(|(meta, progress, is_outbound)| {
            (!is_outbound && meta.id == transfer_id).then_some(progress)
        })
}

async fn wait_for_direct_text(core: &ADACore, peer: &ada_core::identity::PeerId, text: &str) {
    let conv = ada_core::messaging::ConversationId::Direct(peer.clone());

    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let messages = core.get_messages(&conv, 20);
            if messages.iter().any(|message| {
                matches!(
                    &message.kind,
                    ada_core::messaging::MessageKind::Text(candidate) if candidate == text
                )
            }) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await
    .expect("direct text message should arrive within timeout");
}

async fn try_recv_bridge_delivery(
    connection: &mut ConnectedBridge,
    timeout: Duration,
) -> Option<ada_core::bridge::BridgeEnvelope> {
    tokio::time::timeout(timeout, async {
        loop {
            let wire_format = connection.bridge.wire_format;
            match recv_frame(&mut connection.connection, wire_format).await {
                Ok(BridgeFrame::Deliver { envelope }) => return Some(envelope),
                Ok(BridgeFrame::Ping) => {
                    send_frame(&mut connection.connection, &BridgeFrame::Pong, wire_format)
                        .await
                        .expect("bridge pong should send");
                }
                Ok(BridgeFrame::Pong) => {}
                Ok(other) => panic!(
                    "unexpected bridge frame while waiting for delivery: {:?}",
                    other
                ),
                Err(_) => return None,
            }
        }
    })
    .await
    .ok()
    .flatten()
}

fn valid_audio_sdp() -> String {
    "v=0\r\no=ada 0 0 IN IP4 0.0.0.0\r\ns=ADA Call\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=sendrecv\r\n"
        .to_string()
}

async fn wait_for_bridge_server(server: &TestBridgeServer, entry: &ManifestBridgeEntry) {
    let hostname = entry.hostname.as_deref().unwrap_or(&entry.address);
    let last_error = Arc::new(std::sync::Mutex::new(String::from("none")));
    let last_error_ref = last_error.clone();

    tokio::time::timeout(Duration::from_secs(5), async move {
        loop {
            if server.handle.is_finished() {
                panic!("test bridge server task exited before becoming reachable");
            }
            match ada_core::bridge::WsTunnel::connect_with_options(
                &entry.address,
                entry.port,
                hostname,
                entry.insecure,
            )
            .await
            {
                Ok(_) => return,
                Err(error) => {
                    *last_error_ref.lock().expect("last_error mutex should lock") =
                        error.to_string();
                }
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .unwrap_or_else(|_| {
        panic!(
            "test bridge server should become reachable within timeout; last error: {}",
            last_error
                .lock()
                .expect("last_error mutex should lock")
                .clone()
        )
    });
}

fn unused_local_port() -> u16 {
    let listener = StdTcpListener::bind(("127.0.0.1", 0))
        .expect("temporary listener should bind for unused-port discovery");
    listener
        .local_addr()
        .expect("temporary listener should expose a local address")
        .port()
}

async fn test_core_with_identity(config: ADAConfig, identity: Arc<Identity>) -> Arc<ADACore> {
    ADACore::with_identity_pub(config, identity)
        .await
        .expect("core should construct with explicit identity")
}

async fn register_live_bridge_peer(
    identity: &Identity,
    entry: &ManifestBridgeEntry,
) -> ConnectedBridge {
    let mut manager = BridgeManager::new();
    manager.add_bridge(
        entry
            .to_runtime_bridge()
            .expect("manifest entry should convert to runtime bridge"),
    );

    let mut connected = manager
        .connect_via_best_transport()
        .await
        .expect("bridge transport should connect");
    let wire_format = connected.bridge.wire_format;
    let auth = fresh_bridge_auth();
    let signature = identity.sign(
        &register_challenge(&identity.peer_id.0, &auth)
            .expect("bridge register challenge should build"),
    );

    send_frame(
        &mut connected.connection,
        &BridgeFrame::Register {
            peer_id: identity.peer_id.0,
            signature,
            listen_for_mailbox: true,
            auth,
        },
        wire_format,
    )
    .await
    .expect("bridge register frame should send");

    match recv_frame(&mut connected.connection, wire_format)
        .await
        .expect("bridge register response should arrive")
    {
        BridgeFrame::RegisterOk {
            bridge_fingerprint, ..
        } => {
            verify_bridge_fingerprint(&connected.bridge.fingerprint, &bridge_fingerprint)
                .expect("bridge fingerprint should verify");
            connected
        }
        other => panic!("unexpected bridge register frame: {:?}", other),
    }
}

async fn recv_bridge_delivery(
    connection: &mut ConnectedBridge,
) -> ada_core::bridge::BridgeEnvelope {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let wire_format = connection.bridge.wire_format;
            match recv_frame(&mut connection.connection, wire_format)
                .await
                .expect("bridge frame should decode")
            {
                BridgeFrame::Deliver { envelope } => return envelope,
                BridgeFrame::Ping => {
                    send_frame(&mut connection.connection, &BridgeFrame::Pong, wire_format)
                        .await
                        .expect("bridge pong should send");
                }
                BridgeFrame::Pong => {}
                other => panic!(
                    "unexpected bridge frame while waiting for delivery: {:?}",
                    other
                ),
            }
        }
    })
    .await
    .expect("bridge delivery should arrive within timeout")
}

async fn deliver_bridge_envelope(
    core: &ADACore,
    connection: &mut ConnectedBridge,
    envelope: ada_core::bridge::BridgeEnvelope,
) {
    let wire = decode_dm_wire(&envelope.wire_bytes);
    core.receive_encrypted_wire(wire)
        .await
        .expect("bridge-delivered wire should decrypt");
    let wire_format = connection.bridge.wire_format;
    send_frame(
        &mut connection.connection,
        &BridgeFrame::Ack {
            message_ids: vec![envelope.message_id],
        },
        wire_format,
    )
    .await
    .expect("bridge ack should send");
}

async fn ack_bridge_message(connection: &mut ConnectedBridge, message_id: [u8; 16]) {
    let wire_format = connection.bridge.wire_format;
    send_frame(
        &mut connection.connection,
        &BridgeFrame::Ack {
            message_ids: vec![message_id],
        },
        wire_format,
    )
    .await
    .expect("bridge ack should send");
}

// ── existing tests (previously not wired) ─────────────────────────────────────

#[tokio::test(flavor = "multi_thread")]
async fn core_create_and_start() {
    let (cfg, _dir) = test_config("create_start");
    let core = ADACore::new(cfg, "Test Node").await.unwrap();
    core.start().await.unwrap();

    let peer_id = core.peer_id().to_base64();
    assert!(!peer_id.is_empty());

    core.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn two_nodes_can_start() {
    let (cfg_a, _dir_a) = test_config("tns_alice");
    let (cfg_b, _dir_b) = test_config("tns_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    assert_ne!(alice.peer_id().to_base64(), bob.peer_id().to_base64());

    alice.stop().await;
    bob.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn group_creation() {
    let (cfg, _dir) = test_config("group_create");
    let core = ADACore::new(cfg, "Group Tester").await.unwrap();
    core.start().await.unwrap();

    let (group_id, topic) = core.create_group("Test Group").await;
    assert!(!topic.is_empty());
    assert!(topic.contains(&hex::encode(group_id)));

    let groups = core.list_groups();
    assert_eq!(groups.len(), 1);
    assert_eq!(groups[0].name, "Test Group");

    core.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn group_call_announcement_is_stored_in_group_history() {
    let (cfg, _dir) = test_config("group_call_announcement");
    let core = ADACore::new(cfg, "Caller").await.unwrap();
    core.start().await.unwrap();

    let (group_id, _topic) = core.create_group("Call Group").await;
    let session_id = [9u8; 16];

    core.announce_group_call(group_id, session_id, true)
        .await
        .unwrap();

    let conv = ada_core::messaging::ConversationId::Group(group_id);
    let msgs = core.get_messages(&conv, 10);
    assert_eq!(msgs.len(), 1, "group call announcement should be stored");
    match &msgs[0].kind {
        ada_core::messaging::MessageKind::GroupCallStart {
            session_id: actual_session_id,
            has_video,
        } => {
            assert_eq!(*actual_session_id, session_id);
            assert!(*has_video);
        }
        other => panic!("unexpected message kind: {:?}", other),
    }

    core.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn identity_persistence() {
    // Keep _dir alive so the tempdir isn't deleted mid-test.
    let (cfg, _dir) = test_config("identity_persist");

    let core = ADACore::new(cfg.clone(), "Persistent").await.unwrap();
    let peer_id_before = core.peer_id().clone();
    core.save_identity(b"test-passphrase").unwrap();
    core.stop().await;

    let core2 = ADACore::load(cfg.clone(), b"test-passphrase")
        .await
        .unwrap();
    let peer_id_after = core2.peer_id().clone();
    assert_eq!(peer_id_before, peer_id_after);
    core2.stop().await;
}

// ── 9.17 — Full crypto pipeline: Alice → Bob (no real network) ────────────────

/// Tests the complete message delivery pipeline between two cores:
///   contact exchange → X3DH → Double Ratchet encrypt → wire bytes
///   → Double Ratchet decrypt → message store → ADAEvent.
#[tokio::test(flavor = "multi_thread")]
async fn message_delivery_alice_to_bob() {
    let (cfg_a, _dir_a) = test_config("md_alice");
    let (cfg_b, _dir_b) = test_config("md_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    // Out-of-band contact exchange (simulates QR code scan)
    alice.add_contact(bob.public_bundle()).unwrap();
    bob.add_contact(alice.public_bundle()).unwrap();

    // Alice prepares a message (sign + X3DH encrypt + store) — returns wire bytes.
    // No network I/O here; we hand the bytes to Bob directly.
    let (msg_id, wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "Hello Bob!".into(), None)
        .await
        .unwrap();

    // Decode and deliver to Bob's decryption pipeline.
    let wire = decode_dm_wire(&wire_bytes);
    bob.receive_encrypted_wire(wire).await.unwrap();

    // ── Assert: message in Bob's store ──────────────────────────────────────
    let conv = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let msgs = bob.get_messages(&conv, 10);
    assert_eq!(msgs.len(), 1, "Bob should have exactly one message");
    assert_eq!(msgs[0].id, msg_id, "message id mismatch");
    match &msgs[0].kind {
        ada_core::messaging::MessageKind::Text(text) => {
            assert_eq!(text, "Hello Bob!", "text content mismatch");
        }
        other => panic!("unexpected message kind: {:?}", other),
    }

    // ── Assert: MessageReceived event in Bob's queue ─────────────────────────
    // start() may emit NetworkConnected or other events first; loop until we
    // find MessageReceived or time out.
    let mut event_rx = bob.take_events().await.unwrap();
    let found = tokio::time::timeout(std::time::Duration::from_secs(3), async {
        loop {
            match event_rx.recv().await {
                Some(ADAEvent::MessageReceived(m)) => return Some(m),
                Some(_other) => continue, // skip NetworkConnected etc.
                None => return None,
            }
        }
    })
    .await
    .expect("event queue timeout — MessageReceived never fired");

    assert!(
        found.is_some(),
        "event channel closed without MessageReceived"
    );

    alice.stop().await;
    bob.stop().await;
}

/// Regression coverage for the onboarding path used by QR/deeplink contact add:
/// import the peer from contact-card JSON and immediately send the first message
/// without waiting for relay discovery or any live network.
#[tokio::test(flavor = "multi_thread")]
async fn contact_card_import_allows_immediate_first_message() {
    let (cfg_a, _dir_a) = test_config("card_import_alice");
    let (cfg_b, _dir_b) = test_config("card_import_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    let bob_card_json = contact_card_json_from_bundle(&bob.public_bundle());
    let alice_card_json = contact_card_json_from_bundle(&alice.public_bundle());

    import_contact_from_card(&alice, &bob_card_json);
    import_contact_from_card(&bob, &alice_card_json);

    let imported = alice
        .get_contact(&bob.peer_id().to_base64())
        .unwrap()
        .expect("Alice should have Bob after card import");
    assert_eq!(imported.peer_id, *bob.peer_id());
    assert_eq!(imported.display_name, "Bob");
    assert!(alice.conversations().iter().any(|conv| matches!(
        &conv.id,
        ada_core::messaging::ConversationId::Direct(peer) if peer == bob.peer_id()
    )),);

    let (msg_id, wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "First contact message".into(), None)
        .await
        .expect("first message should be encryptable right after contact import");

    let wire = decode_dm_wire(&wire_bytes);
    bob.receive_encrypted_wire(wire).await.unwrap();

    let conv = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let msgs = bob.get_messages(&conv, 10);
    assert_eq!(
        msgs.len(),
        1,
        "Bob should receive the very first imported-contact message"
    );
    assert_eq!(msgs[0].id, msg_id);
    assert!(matches!(
        &msgs[0].kind,
        ada_core::messaging::MessageKind::Text(text) if text == "First contact message"
    ));

    alice.stop().await;
    bob.stop().await;
}

/// Regression coverage for late contact-save after an already-working one-way DM:
/// the bundle should still be saved and the existing chat should replace its
/// autogenerated peer-id placeholder with the real display name.
#[tokio::test(flavor = "multi_thread")]
async fn late_contact_save_updates_existing_direct_chat_name() {
    let (cfg_a, _dir_a) = test_config("late_contact_save_alice");
    let (cfg_b, _dir_b) = test_config("late_contact_save_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    // Only the sender knows the recipient up front.
    alice.add_contact(bob.public_bundle()).unwrap();

    let (_msg_id, wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "Hello from unknown sender".into(), None)
        .await
        .unwrap();

    let wire = decode_dm_wire(&wire_bytes);
    bob.receive_encrypted_wire(wire).await.unwrap();

    let conv_id = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let alice_peer_b64 = alice.peer_id().to_base64();
    let generated_name = format!("{}…", &alice_peer_b64[..8.min(alice_peer_b64.len())]);

    let before = bob
        .conversations()
        .into_iter()
        .find(|conv| conv.id == conv_id)
        .expect("Bob should have a conversation for Alice after first inbound message");
    assert_eq!(before.display_name, generated_name);

    // Late add-contact should persist Alice's bundle and rename the same chat.
    bob.add_contact(alice.public_bundle()).unwrap();

    let after = bob
        .conversations()
        .into_iter()
        .find(|conv| conv.id == conv_id)
        .expect("Bob should still have the same direct conversation");
    assert_eq!(after.display_name, "Alice");
    assert_eq!(
        bob.conversations().len(),
        1,
        "late contact save must not create a duplicate chat"
    );

    let imported = bob
        .get_contact(&alice.peer_id().to_base64())
        .unwrap()
        .expect("late contact save should persist Alice's bundle");
    assert_eq!(imported.display_name, "Alice");

    alice.stop().await;
    bob.stop().await;
}

/// 9.17 variant — bidirectional exchange, two Double Ratchet steps.
#[tokio::test(flavor = "multi_thread")]
async fn message_delivery_bidirectional() {
    let (cfg_a, _dir_a) = test_config("bidi_alice");
    let (cfg_b, _dir_b) = test_config("bidi_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    alice.add_contact(bob.public_bundle()).unwrap();
    bob.add_contact(alice.public_bundle()).unwrap();

    // ── Step 1: Alice → Bob ──────────────────────────────────────────────────
    let (_, ab_wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "ping".into(), None)
        .await
        .unwrap();
    let ab_wire = decode_dm_wire(&ab_wire_bytes);
    bob.receive_encrypted_wire(ab_wire).await.unwrap();

    // ── Step 2: Bob → Alice (DH ratchet advances) ───────────────────────────
    let (_, ba_wire_bytes) = bob
        .prepare_text_message(alice.peer_id(), "pong".into(), None)
        .await
        .unwrap();
    let ba_wire = decode_dm_wire(&ba_wire_bytes);
    alice.receive_encrypted_wire(ba_wire).await.unwrap();

    // ── Assert both sides ────────────────────────────────────────────────────
    // Alice's conversation with Bob contains both her outgoing "ping" and
    // Bob's incoming "pong" (same behaviour as Signal: sent + received in one thread).
    let conv_bob_at_alice = ada_core::messaging::ConversationId::Direct(bob.peer_id().clone());
    let got_by_alice = alice.get_messages(&conv_bob_at_alice, 10);
    assert_eq!(
        got_by_alice.len(),
        2,
        "Alice should have ping (sent) + pong (received)"
    );
    assert!(
        got_by_alice.iter().any(|m| matches!(&m.kind,
            ada_core::messaging::MessageKind::Text(t) if t == "pong")),
        "Alice should have received pong"
    );

    // Bob's conversation with Alice contains both his outgoing "pong" and
    // Alice's incoming "ping".
    let conv_alice_at_bob = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let got_by_bob = bob.get_messages(&conv_alice_at_bob, 10);
    assert_eq!(
        got_by_bob.len(),
        2,
        "Bob should have ping (received) + pong (sent)"
    );
    assert!(
        got_by_bob.iter().any(|m| matches!(&m.kind,
            ada_core::messaging::MessageKind::Text(t) if t == "ping")),
        "Bob should have received ping"
    );

    alice.stop().await;
    bob.stop().await;
}

/// 9.17 variant — replay attack prevention: duplicate wire message is dropped.
#[tokio::test(flavor = "multi_thread")]
async fn replay_attack_wire_dedup() {
    let (cfg_a, _dir_a) = test_config("replay_alice");
    let (cfg_b, _dir_b) = test_config("replay_bob");
    let alice = ADACore::new(cfg_a, "Alice").await.unwrap();
    let bob = ADACore::new(cfg_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    alice.add_contact(bob.public_bundle()).unwrap();
    bob.add_contact(alice.public_bundle()).unwrap();

    let (_, wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "unique".into(), None)
        .await
        .unwrap();

    // First delivery — should succeed.
    let wire1 = decode_dm_wire(&wire_bytes);
    bob.receive_encrypted_wire(wire1).await.unwrap();

    // Second delivery of the SAME wire bytes — should be silently deduplicated.
    // With Double Ratchet, the second decrypt will fail (can't reuse ratchet state),
    // which prevents replay. The important thing is that only 1 message is stored.
    let wire2 = decode_dm_wire(&wire_bytes);
    let _ = bob.receive_encrypted_wire(wire2).await; // may return Err — that's fine

    let conv = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let msgs = bob.get_messages(&conv, 10);
    assert_eq!(msgs.len(), 1, "replay must not duplicate the message");

    alice.stop().await;
    bob.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_blocked_quic_uses_live_bridge() {
    let signing_seed = [41u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_live_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_live_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    bob.start().await.unwrap();
    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let live_entry = bridge.manifest_entry("bridge-live", 200);

    import_signed_manifest(
        &alice,
        vec![live_entry.clone()],
        signing_seed,
        "test/live-bridge",
    )
    .await;
    wait_for_bridge_server(&bridge, &live_entry).await;
    let mut bob_bridge = register_live_bridge_peer(&bob_id, &live_entry).await;

    let message_id = alice
        .send_text(bob.peer_id(), "bridge-only hello".into())
        .await
        .unwrap();
    let envelope = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, envelope).await;
    wait_for_direct_text(&bob, alice.peer_id(), "bridge-only hello").await;

    let status = bridge_status(&alice).await;
    assert_eq!(status["relay_only"].as_bool(), Some(true));
    assert_eq!(
        status["relay_only_scope"].as_str(),
        Some("bridge_or_mailbox_only")
    );
    assert_eq!(
        status["last_outcome"]["message_id"].as_str(),
        Some(hex::encode(message_id).as_str())
    );
    assert_eq!(
        status["last_outcome"]["route"].as_str(),
        Some("bridge_websocket_tls")
    );
    assert_eq!(
        status["capabilities"]["mailbox_delivery"].as_bool(),
        Some(true)
    );
    assert_eq!(status["telemetry"]["route_success_total"].as_u64(), Some(1));
    assert_eq!(
        status["telemetry"]["route_totals"]["bridge_websocket_tls"].as_u64(),
        Some(1)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_allowlist_mailbox_store_and_forward() {
    let signing_seed = [42u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_mailbox_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_mailbox_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let entries = vec![bridge.manifest_entry("bridge-mailbox", 200)];
    import_signed_manifest(&alice, entries.clone(), signing_seed, "test/mailbox").await;
    wait_for_bridge_server(&bridge, &entries[0]).await;

    let message_id = alice
        .send_text(bob.peer_id(), "queued via mailbox".into())
        .await
        .unwrap();

    let queued_status = bridge_status(&alice).await;
    assert_eq!(
        queued_status["last_outcome"]["message_id"].as_str(),
        Some(hex::encode(message_id).as_str())
    );
    assert_eq!(
        queued_status["last_outcome"]["route"].as_str(),
        Some("mailbox_bridge")
    );
    assert!(
        queued_status["bridge_mailbox_depth"]
            .as_u64()
            .unwrap_or_default()
            >= 1
    );
    assert_eq!(
        queued_status["telemetry"]["route_totals"]["mailbox_bridge"].as_u64(),
        Some(1)
    );
    assert!(
        queued_status["telemetry"]["mailbox_depth_high_watermark"]
            .as_u64()
            .unwrap_or_default()
            >= 1
    );

    let mut bob_bridge = register_live_bridge_peer(&bob_id, &entries[0]).await;
    let envelope = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, envelope).await;
    wait_for_direct_text(&bob, alice.peer_id(), "queued via mailbox").await;
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_http_mailbox_fallback_when_websocket_upgrade_is_blocked() {
    let signing_seed = [46u8; 32];
    let mut bridge = start_http_only_mailbox_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_http_mailbox_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_http_mailbox_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if reqwest::get(format!("http://{}/healthz", bridge.bind))
                .await
                .is_ok()
            {
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("http-only bridge should become reachable");

    let entry = bridge.manifest_entry("http-mailbox-fallback", 200);
    import_signed_manifest(&alice, vec![entry], signing_seed, "test/http-mailbox").await;

    let message_id = alice
        .send_text(bob.peer_id(), "posted via http mailbox".into())
        .await
        .unwrap();
    let envelope = tokio::time::timeout(Duration::from_secs(5), bridge.deliveries.recv())
        .await
        .expect("http mailbox push should arrive")
        .expect("http mailbox channel should stay open");
    assert_eq!(envelope.message_id, message_id);
    assert_eq!(envelope.recipient, bob.peer_id().0);

    let wire = decode_dm_wire(&envelope.wire_bytes);
    bob.receive_encrypted_wire(wire)
        .await
        .expect("http mailbox wire should decrypt");
    wait_for_direct_text(&bob, alice.peer_id(), "posted via http mailbox").await;

    let status = bridge_status(&alice).await;
    assert_eq!(
        status["last_outcome"]["route"].as_str(),
        Some("mailbox_bridge")
    );
    assert_eq!(
        status["telemetry"]["route_totals"]["mailbox_bridge"].as_u64(),
        Some(1)
    );
    assert!(status["bridge_mailbox_depth"].as_u64().unwrap_or_default() >= 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn allowlist_only_http_mailbox_pull_ack_receives_without_websocket_listener() {
    let signing_seed = [47u8; 32];
    let bridge = start_http_only_mailbox_bridge(signing_seed).await;
    let (mut cfg_a, _dir_a) = relay_only_config("allowlist_http_pull_alice");
    let (mut cfg_b, _dir_b) = relay_only_config("allowlist_http_pull_bob");
    cfg_a.network.connection_profile = ConnectionProfile::AllowlistOnly;
    cfg_b.network.connection_profile = ConnectionProfile::AllowlistOnly;
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let entry = bridge.manifest_entry("allowlist-http-pull", 200);
    import_signed_manifest(
        &alice,
        vec![entry.clone()],
        signing_seed,
        "test/allowlist-http-pull-alice",
    )
    .await;
    import_signed_manifest(
        &bob,
        vec![entry],
        signing_seed,
        "test/allowlist-http-pull-bob",
    )
    .await;

    let _message_id = alice
        .send_text(bob.peer_id(), "pulled via http mailbox".into())
        .await
        .unwrap();

    let handled = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let handled = bob
                .poll_http_mailbox_once()
                .await
                .expect("http mailbox pull should succeed");
            if handled > 0 {
                return handled;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("http mailbox pull should receive queued envelope");

    assert_eq!(handled, 1);
    wait_for_direct_text(&bob, alice.peer_id(), "pulled via http mailbox").await;

    let second_pull = bob
        .poll_http_mailbox_once()
        .await
        .expect("http mailbox ack should remove delivered envelope");
    assert_eq!(second_pull, 0);

    let status = bridge_status(&bob).await;
    assert_eq!(
        status["connection_profile"].as_str(),
        Some("allowlist_only")
    );
    assert_eq!(status["capabilities"]["mailbox_pull"].as_bool(), Some(true));
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_falls_back_from_dead_bridge_profile() {
    let signing_seed = [43u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_fallback_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_fallback_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let dead_entry = ManifestBridgeEntry {
        id: "dead-bridge".to_string(),
        address: "127.0.0.1".to_string(),
        port: unused_local_port(),
        protocol: "websocket".to_string(),
        hostname: Some("127.0.0.1".to_string()),
        insecure: true,
        fingerprint_hex: hex::encode([0xAA; 32]),
        shared_secret_hex: None,
        priority: 250,
        is_active: true,
        front_domain: None,
        front_url: None,
        wire_format: None,
    };
    let live_entry = bridge.manifest_entry("live-fallback-bridge", 200);

    import_signed_manifest(
        &alice,
        vec![dead_entry.clone(), live_entry.clone()],
        signing_seed,
        "test/fallback",
    )
    .await;
    wait_for_bridge_server(&bridge, &live_entry).await;
    let mut bob_bridge = register_live_bridge_peer(&bob_id, &live_entry).await;

    alice
        .send_text(bob.peer_id(), "fallback bridge hello".into())
        .await
        .unwrap();
    let envelope = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, envelope).await;
    wait_for_direct_text(&bob, alice.peer_id(), "fallback bridge hello").await;

    let status = bridge_status(&alice).await;
    let bridges = status["bridges"]
        .as_array()
        .expect("bridge status should contain a bridge list");
    let dead = bridges
        .iter()
        .find(|entry| entry["id"].as_str() == Some("dead-bridge"))
        .expect("dead bridge entry should be present");
    let live = bridges
        .iter()
        .find(|entry| entry["id"].as_str() == Some("live-fallback-bridge"))
        .expect("live bridge entry should be present");

    assert_eq!(status["has_working"].as_bool(), Some(true));
    assert_eq!(
        status["last_outcome"]["route"].as_str(),
        Some("bridge_websocket_tls")
    );
    assert_eq!(dead["reachable"].as_bool(), Some(false));
    assert_eq!(live["reachable"].as_bool(), Some(true));
    assert_eq!(
        status["telemetry"]["route_totals"]["bridge_websocket_tls"].as_u64(),
        Some(1)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_file_transfer_recovers_after_recipient_reconnect() {
    let signing_seed = [44u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_file_recovery_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_file_recovery_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let entry = bridge.manifest_entry("bridge-file-recovery", 200);
    import_signed_manifest(
        &alice,
        vec![entry.clone()],
        signing_seed,
        "test/file-recovery",
    )
    .await;
    wait_for_bridge_server(&bridge, &entry).await;

    let file_data = vec![0xAB; 32 * 1024];
    let transfer_id = alice
        .send_file(
            bob.peer_id().clone(),
            "hostile-transfer.bin",
            "application/octet-stream",
            file_data.clone(),
        )
        .await
        .unwrap();

    let chunk_count = file_data.len().div_ceil(ada_core::CHUNK_SIZE);
    for _ in 0..chunk_count {
        alice.tick_transfers().await;
    }

    let bridge_ops = bridge_ops_status(&bridge).await;
    assert_eq!(bridge_ops["total_queued_envelopes"].as_u64(), Some(2));

    let mut bob_bridge = register_live_bridge_peer(&bob_id, &entry).await;
    let first_envelope = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, first_envelope).await;
    assert_eq!(
        inbound_transfer_progress(&bob, transfer_id).await,
        Some(0.0),
        "first queued delivery should be file metadata"
    );

    let second_envelope = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, second_envelope).await;
    assert_eq!(
        inbound_transfer_progress(&bob, transfer_id).await,
        None,
        "single-chunk transfer should complete immediately after the second queued delivery"
    );
    assert!(
        bob.get_active_transfers().await.is_empty(),
        "completed inbound transfer should leave no active transfers"
    );

    let status = bridge_status(&alice).await;
    assert_eq!(status["relay_only"].as_bool(), Some(true));
    assert!(
        status["telemetry"]["route_totals"]["mailbox_bridge"]
            .as_u64()
            .unwrap_or_default()
            >= 1,
        "hostile file transfer should record mailbox delivery in relay-only mode"
    );
    assert!(
        status["telemetry"]["mailbox_depth_high_watermark"]
            .as_u64()
            .unwrap_or_default()
            >= 1,
        "mailbox depth should reflect queued file envelopes"
    );

    bob.stop().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_calls_remain_degraded_without_local_live_bridge() {
    let signing_seed = [45u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_call_recovery_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_call_recovery_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let entry = bridge.manifest_entry("bridge-call-live", 200);
    import_signed_manifest(
        &alice,
        vec![entry.clone()],
        signing_seed,
        "test/call-recovery",
    )
    .await;
    wait_for_bridge_server(&bridge, &entry).await;

    let status_before = bridge_status(&alice).await;
    assert_eq!(
        status_before["capabilities"]["realtime_calls"].as_bool(),
        Some(false)
    );
    let availability_before: Value =
        serde_json::from_str(&alice.get_call_availability_json().await)
            .expect("call availability JSON should parse");
    assert_eq!(availability_before["available"].as_bool(), Some(false));
    assert_eq!(
        availability_before["reason"].as_str(),
        Some("live_bridge_required")
    );

    let _bob_bridge = register_live_bridge_peer(&bob_id, &entry).await;

    let err = alice
        .call_audio(bob.peer_id().clone(), valid_audio_sdp())
        .await
        .expect_err("call should be rejected while the local live bridge listener is unavailable");
    assert!(
        matches!(&err, ada_core::ADAError::Call(message) if message.contains("voice/video unavailable")),
        "unexpected pre-bridge call error: {err}"
    );

    let status_after = bridge_status(&alice).await;
    assert_eq!(
        status_after["capabilities"]["realtime_calls"].as_bool(),
        Some(false)
    );
    assert_eq!(
        status_after["bridge_listener_connected"].as_bool(),
        Some(false)
    );
    let availability_after: Value = serde_json::from_str(&alice.get_call_availability_json().await)
        .expect("call availability JSON should parse after failed call");
    assert_eq!(availability_after["available"].as_bool(), Some(false));
}

#[tokio::test(flavor = "multi_thread")]
async fn hostile_network_missing_chunk_request_resends_only_missing_piece() {
    let signing_seed = [46u8; 32];
    let bridge = start_test_bridge(signing_seed).await;
    let (cfg_a, _dir_a) = relay_only_config("hostile_missing_chunk_alice");
    let (cfg_b, _dir_b) = relay_only_config("hostile_missing_chunk_bob");
    let alice_id = Arc::new(Identity::generate("Alice"));
    let bob_id = Arc::new(Identity::generate("Bob"));
    let alice = test_core_with_identity(cfg_a, alice_id.clone()).await;
    let bob = test_core_with_identity(cfg_b, bob_id.clone()).await;

    alice.add_contact(bob_id.public_bundle()).unwrap();
    bob.add_contact(alice_id.public_bundle()).unwrap();

    let entry = bridge.manifest_entry("bridge-missing-chunk", 200);
    import_signed_manifest(
        &alice,
        vec![entry.clone()],
        signing_seed,
        "test/missing-chunk",
    )
    .await;
    import_signed_manifest(
        &bob,
        vec![entry.clone()],
        signing_seed,
        "test/missing-chunk",
    )
    .await;
    wait_for_bridge_server(&bridge, &entry).await;

    let mut alice_bridge = register_live_bridge_peer(&alice_id, &entry).await;
    let mut bob_bridge = register_live_bridge_peer(&bob_id, &entry).await;

    let file_data = vec![0xCD; ada_core::CHUNK_SIZE + 1024];
    let transfer_id = alice
        .send_file(
            bob.peer_id().clone(),
            "missing-piece.bin",
            "application/octet-stream",
            file_data,
        )
        .await
        .unwrap();

    let chunk_count = 1 + 1024usize.div_ceil(ada_core::CHUNK_SIZE);
    for _ in 0..chunk_count {
        alice.tick_transfers().await;
    }

    let metadata = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, metadata).await;
    assert_eq!(
        inbound_transfer_progress(&bob, transfer_id).await,
        Some(0.0),
        "metadata should create an inbound transfer without completing it"
    );

    let first_chunk = recv_bridge_delivery(&mut bob_bridge).await;
    deliver_bridge_envelope(&bob, &mut bob_bridge, first_chunk).await;
    let progress_after_first_chunk = inbound_transfer_progress(&bob, transfer_id)
        .await
        .expect("inbound transfer should remain active after only one chunk");
    assert!(
        progress_after_first_chunk > 0.0 && progress_after_first_chunk < 1.0,
        "one delivered chunk should leave the transfer incomplete"
    );

    let dropped_chunk = recv_bridge_delivery(&mut bob_bridge).await;
    ack_bridge_message(&mut bob_bridge, dropped_chunk.message_id).await;
    let progress_after_drop = inbound_transfer_progress(&bob, transfer_id)
        .await
        .expect("dropping one chunk should still leave an inbound transfer to recover");
    assert!(
        progress_after_drop > 0.0 && progress_after_drop < 1.0,
        "transfer should still be missing data after a dropped chunk"
    );

    bob.request_missing_chunks(transfer_id, alice.peer_id().clone())
        .await
        .expect("receiver should request the missing chunk");

    let chunk_request = try_recv_bridge_delivery(&mut alice_bridge, Duration::from_secs(5))
        .await
        .expect("chunk request should reach Alice over the live bridge");
    deliver_bridge_envelope(&alice, &mut alice_bridge, chunk_request).await;

    let resent_chunk = try_recv_bridge_delivery(&mut bob_bridge, Duration::from_secs(5))
        .await
        .expect("Alice should resend the missing chunk back to Bob");
    deliver_bridge_envelope(&bob, &mut bob_bridge, resent_chunk).await;

    assert!(
        bob.get_active_transfers().await.is_empty(),
        "resent missing chunk should complete the inbound transfer"
    );
}
