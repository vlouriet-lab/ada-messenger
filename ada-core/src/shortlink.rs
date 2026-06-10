//! Opaque contact-link encoding for the ADA short-link scheme.
//!
//! Converts `ada://s/<base64url>` ↔ contact-card JSON.
//!
//! # Encoding
//!
//! **v2 (current):** the contact card fields are packed into a compact binary
//! representation before encryption — roughly 200 bytes instead of ~350 bytes
//! of JSON.  This cuts the final URL length by ≈35 %.
//!
//! **v1 (legacy):** the full JSON was encrypted directly.  `decode` still
//! accepts v1 tokens transparently (auto-detected: decrypted bytes starting
//! with `{` are treated as JSON).
//!
//! # Security model
//!
//! The token is XChaCha20-Poly1305 ciphertext with a per-link random nonce.
//! The symmetric key is **compiled into every ADA binary** — all instances share
//! it — so any ADA app can decode any ADA contact link.
//!
//! **Goal: obfuscation**, not secrecy.  The raw peer ID and key material are
//! invisible to:
//! - QR-code scanners that can't run ADA
//! - Third parties who see the link in plain text
//! - Shoulder-surfing observers

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce};
use rand::RngCore;

// ── Compile-time symmetric key ────────────────────────────────────────────────

/// App-wide symmetric key compiled into every ADA binary.
/// Not a per-user secret — its purpose is encoding obfuscation only.
const LINK_KEY: [u8; 32] = [
    0x61, 0x64, 0x61, 0x5f, 0x73, 0x68, 0x6f, 0x72, // "ada_shor"
    0x74, 0x6c, 0x69, 0x6e, 0x6b, 0x5f, 0x76, 0x31, // "tlink_v1"
    0x5f, 0x49, 0x4e, 0x54, 0x45, 0x52, 0x4e, 0x41, // "_INTERNA"
    0x4c, 0x5f, 0x4b, 0x45, 0x59, 0x5f, 0x30, 0x30, // "L_KEY_00"
];

const NONCE_LEN: usize = 24;

/// URL prefix used for the short-link scheme.
pub const PREFIX: &str = "ada://s/";

// ── Binary packing ────────────────────────────────────────────────────────────
//
// Layout (all multi-byte integers are little-endian):
//
//   offset  len  field
//   ──────  ───  ──────────────────────────
//   0       1    card version (2 | 3)
//   1       1    flags  (bit 0 = has_opk, bit 1 = has_ephemeral_ik)
//   2       32   peer_id  (Ed25519 VK)
//   34      32   ik       (X25519 DH pub)
//   66      32   spk      (X25519 SPK pub)
//   98      64   spk_sig  (Ed25519 sig of spk)
//   162     1    name_len (0–255)
//   163     N    name     (UTF-8, N = name_len)
//   163+N   …    [if has_opk: 32 opk_pub + 4 opk_id(LE)]
//                [if has_ephemeral_ik: 32 ephemeral_ik]

const FLAG_HAS_OPK: u8 = 0x01;
const FLAG_HAS_EPHEMERAL_IK: u8 = 0x02;

