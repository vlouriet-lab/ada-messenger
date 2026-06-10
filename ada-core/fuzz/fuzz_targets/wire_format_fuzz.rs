#![no_main]
use libfuzzer_sys::fuzz_target;
use ada_core::messaging::types::{Message, MessageKind};
use ada_core::messaging::session::WireEnvelope;
use ada_core::bridge::mailbox::{BridgeFrame, BridgeEnvelope};
use ada_core::group::sender_keys::{SenderKeyMessage, SenderKeyDistribution};
use ada_core::mesh_handoff::{HandoffOffer, HandoffChunk};

fuzz_target!(|data: &[u8]| {
    // 1. Fuzz the inner Message deserialization pathway
    if let Ok(msg) = bincode::deserialize::<Message>(data) {
        let _ = msg.bytes_to_sign();
        match &msg.kind {
            MessageKind::Text(txt) => { let _ = txt.len(); }
            MessageKind::File { file_name, mime_type, .. } => {
                let _ = file_name.len();
                let _ = mime_type.len();
            }
            MessageKind::Reaction { emoji, .. } => { let _ = emoji.len(); }
            _ => {}
        }
    }

    // 2. Fuzz outermost Iroh WireEnvelope
    if let Ok(envelope) = bincode::deserialize::<WireEnvelope>(data) {
        match envelope {
            WireEnvelope::Dm(enc_wire) => {
                let _ = enc_wire.payload.len();
            }
            WireEnvelope::Group(skm) => {
                let _ = skm.ciphertext.len();
            }
            WireEnvelope::WebRtcProxy(proxy_bytes) => {
                let _ = proxy_bytes.len();
            }
        }
    }

    // 3. Fuzz Bridge Control / Transport Frames
    // Usually these come as JSON or Bincode depending on BridgeWireFormat,
    // but the backend deserializes it just like this:
    let _ = bincode::deserialize::<BridgeFrame>(data);
    let _ = serde_json::from_slice::<BridgeFrame>(data);

    // 4. Fuzz Group Distributions
    let _ = bincode::deserialize::<SenderKeyDistribution>(data);

    // 5. Fuzz Mesh Handoffs (Visual Auth / NFC Handoff sync)
    let _ = bincode::deserialize::<HandoffOffer>(data);
    let _ = bincode::deserialize::<HandoffChunk>(data);
});