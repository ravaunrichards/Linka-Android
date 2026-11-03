# LINKA architecture

## Product modes
1. Cellular calling through Android/system telephony and the user's carrier. Digicel Jamaica and FLOW Jamaica are initial compatibility targets. LINKA cannot make carrier calls free.
2. Free app-to-app internet calling using WebRTC, signaling, push notifications and TURN fallback. Requires connectivity.
3. SIM-free offline direct calling using local discovery and nearby peer transport. No SIM or internet is required after devices have exchanged trusted identity information.
4. Experimental offline mesh: opt-in relays forward encrypted voice packets between neighboring devices.

## Layers
- UI: dialer, contacts, nearby devices, pairing, incoming and active calls.
- Identity: local device identity, username cache, QR exchange, trust and revocation.
- Discovery: BLE, Wi-Fi Direct service discovery and/or Nearby Connections adapters.
- Transport: capability-negotiated peer links.
- Call control: invite, ring, accept, reject, timeout, end and reconnect.
- Audio: AudioRecord/AudioTrack, Opus, echo cancellation, jitter buffer and packet-loss concealment.
- Security: authenticated pairing, endpoint key agreement, per-call keys, authenticated encryption and replay protection.
- Mesh: neighbor table, route discovery, bounded hop count, duplicate suppression, congestion control and route repair.

## Hard constraints
- A username is not a radio address. Offline lookup requires a cached identity or discoverable device.
- Mesh relays forward ciphertext only.
- Relaying is opt-in and battery-aware.
- No unlimited-range claims.
- Android radio concurrency and Wi-Fi Direct topology vary by manufacturer; arbitrary simultaneous multi-hop mesh cannot be assumed.
- Never silently fall back to a chargeable SIM call.
- LINKA is not an emergency service.

## Delivery sequence
1. Build and verify Android shell and domain model.
2. Implement Keystore-backed identity and QR pairing.
3. Implement discovery adapters and permission UX.
4. Prove two-device bidirectional voice over one local transport.
5. Add recovery and device compatibility tests.
6. Add mesh only after transport topology is proven.
7. Add internet calling and optional Telecom integration separately.
