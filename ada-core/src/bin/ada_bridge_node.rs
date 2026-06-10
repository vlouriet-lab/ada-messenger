use std::net::SocketAddr;

use ada_core::bridge::{
    manifest::BridgeManifestPayload, server::BridgeServerState, ManifestBridgeEntry,
};
use ed25519_dalek::SigningKey;

fn unix_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let bind: SocketAddr = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "127.0.0.1:8787".to_string())
        .parse()?;

    let seed_hex = std::env::var("ADA_BRIDGE_SIGNING_SEED").unwrap_or_else(|_| {
        "0101010101010101010101010101010101010101010101010101010101010101".to_string()
    });
    let seed_bytes = hex::decode(seed_hex)?;
    let seed: [u8; 32] = seed_bytes
        .try_into()
        .map_err(|_| anyhow::anyhow!("ADA_BRIDGE_SIGNING_SEED must be 32 bytes"))?;
    let signing_key = SigningKey::from_bytes(&seed);
    let fingerprint = signing_key.verifying_key().to_bytes();

    let payload = BridgeManifestPayload {
        version: 1,
        issued_at_ms: unix_now_ms(),
        ttl_secs: 24 * 3600,
        max_attachment_bytes: Some(256 * 1024),
        supports_realtime_calls: false,
        bridges: vec![ManifestBridgeEntry {
            id: "local-ws-bridge".to_string(),
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
    };
    let manifest = payload.to_signed(&signing_key)?;

    let state = BridgeServerState::new(manifest, fingerprint, 1024);
    let listener = tokio::net::TcpListener::bind(bind).await?;
    println!("ada-bridge-node listening on {}", bind);
    println!("bridge fingerprint: {}", hex::encode(fingerprint));
    println!("ops status: http://{}/ops/status", bind);
    println!("healthz: http://{}/healthz", bind);
    state.serve(listener).await?;
    Ok(())
}
