import test from "node:test";
import assert from "node:assert/strict";
import { createPostgresStore } from "../src/postgres-store.mjs";

const ACCOUNT = "00000000-0000-4000-8000-000000000101";
const CURRENT_DEVICE = "00000000-0000-4000-8000-000000000111";
const LOST_DEVICE = "00000000-0000-4000-8000-000000000112";
const OTHER_ACCOUNT_DEVICE = "00000000-0000-4000-8000-000000000199";
const CREATED_AT = new Date("2026-11-10T10:00:00.000Z");
const REVOKED_AT = new Date("2026-11-10T11:00:00.000Z");

function fakePool({ devices = [], selectedDevice = { device_id: LOST_DEVICE, revoked_at: null } } = {}) {
  const calls = [];
  const pool = {
    calls,
    on() {},
    async query(sql, values = []) {
      calls.push({ sql, values, scope: "pool" });
      if (sql.includes("FROM linka_private.user_devices d") &&
          sql.includes("LEFT JOIN linka_private.auth_sessions")) {
        assert.deepEqual(values, [ACCOUNT]);
        return { rows: devices };
      }
      throw new Error("unexpected_pool_query");
    },
    async connect() {
      return {
        async query(sql, values = []) {
          calls.push({ sql, values, scope: "transaction" });
          if (["BEGIN", "COMMIT", "ROLLBACK"].includes(sql)) return { rows: [], rowCount: 0 };
          if (sql.includes("SELECT device_id, revoked_at") &&
              sql.includes("FROM linka_private.user_devices")) {
            return { rows: selectedDevice ? [selectedDevice] : [] };
          }
          if (sql.includes("UPDATE linka_private.user_devices") && sql.includes("RETURNING revoked_at")) {
            return { rows: [{ revoked_at: REVOKED_AT }], rowCount: 1 };
          }
          if (sql.includes("UPDATE linka_private.auth_sessions")) return { rows: [], rowCount: 2 };
          if (sql.includes("UPDATE linka_private.device_encryption_keys")) return { rows: [], rowCount: 1 };
          throw new Error("unexpected_transaction_query");
        },
        release() {}
      };
    },
    async end() {}
  };
  return pool;
}

test("lists only safe account device metadata and identifies the current device", async () => {
  const pool = fakePool({
    devices: [
      {
        device_id: CURRENT_DEVICE,
        platform: "android",
        device_name: "Current phone",
        created_at: CREATED_AT,
        last_seen_at: CREATED_AT,
        revoked_at: null,
        active_session_count: 1
      },
      {
        device_id: LOST_DEVICE,
        platform: "web",
        device_name: "Old browser",
        created_at: CREATED_AT,
        last_seen_at: null,
        revoked_at: REVOKED_AT,
        active_session_count: 0
      }
    ]
  });
  const store = createPostgresStore({ pool });

  const devices = await store.listAccountDevices({
    accountId: ACCOUNT,
    currentDeviceId: CURRENT_DEVICE
  });

  assert.equal(devices.length, 2);
  assert.deepEqual(devices[0], {
    deviceId: CURRENT_DEVICE,
    platform: "android",
    deviceName: "Current phone",
    createdAt: CREATED_AT.toISOString(),
    lastSeenAt: CREATED_AT.toISOString(),
    revokedAt: null,
    status: "active",
    activeSessionCount: 1,
    current: true
  });
  assert.deepEqual(devices[1], {
    deviceId: LOST_DEVICE,
    platform: "web",
    deviceName: "Old browser",
    createdAt: CREATED_AT.toISOString(),
    lastSeenAt: null,
    revokedAt: REVOKED_AT.toISOString(),
    status: "revoked",
    activeSessionCount: 0,
    current: false
  });
  assert.equal(JSON.stringify(devices).includes("signing_public_key"), false);
});

test("rejects invalid account/device identifiers before database access", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });

  await assert.rejects(store.listAccountDevices({ accountId: "bad" }), { code: "invalid_account_id" });
  await assert.rejects(
    store.revokeAccountDevice({ accountId: ACCOUNT, deviceId: "bad" }),
    { code: "invalid_device_id" }
  );
  assert.equal(pool.calls.length, 0);
});

test("revokes a lost device, all its sessions and active messaging keys atomically", async () => {
  const pool = fakePool();
  const store = createPostgresStore({ pool });

  const result = await store.revokeAccountDevice({
    accountId: ACCOUNT,
    deviceId: LOST_DEVICE
  });

  assert.deepEqual(result, {
    deviceId: LOST_DEVICE,
    status: "revoked",
    alreadyRevoked: false,
    revokedAt: REVOKED_AT.toISOString()
  });
  const queries = pool.calls.filter(call => call.scope === "transaction");
  assert.equal(queries[0].sql, "BEGIN");
  assert.ok(queries.some(call => call.sql.includes("SET revoked_at = now(), relay_consent = FALSE")));
  assert.ok(queries.some(call => call.sql.includes("UPDATE linka_private.auth_sessions SET revoked_at = now()")));
  assert.ok(queries.some(call => call.sql.includes("UPDATE linka_private.device_encryption_keys SET retired_at = now()")));
  assert.equal(queries.at(-1).sql, "COMMIT");
  for (const query of queries.filter(call => queryTouchesAccount(query))) {
    assert.ok(query.values.includes(ACCOUNT));
  }
});

function queryTouchesAccount(query) {
  return query.sql.includes("account_id = $2::uuid") ||
    query.sql.includes("account_id = $1::uuid");
}

test("does not reveal or mutate a device belonging to another account", async () => {
  const pool = fakePool({ selectedDevice: null });
  const store = createPostgresStore({ pool });

  await assert.rejects(
    store.revokeAccountDevice({ accountId: ACCOUNT, deviceId: OTHER_ACCOUNT_DEVICE }),
    { code: "device_not_found", statusCode: 404 }
  );
  assert.equal(pool.calls.filter(call => call.sql.startsWith("UPDATE ")).length, 0);
  assert.equal(pool.calls.at(-1).sql, "ROLLBACK");
});

test("repeating a device revocation is idempotent", async () => {
  const pool = fakePool({
    selectedDevice: { device_id: LOST_DEVICE, revoked_at: REVOKED_AT }
  });
  const store = createPostgresStore({ pool });

  const result = await store.revokeAccountDevice({ accountId: ACCOUNT, deviceId: LOST_DEVICE });

  assert.deepEqual(result, {
    deviceId: LOST_DEVICE,
    status: "revoked",
    alreadyRevoked: true
  });
  assert.equal(pool.calls.some(call => call.sql.startsWith("UPDATE ")), false);
  assert.equal(pool.calls.at(-1).sql, "COMMIT");
});
