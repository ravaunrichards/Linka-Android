const CREDIT_SOURCES = new Set(["purchased", "promotional", "sponsored"]);

const GRANT_SOURCE_BY_TYPE = Object.freeze({
  PURCHASE_CONFIRMED: "purchased",
  PROMOTIONAL_GRANT: "promotional",
  REFERRAL_GRANT: "promotional",
  SPONSORED_GRANT: "sponsored"
});

const USAGE_TYPES = new Set([
  "CALL_USAGE",
  "MESSAGE_USAGE",
  "NUMBER_RENTAL",
  "NUMBER_RENEWAL"
]);

const ALLOWED_TYPES = new Set([
  ...Object.keys(GRANT_SOURCE_BY_TYPE),
  ...USAGE_TYPES,
  "REFUND"
]);

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  throw error;
}

function requiredText(value, fieldName) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail("invalid_ledger_event", `${fieldName} must be a non-empty string.`);
  }
  return value.trim();
}

function assertWalletState(state) {
  if (
    !state ||
    typeof state !== "object" ||
    typeof state.accountId !== "string" ||
    !state.balances ||
    !Array.isArray(state.events) ||
    !CREDIT_SOURCES.has("purchased") ||
    !CREDIT_SOURCES.has("promotional") ||
    !CREDIT_SOURCES.has("sponsored")
  ) {
    fail("invalid_wallet_state", "A valid wallet state is required.");
  }

  for (const source of CREDIT_SOURCES) {
    if (!Number.isSafeInteger(state.balances[source]) || state.balances[source] < 0) {
      fail("invalid_wallet_state", "Wallet source balances must be non-negative safe integers.");
    }
  }
}

function freezeState(accountId, balances, events) {
  return Object.freeze({
    accountId,
    balances: Object.freeze({ ...balances }),
    events: Object.freeze([...events])
  });
}

function fingerprint(event) {
  return JSON.stringify([
    event.eventId,
    event.idempotencyKey,
    event.accountId,
    event.type,
    event.source,
    event.amount,
    event.referenceId ?? null,
    event.reversesEventId ?? null
  ]);
}

/**
 * Create an immutable wallet snapshot for one account.
 *
 * Values are integer credit units, never floating-point currency amounts.
 * This pure reducer is a domain primitive, not a persistence or payment API.
 */
export function createWalletState(accountId) {
  const normalizedAccountId = requiredText(accountId, "accountId");
  return freezeState(normalizedAccountId, {
    purchased: 0,
    promotional: 0,
    sponsored: 0
  }, []);
}

/** Return a frozen balance snapshot while keeping credit sources separate. */
export function getWalletBalances(state) {
  assertWalletState(state);
  const purchased = state.balances.purchased;
  const promotional = state.balances.promotional;
  const sponsored = state.balances.sponsored;

  return Object.freeze({
    purchased,
    promotional,
    sponsored,
    total: purchased + promotional + sponsored
  });
}

/**
 * Apply a validated ledger event to an immutable wallet snapshot.
 *
 * The caller must supply an already authenticated and authorized event.
 * Production storage must persist the event and resulting balance atomically,
 * serialize concurrent writes, authenticate provider webhooks, enforce account
 * ownership and reconcile payment/telecom provider records.
 */
export function applyWalletEvent(state, input) {
  assertWalletState(state);

  if (!input || typeof input !== "object") {
    fail("invalid_ledger_event", "A ledger event is required.");
  }

  const event = {
    eventId: requiredText(input.eventId, "eventId"),
    idempotencyKey: requiredText(input.idempotencyKey, "idempotencyKey"),
    accountId: requiredText(input.accountId, "accountId"),
    type: requiredText(input.type, "type"),
    source: requiredText(input.source, "source"),
    amount: input.amount
  };

  if (input.referenceId !== undefined) {
    event.referenceId = requiredText(input.referenceId, "referenceId");
  }
  if (input.reversesEventId !== undefined) {
    event.reversesEventId = requiredText(input.reversesEventId, "reversesEventId");
  }

  if (event.accountId !== state.accountId) {
    fail("account_mismatch", "The ledger event belongs to a different account.");
  }
  if (!ALLOWED_TYPES.has(event.type)) {
    fail("unsupported_ledger_event", "The ledger event type is not supported.");
  }
  if (!CREDIT_SOURCES.has(event.source)) {
    fail("invalid_credit_source", "source must be purchased, promotional or sponsored.");
  }
  if (!Number.isSafeInteger(event.amount) || event.amount <= 0) {
    fail("invalid_credit_amount", "amount must be a positive safe integer credit amount.");
  }

  const priorByKey = state.events.find(entry => entry.idempotencyKey === event.idempotencyKey);
  if (priorByKey) {
    if (fingerprint(priorByKey) === fingerprint(event)) {
      return Object.freeze({ status: "duplicate", state });
    }
    fail("idempotency_conflict", "The idempotency key was already used for a different event.");
  }

  if (state.events.some(entry => entry.eventId === event.eventId)) {
    fail("duplicate_event_id", "eventId has already been recorded.");
  }

  let delta = event.amount;
  const requiredGrantSource = GRANT_SOURCE_BY_TYPE[event.type];

  if (requiredGrantSource) {
    if (event.source !== requiredGrantSource) {
      fail("invalid_credit_source", `${event.type} must use the ${requiredGrantSource} balance.`);
    }
  } else if (USAGE_TYPES.has(event.type)) {
    delta = -event.amount;
  } else if (event.type === "REFUND") {
    if (!event.reversesEventId) {
      fail("invalid_refund", "A refund must reference an original usage event.");
    }

    const original = state.events.find(entry => entry.eventId === event.reversesEventId);
    if (!original || !USAGE_TYPES.has(original.type) || original.source !== event.source) {
      fail("invalid_refund", "A refund must match an existing usage event and credit source.");
    }

    const alreadyRefunded = state.events
      .filter(entry => entry.type === "REFUND" && entry.reversesEventId === original.eventId)
      .reduce((sum, entry) => sum + entry.amount, 0);

    if (alreadyRefunded + event.amount > original.amount) {
      fail("refund_exceeds_usage", "Refund amount exceeds the remaining refundable usage.");
    }
  }

  const nextBalance = state.balances[event.source] + delta;
  if (!Number.isSafeInteger(nextBalance) || nextBalance < 0) {
    fail("insufficient_credits", "The selected credit source does not have enough available credits.");
  }

  const balances = {
    ...state.balances,
    [event.source]: nextBalance
  };
  const storedEvent = Object.freeze(event);
  const nextState = freezeState(state.accountId, balances, [...state.events, storedEvent]);

  return Object.freeze({ status: "applied", state: nextState });
}
