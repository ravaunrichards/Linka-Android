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

test("HTTP E.164 normalization endpoint validates syntax without guessing destination metadata", async t => {
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

  await t.test("canonicalizes an explicitly international Jamaican number", async () => {
    const response = await fetch(origin + "/v1/destinations/normalize", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number: "+1 (876) 555-0100" })
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      normalizedNumber: "+18765550100",
      format: "E.164",
      validation: "syntax_only",
      destinationResolution: "not_performed",
      numberType: "unknown",
      providerReachability: "not_checked"
    });
  });

  await t.test("converts the international 00 prefix without resolving a country", async () => {
    const response = await fetch(origin + "/v1/destinations/normalize", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number: "00 44 20 7946 0958" })
    });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.normalizedNumber, "+442079460958");
    assert.equal(body.destinationResolution, "not_performed");
    assert.equal(body.providerReachability, "not_checked");
  });

  await t.test("rejects national-format numbers instead of assuming a country", async () => {
    const response = await fetch(origin + "/v1/destinations/normalize", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number: "8765550100" })
    });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { error: "invalid_e164" });
  });

  await t.test("rejects a missing number and non-JSON requests", async () => {
    const missing = await fetch(origin + "/v1/destinations/normalize", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({})
    });
    assert.equal(missing.status, 400);
    assert.deepEqual(await missing.json(), { error: "invalid_e164" });

    const wrongContentType = await fetch(origin + "/v1/destinations/normalize", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: "+18765550100"
    });
    assert.equal(wrongContentType.status, 415);
    assert.deepEqual(await wrongContentType.json(), { error: "request_failed" });
  });

  await t.test("does not expose a GET route", async () => {
    const response = await fetch(origin + "/v1/destinations/normalize");
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "not_found" });
  });
});
