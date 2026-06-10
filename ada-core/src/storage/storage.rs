//! Persistent storage backed by SQLite / SQLCipher.
//!
//! Architecture (inspired by Plex project):
//!   - Two connections: writer (mutex) + reader (mutex) — WAL mode for concurrent reads.
//!   - All key-value data stored in `kv_store` table (replaces in-memory HashMap).
//!   - Ratchet states stored encrypted with XChaCha20-Poly1305 (defense-in-depth; 192-bit nonce).
//!   - Identity encrypted with Argon2id KDF + AES-256-GCM.
//!   - Automatic schema migration (V1 -> latest).
//!
//! ## SQLCipher (production / mobile)
//!
//! When compiled with `--features sqlcipher` (enabled by default in the `mobile`
//! feature set), every database file is transparently encrypted with AES-256-CBC
//! via SQLCipher.  The 32-byte encryption key is passed to `open_encrypted()` and
//! applied as `PRAGMA key = "x'<hex>'"` before any other operation.
//!
//! On desktop / test builds the `bundled-sqlite` feature is used instead — plain
//! SQLite without file encryption (ChaCha20-Poly1305 layer for ratchet states is
//! always active regardless).  This lets `cargo check` / `cargo test` run on
//! Windows without an OpenSSL installation.

use crate::crypto::ratchet::RatchetState;
use crate::error::{ADAError, Result};
use crate::identity::{Identity, IdentityExport, PeerId, PublicBundle};
use argon2::{Argon2, Params as Argon2Params, Version as Argon2Version};
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload as AeadPayload},
    ChaCha20Poly1305, Nonce as ChaNonce, XChaCha20Poly1305, XNonce,
};
#[cfg(feature = "sqlcipher")]
use hex;
use rand::RngCore;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::{Arc, Mutex};

// ── Tokio runtime guard ───────────────────────────────────────────────────────

/// Runs `f` without occupying a Tokio async-executor thread slot.
///
/// When called from **inside a running Tokio multi-thread runtime** (async tasks,
/// `block_on`) `block_in_place` is used so other tasks can progress during
/// blocking I/O.  When called from a plain synchronous context (tests, CLI) it
/// falls back to a direct call to avoid a runtime panic.
///
/// This is the C2 fix: prevents SQLite `Mutex::lock()` from stalling Tokio's
/// 2-thread worker pool on mobile.
#[inline]
pub(crate) fn blocking_op<F, T>(f: F) -> T
where
    F: FnOnce() -> T,
{
    if tokio::runtime::Handle::try_current().is_ok() {
        tokio::task::block_in_place(f)
    } else {
        f()
    }
}

// Schema migrations

const MIGRATION_V1: &str = "
    CREATE TABLE IF NOT EXISTS kv_store (
        key   TEXT NOT NULL PRIMARY KEY,
        value BLOB NOT NULL
    );
    CREATE TABLE IF NOT EXISTS ratchet_enc (
        peer_id    TEXT NOT NULL PRIMARY KEY,
        nonce      BLOB NOT NULL,
        ciphertext BLOB NOT NULL,
        updated_at INTEGER NOT NULL
    );
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
";

