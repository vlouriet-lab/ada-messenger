//! `metrics.rs` — lock-free production metrics for ADA core.
//!
//! Pattern borrowed from Plex project's `CoreMetrics`:
//! - All counters are `AtomicU64` — zero lock contention.
//! - `snapshot()` returns a plain struct suitable for FFI/JSON serialization.
//! - Android layer computes deltas between two snapshots for rate metrics.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

/// Lock-free production metrics.
/// Wrap in `Arc` and share across tasks.
pub struct CoreMetrics {
    // ── Connection pool ───────────────────────────────────────────────────────
    pub pool_active_connections: AtomicU64,
    pub pool_dial_attempts: AtomicU64,
    pub pool_dial_failures: AtomicU64,

    // ── Chat messages ─────────────────────────────────────────────────────────
    pub chat_messages_queued: AtomicU64,
    pub chat_messages_received: AtomicU64,
    /// Duplicate messages silently ignored (seen-set hit)
    pub chat_messages_duplicate: AtomicU64,

    // ── Outbox delivery ───────────────────────────────────────────────────────
    pub outbox_sent_total: AtomicU64,
    pub outbox_delivered_total: AtomicU64,
    pub outbox_failures_total: AtomicU64,
    pub outbox_retries_total: AtomicU64,

    // ── Sync protocol ─────────────────────────────────────────────────────────
    pub sync_rounds_completed: AtomicU64,
    pub sync_messages_recovered: AtomicU64,

    // ── Calls ─────────────────────────────────────────────────────────────────
    pub calls_initiated_total: AtomicU64,
    pub calls_received_total: AtomicU64,
    pub calls_ended_total: AtomicU64,
    pub calls_failed_total: AtomicU64,

    // ── Ratchet ───────────────────────────────────────────────────────────────
    pub ratchet_encrypt_total: AtomicU64,
    pub ratchet_decrypt_total: AtomicU64,
    pub ratchet_decrypt_errors: AtomicU64,

    // ── File transfers ────────────────────────────────────────────────────────
    pub transfer_chunks_sent: AtomicU64,
    pub transfer_chunks_received: AtomicU64,
    pub transfer_chunks_retransmitted: AtomicU64,
    pub transfers_completed: AtomicU64,
    pub transfers_failed: AtomicU64,
}

