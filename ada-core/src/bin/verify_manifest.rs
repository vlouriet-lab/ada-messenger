use std::{env, fs, path::PathBuf};

use ada_core::bridge::manifest::SignedBridgeManifest;
use anyhow::{anyhow, Context};

fn usage() -> ! {
    eprintln!("Usage: verify_manifest <signed-manifest.json> [public-key-hex[,public-key-hex...]]");
    eprintln!("Environment fallback: ADA_BUILTIN_MANIFEST_PUBLIC_KEYS=<32-byte hex key list>");
    std::process::exit(2);
}

fn split_key_values(raw: &str) -> impl Iterator<Item = &str> {
    raw.split(|ch: char| ch == ',' || ch == ';' || ch.is_whitespace())
        .map(str::trim)
        .filter(|value| !value.is_empty())
}

fn decode_public_keys(raw: &str) -> anyhow::Result<Vec<[u8; 32]>> {
    let mut keys = Vec::new();
    for key in split_key_values(raw) {
        let bytes = hex::decode(key.replace(':', ""))
            .with_context(|| format!("manifest public key is not valid hex: {key}"))?;
        let key_bytes: [u8; 32] = bytes
            .try_into()
            .map_err(|_| anyhow!("manifest public key must decode to exactly 32 bytes: {key}"))?;
        keys.push(key_bytes);
    }
    if keys.is_empty() {
        return Err(anyhow!("no manifest public keys provided"));
    }
    Ok(keys)
}

fn main() -> anyhow::Result<()> {
    let mut args = env::args_os().skip(1);
    let input_path = match args.next() {
        Some(path) => PathBuf::from(path),
        None => usage(),
    };
    let public_keys_raw = match args.next() {
        Some(value) => value.to_string_lossy().to_string(),
        None => env::var("ADA_BUILTIN_MANIFEST_PUBLIC_KEYS")
            .context("public keys argument missing and ADA_BUILTIN_MANIFEST_PUBLIC_KEYS is not set")?,
    };
    if args.next().is_some() {
        usage();
    }

    let manifest_json = fs::read_to_string(&input_path)
        .with_context(|| format!("failed to read {}", input_path.display()))?;
    let signed_manifest: SignedBridgeManifest = serde_json::from_str(manifest_json.trim_start_matches('\u{feff}'))
        .with_context(|| format!("failed to parse {}", input_path.display()))?;
    let public_keys = decode_public_keys(&public_keys_raw)?;
    let payload = signed_manifest.verify(&public_keys)?;

    eprintln!("manifest verified");
    eprintln!("version: {}", payload.version);
    eprintln!("ttl_secs: {}", payload.ttl_secs);
    eprintln!("bridge count: {}", payload.bridges.len());
    for bridge in payload.bridges {
        eprintln!(
            "bridge: id={} protocol={} host={} port={} priority={} active={}",
            bridge.id,
            bridge.protocol,
            bridge.hostname.as_deref().unwrap_or(&bridge.address),
            bridge.port,
            bridge.priority,
            bridge.is_active,
        );
    }
    Ok(())
}