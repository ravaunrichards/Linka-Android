const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64(value) {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function base64ToBytes(value) {
  if (typeof value !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new TypeError("Invalid LINKA encrypted-message encoding.");
  }
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
  if (bytesToBase64(bytes) !== value) throw new TypeError("Non-canonical base64 encoding.");
  return bytes;
}

function randomBytes(length) {
  return globalThis.crypto.getRandomValues(new Uint8Array(length));
}

function keyEnvelopeInfo(conversationId, recipientDeviceId, recipientKeyVersion) {
  return encoder.encode([
    "LINKA-MESSAGE-KEY-V1",
    conversationId,
    recipientDeviceId,
    String(recipientKeyVersion)
  ].join("\n"));
}

async function deriveWrappingKey(privateKey, publicKey, salt, info) {
  const sharedSecret = await globalThis.crypto.subtle.deriveBits(
    { name: "ECDH", public: publicKey },
    privateKey,
    256
  );
  const hkdfMaterial = await globalThis.crypto.subtle.importKey(
    "raw",
    sharedSecret,
    "HKDF",
    false,
    ["deriveKey"]
  );
  return globalThis.crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt, info },
    hkdfMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

/**
 * Creates a device-bound, non-exportable P-256 ECDH key pair.
 * The public SPKI key is registered with LINKA; the private CryptoKey stays local.
 */
export async function generateBrowserEncryptionIdentity() {
  if (!globalThis.crypto?.subtle || typeof globalThis.crypto.randomUUID !== "function") {
    throw new Error("Secure Web Crypto is required for LINKA encrypted messaging.");
  }
  const pair = await globalThis.crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    false,
    ["deriveBits"]
  );
  const publicKeyDer = new Uint8Array(
    await globalThis.crypto.subtle.exportKey("spki", pair.publicKey)
  );
  return Object.freeze({
    privateKey: pair.privateKey,
    publicKeyBase64: bytesToBase64(publicKeyDer),
    algorithm: "ECDH-P256"
  });
}

/**
 * Encrypts a message once with a fresh content key, then wraps that key for every
 * active conversation device using a fresh ephemeral ECDH P-256 key pair per device.
 */
