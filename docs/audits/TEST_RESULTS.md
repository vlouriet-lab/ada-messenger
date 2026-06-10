# ada-core Test Results

Automated test results for the Rust security & networking core (`ada-core`).

| | |
|---|---|
| **Date** | 2026-06-10 |
| **Toolchain** | rustc 1.94.1 / cargo 1.94.1 |
| **Host** | Windows (x86_64-pc-windows-msvc) |
| **Command** | `cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev` |
| **Result** | **157 passed · 0 failed · 3 ignored** |

## Summary by suite

| Suite | Passed | Failed | Ignored | Time |
|-------|-------:|-------:|--------:|-----:|
| Unit tests (`src/lib.rs`) | 137 | 0 | 2 | 15.86s |
| Integration tests (`tests/integration.rs`) | 20 | 0 | 1 | 8.51s |
| Binary targets (`ada_bridge_node`, `main`, manifest tools) | 0 | 0 | 0 | — |
| **Total** | **157** | **0** | **3** | — |

> The 3 ignored cases are environment-dependent (a live network/relay round-trip
> and a doctest) that are skipped in the default offline run.

## Coverage areas

The suite exercises the security-critical surface of the core:

- **Cryptography** — X3DH key agreement, Double Ratchet (in-order, out-of-order,
  bidirectional), AES-GCM, HKDF, Ed25519 sign/verify, sender-key group encryption.
- **Pattern authentication** — deterministic identity derivation, canonical pattern
  keys, validation/rejection of malformed input.
- **Bridges & censorship resistance** — bridge line parsing, manifest signing &
  verification, circuit breaker, obfs4 handshake, domain-fronting tunnel, WebSocket
  tunnel, steganography round-trip, mailbox store-and-forward, replay/nonce caches,
  token-bucket rate limiting.
- **Transport routing** — local-mesh / live-route / offline-queue prioritization,
  relay-only capability degradation, hostile-network fallbacks.
- **Messaging & storage** — message store dedup, search, edits, unread counters,
  conversation ordering.
- **Media / calls** — Opus encoder framing, adaptive bitrate under packet loss,
  call state machine, duplicate-invite rejection.
- **Networking** — iroh endpoint startup, blob store, relay reputation, DPI padding,
  sync round-trips, mesh handoff (chunking, tamper detection, missing-chunk recovery).
- **FFI / shortlinks** — contact-card and shortlink encode/decode, tamper detection.

## How to reproduce

```bash
cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev
```

`mobile-dev` uses bundled SQLite (no native SQLCipher toolchain required) so the
suite builds and runs on a clean developer machine. CI runs the same suite on each
push; see `.github/workflows/`.

## Full test list

