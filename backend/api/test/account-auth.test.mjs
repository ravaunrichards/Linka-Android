import test from "node:test";
import assert from "node:assert/strict";
import {
  createAccessToken,
  createSessionExpiry,
  hashPassword,
  normalizeEmail,
  validateDisplayName,
  validatePassword,
  verifyPassword
} from "../src/account-auth.mjs";

test("normalizes email casing and internationalized domains", () => {
  assert.equal(normalizeEmail("  Alex.Example@Example.COM "), "alex.example@example.com");
  assert.equal(normalizeEmail("person@bücher.de"), "person@xn--bcher-kva.de");
});

test("rejects malformed email addresses", () => {
  for (const value of ["", "not-an-email", "@example.com", "a@@example.com", "a@localhost", "a b@example.com"]) {
    assert.throws(() => normalizeEmail(value), { code: "invalid_email" });
  }
});

test("validates display names and password length", () => {
  assert.equal(validateDisplayName("  LINKA User  "), "LINKA User");
  assert.throws(() => validateDisplayName(" "), { code: "invalid_display_name" });
  assert.throws(() => validatePassword("short"), { code: "invalid_password" });
  assert.throws(() => validatePassword("x".repeat(257)), { code: "invalid_password" });
  assert.equal(validatePassword("long-enough-example-password").length, 28);
});

test("scrypt password hashes are salted, versioned and verify only the matching password", async () => {
  const password = "long-enough-example-password";
  const first = await hashPassword(password);
  const second = await hashPassword(password);

  assert.match(first, /^scrypt-v1\$32768\$8\$1\$/);
  assert.notEqual(first, second);
  assert.equal(await verifyPassword(password, first), true);
  assert.equal(await verifyPassword("another-password-value", first), false);
  assert.equal(await verifyPassword(password, "malformed"), false);
});

test("creates opaque random access tokens and bounded session expiry strings", () => {
  const first = createAccessToken();
  const second = createAccessToken();
  assert.match(first, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(first, second);
  assert.equal(createSessionExpiry(0, 60_000), "1970-01-01T00:01:00.000Z");
  assert.throws(() => createSessionExpiry(0, 1), { code: "invalid_session_expiry" });
});
