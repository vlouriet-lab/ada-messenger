//! Domain Fronting and Meek transports
//!
//! Domain Fronting: Connect via TLS to a CDN (e.g. Cloudflare), setting
//!   HTTP CONNECT `Host` to the real destination inside the TLS tunnel.
//!   DPI only sees a TLS handshake to the non-blocked CDN SNI.
//!
//! Meek: HTTPS POST camouflage — payload is the POST body over TLS to the
//!   front domain.  Looks like normal browser HTTPS traffic to DPI.
//!
//! Both transports use `tokio-rustls` (rustls 0.22) so all bytes are
//! TLS-encrypted before any ADA protocol data is transmitted.
//! Meek itself uses `reqwest` with rustls, which negotiates HTTP/2 via ALPN
//! when the front CDN supports it.

use crate::error::{ADAError, Result};
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_rustls::{client::TlsStream, TlsConnector};

// ── TLS setup ─────────────────────────────────────────────────────────────────

/// Build a TLS connector backed by the webpki root store.
/// Uses `webpki-roots` already present transitively via `tokio-tungstenite`.
fn build_tls_connector() -> TlsConnector {
    let mut root_store = RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();
    TlsConnector::from(Arc::new(config))
}

// ── Domain Fronting via TLS + HTTP CONNECT ───────────────────────────────────

/// A TLS-encrypted byte stream opened through a CDN using domain fronting.
///
/// The outer TLS SNI is `front_host` (CDN hostname not blocked by DPI).
/// After the TLS handshake an HTTP CONNECT request is sent with
/// `Host: real_host:real_port`, routing traffic to the real ADA relay.
pub struct DomainFrontTunnel {
    inner: TlsStream<TcpStream>,
}

impl DomainFrontTunnel {
    /// Open the domain-fronted tunnel.
    ///
    /// 1. TCP connect to `front_host:443`
    /// 2. TLS handshake (SNI = `front_host`) — DPI sees only this
    /// 3. HTTP CONNECT with `Host: real_host:real_port`
    /// 4. Verify 200 response
    ///
    /// After this the TLS stream is a transparent tunnel to the real host.
    pub async fn connect(front_host: &str, real_host: &str, real_port: u16) -> Result<Self> {
        Self::connect_with_connector_and_port(
            front_host,
            443,
            real_host,
            real_port,
            build_tls_connector(),
        )
        .await
    }

    async fn connect_with_connector_and_port(
        front_host: &str,
        front_port: u16,
        real_host: &str,
        real_port: u16,
        connector: TlsConnector,
    ) -> Result<Self> {
        use tokio::time::{timeout, Duration};
        let connect_timeout = Duration::from_secs(15);

        // Step 1+2: TCP + TLS to CDN — DPI sees SNI = front_host
        let addr = format!("{}:{}", front_host, front_port);
        let tcp = timeout(connect_timeout, TcpStream::connect(&addr))
            .await
            .map_err(|_| ADAError::Network("DomainFront TCP connect timeout".into()))?
            .map_err(|e| ADAError::Network(format!("DomainFront TCP: {}", e)))?;

        let server_name = ServerName::try_from(front_host)
            .map_err(|_| ADAError::Network(format!("DomainFront: invalid SNI '{}'", front_host)))?
            .to_owned();
        let mut tls = timeout(connect_timeout, connector.connect(server_name, tcp))
            .await
            .map_err(|_| ADAError::Network("DomainFront TLS handshake timeout".into()))?
            .map_err(|e| ADAError::Network(format!("DomainFront TLS: {}", e)))?;

        // Step 3: HTTP CONNECT through TLS — CDN routes via Host to real_host
        let connect_req = format!(
            "CONNECT {}:{} HTTP/1.1\r\nHost: {}:{}\r\nProxy-Connection: keep-alive\r\n\r\n",
            real_host, real_port, real_host, real_port
        );
        tls.write_all(connect_req.as_bytes())
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;

        // Step 4: Read 200 response
        let mut resp = [0u8; 512];
        let n = tls
            .read(&mut resp)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))?;
        let resp_str = std::str::from_utf8(&resp[..n]).unwrap_or("");
        if !resp_str.contains("200") {
            return Err(ADAError::Network(format!(
                "DomainFront CONNECT rejected: {}",
                resp_str.lines().next().unwrap_or("(empty)")
            )));
        }

        Ok(DomainFrontTunnel { inner: tls })
    }

    pub async fn send(&mut self, data: &[u8]) -> Result<()> {
        self.inner
            .write_all(data)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))
    }

    pub async fn recv(&mut self, buf: &mut [u8]) -> Result<usize> {
        self.inner
            .read(buf)
            .await
            .map_err(|e| ADAError::Network(e.to_string()))
    }
}

