use std::env;

use anyhow::{anyhow, Context};
use ed25519_dalek::SigningKey;

fn main() -> anyhow::Result<()> {
    let seed_hex = env::var("ADA_BRIDGE_SIGNING_SEED")
        .context("ADA_BRIDGE_SIGNING_SEED is not set")?;
    let seed_bytes = hex::decode(seed_hex.trim())
        .context("ADA_BRIDGE_SIGNING_SEED is not valid hex")?;
    let seed: [u8; 32] = seed_bytes
        .try_into()
        .map_err(|_| anyhow!("ADA_BRIDGE_SIGNING_SEED must decode to exactly 32 bytes"))?;
    let signing_key = SigningKey::from_bytes(&seed);
    println!("{}", hex::encode(signing_key.verifying_key().to_bytes()));
    Ok(())
}