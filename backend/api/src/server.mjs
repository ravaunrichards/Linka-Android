import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { LinkaRuntime } from "./runtime.mjs";
import { createPostgresStore } from "./postgres-store.mjs";
import { createCorsPolicy } from "./cors.mjs";
import { normalizeE164 } from "./domain/e164.mjs";

const PORT = Number.parseInt(process.env.PORT ?? "8080", 10);
const NODE_ENV = process.env.NODE_ENV ?? "development";
const API_VERSION = "0.1.0";
const MAX_BODY_BYTES = 20 * 1024;
const runtime = new LinkaRuntime();
const postgresStore = createPostgresStore();
const corsPolicy = createCorsPolicy(process.env.CORS_ALLOWED_ORIGINS ?? "");
const WEB_ROOT = new URL("../../../web/", import.meta.url);
const WEB_ASSETS = new Map([
  ["/", { file: "index.html", contentType: "text/html; charset=utf-8" }],
  ["/index.html", { file: "index.html", contentType: "text/html; charset=utf-8" }],
  ["/styles.css", { file: "styles.css", contentType: "text/css; charset=utf-8" }],
  ["/app.js", { file: "app.js", contentType: "text/javascript; charset=utf-8" }],
  ["/account.mjs", { file: "account.mjs", contentType: "text/javascript; charset=utf-8" }],
  ["/auth-crypto.mjs", { file: "auth-crypto.mjs", contentType: "text/javascript; charset=utf-8" }]
]);

const rateBuckets = new Map();

function response(res, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": bytes.length,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer"
  });
  res.end(bytes);
}

async function serveWebAsset(req, res, pathname) {
  if (req.method !== "GET" && req.method !== "HEAD") return false;
  const asset = WEB_ASSETS.get(pathname);
  if (!asset) return false;

  try {
    const body = await readFile(new URL(asset.file, WEB_ROOT));
    res.writeHead(200, {
      "content-type": asset.contentType,
      "content-length": body.length,
      "cache-control": asset.file === "index.html" ? "no-cache" : "public, max-age=3600",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer"
    });
    res.end(req.method === "HEAD" ? undefined : body);
    return true;
  } catch {
    response(res, 503, { error: "web_companion_unavailable" });
    return true;
  }
}

function rateLimit(key, limit, windowMs) {
  const now = Date.now();
  let bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt <= now) {
    bucket = { count: 0, resetAt: now + windowMs };
    rateBuckets.set(key, bucket);
  }
  bucket.count += 1;
  if (bucket.count > limit) return false;

  // Keep the rate-limit map bounded during sustained traffic.
  if (rateBuckets.size > 10_000) {
    for (const [entry, value] of rateBuckets) {
      if (value.resetAt <= now) rateBuckets.delete(entry);
    }
  }
  return true;
}

async function readJson(req) {
  const contentType = req.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw Object.assign(new Error("content_type_must_be_json"), { statusCode: 415 });
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      throw Object.assign(new Error("request_body_too_large"), { statusCode: 413 });
    }
    chunks.push(chunk);
  }

  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("invalid_json"), { statusCode: 400 });
  }
}

function bearerToken(req) {
  const header = req.headers.authorization ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{32,128})$/.exec(header);
  return match?.[1] ?? null;
}

async function requireDeviceAuth(req, res) {
  const token = bearerToken(req);
  const session = runtime.authenticate(token);
  if (!session) {
    response(res, 401, { error: "device_auth_required" });
    return null;
  }
  return { ...session, token, authType: "device" };
}

async function requireAuth(req, res) {
  const token = bearerToken(req);
  const deviceSession = runtime.authenticate(token);
  if (deviceSession) return { ...deviceSession, token, authType: "device" };

  if (postgresStore) {
    try {
      const accountSession = await postgresStore.authenticateAccountSession(token);
      if (accountSession) return { ...accountSession, token, authType: "account" };
    } catch {
      response(res, 503, { error: "authentication_store_unavailable" });
      return null;
    }
  }

  response(res, 401, {
    error: "unauthorized",
    message: "A valid LINKA access token is required."
  });
  return null;
}

