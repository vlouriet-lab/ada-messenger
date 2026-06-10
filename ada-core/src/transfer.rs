use crate::crypto::symmetric::{decrypt, encrypt, generate_key, EncryptedData};
use crate::error::{ADAError, Result};
use crate::identity::PeerId;
use crate::CHUNK_SIZE;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use tokio::sync::{mpsc, RwLock};

/// Minimum chunk size for adaptive selection (16 KiB).
const MIN_CHUNK_SIZE: usize = 16 * 1024;
/// Maximum chunk size for adaptive selection (512 KiB).
const MAX_CHUNK_SIZE: usize = 512 * 1024;
/// Preferred number of chunks for a transfer (used by adaptive sizing).
const TARGET_CHUNKS: usize = 64;

pub type TransferId = [u8; 16];

fn new_transfer_id() -> TransferId {
    let mut id = [0u8; 16];
    rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
    id
}

/// State of a file transfer
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub enum TransferState {
    Pending,
    Negotiating,
    Transferring { chunks_done: u32, chunks_total: u32 },
    Paused { chunks_done: u32 },
    Verifying,
    Complete,
    Failed(String),
    Cancelled,
}

impl TransferState {
    pub fn progress(&self) -> f32 {
        match self {
            TransferState::Transferring {
                chunks_done,
                chunks_total,
            } => {
                if *chunks_total == 0 {
                    0.0
                } else {
                    *chunks_done as f32 / *chunks_total as f32
                }
            }
            TransferState::Complete | TransferState::Verifying => 1.0,
            _ => 0.0,
        }
    }
}

/// File transfer metadata
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TransferMeta {
    pub id: TransferId,
    pub peer: PeerId,
    pub file_name: String,
    pub file_size: u64,
    pub mime_type: String,
    /// Blake3 checksum of the full file
    pub checksum: [u8; 32],
    /// Symmetric key for chunk encryption
    pub encryption_key: [u8; 32],
    pub chunk_count: u32,
    pub chunk_size: u32,
    pub is_outbound: bool,
}

impl TransferMeta {
    pub fn from_file(peer: PeerId, path: &Path, data: &[u8], mime_type: String) -> Self {
        let checksum = *blake3::hash(data).as_bytes();
        let chunk_count = data.len().div_ceil(CHUNK_SIZE) as u32;
        let file_name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();

        TransferMeta {
            id: new_transfer_id(),
            peer,
            file_name,
            file_size: data.len() as u64,
            mime_type,
            checksum,
            encryption_key: generate_key(),
            chunk_count,
            chunk_size: CHUNK_SIZE as u32,
            is_outbound: true,
        }
    }
}

/// A single encrypted file chunk
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FileChunk {
    pub transfer_id: TransferId,
    pub index: u32,
    pub total: u32,
    pub data: EncryptedData,
    /// Blake3 checksum of the plaintext chunk
    pub chunk_checksum: [u8; 32],
}

impl FileChunk {
    pub fn encrypt(
        transfer_id: TransferId,
        index: u32,
        total: u32,
        key: &[u8; 32],
        plaintext: &[u8],
    ) -> Result<Self> {
        let chunk_checksum = *blake3::hash(plaintext).as_bytes();
        let aad = format!("{}-{}", hex::encode(transfer_id), index);
        let data = encrypt(key, plaintext, Some(aad.as_bytes()))?;
        Ok(FileChunk {
            transfer_id,
            index,
            total,
            data,
            chunk_checksum,
        })
    }

    pub fn decrypt(&self, key: &[u8; 32]) -> Result<Vec<u8>> {
        let aad = format!("{}-{}", hex::encode(self.transfer_id), self.index);
        let plaintext = decrypt(key, &self.data, Some(aad.as_bytes()))?;
        // Verify chunk integrity
        let got = *blake3::hash(&plaintext).as_bytes();
        if got != self.chunk_checksum {
            return Err(ADAError::Transfer("Chunk checksum mismatch".into()));
        }
        Ok(plaintext)
    }
}

