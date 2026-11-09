const BILLING_UNIT_BY_SERVICE = Object.freeze({
  voice: "minute",
  sms: "message",
  mms: "message"
});

function requiredText(value, fieldName) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new TypeError(`${fieldName} must be a non-empty string.`);
  }
  return value.trim();
}

function usableCreditQuote(quote, expectedUnit) {
  return Boolean(
    quote &&
    typeof quote === "object" &&
    typeof quote.quoteId === "string" &&
    quote.quoteId.trim().length > 0 &&
    quote.billingUnit === expectedUnit &&
    quote.currency === "LINKA_CREDIT" &&
    Number.isFinite(quote.creditAmount) &&
    quote.creditAmount > 0
  );
}

/**
 * Build the communication routes that the server has independently confirmed.
 *
 * This function does not discover providers, parse phone numbers, set rates,
 * charge credits or initiate a call/message. Its inputs must come from trusted
 * server-side eligibility, allowance and rate services.
 */
export function buildCommunicationRouteOptions({ service, destination, routes = {} }) {
  const billingUnit = BILLING_UNIT_BY_SERVICE[service];
  if (!billingUnit) {
    throw new TypeError("service must be voice, sms or mms.");
  }
  if (!destination || typeof destination !== "object") {
    throw new TypeError("destination is required.");
  }
  if (!["linka_user", "telephone_number"].includes(destination.kind)) {
    throw new TypeError("destination.kind must be linka_user or telephone_number.");
  }

  const options = [];

  if (destination.kind === "linka_user") {
    if (routes.internal?.available === true) {
      options.push({
        id: "linka-internal",
        kind: "linka_internal",
        chargeType: "free",
        billingUnit,
        requiresPaidConsent: false
      });
    }
  } else {
    const funded = routes.fundedFree;
    if (
      funded?.available === true &&
      funded.fundingConfirmed === true &&
      typeof funded.allowanceId === "string" &&
      funded.allowanceId.trim().length > 0 &&
      Number.isFinite(funded.remainingUnits) &&
      funded.remainingUnits > 0 &&
      funded.billingUnit === billingUnit
    ) {
      options.push({
        id: `funded-free:${funded.allowanceId.trim()}`,
        kind: "funded_external_free",
        chargeType: "free",
        billingUnit,
        remainingUnits: funded.remainingUnits,
        requiresPaidConsent: false
      });
    }

    const credit = routes.credit;
    if (credit?.available === true && usableCreditQuote(credit.quote, billingUnit)) {
      options.push({
        id: `credit:${credit.quote.quoteId.trim()}`,
        kind: "credit_external",
        chargeType: "credits",
        billingUnit,
        quote: {
          quoteId: credit.quote.quoteId.trim(),
          creditAmount: credit.quote.creditAmount,
          billingUnit: credit.quote.billingUnit,
          currency: credit.quote.currency
        },
        requiresPaidConsent: true
      });
    }
  }

  const recommended = options.find(option => option.chargeType === "free") ?? options[0] ?? null;

  return {
    service,
    destinationKind: destination.kind,
    options,
    recommendedRouteId: recommended?.id ?? null,
    requiresUserChoice: options.length > 1
  };
}

/**
 * Authorize a selected route. Credit routes require consent tied to the exact
 * quote so a changed rate cannot reuse consent for a different price.
 */
export function authorizeRouteSelection({ options, selectedRouteId, explicitPaidConsent = false, confirmedQuoteId = null }) {
  if (!Array.isArray(options)) {
    throw new TypeError("options must be an array.");
  }
  if (typeof selectedRouteId !== "string" || selectedRouteId.trim().length === 0) {
    return { status: "route_selection_required" };
  }

  const route = options.find(option => option.id === selectedRouteId);
  if (!route) {
    return { status: "route_unavailable" };
  }

  if (route.chargeType === "credits") {
    if (
      explicitPaidConsent !== true ||
      confirmedQuoteId !== route.quote?.quoteId
    ) {
      return {
        status: "paid_consent_required",
        routeId: route.id,
        quote: route.quote
      };
    }
  }

  return {
    status: "authorized",
    route
  };
}

/**
 * Decide whether a failed route may switch to another route automatically.
 * Free-to-free switching may occur when enabled. A paid route requires an
 * explicit, quote-specific preauthorization and a user-defined credit cap.
 */
export function decideAutomaticFallback({
  automaticSwitchingEnabled,
  candidateRoute,
  paidFallbackAuthorization = null
}) {
  if (automaticSwitchingEnabled !== true) {
    return { status: "user_choice_required", reason: "automatic_switching_disabled" };
  }
  if (!candidateRoute || typeof candidateRoute !== "object") {
    return { status: "fallback_unavailable" };
  }
  if (candidateRoute.chargeType === "free") {
    return { status: "automatic_switch_allowed", routeId: candidateRoute.id };
  }
  if (candidateRoute.chargeType !== "credits" || !candidateRoute.quote) {
    return { status: "fallback_unavailable" };
  }

  const authorizationMatches =
    paidFallbackAuthorization?.enabled === true &&
    paidFallbackAuthorization.quoteId === candidateRoute.quote.quoteId &&
    Number.isFinite(paidFallbackAuthorization.maxCreditAmount) &&
    paidFallbackAuthorization.maxCreditAmount >= candidateRoute.quote.creditAmount;

  if (!authorizationMatches) {
    return {
      status: "paid_consent_required",
      routeId: candidateRoute.id,
      quote: candidateRoute.quote
    };
  }

  return {
    status: "automatic_switch_allowed",
    routeId: candidateRoute.id,
    requiresPaidConsent: false,
    preauthorizationApplied: true
  };
}
