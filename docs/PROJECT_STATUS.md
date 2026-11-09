# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified source head at recovery: `ebe1490459da2b758cb7f08d9012fea425ecb1aa` (main).
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 144 entries, not truncated.
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

- Latest Android-tested main revision: `ebe1490459da2b758cb7f08d9012fea425ecb1aa`.
- Latest API-tested and PostgreSQL-E2E-tested functional revision: `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`.
- Full-product progress: **43% complete | 57% remaining**.
- Latest inspected repository tree: 144 entries, recursive tree not truncated.
- Render workspace inspection is blocked pending explicit workspace confirmation; no live Render configuration or deployment health is claimed.
- PostgreSQL Schema CI run 42665527585 passed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 75 passed, 0 failed, 0 skipped; migrations, schema checks, account HTTP integration, PostgreSQL readiness and browser-WebCrypto account E2E passed.
- LINKA API CI run 42665527245 passed on the same commit: 75 total, 73 passed, 0 failed and 2 database-only tests skipped without DATABASE_URL.
- Android CI run 42665968552 passed on `ebe1490459da2b758cb7f08d9012fea425ecb1aa`: Gradle 8.14.4, debug APK build and `:app:testDebugUnitTest` passed; source metadata and APK artifact were uploaded.
- Latest debug APK artifact `linka-debug-apk-ebe1490459da2b758cb7f08d9012fea425ecb1aa` (artifact ID `13696198097`, 17,845,189 bytes) expires 2026-12-09. It is a debug APK, not a production-signed release.
- Android and browser account registration/login/profile/sign-out source is present. Android session restore/logout behavior has seven JVM test cases; browser WebCrypto account lifecycle has a PostgreSQL-backed CI E2E.
- Password recovery and email verification remain unavailable pending a configured email-delivery provider. Render, carrier, payment, tariff and physical-device status have not been live-verified.
- Next action: add the remaining no-saved-session logout and API-origin validation edge-case tests for the Android account client, then verify them in Android CI.

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
| Complete Android phone experience and native system integration | 9% | 4.5% | 4.5% |
| Backend identity/signaling, schema integrity, PostgreSQL adapter and account authentication | 8% | 8% | 0% |
| Free-first route policy and international number normalization | 6% | 3% | 3% |
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

**Current full-product completion: 44.5% | Remaining: 55.5%.**

The previous verified checkpoint was 41%. Two further percentage points are credited only to independently tested portions of the existing account-client milestones: one point in the Android phone milestone for injected-client session restore/logout behavior covered by seven passing JVM tests, and one point in the browser milestone for the browser-WebCrypto account lifecycle against a disposable PostgreSQL service, including second-device login, per-device revocation and CORS allow/deny. The API's no-database workflow passed 73 tests with 2 DB-only tests skipped; the PostgreSQL workflow passed all 75 tests and the E2E script. Android CI passed on the newest source commit. These are not physical-device tests or an actual Chrome/Firefox DOM+IndexedDB run. Provider-backed phone numbers, PSTN/SIP, SMS/MMS, media relay, payments, public wallet APIs, email recovery/verification, hosted production DB and deployment health remain unfinished.

## Easy, medium and hard work remaining

### Easy
- Add the no-saved-session logout and API-origin validation edge-case tests for the Android account client.
- Keep current documentation and repository inventories synchronized with the verified branch.
- Continue focused API negative-path tests where no external provider is required.

### Medium
- Add actual Chromium/Firefox browser automation for the page DOM, IndexedDB device-key persistence, tab-scoped session restoration and logout against a disposable PostgreSQL CI service.
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

Add two focused Android account-client edge-case tests (logout without a saved token; HTTPS-only API-origin validation), run Android CI, and update this checkpoint. The browser-WebCrypto/PostgreSQL E2E is already verified in disposable CI; actual browser DOM/IndexedDB automation remains next after the Android edge tests. Keep password reset/email verification disabled until a real email provider and one-use token workflow exist.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.


## Latest continuation checkpoint — 2026-11-09

**COMPLETION: 44.5% | REMAINING: 55.5%**

