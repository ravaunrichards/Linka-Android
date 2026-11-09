import test from "node:test";
import assert from "node:assert/strict";
import { resolveDestinationNumber } from "../src/domain/destination-metadata.mjs";

test("resolves Jamaican +1 876 area code metadata", () => {
  const result = resolveDestinationNumber("+1 876 555 1234");

  assert.equal(result.normalizedNumber, "+18765551234");
  assert.equal(result.countryIso2, "JM");
  assert.equal(result.countryResolution, "resolved");
  assert.ok(result.possibleCountries.includes("JM"));
  assert.equal(result.numberPlanValidation, "valid");
  assert.ok(["mobile", "landline", "other", "unknown"].includes(result.numberType));
  assert.equal(result.providerReachability, "not_checked");
});

test("resolves Jamaican +1 658 overlay metadata", () => {
  const result = resolveDestinationNumber("+1 658 555 1234");

  assert.equal(result.normalizedNumber, "+16585551234");
  assert.equal(result.countryIso2, "JM");
  assert.equal(result.countryResolution, "resolved");
});

test("distinguishes United States and Canadian numbers sharing calling code +1", () => {
  const unitedStates = resolveDestinationNumber("+1 202 555 1234");
  const canada = resolveDestinationNumber("+1 416 555 1234");

  assert.equal(unitedStates.countryIso2, "US");
  assert.equal(unitedStates.countryResolution, "resolved");
  assert.equal(canada.countryIso2, "CA");
  assert.equal(canada.countryResolution, "resolved");
});

test("resolves a UK +44 number using numbering-plan metadata", () => {
  const result = resolveDestinationNumber("+44 20 7946 0958");

  assert.equal(result.normalizedNumber, "+442079460958");
  assert.equal(result.countryIso2, "GB");
  assert.equal(result.countryResolution, "resolved");
});

test("reports non-geographic +800 numbers without inventing a country", () => {
  const result = resolveDestinationNumber("+800 1234 5678");

  assert.equal(result.normalizedNumber, "+80012345678");
  assert.equal(result.numberPlanValidation, "valid");
  assert.equal(result.countryIso2, null);
  assert.equal(result.countryResolution, "unresolved");
  assert.equal(result.providerReachability, "not_checked");
});

test("rejects definitely invalid international numbers and malformed text", () => {
  for (const value of ["+123", "+999123456789", "call me at +18765551234"]) {
    assert.throws(
      () => resolveDestinationNumber(value),
      error => ["invalid_e164", "invalid_destination_number"].includes(error.code)
    );
  }
});

test("requires an explicitly international number and never guesses a default country", () => {
  assert.throws(
    () => resolveDestinationNumber("8765551234"),
    { code: "invalid_e164" }
  );
});