/// Active outbound transfer
pub struct OutboundTransfer {
    pub meta: TransferMeta,
    pub data: Vec<u8>,
    pub state: TransferState,
    pub acked_chunks: HashSet<u32>,
}

impl OutboundTransfer {
    pub fn new(meta: TransferMeta, data: Vec<u8>) -> Self {
        OutboundTransfer {
            meta,
            data,
            state: TransferState::Pending,
            acked_chunks: HashSet::new(),
        }
    }

    /// Get the next chunk to send
    pub fn next_chunk(&self) -> Option<u32> {
        let total = self.meta.chunk_count;
        (0..total).find(|i| !self.acked_chunks.contains(i))
    }

    pub fn get_chunk(&self, index: u32) -> Result<FileChunk> {
        let start = (index as usize) * CHUNK_SIZE;
        let end = ((index + 1) as usize * CHUNK_SIZE).min(self.data.len());
        let plaintext = &self.data[start..end];
        FileChunk::encrypt(
            self.meta.id,
            index,
            self.meta.chunk_count,
            &self.meta.encryption_key,
            plaintext,
        )
    }

    pub fn ack_chunk(&mut self, index: u32) {
        self.acked_chunks.insert(index);
        let done = self.acked_chunks.len() as u32;
        let total = self.meta.chunk_count;
        if done >= total {
            self.state = TransferState::Complete;
        } else {
            self.state = TransferState::Transferring {
                chunks_done: done,
                chunks_total: total,
            };
        }
    }
}

/// Active inbound transfer
pub struct InboundTransfer {
    pub meta: TransferMeta,
    pub received_chunks: HashMap<u32, Vec<u8>>,
    pub state: TransferState,
}

impl InboundTransfer {
    pub fn new(meta: TransferMeta) -> Self {
        let total = meta.chunk_count;
        InboundTransfer {
            meta,
            received_chunks: HashMap::new(),
            state: TransferState::Transferring {
                chunks_done: 0,
                chunks_total: total,
            },
        }
    }

    pub fn receive_chunk(&mut self, chunk: &FileChunk) -> Result<bool> {
        let plaintext = chunk.decrypt(&self.meta.encryption_key)?;
        self.received_chunks.insert(chunk.index, plaintext);

        let done = self.received_chunks.len() as u32;
        let total = self.meta.chunk_count;

        if done >= total {
            // All chunks received, verify complete file
            self.state = TransferState::Verifying;
            return Ok(true); // Signal completion
        }

        self.state = TransferState::Transferring {
            chunks_done: done,
            chunks_total: total,
        };
        Ok(false)
    }

    pub fn missing_chunks(&self) -> Vec<u32> {
        (0..self.meta.chunk_count)
            .filter(|i| !self.received_chunks.contains_key(i))
            .collect()
    }

    /// Assemble all chunks into final file bytes, verify checksum
    pub fn assemble(&self) -> Result<Vec<u8>> {
        let mut result = Vec::with_capacity(self.meta.file_size as usize);
        for i in 0..self.meta.chunk_count {
            let chunk = self
                .received_chunks
                .get(&i)
                .ok_or(ADAError::Transfer(format!("Missing chunk {}", i)))?;
            result.extend_from_slice(chunk);
        }

        // Verify file integrity
        let checksum = *blake3::hash(&result).as_bytes();
        if checksum != self.meta.checksum {
            return Err(ADAError::Transfer("File checksum mismatch".into()));
        }

        Ok(result)
    }
}

/// Manages all file transfers
pub struct TransferManager {
    outbound: RwLock<HashMap<TransferId, OutboundTransfer>>,
    completed_outbound: RwLock<HashMap<TransferId, OutboundTransfer>>,
    inbound: RwLock<HashMap<TransferId, InboundTransfer>>,
    event_tx: mpsc::Sender<TransferEvent>,
}

