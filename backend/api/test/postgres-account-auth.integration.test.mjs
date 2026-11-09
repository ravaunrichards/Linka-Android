import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer as createTcpServer } from "node:net";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { buildAuthStatement } from "../src/runtime.mjs";

const connectionString = process.env.DATABASE_URL;
const apiDirectory = fileURLToPath(new URL("..", import.meta.url));

function createIdentity() {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const deviceId = randomUUID();
  const publicKeyDer = pair.publicKey.export({ format: "der", type: "spki" });
  const publicKeyBase64 = publicKeyDer.toString("base64");

  return {
    deviceId,
    publicKeyDer,
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

async function availablePort() {
  const listener = createTcpServer();
  await new Promise((resolve, reject) => {
    listener.once("error", reject);
    listener.listen(0, "127.0.0.1", resolve);
  });
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  return port;
}

async function postJson(baseUrl, path, body, token) {
  const response = await fetch(baseUrl + path, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: "Bearer " + token } : {})
    },
    body: JSON.stringify(body)
  });
  return { status: response.status, body: await response.json() };
}

async function getJson(baseUrl, path, token) {
  const response = await fetch(baseUrl + path, {
    headers: token ? { authorization: "Bearer " + token } : {}
  });
  return { status: response.status, body: await response.json() };
}

async function getDeviceToken(baseUrl, identity) {
  const challengeResponse = await postJson(baseUrl, "/v1/auth/challenge", {
    deviceId: identity.deviceId
  });
  assert.equal(challengeResponse.status, 201);
  const challenge = challengeResponse.body;
  const verified = await postJson(baseUrl, "/v1/auth/verify", {
    challengeId: challenge.challengeId,
    deviceId: identity.deviceId,
    publicKeyBase64: identity.publicKeyBase64,
    signatureBase64: identity.signChallenge(challenge)
  });
  assert.equal(verified.status, 200);
  return verified.body.accessToken;
}

test("account registration, persistent login sessions and multi-device binding work over HTTP", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false,
  timeout: 45_000
}, async () => {
  const port = await availablePort();
  const baseUrl = "http://127.0.0.1:" + port;
  const child = spawn(process.execPath, ["src/server.mjs"], {
    cwd: apiDirectory,
    env: {
      ...process.env,
      DATABASE_URL: connectionString,
      PORT: String(port),
      NODE_ENV: "test"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  child.stdout.on("data", chunk => { output = (output + chunk.toString()).slice(-8_000); });
  child.stderr.on("data", chunk => { output = (output + chunk.toString()).slice(-8_000); });

  try {
    let ready = false;
    for (let attempt = 0; attempt < 80; attempt += 1) {
      if (child.exitCode != null) throw new Error("LINKA API exited during startup: " + output);
      try {
        const health = await fetch(baseUrl + "/healthz");
        if (health.ok) { ready = true; break; }
      } catch {
        // The child has not started listening yet.
      }
      await delay(100);
    }
    assert.equal(ready, true, "LINKA API did not become healthy: " + output);

    const firstDevice = createIdentity();
    const firstDeviceToken = await getDeviceToken(baseUrl, firstDevice);
    const password = "LINKA-Account-Password-2026!";

    const registration = await postJson(baseUrl, "/v1/accounts/register", {
      email: "Linka.Test+" + randomUUID() + "@Example.com",
      password,
      displayName: "LINKA Test Account",
      platform: "android",
      deviceName: "CI Android Device"
    }, firstDeviceToken);
    assert.equal(registration.status, 201, JSON.stringify(registration.body));
    assert.equal(registration.body.displayName, "LINKA Test Account");
    assert.equal(registration.body.emailVerified, false);
    assert.match(registration.body.accessToken, /^[A-Za-z0-9_-]{43}$/);
    const accountToken = registration.body.accessToken;
    const email = registration.body.email;

    const firstProfile = await getJson(baseUrl, "/v1/me", accountToken);
    assert.equal(firstProfile.status, 200);
    assert.equal(firstProfile.body.accountId, registration.body.accountId);
    assert.equal(firstProfile.body.email, email);
    assert.equal(firstProfile.body.deviceId, firstDevice.deviceId);

    const deviceReused = await postJson(baseUrl, "/v1/accounts/register", {
      email: "different-" + randomUUID() + "@example.invalid",
      password,
      displayName: "Wrong Link",
      platform: "android"
    }, firstDeviceToken);
    assert.equal(deviceReused.status, 409, JSON.stringify(deviceReused.body));
    assert.equal(deviceReused.body.error, "device_already_linked");

    const recovery = await postJson(baseUrl, "/v1/accounts/recovery", { email });
    assert.equal(recovery.status, 501);
    assert.equal(recovery.body.error, "account_recovery_unavailable");

    const logout = await postJson(baseUrl, "/v1/auth/logout", {}, accountToken);
    assert.equal(logout.status, 200);
    assert.equal((await getJson(baseUrl, "/v1/me", accountToken)).status, 401);

    const firstDeviceTokenAgain = await getDeviceToken(baseUrl, firstDevice);
    const wrongPassword = await postJson(baseUrl, "/v1/accounts/login", {
      email,
      password: "incorrect-password",
      platform: "android"
    }, firstDeviceTokenAgain);
    assert.equal(wrongPassword.status, 401, JSON.stringify(wrongPassword.body));
    assert.equal(wrongPassword.body.error, "invalid_credentials");

    const secondDevice = createIdentity();
    const secondDeviceToken = await getDeviceToken(baseUrl, secondDevice);
    const secondLogin = await postJson(baseUrl, "/v1/accounts/login", {
      email,
      password,
      platform: "android",
      deviceName: "Second CI Device"
    }, secondDeviceToken);
    assert.equal(secondLogin.status, 200, JSON.stringify(secondLogin.body));
    assert.equal(secondLogin.body.accountId, registration.body.accountId);
    assert.equal(secondLogin.body.deviceId, secondDevice.deviceId);

    const secondProfile = await getJson(baseUrl, "/v1/me", secondLogin.body.accessToken);
    assert.equal(secondProfile.status, 200);
    assert.equal(secondProfile.body.accountId, registration.body.accountId);
    assert.equal(secondProfile.body.deviceId, secondDevice.deviceId);

    const db = await import("pg");
    const pool = new db.Pool({ connectionString, max: 1 });
    try {
      const sessions = await pool.query(
        "SELECT token_hash FROM linka_private.auth_sessions WHERE account_id = $1::uuid",
        [registration.body.accountId]
      );
      assert.ok(sessions.rows.length >= 2);
      const knownTokenHash = createHash("sha256").update(secondLogin.body.accessToken).digest();
      assert.ok(sessions.rows.some(row => row.token_hash.equals(knownTokenHash)));
      assert.ok(sessions.rows.every(row => row.token_hash.length === 32));
    } finally {
      await pool.end();
    }

    const secondLogout = await postJson(baseUrl, "/v1/auth/logout", {}, secondLogin.body.accessToken);
    assert.equal(secondLogout.status, 200);
    assert.equal((await getJson(baseUrl, "/v1/me", secondLogin.body.accessToken)).status, 401);
  } finally {
    if (child.exitCode == null) {
      child.kill("SIGTERM");
      await Promise.race([
        new Promise(resolve => child.once("exit", resolve)),
        delay(5_000)
      ]);
      if (child.exitCode == null) child.kill("SIGKILL");
    }
  }
});
