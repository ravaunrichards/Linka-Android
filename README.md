# LINKA — International Calling and Communication Platform

LINKA is an independent Android and web communication product planned for Jamaican and international telephone numbers, free and credit-based services, internet calling, supported cellular/SIM/eSIM integration, messaging and nearby offline voice.

**Important implementation status:** The current repository already contains a substantial Android nearby/offline voice and multi-hop mesh source foundation, automated protocol tests, a small Node.js signaling API and a static browser companion. It is **not yet** a complete TextNow/2ndLine-style cloud telephone service. Provider-backed numbers, SIP/PSTN calls, integrated SMS/MMS, complete internet media calling, a durable account system, credit wallet, payment processing, destination rate catalog and SIM/eSIM provisioning are not verified as implemented.

## Start here
- [Documentation index](docs/INDEX.md)
- [Complete product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Repository structure and organization](docs/REPOSITORY_STRUCTURE.md)
- [Architecture decisions](docs/ARCHITECTURE_DECISIONS.md)
- [Implementation roadmap](docs/ROADMAP.md)
- [Integration status](docs/INTEGRATION_MATRIX.md)
- [Telephony, free service and credits](docs/TELEPHONY_AND_BILLING.md)
- [Project status and recovery checkpoint](docs/PROJECT_STATUS.md)
- [Testing strategy](docs/TESTING.md)
- [Security policy](SECURITY.md)
- [Open physical-device and release gates](https://github.com/ravaunrichards/Linka-Android/issues/2)

## Product principles
- Support Jamaican numbers (+1 876 and +1 658) and international destinations as real providers permit.
- Offer both free and credit choices wherever a genuine route or allowance is available.
- Keep eligible LINKA-to-LINKA calls and messages free; do not deduct wallet credits for internal traffic.
- Fund any free call to an ordinary telephone number through a carrier agreement, sponsor, funded allowance or other disclosed business model.
- Show number rental, calling and messaging prices separately.
- Show destination-specific rates before paid calls or messages and obtain explicit consent.
- Never silently switch a failed free route to a billable route.
- Keep nearby offline voice separate from internet calling and carrier/PSTN calling.
- Do not describe an unimplemented provider integration as operational.

## What is present in the repository today

### Android source foundation
- Kotlin and Jetpack Compose Android application.
- Nearby Connections advertising, discovery and device pairing.
- Android Keystore-based P-256 device signing identity and signed QR identity.
- Trusted-device handshake, ECDH/HKDF session key derivation and authenticated encryption.
- Direct nearby voice capture/playback using 8 kHz mono audio and G.711 mu-law frames.
- Multi-peer nearby links, explicit relay consent and multi-hop route discovery/forwarding.
- End-to-end encrypted routed call-control and voice payloads.
- Automated unit tests for call control/state, audio framing, peer security and routing.

These are source and automated-test capabilities. Physical radio/audio tests and independent security review remain release gates.

### Android system-app handoffs
The Phone hub uses Android ACTION_DIAL so the user reviews a carrier number in the system dialer. SMS composition, contacts and recent-call history are handed to installed Android system apps. These are not LINKA's own integrated carrier calling or SMS/MMS services.

### Backend and browser companion
- backend/api contains a Node.js signed-device challenge and ephemeral call-signaling API.
- The signaling runtime holds sessions and queues in memory; it is not a durable account, number, message or billing database.
- The API carries signaling, not voice audio.
- web contains a static responsive browser companion. It cannot access Android Nearby Connections or Android Keystore and is not a full web telephone client.

## Current repository layout
- app/ — Android application and native functionality.
- backend/api/ — signaling API and tests.
- web/ — static browser companion.
- docs/ — product, architecture, security, compatibility, integration and testing records.
- .github/ — GitHub Actions and repository collaboration configuration.
- README.md, CONTRIBUTING.md and SECURITY.md — repository entry and governance guidance.

See [Repository structure](docs/REPOSITORY_STRUCTURE.md) before adding modules. Do not create empty placeholder folders.

## Build and test
Requirements for the current Android build:
- JDK 17.
- Android SDK 35.
- CI currently installs Gradle 8.9 directly. A Gradle wrapper is not yet checked into this repository.

From the repository root:
- Build Android debug APK: `gradle :app:assembleDebug`
- Run Android unit tests: `gradle :app:testDebugUnitTest`
- Run backend tests: `npm --prefix backend/api test`
- Start local backend API: `npm --prefix backend/api start`

The Android CI workflow builds the debug APK and runs unit tests. The API CI workflow runs backend tests and checks API/browser routes. A debug APK is not a production-signed release. Keep release signing keys outside the repository and CI logs.

## Market and platform limits
Jamaica is the initial device/carrier validation market. Digicel Jamaica and FLOW Jamaica are compatibility test references, not evidence of a commercial LINKA carrier agreement. Number provisioning and international calling depend on real provider inventory, contracts, rates and country-specific rules.

SIM/eSIM features require compatible devices, operator provisioning and applicable approval. Automatic route switching must respect user settings and must never create an unapproved paid call.

LINKA is not an emergency-calling service. No offline range, island-wide coverage, international number availability, carrier integration or production security certification may be claimed without the corresponding evidence.

## License
See [LICENSE](LICENSE).
