import test from "node:test";
import assert from "node:assert/strict";
import { createPostgresStore } from "../src/postgres-store.mjs";

const ACCOUNT = "00000000-0000-4000-8000-000000000001";
const OTHER = "00000000-0000-4000-8000-000000000002";

function ledgerRows() {
  return [
    {
      event_id: "event-3",
      event_type: "CALL_USAGE",
      credit_source: "purchased",
      amount_credit_units: "12",
      reference_id: "call-3",
      reverses_event_id: null,
      created_at: new Date("2026-11-09T12:03:00.000Z")
    },
    {
      event_id: "event-2",
      event_type: "PURCHASE_CONFIRMED",
      credit_source: "purchased",
      amount_credit_units: "100",
      reference_id: "payment-2",
      reverses_event_id: null,
      created_at: new Date("2026-11-09T12:02:00.000Z")
    },
    {
      event_id: "event-1",
      event_type: "PROMOTIONAL_GRANT",
      credit_source: "promotional",
      amount_credit_units: "25",
      reference_id: null,
      reverses_event_id: null,
      created_at: new Date("2026-11-09T12:01:00.000Z")
    }
  ];
}

function walletPool(rows = ledgerRows()) {
  const calls = [];
  return {
    calls,
    on() {},
    async query(sql, values = []) {
      calls.push({ sql, values });
      if (sql.includes("FROM linka_private.wallet_ledger_events")) {
        return { rows: rows.slice(0, values[1]) };
      }
      if (sql.includes("FROM linka_private.wallet_accounts")) {
        return { rows: [{
          purchased_credit_units: "88",
          promotional_credit_units: "25",
          sponsored_credit_units: "5",
          version: "3"
        }] };
      }
      throw new Error("unexpected_wallet_read_query");
    },
    async end() {}
  };
}

test("returns only the authenticated account's separate read-only wallet balances", async () => {
  const pool = walletPool();
  const store = createPostgresStore({ pool });

  const snapshot = await store.getWalletSnapshot(ACCOUNT);

  assert.deepEqual(snapshot, {
    purchased: 88,
    promotional: 25,
    sponsored: 5,
    total: 118,
    version: 3
  });
  assert.deepEqual(pool.calls[0].values, [ACCOUNT]);
  assert.match(pool.calls[0].sql, /WHERE account_id = \$1::uuid/);
  assert.doesNotMatch(pool.calls[0].sql, /UPDATE|INSERT|DELETE/i);
});

test("returns bounded ledger pages with a stable timestamp plus event-ID cursor", async () => {
  const pool = walletPool();
  const store = createPostgresStore({ pool });

  const page = await store.listWalletLedgerEvents({
    accountId: ACCOUNT,
    limit: 2
  });

  assert.equal(page.events.length, 2);
  assert.equal(page.events[0].eventId, "event-3");
  assert.equal(page.events[0].amountCreditUnits, 12);
  assert.equal(page.events[1].eventId, "event-2");
  assert.deepEqual(page.nextCursor, {
    before: "2026-11-09T12:02:00.000Z",
    beforeEventId: "event-2"
  });
  assert.deepEqual(pool.calls[0].values, [ACCOUNT, 3, null, null]);
  assert.match(pool.calls[0].sql, /ORDER BY created_at DESC, event_id DESC/);
  assert.match(pool.calls[0].sql, /account_id = \$1::uuid/);
  assert.doesNotMatch(pool.calls[0].sql, /provider_reference|metadata/i);
});

test("accepts the stable cursor for the next page without exposing another account", async () => {
  const rows = ledgerRows().slice(2);
  const pool = walletPool(rows);
  const store = createPostgresStore({ pool });

  const page = await store.listWalletLedgerEvents({
    accountId: ACCOUNT,
    limit: 2,
    before: "2026-11-09T12:02:00.000Z",
    beforeEventId: "event-2"
  });

  assert.equal(page.events.length, 1);
  assert.equal(page.events[0].eventId, "event-1");
  assert.equal(page.nextCursor, null);
  assert.deepEqual(pool.calls[0].values, [
    ACCOUNT, 3, new Date("2026-11-09T12:02:00.000Z"), "event-2"
  ]);
  assert.notEqual(pool.calls[0].values[0], OTHER);
});

test("rejects invalid account IDs, limits and incomplete or malformed cursors before querying", async () => {
  const pool = walletPool();
  const store = createPostgresStore({ pool });

  await assert.rejects(
    store.listWalletLedgerEvents({ accountId: "not-a-uuid" }),
    { code: "invalid_account_id" }
  );
  await assert.rejects(
    store.listWalletLedgerEvents({ accountId: ACCOUNT, limit: 101 }),
    { code: "invalid_page_limit" }
  );
  await assert.rejects(
    store.listWalletLedgerEvents({ accountId: ACCOUNT, before: "2026-11-09T12:00:00Z" }),
    { code: "invalid_wallet_cursor" }
  );
  await assert.rejects(
    store.listWalletLedgerEvents({
      accountId: ACCOUNT,
      before: "not-a-date",
      beforeEventId: "event-1"
    }),
    { code: "invalid_wallet_cursor" }
  );
  assert.equal(pool.calls.length, 0);
});
