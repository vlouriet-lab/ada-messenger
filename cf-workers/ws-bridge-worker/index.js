// Cloudflare Worker front door for an existing ADA bridge origin.
// This variant hides the origin behind Cloudflare and gives the client a
// censorship-resistant WebSocket endpoint, but it does not eliminate the
// backend server. A true serverless bridge would need Durable Objects plus a
// JavaScript/Wasm implementation of the bridge bincode/auth protocol.
//
// Required Cloudflare Worker environment variables (set in wrangler.toml secrets):
//   BRIDGE_ORIGIN — absolute HTTPS URL of the backend bridge (e.g. https://bridge.example.com)
//   BRIDGE_TOKEN  — pre-shared secret that clients must provide in the
//                   Authorization header as "Bearer <BRIDGE_TOKEN>".
//                   Set to "" to disable token auth (NOT recommended for production).
//   BRIDGE_PATH   — (optional) path to proxy; defaults to /ada

const DEFAULT_BRIDGE_PATH = "/ada";

function websocketUpgradeRequested(request) {
  const upgrade = request.headers.get("Upgrade");
  return typeof upgrade === "string" && upgrade.toLowerCase() === "websocket";
}

function buildProxyRequest(request, targetUrl) {
  const headers = new Headers(request.headers);
  headers.set("Host", targetUrl.host);
  // Strip the client-facing Authorization header so the bridge token is not
  // forwarded to the origin — the origin uses its own auth scheme.
  headers.delete("Authorization");

  return new Request(targetUrl.toString(), {
    method: request.method,
    headers,
    body: request.body,
    redirect: "manual",
  });
}

export default {
  async fetch(request, env) {
    if (!websocketUpgradeRequested(request)) {
      return new Response("Expected WebSocket upgrade", {
        status: 426,
        headers: { Upgrade: "websocket" },
      });
    }

    // ── СРЕД-19: Pre-shared-secret authentication ─────────────────────────
    // BRIDGE_TOKEN must be configured in Cloudflare dashboard / wrangler secrets.
    // If set, the client must supply "Authorization: Bearer <token>" or the
    // request is rejected before any origin contact is made.  This prevents
    // the Worker from becoming an open proxy to the backend.
    const expectedToken = (env.BRIDGE_TOKEN || "").trim();
    if (expectedToken) {
      const authHeader = request.headers.get("Authorization") || "";
      const providedToken = authHeader.startsWith("Bearer ")
        ? authHeader.slice("Bearer ".length).trim()
        : "";
      if (providedToken !== expectedToken) {
        // Use 404 instead of 401 to avoid fingerprinting the Worker as an ADA bridge.
        return new Response("Not Found", { status: 404 });
      }
    }

    const bridgeOrigin = (env.BRIDGE_ORIGIN || "").trim();
    if (!bridgeOrigin) {
      return new Response("BRIDGE_ORIGIN is not configured", { status: 500 });
    }

    let targetUrl;
    try {
      targetUrl = new URL(bridgeOrigin);
    } catch {
      return new Response("BRIDGE_ORIGIN must be an absolute https URL", { status: 500 });
    }

    // МАЛ-20: enforce HTTPS scheme so a misconfigured BRIDGE_ORIGIN (e.g.
    // "ftp://..." or plain hostname) is rejected at the Worker, not silently
    // proxied over an unencrypted channel.
    if (targetUrl.protocol !== "https:") {
      return new Response("BRIDGE_ORIGIN must use the https: scheme", { status: 500 });
    }

    const incomingUrl = new URL(request.url);
    const bridgePath = (env.BRIDGE_PATH || DEFAULT_BRIDGE_PATH).trim() || DEFAULT_BRIDGE_PATH;
    if (incomingUrl.pathname !== bridgePath) {
      return new Response("Not Found", { status: 404 });
    }

    targetUrl.pathname = bridgePath;
    targetUrl.search = incomingUrl.search;

    return fetch(buildProxyRequest(request, targetUrl));
  },
};