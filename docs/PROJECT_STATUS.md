# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified main head immediately before this checkpoint documentation refresh: 60ec4c77e938867f53e164d5e6623461ff92d397.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 141 entries, not truncated.
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
- 4633d10c307fce56f1458f5ffef7e01571f84ab9 — added duplicate-email registration rejection to the HTTP/PostgreSQL integration test; API, PostgreSQL and Android CI passed.
- 92f900d77a4b20e0e1541c55a18aa46ada0dfe5b — updated the recovery checkpoint before account-client UI work.
- 316db4bc4363526827c22553730988b0016e557d — added Android account registration/login UI and the Keystore-backed account client.
- 9f291b73f2f97e9cd2ae6a6965337f1bf3333e3c — added browser account registration/login, non-exportable WebCrypto device identity and ECDSA signature conversion.
- 124cfdb60b5d24ad77576bc72bb331fb48e5f316 — expanded API CI to syntax-check browser modules and verify both browser authentication assets are served.
- 4a901337d42f51d45747f65e1c23ee911026d73b — upgraded Android workflow actions to checkout v7, setup-java v6, setup-gradle v6 and upload-artifact v7.
- 31500317daa2e408419b27dd203e31d10e2ef9d4 — upgraded API workflow actions to checkout v7 and setup-node v7.
- 3ddd892181f13de5fa5edb13d6df6b9c9af36572 — upgraded PostgreSQL workflow checkout to v7.
- 76401c9311a25a64beec974a10794df3edaf5f49 — added API CI checks for browser account form controls and module entry point.
- 94b69d192ba720f805657f8b83b3120f960899f4 — replaced deprecated Backspace and Message icons with AutoMirrored variants; Android CI passed.
- 68813c280a5ff8679aa5f4508f92d75a91edcc20 — upgraded the Android CI Gradle distribution to 8.14.3; build and tests passed, but KGP warned that 8.14.4 is the minimum before Kotlin 2.5.0.
- 312aa4677baab63d3677b3d73718e97d3847eb2e — upgraded the Android CI Gradle distribution to 8.14.4, the KGP-stated minimum and a Gradle patch release containing repository-resolution security fixes; Android CI passed on the exact commit.
- 9062f26e42691a272cbcae2890890b4b134eda2b — added the browser account-session invalidation policy helper.
- abb6a349b06b3ad5f0d9871c855ee3bc23e77c46 — preserved browser session tokens after transient API/network errors; only HTTP 401 clears the cached token.
- 812fa3982b235fb79397f4e2281685f734a97cd6 — added a unit test for the browser session invalidation policy; API CI passed.
- 9eb9cba8756cb76233f78307db961118d06f1e7c — added strict CORS origin policy parsing and response headers.
- ce82a8b13d9f81e1d08e9a522b2ba5c61e8df737 — added tests for same-origin, allowlisted and rejected CORS origins.
- 3995e47d3ca51df396c2d6ecb25ac4df6b33b49b — enforced CORS policy and OPTIONS preflight handling in the API server.
- 4e0b0e90776494a22fac80612b27464bc33d7473 — added API CI preflight checks; the first run failed one wildcard-origin assertion.
- 60ec4c77e938867f53e164d5e6623461ff92d397 — fixed explicit wildcard rejection; API CI passed all 75 tests and allowed/denied preflight checks. The repository tree now contains 141 entries, including the CORS policy module and its tests.

Documentation recovery commits after that tested source revision:
- 935080fe199b64bb1896ee125880ab451cdd5460 — corrected README account-client implementation status.
- bb58573d5bf67b1cba1a5392bf653927ee1476c4 — documented Android and browser account session storage.
- e2ea64ab167eeef343f759645a8cc0babf795d9d — refreshed the integration matrix with current CI evidence.
- aefbe54bf64d614ed42f64e5ed1f21ac71f33ac8 — updated the repository tree and ownership map.
- ae2e1110311673b6493c04e9a8de8e86ab3bebd9 — saved the 41% recovery checkpoint and current test evidence.

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
- Browser companion with account registration/login UI and browser-specific WebCrypto device identity; full web calling/messaging is not implemented.
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

