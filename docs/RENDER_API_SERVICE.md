# LINKA API service

## Purpose

The Node.js service in `backend/api/` serves the browser companion and exposes LINKA's signed-device authentication, PostgreSQL-backed account/session routes (when `DATABASE_URL` is configured), and call-control signaling.

The API does not relay voice audio. It is not a TURN server, SFU, push-notification provider, number-provisioning provider, PSTN/SIP gateway or SMS/MMS gateway.

## Repository-defined runtime configuration

These are the configuration requirements expressed by the repository. They are not proof that a live Render service currently has them set.

- Service name previously recorded: `linka-api`.
- Previously recorded origin: `https://linka-api-vq2h.onrender.com/` (historical; not live-verified in this GitHub-only check).
- Repository: `ravaunrichards/Linka-Android`.
- Branch: `main`.
- Runtime: Node.js 22 or newer.
- Build command: `npm --prefix backend/api install --omit=dev`.
- Start command: `npm --prefix backend/api start`.
- Port: Render-provided `PORT`.
- Health endpoint: `/healthz`.
- Optional `DATABASE_URL`: enables PostgreSQL-backed account registration, persistent account sessions and the server-side wallet store. Account endpoints return `database_not_configured` if PostgreSQL is not configured.
- Optional `CORS_ALLOWED_ORIGINS`: comma-separated exact HTTPS origins allowed to call the API from a separately hosted browser website. Same-origin hosting needs no CORS entry. Wildcards, URL paths and remote HTTP origins are rejected. Local HTTP is allowed only for `localhost` and `127.0.0.1`.
- No email-delivery provider is configured in this repository; password recovery and email verification remain unavailable.

The service uses Node.js built-in HTTP/cryptography modules and the `pg` package for PostgreSQL access.

## Browser origin and CORS

The browser companion defaults to the same origin as the API, so same-origin deployment requires no CORS configuration.

For a separate browser domain:
1. Set `CORS_ALLOWED_ORIGINS` on the API service to exact origins, for example `https://app.example.com`.
2. Separate multiple origins with commas.
3. Do not include paths, trailing URL routes, wildcards or insecure remote HTTP origins.
4. Ensure the browser points to the API's HTTPS origin.

The API handles preflight `OPTIONS` requests for `GET`, `POST`, `PUT` and `DELETE`, allowing only `Authorization` and `Content-Type` request headers. PUT is used for signed device encryption-key registration; DELETE is used for owner-authorized device revocation. It returns `Access-Control-Allow-Origin` for the exact allowlisted origin and rejects unlisted cross-origin requests with HTTP 403. Credentialed cookies are not enabled; browser account tokens use explicit bearer authorization.

API CI tests the allowed-origin preflight, the rejected-origin preflight, origin validation, browser account modules and API startup. This verifies repository behavior, not a live Render environment configuration.

## Browser and health routes

- `GET /` — browser companion entry point.
- `GET /styles.css`, `GET /app.js`, `GET /account.mjs`, `GET /auth-crypto.mjs` — browser assets.
- `GET /healthz` — basic process health.
- `GET /readyz` — readiness and configured capabilities.
- `GET /v1/info` — API version, signaling mode and explicit limitations.
- `POST /v1/destinations/normalize` — rate-limited E.164 syntax normalization; this does not infer country/number type or check provider reachability.
- `POST /v1/destinations/resolve` — rate-limited numbering-plan validation with country candidates and number type from `libphonenumber-js/max`; provider assignment and reachability remain unchecked.

## Authenticated read-only wallet

When PostgreSQL is configured and the authenticated account has a wallet row:

- `GET /v1/wallet` — returns the authenticated account's purchased, promotional, sponsored and total credit-unit balances plus a wallet version. Account identity is taken from the bearer session, not from query/body parameters.
- `GET /v1/wallet/ledger?limit=50` — returns the authenticated account's most recent ledger entries, newest first. The page size is 1–100.
- Optional `before` and `beforeEventId` query parameters continue a cursor page. Both values are required together; the timestamp cursor preserves PostgreSQL microsecond precision.
- Both endpoints are read-only, require a persisted account session, and are rate-limited. They do not accept client balance writes.
- New accounts receive an empty wallet row during the account-registration transaction. If an account has no wallet row, the API returns `404 wallet_not_found`.
- Ledger entries come from the server-side immutable wallet ledger. Purchases, refunds, usage charges and promotional/sponsored credits must be created by trusted server-side events; a client cannot mint or modify credit balances.

The browser account page displays the four balance categories and cursor-paginated activity. It does not provide a purchase or top-up flow. Payment processing, signed provider webhooks, purchase reconciliation, customer-funded credits and provider tariff billing remain unimplemented.