```
test api::tests::healthy_iroh_runtime_overrides_probe_to_none ... ok
test api::tests::relay_only_does_not_force_green ... ok
test api::tests::partial_runtime_softens_probe_to_light ... ok
test api::tests::only_iroh_live_route_implies_peer_online ... ok
test api::tests::sync_cursor_mode_returns_immediately_preceding_page ... ok
test api::tests::outgoing_prepare_text_message_keeps_unread_zero ... ok
test api::tests::sync_backlog_recovery_pages_without_gaps ... ok
test bridge::bridge::tests::bridge_line_requires_domain_front_field ... ok
test bridge::bridge::tests::bridge_line_parses_json_wire_format ... ok
test bridge::bridge::tests::bridge_line_rejects_unknown_protocol ... ok
test bridge::bridge::tests::bridge_line_rejects_invalid_port ... ok
test bridge::bridge::tests::bridge_line_rejects_unknown_wire_format ... ok
test bridge::bridge::tests::circuit_breaker_trips_after_three_failures_and_recovers ... ok
test bridge::bridge::tests::bridge_line_requires_nonzero_fingerprint ... ok
test bridge::bridge::tests::protocol_score_prefers_mode_compatible_bridges ... ok
test bridge::bridge::tests::recommended_mode_uses_configured_bridge_hosts ... ok
test bridge::domain_front::tests::meek_rejects_non_https_front_url ... ok
test bridge::domain_front::tests::domain_front_tunnel_connects_over_local_tls_and_forwards_bytes ... ok
test bridge::mailbox::tests::fingerprint_verification_accepts_zero_or_match ... ok
test bridge::mailbox::tests::delivery_class_maps_to_expected_lane ... ok
test bridge::mailbox::tests::fingerprint_verification_rejects_mismatch ... ok
test bridge::mailbox::tests::http_auth_challenges_match_worker_manual_bincode_layout ... ok
test bridge::mailbox::tests::json_wire_format_roundtrips_bridge_frame ... ok
test bridge::manifest::tests::manifest_entry_converts_to_runtime_bridge ... ok
test bridge::manifest::tests::manifest_entry_supports_json_wire_format ... ok
test bridge::manifest::tests::manifest_validation_rejects_duplicate_bridge_ids ... ok
test bridge::manifest::tests::manifest_validation_rejects_missing_domain_front_field ... ok
test bridge::manifest::tests::manifest_validation_rejects_zero_fingerprint ... ok
test bridge::manifest::tests::manifest_validation_rejects_zero_ttl ... ok
test bridge::manifest::tests::manifest_rejects_untrusted_signer ... ok
test bridge::manifest::tests::manifest_roundtrip_signs_and_verifies ... ok
test bridge::obfs4::tests::session_keys_are_directional ... ok
test bridge::obfs4::tests::handshake_tags_depend_on_secret_and_nonce ... ok
test bridge::obfs4::tests::client_and_server_roundtrip_payload_after_authenticated_handshake ... ok
test bridge::server::tests::enqueue_deduplicates_and_ack_removes_messages ... ok
test bridge::server::tests::auth_rejects_stale_timestamp ... ok
test bridge::server::tests::send_live_delivers_to_registered_peer ... ok
test bridge::server::tests::ops_status_reports_queue_health_and_counters ... ok
test bridge::server::tests::token_bucket_rejects_burst_above_capacity ... ok
test bridge::server::tests::auth_replay_cache_rejects_nonce_reuse ... ok
test bridge::steg::tests::test_steganography_roundtrip ... ok
test bridge::ws_tunnel::tests::insecure_ws_tunnel_roundtrips_and_sets_expected_host_header ... ok
test config::tests::connection_profile_parses_ui_and_legacy_aliases ... ok
test config::tests::build_time_bootstrap_values_allow_multiple_separators ... ok
test config::tests::connection_profile_serializes_as_snake_case ... ok
test config::tests::default_configs_use_auto_connection_profile ... ok
test config::tests::legacy_network_config_without_profile_defaults_to_auto ... ok
test crypto::ratchet::tests::encrypt_decrypt_basic ... ok
test crypto::ratchet::tests::bidirectional ... ok
test crypto::ratchet::tests::multiple_messages_in_order ... ok
test crypto::x3dh::tests::x3dh_shared_secret_matches ... ok
test crypto::x3dh::tests::x3dh_with_opk_matches ... ok
test crypto::prekeys::tests::consume_opk ... ok
test crypto::prekeys::tests::initial_pool_size ... ok
test ffi::tests::add_bridge_records_last_error_message ... ok
test group::sender_keys::tests::test_sender_key_group_chat ... ok
test logging::tests::test_init_tracing_desktop ... ok
test media::audio::tests::bitrate_controller_degrades_on_packet_loss ... ok
test media::audio::tests::bitrate_controller_respects_upgrade_cooldown ... ok
test media::audio::tests::opus_encoder_buffers_samples_until_full_frame ... ok
test media::call::tests::answer_and_remote_sdp_transition_call_to_active ... ok
test media::call::tests::check_timeouts_removes_expired_connecting_calls ... ok
test media::call::tests::duplicate_invite_for_same_call_id_is_rejected ... ok
test mesh_handoff::tests::adaptive_chunk_size_respects_target ... ok
test mesh_handoff::tests::missing_chunks_reported ... ok
test mesh_handoff::tests::detects_tampered_chunk ... ok
test mesh_handoff::tests::roundtrip_small_bundle ... ok
test messaging::store::tests::hidden_messages_do_not_replace_last_visible_conversation_message ... ok
test metrics::tests::snapshot_reflects_increments ... ok
test network::dpi::tests::padding_roundtrip ... ok
test network::iroh_fallback::tests::test_fallback_config_defaults ... ok
test network::iroh_fallback::tests::test_send_outcome_display ... ok
test network::iroh_transport::tests::iroh_endpoint_starts ... ok
test network::iroh_transport::tests::blob_store_insert_evict ... ok
test network::relay::tests::enqueue_and_drain_offline ... ok
test network::relay_reputation::tests::clamping ... ok
test network::relay_reputation::tests::decay_does_not_overshoot_default ... ok
test network::relay_reputation::tests::decay_moves_score_towards_default ... ok
test network::relay_reputation::tests::preferred_and_avoid_thresholds ... ok
test network::relay_reputation::tests::score_after_event_applies_bonus_and_penalty ... ok
test network::relay_reputation::tests::uptime_percent_is_bounded ... ok
test network::sync::tests::sync_round_trip_in_memory ... ok
test pattern_auth::tests::from_bytes_rejects_wrong_length ... ok
test pattern_auth::tests::from_bytes_roundtrip ... ok
test pattern_auth::tests::pattern_key_is_canonical_sorted ... ok
test pattern_auth::tests::pattern_key_rejects_duplicates ... ok
test pattern_auth::tests::pattern_key_rejects_invalid_color ... ok
test pattern_auth::tests::pattern_key_rejects_out_of_range ... ok
test pattern_auth::tests::pattern_key_validates_length ... ok
test pattern_auth::tests::contact_card_json_roundtrip ... ok
test pattern_auth::tests::derive_identity_is_deterministic ... ok
test pattern_auth::tests::different_patterns_give_different_peer_ids ... ok
test pattern_auth::tests::verify_pattern_correct ... ok
test pattern_auth::tests::different_colors_give_different_peer_ids ... ok
test pattern_auth::tests::verify_pattern_wrong_cells ... ok
test pattern_auth::tests::verify_pattern_wrong_color ... ok
test pattern_auth::tests::derive_identity_display_name_does_not_affect_peer_id ... ok
test shortlink::tests::compact_is_shorter_than_legacy ... ok
test shortlink::tests::compact_round_trip_v2 ... ok
test shortlink::tests::compact_round_trip_with_opk ... ok
test shortlink::tests::each_encode_is_unique ... ok
test shortlink::tests::legacy_json_still_decodes ... ok
test shortlink::tests::relay_url_round_trip_uses_json_fallback ... ok
test shortlink::tests::tamper_detected ... ok
test shortlink::tests::too_short_rejected ... ok
test shortlink::tests::wrong_prefix_rejected ... ok
test tests::tests::aes_gcm_roundtrip ... ok
test tests::tests::aes_gcm_wrong_aad_fails ... ok
test tests::tests::aes_gcm_wrong_key_fails ... ok
test tests::tests::group_join_tracks_local_member_and_sender_keys ... ok
test tests::tests::group_encrypt_decrypt_roundtrip ... ok
test tests::tests::hkdf_deterministic ... ok
test tests::tests::hkdf_different_info_produces_different_keys ... ok
test tests::tests::identity_sign_verify ... ok
test tests::tests::group_multiple_messages ... ok
test tests::tests::message_store_clear_messages_removes_search_hits ... ok
test tests::tests::message_store_dedup ... ok
test tests::tests::identity_sign_verify_wrong_message ... ok
test tests::tests::message_store_mark_read_clears_unread ... ok
test tests::tests::message_store_save_and_retrieve ... ok
test tests::tests::message_store_save_without_unread_keeps_counter_zero ... ok
test tests::tests::message_store_search_conversations_matches_text_and_display_name ... ok
test tests::tests::message_store_search ... ok
test tests::tests::message_store_search_reverts_when_edit_message_is_deleted ... ok
test tests::tests::message_store_search_uses_latest_edit_text ... ok
test tests::tests::peer_id_base64_roundtrip ... ok
test tests::tests::ratchet_basic_roundtrip ... ok
test tests::tests::ratchet_out_of_order_delivery ... ok
test tests::tests::session_manager_send_receive ... ok
test tests::tests::x3dh_shared_secret_matches ... ok
test transfer::handoff_tests::choose_chunk_size_clamps ... ok
test transfer::handoff_tests::transfer_offer_verify ... ok
test transport::tests::attempts_prioritize_local_mesh_then_live_routes_then_offline_queue ... ok
test transport::tests::attempts_skip_disabled_routes_but_keep_local_mesh_first ... ok
test transport::tests::bridge_protocol_mapping_preserves_live_vs_mailbox_routes ... ok
test transport::tests::live_bridge_capabilities_restore_realtime_calls_under_relay_only ... ok
test transport::tests::relay_only_capabilities_disable_large_attachments_without_live_bridge ... ok

# Integration tests (tests/integration.rs)
test add_contact_without_current_runtime_does_not_panic ... ok
test bridge_manifest_import_rejects_invalid_fields_without_replacing_existing_config ... ok
test core_create_and_start ... ok
test group_creation ... ok
test hostile_network_calls_remain_degraded_without_local_live_bridge ... ok
test allowlist_only_http_mailbox_pull_ack_receives_without_websocket_listener ... ok
test hostile_network_allowlist_mailbox_store_and_forward ... ok
test hostile_network_blocked_quic_uses_live_bridge ... ok
test hostile_network_file_transfer_recovers_after_recipient_reconnect ... ok
test group_call_announcement_is_stored_in_group_history ... ok
test hostile_network_http_mailbox_fallback_when_websocket_upgrade_is_blocked ... ok
test hostile_network_missing_chunk_request_resends_only_missing_piece ... ok
test late_contact_save_updates_existing_direct_chat_name ... ok
test hostile_network_falls_back_from_dead_bridge_profile ... ok
test contact_card_import_allows_immediate_first_message ... ok
test two_nodes_can_start ... ok
test message_delivery_alice_to_bob ... ok
test replay_attack_wire_dedup ... ok
test message_delivery_bidirectional ... ok
test identity_persistence ... ok
```