/// V2: persistent chat messages table with delivery status machine.
/// Status ordering (cannot downgrade): queued < sent < delivered < read.
const MIGRATION_V2: &str = "
    CREATE TABLE IF NOT EXISTS chat_messages (
        message_id          TEXT NOT NULL PRIMARY KEY,
        peer_id             TEXT NOT NULL,
        is_outgoing         INTEGER NOT NULL DEFAULT 0,
        kind                TEXT NOT NULL DEFAULT 'text',
        body_text           TEXT,
        media_name          TEXT,
        media_mime          TEXT,
        media_size          INTEGER,
        media_blob          BLOB,
        status              TEXT NOT NULL DEFAULT 'queued',
        created_at          INTEGER NOT NULL,
        sent_at             INTEGER,
        delivered_at        INTEGER,
        read_at             INTEGER,
        updated_at          INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_chat_messages_peer ON chat_messages(peer_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_messages_status ON chat_messages(status);
";

/// V3: persistent call log for the in-app call history screen.
const MIGRATION_V3: &str = "
    CREATE TABLE IF NOT EXISTS call_log (
        call_id       TEXT NOT NULL PRIMARY KEY,
        peer_id       TEXT NOT NULL,
        direction     TEXT NOT NULL,
        has_video     INTEGER NOT NULL DEFAULT 0,
        duration_secs INTEGER NOT NULL DEFAULT 0,
        started_at    INTEGER NOT NULL,
        ended_at      INTEGER NOT NULL,
        reason        TEXT NOT NULL DEFAULT 'hung_up'
    );
    CREATE INDEX IF NOT EXISTS idx_call_log_ended ON call_log(ended_at DESC);
";

/// Thread-safe persistent key-value store backed by SQLite.
pub struct KeyValueStore {
    writer: Mutex<Connection>,
    reader: Mutex<Connection>,
    mem: parking_lot::RwLock<std::collections::HashMap<String, Vec<u8>>>,
    persistent: bool,
    ratchet_key: [u8; 32],
}

impl KeyValueStore {
    pub fn in_memory() -> Self {
        let mem_conn = || Connection::open_in_memory().expect("in-memory sqlite");
        // S5 fix: use a random key even for in-memory stores — prevents accidental
        // production use with a well-known all-zeros key.
        let mut ratchet_key = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut ratchet_key);
        KeyValueStore {
            writer: Mutex::new(mem_conn()),
            reader: Mutex::new(mem_conn()),
            mem: parking_lot::RwLock::new(std::collections::HashMap::new()),
            persistent: false,
            ratchet_key,
        }
    }

    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| ADAError::Storage(format!("mkdir: {}", e)))?;
            }
        }
        let path_str = path.to_string_lossy().to_string();
        // S1 fix: load or generate a random per-instance ratchet encryption key
        // stored in a sidecar file — avoids deriving from the public file path.
        let rk_path = format!("{}.rk", path_str);
        let ratchet_key: [u8; 32] =
            match std::fs::read(&rk_path).ok().and_then(|v| v.try_into().ok()) {
                Some(k) => k,
                None => {
                    let mut k = [0u8; 32];
                    rand::rngs::OsRng.fill_bytes(&mut k);
                    // S1-fix: propagate write error — if the sidecar is not persisted
                    // the next open() will generate a different key, making all stored
                    // ratchet states permanently unreadable (silent data loss).
                    std::fs::write(&rk_path, k).map_err(|e| {
                        ADAError::Storage(format!("persist ratchet key '{}': {}", rk_path, e))
                    })?;
                    k
                }
            };
        let open_conn = |p: &str| -> Result<Connection> {
            let conn = Connection::open(p)
                .map_err(|e| ADAError::Storage(format!("sqlite open: {}", e)))?;

            let current_version: u32 = conn
                .query_row("PRAGMA user_version", [], |r| r.get(0))
                .unwrap_or(0);

            if current_version < 1 {
                conn.execute_batch(MIGRATION_V1)
                    .map_err(|e| ADAError::Storage(format!("migration v1: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 1")
                    .map_err(|e| ADAError::Storage(format!("set version 1: {}", e)))?;
            }
            if current_version < 2 {
                conn.execute_batch(MIGRATION_V2)
                    .map_err(|e| ADAError::Storage(format!("migration v2: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 2")
                    .map_err(|e| ADAError::Storage(format!("set version 2: {}", e)))?;
            }
            if current_version < 3 {
                conn.execute_batch(MIGRATION_V3)
                    .map_err(|e| ADAError::Storage(format!("migration v3: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 3")
                    .map_err(|e| ADAError::Storage(format!("set version 3: {}", e)))?;
            }
            Ok(conn)
        };
        Ok(KeyValueStore {
            writer: Mutex::new(open_conn(&path_str)?),
            reader: Mutex::new(open_conn(&path_str)?),
            mem: parking_lot::RwLock::new(std::collections::HashMap::new()),
            persistent: true,
            ratchet_key,
        })
    }

    /// Open a SQLCipher-encrypted database.
    ///
    /// `db_key` is a 32-byte key used for AES-256-CBC full-file encryption
    /// by SQLCipher.  When built without the `sqlcipher` feature this falls
    /// back to a plain SQLite file (the key is ignored) — the ChaCha20-Poly1305
    /// in-row encryption layer for ratchet states is still active.
    ///
    /// # SQLCipher key format
    /// The key is passed as `PRAGMA key = "x'<64-char-hex>'"` which is the raw
    /// binary key form (not a passphrase).  This must be the very first statement
    /// executed on the connection, before any schema access.
    pub fn open_encrypted(path: impl AsRef<Path>, db_key: &[u8; 32]) -> Result<Self> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| ADAError::Storage(format!("mkdir: {}", e)))?;
            }
        }
        let path_str = path.to_string_lossy().to_string();
        // C1 fix: derive ratchet key from db_key (Argon2id output),
        // not from the public file path. This means ratchet-state encryption
        // is tied to the user's pattern, not to a predictable constant.
        let ratchet_key = derive_ratchet_key_from_secret(db_key);

        // Copy the key so the closure can capture it by value.
        let _db_key = *db_key;

        let open_conn = |p: &str| -> Result<Connection> {
            let conn = Connection::open(p)
                .map_err(|e| ADAError::Storage(format!("sqlite open: {}", e)))?;

            // SQLCipher: PRAGMA key must be the very first statement.
            // Compiled out if the `sqlcipher` feature is not enabled so that
            // unencrypted dev builds still work with plain SQLite.
            #[cfg(feature = "sqlcipher")]
            {
                let key_hex = hex::encode(&_db_key);
                conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";\n", key_hex))
                    .map_err(|e| ADAError::Storage(format!("sqlcipher PRAGMA key: {}", e)))?;
            }

            let current_version: u32 = conn
                .query_row("PRAGMA user_version", [], |r| r.get(0))
                .unwrap_or(0);

            if current_version < 1 {
                conn.execute_batch(MIGRATION_V1)
                    .map_err(|e| ADAError::Storage(format!("migration v1: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 1")
                    .map_err(|e| ADAError::Storage(format!("set version 1: {}", e)))?;
            }
            if current_version < 2 {
                conn.execute_batch(MIGRATION_V2)
                    .map_err(|e| ADAError::Storage(format!("migration v2: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 2")
                    .map_err(|e| ADAError::Storage(format!("set version 2: {}", e)))?;
            }
            if current_version < 3 {
                conn.execute_batch(MIGRATION_V3)
                    .map_err(|e| ADAError::Storage(format!("migration v3: {}", e)))?;
                conn.execute_batch("PRAGMA user_version = 3")
                    .map_err(|e| ADAError::Storage(format!("set version 3: {}", e)))?;
            }
            Ok(conn)
        };
        Ok(KeyValueStore {
            writer: Mutex::new(open_conn(&path_str)?),
            reader: Mutex::new(open_conn(&path_str)?),
            mem: parking_lot::RwLock::new(std::collections::HashMap::new()),
            persistent: true,
            ratchet_key,
        })
    }

    pub fn set(&self, key: &str, value: Vec<u8>) -> Result<()> {
        if self.persistent {
            blocking_op(|| {
                let conn = self.writer.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "INSERT INTO kv_store (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    params![key, &value],
                ).map_err(|e| ADAError::Storage(e.to_string()))?;
                Ok(())
            })
        } else {
            self.mem.write().insert(key.to_string(), value);
            Ok(())
        }
    }

    pub fn get(&self, key: &str) -> Option<Vec<u8>> {
        if self.persistent {
            blocking_op(|| {
                let conn = self.reader.lock().unwrap_or_else(|e| e.into_inner());
                conn.query_row("SELECT value FROM kv_store WHERE key = ?1", [key], |row| {
                    row.get::<_, Vec<u8>>(0)
                })
                .optional()
                .ok()
                .flatten()
            })
        } else {
            self.mem.read().get(key).cloned()
        }
    }

    pub fn delete(&self, key: &str) {
        if self.persistent {
            blocking_op(|| {
                if let Ok(conn) = self.writer.lock() {
                    let _ = conn.execute("DELETE FROM kv_store WHERE key = ?1", [key]);
                }
            })
        } else {
            self.mem.write().remove(key);
        }
    }

    pub fn set_json<T: Serialize>(&self, key: &str, value: &T) -> Result<()> {
        let bytes = serde_json::to_vec(value).map_err(ADAError::Json)?;
        self.set(key, bytes)
    }

    pub fn get_json<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Result<Option<T>> {
        match self.get(key) {
            Some(bytes) => Ok(Some(
                serde_json::from_slice(&bytes).map_err(ADAError::Json)?,
            )),
            None => Ok(None),
        }
    }

    /// Return all keys that start with the given prefix.
    /// Append a finished call to the persistent call log.
    ///
    /// Parameters match the `call_log` table columns.  The method is a no-op on
    /// in-memory stores (tests) — they don't run migrations so the table doesn't
    /// exist and the call would error.
    pub fn save_call_log_entry(
        &self,
        call_id: &str,
        peer_id: &str,
        direction: &str,
        has_video: bool,
        duration_secs: i64,
        started_at: i64,
        ended_at: i64,
        reason: &str,
    ) {
        if !self.persistent {
            return;
        }
        blocking_op(|| {
            if let Ok(conn) = self.writer.lock() {
                let _ = conn.execute(
                    "INSERT OR IGNORE INTO call_log \
                     (call_id, peer_id, direction, has_video, duration_secs, \
                      started_at, ended_at, reason) \
                     VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
                    params![
                        call_id,
                        peer_id,
                        direction,
                        if has_video { 1i64 } else { 0i64 },
                        duration_secs,
                        started_at,
                        ended_at,
                        reason,
                    ],
                );
            }
        });
    }

    /// Return up to `limit` most-recent call log entries as a JSON array.
    ///
    /// Schema: `[{"call_id":"HEX","peer_id":"B64","direction":"outgoing"|"incoming",
    ///           "has_video":bool,"duration_secs":N,"started_at":N,"ended_at":N,
    ///           "reason":"..."}]`
    pub fn get_call_history_json(&self, limit: usize) -> String {
        if !self.persistent {
            return "[]".to_string();
        }
        blocking_op(|| {
            let conn = match self.reader.lock() {
                Ok(c) => c,
                Err(_) => return "[]".to_string(),
            };
            let mut stmt = match conn.prepare(
                "SELECT call_id, peer_id, direction, has_video, duration_secs, \
                        started_at, ended_at, reason \
                 FROM call_log ORDER BY ended_at DESC LIMIT ?1",
            ) {
                Ok(s) => s,
                Err(_) => return "[]".to_string(),
            };
            let rows = stmt.query_map([limit as i64], |row| {
                Ok(serde_json::json!({
                    "call_id":       row.get::<_, String>(0)?,
                    "peer_id":       row.get::<_, String>(1)?,
                    "direction":     row.get::<_, String>(2)?,
                    "has_video":     row.get::<_, i64>(3)? != 0,
                    "duration_secs": row.get::<_, i64>(4)?,
                    "started_at":    row.get::<_, i64>(5)?,
                    "ended_at":      row.get::<_, i64>(6)?,
                    "reason":        row.get::<_, String>(7)?,
                }))
            });
            match rows {
                Ok(iter) => {
                    let arr: Vec<serde_json::Value> = iter.filter_map(|r| r.ok()).collect();
                    serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string())
                }
                Err(_) => "[]".to_string(),
            }
        })
    }

    pub fn keys_with_prefix(&self, prefix: &str) -> Vec<String> {
        if self.persistent {
            let prefix_escaped = prefix
                .replace('\\', "\\\\")
                .replace('%', "\\%")
                .replace('_', "\\_");
            let pattern = format!("{}%", prefix_escaped);
            blocking_op(|| {
                let conn = self.reader.lock().unwrap_or_else(|e| e.into_inner());
                let result: Vec<String> =
                    match conn.prepare("SELECT key FROM kv_store WHERE key LIKE ?1 ESCAPE '\\'") {
                        Err(_) => vec![],
                        Ok(mut stmt) => match stmt
                            .query_map([pattern.as_str()], |row| row.get::<_, String>(0))
                        {
                            Ok(rows) => rows.filter_map(|r| r.ok()).collect(),
                            Err(_) => vec![],
                        },
                    };
                result
            })
        } else {
            self.mem
                .read()
                .keys()
                .filter(|k| k.starts_with(prefix))
                .cloned()
                .collect()
        }
    }

    pub fn delete_ratchet_enc(&self, peer_id: &str) -> Result<()> {
        if self.persistent {
            blocking_op(|| {
                let conn = self.writer.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "DELETE FROM ratchet_enc WHERE peer_id = ?1",
                    params![peer_id],
                )
                .map_err(|e| ADAError::Storage(e.to_string()))
                .map(|_| ())
            })
        } else {
            self.mem.write().remove(&format!("ratchet_enc.{}", peer_id));
            Ok(())
        }
    }

    pub fn save_ratchet_enc(&self, peer_id: &str, state_bytes: &[u8]) -> Result<()> {
        let (nonce, ciphertext) =
            encrypt_chacha(&self.ratchet_key, state_bytes, peer_id.as_bytes())?;
        let now = unix_now();
        if self.persistent {
            blocking_op(|| {
                let conn = self.writer.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "INSERT INTO ratchet_enc (peer_id, nonce, ciphertext, updated_at) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(peer_id) DO UPDATE SET nonce = excluded.nonce, ciphertext = excluded.ciphertext, updated_at = excluded.updated_at",
                    params![peer_id, &nonce[..], &ciphertext[..], now],
                ).map_err(|e| ADAError::Storage(e.to_string())).map(|_| ())
            })
        } else {
            self.mem
                .write()
                .insert(format!("ratchet_enc.{}", peer_id), state_bytes.to_vec());
            Ok(())
        }
    }

    pub fn load_ratchet_enc(&self, peer_id: &str) -> Result<Option<Vec<u8>>> {
        if !self.persistent {
            return Ok(self
                .mem
                .read()
                .get(&format!("ratchet_enc.{}", peer_id))
                .cloned());
        }
        let row: Option<(Vec<u8>, Vec<u8>)> = blocking_op(|| {
            let conn = self.reader.lock().unwrap_or_else(|e| e.into_inner());
            conn.query_row(
                "SELECT nonce, ciphertext FROM ratchet_enc WHERE peer_id = ?1",
                [peer_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|e| ADAError::Storage(e.to_string()))
        })?;
        match row {
            None => Ok(None),
            Some((nonce, ciphertext)) => {
                let plain =
                    decrypt_chacha(&self.ratchet_key, &nonce, &ciphertext, peer_id.as_bytes())?;
                Ok(Some(plain))
            }
        }
    }

    /// Return all peer base64 IDs that have a persisted ratchet state.
    /// Used at startup to load sessions for peers who sent us messages
    /// but whose PublicBundle was never saved (no QR scan).
    pub fn list_ratchet_peer_ids(&self) -> Vec<String> {
        if !self.persistent {
            return self
                .mem
                .read()
                .keys()
                .filter_map(|k| k.strip_prefix("ratchet_enc.").map(|s| s.to_string()))
                .collect();
        }
        blocking_op(|| {
            let conn = self.reader.lock().unwrap_or_else(|e| e.into_inner());
            let mut stmt = match conn.prepare("SELECT peer_id FROM ratchet_enc") {
                Ok(s) => s,
                Err(_) => return vec![],
            };
            stmt.query_map([], |r| r.get::<_, String>(0))
                .map(|rows| rows.filter_map(|r| r.ok()).collect())
                .unwrap_or_default()
        })
    }
}

