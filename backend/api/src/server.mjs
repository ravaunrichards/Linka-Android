import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { LinkaRuntime } from "./runtime.mjs";
import { createPostgresStore } from "./postgres-store.mjs";
import { createCorsPolicy } from "./cors.mjs";
import { normalizeE164 } from "./domain/e164.mjs";
import { resolveDestinationNumber } from "./domain/destination-metadata.mjs";

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
  ["/auth-crypto.mjs", { file: "auth-crypto.mjs", contentType: "text/javascript; charset=utf-8" }],
  ["/messaging-crypto.mjs", { file: "messaging-crypto.mjs", contentType: "text/javascript; charset=utf-8" }]
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

async function readJson(req, maxBytes = MAX_BODY_BYTES) {
  const contentType = req.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw Object.assign(new Error("content_type_must_be_json"), { statusCode: 415 });
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBytes) {
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

async function requireAccountAuth(req, res) {
  const session = await requireAuth(req, res);
  if (!session) return null;
  if (session.authType !== "account" || !session.accountId) {
    response(res, 403, { error: "account_auth_required" });
    return null;
  }
  return session;
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
    "invalid_device_identity", "invalid_device_platform", "invalid_device_management",
    "device_not_found", "invalid_credentials",
    "authentication_store_unavailable", "account_recovery_unavailable",
    "email_verification_unavailable", "invalid_e164", "invalid_destination_number",
    "account_auth_required", "invalid_conversation", "invalid_conversation_kind",
    "invalid_conversation_members", "conversation_member_not_found", "invalid_page_limit",
    "invalid_message", "invalid_message_ciphertext", "invalid_message_encryption_version",
    "invalid_message_attachments", "message_attachments_unavailable",
    "invalid_message_query", "invalid_message_cursor", "conversation_not_found",
    "invalid_encryption_key", "invalid_encryption_key_proof", "encryption_key_not_found", "conversation_encryption_keys_incomplete",
    "conversation_encryption_key_changed", "invalid_message_key_envelopes", "encryption_key_version_exhausted",
    "invalid_message_receipt", "attachment_not_found", "conversation_attachment_key_changed"
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
          ...(postgresStore ? ["postgres-wallet-ledger", "account-scoped-messaging-storage"] : [])
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
          walletHttpApi: "not_implemented",
          internalMessaging: postgresStore ? "device_encryption_required" : "database_required",
          internalMessageReceipts: postgresStore ? "per_device_delivered_read" : "database_required",
          encryptedAttachmentStorage: postgresStore ? "end_to_end_encrypted_4_mib" : "database_required"
        },
        limitations: [
          "This service carries call-control and WebRTC signaling only.",
          "It does not carry voice audio.",
          "Nearby SIM-free calls do not depend on this service.",
          "Device challenge sessions and signaling queues remain in-memory and expire.",
          "Account sessions are persisted when PostgreSQL is configured.",
          "Password recovery and email verification require an email delivery provider that is not configured.",
          "Wallet operations are server-side only; public wallet HTTP endpoints are not implemented.",
          "Internal messages are stored as opaque client-supplied ciphertext; the API does not decrypt or independently prove end-to-end encryption.",
          "Device encryption keys use ECDH P-256 and per-device message-key envelopes; private keys remain on clients.",
          "The backend validates current device-key coverage and stores wrapped content keys but cannot independently audit client cryptographic implementation.",
          "Encrypted attachment blobs up to 4 MiB are stored as ciphertext with per-device wrapped keys; only conversation members with a matching device envelope can retrieve them.",
          "The E2EE client implementation has automated browser and Android emulator tests but has not undergone an independent cryptographic audit."
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

    if (req.method === "POST" && path === "/v1/destinations/resolve") {
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`resolve-number:${remote}`, 60, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const destination = resolveDestinationNumber(body?.number);
      response(res, 200, destination);
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

    if (req.method === "GET" && path === "/v1/me/devices") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`account-devices-list:${session.accountId}:${remote}`, 60, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const devices = await postgresStore.listAccountDevices({
        accountId: session.accountId,
        currentDeviceId: session.deviceId
      });
      response(res, 200, { devices });
      return;
    }

    const accountDeviceRoute = /^\\/v1\\/me\\/devices\\/([^/]+)$/.exec(path);
    if (accountDeviceRoute && req.method === "DELETE") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`account-device-revoke:${session.accountId}:${remote}`, 12, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const revoked = await postgresStore.revokeAccountDevice({
        accountId: session.accountId,
        deviceId: accountDeviceRoute[1]
      });
      runtime.revokeDeviceSessions(accountDeviceRoute[1]);
      response(res, 200, revoked);
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

    if (req.method === "PUT" && path === "/v1/me/encryption-key") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`encryption-key-register:${session.accountId}:${session.deviceId}:${remote}`, 8, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      if (body.algorithm !== "ECDH-P256") {
        response(res, 400, { error: "invalid_encryption_key" });
        return;
      }
      if (typeof body.publicKeyBase64 !== "string" ||
          body.publicKeyBase64.length < 4 ||
          body.publicKeyBase64.length > 1024 ||
          !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.publicKeyBase64)) {
        response(res, 400, { error: "invalid_encryption_key" });
        return;
      }
      const publicKeyDer = Buffer.from(body.publicKeyBase64, "base64");
      if (publicKeyDer.toString("base64") !== body.publicKeyBase64 ||
          typeof body.signatureBase64 !== "string" ||
          !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.signatureBase64)) {
        response(res, 400, { error: "invalid_encryption_key_proof" });
        return;
      }
      const proofSignature = Buffer.from(body.signatureBase64, "base64");
      if (proofSignature.length < 64 || proofSignature.length > 80 ||
          proofSignature.toString("base64") !== body.signatureBase64) {
        response(res, 400, { error: "invalid_encryption_key_proof" });
        return;
      }
      const registered = await postgresStore.registerDeviceEncryptionKey({
        accountId: session.accountId,
        deviceId: session.deviceId,
        algorithm: body.algorithm,
        publicKeyDer,
        proofSignature
      });
      response(res, registered.reused ? 200 : 201, registered);
      return;
    }

    const encryptionDevicesRoute = /^\/v1\/conversations\/([^/]+)\/encryption-devices$/.exec(path);
    if (encryptionDevicesRoute && req.method === "GET") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`encryption-device-list:${session.accountId}:${remote}`, 120, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const devices = await postgresStore.listConversationEncryptionDevices({
        accountId: session.accountId,
        conversationId: encryptionDevicesRoute[1]
      });
      response(res, 200, { devices });
      return;
    }

    if (req.method === "GET" && path === "/v1/conversations") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`conversation-list:${session.accountId}:${remote}`, 120, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const limit = Number.parseInt(url.searchParams.get("limit") ?? "50", 10);
      const conversations = await postgresStore.listConversations(session.accountId, limit);
      response(res, 200, { conversations });
      return;
    }

    if (req.method === "POST" && path === "/v1/conversations") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`conversation-create:${session.accountId}:${remote}`, 20, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const conversation = await postgresStore.createConversation({
        accountId: session.accountId,
        kind: body.kind,
        participantAccountIds: body.participantAccountIds
      });
      response(res, conversation.reused ? 200 : 201, conversation);
      return;
    }

    const attachmentCollectionRoute = /^\/v1\/conversations\/([^/]+)\/attachments$/.exec(path);
    if (attachmentCollectionRoute && req.method === "POST") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`message-attachment-upload:${session.accountId}:${session.deviceId}:${remote}`, 12, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req, 6 * 1024 * 1024);
      const attachment = await postgresStore.createInternalAttachment({
        accountId: session.accountId,
        senderDeviceId: session.deviceId,
        conversationId: attachmentCollectionRoute[1],
        ciphertextBase64: body.ciphertextBase64,
        plaintextSizeBytes: body.plaintextSizeBytes,
        encryptionVersion: body.encryptionVersion,
        keyEnvelopes: body.keyEnvelopes
      });
      response(res, 201, { attachment });
      return;
    }

    const attachmentItemRoute = /^\/v1\/conversations\/([^/]+)\/attachments\/([^/]+)$/.exec(path);
    if (attachmentItemRoute && req.method === "GET") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`message-attachment-download:${session.accountId}:${session.deviceId}:${remote}`, 60, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const attachment = await postgresStore.getInternalAttachment({
        accountId: session.accountId,
        deviceId: session.deviceId,
        conversationId: attachmentItemRoute[1],
        attachmentId: attachmentItemRoute[2]
      });
      response(res, 200, { attachment });
      return;
    }

    const messageReceiptRoute = /^\/v1\/conversations\/([^/]+)\/receipts$/.exec(path);
    if (messageReceiptRoute && req.method === "POST") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`message-receipts:${session.accountId}:${session.deviceId}:${remote}`, 120, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const acknowledgement = await postgresStore.markInternalMessagesReceived({
        accountId: session.accountId,
        deviceId: session.deviceId,
        conversationId: messageReceiptRoute[1],
        messageIds: body.messageIds,
        state: body.state
      });
      response(res, 200, acknowledgement);
      return;
    }

    const messageRoute = /^\/v1\/conversations\/([^/]+)\/messages$/.exec(path);
    if (messageRoute && req.method === "POST") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`message-send:${session.accountId}:${remote}`, 60, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const body = await readJson(req);
      const message = await postgresStore.sendInternalMessage({
        accountId: session.accountId,
        senderDeviceId: session.deviceId,
        conversationId: messageRoute[1],
        ciphertext: body.ciphertext,
        encryptionVersion: body.encryptionVersion,
        keyEnvelopes: body.keyEnvelopes,
        attachmentManifest: body.attachmentManifest
      });
      response(res, 201, { message });
      return;
    }

    if (messageRoute && req.method === "GET") {
      const session = await requireAccountAuth(req, res);
      if (!session) return;
      const remote = req.socket.remoteAddress ?? "unknown";
      if (!rateLimit(`message-read:${session.accountId}:${remote}`, 120, 60_000)) {
        response(res, 429, { error: "rate_limited" });
        return;
      }
      const limit = Number.parseInt(url.searchParams.get("limit") ?? "50", 10);
      const before = url.searchParams.get("before");
      const messages = await postgresStore.listInternalMessages({
        accountId: session.accountId,
        deviceId: session.deviceId,
        conversationId: messageRoute[1],
        limit,
        before
      });
      response(res, 200, { messages });
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
