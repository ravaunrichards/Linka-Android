# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified current main head at this checkpoint update: 08d8d95b1c5ddeb86b33e798b4cfa39fcefc611e.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 126 entries, not truncated.
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

- 8f62f1d978da81c16fbc13a5fc597c05d06e48e7 — added composite foreign keys to bind wallet ledger events, call devices, internal-message senders and outbound sender numbers to the correct account.
- 770bcd04e2b95390903a3bb32c12980ba88f1bf9 — fixed the PostgreSQL verification script's cross-account SQL block boundaries; API, Android and PostgreSQL CI passed.
- cecf5b4ee9f3c2ca3239e92f85e55710eca0fb80 — added the optional Node `pg` pool/store, wallet integration tests, API readiness and PostgreSQL integration workflow.
- b60e0ae63eb47729573244fd2ccb37cc7bd4e6cd — made readiness verify that migration 0002 is recorded as well as the wallet function.
- 08d8d95b1c5ddeb86b33e798b4cfa39fcefc611e — extended PostgreSQL CI path filters to cover persistence-adapter changes, tests and package updates.

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

The PostgreSQL schema, cross-account constraints and Node PostgreSQL adapter are now API/DB CI VERIFIED. The adapter supports schema-aware readiness, wallet creation/snapshot reads and parameterized wallet events through the atomic SQL function. The API deliberately does not expose wallet HTTP routes because signed devices are not yet linked to authenticated accounts. Durable account registration, account/device/session binding, payment processing and provider feeds remain unfinished.

These are provider-independent, tested foundations. They do not provide a live provider tariff feed, actual number inventory, production database hosting, authenticated account recovery, payment authorization/webhooks, public wallet APIs, Android/web wallet UI or operational carrier routes. No LINKA customer prices are set by these modules.

## Latest verified checkpoint summary

- Current main: `08d8d95b1c5ddeb86b33e798b4cfa39fcefc611e`.
- Full-product progress: **38% complete | 62% remaining**.
- Latest PostgreSQL integration: run 42615498199, passed 58/58 on PostgreSQL 17.
- Latest API regression: run 42615391308, passed for the Postgres adapter/readiness source.
- Latest Android CI for current main (run 42615498191) was pending when this checkpoint was prepared.
- Next action: durable account registration/authentication and account/device/session binding.

## Current CI and failure recovery

- PostgreSQL Schema CI run 42615498199 for main commit 08d8d95 completed successfully on 2026-11-09. It applied migrations 0001 and 0002 twice, passed schema, wallet and cross-account constraint checks, ran 58 Node/PostgreSQL tests with 58 passed and 0 failed, and confirmed `/readyz` reports PostgreSQL readiness.
- LINKA API CI run 42615391308 for source commit b60e0ae completed successfully; API test suite and no-database server startup/readiness checks passed.
- Android CI run 42615498191 for main commit 08d8d95 was pending at checkpoint time; do not count it as passed until GitHub confirms the conclusion.
- Prior PostgreSQL Schema CI run 42615244252 for commit cecf5b4 also passed its real Node/PostgreSQL integration and readiness checks.
- Historical API CI run 42610320645 failed because an overflow test expected a valid MAX_SAFE_INTEGER balance to fail. The test was corrected in commit f647d7267432c89bc355e4008c12343093393100, whose API CI passed.
- Historical API CI run 42611182288 failed because the rate-prefix validator rejected the bare +1 calling-code prefix. The validator was corrected in commit 53772274ae6b4389ec60fcf00c4174c8e450c293, whose API CI passed.
- Historical PostgreSQL run 42613752681 failed because its cross-account test block was inserted with malformed `DO DO` syntax. Commit 770bcd0 corrected the SQL block; later run 42613856798 passed all ownership tests.
- Historical PostgreSQL run 42612619048 failed because the CI device public-key fixture was shorter than the schema's declared minimum. Commit 03d07a2 corrected it; run 42612753799 then passed.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages; its upload/deploy steps were skipped. The Pages workflow was later removed and the repository documents a single Render origin.
- No external Render dashboard, carrier account, payment account, tariff feed or device lab was accessed during this GitHub-only pass.

## Full LINKA product progress tracker

This is the weighted tracker for the full LINKA product and its repository implementation. The weights total 100%. It is separate from the earlier repository-organization milestone, which remains complete.

| Milestone | Weight | Verified credit | Remaining |
|---|---:|---:|---:|
| Repository organization, requirements and engineering documentation | 10% | 10% | 0% |
| Existing Android offline direct/mesh source and automated verification | 12% | 8% | 4% |
| Complete Android phone experience and native system integration | 9% | 2% | 7% |
| Backend identity/signaling, schema integrity and PostgreSQL adapter | 8% | 7% | 1% |
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
| **TOTAL** | **100%** | **38%** | **62%** |

**Current full-product completion: 38% | Remaining: 62%.**

The scope-wide full-product baseline was 35%; the prior checkpoint was 36% after migration 0001 passed. Two additional verified backend parts now credit two points: (1) migration 0002 enforces account ownership through composite foreign keys and PostgreSQL CI passes negative cross-account tests; (2) the Node API uses a bounded PostgreSQL pool for wallet operations and passed real PostgreSQL integration tests plus schema-aware readiness. The backend milestone is now 7/8 points; its remaining point covers durable account registration/linking and authenticated persistent sessions. No credit is claimed for public wallet endpoints, payment settlement, live carrier/number providers, physical-device validation or production release.

## Easy, medium and hard work remaining

### Easy
- Keep documentation and integration status synchronized with source.
- Maintain focused API unit tests and verify all local Markdown links.
- Complete Android CI for the latest main commit.
- Add missing API contract tests and negative-path validation where no external provider is required.

### Medium
- Implement account registration/login/recovery and bind signed device identities to accounts and durable PostgreSQL sessions before exposing account-scoped wallet services.

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

After the latest Android CI conclusion is recorded, continue with the next medium backend step: implement account registration/authentication and securely bind signed device identities to durable account/session records. Keep passwords hashed, bearer tokens hashed at rest, use transaction-safe account/device/session writes, and do not expose wallet HTTP routes until the caller's account is cryptographically bound.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.