- Previous verified completion: **43%**.
- Current verified completion: **44.5%**.
- Progress since previous checkpoint: **+1.5 percentage points**.
- Repository: `ravaunrichards/Linka-Android`.
- Current implementation branch: `feature/native-phone-favorites`.
- Implementation branch head: `8eb8b6ee74d892c92fabaf0fe25469452e8f96ca`.
- Android CI tested merge revision: `facd9ae4ce309a325face0a1ce358b564cc72509`.
- Main branch remains at its recovered baseline; neither PR has been merged.
- PR #14 — browser UI automation and CI integration: https://github.com/ravaunrichards/Linka-Android/pull/14 (draft, open, unmerged).
- PR #15 — encrypted native phone favourites: https://github.com/ravaunrichards/Linka-Android/pull/15 (draft, open, unmerged).
- Recovery confidence: **PARTIALLY RECOVERED**. GitHub source, commits, branches, test runs, APK artifact and durable project checkpoint are confirmed. The separate ChatGPT conversation transcript titled “Build Virtual Phone App” was not directly available to this session, so no claim is made that its exact message-by-message history was read.

### Newly verified implementation

**Browser account UI (PR #14)**
- Added Playwright 1.65.0 to the API development dependencies and wired the existing 182-line Chromium account lifecycle test into PostgreSQL CI.
- API CI run [42674508636](https://github.com/ravaunrichards/Linka-Android/actions/runs/42674508636): **PASS** — dependency installation, browser-test syntax check, API tests and API start checks.
- PostgreSQL Schema CI run [42674508845](https://github.com/ravaunrichards/Linka-Android/actions/runs/42674508845): **PASS** — migrations applied twice, schema/ledger checks passed, 75 tests passed, 0 failed, 0 skipped, browser-WebCrypto E2E passed, and real Chromium UI lifecycle test passed.
- Android CI run [42674508842](https://github.com/ravaunrichards/Linka-Android/actions/runs/42674508842): **PASS** — debug APK build and Android unit tests.
- One additional percentage point is credited to the account UI portion of the website milestone because a real Chromium page run verified registration, non-exportable IndexedDB identity, session restore after reload, separate browser-device login, per-device logout and the explicit recovery limitation. Full browser calling is still unfinished.

**Native Phone favourites (PR #15)**
- Added `PhoneFavorites.kt`: phone number/name validation, canonical number normalization, JSON codec, 50-entry bound, duplicate-number replacement and AES-GCM local persistence through a non-exportable Android Keystore key.
- Integrated add, display, dial, message and remove actions into `PhoneHubScreen.kt`; the Android system dialler and SMS app still require user confirmation and operator service.
- Added six `PhoneFavoritesCodecTest` cases and physical-device acceptance steps in `docs/TESTING.md`.
- Android CI run [42676400680](https://github.com/ravaunrichards/Linka-Android/actions/runs/42676400680): **PASS** — `:app:assembleDebug` and `:app:testDebugUnitTest` both succeeded; APK artifact upload succeeded.
- A half percentage point is credited for the compiled native UI and passing codec/validation tests. Actual Android Keystore runtime, local encrypted-preferences inspection and system-app dial/SMS handoff still require emulator or physical-device acceptance; those checks are not claimed complete.

### Debug APK artifact

- Artifact: `linka-debug-apk-facd9ae4ce309a325face0a1ce358b564cc72509`.
- GitHub artifact ID: `13699983271`.
- Download: https://github.com/ravaunrichards/Linka-Android/actions/runs/42676400680/artifacts/13699983271
- APK filename: `LINKA-Android-debug.apk`.
- Size: 18,466,905 bytes.
- SHA-256: `6cb6b5b150c58bcd19bc1d55f66e5e1e2f5986a3b75147537e3bab6703574fd6`.
- Build commit metadata: `facd9ae4ce309a325face0a1ce358b564cc72509`.
- This is an unsigned-by-production debug APK build artifact, not a Play Store release or production-signed build.

### Remaining work — easiest first

**EASY**
- Verify encrypted favourites save/load and delete behavior on an Android emulator or handset; inspect that preferences store ciphertext, not phone numbers.
- Verify dial and SMS intents open the system handlers with the selected favourite and do not place calls/send messages automatically.
- Continue small provider-independent account, phone-number and UI negative-path tests.

**MEDIUM**
- Extend automated Android UI/instrumentation coverage for native Phone and account screens, including app recreation and persisted favourites.
- Complete account-based web dashboard and actual browser telephone-client UI.
- Implement authenticated internal LINKA messaging and synchronize it across supported devices.
- Connect client features to the existing server-backed account/device/session foundation.
- Verify a separately hosted browser origin and Render preview using exact CORS allowlisting when the hosting workspace is accessible.

**HARD / EXTERNAL DEPENDENCIES**
- Real Jamaican and international number inventory, carrier contracts, SIP/PSTN inbound/outbound calling and number rental.
- Actual external SMS/MMS delivery, billing rates and delivery receipts.
- WebRTC audio/video media path and TURN integration.
- Payment provider onboarding, payments, signed webhooks, refunds and settlement reconciliation.
- SIM/eSIM operator provisioning and supported connection switching.
- Physical two-device and multi-hop offline voice validation, Android background/audio lifecycle, independent cryptographic audit and telecom/regulatory review.
- Production database/Render configuration and production-signed release.

**BLOCKERS:** Physical handset/emulator test results are not available from this execution. Carrier, number-inventory, SMS/MMS, payment and SIM/eSIM providers are not verified as connected. Password recovery/email verification remain disabled because email delivery is not configured. Render production health is not verified.

**LIVE INFORMATION STATUS:** GitHub source, PR state, GitHub Actions results and artifact metadata were retrieved live during this execution. Provider operational status, actual carrier rates, Render deployment health and physical-device radio/audio behavior remain unavailable/unverified.

**LAST VERIFIED MILESTONE:** Chromium browser account UI lifecycle and native Phone favourites source build/unit tests.

**LAST CONFIRMED ACTION:** Saved the native favourites implementation, added six codec/validation tests, documented device acceptance and verified Android CI plus debug APK artifact upload.

**NEXT ACTION:** Add Android emulator/instrumentation coverage for favourites save/load, Keystore encryption, add/remove persistence and system intent handoffs, then run the emulator workflow. Continue toward the provider-backed phone-service requirements; do not claim those are operational until real provider accounts and tests exist.

**DEPLOYMENT STATUS:** Not deployed. No production service changes were made.



## Continuation checkpoint — Android Keystore emulator tests — 2026-11-09

**Working-branch completion remains 44.5% | Remaining 55.5%.** No additional completion credit is awarded for the new instrumentation suite until an Android emulator workflow passes. The main branch's latest durable product tracker is 44% complete / 56% remaining; the additional 0.5 percentage point is the already-recorded partial native Phone favourites implementation on this open draft branch.

- Repository: `ravaunrichards/Linka-Android`.
- Main branch last observed head: `3982caf5d88d6286a5567354ff52841b06348afa`.
- Working branch: `feature/native-phone-favorites`.
- Latest working-branch commit at this checkpoint: `75923241a314de3e496ce5b92013d9f494035352`.
- Pull request: [#15 — encrypted native phone favourites](https://github.com/ravaunrichards/Linka-Android/pull/15), still draft/open/unmerged.

### Implemented, not yet CI-verified

- Added `PhoneFavoritesStoreTest.kt` with five Android instrumentation tests for ciphertext-only SharedPreferences, Android Keystore key non-exportability, tamper failure, missing-key failure and persistence of removals.
- Added AndroidX Test stable dependencies: core 1.7.0, runner 1.7.0 and ext.junit 1.3.0.
- Added an API 35 Google APIs emulator step to Android CI using `reactivecircus/android-emulator-runner@v2` and `gradle :app:connectedDebugAndroidTest`.
- Documented the emulator test scope and limitations in `docs/TESTING.md`.
- Existing evidence remains: prior PR branch debug APK build and JVM tests passed in Android CI run 42677221281 / earlier run 42676400680. Those runs predate the new instrumentation source and do not verify it.

### Verification and blocker

- Current branch head: `75923241a314de3e496ce5b92013d9f494035352`.
- A pull-request workflow run for the new head has not been returned by the connected GitHub Actions read tools. The available GitHub toolset exposes workflow-run inspection and reruns of failed jobs, but no workflow-dispatch action for starting a new run. Do not count the new emulator suite as passed.
- This is a verification blocker, not evidence that the instrumentation tests failed.
- The code is committed to the existing draft branch; no merge, production deployment or secrets change was performed.

**Last confirmed action:** Added the five-test Android Keystore/SharedPreferences instrumentation suite, added emulator CI wiring, documented it, and committed all changes to the existing feature branch.

**Next action:** Trigger Android CI on this exact feature-branch head (or push a subsequent commit from the repository owner so the pull-request workflow starts), then inspect the emulator job logs, fix any failures and record the verified result. Do not merge the draft PR until the required physical-device checks and project review are complete.

**Unchanged external blockers:** Render service/workspace approval, production PostgreSQL configuration, email delivery, carrier/telephone-number providers, SIP/PSTN, SMS/MMS, WebRTC/TURN media, payments, SIM/eSIM, physical multi-device field testing, independent security review and production signing.
