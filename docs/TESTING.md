# LINKA Testing Strategy

## Automated Android CI
The workflow runs:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The debug APK is uploaded only after compilation and the unit-test task succeed. Build metadata records the exact repository commit and workflow run.

## Automated test coverage
The current unit suite exercises:
- Call-state transitions and call-control packet parsing.
- G.711 voice codec and bounded voice-frame framing.
- Peer identity handshake, trusted QR key matching, invalid signature rejection and relay-capability binding.
- Bidirectional AES-GCM control/media encryption, call-ID binding, replay/tamper rejection and frame bounds.
- Route discovery/replies, reverse-path forwarding, multiple-relay chains, bidirectional opaque payload delivery, relay consent, duplicate suppression, malformed input, unauthenticated ingress, packet hop/lifetime bounds and route expiry.
- Routed payload type/length framing, including handshake payloads larger than 255 bytes.

Automated tests establish deterministic source-level behavior. They do not exercise Android radios, microphones, real acoustic conditions or device-specific background restrictions.

## Android phone hub handoff checks

On a physical Android phone with the relevant system apps installed:
1. Open the Phone destination and enter a valid number using the keypad and text field.
2. Tap Open dialer. Confirm Android's system dialer opens with the selected number prefilled and that LINKA does not place the call itself.
3. Return to LINKA and tap Message. Confirm the installed SMS app opens a message addressed to the selected number.
4. Open Recent calls and Contacts. Confirm Android routes each action to the corresponding system app.
5. Repeat with no SIM, no default SMS app, and restricted system-app availability. Confirm the app reports that a handler is unavailable instead of crashing.
6. Verify the Nearby destination still performs discovery, pairing and LINKA voice call controls independently of the carrier dialer.

The handoff checks do not establish that a carrier call or SMS succeeds. Those outcomes depend on the user's operator, SIM/account state, radio coverage and installed Android apps.

## Two-phone direct offline test
Use two supported Android phones:
1. Record manufacturer/model, Android version, security patch and Google Play services version.
2. Install the same test build. Scan and trust the other phone's signed LINKA QR on both phones.
3. Remove/disable SIMs where practical; turn mobile data off and disconnect from Wi-Fi internet.
4. Enable only the local radios required by Nearby Connections. Grant nearby-device and microphone permissions.
5. Start advertising on one phone and discovery on the other. Confirm the actual peer appears.
6. Request a connection and compare the platform authentication digits on both screens. Accept on both sides.
7. Confirm the app-level trusted identity handshake completes.
8. Place and accept calls in both directions. Verify intelligible two-way audio.
9. Repeat reject, timeout, hang-up, reconnect, permission denial, trust revocation, app backgrounding, screen off, battery saver, audio route changes and Bluetooth headset behavior.
10. Confirm that a lost link ends the call and never triggers an unapproved cellular call.

## Multi-phone offline relay test
Use at least four physical Android phones for the two-relay chain:
- Caller, Relay A, Relay B and Recipient.
- Every endpoint must explicitly QR-trust the other identities required by the current route policy.
- Relay A and Relay B must each enable relay consent. Keep consent disabled on endpoints that should not relay.
- With mobile data off and Wi-Fi internet disconnected, discover the route and verify the destination's end-to-end identity handshake.
- Place calls in both directions and verify audible media through the chain.
- Disable consent, revoke trust, disconnect each relay in turn and confirm calls fail safely without cellular fallback.
- Test duplicate, stale, forged, oversized and malformed packets; attempt route loops and route poisoning.
- Record route/hop diagnostics and measure latency, jitter, packet loss, call setup time, quality, reconnect behavior, battery and heat.

Repeat across multiple handset manufacturers, Android versions, low-cost devices and realistic Jamaican urban, suburban and rural environments. A single chain test is not an island-wide coverage demonstration.

## Chromium browser account UI end-to-end test

The browser UI test is `backend/api/verify-browser-ui.e2e.mjs` and uses the repository-pinned Playwright library (1.65.0). It must target a running LINKA API backed by a migrated PostgreSQL database.

The test launches real headless Chromium and exercises the served account page. It verifies:
- Same-origin API address defaults correctly and secure WebCrypto/IndexedDB are available.
- Registration creates a real account and signed browser-device session.
- The P-256 private key remains non-exportable in IndexedDB.
- Tab-scoped account session and browser device identity survive page reload.
- A second isolated browser context can log into the same account using a distinct device identity and receives its own session token.
- Signing out one browser context revokes only that device session; the other remains active until its own logout.
- Password recovery reports the current unavailable state without pretending an email was sent.
- No uncaught browser page errors occur during the flow.

To run locally, install backend dependencies, install Chromium with `cd backend/api && npx playwright install chromium`, start the API with `DATABASE_URL` pointing at a migrated PostgreSQL database, then run `LINKA_API_URL=http://127.0.0.1:8080 npm --prefix backend/api run test:browser-ui`.

The PostgreSQL CI workflow installs Chromium with the required Linux dependencies and runs this browser UI test after the existing API/database account end-to-end test. A committed test and workflow definition are not themselves a passing CI result; record the workflow's actual run evidence separately.

## Evidence to retain
For every physical run, retain the commit SHA, APK version, device models, Android and Play services versions, radio/permission state, terrain/environment, exact test steps, observed results, audio/latency metrics and pass/fail. Redact identity authentication codes, private key material, media keys and personal data.


## PostgreSQL schema and Node adapter CI

The database workflow uses PostgreSQL 17 and performs these checks:

1. Installs the pinned `pg` dependency.
2. Applies every migration under `backend/api/db/migrations/` twice.
3. Executes `backend/api/db/checks/verify_core_schema.sql`, testing wallet purchase/idempotency/usage/refund/overdraft/append-only behavior, active-number uniqueness and cross-account ownership constraints.
4. Runs the real Node/PostgreSQL integration test in `backend/api/test/postgres-integration.test.mjs`.
5. Starts the Node API with `DATABASE_URL` and confirms that `/readyz` advertises `postgres-wallet-ledger`.

The ordinary API CI runs the same test suite without a database. In that context, the PostgreSQL integration test is explicitly skipped because `DATABASE_URL` is absent. PostgreSQL Schema CI runs it against a disposable database service.

Latest verified database run: **42617603718**, commit `4633d10c307fce56f1458f5ffef7e01571f84ab9`, completed successfully on 2026-11-09. It reports 65 passed, 0 failed and includes the PostgreSQL wallet integration, account HTTP integration and API readiness.

The account HTTP integration test verifies:
- Registration after a P-256 signed-device challenge.
- The persistent account token authenticates `/v1/me`.
- Re-registering with the same signed device is rejected.
- Registering an existing email from another signed device is rejected.
- Password recovery returns an explicit unavailable response; no email is simulated.
- Logout revokes the persistent account token.
- Wrong passwords return a generic invalid-credentials response.
- The same account can log in from a second signed device.
- The account bearer-token SHA-256 hash is stored in PostgreSQL rather than the raw token.
- The second account session is revoked on logout.

The ordinary API CI run **42617603655** on 2026-11-09 reports 63 passed, 0 failed and 2 PostgreSQL-only tests skipped because `DATABASE_URL` is absent.

These tests verify account registration/login and persistent account/device/session integration in a disposable CI database. They do not verify production database hosting, email verification, password-reset delivery, public wallet endpoints, payment processing or live carrier services.