/// Encrypt with XChaCha20-Poly1305 (192-bit random nonce — negligible collision probability).
fn encrypt_chacha(key: &[u8; 32], plaintext: &[u8], aad: &[u8]) -> Result<([u8; 24], Vec<u8>)> {
    let cipher = XChaCha20Poly1305::new(key.into());
    let mut nonce_bytes = [0u8; 24];
    rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = XNonce::from_slice(&nonce_bytes);
    let ciphertext = cipher
        .encrypt(
            nonce,
            AeadPayload {
                msg: plaintext,
                aad,
            },
        )
        .map_err(|_| ADAError::Crypto("XChaCha20 encrypt failed".into()))?;
    Ok((nonce_bytes, ciphertext))
}

/// Decrypt ratchet state — handles both legacy 12-byte (ChaCha20) and current
/// 24-byte (XChaCha20) nonces so existing records migrate transparently on
/// the next save.
fn decrypt_chacha(key: &[u8; 32], nonce: &[u8], ciphertext: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    match nonce.len() {
        12 => {
            // Legacy ChaCha20-Poly1305 record — decrypted and re-encrypted as
            // XChaCha20-Poly1305 on the next save_ratchet_enc call.
            let cipher = ChaCha20Poly1305::new(key.into());
            let n = ChaNonce::from_slice(nonce);
            cipher
                .decrypt(
                    n,
                    AeadPayload {
                        msg: ciphertext,
                        aad,
                    },
                )
                .map_err(|_| ADAError::Crypto("ChaCha20 decrypt failed (tampered?)".into()))
        }
        24 => {
            let cipher = XChaCha20Poly1305::new(key.into());
            let n = XNonce::from_slice(nonce);
            cipher
                .decrypt(
                    n,
                    AeadPayload {
                        msg: ciphertext,
                        aad,
                    },
                )
                .map_err(|_| ADAError::Crypto("XChaCha20 decrypt failed (tampered?)".into()))
        }
        n => Err(ADAError::Crypto(format!("bad nonce len: {}", n))),
    }
}

