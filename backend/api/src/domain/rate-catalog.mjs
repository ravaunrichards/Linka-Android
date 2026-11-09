import { normalizeE164 } from "./e164.mjs";

const SERVICES = new Set(["voice", "sms", "mms"]);
const NUMBER_TYPES = new Set(["mobile", "landline", "other", "unknown", "any"]);
const EXPECTED_BILLING_UNIT = Object.freeze({
  voice: "minute",
  sms: "message",
  mms: "message"
});

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  throw error;
}

function requiredText(value, fieldName) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail("invalid_rate_record", `${fieldName} must be a non-empty string.`);
  }
  return value.trim();
}

function positiveSafeInteger(value, fieldName) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    fail("invalid_rate_record", `${fieldName} must be a positive safe integer.`);
  }
  return value;
}

function timestamp(value, fieldName, nullable = false) {
  if (nullable && value === null) return null;
  if (typeof value !== "string" || value.trim().length === 0) {
    fail("invalid_rate_record", `${fieldName} must be an ISO date-time string.`);
  }
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) {
    fail("invalid_rate_record", `${fieldName} must be a valid ISO date-time.`);
  }
  return parsed;
}

/**
 * Validate one provider-supplied tariff record. This code does not set prices
 * or infer number geography. The caller must load trusted provider tariff data.
 */
export function validateRateRecord(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    fail("invalid_rate_record", "A provider rate record is required.");
  }

  const rate = {
    rateId: requiredText(input.rateId, "rateId"),
    providerId: requiredText(input.providerId, "providerId"),
    routeId: requiredText(input.routeId, "routeId"),
    providerTariffRef: requiredText(input.providerTariffRef, "providerTariffRef"),
    rateVersion: requiredText(input.rateVersion, "rateVersion"),
    service: input.service,
    destinationCountryIso2: requiredText(input.destinationCountryIso2, "destinationCountryIso2").toUpperCase(),
    destinationPrefix: requiredText(input.destinationPrefix, "destinationPrefix"),
    numberType: input.numberType,
    billingUnit: input.billingUnit,
    billingIncrementUnits: positiveSafeInteger(input.billingIncrementUnits, "billingIncrementUnits"),
    minimumBillableUnits: positiveSafeInteger(input.minimumBillableUnits, "minimumBillableUnits"),
    creditUnitsPerIncrement: positiveSafeInteger(input.creditUnitsPerIncrement, "creditUnitsPerIncrement"),
    effectiveFrom: input.effectiveFrom,
    effectiveUntil: input.effectiveUntil ?? null
  };

  if (!SERVICES.has(rate.service)) {
    fail("invalid_rate_record", "service must be voice, sms or mms.");
  }
  if (!/^[A-Z]{2}$/.test(rate.destinationCountryIso2)) {
    fail("invalid_rate_record", "destinationCountryIso2 must be a two-letter uppercase country code.");
  }
  if (!/^\+[1-9]\d{1,14}$/.test(rate.destinationPrefix)) {
    fail("invalid_rate_record", "destinationPrefix must be an international E.164 prefix.");
  }
  if (!NUMBER_TYPES.has(rate.numberType)) {
    fail("invalid_rate_record", "numberType must be mobile, landline, other, unknown or any.");
  }
  if (rate.billingUnit !== EXPECTED_BILLING_UNIT[rate.service]) {
    fail("invalid_rate_record", `${rate.service} rates must use the ${EXPECTED_BILLING_UNIT[rate.service]} billing unit.`);
  }

  const effectiveFromMs = timestamp(rate.effectiveFrom, "effectiveFrom");
  const effectiveUntilMs = timestamp(rate.effectiveUntil, "effectiveUntil", true);
  if (effectiveUntilMs !== null && effectiveUntilMs <= effectiveFromMs) {
    fail("invalid_rate_record", "effectiveUntil must be later than effectiveFrom.");
  }

  return Object.freeze({
    ...rate,
    effectiveFromMs,
    effectiveUntilMs
  });
}

/**
 * Resolve a rate only when the caller supplies trusted destination metadata.
 * Country and number type are never guessed from the +1 calling code.
 */
