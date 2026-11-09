# LINKA integration and implementation matrix

This matrix separates repository evidence from device, provider and production proof. Repository/CI status reflects GitHub Actions runs retrieved on 2026-11-09; provider and deployment status has not been live-verified. Latest Android-tested main revision is `ebe1490459da2b758cb7f08d9012fea425ecb1aa`; latest API- and PostgreSQL-tested functional revision is `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`.

| Capability | Repository evidence | Current status | Remaining proof/dependency |
|---|---|---|---|
| Android application shell | Kotlin and Jetpack Compose source under app/ | SOURCE IMPLEMENTED | Re-run build/tests after future changes; real-device compatibility |
| Nearby discovery | Nearby Connections code and manifest permissions | SOURCE IMPLEMENTED | Physical device and Google Play Services matrix |
| Device identity and trusted pairing | Android Keystore identity, signed QR identity and peer handshake | SOURCE IMPLEMENTED; AUTOMATED TESTED | Independent security review and real-device trust tests |
| Direct nearby audio | Audio capture/playback, G.711 framing and encrypted voice sessions | SOURCE IMPLEMENTED; AUTOMATED TESTED at source level | Two-phone SIM/data/internet-off audible call proof |
| Multi-hop offline routing | Mesh router, bounded routes, relay consent and opaque routed payloads | SOURCE IMPLEMENTED; AUTOMATED TESTED at source level | Four-or-more-phone voice test, performance and field validation |
| Android system dialer handoff | Phone hub uses ACTION_DIAL | SOURCE IMPLEMENTED | Physical device/handler checks; not an in-app carrier stack |
| Android SMS handoff | ACTION_SENDTO to installed SMS app | SOURCE IMPLEMENTED | Physical device/handler checks; not integrated SMS/MMS |
| Contacts/recent-call handoff | Intents to Android system apps | SOURCE IMPLEMENTED | Device/system-handler checks |
| Backend signed device challenge | backend/api source | SOURCE IMPLEMENTED; API CI PASSED on current main | Production TLS/domain, persistent challenge design and external abuse monitoring |
| Account registration and persistent login | `POST /v1/accounts/register`, `POST /v1/accounts/login`, `/v1/me`, logout, scrypt password hashes, DB-backed device/session integration test | SOURCE IMPLEMENTED; POSTGRESQL HTTP CI VERIFIED; ANDROID + BROWSER UI SOURCE IMPLEMENTED; BROWSER-WEBCRYPTO/POSTGRESQL E2E VERIFIED IN CI | Actual Chrome/Firefox DOM+IndexedDB and hosted production database run; password recovery/email verification need a configured mail provider |
| Backend call signaling queue | In-memory runtime and signaling routes | SOURCE IMPLEMENTED; API CI previously passed | Durable presence/queue design, client integration and real call tests |
| WebRTC media/TURN | No operational media relay verified | NOT IMPLEMENTED | Media topology, provider/infrastructure choice and network tests |
| Android account client session handling | `LinkaAccountClient`, injected token-store/device-identity/API-transport interfaces and `LinkaAccountClientTest.kt` | SESSION RESTORE/LOGOUT SOURCE IMPLEMENTED; 7 JVM TESTS PASS IN ANDROID CI | Physical Android sign-in/session persistence on real hardware; encrypted Android Keystore storage needs device validation | 
| Browser companion | Responsive source under web/, including `account.mjs` and `auth-crypto.mjs`, served through the API asset allowlist | ACCOUNT REGISTRATION/SIGN-IN SOURCE IMPLEMENTED; STRICT CORS ALLOWLIST SOURCE IMPLEMENTED; BROWSER-WEBCRYPTO/POSTGRESQL API E2E VERIFIED IN CI; CHROMIUM DOM/INDEXEDDB ACCOUNT UI E2E VERIFIED IN POSTGRESQL CI RUN 42677952248; FULL WEB TELEPHONE CLIENT NOT IMPLEMENTED | Firefox cross-browser coverage; configured production origin; full calling, messaging, number and wallet experiences |
| Render deployment | Historical details in docs/RENDER_API_SERVICE.md | HISTORICALLY DOCUMENTED; NOT LIVE VERIFIED IN THIS GITHUB-ONLY RECOVERY | Check dashboard, health, environment and current provider status before relying on it |
| Jamaican number provisioning | Product requirement and compatibility plan | NOT IMPLEMENTED | Real number inventory, agreements, eligibility and regulatory review |
| International number provisioning | Product scope documented | NOT IMPLEMENTED | Provider country coverage, number types, local-presence rules and contracts |
| SIP/PSTN calling | No production carrier adapter verified | NOT IMPLEMENTED | Carrier/wholesale provider, routing, caller ID, rate, billing and approvals |
| External SMS/MMS | Android system-app handoff only | NOT IMPLEMENTED as integrated service | Messaging provider, destination coverage, sender registration, delivery receipts and pricing |
| Internal LINKA chat | No complete persistent messaging service verified | NOT IMPLEMENTED | Account identity, durable storage, delivery/push and client implementation |
| Internet audio/video | Signaling API exists; media and client integration incomplete | PARTIAL FOUNDATION; NOT END-TO-END VERIFIED | WebRTC client/media path, TURN where needed, signaling integration and device/browser tests |
| Credit wallet and ledger | No production wallet/ledger verified | NOT IMPLEMENTED | Durable ledger, pricing model, atomic/idempotent updates and reconciliation |
| Payments | No processor integration verified | NOT IMPLEMENTED | Provider onboarding, webhook security, refunds, disputes and market support |
| Destination rate finder | No provider-backed rate catalog verified | NOT IMPLEMENTED | Current tariff feed/API, rate versioning, taxes and number-type classification |
| SIM/eSIM integration | Current Android manifest does not establish provisioning | NOT IMPLEMENTED | Android role/API design, carrier/eSIM partner and device tests |
| Free-first route options | backend/api/src/domain/route-options.mjs with unit tests | SOURCE IMPLEMENTED; AUTOMATED TESTED as a provider-independent policy core | Provider eligibility/rate feeds, API wiring, Android/web selection UI and durable user consent are not yet integrated |
| E.164 destination normalization | backend/api/src/domain/e164.mjs with unit tests | SOURCE IMPLEMENTED; AUTOMATED TESTED for international-format canonicalization | Country/number-type resolution, number assignment and provider availability still require numbering metadata and provider inventory |
| Provider-fed rate catalog and usage quotes | backend/api/src/domain/rate-catalog.mjs with unit tests | SOURCE IMPLEMENTED; AUTOMATED TESTED as a tariff validation/matching/quote core | No live provider tariff import, current LINKA prices, API endpoint, wallet deduction or client display is integrated |
| Wallet credit-ledger domain | backend/api/src/domain/credit-ledger.mjs with unit tests | SOURCE IMPLEMENTED; AUTOMATED TESTED as an immutable in-memory reducer | Durable database transactions, authenticated provider events, concurrency control, API endpoints, payment reconciliation and client UI are not yet integrated |
| PostgreSQL persistence adapter | backend/api/src/postgres-store.mjs, account/device/session and wallet transactions, schema-aware readiness and PostgreSQL 17 CI | SOURCE IMPLEMENTED; ACCOUNT/DB INTEGRATION CI VERIFIED | No public wallet HTTP endpoint; production DB credentials/hosting remain unconfigured |
| Automatic route switching | Pure policy allows free-to-free switching; paid fallback requires exact-quote preauthorization and a spend cap | PARTIAL FOUNDATION; AUTOMATED TESTED at policy level | Provider route discovery, user preference persistence and end-to-end route switching tests |
| Emergency calling | Not implemented; README states LINKA is not an emergency service | NOT IMPLEMENTED | Specialist provider, regulatory approval, location and emergency-service validation |

