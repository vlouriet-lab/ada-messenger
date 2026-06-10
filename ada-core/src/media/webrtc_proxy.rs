use crate::identity::PeerId;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

/// Local UDP Media Proxy for WebRTC
///
/// Binds to a local UDP port and proxies datagrams to/from the given peer
/// via the overarching ADA-core messaging network (Iroh/Bridge).
pub struct WebRtcProxy {
    _peer_id: PeerId,
    local_addr: SocketAddr,
    inbound_tx: mpsc::Sender<Vec<u8>>,
    task: Option<JoinHandle<()>>,
}

impl WebRtcProxy {
    /// Starts the local media proxy.
    /// outbound_tx is used to send local UDP traffic to the active peer via ada-core transport.
    pub async fn start(
        peer_id: PeerId,
        outbound_tx: mpsc::Sender<Vec<u8>>,
    ) -> crate::error::Result<Self> {
        let socket = UdpSocket::bind("127.0.0.1:0").await.map_err(|e| {
            crate::error::ADAError::Network(format!("Failed to bind media proxy socket: {}", e))
        })?;

        let local_addr = socket.local_addr().map_err(|e| {
            crate::error::ADAError::Network(format!(
                "Failed to get local addr for media proxy: {}",
                e
            ))
        })?;

        tracing::info!(
            "Started WebRTC proxy for peer {} on {}",
            hex::encode(&peer_id.0),
            local_addr
        );

        let socket = Arc::new(socket);
        let rx_socket = socket.clone();

        // Channel for ada-core to deliver remote packets to this local proxy
        let (inbound_tx, mut inbound_rx) = mpsc::channel::<Vec<u8>>(1024);

        let mut buf = vec![0u8; 65536];

        let task = tokio::spawn(async move {
            let mut client_addr: Option<SocketAddr> = None;
            loop {
                tokio::select! {
                    // 1) Read from Android's WebRTC stack via local UDP socket
                    recv_res = rx_socket.recv_from(&mut buf) => {
                        match recv_res {
                            Ok((size, addr)) => {
                                // Save the Android app's local ephemeral port so we can send back
                                client_addr = Some(addr);
                                let payload = buf[..size].to_vec();
                                // Send into ADA's encrypted transport layer
                                let _ = outbound_tx.try_send(payload);
                            }
                            Err(e) => {
                                tracing::debug!("Local WebRtc proxy read error: {}", e);
                                break;
                            }
                        }
                    }
                    // 2) Receive incoming remote payload from ADA transport and send to local WebRTC
                    inbound_msg = inbound_rx.recv() => {
                        match inbound_msg {
                            Some(payload) => {
                                if let Some(addr) = client_addr {
                                    let _ = rx_socket.send_to(&payload, addr).await;
                                }
                            }
                            None => break, // Channel closed
                        }
                    }
                }
            }
        });

        Ok(Self {
            _peer_id: peer_id,
            local_addr,
            inbound_tx,
            task: Some(task),
        })
    }

    pub fn local_port(&self) -> u16 {
        self.local_addr.port()
    }

    /// Push an incoming packet from the ADA network to the local WebRTC stack
    pub fn deliver_inbound(&self, payload: Vec<u8>) {
        let _ = self.inbound_tx.try_send(payload);
    }
}

impl Drop for WebRtcProxy {
    fn drop(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}
