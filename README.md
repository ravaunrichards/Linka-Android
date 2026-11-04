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

The Android CI workflow runs on every push to `main`, every pull request targeting `main`, and manual dispatch. It builds the debug APK and runs unit tests. Only after both succeed does it upload the APK together with `build-info.txt`, which records the exact repository commit and workflow run. The artifact is retained by GitHub Actions for 30 days. Download it from the successful run's **Artifacts** section.

The CI APK is a debug build for testing, not a Play Store release. A phone that already has LINKA installed does not update from a Git commit or CI artifact. Updating installed copies requires a release-signed APK/AAB, an increasing Android `versionCode`, and a distribution channel such as Google Play. Release signing keys and Play credentials must remain outside the repository.

## Repository and backend synchronization

GitHub `main` is the source of truth. The Render `linka-api` service is connected to this repository's `main` branch with automatic deployment enabled, so commits to `main` trigger a backend deployment. Android changes are built by GitHub Actions; they are not deployed as Android updates by Render.

## Jamaica scope

Initial carrier-compatibility testing targets Digicel Jamaica and FLOW Jamaica. Carrier calls are separate from SIM-free nearby communication and must be tested against real devices, SIM plans, VoLTE provisioning and operator requirements.

## License

See [LICENSE](LICENSE).
