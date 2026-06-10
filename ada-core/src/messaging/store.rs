use crate::error::{ADAError, Result};
use crate::identity::PeerId;
use crate::messaging::types::{Message, MessageId, MessageStatus};
use parking_lot::RwLock;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

const DB_SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS messages (
        id        TEXT    NOT NULL PRIMARY KEY,
        conv_key  TEXT    NOT NULL,
        timestamp INTEGER NOT NULL,
        status    TEXT    NOT NULL DEFAULT 'sending',
        msg_blob  BLOB    NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages (conv_key, timestamp);
    CREATE TABLE IF NOT EXISTS conv_meta (
        conv_key         TEXT    NOT NULL PRIMARY KEY,
        display_name     TEXT    NOT NULL DEFAULT '',
        last_activity_ms INTEGER NOT NULL DEFAULT 0,
        unread_count     INTEGER NOT NULL DEFAULT 0
    );
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
";

const FTS_SCHEMA: &str = "
    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
        id UNINDEXED,
        conv_key UNINDEXED,
        text,
        tokenize = 'unicode61 remove_diacritics 2'
    );
";

/// Conversation identifier
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ConversationId {
    /// Direct conversation with a peer
    Direct(PeerId),
    /// Group conversation
    Group([u8; 16]),
}

/// Metadata for a conversation
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Conversation {
    pub id: ConversationId,
    pub display_name: String,
    pub last_message_id: Option<MessageId>,
    pub last_activity_ms: u64,
    pub unread_count: u32,
    pub is_muted: bool,
    pub is_archived: bool,
}

/// Message store with SQLite persistence and in-memory read cache.
pub struct MessageStore {
    /// conversation_key -> Vec<Message> (sorted by timestamp)
    conversations: RwLock<std::collections::HashMap<String, Vec<Message>>>,
    /// message_id -> conversation_key
    message_index: RwLock<std::collections::HashMap<[u8; 16], String>>,
    /// conversation metadata
    conv_meta: RwLock<std::collections::HashMap<String, Conversation>>,
    /// SQLite connection (None = in-memory / test mode)
    db: Option<Mutex<Connection>>,
    /// Whether the current SQLite backend accepted FTS5 initialization.
    fts_enabled: AtomicBool,
}

use crate::storage::blocking_op;

fn status_str(s: &MessageStatus) -> &'static str {
    match s {
        MessageStatus::Sending => "sending",
        MessageStatus::Sent => "sent",
        MessageStatus::Delivered => "delivered",
        MessageStatus::Read => "read",
        MessageStatus::Failed(_) => "failed",
    }
}

fn status_from_str(s: &str) -> MessageStatus {
    match s {
        "sent" => MessageStatus::Sent,
        "delivered" => MessageStatus::Delivered,
        "read" => MessageStatus::Read,
        "failed" => MessageStatus::Failed(String::new()),
        _ => MessageStatus::Sending,
    }
}

impl MessageStore {
    /// Create an in-memory store (for testing or when no path given)
    pub fn in_memory() -> Self {
        MessageStore {
            conversations: RwLock::new(std::collections::HashMap::new()),
            message_index: RwLock::new(std::collections::HashMap::new()),
            conv_meta: RwLock::new(std::collections::HashMap::new()),
            db: None,
            fts_enabled: AtomicBool::new(false),
        }
    }

