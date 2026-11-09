import test from "node:test";
import assert from "node:assert/strict";
import {
  authorizeRouteSelection,
  buildCommunicationRouteOptions,
  decideAutomaticFallback
} from "../src/domain/route-options.mjs";

const externalDestination = { kind: "telephone_number" };
const internalDestination = { kind: "linka_user" };

function creditRoute(service = "voice", quoteId = "quote-001", creditAmount = 12) {
  return {
    available: true,
    quote: {
      quoteId,
      creditAmount,
      billingUnit: service === "voice" ? "minute" : "message",
      currency: "LINKA_CREDIT"
    }
  };
}

test("LINKA-to-LINKA communication is free and never exposes external credit routes", () => {
  const result = buildCommunicationRouteOptions({
    service: "voice",
    destination: internalDestination,
    routes: {
      internal: { available: true },
      credit: creditRoute()
    }
  });

  assert.equal(result.options.length, 1);
  assert.equal(result.options[0].kind, "linka_internal");
  assert.equal(result.options[0].chargeType, "free");
  assert.equal(result.recommendedRouteId, "linka-internal");
  assert.equal(result.requiresUserChoice, false);
});

test("a funded free external route and a credit route can both be offered", () => {
  const result = buildCommunicationRouteOptions({
    service: "voice",
    destination: externalDestination,
    routes: {
      fundedFree: {
        available: true,
        fundingConfirmed: true,
        allowanceId: "sponsor-allowance-1",
        remainingUnits: 30,
        billingUnit: "minute"
      },
      credit: creditRoute()
    }
  });

  assert.deepEqual(result.options.map(option => option.chargeType), ["free", "credits"]);
  assert.equal(result.recommendedRouteId, "funded-free:sponsor-allowance-1");
  assert.equal(result.requiresUserChoice, true);
});

test("an external free route is excluded unless its funding is confirmed", () => {
  const result = buildCommunicationRouteOptions({
    service: "voice",
    destination: externalDestination,
    routes: {
      fundedFree: {
        available: true,
        fundingConfirmed: false,
        allowanceId: "unfunded-allowance",
        remainingUnits: 30,
        billingUnit: "minute"
      },
      credit: creditRoute()
    }
  });

  assert.deepEqual(result.options.map(option => option.kind), ["credit_external"]);
});

test("credit route is excluded when its quote is missing or has the wrong billing unit", () => {
  const missingQuote = buildCommunicationRouteOptions({
    service: "sms",
    destination: externalDestination,
    routes: { credit: { available: true } }
  });
  const wrongUnit = buildCommunicationRouteOptions({
    service: "sms",
    destination: externalDestination,
    routes: { credit: creditRoute("voice") }
  });

  assert.equal(missingQuote.options.length, 0);
  assert.equal(wrongUnit.options.length, 0);
});

test("a paid route requires consent tied to the exact quote", () => {
  const result = buildCommunicationRouteOptions({
    service: "voice",
    destination: externalDestination,
    routes: { credit: creditRoute() }
  });
  const routeId = result.options[0].id;

  assert.equal(
    authorizeRouteSelection({ options: result.options, selectedRouteId: routeId }).status,
    "paid_consent_required"
  );
  assert.equal(
    authorizeRouteSelection({
      options: result.options,
      selectedRouteId: routeId,
      explicitPaidConsent: true,
      confirmedQuoteId: "a-different-quote"
    }).status,
    "paid_consent_required"
  );
  assert.equal(
    authorizeRouteSelection({
      options: result.options,
      selectedRouteId: routeId,
      explicitPaidConsent: true,
      confirmedQuoteId: "quote-001"
    }).status,
    "authorized"
  );
});

test("automatic switching may use a free route when enabled", () => {
  const result = decideAutomaticFallback({
    automaticSwitchingEnabled: true,
    candidateRoute: { id: "funded-free:allowance-2", chargeType: "free" }
  });

  assert.equal(result.status, "automatic_switch_allowed");
});

test("automatic switching never changes to a paid route without matching capped preauthorization", () => {
  const route = {
    id: "credit:quote-001",
    chargeType: "credits",
    quote: { quoteId: "quote-001", creditAmount: 12 }
  };

  assert.equal(
    decideAutomaticFallback({ automaticSwitchingEnabled: true, candidateRoute: route }).status,
    "paid_consent_required"
  );
  assert.equal(
    decideAutomaticFallback({
      automaticSwitchingEnabled: true,
      candidateRoute: route,
      paidFallbackAuthorization: {
        enabled: true,
        quoteId: "quote-001",
        maxCreditAmount: 10
      }
    }).status,
    "paid_consent_required"
  );
  assert.equal(
    decideAutomaticFallback({
      automaticSwitchingEnabled: true,
      candidateRoute: route,
      paidFallbackAuthorization: {
        enabled: true,
        quoteId: "quote-001",
        maxCreditAmount: 12
      }
    }).status,
    "automatic_switch_allowed"
  );
});

test("automatic switching respects the user's disabled setting", () => {
  const result = decideAutomaticFallback({
    automaticSwitchingEnabled: false,
    candidateRoute: { id: "free-route", chargeType: "free" }
  });

  assert.equal(result.status, "user_choice_required");
});

test("no provider-confirmed route produces no selectable route", () => {
  const result = buildCommunicationRouteOptions({
    service: "mms",
    destination: externalDestination,
    routes: {
      fundedFree: {
        available: true,
        fundingConfirmed: true,
        allowanceId: "allowance-1",
        remainingUnits: 0,
        billingUnit: "message"
      },
      credit: {
        available: true,
        quote: {
          quoteId: "expired-or-invalid",
          creditAmount: 0,
          billingUnit: "message",
          currency: "LINKA_CREDIT"
        }
      }
    }
  });

  assert.deepEqual(result.options, []);
  assert.equal(result.recommendedRouteId, null);
});
