import { createHash, createPublicKey, randomUUID, verify as verifySignature } from "node:crypto";
import { Pool } from "pg";
import {
  createAccessToken,
  createSessionExpiry,
  hashPassword,
  normalizeEmail,
  validateDisplayName,
  validatePassword,
  verifyPassword
} from "./account-auth.mjs";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CREDIT_SOURCES = new Set(["purchased", "promotional", "sponsored"]);
const EVENT_TYPES = new Set([
  "PURCHASE_CONFIRMED", "PROMOTIONAL_GRANT", "REFERRAL_GRANT",
  "SPONSORED_GRANT", "CALL_USAGE", "MESSAGE_USAGE",
  "NUMBER_RENTAL", "NUMBER_RENEWAL", "REFUND"
]);

function fail(code, message = code, statusCode) {
  const error = new Error(message);
  error.code = code;
  if (Number.isInteger(statusCode)) error.statusCode = statusCode;
  throw error;
}
function requiredText(value, field) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail("invalid_ledger_event", field + " must be a non-empty string.");
  }
  return value.trim();
}
function requireUuid(value, field) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    fail("invalid_account_id", field + " must be a UUID.");
  }
  return value;
}
function requireSafeCreditAmount(value) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    fail("invalid_credit_amount", "amount must be a positive safe integer.");
  }
  return value;
}
function safeInteger(value, field) {
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    fail("invalid_database_result", "Database returned an invalid " + field + " value.");
  }
  return parsed;
}
function walletBalances(row) {
  const purchased = safeInteger(row.purchased_credit_units, "purchased balance");
  const promotional = safeInteger(row.promotional_credit_units, "promotional balance");
  const sponsored = safeInteger(row.sponsored_credit_units, "sponsored balance");
  const total = purchased + promotional + sponsored;
  if (!Number.isSafeInteger(total)) fail("wallet_balance_overflow");
  return Object.freeze({
    purchased, promotional, sponsored, total,
    version: safeInteger(row.version, "wallet version")
  });
}
const ACCESS_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const DEVICE_PLATFORMS = new Set(["android", "web", "other"]);
let dummyPasswordHashPromise;

async function getDummyPasswordHash() {
  dummyPasswordHashPromise ??= hashPassword(
    "LINKA-invalid-account-password-do-not-use-2026"
  );
  return dummyPasswordHashPromise;
}

function requiredDevice(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    fail("invalid_device_identity");
  }
  const deviceId = requireUuid(input.deviceId, "deviceId");
  if (!Buffer.isBuffer(input.publicKeyDer) ||
      input.publicKeyDer.length < 64 ||
      input.publicKeyDer.length > 512) {
    fail("invalid_device_identity");
  }
  if (!DEVICE_PLATFORMS.has(input.platform)) fail("invalid_device_platform");
  const deviceName = input.deviceName == null
    ? null
    : String(input.deviceName).trim().slice(0, 100) || null;
  const fingerprint = createHash("sha256")
    .update(input.publicKeyDer)
    .digest("hex")
    .toUpperCase();
  return {
    deviceId,
    publicKeyDer: input.publicKeyDer,
    fingerprint,
    platform: input.platform,
    deviceName
  };
}

function messageEncryptionKeyStatement(deviceId, algorithm, fingerprint) {
  return Buffer.from([
    "LINKA-MESSAGING-KEY-V1",
    deviceId,
    algorithm,
    fingerprint
  ].join("\n"), "utf8");
}

function encryptionPublicKeyDer(value, field = "publicKeyDer") {
  if (!Buffer.isBuffer(value) || value.length < 64 || value.length > 512) {
    fail("invalid_encryption_key", field + " must be a bounded DER public key.");
  }
  try {
    const key = createPublicKey({ key: value, format: "der", type: "spki" });
    if (key.asymmetricKeyType !== "ec" ||
        key.asymmetricKeyDetails?.namedCurve !== "prime256v1") {
      fail("invalid_encryption_key", "Only ECDH P-256 public keys are supported.");
    }
    const canonical = key.export({ format: "der", type: "spki" });
    if (!canonical.equals(value)) fail("invalid_encryption_key");
    return canonical;
  } catch (error) {
    if (error?.code === "invalid_encryption_key") throw error;
    fail("invalid_encryption_key", "The ECDH public key is not valid DER/SPKI.");
  }
}

function strictBase64Buffer(value, field, minBytes, maxBytes = minBytes) {
  if (typeof value !== "string" || value.length < 4 ||
      value.length > Math.ceil(maxBytes * 4 / 3) + 4 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    fail("invalid_message_key_envelopes", field + " must be valid base64.");
  }
  const buffer = Buffer.from(value, "base64");
  if (buffer.length < minBytes || buffer.length > maxBytes ||
      buffer.toString("base64") !== value) {
    fail("invalid_message_key_envelopes", field + " has an invalid size.");
  }
  return buffer;
}

