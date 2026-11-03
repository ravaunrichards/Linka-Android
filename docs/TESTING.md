# Testing strategy

## Automated checks

The GitHub Actions workflow runs:

- `gradle :app:assembleDebug`
- `gradle :app:testDebugUnitTest`

Unit-test routing costs, hop limits, call-state transitions, identity serialization, QR payload validation, packet parsing, duplicate suppression and route expiry as those components are implemented. A source commit is not a successful build; record the latest CI conclusion and the exact commit SHA.

## Nearby discovery and pairing tests

Use two physical Android phones with LINKA installed.

1. Clear the app data on both phones and launch LINKA.
2. Confirm each phone displays a stable device ID across app restarts.
3. Grant nearby permissions and manually enable Bluetooth or Wi-Fi.
4. Start advertising on phone A and discovery on phone B.
5. Confirm phone B discovers the correct LINKA endpoint.
6. Request a connection and compare the four-digit authentication code on both screens.
7. Confirm both sides must accept before the connection is established.
8. Repeat with a mismatched/unexpected request and reject it.
9. Disconnect either side and confirm the other side returns to a disconnected state.
10. Repeat with mobile data off and no Wi-Fi internet connection.

## Physical-device call tests

A simulator cannot prove Bluetooth/Wi-Fi range, radio concurrency, microphone reliability, background execution, audio latency or mesh forwarding.

Minimum lab: two Android phones for direct discovery and bidirectional voice; three or more phones for mesh proof-of-concept; at least two manufacturers and Android versions; Google Play services availability recorded; SIM removed and mobile data disabled; Wi-Fi internet disconnected; airplane mode with local radios re-enabled where supported.

Do not label offline calling as working until a physical-device test demonstrates no SIM, no internet, authenticated identity, bidirectional intelligible audio, safe disconnect/recovery and no unapproved SIM fallback. Mesh additionally requires a demonstrated multi-hop route with encrypted payload forwarding and opt-in relay consent.

## Evidence to retain

For each test run, record commit SHA, APK version, device model, Android version, security patch, Google Play services version, radio state, permission state, steps, expected result, observed result, logs with secrets removed and pass/fail. Do not record authentication codes or private key material.