- Latest API-tested main revision: `60ec4c77e938867f53e164d5e6623461ff92d397` (API CI passed with CORS policy and preflight tests). Latest Android-tested main revision: `60ec4c77e938867f53e164d5e6623461ff92d397` (Android CI passed with Gradle 8.14.4; backend-only CORS changes did not alter Android source).
- Full-product progress: **41% complete | 59% remaining**.
- PostgreSQL Schema CI run 42617603718 passed with 65/65 tests; migrations 0001/0002 were applied twice, schema/wallet/cross-account checks passed, account HTTP integration passed and PostgreSQL readiness passed.
- LINKA API CI run 42619263365 passed: browser module syntax checks, 67 passed, 0 failed, 2 database-only skips; API startup and both browser-module asset routes passed.
- Android CI run 42660779368 passed on commit `94b69d192ba720f805657f8b83b3120f960899f4`: debug APK build, unit tests, source revision metadata and artifact upload all succeeded. The deprecated icon warnings were removed; Gradle 8.9 still emitted its deprecation warning.
- Debug APK artifact `linka-debug-apk-312aa4677baab63d3677b3d73718e97d3847eb2e` (artifact ID `13693714374`, 17,843,074 bytes) is available until 2026-12-09; it is not a production-signed APK.
- Android CI run 42661787677 completed successfully on `312aa4677baab63d3677b3d73718e97d3847eb2e`: Gradle 8.14.4 setup, debug APK build, unit tests, revision metadata, artifact upload and cleanup all passed.
- Android and browser registration/login/profile/sign-out source is now present. Browser account tokens are tab-scoped; Android account tokens are AES-GCM encrypted using an Android Keystore key.
- Password recovery and email verification remain unavailable pending a configured email-delivery provider. No provider, deployment or physical-device status was live-verified.
- Next action: add focused client session restore/logout tests and a browser account/API end-to-end test using a disposable PostgreSQL CI service.

## Current CI and failure recovery

- PostgreSQL Schema CI run 42617603718 passed: migrations 0001/0002 reapplied twice, schema/wallet/cross-account checks passed, 65/65 tests passed, account HTTP integration and PostgreSQL readiness passed.
- LINKA API CI run 42663600102 passed on `60ec4c77e938867f53e164d5e6623461ff92d397`: 75 tests total, 73 passed, 0 failed and 2 database-only tests skipped without DATABASE_URL; CORS origin policy, allowed and denied preflight, browser account session policy, browser module syntax, account form controls, API startup and asset routes passed.
- Android CI run 42663600083 passed on the same commit: Gradle 8.14.4, debug APK build, Android unit-test task, source revision metadata, APK artifact upload and cleanup all succeeded.
- Latest debug APK artifact `linka-debug-apk-60ec4c77e938867f53e164d5e6623461ff92d397` (artifact ID `13695177963`, 17,843,078 bytes) expires 2026-12-09; it is a debug APK, not a production-signed release.
- PostgreSQL Schema CI run 42659791482 passed on `3ddd892181f13de5fa5edb13d6df6b9c9af36572`: 69 passed, 0 failed, 0 skipped; migrations, constraints, account integration and readiness all passed.
- Android CI run 42660779368 passed on `94b69d192ba720f805657f8b83b3120f960899f4`: build, unit tests, revision metadata and artifact upload succeeded.
- Android CI run 42661787677 passed on `312aa4677baab63d3677b3d73718e97d3847eb2e` using Gradle 8.14.4: `:app:assembleDebug` and `:app:testDebugUnitTest` both succeeded; source revision metadata and debug APK artifact upload succeeded. The Kotlin Gradle Plugin minimum-version warning seen on 8.14.3 is absent on 8.14.4; the Gradle action emits only a generic notice that 8.14.4 is not the newest Gradle major.
- The API no-database job reports PostgreSQL `not_configured`; this is expected in that CI job and does not prove a hosted production database is running.
- Historical API CI 42617260190 and PostgreSQL CI 42617260157 failed because the fake-pool test did not handle account lookup; PostgreSQL's real account HTTP integration already passed. Commit 9d870b1 added the fake query stub and the latest workflows passed.
- Historical PostgreSQL run 42617062392 failed because a device-reuse HTTP conflict returned 400 rather than 409; commit 27b5989 fixed the store error helper to preserve status codes. The account HTTP test then passed.
- Historical API run 42610320645 failed because an overflow test expected a valid MAX_SAFE_INTEGER balance to fail; commit f647d726 corrected the assertion and later API CI passed.
- Historical API run 42611182288 failed because the rate-prefix validator rejected a bare +1 prefix; commit 53772274 corrected the validator and later API CI passed.
- Historical PostgreSQL run 42613752681 failed because its cross-account SQL test block was malformed `DO DO`; commit 770bcd0 fixed it and later PostgreSQL CI passed.
- Historical PostgreSQL run 42612619048 failed because a test public-key fixture was shorter than the schema's minimum; commit 03d07a2 fixed it and later PostgreSQL CI passed.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages; upload/deploy were skipped. The workflow was later removed in favor of the documented Render origin.
- No external Render, email, carrier, payment, tariff or device-lab service was live-verified. Password reset and email verification are therefore not enabled.
- A GitHub-only Markdown relative-link check passed for README.md and the four updated account, integration, repository-structure and project-status documents: 5 files checked, 0 broken relative links.
- GitHub Actions action-major maintenance is complete and verified by API CI, PostgreSQL CI and the Android CI run on commit `94b69d1`. Gradle 8.14.3 passed build and tests but emitted a KGP warning recommending 8.14.4 before Kotlin 2.5.0. Gradle 8.14.4 then passed Android CI and removed that KGP minimum-version warning.

