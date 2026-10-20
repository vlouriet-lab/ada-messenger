use std::{env, fs, path::PathBuf};

use ada_core::bridge::manifest::SignedBridgeManifest;
use anyhow::{anyhow, Context};

fn usage() -> ! {
    eprintln!("Usage: verify_manifest <signed-manifest.json> [anchor-hex[,anchor-hex...]]");
    eprintln!("Environment fallback: ADA_BUILTIN_MANIFEST_PUBLIC_KEYS or ADA_BUILTIN_MANIFEST_ANCHORS");
    std::process::exit(2);
}

fn split_delimited_values(raw: &str) -> impl Iterator<Item = &str> {
    raw.split(|ch: char| ch == ',' || ch == ';' || ch.is_whitespace())
        .map(str::trim)
        .filter(|value| !value.is_empty())
}

fn decode_signing_anchors(raw: &str) -> anyhow::Result<Vec<[u8; 32]>> {
    let mut anchors = Vec::new();
    for item in split_delimited_values(raw) {
        let bytes = hex::decode(item.replace(':', ""))
            .with_context(|| format!("manifest signing anchor is not valid hex: {item}"))?;
        let anchor_bytes: [u8; 32] = bytes
            .try_into()
            .map_err(|_| anyhow!("manifest signing anchor must decode to exactly 32 bytes: {item}"))?;
        anchors.push(anchor_bytes);
    }
    if anchors.is_empty() {
        return Err(anyhow!("no manifest signing anchors provided"));
    }
    Ok(anchors)
}

fn main() -> anyhow::Result<()> {
    let mut args = env::args_os().skip(1);
    let input_path = match args.next() {
        Some(path) => PathBuf::from(path),
        None => usage(),
    };
    let anchors_raw = match args.next() {
        Some(value) => value.to_string_lossy().to_string(),
        None => env::var("ADA_BUILTIN_MANIFEST_ANCHORS")
            .or_else(|_| env::var("ADA_BUILTIN_MANIFEST_PUBLIC_KEYS"))
            .context("anchors argument missing and ADA_BUILTIN_MANIFEST_PUBLIC_KEYS is not set")?,
    };
    if args.next().is_some() {
        usage();
    }

    let manifest_json = fs::read_to_string(&input_path)
        .with_context(|| format!("failed to read {}", input_path.display()))?;
    let signed_manifest: SignedBridgeManifest = serde_json::from_str(manifest_json.trim_start_matches('\u{feff}'))
        .with_context(|| format!("failed to parse {}", input_path.display()))?;
    let trusted_anchors = decode_signing_anchors(&anchors_raw)?;
    signed_manifest.verify_signature(&trusted_anchors)?;
    let manifest_payload = signed_manifest.parse_payload()?;

    println!("manifest verified");
    println!("version: {}", manifest_payload.version);
    println!("ttl_secs: {}", manifest_payload.ttl_secs);
    println!("bridge count: {}", manifest_payload.bridges.len());
    for bridge in &manifest_payload.bridges {
        println!(
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