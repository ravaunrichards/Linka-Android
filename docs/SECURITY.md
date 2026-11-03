# Security requirements and current status

## Implemented foundation

- A random local device ID is persisted in app-private preferences. It is a public routing label, not a secret.
- A P-256 signing key pair is generated using Android Keystore. The private key is not exported by the app; the public-key fingerprint is shown in the identity screen.
- Nearby connection requests require a human to compare the same four-digit authentication code on both phones and explicitly accept. Unexpected requests must be rejected.
- The Nearby Connections SDK provides an encrypted peer transport, but LINKA still needs its own authenticated identity binding and call-session security.

## Required before voice calling

- Sign and verify identity claims so a peer's device ID and public key cannot be silently substituted.
- Establish ephemeral endpoint key agreement and derive independent keys for each call direction.
- Encrypt voice frames using a reviewed AEAD construction with unique nonces and authenticated metadata.
- Include sequence numbers, replay protection, bounded packet lifetimes and safe re-keying.
- Erase per-call secrets and stop capture/playback on every disconnect, rejection, permission loss or app shutdown.
- Do not expose call secrets or private keys in QR codes, logs, notifications, crash reports or analytics.
- Mesh relays must never possess media decryption keys and may forward ciphertext only.

## QR pairing

QR content may include a public device ID, public key, version and an expiry-limited pairing challenge. It must never include private keys, session secrets or reusable bearer credentials. Pairing must verify the scanned public identity and require explicit user consent.

## Threat model

Address malicious nearby endpoints, device impersonation, man-in-the-middle pairing, replay, malformed payloads, denial of service, traffic analysis, lost devices and malicious relays. Add trust revocation and lost-device recovery before production.

## Release gate

The current pairing screen is not a complete end-to-end identity system, and voice media has not yet been implemented. Do not claim that LINKA voice calls are end-to-end encrypted until call media is implemented, tested and independently reviewed.
