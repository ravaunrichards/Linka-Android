# LINKA architecture

## Product modes

1. **Optional cellular calling:** Android/system telephony using the user's carrier. Digicel Jamaica and FLOW Jamaica are compatibility targets. LINKA cannot make carrier calls free.
2. **Internet app calling:** app-to-app voice over an internet connection. This remains separate from offline calling.
3. **SIM-free nearby calling:** local discovery and direct peer transport. The intended call path must work without a SIM, airtime, mobile data, or Wi-Fi internet.
4. **Experimental offline mesh:** opt-in relays forward end-to-end encrypted packets between neighboring devices.

## Current Android implementation

- Jetpack Compose Android application shell.
- Persisted random device ID stored in app-private preferences.
- P-256 signing key generated and retained in Android Keystore; only the public key fingerprint is displayed.
- Google Nearby Connections dependency and direct point-to-point strategy.
- Local advertising and discovery, nearby endpoint list, explicit connection request, and two-sided six-digit authentication-code confirmation.
- Stop/disconnect controls and visible distinction between a radio connection and a voice call.

The current implementation stops at the nearby connection layer. It does not yet exchange signed identity claims, derive call-session keys, deliver call-control messages, or stream audio.

## Target layers

- **UI:** dialer, contacts, nearby peers, QR identity pairing, incoming calls and active-call controls.
- **Identity:** stable local device ID, Keystore signing identity, username cache, QR exchange, trust and revocation.
- **Discovery:** Nearby Connections adapter; additional BLE or Wi-Fi Direct adapters only where device tests justify them.
- **Transport:** capability-negotiated direct peer link, connection health and recovery.
- **Call control:** signed invite, ringing, accept, reject, timeout, end and reconnect.
- **Audio:** AudioRecord/AudioTrack, a reviewed low-latency codec, echo cancellation, jitter buffer and packet-loss concealment.
- **Security:** authenticated identity binding, ephemeral key agreement, per-call keys, authenticated encryption, replay protection and key erasure.
- **Mesh:** opt-in neighbor table, route discovery, bounded hop count, duplicate suppression, congestion control and route repair.
- **Optional internet mode:** independently designed signaling and media transport; it must never be silently selected as a replacement for offline mode.

## Hard constraints

- A username is not a radio address. Offline lookup requires a cached identity or discoverable nearby device.
- Nearby Connections requires compatible Google Play services and permissions; a GMS-free device needs a separately implemented transport.
- The user must enable Bluetooth or Wi-Fi. The app must not assume the Nearby SDK can turn radios on.
- Mesh relays forward ciphertext only and never receive call-media keys.
- Relaying is opt-in and battery-aware.
- No unlimited-range claims.
- Android radio concurrency and Wi-Fi Direct topology vary by manufacturer; arbitrary simultaneous multi-hop mesh cannot be assumed.
- Never silently fall back to a chargeable SIM call.
- LINKA is not an emergency service.

## Delivery sequence

1. Establish a reproducible Android build and automated test baseline.
2. Complete stable identity, QR exchange, cryptographic identity binding and revocation.
3. Complete direct discovery and consent pairing across supported Android versions.
4. Implement encrypted call signaling and full-duplex audio over a direct nearby transport.
5. Prove two-phone SIM-free calling with mobile data off and no Wi-Fi internet.
6. Add device compatibility, battery, background and recovery testing.
7. Evaluate mesh topology on physical hardware; implement relaying only for proven device/radio combinations.
8. Add internet calling and optional Telecom integration as separately selectable modes.
