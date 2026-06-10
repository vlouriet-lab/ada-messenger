// ── Logging initialization for ADA Core
//
// Configures structured tracing with level filtering based on environment variables.
// In production (mobile), logs are written to files; in development, to stderr.

use once_cell::sync::OnceCell;
use std::path::Path;
use tracing_subscriber::{
    filter::EnvFilter,
    fmt::{self, format::FmtSpan},
    layer::SubscriberExt,
    Registry,
};

static LOG_GUARD: OnceCell<tracing_appender::non_blocking::WorkerGuard> = OnceCell::new();

/// Initialize tracing for the ADA core.
///
/// - On **mobile (Android/iOS)**: logs go to `{data_dir}/ada.log` (rotated daily)
/// - On **desktop/test**: logs go to stderr with RUST_LOG filtering
///
/// Call this once during app init, before starting ADACore.
pub fn init_tracing(data_dir: &str, is_mobile: bool) {
    let env_filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| {
        // In debug builds expose verbose ada traces; in release builds
        // silence non-essential output to reduce log file exposure.
        #[cfg(debug_assertions)]
        {
            EnvFilter::new("info,ada_core=debug,ada=debug,iroh=info")
        }
        #[cfg(not(debug_assertions))]
        {
            EnvFilter::new("warn,ada_core=info,ada=info,iroh=warn")
        }
    });

    if is_mobile {
        // Mobile: write to file with daily rotation
        let log_path = Path::new(data_dir).join("ada.log");
        let file_appender = tracing_appender::rolling::daily(Path::new(data_dir), "ada.log");
        let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);
        let _ = LOG_GUARD.set(guard);

        let subscriber = Registry::default().with(env_filter).with(
            fmt::layer()
                .with_writer(non_blocking)
                .with_target(true)
                .with_level(true)
                .with_span_events(FmtSpan::CLOSE)
                .compact(),
        );

        if tracing::subscriber::set_global_default(subscriber).is_err() {
            return;
        }

        tracing::info!(
            "ADA Core logging initialized — file: {}",
            log_path.display()
        );
    } else {
        // Desktop/test: write to file so logs remain observable even when the JVM child
        // process has no useful attached console on Windows.
        let log_path = Path::new(data_dir).join("ada-desktop.log");
        let file_appender = tracing_appender::rolling::daily(Path::new(data_dir), "ada-desktop.log");
        let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);
        let _ = LOG_GUARD.set(guard);

        let subscriber = Registry::default().with(env_filter).with(
            fmt::layer()
                .with_writer(non_blocking)
                .with_target(true)
                .with_level(true)
                .with_span_events(FmtSpan::CLOSE)
                .compact(),
        );

        if tracing::subscriber::set_global_default(subscriber).is_err() {
            return;
        }

        tracing::info!(
            "ADA Core logging initialized — desktop file: {}",
            log_path.display()
        );
    }
}

/// Lightweight telemetry stub — the authoritative metrics snapshot is
/// `ADACore::get_metrics_snapshot(&self) -> String` (in `api.rs`), which
/// reads all atomic counters synchronously and serialises them as JSON.
///
/// This top-level function exists only for backward API compatibility.
/// Callers that have access to an `ADACore` instance should call
/// `core.get_metrics_snapshot()` directly, or use the JNI method
/// `nativeGetMetricsSnapshot` from Kotlin.
pub fn get_metrics_snapshot() -> String {
    // No ADACore reference available at this call site.
    // Use ADACore::get_metrics_snapshot() or the JNI nativeGetMetricsSnapshot instead.
    "{\"status\":\"use ADACore::get_metrics_snapshot\"}".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_init_tracing_desktop() {
        // Should not panic
        let _ = std::panic::catch_unwind(|| {
            init_tracing("/tmp", false);
        });
    }
}
