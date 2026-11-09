# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified source baseline before this checkpoint update: a230d168de391b3d209865eb8c51bbbc3c5aa03c.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 122 entries, not truncated.
- GitHub permission snapshot during recovery: admin, maintain and push were true.
- Recovery confidence: CONFIRMED for branch, commit ancestry, repository files, commit changes, issue records and Actions results. A local working tree is not exposed by the GitHub read APIs.

## Recovery labels

- RECOVERED — present in prior task context or durable GitHub records.
- VERIFIED — re-read from GitHub during the current recovery.
- HISTORICAL — a dated GitHub Actions, issue or repository event.
- INFERRED — reasonable reconstruction not directly confirmed.
- UNKNOWN — not established by available evidence.

## Recovery from the prior checkpoint

The previous saved checkpoint was at repository commit 36ef4745a13e48c37b89bead9c6497e66993520c and recorded the repository-organization phase as complete. Since that checkpoint, additional commits appeared on main and were recovered rather than overwritten:

- 71deee602161d6a1ac239d92f8868a423eb347c3 — added the tested immutable wallet credit-ledger domain, its tests and design documentation.
- 7cf935f3fc5137e8c7756f4f703997dbb6e06646 — added a combined-credit-balance overflow guard and extended the wallet-ledger documentation.
- f647d7267432c89bc355e4008c12343093393100 — corrected the wallet overflow test: a balance exactly at Number.MAX_SAFE_INTEGER is valid; only a genuinely overflowing combined snapshot should fail.
- e30a9087fec46c43038f0463877b05961120178d — fixed literal escaped-newline separators that had merged two integration-matrix rows.
- f16f2a36d07ab19c52fdbb937335cb5604d2ce76 — added a provider-fed rate catalog and credit-estimate domain with unit tests.
- 53772274ae6b4389ec60fcf00c4174c8e450c293 — corrected rate-prefix validation to permit a bare calling-code prefix such as +1.
- a230d168de391b3d209865eb8c51bbbc3c5aa03c — made message quotes explicitly reject voice tariffs.

- 98e8eda41d0689e296169ffc5c971a34660286eb — added the PostgreSQL core schema, atomic wallet ledger function, SQL checks and PostgreSQL 17 CI workflow.
- 03d07a2cb3ca921e4c2922a3146d8312ac87129a — corrected the SQL CI device public-key fixture to the schema's minimum byte length; PostgreSQL and API CI passed.

No source from those commits was discarded. All are ancestors of the verified main branch baseline.

## Previous project work preserved

Closed and merged pull requests:
- PR #1 — LINKA Android improvements and responsive web companion.
- PR #3 — secure direct and multi-hop offline voice calling.
- PR #4 — premium phone hub and Android system integrations.

The existing implementation still contains:
- Kotlin/Jetpack Compose Android application.
- Nearby Connections discovery, advertising and pairing.
- Android Keystore device identity and signed QR identity.
- Authenticated peer/session security.
- Direct encrypted voice transport.
- Multi-peer/multi-hop offline route source with local relay consent.
- Android phone hub with keypad and system-app handoffs for dialer, SMS, contacts and recent calls.
- Unit tests for call control/state, audio framing, peer security and mesh routing.
- Node.js signed-device challenge and ephemeral call-signaling API.
- Static browser companion.
- Android CI and API CI workflows.
- Architecture, compatibility, security, mesh, testing and Render service documentation.

Open issue #2 remains the physical-device, security-review and release-gate tracker. It still includes two-phone direct audio proof, four-or-more-device relay proof, Android lifecycle/audio checks, independent cryptographic review, non-GMS scope, Android Telecom/default-dialer and release signing.

## New backend domain capabilities recovered and verified

The backend now includes:
- `backend/api/src/domain/e164.mjs` — strict international-format normalization of explicitly supplied phone numbers. It does not identify country from +1.
- `backend/api/src/domain/route-options.mjs` — free-first option construction, exact-quote paid consent and bounded paid auto-fallback policy.
- `backend/api/src/domain/credit-ledger.mjs` — immutable in-memory wallet reducer with separate purchased/promotional/sponsored buckets, idempotency checks, usage deductions, capped refunds and safe-integer balance limits.
- `backend/api/src/domain/rate-catalog.mjs` — validation of provider-supplied tariff records, active-rate selection by provider/country/number-type/longest prefix and credit estimates for voice/SMS/MMS.
- `backend/api/db/migrations/0001_core_schema.sql` — PostgreSQL core tables for accounts, devices, sessions, providers, telephone-number inventory, rate versions, wallets, ledger events, payments/webhooks, calls and messaging.
- `backend/api/db/checks/verify_core_schema.sql` and `.github/workflows/database-ci.yml` — PostgreSQL 17 workflow that reapplies the migration and tests schema/wallet transaction invariants.

The PostgreSQL schema and SQL wallet function are now DATABASE CI VERIFIED. They are not yet connected to a PostgreSQL pool in the Node API; authenticated account/wallet endpoints, payment processing and provider feeds remain unfinished.

These are provider-independent, tested domain foundations. They do not provide a live provider tariff feed, actual number inventory, persistence, concurrent database transactions, payment authorization/webhooks, user wallet APIs, Android/web UI or operational carrier routes. No LINKA customer prices are set by these modules.

