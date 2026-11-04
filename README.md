# LINKA — Jamaica-first Android Calling

LINKA is an Android-first communication project for Jamaica. Its primary goal is SIM-free offline communication, with nearby direct calling and a longer-distance relay network as separate capabilities.

## Product modes

- **Nearby offline calling:** two compatible LINKA phones discover, pair and communicate directly over local radios. The intended path must work without a SIM, airtime, mobile data, Wi-Fi internet or a cloud service.
- **Offline mesh calling:** LINKA phones or dedicated relay devices forward end-to-end encrypted traffic across multiple nearby radio links. This is the long-distance offline goal; the multi-hop routing and relay-safe media security are not yet complete.
- **Internet LINKA calls:** optional app-to-app calling over the internet. This is a separate mode and must never be selected as a hidden fallback for offline calls.
- **Optional cellular calls:** use the Android/system dialer and the user's Digicel Jamaica or FLOW Jamaica service. Carrier charges and operator provisioning still apply.

## Current implementation status

The Android source currently contains:

- A persisted local device ID and a P-256 signing key generated and retained in Android Keystore.
- Signed, expiring QR identity exchange, a trusted-device store and device revocation controls.
- Google Nearby Connections advertising, discovery, explicit connection requests and two-sided authentication-code confirmation.
- A multi-peer Nearby Connections cluster foundation. A phone can keep more than one nearby peer link and select one peer for a direct call.
- Call-control state handling for invites, ringing, accept, reject, timeout and hang-up.
- A narrowband full-duplex audio implementation using Android AudioRecord/AudioTrack, 8 kHz mono PCM, G.711 mu-law 20 ms frames, and a foreground microphone service.

**Source code is not proof of working calls.** Direct audio still needs repeatable two-phone testing with mobile data and Wi-Fi internet disabled, across supported Android models and versions. The current nearby links are not yet a routable long-distance mesh.

## What is not complete

- App-layer end-to-end media encryption and authenticated call-session key agreement.
- A signed, authenticated relay protocol that forwards ciphertext without exposing call audio to intermediate phones.
- Multi-hop route discovery, route selection, loop prevention, hop limits, duplicate suppression, congestion handling and route repair.
- Live voice performance and reliability testing across two or more relay hops.
- A validated plan and physical deployment for fixed, powered relay nodes where ordinary phones cannot form a continuous chain.
- Complete device compatibility, background-operation, battery, release-signing and field testing.

## Important operating constraints

- Direct radio range is limited and varies with handset, radio mode, obstruction, interference and environment. No fixed distance or island-wide coverage is promised.
- Long-distance offline voice requires a continuous chain of powered, participating relays. Every relay must have a live radio link to the next relay. Voice cannot cross a disconnected gap.
- Multi-device cluster connections have different bandwidth and latency characteristics from one-to-one connections. Real-device voice testing is required before choosing a mesh topology for calls.
- A username is not a radio address. Offline lookup can only resolve a locally cached identity or a device that is currently reachable through an established offline route.
- Google Nearby Connections depends on compatible Google Play services. Devices without it need a separately implemented transport adapter.
- Mesh forwarding must be opt-in, battery-aware and end-to-end encrypted. Relays must never possess call-media decryption keys.
- LINKA must never silently fall back to a chargeable cellular call.
- LINKA is not an emergency-calling service.

## Browser version

A responsive LINKA web companion is available in `web/`. It provides browser views for the overview, nearby network, identity, calling and offline mesh roadmap. It clearly distinguishes real Android capabilities from features that are not yet implemented.

The browser companion is a separate interface: it cannot use Android Nearby Connections or Android Keystore, and it does not currently scan nearby devices or place offline calls. SIM-free radio calling and future multi-hop mesh remain Android/device-network capabilities.

The GitHub Pages workflow publishes the contents of `web/` after changes are merged to `main`. To activate hosting, open **Repository Settings → Pages** and set the build/deployment source to **GitHub Actions**. Once the first deployment succeeds, GitHub will display the public webpage URL in that same Pages settings area and in the deployment workflow.

## Repository layout

- **app/**: Android application, UI, identity, nearby connectivity, call control and audio engine.
- **app/src/test/**: automated unit tests.
- **docs/**: architecture, security, Jamaica compatibility and offline-network delivery plans.
- **.github/workflows/**: Android CI.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35 installed, or run:

    gradle :app:assembleDebug
    gradle :app:testDebugUnitTest

The Android CI workflow runs on every push to main, every pull request targeting main, and manual dispatch. It builds the debug APK and runs unit tests. Only after both succeed does it upload the APK together with build-info.txt, which records the exact repository commit and workflow run. The artifact is retained by GitHub Actions for 30 days. Download it from the successful run's **Artifacts** section.

The CI APK is a debug build for testing, not a Play Store release. A phone that already has LINKA installed does not update from a Git commit or CI artifact. Updating installed copies requires a release-signed APK/AAB, an increasing Android versionCode, and a distribution channel such as Google Play. Release signing keys and Play credentials must remain outside the repository.

## Repository and backend synchronization

GitHub main is the source of truth. The Render linka-api service is connected to this repository's main branch with automatic deployment enabled, so commits to main trigger a backend deployment. Android changes are built by GitHub Actions; they are not deployed as Android updates by Render. The offline nearby and mesh paths must not depend on that backend.

## Jamaica scope

Initial carrier-compatibility testing targets Digicel Jamaica and FLOW Jamaica. These carriers are relevant only to optional cellular calling and device/network compatibility; SIM-free offline communication is independent of both operators.

Offline field testing should include urban, town and rural environments across Jamaica. Test locations are not coverage guarantees; actual relay availability must be measured on the ground.

## License

See LICENSE.
