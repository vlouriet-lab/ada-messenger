//! Mobile FFI bindings
//!
//! Exposes ADA Core to Swift (iOS) and Kotlin/Java (Android) via C ABI.
//! Usage:
//!   - iOS: generate C headers with cbindgen, link as static library
//!   - Android: build as shared library (.so), use JNI or FFI crate

use once_cell::sync::OnceCell;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_uint};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::runtime::Runtime;

use crate::api::ADACore;
use crate::config::{ADAConfig, ConnectionProfile};

/// Global Tokio runtime for FFI calls
static RUNTIME: OnceCell<Runtime> = OnceCell::new();

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        // Attempt multi-threaded first; fall back to single-threaded when the
        // OS can't allocate the worker threads (e.g. limited thread quota).
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .or_else(|e| {
                log::error!(
                    "ada_core: multi-thread runtime init failed ({e}), retrying single-thread"
                );
                tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
            })
            // Only panic if both builders fail вЂ” that means the OS itself is
            // broken and the process cannot do any useful async work.
            .expect("Failed to create Tokio runtime (both multi and single-thread exhausted)")
    })
}

const CALLBACK_POLL_TIMEOUT_MS: u32 = 250;
const COMPLETED_TRANSFER_CACHE_DIR: &str = "completed_transfers";
const MAX_COMPLETED_TRANSFER_CACHE_ENTRIES: usize = 64;
const MAX_COMPLETED_TRANSFER_CACHE_BYTES: u64 = 512 * 1024 * 1024;

unsafe fn read_connection_profile_arg(
    connection_profile: *const c_char,
) -> std::result::Result<Option<ConnectionProfile>, ()> {
    if connection_profile.is_null() {
        return Ok(None);
    }
    let profile = CStr::from_ptr(connection_profile)
        .to_str()
        .map_err(|_| ())?
        .trim();
    if profile.is_empty() {
        return Ok(None);
    }
    ConnectionProfile::from_str_key(profile).map(Some).ok_or(())
}

struct CallbackWorker {
    stop_requested: Arc<AtomicBool>,
    thread: std::thread::JoinHandle<()>,
}

#[derive(Clone)]
struct CompletedTransferCacheEntry {
    path: PathBuf,
    file_name: String,
    mime_type: String,
    file_size: u64,
    cached_bytes: u64,
    cached_at_ms: u128,
}

fn unix_time_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn completed_transfer_cache_dir(handle: &AdaCoreHandle) -> PathBuf {
    PathBuf::from(&handle.inner.config.storage.data_dir).join(COMPLETED_TRANSFER_CACHE_DIR)
}

fn prune_completed_transfer_cache(map: &mut HashMap<String, CompletedTransferCacheEntry>) {
    while map.len() > MAX_COMPLETED_TRANSFER_CACHE_ENTRIES {
        let Some(oldest_key) = map
            .iter()
            .min_by_key(|(_, entry)| entry.cached_at_ms)
            .map(|(key, _)| key.clone())
        else {
            break;
        };
        if let Some(entry) = map.remove(&oldest_key) {
            let _ = std::fs::remove_file(entry.path);
        }
    }

    while map.len() > 1 {
        let total_bytes: u64 = map.values().map(|entry| entry.cached_bytes).sum();
        if total_bytes <= MAX_COMPLETED_TRANSFER_CACHE_BYTES {
            break;
        }
        let Some(oldest_key) = map
            .iter()
            .min_by_key(|(_, entry)| entry.cached_at_ms)
            .map(|(key, _)| key.clone())
        else {
            break;
        };
        if let Some(entry) = map.remove(&oldest_key) {
            let _ = std::fs::remove_file(entry.path);
        }
    }
}

fn cache_completed_inbound_transfer(
    handle: &AdaCoreHandle,
    transfer_id_hex: String,
    data: &[u8],
    file_name: String,
    mime_type: String,
    file_size: u64,
) {
    let cache_dir = completed_transfer_cache_dir(handle);
    if let Err(e) = std::fs::create_dir_all(&cache_dir) {
        tracing::warn!("completed transfer cache: create dir failed: {}", e);
        return;
    }

    let now_ms = unix_time_ms();
    let tmp_path = cache_dir.join(format!("{}.{}.tmp", transfer_id_hex, now_ms));
    let final_path = cache_dir.join(format!("{}.bin", transfer_id_hex));

    if let Err(e) = std::fs::write(&tmp_path, data) {
        tracing::warn!("completed transfer cache: write failed: {}", e);
        let _ = std::fs::remove_file(&tmp_path);
        return;
    }
    if final_path.exists() {
        let _ = std::fs::remove_file(&final_path);
    }
    if let Err(e) = std::fs::rename(&tmp_path, &final_path) {
        tracing::warn!("completed transfer cache: rename failed: {}", e);
        let _ = std::fs::remove_file(&tmp_path);
        return;
    }

    let entry = CompletedTransferCacheEntry {
        path: final_path,
        file_name,
        mime_type,
        file_size,
        cached_bytes: data.len() as u64,
        cached_at_ms: now_ms,
    };

    if let Ok(mut map) = handle.completed_transfers.lock() {
        if let Some(old_entry) = map.insert(transfer_id_hex, entry) {
            let _ = std::fs::remove_file(old_entry.path);
        }
        prune_completed_transfer_cache(&mut map);
    }
}

fn clear_completed_transfer_cache(handle: &AdaCoreHandle) {
    if let Ok(mut map) = handle.completed_transfers.lock() {
        for (_, entry) in map.drain() {
            let _ = std::fs::remove_file(entry.path);
        }
    }
}

fn stop_event_callback_worker(handle: &AdaCoreHandle) {
    let worker = handle.event_callback.lock().take();
    if let Some(worker) = worker {
        worker.stop_requested.store(true, Ordering::Release);
        if worker.thread.thread().id() != std::thread::current().id() {
            let _ = worker.thread.join();
        }
    }
}

/// Opaque handle to an ADACore instance
pub struct AdaCoreHandle {
    inner: Arc<ADACore>,
    /// Cache of completed inbound transfers: transfer_id_hex -> temp file + metadata.
    completed_transfers: std::sync::Mutex<HashMap<String, CompletedTransferCacheEntry>>,
    // parking_lot::Mutex does not poison on panic — eliminates unwrap() abort risk in FFI
    event_callback: parking_lot::Mutex<Option<CallbackWorker>>,
    last_error: parking_lot::Mutex<Option<String>>,
}

impl AdaCoreHandle {
    pub(crate) fn clear_last_error(&self) {
        *self.last_error.lock() = None;
    }

    pub(crate) fn set_last_error(&self, message: impl Into<String>) {
        *self.last_error.lock() = Some(message.into());
    }

    pub(crate) fn take_last_error(&self) -> Option<String> {
        self.last_error.lock().take()
    }
}

/// Result code returned by FFI functions
#[repr(C)]
pub enum AdaResult {
    Ok = 0,
    Error = 1,
    NotInitialized = 2,
    InvalidArg = 3,
    Timeout = 4,
}

/// Create a new ADA Core instance
///
/// # Safety
/// - `display_name` must be a valid null-terminated UTF-8 string
/// - Caller must free the handle with `ada_core_free`
#[no_mangle]
pub unsafe extern "C" fn ada_core_create(
    display_name: *const c_char,
    data_dir: *const c_char,
) -> *mut AdaCoreHandle {
    ada_core_create_with_profile(display_name, data_dir, std::ptr::null())
}

/// Create a new ADA Core instance with an explicit connection profile.
///
/// # Safety
/// - `display_name`, `data_dir`, and `connection_profile` must be valid
///   null-terminated UTF-8 strings when non-null.
#[no_mangle]
pub unsafe extern "C" fn ada_core_create_with_profile(
    display_name: *const c_char,
    data_dir: *const c_char,
    connection_profile: *const c_char,
) -> *mut AdaCoreHandle {
    // Guard against null pointers before dereferencing.
    if display_name.is_null() || data_dir.is_null() {
        return std::ptr::null_mut();
    }
    let name = match CStr::from_ptr(display_name).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let dir = match CStr::from_ptr(data_dir).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut config = ADAConfig::for_mobile();
    config.storage.data_dir = dir;
    match read_connection_profile_arg(connection_profile) {
        Ok(Some(profile)) => config.network.connection_profile = profile,
        Ok(None) => {}
        Err(_) => {
            log::error!("ada_core_create_with_profile: invalid connection_profile");
            return std::ptr::null_mut();
        }
    }

    let core = runtime().block_on(async { ADACore::new(config, &name).await });

    match core {
        Ok(c) => match runtime().block_on(async { c.start().await }) {
            Ok(()) => Box::into_raw(Box::new(AdaCoreHandle {
                inner: c,
                completed_transfers: std::sync::Mutex::new(std::collections::HashMap::new()),
                event_callback: parking_lot::Mutex::new(None),
                last_error: parking_lot::Mutex::new(None),
            })),
            Err(e) => {
                log::error!("ada_core_create: core.start() failed: {e:?}");
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log::error!("ada_core_create failed for '{name}': {e:?}");
            std::ptr::null_mut()
        }
    }
}

/// Free an ADA Core handle
///
/// # Safety
/// `handle` must be a valid pointer from `ada_core_create`
#[no_mangle]
pub unsafe extern "C" fn ada_core_free(handle: *mut AdaCoreHandle) {
    if !handle.is_null() {
        let h = Box::from_raw(handle);
        stop_event_callback_worker(&h);
        clear_completed_transfer_cache(&h);
        runtime().block_on(async { h.inner.stop().await });
    }
}

/// Notify the core that device network has been restored.
///
/// Call this from `ConnectivityManager.NetworkCallback.onAvailable()` as soon
/// as the Android system signals a new network interface.  This forces iroh to
/// re-probe its interfaces and triggers an immediate pkarr republish, cutting
/// peer-discovery latency from up to 30 s (backoff) down to ~1 s.
///
/// # Safety
/// `handle` must be a valid pointer from `ada_core_create` and must not be null.
#[no_mangle]
pub unsafe extern "C" fn ada_notify_network_available(handle: *const AdaCoreHandle) {
    if handle.is_null() {
        return;
    }
    let h = &*handle;
    runtime().block_on(h.inner.notify_network_available());
}

/// Notify the core that the current network interface has been lost.
///
/// Call this from `ConnectivityManager.NetworkCallback.onLost()` to let iroh
/// drop stale QUIC connections before the new interface comes up.
///
/// # Safety
/// `handle` must be a valid pointer from `ada_core_create` and must not be null.
#[no_mangle]
pub unsafe extern "C" fn ada_notify_network_lost(handle: *const AdaCoreHandle) {
    if handle.is_null() {
        return;
    }
    let h = &*handle;
    runtime().block_on(h.inner.notify_network_lost());
}

/// Receives incoming generic mesh bytes and forwards them for decryption
///
/// # Safety
/// `peer_b64` must be a valid null-terminated UTF-8 string containing base64 peer id.
/// `bytes` must be a pointer to a byte array of length `len`.
#[no_mangle]
pub unsafe extern "C" fn ada_receive_mesh_bytes(
    handle: *const AdaCoreHandle,
    peer_b64: *const c_char,
    bytes: *const u8,
    len: usize,
) -> i32 {
    // Returns 0 on success, < 0 on parse error.
    if handle.is_null() || peer_b64.is_null() || bytes.is_null() {
        return -1;
    }

    let peer_str = match CStr::from_ptr(peer_b64).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };

    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return -3,
    };

    let slice = std::slice::from_raw_parts(bytes, len);
    let bytes_vec = slice.to_vec();

    let h = &*handle;
    runtime().block_on(async {
        match h.inner.receive_mesh_bytes(peer, bytes_vec).await {
            Ok(_) => 0,
            Err(_) => -4,
        }
    })
}

/// Disconnect a mesh peer
///
/// # Safety
/// `peer_b64` must be a valid null-terminated UTF-8 string containing base64 peer id.
#[no_mangle]
pub unsafe extern "C" fn ada_mesh_peer_disconnected(
    handle: *const AdaCoreHandle,
    peer_b64: *const c_char,
) -> i32 {
    if handle.is_null() || peer_b64.is_null() {
        return -1;
    }

    let peer_str = match CStr::from_ptr(peer_b64).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return -3,
    };

    let h = &*handle;
    h.inner.disconnect_mesh_peer(&peer);
    0
}

/// Get the local peer ID as a null-terminated base64 string
///
/// # Safety
/// - `handle` must be valid
/// - Caller must free returned string with `ada_string_free`
#[no_mangle]
pub unsafe extern "C" fn ada_get_peer_id(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let id = h.inner.peer_id().to_base64();
    match CString::new(id) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export the running identity's secret key material as a JSON string.
///
/// Returns a heap-allocated null-terminated UTF-8 string on success, or NULL
/// on failure.  The caller is responsible for freeing the returned string via
/// `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid non-null pointer returned by `ada_core_create*`.
#[no_mangle]
pub unsafe extern "C" fn ada_export_identity_json(
    handle: *const AdaCoreHandle,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime().block_on(h.inner.export_identity_json())
    }));
    match result {
        Ok(Ok(json)) => match CString::new(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(e)) => {
            tracing::error!("[ffi] ada_export_identity_json error: {}", e);
            std::ptr::null_mut()
        }
        Err(_) => {
            tracing::error!("[ffi] ada_export_identity_json panicked");
            std::ptr::null_mut()
        }
    }
}

/// Free a C string previously returned by an `ada_*` function (e.g. `ada_get_peer_id`,
/// `ada_export_identity_json`).
///
/// # Safety
/// `ptr` must have been allocated by Rust's `CString::into_raw()`.  Passing any
/// other pointer is undefined behaviour.  Passing NULL is safe (no-op).
#[no_mangle]
pub unsafe extern "C" fn ada_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

