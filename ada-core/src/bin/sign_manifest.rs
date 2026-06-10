use std::{env, fs, path::PathBuf};

use ada_core::bridge::manifest::BridgeManifestPayload;
use anyhow::{anyhow, Context};
use ed25519_dalek::SigningKey;

fn usage() -> ! {
    eprintln!("Usage: sign_manifest <payload.json> [output.json]");
    eprintln!("Environment: ADA_BRIDGE_SIGNING_SEED=<64 hex chars>");
    std::process::exit(2);
}

fn load_signing_key() -> anyhow::Result<SigningKey> {
    let seed_hex =
        env::var("ADA_BRIDGE_SIGNING_SEED").context("ADA_BRIDGE_SIGNING_SEED is not set")?;
    let seed_bytes =
        hex::decode(seed_hex.trim()).context("ADA_BRIDGE_SIGNING_SEED is not valid hex")?;
    let seed: [u8; 32] = seed_bytes
        .try_into()
        .map_err(|_| anyhow!("ADA_BRIDGE_SIGNING_SEED must decode to exactly 32 bytes"))?;
    Ok(SigningKey::from_bytes(&seed))
}

fn main() -> anyhow::Result<()> {
    let mut args = env::args_os().skip(1);
    let input_path = match args.next() {
        Some(path) => PathBuf::from(path),
        None => usage(),
    };
    let output_path = args.next().map(PathBuf::from);
    if args.next().is_some() {
        usage();
    }

    let payload_json = fs::read_to_string(&input_path)
        .with_context(|| format!("failed to read {}", input_path.display()))?;
    let payload: BridgeManifestPayload =
        serde_json::from_str(payload_json.trim_start_matches('\u{feff}')).with_context(|| {
            format!(
                "failed to parse {} as BridgeManifestPayload",
                input_path.display()
            )
        })?;

    let signing_key = load_signing_key()?;
    let manifest = payload.to_signed(&signing_key)?;
    let manifest_json = serde_json::to_string_pretty(&manifest)?;

    if let Some(path) = output_path {
        fs::write(&path, &manifest_json)
            .with_context(|| format!("failed to write {}", path.display()))?;
        eprintln!("wrote signed manifest to {}", path.display());
    } else {
        println!("{manifest_json}");
    }

    eprintln!(
        "manifest public key: {}",
        hex::encode(signing_key.verifying_key().to_bytes())
    );
    eprintln!("bridge count: {}", payload.bridges.len());
    Ok(())
}
