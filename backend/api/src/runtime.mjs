import {
  createHash,
  createPublicKey,
  randomBytes,
  randomUUID,
  verify as verifySignature
} from "node:crypto";

const DEVICE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SIGNAL_TYPES = new Set([
  "call_invite",
  "call_accept",
  "call_reject",
  "call_busy",
  "call_end",
  "webrtc_offer",
  "webrtc_answer",
  "webrtc_ice"
]);
const MAX_SIGNAL_PAYLOAD_BYTES = 16 * 1024;

export function buildAuthStatement({ challengeId, nonce, deviceId, issuedAt }) {
  return Buffer.from([
    "LINKA-SERVER-AUTH-V1",
    challengeId,
    nonce,
    deviceId,
    String(issuedAt)
  ].join("\n"), "utf8");
}

function requireDeviceId(deviceId) {
  if (typeof deviceId !== "string" || !DEVICE_ID_PATTERN.test(deviceId)) {
    throw new Error("invalid_device_id");
  }
}

function requirePublicKey(publicKeyBase64) {
  if (typeof publicKeyBase64 !== "string" || publicKeyBase64.length < 80 || publicKeyBase64.length > 512) {
    throw new Error("invalid_public_key");
  }
  const der = Buffer.from(publicKeyBase64, "base64");
  if (der.length < 64 || der.length > 256) throw new Error("invalid_public_key");
  const key = createPublicKey({ key: der, format: "der", type: "spki" });
  if (key.asymmetricKeyType !== "ec" ||
      key.asymmetricKeyDetails?.namedCurve !== "prime256v1") {
    throw new Error("unsupported_public_key");
  }
  return { key, der };
}

export class LinkaRuntime {
  constructor({
    challengeTtlMs = 90_000,
    sessionTtlMs = 60 * 60 * 1000,
    signalTtlMs = 90_000,
    now = () => Date.now()
  } = {}) {
    this.challengeTtlMs = challengeTtlMs;
    this.sessionTtlMs = sessionTtlMs;
    this.signalTtlMs = signalTtlMs;
    this.now = now;
    this.challenges = new Map();
    this.sessions = new Map();
    this.sessionsByDevice = new Map();
    this.signalsByDevice = new Map();
  }

  issueChallenge(deviceId) {
    requireDeviceId(deviceId);
    this.pruneExpired();
    if (this.challenges.size >= 10_000) throw new Error("challenge_capacity_reached");

    const challengeId = randomUUID();
    const nonce = randomBytes(32).toString("base64url");
    const issuedAt = this.now();
    const expiresAt = issuedAt + this.challengeTtlMs;

    this.challenges.set(challengeId, { deviceId, nonce, issuedAt, expiresAt });
    return { challengeId, nonce, issuedAt, expiresAt };
  }

  verifyChallenge({ challengeId, deviceId, publicKeyBase64, signatureBase64 }) {
    requireDeviceId(deviceId);
    if (typeof challengeId !== "string" || typeof signatureBase64 !== "string") {
      throw new Error("invalid_challenge_proof");
    }

    const challenge = this.challenges.get(challengeId);
    // One-time nonce: consume it before any further verification.
    this.challenges.delete(challengeId);
    if (!challenge || challenge.deviceId !== deviceId || challenge.expiresAt < this.now()) {
      throw new Error("challenge_expired_or_invalid");
    }

    const { key, der } = requirePublicKey(publicKeyBase64);
    const signature = Buffer.from(signatureBase64, "base64");
    if (signature.length < 64 || signature.length > 80) {
      throw new Error("invalid_signature");
    }

    const statement = buildAuthStatement({
      challengeId,
      nonce: challenge.nonce,
      deviceId,
      issuedAt: challenge.issuedAt
    });
    if (!verifySignature("sha256", statement, key, signature)) {
      throw new Error("signature_verification_failed");
    }

    const token = randomBytes(32).toString("base64url");
    const expiresAt = this.now() + this.sessionTtlMs;
    const fingerprint = createHash("sha256").update(der).digest("hex").toUpperCase();
    this.sessions.set(token, { deviceId, fingerprint, expiresAt });

    const deviceTokens = this.sessionsByDevice.get(deviceId) ?? new Set();
    deviceTokens.add(token);
    this.sessionsByDevice.set(deviceId, deviceTokens);

    return { accessToken: token, tokenType: "Bearer", expiresAt, deviceId, fingerprint };
  }