/// Send a text message
///
/// # Safety
/// Export a full device snapshot as JSON.
///
/// The snapshot contains identity secret material, contacts, ratchet session
/// states, and recent messages — everything needed to pair a desktop.
/// Returns a heap-allocated null-terminated UTF-8 string on success, or NULL
/// on failure.  The caller must free the string via `ada_free_string`.
///
/// # Safety
/// `handle` must be a valid non-null pointer returned by `ada_core_create*`.
#[no_mangle]
pub unsafe extern "C" fn ada_export_snapshot(
    handle: *const AdaCoreHandle,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime().block_on(h.inner.export_snapshot())
    }));
    match result {
        Ok(Ok(json)) => match CString::new(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Ok(Err(e)) => {
            tracing::error!("[ffi] ada_export_snapshot error: {}", e);
            std::ptr::null_mut()
        }
        Err(_) => {
            tracing::error!("[ffi] ada_export_snapshot panicked");
            std::ptr::null_mut()
        }
    }
}

/// Import snapshot data (contacts, ratchet states, messages) into a running core.
///
/// Returns 0 on success, non-zero on failure.
///
/// # Safety
/// `handle` must be a valid pointer; `snapshot_json` must be a valid null-terminated
/// UTF-8 C string containing a `DeviceSnapshot` JSON payload.
#[no_mangle]
pub unsafe extern "C" fn ada_import_snapshot_data(
    handle: *const AdaCoreHandle,
    snapshot_json: *const c_char,
) -> c_int {
    if handle.is_null() || snapshot_json.is_null() {
        return 1;
    }
    let h = &*handle;
    let json_str = match CStr::from_ptr(snapshot_json).to_str() {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        runtime().block_on(h.inner.import_snapshot_data(json_str))
    }));
    match result {
        Ok(Ok(())) => 0,
        Ok(Err(e)) => {
            tracing::error!("[ffi] ada_import_snapshot_data error: {}", e);
            1
        }
        Err(_) => {
            tracing::error!("[ffi] ada_import_snapshot_data panicked");
            1
        }
    }
}

/// Create a new ADA Core instance from a phone snapshot + user pattern.
///
/// - Derives database key from `pattern_cells` (same Argon2id as `ada_create_from_pattern`).
/// - Stores the snapshot identity in the encrypted KV so that subsequent
///   `ada_create_from_pattern` logins use the snapshot peer-id.
/// - Imports all contacts, ratchet sessions, and messages from the snapshot.
/// - Starts the core's background network tasks.
///
/// Returns a non-null handle on success, NULL on failure.
/// Caller must free with `ada_core_free`.
///
/// # Safety
/// - `pattern_cells` must point to `cells_len` readable bytes (canonical format: `[idx,color,…]`).
/// - `snapshot_json` must be a valid null-terminated UTF-8 C string.
/// - `data_dir` must be a valid null-terminated UTF-8 C string.
/// - `connection_profile` may be null; otherwise valid null-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ada_create_from_snapshot_with_pattern(
    pattern_cells: *const u8,
    cells_len: usize,
    snapshot_json: *const c_char,
    data_dir: *const c_char,
    connection_profile: *const c_char,
) -> *mut AdaCoreHandle {
    if pattern_cells.is_null() || snapshot_json.is_null() || data_dir.is_null() {
        log::error!("[snapshot] ada_create_from_snapshot_with_pattern: null pointer");
        return std::ptr::null_mut();
    }
    if cells_len != crate::pattern_auth::PATTERN_KEY_BYTES {
        log::error!(
            "[snapshot] ada_create_from_snapshot_with_pattern: invalid cells_len={}",
            cells_len
        );
        return std::ptr::null_mut();
    }

    let cells_slice = std::slice::from_raw_parts(pattern_cells, cells_len);
    let pattern = match crate::pattern_auth::PatternKey::from_bytes(cells_slice) {
        Ok(p) => p,
        Err(e) => {
            log::error!("[snapshot] PatternKey::from_bytes failed: {e:?}");
            return std::ptr::null_mut();
        }
    };

    let json_str = match CStr::from_ptr(snapshot_json).to_str() {
        Ok(s) => s.to_string(),
        Err(e) => {
            log::error!("[snapshot] invalid snapshot_json UTF-8: {e:?}");
            return std::ptr::null_mut();
        }
    };

    let dir = match CStr::from_ptr(data_dir).to_str() {
        Ok(s) => s.to_string(),
        Err(e) => {
            log::error!("[snapshot] invalid data_dir UTF-8: {e:?}");
            return std::ptr::null_mut();
        }
    };

    let mut config = crate::config::ADAConfig::for_mobile();
    config.storage.data_dir = dir;
    match read_connection_profile_arg(connection_profile) {
        Ok(Some(profile)) => config.network.connection_profile = profile,
        Ok(None) => {}
        Err(_) => {
            log::error!("[snapshot] invalid connection_profile");
            return std::ptr::null_mut();
        }
    }

    let core = runtime().block_on(async {
        crate::api::ADACore::from_snapshot_with_pattern(config, &json_str, &pattern).await
    });

    match core {
        Ok(c) => {
            log::info!(
                "[snapshot] ada_create_from_snapshot_with_pattern: core ready peer_id={}",
                c.peer_id()
            );
            runtime().block_on(async {
                if let Err(e) = c.start().await {
                    log::error!("[snapshot] core.start() failed: {e:?}");
                }
            });
            Box::into_raw(Box::new(AdaCoreHandle {
                inner: c,
                completed_transfers: std::sync::Mutex::new(std::collections::HashMap::new()),
                event_callback: parking_lot::Mutex::new(None),
                last_error: parking_lot::Mutex::new(None),
            }))
        }
        Err(e) => {
            log::error!("[snapshot] ada_create_from_snapshot_with_pattern failed: {e:?}");
            std::ptr::null_mut()
        }
    }
}

// ─── Device Sync Channel FFI ──────────────────────────────────────────────────

