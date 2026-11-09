# LINKA database schema and migrations

## Ownership and security boundary

The initial PostgreSQL migration creates a private schema named `linka_private`. It is intended for access through authenticated LINKA backend services only. Do not expose these tables directly to anonymous browser clients or place database credentials in source control.

The application supplies UUIDs and authentication token hashes. The migration does not require a PostgreSQL extension and does not store raw bearer tokens, payment credentials, provider API secrets or plaintext internal message content.

## Initial migration

File: `backend/api/db/migrations/0001_core_schema.sql`

It creates durable structures for:
- Accounts, registered devices and hashed authentication sessions.
- Telecom/number/messaging/payment provider metadata.
- Provider-backed Jamaican and international telephone number inventory.
- Versioned provider rate records with effective dates and number-type/prefix lookup fields.
- Wallet accounts with separate purchased, promotional and sponsored balances.
- Append-only wallet ledger events with per-account idempotency.
- Payment transaction and deduplicated provider-webhook records.
- Call session history.
- Internal encrypted message records and external SMS/MMS delivery jobs.

A telephone number's acquisition/rental cost is separate from call and message usage. The schema stores provider identity and normalized number metadata; it does not create inventory or enable a carrier route by itself.

## Wallet database operation

The migration creates `linka_private.apply_wallet_event(...)`. This function:
- Locks the account's wallet row before applying an event.
- Applies idempotency checks against account/event identifiers.
- Separates purchased, promotional and sponsored credits.
- Rejects wrong grant sources, unknown event types, overdrafts and safe-integer overflow.
- Requires refunds to reference a usage event in the same credit source.
- Prevents cumulative refunds from exceeding the original usage.
- Inserts a ledger event and updates the balance buckets within the same database transaction.
- Returns `applied` or `duplicate` plus the resulting balance snapshot.

Wallet ledger rows are protected by an append-only trigger. Refunds and corrections must be new ledger events; never update or delete historical entries.

This SQL function is a durable database primitive. The current Node API has not yet been connected to a PostgreSQL pool or exposed as a wallet endpoint. Do not call the wallet production-ready until authenticated API integration, provider webhook verification, transaction reconciliation, backup/recovery and concurrency tests are complete.

## Apply locally

Use a PostgreSQL 17 database and a role authorized to create the private schema and its objects:

```sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f backend/api/db/migrations/0001_core_schema.sql
```

The migration is designed to be safely reapplied: it uses idempotent table/index creation, replaces its functions, recreates its trigger and records the migration version once.

The CI verification script runs the migration twice, then inserts temporary test records and checks wallet purchase, duplicate idempotency, usage deduction, partial refund, overdraft rejection, append-only enforcement and active-number uniqueness. Its test data is rolled back.

## Migration rules

- Never edit an already-applied migration to change an existing database. Add a forward migration.
- Keep schema changes in `backend/api/db/migrations/`.
- Add assertions and transactional behavior checks under `backend/api/db/checks/`.
- Do not add real provider credentials, raw payment details or customer data to migrations or fixtures.
- Use database transactions and row locking for balances, number assignment and payment reconciliation.
- Keep provider prices versioned and time-bounded; a cached or historical tariff must not be presented as current.
- Restrict production grants to backend service roles and review database permissions before enabling any public API access.

## Current status

The migration and CI exercise establish a PostgreSQL schema and test its SQL wallet invariants. They do not connect the existing in-memory API runtime to PostgreSQL, provision real numbers, process carrier usage, receive real payment webhooks or enable live telephony services.