export async function encryptMessageForDevices({
  plaintext,
  conversationId,
  senderAccountId,
  devices
}) {
  if (typeof plaintext !== "string" || plaintext.length === 0 || plaintext.length > 6000) {
    throw new TypeError("Enter a message between 1 and 6000 characters.");
  }
  if (typeof conversationId !== "string" || typeof senderAccountId !== "string") {
    throw new TypeError("A conversation and signed-in sender are required.");
  }
  if (!Array.isArray(devices) || devices.length < 1 || devices.length > 100) {
    throw new TypeError("The active conversation device list is invalid.");
  }
  const seen = new Set();
  for (const device of devices) {
    if (!device || typeof device.deviceId !== "string" ||
        !Number.isSafeInteger(device.keyVersion) || device.keyVersion < 1 ||
        device.algorithm !== "ECDH-P256" || typeof device.publicKeyBase64 !== "string") {
      throw new Error("Every active conversation device must register an ECDH P-256 public key before sending.");
    }
    if (seen.has(device.deviceId)) throw new Error("The conversation contains a duplicate device identity.");
    seen.add(device.deviceId);
  }

  const contentKeyBytes = randomBytes(32);
  try {
    const contentKey = await globalThis.crypto.subtle.importKey(
      "raw",
      contentKeyBytes,
      { name: "AES-GCM" },
      true,
      ["encrypt", "decrypt"]
    );
    const messageNonce = randomBytes(12);
    const messageInfo = encoder.encode([
      "LINKA-MESSAGE-V2",
      conversationId,
      senderAccountId
    ].join("\n"));
    const encryptedMessage = new Uint8Array(await globalThis.crypto.subtle.encrypt(
      { name: "AES-GCM", iv: messageNonce, additionalData: messageInfo, tagLength: 128 },
      contentKey,
      encoder.encode(plaintext)
    ));
    const ciphertext = new Uint8Array(messageNonce.length + encryptedMessage.length);
    ciphertext.set(messageNonce, 0);
    ciphertext.set(encryptedMessage, messageNonce.length);

    const keyEnvelopes = [];
    for (const device of devices) {
      const recipientPublicKey = await globalThis.crypto.subtle.importKey(
        "spki",
        base64ToBytes(device.publicKeyBase64),
        { name: "ECDH", namedCurve: "P-256" },
        false,
        []
      );
      const ephemeralPair = await globalThis.crypto.subtle.generateKey(
        { name: "ECDH", namedCurve: "P-256" },
        true,
        ["deriveBits"]
      );
      const ephemeralPublicKeyDer = new Uint8Array(
        await globalThis.crypto.subtle.exportKey("spki", ephemeralPair.publicKey)
      );
      const salt = randomBytes(16);
      const wrapNonce = randomBytes(12);
      const info = keyEnvelopeInfo(
        conversationId,
        device.deviceId,
        device.keyVersion
      );
      const wrappingKey = await deriveWrappingKey(
        ephemeralPair.privateKey,
        recipientPublicKey,
        salt,
        info
      );
      const wrappedContentKey = await globalThis.crypto.subtle.encrypt(
        { name: "AES-GCM", iv: wrapNonce, additionalData: info, tagLength: 128 },
        wrappingKey,
        contentKeyBytes
      );
      keyEnvelopes.push(Object.freeze({
        recipientDeviceId: device.deviceId,
        recipientKeyVersion: device.keyVersion,
        ephemeralPublicKeyBase64: bytesToBase64(ephemeralPublicKeyDer),
        hkdfSaltBase64: bytesToBase64(salt),
        wrapNonceBase64: bytesToBase64(wrapNonce),
        wrappedContentKeyBase64: bytesToBase64(new Uint8Array(wrappedContentKey))
      }));
    }

    return Object.freeze({
      ciphertext: bytesToBase64(ciphertext),
      encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: Object.freeze(keyEnvelopes)
    });
  } finally {
    contentKeyBytes.fill(0);
  }
}

/** Decrypts only the key envelope addressed to this browser device. */
export async function decryptMessageForDevice({
  ciphertext,
  encryptionVersion,
  conversationId,
  senderAccountId,
  deviceId,
  privateKey,
  keyEnvelope
}) {
  if (encryptionVersion !== "LINKA-E2EE-2") {
    throw new Error("This message uses an unsupported or legacy encryption version.");
  }
  if (!keyEnvelope || keyEnvelope.recipientDeviceId !== deviceId ||
      !Number.isSafeInteger(keyEnvelope.recipientKeyVersion)) {
    throw new Error("No message-key envelope exists for this browser device.");
  }
  const packed = base64ToBytes(ciphertext);
  if (packed.length < 28 || packed.length > 12 * 1024) {
    throw new TypeError("Encrypted message has an invalid size.");
  }
  const ephemeralPublicKey = await globalThis.crypto.subtle.importKey(
    "spki",
    base64ToBytes(keyEnvelope.ephemeralPublicKeyBase64),
    { name: "ECDH", namedCurve: "P-256" },
    false,
    []
  );
  const salt = base64ToBytes(keyEnvelope.hkdfSaltBase64);
  const wrapNonce = base64ToBytes(keyEnvelope.wrapNonceBase64);
  const wrappedContentKey = base64ToBytes(keyEnvelope.wrappedContentKeyBase64);
  if (salt.length !== 16 || wrapNonce.length !== 12 || wrappedContentKey.length !== 48) {
    throw new TypeError("Encrypted message-key envelope has an invalid size.");
  }
  const info = keyEnvelopeInfo(
    conversationId,
    deviceId,
    keyEnvelope.recipientKeyVersion
  );
  const wrappingKey = await deriveWrappingKey(privateKey, ephemeralPublicKey, salt, info);
  const contentKeyBytes = new Uint8Array(await globalThis.crypto.subtle.decrypt(
    { name: "AES-GCM", iv: wrapNonce, additionalData: info, tagLength: 128 },
    wrappingKey,
    wrappedContentKey
  ));
  if (contentKeyBytes.length !== 32) throw new Error("Invalid message content key.");
  const messageKey = await globalThis.crypto.subtle.importKey(
    "raw",
    contentKeyBytes,
    { name: "AES-GCM" },
    false,
    ["decrypt"]
  );
  contentKeyBytes.fill(0);
  const messageNonce = packed.slice(0, 12);
  const encryptedMessage = packed.slice(12);
  const messageInfo = encoder.encode([
    "LINKA-MESSAGE-V2",
    conversationId,
    senderAccountId
  ].join("\n"));
  const plaintext = await globalThis.crypto.subtle.decrypt(
    { name: "AES-GCM", iv: messageNonce, additionalData: messageInfo, tagLength: 128 },
    messageKey,
    encryptedMessage
  );
  return decoder.decode(plaintext);
}

