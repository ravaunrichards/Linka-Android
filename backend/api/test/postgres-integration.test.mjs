import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { createPostgresStore } from "../src/postgres-store.mjs";

const connectionString = process.env.DATABASE_URL;

test("PostgreSQL store performs durable wallet operations against the migrated schema", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async () => {
  const pool = new Pool({ connectionString, max: 2, connectionTimeoutMillis: 5_000 });
  const store = createPostgresStore({ pool });
  const accountId = randomUUID();

  try {
    await store.checkReady();

    // This account exists only in the disposable GitHub Actions PostgreSQL service.
    await pool.query(
      "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
      "VALUES ($1::uuid, $2::text, 'PostgreSQL Integration Test')",
      [accountId, "ci-" + accountId + "@schema-test.invalid"]
    );

    const initial = await store.ensureWallet(accountId);
    assert.deepEqual(initial, {
      purchased: 0, promotional: 0, sponsored: 0, total: 0, version: 0
    });

    const grant = {
      eventId: "purchase-" + accountId,
      idempotencyKey: "purchase-key-" + accountId,
      accountId,
      eventType: "PURCHASE_CONFIRMED",
      creditSource: "purchased",
      amount: 100,
      referenceId: "payment-" + accountId
    };
    const first = await store.applyWalletEvent(grant);
    assert.equal(first.status, "applied");
    assert.equal(first.balances.purchased, 100);

    const duplicate = await store.applyWalletEvent(grant);
    assert.equal(duplicate.status, "duplicate");
    assert.equal(duplicate.balances.purchased, 100);

    const usage = await store.applyWalletEvent({
      eventId: "usage-" + accountId,
      idempotencyKey: "usage-key-" + accountId,
      accountId,
      eventType: "CALL_USAGE",
      creditSource: "purchased",
      amount: 30,
      referenceId: "call-" + accountId
    });
    assert.equal(usage.balances.purchased, 70);

    const refund = await store.applyWalletEvent({
      eventId: "refund-" + accountId,
      idempotencyKey: "refund-key-" + accountId,
      accountId,
      eventType: "REFUND",
      creditSource: "purchased",
      amount: 10,
      referenceId: "refund-reference-" + accountId,
      reversesEventId: "usage-" + accountId
    });
    assert.equal(refund.balances.purchased, 80);

    const snapshot = await store.getWalletSnapshot(accountId);
    assert.deepEqual(snapshot, {
      purchased: 80, promotional: 0, sponsored: 0, total: 80, version: 3
    });

    await assert.rejects(store.applyWalletEvent({
      eventId: "overspend-" + accountId,
      idempotencyKey: "overspend-key-" + accountId,
      accountId,
      eventType: "CALL_USAGE",
      creditSource: "purchased",
      amount: 1_000
    }), error => error.code === "P0001" && error.message === "insufficient_credits");
  } finally {
    await store.close();
  }
});
