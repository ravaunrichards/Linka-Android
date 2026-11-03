import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { LinkaRuntime, buildAuthStatement } from "../src/runtime.mjs";

const DEVICE_A = "a0a00000-0000-4000-8000-000000000001";
const DEVICE_B = "b0b00000-0000-4000-8000-000000000002";

function createIdentity(deviceId) {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const publicKeyBase64 = pair.publicKey.export({
    format: "der",
    type: "spki"
  }).toString("base64");

  return {
    deviceId,
    publicKeyBase64,
    signChallenge(challenge) {
      const statement = buildAuthStatement({
        challengeId: challenge.challengeId,
        nonce: challenge.nonce,
        deviceId,
        issuedAt: challenge.issuedAt
      });
      return sign("sha256", statement, pair.privateKey).toString("base64");
    }
  };
}

function authenticate(runtime, identity) {
  const challenge = runtime.issueChallenge(identity.deviceId);
  return runtime.verifyChallenge({
    challengeId: challenge.challengeId,
    deviceId: identity.deviceId,
    publicKeyBase64: identity.publicKeyBase64,
    signatureBase64: identity.signChallenge(challenge)
  });
}

test("signed P-256 challenge issues a bearer session", () => {
  const runtime = new LinkaRuntime();
  const identity = createIdentity(DEVICE_A);
  const session = authenticate(runtime, identity);

  assert.equal(session.deviceId, DEVICE_A);
  assert.equal(session.tokenType, "Bearer");
  assert.equal(runtime.authenticate(session.accessToken).fingerprint, session.fingerprint);
  assert.equal(session.fingerprint.length, 64);
});

test("challenge is one-time and cannot be replayed", () => {
  const runtime = new LinkaRuntime();
  const identity = createIdentity(DEVICE_A);
  const challenge = runtime.issueChallenge(identity.deviceId);
  const proof = {
    challengeId: challenge.challengeId,
    deviceId: identity.deviceId,
    publicKeyBase64: identity.publicKeyBase64,
    signatureBase64: identity.signChallenge(challenge)
  };

  runtime.verifyChallenge(proof);
  assert.throws(() => runtime.verifyChallenge(proof), /challenge_expired_or_invalid/);
});

test("expired challenge is rejected", () => {
  let now = 1_800_000_000_000;
  const runtime = new LinkaRuntime({
    now: () => now,
    challengeTtlMs: 1_000
  });
  const identity = createIdentity(DEVICE_A);
  const challenge = runtime.issueChallenge(identity.deviceId);
  const proof = {
    challengeId: challenge.challengeId,
    deviceId: identity.deviceId,
    publicKeyBase64: identity.publicKeyBase64,
    signatureBase64: identity.signChallenge(challenge)
  };

  now += 1_001;
  assert.throws(() => runtime.verifyChallenge(proof), /challenge_expired_or_invalid/);
});

test("wrong key and changed device identity cannot authenticate", () => {
  const runtime = new LinkaRuntime();
  const identityA = createIdentity(DEVICE_A);
  const identityB = createIdentity(DEVICE_B);
  const challenge = runtime.issueChallenge(DEVICE_A);

  assert.throws(() => runtime.verifyChallenge({
    challengeId: challenge.challengeId,
    deviceId: DEVICE_A,
    publicKeyBase64: identityB.publicKeyBase64,
    signatureBase64: identityB.signChallenge(challenge)
  }), /signature_verification_failed/);
});

test("authenticated sender can deliver signaling only to an online recipient", () => {
  const runtime = new LinkaRuntime();
  const sender = authenticate(runtime, createIdentity(DEVICE_A));
  const recipient = authenticate(runtime, createIdentity(DEVICE_B));

  const result = runtime.enqueueSignal(sender.deviceId, {
    recipientDeviceId: recipient.deviceId,
    type: "call_invite",
    callId: "call-1",
    payload: { displayName: "LINKA peer" }
  });

  assert.equal(result.queued, true);
  const received = runtime.pollSignals(recipient.deviceId);
  assert.equal(received.length, 1);
  assert.equal(received[0].senderDeviceId, DEVICE_A);
  assert.equal(received[0].type, "call_invite");
  assert.deepEqual(runtime.pollSignals(recipient.deviceId), []);
});

test("rejects offline recipient and attempts to spoof sender identity", () => {
  const runtime = new LinkaRuntime();
  const sender = authenticate(runtime, createIdentity(DEVICE_A));

  assert.throws(() => runtime.enqueueSignal(sender.deviceId, {
    recipientDeviceId: DEVICE_B,
    type: "call_invite",
    callId: "call-2",
    senderDeviceId: DEVICE_B,
    payload: {}
  }), /recipient_offline/);
});

test("rejects unsupported signal types, malformed IDs and oversized payloads", () => {
  const runtime = new LinkaRuntime();
  const sender = authenticate(runtime, createIdentity(DEVICE_A));
  const recipient = authenticate(runtime, createIdentity(DEVICE_B));

  assert.throws(() => runtime.enqueueSignal(sender.deviceId, {
    recipientDeviceId: recipient.deviceId,
    type: "raw_audio",
    callId: "call-3",
    payload: {}
  }), /unsupported_signal_type/);

  assert.throws(() => runtime.enqueueSignal(sender.deviceId, {
    recipientDeviceId: recipient.deviceId,
    type: "call_invite",
    callId: "call with spaces",
    payload: {}
  }), /invalid_call_id/);

  assert.throws(() => runtime.enqueueSignal(sender.deviceId, {
    recipientDeviceId: recipient.deviceId,
    type: "webrtc_offer",
    callId: "call-4",
    payload: { sdp: "x".repeat(17 * 1024) }
  }), /signal_payload_too_large/);
});

test("logout revokes the bearer token", () => {
  const runtime = new LinkaRuntime();
  const session = authenticate(runtime, createIdentity(DEVICE_A));
  assert.equal(runtime.revokeToken(session.accessToken), true);
  assert.equal(runtime.authenticate(session.accessToken), null);
});