function attachmentKeyEnvelopeInfo(conversationId, recipientDeviceId, recipientKeyVersion) {
  return encoder.encode([
    "LINKA-ATTACHMENT-KEY-V1",
    conversationId,
    recipientDeviceId,
    String(recipientKeyVersion)
  ].join("\n"));
}

function attachmentMessageInfo(conversationId) {
  return encoder.encode(["LINKA-ATTACHMENT-V1", conversationId].join("\n"));
}

/** Encrypts file bytes in the browser and wraps the file key for every active device. */
export async function encryptAttachmentForDevices({ bytes, conversationId, devices }) {
  const plaintext = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (plaintext.length < 1 || plaintext.length > 4 * 1024 * 1024) {
    throw new TypeError("Encrypted attachments must be between 1 byte and 4 MiB.");
  }
  if (typeof conversationId !== "string" || !Array.isArray(devices) ||
      devices.length < 1 || devices.length > 100) {
    throw new TypeError("A conversation and valid active device list are required.");
  }
  const seen = new Set();
  for (const device of devices) {
    if (!device || typeof device.deviceId !== "string" ||
        !Number.isSafeInteger(device.keyVersion) || device.keyVersion < 1 ||
        device.algorithm !== "ECDH-P256" || typeof device.publicKeyBase64 !== "string" ||
        seen.has(device.deviceId)) {
      throw new Error("Every active device must have a unique registered ECDH P-256 key.");
    }
    seen.add(device.deviceId);
  }

  const contentKeyBytes = randomBytes(32);
  try {
    const contentKey = await globalThis.crypto.subtle.importKey(
      "raw", contentKeyBytes, { name: "AES-GCM" }, true, ["encrypt"]
    );
    const nonce = randomBytes(12);
    const encrypted = new Uint8Array(await globalThis.crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: attachmentMessageInfo(conversationId), tagLength: 128 },
      contentKey,
      plaintext
    ));
    const packed = new Uint8Array(nonce.length + encrypted.length);
    packed.set(nonce);
    packed.set(encrypted, nonce.length);

    const keyEnvelopes = [];
    for (const device of devices) {
      const recipientPublicKey = await globalThis.crypto.subtle.importKey(
        "spki",
        base64ToBytes(device.publicKeyBase64),
        { name: "ECDH", namedCurve: "P-256" },
        false,
        []
      );
      const ephemeralPair = await globalThis.crypto.subtle.generateKey(
        { name: "ECDH", namedCurve: "P-256" },
        true,
        ["deriveBits"]
      );
      const ephemeralPublicKeyDer = new Uint8Array(
        await globalThis.crypto.subtle.exportKey("spki", ephemeralPair.publicKey)
      );
      const salt = randomBytes(16);
      const wrapNonce = randomBytes(12);
      const info = attachmentKeyEnvelopeInfo(conversationId, device.deviceId, device.keyVersion);
      const wrappingKey = await deriveWrappingKey(
        ephemeralPair.privateKey, recipientPublicKey, salt, info
      );
      const wrapped = await globalThis.crypto.subtle.encrypt(
        { name: "AES-GCM", iv: wrapNonce, additionalData: info, tagLength: 128 },
        wrappingKey,
        contentKeyBytes
      );
      keyEnvelopes.push(Object.freeze({
        recipientDeviceId: device.deviceId,
        recipientKeyVersion: device.keyVersion,
        ephemeralPublicKeyBase64: bytesToBase64(ephemeralPublicKeyDer),
        hkdfSaltBase64: bytesToBase64(salt),
        wrapNonceBase64: bytesToBase64(wrapNonce),
        wrappedContentKeyBase64: bytesToBase64(new Uint8Array(wrapped))
      }));
    }

    return Object.freeze({
      ciphertextBase64: bytesToBase64(packed),
      plaintextSizeBytes: plaintext.length,
      encryptionVersion: "LINKA-E2EE-2",
      keyEnvelopes: Object.freeze(keyEnvelopes)
    });
  } finally {
    contentKeyBytes.fill(0);
  }
}

