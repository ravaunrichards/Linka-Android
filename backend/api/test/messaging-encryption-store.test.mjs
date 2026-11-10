import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, sign as signSignature } from "node:crypto";
import { createPostgresStore } from "../src/postgres-store.mjs";

const ACTOR = "00000000-0000-4000-8000-000000000001";
const MEMBER = "00000000-0000-4000-8000-000000000002";
const SENDER_DEVICE = "00000000-0000-4000-8000-000000000011";
const SECOND_DEVICE = "00000000-0000-4000-8000-000000000012";
const MEMBER_DEVICE = "00000000-0000-4000-8000-000000000013";
const CONVERSATION = "00000000-0000-4000-8000-000000000021";
const MESSAGE = "00000000-0000-4000-8000-000000000031";
const CREATED_AT = new Date("2026-11-09T17:00:00.000Z");
const DEVICE_SIGNING_PAIR = generateKeyPairSync("ec", { namedCurve: "prime256v1" });

function signedRegistrationInput(publicKeyDer = ecdhPublicKey(), {
  signer = DEVICE_SIGNING_PAIR,
  algorithm = "ECDH-P256"
} = {}) {
  const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
  const statement = Buffer.from([
    "LINKA-MESSAGING-KEY-V1",
    SENDER_DEVICE,
    algorithm,
    fingerprint
  ].join("\n"), "utf8");
  return {
    accountId: ACTOR,
    deviceId: SENDER_DEVICE,
    algorithm,
    publicKeyDer,
    proofSignature: signSignature("sha256", statement, signer.privateKey)
  };
}

function ecdhPublicKey() {
  return generateKeyPairSync("ec", { namedCurve: "prime256v1" }).publicKey.export({
    format: "der",
    type: "spki"
  });
}

function base64(bytes) {
  return Buffer.from(bytes).toString("base64");
}

function envelope(recipientDeviceId, recipientKeyVersion, publicKeyDer = ecdhPublicKey()) {
  return {
    recipientDeviceId,
    recipientKeyVersion,
    ephemeralPublicKeyBase64: base64(publicKeyDer),
    hkdfSaltBase64: base64(Buffer.alloc(16, 7)),
    wrapNonceBase64: base64(Buffer.alloc(12, 8)),
    wrappedContentKeyBase64: base64(Buffer.alloc(48, 9))
  };
}

function fakePool({ deviceExists = true, currentKey = null, latestVersion = 0,
  conversationDevices = null, historyMember = true } = {}) {
  const calls = [];
  const pool = {
    calls,
    on() {},
    async connect() {
      return {
        async query(sql, values = []) {
          calls.push({ sql, values });
          if (["BEGIN", "COMMIT", "ROLLBACK"].includes(sql)) return { rows: [], rowCount: 0 };
          if (sql.includes("FROM linka_private.user_devices") &&
              sql.includes("revoked_at IS NULL") && sql.includes("FOR UPDATE")) {
            return { rows: deviceExists ? [{
              device_id: values[0],
              signing_public_key_der: DEVICE_SIGNING_PAIR.publicKey.export({ format: "der", type: "spki" })
            }] : [] };
          }
          if (sql.includes("FROM linka_private.device_encryption_keys") &&
              sql.includes("retired_at IS NULL FOR UPDATE")) {
            return { rows: currentKey ? [currentKey] : [] };
          }
          if (sql.includes("COALESCE(MAX(key_version)")) {
            return { rows: [{ latest_version: String(latestVersion) }] };
          }
          if (sql.includes("UPDATE linka_private.device_encryption_keys")) {
            return { rows: [], rowCount: 1 };
          }
          if (sql.includes("INSERT INTO linka_private.device_encryption_keys")) {
            return { rows: [], rowCount: 1 };
          }
          if (sql.includes("FROM linka_private.conversation_members m") &&
              sql.includes("JOIN linka_private.user_devices d") &&
              sql.includes("public_key_base64")) {
            return { rows: conversationDevices ?? [] };
          }
          if (sql.includes("FOR SHARE OF m, d")) {
            return { rows: conversationDevices ?? [
              { device_id: SENDER_DEVICE, account_id: ACTOR, key_version: 1 },
              { device_id: SECOND_DEVICE, account_id: ACTOR, key_version: 2 },
              { device_id: MEMBER_DEVICE, account_id: MEMBER, key_version: 1 }
            ] };
          }
          if (sql.includes("INSERT INTO linka_private.internal_messages")) {
            return { rows: [{
              message_id: values[0],
              conversation_id: values[1],
              sender_account_id: values[2],
              ciphertext: Buffer.from(values[3]).toString("base64"),
              encryption_version: values[4],
              attachment_manifest: [],
              sent_at: CREATED_AT
            }], rowCount: 1 };
          }
          if (sql.includes("INSERT INTO linka_private.internal_message_key_envelopes")) {
            return { rows: [], rowCount: 1 };
          }
          if (sql.includes("INSERT INTO linka_private.internal_message_device_receipts")) {
            return { rows: [], rowCount: 1 };
          }
          throw new Error("unexpected_transaction_query");
        },
        release() {}
      };
    },
    async query(sql, values = []) {
      calls.push({ sql, values });
      if (sql.includes("FROM linka_private.conversation_members mine")) {
        return { rows: historyMember ? (conversationDevices ?? [
          {
            device_id: SENDER_DEVICE,
            account_id: ACTOR,
            display_name: "Actor",
            key_version: 1,
            public_key_base64: base64(ecdhPublicKey()),
            public_key_fingerprint: "A".repeat(64)
          },
          {
            device_id: MEMBER_DEVICE,
            account_id: MEMBER,
            display_name: "Member",
            key_version: null,
            public_key_base64: null,
            public_key_fingerprint: null
          }
        ]) : [] };
      }
      throw new Error("unexpected_pool_query");
    },
    async end() {}
  };
  return pool;
}

