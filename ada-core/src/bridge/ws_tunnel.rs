//! WebSocket TLS tunnel transport
//!
//! Wraps all traffic in WebSocket frames over TLS so it looks like
//! harmless HTTPS WebSocket traffic to DPI systems.
//!
//! The tunnel is a bidirectional byte-stream:
//!   connect() → tokio::io::{AsyncRead, AsyncWrite} handle
//!
//! Uses `tokio-tungstenite` (already in Cargo.toml).

use crate::error::{ADAError, Result};
use futures::{SinkExt, StreamExt};
use tokio_tungstenite::{
    connect_async_tls_with_config,
    tungstenite::{
        client::IntoClientRequest,
        http::{HeaderValue, Uri},
        Message as WsMessage,
    },
};

/// A bidirectional byte-channel multiplexed over a single WebSocket connection.
/// Incoming binary frames are delivered via an internal channel and properly
/// awaited — `recv()` blocks until data arrives or the tunnel is closed.
pub struct WsTunnel {
    /// Receiver for incoming binary messages from the background task
    read_rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    /// Leftover bytes from a partially consumed message
    partial: Vec<u8>,
    /// Outgoing frame sender half
    write_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    /// Background task handle (aborted on drop)
    _task: tokio::task::JoinHandle<()>,
}

impl WsTunnel {
    pub fn has_buffered_bytes(&self) -> bool {
        !self.partial.is_empty()
    }

    /// Connect to `wss://hostname:port/path` using the given SNI hostname.
    pub async fn connect(address: &str, port: u16, hostname: &str) -> Result<Self> {
        Self::connect_with_options(address, port, hostname, false).await
    }

