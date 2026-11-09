import test from "node:test";
import assert from "node:assert/strict";
import { createPostgresStore } from "../src/postgres-store.mjs";

const ACTOR = "00000000-0000-4000-8000-000000000001";
const MEMBER = "00000000-0000-4000-8000-000000000002";
const OTHER = "00000000-0000-4000-8000-000000000003";
const CONVERSATION = "00000000-0000-4000-8000-000000000010";
const MESSAGE = "00000000-0000-4000-8000-000000000020";
const CREATED_AT = new Date("2026-11-09T12:00:00.000Z");

function messagingPool({ activeAccounts = [ACTOR, MEMBER], existingConversation = null,
  messageInsert = true, membership = true, messages = [] } = {}) {
  const calls = [];
  const pool = {
    on() {},
    async connect() {
      const client = {
        async query(sql, values = []) {
          calls.push({ sql, values });
          if (sql === "BEGIN" || sql === "COMMIT" || sql === "ROLLBACK") return { rows: [], rowCount: 0 };
          if (sql.includes("FROM linka_private.accounts") && sql.includes("ANY($1::uuid[])")) {
            return { rows: activeAccounts.map(account_id => ({ account_id })) };
          }
          if (sql.includes("pg_advisory_xact_lock")) return { rows: [], rowCount: 1 };
          if (sql.includes("FROM linka_private.conversations c") && sql.includes("conversation_kind = 'direct'")) {
            return { rows: existingConversation ? [existingConversation] : [] };
          }
          if (sql.includes("INSERT INTO linka_private.conversations")) {
            return { rows: [{ created_at: CREATED_AT }], rowCount: 1 };
          }
          if (sql.includes("INSERT INTO linka_private.conversation_members")) {
            return { rows: [], rowCount: 2 };
          }
          throw new Error("unexpected_transaction_query");
        },
        release() {}
      };
      return client;
    },
    async query(sql, values = []) {
      calls.push({ sql, values });
      if (sql.includes("INSERT INTO linka_private.internal_messages")) {
        if (!messageInsert) return { rows: [], rowCount: 0 };
        return { rows: [{
          message_id: MESSAGE,
          conversation_id: values[1],
          sender_account_id: values[2],
          ciphertext: Buffer.from(values[3]).toString("base64"),
          encryption_version: values[4],
          attachment_manifest: [],
          sent_at: CREATED_AT
        }], rowCount: 1 };
      }
      if (sql.includes("FROM linka_private.conversation_members") && sql.includes("left_at IS NULL")) {
        return { rows: membership ? [{ "?column?": 1 }] : [] };
      }
      if (sql.includes("FROM linka_private.internal_messages m")) {
        return { rows: messages };
      }
      throw new Error("unexpected_pool_query");
    },
    async end() {},
    calls
  };
  return pool;
}

test("creates a direct conversation using the authenticated account and one existing recipient", async () => {
  const pool = messagingPool();
  const store = createPostgresStore({ pool });
  const result = await store.createConversation({
    accountId: ACTOR,
    kind: "direct",
    participantAccountIds: [MEMBER]
  });

  assert.equal(result.kind, "direct");
  assert.equal(result.conversationId.length, 36);
  assert.deepEqual(result.memberAccountIds, [ACTOR, MEMBER]);
  assert.equal(result.reused, false);
  assert.ok(pool.calls.some(call => call.sql.includes("pg_advisory_xact_lock")));
  assert.ok(pool.calls.some(call => call.sql.includes("INSERT INTO linka_private.conversation_members")));
});

test("reuses an existing direct conversation rather than creating duplicates", async () => {
  const pool = messagingPool({
    existingConversation: { conversation_id: CONVERSATION, created_at: CREATED_AT }
  });
  const store = createPostgresStore({ pool });
  const result = await store.createConversation({
    accountId: ACTOR,
    kind: "direct",
    participantAccountIds: [MEMBER]
  });

  assert.equal(result.conversationId, CONVERSATION);
  assert.equal(result.reused, true);
  assert.equal(pool.calls.some(call => call.sql.includes("INSERT INTO linka_private.conversations")), false);
});

test("creates group membership transactionally and rejects nonexistent accounts", async () => {
  const pool = messagingPool({ activeAccounts: [ACTOR, MEMBER, OTHER] });
  const store = createPostgresStore({ pool });
  const group = await store.createConversation({
    accountId: ACTOR,
    kind: "group",
    participantAccountIds: [MEMBER, OTHER]
  });
  assert.equal(group.kind, "group");
  assert.deepEqual(group.memberAccountIds, [ACTOR, MEMBER, OTHER]);

  const missingPool = messagingPool({ activeAccounts: [ACTOR] });
  const missingStore = createPostgresStore({ pool: missingPool });
  await assert.rejects(
    missingStore.createConversation({
      accountId: ACTOR,
      kind: "group",
      participantAccountIds: [MEMBER]
    }),
    { code: "conversation_member_not_found", statusCode: 404 }
  );
  assert.ok(missingPool.calls.some(call => call.sql === "ROLLBACK"));
});

