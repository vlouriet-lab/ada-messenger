//! iroh transport with offline queue fallback.
//!
//! ## Strategy
//!
//! 1. **Primary (iroh):** Try QUIC unicast via public relay; target ~5ms on good networks
//! 2. **Offline queue:** If iroh fails, store in relay_mgr for later retry
//!
//! When iroh succeeds, future messages to the same peer will reuse the cached QUIC
//! connection (~1ms latency). On iroh failure, we queue for offline delivery.

use crate::identity::PeerId;

/// Outcome of a send attempt
#[derive(Debug, Clone)]
pub enum SendOutcome {
    /// Message delivered over iroh QUIC (most likely instant delivery)
    IrohDirect { latency_ms: u32 },
    /// Message queued for later retry (peer is offline)
    OfflineQueue { queue_depth: u32 },
    /// Transport exhausted; message lost (should not happen with offline queue)
    Failed { reason: String },
}

impl std::fmt::Display for SendOutcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SendOutcome::IrohDirect { latency_ms } => {
                write!(f, "iroh direct ({}ms)", latency_ms)
            }
            SendOutcome::OfflineQueue { queue_depth } => {
                write!(f, "offline queue (depth={})", queue_depth)
            }
            SendOutcome::Failed { reason } => {
                write!(f, "failed: {}", reason)
            }
        }
    }
}

/// Configuration for fallback behaviour
#[derive(Debug, Clone)]
pub struct FallbackConfig {
    /// Timeout before giving up on iroh and queueing offline (default: 3s)
    pub iroh_timeout_ms: u64,
    /// Maximum retries for iroh before fallback (default: 1)
    pub iroh_retries: u32,
    /// Whether to always use relay instead of direct QUIC (privacy mode)
    pub always_relay: bool,
}

impl Default for FallbackConfig {
    fn default() -> Self {
        FallbackConfig {
            iroh_timeout_ms: 3000,
            iroh_retries: 1,
            always_relay: false,
        }
    }
}

/// Result of a single send attempt
#[derive(Debug)]
pub struct SendAttempt {
    pub transport: TransportKind,
    pub success: bool,
    pub latency_ms: u32,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportKind {
    Iroh,
    OfflineQueue,
}

impl std::fmt::Display for TransportKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TransportKind::Iroh => write!(f, "iroh"),
            TransportKind::OfflineQueue => write!(f, "offline_queue"),
        }
    }
}

/// Message delivery context for logging and metrics
#[derive(Debug)]
pub struct SendContext {
    pub peer_id: PeerId,
    pub wire_bytes_len: usize,
    pub is_retry: bool,
    pub start_time: std::time::Instant,
}

impl SendContext {
    pub fn new(peer_id: PeerId, wire_bytes_len: usize) -> Self {
        SendContext {
            peer_id,
            wire_bytes_len,
            is_retry: false,
            start_time: std::time::Instant::now(),
        }
    }

    pub fn elapsed_ms(&self) -> u32 {
        self.start_time.elapsed().as_millis() as u32
    }
}

/// Logs the result of a send attempt
pub fn log_send_attempt(ctx: &SendContext, attempt: &SendAttempt, outcome: &SendOutcome) {
    let peer_short = format!("{}", ctx.peer_id)
        .chars()
        .take(8)
        .collect::<String>();
    match attempt.transport {
        TransportKind::Iroh if attempt.success => {
            tracing::debug!(
                target: "ada.network",
                peer = %peer_short,
                bytes = ctx.wire_bytes_len,
                latency_ms = attempt.latency_ms,
                "iroh direct send ✓"
            );
        }
        TransportKind::Iroh => {
            tracing::debug!(
                target: "ada.network",
                peer = %peer_short,
                error = ?attempt.error,
                "iroh failed, using offline queue"
            );
        }
        TransportKind::OfflineQueue => {
            tracing::info!(
                target: "ada.network",
                peer = %peer_short,
                total_elapsed_ms = ctx.elapsed_ms(),
                outcome = %outcome,
                "message queued for offline delivery"
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_send_outcome_display() {
        let o1 = SendOutcome::IrohDirect { latency_ms: 5 };
        assert!(format!("{}", o1).contains("iroh"));

        let o2 = SendOutcome::OfflineQueue { queue_depth: 3 };
        assert!(format!("{}", o2).contains("offline"));
    }

    #[test]
    fn test_fallback_config_defaults() {
        let cfg = FallbackConfig::default();
        assert_eq!(cfg.iroh_timeout_ms, 3000);
        assert!(!cfg.always_relay);
    }
}