## Current CI and failure recovery

- API CI run 42611442996 for commit a230d168de391b3d209865eb8c51bbbc3c5aa03c completed successfully. Its test log reports 49 tests passed, 0 failed; API start/readiness and browser route checks also passed.
- Android CI run 42611442986 was queued/pending when this checkpoint was prepared; do not report it as passed until GitHub confirms its conclusion.
- Historical API CI run 42610320645 failed because an overflow test expected a valid MAX_SAFE_INTEGER balance to fail. The implementation was retained; the test was corrected in commit f647d7267432c89bc355e4008c12343093393100, whose API CI passed.
- Historical API CI run 42611182288 failed because the rate-prefix validator rejected the bare +1 calling-code prefix. The validator was corrected in commit 53772274ae6b4389ec60fcf00c4174c8e450c293, whose API CI passed.
- These failures and fixes are retained in history; they are not hidden or counted as successful runs.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages; its upload/deploy steps were skipped. The Pages workflow was later removed and the repository documents a single Render origin.
- PostgreSQL Schema CI run 42612753799 for commit 03d07a2 completed successfully on 2026-11-09. It applied the migration twice and passed schema, wallet purchase/idempotency/usage/refund/overdraft/append-only and active-number uniqueness checks; temporary test rows were rolled back.
- LINKA API CI run 42612753732 for commit 03d07a2 completed successfully on 2026-11-09.
- Android CI run 42612753713 for commit 03d07a2 completed the debug APK build, unit tests, source metadata and artifact upload successfully; post-job cleanup was still running at the time this checkpoint was drafted.
- The first PostgreSQL run 42612619048 failed because the CI device public-key fixture was shorter than the schema's declared minimum. Commit 03d07a2 corrected the fixture; the succeeding PostgreSQL run passed.
- No external Render dashboard, carrier account, payment account, tariff feed or device lab was accessed during this GitHub-only pass.

## Full LINKA product progress tracker

This is the weighted tracker for the full LINKA product and its repository implementation. The weights total 100%. It is separate from the earlier repository-organization milestone, which remains complete.

| Milestone | Weight | Verified credit | Remaining |
|---|---:|---:|---:|
| Repository organization, requirements and engineering documentation | 10% | 10% | 0% |
| Existing Android offline direct/mesh source and automated verification | 12% | 8% | 4% |
| Complete Android phone experience and native system integration | 9% | 2% | 7% |
| Backend identity/signaling and durable schema foundation | 8% | 5% | 3% |
| Free-first route policy and international number normalization | 6% | 3% | 3% |
| Wallet credit-ledger domain | 8% | 4% | 4% |
| Provider-fed rate catalog and usage estimation | 6% | 3% | 3% |
| Provider-backed Jamaican/international number inventory and SIP/PSTN | 10% | 0% | 10% |
| End-to-end internet audio/video media and clients | 7% | 0% | 7% |
| Internal chat and external SMS/MMS | 6% | 0% | 6% |
| Payment processing, refunds and settlement reconciliation | 6% | 0% | 6% |
| Full account-based website and browser calling | 5% | 1% | 4% |
| Supported SIM/eSIM and complete automatic connection switching | 3% | 0% | 3% |
| Independent security, compliance, physical field tests and production release | 4% | 0% | 4% |
| **TOTAL** | **100%** | **36%** | **64%** |

**Current full-product completion: 36% | Remaining: 64%.**

The initial scope-wide weighted baseline was 35%. One additional point is now credited in the backend milestone because the durable PostgreSQL schema and atomic wallet function were executed successfully in PostgreSQL 17 CI, including migration reapplication and wallet/constraint checks. The Node API is not yet connected to the database, so no further database-runtime credit is claimed. Partial credit remains limited to source and automated-test evidence; provider integrations, physical-device verification and production release require separate proof.

## Easy, medium and hard work remaining

### Easy
- Keep documentation and integration status synchronized with source.
- Maintain focused API unit tests and verify all local Markdown links.
- Complete Android CI for the latest main commit.
- Add missing API contract tests and negative-path validation where no external provider is required.

### Medium
- Connect domain policy to authenticated backend services.
- Connect the verified PostgreSQL schema and wallet SQL function to the Node API using a pooled, authenticated database adapter and transaction-safe service methods.
- Add provider adapter contracts and import/refresh interfaces without fabricating live provider data.
- Integrate Android and web clients with supported backend capabilities.

### Hard / externally dependent
- Jamaican and international number provisioning and SIP/PSTN carrier agreements.
- Actual external SMS/MMS delivery providers.
- WebRTC media path, TURN and production network integration.
- Payment processor onboarding, signed webhooks, settlement/refund handling.
- SIM/eSIM operator provisioning and supported automatic routing.
- Physical two-phone and multi-relay field tests, independent cryptographic review, telecom/regulatory reviews and release signing.

## Exact next action

After the latest Android CI conclusion is recorded, continue with the next medium backend step: connect the already-tested PostgreSQL schema to Node API services using a pooled connection, authenticated wallet/account operations and integration tests. Keep runtime credentials out of Git and do not claim real provider availability.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.
