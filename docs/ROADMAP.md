# LINKA implementation roadmap

This roadmap describes the full product target. Phase completion must be based on verified repository evidence and acceptance criteria, not on a plan or UI mock-up.

## Phase 0 — GitHub repository foundation
- Recover and document existing source and verified progress.
- Establish authoritative product requirements and architecture decisions.
- Map current repository and target organization.
- Add repository hygiene, contribution/security guidance, issue/PR templates and dependency automation.
- Record integrations, blockers, CI history and a recovery checkpoint.
- Keep one independent repository and preserve existing source.

Acceptance: documentation links resolve; repository tree matches the structure guide; implementation claims distinguish source, automated tests, device tests and provider integrations; existing CI workflows remain present and correctly scoped.

## Phase 1 — Existing source validation and Android release gates
- Preserve current Kotlin/Jetpack Compose Android app.
- Re-run Android build and unit tests after relevant source changes.
- Complete two-physical-phone direct nearby calling with no SIM, mobile data or Wi-Fi internet.
- Complete four-or-more-phone multi-hop relay voice tests with explicit trust and relay consent.
- Measure call setup, latency, jitter, packet loss, range, audio quality, battery and thermal behavior.
- Test background/foreground, screen-off, battery saver, Android microphone foreground-service restrictions, permission loss and audio routing.
- Commission independent cryptographic/protocol security review.
- Decide and test a non-GMS transport adapter if non-GMS devices are in scope.
- Complete Android Telecom/default-dialer scope and release signing plan.

Acceptance: issue #2 release gates are closed with retained evidence; no range, security or emergency claim exceeds that evidence.

## Phase 2 — Account and durable backend foundation
- Define account, device, session and recovery lifecycle.
- Select and document authentication architecture.
- Add durable PostgreSQL storage and migrations.
- Define API authentication, authorization, rate limits, audit records and retention.
- Separate ephemeral signaling from durable account data.
- Add integration and migration tests.

Acceptance: multi-device account lifecycle works against durable storage; access controls are tested; backup and recovery are documented.

## Phase 3 — International number inventory and provisioning
- Evaluate providers able to support Jamaica, the US, Canada, the UK and additional destinations.
- Confirm number inventory, number types, local-presence restrictions and regulatory requirements.
- Build provider adapters and a normalized number catalog.
- Implement search, availability, acquisition, rental, renewal, cancellation and ownership verification.
- Add inbound routing and authorized outbound caller ID.
- Add provider reconciliation and number lifecycle notifications.

Acceptance: users see real provider-confirmed numbers and can complete supported lifecycle actions. No simulated number inventory.

## Phase 4 — Internet calling and connection orchestration
- Integrate secure Android and browser signaling clients.
- Implement actual WebRTC media transport.
- Add TURN where required by tested network conditions.
- Implement call waiting, hold, mute, speaker, Bluetooth and supported conference/forwarding features.
- Add presence, missed-call notifications, reconnect and failure reporting.
- Add route policy distinguishing internal, internet, nearby and carrier paths.
- Require user authorization before any paid carrier fallback.

Acceptance: real LINKA-to-LINKA audio calls work on tested Android and supported browsers; billing route and media route are explicit.

## Phase 5 — Free calling, credits, rates and payments
- Define funded free-call allowances and eligibility.
- Build destination-based rate catalog with effective dating/versioning.
- Implement separate number, voice and message charges.
- Implement optional wallet and immutable ledger.
- Add credit packages, custom purchase where supported, promotional/referral credits, receipts and refunds.
- Integrate payment provider(s) and signed idempotent webhooks.
- Implement free-first, user-selected route authorization and spending controls.
- Reconcile provider charges and payment settlements.

Acceptance: internal LINKA calls/messages never deduct credits; paid external service cannot start without consent; failed calls are not charged; wallet balances reconcile.

## Phase 6 — Internal and external messaging
- Complete internal chat, group conversations, media and voice notes.
- Integrate external SMS/MMS provider(s).
- Implement number capability checks, destination rates and consent before paid messages.
- Add delivery reports, queue/retry rules, spam/abuse handling and cross-device history.

Acceptance: real end-to-end delivery is verified against provider receipts; unsupported destinations are clearly reported.

## Phase 7 — Native cellular, SIM and eSIM integration
- Confirm relevant Android APIs and role requirements.
- Confirm carrier/eSIM operator support and provisioning arrangements.
- Define preferred connection settings and route-switch rules.
- Implement supported SIM/cellular calling and number identity integration.
- Prevent unapproved billable fallback.
- Test dual-SIM, roaming, permission, network-loss and device-specific behavior.

Acceptance: supported devices and providers pass documented tests; limitations are shown per device/provider.

## Phase 8 — Complete website and product experience
- Replace the static companion with a full account-based web client.
- Add number management, calling, messaging, rate finder, wallet and account settings.
- Implement browser-compatible media and notifications.
- Keep Android-only radio and SIM features separate.
- Add responsive accessible layouts and localization.

Acceptance: every visible control has a real tested action or is clearly unavailable; no fake devices, calls, balances or number inventory.

## Phase 9 — Production readiness and market release
- Complete privacy, terms, security and regulatory reviews.
- Validate Jamaica operations and international launch eligibility.
- Complete abuse, fraud, spam, support and refund operations.
- Establish monitoring, backups, disaster recovery and provider incident handling.
- Complete load, compatibility, accessibility and end-to-end testing.
- Protect release signing keys outside GitHub.
- Publish signed Android releases and production web/API deployment only after authorization and release gates pass.

Acceptance: actual provider and service evidence supports every product claim and market-availability statement.

## Execution order

Within each phase, finish independent low-risk documentation, configuration and test work before dependent cross-service implementation. Do not delay an available easy task for an unrelated harder task. Record exact provider, device and regulatory dependencies while independent repository work continues.