/// Pack a `PatternContactCard` into compact bytes.
fn pack_card(card: &crate::pattern_auth::PatternContactCard) -> Option<Vec<u8>> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;

    // Preserve relay_url exactly by falling back to legacy JSON encoding.
    // The compact binary format currently has no field for it.
    if !card.relay_url.is_empty() {
        return None;
    }

    let peer_id: [u8; 32] = b64.decode(&card.id).ok()?.try_into().ok()?;
    let ik: [u8; 32] = b64.decode(&card.ik).ok()?.try_into().ok()?;
    let spk: [u8; 32] = b64.decode(&card.spk).ok()?.try_into().ok()?;
    let spk_sig: Vec<u8> = b64.decode(&card.spk_sig).ok()?;
    if spk_sig.len() != 64 {
        return None;
    }

    let has_opk = !card.opk.is_empty() && !card.opk_id.is_empty();
    let has_eph = !card.ephemeral_ik.is_empty();

    let mut flags: u8 = 0;
    if has_opk {
        flags |= FLAG_HAS_OPK;
    }
    if has_eph {
        flags |= FLAG_HAS_EPHEMERAL_IK;
    }

    let name_bytes = card.name.as_bytes();
    if name_bytes.len() > 255 {
        return None;
    }

    let capacity = 2
        + 32
        + 32
        + 32
        + 64
        + 1
        + name_bytes.len()
        + if has_opk { 36 } else { 0 }
        + if has_eph { 32 } else { 0 };

    let mut buf = Vec::with_capacity(capacity);
    buf.push(card.v);
    buf.push(flags);
    buf.extend_from_slice(&peer_id);
    buf.extend_from_slice(&ik);
    buf.extend_from_slice(&spk);
    buf.extend_from_slice(&spk_sig);
    buf.push(name_bytes.len() as u8);
    buf.extend_from_slice(name_bytes);

    if has_opk {
        let opk_pub: [u8; 32] = b64.decode(&card.opk).ok()?.try_into().ok()?;
        let opk_id: u32 = card.opk_id.parse().ok()?;
        buf.extend_from_slice(&opk_pub);
        buf.extend_from_slice(&opk_id.to_le_bytes());
    }
    if has_eph {
        let eph: [u8; 32] = b64.decode(&card.ephemeral_ik).ok()?.try_into().ok()?;
        buf.extend_from_slice(&eph);
    }

    Some(buf)
}

/// Unpack compact bytes back into a `PatternContactCard`.
fn unpack_card(data: &[u8]) -> Option<crate::pattern_auth::PatternContactCard> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;

    if data.len() < 163 {
        return None;
    }

    let version = data[0];
    let flags = data[1];

    let peer_id: [u8; 32] = data[2..34].try_into().ok()?;
    let ik: [u8; 32] = data[34..66].try_into().ok()?;
    let spk: [u8; 32] = data[66..98].try_into().ok()?;
    let spk_sig: &[u8] = &data[98..162];

    let name_len = data[162] as usize;
    if data.len() < 163 + name_len {
        return None;
    }
    let name = std::str::from_utf8(&data[163..163 + name_len]).ok()?;

    let mut cursor = 163 + name_len;

    let (opk, opk_id_str) = if flags & FLAG_HAS_OPK != 0 {
        if data.len() < cursor + 36 {
            return None;
        }
        let opk_pub: [u8; 32] = data[cursor..cursor + 32].try_into().ok()?;
        let opk_id = u32::from_le_bytes(data[cursor + 32..cursor + 36].try_into().ok()?);
        cursor += 36;
        (b64.encode(opk_pub), opk_id.to_string())
    } else {
        (String::new(), String::new())
    };

    let ephemeral_ik = if flags & FLAG_HAS_EPHEMERAL_IK != 0 {
        if data.len() < cursor + 32 {
            return None;
        }
        let eph: [u8; 32] = data[cursor..cursor + 32].try_into().ok()?;
        b64.encode(eph)
    } else {
        String::new()
    };

    Some(crate::pattern_auth::PatternContactCard {
        v: version,
        id: b64.encode(peer_id),
        ik: b64.encode(ik),
        spk: b64.encode(spk),
        spk_sig: b64.encode(spk_sig),
        name: name.to_string(),
        relay_url: String::new(),
        opk,
        opk_id: opk_id_str,
        ephemeral_ik,
    })
}

// ── Internal helpers ──────────────────────────────────────────────────────────