test("rejects malformed participants before opening a database transaction", async () => {
  const pool = messagingPool();
  const store = createPostgresStore({ pool });
  await assert.rejects(
    store.createConversation({
      accountId: ACTOR,
      kind: "direct",
      participantAccountIds: [ACTOR]
    }),
    { code: "invalid_conversation_members" }
  );
  await assert.rejects(
    store.createConversation({
      accountId: ACTOR,
      kind: "direct",
      participantAccountIds: [MEMBER, OTHER]
    }),
    { code: "invalid_conversation_members" }
  );
  assert.equal(pool.calls.length, 0);
});

test("stores opaque encrypted message bytes and returns the sender from authenticated input", async () => {
  const pool = messagingPool();
  const store = createPostgresStore({ pool });
  const ciphertext = Buffer.from("LINKA encrypted payload").toString("base64");
  const result = await store.sendInternalMessage({
    accountId: ACTOR,
    conversationId: CONVERSATION,
    ciphertext,
    encryptionVersion: "LINKA-E2EE-1"
  });

  assert.equal(result.messageId, MESSAGE);
  assert.equal(result.conversationId, CONVERSATION);
  assert.equal(result.senderAccountId, ACTOR);
  assert.equal(result.ciphertext, ciphertext);
  assert.equal(result.encryptionVersion, "LINKA-E2EE-1");
  const insert = pool.calls.find(call => call.sql.includes("INSERT INTO linka_private.internal_messages"));
  assert.match(insert.sql, /EXISTS (SELECT 1 FROM linka_private.conversation_members/);
  assert.equal(insert.values[2], ACTOR);
  assert.equal(insert.values[4], "LINKA-E2EE-1");
});

test("does not insert a message for an account outside the conversation", async () => {
  const pool = messagingPool({ messageInsert: false });
  const store = createPostgresStore({ pool });
  await assert.rejects(
    store.sendInternalMessage({
      accountId: OTHER,
      conversationId: CONVERSATION,
      ciphertext: Buffer.from("ciphertext").toString("base64"),
      encryptionVersion: "LINKA-E2EE-1"
    }),
    { code: "conversation_not_found", statusCode: 404 }
  );
});

test("rejects plaintext-invalid payload formats, unsupported attachments and oversized messages", async () => {
  const pool = messagingPool();
  const store = createPostgresStore({ pool });
  await assert.rejects(
    store.sendInternalMessage({
      accountId: ACTOR, conversationId: CONVERSATION,
      ciphertext: "not base64!", encryptionVersion: "LINKA-E2EE-1"
    }),
    { code: "invalid_message_ciphertext" }
  );
  await assert.rejects(
    store.sendInternalMessage({
      accountId: ACTOR, conversationId: CONVERSATION,
      ciphertext: Buffer.alloc(12 * 1024 + 1).toString("base64"),
      encryptionVersion: "LINKA-E2EE-1"
    }),
    { code: "invalid_message_ciphertext" }
  );
  await assert.rejects(
    store.sendInternalMessage({
      accountId: ACTOR, conversationId: CONVERSATION,
      ciphertext: Buffer.from("ciphertext").toString("base64"),
      encryptionVersion: "LINKA-E2EE-1",
      attachmentManifest: [{ assetId: "unverified" }]
    }),
    { code: "message_attachments_unavailable", statusCode: 501 }
  );
  assert.equal(pool.calls.length, 0);
});

test("requires active conversation membership before reading messages", async () => {
  const pool = messagingPool({ membership: false });
  const store = createPostgresStore({ pool });
  await assert.rejects(
    store.listInternalMessages({
      accountId: OTHER, conversationId: CONVERSATION
    }),
    { code: "conversation_not_found", statusCode: 404 }
  );
  assert.equal(pool.calls.filter(call => call.sql.includes("FROM linka_private.internal_messages m")).length, 0);
});

test("paginates bounded message history and returns ciphertext without plaintext", async () => {
  const pool = messagingPool({
    messages: [{
      message_id: MESSAGE,
      conversation_id: CONVERSATION,
      sender_account_id: MEMBER,
      ciphertext: Buffer.from("encrypted").toString("base64"),
      encryption_version: "LINKA-E2EE-1",
      attachment_manifest: [],
      sent_at: CREATED_AT
    }]
  });
  const store = createPostgresStore({ pool });
  const result = await store.listInternalMessages({
    accountId: ACTOR, conversationId: CONVERSATION, limit: 25
  });

  assert.equal(result.length, 1);
  assert.equal(result[0].ciphertext, Buffer.from("encrypted").toString("base64"));
  assert.equal(result[0].senderAccountId, MEMBER);
  assert.equal(pool.calls[1].values[1], 25);
  await assert.rejects(
    store.listInternalMessages({ accountId: ACTOR, conversationId: CONVERSATION, limit: 101 }),
    { code: "invalid_page_limit" }
  );
});
