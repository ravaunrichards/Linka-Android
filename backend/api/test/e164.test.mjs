import test from "node:test";
import assert from "node:assert/strict";
import { normalizeE164 } from "../src/domain/e164.mjs";

test("normalizes Jamaican +1 876 numbers without changing their digits", () => {
  assert.equal(normalizeE164("+1 876 555 0100"), "+18765550100");
});

test("normalizes Jamaican +1 658 numbers", () => {
  assert.equal(normalizeE164("+1 (658) 555-0100"), "+16585550100");
});

test("normalizes an international UK +44 number", () => {
  assert.equal(normalizeE164("+44 20 7946 0958"), "+442079460958");
});

test("converts the international 00 prefix to plus", () => {
  assert.equal(normalizeE164("00 1 876 555 0100"), "+18765550100");
});

test("accepts other +1 NANP examples without guessing their country", () => {
  assert.equal(normalizeE164("+1 202 555 0100"), "+12025550100");
  assert.equal(normalizeE164("+1 416 555 0100"), "+14165550100");
});

test("rejects national-format numbers when country metadata was not supplied", () => {
  assert.throws(() => normalizeE164("8765550100"), { code: "invalid_e164" });
  assert.throws(() => normalizeE164("02079460958"), { code: "invalid_e164" });
});

test("rejects extensions, letters, zero country prefixes and malformed input", () => {
  for (const value of ["+44 20 7946 0958 ext 123", "+1-ABC-555-0100", "+0123456789", "++18765550100", ""]) {
    assert.throws(() => normalizeE164(value), { code: "invalid_e164" });
  }
});

test("rejects numbers longer than the E.164 15-digit maximum and non-string input", () => {
  assert.throws(() => normalizeE164("+1234567890123456"), { code: "invalid_e164" });
  assert.throws(() => normalizeE164(null), { code: "invalid_e164" });
});
