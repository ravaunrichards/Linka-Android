import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { Pool } from "pg";

const connectionString = process.env.DATABASE_URL;

async function availablePort() {
  const probe = createServer();
  probe.listen(0, "127.0.0.1");
  await once(probe, "listening");
  const address = probe.address();
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()));
  return address.port;
}

async function waitForHealth(origin, child) {
  const deadline = Date.now() + 12_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error("LINKA API exited before becoming healthy.");
    try {
      const response = await fetch(origin + "/healthz");
      if (response.ok) return;
    } catch {
      // Continue until bounded startup timeout.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("LINKA API did not become healthy within 12 seconds.");
}

test("HTTP internal messaging uses persisted account sessions and enforces conversation membership", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async t => {
  const pool = new Pool({ connectionString, max: 3, connectionTimeoutMillis: 5_000 });
  const accounts = [];
  const suffix = randomUUID();

  try {
    for (let index = 0; index < 3; index += 1) {
      const accountId = randomUUID();
      const deviceId = randomUUID();
      const token = randomBytes(32).toString("base64url");
      const publicKeyDer = Buffer.alloc(64, index + 1);
      const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();

      await pool.query(
        "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
        "VALUES ($1::uuid, $2::text, $3::text)",
        [accountId, `http-chat-${suffix}-${index}@schema-test.invalid`, `HTTP Chat ${index}`]
      );
      await pool.query(
        "INSERT INTO linka_private.user_devices " +
        "(device_id, account_id, device_fingerprint, signing_public_key_der, platform) " +
        "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, 'web')",
        [deviceId, accountId, fingerprint, publicKeyDer]
      );
      await pool.query(
        "INSERT INTO linka_private.auth_sessions " +
        "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), now() + interval '1 hour')",
        [randomUUID(), accountId, deviceId, createHash("sha256").update(token).digest()]
      );
      accounts.push({ accountId, token });
    }

    const port = await availablePort();
    const origin = "http://127.0.0.1:" + port;
    const child = spawn(process.execPath, [
      fileURLToPath(new URL("../src/server.mjs", import.meta.url))
    ], {
      env: {
        ...process.env,
        PORT: String(port),
        DATABASE_URL: connectionString,
        NODE_ENV: "test",
        CORS_ALLOWED_ORIGINS: ""
      },
      stdio: "ignore"
    });

    t.after(async () => {
      if (child.exitCode === null && child.signalCode === null) {
        const exited = once(child, "exit");
        child.kill("SIGTERM");
        await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 2_000))]);
        if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      }
      await pool.end();
    });

    await waitForHealth(origin, child);
    async function request(account, path, method = "GET", body) {
      const response = await fetch(origin + path, {
        method,
        headers: {
          authorization: "Bearer " + account.token,
          ...(body === undefined ? {} : { "content-type": "application/json" })
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      });
      return { status: response.status, body: await response.json() };
    }

    await t.test("requires a real account session", async () => {
      const response = await fetch(origin + "/v1/conversations");
      assert.equal(response.status, 401);
      assert.deepEqual(await response.json(), {
        error: "unauthorized",
        message: "A valid LINKA access token is required."
      });
    });

    await t.test("creates and reuses direct conversations", async () => {
      const created = await request(accounts[0], "/v1/conversations", "POST", {
        kind: "direct",
        participantAccountIds: [accounts[1].accountId]
      });
      assert.equal(created.status, 201);
      assert.equal(created.body.kind, "direct");
      const duplicate = await request(accounts[1], "/v1/conversations", "POST", {
        kind: "direct",
        participantAccountIds: [accounts[0].accountId]
      });
      assert.equal(duplicate.status, 200);
      assert.equal(duplicate.body.conversationId, created.body.conversationId);
    });

    const created = await request(accounts[0], "/v1/conversations", "POST", {
      kind: "direct",
      participantAccountIds: [accounts[1].accountId]
    });
    const conversationId = created.body.conversationId;

    await t.test("sends encrypted payloads and shares history across account sessions", async () => {
      const ciphertext = Buffer.from("ciphertext-only-payload").toString("base64");
      const sent = await request(accounts[0], `/v1/conversations/${conversationId}/messages`, "POST", {
        ciphertext,
        encryptionVersion: "LINKA-E2EE-1"
      });
      assert.equal(sent.status, 201);
      assert.equal(sent.body.message.senderAccountId, accounts[0].accountId);
      assert.equal(sent.body.message.ciphertext, ciphertext);
      assert.equal(JSON.stringify(sent.body).includes("ciphertext-only-payload"), false);

      const history = await request(accounts[1], `/v1/conversations/${conversationId}/messages?limit=10`);
      assert.equal(history.status, 200);
      assert.equal(history.body.messages.length, 1);
      assert.equal(history.body.messages[0].messageId, sent.body.message.messageId);
      assert.equal(history.body.messages[0].ciphertext, ciphertext);
    });

    await t.test("lists conversations with member metadata", async () => {
      const result = await request(accounts[0], "/v1/conversations");
      assert.equal(result.status, 200);
      const conversation = result.body.conversations.find(item => item.conversationId === conversationId);
      assert.ok(conversation);
      assert.equal(conversation.memberCount, 2);
      assert.equal(conversation.members.length, 2);
    });

    await t.test("does not expose history or sending to non-members", async () => {
      const read = await request(accounts[2], `/v1/conversations/${conversationId}/messages`);
      assert.equal(read.status, 404);
      assert.deepEqual(read.body, { error: "conversation_not_found" });
      const send = await request(accounts[2], `/v1/conversations/${conversationId}/messages`, "POST", {
        ciphertext: Buffer.from("not allowed").toString("base64"),
        encryptionVersion: "LINKA-E2EE-1"
      });
      assert.equal(send.status, 404);
      assert.deepEqual(send.body, { error: "conversation_not_found" });
    });

    await t.test("rejects invalid members, unsupported attachments and bad ciphertext", async () => {
      const self = await request(accounts[0], "/v1/conversations", "POST", {
        kind: "direct",
        participantAccountIds: [accounts[0].accountId]
      });
      assert.equal(self.status, 400);
      assert.equal(self.body.error, "invalid_conversation_members");

      const attachment = await request(accounts[0], `/v1/conversations/${conversationId}/messages`, "POST", {
        ciphertext: Buffer.from("encrypted").toString("base64"),
        encryptionVersion: "LINKA-E2EE-1",
        attachmentManifest: [{ assetId: randomUUID() }]
      });
      assert.equal(attachment.status, 501);
      assert.equal(attachment.body.error, "message_attachments_unavailable");

      const invalid = await request(accounts[0], `/v1/conversations/${conversationId}/messages`, "POST", {
        ciphertext: "plaintext-not-base64",
        encryptionVersion: "LINKA-E2EE-1"
      });
      assert.equal(invalid.status, 400);
      assert.equal(invalid.body.error, "invalid_message_ciphertext");
    });
  } catch (error) {
    await pool.end();
    throw error;
  }
});
