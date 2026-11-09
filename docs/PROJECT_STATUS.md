# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified current main head at this checkpoint update: 4633d10c307fce56f1458f5ffef7e01571f84ab9.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 129 entries, not truncated.
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
- 31e7c573207af9c21cc9e79d7e3b45738506cfbb — added scrypt password handling, signed-device account registration/login store methods, persistent account sessions and HTTP routes, plus a real PostgreSQL HTTP integration test.
- 27b598903b2b66b0808c8023833a3b4dee139a23 — preserved intended 401/409 account authentication HTTP status codes.
- 9d870b18c6533d99d32192eb980d33fae7fde130 — corrected the fake-pool login lookup in the store unit test.
- 4633d10c307fce56f1458f5ffef7e01571f84ab9 — added duplicate-email registration rejection to the HTTP/PostgreSQL integration test; latest API, PostgreSQL and Android CI passed.

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

The PostgreSQL schema, cross-account constraints, Node PostgreSQL adapter and account authentication are now ACCOUNT/DB CI VERIFIED. Registration and login require an existing P-256 signed-device session. Account passwords use salted scrypt hashes; persistent bearer sessions are stored as SHA-256 hashes and tied to account/device records. The HTTP/PostgreSQL integration test verifies multi-device login, duplicate device/email rejection, token-hash storage and logout revocation. Public wallet HTTP routes remain disabled. Password recovery/email verification, payment processing and provider feeds remain unfinished.

These are provider-independent, tested foundations. They do not provide a live provider tariff feed, actual number inventory, production database hosting, authenticated account recovery, payment authorization/webhooks, public wallet APIs, Android/web wallet UI or operational carrier routes. No LINKA customer prices are set by these modules.

## Latest verified checkpoint summary

- Current main: `4633d10c307fce56f1458f5ffef7e01571f84ab9`.
- Full-product progress: **39% complete | 61% remaining**.
- PostgreSQL Schema CI run 42617603718 passed on 2026-11-09 with 65/65 tests; account registration, device binding, duplicate-email rejection, password login, second-device login, persistent hashed sessions and logout all passed over HTTP.
- LINKA API CI run 42617603655 passed: 63 pass, 0 fail, 2 database-only skips.
- Android CI run 42617603672 passed debug APK build, unit tests, revision metadata and artifact upload.
- Password recovery and email verification remain unavailable pending a configured email-delivery provider.
- Next action: implement the Android and website registration/login UI and secure token storage, then design email verification/recovery when a provider can be configured.

## Current CI and failure recovery

- PostgreSQL Schema CI run 42617603718 on commit 4633d10 completed successfully on 2026-11-09. It reapplied migrations 0001/0002 twice, passed schema/wallet/cross-account checks, ran 65 tests with 65 passed and 0 failed, verified account HTTP integration and confirmed PostgreSQL readiness.
- LINKA API CI run 42617603655 on commit 4633d10 completed successfully: 63 passed, 0 failed, 2 database-only tests skipped without DATABASE_URL; API/no-DB startup and routes passed.
- Android CI run 42617603672 on commit 4633d10 completed successfully: debug APK build, Android unit tests, source revision metadata and APK artifact upload all passed.
- Historical API CI 42617260190 and PostgreSQL CI 42617260157 failed because the fake-pool test did not handle account lookup; PostgreSQL's real account HTTP integration already passed. Commit 9d870b1 added the fake query stub and the latest workflows passed.
- Historical PostgreSQL run 42617062392 failed because a device-reuse HTTP conflict returned 400 rather than 409; commit 27b5989 fixed the store error helper to preserve status codes. The account HTTP test then passed.
- Historical API run 42610320645 failed because an overflow test expected a valid MAX_SAFE_INTEGER balance to fail; commit f647d726 corrected the assertion and later API CI passed.
- Historical API run 42611182288 failed because the rate-prefix validator rejected a bare +1 prefix; commit 53772274 corrected the validator and later API CI passed.
- Historical PostgreSQL run 42613752681 failed because its cross-account SQL test block was malformed `DO DO`; commit 770bcd0 fixed it and later PostgreSQL CI passed.
- Historical PostgreSQL run 42612619048 failed because a test public-key fixture was shorter than the schema's minimum; commit 03d07a2 fixed it and later PostgreSQL CI passed.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages; upload/deploy were skipped. The workflow was later removed in favor of the documented Render origin.
- No external Render, email, carrier, payment, tariff or device-lab service was live-verified. Password reset and email verification are therefore not enabled.

## Full LINKA product progress tracker

This is the weighted tracker for the full LINKA product and its repository implementation. The weights total 100%. It is separate from the earlier repository-organization milestone, which remains complete.

| Milestone | Weight | Verified credit | Remaining |
|---|---:|---:|---:|
| Repository organization, requirements and engineering documentation | 10% | 10% | 0% |
| Existing Android offline direct/mesh source and automated verification | 12% | 8% | 4% |
| Complete Android phone experience and native system integration | 9% | 2% | 7% |
| Backend identity/signaling, schema integrity, PostgreSQL adapter and account authentication | 8% | 8% | 0% |
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
| **TOTAL** | **100%** | **39%** | **61%** |

**Current full-product completion: 39% | Remaining: 61%.**

The scope-wide full-product baseline was 35%; the prior checkpoint was 38%. One additional point is now credited because account registration and password login are integrated with PostgreSQL and passed real HTTP tests using P-256 signed devices. The test proves account/device binding, duplicate-device and duplicate-email rejection, multi-device login, password verification, SHA-256 bearer-token hashes at rest, persistent-session authentication and logout revocation. The backend milestone is now 8/8. Email verification/password recovery, public wallet endpoints, payment settlement, carrier/number providers, physical-device calling validation and production release remain unfinished and are not counted here.

## Easy, medium and hard work remaining

### Easy
- Keep documentation and integration status synchronized with source.
- Maintain focused API unit tests and verify all local Markdown links.
- Complete Android CI for the latest main commit.
- Add missing API contract tests and negative-path validation where no external provider is required.

### Medium
- Add Android and website registration/login screens, secure account-token storage and account-profile integration. Keep account-scoped wallet HTTP routes disabled until client and authorization tests are complete.

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

Continue with the next medium client integration task: add Android and website registration/login flows that first complete the existing signed-device challenge, then call the verified account endpoints and store persistent account tokens using platform-secure storage. Add client-side session restore/logout tests. Separately, configure an email provider before implementing password reset or verification delivery.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.
