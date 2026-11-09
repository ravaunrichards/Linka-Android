import {
  randomBytes,
  scrypt as scryptCallback,
  timingSafeEqual
} from "node:crypto";
import { promisify } from "node:util";
import { domainToASCII } from "node:url";

const scrypt = promisify(scryptCallback);
const SCRYPT_COST = 32_768;
const SCRYPT_BLOCK_SIZE = 8;
const SCRYPT_PARALLELIZATION = 1;
const SCRYPT_KEY_BYTES = 64;
const SCRYPT_SALT_BYTES = 16;
const SCRYPT_MAXMEM_BYTES = 64 * 1024 * 1024;

function fail(code, message = code, statusCode = 400) {
  const error = new Error(message);
  error.code = code;
  error.statusCode = statusCode;
  throw error;
}

export function normalizeEmail(value) {
  if (typeof value !== "string") fail("invalid_email");
  const input = value.trim();
  if (input.length < 3 || input.length > 320) fail("invalid_email");

  const at = input.lastIndexOf("@");
  if (at <= 0 || at !== input.indexOf("@") || at === input.length - 1) {
    fail("invalid_email");
  }

  const local = input.slice(0, at);
  const rawDomain = input.slice(at + 1);
  if (
    local.length > 64 ||
    !/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local) ||
    local.startsWith(".") ||
    local.endsWith(".") ||
    local.includes("..")
  ) {
    fail("invalid_email");
  }

  const domain = domainToASCII(rawDomain).toLowerCase();
  if (domain.length < 3 || domain.length > 253) fail("invalid_email");
  const labels = domain.split(".");
  if (
    labels.length < 2 ||
    labels.some(label =>
      label.length === 0 ||
      label.length > 63 ||
      !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)
    )
  ) {
    fail("invalid_email");
  }

  return local.toLowerCase() + "@" + domain;
}

export function validateDisplayName(value) {
  if (
    typeof value !== "string" ||
    value.trim().length < 1 ||
    value.trim().length > 100
  ) {
    fail("invalid_display_name");
  }
  return value.trim();
}

export function validatePassword(value) {
  if (
    typeof value !== "string" ||
    value.length < 12 ||
    value.length > 256 ||
    value.includes("\0")
  ) {
    fail("invalid_password", "Password must contain 12 to 256 characters.");
  }
  return value;
}

function scryptOptions() {
  return {
    N: SCRYPT_COST,
    r: SCRYPT_BLOCK_SIZE,
    p: SCRYPT_PARALLELIZATION,
    maxmem: SCRYPT_MAXMEM_BYTES
  };
}

/** Return a versioned scrypt hash. The raw password is never persisted. */
export async function hashPassword(passwordInput) {
  const password = validatePassword(passwordInput);
  const salt = randomBytes(SCRYPT_SALT_BYTES);
  const derivedKey = await scrypt(password, salt, SCRYPT_KEY_BYTES, scryptOptions());
  return [
    "scrypt-v1",
    String(SCRYPT_COST),
    String(SCRYPT_BLOCK_SIZE),
    String(SCRYPT_PARALLELIZATION),
    salt.toString("base64url"),
    Buffer.from(derivedKey).toString("base64url")
  ].join("$");
}

/** Constant-time comparison for valid stored hashes; malformed hashes fail closed. */
export async function verifyPassword(passwordInput, encodedHash) {
  if (typeof passwordInput !== "string" || passwordInput.length > 256) return false;
  if (typeof encodedHash !== "string" || encodedHash.length > 256) return false;

  const parts = encodedHash.split("$");
  if (
    parts.length !== 6 ||
    parts[0] !== "scrypt-v1" ||
    parts[1] !== String(SCRYPT_COST) ||
    parts[2] !== String(SCRYPT_BLOCK_SIZE) ||
    parts[3] !== String(SCRYPT_PARALLELIZATION)
  ) {
    return false;
  }

  const salt = Buffer.from(parts[4], "base64url");
  const expected = Buffer.from(parts[5], "base64url");
  if (salt.length !== SCRYPT_SALT_BYTES || expected.length !== SCRYPT_KEY_BYTES) {
    return false;
  }

  try {
    const actual = Buffer.from(
      await scrypt(passwordInput, salt, SCRYPT_KEY_BYTES, scryptOptions())
    );
    return timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

export function createAccessToken() {
  return randomBytes(32).toString("base64url");
}

export function createSessionExpiry(
  now = Date.now(),
  ttlMs = 30 * 24 * 60 * 60 * 1000
) {
  if (!Number.isFinite(now) || !Number.isFinite(ttlMs) || ttlMs < 60_000) {
    fail("invalid_session_expiry");
  }
  return new Date(now + ttlMs).toISOString();
}
