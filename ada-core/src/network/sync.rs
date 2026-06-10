//! `sync.rs` — offline message recovery via `MeshSyncBundle`.
//!
//! Adapted from Plex's `sync_protocol.rs` for ADA's message model.
//!
//! ## Protocol (two-step handshake on reconnect)
//!
//! 1. **Both peers** send a `SyncRequest` containing the IDs of all messages
//!    they already have for the shared conversation.
//! 2. Each peer builds a `SyncResponse` with messages the remote side is missing.
//! 3. Both sides `apply` the response to their local `KeyValueStore`.
//!
//! The bundle intentionally stays small (≤ `MAX_MESSAGES_PER_SYNC` messages per
//! round). For longer gaps, repeat the handshake.
//!
//! ## Usage
//! ```ignore
//! // On reconnect with a peer:
//! let req = build_sync_request(&storage, &peer_id);
//! // Serialise and send req to peer.
//! // Receive peer's SyncRequest:
//! let resp = build_sync_response(&storage, &peer_id, &their_req);
//! // Serialise and send resp to peer.
//! // Receive peer's SyncResponse:
//! let applied = apply_sync_response(&storage, &peer_id, &their_resp);
//! ```

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

use crate::error::{ADAError, Result};
use crate::storage::storage::{ChatMessage, KeyValueStore};

/// Maximum messages transferred in a single sync round.
const MAX_MESSAGES_PER_SYNC: usize = 128;
/// Maximum number of ids advertised in a SyncRequest's `known_ids` list.
const MAX_KNOWN_IDS: usize = 512;

// ── Wire types ────────────────────────────────────────────────────────────────

/// Sent to a peer immediately after (re-)connection.
/// Lists the message IDs this node already has; the remote side will fill gaps.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncRequest {
    /// Peer ID of the sender (base64 identity key)
    pub sender_peer_id: String,
    /// IDs of chat messages the sender already has for this conversation.
    /// Truncated to `MAX_KNOWN_IDS` oldest entries if the set is large.
    pub known_message_ids: Vec<String>,
    /// Unix timestamp (secs) of the newest message the sender holds.
    /// `None` if the sender has no messages at all.
    pub latest_message_at: Option<i64>,
}

/// Response to a `SyncRequest`.
/// Contains messages that the requester is missing.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncResponse {
    /// Messages the remote side was missing (chosen by diffing their `known_message_ids`)
    pub missing_messages: Vec<ChatMessage>,
    /// `true` if there are more messages beyond this batch — trigger another round.
    pub has_more: bool,
}

/// Summary of what was applied from a `SyncResponse`.
#[derive(Debug, Default)]
pub struct SyncApplyReport {
    pub messages_inserted: usize,
    /// IDs that were already in local storage (duplicates skipped)
    pub duplicates_skipped: usize,
}

// ── Builder helpers ───────────────────────────────────────────────────────────

/// Build a `SyncRequest` for `peer_id`'s conversation from local storage.
///
/// Loads all stored messages from that peer and fills `known_message_ids`.
pub fn build_sync_request(store: &KeyValueStore, peer_id: &str) -> Result<SyncRequest> {
    let messages = store.load_chat_messages(peer_id, MAX_KNOWN_IDS)?;
    let latest = messages.iter().map(|m| m.created_at).max();
    let known_ids = messages.into_iter().map(|m| m.message_id).collect();

    let my_peer_id = store
        .get("identity.peer_id")
        .and_then(|b| String::from_utf8(b).ok())
        .unwrap_or_default();

    Ok(SyncRequest {
        sender_peer_id: my_peer_id,
        known_message_ids: known_ids,
        latest_message_at: latest,
    })
}

/// Build a `SyncResponse` to answer a remote `SyncRequest`.
///
/// Loads all local messages for the conversation and returns those not
/// present in `their_request.known_message_ids`.
pub fn build_sync_response(
    store: &KeyValueStore,
    peer_id: &str,
    their_request: &SyncRequest,
) -> Result<SyncResponse> {
    // B-7 fix: guard against oversized known_ids lists from buggy or malicious peers.
    // A legitimate peer never needs more than MAX_KNOWN_IDS entries; discard the
    // request entirely rather than performing a large HashSet allocation.
    if their_request.known_message_ids.len() > MAX_KNOWN_IDS {
        return Err(ADAError::Message(format!(
            "sync request known_ids too large: {} > {} (DoS guard)",
            their_request.known_message_ids.len(),
            MAX_KNOWN_IDS,
        )));
    }
    let known: HashSet<&str> = their_request
        .known_message_ids
        .iter()
        .map(|s| s.as_str())
        .collect();

    // Load all our messages for this peer (up to a generous ceiling)
    let all_messages = store.load_chat_messages(peer_id, MAX_MESSAGES_PER_SYNC * 4)?;

    let mut missing: Vec<ChatMessage> = all_messages
        .into_iter()
        .filter(|m| !known.contains(m.message_id.as_str()))
        .collect();

    let has_more = missing.len() > MAX_MESSAGES_PER_SYNC;
    missing.truncate(MAX_MESSAGES_PER_SYNC);

    Ok(SyncResponse {
        missing_messages: missing,
        has_more,
    })
}

