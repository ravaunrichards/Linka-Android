import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign as signSignature } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { Pool } from "pg";

const connectionString = process.env.DATABASE_URL;

function p256PublicKey() {
  return generateKeyPairSync("ec", { namedCurve: "prime256v1" }).publicKey.export({
    format: "der",
    type: "spki"
  });
}

function signEncryptionKey(device, publicKeyDer) {
  const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
  const statement = Buffer.from([
    "LINKA-MESSAGING-KEY-V1",
    device.deviceId,
    "ECDH-P256",
    fingerprint
  ].join("\n"), "utf8");
  return signSignature("sha256", statement, device.signingPrivateKey).toString("base64");
}

function makeEnvelope(deviceId, keyVersion) {
  return {
    recipientDeviceId: deviceId,
    recipientKeyVersion: keyVersion,
    ephemeralPublicKeyBase64: p256PublicKey().toString("base64"),
    hkdfSaltBase64: randomBytes(16).toString("base64"),
    wrapNonceBase64: randomBytes(12).toString("base64"),
    wrappedContentKeyBase64: randomBytes(48).toString("base64")
  };
}

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
      // Continue until the bounded startup timeout.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("LINKA API did not become healthy within 12 seconds.");
}

test("HTTP messaging registers device keys and returns each E2EE key envelope only to its recipient", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async t => {
  const pool = new Pool({ connectionString, max: 4, connectionTimeoutMillis: 5_000 });
  const suffix = randomUUID();
  const accounts = [];
  const devices = [];

  try {
    await pool.query(
      "SELECT 1 FROM linka_private.schema_migrations WHERE version = $1::text",
      ["0003_messaging_device_encryption"]
    );

    for (let accountIndex = 0; accountIndex < 3; accountIndex += 1) {
      const accountId = randomUUID();
      await pool.query(
        "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
        "VALUES ($1::uuid, $2::text, $3::text)",
        [accountId, `e2ee-${suffix}-${accountIndex}@schema-test.invalid`, `E2EE Account ${accountIndex}`]
      );
      accounts.push({ accountId });
    }

    const deviceRows = [
      { accountIndex: 0, name: "Sender browser" },
      { accountIndex: 0, name: "Sender second device" },
      { accountIndex: 1, name: "Recipient device" },
      { accountIndex: 2, name: "Non-member device" }
    ];
    for (const [index, item] of deviceRows.entries()) {
      const account = accounts[item.accountIndex];
      const deviceId = randomUUID();
      const signingPair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
      const signingPublicKey = signingPair.publicKey.export({ format: "der", type: "spki" });
      const fingerprint = createHash("sha256").update(signingPublicKey).digest("hex").toUpperCase();
      const token = randomBytes(32).toString("base64url");
      await pool.query(
        "INSERT INTO linka_private.user_devices " +
        "(device_id, account_id, device_fingerprint, signing_public_key_der, platform, device_name) " +
        "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, 'web', $5::text)",
        [deviceId, account.accountId, fingerprint, signingPublicKey, item.name]
      );
      await pool.query(
        "INSERT INTO linka_private.auth_sessions " +
        "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), now() + interval '1 hour')",
        [randomUUID(), account.accountId, deviceId, createHash("sha256").update(token).digest()]
      );
      devices.push({
        deviceId,
        accountId: account.accountId,
        token,
        signingPrivateKey: signingPair.privateKey,
        encryptionPublicKey: null,
        keyVersion: null
      });
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
    async function request(device, path, method = "GET", body) {
      const response = await fetch(origin + path, {
        method,
        headers: {
          ...(device ? { authorization: "Bearer " + device.token } : {}),
          ...(body === undefined ? {} : { "content-type": "application/json" })
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      });
      return { status: response.status, body: await response.json() };
    }

    await t.test("registers a device-bound P-256 ECDH public key", async () => {
      for (const device of devices.slice(0, 3)) {
        device.encryptionPublicKey = p256PublicKey();
        const result = await request(device, "/v1/me/encryption-key", "PUT", {
          algorithm: "ECDH-P256",
          publicKeyBase64: device.encryptionPublicKey.toString("base64"),
          signatureBase64: signEncryptionKey(device, device.encryptionPublicKey)
        });
        assert.ok([200, 201].includes(result.status));
        assert.equal(result.body.deviceId, device.deviceId);
        assert.equal(result.body.algorithm, "ECDH-P256");
        assert.equal(result.body.keyVersion, 1);
        device.keyVersion = 1;
      }

      const invalid = await request(devices[3], "/v1/me/encryption-key", "PUT", {
        algorithm: "RSA-OAEP",
        publicKeyBase64: p256PublicKey().toString("base64")
      });
      assert.equal(invalid.status, 400);
      assert.equal(invalid.body.error, "invalid_encryption_key");
    });

    const conversation = await request(devices[0], "/v1/conversations", "POST", {
      kind: "direct",
      participantAccountIds: [accounts[1].accountId]
    });
    assert.equal(conversation.status, 201);
    const conversationId = conversation.body.conversationId;

    await t.test("lists only active conversation devices and enforces membership", async () => {
      const result = await request(
        devices[0],
        `/v1/conversations/${conversationId}/encryption-devices`
      );
      assert.equal(result.status, 200);
      assert.equal(result.body.devices.length, 3);
      assert.ok(result.body.devices.every(device => device.algorithm === "ECDH-P256"));
      assert.equal(result.body.devices.find(device => device.deviceId === devices[2].deviceId).accountId, accounts[1].accountId);

      const outsider = await request(
        devices[3],
        `/v1/conversations/${conversationId}/encryption-devices`
      );
      assert.equal(outsider.status, 404);
      assert.deepEqual(outsider.body, { error: "conversation_not_found" });
    });

    const ciphertext = randomBytes(96).toString("base64");
    const envelopeBody = () => devices.slice(0, 3).map(device =>
      makeEnvelope(device.deviceId, device.keyVersion)
    );

    let sentMessageId;
    await t.test("requires a current encrypted key envelope for every active device", async () => {
      const missing = await request(
        devices[0],
        `/v1/conversations/${conversationId}/messages`,
        "POST",
        {
          ciphertext,
          encryptionVersion: "LINKA-E2EE-2",
          keyEnvelopes: envelopeBody().slice(0, 2)
        }
      );
      assert.equal(missing.status, 400);
      assert.equal(missing.body.error, "invalid_message_key_envelopes");

      const sent = await request(
        devices[0],
        `/v1/conversations/${conversationId}/messages`,
        "POST",
        {
          ciphertext,
          encryptionVersion: "LINKA-E2EE-2",
          keyEnvelopes: envelopeBody()
        }
      );
      assert.equal(sent.status, 201);
      assert.equal(sent.body.message.encryptionVersion, "LINKA-E2EE-2");
      assert.equal(sent.body.message.senderAccountId, accounts[0].accountId);
      sentMessageId = sent.body.message.messageId;
    });

    await t.test("returns only the envelope for the authenticated device", async () => {
      for (const device of devices.slice(0, 3)) {
        const history = await request(
          device,
          `/v1/conversations/${conversationId}/messages`
        );
        assert.equal(history.status, 200);
        assert.equal(history.body.messages.length, 1);
        const message = history.body.messages[0];
        assert.equal(message.messageId, sentMessageId);
        assert.equal(message.keyEnvelope.recipientDeviceId, device.deviceId);
        assert.equal(message.keyEnvelope.recipientKeyVersion, device.keyVersion);
        assert.ok(message.keyEnvelope.ephemeralPublicKeyBase64);
        assert.ok(message.keyEnvelope.wrappedContentKeyBase64);
      }
      const outsider = await request(
        devices[3],
        `/v1/conversations/${conversationId}/messages`
      );
      assert.equal(outsider.status, 404);
    });

    await t.test("records per-device delivery and read receipts without exposing them to recipients", async () => {
      const delivered = await request(
        devices[2],
        `/v1/conversations/${conversationId}/receipts`,
        "POST",
        { messageIds: [sentMessageId], state: "delivered" }
      );
      assert.equal(delivered.status, 200);
      assert.equal(delivered.body.state, "delivered");
      assert.equal(delivered.body.acknowledged, 1);
      assert.deepEqual(delivered.body.messageIds, [sentMessageId]);

      const read = await request(
        devices[2],
        `/v1/conversations/${conversationId}/receipts`,
        "POST",
        { messageIds: [sentMessageId], state: "read" }
      );
      assert.equal(read.status, 200);
      assert.equal(read.body.state, "read");
      assert.equal(read.body.acknowledged, 1);

      const senderHistory = await request(
        devices[0],
        `/v1/conversations/${conversationId}/messages`
      );
      const senderMessage = senderHistory.body.messages[0];
      assert.deepEqual(senderMessage.receiptSummary, {
        recipientDevices: 1,
        deliveredDevices: 1,
        readDevices: 1
      });

      const recipientHistory = await request(
        devices[2],
        `/v1/conversations/${conversationId}/messages`
      );
      assert.equal(
        Object.hasOwn(recipientHistory.body.messages[0], "receiptSummary"),
        false,
        "recipient devices must not receive sender-only aggregate receipt metadata"
      );

      const senderCannotAcknowledgeTheirOwnMessage = await request(
        devices[0],
        `/v1/conversations/${conversationId}/receipts`,
        "POST",
        { messageIds: [sentMessageId], state: "read" }
      );
      assert.equal(senderCannotAcknowledgeTheirOwnMessage.status, 200);
      assert.equal(senderCannotAcknowledgeTheirOwnMessage.body.acknowledged, 0);

      const outsider = await request(
        devices[3],
        `/v1/conversations/${conversationId}/receipts`,
        "POST",
        { messageIds: [sentMessageId], state: "read" }
      );
      assert.equal(outsider.status, 404);
      assert.deepEqual(outsider.body, { error: "conversation_not_found" });
    });

    await t.test("rejects stale envelopes after a device rotates its ECDH key", async () => {
      const rotated = p256PublicKey();
      const response = await request(devices[1], "/v1/me/encryption-key", "PUT", {
        algorithm: "ECDH-P256",
        publicKeyBase64: rotated.toString("base64"),
        signatureBase64: signEncryptionKey(devices[1], rotated)
      });
      assert.equal(response.status, 201);
      assert.equal(response.body.keyVersion, 2);

      const stale = await request(
        devices[0],
        `/v1/conversations/${conversationId}/messages`,
        "POST",
        {
          ciphertext,
          encryptionVersion: "LINKA-E2EE-2",
          keyEnvelopes: envelopeBody()
        }
      );
      assert.equal(stale.status, 409);
      assert.equal(stale.body.error, "conversation_encryption_key_changed");
    });

    await t.test("refuses sends until a newly active device has registered an encryption key", async () => {
      const extraDeviceId = randomUUID();
      const extraSigningPair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
      const signingPublicKey = extraSigningPair.publicKey.export({ format: "der", type: "spki" });
      const token = randomBytes(32).toString("base64url");
      const fingerprint = createHash("sha256").update(signingPublicKey).digest("hex").toUpperCase();
      await pool.query(
        "INSERT INTO linka_private.user_devices " +
        "(device_id, account_id, device_fingerprint, signing_public_key_der, platform) " +
        "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, 'web')",
        [extraDeviceId, accounts[0].accountId, fingerprint, signingPublicKey]
      );
      await pool.query(
        "INSERT INTO linka_private.auth_sessions " +
        "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), now() + interval '1 hour')",
        [randomUUID(), accounts[0].accountId, extraDeviceId, createHash("sha256").update(token).digest()]
      );
      const missingKeyDevice = { deviceId: extraDeviceId, accountId: accounts[0].accountId, token };
      const directory = await request(
        devices[0],
        `/v1/conversations/${conversationId}/encryption-devices`
      );
      assert.equal(directory.status, 200);
      assert.ok(directory.body.devices.some(item => item.deviceId === extraDeviceId && item.keyVersion === null));

      const send = await request(
        devices[0],
        `/v1/conversations/${conversationId}/messages`,
        "POST",
        {
          ciphertext,
          encryptionVersion: "LINKA-E2EE-2",
          keyEnvelopes: envelopeBody()
        }
      );
      assert.equal(send.status, 409);
      assert.equal(send.body.error, "conversation_encryption_keys_incomplete");
      assert.ok(missingKeyDevice.deviceId);
    });
  } catch (error) {
    await pool.end().catch(() => {});
    throw error;
  }
});