## Authenticated internal messaging

PostgreSQL-backed account sessions can use the following routes:

- `GET /v1/conversations?limit=50` — list the signed-in account's active direct and group conversations, member display names, and latest-message metadata.
- `POST /v1/conversations` — create or reuse a direct conversation, or create a group. The request supplies `kind` (`direct` or `group`) and recipient `participantAccountIds`; the server adds the authenticated account as a member. Direct conversations are reused for the same active two-account pair. Group conversations allow up to 50 total members.
- `PUT /v1/me/encryption-key` — register the authenticated device's ECDH P-256 public key. Registration requires an ECDSA signature by that device's existing LINKA signing identity; key rotations are versioned.
- `GET /v1/conversations/:conversationId/encryption-devices` — list active member devices and their current public encryption keys. Non-members receive the same 404 response as an unknown conversation.
- `POST /v1/conversations/:conversationId/messages` — send base64-encoded ciphertext and `LINKA-E2EE-2` key envelopes. The server requires one current wrapped content-key envelope for every active device and rejects missing or stale keys. The sender account and device are taken from the authenticated session, never the request body. Ciphertext is limited to 12 KiB.
- `GET /v1/conversations/:conversationId/messages?limit=50` — retrieve the newest messages. The response includes only the message-key envelope addressed to the authenticated device. Aggregate receipt counts are visible to the sender, not recipients.
- `POST /v1/conversations/:conversationId/receipts` — acknowledge up to 100 message IDs as `delivered` or `read`. Receipt rows are scoped to the authenticated device and account. A read acknowledgement also records delivery.

All messaging routes require a persisted account session and active conversation membership. A device-challenge token without an account session is insufficient. Unknown or inaccessible conversations return the same 404 response to prevent membership enumeration. Requests are rate-limited per account/device and remote address.

**Security boundary:** browser and Android clients encrypt messages locally using ECDH P-256, HKDF-SHA-256 and AES-256-GCM. The backend stores ciphertext and wrapped per-device content keys; it never receives private keys or plaintext. A signed device-key registration prevents an account bearer token alone from silently replacing a device's public encryption key. The server enforces current key coverage but cannot prove that every client correctly implemented the cryptographic protocol. An independent cryptographic audit is still required. Encrypted attachment ciphertext up to 4 MiB is accepted through the authenticated attachment routes, with per-device wrapped keys and conversation membership checks. The current implementation uses the configured PostgreSQL store; production object storage, backup/retention controls, antivirus/content policy and independent security review remain separate release requirements.
- `OPTIONS` — CORS preflight for configured cross-origin browser clients.

## Account authentication

When PostgreSQL is configured:
- `POST /v1/auth/challenge` and `POST /v1/auth/verify` establish a signed device session.
- `POST /v1/accounts/register` creates an account and links the verified device.
- `POST /v1/accounts/login` signs an existing account in on another verified device.
- `GET /v1/me` returns the current account profile.
- `POST /v1/auth/logout` revokes the persistent account session.
- `POST /v1/accounts/recovery` explicitly returns HTTP 501 until email delivery and one-use recovery tokens are implemented.

Passwords are stored as salted scrypt hashes. Persistent bearer tokens are stored as SHA-256 hashes, not plaintext.

## Device authentication

1. `POST /v1/auth/challenge` with a device UUID.
2. The server returns a one-time challenge ID, nonce, issue time and expiry.
3. The device signs the LINKA server-authentication statement using its P-256 device key.
4. `POST /v1/auth/verify` submits the device ID, DER SPKI public key and DER ECDSA signature.
5. The server verifies the signature and returns a short-lived bearer token.

Device-key possession does not by itself prove another person has trusted the device. Trust remains a separate QR pairing action.

## Call signaling

Authenticated clients use:
- `POST /v1/signals` to send a supported call-control/WebRTC signaling event to an online device.
- `GET /v1/signals` to poll and consume queued signaling events.

Signaling queues and short-lived device challenge sessions remain in memory and are lost on restart. The API does not transport voice audio.

## Operational limitations and verification

- No SIP/PSTN termination, carrier number inventory, SMS/MMS provider, payment settlement, TURN/SFU media relay or emergency calling is implemented by this service.
- The Android client has not been validated for internet media calls against a live provider.
- A configured PostgreSQL connection is required for account registration/login and persistent account sessions.
- Render service status, environment variables, live origin, plan, region and deployment health were not checked during this GitHub-only recovery.

Use `npm --prefix backend/api test` or the LINKA API CI workflow. CI validates unit tests, CORS policy, browser assets and API startup; it does not establish production deployment or physical-device readiness.