fn cipher() -> XChaCha20Poly1305 {
    XChaCha20Poly1305::new_from_slice(&LINK_KEY).expect("key is exactly 32 bytes")
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Encode a contact-card JSON string to an opaque `ada://s/<token>` URL.
///
/// The card is parsed, binary-packed (≈200 B instead of ≈350 B of JSON),
/// then encrypted with XChaCha20-Poly1305.
///
/// Token format: `base64url( nonce(24) || xchacha20poly1305(packed_binary) )`
pub fn encode(json: &str) -> String {
    // Try compact binary packing; fall back to raw JSON on parse failure.
    let plaintext = match serde_json::from_str::<crate::pattern_auth::PatternContactCard>(json) {
        Ok(card) => pack_card(&card).unwrap_or_else(|| json.as_bytes().to_vec()),
        Err(_) => json.as_bytes().to_vec(),
    };

    let mut nonce_bytes = [0u8; NONCE_LEN];
    rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = XNonce::from_slice(&nonce_bytes);

    let ct = cipher()
        .encrypt(nonce, plaintext.as_slice())
        .expect("chacha20poly1305 encrypt");

    let mut payload = Vec::with_capacity(NONCE_LEN + ct.len());
    payload.extend_from_slice(&nonce_bytes);
    payload.extend_from_slice(&ct);

    format!("{}{}", PREFIX, URL_SAFE_NO_PAD.encode(&payload))
}

/// Decode an `ada://s/<token>` URL back to the contact-card JSON string.
///
/// Transparently handles both compact binary tokens (v2) and legacy
/// JSON-encrypted tokens (v1).
///
/// Returns `None` if:
/// - The URL does not start with `ada://s/`
/// - The base64url token is malformed
/// - The authentication tag doesn't match (tampered or truncated data)
/// - The decrypted bytes cannot be parsed as binary card or valid UTF-8 JSON
pub fn decode(url: &str) -> Option<String> {
    let token = url.strip_prefix(PREFIX)?;
    let payload = URL_SAFE_NO_PAD.decode(token).ok()?;
    if payload.len() <= NONCE_LEN {
        return None;
    }
    let nonce = XNonce::from_slice(&payload[..NONCE_LEN]);
    let pt = cipher().decrypt(nonce, &payload[NONCE_LEN..]).ok()?;

    // Auto-detect format: JSON starts with '{', binary starts with version byte.
    if pt.first() == Some(&b'{') {
        // Legacy v1: raw JSON
        String::from_utf8(pt).ok()
    } else {
        // v2 compact binary → reconstruct JSON
        let card = unpack_card(&pt)?;
        serde_json::to_string(&card).ok()
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a realistic v2 contact card JSON with all key fields populated.
    fn sample_v2_card_json(name: &str, with_opk: bool) -> String {
        use base64::Engine;
        let b64 = base64::engine::general_purpose::STANDARD;
        let peer_id = [0xAA_u8; 32];
        let ik = [0xBB_u8; 32];
        let spk = [0xCC_u8; 32];
        let spk_sig = [0xDD_u8; 64];
        let mut json = format!(
            r#"{{"v":2,"id":"{}","spk":"{}","ik":"{}","spk_sig":"{}","name":"{}""#,
            b64.encode(peer_id),
            b64.encode(spk),
            b64.encode(ik),
            b64.encode(spk_sig),
            name,
        );
        if with_opk {
            let opk = [0xEE_u8; 32];
            json.push_str(&format!(r#","opk":"{}","opk_id":"42""#, b64.encode(opk)));
        }
        json.push('}');
        json
    }

    #[test]
    fn compact_round_trip_v2() {
        let json = sample_v2_card_json("Alice", false);
        let url = encode(&json);
        assert!(url.starts_with(PREFIX));
        // Decode should produce equivalent JSON (field order may differ).
        let decoded_json = decode(&url).expect("decode must succeed");
        let orig: serde_json::Value = serde_json::from_str(&json).unwrap();
        let dec: serde_json::Value = serde_json::from_str(&decoded_json).unwrap();
        assert_eq!(orig, dec);
    }

    #[test]
    fn compact_round_trip_with_opk() {
        let json = sample_v2_card_json("Bob", true);
        let url = encode(&json);
        let decoded_json = decode(&url).expect("decode must succeed");
        let orig: serde_json::Value = serde_json::from_str(&json).unwrap();
        let dec: serde_json::Value = serde_json::from_str(&decoded_json).unwrap();
        assert_eq!(orig, dec);
    }

    #[test]
    fn relay_url_round_trip_uses_json_fallback() {
        let json = r#"{"v":2,"id":"qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqo=","spk":"zMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMw=","ik":"u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7u7s=","spk_sig":"3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3Q==","name":"Alice","relay_url":"https://euw1-1.relay.iroh.network./"}"#;
        let url = encode(json);
        let decoded_json = decode(&url).expect("decode must succeed");
        let orig: serde_json::Value = serde_json::from_str(json).unwrap();
        let dec: serde_json::Value = serde_json::from_str(&decoded_json).unwrap();
        assert_eq!(orig, dec);
    }

    #[test]
    fn compact_is_shorter_than_legacy() {
        let json = sample_v2_card_json("Alice", true);
        let compact_url = encode(&json);

        // Manually produce a legacy (raw-JSON) token for comparison.
        let mut nonce_bytes = [0u8; NONCE_LEN];
        rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = XNonce::from_slice(&nonce_bytes);
        let ct = cipher().encrypt(nonce, json.as_bytes()).unwrap();
        let mut payload = Vec::with_capacity(NONCE_LEN + ct.len());
        payload.extend_from_slice(&nonce_bytes);
        payload.extend_from_slice(&ct);
        let legacy_url = format!("{}{}", PREFIX, URL_SAFE_NO_PAD.encode(&payload));

        assert!(
            compact_url.len() < legacy_url.len(),
            "compact {} chars should be shorter than legacy {} chars",
            compact_url.len(),
            legacy_url.len(),
        );
    }

    #[test]
    fn legacy_json_still_decodes() {
        // Simulate a v1 token: encrypt raw JSON directly.
        let json = r#"{"id":"abc123","name":"Alice","ver":1}"#;
        let mut nonce_bytes = [0u8; NONCE_LEN];
        rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = XNonce::from_slice(&nonce_bytes);
        let ct = cipher().encrypt(nonce, json.as_bytes()).unwrap();
        let mut payload = Vec::with_capacity(NONCE_LEN + ct.len());
        payload.extend_from_slice(&nonce_bytes);
        payload.extend_from_slice(&ct);
        let legacy_url = format!("{}{}", PREFIX, URL_SAFE_NO_PAD.encode(&payload));
        assert_eq!(decode(&legacy_url).as_deref(), Some(json));
    }

    #[test]
    fn each_encode_is_unique() {
        let json = sample_v2_card_json("X", false);
        let a = encode(&json);
        let b = encode(&json);
        assert_ne!(
            a, b,
            "two encodes of the same JSON must differ (random nonce)"
        );
        // But both must decode to the same card.
        let da: serde_json::Value = serde_json::from_str(&decode(&a).unwrap()).unwrap();
        let db: serde_json::Value = serde_json::from_str(&decode(&b).unwrap()).unwrap();
        assert_eq!(da, db);
    }

    #[test]
    fn tamper_detected() {
        let json = sample_v2_card_json("Eve", false);
        let mut url = encode(&json);
        let last = url.pop().unwrap();
        url.push(if last == 'A' { 'B' } else { 'A' });
        assert!(decode(&url).is_none(), "tampered token must not decode");
    }

    #[test]
    fn wrong_prefix_rejected() {
        let json = sample_v2_card_json("A", false);
        let url = encode(&json);
        let broken = url.replacen("ada://s/", "ada://x/", 1);
        assert!(decode(&broken).is_none());
    }

    #[test]
    fn too_short_rejected() {
        assert!(decode("ada://s/").is_none());
        assert!(decode("ada://s/aGVsbG8=").is_none());
    }
}