## CI and repository evidence recovered

- Latest Android-tested main revision: `ebe1490459da2b758cb7f08d9012fea425ecb1aa`.
- LINKA API CI run 42665527245 passed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 75 tests total, 73 passed, 0 failed and 2 database-only tests skipped without DATABASE_URL; API startup and CORS preflight checks passed.
- PostgreSQL Schema CI run 42665527585 passed on `b205708cd0c8ba5e08068e9ebfbc63eb16cdede8`: 75 tests passed, 0 failed, 0 skipped; migrations, schema checks, account HTTP integration, readiness and browser-WebCrypto account E2E all passed.
- Android CI run 42665968552 passed on `ebe1490459da2b758cb7f08d9012fea425ecb1aa`: Gradle 8.14.4 debug APK assembly, `:app:testDebugUnitTest`, source revision metadata, artifact upload and cleanup all succeeded.
- Latest debug APK artifact `linka-debug-apk-ebe1490459da2b758cb7f08d9012fea425ecb1aa` (GitHub artifact ID `13696198097`; 17,845,189 bytes; expires 2026-12-09). It is a debug APK, not a production-signed release.
- The browser-WebCrypto E2E creates an account, authenticates a second signed browser identity on the same account, tests CORS allow/deny, checks the explicitly unavailable password recovery route, revokes one account session without invalidating the other device, then revokes the remaining session.
- Account integration verifies signed-device registration/login, duplicate device/email rejection, persistent token hashes, second-device login and logout revocation.
- Password reset/email verification are not live-enabled; no email provider is configured.
- Render, carrier, payment, tariff and device-lab services have not been live-verified.

## Status update rule

Update this matrix when capabilities change. A source change alone may move a row to SOURCE IMPLEMENTED, not DEVICE TESTED, PROVIDER ENABLED or PRODUCTION VERIFIED. Attach an issue, test run, provider evidence or release reference for each higher status.
