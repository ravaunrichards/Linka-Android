function concatenateBytes(parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const output = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function encodeDerInteger(bytes) {
  let start = 0;
  while (start < bytes.length - 1 && bytes[start] === 0) start += 1;
  let value = bytes.slice(start);
  if ((value[0] & 0x80) !== 0) {
    value = concatenateBytes([new Uint8Array([0]), value]);
  }
  return concatenateBytes([new Uint8Array([0x02, value.length]), value]);
}

/** Convert WebCrypto ECDSA P1363 (r || s) into ASN.1 DER accepted by Node verify(). */
export function p1363ToDer(signature) {
  const raw = signature instanceof Uint8Array ? signature : new Uint8Array(signature);
  if (raw.length !== 64) throw new TypeError("An ES256 P1363 signature must be 64 bytes.");
  const r = encodeDerInteger(raw.slice(0, 32));
  const s = encodeDerInteger(raw.slice(32, 64));
  const body = concatenateBytes([r, s]);
  if (body.length >= 128) throw new TypeError("Unexpected ECDSA signature length.");
  return concatenateBytes([new Uint8Array([0x30, body.length]), body]);
}

export function bytesToBase64(bytes) {
  const input = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = "";
  for (let offset = 0; offset < input.length; offset += 0x8000) {
    binary += String.fromCharCode(...input.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

export async function generateBrowserSigningIdentity() {
  if (!globalThis.crypto?.subtle || typeof globalThis.crypto.randomUUID !== "function") {
    throw new Error("This browser does not provide the secure Web Crypto APIs required by LINKA.");
  }
  const keyPair = await globalThis.crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign", "verify"]
  );
  const publicKeyDer = new Uint8Array(
    await globalThis.crypto.subtle.exportKey("spki", keyPair.publicKey)
  );
  return Object.freeze({
    deviceId: globalThis.crypto.randomUUID(),
    privateKey: keyPair.privateKey,
    publicKey: keyPair.publicKey,
    publicKeyDer,
    publicKeyBase64: bytesToBase64(publicKeyDer)
  });
}

export async function signLinkaDeviceChallenge(privateKey, {
  challengeId, nonce, deviceId, issuedAt
}) {
  const statement = [
    "LINKA-SERVER-AUTH-V1",
    challengeId,
    nonce,
    deviceId,
    String(issuedAt)
  ].join("\n");
  const rawSignature = await globalThis.crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(statement)
  );
  return bytesToBase64(p1363ToDer(new Uint8Array(rawSignature)));
}

/** Clear a cached bearer token only when the API explicitly rejects it. */
export function shouldClearAccountSession(status) {
  return status === 401;
}

/** Proves possession of the signed LINKA device identity for a messaging ECDH public key. */
export async function signBrowserMessagingKey(privateKey, deviceId, algorithm, publicKeyBase64) {
  if (typeof deviceId !== "string" || typeof algorithm !== "string" ||
      typeof publicKeyBase64 !== "string") {
    throw new TypeError("A LINKA device and messaging public key are required.");
  }
  const binary = atob(publicKeyBase64);
  const publicKeyDer = Uint8Array.from(binary, character => character.charCodeAt(0));
  if (bytesToBase64(publicKeyDer) !== publicKeyBase64) {
    throw new TypeError("The messaging public key must use canonical base64.");
  }
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", publicKeyDer));
  const fingerprint = [...digest].map(byte => byte.toString(16).padStart(2, "0")).join("").toUpperCase();
  const statement = [
    "LINKA-MESSAGING-KEY-V1",
    deviceId,
    algorithm,
    fingerprint
  ].join("\n");
  const signature = await globalThis.crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(statement)
  );
  return bytesToBase64(p1363ToDer(new Uint8Array(signature)));
}
