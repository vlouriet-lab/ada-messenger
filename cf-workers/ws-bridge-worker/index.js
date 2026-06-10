// Cloudflare Worker front door for an existing ADA bridge origin.
// This variant hides the origin behind Cloudflare and gives the client a
// censorship-resistant WebSocket endpoint, but it does not eliminate the
// backend server. A true serverless bridge would need Durable Objects plus a
// JavaScript/Wasm implementation of the bridge bincode/auth protocol.
const DEFAULT_BRIDGE_PATH = "/ada";

function websocketUpgradeRequested(request) {
  const upgrade = request.headers.get("Upgrade");
  return typeof upgrade === "string" && upgrade.toLowerCase() === "websocket";
}

function buildProxyRequest(request, targetUrl) {
  const headers = new Headers(request.headers);
  headers.set("Host", targetUrl.host);

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