import test from "node:test";
import assert from "node:assert/strict";
import { createPostgresStore } from "../src/postgres-store.mjs";

function fakePool({ schemaReady = true, crossAccountReady = true } = {}) {
  const calls = [];
  const pool = {
    on(event, callback) {
      assert.equal(event, "error");
      this.errorHandler = callback;
    },
    async query(sql, values = []) {
      calls.push({ sql, values });
      if (sql.includes("to_regprocedure")) return {
        rows: [{
          wallet_function_ready: schemaReady,
          cross_account_migration_ready: crossAccountReady
        }]
      };
      if (sql.includes("INSERT INTO linka_private.wallet_accounts")) return { rows: [], rowCount: 1 };
      if (sql.includes("FROM linka_private.wallet_accounts")) {
        return { rows: [{
          purchased_credit_units: "25",
          promotional_credit_units: "10",
          sponsored_credit_units: "5",
          version: "3"
        }] };
      }
      if (sql.includes("apply_wallet_event")) {
        return { rows: [{
          event_status: "applied",
          purchased_credit_units: "80",
          promotional_credit_units: "10",
          sponsored_credit_units: "5"
        }] };
      }
      throw new Error("unexpected_query");
    },
    async end() { this.ended = true; },
    calls
  };
  return pool;
}

const ACCOUNT_ID = "00000000-0000-4000-8000-000000000001";

test("does not create a database connection when DATABASE_URL is absent", () => {
  assert.equal(createPostgresStore({ connectionString: "" }), null);
  assert.equal(createPostgresStore({ connectionString: null }), null);
});

test("checks for the migrated wallet function before reporting readiness", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  assert.deepEqual(await store.checkReady(), {
    status: "ready",
    schemaVersion: "0002_cross_account_integrity"
  });
  assert.match(pool.calls[0].sql, /to_regprocedure/);
  await store.close();
});

test("does not report readiness when the wallet function is missing", async () => {
  const pool = fakePool({ schemaReady: false });
  const store = createPostgresStore({ pool });
  await assert.rejects(store.checkReady(), { code: "database_schema_not_ready" });
  await store.close();
});

test("does not report version 0002 until the ownership migration is recorded", async () => {
  const pool = fakePool({ crossAccountReady: false });
  const store = createPostgresStore({ pool });
  await assert.rejects(store.checkReady(), { code: "database_schema_not_ready" });
  assert.deepEqual(pool.calls[0].values, ["0002_cross_account_integrity"]);
  await store.close();
});

test("creates a wallet idempotently and returns safe integer balances", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  const snapshot = await store.ensureWallet(ACCOUNT_ID);
  assert.deepEqual(snapshot, {
    purchased: 25, promotional: 10, sponsored: 5, total: 40, version: 3
  });
  assert.equal(pool.calls.length, 2);
  assert.match(pool.calls[0].sql, /ON CONFLICT \(account_id\) DO NOTHING/);
  assert.equal(pool.calls[0].values[1], ACCOUNT_ID);
  await store.close();
});

test("applies wallet events using parameterized SQL and returns separated balances", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  const result = await store.applyWalletEvent({
    eventId: "event-1",
    idempotencyKey: "idem-1",
    accountId: ACCOUNT_ID,
    eventType: "PURCHASE_CONFIRMED",
    creditSource: "purchased",
    amount: 100,
    referenceId: "payment-1"
  });
  assert.deepEqual(result, {
    status: "applied",
    balances: { purchased: 80, promotional: 10, sponsored: 5, total: 95 }
  });
  assert.match(pool.calls[0].sql, /apply_wallet_event/);
  assert.doesNotMatch(pool.calls[0].sql, /event-1|payment-1/);
  assert.deepEqual(pool.calls[0].values, [
    "event-1", "idem-1", ACCOUNT_ID, "PURCHASE_CONFIRMED",
    "purchased", 100, "payment-1", null
  ]);
  await store.close();
});

test("rejects invalid account IDs, event types, sources and amounts before querying", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  const base = {
    eventId: "event-1",
    idempotencyKey: "idem-1",
    accountId: ACCOUNT_ID,
    eventType: "PURCHASE_CONFIRMED",
    creditSource: "purchased",
    amount: 10
  };
  await assert.rejects(store.applyWalletEvent({ ...base, accountId: "not-a-uuid" }), {
    code: "invalid_account_id"
  });
  await assert.rejects(store.applyWalletEvent({ ...base, eventType: "BALANCE_SET" }), {
    code: "unsupported_ledger_event"
  });
  await assert.rejects(store.applyWalletEvent({ ...base, creditSource: "unknown" }), {
    code: "invalid_credit_source"
  });
  await assert.rejects(store.applyWalletEvent({ ...base, amount: 0 }), {
    code: "invalid_credit_amount"
  });
  assert.equal(pool.calls.length, 0);
  await store.close();
});

test("closes the pool once and refuses queries after close", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  await store.close();
  await store.close();
  assert.equal(pool.ended, true);
  await assert.rejects(store.checkReady(), { code: "database_store_closed" });
});
