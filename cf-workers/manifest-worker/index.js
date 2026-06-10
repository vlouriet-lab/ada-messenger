const DEFAULT_MANIFEST_PATH = "/manifest.json";

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const manifestPath = (env.MANIFEST_PATH || DEFAULT_MANIFEST_PATH).trim() || DEFAULT_MANIFEST_PATH;

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method Not Allowed", {
        status: 405,
        headers: {
          ...corsHeaders(),
          Allow: "GET, HEAD, OPTIONS",
        },
      });
    }

    if (url.pathname !== "/" && url.pathname !== manifestPath) {
      return new Response("Not Found", {
        status: 404,
        headers: corsHeaders(),
      });
    }

    const manifestJson = (env.SIGNED_MANIFEST_JSON || "").trim();
    if (!manifestJson) {
      return new Response("SIGNED_MANIFEST_JSON is not configured", {
        status: 500,
        headers: corsHeaders(),
      });
    }

    return new Response(request.method === "HEAD" ? null : manifestJson, {
      status: 200,
      headers: {
        ...corsHeaders(),
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "public, max-age=300, stale-while-revalidate=300",
      },
    });
  },
};