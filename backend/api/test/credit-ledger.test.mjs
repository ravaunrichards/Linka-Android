import test from "node:test";
import assert from "node:assert/strict";
import {
  applyWalletEvent,
  createWalletState,
  getWalletBalances
} from "../src/domain/credit-ledger.mjs";

function grant(overrides = {}) {
  return {
    eventId: "event-1",
    idempotencyKey: "idem-1",
    accountId: "account-1",
    type: "PURCHASE_CONFIRMED",
    source: "purchased",
    amount: 100,
    referenceId: "payment-1",
    ...overrides
  };
}

function usage(overrides = {}) {
  return {
    eventId: "usage-1",
    idempotencyKey: "usage-idem-1",
    accountId: "account-1",
    type: "CALL_USAGE",
    source: "purchased",
    amount: 30,
    referenceId: "call-1",
    ...overrides
  };
}

test("confirmed purchases add integer credits to the purchased balance", () => {
  const state = createWalletState("account-1");
  const result = applyWalletEvent(state, grant());

  assert.equal(result.status, "applied");
  assert.deepEqual(getWalletBalances(result.state), {
    purchased: 100,
    promotional: 0,
    sponsored: 0,
    total: 100
  });
  assert.equal(getWalletBalances(state).total, 0);
});

test("promotional, referral and sponsored credits remain in separate buckets", () => {
  let state = createWalletState("account-1");
  state = applyWalletEvent(state, grant({
    eventId: "promo-1", idempotencyKey: "promo-key", type: "PROMOTIONAL_GRANT",
    source: "promotional", amount: 10
  })).state;
  state = applyWalletEvent(state, grant({
    eventId: "referral-1", idempotencyKey: "referral-key", type: "REFERRAL_GRANT",
    source: "promotional", amount: 5
  })).state;
  state = applyWalletEvent(state, grant({
    eventId: "sponsor-1", idempotencyKey: "sponsor-key", type: "SPONSORED_GRANT",
    source: "sponsored", amount: 20
  })).state;

  assert.deepEqual(getWalletBalances(state), {
    purchased: 0, promotional: 15, sponsored: 20, total: 35
  });
});

test("usage deducts only from the explicitly selected credit source", () => {
  let state = createWalletState("account-1");
  state = applyWalletEvent(state, grant()).state;
  state = applyWalletEvent(state, grant({
    eventId: "promo-1", idempotencyKey: "promo-key", type: "PROMOTIONAL_GRANT",
    source: "promotional", amount: 50
  })).state;
  state = applyWalletEvent(state, usage({ source: "purchased", amount: 25 })).state;

  assert.deepEqual(getWalletBalances(state), {
    purchased: 75, promotional: 50, sponsored: 0, total: 125
  });
});

test("insufficient balance is rejected without mutating the original snapshot", () => {
  const state = createWalletState("account-1");
  assert.throws(() => applyWalletEvent(state, usage()), { code: "insufficient_credits" });
  assert.equal(getWalletBalances(state).total, 0);
  assert.equal(state.events.length, 0);
});

test("exact duplicate idempotency key returns duplicate without double-crediting", () => {
  const state = createWalletState("account-1");
  const first = applyWalletEvent(state, grant());
  const duplicate = applyWalletEvent(first.state, grant());

  assert.equal(duplicate.status, "duplicate");
  assert.strictEqual(duplicate.state, first.state);
  assert.equal(getWalletBalances(duplicate.state).purchased, 100);
});

test("reusing an idempotency key with different event details is rejected", () => {
  let state = applyWalletEvent(createWalletState("account-1"), grant()).state;
  assert.throws(() => applyWalletEvent(state, grant({
    eventId: "different-event", amount: 500
  })), { code: "idempotency_conflict" });
});

test("duplicate event IDs with a different idempotency key are rejected", () => {
  const state = applyWalletEvent(createWalletState("account-1"), grant()).state;
  assert.throws(() => applyWalletEvent(state, grant({
    idempotencyKey: "another-key"
  })), { code: "duplicate_event_id" });
});

