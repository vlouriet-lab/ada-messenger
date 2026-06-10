use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};

use crate::{
    bridge::bridge::{
        BridgeConfig as RuntimeBridgeConfig, BridgeProtocol, BridgeSource, BridgeWireFormat,
    },
    error::{ADAError, Result},
};

fn clean_required_field<'a>(value: &'a str, field_name: &str) -> Result<&'a str> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Err(ADAError::Bridge(format!(
            "manifest {} must not be empty",
            field_name
        )));
    }
    if trimmed
        .chars()
        .any(|ch| ch.is_control() || ch.is_whitespace())
    {
        return Err(ADAError::Bridge(format!(
            "manifest {} must not contain whitespace or control characters",
            field_name
        )));
    }
    Ok(trimmed)
}

fn optional_clean_field(value: &Option<String>, field_name: &str) -> Result<()> {
    if let Some(value) = value {
        clean_required_field(value, field_name)?;
    }
    Ok(())
}

fn decode_32_byte_hex(
    value: &str,
    invalid_message: &str,
    length_message: &str,
) -> Result<[u8; 32]> {
    let bytes = hex::decode(value.replace(':', ""))
        .map_err(|_| ADAError::Bridge(invalid_message.into()))?;
    let array: [u8; 32] = bytes
        .try_into()
        .map_err(|_| ADAError::Bridge(length_message.into()))?;
    Ok(array)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BridgeManifestPayload {
    pub version: u64,
    pub issued_at_ms: u64,
    pub ttl_secs: u64,
    #[serde(default)]
    pub max_attachment_bytes: Option<u64>,
    #[serde(default)]
    pub supports_realtime_calls: bool,
    #[serde(default)]
    pub bridges: Vec<ManifestBridgeEntry>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ManifestBridgeEntry {
    pub id: String,
    pub address: String,
    pub port: u16,
    pub protocol: String,
    #[serde(default)]
    pub hostname: Option<String>,
    #[serde(default)]
    pub insecure: bool,
    pub fingerprint_hex: String,
    #[serde(default)]
    pub shared_secret_hex: Option<String>,
    #[serde(default)]
    pub priority: u8,
    #[serde(default = "default_manifest_bridge_active")]
    pub is_active: bool,
    #[serde(default)]
    pub front_domain: Option<String>,
    #[serde(default)]
    pub front_url: Option<String>,
    #[serde(default)]
    pub wire_format: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SignedBridgeManifest {
    pub payload_json: String,
    pub signature_hex: String,
}

fn default_manifest_bridge_active() -> bool {
    true
}

impl SignedBridgeManifest {
    pub fn verify(&self, trusted_public_keys: &[[u8; 32]]) -> Result<BridgeManifestPayload> {
        if trusted_public_keys.is_empty() {
            return Err(ADAError::Bridge(
                "no trusted manifest public keys configured".into(),
            ));
        }

        let sig_bytes = hex::decode(&self.signature_hex)
            .map_err(|_| ADAError::Bridge("manifest signature is not valid hex".into()))?;
        let sig_arr: [u8; 64] = sig_bytes
            .try_into()
            .map_err(|_| ADAError::Bridge("manifest signature has invalid length".into()))?;
        let signature = Signature::from_bytes(&sig_arr);

        let payload_bytes = self.payload_json.as_bytes();
        let mut verified = false;
        for key_bytes in trusted_public_keys {
            let key = VerifyingKey::from_bytes(key_bytes)
                .map_err(|_| ADAError::Bridge("invalid trusted manifest public key".into()))?;
            if key.verify(payload_bytes, &signature).is_ok() {
                verified = true;
                break;
            }
        }
        if !verified {
            return Err(ADAError::InvalidSignature);
        }

        let payload: BridgeManifestPayload =
            serde_json::from_str(&self.payload_json).map_err(ADAError::Json)?;
        payload.validate()?;
        Ok(payload)
    }
}

impl BridgeManifestPayload {
    pub fn validate(&self) -> Result<()> {
        if self.version == 0 {
            return Err(ADAError::Bridge(
                "manifest version must be greater than zero".into(),
            ));
        }
        if self.issued_at_ms == 0 {
            return Err(ADAError::Bridge(
                "manifest issued_at_ms must be greater than zero".into(),
            ));
        }
        if self.ttl_secs == 0 {
            return Err(ADAError::Bridge(
                "manifest ttl_secs must be greater than zero".into(),
            ));
        }
        if self.max_attachment_bytes == Some(0) {
            return Err(ADAError::Bridge(
                "manifest max_attachment_bytes must be greater than zero".into(),
            ));
        }

        let mut ids = std::collections::HashSet::new();
        for entry in &self.bridges {
            entry.validate()?;
            let id = entry.id.trim().to_string();
            if !ids.insert(id.clone()) {
                return Err(ADAError::Bridge(format!(
                    "manifest bridge id {} is duplicated",
                    id
                )));
            }
        }

        Ok(())
    }

    pub fn to_signed(&self, signing_key: &SigningKey) -> Result<SignedBridgeManifest> {
        self.validate()?;
        let payload_json = serde_json::to_string(self).map_err(ADAError::Json)?;
        let signature = signing_key.sign(payload_json.as_bytes());
        Ok(SignedBridgeManifest {
            payload_json,
            signature_hex: hex::encode(signature.to_bytes()),
        })
    }
}

impl ManifestBridgeEntry {
    pub fn validate(&self) -> Result<()> {
        clean_required_field(&self.id, "bridge id")?;
        clean_required_field(&self.address, "bridge address")?;
        if self.port == 0 {
            return Err(ADAError::Bridge(
                "manifest bridge port must be greater than zero".into(),
            ));
        }
        let protocol = clean_required_field(&self.protocol, "bridge protocol")?;

        match protocol {
            "obfs4" | "websocket" | "websocket_tls" | "tcp" | "tcp_direct" => {}
            "fronting" | "domain_fronting" => {
                let front_domain = self.front_domain.as_ref().ok_or_else(|| {
                    ADAError::Bridge("manifest domain-front bridge requires front_domain".into())
                })?;
                clean_required_field(front_domain, "bridge front_domain")?;
            }
            "meek" => {
                let front_url = self.front_url.as_ref().ok_or_else(|| {
                    ADAError::Bridge("manifest meek bridge requires front_url".into())
                })?;
                let trimmed = front_url.trim();
                if !trimmed.starts_with("https://") {
                    return Err(ADAError::Bridge(
                        "manifest meek front_url must use https".into(),
                    ));
                }
                if trimmed
                    .chars()
                    .any(|ch| ch.is_control() || ch.is_whitespace())
                {
                    return Err(ADAError::Bridge(
                        "manifest bridge front_url must not contain whitespace or control characters".into(),
                    ));
                }
            }
            other => {
                return Err(ADAError::Bridge(format!(
                    "unsupported manifest bridge protocol {}",
                    other
                )));
            }
        }

        optional_clean_field(&self.hostname, "bridge hostname")?;
        optional_clean_field(&self.front_domain, "bridge front_domain")?;
        if let Some(front_url) = &self.front_url {
            let trimmed = front_url.trim();
            if trimmed.is_empty()
                || trimmed
                    .chars()
                    .any(|ch| ch.is_control() || ch.is_whitespace())
            {
                return Err(ADAError::Bridge(
                    "manifest bridge front_url must not be empty or contain whitespace".into(),
                ));
            }
        }

        let fingerprint = decode_32_byte_hex(
            &self.fingerprint_hex,
            "manifest fingerprint is not valid hex",
            "manifest fingerprint must be 32 bytes",
        )?;
        if fingerprint == [0u8; 32] {
            return Err(ADAError::Bridge(
                "manifest fingerprint must not be all zero".into(),
            ));
        }
        if let Some(secret_hex) = &self.shared_secret_hex {
            decode_32_byte_hex(
                secret_hex,
                "manifest shared_secret is not valid hex",
                "manifest shared_secret must be 32 bytes",
            )?;
        }

        match self.wire_format.as_deref() {
            None | Some("bincode") | Some("json") => {}
            Some(other) => {
                return Err(ADAError::Bridge(format!(
                    "unsupported manifest wire format {}",
                    other
                )));
            }
        }

        Ok(())
    }

    pub fn to_runtime_bridge(&self) -> Result<RuntimeBridgeConfig> {
        self.validate()?;
        let protocol = match self.protocol.as_str() {
            "obfs4" => BridgeProtocol::Obfs4,
            "websocket" | "websocket_tls" => BridgeProtocol::WebSocketTLS,
            "fronting" | "domain_fronting" => BridgeProtocol::DomainFronting {
                front_domain: self.front_domain.clone().unwrap_or_default(),
            },
            "meek" => BridgeProtocol::Meek {
                front_url: self.front_url.clone().unwrap_or_default(),
            },
            "tcp" | "tcp_direct" => BridgeProtocol::TcpDirect,
            other => {
                return Err(ADAError::Bridge(format!(
                    "unsupported manifest bridge protocol {}",
                    other
                )));
            }
        };

        let fingerprint = decode_32_byte_hex(
            &self.fingerprint_hex,
            "manifest fingerprint is not valid hex",
            "manifest fingerprint must be 32 bytes",
        )?;

        let shared_secret = match &self.shared_secret_hex {
            Some(secret_hex) => Some(decode_32_byte_hex(
                secret_hex,
                "manifest shared_secret is not valid hex",
                "manifest shared_secret must be 32 bytes",
            )?),
            None => None,
        };

        let wire_format = match self.wire_format.as_deref() {
            None | Some("bincode") => BridgeWireFormat::Bincode,
            Some("json") => BridgeWireFormat::Json,
            Some(other) => {
                return Err(ADAError::Bridge(format!(
                    "unsupported manifest wire format {}",
                    other
                )));
            }
        };

        Ok(RuntimeBridgeConfig {
            id: self.id.clone(),
            address: self.address.clone(),
            port: self.port,
            protocol,
            fingerprint,
            shared_secret,
            priority: self.priority,
            is_active: self.is_active,
            hostname: self.hostname.clone(),
            insecure: self.insecure,
            wire_format,
            source: BridgeSource::Manifest,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_entry() -> ManifestBridgeEntry {
        ManifestBridgeEntry {
            id: "bridge-a".into(),
            address: "127.0.0.1".into(),
            port: 443,
            protocol: "domain_fronting".into(),
            hostname: Some("front.example".into()),
            insecure: false,
            fingerprint_hex: hex::encode([9u8; 32]),
            shared_secret_hex: Some(hex::encode([5u8; 32])),
            priority: 7,
            is_active: true,
            front_domain: Some("cdn.example".into()),
            front_url: None,
            wire_format: None,
        }
    }

    fn sample_payload() -> BridgeManifestPayload {
        BridgeManifestPayload {
            version: 3,
            issued_at_ms: 1_234,
            ttl_secs: 3_600,
            max_attachment_bytes: Some(65_536),
            supports_realtime_calls: false,
            bridges: vec![sample_entry()],
        }
    }

    #[test]
    fn manifest_roundtrip_signs_and_verifies() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let payload = sample_payload();

        let signed = payload
            .to_signed(&signing_key)
            .expect("manifest should sign");
        let verified = signed
            .verify(&[signing_key.verifying_key().to_bytes()])
            .expect("manifest should verify");

        assert_eq!(verified.version, payload.version);
        assert_eq!(verified.max_attachment_bytes, payload.max_attachment_bytes);
        assert_eq!(
            verified.supports_realtime_calls,
            payload.supports_realtime_calls
        );
        assert_eq!(verified.bridges.len(), 1);
        assert_eq!(verified.bridges[0].id, "bridge-a");
    }

    #[test]
    fn manifest_rejects_untrusted_signer() {
        let signing_key = SigningKey::from_bytes(&[7u8; 32]);
        let other_key = SigningKey::from_bytes(&[8u8; 32]);
        let signed = sample_payload()
            .to_signed(&signing_key)
            .expect("manifest should sign");

        let err = signed
            .verify(&[other_key.verifying_key().to_bytes()])
            .expect_err("manifest should reject unknown signer");

        assert!(matches!(err, ADAError::InvalidSignature));
    }

    #[test]
    fn manifest_entry_converts_to_runtime_bridge() {
        let runtime = sample_entry()
            .to_runtime_bridge()
            .expect("entry should convert to runtime bridge");

        assert_eq!(runtime.id, "bridge-a");
        assert_eq!(runtime.address, "127.0.0.1");
        assert_eq!(runtime.port, 443);
        assert_eq!(runtime.priority, 7);
        assert_eq!(runtime.hostname.as_deref(), Some("front.example"));
        assert_eq!(runtime.shared_secret, Some([5u8; 32]));
        assert_eq!(runtime.source, BridgeSource::Manifest);
        assert_eq!(runtime.wire_format, BridgeWireFormat::Bincode);
        assert!(matches!(
            &runtime.protocol,
            BridgeProtocol::DomainFronting { front_domain } if front_domain == "cdn.example"
        ));
    }

    #[test]
    fn manifest_entry_supports_json_wire_format() {
        let mut entry = sample_entry();
        entry.protocol = "websocket".into();
        entry.front_domain = None;
        entry.hostname = Some("ada-edge.example.workers.dev".into());
        entry.wire_format = Some("json".into());

        let runtime = entry
            .to_runtime_bridge()
            .expect("json wire format should parse");

        assert_eq!(runtime.wire_format, BridgeWireFormat::Json);
        assert!(matches!(runtime.protocol, BridgeProtocol::WebSocketTLS));
    }

    #[test]
    fn manifest_validation_rejects_missing_domain_front_field() {
        let mut entry = sample_entry();
        entry.front_domain = None;

        let err = entry
            .validate()
            .expect_err("domain fronting without front_domain should fail");

        assert!(
            matches!(err, ADAError::Bridge(message) if message.contains("requires front_domain"))
        );
    }

    #[test]
    fn manifest_validation_rejects_duplicate_bridge_ids() {
        let mut first = sample_entry();
        first.id = "dup".into();
        let mut second = sample_entry();
        second.id = "dup".into();

        let payload = BridgeManifestPayload {
            bridges: vec![first, second],
            ..sample_payload()
        };

        let err = payload
            .validate()
            .expect_err("duplicate bridge IDs should fail");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("duplicated")));
    }

    #[test]
    fn manifest_validation_rejects_zero_ttl() {
        let payload = BridgeManifestPayload {
            ttl_secs: 0,
            ..sample_payload()
        };

        let err = payload.validate().expect_err("zero ttl should fail");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("ttl_secs")));
    }

    #[test]
    fn manifest_validation_rejects_zero_fingerprint() {
        let mut entry = sample_entry();
        entry.fingerprint_hex = hex::encode([0u8; 32]);

        let err = entry.validate().expect_err("zero fingerprint should fail");

        assert!(matches!(err, ADAError::Bridge(message) if message.contains("fingerprint")));
    }
}
