/**
 * Normalize an explicitly international telephone number to E.164 form.
 *
 * This is a syntax/canonicalization helper only. It does not infer the
 * destination country, validate whether a number is assigned, determine
 * mobile versus landline, or prove provider reachability. Those decisions
 * require numbering-plan metadata and provider inventory.
 */
export function normalizeE164(input) {
  if (typeof input !== "string") {
    const error = new TypeError("telephone number must be a string.");
    error.code = "invalid_e164";
    throw error;
  }

  let normalized = input.trim().replace(/[\s().-]/g, "");
  if (normalized.startsWith("00")) {
    normalized = `+${normalized.slice(2)}`;
  }

  if (!/^\+[1-9]\d{1,14}$/.test(normalized)) {
    const error = new TypeError(
      "telephone number must use an international + or 00 prefix and contain 2 to 15 E.164 digits."
    );
    error.code = "invalid_e164";
    throw error;
  }

  return normalized;
}