/// Apply a `SyncResponse` received from a remote peer into local storage.
///
/// Each message is upserted; duplicates (already-known IDs) are silently skipped.
pub fn apply_sync_response(
    store: &KeyValueStore,
    response: &SyncResponse,
) -> Result<SyncApplyReport> {
    let mut report = SyncApplyReport::default();

    for msg in &response.missing_messages {
        match store.upsert_chat_message(msg) {
            Ok(()) => report.messages_inserted += 1,
            Err(ADAError::Storage(ref s)) if s.contains("UNIQUE") => {
                // Already stored — harmless duplicate
                report.duplicates_skipped += 1;
            }
            Err(e) => return Err(e),
        }
    }

    tracing::info!(
        "[sync] applied response: inserted={} skipped={}",
        report.messages_inserted,
        report.duplicates_skipped
    );
    Ok(report)
}

// ── Full bundle (for one-shot transfer, e.g., QR-code or local mesh) ─────────

/// A self-contained snapshot of all messages for a set of peers.
/// Used for local ("mesh handoff") backup/export flows where a two-step
/// request/response is impractical.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshSyncBundle {
    /// Unix timestamp (secs) when this bundle was generated
    pub generated_at: i64,
    /// Exporting peer ID
    pub source_peer_id: String,
    /// All exported messages, sorted oldest-first
    pub messages: Vec<ChatMessage>,
}

impl MeshSyncBundle {
    /// Build a bundle containing all messages for every peer in `peer_ids`.
    pub fn export(store: &KeyValueStore, peer_ids: &[&str], max_per_peer: usize) -> Result<Self> {
        let mut all_messages = Vec::new();
        for &peer_id in peer_ids {
            let msgs = store.load_chat_messages(peer_id, max_per_peer)?;
            all_messages.extend(msgs);
        }
        // Sort oldest-first for deterministic output
        all_messages.sort_by_key(|m| m.created_at);

        let source_peer_id = store
            .get("identity.peer_id")
            .and_then(|b| String::from_utf8(b).ok())
            .unwrap_or_default();

        Ok(MeshSyncBundle {
            generated_at: unix_now(),
            source_peer_id,
            messages: all_messages,
        })
    }

    /// Apply a `MeshSyncBundle` into `store`. Returns number of new messages persisted.
    pub fn apply(&self, store: &KeyValueStore) -> Result<usize> {
        let mut inserted = 0usize;
        for msg in &self.messages {
            store.upsert_chat_message(msg)?;
            inserted += 1;
        }
        tracing::info!("[sync] mesh bundle applied: {} messages", inserted);
        Ok(inserted)
    }
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::storage::ChatMessage;

    fn make_msg(id: &str, peer: &str, body: &str, t: i64) -> ChatMessage {
        use crate::storage::storage::ChatStatus;
        ChatMessage {
            message_id: id.to_string(),
            peer_id: peer.to_string(),
            is_outgoing: false,
            kind: "text".to_string(),
            body_text: Some(body.to_string()),
            media_name: None,
            media_mime: None,
            media_size: None,
            media_blob: None,
            status: ChatStatus::Delivered,
            created_at: t,
            sent_at: None,
            delivered_at: None,
            read_at: None,
        }
    }

    #[test]
    fn sync_round_trip_in_memory() {
        let store_a = KeyValueStore::in_memory();
        let store_b = KeyValueStore::in_memory();

        let peer = "alice";
        let msg1 = make_msg("msg-1", peer, "hello", 1000);
        let msg2 = make_msg("msg-2", peer, "world", 2000);

        // A has msg1; B has msg2
        store_a.upsert_chat_message(&msg1).unwrap();
        store_b.upsert_chat_message(&msg2).unwrap();

        // A sends request to B
        let req_a = build_sync_request(&store_a, peer).unwrap();
        assert_eq!(req_a.known_message_ids, vec!["msg-1"]);

        // B builds response
        let resp = build_sync_response(&store_b, peer, &req_a).unwrap();
        assert_eq!(resp.missing_messages.len(), 1);
        assert_eq!(resp.missing_messages[0].message_id, "msg-2");

        // A applies response
        let report = apply_sync_response(&store_a, &resp).unwrap();
        assert_eq!(report.messages_inserted, 1);
        assert_eq!(report.duplicates_skipped, 0);

        // A now has both messages
        let msgs = store_a.load_chat_messages(peer, 100).unwrap();
        assert_eq!(msgs.len(), 2);
    }
}