#[derive(Debug, Clone)]
pub enum TransferEvent {
    Started {
        id: TransferId,
        meta: TransferMeta,
    },
    Progress {
        id: TransferId,
        progress: f32,
    },
    Completed {
        id: TransferId,
        data: Vec<u8>,
        meta: TransferMeta,
    },
    Failed {
        id: TransferId,
        reason: String,
    },
    Cancelled {
        id: TransferId,
    },
}

impl TransferManager {
    pub fn new() -> (Self, mpsc::Receiver<TransferEvent>) {
        let (tx, rx) = mpsc::channel(64);
        (
            TransferManager {
                outbound: RwLock::new(HashMap::new()),
                completed_outbound: RwLock::new(HashMap::new()),
                inbound: RwLock::new(HashMap::new()),
                event_tx: tx,
            },
            rx,
        )
    }

    /// Queue a file for sending
    pub async fn send_file(
        &self,
        peer: PeerId,
        path: &Path,
        mime_type: String,
        data: Vec<u8>,
    ) -> Result<TransferMeta> {
        let meta = TransferMeta::from_file(peer, path, &data, mime_type);
        let transfer = OutboundTransfer::new(meta.clone(), data);
        self.outbound.write().await.insert(meta.id, transfer);
        let _ = self
            .event_tx
            .send(TransferEvent::Started {
                id: meta.id,
                meta: meta.clone(),
            })
            .await;
        Ok(meta)
    }

    /// Queue a file for sending from an async reader (disk streaming — avoids
    /// loading the entire file into RAM at once).
    pub async fn send_file_streaming<R: tokio::io::AsyncRead + Unpin>(
        &self,
        peer: PeerId,
        file_name: &str,
        file_size: u64,
        mut reader: R,
    ) -> Result<TransferMeta> {
        use tokio::io::AsyncReadExt;

        let encryption_key = crate::crypto::symmetric::generate_key();
        let mut hasher = blake3::Hasher::new();
        let mut all_chunks: Vec<u8> = Vec::with_capacity(file_size as usize);

        let mut buf = vec![0u8; CHUNK_SIZE];
        loop {
            let n = reader
                .read(&mut buf)
                .await
                .map_err(|e| ADAError::Transfer(e.to_string()))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            all_chunks.extend_from_slice(&buf[..n]);
        }

        let checksum = *hasher.finalize().as_bytes();
        let chunk_count = all_chunks.len().div_ceil(CHUNK_SIZE) as u32;
        let mime_type = mime_guess::from_path(file_name)
            .first_or_octet_stream()
            .to_string();

        let meta = TransferMeta {
            id: new_transfer_id(),
            peer,
            file_name: file_name.to_string(),
            file_size,
            mime_type,
            checksum,
            encryption_key,
            chunk_count,
            chunk_size: CHUNK_SIZE as u32,
            is_outbound: true,
        };

        let transfer = OutboundTransfer::new(meta.clone(), all_chunks);
        self.outbound.write().await.insert(meta.id, transfer);
        let _ = self
            .event_tx
            .send(TransferEvent::Started {
                id: meta.id,
                meta: meta.clone(),
            })
            .await;
        Ok(meta)
    }

    /// Accept an incoming transfer
    pub async fn accept_transfer(&self, meta: TransferMeta) {
        let id = meta.id;
        let transfer = InboundTransfer::new(meta.clone());
        self.inbound.write().await.insert(id, transfer);
        let _ = self
            .event_tx
            .send(TransferEvent::Started { id, meta })
            .await;
    }

    /// Get the next chunk to send for a transfer
    pub async fn next_outbound_chunk(&self, id: &TransferId) -> Result<Option<FileChunk>> {
        let outbound = self.outbound.read().await;
        let transfer = outbound
            .get(id)
            .ok_or(ADAError::Transfer("Transfer not found".into()))?;
        match transfer.next_chunk() {
            Some(idx) => Ok(Some(transfer.get_chunk(idx)?)),
            None => Ok(None),
        }
    }

