# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Verified source baseline before this checkpoint update: a230d168de391b3d209865eb8c51bbbc3c5aa03c.
- Repository ID: 1444736198.
- Repository visibility: public.
- Last inspected recursive tree: 115 entries, not truncated.
- GitHub permission snapshot during recovery: admin, maintain and push were true.
- Recovery confidence: CONFIRMED for branch, commit ancestry, repository files, commit changes, issue records and Actions results. A local working tree is not exposed by the GitHub read APIs.

## Recovery labels

- RECOVERED — present in prior task context or durable GitHub records.
- VERIFIED — re-read from GitHub during the current recovery.
- HISTORICAL — a dated GitHub Actions, issue or repository event.
- INFERRED — reasonable reconstruction not directly confirmed.
- UNKNOWN — not established by available evidence.

## Recovery from the prior checkpoint

The previous saved checkpoint was at repository commit 36ef4745a13e48c37b89bead9c6497e66993520c and recorded the repository-organization phase as complete. Since that checkpoint, additional commits appeared on main and were recovered rather than overwritten:

- 71deee602161d6a1ac239d92f8868a423eb347c3 — added the tested immutable wallet credit-ledger domain, its tests and design documentation.
- 7cf935f3fc5137e8c7756f4f703997dbb6e06646 — added a combined-credit-balance overflow guard and extended the wallet-ledger documentation.
- f647d7267432c89bc355e4008c12343093393100 — corrected the wallet overflow test: a balance exactly at Number.MAX_SAFE_INTEGER is valid; only a genuinely overflowing combined snapshot should fail.
- e30a9087fec46c43038f0463877b05961120178d — fixed literal escaped-newline separators that had merged two integration-matrix rows.
- f16f2a36d07ab19c52fdbb937335cb5604d2ce76 — added a provider-fed rate catalog and credit-estimate domain with unit tests.
- 53772274ae6b4389ec60fcf00c4174c8e450c293 — corrected rate-prefix validation to permit a bare calling-code prefix such as +1.
- a230d168de391b3d209865eb8c51bbbc3c5aa03c — made message quotes explicitly reject voice tariffs.

No source from those commits was discarded. All are ancestors of the verified main branch baseline.

## Previous project work preserved

Closed and merged pull requests:
- PR #1 — LINKA Android improvements and responsive web companion.
- PR #3 — secure direct and multi-hop offline voice calling.
- PR #4 — premium phone hub and Android system integrations.

The existing implementation still contains:
- Kotlin/Jetpack Compose Android application.
- Nearby Connections discovery, advertising and pairing.
- Android Keystore device identity and signed QR identity.
- Authenticated peer/session security.
- Direct encrypted voice transport.
- Multi-peer/multi-hop offline route source with local relay consent.
- Android phone hub with keypad and system-app handoffs for dialer, SMS, contacts and recent calls.
- Unit tests for call control/state, audio framing, peer security and mesh routing.
- Node.js signed-device challenge and ephemeral call-signaling API.
- Static browser companion.
- Android CI and API CI workflows.
- Architecture, compatibility, security, mesh, testing and Render service documentation.

Open issue #2 remains the physical-device, security-review and release-gate tracker. It still includes two-phone direct audio proof, four-or-more-device relay proof, Android lifecycle/audio checks, independent cryptographic review, non-GMS scope, Android Telecom/default-dialer and release signing.

## New backend domain capabilities recovered and verified

The backend now includes:
- `backend/api/src/domain/e164.mjs` — strict international-format normalization of explicitly supplied phone numbers. It does not identify country from +1.
- `backend/api/src/domain/route-options.mjs` — free-first option construction, exact-quote paid consent and bounded paid auto-fallback policy.
- `backend/api/src/domain/credit-ledger.mjs` — immutable in-memory wallet reducer with separate purchased/promotional/sponsored buckets, idempotency checks, usage deductions, capped refunds and safe-integer balance limits.
- `backend/api/src/domain/rate-catalog.mjs` — validation of provider-supplied tariff records, active-rate selection by provider/country/number-type/longest prefix and credit estimates for voice/SMS/MMS.

These are provider-independent, tested domain foundations. They do not provide a live provider tariff feed, actual number inventory, persistence, concurrent database transactions, payment authorization/webhooks, user wallet APIs, Android/web UI or operational carrier routes. No LINKA customer prices are set by these modules.

## Current CI and failure recovery

