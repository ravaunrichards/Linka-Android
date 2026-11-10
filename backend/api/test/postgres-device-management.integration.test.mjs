import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { Pool } from "pg";

const connectionString = process.env.DATABASE_URL;

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
      if ((await fetch(origin + "/healthz")).ok) return;
    } catch {
      // Continue through the bounded startup window.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("LINKA API did not become healthy within 12 seconds.");
}

function deviceIdentity() {
  const pair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const publicKeyDer = pair.publicKey.export({ format: "der", type: "spki" });
  return {
    deviceId: randomUUID(),
    publicKeyDer,
    fingerprint: createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase()
  };
}

test("account owners can list devices and revoke a lost device and its sessions/keys", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async t => {
  const pool = new Pool({ connectionString, max: 4, connectionTimeoutMillis: 5_000 });
  const accountId = randomUUID();
  const outsiderAccountId = randomUUID();
  const current = deviceIdentity();
  const lost = deviceIdentity();
  const outsider = deviceIdentity();
  const currentToken = randomBytes(32).toString("base64url");
  const lostToken = randomBytes(32).toString("base64url");
  const outsiderToken = randomBytes(32).toString("base64url");
  const encryptionPair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const encryptionPublicKey = encryptionPair.publicKey.export({ format: "der", type: "spki" });
  const encryptionFingerprint = createHash("sha256").update(encryptionPublicKey).digest("hex").toUpperCase();

  try {
    await pool.query(
      "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
      "VALUES ($1::uuid, $2::text, 'Device Owner'), ($3::uuid, $4::text, 'Other Owner')",
      [accountId, `device-owner-${randomUUID()}@schema-test.invalid`,
        outsiderAccountId, `device-outsider-${randomUUID()}@schema-test.invalid`]
    );

    for (const [device, owner, name] of [
      [current, accountId, "Current Android"],
      [lost, accountId, "Lost browser"],
      [outsider, outsiderAccountId, "Other account device"]
    ]) {
      await pool.query(
        "INSERT INTO linka_private.user_devices " +
        "(device_id, account_id, device_fingerprint, signing_public_key_der, platform, device_name, relay_consent, last_seen_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, 'android', $5::text, $6::boolean, now())",
        [device.deviceId, owner, device.fingerprint, device.publicKeyDer, name, device.deviceId === lost.deviceId]
      );
    }

    for (const [device, owner, token] of [
      [current, accountId, currentToken],
      [lost, accountId, lostToken],
      [outsider, outsiderAccountId, outsiderToken]
    ]) {
      await pool.query(
        "INSERT INTO linka_private.auth_sessions " +
        "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), now() + interval '1 hour')",
        [randomUUID(), owner, device.deviceId, createHash("sha256").update(token).digest()]
      );
    }

    await pool.query(
      "INSERT INTO linka_private.device_encryption_keys " +
      "(device_id, account_id, key_version, key_algorithm, public_key_der, public_key_fingerprint) " +
      "VALUES ($1::uuid, $2::uuid, 1, 'ECDH-P256', $3::bytea, $4::char(64))",
      [lost.deviceId, accountId, encryptionPublicKey, encryptionFingerprint]
    );

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
    async function request(token, path, method = "GET") {
      const response = await fetch(origin + path, {
        method,
        headers: { authorization: "Bearer " + token }
      });
      const body = await response.json();
      return { status: response.status, body };
    }

    await t.test("lists only devices belonging to the authenticated account", async () => {
      const result = await request(currentToken, "/v1/me/devices");
      assert.equal(result.status, 200);
      assert.equal(result.body.devices.length, 2);
      assert.equal(result.body.devices.find(device => device.deviceId === current.deviceId)?.current, true);
      assert.equal(result.body.devices.find(device => device.deviceId === lost.deviceId)?.status, "active");
      assert.equal(JSON.stringify(result.body).includes("signing_public_key_der"), false);
    });

    await t.test("revokes the lost device, active sessions and encryption key", async () => {
      const result = await request(currentToken, "/v1/me/devices/" + lost.deviceId, "DELETE");
      assert.equal(result.status, 200);
      assert.equal(result.body.deviceId, lost.deviceId);
      assert.equal(result.body.status, "revoked");
      assert.equal(result.body.alreadyRevoked, false);

      const device = await pool.query(
        "SELECT revoked_at, relay_consent FROM linka_private.user_devices " +
        "WHERE device_id = $1::uuid AND account_id = $2::uuid",
        [lost.deviceId, accountId]
      );
      assert.ok(device.rows[0].revoked_at);
      assert.equal(device.rows[0].relay_consent, false);

      const sessions = await pool.query(
        "SELECT revoked_at FROM linka_private.auth_sessions WHERE device_id = $1::uuid",
        [lost.deviceId]
      );
      assert.equal(sessions.rows.length, 1);
      assert.ok(sessions.rows[0].revoked_at);

      const keys = await pool.query(
        "SELECT retired_at FROM linka_private.device_encryption_keys WHERE device_id = $1::uuid",
        [lost.deviceId]
      );
      assert.equal(keys.rows.length, 1);
      assert.ok(keys.rows[0].retired_at);

      const invalidated = await request(lostToken, "/v1/me");
      assert.equal(invalidated.status, 401);
    });

    await t.test("keeps revocation idempotent and prevents cross-account device disclosure", async () => {
      const repeated = await request(currentToken, "/v1/me/devices/" + lost.deviceId, "DELETE");
      assert.equal(repeated.status, 200);
      assert.equal(repeated.body.alreadyRevoked, true);

      const hidden = await request(outsiderToken, "/v1/me/devices/" + lost.deviceId, "DELETE");
      assert.equal(hidden.status, 404);
      assert.deepEqual(hidden.body, { error: "device_not_found" });

      const currentStillWorks = await request(currentToken, "/v1/me");
      assert.equal(currentStillWorks.status, 200);
    });
  } catch (error) {
    await pool.end();
    throw error;
  }
});