    pub async fn connect_with_options(
        address: &str,
        port: u16,
        hostname: &str,
        insecure: bool,
    ) -> Result<Self> {
        let scheme = if insecure { "ws" } else { "wss" };
        let url = format!("{}://{}:{}/ada", scheme, address, port);
        let _uri = url
            .parse::<Uri>()
            .map_err(|e| ADAError::Network(format!("Invalid WS URL: {}", e)))?;

        let mut request = url
            .into_client_request()
            .map_err(|e| ADAError::Network(format!("WS request build: {}", e)))?;
        let host_header = if (insecure && port != 80) || (!insecure && port != 443) {
            format!("{}:{}", hostname, port)
        } else {
            hostname.to_string()
        };
        let host_header = HeaderValue::from_str(&host_header)
            .map_err(|e| ADAError::Network(format!("WS host header: {}", e)))?;
        request.headers_mut().insert("Host", host_header);
        request.headers_mut().insert(
            "User-Agent",
            HeaderValue::from_static("Mozilla/5.0 (compatible)"),
        );

        let (ws_stream, _response) = connect_async_tls_with_config(request, None, false, None)
            .await
            .map_err(|e| ADAError::Network(format!("WS connect failed: {}", e)))?;

        let (read_tx, read_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(64);
        let (write_tx, mut write_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(64);

        let task = tokio::spawn(async move {
            let (mut sink, mut stream) = ws_stream.split();

            loop {
                tokio::select! {
                    // Incoming WebSocket frame → forward to read channel
                    Some(msg) = stream.next() => {
                        match msg {
                            Ok(WsMessage::Binary(data)) => {
                                if read_tx.send(data).await.is_err() {
                                    break; // receiver dropped
                                }
                            }
                            Ok(WsMessage::Close(_)) | Err(_) => break,
                            _ => {} // Ping/Pong handled by tungstenite
                        }
                    }
                    // Outgoing bytes → wrap in binary frame and send
                    Some(data) = write_rx.recv() => {
                        if sink.send(WsMessage::Binary(data)).await.is_err() {
                            break;
                        }
                    }
                    else => break,
                }
            }
        });

        Ok(WsTunnel {
            read_rx,
            partial: Vec::new(),
            write_tx,
            _task: task,
        })
    }

    /// Send raw bytes through the tunnel.
    pub async fn send(&self, data: &[u8]) -> Result<()> {
        self.write_tx
            .send(data.to_vec())
            .await
            .map_err(|_| ADAError::Network("WS tunnel closed".into()))
    }

    /// Read up to `buf.len()` bytes. Awaits data if none is available.
    /// Returns 0 only when the tunnel is closed.
    pub async fn recv(&mut self, buf: &mut [u8]) -> usize {
        // Serve leftover partial data first
        if !self.partial.is_empty() {
            let n = buf.len().min(self.partial.len());
            buf[..n].copy_from_slice(&self.partial[..n]);
            self.partial.drain(..n);
            return n;
        }
        // Await next message from the background task
        match self.read_rx.recv().await {
            Some(data) => {
                let n = buf.len().min(data.len());
                buf[..n].copy_from_slice(&data[..n]);
                if data.len() > n {
                    self.partial.extend_from_slice(&data[n..]);
                }
                n
            }
            None => 0, // channel closed = tunnel closed
        }
    }
}

/// Probe whether a WebSocket TLS connection to the given endpoint is possible.
/// Returns Ok(true) if the connection handshake succeeded.
pub async fn probe(address: &str, port: u16, hostname: &str) -> bool {
    WsTunnel::connect(address, port, hostname).await.is_ok()
}

pub async fn probe_with_options(address: &str, port: u16, hostname: &str, insecure: bool) -> bool {
    WsTunnel::connect_with_options(address, port, hostname, insecure)
        .await
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::{SinkExt, StreamExt};
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_hdr_async;
    use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};

    #[tokio::test]
    async fn insecure_ws_tunnel_roundtrips_and_sets_expected_host_header() {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind ws listener");
        let port = listener.local_addr().expect("ws listener addr").port();
        let seen_host = Arc::new(Mutex::new(None::<String>));
        let seen_path = Arc::new(Mutex::new(None::<String>));
        let host_ref = Arc::clone(&seen_host);
        let path_ref = Arc::clone(&seen_path);

        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.expect("accept ws client");
            let ws_stream =
                accept_hdr_async(stream, move |request: &Request, response: Response| {
                    *host_ref.lock().expect("host lock") = request
                        .headers()
                        .get("Host")
                        .and_then(|value| value.to_str().ok())
                        .map(|value| value.to_string());
                    *path_ref.lock().expect("path lock") = Some(request.uri().path().to_string());
                    Ok(response)
                })
                .await
                .expect("ws handshake");

            let (mut sink, mut stream) = ws_stream.split();
            match stream.next().await {
                Some(Ok(WsMessage::Binary(payload))) => {
                    sink.send(WsMessage::Binary(payload))
                        .await
                        .expect("echo frame");
                }
                other => panic!("unexpected ws frame: {:?}", other),
            }
        });

        let mut tunnel = WsTunnel::connect_with_options("127.0.0.1", port, "ada.example", true)
            .await
            .expect("connect insecure ws tunnel");
        tunnel.send(b"abcdefgh").await.expect("send over ws tunnel");

        let mut chunk = [0u8; 3];
        let first = tunnel.recv(&mut chunk).await;
        assert_eq!(first, 3);
        assert_eq!(&chunk, b"abc");

        let second = tunnel.recv(&mut chunk).await;
        assert_eq!(second, 3);
        assert_eq!(&chunk, b"def");

        let mut tail = [0u8; 2];
        let third = tunnel.recv(&mut tail).await;
        assert_eq!(third, 2);
        assert_eq!(&tail, b"gh");

        server.await.expect("ws server task");
        assert_eq!(
            seen_host.lock().expect("read host").as_deref(),
            Some(format!("ada.example:{}", port).as_str())
        );
        assert_eq!(
            seen_path.lock().expect("read path").as_deref(),
            Some("/ada")
        );
    }
}