## Full LINKA product progress tracker

This is the weighted tracker for the full LINKA product and its repository implementation. The weights total 100%. It is separate from the earlier repository-organization milestone, which remains complete.

| Milestone | Weight | Verified credit | Remaining |
|---|---:|---:|---:|
| Repository organization, requirements and engineering documentation | 10% | 10% | 0% |
| Existing Android offline direct/mesh source and automated verification | 12% | 8% | 4% |
| Complete Android phone experience and native system integration | 9% | 3% | 6% |
| Backend identity/signaling, schema integrity, PostgreSQL adapter and account authentication | 8% | 8% | 0% |
| Free-first route policy and international number normalization | 6% | 3% | 3% |
| Wallet credit-ledger domain | 8% | 4% | 4% |
| Provider-fed rate catalog and usage estimation | 6% | 3% | 3% |
| Provider-backed Jamaican/international number inventory and SIP/PSTN | 10% | 0% | 10% |
| End-to-end internet audio/video media and clients | 7% | 0% | 7% |
| Internal chat and external SMS/MMS | 6% | 0% | 6% |
| Payment processing, refunds and settlement reconciliation | 6% | 0% | 6% |
| Full account-based website and browser calling | 5% | 2% | 3% |
| Supported SIM/eSIM and complete automatic connection switching | 3% | 0% | 3% |
| Independent security, compliance, physical field tests and production release | 4% | 0% | 4% |
| **TOTAL** | **100%** | **41%** | **59%** |

**Current full-product completion: 41% | Remaining: 59%.**

The previous verified checkpoint was 39%. Two additional points are credited for Android and browser account registration/login experiences now present in source and passing their respective CI workflows: one point in the Android phone experience milestone and one point in the website milestone. The backend account milestone remains 8/8 based on PostgreSQL HTTP integration proof. Android CI proves compilation, debug APK assembly and unit-test task success; API CI proves browser-module syntax, browser crypto tests, API startup and asset serving. Neither CI run proves physical Android sign-in or a real browser sign-in against a hosted production database. Email verification/password recovery, public wallet endpoints, payment settlement, carrier/number providers, physical-device calling validation and production release remain unfinished.

## Easy, medium and hard work remaining

### Easy
- Keep documentation and integration status synchronized with source.
- Maintain focused API unit tests and verify all local Markdown links.
- Keep repository documentation and integration status synchronized with source and CI evidence.
- Add focused Android session restore/logout tests and a browser account/API end-to-end test against a disposable PostgreSQL CI service.
- Add focused browser account lifecycle tests and Android account-session restore/logout tests.
- Add API contract and negative-path validation where no external provider is required.

### Medium
- Add focused account-session restore/logout tests for Android and browser clients. Keep account-scoped wallet HTTP routes disabled until client and authorization tests are complete.

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

Continue with focused Android account-session restore/logout tests and a browser account/API end-to-end test against a disposable PostgreSQL CI service. Keep password reset and email verification disabled until a real email-delivery provider and one-use reset/verification-token workflow are configured.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.
