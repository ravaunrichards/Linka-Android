# LINKA — Jamaica-first Android Calling

LINKA is an Android-first calling application for Jamaica. Its product direction combines optional carrier calls, internet-based app calls, SIM-free nearby voice calls, and carefully constrained offline mesh experiments.

## Product modes

- **Nearby offline LINKA:** direct phone-to-phone communication using available local radios. The intended call mode must work without a SIM, airtime, mobile data, or Wi-Fi internet.
- **Internet LINKA calls:** app-to-app voice calling over an internet connection. This is a separate mode and is not part of the offline path.
- **Optional cellular calls:** use the Android/system dialer and the user's Digicel Jamaica or FLOW Jamaica service. Carrier charges and operator provisioning still apply.
- **Experimental offline mesh:** opt-in forwarding between nearby devices, only after direct calling and radio topology are proven.

## Current implementation status

The Android app now has a persisted local device ID, an Android Keystore signing key, Nearby Connections discovery/advertising, a peer list, and a two-sided six-digit connection confirmation flow. These functions establish a nearby radio connection between compatible LINKA installations.

**This is not yet a completed calling app.** The encrypted audio pipeline, call invitations and call controls, QR identity exchange, internet calling, incoming-call service, and operational multi-hop mesh still need implementation and real-device verification. The UI must not be interpreted as evidence that voice calls are working.

## Important operating constraints

- Nearby discovery is local-radio based. It does not provide unlimited distance or remote username lookup.
- Both phones need compatible Android hardware, LINKA installed, required permissions, Google Play services with Nearby Connections available, and Bluetooth or Wi-Fi enabled.
- Starting in late 2026, Nearby Connections no longer automatically enables radios for third-party apps. Users must enable Bluetooth or Wi-Fi themselves before starting discovery.
- Nearby range depends on radios, device models, physical obstacles and interference.
- A username is not a radio address. Offline lookup can only resolve a cached identity or a device that is currently discoverable nearby.
- Mesh relaying must be opt-in and forward end-to-end encrypted data only.
- LINKA must never silently fall back to a chargeable cellular call.
- LINKA is not an emergency-calling service.

## Repository layout

- `app/`: Android application, UI, identity, nearby connectivity and call engine.
- `app/src/test/`: automated unit tests.
- `docs/`: architecture, security, Jamaica compatibility and test plans.
- `.github/workflows/`: Android CI.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The CI workflow runs both commands on pushes and pull requests to `main`. The latest successful workflow result is the build verification authority; source commits alone do not prove the APK builds.

## Jamaica scope

Initial carrier-compatibility testing targets Digicel Jamaica and FLOW Jamaica. Carrier calls are separate from SIM-free nearby communication and must be tested against real devices, SIM plans, VoLTE provisioning and operator requirements.

## License

See [LICENSE](LICENSE).
