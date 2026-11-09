# LINKA repository structure and organization

## 1. Repository identity
- Repository: ravaunrichards/Linka-Android
- Default branch: main
- Product: LINKA
- Scope: one independent product across Android, web, backend, infrastructure configuration and documentation.
- Do not create a duplicate LINKA repository.

## 2. Current verified source tree

The inspected main-branch tree contains 85 entries and is not an empty starter:
- .github/workflows/ — Android CI and LINKA API CI.
- app/ — native Android application.
- app/src/main/java/com/linka/android/ — application UI and core Android code.
- app/src/main/java/com/linka/android/core/audio/ — codecs, direct/routed voice sessions and microphone foreground service.
- app/src/main/java/com/linka/android/core/call/ — call control and call-state logic.
- app/src/main/java/com/linka/android/core/identity/ — QR identity, local device identity and trusted-device storage.
- app/src/main/java/com/linka/android/core/mesh/ — packet routing, relay consent and routed payload handling.
- app/src/main/java/com/linka/android/core/nearby/ — nearby radio connectivity.
- app/src/main/java/com/linka/android/core/security/ — peer session security.
- app/src/test/ — unit tests for codec, call control/state, mesh routing and security.
- backend/api/ — Node.js signaling API and automated tests.
- web/ — static responsive browser companion.
- docs/ — architecture, security, compatibility, testing, mesh and service documentation.
- README.md and LICENSE — repository entry point and license.

This inventory describes what was present on the inspected main branch. It does not mean planned production modules already exist.

## 3. Repository organization rules
- Keep all LINKA source, documentation, tests and deployment configuration in this repository.
- Keep Android-specific implementation in app/.
- Keep backend services under backend/, grouped by real bounded capability when implemented.
- Keep browser source under web/.
- Keep architecture, product requirements, integration evidence, release gates and operating instructions under docs/.
- Keep GitHub automation, issue templates, PR templates and dependency automation under .github/.
- Keep tests close to the module they verify.
- Keep migrations beside the backend/data service that owns them, with ordering and rollback notes.
- Keep safe example configuration in Git; keep real secrets outside Git.
- Use explicit interfaces/adapters for carriers, number providers, messaging and payment providers.
- Avoid duplicating the rate catalog, wallet ledger or number inventory across clients.
- Do not create empty placeholder directories. The first real source, test or configuration file should create a module boundary.

## 4. Target organization as capabilities are implemented

This is a target map, not a claim that these directories currently exist:
- app/ — Android application and native integrations.
- backend/api/ — public API and routing entry point.
- backend/identity/ — account, device and session services when split out.
- backend/telephony/ — carrier, SIP/PSTN and number lifecycle adapters.
- backend/messaging/ — SMS/MMS and internal messaging services.
- backend/billing/ — wallet, immutable ledger, pricing and payment integrations.
- backend/notifications/ — push and delivery notifications.
- backend/shared/ — shared backend contracts only when genuinely reused.
- web/ — full web client and browser calling.
- docs/ — product, architecture, integration, security, testing and release records.
- infra/ — infrastructure-as-code and deployment configuration when adopted.
- .github/ — CI, dependency updates, review templates and release workflows.

Create target modules incrementally when real source, configuration, tests or migrations are ready. Do not add empty folders or fake service shells.

## 5. Required ownership boundaries
- Android client: presentation, device permissions, local identity, audio routing and client-side connection management.
- Backend: authoritative identity/session state, number inventory, provider adapters, rate decisions, usage records, wallet ledger, payment reconciliation and notifications.
- Web client: browser-compatible experiences; it must not claim native Android radio or SIM access.
- Provider adapters: isolate vendor-specific API behavior and normalize errors, pricing, number types and delivery states.
- Billing: one server-authoritative ledger; clients display balances but do not write authoritative balances.
- Documentation: state whether a capability is planned, source implemented, automated tested, device tested, provider enabled or production verified.

## 6. Repository hygiene

Root files:
- .gitignore — build output, local configuration, dependency folders and secret material.
- .editorconfig — consistent encoding and formatting.
- .gitattributes — normalized line endings.
- CONTRIBUTING.md — development and pull-request expectations.
- SECURITY.md — safe vulnerability reporting and security requirements.
- CODEOWNERS and issue/PR templates under .github/.

## 7. Current missing organization items

The baseline inspection found no root .gitignore, .editorconfig, .gitattributes or CONTRIBUTING.md, and no GitHub issue/PR templates or dependency-update configuration. The project also does not check in the Gradle wrapper. Android CI installs Gradle 8.9 directly. Add a wrapper only after generating and verifying official wrapper scripts and JAR; do not fabricate a wrapper binary.

## 8. Rules for structural changes

Before moving or renaming source:
1. Inspect imports, Gradle project settings, package references, CI paths and deployment paths.
2. Make one coherent change with all references updated.
3. Run module-specific tests and builds.
4. Verify GitHub Actions and paths after the change.
5. Document the resulting tree.

Do not reorganize working Android source merely for visual neatness. Preserve the verified implementation and use clear package ownership for new capabilities.
