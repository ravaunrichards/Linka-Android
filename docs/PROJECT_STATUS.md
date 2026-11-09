# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified main head at checkpoint: `3982caf5d88d6286a5567354ff52841b06348afa` (main); latest browser test/workflow code verified by PostgreSQL/Chromium/Firefox CI: `81f0d0eb4903787ba2e91395013eb9f765991f88`.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 145 entries, not truncated.
- GitHub permission snapshot during recovery: admin, maintain and push were true.
- Recovery confidence: CONFIRMED for branch, commit ancestry, repository files, commit changes, issue records and Actions results. A local working tree is not exposed by the GitHub read APIs.

## Recovery labels

- RECOVERED — present in prior task context or durable GitHub records.
- VERIFIED — re-read from GitHub during the current recovery.
- HISTORICAL — a dated GitHub Actions, issue or repository event.
- INFERRED — reasonable reconstruction not directly confirmed.
- UNKNOWN — not established by available evidence.


## Continuation update — 2026-11-09

This continuation resumed directly from the existing LINKA repository; no earlier chat transcript was required. Changes were made on the existing `main` branch and verified against GitHub Actions:

- `472a590500e19f657746db77a0d15f5bac36ad07` adds two Android account-client edge-case tests: logout with no local token makes no network request, and non-local API origins require HTTPS. The account-client test file now contains nine test cases.
- `12221854e5991e436a40aa9f5819c93507c21866` adds the real Playwright browser UI lifecycle test for IndexedDB device-key persistence, tab-session restore, second-device login, independent logout, recovery messaging and uncaught page errors.
- `814be0cd77904872e941133a76d9830dc7ba9b97` pins Playwright 1.65.0 and adds the browser UI test command.
- `e9cacd9f8b53f221eb125733b2836a6fd8fc84a7` wires Chromium UI automation into the disposable-PostgreSQL workflow.
- `ccae0c1a0b86c0a39ed71d68b3743766ed67e293` makes the browser lifecycle test selectable between Chromium and Firefox.
- `81f0d0eb4903787ba2e91395013eb9f765991f88` installs both browsers and runs both lifecycle tests in PostgreSQL CI.
- `b49a89a4214c87400da65a54c9c3aff9f52f7d36` documents the Chromium/Firefox browser test commands.
- `3982caf5d88d6286a5567354ff52841b06348afa` records the latest continuation checkpoint.