test("registers a valid P-256 encryption key for the authenticated active device", async () => {
  const publicKeyDer = ecdhPublicKey();
  const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
  const pool = fakePool({ latestVersion: 0 });
  const store = createPostgresStore({ pool });

  const result = await store.registerDeviceEncryptionKey(
    signedRegistrationInput(publicKeyDer)
  );

  assert.deepEqual(result, {
    deviceId: SENDER_DEVICE,
    algorithm: "ECDH-P256",
    keyVersion: 1,
    publicKeyFingerprint: fingerprint,
    reused: false
  });
  const insert = pool.calls.find(call => call.sql.includes("INSERT INTO linka_private.device_encryption_keys"));
  assert.deepEqual(insert.values.slice(0, 3), [SENDER_DEVICE, ACTOR, 1]);
  assert.deepEqual(insert.values[3], publicKeyDer);
  assert.equal(insert.values[4], fingerprint);
});

test("reuses the same current encryption key without creating another version", async () => {
  const publicKeyDer = ecdhPublicKey();
  const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
  const pool = fakePool({
    currentKey: { key_version: 3, public_key_fingerprint: fingerprint },
    latestVersion: 3
  });
  const store = createPostgresStore({ pool });

  const result = await store.registerDeviceEncryptionKey(
    signedRegistrationInput(publicKeyDer)
  );

  assert.equal(result.keyVersion, 3);
  assert.equal(result.reused, true);
  assert.equal(pool.calls.some(call => call.sql.includes("INSERT INTO linka_private.device_encryption_keys")), false);
});

test("rotates a changed P-256 key to a new retained version", async () => {
  const publicKeyDer = ecdhPublicKey();
  const pool = fakePool({
    currentKey: { key_version: 3, public_key_fingerprint: "B".repeat(64) },
    latestVersion: 3
  });
  const store = createPostgresStore({ pool });

  const result = await store.registerDeviceEncryptionKey(
    signedRegistrationInput(publicKeyDer)
  );

  assert.equal(result.keyVersion, 4);
  assert.equal(result.reused, false);
  assert.ok(pool.calls.some(call => call.sql.includes("SET retired_at = now()")));
});

test("rejects non-P256 keys, malformed DER and inactive devices", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  await assert.rejects(
    store.registerDeviceEncryptionKey({
      accountId: ACTOR,
      deviceId: SENDER_DEVICE,
      algorithm: "RSA-OAEP",
      publicKeyDer: ecdhPublicKey()
    }),
    { code: "invalid_encryption_key" }
  );
  await assert.rejects(
    store.registerDeviceEncryptionKey({
      accountId: ACTOR,
      deviceId: SENDER_DEVICE,
      algorithm: "ECDH-P256",
      publicKeyDer: Buffer.from("not a key")
    }),
    { code: "invalid_encryption_key" }
  );
  const inactive = createPostgresStore({ pool: fakePool({ deviceExists: false }) });
  await assert.rejects(
    inactive.registerDeviceEncryptionKey(
      signedRegistrationInput(ecdhPublicKey())
    ),
    { code: "encryption_key_not_found", statusCode: 404 }
  );
});

test("rejects an ECDH key proof signed by a different device identity", async () => {
  const publicKeyDer = ecdhPublicKey();
  const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();
  const otherSigner = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const statement = Buffer.from([
    "LINKA-MESSAGING-KEY-V1", SENDER_DEVICE, "ECDH-P256", fingerprint
  ].join("\n"), "utf8");
  const store = createPostgresStore({ pool: fakePool() });
  await assert.rejects(
    store.registerDeviceEncryptionKey({
      ...signedRegistrationInput(publicKeyDer),
      proofSignature: signSignature("sha256", statement, otherSigner.privateKey)
    }),
    { code: "invalid_encryption_key_proof" }
  );
});

