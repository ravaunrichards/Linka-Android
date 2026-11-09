# LINKA integration and implementation matrix

This matrix separates repository evidence from device, provider and production proof. Repository/CI status reflects GitHub Actions runs retrieved on 2026-11-09; provider and deployment status has not been live-verified. Latest Android-tested main revision is `312aa4677baab63d3677b3d73718e97d3847eb2e`; latest API-tested revision is `76401c9311a25a64beec974a10794df3edaf5f49`. Android CI passed on main with Gradle 8.14.4.

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
| Account registration and persistent login | `POST /v1/accounts/register`, `POST /v1/accounts/login`, `/v1/me`, logout, scrypt password hashes, DB-backed device/session integration test | SOURCE IMPLEMENTED; POSTGRESQL HTTP CI VERIFIED; ANDROID + BROWSER UI SOURCE IMPLEMENTED; ANDROID/API CI PASSED | Real browser/Android-to-hosted-PostgreSQL end-to-end verification; password recovery/email verification need a configured mail provider |
| Backend call signaling queue | In-memory runtime and signaling routes | SOURCE IMPLEMENTED; API CI previously passed | Durable presence/queue design, client integration and real call tests |
| WebRTC media/TURN | No operational media relay verified | NOT IMPLEMENTED | Media topology, provider/infrastructure choice and network tests |
| Browser companion | Responsive source under web/, including `account.mjs` and `auth-crypto.mjs`, served through the API asset allowlist | ACCOUNT REGISTRATION/SIGN-IN SOURCE IMPLEMENTED; API CI PASSED; FULL WEB TELEPHONE CLIENT NOT IMPLEMENTED | Real browser end-to-end account test against hosted PostgreSQL; full calling, messaging, number and wallet experiences |
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

- Latest Android-tested source revision: `312aa4677baab63d3677b3d73718e97d3847eb2e`.
- PostgreSQL Schema CI run 42659791482 passed on `3ddd892181f13de5fa5edb13d6df6b9c9af36572`: migrations, constraints, account integration and readiness passed; 69 passed, 0 failed, 0 skipped.
- LINKA API CI run 42660318140 passed on `76401c9311a25a64beec974a10794df3edaf5f49`: browser module syntax and account-form checks passed; 67 passed, 0 failed, 2 database-only tests skipped without DATABASE_URL; API startup and browser asset routes passed.
- Android CI run 42661787677 passed on `312aa4677baab63d3677b3d73718e97d3847eb2e`: Gradle 8.14.4, `:app:assembleDebug`, `:app:testDebugUnitTest`, source revision metadata and APK artifact upload all succeeded.
- Debug APK artifact `linka-debug-apk-312aa4677baab63d3677b3d73718e97d3847eb2e` (GitHub artifact ID `13693714374`; expires 2026-12-09). It is not a production-signed release.
- Android CI run 42661787677 passed on `312aa4677baab63d3677b3d73718e97d3847eb2e`: Gradle 8.14.4, debug APK build, unit tests, revision metadata, artifact upload and cleanup all succeeded. The Kotlin Gradle Plugin minimum-version warning seen on 8.14.3 is absent.
- Account integration verifies signed-device registration/login, duplicate device/email rejection, persistent token hashes, second-device login and logout revocation.
- Password reset/email verification are not live-enabled; no email provider is configured.
- Render, carrier, payment, tariff and device-lab services have not been live-verified.

## Status update rule

Update this matrix when capabilities change. A source change alone may move a row to SOURCE IMPLEMENTED, not DEVICE TESTED, PROVIDER ENABLED or PRODUCTION VERIFIED. Attach an issue, test run, provider evidence or release reference for each higher status.