impl CoreMetrics {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            pool_active_connections: AtomicU64::new(0),
            pool_dial_attempts: AtomicU64::new(0),
            pool_dial_failures: AtomicU64::new(0),
            chat_messages_queued: AtomicU64::new(0),
            chat_messages_received: AtomicU64::new(0),
            chat_messages_duplicate: AtomicU64::new(0),
            outbox_sent_total: AtomicU64::new(0),
            outbox_delivered_total: AtomicU64::new(0),
            outbox_failures_total: AtomicU64::new(0),
            outbox_retries_total: AtomicU64::new(0),
            sync_rounds_completed: AtomicU64::new(0),
            sync_messages_recovered: AtomicU64::new(0),
            calls_initiated_total: AtomicU64::new(0),
            calls_received_total: AtomicU64::new(0),
            calls_ended_total: AtomicU64::new(0),
            calls_failed_total: AtomicU64::new(0),
            ratchet_encrypt_total: AtomicU64::new(0),
            ratchet_decrypt_total: AtomicU64::new(0),
            ratchet_decrypt_errors: AtomicU64::new(0),
            transfer_chunks_sent: AtomicU64::new(0),
            transfer_chunks_received: AtomicU64::new(0),
            transfer_chunks_retransmitted: AtomicU64::new(0),
            transfers_completed: AtomicU64::new(0),
            transfers_failed: AtomicU64::new(0),
        })
    }

    /// Increment a counter by 1.
    #[inline]
    pub fn inc(&self, counter: &AtomicU64) {
        counter.fetch_add(1, Ordering::Relaxed);
    }

    /// Set a gauge (e.g., pool_active_connections).
    #[inline]
    pub fn set(&self, gauge: &AtomicU64, value: u64) {
        gauge.store(value, Ordering::Relaxed);
    }

    /// Returns a plain snapshot of all current values.
    /// All counters are monotonically increasing; compute deltas between
    /// snapshots to get rates.
    pub fn snapshot(&self) -> CoreMetricsSnapshot {
        CoreMetricsSnapshot {
            pool_active_connections: self.pool_active_connections.load(Ordering::Relaxed),
            pool_dial_attempts: self.pool_dial_attempts.load(Ordering::Relaxed),
            pool_dial_failures: self.pool_dial_failures.load(Ordering::Relaxed),
            chat_messages_queued: self.chat_messages_queued.load(Ordering::Relaxed),
            chat_messages_received: self.chat_messages_received.load(Ordering::Relaxed),
            chat_messages_duplicate: self.chat_messages_duplicate.load(Ordering::Relaxed),
            outbox_sent_total: self.outbox_sent_total.load(Ordering::Relaxed),
            outbox_delivered_total: self.outbox_delivered_total.load(Ordering::Relaxed),
            outbox_failures_total: self.outbox_failures_total.load(Ordering::Relaxed),
            outbox_retries_total: self.outbox_retries_total.load(Ordering::Relaxed),
            sync_rounds_completed: self.sync_rounds_completed.load(Ordering::Relaxed),
            sync_messages_recovered: self.sync_messages_recovered.load(Ordering::Relaxed),
            calls_initiated_total: self.calls_initiated_total.load(Ordering::Relaxed),
            calls_received_total: self.calls_received_total.load(Ordering::Relaxed),
            calls_ended_total: self.calls_ended_total.load(Ordering::Relaxed),
            calls_failed_total: self.calls_failed_total.load(Ordering::Relaxed),
            ratchet_encrypt_total: self.ratchet_encrypt_total.load(Ordering::Relaxed),
            ratchet_decrypt_total: self.ratchet_decrypt_total.load(Ordering::Relaxed),
            ratchet_decrypt_errors: self.ratchet_decrypt_errors.load(Ordering::Relaxed),
            transfer_chunks_sent: self.transfer_chunks_sent.load(Ordering::Relaxed),
            transfer_chunks_received: self.transfer_chunks_received.load(Ordering::Relaxed),
            transfer_chunks_retransmitted: self
                .transfer_chunks_retransmitted
                .load(Ordering::Relaxed),
            transfers_completed: self.transfers_completed.load(Ordering::Relaxed),
            transfers_failed: self.transfers_failed.load(Ordering::Relaxed),
        }
    }
}

/// Plain snapshot of all metrics, suitable for FFI and JSON export.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct CoreMetricsSnapshot {
    pub pool_active_connections: u64,
    pub pool_dial_attempts: u64,
    pub pool_dial_failures: u64,
    pub chat_messages_queued: u64,
    pub chat_messages_received: u64,
    pub chat_messages_duplicate: u64,
    pub outbox_sent_total: u64,
    pub outbox_delivered_total: u64,
    pub outbox_failures_total: u64,
    pub outbox_retries_total: u64,
    pub sync_rounds_completed: u64,
    pub sync_messages_recovered: u64,
    pub calls_initiated_total: u64,
    pub calls_received_total: u64,
    pub calls_ended_total: u64,
    pub calls_failed_total: u64,
    pub ratchet_encrypt_total: u64,
    pub ratchet_decrypt_total: u64,
    pub ratchet_decrypt_errors: u64,
    pub transfer_chunks_sent: u64,
    pub transfer_chunks_received: u64,
    pub transfer_chunks_retransmitted: u64,
    pub transfers_completed: u64,
    pub transfers_failed: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_reflects_increments() {
        let m = CoreMetrics::new();
        m.inc(&m.chat_messages_queued);
        m.inc(&m.chat_messages_queued);
        m.inc(&m.ratchet_decrypt_errors);
        m.set(&m.pool_active_connections, 5);

        let s = m.snapshot();
        assert_eq!(s.chat_messages_queued, 2);
        assert_eq!(s.ratchet_decrypt_errors, 1);
        assert_eq!(s.pool_active_connections, 5);
    }
}