test("account mismatch and invalid amounts are rejected", () => {
  const state = createWalletState("account-1");
  assert.throws(() => applyWalletEvent(state, grant({ accountId: "account-2" })), { code: "account_mismatch" });
  assert.throws(() => applyWalletEvent(state, grant({ amount: 0 })), { code: "invalid_credit_amount" });
  assert.throws(() => applyWalletEvent(state, grant({ amount: 1.25 })), { code: "invalid_credit_amount" });
  assert.throws(() => applyWalletEvent(state, grant({ amount: Number.MAX_SAFE_INTEGER + 1 })), { code: "invalid_credit_amount" });
});

test("grant event types cannot credit the wrong balance bucket", () => {
  const state = createWalletState("account-1");
  assert.throws(() => applyWalletEvent(state, grant({
    type: "SPONSORED_GRANT", source: "purchased"
  })), { code: "invalid_credit_source" });
});

test("a refund restores only the original usage source and cannot exceed the usage", () => {
  let state = applyWalletEvent(createWalletState("account-1"), grant()).state;
  state = applyWalletEvent(state, usage()).state;
  state = applyWalletEvent(state, grant({
    eventId: "promo-1", idempotencyKey: "promo-key", type: "PROMOTIONAL_GRANT",
    source: "promotional", amount: 40
  })).state;

  state = applyWalletEvent(state, {
    eventId: "refund-1", idempotencyKey: "refund-key", accountId: "account-1",
    type: "REFUND", source: "purchased", amount: 10, reversesEventId: "usage-1"
  }).state;

  assert.deepEqual(getWalletBalances(state), {
    purchased: 80, promotional: 40, sponsored: 0, total: 120
  });
  assert.throws(() => applyWalletEvent(state, {
    eventId: "refund-2", idempotencyKey: "refund-key-2", accountId: "account-1",
    type: "REFUND", source: "purchased", amount: 25, reversesEventId: "usage-1"
  }), { code: "refund_exceeds_usage" });
});

test("refunds cannot target a grant event or another credit source", () => {
  const state = applyWalletEvent(createWalletState("account-1"), grant()).state;
  assert.throws(() => applyWalletEvent(state, {
    eventId: "refund-1", idempotencyKey: "refund-1-key", accountId: "account-1",
    type: "REFUND", source: "purchased", amount: 1, reversesEventId: "event-1"
  }), { code: "invalid_refund" });
});

test("unsupported events, empty IDs and malformed state are rejected", () => {
  const state = createWalletState("account-1");
  assert.throws(() => applyWalletEvent(state, grant({ type: "BALANCE_SET" })), { code: "unsupported_ledger_event" });
  assert.throws(() => applyWalletEvent(state, grant({ eventId: " " })), { code: "invalid_ledger_event" });
  assert.throws(() => getWalletBalances(null), { code: "invalid_wallet_state" });
  assert.throws(() => createWalletState(" "), { code: "invalid_ledger_event" });
});

test("combined wallet balance cannot overflow the safe integer range", () => {
  let state = createWalletState("account-1");
  state = applyWalletEvent(state, grant({ amount: Number.MAX_SAFE_INTEGER })).state;
  assert.throws(() => applyWalletEvent(state, grant({
    eventId: "promo-overflow",
    idempotencyKey: "promo-overflow-key",
    type: "PROMOTIONAL_GRANT",
    source: "promotional",
    amount: 1
  })), { code: "wallet_balance_overflow" });
  assert.throws(() => getWalletBalances(state), { code: "wallet_balance_overflow" });
});

test("wallet snapshots and stored ledger events are immutable", () => {
  const state = createWalletState("account-1");
  const next = applyWalletEvent(state, grant()).state;
  assert.equal(Object.isFrozen(next), true);
  assert.equal(Object.isFrozen(next.balances), true);
  assert.equal(Object.isFrozen(next.events), true);
  assert.equal(Object.isFrozen(next.events[0]), true);
});