Independent verification:
- Android CI run [#42670913670](https://github.com/ravaunrichards/Linka-Android/actions/runs/42670913670) passed on the two new account-client edge cases.
- PostgreSQL/Chromium/Firefox CI run [#42680769129](https://github.com/ravaunrichards/Linka-Android/actions/runs/42680769129) passed on `81f0d0eb4903787ba2e91395013eb9f765991f88`: migrations were applied twice; 75 backend/database tests passed, 0 failed; Node WebCrypto account E2E, Chromium UI E2E and Firefox UI E2E each printed PASS.
- LINKA API CI run [#42680765486](https://github.com/ravaunrichards/Linka-Android/actions/runs/42680765486) passed on `ccae0c1a0b86c0a39ed71d68b3743766ed67e293`: 73 passed, 0 failed and 2 PostgreSQL-only tests skipped in the no-database workflow.
- Android CI run [#42680782249](https://github.com/ravaunrichards/Linka-Android/actions/runs/42680782249) passed on `3982caf5d88d6286a5567354ff52841b06348afa`: debug APK assembly and Android unit-test task succeeded and the build artifact was uploaded.
- Current weighted product progress is 44% complete / 56% remaining. One point was credited when the real Chromium browser UI account lifecycle test passed; Firefox is additional compatibility evidence within that same browser milestone and does not add duplicate credit.

The browser tests verify account registration, non-exportable IndexedDB P-256 key persistence, tab-scoped session restoration across reload, isolated second-device login with a distinct identity/token, independent per-device logout, the explicit password-recovery limitation and zero uncaught browser page errors. They do not verify hosted production origins, complete web calling or physical Android behavior.

Render remains unverified because no workspace has been selected; the only returned workspace is named "My Workspace", and user confirmation is still required before inspecting or modifying it. Physical radio/audio tests, provider integrations, production signing and production release remain open.


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
- 60ec4c77e938867f53e164d5e6623461ff92d397 — fixed explicit wildcard rejection; API CI passed all 75 tests and allowed/denied preflight checks. The repository tree then contained 141 entries, including the CORS policy module and its tests.
- fa827d8cdacbbf64ea00ec68320ed6d6465fe211 — refactored `LinkaAccountClient` to accept testable token-store, device-identity and API-transport interfaces while preserving the production Android Keystore/HTTP adapters.
- 68d9f1382b16f6b95a4aa8f383b3873eeea4ce2f — added Android account session restore/logout tests; its first Android CI run failed two tests because JVM tests were using Android's mocked `org.json` classes.
- b205708cd0c8ba5e08068e9ebfbc63eb16cdede8 — added a browser-WebCrypto account E2E against the API and disposable PostgreSQL; API and PostgreSQL workflows passed, while the first Android test run still exposed the JSON test-runtime issue.
- ebe1490459da2b758cb7f08d9012fea425ecb1aa — added a real JVM `org.json:json` test dependency; Android CI then passed debug APK assembly and the account/session unit tests.

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

- Latest Android-tested main revision: `3982caf5d88d6286a5567354ff52841b06348afa`.
- Latest API-tested revision: `ccae0c1a0b86c0a39ed71d68b3743766ed67e293`; latest PostgreSQL + Chromium/Firefox browser UI E2E-tested workflow revision: `81f0d0eb4903787ba2e91395013eb9f765991f88`.
- Full-product progress: **44% complete | 56% remaining**.
- Latest inspected repository tree: 145 entries, recursive tree not truncated.
- Render workspace inspection is blocked pending explicit workspace confirmation; no live Render configuration or deployment health is claimed.
- PostgreSQL Schema CI run 42680769129 passed on `81f0d0eb4903787ba2e91395013eb9f765991f88`: 75 passed, 0 failed, 0 skipped; migrations ran twice, schema/wallet checks and API readiness passed, and Node WebCrypto, Chromium UI and Firefox UI account E2E each printed PASS.
- LINKA API CI run 42680765486 passed on `ccae0c1a0b86c0a39ed71d68b3743766ed67e293`: 73 passed, 0 failed and 2 database-only tests skipped without DATABASE_URL; API startup and browser asset checks passed.
- Android account edge-case CI run 42670913670 passed on `472a590500e19f657746db77a0d15f5bac36ad07`, including `:app:assembleDebug` and `:app:testDebugUnitTest`.
- Latest main checkpoint Android CI run 42680782249 passed on `3982caf5d88d6286a5567354ff52841b06348afa`; debug APK assembly, `:app:testDebugUnitTest`, revision metadata and artifact upload passed.
- Latest debug APK artifact `linka-debug-apk-3982caf5d88d6286a5567354ff52841b06348afa` (artifact ID `13702851282`, 17,845,192 bytes, GitHub ZIP SHA-256 `e035a6d3a59fe77d11321a86e42f3e70542cb04e3411bab98af6b0c66d915773`) expires 2026-12-09. It is a debug APK, not a production-signed release.
- Android and browser account registration/login/profile/sign-out source is present. Android session restore/logout and API-origin validation behavior has nine JVM test cases; Chromium and Firefox both verify actual account DOM, non-exportable IndexedDB device-key persistence, tab-scoped session restoration, second-context login and independent logout.
- Password recovery and email verification remain unavailable pending a configured email-delivery provider. Carrier, payment, tariff and physical-device status have not been live-verified.
- Current verified CI state: Android runs 42670913670 and 42680782249 PASS; API run 42680765486 PASS; PostgreSQL/Chromium/Firefox run 42680769129 PASS (75/75 tests and all three browser E2E confirmation lines).

## Current CI and failure recovery

- PostgreSQL Schema CI run 42617603718 passed: migrations 0001/0002 reapplied twice, schema/wallet/cross-account checks passed, 65/65 tests passed, account HTTP integration and PostgreSQL readiness passed.
- LINKA API CI run 42665527245 passed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 75 total, 73 passed, 0 failed and 2 PostgreSQL-only tests skipped without DATABASE_URL.
- PostgreSQL Schema CI run 42665527585 passed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 75 passed, 0 failed, 0 skipped; browser-WebCrypto E2E printed its PASS confirmation.
- Android CI run 42665968552 passed on `ebe1490459da2b758cb7f08d9012fea425ecb1aa`; debug APK assembly, `:app:testDebugUnitTest`, source revision metadata and artifact upload succeeded.
- Android CI run 42665527265 failed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 53 tests completed and 2 account-client tests failed because `org.json.JSONObject` in the Android JVM test environment was a throwing stub. Commit `ebe1490` added `testImplementation("org.json:json:20240303")`; run 42665968552 then passed.
- Historical CORS run 42663401916 also failed one wildcard-origin assertion; commit `60ec4c7` corrected the parser and API CI 42663600102 subsequently passed.
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
| Complete Android phone experience and native system integration | 9% | 4% | 5% |
| Backend identity/signaling, schema integrity, PostgreSQL adapter and account authentication | 8% | 8% | 0% |
| Free-first route policy and international number normalization | 6% | 3.5% | 2.5% |
| Wallet credit-ledger domain | 8% | 4% | 4% |
| Provider-fed rate catalog and usage estimation | 6% | 3% | 3% |
| Provider-backed Jamaican/international number inventory and SIP/PSTN | 10% | 0% | 10% |
| End-to-end internet audio/video media and clients | 7% | 0% | 7% |
| Internal chat and external SMS/MMS | 6% | 0% | 6% |
| Payment processing, refunds and settlement reconciliation | 6% | 0% | 6% |
| Full account-based website and browser calling | 5% | 4% | 1% |
| Supported SIM/eSIM and complete automatic connection switching | 3% | 0% | 3% |
| Independent security, compliance, physical field tests and production release | 4% | 0% | 4% |
| **TOTAL** | **100%** | **44.5%** | **55.5%** |

**Previous verified completion: 44% | Current verified completion: 44.5% | Remaining: 55.5% | Progress since checkpoint: +0.5 percentage points.**

The previous verified checkpoint was 44%. A further 0.5 percentage point is credited to the E.164/API integration milestone: POST /v1/destinations/normalize is merged to main at 776f9c1890ace342d00ac35d10981d93e0a226ff; API CI passed on the merge commit (run 42699519290), and the pull-request test launched the real API process to verify + and 00 prefix normalization, rejection of national-format input, missing-number handling, JSON content-type enforcement and GET rejection. The response explicitly says syntax-only, destination resolution not performed, number type unknown and provider reachability not checked. Prior browser verification also includes both Chromium and Firefox DOM/IndexedDB lifecycle passes in PostgreSQL CI run 42680769129. Android CI passed on PR run 42699002701 for the same code change; the post-merge main Android workflow run 42699519172 must still be confirmed. These checks do not establish physical radio/audio behavior, hosted-origin deployment, provider inventory or commercial carrier routes. Provider-backed phone numbers, PSTN/SIP, SMS/MMS, WebRTC media relay, payments, public wallet APIs, email recovery/verification, hosted production DB and deployment health remain unfinished.

## Easy, medium and hard work remaining

### Easy
- Keep Android account-client tests synchronized with account lifecycle behavior and add new cases only when a distinct requirement is still uncovered.
- Keep current documentation and repository inventories synchronized with the verified branch.
- Continue focused API negative-path tests where no external provider is required.

### Medium
- Verify the separately hosted HTTPS browser companion and its explicit production CORS allowlist after the deployment workspace is confirmed.
- Run browser registration/login on a separately hosted test origin using the explicit CORS configuration; do not treat the current Node WebCrypto E2E as browser-rendering proof.
- Add provider adapter contracts and import/refresh interfaces without fabricating live provider data.
- Integrate Android and web clients with supported backend capabilities.

### Hard / externally dependent
- Jamaican and international number provisioning and SIP/PSTN carrier agreements.
- Actual external SMS/MMS delivery providers.
- WebRTC media path, TURN and production network integration.
- Payment processor onboarding, signed webhooks, settlement/refund handling.
- SIM/eSIM operator provisioning and supported automatic routing.
- Production Render/PostgreSQL configuration, service health and secrets supplied through the confirmed workspace.
- Physical two-phone and multi-relay field tests, independent cryptographic review, telecom/regulatory reviews and release signing.

## Exact next action

Verify the separately hosted HTTPS browser origin and explicit CORS allowlist once a Render workspace is confirmed; continue provider-independent integration work while provider access is unavailable. Keep password reset/email verification disabled until a real email provider and one-use token workflow exist.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.


## Authoritative continuation checkpoint — 2026-11-09

**Project:** LINKA Android and browser calling platform  
**Repository:** ravaunrichards/Linka-Android  
**Main branch:** main  
**Latest confirmed main commit:** 776f9c1890ace342d00ac35d10981d93e0a226ff  
**Last previous verified score:** 44.0%  
**Current verified score:** 44.5%  
**Remaining:** 55.5%  
**Progress since previous checkpoint:** +0.5 percentage points  
**Current task lock:** No source task in progress; next task is R008 destination metadata resolution  
**Latest merged feature:** PR #16, E.164 normalization HTTP endpoint

### Verified milestone evidence

- API CI run [42699002990](https://github.com/ravaunrichards/Linka-Android/actions/runs/42699002990): PASS on PR head 3ee3b8bd43847699045eda9d51217aad65a3f449; real-server HTTP tests passed, as did API startup, CORS and browser asset checks.
- Android CI run [42699002701](https://github.com/ravaunrichards/Linka-Android/actions/runs/42699002701): PASS on the same PR head; debug APK build, unit tests and artifact upload succeeded.
- API CI run [42699519290](https://github.com/ravaunrichards/Linka-Android/actions/runs/42699519290): PASS on merged main commit 776f9c1890ace342d00ac35d10981d93e0a226ff.
- Browser UI: Chromium and Firefox account lifecycle runs PASS in PostgreSQL CI run [42680769129](https://github.com/ravaunrichards/Linka-Android/actions/runs/42680769129), including non-exportable IndexedDB identity, tab session restore, second browser context, per-device logout and zero uncaught browser errors.
- Earlier main Android CI run [42682256606](https://github.com/ravaunrichards/Linka-Android/actions/runs/42682256606): PASS on main commit 8fcd0be34829c61c335376454ad1302b8e17b5d8.
- Post-merge Android CI run [42699519172](https://github.com/ravaunrichards/Linka-Android/actions/runs/42699519172): PASS on main commit `776f9c1890ace342d00ac35d10981d93e0a226ff`; debug APK build, JVM unit tests, revision metadata and artifact upload succeeded.
- The API merge commit is verified in main; PR #16 is closed and merged.
- POST /v1/destinations/normalize validates explicit international E.164 syntax only. It does not infer a country, identify mobile/landline type, prove number assignment or provider reachability, return provider pricing or place a call.
- The native phone favourites change remains isolated on draft PR #15 / feature/native-phone-favorites; the instrumentation suite and emulator CI wiring are committed but no CI result exists for its latest branch head. This work is not counted in the main-branch score.

### Master requirement register

| ID | Requirement / acceptance result | Priority | Dependency | Initial state | Implementation / affected files | Test and evidence | Result / status |
|---|---|---|---|---|---|---|---|
| R001 | Independent LINKA repo, organized architecture, requirements, security and recovery documentation | Easy | None | Existing product source needed consolidation | Root docs, docs/*, GitHub templates and CI workflows | Main repository tree and CI reviewed; docs/link checks recorded in prior checkpoints | PASS / Verified |
| R002 | Android app shell, phone hub, dial pad, contacts/recent-call handoffs and favourites | Medium | Android build; device/system handlers | Android Compose shell and system-app handoffs existed; favourites added separately | app/src/main/java/com/linka/android/PhoneHubScreen.kt and PhoneFavorites.kt on draft PR #15 | JVM codec tests/build passed on earlier PR CI; latest Keystore instrumentation run not available | PARTIAL / In progress |
| R003 | Trusted two-device direct offline encrypted voice call with bidirectional audible audio and lifecycle proof | Hard | Two physical Android devices and device lab | Security/audio source and automated protocol tests existed | app/src/main/java/com/linka/android/core/audio/* and core/security/* | JVM and protocol tests previously passed; no two-phone RF/audio result | PARTIAL / Blocked on physical devices |
| R004 | Four-or-more-device opt-in encrypted offline mesh voice routing | Hard | Four physical Android devices and field test | Mesh source, relay consent and automated route tests existed | app/src/main/java/com/linka/android/core/mesh/* and routed voice/call source | Automated route tests in CI; no field call/latency/range result | PARTIAL / Blocked on physical devices |
| R005 | Signed device auth, account registration/login, multi-device sessions, logout and PostgreSQL account integrity | Medium | PostgreSQL schema and signed device identity | Backend and account foundation partially implemented | backend/api/src/account-auth.mjs, postgres-store.mjs, server routes and migrations 0001/0002 | PostgreSQL CI and earlier account CI; Android and browser account UI tests | PASS for scoped account/session foundation / Verified; recovery is R006 |
| R006 | Secure email verification and one-use password recovery | Medium | Email-delivery provider, verified domain, token lifecycle | Recovery route deliberately returns 501; no mail provider configured | Not implemented; no sender configured | Browser tests verify explicit unavailable message/501 rather than simulated delivery | BLOCKED / External email provider required |
| R007 | E.164 syntax normalization exposed through a rate-limited API route | Easy | None | Helper existed without HTTP endpoint | backend/api/src/server.mjs, backend/api/test/e164-api.test.mjs, docs/RENDER_API_SERVICE.md | API CI 42699002990 and post-merge API CI 42699519290; real HTTP tests PASS | PASS / Verified |
| R008 | Resolve destination country/region and number type correctly, including Jamaica +1 876/+1 658 versus US/Canada +1 | Medium | Current numbering-plan metadata/library and update process | E.164 canonicalization does not infer geography or number type | Not yet implemented | Existing domain tests deliberately avoid guessing from shared +1; metadata tests required | NOT TESTED / Remaining |
| R009 | Provider-confirmed number search, acquisition/rental, renewal, release, inbound routing and caller-ID control | Hard | Carrier/virtual-number provider contracts, credentials, inventory and regulatory eligibility | Schema foundation exists; no live inventory/provider connection | telephone_numbers schema and provider roadmap; no connected operational adapter | No provider account, inventory query or lifecycle test available | BLOCKED / External provider and regulatory eligibility |
| R010 | Free-first availability, provider tariffs, service eligibility and destination usage estimates | Medium | Trusted provider routes, funding/allowance information and active tariff feed | Pure route policy, E.164 helper and tariff validation/quote domains exist | backend/api/src/domain/route-options.mjs, rate-catalog.mjs and tests | Unit tests cover free-first, quote consent, effective rates and rounding; no live provider feed | PARTIAL / Provider/API/client integration remains |
| R011 | Server-authoritative wallet, immutable ledger, idempotent atomic credit/refund operations and user-facing history | Medium | PostgreSQL and authenticated payment/provider events | In-memory reducer and SQL ledger function/schema foundations exist | credit-ledger.mjs, PostgreSQL schema and apply_wallet_event | PostgreSQL CI schema/transaction checks and unit tests passed; no public wallet endpoints or client UI | PARTIAL / API, reconciliation and UI remain |
| R012 | Real payment methods, signed provider webhooks, refunds, disputes and settlement reconciliation | Hard | Payment provider onboarding, merchant approval and secrets | No payment integration | Tables/design only; no processor adapter | No sandbox/live payment test or webhook verification | BLOCKED / External payment onboarding |
| R013 | Internal end-to-end encrypted one-to-one/group messaging, attachments and cross-device history | Medium | Account membership, client encryption/key distribution and durable Postgres store | Schema tables exist; complete API/client implementation absent | conversations, conversation_members and internal_messages schema only | No end-to-end send/read UI or delivery test | NOT IMPLEMENTED / Remaining |
| R014 | External SMS/MMS with delivery reports, rates and explicit paid-send consent | Hard | SMS/MMS provider, sender registration and country coverage | Android system SMS handoff only | External message schema/design only | No provider delivery receipt or end-to-end send test | BLOCKED / External provider |
| R015 | LINKA-to-LINKA internet audio/video calls on Android and supported browsers with WebRTC/TURN media | Hard | WebRTC clients, signaling integration, TURN and network test | Ephemeral signaling API exists; media relay/client not verified | backend/api/src/runtime.mjs and signaling routes; no operational media path | API signaling CI only; no audible/video call test | PARTIAL / Major implementation remaining |
| R016 | Full account-based browser product: number management, call UI, messaging, wallet and settings | Medium | R007-R015 backend/service capabilities | Browser companion and account shell exist | web/* and backend/api/verify-browser-ui.e2e.mjs | Chromium + Firefox account lifecycle in PostgreSQL CI 42680769129 | PARTIAL / Full telephone-client work remains |
| R017 | SIM/eSIM support and automatic connectivity switching with paid-route consent | Hard | Android role/API support, compatible devices and operator/eSIM partner | No SIM/eSIM provisioning implementation | Architecture/product docs only | No operator/device test | BLOCKED / External carrier/device support |
| R018 | Physical Android tests: direct two-phone, four-device mesh, background/audio lifecycle and handset compatibility | Hard | Physical phones, test SIMs and test environment | Automated tests only | Issue #2 device acceptance tracker | No physical device-lab evidence | BLOCKED / Physical hardware required |
| R019 | Independent cryptographic/security review, abuse controls, telecom/privacy compliance and release approval | Hard | Independent reviewer and relevant market/provider/legal approvals | Source security design and automated tests exist | SECURITY.md, docs/SECURITY.md and issue #2 | No independent cryptographic audit, external regulatory sign-off or release review | BLOCKED / Qualified external review |
| R020 | Production Render/PostgreSQL deployment, HTTPS origin/CORS, monitoring/backup and verified signed Android release | Hard | Confirmed Render workspace/service, production DB secrets and protected signing key | Historical Render instructions only; no live health evidence | docs/RENDER_API_SERVICE.md and CI debug APK artifact | CI artifacts are debug builds; production endpoint/workspace not checked | BLOCKED / Workspace selection and production credentials/approval |
| R021 | Phone favourites securely saved/loaded/deleted and system dial/SMS intents open on device | Easy | Android Keystore emulator/handset and system handlers | Source, UI and JVM codec tests exist on draft PR #15 | PhoneFavorites.kt, PhoneHubScreen.kt and PhoneFavoritesStoreTest.kt on feature/native-phone-favorites | Five Android instrumentation tests and emulator CI wiring committed; no run for latest branch head | NOT VERIFIED / Emulator workflow result required |

### Remaining work groups

**EASY**
- R008: add maintained numbering-plan/region and number-type resolution without guessing from shared +1.
- R021: verify Android Keystore persistence/tamper behavior and actual dial/SMS handoffs on emulator or handset.
- Add focused negative-path API tests where they cover an unmet acceptance criterion.

**MEDIUM**
- R010: wire trusted server-side provider/rate/allowance data into route options and quote API; keep routes unavailable where no verified data exists.
- R011: expose authenticated customer wallet snapshot/history endpoints and explicit ledger actions without allowing clients to set balances.
- R013: build the internal encrypted messaging API and client key/session flow.
- R016: complete actual browser telephone-client areas beyond account registration/sign-in.

**HARD / EXTERNAL DEPENDENCIES**
- R006 email sender and recovery verification.
- R009/R014 carrier number and external messaging provider contracts, inventory, credentials and compliance.
- R012 payment processor approval and webhook secrets.
- R015 WebRTC/TURN media infrastructure and client implementations.
- R017 SIM/eSIM operator partnership and device coverage.
- R018 physical phones/device lab and field testing.
- R019 independent security and telecom/privacy review.
- R020 explicit Render workspace/service confirmation, production environment and protected signing.

### Exact next action

Implement R008 using a maintained numbering-plan metadata package with tested support for JM, US, CA and GB; retain POST /v1/destinations/normalize as syntax-only and add a separate resolved-metadata endpoint rather than changing the existing contract. Verify using Jamaican +1 876/+1 658, US +1, Canada +1, UK +44 and invalid/unresolved examples. Then implement the internal authenticated messaging backend (R013) while leaving provider-blocked work accurately classified.

### Latest confirmed action

Merged PR #16 after API and Android PR CI succeeded; post-merge API CI also passed. Main branch head is 776f9c1890ace342d00ac35d10981d93e0a226ff. No production service or database was modified.
