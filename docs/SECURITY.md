# LINKA Security Model and Status

## Identity and trust
- Each installation has a persistent public device ID and a P-256 signing key generated and retained by Android Keystore.
- The My ID screen produces a signed, short-lived public identity QR. Users scan the other phone's QR in person, inspect the fingerprint and explicitly trust it.
- Trusted device records can be revoked. Trust is local to each installation; it is not a global account directory.
- Nearby Connections authentication digits are still compared and accepted on both phones.
- A second signed handshake binds the radio endpoint to the QR-trusted identity. The handshake uses a fresh ephemeral P-256 ECDH key, signs the transcript with the trusted identity key, derives a session secret with HKDF-SHA-256 and confirms it with transcript-bound HMAC.

## Direct-call protection implemented in source
- Call-control uses direction-separated AES-GCM keys derived from the authenticated peer session.
- Each call derives fresh direction-separated media keys from the authenticated session and a unique call ID.
- Every 20 ms G.711 voice frame is encrypted/authenticated before being sent over the nearby stream.
- Authenticated context binds the packet to the control or voice channel and, for voice, to the call ID.
- Sequence numbers, a bounded replay window, duplicate rejection, authentication-tag verification and strict frame-size limits are enforced.
- Reusing a call ID within a peer session is rejected.
- Key byte arrays are cleared on call stop, disconnect, peer-session replacement, trust refresh and shutdown. Managed-runtime key clearing is best effort, not a guarantee that every JVM/OS copy has been overwritten.

## Routed-call and relay protection implemented in source
- Relay consent is local, persistent and disabled by default. The remote relay capability is included in the signed peer handshake.
- Each adjacent radio link must be authenticated. Route-control packets are origin-signed, size-bounded, expiry-bounded and constrained by hop limits.
- Route requests and replies maintain reverse paths at relay nodes. Duplicate suppression and loop/ingress checks reject malformed or stale forwarding.
- The routed opaque payload contains a separately authenticated caller-to-recipient peer handshake, encrypted call control or encrypted voice frame.
- The destination and caller establish their own end-to-end session across the route. A relay can forward or discard ciphertext but does not receive the end-to-end media keys.
- Trust changes clear routed peer sessions and route caches. Route expiry or link loss ends the routed call; there is no carrier fallback.

## Security limits and release requirements
This implementation is **not independently cryptographically audited or certified**. Automated unit tests exercise source-level behavior; they do not prove resistance to every malicious device, OS compromise, side channel, radio denial-of-service or implementation defect.

Before production:
- Commission an independent review of the identity handshake, transcript construction, key derivation, nonce/sequence design, AEAD framing, key lifecycle and mesh packet protocol.
- Run two-phone direct tests and multi-phone relay tests on real Android handsets with SIM/cellular data and Wi-Fi internet disabled.
- Test forged identity, revoked trust, replay, reordered frames, duplicate packets, packet flooding, route poisoning, malicious relays, connection churn and relay drop/delay.
- Measure range, latency, jitter, packet loss, audio quality, battery and thermal behavior.
- Test screen-off/background audio, Android microphone foreground-service restrictions, audio routing, permission loss and process death.
- Keep private keys and media keys out of QR codes, logs, notifications, analytics, crash reports and server requests.

## Threat model
Consider nearby endpoint impersonation, QR/key substitution, man-in-the-middle pairing, replay, modified ciphertext, malformed frames, routing attacks, duplicate/looped packets, malicious relays, denial of service, traffic analysis, lost devices, trust revocation and a compromised handset. End-to-end encryption prevents a relay from decrypting media, but cannot prevent it from dropping, delaying or observing traffic metadata.

## QR content
QR codes contain only public identity material, a version, an expiry-limited statement and signature. They must never contain private keys, call secrets, reusable bearer tokens or passwords.

## Product safety
LINKA is an offline communication app, not an emergency-calling service. It must never silently switch to a paid cellular call. No range, coverage or production-security guarantee may be claimed without the corresponding device evidence and review.
