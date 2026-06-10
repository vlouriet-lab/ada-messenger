use crate::error::Result;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

/// Built-in bootstrap bridge lines compiled into the binary.
///
/// These are the "last resort" bridges used when:
///   1. No bridges were configured in `ADAConfig.bridge.bridges`,
///   2. No bridge manifest is cached on disk, and
///   3. `fetch_bridge_manifest()` failed (manifest URL blocked).
///
/// In whitelist-based censorship the user has no way to fetch bridges over
/// the network on first install.  These hardcoded entries provide a minimal
/// working path until the operator's manifest can be fetched (possibly via
/// domain fronting from `fetch_bridge_manifest()` once at least one bridge
/// is up).
///
/// **Operator note**: replace these placeholders with real bridge-line strings
/// before shipping a production build.  Use domain-fronted (`fronting`) or
/// WebSocket (`websocket`) bridges for maximum whitelist resistance.
/// Format: `<protocol> <host>:<port> fp=<64-hex> [secret=<64-hex>] [host=<sni>] [front=<cdn-host>] [wire=json|bincode]`
pub const BUILTIN_BOOTSTRAP_BRIDGES: &[&str] = &[
    // Example WebSocket bridge behind Cloudflare (replace with real values):
    // "websocket bridge1.example.com:443 fp=1111111111111111111111111111111111111111111111111111111111111111 host=bridge1.example.com"
    // Example serverless Cloudflare Worker bridge (JSON wire format):
    // "websocket ada-edge.example.workers.dev:443 fp=1111111111111111111111111111111111111111111111111111111111111111 host=ada-edge.example.workers.dev wire=json"
    // Example Domain-Fronting bridge (replace with real values):
    // "fronting bridge2.example.com:443 fp=1111111111111111111111111111111111111111111111111111111111111111 front=cdn.example.com host=bridge2.example.com"
];

/// Build-time HTTPS manifest bootstrap URLs, embedded into production binaries.
///
/// Set `ADA_BUILTIN_MANIFEST_URLS` while building the Rust core, for example:
/// `https://ada-manifest.example.workers.dev/manifest.json;https://manifest.example.com/manifest.json`.
/// The signed manifest should normally point at Cloudflare Worker/custom-domain
/// bridge entries, so first-install clients in allowlist networks can discover
/// the active mailbox/bridge fleet without manual import.
const BUILTIN_MANIFEST_URLS_RAW: Option<&str> = option_env!("ADA_BUILTIN_MANIFEST_URLS");

/// Build-time Ed25519 public keys trusted for signed bridge manifests.
///
/// Set `ADA_BUILTIN_MANIFEST_PUBLIC_KEYS` to one or more 32-byte hex public keys
/// separated by comma, semicolon, or whitespace. The signing private key must
/// stay outside the app and Workers.
const BUILTIN_MANIFEST_PUBLIC_KEYS_RAW: Option<&str> =
    option_env!("ADA_BUILTIN_MANIFEST_PUBLIC_KEYS");

