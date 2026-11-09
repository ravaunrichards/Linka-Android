# LINKA architecture decisions

These decisions preserve product boundaries and prevent future work from confusing offline radio, internet communication and carrier telephone service.

## ADR-001 — One independent LINKA repository
**Decision:** Maintain LINKA Android, web, backend, tests, deployment configuration and product documentation in ravaunrichards/Linka-Android.

**Reason:** One repository is the authoritative source and avoids splitting or mixing LINKA with unrelated products.

**Consequence:** New source, tests, migrations and documentation belong here. Do not create a duplicate repository.

## ADR-002 — Separate communication transports
**Decision:** Treat nearby offline voice, internet voice/video and carrier/PSTN telephone service as separate transports with explicit capabilities.

**Reason:** They have different hardware, network, identity, quality, cost and provider dependencies.

**Consequence:** The UI and route policy must identify the actual transport. A nearby mesh call is not a PSTN call. An API signaling endpoint does not imply voice media is carried.

## ADR-003 — Never silently fall back to a billable route
**Decision:** A failed free or offline route must not silently start a paid cellular or carrier call.

**Reason:** Users control their charges and route choice.

**Consequence:** Show the alternative, rate and expected charge, then obtain explicit authorization. Preserve the current user-confirmed Android system dialer handoff.

## ADR-004 — Free internal service; funded external free service
**Decision:** Eligible LINKA-to-LINKA audio calls and messages are free and never deduct credits. External free calls may be offered only when a funded allowance, sponsor or carrier agreement covers the cost.

**Reason:** External PSTN termination and messaging have provider costs.

**Consequence:** Do not advertise unlimited free external calls without confirmed funding, coverage and fair-use controls.

## ADR-005 — Destination rates and separate charges
**Decision:** Number rental, external calling and external messaging have independent price records.

**Reason:** Owning a number is not the same service as placing a call or sending a message. Destination, number type, provider and route can change the rate.

**Consequence:** Show provider-backed prices before acquisition, renewal, calling or messaging. Store the applied rate version with each usage event.

## ADR-006 — Server-authoritative credits and immutable ledger
**Decision:** The backend owns wallet balances and every transaction is an auditable ledger event.

**Reason:** Client-controlled balances are unsafe and reconciliation requires a durable source of truth.

**Consequence:** Use atomic/idempotent ledger updates, authenticated payment webhooks, deduplication, refunds/reversals and provider settlement reconciliation. Never commit payment secrets.

## ADR-007 — Provider adapters isolate vendors
**Decision:** Number provisioning, PSTN/SIP, SMS/MMS, payments and eSIM capabilities must be isolated behind provider-specific adapters and normalized internal contracts.

**Reason:** Coverage, regulations, prices and provider terms vary and change.

**Consequence:** Do not hardcode one provider response shape throughout Android and web clients. Keep provider credentials in secret storage and test unsupported/error states.

## ADR-008 — Web and Android have different capabilities
**Decision:** The web client uses browser-supported APIs; Android-only radio, Keystore and SIM capabilities remain native.

**Reason:** A normal browser cannot access Android Nearby Connections, Android Keystore or a physical SIM as a native app can.

**Consequence:** Do not fabricate browser-connected devices or claim web access to native radios. Label platform differences.

## ADR-009 — Preserve verified offline foundation
**Decision:** Keep existing Android identity, direct voice and multi-hop mesh code unless a measured defect or reviewed design change requires modification.

**Reason:** The repository contains non-trivial implementation and automated tests that must not be discarded during product expansion.

**Consequence:** Inspect dependencies and run relevant tests before changing identity, encryption, routing or audio code.

## ADR-010 — No production/emergency claims without evidence
**Decision:** Do not state carrier coverage, eSIM provisioning, emergency calling, production security or offline range is available without provider, regulatory, independent-review or field-test evidence as appropriate.

**Reason:** A design document or green unit-test run cannot prove external or physical properties.

**Consequence:** Maintain explicit integration status, field-test records and limitations in README, issues and user-facing product.
