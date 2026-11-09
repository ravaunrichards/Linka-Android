# LINKA offline nearby and mesh voice status

## Product goal

LINKA has two SIM-free voice targets:
1. Direct voice between two reachable Android phones using supported nearby radios.
2. Routed voice over a continuous chain of powered and consenting relay phones or relay devices.

This offline path is separate from internet calling and ordinary carrier/PSTN calls. It must not depend on a SIM, airtime, mobile data, Wi-Fi internet, Render or a cloud relay. It cannot cross a radio gap without a relay.

## Source implementation recorded on main

The current Android source contains:
- Nearby Connections advertising, discovery and multi-peer cluster connectivity.
- Explicit connection requests and two-sided platform authentication-code confirmation.
- Persistent P-256 signing identity stored in Android Keystore.
- Signed and expiring public QR identity, explicit trust and revocation.
- A second signed peer handshake binding a radio endpoint to its QR-trusted identity.
- Fresh P-256 ECDH, transcript-bound HMAC confirmation and HKDF-SHA-256 session key derivation.
- Direction-separated AES-GCM call-control keys and unique per-call media keys.
- 8 kHz mono PCM capture/playback and G.711 mu-law 20 ms voice frames.
- Encrypted voice transport over Nearby Connections streams.
- Replay/tamper checks, bounded framing, call-ID uniqueness and key cleanup.
- Multiple neighbour links and opt-in relay consent, disabled by default.
- Signed bounded route requests/replies, reverse-route tracking, hop budgets, route expiry, duplicate suppression and loop checks.
- Authenticated adjacent radio links.
- A separate end-to-end caller-to-recipient handshake and encrypted routed call-control/voice payloads.
- Relay forwarding of opaque payloads without caller-to-recipient media keys.
- Route-loss handling and no cellular fallback.

## Automated test coverage in source

The current unit test tree includes tests for voice frame codec, call control codec/state machine, peer session security and secure call cipher, mesh packet routing/route selection and routed payload framing.

The current README, test plan and issue #2 record source-level protocol coverage. A green unit suite does not prove radio range, intelligible two-way audio, multi-hop voice quality, battery life or Android background behavior.

## Outstanding physical and independent verification

### Direct two-phone test
- Use two supported Android phones.
- Establish QR trust on both devices.
- Disable/remove SIM where practical, disable mobile data and disconnect Wi-Fi internet.
- Enable only required local radios and permissions.
- Discover, pair, verify the trusted identity handshake and complete a call.
- Confirm intelligible two-way audio.
- Repeat reject, timeout, hang-up, reconnect, permission loss, trust revocation, screen-off, background, battery saver, audio-route and Bluetooth cases.

### Multi-hop relay test
- Use at least four physical Android devices: caller, two relays and recipient.
- Establish all trust relationships required by the route policy.
- Enable relay consent explicitly on each relay.
- Disable mobile data and disconnect Wi-Fi internet.
- Establish the routed end-to-end identity handshake.
- Confirm audible two-way voice through the relay chain.
- Withdraw consent, revoke trust and disconnect each relay in turn.
- Verify safe route failure without cellular fallback.
- Measure call setup time, latency, jitter, packet loss, range, battery and thermal behavior.

### Security and platform release gates
- Independent cryptographic/protocol review.
- Forged identity, replay, tampering, route poisoning, flooding, malicious relay, link-loss and revocation testing.
- Test multiple manufacturers and Android versions.
- Validate microphone foreground-service, screen-off/background and process-death behavior.
- Decide whether non-GMS transport support is required.
- Complete Android Telecom/default-dialer scope and release signing.

## Limits

- Nearby Connections requires compatible Google Play Services.
- Long-distance offline calling requires a continuous live chain of powered, reachable, consenting relays.
- A relay can drop or delay packets and can observe traffic metadata even when it cannot decrypt end-to-end media.
- No fixed range or Jamaica-wide coverage is promised.
- LINKA is not an emergency-calling service.
- Offline mesh cannot dial ordinary telephone numbers unless a separate, explicitly authorized gateway to a carrier network is available.