    /// Get a specific outbound chunk by index without mutating transfer state.
    pub async fn outbound_chunk_by_index(
        &self,
        id: &TransferId,
        index: u32,
    ) -> Result<Option<FileChunk>> {
        {
            let outbound = self.outbound.read().await;
            if let Some(transfer) = outbound.get(id) {
                if index >= transfer.meta.chunk_count {
                    return Ok(None);
                }
                return Ok(Some(transfer.get_chunk(index)?));
            }
        }

        let completed_outbound = self.completed_outbound.read().await;
        let Some(transfer) = completed_outbound.get(id) else {
            return Ok(None);
        };
        if index >= transfer.meta.chunk_count {
            return Ok(None);
        }
        Ok(Some(transfer.get_chunk(index)?))
    }

    /// Acknowledge a sent chunk
    pub async fn ack_chunk(&self, id: &TransferId, index: u32) -> Result<()> {
        let (progress, completed_transfer) = {
            let mut outbound = self.outbound.write().await;
            let transfer = outbound
                .get_mut(id)
                .ok_or(ADAError::Transfer("Transfer not found".into()))?;
            transfer.ack_chunk(index);
            let progress = transfer.state.progress();
            let completed_transfer = if matches!(transfer.state, TransferState::Complete) {
                outbound.remove(id)
            } else {
                None
            };
            (progress, completed_transfer)
        };

        let _ = self
            .event_tx
            .send(TransferEvent::Progress { id: *id, progress })
            .await;
        if let Some(transfer) = completed_transfer {
            // Retain the sent payload so a receiver can request missing chunks
            // after a live-bridge drop without keeping the transfer active in UI.
            self.completed_outbound.write().await.insert(*id, transfer);
        }
        Ok(())
    }

    /// Process an incoming chunk
    pub async fn receive_chunk(&self, chunk: FileChunk) -> Result<()> {
        let id = chunk.transfer_id;
        let complete = {
            let mut inbound = self.inbound.write().await;
            let transfer = inbound
                .get_mut(&id)
                .ok_or(ADAError::Transfer("Unknown transfer".into()))?;
            let done = transfer.receive_chunk(&chunk)?;
            let progress = transfer.state.progress();
            let _ = self
                .event_tx
                .send(TransferEvent::Progress { id, progress })
                .await;
            done
        };

        if complete {
            let mut inbound = self.inbound.write().await;
            if let Some(transfer) = inbound.remove(&id) {
                match transfer.assemble() {
                    Ok(data) => {
                        let _ = self
                            .event_tx
                            .send(TransferEvent::Completed {
                                id,
                                data,
                                meta: transfer.meta,
                            })
                            .await;
                    }
                    Err(e) => {
                        let _ = self
                            .event_tx
                            .send(TransferEvent::Failed {
                                id,
                                reason: e.to_string(),
                            })
                            .await;
                    }
                }
            }
        }
        Ok(())
    }

    /// Cancel a transfer
    pub async fn cancel(&self, id: &TransferId) {
        self.outbound.write().await.remove(id);
        self.completed_outbound.write().await.remove(id);
        self.inbound.write().await.remove(id);
        let _ = self
            .event_tx
            .send(TransferEvent::Cancelled { id: *id })
            .await;
    }

    /// Request missing chunks (for recovery)
    pub async fn missing_chunks(&self, id: &TransferId) -> Vec<u32> {
        let inbound = self.inbound.read().await;
        inbound
            .get(id)
            .map(|t| t.missing_chunks())
            .unwrap_or_default()
    }

    /// Return info about all active transfers: (meta, progress_0_to_1, is_outbound).
    pub async fn active_transfers_info(&self) -> Vec<(TransferMeta, f32, bool)> {
        let mut result = Vec::new();
        {
            let outbound = self.outbound.read().await;
            for t in outbound.values() {
                result.push((t.meta.clone(), t.state.progress(), true));
            }
        }
        {
            let inbound = self.inbound.read().await;
            for t in inbound.values() {
                result.push((t.meta.clone(), t.state.progress(), false));
            }
        }
        result
    }

