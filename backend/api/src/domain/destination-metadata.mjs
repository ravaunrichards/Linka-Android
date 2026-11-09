import { parsePhoneNumberFromString } from "libphonenumber-js/max";
import { normalizeE164 } from "./e164.mjs";

const NUMBER_TYPE_MAP = Object.freeze({
  MOBILE: "mobile",
  FIXED_LINE: "landline",
  FIXED_LINE_OR_MOBILE: "unknown"
});

/**
 * Resolve number-plan metadata from an explicitly international number.
 *
 * This validates numbering-plan format and reports country/type metadata only.
 * It does not prove that a number is assigned, active, owned by the caller or
 * reachable through a LINKA provider.
 */
export function resolveDestinationNumber(input) {
  const normalizedNumber = normalizeE164(input);
  const phoneNumber = parsePhoneNumberFromString(normalizedNumber);

  if (!phoneNumber || !phoneNumber.isValid()) {
    const error = new TypeError("telephone number is not valid under current numbering-plan metadata.");
    error.code = "invalid_destination_number";
    throw error;
  }

  const possibleCountries = phoneNumber.getPossibleCountries();
  const countryIso2 = phoneNumber.country ??
    (possibleCountries.length === 1 ? possibleCountries[0] : null);
  const countryResolution = countryIso2
    ? "resolved"
    : possibleCountries.length > 1
      ? "ambiguous"
      : "unresolved";
  const numberTypeDetail = phoneNumber.getType() ?? null;
  const numberType = numberTypeDetail === null
    ? "unknown"
    : NUMBER_TYPE_MAP[numberTypeDetail] ?? "other";

  return Object.freeze({
    normalizedNumber: phoneNumber.number,
    numberPlanValidation: "valid",
    countryIso2,
    possibleCountries: Object.freeze([...possibleCountries]),
    countryResolution,
    numberType,
    numberTypeDetail,
    providerReachability: "not_checked"
  });
}