function safeErrorCode(error) {
  const allowed = new Set([
    "invalid_device_id", "invalid_public_key", "unsupported_public_key",
    "invalid_challenge_proof", "challenge_expired_or_invalid", "invalid_signature",
    "signature_verification_failed", "cannot_signal_self", "unsupported_signal_type",
    "invalid_call_id", "invalid_signal_payload", "signal_payload_too_large",
    "recipient_offline", "recipient_queue_full", "challenge_capacity_reached",
    "device_auth_required", "database_not_configured", "invalid_email",
    "invalid_display_name", "invalid_password", "invalid_registration",
    "invalid_login", "email_already_registered", "device_already_linked",
    "device_identity_mismatch", "device_identity_conflict", "device_revoked",
    "invalid_device_identity", "invalid_device_platform", "invalid_credentials",
    "authentication_store_unavailable", "account_recovery_unavailable",
    "email_verification_unavailable", "invalid_e164"
  ]);
  if (typeof error.code === "string" && allowed.has(error.code)) return error.code;
  return allowed.has(error.message) ? error.message : "request_failed";
}

async function handleRequest(req, res) {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = url.pathname;

  const cors = corsPolicy(req);
  if (!cors.allowed) {
    response(res, 403, { error: "origin_not_allowed" });
    return;
  }
  for (const [name, value] of Object.entries(cors.headers)) {
    res.setHeader(name, value);
  }
  if (req.method === "OPTIONS") {
    res.writeHead(204, { "cache-control": "no-store" });
    res.end();
    return;
  }

  try {
    if (await serveWebAsset(req, res, path)) return;

    if (req.method === "GET" && path === "/healthz") {
      response(res, 200, {
        status: "ok",
        service: "linka-api",
        version: API_VERSION
      });
      return;
    }

    if (req.method === "GET" && path === "/readyz") {
      if (postgresStore) {
        try {
          await postgresStore.checkReady();
        } catch {
          response(res, 503, {
            status: "not_ready",
            service: "linka-api",
            dependencies: { postgres: "unavailable" }
          });
          return;
        }
      }

      response(res, 200, {
        status: "ready",
        service: "linka-api",
        capabilities: [
          "signed-device-auth",
          "ephemeral-call-signaling",
          ...(postgresStore ? ["postgres-wallet-ledger"] : [])
        ],
        dependencies: { postgres: postgresStore ? "ready" : "not_configured" }
      });
      return;
    }

    if (req.method === "GET" && path === "/v1/info") {
      response(res, 200, {
        service: "linka-api",
        apiVersion: 1,
        appVersion: API_VERSION,
        modes: {
          nearbyOfflineVoice: "device-local",
          internetCallSignaling: "enabled",
          serverMediaRelay: false
        },
        persistence: {
          postgres: postgresStore ? "configured" : "not_configured",
          walletSqlFunction: postgresStore ? "available_when_ready" : "not_configured",
          accountRegistration: postgresStore ? "available" : "database_required",
          persistentAccountSessions: postgresStore ? "available" : "database_required",
          passwordRecovery: "not_implemented",
          emailVerification: "not_implemented",
          walletHttpApi: "not_implemented"
        },
        limitations: [
          "This service carries call-control and WebRTC signaling only.",
          "It does not carry voice audio.",
          "Nearby SIM-free calls do not depend on this service.",
          "Device challenge sessions and signaling queues remain in-memory and expire.",
          "Account sessions are persisted when PostgreSQL is configured.",
          "Password recovery and email verification require an email delivery provider that is not configured.",
          "Wallet operations are server-side only; public wallet HTTP endpoints are not implemented."
        ]
      });
      return;
    }

    if (req.method === "POST" && path === "/v1/destinations/normalize") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`normalize-number:${remote}`, 60, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const normalizedNumber = normalizeE164(body?.number);
      response(res, 200, {
        normalizedNumber,
        format: "E.164",
        validation: "syntax_only",
        destinationResolution: "not_performed",
        numberType: "unknown",
        providerReachability: "not_checked"
      });
      return;
    }

    if (req.method === "POST" && path === "/v1/auth/challenge") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`challenge:${remote}`, 20, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const challenge = runtime.issueChallenge(body.deviceId);
      response(res, 201, challenge);
      return;
    }

    if (req.method === "POST" && path === "/v1/auth/verify") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`verify:${remote}`, 30, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const session = runtime.verifyChallenge({
        challengeId: body.challengeId,
        deviceId: body.deviceId,
        publicKeyBase64: body.publicKeyBase64,
        signatureBase64: body.signatureBase64
      });
      response(res, 200, session);
      return;
    }

    if (req.method === "POST" && path === "/v1/accounts/register") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit("register:" + remote, 5, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      if (!postgresStore) {
        response(res, 503, { error: "database_not_configured" });
        return;
      }
      const device = await requireDeviceAuth(req, res);
      if (!device) return;
      const body = await readJson(req);
      const account = await postgresStore.createAccountWithDevice({
        email: body.email,
        password: body.password,
        displayName: body.displayName,
        device: {
          deviceId: device.deviceId,
          publicKeyDer: device.publicKeyDer,
          platform: body.platform,
          deviceName: body.deviceName
        }
      });
      response(res, 201, {
        accountId: account.accountId,
        email: account.email,
        displayName: account.displayName,
        emailVerified: false,
        deviceId: account.deviceId,
        accessToken: account.accessToken,
        tokenType: account.tokenType,
        expiresAt: account.expiresAt
      });
      return;
    }

    if (req.method === "POST" && path === "/v1/accounts/login") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit("login:" + remote, 10, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      if (!postgresStore) {
        response(res, 503, { error: "database_not_configured" });
        return;
      }
      const device = await requireDeviceAuth(req, res);
      if (!device) return;
      const body = await readJson(req);
      const account = await postgresStore.loginAccount({
        email: body.email,
        password: body.password,
        device: {
          deviceId: device.deviceId,
          publicKeyDer: device.publicKeyDer,
          platform: body.platform,
          deviceName: body.deviceName
        }
      });
      response(res, 200, {
        accountId: account.accountId,
        email: account.email,
        displayName: account.displayName,
        emailVerified: account.emailVerified,
        deviceId: account.deviceId,
        accessToken: account.accessToken,
        tokenType: account.tokenType,
        expiresAt: account.expiresAt
      });
      return;
    }

    if (req.method === "POST" && path === "/v1/accounts/recovery") {
      // Intentionally not simulated: a secure reset requires a configured email sender.
      response(res, 501, { error: "account_recovery_unavailable" });
      return;
    }

    if (req.method === "GET" && path === "/v1/me") {
      const session = await requireAuth(req, res);
      if (!session) return;
      if (session.authType === "account") {
        response(res, 200, {
          accountId: session.accountId,
          email: session.email,
          displayName: session.displayName,
          emailVerified: session.emailVerified,
          deviceId: session.deviceId,
          deviceFingerprint: session.fingerprint,
          expiresAt: session.expiresAt
        });
      } else {
        response(res, 200, {
          deviceId: session.deviceId,
          publicKeyFingerprint: session.fingerprint,
          expiresAt: session.expiresAt
        });
      }
      return;
    }

    if (req.method === "POST" && path === "/v1/auth/logout") {
      const session = await requireAuth(req, res);
      if (!session) return;
      if (session.authType === "account") {
        await postgresStore.revokeAccountSession(session.token);
        runtime.revokeDeviceSessions(session.deviceId);
      } else {
        runtime.revokeToken(session.token);
      }
      response(res, 200, { status: "signed_out" });
      return;
    }

    if (req.method === "POST" && path === "/v1/signals") {
      const session = await requireAuth(req, res);
      if (!session) return;
      const body = await readJson(req);
      const queued = runtime.enqueueSignal(session.deviceId, body);
      response(res, 202, queued);
      return;
    }

    if (req.method === "GET" && path === "/v1/signals") {
      const session = await requireAuth(req, res);
      if (!session) return;
      const limit = Number.parseInt(url.searchParams.get("limit") ?? "25", 10);
      const signals = runtime.pollSignals(session.deviceId, limit);
      response(res, 200, { signals, serverTime: Date.now() });
      return;
    }

    response(res, 404, { error: "not_found" });
  } catch (error) {
    const status = Number.isInteger(error.statusCode) ? error.statusCode : 400;
    const code = safeErrorCode(error);
    // Log only the route and error class, never request bodies, tokens or keys.
    console.warn(JSON.stringify({
      level: "warn",
      route: path,
      status,
      error: code
    }));
    response(res, status, { error: code });
  }
}

const server = createServer((req, res) => {
  handleRequest(req, res).catch(() => {
    if (!res.headersSent) {
      response(res, 500, { error: "internal_error" });
    } else {
      res.destroy();
    }
  });
});

server.requestTimeout = 15_000;
server.headersTimeout = 10_000;
server.keepAliveTimeout = 5_000;

const cleanupTimer = setInterval(() => {
  runtime.pruneExpired();
}, 30_000);
cleanupTimer.unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log(JSON.stringify({
    level: "info",
    event: "server_listening",
    service: "linka-api",
    port: PORT,
    environment: NODE_ENV
  }));
});

function shutdown(signal) {
  console.log(JSON.stringify({ level: "info", event: "shutdown", signal }));
  clearInterval(cleanupTimer);
  server.close(async () => {
    try {
      await postgresStore?.close();
      process.exit(0);
    } catch {
      process.exit(1);
    }
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
