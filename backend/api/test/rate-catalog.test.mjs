import test from "node:test";
import assert from "node:assert/strict";
import {
  quoteMessages,
  quoteVoiceCall,
  selectApplicableRate,
  validateRateRecord
} from "../src/domain/rate-catalog.mjs";

function rate(overrides = {}) {
  return {
    rateId: "rate-jm-mobile",
    providerId: "carrier-a",
    routeId: "route-a",
    providerTariffRef: "tariff-mobile",
    rateVersion: "2026-11",
    service: "voice",
    destinationCountryIso2: "JM",
    destinationPrefix: "+1876",
    numberType: "mobile",
    billingUnit: "minute",
    billingIncrementUnits: 1,
    minimumBillableUnits: 1,
    creditUnitsPerIncrement: 12,
    effectiveFrom: "2026-11-01T00:00:00Z",
    effectiveUntil: null,
    ...overrides
  };
}

test("selects a provider-supplied Jamaica rate using explicit country and number-type metadata", () => {
  const selected = selectApplicableRate({
    rates: [rate()],
    destinationNumber: "+1 876 555 0100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  });

  assert.equal(selected.rateId, "rate-jm-mobile");
});

test("longest matching destination prefix takes precedence", () => {
  const selected = selectApplicableRate({
    rates: [
      rate({ rateId: "country-wide", destinationPrefix: "+1", destinationCountryIso2: "JM", numberType: "any" }),
      rate({ rateId: "jamaica-mobile", destinationPrefix: "+1876", numberType: "mobile" })
    ],
    destinationNumber: "+1876 555 0100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  });

  assert.equal(selected.rateId, "jamaica-mobile");
});

test("specific number type takes precedence over an any-type rate for the same prefix", () => {
  const selected = selectApplicableRate({
    rates: [
      rate({ rateId: "any", numberType: "any" }),
      rate({ rateId: "mobile", numberType: "mobile" })
    ],
    destinationNumber: "+1876 555 0100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  });

  assert.equal(selected.rateId, "mobile");
});

test("does not infer Jamaica, US or Canada from country code +1", () => {
  const found = selectApplicableRate({
    rates: [rate()],
    destinationNumber: "+1 202 555 0100",
    destinationCountryIso2: "US",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  });

  assert.equal(found, null);
});

test("returns no rate for expired, future, wrong-provider or wrong-service records", () => {
  const input = {
    destinationNumber: "+18765550100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  };

  assert.equal(selectApplicableRate({ ...input, rates: [rate({ effectiveUntil: "2026-11-08T00:00:00Z" })] }), null);
  assert.equal(selectApplicableRate({ ...input, rates: [rate({ effectiveFrom: "2026-11-10T00:00:00Z" })] }), null);
  assert.equal(selectApplicableRate({ ...input, rates: [rate({ providerId: "carrier-b" })] }), null);
  assert.equal(selectApplicableRate({ ...input, rates: [rate({ service: "sms", billingUnit: "message" })] }), null);
});

test("rejects malformed provider tariffs and duplicate rate identifiers", () => {
  assert.throws(() => validateRateRecord(rate({ creditUnitsPerIncrement: 0 })), { code: "invalid_rate_record" });
  assert.throws(() => validateRateRecord(rate({ effectiveUntil: "2026-10-01T00:00:00Z" })), { code: "invalid_rate_record" });
  assert.throws(() => selectApplicableRate({
    rates: [rate(), rate()],
    destinationNumber: "+18765550100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  }), { code: "duplicate_rate_id" });
});

test("rejects ambiguous active tariffs from the same provider", () => {
  assert.throws(() => selectApplicableRate({
    rates: [
      rate({ rateId: "rate-one", routeId: "route-one" }),
      rate({ rateId: "rate-two", routeId: "route-two" })
    ],
    destinationNumber: "+18765550100",
    destinationCountryIso2: "JM",
    numberType: "mobile",
    service: "voice",
    providerId: "carrier-a",
    at: "2026-11-09T12:00:00Z"
  }), { code: "ambiguous_rate_catalog" });
});

test("quotes voice usage using the configured minute increment and minimum", () => {
  const quote = quoteVoiceCall(rate({ minimumBillableUnits: 2 }), { durationSeconds: 61 });

  assert.equal(quote.requestedUnits, 61 / 60);
  assert.equal(quote.billableUnits, 2);
  assert.equal(quote.creditUnits, 24);
  assert.equal(quote.creditUnit, "LINKA_CREDIT");
});

test("quotes SMS and MMS from the provider's message tariff", () => {
  const smsRate = rate({
    rateId: "sms-jm",
    providerTariffRef: "tariff-sms",
    service: "sms",
    numberType: "mobile",
    billingUnit: "message",
    creditUnitsPerIncrement: 3
  });
  const mmsRate = rate({
    rateId: "mms-jm",
    providerTariffRef: "tariff-mms",
    service: "mms",
    numberType: "mobile",
    billingUnit: "message",
    creditUnitsPerIncrement: 8
  });

  assert.equal(quoteMessages(smsRate, { messageCount: 3 }).creditUnits, 9);
  assert.equal(quoteMessages(mmsRate, { messageCount: 2 }).creditUnits, 16);
});

test("rejects zero usage and quote arithmetic overflow", () => {
  assert.throws(() => quoteVoiceCall(rate(), { durationSeconds: 0 }), TypeError);
  assert.throws(() => quoteMessages(rate({ service: "sms", billingUnit: "message", creditUnitsPerIncrement: Number.MAX_SAFE_INTEGER }), { messageCount: 2 }), { code: "rate_quote_overflow" });
});
