# LINKA Jamaica and international compatibility plan

## Market and number scope

LINKA's initial validation market is Jamaica, while the product number scope includes both Jamaican and international destinations.

Initial number destinations:
- Jamaica: +1 876 and +1 658.
- United States: supported +1 numbers where provider inventory and eligibility permit.
- Canada: supported +1 numbers where provider inventory and eligibility permit.
- United Kingdom: supported +44 numbers where provider inventory and eligibility permit.
- Other international destinations as provider coverage and regulatory approvals expand.

The +1 calling code is shared by Jamaica, the United States, Canada and other NANP locations. Determine the destination from a validated full number and numbering metadata; do not identify a country from +1 alone.

## Jamaica carrier and device validation

Digicel Jamaica and FLOW Jamaica are relevant operators for optional SIM/cellular compatibility testing. Mentioning an operator does not mean LINKA has a commercial integration, wholesale agreement, approved number inventory or permission to originate calls through that operator.

Test only with consenting users and legitimately provisioned SIMs:
- Outgoing and incoming cellular calls.
- VoLTE and supported network modes where provisioned.
- Dual-SIM selection and default-call settings.
- SIM removed/disabled.
- Mobile data disabled.
- Wi-Fi internet disconnected.
- Local radios used by Nearby Connections enabled as required.
- Direct nearby discovery, pairing, call setup and two-way audio.
- Mesh relay consent, forwarding, route loss and recovery.
- Android permissions, foreground microphone service, screen-off/background and battery optimization.
- No silent fallback to a carrier-billed call.

## Device test matrix

For each device, record manufacturer/model, Android version and security patch, Google Play Services version, cellular/SIM configuration, Bluetooth and Wi-Fi radio behavior, battery/background settings, permissions, direct/routed outcome, call setup time, latency, jitter, packet loss, audio quality, battery and thermal observations, test environment and date.

## Suggested Jamaican field locations

Kingston/St Andrew, Spanish Town, Portmore, Montego Bay, Ocho Rios, Mandeville, May Pen, Port Antonio and Negril are candidate consenting field-test locations. They are not coverage claims or evidence of island-wide availability.

## International validation

For each international market, separately establish number inventory/type, local-presence and identity requirements, inbound/outbound calling, SMS/MMS and sender registration, destination rates/tax/currency, caller ID and porting restrictions, emergency limitations, data protection and telecommunications obligations, provider and regulatory approvals.

## Important limitations

Offline nearby calling is independent of Digicel and FLOW. Nearby mesh calling requires a continuous chain of powered, reachable and consenting devices. It cannot bridge a radio gap by itself.

Carrier integration, number provisioning, eSIM service, VoLTE and international PSTN access require compatible devices, providers, contracts and applicable approval. No operator relationship, free external call coverage or international number availability should be claimed without evidence.
