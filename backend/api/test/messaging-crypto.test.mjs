import test from "node:test";
import assert from "node:assert/strict";
import {
  decryptAttachmentForDevice,
  decryptMessageForDevice,
  encryptAttachmentForDevices,
  encryptMessageForDevices,
  generateBrowserEncryptionIdentity
} from "../../../web/messaging-crypto.mjs";

const CONVERSATION = "00000000-0000-4000-8000-000000000101";
const SENDER = "00000000-0000-4000-8000-000000000001";
const DEVICES = [
  "00000000-0000-4000-8000-000000000011",
  "00000000-0000-4000-8000-000000000012",
  "00000000-0000-4000-8000-000000000013"
];

test("generates a non-exportable local ECDH P-256 private key", async () => {
  const identity = await generateBrowserEncryptionIdentity();
  assert.equal(identity.algorithm, "ECDH-P256");
  assert.equal(identity.privateKey.extractable, false);
  assert.equal(identity.privateKey.algorithm.name, "ECDH");
  assert.equal(identity.privateKey.algorithm.namedCurve, "P-256");
  assert.ok(identity.publicKeyBase64.length > 80);
});

test("encrypts one message for every active device and each device decrypts only its own key envelope", async () => {
  const identities = await Promise.all(DEVICES.map(() => generateBrowserEncryptionIdentity()));
  const devices = identities.map((identity, index) => ({
    deviceId: DEVICES[index],
    accountId: index === 2
      ? "00000000-0000-4000-8000-000000000002"
      : SENDER,
    keyVersion: index + 1,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  }));
  const plaintext = "LINKA private conversation — device-to-device encryption.";
  const encrypted = await encryptMessageForDevices({
    plaintext,
    conversationId: CONVERSATION,
    senderAccountId: SENDER,
    devices
  });

  assert.equal(encrypted.encryptionVersion, "LINKA-E2EE-2");
  assert.equal(encrypted.keyEnvelopes.length, 3);
  assert.equal(encrypted.ciphertext.includes(Buffer.from(plaintext).toString("base64")), false);

  for (let index = 0; index < devices.length; index += 1) {
    const envelope = encrypted.keyEnvelopes[index];
    assert.equal(envelope.recipientDeviceId, devices[index].deviceId);
    assert.equal(envelope.recipientKeyVersion, devices[index].keyVersion);
    const decrypted = await decryptMessageForDevice({
      ciphertext: encrypted.ciphertext,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      deviceId: devices[index].deviceId,
      privateKey: identities[index].privateKey,
      keyEnvelope: envelope
    });
    assert.equal(decrypted, plaintext);
  }
});

test("rejects decryption using another device's identity or a different conversation", async () => {
  const identities = await Promise.all([generateBrowserEncryptionIdentity(), generateBrowserEncryptionIdentity()]);
  const devices = identities.map((identity, index) => ({
    deviceId: DEVICES[index],
    accountId: SENDER,
    keyVersion: 1,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  }));
  const encrypted = await encryptMessageForDevices({
    plaintext: "Only registered recipient devices should decrypt this.",
    conversationId: CONVERSATION,
    senderAccountId: SENDER,
    devices
  });
  const envelope = encrypted.keyEnvelopes[0];

  await assert.rejects(
    decryptMessageForDevice({
      ciphertext: encrypted.ciphertext,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      deviceId: DEVICES[0],
      privateKey: identities[1].privateKey,
      keyEnvelope: envelope
    })
  );
  await assert.rejects(
    decryptMessageForDevice({
      ciphertext: encrypted.ciphertext,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: "00000000-0000-4000-8000-000000000199",
      senderAccountId: SENDER,
      deviceId: DEVICES[0],
      privateKey: identities[0].privateKey,
      keyEnvelope: envelope
    })
  );
});

test("rejects tampered ciphertext, tampered key envelopes and missing recipient envelopes", async () => {
  const identity = await generateBrowserEncryptionIdentity();
  const devices = [{
    deviceId: DEVICES[0],
    accountId: SENDER,
    keyVersion: 4,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  }];
  const encrypted = await encryptMessageForDevices({
    plaintext: "Tamper detection test.",
    conversationId: CONVERSATION,
    senderAccountId: SENDER,
    devices
  });
  const envelope = encrypted.keyEnvelopes[0];
  const packed = Buffer.from(encrypted.ciphertext, "base64");
  packed[packed.length - 1] ^= 1;

  await assert.rejects(
    decryptMessageForDevice({
      ciphertext: packed.toString("base64"),
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      deviceId: DEVICES[0],
      privateKey: identity.privateKey,
      keyEnvelope: envelope
    })
  );

  await assert.rejects(
    decryptMessageForDevice({
      ciphertext: encrypted.ciphertext,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      deviceId: DEVICES[0],
      privateKey: identity.privateKey,
      keyEnvelope: { ...envelope, wrappedContentKeyBase64: Buffer.alloc(48, 2).toString("base64") }
    })
  );

  await assert.rejects(
    decryptMessageForDevice({
      ciphertext: encrypted.ciphertext,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      deviceId: DEVICES[0],
      privateKey: identity.privateKey,
      keyEnvelope: null
    })
  );
});

