import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { Pool } from "pg";
import { createPostgresStore } from "../src/postgres-store.mjs";

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
      const response = await fetch(origin + "/healthz");
      if (response.ok) return;
    } catch {
      // Continue until the bounded startup timeout.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("LINKA API did not become healthy within 12 seconds.");
}

test("HTTP wallet reads are account-scoped, paginated and never accept client balance writes", {
  skip: !connectionString ? "DATABASE_URL is not configured for this test run." : false
}, async t => {
  const pool = new Pool({ connectionString, max: 3, connectionTimeoutMillis: 5_000 });
  const store = createPostgresStore({ pool });
  let seedPoolClosed = false;
  const suffix = randomUUID();
  const accounts = [];

  try {
    await store.checkReady();
    for (let index = 0; index < 2; index += 1) {
      const accountId = randomUUID();
      const deviceId = randomUUID();
      const token = randomBytes(32).toString("base64url");
      const publicKeyDer = Buffer.alloc(64, index + 11);
      const fingerprint = createHash("sha256").update(publicKeyDer).digest("hex").toUpperCase();

      await pool.query(
        "INSERT INTO linka_private.accounts (account_id, email_normalized, display_name) " +
        "VALUES ($1::uuid, $2::text, $3::text)",
        [accountId, `wallet-read-${suffix}-${index}@schema-test.invalid`, `Wallet Reader ${index}`]
      );
      await pool.query(
        "INSERT INTO linka_private.user_devices " +
        "(device_id, account_id, device_fingerprint, signing_public_key_der, platform) " +
        "VALUES ($1::uuid, $2::uuid, $3::char(64), $4::bytea, 'web')",
        [deviceId, accountId, fingerprint, publicKeyDer]
      );
      await pool.query(
        "INSERT INTO linka_private.auth_sessions " +
        "(session_id, account_id, device_id, token_hash, issued_at, expires_at) " +
        "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::bytea, now(), now() + interval '1 hour')",
        [randomUUID(), accountId, deviceId, createHash("sha256").update(token).digest()]
      );
      accounts.push({ accountId, token });
      await store.ensureWallet(accountId);
    }

    const purchaseId = "purchase-" + suffix;
    const usageId = "usage-" + suffix;
    await store.applyWalletEvent({
      eventId: purchaseId,
      idempotencyKey: purchaseId,
      accountId: accounts[0].accountId,
      eventType: "PURCHASE_CONFIRMED",
      creditSource: "purchased",
      amount: 100,
      referenceId: "order-" + suffix
    });
    await store.applyWalletEvent({
      eventId: "promo-" + suffix,
      idempotencyKey: "promo-" + suffix,
      accountId: accounts[0].accountId,
      eventType: "PROMOTIONAL_GRANT",
      creditSource: "promotional",
      amount: 10
    });
    await store.applyWalletEvent({
      eventId: usageId,
      idempotencyKey: usageId,
      accountId: accounts[0].accountId,
      eventType: "CALL_USAGE",
      creditSource: "purchased",
      amount: 20,
      referenceId: "call-" + suffix
    });
    await store.applyWalletEvent({
      eventId: "refund-" + suffix,
      idempotencyKey: "refund-" + suffix,
      accountId: accounts[0].accountId,
      eventType: "REFUND",
      creditSource: "purchased",
      amount: 5,
      referenceId: "refund-" + suffix,
      reversesEventId: usageId
    });

    await store.close();
    seedPoolClosed = true;

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
      if (!seedPoolClosed) await pool.end();
    });

    await waitForHealth(origin, child);
    async function request(account, path, method = "GET", body) {
      const response = await fetch(origin + path, {
        method,
        headers: {
          ...(account ? { authorization: "Bearer " + account.token } : {}),
          ...(body === undefined ? {} : { "content-type": "application/json" })
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      });
      return { status: response.status, body: await response.json() };
    }

    await t.test("requires a persisted account session", async () => {
      const result = await request(null, "/v1/wallet");
      assert.equal(result.status, 401);
      assert.equal(result.body.error, "unauthorized");
    });

    await t.test("returns separate balances only for the authenticated account", async () => {
      const first = await request(accounts[0], "/v1/wallet");
      assert.equal(first.status, 200);
      assert.deepEqual(first.body.wallet, {
        purchased: 85,
        promotional: 10,
        sponsored: 0,
        total: 95,
        version: 4
      });

      const second = await request(accounts[1], "/v1/wallet");
      assert.equal(second.status, 200);
      assert.deepEqual(second.body.wallet, {
        purchased: 0,
        promotional: 0,
        sponsored: 0,
        total: 0,
        version: 0
      });

      const attemptedOverride = await request(
        accounts[0],
        "/v1/wallet?accountId=" + encodeURIComponent(accounts[1].accountId)
      );
      assert.equal(attemptedOverride.body.wallet.total, 95);
    });

    await t.test("returns a bounded activity page and a stable cursor for the next page", async () => {
      const first = await request(accounts[0], "/v1/wallet/ledger?limit=2");
      assert.equal(first.status, 200);
      assert.equal(first.body.events.length, 2);
      assert.ok(first.body.nextCursor?.before);
      assert.ok(first.body.nextCursor?.beforeEventId);
      for (const event of first.body.events) {
        assert.ok(event.eventId);
        assert.ok(event.eventType);
        assert.ok(Number.isSafeInteger(event.amountCreditUnits));
        assert.equal(Object.hasOwn(event, "providerReference"), false);
        assert.equal(Object.hasOwn(event, "metadata"), false);
      }

      const cursor = first.body.nextCursor;
      const query = new URLSearchParams({
        limit: "2",
        before: cursor.before,
        beforeEventId: cursor.beforeEventId
      });
      const second = await request(accounts[0], "/v1/wallet/ledger?" + query.toString());
      assert.equal(second.status, 200);
      assert.equal(second.body.events.length, 2);
      const firstIds = new Set(first.body.events.map(event => event.eventId));
      assert.ok(second.body.events.every(event => !firstIds.has(event.eventId)));
      assert.equal(second.body.nextCursor, null);
    });

    await t.test("does not expose client-controlled wallet balance mutation", async () => {
      const post = await request(accounts[0], "/v1/wallet", "POST", {
        accountId: accounts[1].accountId,
        balance: 999999999
      });
      assert.equal(post.status, 404);
      assert.deepEqual(post.body, { error: "not_found" });

      const invalidCursor = await request(accounts[0], "/v1/wallet/ledger?before=not-a-date&beforeEventId=x");
      assert.equal(invalidCursor.status, 400);
      assert.equal(invalidCursor.body.error, "invalid_wallet_cursor");
    });
  } catch (error) {
    if (!seedPoolClosed) await pool.end().catch(() => {});
    throw error;
  }
});
