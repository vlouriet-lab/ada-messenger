use rand::RngCore;
use serde::{Deserialize, Serialize};

use crate::{
    bridge::bridge::{BridgeConnection, BridgeWireFormat},
    error::{ADAError, Result},
    transport::DeliveryClass,
};

pub const BRIDGE_REGISTER_CONTEXT: &[u8] = b"ada/bridge-register/v1";
pub const BRIDGE_HTTP_PUSH_CONTEXT: &[u8] = b"ada/bridge-http-push/v1";
pub const BRIDGE_HTTP_PULL_CONTEXT: &[u8] = b"ada/bridge-http-pull/v1";
pub const BRIDGE_HTTP_ACK_CONTEXT: &[u8] = b"ada/bridge-http-ack/v1";
pub const BRIDGE_AUTH_MAX_SKEW_MS: u64 = 5 * 60 * 1000;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BridgeAuth {
    pub nonce: [u8; 16],
    pub timestamp_ms: u64,
}

pub fn current_auth_timestamp_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn new_auth_nonce() -> [u8; 16] {
    let mut nonce = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut nonce);
    nonce
}

pub fn fresh_bridge_auth() -> BridgeAuth {
    BridgeAuth {
        nonce: new_auth_nonce(),
        timestamp_ms: current_auth_timestamp_ms(),
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum BridgeDeliveryLane {
    TextDm,
    FileMetadata,
    FileChunk,
    CallSignaling,
    MaintenanceRetry,
}

impl From<DeliveryClass> for BridgeDeliveryLane {
    fn from(value: DeliveryClass) -> Self {
        match value {
            DeliveryClass::DirectMessage => Self::TextDm,
            DeliveryClass::FileMetadata => Self::FileMetadata,
            DeliveryClass::FileChunk => Self::FileChunk,
            DeliveryClass::CallSignaling => Self::CallSignaling,
            DeliveryClass::MaintenanceRetry => Self::MaintenanceRetry,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BridgeEnvelope {
    pub message_id: [u8; 16],
    pub sender: [u8; 32],
    pub recipient: [u8; 32],
    pub lane: BridgeDeliveryLane,
    pub wire_bytes: Vec<u8>,
    pub created_at_ms: u64,
    pub expires_at: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum BridgePushDisposition {
    LiveBridge,
    MailboxQueued,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum BridgeFrame {
    Register {
        peer_id: [u8; 32],
        signature: Vec<u8>,
        listen_for_mailbox: bool,
        auth: BridgeAuth,
    },
    RegisterOk {
        bridge_fingerprint: [u8; 32],
        queued_count: u32,
    },
    Push {
        envelope: BridgeEnvelope,
    },
    PushAck {
        disposition: BridgePushDisposition,
        queue_depth: u32,
    },
    Deliver {
        envelope: BridgeEnvelope,
    },
    Ack {
        message_ids: Vec<[u8; 16]>,
    },
    Ping,
    Pong,
    Error {
        message: String,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpPushRequest {
    pub sender: [u8; 32],
    pub signature: Vec<u8>,
    pub auth: BridgeAuth,
    pub envelope: BridgeEnvelope,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpPushResponse {
    pub disposition: BridgePushDisposition,
    pub queue_depth: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bridge_fingerprint: Option<[u8; 32]>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpPullRequest {
    pub peer_id: [u8; 32],
    pub signature: Vec<u8>,
    pub auth: BridgeAuth,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpPullResponse {
    pub envelopes: Vec<BridgeEnvelope>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bridge_fingerprint: Option<[u8; 32]>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpAckRequest {
    pub peer_id: [u8; 32],
    pub signature: Vec<u8>,
    pub auth: BridgeAuth,
    pub message_ids: Vec<[u8; 16]>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HttpAckResponse {
    pub remaining: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bridge_fingerprint: Option<[u8; 32]>,
}

pub fn register_challenge(peer_id: &[u8; 32], auth: &BridgeAuth) -> Result<Vec<u8>> {
    contextual_payload(BRIDGE_REGISTER_CONTEXT, &(peer_id, auth))
}

pub fn http_push_challenge(envelope: &BridgeEnvelope, auth: &BridgeAuth) -> Result<Vec<u8>> {
    contextual_payload(BRIDGE_HTTP_PUSH_CONTEXT, &(envelope, auth))
}

pub fn http_pull_challenge(peer_id: &[u8; 32], auth: &BridgeAuth) -> Result<Vec<u8>> {
    contextual_payload(BRIDGE_HTTP_PULL_CONTEXT, &(peer_id, auth))
}

pub fn http_ack_challenge(
    peer_id: &[u8; 32],
    message_ids: &[[u8; 16]],
    auth: &BridgeAuth,
) -> Result<Vec<u8>> {
    contextual_payload(BRIDGE_HTTP_ACK_CONTEXT, &(peer_id, message_ids, auth))
}

fn contextual_payload<T: Serialize>(prefix: &[u8], value: &T) -> Result<Vec<u8>> {
    let payload = bincode::serialize(value).map_err(ADAError::Serialization)?;
    let mut out = Vec::with_capacity(prefix.len() + payload.len());
    out.extend_from_slice(prefix);
    out.extend_from_slice(&payload);
    Ok(out)
}

fn serialize_frame(frame: &BridgeFrame, wire_format: BridgeWireFormat) -> Result<Vec<u8>> {
    match wire_format {
        BridgeWireFormat::Bincode => bincode::serialize(frame).map_err(ADAError::Serialization),
        BridgeWireFormat::Json => serde_json::to_vec(frame).map_err(ADAError::Json),
    }
}

fn deserialize_frame(bytes: &[u8], wire_format: BridgeWireFormat) -> Result<BridgeFrame> {
    match wire_format {
        BridgeWireFormat::Bincode => bincode::deserialize(bytes)
            .map_err(|e| ADAError::Bridge(format!("bridge frame decode: {}", e))),
        BridgeWireFormat::Json => serde_json::from_slice(bytes)
            .map_err(|e| ADAError::Bridge(format!("bridge frame json decode: {}", e))),
    }
}

pub async fn send_frame(
    connection: &mut BridgeConnection,
    frame: &BridgeFrame,
    wire_format: BridgeWireFormat,
) -> Result<()> {
    let bytes = serialize_frame(frame, wire_format)?;
    connection.send(&bytes).await
}

pub async fn recv_frame(
    connection: &mut BridgeConnection,
    wire_format: BridgeWireFormat,
) -> Result<BridgeFrame> {
    let bytes = connection.recv().await?;
    deserialize_frame(&bytes, wire_format)
}

pub fn verify_bridge_fingerprint(expected: &[u8; 32], actual: &[u8; 32]) -> Result<()> {
    if expected.iter().all(|b| *b == 0) || expected == actual {
        return Ok(());
    }
    Err(ADAError::Bridge("bridge fingerprint mismatch".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn le_u32(value: u32) -> [u8; 4] {
        value.to_le_bytes()
    }

    fn le_u64(value: u64) -> [u8; 8] {
        value.to_le_bytes()
    }

    fn test_auth() -> BridgeAuth {
        BridgeAuth {
            nonce: [7u8; 16],
            timestamp_ms: 11,
        }
    }

    fn test_envelope() -> BridgeEnvelope {
        BridgeEnvelope {
            message_id: [1u8; 16],
            sender: [2u8; 32],
            recipient: [3u8; 32],
            lane: BridgeDeliveryLane::FileChunk,
            wire_bytes: vec![4, 5, 6],
            created_at_ms: 9,
            expires_at: 10,
        }
    }

    #[test]
    fn delivery_class_maps_to_expected_lane() {
        assert_eq!(
            BridgeDeliveryLane::from(DeliveryClass::DirectMessage),
            BridgeDeliveryLane::TextDm
        );
        assert_eq!(
            BridgeDeliveryLane::from(DeliveryClass::FileMetadata),
            BridgeDeliveryLane::FileMetadata
        );
        assert_eq!(
            BridgeDeliveryLane::from(DeliveryClass::FileChunk),
            BridgeDeliveryLane::FileChunk
        );
        assert_eq!(
            BridgeDeliveryLane::from(DeliveryClass::CallSignaling),
            BridgeDeliveryLane::CallSignaling
        );
        assert_eq!(
            BridgeDeliveryLane::from(DeliveryClass::MaintenanceRetry),
            BridgeDeliveryLane::MaintenanceRetry
        );
    }

    #[test]
    fn fingerprint_verification_accepts_zero_or_match() {
        let actual = [4u8; 32];

        verify_bridge_fingerprint(&[0u8; 32], &actual)
            .expect("zero expected fingerprint should skip verification");
        verify_bridge_fingerprint(&actual, &actual).expect("matching fingerprint should verify");
    }

    #[test]
    fn fingerprint_verification_rejects_mismatch() {
        let err = verify_bridge_fingerprint(&[1u8; 32], &[2u8; 32])
            .expect_err("mismatch should fail verification");

        assert!(
            matches!(err, ADAError::Bridge(message) if message.contains("fingerprint mismatch"))
        );
    }

    #[test]
    fn json_wire_format_roundtrips_bridge_frame() {
        let frame = BridgeFrame::Error {
            message: "worker bridge json path".into(),
        };

        let bytes =
            serialize_frame(&frame, BridgeWireFormat::Json).expect("json frame should encode");
        let decoded =
            deserialize_frame(&bytes, BridgeWireFormat::Json).expect("json frame should decode");

        match decoded {
            BridgeFrame::Error { message } => assert_eq!(message, "worker bridge json path"),
            other => panic!("unexpected decoded frame: {:?}", other),
        }
    }

    #[test]
    fn http_auth_challenges_match_worker_manual_bincode_layout() {
        let auth = test_auth();
        let envelope = test_envelope();

        let mut push_expected = Vec::new();
        push_expected.extend_from_slice(BRIDGE_HTTP_PUSH_CONTEXT);
        push_expected.extend_from_slice(&[1u8; 16]);
        push_expected.extend_from_slice(&[2u8; 32]);
        push_expected.extend_from_slice(&[3u8; 32]);
        push_expected.extend_from_slice(&le_u32(2));
        push_expected.extend_from_slice(&le_u64(3));
        push_expected.extend_from_slice(&[4, 5, 6]);
        push_expected.extend_from_slice(&le_u64(9));
        push_expected.extend_from_slice(&le_u64(10));
        push_expected.extend_from_slice(&[7u8; 16]);
        push_expected.extend_from_slice(&le_u64(11));
        assert_eq!(
            http_push_challenge(&envelope, &auth).unwrap(),
            push_expected
        );

        let mut pull_expected = Vec::new();
        pull_expected.extend_from_slice(BRIDGE_HTTP_PULL_CONTEXT);
        pull_expected.extend_from_slice(&[8u8; 32]);
        pull_expected.extend_from_slice(&[7u8; 16]);
        pull_expected.extend_from_slice(&le_u64(11));
        assert_eq!(
            http_pull_challenge(&[8u8; 32], &auth).unwrap(),
            pull_expected
        );

        let message_ids = vec![[1u8; 16], [2u8; 16]];
        let mut ack_expected = Vec::new();
        ack_expected.extend_from_slice(BRIDGE_HTTP_ACK_CONTEXT);
        ack_expected.extend_from_slice(&[8u8; 32]);
        ack_expected.extend_from_slice(&le_u64(2));
        ack_expected.extend_from_slice(&[1u8; 16]);
        ack_expected.extend_from_slice(&[2u8; 16]);
        ack_expected.extend_from_slice(&[7u8; 16]);
        ack_expected.extend_from_slice(&le_u64(11));
        assert_eq!(
            http_ack_challenge(&[8u8; 32], &message_ids, &auth).unwrap(),
            ack_expected,
        );
    }
}
