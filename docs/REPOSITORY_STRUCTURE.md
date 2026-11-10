# LINKA repository structure and organization

## Repository identity and verified inventory

- Repository: `ravaunrichards/Linka-Android`
- Default branch: `main`
- Product: LINKA — independent Android-first international calling and communications platform.
- Active implementation branch for encrypted messaging: `feature/messaging-device-e2ee`
- Structure inventory commit: `bfaea9fedcc925a64af8f49d537e45275af8b187`
- Recursive Git tree: 172 entries; not truncated.
- This is an existing application repository. Preserve the current Gradle root and package layout; do not create a second repository or an artificial duplicate tree.

## Current repository tree

```text
Linka-Android/
├── .github/
│   ├── CODEOWNERS
│   ├── ISSUE_TEMPLATE/
│   ├── PULL_REQUEST_TEMPLATE.md
│   ├── dependabot.yml
│   └── workflows/
│       ├── android-ci.yml
│       ├── database-ci.yml
│       └── linka-api-ci.yml
├── app/                              # Android Gradle application module
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/xml/
│       │   └── java/com/linka/android/
│       │       ├── MainActivity.kt
│       │       ├── LinkaApplication.kt
│       │       ├── LinkaAccountPanel.kt
│       │       ├── PhoneHubScreen.kt
│       │       ├── MessagingScreen.kt
│       │       ├── account/
│       │       │   └── LinkaAccountClient.kt
│       │       ├── messaging/
│       │       │   └── AndroidMessagingKeyManager.kt
│       │       └── core/
│       │           ├── audio/
│       │           │   ├── LinkaVoiceForegroundService.kt
│       │           │   ├── NearbyVoiceSession.kt
│       │           │   ├── RoutedVoiceSession.kt
│       │           │   ├── VoiceFrameCodec.kt
│       │           │   └── MuLawCodec.kt
│       │           ├── call/
│       │           │   ├── CallControlCodec.kt
│       │           │   ├── CallStateMachine.kt
│       │           │   └── NearbyCallController.kt
│       │           ├── identity/
│       │           │   ├── LocalDeviceIdentity.kt
│       │           │   ├── LinkaQrIdentity.kt
│       │           │   ├── LinkaQrBitmap.kt
│       │           │   └── TrustedDeviceStore.kt
│       │           ├── mesh/
│       │           │   ├── MeshModels.kt
│       │           │   ├── MeshPacketRouter.kt
│       │           │   ├── RelayConsentStore.kt
│       │           │   ├── RouteSelector.kt
│       │           │   └── RoutedPayloadCodec.kt
│       │           ├── nearby/
│       │           │   └── NearbyDirectLink.kt
│       │           └── security/
│       │               └── PeerSessionSecurity.kt
│       ├── test/                     # Android/JVM unit tests
│       └── androidTest/              # Android instrumented/Keystore tests
├── backend/
│   └── api/
│       ├── package.json
│       ├── db/
│       │   ├── checks/
│       │   │   └── verify_core_schema.sql
│       │   └── migrations/
│       │       ├── 0001_core_schema.sql
│       │       ├── 0002_cross_account_integrity.sql
│       │       ├── 0003_messaging_device_encryption.sql
│       │       ├── 0004_messaging_device_receipts.sql
│       │       └── 0005_messaging_encrypted_attachments.sql
│       ├── src/
│       │   ├── account-auth.mjs
│       │   ├── cors.mjs
│       │   ├── postgres-store.mjs
│       │   ├── runtime.mjs
│       │   ├── server.mjs
│       │   └── domain/
│       │       ├── credit-ledger.mjs
│       │       ├── destination-metadata.mjs
│       │       ├── e164.mjs
│       │       ├── rate-catalog.mjs
│       │       └── route-options.mjs
│       ├── test/                     # API, domain and PostgreSQL integration tests
│       ├── verify-browser-account.e2e.mjs
│       └── verify-browser-ui.e2e.mjs
├── web/                              # Browser companion / web preview
│   ├── index.html
│   ├── styles.css
│   ├── app.js
│   ├── account.mjs
│   ├── auth-crypto.mjs
│   └── messaging-crypto.mjs
├── docs/
│   ├── INDEX.md
│   ├── PRODUCT_REQUIREMENTS.md
│   ├── ARCHITECTURE.md
│   ├── ARCHITECTURE_DECISIONS.md
│   ├── REPOSITORY_STRUCTURE.md
│   ├── PROJECT_STATUS.md
│   ├── ROADMAP.md
│   ├── INTEGRATION_MATRIX.md
│   ├── ACCOUNT_AUTH.md
│   ├── DATABASE_SCHEMA.md
│   ├── RENDER_API_SERVICE.md
│   ├── TELEPHONY_AND_BILLING.md
│   ├── WALLET_LEDGER_DESIGN.md
│   ├── OFFLINE_MESH_ROADMAP.md
│   ├── JAMAICA_COMPATIBILITY.md
│   ├── SECURITY.md
│   └── TESTING.md
├── deployment/                       # Render/deployment runbook (no live secrets)
│   └── README.md
├── .editorconfig
├── .gitattributes
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── CONTRIBUTING.md
├── SECURITY.md
├── LICENSE
└── README.md
```

