import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { once } from "node:events";

async function availablePort() {
  const probe = createServer();
  probe.listen(0, "127.0.0.1");
  await once(probe, "listening");
  const address = probe.address();
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()));
  return address.port;
}

async function waitForHealth(origin, child) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error("LINKA API exited before becoming healthy.");
    }
    try {
      const response = await fetch(origin + "/healthz");
      if (response.ok) return;
    } catch {
      // The child is still starting; retry until the bounded deadline.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("LINKA API did not become healthy within 10 seconds.");
}

test("HTTP destination resolution returns numbering metadata without claiming provider reachability", async t => {
  const port = await availablePort();
  const origin = "http://127.0.0.1:" + port;
  const child = spawn(process.execPath, [
    fileURLToPath(new URL("../src/server.mjs", import.meta.url))
  ], {
    env: {
      ...process.env,
      PORT: String(port),
      DATABASE_URL: "",
      NODE_ENV: "test",
      CORS_ALLOWED_ORIGINS: ""
    },
    stdio: "ignore"
  });

  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = once(child, "exit");
      child.kill("SIGTERM");
      await Promise.race([
        exited,
        new Promise(resolve => setTimeout(resolve, 2_000))
      ]);
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    }
  });

  await waitForHealth(origin, child);

  async function resolve(number) {
    return fetch(origin + "/v1/destinations/resolve", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number })
    });
  }

  await t.test("resolves Jamaica, United States, Canada and the United Kingdom", async () => {
    const cases = [
      ["+1 876 555 1234", "JM", "+18765551234"],
      ["+1 658 555 1234", "JM", "+16585551234"],
      ["+1 202 555 1234", "US", "+12025551234"],
      ["+1 416 555 1234", "CA", "+14165551234"],
      ["+44 20 7946 0958", "GB", "+442079460958"]
    ];

    for (const [number, country, normalized] of cases) {
      const response = await resolve(number);
      assert.equal(response.status, 200, number);
      const body = await response.json();
      assert.equal(body.normalizedNumber, normalized);
      assert.equal(body.countryIso2, country);
      assert.equal(body.countryResolution, "resolved");
      assert.equal(body.numberPlanValidation, "valid");
      assert.equal(body.providerReachability, "not_checked");
    }
  });

  await t.test("does not guess a country for ambiguous shared +1 numbers", async () => {
    const response = await resolve("+1 800 555 0123");
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.countryIso2, null);
    assert.notEqual(body.countryResolution, "resolved");
    assert.equal(body.providerReachability, "not_checked");
  });

  await t.test("rejects invalid or national-format numbers", async () => {
    for (const number of ["8765551234", "+1 234 567 8900", "+44 123"]) {
      const response = await resolve(number);
      assert.equal(response.status, 400, number);
      const body = await response.json();
      assert.ok(["invalid_e164", "invalid_destination_number"].includes(body.error));
    }
  });

  await t.test("rejects missing values, non-JSON requests and unsupported methods", async () => {
    const missing = await resolve(undefined);
    assert.equal(missing.status, 400);
    assert.deepEqual(await missing.json(), { error: "invalid_e164" });

    const wrongType = await fetch(origin + "/v1/destinations/resolve", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: "+18765551234"
    });
    assert.equal(wrongType.status, 415);

    const get = await fetch(origin + "/v1/destinations/resolve");
    assert.equal(get.status, 404);
    assert.deepEqual(await get.json(), { error: "not_found" });
  });
});
