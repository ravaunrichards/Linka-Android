const LOCAL_HTTP_HOSTS = new Set(["localhost", "127.0.0.1"]);

function parseAllowedOrigins(value) {
  const origins = new Set();
  for (const entry of String(value).split(",")) {
    const candidate = entry.trim();
    if (!candidate) continue;

    let url;
    try {
      url = new URL(candidate);
    } catch {
      throw new TypeError("CORS_ALLOWED_ORIGINS must contain valid origins.");
    }

    const localHttp = url.protocol === "http:" && LOCAL_HTTP_HOSTS.has(url.hostname);
    if (
      url.origin !== candidate ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== "/" ||
      !(url.protocol === "https:" || localHttp)
    ) {
      throw new TypeError("CORS_ALLOWED_ORIGINS accepts exact HTTPS origins and localhost HTTP origins only.");
    }
    origins.add(url.origin);
  }
  return origins;
}

function forwardedRequestOrigin(req) {
  const host = req.headers.host;
  if (!host) return null;
  const forwarded = req.headers["x-forwarded-proto"];
  const protocol = typeof forwarded === "string" && forwarded.trim()
    ? forwarded.split(",")[0].trim().toLowerCase()
    : req.socket?.encrypted ? "https" : "http";
  if (protocol !== "http" && protocol !== "https") return null;
  return protocol + "://" + host.toLowerCase();
}

/**
 * Create a strict CORS policy. Same-origin requests need no CORS headers;
 * cross-origin browser clients must be explicitly listed in the environment.
 */
export function createCorsPolicy(configuredOrigins = "") {
  const allowedOrigins = parseAllowedOrigins(configuredOrigins);
  return function corsForRequest(req) {
    const origin = req.headers.origin;
    if (typeof origin !== "string" || !origin) {
      return { allowed: true, headers: {} };
    }

    if (origin === forwardedRequestOrigin(req)) {
      return { allowed: true, headers: {} };
    }

    if (!allowedOrigins.has(origin)) {
      return { allowed: false, headers: {} };
    }

    return {
      allowed: true,
      headers: {
        "access-control-allow-origin": origin,
        "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "Authorization, Content-Type",
        "access-control-max-age": "600",
        vary: "Origin"
      }
    };
  };
}
