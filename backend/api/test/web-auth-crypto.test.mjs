import test from "node:test";
import assert from "node:assert/strict";
import { createPublicKey, verify } from "node:crypto";
import {
  bytesToBase64,
  generateBrowserSigningIdentity,
  p1363ToDer,
  shouldClearAccountSession,
  signLinkaDeviceChallenge
} from "../../../web/auth-crypto.mjs";

test("generates a non-exportable P-256 browser device identity with SPKI public key", async () => {
  const identity = await generateBrowserSigningIdentity();
  assert.match(identity.deviceId, /^[0-9a-f-]{36}$/i);
  assert.equal(identity.publicKeyDer[0], 0x30);
  assert.ok(identity.publicKeyBase64.length > 100);
  assert.equal(identity.privateKey.extractable, false);
});

test("converts WebCrypto P1363 signatures to Node-compatible ECDSA DER signatures", async () => {
  const identity = await generateBrowserSigningIdentity();
  const proof = {
    challengeId: "challenge-1",
    nonce: "nonce-1",
    deviceId: identity.deviceId,
    issuedAt: 1_800_000_000_000
  };
  const signatureBase64 = await signLinkaDeviceChallenge(identity.privateKey, proof);
  const publicKey = createPublicKey({
    key: Buffer.from(identity.publicKeyDer),
    format: "der",
    type: "spki"
  });
  const statement = [
    "LINKA-SERVER-AUTH-V1",
    proof.challengeId,
    proof.nonce,
    proof.deviceId,
    String(proof.issuedAt)
  ].join("\n");
  assert.equal(verify("sha256", Buffer.from(statement), publicKey, Buffer.from(signatureBase64, "base64")), true);
});

test("rejects malformed P1363 signature sizes", () => {
  assert.throws(() => p1363ToDer(new Uint8Array(63)), TypeError);
  assert.throws(() => p1363ToDer(new Uint8Array(65)), TypeError);
});

test("encodes public-key bytes as base64 without Node-specific globals", () => {
  assert.equal(bytesToBase64(new Uint8Array([1, 2, 3])), "AQID");
});

test("clears a browser account token only when the API rejects it with 401", () => {
  assert.equal(shouldClearAccountSession(401), true);
  for (const status of [0, 403, 404, 429, 500, 503, undefined]) {
    assert.equal(shouldClearAccountSession(status), false);
  }
});
