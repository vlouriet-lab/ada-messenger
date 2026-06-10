pub mod api;
pub mod bridge;
pub mod config;
pub mod crypto;
pub mod error;
pub mod ffi;
pub mod group;
pub mod identity;
pub mod logging;
pub mod media;
pub mod mesh_handoff;
pub mod messaging;
pub mod metrics;
pub mod network;
pub mod pattern_auth;
pub mod shortlink;
pub mod storage;
pub mod transfer;
pub mod transport;

#[cfg(feature = "jni-bindings")]
pub mod jni;

#[cfg(test)]
mod tests;

pub use api::{ADACore, ADAEvent};
pub use config::ADAConfig;
pub use error::{ADAError, Result};

pub const PROTOCOL_VERSION: &str = "0.1.0";

/// Maximum allowed plaintext DM message size.
/// Enforced at the iroh transport layer (MAX_MSG_BYTES in iroh_transport.rs).
/// Ratchet messages are additionally capped at 512 KiB by MAX_RATCHET_MSG_BYTES.
pub const MAX_MESSAGE_SIZE: usize = 4 * 1024 * 1024; // 4 MiB — matches iroh_transport limit
/// Chunk size used for file transfers (64 KiB)
pub const CHUNK_SIZE: usize = 64 * 1024;
