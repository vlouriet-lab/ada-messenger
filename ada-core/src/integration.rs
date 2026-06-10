use ada_core::{ADACore, ADAConfig, api::ADAEvent, identity::PeerId};

/// 9.17 — Full Alice → Bob messaging pipeline (encrypt/decrypt over wire).
///
/// Does NOT require actual network sockets; tests the full cryptographic path:
///   X3DH key agreement → Double Ratchet encrypt → wire bytes →
///   Double Ratchet decrypt → message store → event queue.
#[tokio::test]
async fn test_two_nodes_message_delivery() {
    let alice = ADACore::new(test_config("alice_msg"), "Alice").await.unwrap();
    let bob   = ADACore::new(test_config("bob_msg"),   "Bob")  .await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    // Exchange public bundles (simulates contact-add via QR / link)
    alice.add_contact(bob.public_bundle()).unwrap();
    bob.add_contact(alice.public_bundle()).unwrap();

    // Alice prepares a text message: signs, X3DH-encrypts, stores locally,
    // returns the serialised wire bytes without doing any network I/O.
    let (msg_id, wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "Hello Bob!".into())
        .await
        .unwrap();

    // Feed the wire bytes directly into Bob's decryption pipeline.
    let wire: ada_core::messaging::session::EncryptedWire =
        bincode::deserialize(&wire_bytes).expect("wire deserialize");
    bob.receive_encrypted_wire(wire).await.unwrap();

    // Verify Bob stored the message.
    let conv = ada_core::messaging::ConversationId::Direct(alice.peer_id().clone());
    let msgs = bob.get_messages(&conv, 10);
    assert_eq!(msgs.len(), 1, "Bob should have exactly one message");
    let msg = &msgs[0];
    assert_eq!(msg.id, msg_id, "message id mismatch");
    match &msg.kind {
        ada_core::messaging::MessageKind::Text(text) => {
            assert_eq!(text, "Hello Bob!", "text content mismatch");
        }
        other => panic!("unexpected message kind: {:?}", other),
    }

    // Verify Bob's event queue has a MessageReceived event.
    let mut event_rx = bob.take_events().await.unwrap();
    let event = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        event_rx.recv(),
    )
    .await
    .expect("event timeout")
    .expect("channel closed");
    assert!(
        matches!(event, ADAEvent::MessageReceived(_)),
        "expected MessageReceived, got {:?}", event
    );

    alice.stop().await;
    bob.stop().await;
}

/// 9.17 variant — Bob replies to Alice (bidirectional, two ratchet steps).
#[tokio::test]
async fn test_bidirectional_message_delivery() {
    let alice = ADACore::new(test_config("alice_bidi"), "Alice").await.unwrap();
    let bob   = ADACore::new(test_config("bob_bidi"),   "Bob")  .await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    alice.add_contact(bob.public_bundle()).unwrap();
    bob.add_contact(alice.public_bundle()).unwrap();

    // Alice → Bob
    let (_, ab_wire_bytes) = alice
        .prepare_text_message(bob.peer_id(), "ping".into())
        .await
        .unwrap();
    let ab_wire: ada_core::messaging::session::EncryptedWire =
        bincode::deserialize(&ab_wire_bytes).unwrap();
    bob.receive_encrypted_wire(ab_wire).await.unwrap();

    // Bob → Alice (ratchet advances on Bob's side first)
    let (_, ba_wire_bytes) = bob
        .prepare_text_message(alice.peer_id(), "pong".into())
        .await
        .unwrap();
    let ba_wire: ada_core::messaging::session::EncryptedWire =
        bincode::deserialize(&ba_wire_bytes).unwrap();
    alice.receive_encrypted_wire(ba_wire).await.unwrap();

    let conv_at_alice = ada_core::messaging::ConversationId::Direct(bob.peer_id().clone());
    let msgs = alice.get_messages(&conv_at_alice, 10);
    assert_eq!(msgs.len(), 1);
    assert!(matches!(&msgs[0].kind,
        ada_core::messaging::MessageKind::Text(t) if t == "pong"));

    alice.stop().await;
    bob.stop().await;
}


#[tokio::test]
async fn test_core_create_and_start() {
    let mut config = ADAConfig::default();
    config.storage.data_dir = tempfile::tempdir().unwrap().path().to_str().unwrap().into();
    config.network.tcp_port = 0;
    config.network.quic_port = 0;

    let core = ADACore::new(config, "Test Node").await.unwrap();
    core.start().await.unwrap();

    let peer_id = core.peer_id().to_base64();
    assert!(!peer_id.is_empty());

    core.stop().await;
}

#[tokio::test]
async fn test_two_nodes_messaging() {
    // Create two nodes in memory
    let config_a = test_config("a");
    let config_b = test_config("b");

    let alice = ADACore::new(config_a, "Alice").await.unwrap();
    let bob = ADACore::new(config_b, "Bob").await.unwrap();

    alice.start().await.unwrap();
    bob.start().await.unwrap();

    let alice_id = alice.peer_id().clone();
    let bob_id = bob.peer_id().clone();

    println!("Alice: {}", alice_id);
    println!("Bob:   {}", bob_id);

    alice.stop().await;
    bob.stop().await;
}

#[tokio::test]
async fn test_group_creation() {
    let config = test_config("group_test");
    let core = ADACore::new(config, "Group Tester").await.unwrap();
    core.start().await.unwrap();

    let (group_id, topic) = core.create_group("Test Group").await;
    assert!(!topic.is_empty());
    assert!(topic.contains(&hex::encode(group_id)));

    let groups = core.list_groups();
    assert_eq!(groups.len(), 1);
    assert_eq!(groups[0].name, "Test Group");

    core.stop().await;
}

#[tokio::test]
async fn test_identity_persistence() {
    let dir = tempfile::tempdir().unwrap();
    let data_dir = dir.path().to_str().unwrap().to_string();

    let config = {
        let mut c = ADAConfig::default();
        c.storage.data_dir = data_dir.clone();
        c
    };

    let core = ADACore::new(config.clone(), "Persistent").await.unwrap();
    let peer_id_before = core.peer_id().clone();
    core.save_identity(b"test-passphrase").unwrap();
    core.stop().await;

    // Load back
    let core2 = ADACore::load(config, b"test-passphrase").await.unwrap();
    let peer_id_after = core2.peer_id().clone();
    assert_eq!(peer_id_before, peer_id_after);
    core2.stop().await;
}

fn test_config(suffix: &str) -> ADAConfig {
    let dir = tempfile::tempdir().unwrap();
    let mut config = ADAConfig::default();
    config.storage.data_dir = dir.path().join(suffix).to_str().unwrap().into();
    config.network.tcp_port = 0;
    config.network.quic_port = 0;
    config.network.mdns = false;
    config.network.bootstrap_peers = vec![];
    config
}
