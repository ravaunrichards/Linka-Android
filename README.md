# LINKA — Offline Android Voice Calling

LINKA is an Android-first communication app for direct and multi-hop offline voice calling. Its primary mode is SIM-free communication over nearby radios. Internet and carrier calling are separate future integrations, not silent fallbacks.

## Implemented in the Android source

### Direct nearby voice
- Nearby Connections advertising, discovery, explicit connection requests and two-sided platform authentication-code confirmation.
- Persistent device identity using a P-256 signing key retained in Android Keystore.
- Short-lived signed identity QR codes, explicit in-person trust and revocation.
- A second peer-authentication handshake that binds a radio endpoint to the exact QR-trusted public key and fingerprint.
- Fresh P-256 ECDH, transcript-bound HMAC confirmation and HKDF-SHA-256 session key derivation.
- Direction-separated AES-GCM call-control keys and unique per-call media keys.
- 8 kHz mono PCM audio, G.711 mu-law 20 ms frames, encrypted before transmission, using Android AudioRecord/AudioTrack and a microphone foreground service.
- Replay/tamper checks, bounded framing, call-ID uniqueness and key destruction on call/session shutdown.

### Opt-in offline routing and routed voice
- Multiple connected-neighbour links using Nearby Connections cluster topology.
- Persistent relay consent, off by default. The peer's relay capability is included in its signed identity handshake.
- Signed, bounded route requests and replies, reverse-route tracking, hop budgets, route expiry, duplicate suppression and loop checks.
- Each adjacent radio hop is authenticated and encrypted. Routed payloads remain opaque to relays.
- The final caller and recipient establish a separate end-to-end authenticated session through the route. Relays do not receive the caller-to-recipient media keys.
- Routed call-control and 20 ms AES-GCM voice-frame transport with bounded queues and route-loss handling.
- The app offers a routed Call action only when a route is live and the destination's end-to-end identity handshake is complete.

### Automated tests
The unit suite covers peer identity handshakes, trusted-key rejection, signature tampering, bidirectional encrypted payloads, replay/tamper handling, route discovery, reverse paths, three-/four-device relay chains, both traffic directions, relay consent, packet bounds/expiry and routed-payload framing.

A successful build and unit suite validate the source and protocol components. They do **not** prove radio range or intelligible two-way calls between physical phones.

## What still needs real-world validation or release work
- Two physical phones must prove discovery, pairing and two-way voice with SIM removed/disabled, mobile data off and no Wi-Fi internet.
- Three or more physical phones must prove end-to-end voice over a relay chain, in both directions, with all trust and opt-in conditions satisfied.
- Measure call setup time, one-way latency, jitter, packet loss, intelligibility, echo, clipping, range, reconnect, battery and thermal behavior on multiple handset models.
- Test screen-off/background operation, Android microphone foreground-service restrictions, battery saver, permission loss, audio-route changes and Bluetooth headsets.
- Complete independent cryptographic/security review before making a production security certification claim.
- Validate the Google Play Services requirement and build a separately tested transport adapter if non-GMS phones are in scope.
- Complete Android system/Telecom integration, production signing, release artifact verification and distribution.
- Configure and verify GitHub Pages separately for the static browser companion.

## Important operating constraints
- LINKA's offline radio path does not require a SIM, airtime, mobile data, Wi-Fi internet, Render or a cloud relay.
- Nearby Connections requires compatible Google Play services. Device support must be tested; a non-GMS fallback transport is not implemented.
- Long-distance offline calling requires a continuous chain of powered, reachable and consenting relays. The app cannot bridge an empty radio gap, and no fixed range or Jamaica-wide coverage is promised.
- Relays must never receive end-to-end media keys. Relays can still drop, delay or deny packets; no radio mesh can guarantee delivery.
- LINKA must never silently fall back to a chargeable carrier call.
- LINKA is not an emergency-calling service.

## Browser companion
The static responsive browser companion is under `web/`. It documents LINKA and its current Android capabilities. A normal webpage cannot access Android Nearby Connections or Android Keystore and is not an offline calling client.

GitHub Pages deployment is configured in `.github/workflows/web-pages.yml`. A successful deployment and public URL must be verified from the Actions run and repository Pages settings.

## Repository layout
- `app/`: Android app, UI, identity, nearby radio links, direct/routed call control and audio.
- `app/src/test/`: protocol and application unit tests.
- `backend/api/`: optional internet signaling API; not used by the SIM-free nearby or mesh path.
- `web/`: static browser companion.
- `docs/`: architecture, security, Jamaica compatibility, API and test plans.
- `.github/workflows/`: Android CI, API CI and optional Pages deployment.

## Build and test
Requirements: JDK 17 and Android SDK 35. Run from the repository root:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

GitHub Actions runs both Android tasks for pushes to `main`, pull requests targeting `main`, and manual dispatch. After both pass, it uploads the debug APK and build metadata containing the exact source commit and workflow run. The CI artifact is a **debug build**, not a production-signed Play Store release. Release signing keys must remain outside the repository and CI logs.

## Jamaica scope
Digicel Jamaica and FLOW Jamaica are relevant only to separately labelled optional carrier integration and device compatibility. SIM-free offline calling is independent of both operators. Urban, town and rural field tests are required before making any coverage statement.

## License
See [LICENSE](LICENSE).
