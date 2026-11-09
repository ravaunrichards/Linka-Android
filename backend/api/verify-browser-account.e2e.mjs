import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import {
  generateBrowserSigningIdentity,
  signLinkaDeviceChallenge
} from "../../web/auth-crypto.mjs";

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, "crypto", { value: webcrypto });
}
if (typeof globalThis.btoa !== "function") {
  globalThis.btoa = value => Buffer.from(value, "binary").toString("base64");
}

const apiOrigin = process.env.LINKA_API_URL;
if (!apiOrigin) {
  throw new Error("Set LINKA_API_URL to the running LINKA API for this integration test.");
}
const browserOrigin = "https://browser.example";

async function request(path, {
  method = "GET",
  body,
  token,
  origin = browserOrigin,
  extraHeaders = {}
} = {}) {
  const response = await fetch(new URL(path, apiOrigin), {
    method,
    headers: {
      Origin: origin,
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...extraHeaders
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const payload = await response.json().catch(() => ({}));
  return { response, payload };
}

async function createBrowserDeviceSession() {
  const identity = await generateBrowserSigningIdentity();
  assert.equal(identity.privateKey.extractable, false, "browser private key must not be exportable");

  const challengeResult = await request("/v1/auth/challenge", {
    method: "POST",
    body: { deviceId: identity.deviceId }
  });
  assert.equal(challengeResult.response.status, 201, "device challenge should be created");

  const challenge = challengeResult.payload;
  const signatureBase64 = await signLinkaDeviceChallenge(identity.privateKey, {
    challengeId: challenge.challengeId,
    nonce: challenge.nonce,
    deviceId: identity.deviceId,
    issuedAt: challenge.issuedAt
  });

  const verifyResult = await request("/v1/auth/verify", {
    method: "POST",
    body: {
      challengeId: challenge.challengeId,
      deviceId: identity.deviceId,
      publicKeyBase64: identity.publicKeyBase64,
      signatureBase64
    }
  });
  assert.equal(verifyResult.response.status, 200, "browser signature should establish device session");
  assert.equal(typeof verifyResult.payload.accessToken, "string");
  return { identity, deviceToken: verifyResult.payload.accessToken };
}

function assertAllowedCors(response) {
  assert.equal(
    response.headers.get("access-control-allow-origin"),
    browserOrigin,
    "API should return the exact allowlisted browser origin"
  );
}

async function main() {
  const allowedPreflight = await request("/v1/accounts/register", {
    method: "OPTIONS",
    extraHeaders: {
      "Access-Control-Request-Method": "POST",
      "Access-Control-Request-Headers": "authorization,content-type"
    }
  });
  assert.equal(allowedPreflight.response.status, 204, "allowlisted browser preflight should pass");
  assertAllowedCors(allowedPreflight.response);
  assert.match(
    allowedPreflight.response.headers.get("access-control-allow-headers") || "",
    /authorization/i
  );

  const deniedPreflight = await request("/v1/me", {
    method: "OPTIONS",
    origin: "https://untrusted.example",
    extraHeaders: { "Access-Control-Request-Method": "GET" }
  });
  assert.equal(deniedPreflight.response.status, 403, "unlisted browser origin should be rejected");

  const firstDevice = await createBrowserDeviceSession();
  const unique = globalThis.crypto.randomUUID();
  const email = "browser-e2e+" + unique + "@example.test";
  const password = "LINKA-Browser-E2E-" + unique;

  const registration = await request("/v1/accounts/register", {
    method: "POST",
    token: firstDevice.deviceToken,
    body: {
      email,
      password,
      displayName: "LINKA Browser E2E",
      platform: "web",
      deviceName: "GitHub Actions browser crypto test"
    }
  });
  assert.equal(registration.response.status, 201, "browser account registration should succeed");
  assertAllowedCors(registration.response);
  const firstAccountToken = registration.payload.accessToken;
  assert.equal(typeof firstAccountToken, "string");
  assert.equal(registration.payload.emailVerified, false);

  const firstProfile = await request("/v1/me", { token: firstAccountToken });
  assert.equal(firstProfile.response.status, 200, "new account bearer token should authenticate /v1/me");
  assert.equal(firstProfile.payload.email, email);
  assert.equal(firstProfile.payload.accountId, registration.payload.accountId);

  const secondDevice = await createBrowserDeviceSession();
  const login = await request("/v1/accounts/login", {
    method: "POST",
    token: secondDevice.deviceToken,
    body: {
      email,
      password,
      platform: "web",
      deviceName: "Second GitHub Actions browser identity"
    }
  });
  assert.equal(login.response.status, 200, "second browser device should sign in to the same account");
  assert.equal(login.payload.accountId, registration.payload.accountId);
  assert.equal(login.payload.deviceId, secondDevice.identity.deviceId);

  const secondProfile = await request("/v1/me", { token: login.payload.accessToken });
  assert.equal(secondProfile.response.status, 200);
  assert.equal(secondProfile.payload.accountId, registration.payload.accountId);

  const recovery = await request("/v1/accounts/recovery", {
    method: "POST",
    body: { email }
  });
  assert.equal(recovery.response.status, 501, "recovery must not claim email delivery before configuration");
  assert.equal(recovery.payload.error, "account_recovery_unavailable");

  const logout = await request("/v1/auth/logout", {
    method: "POST",
    token: login.payload.accessToken,
    body: {}
  });
  assert.equal(logout.response.status, 200, "second device session should revoke on logout");

  const revokedProfile = await request("/v1/me", { token: login.payload.accessToken });
  assert.equal(revokedProfile.response.status, 401, "logged-out account token must be rejected");

  const firstStillActive = await request("/v1/me", { token: firstAccountToken });
  assert.equal(firstStillActive.response.status, 200, "logout on one device must not silently revoke another active device");

  const firstLogout = await request("/v1/auth/logout", {
    method: "POST",
    token: firstAccountToken,
    body: {}
  });
  assert.equal(firstLogout.response.status, 200);

  const firstRevoked = await request("/v1/me", { token: firstAccountToken });
  assert.equal(firstRevoked.response.status, 401);

  console.log("PASS: browser WebCrypto device registration, account creation, second-device login, CORS allow/deny, explicit recovery limitation and per-device logout revocation.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