  authenticate(token) {
    if (typeof token !== "string" || token.length < 32 || token.length > 128) return null;
    const session = this.sessions.get(token);
    if (!session) return null;
    if (session.expiresAt <= this.now()) {
      this.revokeToken(token);
      return null;
    }
    return { ...session, token };
  }

  revokeToken(token) {
    const session = this.sessions.get(token);
    if (!session) return false;
    this.sessions.delete(token);
    const tokens = this.sessionsByDevice.get(session.deviceId);
    tokens?.delete(token);
    if (tokens?.size === 0) this.sessionsByDevice.delete(session.deviceId);
    return true;
  }

  isDeviceOnline(deviceId) {
    const tokens = this.sessionsByDevice.get(deviceId);
    if (!tokens) return false;
    for (const token of [...tokens]) {
      if (!this.authenticate(token)) continue;
      return true;
    }
    return false;
  }

  enqueueSignal(senderDeviceId, { recipientDeviceId, type, callId, payload = {} }) {
    requireDeviceId(recipientDeviceId);
    if (recipientDeviceId === senderDeviceId) throw new Error("cannot_signal_self");
    if (!SIGNAL_TYPES.has(type)) throw new Error("unsupported_signal_type");
    if (typeof callId !== "string" || callId.length > 64 ||
        !/^[A-Za-z0-9._:-]{1,64}$/.test(callId)) {
      throw new Error("invalid_call_id");
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("invalid_signal_payload");
    }
    const encodedPayload = Buffer.from(JSON.stringify(payload), "utf8");
    if (encodedPayload.length > MAX_SIGNAL_PAYLOAD_BYTES) {
      throw new Error("signal_payload_too_large");
    }
    if (!this.isDeviceOnline(recipientDeviceId)) {
      throw new Error("recipient_offline");
    }

    const queue = this.signalsByDevice.get(recipientDeviceId) ?? [];
    this.pruneSignalQueue(queue);
    if (queue.length >= 50) throw new Error("recipient_queue_full");

    const signal = {
      signalId: randomUUID(),
      senderDeviceId,
      type,
      callId,
      payload,
      sentAt: this.now(),
      expiresAt: this.now() + this.signalTtlMs
    };
    queue.push(signal);
    this.signalsByDevice.set(recipientDeviceId, queue);
    return { signalId: signal.signalId, queued: true };
  }

  pollSignals(deviceId, limit = 25) {
    const queue = this.signalsByDevice.get(deviceId) ?? [];
    this.pruneSignalQueue(queue);
    const boundedLimit = Math.max(1, Math.min(Number(limit) || 25, 25));
    const signals = queue.splice(0, boundedLimit).map(({ expiresAt, ...signal }) => signal);
    if (queue.length === 0) this.signalsByDevice.delete(deviceId);
    else this.signalsByDevice.set(deviceId, queue);
    return signals;
  }

  pruneExpired() {
    const now = this.now();
    for (const [id, challenge] of this.challenges) {
      if (challenge.expiresAt <= now) this.challenges.delete(id);
    }
    for (const [token, session] of this.sessions) {
      if (session.expiresAt <= now) this.revokeToken(token);
    }
    for (const [deviceId, queue] of this.signalsByDevice) {
      this.pruneSignalQueue(queue);
      if (queue.length === 0) this.signalsByDevice.delete(deviceId);
    }
  }

  pruneSignalQueue(queue) {
    const now = this.now();
    for (let index = queue.length - 1; index >= 0; index -= 1) {
      if (queue[index].expiresAt <= now) queue.splice(index, 1);
    }
  }
}