function normalizeMessageKeyEnvelopes(input) {
  if (!Array.isArray(input) || input.length < 1 || input.length > 100) {
    fail("invalid_message_key_envelopes");
  }
  const envelopes = input.map((item, index) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      fail("invalid_message_key_envelopes");
    }
    const recipientDeviceId = requireUuid(item.recipientDeviceId, "keyEnvelopes[" + index + "].recipientDeviceId");
    const recipientKeyVersion = Number(item.recipientKeyVersion);
    if (!Number.isSafeInteger(recipientKeyVersion) || recipientKeyVersion < 1) {
      fail("invalid_message_key_envelopes");
    }
    const ephemeralPublicKeyDer = encryptionPublicKeyDer(
      strictBase64Buffer(item.ephemeralPublicKeyBase64, "ephemeralPublicKeyBase64", 64, 512),
      "ephemeralPublicKeyBase64"
    );
    const hkdfSalt = strictBase64Buffer(item.hkdfSaltBase64, "hkdfSaltBase64", 16);
    const wrapNonce = strictBase64Buffer(item.wrapNonceBase64, "wrapNonceBase64", 12);
    const wrappedContentKey = strictBase64Buffer(
      item.wrappedContentKeyBase64, "wrappedContentKeyBase64", 48
    );
    return Object.freeze({
      recipientDeviceId,
      recipientKeyVersion,
      ephemeralPublicKeyDer,
      hkdfSalt,
      wrapNonce,
      wrappedContentKey
    });
  });
  if (new Set(envelopes.map(item => item.recipientDeviceId)).size !== envelopes.length) {
    fail("invalid_message_key_envelopes", "Duplicate recipient devices are not allowed.");
  }
  return envelopes;
}

function isoDate(value, field) {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) {
    fail("invalid_database_result", "Database returned an invalid " + field + ".");
  }
  return date.toISOString();
}

function accountSessionResult(row, accessToken) {
  return Object.freeze({
    accountId: row.account_id,
    email: row.email_normalized,
    displayName: row.display_name,
    emailVerified: row.email_verified_at != null,
    deviceId: row.device_id,
    deviceFingerprint: row.device_fingerprint,
    accessToken,
    tokenType: "Bearer",
    expiresAt: isoDate(row.expires_at, "session expiry")
  });
}

function safePoolError(error) {
  const code = typeof error?.code === "string" && /^[A-Z0-9_]{1,32}$/.test(error.code)
    ? error.code : "unknown";
  console.error(JSON.stringify({
    level: "error", event: "postgres_idle_client_error", code
  }));
}

/**
 * Create an optional PostgreSQL store. Without DATABASE_URL the API retains
 * its in-memory signaling mode and does not attempt a database connect.
 */
