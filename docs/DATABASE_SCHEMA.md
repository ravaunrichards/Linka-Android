# LINKA database schema and migrations

## Ownership and security boundary

The initial PostgreSQL migration creates a private schema named `linka_private`. It is intended for access through authenticated LINKA backend services only. Do not expose these tables directly to anonymous browser clients or place database credentials in source control.

The application supplies UUIDs and authentication token hashes. The migration does not require a PostgreSQL extension and does not store raw bearer tokens, payment credentials, provider API secrets or plaintext internal message content.

## Initial migration

Initial migrations:
- `backend/api/db/migrations/0001_core_schema.sql` — core account, device, provider, number, rate, wallet, payment, call and messaging schema.
- `backend/api/db/migrations/0002_cross_account_integrity.sql` — composite ownership constraints that bind ledger events to their wallet/account, call devices to their account, message senders to conversation membership, and outbound sender numbers to their account.

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

The CI verification workflow applies all migrations twice, then inserts temporary test records and checks wallet purchase, duplicate idempotency, usage deduction, partial refund, overdraft rejection, append-only enforcement, active-number uniqueness and cross-account isolation for wallets, call devices, conversation senders and outbound sender numbers. Its test data is rolled back.

## Migration rules

- Never edit an already-applied migration to change an existing database. Add a forward migration.
- Keep schema changes in `backend/api/db/migrations/`. Once a migration has been applied, add a forward migration instead of editing it.
- Add assertions and transactional behavior checks under `backend/api/db/checks/`.
- Do not add real provider credentials, raw payment details or customer data to migrations or fixtures.
- Use database transactions and row locking for balances, number assignment and payment reconciliation.
- Keep provider prices versioned and time-bounded; a cached or historical tariff must not be presented as current.
- Restrict production grants to backend service roles and review database permissions before enabling any public API access.

## Current status

**Database and account-authentication verification: PASSED.** PostgreSQL Schema CI run 42617603718 completed successfully on 2026-11-09 against PostgreSQL 17. It reapplied migrations 0001 and 0002 twice, passed wallet and cross-account checks, ran 65 tests with 65 passed and 0 failed, and verified the API readiness probe.

The PostgreSQL store supports account creation with normalized unique email and a versioned salted scrypt password hash; binding the first signed P-256 device; logging into the same account from an additional signed device; rejecting a device fingerprint already bound to another account or a revoked device; persisting account sessions with SHA-256 token hashes and account/device foreign keys; authenticating active unrevoked sessions; revoking sessions on logout; and performing server-side wallet operations.

The API routes `POST /v1/accounts/register`, `POST /v1/accounts/login`, `POST /v1/accounts/recovery` (explicitly 501 until email delivery is configured), `GET /v1/me` and `POST /v1/auth/logout` use these account/device/session checks. Registration/login require a fresh P-256 device challenge; the API never trusts a caller-supplied device ID or public key.

Password recovery and email verification are not implemented, accounts remain unverified and no mail provider is configured. No public wallet HTTP endpoints are exposed. Short-lived device challenge sessions and signaling queues remain in-memory. Production database hosting, provider number inventory, carrier usage, payment webhooks and live telephony remain unintegrated.