fn split_bootstrap_values(raw: Option<&str>) -> Vec<String> {
    raw.unwrap_or_default()
        .split(|ch: char| ch == ',' || ch == ';' || ch.is_whitespace())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn default_manifest_urls() -> Vec<String> {
    split_bootstrap_values(BUILTIN_MANIFEST_URLS_RAW)
}

fn default_manifest_public_keys() -> Vec<String> {
    split_bootstrap_values(BUILTIN_MANIFEST_PUBLIC_KEYS_RAW)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ADAConfig {
    pub storage: StorageConfig,
    pub network: NetworkConfig,
    #[serde(default)]
    pub bridge: BridgeConfig,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StorageConfig {
    pub data_dir: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct NetworkConfig {
    pub stun_servers: Vec<String>,
    pub turn_servers: Vec<TurnServerConfig>,
    /// Runtime transport profile. `auto` keeps the legacy adaptive behaviour;
    /// stricter profiles selectively disable expensive or blocked routes.
    #[serde(default)]
    pub connection_profile: ConnectionProfile,
    /// Files >= this threshold (bytes) are routed through Iroh Blobs
    /// (content-addressed, resumable) instead of the legacy chunked transfer.
    /// Default: 256 KiB.  Set to u64::MAX to disable blob routing.
    #[serde(default = "default_blob_threshold")]
    pub blob_threshold_bytes: u64,
    /// Relay-only policy.
    ///
    /// In the current build ADA cannot yet prove a verified live relay-only
    /// route through public iroh APIs. When enabled, ADA disables live outgoing
    /// iroh for unicast traffic and allows only non-direct bridge or mailbox
    /// routes, falling back to the local offline queue if no censorship-safe
    /// path exists.
    #[serde(default)]
    pub relay_only: bool,
    /// Enable mDNS / LocalSwarmDiscovery for LAN peer discovery.
    ///
    /// When `true`, iroh broadcasts its addresses on the local network via a
    /// multicast UDP service so peers on the same WiFi segment find each other
    /// in <1 s without any DNS lookup or relay roundtrip.
    ///
    /// On Android this requires the `CHANGE_WIFI_MULTICAST_STATE` permission
    /// and a multicast lock; without them the bind will fail silently.
    /// Default: `false` (conservative — safe on all platforms).
    #[serde(default)]
    pub mdns: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConnectionProfile {
    #[default]
    Auto,
    Normal,
    MobileSaver,
    CensoredLight,
    CensoredHeavy,
    AllowlistOnly,
    IncidentSafe,
}

impl ConnectionProfile {
    pub fn from_str_key(value: &str) -> Option<Self> {
        let key = value.trim().to_ascii_lowercase().replace('-', "_");
        match key.as_str() {
            "auto" => Some(Self::Auto),
            "normal" => Some(Self::Normal),
            "mobile_saver" | "battery_saver" => Some(Self::MobileSaver),
            "censored_light" => Some(Self::CensoredLight),
            "censored" | "censored_heavy" => Some(Self::CensoredHeavy),
            "allowlist" | "allowlist_only" | "whitelist" | "whitelist_only" | "https_only" => {
                Some(Self::AllowlistOnly)
            }
            "incident" | "incident_safe" => Some(Self::IncidentSafe),
            _ => None,
        }
    }

    pub fn implies_relay_only(self) -> bool {
        matches!(
            self,
            Self::CensoredHeavy | Self::AllowlistOnly | Self::IncidentSafe
        )
    }

    pub fn enables_mailbox_pull(self) -> bool {
        matches!(
            self,
            Self::CensoredHeavy | Self::AllowlistOnly | Self::IncidentSafe
        )
    }

    pub fn mailbox_poll_interval_secs(self, relay_only: bool) -> Option<u64> {
        match self {
            Self::AllowlistOnly => Some(10),
            Self::CensoredHeavy => Some(15),
            Self::IncidentSafe => Some(30),
            Self::Auto if relay_only => Some(15),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Normal => "normal",
            Self::MobileSaver => "mobile_saver",
            Self::CensoredLight => "censored_light",
            Self::CensoredHeavy => "censored_heavy",
            Self::AllowlistOnly => "allowlist_only",
            Self::IncidentSafe => "incident_safe",
        }
    }
}

fn default_blob_threshold() -> u64 {
    256 * 1024
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TurnServerConfig {
    pub url: String,
    pub username: String,
    pub credential: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BridgeConfig {
    #[serde(default)]
    pub bridges: Vec<String>,
    #[serde(default = "default_manifest_urls")]
    pub manifest_urls: Vec<String>,
    #[serde(default = "default_manifest_public_keys")]
    pub manifest_public_keys: Vec<String>,
    #[serde(default = "default_bridge_reconnect_secs")]
    pub reconnect_secs: u64,
    #[serde(default = "default_bridge_idle_ping_secs")]
    pub idle_ping_secs: u64,
    #[serde(default = "default_censored_attachment_limit")]
    pub max_censored_attachment_bytes: u64,
}

fn default_bridge_reconnect_secs() -> u64 {
    5
}
fn default_bridge_idle_ping_secs() -> u64 {
    20
}
fn default_censored_attachment_limit() -> u64 {
    256 * 1024
}

impl Default for BridgeConfig {
    fn default() -> Self {
        Self {
            bridges: vec![],
            manifest_urls: default_manifest_urls(),
            manifest_public_keys: default_manifest_public_keys(),
            reconnect_secs: default_bridge_reconnect_secs(),
            idle_ping_secs: default_bridge_idle_ping_secs(),
            max_censored_attachment_bytes: default_censored_attachment_limit(),
        }
    }
}

impl ADAConfig {
    pub fn default() -> Self {
        ADAConfig {
            storage: StorageConfig {
                data_dir: "ada_data".to_string(),
            },
            network: NetworkConfig {
                stun_servers: vec![
                    "stun.l.google.com:19302".to_string(),
                    "stun.cloudflare.com:3478".to_string(),
                    "stun.nextcloud.com:443".to_string(),
                ],
                turn_servers: vec![],
                connection_profile: ConnectionProfile::Auto,
                blob_threshold_bytes: default_blob_threshold(),
                relay_only: false,
                mdns: false,
            },
            bridge: BridgeConfig {
                ..BridgeConfig::default()
            },
        }
    }

    pub fn for_mobile() -> Self {
        let mut cfg = Self::default();
        cfg.storage.data_dir = "data".to_string();
        cfg
    }

    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        let content = fs::read_to_string(path)?;
        let cfg = serde_json::from_str(&content)?;
        Ok(cfg)
    }

    pub fn save<P: AsRef<Path>>(&self, path: P) -> Result<()> {
        let content = serde_json::to_string_pretty(self)?;
        fs::write(path, content)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_configs_use_auto_connection_profile() {
        assert_eq!(
            ADAConfig::default().network.connection_profile,
            ConnectionProfile::Auto
        );
        assert_eq!(
            ADAConfig::for_mobile().network.connection_profile,
            ConnectionProfile::Auto
        );
    }

    #[test]
    fn legacy_network_config_without_profile_defaults_to_auto() {
        let json = r#"
        {
            "storage": { "data_dir": "data" },
            "network": {
                "stun_servers": [],
                "turn_servers": [],
                "relay_only": false,
                "mdns": false
            },
            "bridge": {}
        }
        "#;

        let config: ADAConfig = serde_json::from_str(json).expect("legacy config should parse");
        assert_eq!(config.network.connection_profile, ConnectionProfile::Auto);
    }

    #[test]
    fn connection_profile_serializes_as_snake_case() {
        let mut config = ADAConfig::default();
        config.network.connection_profile = ConnectionProfile::AllowlistOnly;

        let json = serde_json::to_string(&config).expect("config should serialize");
        assert!(json.contains("\"connection_profile\":\"allowlist_only\""));
    }

    #[test]
    fn build_time_bootstrap_values_allow_multiple_separators() {
        let values = split_bootstrap_values(Some(
            " https://ada-manifest.example.workers.dev/manifest.json;\nhttps://manifest.example.com/manifest.json, 001122 ",
        ));

        assert_eq!(
            values,
            vec![
                "https://ada-manifest.example.workers.dev/manifest.json".to_string(),
                "https://manifest.example.com/manifest.json".to_string(),
                "001122".to_string(),
            ]
        );
    }

    #[test]
    fn connection_profile_parses_ui_and_legacy_aliases() {
        assert_eq!(
            ConnectionProfile::from_str_key("auto"),
            Some(ConnectionProfile::Auto)
        );
        assert_eq!(
            ConnectionProfile::from_str_key("battery-saver"),
            Some(ConnectionProfile::MobileSaver)
        );
        assert_eq!(
            ConnectionProfile::from_str_key("whitelist_only"),
            Some(ConnectionProfile::AllowlistOnly)
        );
        assert_eq!(ConnectionProfile::from_str_key("unknown"), None);
    }
}