    /// Open (or create) a persistent store backed by SQLite.
    /// The database file is created at `<path>/messages.db`.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let dir = path.as_ref().to_path_buf();
        std::fs::create_dir_all(&dir).map_err(|e| ADAError::Storage(e.to_string()))?;
        let db_file = dir.join("messages.db");
        let conn = Connection::open(&db_file)
            .map_err(|e| ADAError::Storage(format!("open messages.db: {}", e)))?;
        conn.execute_batch(DB_SCHEMA)
            .map_err(|e| ADAError::Storage(format!("messages migration: {}", e)))?;
        let store = MessageStore {
            conversations: RwLock::new(std::collections::HashMap::new()),
            message_index: RwLock::new(std::collections::HashMap::new()),
            conv_meta: RwLock::new(std::collections::HashMap::new()),
            db: Some(Mutex::new(conn)),
            fts_enabled: AtomicBool::new(false),
        };
        store.load_from_db()?;
        store.initialize_fts_index();
        Ok(store)
    }

    /// Open (or create) a SQLCipher-encrypted store.
    ///
    /// `db_key` is the same 32-byte key used for `keys.db` — derived from the
    /// user's pattern via Argon2id.  When built without the `sqlcipher` feature
    /// this falls back silently to a plain SQLite file (development builds only).
    ///
    /// # Security: R1
    /// Previously `messages.db` was opened without encryption while `keys.db`
    /// was protected.  This method closes the gap.
    pub fn open_encrypted(path: impl AsRef<Path>, db_key: &[u8; 32]) -> Result<Self> {
        let dir = path.as_ref().to_path_buf();
        std::fs::create_dir_all(&dir).map_err(|e| ADAError::Storage(e.to_string()))?;
        let db_file = dir.join("messages.db");
        let conn = Connection::open(&db_file)
            .map_err(|e| ADAError::Storage(format!("open messages.db: {}", e)))?;

        // R1: Apply SQLCipher key as the very first statement — before any schema access.
        // Compiled out when the `sqlcipher` feature is not enabled so dev builds work.
        #[cfg(feature = "sqlcipher")]
        {
            let key_hex = hex::encode(db_key);
            conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";\n", key_hex))
                .map_err(|e| ADAError::Storage(format!("messages.db PRAGMA key: {}", e)))?;
        }
        // In non-sqlcipher builds the key is unused; suppress the unused-variable warning.
        #[cfg(not(feature = "sqlcipher"))]
        let _ = db_key;

        conn.execute_batch(DB_SCHEMA)
            .map_err(|e| ADAError::Storage(format!("messages migration: {}", e)))?;
        let store = MessageStore {
            conversations: RwLock::new(std::collections::HashMap::new()),
            message_index: RwLock::new(std::collections::HashMap::new()),
            conv_meta: RwLock::new(std::collections::HashMap::new()),
            db: Some(Mutex::new(conn)),
            fts_enabled: AtomicBool::new(false),
        };
        store.load_from_db()?;
        store.initialize_fts_index();
        Ok(store)
    }

    fn initialize_fts_index(&self) {
        let Some(db) = &self.db else {
            return;
        };

        let init_result = blocking_op(|| -> rusqlite::Result<()> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            conn.execute_batch(FTS_SCHEMA)?;
            Ok(())
        });

        if let Err(error) = init_result {
            tracing::warn!("FTS5 disabled: {}", error);
            self.fts_enabled.store(false, Ordering::Release);
            return;
        }

        self.fts_enabled.store(true, Ordering::Release);
        if let Err(error) = self.rebuild_fts_index() {
            tracing::warn!("FTS5 rebuild failed, disabling search index: {}", error);
            self.fts_enabled.store(false, Ordering::Release);
        }
    }

    fn is_fts_enabled(&self) -> bool {
        self.fts_enabled.load(Ordering::Acquire) && self.db.is_some()
    }

    fn effective_text_entries(messages: &[Message]) -> Vec<(MessageId, String)> {
        let mut text_entries: Vec<(MessageId, String)> = messages
            .iter()
            .filter_map(|message| match &message.kind {
                crate::messaging::types::MessageKind::Text(text) => {
                    Some((message.id, text.clone()))
                }
                _ => None,
            })
            .collect();
        let text_positions: std::collections::HashMap<MessageId, usize> = text_entries
            .iter()
            .enumerate()
            .map(|(index, (id, _))| (*id, index))
            .collect();
        let text_senders: std::collections::HashMap<MessageId, PeerId> = messages
            .iter()
            .filter_map(|message| match &message.kind {
                crate::messaging::types::MessageKind::Text(_) => {
                    Some((message.id, message.sender.clone()))
                }
                _ => None,
            })
            .collect();

        for message in messages {
            let crate::messaging::types::MessageKind::Edit {
                target_msg_id,
                new_text,
            } = &message.kind
            else {
                continue;
            };
            let Some(position) = text_positions.get(target_msg_id) else {
                continue;
            };
            let Some(target_sender) = text_senders.get(target_msg_id) else {
                continue;
            };
            if *target_sender != message.sender {
                continue;
            }
            text_entries[*position].1 = new_text.clone();
        }

        text_entries
    }

    fn search_messages_with_effective_text(
        messages: &[Message],
        query_lower: &str,
    ) -> Vec<Message> {
        let effective_text_by_id: std::collections::HashMap<MessageId, String> =
            Self::effective_text_entries(messages).into_iter().collect();

        messages
            .iter()
            .filter_map(|message| {
                let effective_text = effective_text_by_id.get(&message.id)?;
                if !effective_text.to_lowercase().contains(query_lower) {
                    return None;
                }

                let mut message = message.clone();
                if let crate::messaging::types::MessageKind::Text(text) = &mut message.kind {
                    *text = effective_text.clone();
                }
                Some(message)
            })
            .collect()
    }

    fn effective_text_for_message(
        &self,
        conv_key: &str,
        target_msg_id: &MessageId,
    ) -> Option<String> {
        let convs = self.conversations.read();
        let messages = convs.get(conv_key)?;
        Self::effective_text_entries(messages)
            .into_iter()
            .find(|(id, _)| id == target_msg_id)
            .map(|(_, text)| text)
    }

    fn upsert_fts_row(&self, id: &MessageId, conv_key: &str, text: &str) -> Result<()> {
        if !self.is_fts_enabled() {
            return Ok(());
        }
        let Some(db) = &self.db else {
            return Ok(());
        };
        let id_hex = hex::encode(id);
        let conv_key_owned = conv_key.to_string();
        let text_owned = text.to_string();
        blocking_op(|| -> rusqlite::Result<()> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            conn.execute("DELETE FROM messages_fts WHERE id = ?1", params![id_hex])?;
            conn.execute(
                "INSERT INTO messages_fts (id, conv_key, text) VALUES (?1, ?2, ?3)",
                params![id_hex, conv_key_owned, text_owned],
            )?;
            Ok(())
        })
        .map_err(|e| ADAError::Storage(e.to_string()))
    }

    fn delete_fts_row(&self, id: &MessageId) -> Result<()> {
        if !self.is_fts_enabled() {
            return Ok(());
        }
        let Some(db) = &self.db else {
            return Ok(());
        };
        let id_hex = hex::encode(id);
        blocking_op(|| -> rusqlite::Result<()> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            conn.execute("DELETE FROM messages_fts WHERE id = ?1", params![id_hex])?;
            Ok(())
        })
        .map_err(|e| ADAError::Storage(e.to_string()))
    }

    fn delete_fts_conversation(&self, conv_key: &str) -> Result<()> {
        if !self.is_fts_enabled() {
            return Ok(());
        }
        let Some(db) = &self.db else {
            return Ok(());
        };
        let conv_key_owned = conv_key.to_string();
        blocking_op(|| -> rusqlite::Result<()> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            conn.execute(
                "DELETE FROM messages_fts WHERE conv_key = ?1",
                params![conv_key_owned],
            )?;
            Ok(())
        })
        .map_err(|e| ADAError::Storage(e.to_string()))
    }

    fn refresh_fts_for_message(&self, conv_key: &str, msg: &Message) -> Result<()> {
        match &msg.kind {
            crate::messaging::types::MessageKind::Text(text) => {
                self.upsert_fts_row(&msg.id, conv_key, text)
            }
            crate::messaging::types::MessageKind::Edit { target_msg_id, .. } => {
                if let Some(effective_text) =
                    self.effective_text_for_message(conv_key, target_msg_id)
                {
                    self.upsert_fts_row(target_msg_id, conv_key, &effective_text)?;
                }
                Ok(())
            }
            _ => Ok(()),
        }
    }

    fn refresh_fts_after_delete(&self, conv_key: &str, removed_msg: &Message) -> Result<()> {
        match &removed_msg.kind {
            crate::messaging::types::MessageKind::Text(_) => self.delete_fts_row(&removed_msg.id),
            crate::messaging::types::MessageKind::Edit { target_msg_id, .. } => {
                if let Some(effective_text) =
                    self.effective_text_for_message(conv_key, target_msg_id)
                {
                    self.upsert_fts_row(target_msg_id, conv_key, &effective_text)?;
                }
                Ok(())
            }
            _ => Ok(()),
        }
    }

    fn rebuild_fts_index(&self) -> Result<()> {
        if !self.is_fts_enabled() {
            return Ok(());
        }
        let Some(db) = &self.db else {
            return Ok(());
        };

        let documents: Vec<(String, String, String)> = {
            let convs = self.conversations.read();
            convs
                .iter()
                .flat_map(|(conv_key, messages)| {
                    Self::effective_text_entries(messages)
                        .into_iter()
                        .map(|(id, text)| (hex::encode(id), conv_key.clone(), text))
                        .collect::<Vec<_>>()
                })
                .collect()
        };

        blocking_op(|| -> rusqlite::Result<()> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            conn.execute("DELETE FROM messages_fts", [])?;
            for (id_hex, conv_key, text) in &documents {
                conn.execute(
                    "INSERT INTO messages_fts (id, conv_key, text) VALUES (?1, ?2, ?3)",
                    params![id_hex, conv_key, text],
                )?;
            }
            Ok(())
        })
        .map_err(|e| ADAError::Storage(e.to_string()))
    }

    fn build_fts_query(query: &str) -> Option<String> {
        let tokens: Vec<String> = query
            .split_whitespace()
            .map(|token| {
                token
                    .chars()
                    .filter(|ch| ch.is_alphanumeric() || *ch == '_' || *ch == '-')
                    .collect::<String>()
            })
            .filter(|token| !token.is_empty())
            .collect();

        if tokens.is_empty() {
            None
        } else {
            Some(
                tokens
                    .into_iter()
                    .map(|token| format!("{}*", token))
                    .collect::<Vec<_>>()
                    .join(" AND "),
            )
        }
    }

    fn search_conversation_keys_linear(&self, query: &str) -> Vec<String> {
        let query = query.to_lowercase();
        let convs = self.conversations.read();
        convs
            .iter()
            .filter_map(|(conv_key, messages)| {
                let matched = Self::effective_text_entries(messages)
                    .into_iter()
                    .any(|(_, text)| text.to_lowercase().contains(&query));
                if matched {
                    Some(conv_key.clone())
                } else {
                    None
                }
            })
            .collect()
    }

    pub fn search_conversation_keys(&self, query: &str) -> Vec<String> {
        if query.trim().is_empty() {
            return Vec::new();
        }

        let Some(fts_query) = Self::build_fts_query(query) else {
            return self.search_conversation_keys_linear(query);
        };

        if !self.is_fts_enabled() {
            return self.search_conversation_keys_linear(query);
        }

        let Some(db) = &self.db else {
            return self.search_conversation_keys_linear(query);
        };

        let query_owned = fts_query.clone();
        match blocking_op(|| -> rusqlite::Result<Vec<String>> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            let mut stmt = conn.prepare(
                "SELECT DISTINCT conv_key FROM messages_fts WHERE messages_fts MATCH ?1",
            )?;
            let rows = stmt
                .query_map(params![query_owned], |row| row.get::<_, String>(0))?
                .filter_map(|row| row.ok())
                .collect();
            Ok(rows)
        }) {
            Ok(keys) => keys,
            Err(error) => {
                tracing::warn!(
                    "FTS query failed, falling back to in-memory search: {}",
                    error
                );
                self.search_conversation_keys_linear(query)
            }
        }
    }

    pub fn search_conversations(&self, query: &str) -> Vec<Conversation> {
        if query.trim().is_empty() {
            return self.list_conversations();
        }

        let matched_keys: std::collections::HashSet<String> =
            self.search_conversation_keys(query).into_iter().collect();
        let query_lower = query.to_lowercase();

        self.list_conversations()
            .into_iter()
            .filter(|conversation| {
                let key = Self::conv_key(&conversation.id);
                matched_keys.contains(&key)
                    || conversation
                        .display_name
                        .to_lowercase()
                        .contains(&query_lower)
            })
            .collect()
    }

    fn conv_key(id: &ConversationId) -> String {
        match id {
            ConversationId::Direct(peer) => format!("d:{}", peer.to_base64()),
            ConversationId::Group(gid) => format!("g:{}", hex::encode(gid)),
        }
    }

    /// Reconstruct a ConversationId from its string key ("d:BASE64" or "g:HEX").
    fn parse_conv_key(key: &str) -> Option<ConversationId> {
        if let Some(b64) = key.strip_prefix("d:") {
            PeerId::from_base64(b64).ok().map(ConversationId::Direct)
        } else if let Some(hex_str) = key.strip_prefix("g:") {
            let bytes = hex::decode(hex_str).ok()?;
            if bytes.len() == 16 {
                let mut arr = [0u8; 16];
                arr.copy_from_slice(&bytes);
                Some(ConversationId::Group(arr))
            } else {
                None
            }
        } else {
            None
        }
    }

    /// Load all persisted messages and conversations from SQLite into memory.
    fn load_from_db(&self) -> Result<()> {
        let db = match &self.db {
            Some(d) => d,
            None => return Ok(()),
        };

        // Fetch all rows inside a single lock acquisition.
        let (conv_rows, msg_rows) = blocking_op(|| -> rusqlite::Result<_> {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());

            let mut stmt = conn.prepare(
                "SELECT conv_key, display_name, last_activity_ms, unread_count FROM conv_meta",
            )?;
            let conv_rows: Vec<(String, String, i64, i64)> = stmt
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
                .filter_map(|r| r.ok())
                .collect();
            drop(stmt);

            let mut stmt = conn.prepare(
                "SELECT conv_key, status, msg_blob FROM messages ORDER BY timestamp ASC",
            )?;
            let msg_rows: Vec<(String, String, Vec<u8>)> = stmt
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .filter_map(|r| r.ok())
                .collect();
            drop(stmt);

            Ok((conv_rows, msg_rows))
        })
        .map_err(|e| ADAError::Storage(e.to_string()))?;

        // Populate conv_meta map.
        {
            let mut meta = self.conv_meta.write();
            for (key, display_name, last_ms, unread) in conv_rows {
                if let Some(conv_id) = Self::parse_conv_key(&key) {
                    meta.insert(
                        key,
                        Conversation {
                            id: conv_id,
                            display_name,
                            last_message_id: None,
                            last_activity_ms: last_ms as u64,
                            unread_count: unread as u32,
                            is_muted: false,
                            is_archived: false,
                        },
                    );
                }
            }
        }

        // Populate message maps.
        {
            let mut convs = self.conversations.write();
            let mut idx = self.message_index.write();
            for (conv_key, status_s, blob) in msg_rows {
                let mut msg: Message = match bincode::deserialize(&blob) {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("Skipping unreadable stored message: {}", e);
                        continue;
                    }
                };
                // Status is kept in a separate column so it can be updated cheaply.
                msg.status = status_from_str(&status_s);
                idx.insert(msg.id, conv_key.clone());
                convs.entry(conv_key).or_default().push(msg);
            }
        }

        // Back-fill last_message_id in conv_meta from loaded messages.
        {
            let convs = self.conversations.read();
            let mut meta = self.conv_meta.write();
            for (key, msgs) in convs.iter() {
                if let Some(last) = msgs.last() {
                    if let Some(conv) = meta.get_mut(key) {
                        conv.last_message_id = Some(last.id);
                    }
                }
            }
        }

        Ok(())
    }

    /// Store a message (in-memory + SQLite).
    pub fn save_message(&self, conv_id: &ConversationId, msg: Message) -> Result<()> {
        self.save_message_internal(conv_id, msg, true, true)
    }

    pub fn save_message_with_unread(
        &self,
        conv_id: &ConversationId,
        msg: Message,
        increment_unread: bool,
    ) -> Result<()> {
        self.save_message_internal(conv_id, msg, increment_unread, true)
    }

    /// Store a protocol / maintenance message without changing conversation metadata.
    pub fn save_hidden_message(&self, conv_id: &ConversationId, msg: Message) -> Result<()> {
        self.save_message_internal(conv_id, msg, false, false)
    }

    fn save_message_internal(
        &self,
        conv_id: &ConversationId,
        msg: Message,
        increment_unread: bool,
        update_conv_meta: bool,
    ) -> Result<()> {
        let key = Self::conv_key(conv_id);
        let msg_id = msg.id;
        let id_hex = hex::encode(msg.id);
        let ts = msg.timestamp as i64;
        let sv = status_str(&msg.status);
        // Serialize before msg is moved.
        let blob = bincode::serialize(&msg).map_err(ADAError::Serialization)?;

        // Fast dedup check under a read lock — avoids SQLite round-trip for dupes.
        {
            let messages = self.conversations.read();
            if let Some(vec) = messages.get(&key) {
                if vec.iter().any(|m| m.id == msg_id) {
                    return Ok(());
                }
            }
        }

        // ── 1. Persist to SQLite FIRST ──────────────────────────────────────
        // Only update in-memory state after the durable write succeeds.
        // This prevents the memory being ahead of disk on a SQLite failure.
        if let Some(db) = &self.db {
            let key2 = key.clone();
            let inserted = blocking_op(|| -> rusqlite::Result<bool> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                let inserted = conn.execute(
                    "INSERT OR IGNORE INTO messages \
                     (id, conv_key, timestamp, status, msg_blob) VALUES (?1,?2,?3,?4,?5)",
                    params![id_hex, key2, ts, sv, blob],
                )? > 0;

                if inserted && update_conv_meta {
                    if increment_unread {
                        conn.execute(
                            "INSERT INTO conv_meta (conv_key, display_name, last_activity_ms, unread_count) \
                             VALUES (?1, '', ?2, 1) \
                             ON CONFLICT(conv_key) DO UPDATE SET \
                                 last_activity_ms = MAX(excluded.last_activity_ms, last_activity_ms), \
                                 unread_count = unread_count + 1",
                            params![key2, ts],
                        )?;
                    } else {
                        conn.execute(
                            "INSERT INTO conv_meta (conv_key, display_name, last_activity_ms, unread_count) \
                             VALUES (?1, '', ?2, 0) \
                             ON CONFLICT(conv_key) DO UPDATE SET \
                                 last_activity_ms = MAX(excluded.last_activity_ms, last_activity_ms)",
                            params![key2, ts],
                        )?;
                    }
                }

                Ok(inserted)
            }).map_err(|e| ADAError::Storage(e.to_string()))?;

            if !inserted {
                return Ok(());
            }
        }

        let refresh_msg = msg.clone();

        // ── 2. Update in-memory state (dedup under write lock) ──────────────
        {
            let mut messages = self.conversations.write();
            let vec = messages.entry(key.clone()).or_default();
            if vec.iter().any(|m| m.id == msg_id) {
                // Another thread beat us here (or `INSERT OR IGNORE` was a no-op).
                return Ok(());
            }
            vec.push(msg);
            vec.sort_by_key(|m| m.timestamp);
        }

        self.message_index.write().insert(msg_id, key.clone());

        if update_conv_meta {
            let mut meta = self.conv_meta.write();
            let conv = meta.entry(key.clone()).or_insert_with(|| Conversation {
                id: conv_id.clone(),
                display_name: String::new(),
                last_message_id: None,
                last_activity_ms: 0,
                unread_count: 0,
                is_muted: false,
                is_archived: false,
            });
            conv.last_message_id = Some(msg_id);
            conv.last_activity_ms = ts as u64;
            if increment_unread {
                conv.unread_count = conv.unread_count.saturating_add(1);
            }
        }

        self.refresh_fts_for_message(&key, &refresh_msg)?;

        Ok(())
    }

    /// Get messages in a conversation (paginated)
    pub fn get_messages(
        &self,
        conv_id: &ConversationId,
        before_ts: Option<u64>,
        limit: usize,
    ) -> Vec<Message> {
        let key = Self::conv_key(conv_id);
        let messages = self.conversations.read();
        let vec = match messages.get(&key) {
            Some(v) => v,
            None => return vec![],
        };

        let filtered: Vec<Message> = vec
            .iter()
            .filter(|m| before_ts.map(|ts| m.timestamp < ts).unwrap_or(true))
            .cloned()
            .collect();

        let start = filtered.len().saturating_sub(limit);
        filtered[start..].to_vec()
    }

    /// Get a single message by ID
    pub fn get_message(&self, id: &MessageId) -> Option<Message> {
        let idx = self.message_index.read();
        let key = idx.get(id)?;
        let convs = self.conversations.read();
        let vec = convs.get(key)?;
        vec.iter().find(|m| &m.id == id).cloned()
    }

    /// Update message status (in-memory + SQLite).
    pub fn update_status(&self, id: &MessageId, status: MessageStatus) -> Result<()> {
        // Clone the key while holding only the read lock.
        let key_owned: String = {
            let idx = self.message_index.read();
            idx.get(id)
                .ok_or_else(|| ADAError::Message("Message not found".into()))?
                .clone()
        };

        {
            let mut convs = self.conversations.write();
            if let Some(vec) = convs.get_mut(&key_owned) {
                if let Some(msg) = vec.iter_mut().find(|m| m.id == *id) {
                    msg.status = status.clone();
                }
            }
        }

        if let Some(db) = &self.db {
            let id_hex = hex::encode(id);
            let status_s = status_str(&status);
            blocking_op(|| -> rusqlite::Result<()> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "UPDATE messages SET status = ?1 WHERE id = ?2",
                    params![status_s, id_hex],
                )?;
                Ok(())
            })
            .map_err(|e| ADAError::Storage(e.to_string()))?;
        }

        Ok(())
    }

    /// Look up a single message by its ID (returns a clone from the in-memory index).
    pub fn get_message_by_id(&self, id: &MessageId) -> Option<crate::messaging::types::Message> {
        let idx = self.message_index.read();
        let key = idx.get(id)?;
        let convs = self.conversations.read();
        convs.get(key)?.iter().find(|m| &m.id == id).cloned()
    }

    /// Delete a message
    pub fn delete_message(&self, id: &MessageId) -> Result<()> {
        let key = {
            let idx = self.message_index.read();
            idx.get(id).cloned()
        };
        if let Some(key) = key {
            let removed_msg = {
                let mut convs = self.conversations.write();
                convs.get_mut(&key).and_then(|vec| {
                    vec.iter()
                        .position(|m| &m.id == id)
                        .map(|index| vec.remove(index))
                })
            };
            self.message_index.write().remove(id);

            if let Some(db) = &self.db {
                let id_hex = hex::encode(id);
                blocking_op(|| -> rusqlite::Result<()> {
                    let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                    conn.execute("DELETE FROM messages WHERE id = ?1", [&id_hex])?;
                    Ok(())
                })
                .map_err(|e| ADAError::Storage(e.to_string()))?;
            }

            if let Some(removed_msg) = removed_msg.as_ref() {
                self.refresh_fts_after_delete(&key, removed_msg)?;
            }
        }
        Ok(())
    }

    /// List all conversations sorted by last activity
    pub fn list_conversations(&self) -> Vec<Conversation> {
        let mut convs: Vec<Conversation> = self.conv_meta.read().values().cloned().collect();
        convs.sort_by(|a, b| b.last_activity_ms.cmp(&a.last_activity_ms));
        convs
    }

    /// Mark conversation as read (resets unread counter)
    pub fn mark_read(&self, conv_id: &ConversationId) {
        let key = Self::conv_key(conv_id);
        {
            let mut meta = self.conv_meta.write();
            if let Some(conv) = meta.get_mut(&key) {
                conv.unread_count = 0;
            }
        }
        if let Some(db) = &self.db {
            blocking_op(|| -> rusqlite::Result<()> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "UPDATE conv_meta SET unread_count = 0 WHERE conv_key = ?1",
                    [&key],
                )?;
                Ok(())
            })
            .ok();
        }

        // Ephemeral TTL: start the counter for any ephemeral messages in this conversation
        // that just transitioned from unread to read. We assume any 'Delivered' or 'Sent'
        // message is now 'Read'.
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let mut to_update = Vec::new();
        {
            let mut convs = self.conversations.write();
            if let Some(msgs) = convs.get_mut(&key) {
                for msg in msgs.iter_mut() {
                    if !matches!(msg.status, MessageStatus::Read) {
                        msg.status = MessageStatus::Read;
                        if let Some(ttl) = msg.expires_in {
                            if msg.expires_at.is_none() {
                                // start the TTL clock
                                let expires_at = now_ms.saturating_add((ttl as u64) * 1000);
                                msg.expires_at = Some(expires_at);
                                to_update.push(msg.clone());
                            }
                        }
                    }
                }
            }
        }

        // Re-save updated ephemeral messages into SQLite to persist expires_at
        for msg in to_update {
            let id = msg.id;
            let id_hex = hex::encode(&id);
            let sv = status_str(&msg.status);
            if let Ok(blob) = bincode::serialize(&msg) {
                if let Some(db) = &self.db {
                    blocking_op(|| -> rusqlite::Result<()> {
                        let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                        conn.execute(
                            "UPDATE messages SET status = ?1, msg_blob = ?2 WHERE id = ?3",
                            params![sv, blob, id_hex],
                        )?;
                        Ok(())
                    })
                    .ok();
                }
            }
        }
    }

    /// Search messages by text content
    pub fn search(&self, query: &str) -> Vec<Message> {
        let query = query.trim().to_lowercase();
        if query.is_empty() {
            return Vec::new();
        }

        let convs = self.conversations.read();
        convs
            .values()
            .flat_map(|msgs| Self::search_messages_with_effective_text(msgs, &query))
            .collect()
    }

    pub fn message_count(&self) -> usize {
        self.conversations.read().values().map(|v| v.len()).sum()
    }

    pub fn expire_stale_sending(&self, max_age_secs: u64) -> Vec<MessageId> {
        let cutoff = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
            .saturating_sub(max_age_secs);

        let mut expired: Vec<MessageId> = Vec::new();

        // 1. Update in-memory cache
        {
            let mut convs = self.conversations.write();
            for msgs in convs.values_mut() {
                for msg in msgs.iter_mut() {
                    if matches!(msg.status, MessageStatus::Sending) && msg.timestamp < cutoff {
                        msg.status = MessageStatus::Failed("expired".into());
                        expired.push(msg.id);
                    }
                }
            }
        }

        // 2. Batch-update SQLite
        if !expired.is_empty() {
            if let Some(db) = &self.db {
                let ids: Vec<String> = expired.iter().map(hex::encode).collect();
                let _ = blocking_op(|| -> rusqlite::Result<()> {
                    let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                    for id_hex in &ids {
                        conn.execute(
                            "UPDATE messages SET status = 'failed' WHERE id = ?1 AND status = 'sending'",
                            [id_hex],
                        )?;
                    }
                    Ok(())
                });
            }
        }

        expired
    }

    /// Hard-delete ephemeral messages whose TTL has expired.
    /// Returns the IDs of affected conversations so UI can be refreshed.
    pub fn prune_ephemeral_messages(&self) -> Vec<ConversationId> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let mut to_remove_ids = Vec::new();
        let mut affected_convs = std::collections::HashSet::new();

        // 1. Delete from memory
        {
            let mut convs = self.conversations.write();
            let mut idx = self.message_index.write();

            for (conv_key, msgs) in convs.iter_mut() {
                let initial_len = msgs.len();
                msgs.retain(|msg| {
                    if let Some(expires_in) = msg.expires_in {
                        if now >= msg.timestamp + (expires_in as u64) {
                            to_remove_ids.push(msg.id);
                            idx.remove(&msg.id);
                            return false;
                        }
                    }
                    true
                });

                if msgs.len() != initial_len {
                    if let Some(c_id) = Self::parse_conv_key(conv_key) {
                        affected_convs.insert(c_id);
                    }
                }
            }
        }

        // 2. Delete from SQLite
        if !to_remove_ids.is_empty() {
            if let Some(db) = &self.db {
                let ids: Vec<String> = to_remove_ids.iter().map(hex::encode).collect();
                let _ = blocking_op(|| -> rusqlite::Result<()> {
                    let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                    for id_hex in &ids {
                        conn.execute("DELETE FROM messages WHERE id = ?1", [id_hex])?;
                    }
                    Ok(())
                });
            }
        }

        affected_convs.into_iter().collect()
    }

    /// Delete an entire conversation and all its messages, both in memory and SQLite.
    pub fn delete_conversation(&self, conv_id: &ConversationId) -> Result<()> {
        let key = Self::conv_key(conv_id);

        // Remove message IDs from index first.
        {
            let convs = self.conversations.read();
            if let Some(msgs) = convs.get(&key) {
                let mut idx = self.message_index.write();
                for m in msgs {
                    idx.remove(&m.id);
                }
            }
        }
        self.conversations.write().remove(&key);
        self.conv_meta.write().remove(&key);

        if let Some(db) = &self.db {
            let key2 = key.clone();
            blocking_op(|| -> rusqlite::Result<()> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute("DELETE FROM messages  WHERE conv_key = ?1", [&key2])?;
                conn.execute("DELETE FROM conv_meta WHERE conv_key = ?1", [&key2])?;
                Ok(())
            })
            .map_err(|e| ADAError::Storage(e.to_string()))?;
        }

        self.delete_fts_conversation(&key)?;

        Ok(())
    }

    /// Clear all messages in a conversation but keep the conversation entry in the list.
    pub fn clear_messages(&self, conv_id: &ConversationId) -> Result<()> {
        let key = Self::conv_key(conv_id);

        // Remove message IDs from index.
        {
            let convs = self.conversations.read();
            if let Some(msgs) = convs.get(&key) {
                let mut idx = self.message_index.write();
                for m in msgs {
                    idx.remove(&m.id);
                }
            }
        }
        // Clear the message list, leave the key present so the conversation stays visible.
        if let Some(vec) = self.conversations.write().get_mut(&key) {
            vec.clear();
        }
        // Reset metadata counters but keep display_name.
        if let Some(conv) = self.conv_meta.write().get_mut(&key) {
            conv.last_message_id = None;
            conv.unread_count = 0;
            conv.last_activity_ms = 0;
        }
        // Remove messages from SQLite; keep conv_meta row.
        if let Some(db) = &self.db {
            let key2 = key.clone();
            blocking_op(|| -> rusqlite::Result<()> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute("DELETE FROM messages WHERE conv_key = ?1", [&key2])?;
                conn.execute(
                    "UPDATE conv_meta SET last_activity_ms = 0, unread_count = 0 WHERE conv_key = ?1",
                    [&key2],
                )?;
                Ok(())
            }).map_err(|e| ADAError::Storage(e.to_string()))?;
        }

        self.delete_fts_conversation(&key)?;

        Ok(())
    }

    fn generated_direct_display_name(conv_id: &ConversationId) -> Option<String> {
        match conv_id {
            ConversationId::Direct(peer) => {
                let peer_b64 = peer.to_base64();
                Some(format!("{}…", &peer_b64[..8.min(peer_b64.len())]))
            }
            ConversationId::Group(_) => None,
        }
    }

    /// Create or update a conversation entry without requiring a message.
    /// Updates `display_name` when it was previously empty or when the current
    /// value is the autogenerated truncated peer-id placeholder used for
    /// unknown direct-message senders.
    pub fn upsert_conversation(&self, conv_id: &ConversationId, display_name: &str) {
        let key = Self::conv_key(conv_id);
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let generated_display_name = Self::generated_direct_display_name(conv_id);

        {
            let mut meta = self.conv_meta.write();
            let conv = meta.entry(key.clone()).or_insert_with(|| Conversation {
                id: conv_id.clone(),
                display_name: display_name.to_string(),
                last_message_id: None,
                last_activity_ms: now_ms,
                unread_count: 0,
                is_muted: false,
                is_archived: false,
            });
            let can_replace_generated = generated_display_name
                .as_deref()
                .map(|generated| conv.display_name == generated)
                .unwrap_or(false);
            if !display_name.is_empty() && (conv.display_name.is_empty() || can_replace_generated) {
                conv.display_name = display_name.to_string();
            }
        }

        if let Some(db) = &self.db {
            let dn = display_name.to_string();
            let now = now_ms as i64;
            let generated = generated_display_name.unwrap_or_default();
            blocking_op(|| -> rusqlite::Result<()> {
                let conn = db.lock().unwrap_or_else(|e| e.into_inner());
                conn.execute(
                    "INSERT INTO conv_meta (conv_key, display_name, last_activity_ms, unread_count) \
                     VALUES (?1, ?2, ?3, 0) \
                     ON CONFLICT(conv_key) DO UPDATE SET \
                         display_name = CASE WHEN excluded.display_name != '' AND (display_name = '' OR display_name = ?4) \
                                             THEN excluded.display_name ELSE display_name END",
                    params![key, dn, now, generated],
                )?;
                Ok(())
            }).ok();
        }
    }

    /// Deletes ephemeral messages whose expiration time has passed.
    /// Returns the list of deleted message IDs.
    pub fn sweep_ephemeral(&self) -> Vec<MessageId> {
        let mut expired = Vec::new();
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        {
            let convs = self.conversations.read();
            for msgs in convs.values() {
                for msg in msgs {
                    if let Some(exp_at) = msg.expires_at {
                        if exp_at <= now_ms {
                            expired.push(msg.id);
                        }
                    }
                }
            }
        }
        for id in &expired {
            let _ = self.delete_message(id);
        }
        expired
    }
}

#[cfg(test)]
mod tests {
    use super::{ConversationId, MessageStore};
    use crate::identity::PeerId;
    use crate::messaging::types::{Message, MessageKind};

    #[test]
    fn hidden_messages_do_not_replace_last_visible_conversation_message() {
        let store = MessageStore::in_memory();
        let peer = PeerId([7u8; 32]);
        let conv = ConversationId::Direct(peer.clone());

        let original = Message::new(
            PeerId([1u8; 32]),
            Some(peer.clone()),
            MessageKind::Text("before edit".into()),
        );
        let original_id = original.id;
        store
            .save_message_with_unread(&conv, original, false)
            .unwrap();

        let hidden_protocol = Message::new(
            PeerId([1u8; 32]),
            Some(peer.clone()),
            MessageKind::Reaction {
                target_msg_id: original_id,
                emoji: "👍".into(),
            },
        );
        store.save_hidden_message(&conv, hidden_protocol).unwrap();

        let conversations = store.list_conversations();
        assert_eq!(conversations.len(), 1);
        assert_eq!(conversations[0].last_message_id, Some(original_id));
        assert_eq!(store.get_messages(&conv, None, usize::MAX).len(), 2);
    }
}
