import test from "node:test";
import assert from "node:assert/strict";
import { createCorsPolicy } from "../src/cors.mjs";

function request({ origin, host = "api.example.test", protocol = "https" } = {}) {
  return {
    headers: {
      host,
      "x-forwarded-proto": protocol,
      ...(origin ? { origin } : {})
    },
    socket: { encrypted: protocol === "https" }
  };
}

test("same-origin and server-to-server requests do not need CORS headers", () => {
  const cors = createCorsPolicy();
  assert.deepEqual(cors(request()), { allowed: true, headers: {} });
  assert.deepEqual(cors(request({ origin: "https://api.example.test" })), {
    allowed: true,
    headers: {}
  });
});

test("configured HTTPS browser origins receive narrow CORS headers", () => {
  const cors = createCorsPolicy("https://app.example.test, https://admin.example.test");
  const result = cors(request({ origin: "https://app.example.test" }));
  assert.equal(result.allowed, true);
  assert.equal(result.headers["access-control-allow-methods"], "GET, POST, PUT, DELETE, OPTIONS");
  assert.doesNotMatch(result.headers["access-control-allow-methods"], /PATCH|TRACE/);
  assert.deepEqual(result.headers, {
    "access-control-allow-origin": "https://app.example.test",
    "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
    "access-control-allow-headers": "Authorization, Content-Type",
    "access-control-max-age": "600",
    vary: "Origin"
  });
});

test("unconfigured browser origins are rejected", () => {
  const cors = createCorsPolicy("https://app.example.test");
  assert.deepEqual(cors(request({ origin: "https://evil.example.test" })), {
    allowed: false,
    headers: {}
  });
});

test("CORS configuration rejects wildcards, paths and insecure remote origins", () => {
  assert.throws(() => createCorsPolicy("*"), /exact HTTPS origins/);
  assert.throws(() => createCorsPolicy("https://app.example.test/path"), /exact HTTPS origins/);
  assert.throws(() => createCorsPolicy("http://app.example.test"), /exact HTTPS origins/);
  assert.throws(() => createCorsPolicy("not-an-origin"), /valid origins/);
});

test("localhost HTTP origins are allowed for local browser development", () => {
  const cors = createCorsPolicy("http://localhost:3000,http://127.0.0.1:5173");
  assert.equal(cors(request({
    origin: "http://localhost:3000",
    host: "api.example.test",
    protocol: "https"
  })).allowed, true);
});