/** Decrypts a downloaded encrypted attachment using only this device's envelope. */
export async function decryptAttachmentForDevice({
  ciphertextBase64,
  encryptionVersion,
  conversationId,
  deviceId,
  privateKey,
  keyEnvelope
}) {
  if (encryptionVersion !== "LINKA-E2EE-2" || !keyEnvelope ||
      keyEnvelope.recipientDeviceId !== deviceId ||
      !Number.isSafeInteger(keyEnvelope.recipientKeyVersion)) {
    throw new Error("No supported encrypted attachment key exists for this device.");
  }
  const packed = base64ToBytes(ciphertextBase64);
  if (packed.length < 29 || packed.length > 4 * 1024 * 1024 + 28) {
    throw new TypeError("Encrypted attachment has an invalid size.");
  }
  const ephemeralPublicKey = await globalThis.crypto.subtle.importKey(
    "spki",
    base64ToBytes(keyEnvelope.ephemeralPublicKeyBase64),
    { name: "ECDH", namedCurve: "P-256" },
    false,
    []
  );
  const salt = base64ToBytes(keyEnvelope.hkdfSaltBase64);
  const wrapNonce = base64ToBytes(keyEnvelope.wrapNonceBase64);
  const wrappedContentKey = base64ToBytes(keyEnvelope.wrappedContentKeyBase64);
  if (salt.length !== 16 || wrapNonce.length !== 12 || wrappedContentKey.length !== 48) {
    throw new TypeError("Encrypted attachment key envelope has an invalid size.");
  }
  const info = attachmentKeyEnvelopeInfo(
    conversationId, deviceId, keyEnvelope.recipientKeyVersion
  );
  const wrappingKey = await deriveWrappingKey(privateKey, ephemeralPublicKey, salt, info);
  const keyBytes = new Uint8Array(await globalThis.crypto.subtle.decrypt(
    { name: "AES-GCM", iv: wrapNonce, additionalData: info, tagLength: 128 },
    wrappingKey,
    wrappedContentKey
  ));
  if (keyBytes.length !== 32) throw new Error("Invalid attachment content key.");
  try {
    const contentKey = await globalThis.crypto.subtle.importKey(
      "raw", keyBytes, { name: "AES-GCM" }, false, ["decrypt"]
    );
    const plaintext = await globalThis.crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: packed.slice(0, 12),
        additionalData: attachmentMessageInfo(conversationId),
        tagLength: 128
      },
      contentKey,
      packed.slice(12)
    );
    return new Uint8Array(plaintext);
  } finally {
    keyBytes.fill(0);
  }
}
