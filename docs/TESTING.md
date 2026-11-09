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

Latest verified database run: **42615498199**, commit `08d8d95b1c5ddeb86b33e798b4cfa39fcefc611e`, completed successfully on 2026-11-09. The database-backed test run reports 58 passed, 0 failed, including durable wallet grant, idempotency, usage deduction, partial refund, snapshot read and overdraft rejection.

This verifies the Node-to-PostgreSQL wallet-store integration in CI. It does not verify production database hosting, real customer accounts, authentication-to-account linking, public wallet endpoints, payment processing or live carrier services.
