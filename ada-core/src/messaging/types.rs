use crate::crypto::symmetric::EncryptedData;
use crate::identity::PeerId;
use serde::{Deserialize, Serialize};

/// 16-byte opaque message identifier
pub type MessageId = [u8; 16];

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireMessage {
    pub sender: PeerId,
    pub recipient: PeerId,
    pub ciphertext: EncryptedData,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Message {
    pub id: [u8; 16],
    pub sender: PeerId,
    pub recipient: Option<PeerId>,
    pub group_id: Option<[u8; 16]>,
    pub timestamp: u64,
    pub kind: MessageKind,
    pub signature: Vec<u8>,
    pub status: MessageStatus,
    /// ID of the message this is a reply to (Telegram-style quoting).
    #[serde(default)]
    pub reply_to: Option<[u8; 16]>,
    /// Time-to-live in seconds. If Some, message should be auto-deleted `expires_in` seconds after reading.
    #[serde(default)]
    pub expires_in: Option<u32>,
    /// Absolute timestamp (in milliseconds since epoch) when this message must be destroyed.
    /// Typically set when the message is marked as Read.
    #[serde(default)]
    pub expires_at: Option<u64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum MessageKind {
    Text(String),
    /// File transfer announcement (metadata only — data follows as chunks)
    File {
        file_id: [u8; 16],
        name: String,
        size: u64,
        mime_type: String,
        checksum: [u8; 32],
        /// Symmetric key for AES-GCM chunk encryption (encrypted to recipient with their DH key)
        encryption_key: [u8; 32],
        chunk_count: u32,
    },
    /// A single encrypted file chunk
    FileChunk {
        transfer_id: [u8; 16],
        index: u32,
        total: u32,
        /// AES-GCM encrypted chunk bytes (raw EncryptedData bincode)
        data: Vec<u8>,
        /// Blake3 checksum of the plaintext chunk
        chunk_checksum: [u8; 32],
    },
    /// Receiver asks sender to retransmit specific chunks
    ChunkRequest {
        transfer_id: [u8; 16],
        /// Chunk indices the receiver is still missing
        missing: Vec<u32>,
    },
    /// Iroh Blobs large-file transfer reference (DM-only).
    /// Sender stores the file content-addressed and sends this notification;
    /// receiver calls `ADACore::fetch_file_blob(peer, hash)` to pull the bytes
    /// via a bidi QUIC stream keyed by the blake3 hash.
    BlobRef {
        /// Unique transfer identifier for UI tracking
        file_id: [u8; 16],
        name: String,
        size: u64,
        mime_type: String,
        /// Blake3 content hash — serves as both the blob store key and integrity check
        hash: [u8; 32],
    },
    /// Sender's iroh relay URL hint — sent as a separate DM immediately after BlobRef.
    /// Allows the receiver to connect directly without waiting for pkarr DNS propagation
    /// (which can take 15–60 s on a new session).  Old clients that don't know this
    /// variant will fail to deserialize it gracefully (warning logged, no crash) because
    /// the BlobRef notification was already processed in the preceding message.
    IrohHint {
        /// The sender's current iroh relay URL (n0 infrastructure).
        relay_url: String,
    },
    Call(CallEvent),
    /// Group invite — sent peer-to-peer as an encrypted DM
    GroupInvite {
        group_id: [u8; 16],
        group_name: String,
        group_topic: String,
        /// bincode-serialised SenderKeyDistribution from the inviter
        sender_dist_bytes: Vec<u8>,
    },
    /// Reply when joining: the new member's own SenderKeyDistribution
    GroupJoinAccept {
        group_id: [u8; 16],
        /// bincode-serialised SenderKeyDistribution from the new member
        member_dist_bytes: Vec<u8>,
    },
    /// Visible group-chat announcement for a shared call room.
    ///
    /// This is the chat-level anchor for a group voice/video session. Media
    /// transport may still evolve underneath, but the room identity is shared
    /// by everyone in the chat via `session_id`.
    GroupCallStart {
        session_id: [u8; 16],
        has_video: bool,
    },
    /// Request the peer to delete a specific message on their device.
    /// Only honoured when the sender of this request matches the original
    /// author of the target message (enforced on receipt).
    DeleteRequest {
        target_msg_id: [u8; 16],
    },
    /// Replace the displayed text of an earlier text message.
    ///
    /// The original signed message stays immutable; this protocol message is
    /// applied as a local overlay when rendering or syncing history.
    Edit {
        target_msg_id: [u8; 16],
        new_text: String,
    },
    /// Emoji reaction to an existing message.
    Reaction {
        target_msg_id: [u8; 16],
        emoji: String,
    },
    /// Lightweight per-peer delta sync request sent on reconnect/online transition.
    SyncRequest {
        /// Newest message timestamp known to the sender for this direct conversation.
        latest_message_ts: Option<u64>,
        /// A bounded set of message IDs already present on sender side.
        known_message_ids: Vec<[u8; 16]>,
        /// Optional cursor: request only messages older than this timestamp.
        #[serde(default)]
        cursor_before_ts: Option<u64>,
        /// Requested batch size limit for one response.
        #[serde(default)]
        max_messages: Option<u32>,
    },
    /// Response to `SyncRequest` carrying missing direct-conversation messages.
    SyncResponse {
        /// Messages the requester is missing. Ordered oldest-first.
        messages: Vec<Message>,
        /// True when sender has more missing messages beyond this batch.
        has_more: bool,
        /// Cursor for the next pull round when `has_more = true`.
        #[serde(default)]
        next_cursor_before_ts: Option<u64>,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum CallEvent {
    Invite {
        call_id: [u8; 16],
        offer_sdp: String,
        has_video: bool,
        #[serde(default)]
        group_id: Option<[u8; 16]>,
        #[serde(default)]
        session_id: Option<[u8; 16]>,
        #[serde(default)]
        participants: Vec<PeerId>,
    },
    Answer {
        call_id: [u8; 16],
        answer_sdp: String,
    },
    Candidate {
        call_id: [u8; 16],
        candidate: String,
        sdp_mid: Option<String>,
        sdp_mline_index: Option<u16>,
    },
    Hangup {
        call_id: [u8; 16],
        reason: HangupReason,
    },
    /// ICE restart: offerer sends a new offer with fresh ICE credentials for an existing call.
    IceRestartOffer {
        call_id: [u8; 16],
        offer_sdp: String,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum HangupReason {
    Normal,
    Busy,
    Declined,
    Error,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum MessageStatus {
    Sending,
    Sent,
    Delivered,
    Read,
    Failed(String),
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Conversation {
    pub id: ConversationId,
    pub unread_count: u32,
    pub last_message: Option<Message>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum ConversationId {
    Direct(PeerId),
    Group([u8; 16]),
}

impl Message {
    pub fn new(sender: PeerId, recipient: Option<PeerId>, kind: MessageKind) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        Message {
            id,
            sender,
            recipient,
            group_id: None,
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            kind,
            signature: vec![],
            status: MessageStatus::Sending,
            reply_to: None,
            expires_in: None,
            expires_at: None,
        }
    }

    /// Creates a new ephemeral message that requires auto-deletion based on Time-to-Live
    pub fn new_ephemeral(
        sender: PeerId,
        recipient: Option<PeerId>,
        kind: MessageKind,
        ttl_seconds: u32,
    ) -> Self {
        let mut msg = Self::new(sender, recipient, kind);
        msg.expires_in = Some(ttl_seconds);
        // For the sender, we start the TTL clock immediately upon creation
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        msg.expires_at = Some(now_ms.saturating_add((ttl_seconds as u64) * 1000));
        msg
    }

    pub fn for_group(sender: PeerId, group_id: [u8; 16], kind: MessageKind) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
        Message {
            id,
            sender,
            recipient: None,
            group_id: Some(group_id),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            kind,
            signature: vec![],
            status: MessageStatus::Sending,
            reply_to: None,
            expires_in: None,
            expires_at: None,
        }
    }

    /// Canonical byte representation that is covered by the sender's signature.
    ///
    /// Fields included (in order):
    ///   id (16 B) | timestamp (8 B) | sender (32 B)
    ///   | recipient (32 B, or 32 zeros for group msgs)
    ///   | group_id  (16 B, or 16 zeros for direct msgs)
    ///   | bincode(kind) — covers the actual message content
    ///
    /// Changing any of these fields after signing invalidates the signature,
    /// preventing relay nodes from silently altering message content.
    pub fn bytes_to_sign(&self) -> Vec<u8> {
        let mut b = Vec::with_capacity(16 + 8 + 32 + 32 + 16 + 256);
        b.extend_from_slice(&self.id);
        b.extend_from_slice(&self.timestamp.to_le_bytes());
        b.extend_from_slice(&self.sender.0);
        // Pad absent optional fields with zeros so layout is always deterministic.
        b.extend_from_slice(&self.recipient.as_ref().map_or([0u8; 32], |r| r.0));
        b.extend_from_slice(&self.group_id.unwrap_or([0u8; 16]));
        // Cover the actual message content (text, file metadata, call events, etc.)
        // SAFETY: MessageKind only contains primitives + Vec/String; bincode
        // serialization is infallible for these types. Silently omitting `kind`
        // from the signature would allow relay nodes to alter message content
        // while preserving a valid signature, so we panic rather than degrade.
        let kind_bytes =
            bincode::serialize(&self.kind).expect("MessageKind serialization must not fail");
        b.extend_from_slice(&kind_bytes);
        b.extend_from_slice(&self.reply_to.unwrap_or([0u8; 16]));
        b
    }
}
