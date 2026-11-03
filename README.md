# LINKA — Jamaica-first Android Calling

LINKA is an Android-first calling project for Jamaica. Its long-term product combines:
- Ordinary cellular calls through a user's Digicel or FLOW SIM (optional).
- Free app-to-app internet voice calls.
- SIM-free, internet-free nearby voice calls.
- Experimental multi-hop offline mesh relaying.

**Current repository status:** foundational Android application and offline-networking domain model. This is not yet a production calling service. Direct audio transport, device discovery, mesh forwarding, and carrier interoperability require staged implementation and physical-device verification.

## Product principles
- Offline direct calls must not require a SIM, mobile data, Wi-Fi internet, airtime, or a cloud account once devices have exchanged trusted identity information.
- Offline range is limited by compatible local radios and the environment. There is no unlimited-range offline calling.
- Mesh relaying is opt-in and best-effort; Android hardware and OS restrictions may prevent arbitrary multi-hop topologies.
- No relay may decrypt end-to-end voice media.
- Cellular fallback must be clearly identified and must never be triggered without user consent.

## Repository layout
- `app/`: Android application.
- `core/`: identity, protocol, routing and audio domain foundations.
- `docs/`: architecture, security, testing and Jamaica deployment notes.
- `.github/workflows/`: Android CI.

## Build
Open this project in Android Studio (Hedgehog or newer) with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The Android Gradle Plugin downloads through Gradle when network access is available.

## Current implementation boundary
The first milestone establishes a buildable Android shell, local device identity, typed call/mesh models and routing primitives. It does **not** claim that real offline voice calls or mesh relaying are already operational. Those require transport adapters, audio pipeline integration, permission flows, cryptographic review, and multi-device testing.

## Jamaica scope
Initial carrier compatibility testing targets Digicel Jamaica and FLOW Jamaica. Carrier integration is separate from SIM-free offline calling and must be validated against specific devices, SIM plans, LTE/VoLTE provisioning and operator requirements.

## License
See [LICENSE](LICENSE).
