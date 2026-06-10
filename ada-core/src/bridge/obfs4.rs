//! Obfs4-style stream obfuscation
//!
//! Provides a lightweight TCP stream obfuscator that:
//!   1. Performs an authenticated handshake that proves shared-secret knowledge
//!   2. Encrypts all traffic with ChaCha20 derived from the session secret
//!   3. Adds random-length headers to defeat length analysis
//!
//! This is still NOT a full Tor obfs4 implementation (no ntor/Elligator2),
//! but it now rejects unauthenticated probes instead of responding to any
//! random client bytes.

use crate::error::{ADAError, Result};
use chacha20::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
use chacha20::{ChaCha20, Key as ChaChaKey, Nonce as ChaChaNonce};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::{timeout, Duration};

/// Connection handshake timeout.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// Maximum allowed frame payload (1 MiB) to prevent OOM from malicious peers.
const MAX_FRAME_LEN: usize = 1_048_576;
const HANDSHAKE_NONCE_LEN: usize = 64;
const HANDSHAKE_TAG_LEN: usize = 16;
const HANDSHAKE_FRAME_LEN: usize = HANDSHAKE_NONCE_LEN + HANDSHAKE_TAG_LEN;

/// Stream cipher state for one direction of an obfs session (ChaCha20).
#[derive(Clone)]
pub struct ObfsSession {
    key: [u8; 32],
    /// Nonce: first 12 bytes of the session key (unique per-session).
    nonce: [u8; 12],
    /// Byte position within the ChaCha20 key stream (for seeking).
    position: u64,
}

impl ObfsSession {
    fn new(shared_secret: &[u8; 32]) -> Self {
        let mut nonce = [0u8; 12];
        nonce.copy_from_slice(&shared_secret[..12]);
        ObfsSession {
            key: *shared_secret,
            nonce,
            position: 0,
        }
    }

    /// XOR `buf` with the ChaCha20 key stream in place and advance position.
    pub fn apply(&mut self, buf: &mut [u8]) {
        let key = ChaChaKey::from_slice(&self.key);
        let nonce = ChaChaNonce::from_slice(&self.nonce);
        let mut cipher = ChaCha20::new(key, nonce);
        cipher.seek(self.position);
        cipher.apply_keystream(buf);
        self.position += buf.len() as u64;
    }
}

/// An obfuscated TCP stream wrapping `TcpStream`.
pub struct ObfsStream {
    inner: TcpStream,
    tx_session: ObfsSession,
    rx_session: ObfsSession,
}

impl ObfsStream {
    /// Connect to a server and perform the obfs handshake as client.
    pub async fn connect_client(addr: &str, port: u16, shared_secret: &[u8; 32]) -> Result<Self> {
        let mut stream = timeout(
            CONNECT_TIMEOUT,
            TcpStream::connect(format!("{}:{}", addr, port)),
        )
        .await
        .map_err(|_| ADAError::Network("Obfs4 connect timeout".into()))?
        .map_err(|e| ADAError::Network(format!("Obfs4 connect: {}", e)))?;

        // Client proves shared-secret knowledge before the server sends anything.
        // This avoids the previous behavior where the server replied to any 64 random bytes.
        let mut client_nonce = [0u8; HANDSHAKE_NONCE_LEN];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut client_nonce);

        let mut obscured_nonce = client_nonce;
        let client_mask =
            derive_ks_from_parts(&[shared_secret, b"client-hello-mask"], HANDSHAKE_NONCE_LEN);
        xor_in_place(&mut obscured_nonce, &client_mask);
        let client_tag = handshake_tag(shared_secret, b"client-auth", &[&client_nonce]);

        let mut hello = [0u8; HANDSHAKE_FRAME_LEN];
        hello[..HANDSHAKE_NONCE_LEN].copy_from_slice(&obscured_nonce);
        hello[HANDSHAKE_NONCE_LEN..].copy_from_slice(&client_tag);
        stream
            .write_all(&hello)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;

        // Receive authenticated server response.
        let mut server_hello = [0u8; HANDSHAKE_FRAME_LEN];
        stream
            .read_exact(&mut server_hello)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;

        let mut server_nonce = [0u8; HANDSHAKE_NONCE_LEN];
        server_nonce.copy_from_slice(&server_hello[..HANDSHAKE_NONCE_LEN]);
        let server_mask = derive_ks_from_parts(
            &[shared_secret, &client_nonce, b"server-hello-mask"],
            HANDSHAKE_NONCE_LEN,
        );
        xor_in_place(&mut server_nonce, &server_mask);

        let expected_server_tag = handshake_tag(
            shared_secret,
            b"server-auth",
            &[&client_nonce, &server_nonce],
        );
        if server_hello[HANDSHAKE_NONCE_LEN..] != expected_server_tag {
            return Err(ADAError::Network(
                "Obfs4 server authentication failed".into(),
            ));
        }