- API CI run 42611442996 for commit a230d168de391b3d209865eb8c51bbbc3c5aa03c completed successfully. Its test log reports 49 tests passed, 0 failed; API start/readiness and browser route checks also passed.
- Android CI run 42611442986 was queued/pending when this checkpoint was prepared; do not report it as passed until GitHub confirms its conclusion.
- Historical API CI run 42610320645 failed because an overflow test expected a valid MAX_SAFE_INTEGER balance to fail. The implementation was retained; the test was corrected in commit f647d7267432c89bc355e4008c12343093393100, whose API CI passed.
- Historical API CI run 42611182288 failed because the rate-prefix validator rejected the bare +1 calling-code prefix. The validator was corrected in commit 53772274ae6b4389ec60fcf00c4174c8e450c293, whose API CI passed.
- These failures and fixes are retained in history; they are not hidden or counted as successful runs.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages; its upload/deploy steps were skipped. The Pages workflow was later removed and the repository documents a single Render origin.
- No external Render dashboard, carrier account, payment account, tariff feed or device lab was accessed during this GitHub-only pass.

## Full LINKA product progress tracker

This is the weighted tracker for the full LINKA product and its repository implementation. The weights total 100%. It is separate from the earlier repository-organization milestone, which remains complete.

| Milestone | Weight | Verified credit | Remaining |
|---|---:|---:|---:|
| Repository organization, requirements and engineering documentation | 10% | 10% | 0% |
| Existing Android offline direct/mesh source and automated verification | 12% | 8% | 4% |
| Complete Android phone experience and native system integration | 9% | 2% | 7% |
| Backend device identity and signaling foundation | 8% | 4% | 4% |
| Free-first route policy and international number normalization | 6% | 3% | 3% |
| Wallet credit-ledger domain | 8% | 4% | 4% |
| Provider-fed rate catalog and usage estimation | 6% | 3% | 3% |
| Provider-backed Jamaican/international number inventory and SIP/PSTN | 10% | 0% | 10% |
| End-to-end internet audio/video media and clients | 7% | 0% | 7% |
| Internal chat and external SMS/MMS | 6% | 0% | 6% |
| Payment processing, refunds and settlement reconciliation | 6% | 0% | 6% |
| Full account-based website and browser calling | 5% | 1% | 4% |
| Supported SIM/eSIM and complete automatic connection switching | 3% | 0% | 3% |
| Independent security, compliance, physical field tests and production release | 4% | 0% | 4% |
| **TOTAL** | **100%** | **35%** | **65%** |

**Current full-product completion: 35% | Remaining: 65%.**

The previous checkpoint measured only repository organization at 100%; it did not establish a weighted full-product percentage. Therefore, this is the first explicit full-product weighted baseline. Partial credit is limited to source and automated-test evidence. Provider integrations, durable storage, physical-device verification and production release receive no credit until independently verified.

## Easy, medium and hard work remaining

### Easy
- Keep documentation and integration status synchronized with source.
- Maintain focused API unit tests and verify all local Markdown links.
- Complete Android CI for the latest main commit.
- Add missing API contract tests and negative-path validation where no external provider is required.

### Medium
- Connect domain policy to authenticated backend services.
- Implement a durable PostgreSQL persistence boundary and migrations for accounts, number inventory, rate versions and wallet ledger.
- Add provider adapter contracts and import/refresh interfaces without fabricating live provider data.
- Integrate Android and web clients with supported backend capabilities.

### Hard / externally dependent
- Jamaican and international number provisioning and SIP/PSTN carrier agreements.
- Actual external SMS/MMS delivery providers.
- WebRTC media path, TURN and production network integration.
- Payment processor onboarding, signed webhooks, settlement/refund handling.
- SIM/eSIM operator provisioning and supported automatic routing.
- Physical two-phone and multi-relay field tests, independent cryptographic review, telecom/regulatory reviews and release signing.

## Exact next action

After the latest Android CI conclusion is recorded, continue with the easiest independent backend task: define the durable PostgreSQL schema/migration plan for accounts, provider-backed numbers, rate versions and wallet ledger, keeping production credentials out of Git and not claiming live provider availability.

## Execution boundaries

- Work only in ravaunrichards/Linka-Android.
- Preserve all existing implementation and commits.
- Do not create a duplicate repository or add unrelated products.
- Keep code, docs, tests, migrations and deployment configuration inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials, private signing keys or payment secrets to source control.
- Do not claim physical-device tests, provider enablement, emergency calling or independent security review without evidence.
