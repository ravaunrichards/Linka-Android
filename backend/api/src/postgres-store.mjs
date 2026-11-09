import { randomUUID } from "node:crypto";
import { Pool } from "pg";

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
    if (closed) fail("database_store_closed");
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

  return Object.freeze({
    configured: true,

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
      await pool.end();
    }
  });
}
