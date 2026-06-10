use serde::{Deserialize, Serialize};

use crate::bridge::bridge::BridgeProtocol;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum DeliveryClass {
    DirectMessage,
    FileMetadata,
    FileChunk,
    CallSignaling,
    MaintenanceRetry,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RouteAttempt {
    LocalMesh,
    IrohLive,
    Bridge,
    LocalOfflineQueue,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportRoute {
    IrohLive,
    BridgeWebSocketTls,
    BridgeDomainFront,
    BridgeMeek,
    BridgeObfs4,
    MailboxBridge,
    LocalMesh, // <-- NEW: Wi-Fi Direct / BLE pure mesh
    OfflineQueue,
    Failed,
}

impl TransportRoute {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::IrohLive => "iroh_live",
            Self::BridgeWebSocketTls => "bridge_websocket_tls",
            Self::BridgeDomainFront => "bridge_domain_front",
            Self::BridgeMeek => "bridge_meek",
            Self::BridgeObfs4 => "bridge_obfs4",
            Self::MailboxBridge => "mailbox_bridge",
            Self::LocalMesh => "local_mesh",
            Self::OfflineQueue => "offline_queue",
            Self::Failed => "failed",
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TransportPolicy {
    pub relay_only: bool,
    pub allow_iroh: bool,
    pub allow_bridge: bool,
    pub allow_mailbox: bool,
    pub allow_mailbox_pull: bool,
    pub allow_local_offline_queue: bool,
    pub require_live_delivery: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RouteCapabilities {
    pub bridge_live_delivery: bool,
    pub mailbox_delivery: bool,
    pub realtime_calls: bool,
    pub large_attachments: bool,
    pub max_attachment_bytes: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TransportOutcome {
    pub message_id: [u8; 16],
    pub route: TransportRoute,
    pub queue_depth: Option<u32>,
    pub latency_ms: Option<u64>,
    pub live_delivery: bool,
    pub detail: Option<String>,
}

impl TransportOutcome {
    pub fn new(message_id: [u8; 16], route: TransportRoute) -> Self {
        Self {
            message_id,
            route,
            queue_depth: None,
            latency_ms: None,
            live_delivery: false,
            detail: None,
        }
    }

    pub fn with_queue_depth(mut self, queue_depth: u32) -> Self {
        self.queue_depth = Some(queue_depth);
        self
    }

    pub fn with_latency(mut self, latency_ms: u64) -> Self {
        self.latency_ms = Some(latency_ms);
        self
    }

    pub fn with_live_delivery(mut self, live_delivery: bool) -> Self {
        self.live_delivery = live_delivery;
        self
    }

    pub fn with_detail(mut self, detail: impl Into<String>) -> Self {
        self.detail = Some(detail.into());
        self
    }
}

pub struct TransportRouter {
    policy: TransportPolicy,
}

impl TransportRouter {
    pub fn new(policy: TransportPolicy) -> Self {
        Self { policy }
    }

    pub fn attempts(&self, bridge_ready: bool) -> Vec<RouteAttempt> {
        let mut out = Vec::with_capacity(4);

        // We always attempt local mesh first - it's the safest, zero-cost
        // medium without internet. If it connects, we use it rapidly.
        out.push(RouteAttempt::LocalMesh);

        if self.policy.allow_iroh {
            out.push(RouteAttempt::IrohLive);
        }
        if bridge_ready && self.policy.allow_bridge {
            out.push(RouteAttempt::Bridge);
        }
        if self.policy.allow_local_offline_queue {
            out.push(RouteAttempt::LocalOfflineQueue);
        }
        out
    }

    pub fn capabilities(
        &self,
        bridge_ready: bool,
        bridge_listener_connected: bool,
        max_attachment_bytes: u64,
    ) -> RouteCapabilities {
        let bridge_live_delivery = bridge_ready && bridge_listener_connected;
        let mailbox_delivery = bridge_ready && self.policy.allow_mailbox;
        let realtime_calls =
            (self.policy.allow_iroh && !self.policy.relay_only) || bridge_live_delivery;
        let large_attachments = self.policy.allow_iroh && !self.policy.relay_only;

        RouteCapabilities {
            bridge_live_delivery,
            mailbox_delivery,
            realtime_calls,
            large_attachments,
            max_attachment_bytes,
        }
    }
}

pub fn route_from_bridge_protocol(
    protocol: &BridgeProtocol,
    live_delivery: bool,
) -> TransportRoute {
    if !live_delivery {
        return TransportRoute::MailboxBridge;
    }

    match protocol {
        BridgeProtocol::Obfs4 => TransportRoute::BridgeObfs4,
        BridgeProtocol::WebSocketTLS => TransportRoute::BridgeWebSocketTls,
        BridgeProtocol::DomainFronting { .. } => TransportRoute::BridgeDomainFront,
        BridgeProtocol::Meek { .. } => TransportRoute::BridgeMeek,
        BridgeProtocol::TcpDirect => TransportRoute::MailboxBridge,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(
        relay_only: bool,
        allow_iroh: bool,
        allow_bridge: bool,
        allow_mailbox: bool,
        allow_local_offline_queue: bool,
    ) -> TransportPolicy {
        TransportPolicy {
            relay_only,
            allow_iroh,
            allow_bridge,
            allow_mailbox,
            allow_mailbox_pull: allow_mailbox,
            allow_local_offline_queue,
            require_live_delivery: false,
        }
    }

    #[test]
    fn attempts_prioritize_local_mesh_then_live_routes_then_offline_queue() {
        let router = TransportRouter::new(policy(false, true, true, true, true));

        assert_eq!(
            router.attempts(true),
            vec![
                RouteAttempt::LocalMesh,
                RouteAttempt::IrohLive,
                RouteAttempt::Bridge,
                RouteAttempt::LocalOfflineQueue,
            ]
        );
    }

    #[test]
    fn attempts_skip_disabled_routes_but_keep_local_mesh_first() {
        let router = TransportRouter::new(policy(true, false, false, false, true));

        assert_eq!(
            router.attempts(false),
            vec![RouteAttempt::LocalMesh, RouteAttempt::LocalOfflineQueue]
        );
    }

    #[test]
    fn relay_only_capabilities_disable_large_attachments_without_live_bridge() {
        let router = TransportRouter::new(policy(true, false, true, true, true));

        let capabilities = router.capabilities(true, false, 256 * 1024);

        assert!(!capabilities.bridge_live_delivery);
        assert!(capabilities.mailbox_delivery);
        assert!(!capabilities.realtime_calls);
        assert!(!capabilities.large_attachments);
        assert_eq!(capabilities.max_attachment_bytes, 256 * 1024);
    }

    #[test]
    fn live_bridge_capabilities_restore_realtime_calls_under_relay_only() {
        let router = TransportRouter::new(policy(true, false, true, true, true));

        let capabilities = router.capabilities(true, true, 128 * 1024);

        assert!(capabilities.bridge_live_delivery);
        assert!(capabilities.mailbox_delivery);
        assert!(capabilities.realtime_calls);
        assert!(!capabilities.large_attachments);
    }

    #[test]
    fn bridge_protocol_mapping_preserves_live_vs_mailbox_routes() {
        assert_eq!(
            route_from_bridge_protocol(&BridgeProtocol::WebSocketTLS, true),
            TransportRoute::BridgeWebSocketTls
        );
        assert_eq!(
            route_from_bridge_protocol(
                &BridgeProtocol::DomainFronting {
                    front_domain: "cdn.example".into(),
                },
                true,
            ),
            TransportRoute::BridgeDomainFront
        );
        assert_eq!(
            route_from_bridge_protocol(
                &BridgeProtocol::Meek {
                    front_url: "https://front.example/meek".into(),
                },
                true,
            ),
            TransportRoute::BridgeMeek
        );
        assert_eq!(
            route_from_bridge_protocol(&BridgeProtocol::Obfs4, true),
            TransportRoute::BridgeObfs4
        );
        assert_eq!(
            route_from_bridge_protocol(&BridgeProtocol::TcpDirect, true),
            TransportRoute::MailboxBridge
        );
        assert_eq!(
            route_from_bridge_protocol(&BridgeProtocol::WebSocketTLS, false),
            TransportRoute::MailboxBridge
        );
    }
}