/// C1 fix: derive ratchet-encryption key from a secret (the SQLCipher db_key).
/// The db_key is Argon2id output — high entropy, tied to the user's pattern.
fn derive_ratchet_key_from_secret(secret: &[u8; 32]) -> [u8; 32] {
    use hkdf::Hkdf;
    use sha2::Sha256;
    // secret as HKDF IKM (high-entropy key material), no salt needed.
    let hk = Hkdf::<Sha256>::new(None, secret);
    let mut key = [0u8; 32];
    // 32 bytes is always within HKDF-SHA256's output limit (8160 bytes), so expand() is infallible.
    let _ = hk.expand(b"ada/ratchet-enc/v1", &mut key);
    key
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

pub struct IdentityStore {
    kv: Arc<KeyValueStore>,
}

const ARGON2_MEM_KIB: u32 = 64 * 1024; // H-NEW-2 fix: align with pattern_auth.rs (was 32 MiB / t=2)
const ARGON2_ITERS: u32 = 3;
const ARGON2_PARALLEL: u32 = 1;

impl IdentityStore {
    pub fn new(kv: Arc<KeyValueStore>) -> Self {
        IdentityStore { kv }
    }

    /// Access the underlying key-value store for low-level byte storage.
    pub(crate) fn kv(&self) -> &Arc<KeyValueStore> {
        &self.kv
    }

    pub fn save_identity(&self, identity: &Identity, passphrase: &[u8]) -> Result<()> {
        let export = identity.export_secret();
        let plaintext = bincode::serialize(&export).map_err(ADAError::Serialization)?;
        // H5 fix: per-user Argon2id salt stored in the KV store.
        // The salt is non-secret so storing it plaintext is correct.
        let salt = self.load_or_create_identity_salt()?;
        let key = derive_key_from_passphrase_with_salt(passphrase, &salt)?;
        let encrypted = crate::crypto::symmetric::encrypt(&key, &plaintext, Some(b"identity"))?;
        self.kv.set_json("identity.enc", &encrypted)?;
        self.kv
            .set_json("identity.peer_id", &identity.peer_id.to_base64())?;
        Ok(())
    }

    pub fn load_identity(&self, passphrase: &[u8]) -> Result<Identity> {
        let encrypted: crate::crypto::symmetric::EncryptedData = self
            .kv
            .get_json("identity.enc")?
            .ok_or(ADAError::Identity("No identity stored".into()))?;
        let salt = self
            .kv
            .get("identity.kdf_salt")
            .ok_or(ADAError::Identity("No identity KDF salt stored".into()))?;
        let salt_arr: [u8; 32] = salt
            .try_into()
            .map_err(|_| ADAError::Identity("Identity KDF salt has wrong length".into()))?;
        let key = derive_key_from_passphrase_with_salt(passphrase, &salt_arr)?;
        let plaintext = crate::crypto::symmetric::decrypt(&key, &encrypted, Some(b"identity"))?;
        let export: IdentityExport =
            bincode::deserialize(&plaintext).map_err(ADAError::Serialization)?;
        Identity::import_secret(export)
    }

    /// Load existing KDF salt or generate + store a fresh one.
    fn load_or_create_identity_salt(&self) -> Result<[u8; 32]> {
        if let Some(existing) = self.kv.get("identity.kdf_salt") {
            if existing.len() == 32 {
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&existing);
                return Ok(arr);
            }
        }
        let mut salt = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut salt);
        self.kv.set("identity.kdf_salt", salt.to_vec())?;
        Ok(salt)
    }

    pub fn has_identity(&self) -> bool {
        self.kv.get("identity.enc").is_some()
    }

    pub fn save_peer_bundle(&self, bundle: &PublicBundle) -> Result<()> {
        let key = format!("peer.{}", bundle.peer_id.to_base64());
        self.kv.set_json(&key, bundle)
    }

    pub fn load_peer_bundle(&self, peer_id: &PeerId) -> Result<Option<PublicBundle>> {
        let key = format!("peer.{}", peer_id.to_base64());
        self.kv.get_json(&key)
    }

    /// Return all stored peer bundles (used on startup to rebuild the conversation list).
    pub fn list_peer_bundles(&self) -> Vec<PublicBundle> {
        self.kv
            .keys_with_prefix("peer.")
            .into_iter()
            .filter_map(|key| self.kv.get_json::<PublicBundle>(&key).ok().flatten())
            .collect()
    }

    /// List all peer IDs that have a saved ratchet state (even without a bundle).
    pub fn list_ratchet_peer_ids(&self) -> Vec<String> {
        self.kv.list_ratchet_peer_ids()
    }

    pub fn save_ratchet_state(&self, peer_id: &PeerId, state: &RatchetState) -> Result<()> {
        // H-NEW-1 fix: wrap serialized bytes in Zeroizing so key material is wiped on drop.
        let bytes =
            zeroize::Zeroizing::new(bincode::serialize(state).map_err(ADAError::Serialization)?);
        self.kv.save_ratchet_enc(&peer_id.to_base64(), &bytes)
    }

    pub fn delete_ratchet_state(&self, peer_id: &PeerId) -> Result<()> {
        self.kv.delete_ratchet_enc(&peer_id.to_base64())
    }

    pub fn load_ratchet_state(&self, peer_id: &PeerId) -> Result<Option<RatchetState>> {
        match self.kv.load_ratchet_enc(&peer_id.to_base64())? {
            None => Ok(None),
            Some(bytes) => {
                let state = bincode::deserialize(&bytes).map_err(ADAError::Serialization)?;
                Ok(Some(state))
            }
        }
    }
}