export function selectApplicableRate({
  rates,
  destinationNumber,
  destinationCountryIso2,
  numberType,
  service,
  providerId,
  at = new Date().toISOString()
}) {
  if (!Array.isArray(rates)) {
    throw new TypeError("rates must be an array.");
  }
  if (!SERVICES.has(service)) {
    throw new TypeError("service must be voice, sms or mms.");
  }
  if (!NUMBER_TYPES.has(numberType) || numberType === "any") {
    throw new TypeError("numberType must be a resolved destination type.");
  }

  const country = requiredText(destinationCountryIso2, "destinationCountryIso2").toUpperCase();
  if (!/^[A-Z]{2}$/.test(country)) {
    throw new TypeError("destinationCountryIso2 must be a two-letter country code.");
  }
  const provider = requiredText(providerId, "providerId");
  const normalizedNumber = normalizeE164(destinationNumber);
  const atMs = timestamp(at, "at");

  const ids = new Set();
  const validRates = rates.map(validateRateRecord);
  for (const rate of validRates) {
    if (ids.has(rate.rateId)) {
      fail("duplicate_rate_id", `Rate ID ${rate.rateId} occurs more than once.`);
    }
    ids.add(rate.rateId);
  }

  const candidates = validRates.filter(rate => (
    rate.providerId === provider &&
    rate.service === service &&
    rate.destinationCountryIso2 === country &&
    (rate.numberType === numberType || rate.numberType === "any") &&
    normalizedNumber.startsWith(rate.destinationPrefix) &&
    rate.effectiveFromMs <= atMs &&
    (rate.effectiveUntilMs === null || atMs < rate.effectiveUntilMs)
  ));

  if (candidates.length === 0) return null;

  candidates.sort((left, right) =>
    right.destinationPrefix.length - left.destinationPrefix.length ||
    Number(right.numberType === numberType) - Number(left.numberType === numberType) ||
    right.effectiveFromMs - left.effectiveFromMs
  );

  const selected = candidates[0];
  const tied = candidates.filter(rate =>
    rate.destinationPrefix.length === selected.destinationPrefix.length &&
    (rate.numberType === numberType) === (selected.numberType === numberType) &&
    rate.effectiveFromMs === selected.effectiveFromMs
  );
  if (tied.length > 1) {
    fail("ambiguous_rate_catalog", "More than one active rate has the same destination specificity and effective date.");
  }

  return selected;
}

function buildQuote(rateInput, requestedUnits, service) {
  const rate = validateRateRecord(rateInput);
  if (rate.service !== service) {
    throw new TypeError(`A ${service} rate is required.`);
  }
  if (!Number.isFinite(requestedUnits) || requestedUnits <= 0) {
    throw new TypeError("requested usage must be a positive finite number.");
  }

  const increments = Math.max(
    Math.ceil(rate.minimumBillableUnits / rate.billingIncrementUnits),
    Math.ceil(requestedUnits / rate.billingIncrementUnits)
  );
  const billableUnits = increments * rate.billingIncrementUnits;
  const creditUnits = increments * rate.creditUnitsPerIncrement;
  if (!Number.isSafeInteger(billableUnits) || !Number.isSafeInteger(creditUnits)) {
    fail("rate_quote_overflow", "The requested usage exceeds the safe quote range.");
  }

  return Object.freeze({
    rateId: rate.rateId,
    rateVersion: rate.rateVersion,
    providerId: rate.providerId,
    routeId: rate.routeId,
    service: rate.service,
    billingUnit: rate.billingUnit,
    requestedUnits,
    billableUnits,
    billingIncrementUnits: rate.billingIncrementUnits,
    creditUnitsPerIncrement: rate.creditUnitsPerIncrement,
    creditUnits,
    creditUnit: "LINKA_CREDIT"
  });
}

/** Estimate voice charges from connected-call duration in seconds. */
export function quoteVoiceCall(rate, { durationSeconds }) {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    throw new TypeError("durationSeconds must be a positive finite number.");
  }
  return buildQuote(rate, durationSeconds / 60, "voice");
}

/** Estimate external SMS or MMS charges by provider-billable message count. */
export function quoteMessages(rate, { messageCount }) {
  if (!Number.isSafeInteger(messageCount) || messageCount <= 0) {
    throw new TypeError("messageCount must be a positive safe integer.");
  }
  return buildQuote(rate, messageCount, rate?.service);
}