test("encrypts attachment bytes once and wraps the attachment key for every registered device", async () => {
  const identities = await Promise.all(DEVICES.map(() => generateBrowserEncryptionIdentity()));
  const devices = identities.map((identity, index) => ({
    deviceId: DEVICES[index],
    accountId: index === 2 ? "00000000-0000-4000-8000-000000000002" : SENDER,
    keyVersion: index + 1,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  }));
  const plaintext = new TextEncoder().encode("LINKA confidential file bytes".repeat(1024));
  const encrypted = await encryptAttachmentForDevices({
    bytes: plaintext,
    conversationId: CONVERSATION,
    devices
  });

  assert.equal(encrypted.encryptionVersion, "LINKA-E2EE-2");
  assert.equal(encrypted.plaintextSizeBytes, plaintext.length);
  assert.equal(Buffer.from(encrypted.ciphertextBase64, "base64").length, plaintext.length + 28);
  assert.equal(encrypted.keyEnvelopes.length, devices.length);

  for (let index = 0; index < devices.length; index += 1) {
    const envelope = encrypted.keyEnvelopes[index];
    assert.equal(envelope.recipientDeviceId, devices[index].deviceId);
    assert.equal(envelope.recipientKeyVersion, devices[index].keyVersion);
    const restored = await decryptAttachmentForDevice({
      ciphertextBase64: encrypted.ciphertextBase64,
      encryptionVersion: encrypted.encryptionVersion,
      conversationId: CONVERSATION,
      deviceId: devices[index].deviceId,
      privateKey: identities[index].privateKey,
      keyEnvelope: envelope
    });
    assert.deepEqual(restored, plaintext);
  }
});

test("rejects attachment ciphertext tampering and cross-device envelope substitution", async () => {
  const identities = await Promise.all([generateBrowserEncryptionIdentity(), generateBrowserEncryptionIdentity()]);
  const devices = identities.map((identity, index) => ({
    deviceId: DEVICES[index],
    accountId: SENDER,
    keyVersion: 1,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  }));
  const encrypted = await encryptAttachmentForDevices({
    bytes: new Uint8Array([1, 2, 3, 4, 5, 6]),
    conversationId: CONVERSATION,
    devices
  });
  const envelope = encrypted.keyEnvelopes[0];
  const tampered = Buffer.from(encrypted.ciphertextBase64, "base64");
  tampered[tampered.length - 1] ^= 1;

  await assert.rejects(decryptAttachmentForDevice({
    ciphertextBase64: tampered.toString("base64"),
    encryptionVersion: encrypted.encryptionVersion,
    conversationId: CONVERSATION,
    deviceId: DEVICES[0],
    privateKey: identities[0].privateKey,
    keyEnvelope: envelope
  }));
  await assert.rejects(decryptAttachmentForDevice({
    ciphertextBase64: encrypted.ciphertextBase64,
    encryptionVersion: encrypted.encryptionVersion,
    conversationId: CONVERSATION,
    deviceId: DEVICES[0],
    privateKey: identities[1].privateKey,
    keyEnvelope: envelope
  }));
  await assert.rejects(decryptAttachmentForDevice({
    ciphertextBase64: encrypted.ciphertextBase64,
    encryptionVersion: "LINKA-E2EE-1",
    conversationId: CONVERSATION,
    deviceId: DEVICES[0],
    privateKey: identities[0].privateKey,
    keyEnvelope: envelope
  }));
});

test("rejects zero-length and oversized browser attachments", async () => {
  const identity = await generateBrowserEncryptionIdentity();
  const device = {
    deviceId: DEVICES[0],
    accountId: SENDER,
    keyVersion: 1,
    algorithm: "ECDH-P256",
    publicKeyBase64: identity.publicKeyBase64
  };
  await assert.rejects(encryptAttachmentForDevices({
    bytes: new Uint8Array(0),
    conversationId: CONVERSATION,
    devices: [device]
  }));
  await assert.rejects(encryptAttachmentForDevices({
    bytes: new Uint8Array(4 * 1024 * 1024 + 1),
    conversationId: CONVERSATION,
    devices: [device]
  }));
});

test("refuses to encrypt until every active device has an ECDH public key", async () => {
  const identity = await generateBrowserEncryptionIdentity();
  await assert.rejects(
    encryptMessageForDevices({
      plaintext: "must not send",
      conversationId: CONVERSATION,
      senderAccountId: SENDER,
      devices: [{
        deviceId: DEVICES[0],
        accountId: SENDER,
        keyVersion: null,
        algorithm: null,
        publicKeyBase64: null
      }]
    }),
    /Every active conversation device/
  );
  assert.equal(identity.privateKey.extractable, false);
});