/// H5 fix: per-user salt variant — replaces the static-salt version.
fn derive_key_from_passphrase_with_salt(passphrase: &[u8], salt: &[u8; 32]) -> Result<[u8; 32]> {
    let params = Argon2Params::new(ARGON2_MEM_KIB, ARGON2_ITERS, ARGON2_PARALLEL, Some(32))
        .map_err(|e| ADAError::Crypto(format!("Argon2 params: {}", e)))?;
    let argon2 = Argon2::new(argon2::Algorithm::Argon2id, Argon2Version::V0x13, params);
    let mut output = [0u8; 32];
    argon2
        .hash_password_into(passphrase, salt, &mut output)
        .map_err(|e| ADAError::Crypto(format!("Argon2id: {}", e)))?;
    Ok(output)
}

// ── Chat Messages ─────────────────────────────────────────────────────────────

/// Delivery / read status for a chat message.
///
/// Status can only advance forward in this order:
/// `queued` → `sent` → `delivered` → `read`
///
/// UPSERT logic will never downgrade a status (e.g., won't overwrite `read` with `sent`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ChatStatus {
    Queued,
    Sent,
    Delivered,
    Read,
    /// Terminal failure — max retries exhausted.
    Failed,
}

impl ChatStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            ChatStatus::Queued => "queued",
            ChatStatus::Sent => "sent",
            ChatStatus::Delivered => "delivered",
            ChatStatus::Read => "read",
            ChatStatus::Failed => "failed",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s {
            "sent" => ChatStatus::Sent,
            "delivered" => ChatStatus::Delivered,
            "read" => ChatStatus::Read,
            "failed" => ChatStatus::Failed,
            _ => ChatStatus::Queued,
        }
    }
}