    /// `true` when there is at least one active outbound transfer requiring chunk pumping.
    /// Cheaper than `active_transfers_info()` — avoids cloning metadata on every 50 ms tick.
    pub async fn has_active_outbound(&self) -> bool {
        !self.outbound.read().await.is_empty()
    }
}

// ── Adaptive chunk sizing ─────────────────────────────────────────────────────

/// Choose an appropriate chunk size for a given data length.
///
/// Tries to produce approximately `TARGET_CHUNKS` chunks, but clamps the
/// result between `MIN_CHUNK_SIZE` and `MAX_CHUNK_SIZE`.
/// Borrowed from Plex's `choose_chunk_size` in `mesh_handoff.rs`.
pub fn choose_chunk_size(data_len: usize) -> usize {
    if data_len == 0 {
        return CHUNK_SIZE;
    }
    let ideal = data_len / TARGET_CHUNKS;
    // Round up to the nearest 4 KiB alignment
    let aligned = ideal.div_ceil(4096) * 4096;
    aligned.clamp(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
}

// ── Handoff offer / retransmit structs ────────────────────────────────────────

/// Sent by the uploader at the start of a transfer to describe the payload.
/// The receiver uses this to pre-allocate buffers and verify integrity on
/// completion (SHA-256 of the raw plaintext bundle).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferOffer {
    /// Unique transfer session ID (hex-encoded [u8;16])
    pub session_id: String,
    /// Unix timestamp (secs) when the offer was generated
    pub generated_at: i64,
    /// Total plaintext bytes
    pub total_bytes: u64,
    /// Chunk size in bytes (may differ from the global CHUNK_SIZE constant)
    pub chunk_size: u64,
    /// Total number of chunks
    pub total_chunks: u64,
    /// SHA-256 hex digest of the full plaintext payload (for final integrity check)
    pub sha256: String,
}

impl TransferOffer {
    /// Build an offer for `data`, choosing chunk size adaptively.
    pub fn from_data(data: &[u8]) -> Self {
        use sha2::{Digest, Sha256};
        let chunk_size = choose_chunk_size(data.len());
        let total_chunks = data.len().div_ceil(chunk_size).max(1) as u64;
        let digest = hex::encode(Sha256::digest(data));
        let session_id = {
            let mut id = [0u8; 16];
            rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut id);
            hex::encode(id)
        };
        TransferOffer {
            session_id,
            generated_at: unix_offer_now(),
            total_bytes: data.len() as u64,
            chunk_size: chunk_size as u64,
            total_chunks,
            sha256: digest,
        }
    }

    /// Verify that `data` matches this offer's SHA-256 digest.
    pub fn verify(&self, data: &[u8]) -> bool {
        use sha2::{Digest, Sha256};
        hex::encode(Sha256::digest(data)) == self.sha256
    }
}

/// Sent by the receiver to request retransmission of specific missing chunks.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetransmitRequest {
    pub session_id: String,
    pub requested_at: i64,
    /// Indices of chunks the receiver is missing.
    pub missing_indices: Vec<u64>,
}

impl RetransmitRequest {
    pub fn new(session_id: String, missing_indices: Vec<u64>) -> Self {
        RetransmitRequest {
            session_id,
            requested_at: unix_offer_now(),
            missing_indices,
        }
    }
}

fn unix_offer_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

#[cfg(test)]
mod handoff_tests {
    use super::*;

    #[test]
    fn choose_chunk_size_clamps() {
        assert_eq!(choose_chunk_size(0), CHUNK_SIZE);
        // tiny data → minimum chunk size
        assert_eq!(choose_chunk_size(1024), MIN_CHUNK_SIZE);
        // huge data → capped at max
        let huge = MAX_CHUNK_SIZE * TARGET_CHUNKS * 100;
        assert_eq!(choose_chunk_size(huge), MAX_CHUNK_SIZE);
    }

    #[test]
    fn transfer_offer_verify() {
        let data = b"hello world";
        let offer = TransferOffer::from_data(data);
        assert!(offer.verify(data));
        assert!(!offer.verify(b"wrong"));
    }
}