/// Probe whether a domain-fronted CONNECT to `real_host:real_port` succeeds.
pub async fn probe_domain_front(front_host: &str, real_host: &str, real_port: u16) -> bool {
    DomainFrontTunnel::connect(front_host, real_host, real_port)
        .await
        .is_ok()
}

// ── Meek transport (HTTPS POST over TLS) ─────────────────────────────────────

/// Meek session: encapsulates data as HTTPS POST requests to a fronted URL.
/// Each `round_trip` POSTs the payload over TLS to the front domain.
/// DPI sees only TLS traffic to the front host — not the real backend.
#[derive(Debug)]
pub struct MeekSession {
    /// Frontend URL (e.g. https://cdn.cloudflare.com/meek)
    front_url: String,
    /// Real backend host for the `X-Real-Host` header
    real_host: String,
    /// Session token (cookie-like identifier)
    session_id: String,
    /// HTTP client using rustls + ALPN. When the front supports HTTP/2,
    /// reqwest will negotiate it automatically.
    client: reqwest::Client,
}

impl MeekSession {
    pub fn new(front_url: &str, real_host: &str) -> Result<Self> {
        let parsed = reqwest::Url::parse(front_url).map_err(|e| {
            ADAError::Network(format!("Meek: invalid front URL '{}': {}", front_url, e))
        })?;
        if parsed.scheme() != "https" {
            return Err(ADAError::Network("Meek front_url must use https".into()));
        }

        let mut sid = [0u8; 8];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut sid);
        let client = reqwest::Client::builder()
            .https_only(true)
            .connect_timeout(std::time::Duration::from_secs(15))
            .read_timeout(std::time::Duration::from_secs(15))
            .timeout(std::time::Duration::from_secs(30))
            .http2_adaptive_window(true)
            .pool_max_idle_per_host(1)
            .user_agent("Mozilla/5.0 (compatible)")
            .build()
            .map_err(|e| ADAError::Network(format!("Meek client build: {}", e)))?;

