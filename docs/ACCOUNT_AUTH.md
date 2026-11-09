# LINKA account authentication

## Current API contract

Account registration and password login use the PostgreSQL account/device/session schema. Both routes require an existing bearer token issued by the P-256 signed-device challenge flow.

1. Call `POST /v1/auth/challenge` with the stable device UUID.
2. Sign the returned challenge statement with the device's Android Keystore P-256 signing key.
3. Call `POST /v1/auth/verify` with the device UUID, public-key DER and signature. This creates a short-lived device-authentication token.
4. Send that device token to either registration or login.
5. On success, store the returned persistent account bearer token securely on the client and use it with `/v1/me`, signaling and logout.

### Register

`POST /v1/accounts/register`

Headers: `Authorization: Bearer <signed-device-token>`.

JSON body:

- `email`: normalized to lowercase and validated.
- `password`: 12–256 characters.
- `displayName`: 1–100 characters.
- `platform`: `android`, `web` or `other`.
- `deviceName`: optional, up to 100 characters.

The API creates the account, links the proven device public key/fingerprint, creates a wallet with zero balances and inserts a persistent session in one PostgreSQL transaction. A device fingerprint is globally unique and cannot be linked to another account. The response contains account ID, normalized email, display name, device ID, bearer token, expiry and `emailVerified: false`.

### Login

`POST /v1/accounts/login`

Requires the same signed-device token header. JSON body includes email, password, platform and optional device name.

The API verifies the scrypt password hash and then links the signing device to the same account. A different signed device may log in to the same account, creating a separate device and session. A device already linked to another account is rejected. Unknown email and incorrect password both return a generic invalid-credentials response.

### Current account and logout

- `GET /v1/me` returns account identity when called with a persistent account token; a short-lived device token returns device-only identity.
- `POST /v1/auth/logout` revokes the persistent database session when called with an account token. It also revokes ephemeral in-memory sessions for that device.
- Persistent account tokens are 32 random bytes encoded as base64url. Only their SHA-256 digests are stored in `linka_private.auth_sessions`.
- Account sessions expire after 30 days and are rejected if the session, account or device has been revoked/deactivated.
- Passwords are stored as versioned scrypt hashes with unique random salts; plaintext passwords are never stored.

## Not yet available

- `POST /v1/accounts/recovery` returns HTTP 501 `account_recovery_unavailable`. This is explicit; LINKA does not pretend to send a reset email.
- Email verification is not enabled. New accounts report `emailVerified: false`.
- No email-delivery provider or provider credentials are configured in this repository.
- No public wallet HTTP API is exposed yet. Wallet operations remain a server-side store capability; a request must never choose its own account ID for wallet access.
- Android and browser registration/login/profile/sign-out screens are now present in source. Android uses `LinkaAccountClient` with an AES-GCM encrypted account token protected by a separate Android Keystore key. The browser uses a non-exportable P-256 WebCrypto key persisted in IndexedDB and a tab-scoped account token in `sessionStorage`. Android CI and API CI passed for the current UI integration commit; browser crypto and API asset-serving checks are included in API CI. A real browser-to-hosted-PostgreSQL end-to-end run and physical Android device test remain outstanding.

Password reset and verification must not be implemented as a fake success response. Add a verified email-provider adapter, time-limited one-use hashed tokens, generic request responses, rate limits, token invalidation and delivery tests before enabling those routes.

## Client session behavior

- Android stores the API origin in app preferences and stores only the encrypted account token ciphertext and IV; the AES key remains in Android Keystore. A 401 during session restore clears the local token.
- The browser stores the non-exportable device private key in IndexedDB and keeps the account bearer token in `sessionStorage`, so closing the tab ends the locally restored browser session. This is intentionally not cross-tab persistent token storage.
- Browser session restoration clears the token only when `/v1/me` explicitly returns HTTP 401. Network failures and server errors retain the tab-scoped token so a temporary outage does not force the user to sign in again. The status policy is unit-tested.
- The account token is separate from the short-lived signed-device challenge token. The browser signing identity is browser-specific and is not shared with the Android Keystore identity.
- Password recovery returns an explicit unavailable response until a real email delivery provider and reset-token workflow are configured.

## Browser API origin and CORS

- The browser companion uses the same-origin API by default; no CORS environment setting is needed when the API serves the web assets.
- If the browser website is hosted on a separate domain, add that exact HTTPS origin to `CORS_ALLOWED_ORIGINS` on the API server. Multiple origins are comma-separated; wildcards, URL paths and insecure remote HTTP origins are rejected.
- Local HTTP origins are allowed only for `localhost` and `127.0.0.1` development.
- Cross-origin preflight supports the API's `GET`/`POST` requests and the `Authorization` and `Content-Type` headers. The API does not enable credentialed cookie sharing; account bearer tokens are sent explicitly.
- Unlisted cross-origin requests and preflights are rejected with HTTP 403. API CI tests both an allowed browser origin and an untrusted origin.

## Security boundaries

- Registration/login never trust a client-supplied account ID, device UUID or public key. Device identity is taken from the successfully verified challenge session.
- The store recomputes the public-key fingerprint from DER bytes and enforces the unique device-to-account mapping in PostgreSQL.
- Persistent sessions are bound to the same account/device pair by composite foreign keys.
- Password login failures use a generic response to reduce account enumeration.
- Authentication routes are rate-limited by remote IP.
- Email and account creation remain separate from number provisioning; no number is acquired merely by creating a LINKA account.