/// Return the hex-encoded 32-byte link key for the device sync channel.
/// Returns NULL if no link key is configured (device not paired).
/// The caller must free the returned C string with `ada_free_string`.
#[no_mangle]
pub unsafe extern "C" fn ada_get_link_key_hex(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    match h.inner.get_link_key_hex() {
        Some(hex) => match std::ffi::CString::new(hex) {
            Ok(cs) => cs.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Store the HTTP sync URL of the linked device.
/// `url` must be a valid null-terminated UTF-8 string.
/// Returns 0 on success, -1 on error.
#[no_mangle]
pub unsafe extern "C" fn ada_store_linked_device_sync_url(
    handle: *const AdaCoreHandle,
    url: *const c_char,
) -> c_int {
    if handle.is_null() || url.is_null() {
        return -1;
    }
    let h = &*handle;
    let url_str = match CStr::from_ptr(url).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };
    h.inner.store_linked_device_sync_url(url_str);
    0
}

/// Return the stored HTTP sync URL of the linked device, or NULL if none.
/// The caller must free the returned string with `ada_free_string`.
#[no_mangle]
pub unsafe extern "C" fn ada_get_linked_device_sync_url(
    handle: *const AdaCoreHandle,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    match h.inner.get_linked_device_sync_url() {
        Some(url) => match std::ffi::CString::new(url) {
            Ok(cs) => cs.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Decrypt and apply an incoming device sync push payload.
///
/// `link_key_hex` — 64-char hex string (32-byte key).
/// `data` — raw bytes of the encrypted push (`SYNC_MAGIC | nonce | ciphertext`).
/// `data_len` — byte count of `data`.
///
/// Returns 0 on success, -1 on error.
#[no_mangle]
pub unsafe extern "C" fn ada_handle_sync_push(
    handle: *const AdaCoreHandle,
    link_key_hex: *const c_char,
    data: *const u8,
    data_len: usize,
) -> c_int {
    if handle.is_null() || link_key_hex.is_null() || data.is_null() {
        return -1;
    }
    let h = &*handle;
    let key_hex = match CStr::from_ptr(link_key_hex).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let payload = std::slice::from_raw_parts(data, data_len);
    match runtime().block_on(h.inner.handle_sync_push(key_hex, payload)) {
        Ok(()) => 0,
        Err(e) => {
            log::error!("[sync] ada_handle_sync_push failed: {e:?}");
            -1
        }
    }
}

/// Encrypt a ChatMessage JSON for the sync channel.
///
/// `msg_json` — UTF-8 JSON serialised `ChatMessage` (from the KV store).
/// Returns the sealed bytes as a base64 string (for HTTP transport) or NULL on error.
/// The caller must free with `ada_free_string`.
#[no_mangle]
pub unsafe extern "C" fn ada_seal_sync_push_json(
    handle: *const AdaCoreHandle,
    msg_json: *const c_char,
) -> *mut c_char {
    if handle.is_null() || msg_json.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let json_str = match CStr::from_ptr(msg_json).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let msg: crate::storage::ChatMessage = match serde_json::from_str(json_str) {
        Ok(m) => m,
        Err(e) => {
            log::error!("[sync] seal_sync_push_json: bad msg json: {e}");
            return std::ptr::null_mut();
        }
    };
    match h.inner.seal_sync_push(&msg) {
        Some(sealed) => {
            use base64::Engine;
            let b64 = base64::engine::general_purpose::STANDARD.encode(&sealed);
            match std::ffi::CString::new(b64) {
                Ok(cs) => cs.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}

/// All pointer arguments must be valid null-terminated UTF-8 strings
#[no_mangle]
pub unsafe extern "C" fn ada_send_text(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    text: *const c_char,
    out_message_id: *mut u8, // 16 bytes
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || text.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;

    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let text_str = match CStr::from_ptr(text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };

    // в”Ђв”Ђ Two-phase send: store synchronously, deliver in background в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
    //
    // Calling `block_on(send_message(вЂ¦))` here would block the Android IO-thread
    // for up to CONNECT_TIMEOUT (15 s) + WRITE_TIMEOUT (20 s) = 35 s while iroh
    // performs DNS lookup + QUIC handshake.  That starves the entire IO dispatcher
    // pool and makes the UI unresponsive.
    //
    // Instead:
    //   1. `prepare_text_message` builds the Message struct + encrypts it в†’ stores
    //      it locally в†’ returns the 16-byte message-id.  This is fast (~1 ms).
    //   2. The actual network delivery is spawned as a background tokio task.
    //      The Kotlin side already shows the message optimistically; network errors
    //      are handled inside the task (iroh retry в†’ log warn).
    let arc_core = h.inner.clone();
    match runtime().block_on(async { arc_core.prepare_text_message(&peer, text_str, None).await }) {
        Ok((id, wire_bytes)) => {
            if !out_message_id.is_null() {
                std::slice::from_raw_parts_mut(out_message_id, 16).copy_from_slice(&id);
            }
            // Spawn background delivery вЂ” does not block JNI thread.
            runtime().spawn(async move {
                arc_core.deliver_wire(&peer, id, wire_bytes).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_send_text prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn ada_send_ephemeral_text(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    text: *const c_char,
    expires_in_secs: u32,
    out_message_id: *mut u8, // 16 bytes
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || text.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;

    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let text_str = match CStr::from_ptr(text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };

    let arc_core = h.inner.clone();
    match runtime().block_on(async {
        arc_core
            .prepare_ephemeral_text_message(&peer, text_str, expires_in_secs)
            .await
    }) {
        Ok((id, wire_bytes)) => {
            if !out_message_id.is_null() {
                std::slice::from_raw_parts_mut(out_message_id, 16).copy_from_slice(&id);
            }
            runtime().spawn(async move {
                arc_core.deliver_wire(&peer, id, wire_bytes).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_send_ephemeral_text prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Free a string returned by ADA FFI functions
///
/// # Safety
/// `s` must be a pointer returned by an ADA FFI function
#[no_mangle]
pub unsafe extern "C" fn ada_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

/// Take and clear the last detailed error recorded on this handle.
///
/// Returns null when there is no recorded error. The returned string must be
/// freed with `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid handle created by ADA Core, or null.
#[no_mangle]
pub unsafe extern "C" fn ada_take_last_error_message(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    match h
        .take_last_error()
        .and_then(|message| CString::new(message).ok())
    {
        Some(message) => message.into_raw(),
        None => std::ptr::null_mut(),
    }
}

/// Send a text reply to an existing message.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
/// `reply_to_msg_id_hex` is the hex-encoded 16-byte ID of the message being replied to.
#[no_mangle]
pub unsafe extern "C" fn ada_send_reply(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    text: *const c_char,
    reply_to_msg_id_hex: *const c_char,
    out_message_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || text.is_null() || reply_to_msg_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let text_str = match CStr::from_ptr(text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let reply_hex = match CStr::from_ptr(reply_to_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let reply_bytes = match hex::decode(reply_hex) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut reply_to_id = [0u8; 16];
    reply_to_id.copy_from_slice(&reply_bytes);

    let arc_core = h.inner.clone();
    match runtime().block_on(async {
        arc_core
            .prepare_reply_message(&peer, text_str, reply_to_id)
            .await
    }) {
        Ok((id, wire_bytes)) => {
            if !out_message_id.is_null() {
                std::slice::from_raw_parts_mut(out_message_id, 16).copy_from_slice(&id);
            }
            runtime().spawn(async move {
                arc_core.deliver_wire(&peer, id, wire_bytes).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_send_reply prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Send an emoji reaction to an existing message.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_send_reaction(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    target_msg_id_hex: *const c_char,
    emoji: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || target_msg_id_hex.is_null() || emoji.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let hex_str = match CStr::from_ptr(target_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let emoji_str = match CStr::from_ptr(emoji).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut target_id = [0u8; 16];
    target_id.copy_from_slice(&bytes);

    let arc_core = h.inner.clone();
    match runtime().block_on(async {
        arc_core
            .prepare_reaction_message(&peer, target_id, emoji_str)
            .await
    }) {
        Ok((id, wire_bytes)) => {
            runtime().spawn(async move {
                arc_core.deliver_wire(&peer, id, wire_bytes).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_send_reaction prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Callback type for receiving events
pub type AdaEventCallback = unsafe extern "C" fn(
    event_type: c_int,
    payload_json: *const c_char,
    user_data: *mut std::ffi::c_void,
);

/// Register an event callback.
///
/// The callback is invoked from a native worker thread, not from the UI thread.
///
/// # Safety
/// - `callback` must remain valid for the lifetime of the handle
/// - `user_data` is passed through unchanged
#[no_mangle]
pub unsafe extern "C" fn ada_set_event_callback(
    handle: *mut AdaCoreHandle,
    callback: AdaEventCallback,
    user_data: *mut std::ffi::c_void,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }

    let h = &*handle;
    stop_event_callback_worker(h);

    let arc_core = h.inner.clone();
    let cb = callback as usize;
    let ud = user_data as usize;
    let stop_requested = Arc::new(AtomicBool::new(false));
    let worker_stop = stop_requested.clone();

    let thread = std::thread::spawn(move || {
        let callback_fn: AdaEventCallback = std::mem::transmute(cb);
        let user_data_ptr = ud as *mut std::ffi::c_void;

        while !worker_stop.load(Ordering::Acquire) {
            let event =
                runtime().block_on(async { arc_core.poll_event(CALLBACK_POLL_TIMEOUT_MS).await });
            if let Some(e) = event {
                // Map event to an arbitrary type code
                let event_type = match &e {
                    crate::api::ADAEvent::NetworkConnected => 1,
                    crate::api::ADAEvent::NetworkDisconnected => 2,
                    crate::api::ADAEvent::PeerOnline(_) => 3,
                    crate::api::ADAEvent::PeerOffline(_) => 4,
                    crate::api::ADAEvent::MessageReceived(_) => 5,
                    crate::api::ADAEvent::MessageStatusChanged { .. } => 6,
                    crate::api::ADAEvent::MessageRouteChanged { .. } => 7,
                    crate::api::ADAEvent::IncomingCall { .. } => 8,
                    crate::api::ADAEvent::IceCandidate { .. } => 9,
                    crate::api::ADAEvent::CallStateChanged { .. } => 10,
                    crate::api::ADAEvent::TransferEvent(_) => 11,
                    crate::api::ADAEvent::PeerDiscovered(_) => 12,
                    crate::api::ADAEvent::GroupInviteReceived { .. } => 13,
                    crate::api::ADAEvent::GroupJoined { .. } => 14,
                    crate::api::ADAEvent::ContactUpdated(_) => 15,
                    crate::api::ADAEvent::BlobAvailable { .. } => 16,
                    _ => 0,
                };

                let json = event_to_json(&e, Some(&arc_core));
                if let Ok(s) = CString::new(json) {
                    callback_fn(event_type, s.as_ptr(), user_data_ptr);
                }
            }
        }
    });

    *h.event_callback.lock() = Some(CallbackWorker {
        stop_requested,
        thread,
    });

    AdaResult::Ok
}

/// Clear a previously registered event callback.
///
/// # Safety
/// `handle` must be a valid pointer from `ada_core_create`.
#[no_mangle]
pub unsafe extern "C" fn ada_clear_event_callback(handle: *mut AdaCoreHandle) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }

    let h = &*handle;
    stop_event_callback_worker(h);
    AdaResult::Ok
}

/// Start an audio call
/// Initiate an audio call. Returns call-id via `out_call_id` (16 bytes).
///
/// # Safety
/// `handle` must be valid, `peer_id_b64` a valid C string,
/// `offer_sdp` a valid C string, `out_call_id` must point to 16 writable bytes.
#[no_mangle]
pub unsafe extern "C" fn ada_call_audio(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    offer_sdp: *const c_char,
    out_call_id: *mut u8, // 16 bytes
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || offer_sdp.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    // Phase 1: fast вЂ” validate route + register call state (no network I/O, < 1 ms).
    match runtime().block_on(async {
        h.inner
            .prepare_call_audio(peer.clone(), offer_str.clone())
            .await
    }) {
        Ok((call_id, offer)) => {
            if !out_call_id.is_null() {
                std::slice::from_raw_parts_mut(out_call_id, 16).copy_from_slice(&call_id);
            }
            // Phase 2: deliver invite in background вЂ” does not block JNI thread.
            let arc_core = h.inner.clone();
            runtime().spawn(async move {
                arc_core
                    .deliver_call_invite_bg(peer, call_id, offer, false)
                    .await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_call_audio prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Send an edit for a previously sent direct text message.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_edit_message(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    target_msg_id_hex: *const c_char,
    new_text: *const c_char,
    out_message_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || target_msg_id_hex.is_null() || new_text.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let target_hex = match CStr::from_ptr(target_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let new_text_str = match CStr::from_ptr(new_text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let target_bytes = match hex::decode(target_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let mut target_msg_id = [0u8; 16];
    target_msg_id.copy_from_slice(&target_bytes);

    let arc_core = h.inner.clone();
    match runtime().block_on(async {
        arc_core
            .prepare_edit_message(&peer, target_msg_id, new_text_str)
            .await
    }) {
        Ok((id, wire_bytes)) => {
            if !out_message_id.is_null() {
                std::slice::from_raw_parts_mut(out_message_id, 16).copy_from_slice(&id);
            }
            runtime().spawn(async move {
                arc_core.deliver_wire(&peer, id, wire_bytes).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_edit_message prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Hang up a call
/// Hang up an active call.
///
/// # Safety
/// `handle` must be valid, `call_id` must point to 16 readable bytes,
/// `peer_id_b64` must be a valid null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_hangup(
    handle: *const AdaCoreHandle,
    call_id: *const u8, // 16 bytes
    peer_id_b64: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if call_id.is_null() || peer_id_b64.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    match runtime().block_on(async { h.inner.hangup(cid, peer).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Decline (reject) an incoming call. Sends HangupReason::Declined to the peer.
///
/// # Safety: `handle` must be valid; `call_id` points to 16 bytes; `peer_id_b64` is a valid C string.
/// Decline an incoming call.
///
/// # Safety
/// `handle` must be valid, `call_id` must point to 16 readable bytes,
/// `peer_id_b64` must be a valid null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_decline_call(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    peer_id_b64: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if call_id.is_null() || peer_id_b64.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    match runtime().block_on(async { h.inner.decline_call(cid, peer).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Add a bridge for censorship circumvention.
///
/// # Safety
/// `handle` must be valid (non-null), `bridge_line` must be a valid
/// null-terminated UTF-8 string in Tor bridge-line format.
#[no_mangle]
pub unsafe extern "C" fn ada_add_bridge(
    handle: *mut AdaCoreHandle,
    bridge_line: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    let h = &*handle;
    h.clear_last_error();
    if bridge_line.is_null() {
        h.set_last_error("bridge line is missing");
        return AdaResult::InvalidArg;
    }
    let line = match CStr::from_ptr(bridge_line).to_str() {
        Ok(s) => s.to_string(),
        Err(error) => {
            h.set_last_error(format!("bridge line must be valid UTF-8: {}", error));
            return AdaResult::InvalidArg;
        }
    };
    match runtime().block_on(async { h.inner.add_bridge(&line).await }) {
        Ok(_) => AdaResult::Ok,
        Err(error) => {
            h.set_last_error(error.to_string());
            AdaResult::Error
        }
    }
}

#[derive(Clone)]
struct TextEditOverlay {
    edited_at: u64,
    edited_text: String,
}

fn is_hidden_protocol_message(kind: &crate::messaging::types::MessageKind) -> bool {
    matches!(
        kind,
        crate::messaging::types::MessageKind::GroupJoinAccept { .. }
            | crate::messaging::types::MessageKind::FileChunk { .. }
            | crate::messaging::types::MessageKind::ChunkRequest { .. }
            | crate::messaging::types::MessageKind::DeleteRequest { .. }
            | crate::messaging::types::MessageKind::Edit { .. }
            | crate::messaging::types::MessageKind::SyncRequest { .. }
            | crate::messaging::types::MessageKind::SyncResponse { .. }
            | crate::messaging::types::MessageKind::Reaction { .. }
            | crate::messaging::types::MessageKind::IrohHint { .. }
    )
}

fn build_text_edit_overlays(
    messages: &[crate::messaging::types::Message],
) -> std::collections::HashMap<[u8; 16], TextEditOverlay> {
    let by_id: std::collections::HashMap<[u8; 16], &crate::messaging::types::Message> = messages
        .iter()
        .map(|message| (message.id, message))
        .collect();
    let mut overlays = std::collections::HashMap::new();

    for message in messages {
        let crate::messaging::types::MessageKind::Edit {
            target_msg_id,
            new_text,
        } = &message.kind
        else {
            continue;
        };

        let Some(target) = by_id.get(target_msg_id) else {
            continue;
        };
        if target.sender != message.sender {
            continue;
        }
        if !matches!(target.kind, crate::messaging::types::MessageKind::Text(_)) {
            continue;
        }

        let replace = overlays
            .get(target_msg_id)
            .map(|overlay: &TextEditOverlay| message.timestamp >= overlay.edited_at)
            .unwrap_or(true);
        if replace {
            overlays.insert(
                *target_msg_id,
                TextEditOverlay {
                    edited_at: message.timestamp,
                    edited_text: new_text.clone(),
                },
            );
        }
    }

    overlays
}

struct ConversationPreview {
    text: String,
    kind: &'static str,
    mime_type: String,
    has_visible_message: bool,
}

fn attachment_preview_text(name: &str, mime_type: &str) -> String {
    let lower_name = name.to_ascii_lowercase();
    let lower_mime = mime_type.to_ascii_lowercase();

    if lower_mime.starts_with("audio/") || lower_name.starts_with("voice_") || lower_name.contains("voice") {
        "voice".to_string()
    } else if lower_mime.starts_with("image/") {
        "image".to_string()
    } else if lower_mime.starts_with("video/") {
        "video".to_string()
    } else if name.trim().is_empty() {
        "file".to_string()
    } else {
        name.chars().take(80).collect()
    }
}

fn conversation_last_message_preview(
    core: &crate::api::ADACore,
    conversation_id: &crate::messaging::store::ConversationId,
) -> ConversationPreview {
    let conv_messages = core.get_messages(conversation_id, usize::MAX);
    let text_edits = build_text_edit_overlays(&conv_messages);

    conv_messages
        .iter()
        .rev()
        .find(|message| !is_hidden_protocol_message(&message.kind))
        .and_then(|message| match &message.kind {
            crate::messaging::types::MessageKind::Text(text) => {
                let preview: String = text_edits
                    .get(&message.id)
                    .map(|overlay| overlay.edited_text.clone())
                    .unwrap_or_else(|| text.clone())
                    .chars()
                    .take(80)
                    .collect();
                Some(ConversationPreview {
                    text: if preview.trim().is_empty() {
                        "message".to_string()
                    } else {
                        preview
                    },
                    kind: "text",
                    mime_type: String::new(),
                    has_visible_message: true,
                })
            }
            crate::messaging::types::MessageKind::File {
                name, mime_type, ..
            } => {
                Some(ConversationPreview {
                    text: attachment_preview_text(name, mime_type),
                    kind: "file",
                    mime_type: mime_type.clone(),
                    has_visible_message: true,
                })
            }
            crate::messaging::types::MessageKind::BlobRef {
                name, mime_type, ..
            } => {
                Some(ConversationPreview {
                    text: attachment_preview_text(name, mime_type),
                    kind: "blob_ref",
                    mime_type: mime_type.clone(),
                    has_visible_message: true,
                })
            }
            crate::messaging::types::MessageKind::Call(event) => {
                let text = match event {
                    crate::messaging::types::CallEvent::Invite { has_video, .. } => {
                        if *has_video { "video_call" } else { "audio_call" }
                    }
                    _ => "call",
                };
                Some(ConversationPreview {
                    text: text.to_string(),
                    kind: "call",
                    mime_type: String::new(),
                    has_visible_message: true,
                })
            }
            crate::messaging::types::MessageKind::GroupInvite { group_name, .. } => {
                Some(ConversationPreview {
                    text: group_name.chars().take(80).collect(),
                    kind: "group_invite",
                    mime_type: String::new(),
                    has_visible_message: true,
                })
            }
            crate::messaging::types::MessageKind::GroupCallStart { has_video, .. } => {
                Some(ConversationPreview {
                    text: if *has_video { "group_video_call" } else { "group_audio_call" }.to_string(),
                    kind: "group_call",
                    mime_type: String::new(),
                    has_visible_message: true,
                })
            }
            _ => None,
        })
        .unwrap_or_else(|| ConversationPreview {
            text: String::new(),
            kind: "none",
            mime_type: String::new(),
            has_visible_message: false,
        })
}

fn conversations_to_json(
    core: &crate::api::ADACore,
    conversations: &[crate::messaging::store::Conversation],
) -> String {
    let json_arr: Vec<serde_json::Value> = conversations
        .iter()
        .map(|conversation| {
            let preview = conversation_last_message_preview(core, &conversation.id);
            let id_str = match &conversation.id {
                crate::messaging::store::ConversationId::Direct(peer) => {
                    format!("d:{}", peer.to_base64())
                }
                crate::messaging::store::ConversationId::Group(group_id) => {
                    format!("g:{}", hex::encode(group_id))
                }
            };

            serde_json::json!({
                "id": id_str,
                "display_name": conversation.display_name,
                "last_message": preview.text,
                "last_kind": preview.kind,
                "last_mime_type": preview.mime_type,
                "has_messages": preview.has_visible_message,
                "last_activity_ms": conversation.last_activity_ms,
                "unread_count": conversation.unread_count,
            })
        })
        .collect();

    serde_json::to_string(&json_arr).unwrap_or_else(|_| "[]".to_string())
}

// в”Ђв”Ђв”Ђ Query / polling helpers (JSON-based, for JNI) в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Returns own display name as a null-terminated string.
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn ada_get_display_name(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    match CString::new(h.inner.display_name()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Returns a JSON array of conversations sorted by last activity.
/// Schema: [{"id":"d:BASE64"|"g:HEX","display_name":"...","last_message":"...","last_activity_ms":N,"unread_count":N}]
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn ada_get_conversations_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let convs = h.inner.conversations();
    let json = conversations_to_json(&h.inner, &convs);
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Returns a JSON array of conversations filtered by display name or local message history.
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` and `query` must be valid pointers.
#[no_mangle]
pub unsafe extern "C" fn ada_search_conversations_json(
    handle: *const AdaCoreHandle,
    query: *const c_char,
) -> *mut c_char {
    if handle.is_null() || query.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let query_str = match CStr::from_ptr(query).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let convs = h.inner.search_conversations(query_str);
    let json = conversations_to_json(&h.inner, &convs);
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Returns a JSON array of messages for a conversation.
/// `conv_id_str` is "d:BASE64" for direct or "g:HEX" for group.
/// `limit` is max number of messages (0 = use default 50).
/// Schema: [{"id":"HEX","sender":"BASE64","text":"...","timestamp_ms":N,"is_mine":true,"status":"sent","kind":"text"}]
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_messages_json(
    handle: *const AdaCoreHandle,
    conv_id_str: *const c_char,
    limit: c_uint,
) -> *mut c_char {
    if handle.is_null() || conv_id_str.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;

    let conv_str = match CStr::from_ptr(conv_id_str).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let conv_id = match parse_conv_id(conv_str) {
        Some(c) => c,
        None => return std::ptr::null_mut(),
    };

    let lim = if limit == 0 { 50 } else { limit as usize };
    let messages = h.inner.get_messages(&conv_id, lim);
    let my_id = h.inner.peer_id().to_base64();
    let text_edits = build_text_edit_overlays(&messages);

    // Build reactions map: target_msg_id_hex -> [(sender_b64, emoji)]
    let mut reactions_map: std::collections::HashMap<String, Vec<(String, String)>> =
        std::collections::HashMap::new();
    for m in &messages {
        if let crate::messaging::types::MessageKind::Reaction {
            target_msg_id,
            ref emoji,
        } = m.kind
        {
            reactions_map
                .entry(hex::encode(target_msg_id))
                .or_default()
                .push((m.sender.to_base64(), emoji.clone()));
        }
    }

    // Pre-compute toggle-aware reaction state per message.
    // For each (sender, emoji) pair: odd count в†’ active, even count в†’ cancelled.
    struct ReactionState {
        counts: std::collections::HashMap<String, i32>, // emoji -> active-count
        my_emojis: Vec<String>,                         // this user's active emojis
    }
    let mut reaction_states: std::collections::HashMap<String, ReactionState> =
        std::collections::HashMap::new();
    for (target_hex, entries) in &reactions_map {
        // Count per (sender, emoji) pair
        let mut pair_counts: std::collections::HashMap<(&str, &str), u32> =
            std::collections::HashMap::new();
        for (sender, emoji) in entries {
            *pair_counts
                .entry((sender.as_str(), emoji.as_str()))
                .or_default() += 1;
        }
        let mut counts: std::collections::HashMap<String, i32> = std::collections::HashMap::new();
        let mut my_emojis: Vec<String> = Vec::new();
        for ((sender, emoji), cnt) in &pair_counts {
            if cnt % 2 == 1 {
                // Odd в†’ reaction is active
                *counts.entry(emoji.to_string()).or_default() += 1;
                if *sender == my_id {
                    my_emojis.push(emoji.to_string());
                }
            }
        }
        reaction_states.insert(target_hex.clone(), ReactionState { counts, my_emojis });
    }
    // Build a lookup for reply_to_text: msg_id -> text preview
    let text_lookup: std::collections::HashMap<[u8; 16], String> = messages
        .iter()
        .filter_map(|m| match &m.kind {
            crate::messaging::types::MessageKind::Text(t) => Some((
                m.id,
                text_edits
                    .get(&m.id)
                    .map(|overlay| overlay.edited_text.clone())
                    .unwrap_or_else(|| t.clone())
                    .chars()
                    .take(80)
                    .collect(),
            )),
            _ => None,
        })
        .collect();

    let json_arr: Vec<serde_json::Value> = messages
        .iter()
        .filter_map(|m| {
            let sender_b64 = m.sender.to_base64();
            let is_mine = sender_b64 == my_id;
            let edit_overlay = text_edits.get(&m.id);
            let (kind_str, text, mime_type, file_id, file_size, call_session_id, has_video) =
                match &m.kind {
                    crate::messaging::types::MessageKind::Text(t) => (
                        "text",
                        edit_overlay
                            .map(|overlay| overlay.edited_text.clone())
                            .unwrap_or_else(|| t.clone()),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::File {
                        name,
                        mime_type,
                        file_id,
                        size,
                        ..
                    } => (
                        "file",
                        name.clone(),
                        mime_type.clone(),
                        hex::encode(file_id),
                        *size,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::Call(_) => (
                        "call",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::GroupInvite { group_name, .. } => (
                        "group_invite",
                        group_name.clone(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::GroupCallStart {
                        session_id,
                        has_video,
                    } => (
                        "group_call",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        Some(hex::encode(session_id)),
                        Some(*has_video),
                    ),
                    crate::messaging::types::MessageKind::GroupJoinAccept { .. } => (
                        "group_join_accept",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::FileChunk { .. } => (
                        "file_chunk",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::ChunkRequest { .. } => (
                        "chunk_request",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::DeleteRequest { .. } => (
                        "delete_request",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::Edit { .. } => return None,
                    crate::messaging::types::MessageKind::BlobRef {
                        name,
                        mime_type,
                        file_id,
                        size,
                        ..
                    } => (
                        "blob_ref",
                        name.clone(),
                        mime_type.clone(),
                        hex::encode(file_id),
                        *size,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::IrohHint { .. } => (
                        "iroh_hint",
                        String::new(),
                        String::new(),
                        String::new(),
                        0u64,
                        None,
                        None,
                    ),
                    crate::messaging::types::MessageKind::SyncRequest { .. } => return None,
                    crate::messaging::types::MessageKind::SyncResponse { .. } => return None,
                    crate::messaging::types::MessageKind::Reaction { .. } => return None,
                };
            let status = match &m.status {
                crate::messaging::types::MessageStatus::Sending => "sending",
                crate::messaging::types::MessageStatus::Sent => "sent",
                crate::messaging::types::MessageStatus::Delivered => "delivered",
                crate::messaging::types::MessageStatus::Read => "read",
                crate::messaging::types::MessageStatus::Failed(_) => "failed",
            };
            let msg_hex = hex::encode(m.id);
            // Toggle-aware reactions: {"emoji": active_count, ...}
            let (reactions_json, my_reactions_json) = match reaction_states.get(&msg_hex) {
                Some(state) => {
                    let obj: serde_json::Map<String, serde_json::Value> = state
                        .counts
                        .iter()
                        .filter(|(_, &v)| v > 0)
                        .map(|(k, v)| (k.clone(), serde_json::Value::Number((*v).into())))
                        .collect();
                    let my_arr: Vec<serde_json::Value> = state
                        .my_emojis
                        .iter()
                        .map(|e| serde_json::Value::String(e.clone()))
                        .collect();
                    (
                        if obj.is_empty() {
                            serde_json::Value::Null
                        } else {
                            serde_json::Value::Object(obj)
                        },
                        serde_json::Value::Array(my_arr),
                    )
                }
                None => (serde_json::Value::Null, serde_json::Value::Array(vec![])),
            };
            // Reply-to
            let reply_to_hex = m.reply_to.map(|id| hex::encode(id));
            let reply_to_text = m.reply_to.and_then(|id| text_lookup.get(&id).cloned());
            Some(serde_json::json!({
                "id": msg_hex,
                "sender": sender_b64,
                "text": text,
                "timestamp_ms": m.timestamp * 1000,
                "is_mine": is_mine,
                "status": status,
                "kind": kind_str,
                "mime_type": mime_type,
                "file_id": file_id,
                "file_size": file_size,
                "call_session_id": call_session_id,
                "has_video": has_video,
                "reactions": reactions_json,
                "my_reactions": my_reactions_json,
                "reply_to_id": reply_to_hex,
                "reply_to_text": reply_to_text,
                "expires_in_secs": m.expires_in,
                "is_edited": edit_overlay.is_some(),
            }))
        })
        .collect();

    let json = serde_json::to_string(&json_arr).unwrap_or_else(|_| "[]".to_string());
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Poll one pending event as a JSON string.
/// Will block up to `timeout_ms` waiting for an event. Returns null if none arrived.
/// Schema: {"type":"MessageReceived","data":{...}}
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn ada_poll_event_json(
    handle: *const AdaCoreHandle,
    timeout_ms: c_int,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;

    let timeout = if timeout_ms < 0 { 0 } else { timeout_ms as u32 };
    let event = runtime().block_on(async { h.inner.poll_event(timeout).await });
    let event = match event {
        Some(e) => e,
        None => return std::ptr::null_mut(),
    };

    // Capture completed inbound transfer data before event_to_json discards it
    if let crate::api::ADAEvent::TransferEvent(crate::transfer::TransferEvent::Completed {
        ref id,
        ref data,
        ref meta,
    }) = event
    {
        if !meta.is_outbound {
            cache_completed_inbound_transfer(
                h,
                hex::encode(id),
                data,
                meta.file_name.clone(),
                meta.mime_type.clone(),
                meta.file_size,
            );
        }
    }

    let json = event_to_json(&event, Some(&h.inner));
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Mark all messages in a conversation as read.
///
/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_mark_read(
    handle: *const AdaCoreHandle,
    conv_id_str: *const c_char,
) -> AdaResult {
    if handle.is_null() || conv_id_str.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let conv_str = match CStr::from_ptr(conv_id_str).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    if let Some(conv_id) = parse_conv_id(conv_str) {
        h.inner.mark_read(&conv_id);
        AdaResult::Ok
    } else {
        AdaResult::InvalidArg
    }
}

// в”Ђв”Ђв”Ђ Internal helpers в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_delete_message(
    handle: *const AdaCoreHandle,
    msg_id_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() || msg_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes);
    match h.inner.delete_message(&id) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_delete_conversation(
    handle: *const AdaCoreHandle,
    conv_id_str: *const c_char,
) -> AdaResult {
    if handle.is_null() || conv_id_str.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let conv_str = match CStr::from_ptr(conv_id_str).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    if let Some(conv_id) = parse_conv_id(conv_str) {
        match h.inner.delete_conversation(&conv_id) {
            Ok(_) => AdaResult::Ok,
            Err(_) => AdaResult::Error,
        }
    } else {
        AdaResult::InvalidArg
    }
}

/// # Safety
/// All pointer arguments must be valid.
/// Clears all messages in a conversation but keeps the conversation entry.
#[no_mangle]
pub unsafe extern "C" fn ada_clear_conversation_messages(
    handle: *const AdaCoreHandle,
    conv_id_str: *const c_char,
) -> AdaResult {
    if handle.is_null() || conv_id_str.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let conv_str = match CStr::from_ptr(conv_id_str).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    if let Some(conv_id) = parse_conv_id(conv_str) {
        match h.inner.clear_conversation_messages(&conv_id) {
            Ok(_) => AdaResult::Ok,
            Err(_) => AdaResult::Error,
        }
    } else {
        AdaResult::InvalidArg
    }
}

/// # Safety
/// All pointer arguments must be valid.
/// Deletes the message locally and sends a DeleteRequest to the peer.
#[no_mangle]
pub unsafe extern "C" fn ada_delete_message_for_everyone(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    msg_id_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() || peer_id_b64.is_null() || msg_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let hex_str = match CStr::from_ptr(msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes);
    // Phase 1: delete locally (fast, synchronous).
    match h.inner.delete_message_local(&id) {
        Ok(_) => {
            // Phase 2: deliver DeleteRequest to the peer in the background.
            let arc_core = h.inner.clone();
            runtime().spawn(async move {
                arc_core.deliver_delete_request_bg(peer, id).await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_delete_message_for_everyone local delete failed: {}", e);
            AdaResult::Error
        }
    }
}

fn parse_conv_id(s: &str) -> Option<crate::messaging::store::ConversationId> {
    if let Some(b64) = s.strip_prefix("d:") {
        crate::identity::PeerId::from_base64(b64)
            .ok()
            .map(crate::messaging::store::ConversationId::Direct)
    } else if let Some(hex_str) = s.strip_prefix("g:") {
        let bytes = hex::decode(hex_str).ok()?;
        if bytes.len() != 16 {
            return None;
        }
        let mut arr = [0u8; 16];
        arr.copy_from_slice(&bytes);
        Some(crate::messaging::store::ConversationId::Group(arr))
    } else {
        None
    }
}

fn append_group_call_room_fields(
    obj: &mut serde_json::Value,
    room: Option<crate::api::GroupCallRoomSnapshot>,
) {
    let Some(room) = room else {
        return;
    };
    obj["group_id"] = serde_json::Value::String(hex::encode(room.group_id));
    obj["call_session_id"] = serde_json::Value::String(hex::encode(room.session_id));
    obj["participants"] = serde_json::Value::Array(
        room.participants
            .into_iter()
            .map(|participant| serde_json::Value::String(participant.to_base64()))
            .collect(),
    );
}

fn event_to_json(event: &crate::api::ADAEvent, core: Option<&crate::api::ADACore>) -> String {
    use crate::api::ADAEvent;
    let v = match event {
        ADAEvent::NetworkConnected => serde_json::json!({"type": "NetworkConnected"}),
        ADAEvent::NetworkDisconnected => serde_json::json!({"type": "NetworkDisconnected"}),
        ADAEvent::PeerOnline(p) => {
            serde_json::json!({"type": "PeerOnline", "peer_id": p.to_base64()})
        }
        ADAEvent::PeerOffline(p) => {
            serde_json::json!({"type": "PeerOffline", "peer_id": p.to_base64()})
        }
        ADAEvent::PeerDiscovered(b) => serde_json::json!({
            "type": "PeerDiscovered",
            "peer_id": b.peer_id.to_base64(),
            "display_name": b.display_name,
        }),
        ADAEvent::MessageReceived(m) => {
            let text = match &m.kind {
                crate::messaging::types::MessageKind::Text(t) => t.clone(),
                _ => String::new(),
            };
            let sender_b64 = m.sender.to_base64();
            let sender_name = core
                .map(|c| c.get_sender_display_name(&m.sender))
                .unwrap_or_default();
            let group_id = m.group_id.map(hex::encode);
            serde_json::json!({
                "type": "MessageReceived",
                "message_id": hex::encode(m.id),
                "sender": sender_b64,
                "sender_name": sender_name,
                "recipient": m.recipient.as_ref().map(|p| p.to_base64()),
                "group_id": group_id,
                "text": text,
                "timestamp_ms": m.timestamp * 1000,
            })
        }
        ADAEvent::MessageStatusChanged { message_id, status } => {
            let status_str = match status {
                crate::messaging::types::MessageStatus::Sent => "sent",
                crate::messaging::types::MessageStatus::Delivered => "delivered",
                crate::messaging::types::MessageStatus::Read => "read",
                crate::messaging::types::MessageStatus::Failed(_) => "failed",
                crate::messaging::types::MessageStatus::Sending => "sending",
            };
            serde_json::json!({
                "type": "MessageStatusChanged",
                "message_id": hex::encode(message_id),
                "status": status_str,
            })
        }
        ADAEvent::MessageEdited { target_message_id } => serde_json::json!({
            "type": "MessageEdited",
            "target_message_id": hex::encode(target_message_id),
        }),
        ADAEvent::MessageRouteChanged {
            message_id,
            route,
            queue_depth,
            latency_ms,
        } => {
            serde_json::json!({
                "type": "MessageRouteChanged",
                "message_id": hex::encode(message_id),
                "route": route,
                "queue_depth": queue_depth,
                "latency_ms": latency_ms,
            })
        }
        ADAEvent::IncomingCall {
            call_id,
            from,
            has_video,
            offer_sdp,
            room,
        } => {
            let mut obj = serde_json::json!({
                "type": "IncomingCall",
                "call_id": hex::encode(call_id),
                "peer": from.to_base64(),
                "has_video": has_video,
                "offer_sdp": offer_sdp,
            });
            append_group_call_room_fields(&mut obj, room.clone());
            obj
        }
        ADAEvent::GroupInviteReceived {
            group_id,
            group_name,
            from,
        } => serde_json::json!({
            "type": "GroupInviteReceived",
            "group_id": hex::encode(group_id),
            "group_name": group_name,
            "from": from.to_base64(),
        }),
        ADAEvent::GroupJoined {
            group_id,
            group_name,
        } => serde_json::json!({
            "type": "GroupJoined",
            "group_id": hex::encode(group_id),
            "group_name": group_name,
        }),
        ADAEvent::ContactUpdated(bundle) => serde_json::json!({
            "type": "ContactUpdated",
            "peer_id": bundle.peer_id.to_base64(),
            "display_name": bundle.display_name,
            "dh_public": hex::encode(bundle.dh_public),
        }),
        ADAEvent::SendViaLocalMesh { peer, payload } => {
            use base64::Engine;
            serde_json::json!({
                "type": "SendViaLocalMesh",
                "peer": peer.to_base64(),
                "payload": base64::engine::general_purpose::STANDARD.encode(payload),
            })
        }
        ADAEvent::TransferEvent(te) => {
            let (tid, ev_type, progress, file_name, mime_type, file_size, reason) = match te {
                crate::transfer::TransferEvent::Started { id, meta } => (
                    hex::encode(id),
                    "started",
                    0.0f32,
                    meta.file_name.clone(),
                    meta.mime_type.clone(),
                    meta.file_size,
                    String::new(),
                ),
                crate::transfer::TransferEvent::Progress { id, progress } => (
                    hex::encode(id),
                    "progress",
                    *progress,
                    String::new(),
                    String::new(),
                    0u64,
                    String::new(),
                ),
                crate::transfer::TransferEvent::Completed { id, meta, .. } => (
                    hex::encode(id),
                    "completed",
                    1.0,
                    meta.file_name.clone(),
                    meta.mime_type.clone(),
                    meta.file_size,
                    String::new(),
                ),
                crate::transfer::TransferEvent::Failed { id, reason } => (
                    hex::encode(id),
                    "failed",
                    0.0,
                    String::new(),
                    String::new(),
                    0u64,
                    reason.clone(),
                ),
                crate::transfer::TransferEvent::Cancelled { id } => (
                    hex::encode(id),
                    "cancelled",
                    0.0,
                    String::new(),
                    String::new(),
                    0u64,
                    String::new(),
                ),
            };
            serde_json::json!({
                "type": "TransferEvent",
                "transfer_id": tid,
                "event": ev_type,
                "progress": progress,
                "file_name": file_name,
                "mime_type": mime_type,
                "file_size": file_size,
                "reason": reason,
            })
        }
        ADAEvent::IceCandidate {
            call_id,
            peer,
            candidate,
            sdp_mid,
            sdp_mline_index,
        } => serde_json::json!({
            "type": "IceCandidate",
            "call_id": hex::encode(call_id),
            "peer": peer.to_base64(),
            "candidate": candidate,
            "sdp_mid": sdp_mid,
            "sdp_mline_index": sdp_mline_index,
        }),
        ADAEvent::IceRestartOffer {
            call_id,
            peer,
            offer_sdp,
        } => serde_json::json!({
            "type": "IceRestartOffer",
            "call_id": hex::encode(call_id),
            "peer": peer.to_base64(),
            "offer_sdp": offer_sdp,
        }),
        ADAEvent::CallStateChanged {
            call_id,
            peer,
            has_video,
            state,
            answer_sdp,
        } => {
            use crate::media::call::CallState;
            let state_str = match state {
                CallState::Ringing => "Ringing",
                CallState::IncomingRinging => "IncomingRinging",
                CallState::Connecting => "Connecting",
                CallState::Active { .. } => "Active",
                CallState::Ended { .. } => "Ended",
                CallState::Failed(_) => "Failed",
            };
            let mut obj = serde_json::json!({
                "type": "CallStateChanged",
                "call_id": hex::encode(call_id),
                "peer": peer.to_base64(),
                "has_video": has_video,
                "state": state_str,
            });
            if let Some(sdp) = answer_sdp {
                obj["answer_sdp"] = serde_json::Value::String(sdp.clone());
            }
            append_group_call_room_fields(
                &mut obj,
                core.and_then(|inner| inner.get_group_call_room_for_call(*call_id)),
            );
            obj
        }
        ADAEvent::Error(e) => serde_json::json!({"type": "Error", "message": e}),
        ADAEvent::BlobAvailable {
            from,
            file_id,
            file_name,
            file_size,
            mime_type,
            hash,
        } => serde_json::json!({
            "type": "BlobAvailable",
            "from": from.to_base64(),
            "file_id": hex::encode(file_id),
            "file_name": file_name,
            "file_size": file_size,
            "mime_type": mime_type,
            "hash": hex::encode(hash),
        }),
    };
    v.to_string()
}

// в”Ђв”Ђв”Ђ Phase 5: Call management в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Answer an incoming call and send the SDP answer to the peer via messaging.
///
/// # Safety: `handle` valid, `call_id` points to 16 bytes, `peer_id_b64` valid C string.
#[no_mangle]
pub unsafe extern "C" fn ada_answer_call(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    peer_id_b64: *const c_char,
    answer_sdp: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if call_id.is_null() || peer_id_b64.is_null() || answer_sdp.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let answer_str = match CStr::from_ptr(answer_sdp).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    // Phase 1: update local call state in CallManager (fast, no network I/O).
    match runtime().block_on(async { h.inner.prepare_call_answer(cid, answer_str).await }) {
        Ok(prepared_answer) => {
            // Phase 2: deliver the answer over the network in the background.
            let arc_core = h.inner.clone();
            runtime().spawn(async move {
                arc_core
                    .deliver_call_answer_bg(peer, cid, prepared_answer)
                    .await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_answer_call prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Initiate a video call. Returns call-id via `out_call_id` (16 bytes).
///
/// # Safety: same as `ada_call_audio`.
#[no_mangle]
pub unsafe extern "C" fn ada_call_video(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    offer_sdp: *const c_char,
    out_call_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || offer_sdp.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    // Phase 1: fast вЂ” validate route + register call state (no network I/O, < 1 ms).
    match runtime().block_on(async {
        h.inner
            .prepare_call_video(peer.clone(), offer_str.clone())
            .await
    }) {
        Ok((call_id, offer)) => {
            if !out_call_id.is_null() {
                std::slice::from_raw_parts_mut(out_call_id, 16).copy_from_slice(&call_id);
            }
            // Phase 2: deliver invite in background вЂ” does not block JNI thread.
            let arc_core = h.inner.clone();
            runtime().spawn(async move {
                arc_core
                    .deliver_call_invite_bg(peer, call_id, offer, true)
                    .await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_call_video prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Prepare and deliver a pairwise call invite that belongs to a shared group-call room.
/// `group_id_hex` and `session_id_hex` must be 32-char lowercase hex strings.
/// `offer_sdp` must be a valid C string, `out_call_id` must point to 16 writable bytes.
#[no_mangle]
pub unsafe extern "C" fn ada_call_in_group_room(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    offer_sdp: *const c_char,
    group_id_hex: *const c_char,
    session_id_hex: *const c_char,
    has_video: i32,
    out_call_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null()
        || offer_sdp.is_null()
        || group_id_hex.is_null()
        || session_id_hex.is_null()
    {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let group_hex = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let session_hex = match CStr::from_ptr(session_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let group_bytes = match hex::decode(group_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let session_bytes = match hex::decode(session_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let mut group_id = [0u8; 16];
    group_id.copy_from_slice(&group_bytes);
    let mut session_id = [0u8; 16];
    session_id.copy_from_slice(&session_bytes);
    let video = has_video != 0;

    match runtime().block_on(async {
        h.inner
            .prepare_call_in_group_room(
                peer.clone(),
                offer_str.clone(),
                group_id,
                session_id,
                video,
            )
            .await
    }) {
        Ok((call_id, offer)) => {
            if !out_call_id.is_null() {
                std::slice::from_raw_parts_mut(out_call_id, 16).copy_from_slice(&call_id);
            }
            let arc_core = h.inner.clone();
            runtime().spawn(async move {
                arc_core
                    .deliver_call_invite_bg(peer, call_id, offer, video)
                    .await;
            });
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_call_in_group_room prepare failed: {}", e);
            AdaResult::Error
        }
    }
}

/// Get all active calls as a JSON array.
/// Schema: [{"call_id":"HEX","peer":"BASE64","has_video":bool,"outgoing":bool,"state":"...",
///           "group_id":"HEX"?,"call_session_id":"HEX"?,"participants":["BASE64", ...]?}]
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_active_calls_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let calls = runtime().block_on(async { h.inner.get_active_calls().await });
    let arr: Vec<serde_json::Value> = calls
        .iter()
        .map(|call| {
            let mut obj = serde_json::json!({
                "call_id": hex::encode(call.call_id),
                "peer": call.peer.to_base64(),
                "has_video": call.has_video,
                "outgoing": call.is_outgoing,
                "state": format!("{:?}", call.state),
            });
            append_group_call_room_fields(&mut obj, call.room.clone());
            obj
        })
        .collect();
    let json = serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string());
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return current voice/video call availability as JSON.
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_call_availability_json(
    handle: *const AdaCoreHandle,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let json = runtime().block_on(async { h.inner.get_call_availability_json().await });
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Get persistent call history as a JSON array (most-recent first).
/// Schema: [{"call_id":"HEX","peer_id":"B64","direction":"outgoing"|"incoming",
///           "has_video":bool,"duration_secs":N,"started_at":N,"ended_at":N,"reason":"..."}]
/// `limit` = max entries to return (pass 0 for default of 100).
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_call_history_json(
    handle: *const AdaCoreHandle,
    limit: i32,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let n = if limit <= 0 { 100 } else { limit as usize };
    let json = runtime().block_on(async { h.inner.get_call_history_json(n).await });
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Send a local ICE candidate to the remote peer via encrypted signaling.
///
/// # Safety
/// - `handle` must be a valid pointer obtained from `ada_create`.
/// - `peer_id_b64` must be a null-terminated UTF-8 string.
/// - `call_id` must point to exactly 16 bytes.
/// - `candidate`, `sdp_mid` must be null-terminated UTF-8 strings.
///   `sdp_mid` may be null (treated as empty string).
#[no_mangle]
pub unsafe extern "C" fn ada_send_ice_candidate(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    peer_id_b64: *const c_char,
    candidate: *const c_char,
    sdp_mid: *const c_char,
    sdp_mline_index: u16,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if call_id.is_null() || peer_id_b64.is_null() || candidate.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;

    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);

    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };

    let cand_str = match CStr::from_ptr(candidate).to_str() {
        Ok(s) => s.to_owned(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let mid_str: Option<String> = if sdp_mid.is_null() {
        None
    } else {
        CStr::from_ptr(sdp_mid).to_str().ok().map(|s| s.to_owned())
    };

    match runtime().block_on(async {
        h.inner
            .send_ice_candidate_with_sdp(cid, peer, cand_str, mid_str, Some(sdp_mline_index))
            .await
    }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

// в”Ђв”Ђв”Ђ Phase 6: File transfer в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Send an ICE restart offer for an existing call (called when ICE fails on the offerer side).
/// # Safety: all pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_send_ice_restart_offer(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    peer_id_b64: *const c_char,
    offer_sdp: *const c_char,
) -> AdaResult {
    if handle.is_null() || call_id.is_null() || peer_id_b64.is_null() || offer_sdp.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(s) => s.to_owned(),
        Err(_) => return AdaResult::InvalidArg,
    };
    runtime().block_on(h.inner.send_ice_restart_offer(cid, peer, offer_str));
    AdaResult::Ok
}

/// Send an ICE restart answer (answerer responds to IceRestartOffer).
/// # Safety: all pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_send_ice_restart_answer(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    peer_id_b64: *const c_char,
    answer_sdp: *const c_char,
) -> AdaResult {
    if handle.is_null() || call_id.is_null() || peer_id_b64.is_null() || answer_sdp.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut cid = [0u8; 16];
    std::slice::from_raw_parts(call_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| cid[i] = *b);
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let ans_str = match CStr::from_ptr(answer_sdp).to_str() {
        Ok(s) => s.to_owned(),
        Err(_) => return AdaResult::InvalidArg,
    };
    runtime().block_on(h.inner.send_ice_restart_answer(cid, peer, ans_str));
    AdaResult::Ok
}

/// Queue a file for sending to a peer.
/// `data` points to `data_len` bytes of raw file content.
/// `out_transfer_id` receives the 16-byte transfer ID.
///
/// # Safety: all pointers must be valid; `data` must point to `data_len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn ada_send_file_bytes(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    file_name: *const c_char,
    mime_type: *const c_char,
    data: *const u8,
    data_len: c_uint,
    out_transfer_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || file_name.is_null() || mime_type.is_null() || data.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    let fname = match CStr::from_ptr(file_name).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let mime = match CStr::from_ptr(mime_type).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = std::slice::from_raw_parts(data, data_len as usize).to_vec();

    match runtime().block_on(async { h.inner.send_file(peer, &fname, &mime, bytes).await }) {
        Ok(tid) => {
            if !out_transfer_id.is_null() {
                std::slice::from_raw_parts_mut(out_transfer_id, 16).copy_from_slice(&tid);
            }
            AdaResult::Ok
        }
        Err(_) => AdaResult::Error,
    }
}

#[no_mangle]
pub unsafe extern "C" fn ada_send_file_from_path(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    file_name: *const c_char,
    mime_type: *const c_char,
    file_path: *const c_char,
    out_transfer_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || file_name.is_null() || mime_type.is_null() || file_path.is_null() {
        return AdaResult::InvalidArg;
    }

    {
        let h = &*handle;
        let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
            Ok(s) => s,
            Err(_) => return AdaResult::InvalidArg,
        };
        let peer = match crate::identity::PeerId::from_base64(peer_str) {
            Ok(p) => p,
            Err(_) => return AdaResult::InvalidArg,
        };
        let fname = match CStr::from_ptr(file_name).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return AdaResult::InvalidArg,
        };
        let mime = match CStr::from_ptr(mime_type).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return AdaResult::InvalidArg,
        };
        let path = match CStr::from_ptr(file_path).to_str() {
            Ok(s) => std::path::PathBuf::from(s),
            Err(_) => return AdaResult::InvalidArg,
        };

        match runtime()
            .block_on(async { h.inner.send_file_from_path(peer, &fname, &mime, path).await })
        {
            Ok(tid) => {
                if !out_transfer_id.is_null() {
                    std::slice::from_raw_parts_mut(out_transfer_id, 16).copy_from_slice(&tid);
                }
                AdaResult::Ok
            }
            Err(e) => {
                tracing::warn!("ada_send_file_from_path error: {}", e);
                AdaResult::Error
            }
        }
    }
}

/// Get all active transfers as a JSON array.
/// Schema: [{"id":"HEX","peer":"BASE64","file_name":"...","file_size":N,"progress":0.0,
///           "mime_type":"...","is_outbound":bool}]
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_transfers_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let transfers = runtime().block_on(async { h.inner.get_active_transfers().await });
    let arr: Vec<serde_json::Value> = transfers
        .iter()
        .map(|(meta, progress, outbound)| {
            serde_json::json!({
                "id": hex::encode(meta.id),
                "peer": meta.peer.to_base64(),
                "file_name": meta.file_name,
                "file_size": meta.file_size,
                "mime_type": meta.mime_type,
                "progress": progress,
                "is_outbound": outbound,
            })
        })
        .collect();
    let json = serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string());
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Cancel an active transfer. `transfer_id` points to 16 bytes.
///
/// # Safety: `handle` valid, `transfer_id` points to 16 readable bytes.
#[no_mangle]
pub unsafe extern "C" fn ada_cancel_transfer(
    handle: *const AdaCoreHandle,
    transfer_id: *const u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if transfer_id.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let mut tid = [0u8; 16];
    std::slice::from_raw_parts(transfer_id, 16)
        .iter()
        .enumerate()
        .for_each(|(i, b)| tid[i] = *b);
    runtime().block_on(async { h.inner.cancel_transfer(tid).await });
    AdaResult::Ok
}

/// Save a completed inbound transfer to a file and return its metadata as JSON.
///
/// Looks up the transfer by hex ID in the completed-transfers cache, writes
/// the bytes to `file_path`, then removes the entry from the cache.
///
/// On success `out_meta_json` is set to a newly allocated JSON string:
/// `{"file_name":"...","mime_type":"...","file_size":N}`
/// Caller must free `*out_meta_json` with `ada_string_free`.
///
/// Returns `AdaResult::Ok` on success, `AdaResult::Error` if not found or IO fails,
/// `AdaResult::InvalidArg` if arguments are invalid.
///
/// # Safety
/// All pointer arguments must be valid; `out_meta_json` must point to a writable `*mut c_char`.
#[no_mangle]
pub unsafe extern "C" fn ada_save_transfer_to_file(
    handle: *const AdaCoreHandle,
    transfer_id_hex: *const c_char,
    file_path: *const c_char,
    out_meta_json: *mut *mut c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if transfer_id_hex.is_null() || file_path.is_null() || out_meta_json.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let tid_str = match CStr::from_ptr(transfer_id_hex).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let path_str = match CStr::from_ptr(file_path).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    // Peek first: clone only metadata/path so a write failure does not lose the cache entry.
    // The entry is only removed after the file is successfully copied.
    let entry = {
        let map = match h.completed_transfers.lock() {
            Ok(g) => g,
            Err(_) => return AdaResult::Error,
        };
        map.get(&tid_str).cloned()
    };

    let entry = match entry {
        Some(e) => e,
        None => return AdaResult::Error,
    };

    // Copy bytes to the given path (parent dir must already exist).
    if let Err(e) = std::fs::copy(&entry.path, &path_str) {
        tracing::warn!(
            "ada_save_transfer_to_file: copy to {} failed: {}",
            path_str,
            e
        );
        return AdaResult::Error;
    }

    // Copy succeeded: now remove the cache entry and its temp file.
    if let Ok(mut map) = h.completed_transfers.lock() {
        map.remove(&tid_str);
    }
    let _ = std::fs::remove_file(&entry.path);

    let meta = serde_json::json!({
        "file_name": entry.file_name,
        "mime_type": entry.mime_type,
        "file_size": entry.file_size,
    })
    .to_string();

    match CString::new(meta) {
        Ok(s) => {
            *out_meta_json = s.into_raw();
        }
        Err(_) => return AdaResult::Error,
    }

    AdaResult::Ok
}

/// Fetch a BlobRef payload from a peer and save it to a local file path.
///
/// `peer_id_b64` вЂ” sender peer id in base64
/// `hash_hex`    вЂ” 32-byte blake3 hash encoded as lowercase hex
/// `file_path`   вЂ” destination file path (parent directory must exist)
///
/// Returns `AdaResult::Ok` on success.
///
/// # Safety
/// All pointers must be valid C strings.
#[no_mangle]
pub unsafe extern "C" fn ada_fetch_blob_to_file(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
    hash_hex: *const c_char,
    file_path: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if peer_id_b64.is_null() || hash_hex.is_null() || file_path.is_null() {
        return AdaResult::InvalidArg;
    }
    let _h = &*handle;

    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };

    let hash_str = match CStr::from_ptr(hash_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let hash_bytes = match hex::decode(hash_str) {
        Ok(b) if b.len() == 32 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut hash = [0u8; 32];
    hash.copy_from_slice(&hash_bytes);

    let path_str = match CStr::from_ptr(file_path).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    {
        match runtime().block_on(async {
            _h.inner
                .fetch_file_blob_to_path(&peer, hash, std::path::Path::new(&path_str))
                .await
        }) {
            Ok(_) => return AdaResult::Ok,
            Err(e) => {
                tracing::warn!("ada_fetch_blob_to_file: fetch failed: {}", e);
                return AdaResult::Error;
            }
        }
    }
}

// в”Ђв”Ђв”Ђ Phase 7: Bridge / censorship в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Get bridge list + current mode as JSON.
/// Schema: {"mode":"...","bridges":[...],"has_working":bool}
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_bridge_status_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let json = runtime().block_on(async { h.inner.get_bridge_status_json().await });
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return a synchronous snapshot of all runtime telemetry counters as JSON.
///
/// Unlike `ada_get_bridge_status_json`, this call does not block on async
/// locks — all values are read from atomics directly.  Useful for lightweight
/// monitoring dashboards that poll frequently.
/// Caller must free the returned string with `ada_string_free`.
///
/// # Safety: `handle` must be a valid non-null `AdaCoreHandle`.
#[no_mangle]
pub unsafe extern "C" fn ada_get_metrics_snapshot(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let json = h.inner.get_metrics_snapshot();
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Probe the local network and return the detected censorship level as JSON.
/// Schema: {"level":"None"|"Light"|"Moderate"|"Heavy"|"Extreme"}
/// Caller must free with `ada_string_free`.
///
/// # Safety: `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_detect_censorship_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let json = runtime().block_on(async { h.inner.detect_censorship_json().await });
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Set the active obfuscation/bridge mode.
/// `mode_str` is one of: "none", "padding", "shaping", "websocket", "fronting", "auto".
///
/// # Safety: `handle` valid, `mode_str` valid null-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ada_set_bridge_mode(
    handle: *mut AdaCoreHandle,
    mode_str: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    let h = &*handle;
    let mode = match CStr::from_ptr(mode_str).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    runtime().block_on(async { h.inner.set_bridge_mode_str(&mode).await });
    AdaResult::Ok
}

/// Import a signed bridge manifest JSON payload and optionally pin a trusted public key.
///
/// # Safety: all string pointers must be valid null-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ada_import_bridge_manifest_json(
    handle: *const AdaCoreHandle,
    manifest_json: *const c_char,
    source: *const c_char,
    trusted_public_key_hex: *const c_char,
) -> AdaResult {
    if handle.is_null()
        || manifest_json.is_null()
        || source.is_null()
        || trusted_public_key_hex.is_null()
    {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    h.clear_last_error();
    let manifest_json = match CStr::from_ptr(manifest_json).to_str() {
        Ok(s) => s.to_string(),
        Err(error) => {
            h.set_last_error(format!("manifest JSON must be valid UTF-8: {}", error));
            return AdaResult::InvalidArg;
        }
    };
    let source = match CStr::from_ptr(source).to_str() {
        Ok(s) => s.to_string(),
        Err(error) => {
            h.set_last_error(format!("manifest source must be valid UTF-8: {}", error));
            return AdaResult::InvalidArg;
        }
    };
    let trusted_key = match CStr::from_ptr(trusted_public_key_hex).to_str() {
        Ok(s) => s.trim().to_string(),
        Err(error) => {
            h.set_last_error(format!(
                "manifest trusted key must be valid UTF-8: {}",
                error
            ));
            return AdaResult::InvalidArg;
        }
    };

    match runtime().block_on(async {
        h.inner
            .import_bridge_manifest_json(
                &manifest_json,
                &source,
                if trusted_key.is_empty() {
                    None
                } else {
                    Some(trusted_key.as_str())
                },
            )
            .await
    }) {
        Ok(()) => AdaResult::Ok,
        Err(error) => {
            h.set_last_error(error.to_string());
            AdaResult::Error
        }
    }
}

/// Import a signed bridge manifest from a URL and optionally pin a trusted public key.
///
/// # Safety: all string pointers must be valid null-terminated UTF-8.
#[no_mangle]
pub unsafe extern "C" fn ada_import_bridge_manifest_url(
    handle: *const AdaCoreHandle,
    manifest_url: *const c_char,
    trusted_public_key_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() || manifest_url.is_null() || trusted_public_key_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    h.clear_last_error();
    let manifest_url = match CStr::from_ptr(manifest_url).to_str() {
        Ok(s) => s.to_string(),
        Err(error) => {
            h.set_last_error(format!("manifest URL must be valid UTF-8: {}", error));
            return AdaResult::InvalidArg;
        }
    };
    let trusted_key = match CStr::from_ptr(trusted_public_key_hex).to_str() {
        Ok(s) => s.trim().to_string(),
        Err(error) => {
            h.set_last_error(format!(
                "manifest trusted key must be valid UTF-8: {}",
                error
            ));
            return AdaResult::InvalidArg;
        }
    };

    match runtime().block_on(async {
        h.inner
            .import_bridge_manifest_url(
                &manifest_url,
                if trusted_key.is_empty() {
                    None
                } else {
                    Some(trusted_key.as_str())
                },
            )
            .await
    }) {
        Ok(()) => AdaResult::Ok,
        Err(error) => {
            h.set_last_error(error.to_string());
            AdaResult::Error
        }
    }
}

// в”Ђв”Ђв”Ђ Pattern authentication в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Create an ADA Core instance with an identity derived from a visual pattern.
///
/// `cells` must point to exactly 16 bytes, each a cell index in 0..64.
/// On first registration `create_new` should be 1; set to 0 when loading an
/// existing identity on a new device (the peer_id will be compared later via
/// `ada_verify_pattern` before any sensitive use).
///
/// # Safety
/// - `cells` must be a valid pointer to `cells_len` readable bytes
/// - `display_name`, `data_dir` must be valid null-terminated UTF-8 strings
/// - Caller must free the handle with `ada_core_free`
#[no_mangle]
pub unsafe extern "C" fn ada_create_from_pattern(
    cells: *const u8,
    cells_len: usize,
    display_name: *const c_char,
    data_dir: *const c_char,
) -> *mut AdaCoreHandle {
    ada_create_from_pattern_with_profile(cells, cells_len, display_name, data_dir, std::ptr::null())
}

/// Create an ADA Core instance from a visual pattern and explicit connection profile.
///
/// # Safety
/// - Same requirements as `ada_create_from_pattern`.
/// - `connection_profile` may be null; otherwise it must be a valid UTF-8 C string.
#[no_mangle]
pub unsafe extern "C" fn ada_create_from_pattern_with_profile(
    cells: *const u8,
    cells_len: usize,
    display_name: *const c_char,
    data_dir: *const c_char,
    connection_profile: *const c_char,
) -> *mut AdaCoreHandle {
    if cells.is_null() || display_name.is_null() || data_dir.is_null() {
        log::error!(
            "ada_create_from_pattern: null pointer (cells={}, name={}, dir={})",
            cells.is_null(),
            display_name.is_null(),
            data_dir.is_null()
        );
        return std::ptr::null_mut();
    }
    if cells_len != crate::pattern_auth::PATTERN_KEY_BYTES {
        log::error!(
            "ada_create_from_pattern: invalid cells_len={}, expected={}",
            cells_len,
            crate::pattern_auth::PATTERN_KEY_BYTES
        );
        return std::ptr::null_mut();
    }

    let name = match CStr::from_ptr(display_name).to_str() {
        Ok(s) => s.to_string(),
        Err(e) => {
            log::error!("ada_create_from_pattern: invalid display_name UTF-8: {e:?}");
            return std::ptr::null_mut();
        }
    };
    let dir = match CStr::from_ptr(data_dir).to_str() {
        Ok(s) => s.to_string(),
        Err(e) => {
            log::error!("ada_create_from_pattern: invalid data_dir UTF-8: {e:?}");
            return std::ptr::null_mut();
        }
    };

    let cells_slice = std::slice::from_raw_parts(cells, cells_len);
    let pattern = match crate::pattern_auth::PatternKey::from_bytes(cells_slice) {
        Ok(p) => p,
        Err(e) => {
            log::error!("ada_create_from_pattern: PatternKey::from_bytes failed: {e:?}");
            return std::ptr::null_mut();
        }
    };

    let mut config = crate::config::ADAConfig::for_mobile();
    config.storage.data_dir = dir;
    match read_connection_profile_arg(connection_profile) {
        Ok(Some(profile)) => config.network.connection_profile = profile,
        Ok(None) => {}
        Err(_) => {
            log::error!("ada_create_from_pattern_with_profile: invalid connection_profile");
            return std::ptr::null_mut();
        }
    }

    let create_started_at = std::time::Instant::now();
    log::info!(
        "[startup] ada_create_from_pattern: begin name='{}' data_dir='{}' profile='{}'",
        name,
        config.storage.data_dir,
        config.network.connection_profile.as_str()
    );

    let core = runtime()
        .block_on(async { crate::api::ADACore::from_pattern(config, &pattern, &name).await });

    match core {
        Ok(c) => {
            log::info!(
                "[startup] ada_create_from_pattern: core constructed peer_id={} in {:?}",
                c.peer_id(),
                create_started_at.elapsed()
            );
            // Log start() errors instead of silently ignoring them.
            // A failed start() leaves network = None, causing all group publishes
            // to silently fail and marking every group message as Failed.
            let start_started_at = std::time::Instant::now();
            log::info!(
                "[startup] ada_create_from_pattern: entering core.start() for peer_id={}",
                c.peer_id()
            );
            runtime().block_on(async {
                if let Err(e) = c.start().await {
                    log::error!(
                        "[startup] ada_create_from_pattern: core.start() failed for peer_id={} after {:?}: {e:?}",
                        c.peer_id(),
                        start_started_at.elapsed()
                    );
                } else {
                    log::info!(
                        "[startup] ada_create_from_pattern: core.start() returned for peer_id={} in {:?}",
                        c.peer_id(),
                        start_started_at.elapsed()
                    );
                }
            });
            Box::into_raw(Box::new(AdaCoreHandle {
                inner: c,
                completed_transfers: std::sync::Mutex::new(std::collections::HashMap::new()),
                event_callback: parking_lot::Mutex::new(None),
                last_error: parking_lot::Mutex::new(None),
            }))
        }
        Err(e) => {
            log::error!("ada_create_from_pattern failed for '{name}': {e:?}");
            std::ptr::null_mut()
        }
    }
}

/// Verify that a given pattern matches the peer_id stored in the handle.
///
/// Returns 1 if the pattern is correct, 0 otherwise.
///
/// # Safety
/// - `handle` must be a valid pointer from `ada_create_from_pattern` or `ada_core_create`
/// - `cells` must point to `cells_len` readable bytes
#[no_mangle]
pub unsafe extern "C" fn ada_verify_pattern(
    handle: *const AdaCoreHandle,
    cells: *const u8,
    cells_len: usize,
) -> c_int {
    if handle.is_null() || cells.is_null() {
        return 0;
    }
    if cells_len != crate::pattern_auth::PATTERN_KEY_BYTES {
        return 0;
    }

    let h = &*handle;
    let cells_slice = std::slice::from_raw_parts(cells, cells_len);
    let pattern = match crate::pattern_auth::PatternKey::from_bytes(cells_slice) {
        Ok(p) => p,
        Err(_) => return 0,
    };

    match crate::pattern_auth::verify_pattern(
        &pattern,
        &h.inner.peer_id(),
        &h.inner.config.storage.data_dir,
    ) {
        Ok(true) => 1,
        _ => 0,
    }
}

/// Save a peer's public bundle from the QR contact card JSON.
///
/// Parses the JSON produced by `ada_get_contact_card_json` (v2 format) and stores the
/// `PublicBundle` so that subsequent `ada_send_text` / `ada_call_*` calls can perform X3DH.
///
/// Returns `AdaResult::Ok` on success, `AdaResult::InvalidArg` if the JSON is malformed
/// or missing required fields, `AdaResult::Error` on storage failure.
///
/// # Safety
/// `handle` must be a valid pointer; `contact_card_json` a valid null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_add_contact_json(
    handle: *const AdaCoreHandle,
    contact_card_json: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if contact_card_json.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;
    let json_str = match CStr::from_ptr(contact_card_json).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };

    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;

    let card = match crate::pattern_auth::parse_contact_card(json_str) {
        Ok(c) => c,
        Err(_) => return AdaResult::InvalidArg,
    };

    // Decode peer_id (Ed25519 VK)
    let peer_id_bytes: [u8; 32] = match b64.decode(&card.id).ok().and_then(|v| v.try_into().ok()) {
        Some(b) => b,
        None => return AdaResult::InvalidArg,
    };

    // Decode SPK public
    let spk_public: [u8; 32] = match b64.decode(&card.spk).ok().and_then(|v| v.try_into().ok()) {
        Some(b) => b,
        None => return AdaResult::InvalidArg,
    };

    // Decode IK (X25519 DH public).
    // v3 cards include `ephemeral_ik` вЂ” a per-contact X25519 key that provides
    // SimpleX-style unlinkability.  When present it MUST be used as IK_B instead
    // of the long-term `ik`, so each incognito session uses a different key.
    // v2: use `ik`.  v1 fallback: use peer_id bytes (X3DH will fail until DHT bundle arrives).
    let dh_public: [u8; 32] = if card.v >= 3 && !card.ephemeral_ik.is_empty() {
        match b64
            .decode(&card.ephemeral_ik)
            .ok()
            .and_then(|v| v.try_into().ok())
        {
            Some(b) => b,
            None => return AdaResult::InvalidArg,
        }
    } else if card.ik.is_empty() {
        // v1 card: no IK вЂ” use peer_id bytes as a degraded fallback.
        // X3DH won't be possible until the peer publishes a fresh bundle via DHT.
        peer_id_bytes
    } else {
        match b64.decode(&card.ik).ok().and_then(|v| v.try_into().ok()) {
            Some(b) => b,
            None => return AdaResult::InvalidArg,
        }
    };

    // Decode SPK signature вЂ” required for bundle verification
    let spk_signature: Vec<u8> = if card.spk_sig.is_empty() {
        Vec::new()
    } else {
        match b64.decode(&card.spk_sig) {
            Ok(b) => b,
            Err(_) => return AdaResult::InvalidArg,
        }
    };

    // M-2 fix: verify SPK signature at import time (defense-in-depth; also verified
    // again in x3dh_send on first use).  A card with an invalid or zero-length
    // spk_sig is rejected early вЂ” gives the user an immediate error rather than
    // a cryptic "session failed" on the first message.
    //
    // v1 cards have no spk_sig вЂ” skip verification there.  For v2/v3 the sig
    // MUST be present and valid.
    if card.v >= 2 {
        if spk_signature.len() != 64 {
            tracing::warn!(
                "ada_add_contact_json: v{} card has invalid spk_sig length {}",
                card.v,
                spk_signature.len()
            );
            return AdaResult::InvalidArg;
        }
        let tmp_bundle_for_verify = crate::crypto::x3dh::PreKeyBundle {
            ik_public: dh_public,
            spk_public,
            spk_signature: spk_signature.clone(),
            opk_public: None,
            opk_id: None,
        };
        if tmp_bundle_for_verify
            .verify_spk_signature(&peer_id_bytes)
            .is_err()
        {
            tracing::warn!(
                "ada_add_contact_json: spk_sig verification failed for peer {}",
                card.id
            );
            return AdaResult::InvalidArg;
        }
    }

    // Decode optional one-time pre-key from the card (DH4 forward secrecy).
    let (opk_public, opk_id) = if !card.opk.is_empty() && !card.opk_id.is_empty() {
        let opk_bytes: Option<[u8; 32]> =
            b64.decode(&card.opk).ok().and_then(|v| v.try_into().ok());
        let opk_id_val: Option<u32> = card.opk_id.parse().ok();
        match (opk_bytes, opk_id_val) {
            (Some(pub_bytes), Some(id)) => (Some(pub_bytes), Some(id)),
            _ => (None, None),
        }
    } else {
        (None, None)
    };

    let bundle = crate::identity::PublicBundle {
        peer_id: crate::identity::PeerId(peer_id_bytes),
        dh_public,
        spk_public,
        spk_signature,
        display_name: card.name.clone(),
        opk_public,
        opk_id,
        relay_url: (!card.relay_url.is_empty()).then(|| card.relay_url.clone()),
    };

    match h.inner.add_contact(bundle) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Get the contact card JSON payload for the local identity (used for QR code generation).
///
/// Schema: `{"v":2,"id":"BASE64","spk":"BASE64","ik":"BASE64","spk_sig":"BASE64","name":"Nickname","relay_url":"https://..."}`
///
/// Cannot be used to reconstruct the original pattern вЂ” public keys only.
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn ada_get_contact_card_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    match h.inner.contact_card_json() {
        Ok(json) => match CString::new(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Add a custom iroh relay node URL at runtime.
///
/// `relay_url` is a URL string, e.g. `https://relay.example.com`.
///
/// Returns `AdaResult::Ok` on success.
///
/// # Safety
/// `handle` and `relay_url` must be valid pointers.
#[no_mangle]
pub unsafe extern "C" fn ada_add_relay_node(
    handle: *const AdaCoreHandle,
    relay_url: *const c_char,
) -> AdaResult {
    if handle.is_null() || relay_url.is_null() {
        return AdaResult::NotInitialized;
    }
    let h = &*handle;
    let url_str = match CStr::from_ptr(relay_url).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    match runtime().block_on(async { h.inner.add_relay_node(&url_str).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

// в”Ђв”Ђв”Ђ Incognito chats (ephemeral per-contact identities) в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Open an incognito chat with a peer and return a v3 contact card JSON.
///
/// Generates (or reuses) a per-contact ephemeral X25519 IK so that the
/// resulting X3DH session cannot be correlated across different chats by
/// a passive observer.
///
/// The returned JSON must be displayed as a QR code for the peer to scan
/// (they call `ada_add_contact_json` as usual вЂ” the v3 card is backward
/// compatible with the v2 parser).
///
/// Returns null on error.  Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be valid; `peer_id_b64` a valid null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_create_incognito_chat(
    handle: *const AdaCoreHandle,
    peer_id_b64: *const c_char,
) -> *mut c_char {
    if handle.is_null() || peer_id_b64.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return std::ptr::null_mut(),
    };
    match h.inner.create_incognito_chat(&peer) {
        Ok(json) => match CString::new(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Enable or disable relay-only routing at runtime.
///
/// In the current build ADA does not yet have a verified public-iroh switch
/// for live censorship-safe delivery on its own. When `enabled` is non-zero,
/// live outgoing iroh for unicast is disabled and ADA may use only bridge,
/// mailbox, or local offline-queue routes.
///
/// # Safety
/// `handle` must be a valid pointer obtained from `ada_core_create` or
/// `ada_create_from_pattern`.
#[no_mangle]
pub unsafe extern "C" fn ada_set_relay_only(
    handle: *const AdaCoreHandle,
    enabled: c_int,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    let h = &*handle;
    h.inner.set_relay_only(enabled != 0);
    AdaResult::Ok
}

/// Set the runtime connection profile.
///
/// # Safety
/// `handle` must be valid and `connection_profile` must be a valid UTF-8 C string.
#[no_mangle]
pub unsafe extern "C" fn ada_set_connection_profile(
    handle: *const AdaCoreHandle,
    connection_profile: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    let profile = match read_connection_profile_arg(connection_profile) {
        Ok(Some(profile)) => profile,
        Ok(None) | Err(_) => return AdaResult::InvalidArg,
    };
    let h = &*handle;
    h.inner.set_connection_profile(profile);
    AdaResult::Ok
}

/// Informs the core whether the app has transitioned into the background.
/// Enabling background mode sets aggressive battery optimization limits on
/// background tasks like DHT discovery and connection warmups.
///
/// # Safety
/// `handle` must be a valid pointer obtained from `ada_core_create`.
#[no_mangle]
pub unsafe extern "C" fn ada_set_app_background_state(
    handle: *const AdaCoreHandle,
    in_background: c_int,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    let h = &*handle;
    h.inner.set_app_background_state(in_background != 0);
    AdaResult::Ok
}

// в”Ђв”Ђв”Ђ Group chat management в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

/// Create a new group chat.
/// Returns a null-terminated hex group-id string (32 chars) or null on error.
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` valid; `group_name` valid null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_create_group(
    handle: *const AdaCoreHandle,
    group_name: *const c_char,
) -> *mut c_char {
    if handle.is_null() || group_name.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let name = match CStr::from_ptr(group_name).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return std::ptr::null_mut(),
    };
    let (group_id, _topic) = runtime().block_on(async { h.inner.create_group(&name).await });
    let hex = hex::encode(group_id);
    match CString::new(hex) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Send a text message to a group.
/// Returns 0 on success.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
/// `group_id_hex` must be a 32-char lowercase hex string.
#[no_mangle]
pub unsafe extern "C" fn ada_send_group_text(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    text: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() || text.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let text_str = match CStr::from_ptr(text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    match runtime().block_on(async { h.inner.send_group_text(gid, text_str).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_send_group_reply(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    text: *const c_char,
    reply_to_msg_id_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() || text.is_null() || reply_to_msg_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let text_str = match CStr::from_ptr(text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let reply_hex = match CStr::from_ptr(reply_to_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let reply_bytes = match hex::decode(reply_hex) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    let mut reply_id = [0u8; 16];
    reply_id.copy_from_slice(&reply_bytes);
    match runtime().block_on(async { h.inner.send_group_reply(gid, text_str, reply_id).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Send an edit for a previously sent group text message.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_edit_group_message(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    target_msg_id_hex: *const c_char,
    new_text: *const c_char,
    out_message_id: *mut u8,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() || target_msg_id_hex.is_null() || new_text.is_null() {
        return AdaResult::InvalidArg;
    }

    let h = &*handle;
    let group_hex = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let target_hex = match CStr::from_ptr(target_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let new_text_str = match CStr::from_ptr(new_text).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };

    let group_bytes = match hex::decode(group_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let target_bytes = match hex::decode(target_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };

    let mut group_id = [0u8; 16];
    group_id.copy_from_slice(&group_bytes);
    let mut target_msg_id = [0u8; 16];
    target_msg_id.copy_from_slice(&target_bytes);

    match runtime().block_on(async {
        h.inner
            .send_group_edit(group_id, target_msg_id, new_text_str)
            .await
    }) {
        Ok(id) => {
            if !out_message_id.is_null() {
                std::slice::from_raw_parts_mut(out_message_id, 16).copy_from_slice(&id);
            }
            AdaResult::Ok
        }
        Err(e) => {
            log::warn!("ada_edit_group_message failed: {}", e);
            AdaResult::Error
        }
    }
}

/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_send_group_reaction(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    target_msg_id_hex: *const c_char,
    emoji: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() || target_msg_id_hex.is_null() || emoji.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let target_hex = match CStr::from_ptr(target_msg_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let emoji_str = match CStr::from_ptr(emoji).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let target_bytes = match hex::decode(target_hex) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    let mut target_id = [0u8; 16];
    target_id.copy_from_slice(&target_bytes);
    match runtime().block_on(async { h.inner.send_group_reaction(gid, target_id, emoji_str).await })
    {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Invite a peer to a group (sends an encrypted invite DM).
/// Returns 0 on success.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_invite_to_group(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    peer_id_b64: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() || peer_id_b64.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let peer_str = match CStr::from_ptr(peer_id_b64).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return AdaResult::InvalidArg,
    };
    // Enforce 16-member group limit.
    let current_count = h
        .inner
        .list_groups()
        .into_iter()
        .find(|g| g.id == gid)
        .map(|g| g.members.len())
        .unwrap_or(0);
    if current_count >= 16 {
        return AdaResult::Error;
    }
    match runtime().block_on(async { h.inner.invite_to_group(gid, &peer).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Leave a group.
/// Returns 0 on success.
///
/// # Safety
/// All pointer arguments must be valid null-terminated UTF-8 strings.
#[no_mangle]
pub unsafe extern "C" fn ada_leave_group(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() {
        return AdaResult::NotInitialized;
    }
    if group_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return AdaResult::InvalidArg,
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return AdaResult::InvalidArg,
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    match runtime().block_on(async { h.inner.leave_group(gid).await }) {
        Ok(_) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Get all groups as a JSON array.
/// Schema: [{"id":"HEX","name":"...","member_count":N,"topic":"..."}]
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// `handle` must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_groups_json(handle: *const AdaCoreHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let groups = h.inner.list_groups();
    let arr: Vec<serde_json::Value> = groups
        .iter()
        .map(|g| {
            serde_json::json!({
                "id": hex::encode(g.id),
                "name": g.name,
                "description": g.description,
                "member_count": g.members.len(),
                "topic": g.topic,
                "created_at": g.created_at,
            })
        })
        .collect();
    let json = serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string());
    match CString::new(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Get detailed info for one group (including member list) as JSON.
/// Schema: {"id":"HEX","name":"...","description":"...","members":[{"peer_id":"BASE64","role":"Owner"|"Admin"|"Member","display_name":"..."}],"topic":"..."}
/// Returns null if the group is not found.
/// Caller must free with `ada_string_free`.
///
/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_get_group_info_json(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
) -> *mut c_char {
    if handle.is_null() || group_id_hex.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return std::ptr::null_mut(),
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    let group = match h.inner.list_groups().into_iter().find(|g| g.id == gid) {
        Some(g) => g,
        None => return std::ptr::null_mut(),
    };
    let members: Vec<serde_json::Value> = group
        .members
        .iter()
        .map(|m| {
            let role = match m.role {
                crate::group::types::GroupRole::Owner => "Owner",
                crate::group::types::GroupRole::Admin => "Admin",
                crate::group::types::GroupRole::Member => "Member",
            };
            serde_json::json!({
                "peer_id": m.peer_id.to_base64(),
                "role": role,
                "display_name": m.display_name,
                "joined_at": m.joined_at,
            })
        })
        .collect();
    let obj = serde_json::json!({
        "id": hex::encode(group.id),
        "name": group.name,
        "description": group.description,
        "members": members,
        "topic": group.topic,
        "created_at": group.created_at,
        "created_by": group.created_by.to_base64(),
        "member_count": group.members.len(),
    });
    match CString::new(obj.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Start a group audio/video call.
///
/// Announces a room in the group chat and reuses the shared room/session id
/// for all pairwise calls started from this device.
/// Returns a null-terminated hex group-call-session-id string, or null on error.
/// Caller frees with `ada_string_free`.
///
/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_start_group_call(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    offer_sdp: *const c_char,
    has_video: c_int,
) -> *mut c_char {
    if handle.is_null() || group_id_hex.is_null() || offer_sdp.is_null() {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let hex_str = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return std::ptr::null_mut(),
    };
    let bytes = match hex::decode(hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return std::ptr::null_mut(),
    };
    let mut gid = [0u8; 16];
    gid.copy_from_slice(&bytes);
    let session_id = match runtime().block_on(async {
        h.inner
            .start_group_call_room(gid, offer_str, has_video != 0)
            .await
    }) {
        Ok(session_id) => session_id,
        Err(_) => return std::ptr::null_mut(),
    };

    let hex_session = hex::encode(session_id);
    match CString::new(hex_session) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Join an existing group audio/video room without creating a new announcement.
///
/// Returns the reused room/session id as a null-terminated hex string, or null on error.
/// Caller frees with `ada_string_free`.
///
/// # Safety
/// All pointer arguments must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_join_group_call(
    handle: *const AdaCoreHandle,
    group_id_hex: *const c_char,
    session_id_hex: *const c_char,
    offer_sdp: *const c_char,
    has_video: c_int,
) -> *mut c_char {
    if handle.is_null() || group_id_hex.is_null() || session_id_hex.is_null() || offer_sdp.is_null()
    {
        return std::ptr::null_mut();
    }
    let h = &*handle;
    let group_hex = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let session_hex = match CStr::from_ptr(session_id_hex).to_str() {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_str = match CStr::from_ptr(offer_sdp).to_str() {
        Ok(value) => value.to_string(),
        Err(_) => return std::ptr::null_mut(),
    };
    let group_bytes = match hex::decode(group_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return std::ptr::null_mut(),
    };
    let session_bytes = match hex::decode(session_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return std::ptr::null_mut(),
    };

    let mut group_id = [0u8; 16];
    let mut session_id = [0u8; 16];
    group_id.copy_from_slice(&group_bytes);
    session_id.copy_from_slice(&session_bytes);

    let joined_session = match runtime().block_on(async {
        h.inner
            .join_group_call_room(group_id, session_id, offer_str, has_video != 0)
            .await
    }) {
        Ok(session_id) => session_id,
        Err(_) => return std::ptr::null_mut(),
    };

    match CString::new(hex::encode(joined_session)) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Attach an existing pairwise call-id to a shared group room/session.
///
/// # Safety
/// `handle` must be valid, `call_id` must point to 16 bytes, and hex strings must be valid.
#[no_mangle]
pub unsafe extern "C" fn ada_attach_call_to_group_room(
    handle: *const AdaCoreHandle,
    call_id: *const u8,
    group_id_hex: *const c_char,
    session_id_hex: *const c_char,
    has_video: c_int,
) -> AdaResult {
    if handle.is_null() || call_id.is_null() || group_id_hex.is_null() || session_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let group_hex = match CStr::from_ptr(group_id_hex).to_str() {
        Ok(value) => value,
        Err(_) => return AdaResult::InvalidArg,
    };
    let session_hex = match CStr::from_ptr(session_id_hex).to_str() {
        Ok(value) => value,
        Err(_) => return AdaResult::InvalidArg,
    };
    let group_bytes = match hex::decode(group_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let session_bytes = match hex::decode(session_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };

    let mut call_id_bytes = [0u8; 16];
    call_id_bytes.copy_from_slice(std::slice::from_raw_parts(call_id, 16));
    let mut group_id = [0u8; 16];
    let mut session_id = [0u8; 16];
    group_id.copy_from_slice(&group_bytes);
    session_id.copy_from_slice(&session_bytes);

    match h
        .inner
        .attach_call_to_group_room(call_id_bytes, group_id, session_id, has_video != 0)
    {
        Ok(()) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}

/// Hang up all pairwise calls currently associated with a shared group room/session id.
///
/// # Safety
/// `handle` must be valid and `session_id_hex` must be a null-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn ada_hangup_group_call_room(
    handle: *const AdaCoreHandle,
    session_id_hex: *const c_char,
) -> AdaResult {
    if handle.is_null() || session_id_hex.is_null() {
        return AdaResult::InvalidArg;
    }
    let h = &*handle;
    let session_hex = match CStr::from_ptr(session_id_hex).to_str() {
        Ok(value) => value,
        Err(_) => return AdaResult::InvalidArg,
    };
    let session_bytes = match hex::decode(session_hex) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return AdaResult::InvalidArg,
    };
    let mut session_id = [0u8; 16];
    session_id.copy_from_slice(&session_bytes);

    match runtime().block_on(async { h.inner.hangup_group_call_room(session_id).await }) {
        Ok(()) => AdaResult::Ok,
        Err(_) => AdaResult::Error,
    }
}
#[no_mangle]
pub unsafe extern "C" fn ada_start_webrtc_proxy(
    handle: *const AdaCoreHandle,
    peer_b64: *const c_char,
) -> u16 {
    let core = match Some((*handle).inner.clone()) {
        Some(c) => c,
        None => return 0,
    };
    let peer_str = match std::ffi::CStr::from_ptr(peer_b64).to_str() {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return 0,
    };

    // We block since FFI calls are mostly sync or we don't return future
    // For start proxy we need the result (the port) immediately to feed to Android WebRTC
    // So we use tokio block_on inside the FFI boundary
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        match core.start_webrtc_proxy(peer).await {
            Ok(port) => port,
            Err(_) => 0,
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ada_stop_webrtc_proxy(
    handle: *const AdaCoreHandle,
    peer_b64: *const c_char,
) {
    let core = match Some((*handle).inner.clone()) {
        Some(c) => c,
        None => return,
    };
    let peer_str = match std::ffi::CStr::from_ptr(peer_b64).to_str() {
        Ok(s) => s,
        Err(_) => return,
    };
    let peer = match crate::identity::PeerId::from_base64(peer_str) {
        Ok(p) => p,
        Err(_) => return,
    };

    core.stop_webrtc_proxy(&peer);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_bridge_records_last_error_message() {
        let temp_dir = tempfile::tempdir().expect("temp dir should be created");
        let display_name = CString::new("ffi-error-test").expect("display name CString");
        let data_dir =
            CString::new(temp_dir.path().to_string_lossy().into_owned()).expect("data dir CString");
        let handle = unsafe { ada_core_create(display_name.as_ptr(), data_dir.as_ptr()) };
        assert!(!handle.is_null(), "core handle should be created");

        let bridge_line = CString::new("websocket edge.example:443").expect("bridge line CString");
        let result = unsafe { ada_add_bridge(handle, bridge_line.as_ptr()) };
        assert!(matches!(result, AdaResult::Error));

        let error_ptr = unsafe { ada_take_last_error_message(handle) };
        assert!(!error_ptr.is_null(), "last error should be present");
        let error = unsafe { CStr::from_ptr(error_ptr).to_string_lossy().into_owned() };
        unsafe { ada_string_free(error_ptr) };
        assert!(error.contains("fingerprint"), "unexpected error: {}", error);

        let cleared_ptr = unsafe { ada_take_last_error_message(handle) };
        assert!(
            cleared_ptr.is_null(),
            "last error should be cleared after take"
        );

        unsafe { ada_core_free(handle) };
    }
}
