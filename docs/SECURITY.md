# Security requirements and current status

## Implemented foundation

- A random local device ID is persisted in app-private preferences. It is a public routing label, not a secret.
- A P-256 signing key pair is generated using Android Keystore. The private key is not exported by the app.
- The identity screen can display a signed, short-lived public identity QR. The peer can scan, verify and explicitly trust the presented identity; trusted devices can be revoked.
- Nearby connection requests display the SDK authentication digits for both users to compare and explicitly accept or reject.
- Google Nearby Connections encrypts each direct peer transport. This protects an individual radio link; it does not, by itself, provide caller-to-recipient end-to-end encryption through intermediate relays.

## Required before production direct calling

- Bind the connected Nearby endpoint to the QR-verified LINKA device identity. Do not treat a radio endpoint ID or display name as an authenticated identity.
- Sign and verify call-control messages so a peer's device ID and call lifecycle cannot be silently substituted.
- Establish ephemeral key agreement and derive unique keys for each call and direction.
- Encrypt voice frames with a reviewed AEAD construction and authenticated metadata.
- Include sequence numbers, replay protection, bounded packet lifetimes, key rotation and safe failure behavior.
- Erase per-call secrets and stop capture/playback on every disconnect, rejection, permission loss, call end or app shutdown.
- Do not expose call secrets or private keys in QR codes, logs, notifications, crash reports or analytics.

## Additional requirements before mesh relaying

- End-to-end encrypt call media before it leaves the originating phone. Relays must never receive media decryption keys.
- Authenticate every immediate neighbour and reject unauthenticated route advertisements.
- Use bounded hop counts, packet expiry, duplicate suppression, loop prevention and rate limits.
- Protect route-control messages against spoofing and replay.
- Make relay participation explicit, visible and revocable. Apply battery, thermal and data-rate limits.
- Ensure relays cannot modify media undetected. A relay may drop or delay ciphertext, but it must not be able to impersonate the caller or recipient.
- Test malicious relays, route poisoning, replay, malformed packets, connection churn and denial-of-service attempts.

## QR pairing

QR content may include a public device ID, public key, version and an expiry-limited pairing challenge. It must never include private keys, session secrets or reusable bearer credentials. Pairing must verify the scanned public identity and require explicit user consent.

## Threat model

Address malicious nearby endpoints, device impersonation, man-in-the-middle pairing, replay, malformed payloads, route poisoning, malicious relays, denial of service, traffic analysis, lost devices and compromised relay devices. Include trust revocation and lost-device recovery before production.

## Release gate

The current code contains direct-call control and audio components, but they still require real-device testing with internet connectivity disabled. Do not claim that LINKA calls are caller-to-recipient end-to-end encrypted until authenticated session keys and encrypted media frames are implemented, tested and independently reviewed. Do not enable multi-hop voice relaying before that security gate passes.
