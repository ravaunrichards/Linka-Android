# LINKA Offline Network Delivery Roadmap

## Product goal
LINKA targets two SIM-free experiences:
1. Nearby calling directly between two reachable Android phones.
2. Long-distance offline calling across a continuous chain of consenting, powered relay phones or dedicated relay devices.

The offline path must not depend on cellular service, airtime, mobile data, Wi-Fi internet or a cloud relay. It cannot cross a gap where no radio link or relay exists.

## Implemented in Android source

### Direct calling foundation
- Nearby Connections discovery, advertising, multi-peer neighbour tracking and two-sided platform authentication-code confirmation.
- P-256 Android Keystore signing identity, signed/expiring QR identities, explicit trust and revocation.
- Fresh QR-trusted peer ECDH handshake, transcript-bound HMAC confirmation and HKDF-SHA-256.
- Direction-separated AES-GCM call-control keys and unique per-call voice keys.
- 8 kHz mono G.711 mu-law audio frames, encrypted before Nearby Connections stream transmission.
- Android microphone foreground service and audio resource cleanup.

### Routed calling foundation
- Persistent local relay consent, disabled by default, plus remote relay consent authenticated in the signed peer handshake.
- Signed route discovery and replies, reverse routes at each relay, bounded packet lifetime and hop budget, duplicate/loop suppression and stale route cleanup.
- Direct neighbour links are authenticated and encrypted hop by hop.
- An independent end-to-end peer handshake runs between caller and destination through the route; routed call-control and voice payloads are encrypted end to end.
- Relays forward opaque packets only and never receive caller-to-recipient media keys.
- Routed voice frames use bounded payload framing and queues. Route expiry or link failure terminates the call rather than switching to cellular.

## Automated verification
Unit tests cover direct peer identity and encryption, replay/tamper handling, packet validation, route request/reply, reverse paths, a two-relay chain, bidirectional opaque payload forwarding, relay consent and routed payload framing.

The successful CI run on commit `e83b4b527d9bf89be963f49780b5febd37972ba5` built the debug APK, passed the complete unit-test suite, recorded source metadata and uploaded the APK artifact. This validates the source and tests, not physical radio/audio performance.

## Remaining verification and release gates

### Gate A — Direct offline device proof
- Two physical phones; SIM removed/disabled, mobile data off and no Wi-Fi internet.
- QR trust, discovery, pairing, call setup and intelligible two-way audio.
- Reconnect, reject, timeout, trust revocation, permission loss, screen-off and background-call tests.

### Gate B — Secure routed device proof
- At least four physical Android phones (caller, two relays, recipient).
- All required QR trust relationships established; both relays opt in.
- End-to-end routed handshake and audible voice in both directions.
- Test loss of each relay, route expiry/repair, revocation and consent withdrawal.
- Measure latency, jitter, packet loss, range, audio quality, battery and thermal state.

### Gate C — Independent security review
Review ECDH/transcript construction, signature verification, HKDF use, AES-GCM nonce/sequence rules, replay window, key lifecycle, routed packet validation, trust policy and malicious-relay behavior.

### Gate D — Platform and release readiness
Test multiple manufacturers and Android versions, Google Play services availability, microphone foreground-service restrictions, audio routing, battery optimization, release signing, versioning and distribution. A non-GMS fallback radio adapter and Android Telecom/default-dialer integration are not complete.

### Gate E — Jamaica coverage/deployment
Field-measure actual relay reachability and power requirements in each environment. Do not claim fixed range, island-wide availability or emergency service.

## Constraints
- Google Nearby Connections requires compatible Google Play services.
- Cluster topology may have throughput/latency trade-offs; benchmark it on target phones.
- A relay can still drop, delay or observe traffic metadata even though it cannot decrypt end-to-end media.
- Long-distance voice requires a continuous powered route.
- No silent cellular fallback. LINKA is not an emergency-calling service.