The tree above is an ownership map of the active branch. It intentionally omits generated build output, local secrets, caches and empty future directories.

## Mapping from the requested conceptual layout

The original planning layout used `android/`, `web-preview/` and root `tests/` names. The current repository already uses standard Gradle and colocated-test conventions. Keep those real conventions:

| Planned capability | Existing implementation location | Current boundary |
|---|---|---|
| Android application | `app/` | Existing Gradle application module; do not move beneath a second `android/` root without a coordinated Gradle/CI migration |
| Android core | `app/src/main/java/com/linka/android/core/` | Audio, call state, identity, nearby radio, mesh and security packages |
| Android dialer/phone UI | `PhoneHubScreen.kt` and phone hub components | Native phone hub currently hands carrier dialing to Android system `ACTION_DIAL`; not a replacement default dialer |
| Android contacts | Phone hub/system contact handoffs | Full in-app contact management/sync is not yet a verified complete capability |
| Android telecom | Android manifest and phone handoff integration | Full Telecom/Core-Telecom/default-dialer integration remains a release task |
| Android messaging/security | `MessagingScreen.kt`, `messaging/`, `LinkaAccountPanel.kt` | Device-key E2EE, encrypted attachments, per-device receipts and account device list/revoke controls on PR #20 |
| Backend API/authentication | `backend/api/src/server.mjs`, `account-auth.mjs` | API routing and signed-device/account authentication |
| Backend signaling/calls | `backend/api/src/runtime.mjs`, server routes | Call-control signaling queue; does not relay voice media |
| Backend device management | `postgres-store.mjs`, `server.mjs`, `backend/api/test/device-management-store.test.mjs`, `backend/api/test/postgres-device-management.integration.test.mjs` | Owner-scoped device inventory and revocation; revokes sessions, disables relay consent and retires active ECDH keys |
| Backend notifications | Current API and Android messaging/call notification code | Dedicated production push/delivery service is not yet verified |
| Web preview | `web/` | Browser companion; not yet a full web telephone client |
| Tests | Android `test/`, Android `androidTest/`, backend `test/` and E2E scripts | Includes Android client tests and PostgreSQL-backed account-device revocation integration tests; tests remain beside the owning module |
| Deployment | `docs/RENDER_API_SERVICE.md`, `deployment/README.md` | Runbook only; live Render configuration is not verified by repository CI |
| Documentation | `docs/` and root governance files | Product, architecture, tests, security, release gates and recovery checkpoints |

## What must not be represented as complete

The current source tree is substantial, but folder existence is not proof of product readiness. These are outstanding until supported by code, tests and operational evidence:

- Real SIP/PSTN carrier integration, rented telephone-number inventory and SMS/MMS provider delivery.
- Production WebRTC media transport with TURN/SFU where required; the existing API signaling queue is not a media relay.
- Full Android Telecom/default-dialer integration and SIM/eSIM provisioning.
- Dedicated production notification delivery and operational monitoring.
- Complete browser calling, number management, wallet and settings experiences.
- Independent review of the cryptographic design and production key transparency/recovery.
- Two-phone and four-or-more-phone field proof for direct/mesh offline voice.
- Production Render/PostgreSQL deployment, protected release signing and Play Store release.
- Emergency calling; LINKA must not claim to be an emergency-calling service.

## Organization rules

1. Keep one independent LINKA repository: `ravaunrichards/Linka-Android`.
2. Keep Android Kotlin source inside the existing `app/` Gradle module and use capability packages below `com.linka.android`.
3. Keep API, authentication, signaling, domain rules, persistence and database migrations under `backend/api/` until a bounded service is real and independently deployable.
4. Keep browser source under `web/`; it is the web preview/client.
5. Keep automated tests beside the module they validate; do not add a duplicate root `tests/` tree containing copies.
6. Keep deployment instructions under `deployment/` and service-specific operational details under `docs/`.
7. Never add empty placeholder folders, demo service shells, fake data or unconfigured provider adapters merely to make the tree look larger.
8. Keep credentials, API keys, database passwords, signing keys and keystores outside Git.
9. Before a source move, update Gradle settings, imports, CI paths, Render build/start paths and documentation together; run all affected workflows before merging.
10. Update this map and `docs/PROJECT_STATUS.md` when a real module or verified capability changes.
