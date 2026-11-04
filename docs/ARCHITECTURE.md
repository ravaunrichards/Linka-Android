# LINKA architecture

## Product modes

1. **SIM-free nearby calling:** direct local-radio connection between two compatible LINKA phones, independent of a SIM, airtime, mobile data, Wi-Fi internet and cloud services.
2. **Offline multi-hop mesh:** opt-in phone or fixed relay nodes forward encrypted packets across multiple local radio links. This is the required architecture for long-distance offline reach.
3. **Internet app calling:** separate app-to-app calling over an internet connection. It is optional and must not replace a failed offline route.
4. **Optional cellular calling:** Android/system telephony using Digicel Jamaica or FLOW Jamaica. Carrier charges and provisioning still apply.

## Current Android implementation

- Jetpack Compose application shell with Home, Nearby, My ID and Settings destinations.
- Persisted local device ID and P-256 signing key generated and retained in Android Keystore.
- Signed, expiring QR identity exchange, trusted-device storage and revocation UI.
- Google Nearby Connections advertising, discovery, explicit connection requests and two-sided authentication-code confirmation.
- Nearby Connections now uses the cluster strategy and retains multiple direct neighbour links. The UI can select a neighbour for a direct call or disconnect one link without removing the others.
- Call lifecycle/control state handling for invite, ringing, accept, reject, timeout and end.
- An 8 kHz mono, G.711 mu-law 20 ms frame audio path using Android AudioRecord/AudioTrack, with echo cancellation/noise suppression/automatic gain control where supported and a foreground microphone service.

The code includes a direct-call media path, but physical two-phone operation with mobile data and Wi-Fi internet disabled remains a release verification requirement. A successful Gradle build does not validate radio discovery, microphone permissions, two-way audio, acoustic quality, battery use or Android background behavior.

## Android phone hub integrations

The Compose navigation provides a Phone hub with an on-screen keypad and explicit handoffs to Android system applications:
- Carrier dialing uses `Intent.ACTION_DIAL`; Android's dialer presents the number for user review and confirmation. LINKA does not use `ACTION_CALL` or request the `CALL_PHONE` permission.
- SMS composition uses `Intent.ACTION_SENDTO` with an `smsto:` URI and relies on the user's installed messaging app.
- Contacts and recent-call history open the Android contacts and phone applications through their system content URIs.

These handoffs are not an in-app telephony or messaging implementation. Carrier service, SIM provisioning, SMS capability and operator charges remain the user's responsibility. In-app SMS, video calling and internet-based calling are not implemented in this build. The offline Nearby/mesh voice path remains separate and never silently falls back to carrier service.

## Radio topology and distance

- Nearby Connections P2P_POINT_TO_POINT is one-to-one and prioritizes highest throughput. It cannot keep multiple peer links open at once.
- Nearby Connections P2P_CLUSTER supports multiple-to-multiple nearby peer connections, which provides a basis for a local cluster and relay neighbours, but has lower bandwidth than point-to-point.
- The current app uses the cluster topology to retain multiple neighbour links. It does not yet implement an overlay route protocol, so a connected cluster is not the same thing as an operational multi-hop mesh.
- Every radio hop remains limited by the actual radios, handset models, environment and power state. A long-distance path requires a continuous chain of simultaneously available relay nodes.
- For wider or more reliable coverage, evaluate fixed powered relay nodes and separately validated radio hardware. Do not assume that every Android phone supports the same Wi-Fi Direct, Wi-Fi Aware or simultaneous-radio capabilities.

## Target layers

- **UI:** nearby discovery, direct contacts, route status, incoming calls, active calls, relay consent and network diagnostics.
- **Identity:** stable local device ID, Keystore signing identity, QR exchange, local username cache, trust and revocation.
- **Radio adapters:** Google Nearby Connections first; separately tested Wi-Fi Direct, Wi-Fi Aware or other local-radio adapters where device support justifies them.
- **Neighbour manager:** authenticated peer links, capabilities, link health, user consent, battery policy and per-peer limits.
- **Route discovery:** route requests/advertisements, route expiry, path cost, hop limits, loop prevention, duplicate suppression and route repair.
- **Relay data plane:** bounded packet envelope, per-hop forwarding, congestion/back-pressure, rate limits and failure handling. A relay may forward ciphertext only.
- **Call control:** signed invite, ringing, accept, reject, timeout, end and reconnect, with origin identity and replay protection.
- **Audio:** low-latency capture/playback, codec negotiation, jitter buffering, packet-loss concealment, adaptive bitrate and per-hop performance measurement.
- **End-to-end security:** authenticated identity binding, ephemeral key agreement, per-call keys, authenticated encryption, replay protection, key erasure and no decryption at relays.
- **Optional internet mode:** independently designed signaling and media transport; never silently selected as a replacement for offline mode.

## Long-distance offline behavior

Offline distance is a network-coverage problem, not a setting in the app. For a call between distant towns, there must be an active chain of phones or relay devices, with every node connected to the next. If any gap has no relay link, a live voice call cannot cross it.

Text and small control messages may later support store-and-forward delivery when recipients are temporarily unavailable. Live voice is different: it requires a continuous, low-latency path. A delayed message queue must never be presented as a live call.

The mesh protocol must preserve end-to-end encryption from caller to recipient. Nearby Connections encrypts each direct radio connection, but that alone is not end-to-end encryption across multiple relays: the application must encrypt media before the first hop so intermediate phones only see ciphertext.

## Hard constraints

- A username is not a radio address. Offline lookup requires a cached identity or an established route to a reachable device.
- The Google Nearby Connections transport requires compatible Google Play services; GMS-free support needs another adapter.
- Users must enable the required local radios and grant Android permissions. The app must not claim it can force radios on.
- Cluster bandwidth, simultaneous connection capacity, and audio quality must be measured on actual target handsets.
- Relays must be opt-in, battery-aware, rate-limited and unable to decrypt forwarded call media.
- No unlimited-range or island-wide coverage claims without measured physical relay coverage.
- Never silently fall back to a chargeable SIM call.
- LINKA is not an emergency service.

## Delivery sequence

1. Keep the Android build and unit-test baseline green.
2. Validate a two-phone direct call with SIM removed or disabled, mobile data off and Wi-Fi internet disconnected; manually enable only the local radios required by Android.
3. Complete identity-bound call authentication and end-to-end media key agreement/encryption.
4. Test simultaneous cluster neighbour links and direct voice quality across supported Android handsets.
5. Implement and test route discovery, ciphertext forwarding, bounded hop count, duplicate suppression, loop prevention, route expiry and route repair.
6. Test live voice across increasing relay hops, measuring setup time, one-way delay, packet loss, jitter, battery and reconnect behavior.
7. Evaluate fixed, powered relay nodes and additional radio hardware for areas where phones cannot maintain a continuous chain.
8. Complete security review, Jamaica field testing, release signing and distribution.