export function createPostgresStore({
  connectionString = process.env.DATABASE_URL,
  pool: injectedPool,
  onPoolError = safePoolError
} = {}) {
  const normalizedConnectionString = typeof connectionString === "string"
    ? connectionString.trim() : "";
  if (!injectedPool && normalizedConnectionString.length === 0) return null;

  const pool = injectedPool ?? new Pool({
    connectionString: normalizedConnectionString,
    max: 5,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 5_000,
    application_name: "linka-api"
  });
  let closed = false;
  pool.on?.("error", onPoolError);

  function assertOpen() {
    if (closed || storeClosed) fail("database_store_closed");
  }

  async function getWalletSnapshot(accountIdInput) {
    assertOpen();
    const accountId = requireUuid(accountIdInput, "accountId");
    const result = await pool.query(
      "SELECT purchased_credit_units, promotional_credit_units, " +
      "sponsored_credit_units, version FROM linka_private.wallet_accounts " +
      "WHERE account_id = $1::uuid",
      [accountId]
    );
    if (result.rows.length === 0) return null;
    return walletBalances(result.rows[0]);
  }

  async function persistSession(client, accountId, deviceId) {
    const accessToken = createAccessToken();
    const tokenHash = createHash("sha256").update(accessToken).digest();
    const sessionId = randomUUID();
    const expiresAt = createSessionExpiry();

    const inserted = await client.query(
      "INSERT INTO linka_private.auth_sessions " +
      "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
      "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), $5::timestamptz) " +
      "RETURNING expires_at",
      [sessionId, accountId, deviceId, tokenHash, expiresAt]
    );
    return { accessToken, expiresAt: isoDate(inserted.rows[0].expires_at, "session expiry") };
  }

  async function ensureDeviceForAccount(client, accountId, input) {
    const device = requiredDevice(input);
    const byFingerprint = await client.query(
      "SELECT device_id, account_id, signing_public_key_der, revoked_at " +
      "FROM linka_private.user_devices " +
      "WHERE device_fingerprint = $1::char(64) FOR UPDATE",
      [device.fingerprint]
    );

    if (byFingerprint.rows.length > 0) {
      const existing = byFingerprint.rows[0];
      if (existing.account_id !== accountId) {
        fail("device_already_linked", "This signed device is linked to another account.", 409);
      }
      if (existing.device_id !== device.deviceId ||
          !Buffer.isBuffer(existing.signing_public_key_der) ||
          !existing.signing_public_key_der.equals(device.publicKeyDer)) {
        fail("device_identity_mismatch", "The signed device identity does not match the registered device.", 409);
      }
      if (existing.revoked_at != null) {
        fail("device_revoked", "This device has been revoked.", 403);
      }

      await client.query(
        "UPDATE linka_private.user_devices " +
        "SET last_seen_at = now(), platform = $2, device_name = COALESCE($3, device_name) " +
        "WHERE device_id = $1::uuid",
        [device.deviceId, device.platform, device.deviceName]
      );
      return device;
    }

    const byDeviceId = await client.query(
      "SELECT account_id, device_fingerprint FROM linka_private.user_devices " +
      "WHERE device_id = $1::uuid FOR UPDATE",
      [device.deviceId]
    );
    if (byDeviceId.rows.length > 0) {
      fail("device_identity_conflict", "The device identifier is already registered.", 409);
    }

    await client.query(
      "INSERT INTO linka_private.user_devices " +
      "(device_id, account_id, device_fingerprint, signing_public_key_der, " +
      "platform, device_name, last_seen_at) " +
      "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, $5::text, $6::text, now())",
      [
        device.deviceId, accountId, device.fingerprint,
        device.publicKeyDer, device.platform, device.deviceName
      ]
    );
    return device;
  }

  async function transaction(work) {
    assertStoreOpen();
    const client = await pool.connect();
    let began = false;
    try {
      await client.query("BEGIN");
      began = true;
      const result = await work(client);
      await client.query("COMMIT");
      began = false;
      return result;
    } catch (error) {
      if (began) {
        try { await client.query("ROLLBACK"); } catch { /* Preserve original error. */ }
      }
      throw error;
    } finally {
      client.release();
    }
  }

  async function createAccountWithDevice(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_registration");
    }
    const email = normalizeEmail(input.email);
    const displayName = validateDisplayName(input.displayName);
    const passwordHash = await hashPassword(input.password);
    const device = requiredDevice(input.device);
    const accountId = randomUUID();

    return transaction(async client => {
      const duplicateDevice = await client.query(
        "SELECT account_id FROM linka_private.user_devices " +
        "WHERE device_fingerprint = $1::char(64) FOR UPDATE",
        [device.fingerprint]
      );
      if (duplicateDevice.rows.length > 0) {
        fail("device_already_linked", "This signed device is already linked to an account.", 409);
      }

      try {
        await client.query(
          "INSERT INTO linka_private.accounts " +
          "(account_id, email_normalized, display_name, password_hash, account_status) " +
          "VALUES ($1::uuid, $2::text, $3::text, $4::text, 'active')",
          [accountId, email, displayName, passwordHash]
        );
      } catch (error) {
        if (error?.code === "23505") {
          fail("email_already_registered", "An account already exists for this email.", 409);
        }
        throw error;
      }

      await ensureDeviceForAccount(client, accountId, device);
      await client.query(
        "INSERT INTO linka_private.wallet_accounts (wallet_id, account_id) " +
        "VALUES ($1::uuid, $2::uuid)",
        [randomUUID(), accountId]
      );
      const session = await persistSession(client, accountId, device.deviceId);
      return Object.freeze({
        accountId,
        email,
        displayName,
        emailVerified: false,
        deviceId: device.deviceId,
        deviceFingerprint: device.fingerprint,
        accessToken: session.accessToken,
        tokenType: "Bearer",
        expiresAt: session.expiresAt
      });
    });
  }

  async function loginAccount(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_login");
    }
    const email = normalizeEmail(input.email);
    const device = requiredDevice(input.device);
    const password = typeof input.password === "string" && input.password.length <= 256
      ? input.password
      : "";

    const lookup = await pool.query(
      "SELECT account_id, email_normalized, display_name, password_hash, account_status " +
      "FROM linka_private.accounts WHERE email_normalized = $1::text",
      [email]
    );
    const account = lookup.rows[0] ?? null;
    const candidateHash = account?.password_hash ?? await getDummyPasswordHash();
    const passwordMatches = await verifyPassword(password, candidateHash);
    if (!account || !passwordMatches || account.account_status !== "active" ||
        typeof account.password_hash !== "string") {
      fail("invalid_credentials", "Email or password is incorrect.", 401);
    }

    return transaction(async client => {
      const locked = await client.query(
        "SELECT account_id, email_normalized, display_name, password_hash, " +
        "account_status, email_verified_at " +
        "FROM linka_private.accounts WHERE account_id = $1::uuid FOR UPDATE",
        [account.account_id]
      );
      const current = locked.rows[0];
      if (!current || current.account_status !== "active" ||
          current.password_hash !== account.password_hash) {
        fail("invalid_credentials", "Email or password is incorrect.", 401);
      }

      await ensureDeviceForAccount(client, current.account_id, device);
      const session = await persistSession(client, current.account_id, device.deviceId);
      return Object.freeze({
        accountId: current.account_id,
        email: current.email_normalized,
        displayName: current.display_name,
        emailVerified: current.email_verified_at != null,
        deviceId: device.deviceId,
        deviceFingerprint: device.fingerprint,
        accessToken: session.accessToken,
        tokenType: "Bearer",
        expiresAt: session.expiresAt
      });
    });
  }

  async function authenticateAccountSession(accessToken) {
    assertStoreOpen();
    if (typeof accessToken !== "string" || !ACCESS_TOKEN_PATTERN.test(accessToken)) {
      return null;
    }
    const tokenHash = createHash("sha256").update(accessToken).digest();
    const result = await pool.query(
      "SELECT s.account_id, s.device_id, s.expires_at, " +
      "a.email_normalized, a.display_name, a.email_verified_at, d.device_fingerprint " +
      "FROM linka_private.auth_sessions s " +
      "JOIN linka_private.accounts a ON a.account_id = s.account_id " +
      "JOIN linka_private.user_devices d " +
      "ON d.device_id = s.device_id AND d.account_id = s.account_id " +
      "WHERE s.token_hash = $1::bytea AND s.revoked_at IS NULL " +
      "AND s.expires_at > now() AND a.account_status = 'active' " +
      "AND d.revoked_at IS NULL",
      [tokenHash]
    );
    if (result.rows.length === 0) return null;
    return Object.freeze({
      accountId: result.rows[0].account_id,
      deviceId: result.rows[0].device_id,
      fingerprint: result.rows[0].device_fingerprint,
      email: result.rows[0].email_normalized,
      displayName: result.rows[0].display_name,
      emailVerified: result.rows[0].email_verified_at != null,
      expiresAt: isoDate(result.rows[0].expires_at, "session expiry"),
      authType: "account"
    });
  }

  async function revokeAccountSession(accessToken) {
    assertStoreOpen();
    if (typeof accessToken !== "string" || !ACCESS_TOKEN_PATTERN.test(accessToken)) {
      return null;
    }
    const tokenHash = createHash("sha256").update(accessToken).digest();
    const result = await pool.query(
      "UPDATE linka_private.auth_sessions SET revoked_at = now() " +
      "WHERE token_hash = $1::bytea AND revoked_at IS NULL " +
      "RETURNING account_id, device_id",
      [tokenHash]
    );
    return result.rows[0] ?? null;
  }

  async function createConversation(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_conversation");
    }
    const accountId = requireUuid(input.accountId, "accountId");
    const kind = input.kind;
    if (!["direct", "group"].includes(kind)) fail("invalid_conversation_kind");
    if (!Array.isArray(input.participantAccountIds) ||
        input.participantAccountIds.length < 1 ||
        input.participantAccountIds.length > 49) {
      fail("invalid_conversation_members");
    }
    const recipients = input.participantAccountIds.map((value, index) =>
      requireUuid(value, "participantAccountIds[" + index + "]")
    );
    if (new Set(recipients).size !== recipients.length || recipients.includes(accountId)) {
      fail("invalid_conversation_members");
    }
    if (kind === "direct" && recipients.length !== 1) {
      fail("invalid_conversation_members");
    }
    const memberIds = [accountId, ...recipients].sort();

    return transaction(async client => {
      const activeAccounts = await client.query(
        "SELECT account_id FROM linka_private.accounts " +
        "WHERE account_id = ANY($1::uuid[]) AND account_status = 'active' " +
        "ORDER BY account_id FOR SHARE",
        [memberIds]
      );
      if (activeAccounts.rows.length !== memberIds.length) {
        fail("conversation_member_not_found", "One or more conversation members do not exist.", 404);
      }

      if (kind === "direct") {
        const pairKey = memberIds.join(":");
        await client.query(
          "SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))",
          [pairKey]
        );
        const existing = await client.query(
          "SELECT c.conversation_id, c.created_at " +
          "FROM linka_private.conversations c " +
          "WHERE c.conversation_kind = 'direct' " +
          "AND EXISTS (SELECT 1 FROM linka_private.conversation_members m " +
          "WHERE m.conversation_id = c.conversation_id AND m.account_id = $1::uuid AND m.left_at IS NULL) " +
          "AND EXISTS (SELECT 1 FROM linka_private.conversation_members m " +
          "WHERE m.conversation_id = c.conversation_id AND m.account_id = $2::uuid AND m.left_at IS NULL) " +
          "AND (SELECT count(*) FROM linka_private.conversation_members m " +
          "WHERE m.conversation_id = c.conversation_id AND m.left_at IS NULL) = 2 " +
          "ORDER BY c.created_at ASC LIMIT 1",
          memberIds
        );
        if (existing.rows.length > 0) {
          return Object.freeze({
            conversationId: existing.rows[0].conversation_id,
            kind,
            createdAt: isoDate(existing.rows[0].created_at, "conversation creation"),
            memberAccountIds: Object.freeze(memberIds),
            reused: true
          });
        }
      }

      const conversationId = randomUUID();
      const inserted = await client.query(
        "INSERT INTO linka_private.conversations " +
        "(conversation_id, conversation_kind, created_by_account_id) " +
        "VALUES ($1::uuid, $2::text, $3::uuid) RETURNING created_at",
        [conversationId, kind, accountId]
      );
      await client.query(
        "INSERT INTO linka_private.conversation_members (conversation_id, account_id) " +
        "SELECT $1::uuid, member_id FROM unnest($2::uuid[]) AS members(member_id)",
        [conversationId, memberIds]
      );
      return Object.freeze({
        conversationId,
        kind,
        createdAt: isoDate(inserted.rows[0].created_at, "conversation creation"),
        memberAccountIds: Object.freeze(memberIds),
        reused: false
      });
    });
  }

  async function listConversations(accountIdInput, limitInput = 50) {
    assertStoreOpen();
    const accountId = requireUuid(accountIdInput, "accountId");
    const limit = Number(limitInput);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      fail("invalid_page_limit");
    }
    const result = await pool.query(
      "SELECT c.conversation_id, c.conversation_kind, c.created_at, " +
      "count(m.account_id)::integer AS member_count, " +
      "COALESCE(json_agg(json_build_object(" +
      "'accountId', member.account_id, 'displayName', member.display_name) " +
      "ORDER BY member.display_name) FILTER (WHERE member.account_id IS NOT NULL), '[]'::json) AS members, " +
      "last_message.message_id AS last_message_id, " +
      "last_message.sender_account_id AS last_message_sender_account_id, " +
      "last_message.sent_at AS last_message_at " +
      "FROM linka_private.conversations c " +
      "JOIN linka_private.conversation_members mine " +
      "ON mine.conversation_id = c.conversation_id AND mine.account_id = $1::uuid AND mine.left_at IS NULL " +
      "JOIN linka_private.conversation_members m " +
      "ON m.conversation_id = c.conversation_id AND m.left_at IS NULL " +
      "LEFT JOIN linka_private.accounts member ON member.account_id = m.account_id " +
      "LEFT JOIN LATERAL (" +
      "SELECT message_id, sender_account_id, sent_at FROM linka_private.internal_messages " +
      "WHERE conversation_id = c.conversation_id ORDER BY sent_at DESC, message_id DESC LIMIT 1" +
      ") last_message ON TRUE " +
      "GROUP BY c.conversation_id, last_message.message_id, " +
      "last_message.sender_account_id, last_message.sent_at " +
      "ORDER BY COALESCE(last_message.sent_at, c.created_at) DESC, c.conversation_id DESC " +
      "LIMIT $2::integer",
      [accountId, limit]
    );
    return Object.freeze(result.rows.map(row => Object.freeze({
      conversationId: row.conversation_id,
      kind: row.conversation_kind,
      createdAt: isoDate(row.created_at, "conversation creation"),
      memberCount: safeInteger(row.member_count, "conversation member count"),
      members: Object.freeze(Array.isArray(row.members) ? row.members : []),
      lastMessageId: row.last_message_id ?? null,
      lastMessageSenderAccountId: row.last_message_sender_account_id ?? null,
      lastMessageAt: row.last_message_at == null
        ? null : isoDate(row.last_message_at, "last message time")
    })));
  }

  async function registerDeviceEncryptionKey(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_encryption_key");
    }
    const accountId = requireUuid(input.accountId, "accountId");
    const deviceId = requireUuid(input.deviceId, "deviceId");
    if (input.algorithm !== "ECDH-P256") fail("invalid_encryption_key");
    const publicKeyDer = encryptionPublicKeyDer(input.publicKeyDer);
    const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
    if (!Buffer.isBuffer(input.proofSignature) ||
        input.proofSignature.length < 64 || input.proofSignature.length > 80) {
      fail("invalid_encryption_key_proof");
    }

    return transaction(async client => {
      const device = await client.query(
        "SELECT device_id, signing_public_key_der FROM linka_private.user_devices " +
        "WHERE device_id = $1::uuid AND account_id = $2::uuid AND revoked_at IS NULL " +
        "FOR UPDATE",
        [deviceId, accountId]
      );
      if (device.rows.length === 0) {
        fail("encryption_key_not_found", "The active signed-in device was not found.", 404);
      }
      let proofValid = false;
      try {
        const signingPublicKey = createPublicKey({
          key: device.rows[0].signing_public_key_der,
          format: "der",
          type: "spki"
        });
        proofValid = signingPublicKey.asymmetricKeyType === "ec" &&
          signingPublicKey.asymmetricKeyDetails?.namedCurve === "prime256v1" &&
          verifySignature(
            "sha256",
            messageEncryptionKeyStatement(deviceId, "ECDH-P256", fingerprint),
            signingPublicKey,
            input.proofSignature
          );
      } catch {
        proofValid = false;
      }
      if (!proofValid) fail("invalid_encryption_key_proof");

      const current = await client.query(
        "SELECT key_version, public_key_fingerprint FROM linka_private.device_encryption_keys " +
        "WHERE device_id = $1::uuid AND retired_at IS NULL FOR UPDATE",
        [deviceId]
      );
      if (current.rows[0]?.public_key_fingerprint === fingerprint) {
        return Object.freeze({
          deviceId,
          algorithm: "ECDH-P256",
          keyVersion: safeInteger(current.rows[0].key_version, "encryption key version"),
          publicKeyFingerprint: fingerprint,
          reused: true
        });
      }

      const versionResult = await client.query(
        "SELECT COALESCE(MAX(key_version), 0)::integer AS latest_version " +
        "FROM linka_private.device_encryption_keys WHERE device_id = $1::uuid",
        [deviceId]
      );
      const keyVersion = safeInteger(versionResult.rows[0].latest_version, "encryption key version") + 1;
      if (!Number.isSafeInteger(keyVersion) || keyVersion > 2_147_483_647) {
        fail("encryption_key_version_exhausted");
      }
      await client.query(
        "UPDATE linka_private.device_encryption_keys SET retired_at = now() " +
        "WHERE device_id = $1::uuid AND retired_at IS NULL",
        [deviceId]
      );
      await client.query(
        "INSERT INTO linka_private.device_encryption_keys " +
        "(device_id, account_id, key_version, key_algorithm, public_key_der, public_key_fingerprint) " +
        "VALUES ($1::uuid, $2::uuid, $3::integer, 'ECDH-P256', $4::bytea, $5::char(64))",
        [deviceId, accountId, keyVersion, publicKeyDer, fingerprint]
      );
      return Object.freeze({
        deviceId,
        algorithm: "ECDH-P256",
        keyVersion,
        publicKeyFingerprint: fingerprint,
        reused: false
      });
    });
  }

  async function listConversationEncryptionDevices(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_message_query");
    }
    const accountId = requireUuid(input.accountId, "accountId");
    const conversationId = requireUuid(input.conversationId, "conversationId");
    const result = await pool.query(
      "SELECT d.device_id, d.account_id, a.display_name, k.key_version, " +
      "encode(k.public_key_der, 'base64') AS public_key_base64, " +
      "k.public_key_fingerprint " +
      "FROM linka_private.conversation_members mine " +
      "JOIN linka_private.conversation_members member " +
      "ON member.conversation_id = mine.conversation_id AND member.left_at IS NULL " +
      "JOIN linka_private.accounts a ON a.account_id = member.account_id AND a.account_status = 'active' " +
      "JOIN linka_private.user_devices d ON d.account_id = member.account_id AND d.revoked_at IS NULL " +
      "LEFT JOIN LATERAL (" +
      "SELECT key_version, public_key_der, public_key_fingerprint " +
      "FROM linka_private.device_encryption_keys " +
      "WHERE device_id = d.device_id AND retired_at IS NULL LIMIT 1" +
      ") k ON TRUE " +
      "WHERE mine.conversation_id = $1::uuid AND mine.account_id = $2::uuid " +
      "AND mine.left_at IS NULL " +
      "ORDER BY member.account_id, d.device_id",
      [conversationId, accountId]
    );
    if (result.rows.length === 0) {
      fail("conversation_not_found", "Conversation was not found or the account is not an active member.", 404);
    }
    return Object.freeze(result.rows.map(row => Object.freeze({
      deviceId: row.device_id,
      accountId: row.account_id,
      displayName: row.display_name,
      algorithm: row.key_version == null ? null : "ECDH-P256",
      keyVersion: row.key_version == null ? null : safeInteger(row.key_version, "encryption key version"),
      publicKeyBase64: row.public_key_base64 ?? null,
      publicKeyFingerprint: row.public_key_fingerprint ?? null
    })));
  }

  async function sendInternalMessage(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_message");
    }
    const accountId = requireUuid(input.accountId, "accountId");
    const conversationId = requireUuid(input.conversationId, "conversationId");
    if (typeof input.ciphertext !== "string" ||
        input.ciphertext.length < 4 ||
        input.ciphertext.length > 16_384 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(input.ciphertext)) {
      fail("invalid_message_ciphertext");
    }
    const ciphertext = Buffer.from(input.ciphertext, "base64");
    if (ciphertext.length === 0 || ciphertext.length > 12 * 1024 ||
        ciphertext.toString("base64") !== input.ciphertext) {
      fail("invalid_message_ciphertext");
    }
    if (typeof input.encryptionVersion !== "string" ||
        !/^[A-Za-z0-9._-]{1,64}$/.test(input.encryptionVersion)) {
      fail("invalid_message_encryption_version");
    }
    const attachmentManifest = input.attachmentManifest ?? [];
    if (!Array.isArray(attachmentManifest)) fail("invalid_message_attachments");
    if (attachmentManifest.length > 0) {
      fail("message_attachments_unavailable", "Encrypted attachment storage is not implemented yet.", 501);
    }

    if (input.encryptionVersion === "LINKA-E2EE-2") {
      const senderDeviceId = requireUuid(input.senderDeviceId, "senderDeviceId");
      const keyEnvelopes = normalizeMessageKeyEnvelopes(input.keyEnvelopes);
      return transaction(async client => {
        const devices = await client.query(
          "SELECT d.device_id, d.account_id, k.key_version " +
          "FROM linka_private.conversation_members m " +
          "JOIN linka_private.user_devices d ON d.account_id = m.account_id AND d.revoked_at IS NULL " +
          "LEFT JOIN LATERAL (" +
          "SELECT key_version FROM linka_private.device_encryption_keys " +
          "WHERE device_id = d.device_id AND retired_at IS NULL LIMIT 1" +
          ") k ON TRUE " +
          "WHERE m.conversation_id = $1::uuid AND m.left_at IS NULL " +
          "ORDER BY d.device_id FOR SHARE OF m, d",
          [conversationId]
        );
        if (devices.rows.length === 0 ||
            !devices.rows.some(row => row.account_id === accountId && row.device_id === senderDeviceId)) {
          fail("conversation_not_found", "Conversation was not found or the account is not an active member.", 404);
        }
        if (devices.rows.some(row => row.key_version == null)) {
          fail("conversation_encryption_keys_incomplete",
            "Every active conversation device must register an encryption key before sending.", 409);
        }
        if (devices.rows.length > 100 || keyEnvelopes.length !== devices.rows.length) {
          fail("invalid_message_key_envelopes",
            "A key envelope is required for every active device in the conversation.");
        }
        const expected = new Map(devices.rows.map(row => [row.device_id, safeInteger(row.key_version, "encryption key version")]));
        for (const envelope of keyEnvelopes) {
          if (!expected.has(envelope.recipientDeviceId)) {
            fail("invalid_message_key_envelopes", "An envelope targets a device outside the conversation.");
          }
          if (expected.get(envelope.recipientDeviceId) !== envelope.recipientKeyVersion) {
            fail("conversation_encryption_key_changed",
              "A recipient encryption key changed. Refresh the device list and encrypt again.", 409);
          }
        }
        if (keyEnvelopes.some(item => !expected.has(item.recipientDeviceId)) ||
            [...expected.keys()].some(deviceId => !keyEnvelopes.some(item => item.recipientDeviceId === deviceId))) {
          fail("invalid_message_key_envelopes",
            "A key envelope is required for every active device in the conversation.");
        }

        const messageId = randomUUID();
        const inserted = await client.query(
          "INSERT INTO linka_private.internal_messages " +
          "(message_id, conversation_id, sender_account_id, ciphertext, encryption_version, attachment_manifest) " +
          "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, $5::text, '[]'::jsonb) " +
          "RETURNING message_id, conversation_id, sender_account_id, " +
          "encode(ciphertext, 'base64') AS ciphertext, encryption_version, attachment_manifest, sent_at",
          [messageId, conversationId, accountId, ciphertext, input.encryptionVersion]
        );
        for (const envelope of keyEnvelopes) {
          await client.query(
            "INSERT INTO linka_private.internal_message_key_envelopes " +
            "(message_id, recipient_device_id, recipient_key_version, ephemeral_public_key_der, " +
            "hkdf_salt, wrap_nonce, wrapped_content_key) " +
            "VALUES ($1::uuid, $2::uuid, $3::integer, $4::bytea, $5::bytea, $6::bytea, $7::bytea)",
            [
              messageId, envelope.recipientDeviceId, envelope.recipientKeyVersion,
              envelope.ephemeralPublicKeyDer, envelope.hkdfSalt, envelope.wrapNonce,
              envelope.wrappedContentKey
            ]
          );
        }
        return mapInternalMessage(inserted.rows[0]);
      });
    }

    const messageId = randomUUID();
    const result = await pool.query(
      "INSERT INTO linka_private.internal_messages " +
      "(message_id, conversation_id, sender_account_id, ciphertext, encryption_version, attachment_manifest) " +
      "SELECT $1::uuid, c.conversation_id, $3::uuid, $4::bytea, $5::text, '[]'::jsonb " +
      "FROM linka_private.conversations c " +
      "WHERE c.conversation_id = $2::uuid " +
      "AND EXISTS (SELECT 1 FROM linka_private.conversation_members m " +
      "WHERE m.conversation_id = c.conversation_id AND m.account_id = $3::uuid AND m.left_at IS NULL) " +
      "RETURNING message_id, conversation_id, sender_account_id, " +
      "encode(ciphertext, 'base64') AS ciphertext, encryption_version, " +
      "attachment_manifest, sent_at",
      [messageId, conversationId, accountId, ciphertext, input.encryptionVersion]
    );
    if (result.rows.length === 0) {
      fail("conversation_not_found", "Conversation was not found or the account is not an active member.", 404);
    }
    return mapInternalMessage(result.rows[0]);
  }

  function mapInternalMessage(row) {
    return Object.freeze({
      messageId: row.message_id,
      conversationId: row.conversation_id,
      senderAccountId: row.sender_account_id,
      ciphertext: typeof row.ciphertext === "string"
        ? row.ciphertext : Buffer.from(row.ciphertext).toString("base64"),
      encryptionVersion: row.encryption_version,
      attachmentManifest: Object.freeze(Array.isArray(row.attachment_manifest) ? row.attachment_manifest : []),
      sentAt: isoDate(row.sent_at, "message time"),
      ...(row.envelope_device_id ? {
        keyEnvelope: Object.freeze({
          recipientDeviceId: row.envelope_device_id,
          recipientKeyVersion: safeInteger(row.envelope_key_version, "recipient encryption key version"),
          ephemeralPublicKeyBase64: row.envelope_ephemeral_public_key,
          hkdfSaltBase64: row.envelope_hkdf_salt,
          wrapNonceBase64: row.envelope_wrap_nonce,
          wrappedContentKeyBase64: row.envelope_wrapped_content_key
        })
      } : {})
    });
  }

  async function listInternalMessages(input) {
    assertStoreOpen();
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      fail("invalid_message_query");
    }
    const accountId = requireUuid(input.accountId, "accountId");
    const conversationId = requireUuid(input.conversationId, "conversationId");
    const limit = input.limit == null ? 50 : Number(input.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) fail("invalid_page_limit");

    let before = null;
    if (input.before != null) {
      before = input.before instanceof Date ? input.before : new Date(input.before);
      if (!Number.isFinite(before.getTime())) fail("invalid_message_cursor");
    }

    const access = await pool.query(
      "SELECT 1 FROM linka_private.conversation_members " +
      "WHERE conversation_id = $1::uuid AND account_id = $2::uuid AND left_at IS NULL",
      [conversationId, accountId]
    );
    if (access.rows.length === 0) {
      fail("conversation_not_found", "Conversation was not found or the account is not an active member.", 404);
    }

    const result = await pool.query(
      "SELECT m.message_id, m.conversation_id, m.sender_account_id, " +
      "encode(m.ciphertext, 'base64') AS ciphertext, m.encryption_version, " +
      "m.attachment_manifest, m.sent_at, e.recipient_device_id AS envelope_device_id, " +
      "e.recipient_key_version AS envelope_key_version, " +
      "encode(e.ephemeral_public_key_der, 'base64') AS envelope_ephemeral_public_key, " +
      "encode(e.hkdf_salt, 'base64') AS envelope_hkdf_salt, " +
      "encode(e.wrap_nonce, 'base64') AS envelope_wrap_nonce, " +
      "encode(e.wrapped_content_key, 'base64') AS envelope_wrapped_content_key " +
      "FROM linka_private.internal_messages m " +
      "LEFT JOIN linka_private.internal_message_key_envelopes e " +
      "ON e.message_id = m.message_id AND e.recipient_device_id = $4::uuid " +
      "WHERE m.conversation_id = $1::uuid " +
      "AND ($3::timestamptz IS NULL OR m.sent_at < $3::timestamptz) " +
      "ORDER BY m.sent_at DESC, m.message_id DESC LIMIT $2::integer",
      [conversationId, limit, before, input.deviceId == null ? null : requireUuid(input.deviceId, "deviceId")]
    );
    return Object.freeze(result.rows.map(mapInternalMessage));
  }

  async function getAccountProfile(accountIdInput) {
    assertStoreOpen();
    const accountId = requireUuid(accountIdInput, "accountId");
    const result = await pool.query(
      "SELECT account_id, email_normalized, display_name, email_verified_at, " +
      "account_status, created_at FROM linka_private.accounts " +
      "WHERE account_id = $1::uuid AND account_status = 'active'",
      [accountId]
    );
    if (result.rows.length === 0) return null;
    const row = result.rows[0];
    return Object.freeze({
      accountId: row.account_id,
      email: row.email_normalized,
      displayName: row.display_name,
      emailVerified: row.email_verified_at != null,
      createdAt: isoDate(row.created_at, "account creation")
    });
  }

  let storeClosed = false;
  function assertStoreOpen() {
    if (storeClosed) fail("database_store_closed");
  }

  return Object.freeze({
    configured: true,
    createAccountWithDevice,
    loginAccount,
    authenticateAccountSession,
    revokeAccountSession,
    getAccountProfile,
    createConversation,
    listConversations,
    registerDeviceEncryptionKey,
    listConversationEncryptionDevices,
    sendInternalMessage,
    listInternalMessages,

    async checkReady() {
      assertOpen();
      const result = await pool.query(
        "SELECT to_regprocedure(" +
        "'linka_private.apply_wallet_event(text,text,uuid,text,text,bigint,text,text)'" +
        ") IS NOT NULL AS wallet_function_ready, " +
        "EXISTS (SELECT 1 FROM linka_private.schema_migrations " +
        "WHERE version = $1::text) AS cross_account_migration_ready, " +
        "EXISTS (SELECT 1 FROM linka_private.schema_migrations " +
        "WHERE version = $2::text) AS messaging_encryption_migration_ready",
        ["0002_cross_account_integrity", "0003_messaging_device_encryption"]
      );
      if (result.rows[0]?.wallet_function_ready !== true ||
          result.rows[0]?.cross_account_migration_ready !== true ||
          result.rows[0]?.messaging_encryption_migration_ready !== true) {
        fail("database_schema_not_ready");
      }
      return Object.freeze({
        status: "ready",
        schemaVersion: "0003_messaging_device_encryption"
      });
    },

    async ensureWallet(accountIdInput) {
      assertOpen();
      const accountId = requireUuid(accountIdInput, "accountId");
      await pool.query(
        "INSERT INTO linka_private.wallet_accounts (wallet_id, account_id) " +
        "VALUES ($1::uuid, $2::uuid) ON CONFLICT (account_id) DO NOTHING",
        [randomUUID(), accountId]
      );
      const snapshot = await getWalletSnapshot(accountId);
      if (!snapshot) fail("wallet_create_failed");
      return snapshot;
    },

    getWalletSnapshot,

    async applyWalletEvent(input) {
      assertOpen();
      if (!input || typeof input !== "object" || Array.isArray(input)) {
        fail("invalid_ledger_event", "A wallet event is required.");
      }
      const eventId = requiredText(input.eventId, "eventId");
      const idempotencyKey = requiredText(input.idempotencyKey, "idempotencyKey");
      const accountId = requireUuid(input.accountId, "accountId");
      const eventType = requiredText(input.eventType, "eventType");
      const creditSource = requiredText(input.creditSource, "creditSource");
      const amount = requireSafeCreditAmount(input.amount);
      if (!EVENT_TYPES.has(eventType)) fail("unsupported_ledger_event");
      if (!CREDIT_SOURCES.has(creditSource)) fail("invalid_credit_source");

      const referenceId = input.referenceId == null
        ? null : requiredText(input.referenceId, "referenceId");
      const reversesEventId = input.reversesEventId == null
        ? null : requiredText(input.reversesEventId, "reversesEventId");

      const result = await pool.query(
        "SELECT * FROM linka_private.apply_wallet_event(" +
        "$1::text, $2::text, $3::uuid, $4::text, " +
        "$5::text, $6::bigint, $7::text, $8::text)",
        [
          eventId, idempotencyKey, accountId, eventType,
          creditSource, amount, referenceId, reversesEventId
        ]
      );
      const row = result.rows[0];
      if (!row || !["applied", "duplicate"].includes(row.event_status)) {
        fail("invalid_database_result", "Wallet function returned an invalid result.");
      }
      const balances = walletBalances({
        purchased_credit_units: row.purchased_credit_units,
        promotional_credit_units: row.promotional_credit_units,
        sponsored_credit_units: row.sponsored_credit_units,
        version: 0
      });
      return Object.freeze({
        status: row.event_status,
        balances: Object.freeze({
          purchased: balances.purchased,
          promotional: balances.promotional,
          sponsored: balances.sponsored,
          total: balances.total
        })
      });
    },

    async close() {
      if (closed) return;
      closed = true;
      storeClosed = true;
      await pool.end();
    }
  });
}