        let tx_seed = derive_session_key(shared_secret, b"client-tx", &client_nonce, &server_nonce);
        let rx_seed = derive_session_key(shared_secret, b"client-rx", &client_nonce, &server_nonce);

        Ok(ObfsStream {
            inner: stream,
            tx_session: ObfsSession::new(&tx_seed),
            rx_session: ObfsSession::new(&rx_seed),
        })
    }

    /// Accept an inbound obfuscated stream (server side).
    pub async fn accept_server(mut stream: TcpStream, shared_secret: &[u8; 32]) -> Result<Self> {
        // Read client hello and verify the shared-secret proof before replying.
        let mut client_hello = [0u8; HANDSHAKE_FRAME_LEN];
        stream
            .read_exact(&mut client_hello)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;

        let mut client_nonce = [0u8; HANDSHAKE_NONCE_LEN];
        client_nonce.copy_from_slice(&client_hello[..HANDSHAKE_NONCE_LEN]);
        let hello_ks =
            derive_ks_from_parts(&[shared_secret, b"client-hello-mask"], HANDSHAKE_NONCE_LEN);
        xor_in_place(&mut client_nonce, &hello_ks);

        let expected_client_tag = handshake_tag(shared_secret, b"client-auth", &[&client_nonce]);
        if client_hello[HANDSHAKE_NONCE_LEN..] != expected_client_tag {
            return Err(ADAError::Network(
                "Obfs4 client authentication failed".into(),
            ));
        }

        // Send authenticated server hello.
        let mut server_nonce = [0u8; HANDSHAKE_NONCE_LEN];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut server_nonce);

        let mut obscured_server_nonce = server_nonce;
        let server_mask = derive_ks_from_parts(
            &[shared_secret, &client_nonce, b"server-hello-mask"],
            HANDSHAKE_NONCE_LEN,
        );
        xor_in_place(&mut obscured_server_nonce, &server_mask);
        let server_tag = handshake_tag(
            shared_secret,
            b"server-auth",
            &[&client_nonce, &server_nonce],
        );

        let mut server_hello = [0u8; HANDSHAKE_FRAME_LEN];
        server_hello[..HANDSHAKE_NONCE_LEN].copy_from_slice(&obscured_server_nonce);
        server_hello[HANDSHAKE_NONCE_LEN..].copy_from_slice(&server_tag);

        stream
            .write_all(&server_hello)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;

        let rx_seed = derive_session_key(shared_secret, b"client-tx", &client_nonce, &server_nonce);
        let tx_seed = derive_session_key(shared_secret, b"client-rx", &client_nonce, &server_nonce);

        Ok(ObfsStream {
            inner: stream,
            tx_session: ObfsSession::new(&tx_seed),
            rx_session: ObfsSession::new(&rx_seed),
        })
    }

    /// Send obfuscated data with a random-length padding to defeat length analysis.
    ///
    /// Wire format: `[2B data_len] [1B pad_len] [pad_len B padding] [data_len B data]`
    /// All bytes are encrypted with the tx ChaCha20 keystream.
    pub async fn send(&mut self, plaintext: &[u8]) -> Result<()> {
        let pad_len = (rand::random::<u8>() % 15) as u8;
        let total_len = 3 + pad_len as usize + plaintext.len();
        let mut buf = Vec::with_capacity(total_len);
        buf.push((plaintext.len() >> 8) as u8);
        buf.push((plaintext.len() & 0xFF) as u8);
        buf.push(pad_len);
        let pad: Vec<u8> = (0..pad_len as usize).map(|_| rand::random()).collect();
        buf.extend_from_slice(&pad);
        buf.extend_from_slice(plaintext);

        // Encrypt entire buffer in-place
        self.tx_session.apply(&mut buf);
        self.inner
            .write_all(&buf)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))
    }

    /// Receive and decrypt a frame.
    ///
    /// Wire format: `[2B data_len] [1B pad_len] [pad_len B padding] [data_len B data]`
    pub async fn recv(&mut self) -> Result<Vec<u8>> {
        // Read 3-byte header: data_len (2B) + pad_len (1B)
        let mut header = [0u8; 3];
        self.inner
            .read_exact(&mut header)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;
        self.rx_session.apply(&mut header);
        let data_len = ((header[0] as usize) << 8) | (header[1] as usize);
        let pad_len = header[2] as usize;

        if data_len > MAX_FRAME_LEN {
            return Err(ADAError::Network(format!(
                "obfs4 frame too large: {} bytes",
                data_len
            )));
        }

        // Read and decrypt padding to keep the keystream position in sync
        if pad_len > 0 {
            let mut pad = vec![0u8; pad_len];
            self.inner
                .read_exact(&mut pad)
                .await
                .map_err(|e| ADAError::Network(e.to_string()))?;
            self.rx_session.apply(&mut pad);
        }

        let mut data = vec![0u8; data_len];
        self.inner
            .read_exact(&mut data)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;
        self.rx_session.apply(&mut data);
        Ok(data)
    }
}