        Ok(MeekSession {
            front_url: front_url.to_string(),
            real_host: real_host.to_string(),
            session_id: hex::encode(sid),
            client,
        })
    }

    /// Send `payload` to the backend via a single HTTPS POST.
    /// Returns backend response body bytes.
    pub async fn round_trip(&self, payload: &[u8]) -> Result<Vec<u8>> {
        use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, CONTENT_TYPE};

        let mut headers = HeaderMap::new();
        headers.insert(
            CONTENT_TYPE,
            HeaderValue::from_static("application/octet-stream"),
        );
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/octet-stream, */*"),
        );
        headers.insert(
            "X-Real-Host",
            HeaderValue::from_str(&self.real_host)
                .map_err(|e| ADAError::Network(format!("Meek X-Real-Host header: {}", e)))?,
        );
        headers.insert(
            "X-Session-Id",
            HeaderValue::from_str(&self.session_id)
                .map_err(|e| ADAError::Network(format!("Meek X-Session-Id header: {}", e)))?,
        );

        let response = self
            .client
            .post(&self.front_url)
            .headers(headers)
            .body(payload.to_vec())
            .send()
            .await
            .map_err(|e| ADAError::Network(format!("Meek request: {}", e)))?;

        let status = response.status();
        if !status.is_success() {
            return Err(ADAError::Network(format!(
                "Meek HTTP {} from {}",
                status.as_u16(),
                self.front_url
            )));
        }

        response
            .bytes()
            .await
            .map(|b| b.to_vec())
            .map_err(|e| ADAError::Network(format!("Meek response body: {}", e)))
    }
}

/// Probe Meek connectivity.
pub async fn probe_meek(front_url: &str, real_host: &str) -> bool {
    match MeekSession::new(front_url, real_host) {
        Ok(session) => session.round_trip(b"PING").await.is_ok(),
        Err(_) => false,
    }
}

fn find_double_crlf(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
    use std::sync::Arc;
    use tokio::net::TcpListener;
    use tokio_rustls::TlsAcceptor;

    #[test]
    fn meek_rejects_non_https_front_url() {
        let err = MeekSession::new("http://front.example/meek", "real.example")
            .expect_err("non-https meek URL should be rejected");

        assert!(matches!(err, ADAError::Network(message) if message.contains("must use https")));
    }

    fn test_tls_pair(front_host: &str) -> (TlsConnector, TlsAcceptor) {
        let certified = rcgen::generate_simple_self_signed(vec![front_host.to_string()])
            .expect("generate self-signed cert");
        let cert_der = certified.cert.der().clone();
        let key_der =
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certified.key_pair.serialize_der()));

        let server_config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der.clone()], key_der)
            .expect("server config");

        let mut roots = RootCertStore::empty();
        roots.add(cert_der).expect("add test root");
        let client_config = ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();

        (
            TlsConnector::from(Arc::new(client_config)),
            TlsAcceptor::from(Arc::new(server_config)),
        )
    }

    #[tokio::test]
    async fn domain_front_tunnel_connects_over_local_tls_and_forwards_bytes() {
        let front_host = "localhost";
        let (connector, acceptor) = test_tls_pair(front_host);
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind domain-front listener");
        let port = listener.local_addr().expect("domain-front addr").port();

        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.expect("accept domain-front client");
            let mut tls = acceptor.accept(tcp).await.expect("accept tls");

            let mut request = Vec::new();
            let mut buf = [0u8; 512];
            loop {
                let n = tls.read(&mut buf).await.expect("read connect request");
                assert!(n > 0, "domain-front client closed before CONNECT");
                request.extend_from_slice(&buf[..n]);
                if find_double_crlf(&request).is_some() {
                    break;
                }
            }

            let request = String::from_utf8(request).expect("utf8 CONNECT request");
            assert!(request.starts_with("CONNECT relay.internal:8443 HTTP/1.1\r\n"));
            assert!(request.contains("\r\nHost: relay.internal:8443\r\n"));

            tls.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n")
                .await
                .expect("write connect response");

            let mut payload = [0u8; 4];
            tls.read_exact(&mut payload)
                .await
                .expect("read tunneled payload");
            assert_eq!(&payload, b"PING");

            tls.write_all(b"PONG").await.expect("write tunneled reply");
        });

        let mut tunnel = DomainFrontTunnel::connect_with_connector_and_port(
            front_host,
            port,
            "relay.internal",
            8443,
            connector,
        )
        .await
        .expect("connect domain-front tunnel");
        tunnel.send(b"PING").await.expect("send tunnel payload");

        let mut response = [0u8; 4];
        let n = tunnel
            .recv(&mut response)
            .await
            .expect("recv tunnel payload");
        assert_eq!(n, 4);
        assert_eq!(&response, b"PONG");

        server.await.expect("domain-front server task");
    }
}

// ── Domain-fronted HTTPS GET (for manifest bootstrap) ────────────────────────

/// CDN front hosts to try when fetching a manifest URL via domain fronting.
///
/// In whitelist-based censorship these CDN domains are almost always
/// reachable (millions of legitimate sites use them).  The manifest
/// server must be behind the same CDN so the proxied request succeeds.
pub const CDN_FRONT_HOSTS: &[&str] = &["cdn.cloudflare.com", "ajax.googleapis.com"];

/// Fetch `url` over HTTPS using domain fronting through `front_host`.
///
/// Connects TLS with SNI = `front_host` (CDN edge, not blocked by DPI/whitelist),
/// then sends a plain HTTP/1.1 GET with `Host: <url-hostname>` inside the TLS
/// tunnel.  The CDN proxies the request to the real origin.
///
/// **Requirement**: the server at `url` must be behind the same CDN as
/// `front_host` (e.g. manifest hosted on Cloudflare Workers/Pages → use
/// `cdn.cloudflare.com` as the front host).
pub async fn fetch_url_via_domain_front(url: &str, front_host: &str) -> Result<String> {
    use tokio::time::{timeout, Duration};
    let connect_timeout = Duration::from_secs(10);

    // Parse scheme-stripped URL into (host, path)
    let bare = url
        .trim_start_matches("https://")
        .trim_start_matches("http://");
    let (host_path, _fragment) = bare.split_once('#').unwrap_or((bare, ""));
    let (host_with_query, _) = host_path.split_once('?').unwrap_or((host_path, ""));
    let slash_pos = host_with_query.find('/');
    let (host_port, raw_path) = match slash_pos {
        Some(pos) => (&host_with_query[..pos], &host_with_query[pos..]),
        None => (host_with_query, "/"),
    };
    // Strip port if present; we always connect to front_host:443
    let host = host_port.split(':').next().unwrap_or(host_port);
    // Restore any query string in the request path
    let req_path = if let Some(q) = bare.split_once('?').and_then(|(_, q)| Some(q)) {
        format!("{}?{}", raw_path, q)
    } else {
        raw_path.to_string()
    };

    // TCP + TLS to front_host:443 — DPI sees SNI = front_host, not the blocked origin
    let addr = format!("{}:443", front_host);
    let tcp = timeout(connect_timeout, TcpStream::connect(&addr))
        .await
        .map_err(|_| {
            ADAError::Network(format!(
                "domain-front manifest: TCP timeout to {}",
                front_host
            ))
        })?
        .map_err(|e| {
            ADAError::Network(format!("domain-front manifest: TCP {}: {}", front_host, e))
        })?;

    let connector = build_tls_connector();
    let server_name = ServerName::try_from(front_host)
        .map_err(|_| ADAError::Network(format!("domain-front: invalid SNI '{}'", front_host)))?
        .to_owned();
    let mut tls = timeout(connect_timeout, connector.connect(server_name, tcp))
        .await
        .map_err(|_| {
            ADAError::Network(format!(
                "domain-front manifest: TLS timeout for {}",
                front_host
            ))
        })?
        .map_err(|e| {
            ADAError::Network(format!("domain-front manifest: TLS {}: {}", front_host, e))
        })?;

    // HTTP/1.1 GET with real origin in Host header — CDN routes based on this
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: Mozilla/5.0\r\nAccept: application/json, */*\r\nConnection: close\r\n\r\n",
        req_path, host
    );
    tls.write_all(request.as_bytes())
        .await
        .map_err(|e| ADAError::Network(e.to_string()))?;

    // Read full response (safety cap: 512 KiB)
    let mut response = Vec::with_capacity(4096);
    let mut buf = [0u8; 8192];
    let read_timeout = Duration::from_secs(10);
    loop {
        match timeout(read_timeout, tls.read(&mut buf)).await {
            Ok(Ok(0)) | Err(_) => break,
            Ok(Ok(n)) => response.extend_from_slice(&buf[..n]),
            Ok(Err(_)) => break,
        }
        if response.len() > 512 * 1024 {
            break;
        }
    }

    // Split headers / body and check HTTP 200
    let body_start = find_double_crlf(&response).ok_or_else(|| {
        ADAError::Network("domain-front manifest: incomplete HTTP response".into())
    })?;
    let headers_str = std::str::from_utf8(&response[..body_start]).unwrap_or("");
    let status_line = headers_str.lines().next().unwrap_or("");
    if !status_line.contains("200") {
        return Err(ADAError::Network(format!(
            "domain-front manifest: {} from {} (front={})",
            status_line.trim(),
            host,
            front_host
        )));
    }

    String::from_utf8(response[body_start + 4..].to_vec())
        .map_err(|e| ADAError::Network(format!("domain-front manifest: UTF-8 decode: {}", e)))
}