/// A single chat message record.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    /// Globally unique message ID (hex-encoded [u8;16])
    pub message_id: String,
    /// Remote peer ID (base64-encoded)
    pub peer_id: String,
    pub is_outgoing: bool,
    /// "text" | "image" | "audio" | "video" | "file" | "call_event"
    pub kind: String,
    pub body_text: Option<String>,
    pub media_name: Option<String>,
    pub media_mime: Option<String>,
    pub media_size: Option<i64>,
    /// Inline media blob (stored only when size ≤ INLINE_MEDIA_MAX_BYTES)
    pub media_blob: Option<Vec<u8>>,
    pub status: ChatStatus,
    pub created_at: i64,
    pub sent_at: Option<i64>,
    pub delivered_at: Option<i64>,
    pub read_at: Option<i64>,
}

/// Max size for inline media blob. Larger files must be chunked separately.
const INLINE_MEDIA_MAX_BYTES: usize = 256 * 1024;

impl KeyValueStore {
    /// Upsert a chat message.
    ///
    /// Status is protected by a state machine:
    /// - `read` is never overwritten.
    /// - `delivered` is not overwritten by `queued` or `sent`.
    /// - `sent` is not overwritten by `queued`.
    /// This mirrors the SQL `CASE` logic in Plex's `upsert_chat_message`.
    pub fn upsert_chat_message(&self, msg: &ChatMessage) -> Result<()> {
        if msg.message_id.trim().is_empty() {
            return Err(ADAError::Storage(
                "chat message_id must not be empty".into(),
            ));
        }
        if msg.peer_id.trim().is_empty() {
            return Err(ADAError::Storage("chat peer_id must not be empty".into()));
        }

        // Inline only if blob fits the threshold
        let inline_blob = msg
            .media_blob
            .as_ref()
            .filter(|b| b.len() <= INLINE_MEDIA_MAX_BYTES)
            .cloned();

        let now = unix_now();
        let status_str = msg.status.as_str();

        if self.persistent {
            blocking_op(|| {
                let conn = self.writer.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                "INSERT INTO chat_messages
                 (message_id, peer_id, is_outgoing, kind, body_text,
                  media_name, media_mime, media_size, media_blob,
                  status, created_at, sent_at, delivered_at, read_at, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)
                 ON CONFLICT(message_id) DO UPDATE SET
                    peer_id      = excluded.peer_id,
                    is_outgoing  = excluded.is_outgoing,
                    kind         = excluded.kind,
                    body_text    = excluded.body_text,
                    media_name   = excluded.media_name,
                    media_mime   = excluded.media_mime,
                    media_size   = excluded.media_size,
                    media_blob   = COALESCE(excluded.media_blob, chat_messages.media_blob),
                    status = CASE
                        WHEN chat_messages.status = 'read'                                          THEN chat_messages.status
                        WHEN chat_messages.status = 'delivered' AND excluded.status IN ('queued','sent') THEN chat_messages.status
                        WHEN chat_messages.status = 'sent'      AND excluded.status = 'queued'      THEN chat_messages.status
                        ELSE excluded.status
                    END,
                    sent_at      = COALESCE(excluded.sent_at,      chat_messages.sent_at),
                    delivered_at = COALESCE(excluded.delivered_at, chat_messages.delivered_at),
                    read_at      = COALESCE(excluded.read_at,      chat_messages.read_at),
                    updated_at   = excluded.updated_at",
                params![
                    &msg.message_id,
                    &msg.peer_id,
                    if msg.is_outgoing { 1i32 } else { 0i32 },
                    &msg.kind,
                    &msg.body_text,
                    &msg.media_name,
                    &msg.media_mime,
                    msg.media_size,
                    inline_blob,
                    status_str,
                    msg.created_at,
                    msg.sent_at,
                    msg.delivered_at,
                    msg.read_at,
                    now,
                ],
            ).map_err(|e| ADAError::Storage(format!("upsert_chat_message: {}", e)))
            })?;
        } else {
            // In-memory fallback: directly overwrite in kv (no status protection)
            let bytes = serde_json::to_vec(msg).map_err(ADAError::Json)?;
            self.mem
                .write()
                .insert(format!("chat.{}", msg.message_id), bytes);
        }
        Ok(())
    }

    /// Load all messages for a conversation with a peer, ordered oldest-first.
    pub fn load_chat_messages(&self, peer_id: &str, limit: usize) -> Result<Vec<ChatMessage>> {
        if !self.persistent {
            let map = self.mem.read();
            let prefix = "chat.".to_string();
            let mut msgs: Vec<ChatMessage> = map
                .iter()
                .filter(|(k, _)| k.starts_with(&prefix))
                .filter_map(|(_, v)| serde_json::from_slice(v).ok())
                .filter(|m: &ChatMessage| m.peer_id == peer_id)
                .collect();
            msgs.sort_by_key(|m| m.created_at);
            msgs.truncate(limit);
            return Ok(msgs);
        }

        blocking_op(|| {
            let conn = self.reader.lock().unwrap_or_else(|e| e.into_inner());
            let mut stmt = conn
                .prepare(
                    "SELECT message_id, peer_id, is_outgoing, kind, body_text,
                        media_name, media_mime, media_size, media_blob,
                        status, created_at, sent_at, delivered_at, read_at
                 FROM chat_messages
                 WHERE peer_id = ?1
                 ORDER BY created_at ASC
                 LIMIT ?2",
                )
                .map_err(|e| ADAError::Storage(e.to_string()))?;

            let rows = stmt
                .query_map(params![peer_id, limit as i64], |row| {
                    Ok(ChatMessage {
                        message_id: row.get(0)?,
                        peer_id: row.get(1)?,
                        is_outgoing: row.get::<_, i32>(2)? != 0,
                        kind: row.get(3)?,
                        body_text: row.get(4)?,
                        media_name: row.get(5)?,
                        media_mime: row.get(6)?,
                        media_size: row.get(7)?,
                        media_blob: row.get(8)?,
                        status: ChatStatus::from_str(&row.get::<_, String>(9)?),
                        created_at: row.get(10)?,
                        sent_at: row.get(11)?,
                        delivered_at: row.get(12)?,
                        read_at: row.get(13)?,
                    })
                })
                .map_err(|e| ADAError::Storage(e.to_string()))?;

            rows.collect::<std::result::Result<Vec<_>, _>>()
                .map_err(|e| ADAError::Storage(e.to_string()))
        })
    }

    /// Mark all unread messages from a peer as read (bulk).
    pub fn mark_messages_read(&self, peer_id: &str) -> Result<usize> {
        if !self.persistent {
            return Ok(0);
        }
        let now = unix_now();
        blocking_op(|| {
            let conn = self.writer.lock().unwrap_or_else(|e| e.into_inner());
            let changed = conn
                .execute(
                    "UPDATE chat_messages
                 SET status = 'read', read_at = ?1, updated_at = ?1
                 WHERE peer_id = ?2
                   AND status != 'read'
                   AND is_outgoing = 0",
                    params![now, peer_id],
                )
                .map_err(|e| ADAError::Storage(e.to_string()))?;
            Ok(changed)
        })
    }
}
