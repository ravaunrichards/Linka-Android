import { createHash, randomUUID } from "node:crypto";
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

function fail(code, message = code) {
  const error = new Error(message);
  error.code = code;
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

    async checkReady() {
      assertOpen();
      const result = await pool.query(
        "SELECT to_regprocedure(" +
        "'linka_private.apply_wallet_event(text,text,uuid,text,text,bigint,text,text)'" +
        ") IS NOT NULL AS wallet_function_ready, " +
        "EXISTS (SELECT 1 FROM linka_private.schema_migrations " +
        "WHERE version = $1::text) AS cross_account_migration_ready",
        ["0002_cross_account_integrity"]
      );
      if (result.rows[0]?.wallet_function_ready !== true ||
          result.rows[0]?.cross_account_migration_ready !== true) {
        fail("database_schema_not_ready");
      }
      return Object.freeze({
        status: "ready",
        schemaVersion: "0002_cross_account_integrity"
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