test("lists active conversation device public keys and exposes missing-key devices explicitly", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });

  const devices = await store.listConversationEncryptionDevices({
    accountId: ACTOR,
    conversationId: CONVERSATION
  });

  assert.equal(devices.length, 2);
  assert.equal(devices[0].deviceId, SENDER_DEVICE);
  assert.equal(devices[0].algorithm, "ECDH-P256");
  assert.equal(devices[0].keyVersion, 1);
  assert.equal(devices[1].deviceId, MEMBER_DEVICE);
  assert.equal(devices[1].keyVersion, null);
  assert.equal(devices[1].publicKeyBase64, null);
  assert.match(pool.calls[0].sql, /mine\.account_id = \$2::uuid/);
  assert.match(pool.calls[0].sql, /d\.revoked_at IS NULL/);
});

test("does not disclose the device key directory to a non-member", async () => {
  const pool = fakePool({ historyMember: false });
  const store = createPostgresStore({ pool });

  await assert.rejects(
    store.listConversationEncryptionDevices({ accountId: MEMBER, conversationId: CONVERSATION }),
    { code: "conversation_not_found", statusCode: 404 }
  );
});

test("stores an E2EE-2 message only when every active conversation device has a current envelope", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  const ciphertext = base64(Buffer.from("iv+ciphertext"));
  const envelopes = [
    envelope(SENDER_DEVICE, 1),
    envelope(SECOND_DEVICE, 2),
    envelope(MEMBER_DEVICE, 1)
  ];

  const message = await store.sendInternalMessage({
    accountId: ACTOR,
    senderDeviceId: SENDER_DEVICE,
    conversationId: CONVERSATION,
    ciphertext,
    encryptionVersion: "LINKA-E2EE-2",
    keyEnvelopes: envelopes
  });

  assert.equal(message.encryptionVersion, "LINKA-E2EE-2");
  assert.equal(message.senderAccountId, ACTOR);
  assert.equal(pool.calls.filter(call => call.sql.includes("INSERT INTO linka_private.internal_message_key_envelopes")).length, 3);
  assert.equal(pool.calls.filter(call => call.sql.includes("INSERT INTO linka_private.internal_message_device_receipts")).length, 3);
  assert.ok(pool.calls.some(call => call.sql === "COMMIT"));
});

test("rejects missing device envelopes, stale key versions and unregistered conversation devices", async () => {
  const ciphertext = base64(Buffer.from("iv+ciphertext"));
  const valid = [
    envelope(SENDER_DEVICE, 1),
    envelope(SECOND_DEVICE, 2),
    envelope(MEMBER_DEVICE, 1)
  ];
  const missing = createPostgresStore({ pool: fakePool() });
  await assert.rejects(
    missing.sendInternalMessage({
      accountId: ACTOR, senderDeviceId: SENDER_DEVICE, conversationId: CONVERSATION,
      ciphertext, encryptionVersion: "LINKA-E2EE-2", keyEnvelopes: valid.slice(0, 2)
    }),
    { code: "invalid_message_key_envelopes" }
  );

  const stale = createPostgresStore({ pool: fakePool() });
  await assert.rejects(
    stale.sendInternalMessage({
      accountId: ACTOR, senderDeviceId: SENDER_DEVICE, conversationId: CONVERSATION,
      ciphertext, encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: [envelope(SENDER_DEVICE, 1), envelope(SECOND_DEVICE, 1), envelope(MEMBER_DEVICE, 1)]
    }),
    { code: "conversation_encryption_key_changed", statusCode: 409 }
  );

  const unregistered = createPostgresStore({
    pool: fakePool({
      conversationDevices: [
        { device_id: SENDER_DEVICE, account_id: ACTOR, key_version: 1 },
        { device_id: MEMBER_DEVICE, account_id: MEMBER, key_version: null }
      ]
    })
  });
  await assert.rejects(
    unregistered.sendInternalMessage({
      accountId: ACTOR, senderDeviceId: SENDER_DEVICE, conversationId: CONVERSATION,
      ciphertext, encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: [envelope(SENDER_DEVICE, 1)]
    }),
    { code: "conversation_encryption_keys_incomplete", statusCode: 409 }
  );
});

test("rejects duplicate and malformed key envelopes before opening a transaction", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });
  const repeated = envelope(SENDER_DEVICE, 1);
  await assert.rejects(
    store.sendInternalMessage({
      accountId: ACTOR, senderDeviceId: SENDER_DEVICE, conversationId: CONVERSATION,
      ciphertext: base64(Buffer.from("payload")), encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: [repeated, repeated]
    }),
    { code: "invalid_message_key_envelopes" }
  );
  const malformed = envelope(SENDER_DEVICE, 1);
  malformed.wrapNonceBase64 = base64(Buffer.alloc(11));
  await assert.rejects(
    store.sendInternalMessage({
      accountId: ACTOR, senderDeviceId: SENDER_DEVICE, conversationId: CONVERSATION,
      ciphertext: base64(Buffer.from("payload")), encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: [malformed]
    }),
    { code: "invalid_message_key_envelopes" }
  );
  assert.equal(pool.calls.length, 0);
});