/// Probe whether an obfs4 handshake to `addr:port` can be completed.
pub async fn probe(addr: &str, port: u16, shared_secret: &[u8; 32]) -> bool {
    ObfsStream::connect_client(addr, port, shared_secret)
        .await
        .is_ok()
}

fn xor_in_place(buf: &mut [u8], mask: &[u8]) {
    for (b, m) in buf.iter_mut().zip(mask.iter()) {
        *b ^= m;
    }
}

fn handshake_tag(
    shared_secret: &[u8; 32],
    label: &[u8],
    parts: &[&[u8]],
) -> [u8; HANDSHAKE_TAG_LEN] {
    let mut hasher = blake3::Hasher::new_keyed(shared_secret);
    hasher.update(label);
    for part in parts {
        hasher.update(part);
    }
    let digest = hasher.finalize();
    let mut out = [0u8; HANDSHAKE_TAG_LEN];
    out.copy_from_slice(&digest.as_bytes()[..HANDSHAKE_TAG_LEN]);
    out
}

fn derive_session_key(
    shared_secret: &[u8; 32],
    label: &[u8],
    client_nonce: &[u8; HANDSHAKE_NONCE_LEN],
    server_nonce: &[u8; HANDSHAKE_NONCE_LEN],
) -> [u8; 32] {
    use hkdf::Hkdf;
    use sha2::Sha256;

    let mut ikm = Vec::with_capacity(client_nonce.len() + server_nonce.len());
    ikm.extend_from_slice(client_nonce);
    ikm.extend_from_slice(server_nonce);

    let hk = Hkdf::<Sha256>::new(Some(shared_secret), &ikm);
    let mut out = [0u8; 32];
    hk.expand(label, &mut out)
        .expect("HKDF expand for obfs session key");
    out
}

fn derive_ks_from_parts(parts: &[&[u8]], n: usize) -> Vec<u8> {
    let total_len = parts.iter().map(|p| p.len()).sum();
    let mut seed = Vec::with_capacity(total_len);
    for part in parts {
        seed.extend_from_slice(part);
    }
    derive_ks(&seed, n)
}

fn derive_ks(seed: &[u8], n: usize) -> Vec<u8> {
    // HKDF-SHA256 to derive a 32-byte ChaCha20 key from the (variable-length) seed,
    // then use ChaCha20 keystream to produce `n` bytes.
    // Replaces the previous SHA-256 counter-mode construction.
    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(None, seed);
    let mut key = [0u8; 32];
    // 32 bytes is always within HKDF-SHA256's output limit (8160 bytes), so expand() is infallible.
    let _ = hk.expand(b"ada/obfs/ks-v2", &mut key);
    let cipher_key = ChaChaKey::from_slice(&key);
    let nonce = ChaChaNonce::default(); // 12 zero bytes — key is session-unique
    let mut cipher = ChaCha20::new(cipher_key, &nonce);
    let mut out = vec![0u8; n];
    cipher.apply_keystream(&mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[test]
    fn handshake_tags_depend_on_secret_and_nonce() {
        let secret = [7u8; 32];
        let nonce_a = [1u8; HANDSHAKE_NONCE_LEN];
        let nonce_b = [2u8; HANDSHAKE_NONCE_LEN];

        let tag_a = handshake_tag(&secret, b"client-auth", &[&nonce_a]);
        let tag_b = handshake_tag(&secret, b"client-auth", &[&nonce_b]);

        assert_ne!(tag_a, tag_b);
    }

    #[test]
    fn session_keys_are_directional() {
        let secret = [9u8; 32];
        let client_nonce = [3u8; HANDSHAKE_NONCE_LEN];
        let server_nonce = [4u8; HANDSHAKE_NONCE_LEN];

        let client_tx = derive_session_key(&secret, b"client-tx", &client_nonce, &server_nonce);
        let client_rx = derive_session_key(&secret, b"client-rx", &client_nonce, &server_nonce);

        assert_ne!(client_tx, client_rx);
    }

    #[tokio::test]
    async fn client_and_server_roundtrip_payload_after_authenticated_handshake() {
        let secret = [0x42u8; 32];
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind obfs listener");
        let port = listener.local_addr().expect("obfs listener addr").port();

        let server_secret = secret;
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("accept obfs client");
            let mut server_stream = ObfsStream::accept_server(stream, &server_secret)
                .await
                .expect("accept obfs handshake");

            let payload = server_stream.recv().await.expect("recv obfs payload");
            assert_eq!(payload, b"hello obfs4");

            server_stream.send(b"ack").await.expect("send obfs ack");
        });

        let mut client = ObfsStream::connect_client("127.0.0.1", port, &secret)
            .await
            .expect("connect obfs client");
        client
            .send(b"hello obfs4")
            .await
            .expect("send obfs payload");

        let ack = client.recv().await.expect("recv obfs ack");
        assert_eq!(ack, b"ack");

        server.await.expect("obfs server task");
    }
}
