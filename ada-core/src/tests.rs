//! Integration tests: two-party messaging without a real network.
//!
//! Run: cargo test --lib

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use x25519_dalek::StaticSecret;

    use crate::{
        crypto::{
            prekeys::PreKeyManager,
            ratchet::RatchetState,
            symmetric::{decrypt, encrypt, generate_key, hkdf_derive},
            x3dh::{x3dh_receive, x3dh_send},
        },
        group::GroupManager,
        identity::Identity,
        messaging::{
            session::SessionManager,
            store::{ConversationId, MessageStore},
            types::{Message, MessageKind},
        },
    };

    // ── Symmetric crypto ──────────────────────────────────────────────────

    #[test]
    fn aes_gcm_roundtrip() {
        let key = generate_key();
        let enc = encrypt(&key, b"hello, ADA!", Some(b"aad")).unwrap();
        let dec = decrypt(&key, &enc, Some(b"aad")).unwrap();
        assert_eq!(dec, b"hello, ADA!");
    }

    #[test]
    fn aes_gcm_wrong_aad_fails() {
        let key = generate_key();
        let enc = encrypt(&key, b"data", Some(b"right")).unwrap();
        assert!(decrypt(&key, &enc, Some(b"wrong")).is_err());
    }

    #[test]
    fn aes_gcm_wrong_key_fails() {
        let k1 = generate_key();
        let k2 = generate_key();
        let enc = encrypt(&k1, b"secret", None).unwrap();
        assert!(decrypt(&k2, &enc, None).is_err());
    }

    #[test]
    fn hkdf_deterministic() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        hkdf_derive(b"salt", Some(b"ikm"), b"info", &mut a);
        hkdf_derive(b"salt", Some(b"ikm"), b"info", &mut b);
        assert_eq!(a, b);
    }

    #[test]
    fn hkdf_different_info_produces_different_keys() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        hkdf_derive(b"salt", Some(b"ikm"), b"info-a", &mut a);
        hkdf_derive(b"salt", Some(b"ikm"), b"info-b", &mut b);
        assert_ne!(a, b);
    }

    // ── Identity ──────────────────────────────────────────────────────────

    #[test]
    fn identity_sign_verify() {
        let id = Identity::generate("alice");
        let sig = id.sign(b"test");
        assert!(id.public_bundle().verify(b"test", &sig).is_ok());
    }

    #[test]
    fn identity_sign_verify_wrong_message() {
        let id = Identity::generate("alice");
        let sig = id.sign(b"correct");
        assert!(id.public_bundle().verify(b"wrong", &sig).is_err());
    }

    #[test]
    fn peer_id_base64_roundtrip() {
        let id = Identity::generate("bob");
        let b64 = id.peer_id.to_base64();
        let recovered = crate::identity::PeerId::from_base64(&b64).unwrap();
        assert_eq!(id.peer_id, recovered);
    }

    // ── X3DH ─────────────────────────────────────────────────────────────

    #[test]
    fn x3dh_shared_secret_matches() {
        let alice = Identity::generate("alice");
        let bob = Identity::generate("bob");

        let mut bob_prekeys = PreKeyManager::new(&bob.signing_key);
        let bundle = bob_prekeys.signed_prekey_bundle(&bob);

        let alice_ik = StaticSecret::from(alice.dh_key.to_bytes());
        let result = x3dh_send(&alice_ik, &bundle, &bob.peer_id.0).unwrap();

        let bob_ik = StaticSecret::from(bob.dh_key.to_bytes());
        let bob_spk = StaticSecret::from(*bob.spk_secret_bytes());
        let bob_opk = result
            .opk_id_used
            .and_then(|id| bob_prekeys.consume_opk(id))
            .map(|o| StaticSecret::from(o.secret));

        let alice_ik_pub = x25519_dalek::PublicKey::from(&alice_ik).to_bytes();
        let bob_shared = x3dh_receive(
            &bob_ik,
            &bob_spk,
            bob_opk.as_ref(),
            alice_ik_pub,
            result.ephemeral_public,
        ).unwrap();

        assert_eq!(result.shared_secret, bob_shared);
    }

    // ── Double Ratchet ────────────────────────────────────────────────────

    #[test]
    fn ratchet_basic_roundtrip() {
        let alice = Identity::generate("alice");
        let bob = Identity::generate("bob");

        let mut bob_pk = PreKeyManager::new(&bob.signing_key);
        let bundle = bob_pk.signed_prekey_bundle(&bob);

        let alice_ik = StaticSecret::from(alice.dh_key.to_bytes());
        let x3dh_res = x3dh_send(&alice_ik, &bundle, &bob.peer_id.0).unwrap();

        let bob_ik = StaticSecret::from(bob.dh_key.to_bytes());
        let bob_spk = StaticSecret::from(*bob.spk_secret_bytes());
        let alice_ik_pub = x25519_dalek::PublicKey::from(&alice_ik).to_bytes();
        let bob_opk = x3dh_res
            .opk_id_used
            .and_then(|id| bob_pk.consume_opk(id))
            .map(|o| StaticSecret::from(o.secret));
        let bob_shared = x3dh_receive(
            &bob_ik,
            &bob_spk,
            bob_opk.as_ref(),
            alice_ik_pub,
            x3dh_res.ephemeral_public,
        ).unwrap();

        let mut alice_r = RatchetState::init_sender(x3dh_res.shared_secret, bundle.spk_public);
        let mut bob_r = RatchetState::init_receiver(bob_shared, *bob.spk_secret_bytes());

        // Alice → Bob
        let enc = alice_r.encrypt(b"Hello Bob!").unwrap();
        assert_eq!(bob_r.decrypt(&enc).unwrap(), b"Hello Bob!");

        // Bob → Alice (DH ratchet step)
        let enc2 = bob_r.encrypt(b"Hello Alice!").unwrap();
        assert_eq!(alice_r.decrypt(&enc2).unwrap(), b"Hello Alice!");
    }

    #[test]
    fn ratchet_out_of_order_delivery() {
        let alice = Identity::generate("alice");
        let bob = Identity::generate("bob");

        let mut bob_pk = PreKeyManager::new(&bob.signing_key);
        let bundle = bob_pk.signed_prekey_bundle(&bob);

        let alice_ik = StaticSecret::from(alice.dh_key.to_bytes());
        let x3dh = x3dh_send(&alice_ik, &bundle, &bob.peer_id.0).unwrap();

        let bob_ik = StaticSecret::from(bob.dh_key.to_bytes());
        let bob_spk = StaticSecret::from(*bob.spk_secret_bytes());
        let aik_pub = x25519_dalek::PublicKey::from(&alice_ik).to_bytes();
        let bob_opk = x3dh
            .opk_id_used
            .and_then(|id| bob_pk.consume_opk(id))
            .map(|o| StaticSecret::from(o.secret));
        let bs = x3dh_receive(
            &bob_ik,
            &bob_spk,
            bob_opk.as_ref(),
            aik_pub,
            x3dh.ephemeral_public,
        ).unwrap();

        let mut ar = RatchetState::init_sender(x3dh.shared_secret, bundle.spk_public);
        let mut br = RatchetState::init_receiver(bs, *bob.spk_secret_bytes());

        let e1 = ar.encrypt(b"msg 1").unwrap();
        let e2 = ar.encrypt(b"msg 2").unwrap();
        let e3 = ar.encrypt(b"msg 3").unwrap();

        // Receive out of order
        assert_eq!(br.decrypt(&e3).unwrap(), b"msg 3");
        assert_eq!(br.decrypt(&e1).unwrap(), b"msg 1");
        assert_eq!(br.decrypt(&e2).unwrap(), b"msg 2");
    }

    // ── Session Manager ───────────────────────────────────────────────────

    #[test]
    fn session_manager_send_receive() {
        let alice = Arc::new(Identity::generate("alice"));
        let bob = Arc::new(Identity::generate("bob"));

        let mut bob_pk = PreKeyManager::new(&bob.signing_key);
        let bundle = bob_pk.signed_prekey_bundle(&bob);

        let alice_sm = SessionManager::new(alice.clone());
        let bob_sm = SessionManager::new(bob.clone());

        let wire = alice_sm
            .encrypt_to(&bob.peer_id, b"hello", Some(&bundle), &bob.peer_id.0)
            .unwrap();
        let plain = bob_sm.decrypt_from(&wire, &mut bob_pk, None).unwrap();
        assert_eq!(&plain, b"hello");
    }

    // ── MessageStore ─────────────────────────────────────────────────────

    #[test]
    fn message_store_save_and_retrieve() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        let msg = Message::new(alice.peer_id.clone(), None, MessageKind::Text("hi".into()));
        let id = msg.id;
        store.save_message(&conv, msg).unwrap();

        let msgs = store.get_messages(&conv, None, 10);
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].id, id);
    }

    #[test]
    fn message_store_dedup() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        let msg = Message::new(alice.peer_id.clone(), None, MessageKind::Text("dup".into()));
        store.save_message(&conv, msg.clone()).unwrap();
        store.save_message(&conv, msg).unwrap();
        assert_eq!(store.get_messages(&conv, None, 10).len(), 1);
    }

    #[test]
    fn message_store_search() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());
        let mk = |t: &str| MessageKind::Text(t.to_string());

        store
            .save_message(
                &conv,
                Message::new(alice.peer_id.clone(), None, mk("hello world")),
            )
            .unwrap();
        store
            .save_message(
                &conv,
                Message::new(alice.peer_id.clone(), None, mk("goodbye cruel world")),
            )
            .unwrap();
        store
            .save_message(
                &conv,
                Message::new(alice.peer_id.clone(), None, mk("nothing")),
            )
            .unwrap();

        assert_eq!(store.search("world").len(), 2);
    }

    #[test]
    fn message_store_search_conversations_matches_text_and_display_name() {
        let alice = Identity::generate("alice");
        let bob = Identity::generate("bob");
        let store = MessageStore::in_memory();
        let conv_alice = ConversationId::Direct(alice.peer_id.clone());
        let conv_bob = ConversationId::Direct(bob.peer_id.clone());

        store.upsert_conversation(&conv_alice, "Alice Cooper");
        store.upsert_conversation(&conv_bob, "Bobby");
        store
            .save_message(
                &conv_alice,
                Message::new(
                    alice.peer_id.clone(),
                    None,
                    MessageKind::Text("orchard fox".into()),
                ),
            )
            .unwrap();
        store
            .save_message(
                &conv_bob,
                Message::new(
                    bob.peer_id.clone(),
                    None,
                    MessageKind::Text("quiet room".into()),
                ),
            )
            .unwrap();

        let by_name = store.search_conversations("alice");
        assert_eq!(by_name.len(), 1);
        assert_eq!(by_name[0].display_name, "Alice Cooper");

        let by_text = store.search_conversations("orchard");
        assert_eq!(by_text.len(), 1);
        assert_eq!(by_text[0].display_name, "Alice Cooper");
    }

    #[test]
    fn message_store_search_uses_latest_edit_text() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        let original = Message::new(
            alice.peer_id.clone(),
            None,
            MessageKind::Text("old phrase".into()),
        );
        let original_id = original.id;
        store.save_message(&conv, original).unwrap();
        store
            .save_hidden_message(
                &conv,
                Message::new(
                    alice.peer_id.clone(),
                    None,
                    MessageKind::Edit {
                        target_msg_id: original_id,
                        new_text: "new phrase".into(),
                    },
                ),
            )
            .unwrap();

        assert_eq!(store.search("old phrase").len(), 0);
        assert_eq!(store.search("new phrase").len(), 1);
    }

    #[test]
    fn message_store_search_reverts_when_edit_message_is_deleted() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        let original = Message::new(
            alice.peer_id.clone(),
            None,
            MessageKind::Text("old phrase".into()),
        );
        let original_id = original.id;
        store.save_message(&conv, original).unwrap();

        let edit = Message::new(
            alice.peer_id.clone(),
            None,
            MessageKind::Edit {
                target_msg_id: original_id,
                new_text: "new phrase".into(),
            },
        );
        let edit_id = edit.id;
        store.save_hidden_message(&conv, edit).unwrap();

        assert_eq!(store.search("old phrase").len(), 0);
        assert_eq!(store.search("new phrase").len(), 1);

        store.delete_message(&edit_id).unwrap();

        assert_eq!(store.search("new phrase").len(), 0);
        assert_eq!(store.search("old phrase").len(), 1);
    }

    #[test]
    fn message_store_clear_messages_removes_search_hits() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        store.upsert_conversation(&conv, "Alice Cooper");
        store
            .save_message(
                &conv,
                Message::new(
                    alice.peer_id.clone(),
                    None,
                    MessageKind::Text("orchard fox".into()),
                ),
            )
            .unwrap();

        assert_eq!(store.search("orchard").len(), 1);
        assert_eq!(store.search_conversations("orchard").len(), 1);

        store.clear_messages(&conv).unwrap();

        assert_eq!(store.search("orchard").len(), 0);
        assert_eq!(store.search_conversations("orchard").len(), 0);
        assert_eq!(store.list_conversations().len(), 1);
    }

    #[test]
    fn message_store_mark_read_clears_unread() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        for _ in 0..3 {
            store
                .save_message(
                    &conv,
                    Message::new(alice.peer_id.clone(), None, MessageKind::Text("x".into())),
                )
                .unwrap();
        }
        assert_eq!(store.list_conversations()[0].unread_count, 3);
        store.mark_read(&conv);
        assert_eq!(store.list_conversations()[0].unread_count, 0);
    }

    #[test]
    fn message_store_save_without_unread_keeps_counter_zero() {
        let alice = Identity::generate("alice");
        let store = MessageStore::in_memory();
        let conv = ConversationId::Direct(alice.peer_id.clone());

        store
            .save_message_with_unread(
                &conv,
                Message::new(alice.peer_id.clone(), None, MessageKind::Text("x".into())),
                false,
            )
            .unwrap();

        assert_eq!(store.list_conversations()[0].unread_count, 0);
    }

    // ── Group (Sender Keys) ───────────────────────────────────────────────

    #[test]
    fn group_encrypt_decrypt_roundtrip() {
        let alice = Arc::new(Identity::generate("alice"));
        let bob = Arc::new(Identity::generate("bob"));

        let ag = GroupManager::new(alice.clone());
        let bg = GroupManager::new(bob.clone());

        let (gid, alice_dist) = ag.create_group("test-group");

        let mut group = crate::group::types::Group::new("test-group", alice.peer_id.clone());
        group.id = gid;
        group.topic = format!("/ada/group/{}", hex::encode(gid));
        let bob_dist = bg.join_group_and_init(group, alice_dist.clone());

        ag.install_peer_key(gid, bob_dist).unwrap();
        bg.install_peer_key(gid, alice_dist).unwrap();

        let skm = ag.encrypt_group_message(gid, b"group msg").unwrap();
        assert_eq!(bg.decrypt_group_message(&skm).unwrap(), b"group msg");
    }

    #[test]
    fn group_multiple_messages() {
        let alice = Arc::new(Identity::generate("alice"));
        let bob = Arc::new(Identity::generate("bob"));

        let ag = GroupManager::new(alice.clone());
        let bg = GroupManager::new(bob.clone());

        let (gid, ad) = ag.create_group("chat");
        let mut group = crate::group::types::Group::new("chat", alice.peer_id.clone());
        group.id = gid;
        group.topic = format!("/ada/group/{}", hex::encode(gid));
        let bd = bg.join_group_and_init(group, ad.clone());
        ag.install_peer_key(gid, bd).unwrap();
        bg.install_peer_key(gid, ad).unwrap();

        for i in 0..10u32 {
            let pt = format!("message {i}");
            let skm = ag.encrypt_group_message(gid, pt.as_bytes()).unwrap();
            assert_eq!(bg.decrypt_group_message(&skm).unwrap(), pt.as_bytes());
        }
    }

    #[test]
    fn group_join_tracks_local_member_and_sender_keys() {
        let alice = Arc::new(Identity::generate("alice"));
        let bob = Arc::new(Identity::generate("bob"));

        let ag = GroupManager::new(alice.clone());
        let bg = GroupManager::new(bob.clone());

        let (gid, alice_dist) = ag.create_group("chat");
        ag.add_member(gid, bob.peer_id.clone(), String::new(), &alice.peer_id)
            .unwrap();

        let mut group = crate::group::types::Group::new("chat", alice.peer_id.clone());
        group.id = gid;
        group.topic = format!("/ada/group/{}", hex::encode(gid));

        let bob_dist = bg.join_group_and_init(group, alice_dist);

        let bob_group = bg.get_group(&gid).unwrap();
        assert!(bob_group.members.iter().any(|member| {
            member.peer_id == alice.peer_id
                && member.role == crate::group::types::GroupRole::Owner
                && member.has_sender_key
        }));
        assert!(bob_group.members.iter().any(|member| {
            member.peer_id == bob.peer_id
                && member.role == crate::group::types::GroupRole::Member
                && member.has_sender_key
        }));

        ag.install_peer_key(gid, bob_dist).unwrap();

        let alice_group = ag.get_group(&gid).unwrap();
        assert!(alice_group
            .members
            .iter()
            .any(|member| { member.peer_id == bob.peer_id && member.has_sender_key }));
    }
}
