# LINKA offline network roadmap

## Goal

Deliver two SIM-free communication experiences in Jamaica:

1. **Nearby direct calling:** a caller's phone connects directly to the recipient's phone over local radios.
2. **Long-distance offline calling:** phones and/or installed relay devices form a multi-hop network that forwards encrypted live voice packets between distant users.

Both must operate without cellular service, airtime, mobile data, Wi-Fi internet, or a cloud relay. Internet calling and carrier calling remain separately labelled optional modes.

## What "long distance" requires

The phone app cannot create radio coverage where no radio link exists. A long-distance offline call needs a connected path:

    Caller -> Relay A -> Relay B -> ... -> Relay N -> Recipient

Every hop must be powered and connected while the call is active. If a road, valley, building or rural gap has no reachable relay, live voice cannot cross it. The network may use participating phones where density is sufficient and fixed, powered relay nodes where it is not.

Range must be measured in the real environment. No fixed kilometre value or Jamaica-wide availability should be shown unless field measurements support it.

## Nearby path

- Discover only currently reachable LINKA peers.
- Show the real radio state, discovered devices, connection attempts and authenticated neighbours.
- Require both phones to compare the same connection-authentication code.
- Bind the radio connection to the verified LINKA identity.
- Allow the user to select a connected peer for direct calling.
- Start microphone capture only after an explicit call action and required permission.
- Show call setup, active media, link quality, failure and end state from the actual audio session.

The source already contains direct discovery, identity/QR, call-control and narrowband audio components. A successful Android build is not enough: two physical phones must prove two-way audio with mobile data off and no Wi-Fi internet.

## Multi-peer radio foundation

LINKA now uses the Nearby Connections cluster strategy rather than the one-to-one point-to-point strategy, and tracks multiple connected neighbours. This keeps multiple local links available as a foundation for routing.

This changes the topology; it does not automatically route traffic between peers. Cluster mode also has lower throughput than point-to-point mode, so voice must be benchmarked on physical devices. If quality is not sufficient, evaluate a dedicated direct-media link plus separate relay/control links or dedicated relay hardware. Do not silently lower call quality or state that the mesh is working without test results.

## Mesh routing work still required

1. **Neighbour and identity trust:** associate every radio endpoint with a verified device identity and negotiated capabilities.
2. **Route discovery:** discover reachable destination identities through authenticated neighbours; expire stale routes and repair routes when links disappear.
3. **Packet envelope:** version, source, destination, packet ID, sequence, hop limit, expiry and encrypted payload. Enforce strict maximum sizes.
4. **Forwarding:** each relay chooses the next hop, decrements hop limit, records packet IDs to suppress loops/duplicates, applies rate limits and forwards ciphertext only.
5. **Call security:** establish caller-to-recipient session keys before media starts. Relays must not be able to decrypt, forge or modify media undetected.
6. **Voice transport:** forward time-sensitive frames with bounded queues and back-pressure. Do not use delayed store-and-forward for live audio.
7. **Adaptive behavior:** measure link quality, route latency, packet loss, congestion, battery and thermal state. Prefer fewer stable hops and repair routes without exposing call secrets.
8. **User controls:** make relay participation opt-in, show that the phone is forwarding traffic, show battery impact and provide an immediate stop control.

## Radio and infrastructure strategy

- Use Nearby Connections first for supported Android phones, because it abstracts Bluetooth/BLE/Wi-Fi and supports fully offline nearby data exchange.
- Evaluate Android Wi-Fi Direct or Wi-Fi Aware as separate adapters only after checking hardware support, concurrency, permissions and device-specific behavior.
- Do not design voice transport around Bluetooth Mesh or low-rate telemetry radios without proving the sustained audio throughput and latency.
- Where phone-to-phone density is not enough, evaluate dedicated powered relay devices with a suitable higher-throughput local-radio link, weatherproofing, backhaul-free operation and backup power. A relay node must not require internet to forward local encrypted packets.

## Required verification

### Nearby direct calls

- Two supported Android phones, including low-cost devices commonly used in Jamaica.
- SIM removed or disabled; mobile data off; Wi-Fi internet disconnected.
- Enable only the local radios needed by the selected transport.
- Discovery in both directions, pairing code agreement, call invite, incoming ring, accept, reject, timeout and end.
- Two-way audio, echo, clipping, latency, packet loss and reconnect after temporary range loss.
- Test app foreground, screen off, backgrounding, permission denial and battery saver behavior.

### Long-distance mesh

- Three devices first: caller, one relay, recipient.
- Then four to six devices over a chain and a branching cluster.
- Verify the route, hop count and every forwarding node in diagnostics.
- Disconnect a relay during a call and measure route repair or clear call failure.
- Attempt replay, duplicate injection, malformed packet, route spoofing and malicious-relay tests.
- Measure call setup time, one-way delay, jitter, packet loss, audio quality, battery and heat at each hop.
- Repeat tests in realistic Jamaican urban, suburban, rural and obstructed environments. Record exact device models, radio modes, terrain and results.

## Delivery gates

- **Gate A — Nearby direct:** reproducible two-phone SIM-free offline calls.
- **Gate B — Secure direct:** identity-bound call setup and caller-to-recipient encrypted media.
- **Gate C — Multi-peer network:** stable authenticated neighbour links and tested routing/control messages.
- **Gate D — Multi-hop voice:** live audio across measured relay chains with acceptable quality and safe failure behavior.
- **Gate E — Jamaica deployment:** measured relay placements, power plan, device support matrix and coverage maps backed by field tests.

Do not mark a gate complete based on UI screens, a code commit or a CI build alone.

## Current implementation update

The direct-call Android source now includes QR-trusted identity binding, fresh authenticated ECDH session setup, transcript confirmation, HKDF-derived directional AES-GCM control/media keys, encrypted voice frames, replay/tamper checks and key destruction. Automated unit tests cover the handshake and encrypted-frame behavior. These are implementation/test results, not physical-device proof.

Multi-peer cluster connectivity remains a radio-link foundation only. Authenticated route discovery, opt-in relay capabilities, end-to-end caller-to-recipient handshakes across a routed path, ciphertext-only forwarding and physical three-plus-phone relay tests remain outstanding. Do not enable multi-hop voice or claim Jamaica-wide coverage until those gates pass.
