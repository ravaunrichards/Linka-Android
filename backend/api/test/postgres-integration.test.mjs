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


test("PostgreSQL persists account-scoped direct and group conversations with opaque ciphertext", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async () => {
  const pool = new Pool({ connectionString, max: 2, connectionTimeoutMillis: 5_000 });
  const store = createPostgresStore({ pool });
  const accountIds = [randomUUID(), randomUUID(), randomUUID()];
  const suffix = randomUUID();

  try {
    await store.checkReady();
    for (let index = 0; index < accountIds.length; index += 1) {
      await pool.query(
        "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
        "VALUES ($1::uuid, $2::text, $3::text)",
        [accountIds[index], `messaging-${suffix}-${index}@schema-test.invalid`, `Messaging Test ${index}`]
      );
    }

    const [actor, directPeer, groupPeer] = accountIds;
    const direct = await store.createConversation({
      accountId: actor,
      kind: "direct",
      participantAccountIds: [directPeer]
    });
    assert.equal(direct.kind, "direct");
    assert.equal(direct.reused, false);
    assert.deepEqual(new Set(direct.memberAccountIds), new Set([actor, directPeer]));

    const directAgain = await store.createConversation({
      accountId: directPeer,
      kind: "direct",
      participantAccountIds: [actor]
    });
    assert.equal(directAgain.conversationId, direct.conversationId);
    assert.equal(directAgain.reused, true);

    const directCiphertext = Buffer.from("encrypted-direct-message").toString("base64");
    const directMessage = await store.sendInternalMessage({
      accountId: actor,
      conversationId: direct.conversationId,
      ciphertext: directCiphertext,
      encryptionVersion: "LINKA-E2EE-1"
    });
    assert.equal(directMessage.senderAccountId, actor);
    assert.equal(directMessage.ciphertext, directCiphertext);

    const directHistory = await store.listInternalMessages({
      accountId: directPeer,
      conversationId: direct.conversationId,
      limit: 20
    });
    assert.equal(directHistory.length, 1);
    assert.equal(directHistory[0].messageId, directMessage.messageId);
    assert.equal(directHistory[0].ciphertext, directCiphertext);

    await assert.rejects(
      store.listInternalMessages({
        accountId: groupPeer,
        conversationId: direct.conversationId,
        limit: 20
      }),
      error => error.code === "conversation_not_found" && error.statusCode === 404
    );

    const group = await store.createConversation({
      accountId: actor,
      kind: "group",
      participantAccountIds: [directPeer, groupPeer]
    });
    assert.equal(group.kind, "group");
    assert.equal(group.memberAccountIds.length, 3);

    const groupMessage = await store.sendInternalMessage({
      accountId: directPeer,
      conversationId: group.conversationId,
      ciphertext: Buffer.from("encrypted-group-message").toString("base64"),
      encryptionVersion: "LINKA-E2EE-1"
    });
    assert.equal(groupMessage.senderAccountId, directPeer);

    const conversations = await store.listConversations(actor, 50);
    assert.ok(conversations.some(item => item.conversationId === direct.conversationId));
    assert.ok(conversations.some(item => item.conversationId === group.conversationId));
    assert.equal(conversations.find(item => item.conversationId === group.conversationId).memberCount, 3);
  } finally {
    await store.close();
  }
});
