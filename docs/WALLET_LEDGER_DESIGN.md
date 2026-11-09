# LINKA wallet ledger design

## Purpose

The API domain currently contains a pure, immutable credit-ledger reducer. It defines how verified ledger events affect separate wallet buckets. It does not implement a production wallet service, payment processor, database, API endpoint or user interface.

Source:
- backend/api/src/domain/credit-ledger.mjs
- backend/api/test/credit-ledger.test.mjs

## Credit buckets

Balances are kept separately:
- purchased — credits from confirmed purchases.
- promotional — promotional and referral grants.
- sponsored — credits funded by a sponsor or funded allowance.

The reducer uses integer credit units rather than floating-point currency. Conversion from payment currency to credit units must be performed by a server-controlled, versioned pricing rule, never by the client.

## Supported domain events

Grants:
- PURCHASE_CONFIRMED → purchased.
- PROMOTIONAL_GRANT → promotional.
- REFERRAL_GRANT → promotional.
- SPONSORED_GRANT → sponsored.

Usage:
- CALL_USAGE.
- MESSAGE_USAGE.
- NUMBER_RENTAL.
- NUMBER_RENEWAL.

Refund:
- REFUND restores credits to the same source bucket as the referenced original usage event.
- Cumulative refunds cannot exceed the original usage amount.

A usage event explicitly identifies the source bucket to debit. The reducer does not silently consume another bucket when the selected source is insufficient. The service layer must define and disclose any spending priority policy before use.

## Invariants enforced by the reducer

- Every event is associated with the wallet account.
- Event IDs and idempotency keys are required.
- Replaying an identical idempotent event does not apply it twice.
- Reusing an idempotency key with different event details is rejected.
- Duplicate event IDs are rejected.
- Amounts are positive safe integers.
- Grant event types are restricted to their intended credit source.
- Usage cannot reduce a source bucket below zero.
- Refunds must reference an existing usage event in the same source bucket.
- Cumulative refunds cannot exceed original usage.
- Combined balances cannot exceed JavaScript's safe integer range.
- State, balance snapshots and stored events are immutable.

## What this does not yet do

- Persist balances or events to PostgreSQL.
- Provide atomic database transactions or concurrency locking.
- Authenticate the user or verify ownership of a wallet.
- Verify purchase/payment provider signatures.
- Deduplicate provider webhook deliveries across server instances.
- Reconcile payment settlement and carrier usage.
- Reserve/release credits during a live call.
- Apply live destination rates.
- Expose wallet or purchase endpoints.
- Display a wallet in Android or the website.
- Process refunds through a payment provider.

The API service must not treat this in-memory reducer as a production wallet. It is a tested domain primitive that can be used when a durable persistence and payment integration is implemented.

## Required production architecture

Before enabling real purchases or deductions:
1. Store accounts, wallet buckets and immutable ledger entries in a durable relational database.
2. Apply each ledger event and balance update within one database transaction with row-level locking or an equivalent concurrency-safe mechanism.
3. Enforce unique idempotency keys and event IDs with database constraints.
4. Authenticate every client and authorize access to the account that owns the wallet.
5. Verify provider webhook signatures and event IDs before granting purchased credits.
6. Keep provider secrets outside source control.
7. Store currency, purchase amount, credit quantity, exchange/price version, provider reference and timestamps for confirmed purchases.
8. Store route, quote ID, rate version, destination category, consent record and usage reference for paid calls/messages.
9. Require explicit consent for paid use; any optional automatic paid fallback must be separately enabled, quote-specific and bounded by a user-set maximum.
10. Reconcile payment settlements, carrier usage, call records, refunds and the ledger.
11. Add integration tests for concurrent deductions, duplicate webhook delivery, failed calls, partial refunds, account isolation and database recovery.
12. Add support tools and receipts without exposing secrets or another user's transaction data.

## Credit and free-service policy

- Eligible LINKA-to-LINKA voice, video and messages must not deduct credits.
- An external free route is offered only when provider availability and funding/allowance are confirmed.
- A paid external route requires the user's authorization.
- A failed free route must not silently become a paid route.
- Number rental, voice calling and messaging are separate charge categories.
