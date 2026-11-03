# LINKA API service

## Purpose

The Render service is the internet-connected signaling backend for LINKA Android. It supports signed device authentication and ephemeral call-control / WebRTC signaling between online app instances.

It is **not** part of SIM-free nearby calling. The offline radio path must keep working with no SIM, no mobile data, no Wi-Fi internet and no Render access.

The API does not relay voice audio. It is not a TURN server, SFU, push-notification provider, user directory, or persistent database.

## Render service configuration

- Service name: `linka-api`
- Repository: `https://github.com/ravaunrichards/Linka-Android`
- Branch: `main`
- Runtime: Node
- Node version: 22 or newer
- Build command: `npm --prefix backend/api install --omit=dev`
- Start command: `npm --prefix backend/api start`
- Health check path: `/healthz`
- Region target: Virginia
- Initial plan target: Free (subject to Render's current plan availability and limits)
- Required secret environment variables: none
- Port: Render-provided `PORT`

The API has no third-party runtime dependencies. It uses Node.js built-in HTTP and cryptography modules.

## Health and service information

- `GET /healthz` — basic process health
- `GET /readyz` — readiness and available API capabilities
- `GET /v1/info` — API version, supported signaling mode and explicit media limitations

## Device authentication

1. `POST /v1/auth/challenge` with `{ "deviceId": "<uuid>" }`.
2. The server returns `challengeId`, a random `nonce`, `issuedAt`, and `expiresAt`.
3. The Android device signs the UTF-8 statement below with its Android Keystore P-256 identity key:

```text
LINKA-SERVER-AUTH-V1
{challengeId}
{nonce}
{deviceId}
{issuedAt}
```

4. `POST /v1/auth/verify` with `challengeId`, `deviceId`, `publicKeyBase64` (DER SubjectPublicKeyInfo), and `signatureBase64` (DER ECDSA signature).
5. The server verifies possession of the matching P-256 private key and returns a random, expiring bearer token.

The challenge is single-use and expires after 90 seconds. Bearer sessions expire after one hour. The service verifies device-key possession; it does not create a global username account or prove that a device is trusted by another person. Trust remains a separate in-person QR action in LINKA.

## Call signaling

Authenticated calls use:

- `POST /v1/signals` to send a signal to an online device.
- `GET /v1/signals` to poll and consume up to 25 queued signals.

Allowed signal types: `call_invite`, `call_accept`, `call_reject`, `call_busy`, `call_end`, `webrtc_offer`, `webrtc_answer`, `webrtc_ice`.

The sender identity is taken from the authenticated bearer session, not from a client-supplied sender field. The service rejects offline recipients, self-signals, unsupported message types, malformed call IDs and payloads over 16 KiB.

## Operational limitations

- Sessions, online presence and queued signals are in memory. A deploy or restart clears them.
- Signals expire after 90 seconds; the service is not an offline message store.
- The API has no account database and no permanent username lookup.
- The service does not receive or forward audio. WebRTC media connectivity requires a separate direct peer path and, for restrictive networks, a separately provisioned TURN service.
- The Android client is not yet integrated with these HTTPS endpoints. The API deployment alone does not activate internet calling in the app.
- Free instances may sleep or be constrained by Render's current free-plan policy. Use a paid always-on plan before relying on internet calling for production or emergency communication.
- Do not send passwords, private keys, call audio, or long-lived secrets to the API.

## Verification

Run `npm --prefix backend/api test` locally or use the LINKA API CI workflow. After deployment, verify `/healthz`, `/readyz`, and `/v1/info` on the Render URL. Physical Android-to-Android internet call tests are still required after client integration.
