# LINKA repository structure and organization

## 1. Repository identity
- Repository: ravaunrichards/Linka-Android
- Default branch: main
- Product: LINKA
- Scope: one independent product across Android, browser, backend, database migrations, CI and documentation.
- Do not create a duplicate LINKA repository.
- Latest verified main at this inventory: `124cfdb60b5d24ad77576bc72bb331fb48e5f316`.

## 2. Current verified source tree

The recursive GitHub tree contains 141 entries and is not truncated. The repository is an existing product codebase, not an empty starter.

- `.github/` — CODEOWNERS, issue and pull-request templates, Dependabot configuration and workflows.
- `.github/workflows/android-ci.yml` — Android debug build, unit tests, source revision metadata and APK artifact.
- `.github/workflows/linka-api-ci.yml` — browser module syntax, API unit tests and API startup/asset checks.
- `.github/workflows/database-ci.yml` — PostgreSQL 17 migrations, schema invariants, persistence integration and readiness checks.
- `app/` — Kotlin/Jetpack Compose Android application.
- `app/src/main/java/com/linka/android/` — application shell, phone hub and account panel.
- `app/src/main/java/com/linka/android/account/` — account API client and Android Keystore-backed encrypted account-token storage.
- `app/src/main/java/com/linka/android/core/audio/` — audio codecs, direct/routed voice sessions and microphone foreground service.
- `app/src/main/java/com/linka/android/core/call/` — call control, state machine and nearby call controller.
- `app/src/main/java/com/linka/android/core/identity/` — QR identity, Android Keystore device identity and trusted-device store.
- `app/src/main/java/com/linka/android/core/mesh/` — packet routing, relay consent and routed payload handling.
- `app/src/main/java/com/linka/android/core/nearby/` — nearby radio connections.
- `app/src/main/java/com/linka/android/core/security/` — peer session security and encrypted call primitives.
- `app/src/test/` — Android unit tests for audio, call control/state, mesh routing and security.
- `backend/api/` — Node.js API, domain modules, persistence adapter and automated tests.
- `backend/api/db/migrations/` — ordered PostgreSQL schema migrations.
- `backend/api/db/checks/` — schema, wallet and integrity checks used by database CI.
- `backend/api/src/domain/` — E.164 normalization, free-first route policy, wallet ledger and provider-fed rate catalog.
- `backend/api/src/cors.mjs` — strict exact-origin CORS allowlist for separately hosted browser clients.
- `backend/api/test/` — API, domain, PostgreSQL integration, browser-crypto, browser-session policy and CORS policy tests.
- `web/` — responsive browser companion, account UI, WebCrypto device identity and browser API client.
- `docs/` — requirements, architecture, decisions, repository map, account authentication, database schema, integration matrix, roadmap, security, testing and recovery checkpoint.
- Root files — README, license, contributing guide, security policy, editor settings, git attributes and ignore rules.

The tree count is a Git object inventory, not a claim that all planned product modules exist. Do not create empty placeholders for future services.

## 3. Repository organization rules
- Keep all LINKA source, documentation, tests, migrations and deployment configuration in this repository.
- Keep Android-specific code in `app/`; use clear packages by real capability.
- Keep backend APIs, domain logic, persistence and provider adapters under `backend/api/` until a genuine bounded service requires separation.
- Keep browser source under `web/`.
- Keep architecture, product requirements, integration evidence, release gates and operating instructions under `docs/`.
- Keep GitHub automation, issue/PR templates and dependency automation under `.github/`.
- Keep tests close to the module they verify.
- Keep migrations beside the backend/data service that owns them, with ordering and rollback notes.
- Keep safe example configuration in Git; keep real secrets and release signing keys outside Git.
- Use explicit interfaces/adapters for carriers, number providers, messaging and payment providers.
- Avoid duplicating the rate catalog, wallet ledger or number inventory across clients.
- Do not create empty placeholder directories. The first real source, test or configuration file should create a module boundary.

## 4. Target organization as capabilities are implemented

This is a target map, not a claim that these directories currently exist:
- `app/` — Android application and native integrations.
- `backend/api/` — public API and routing entry point.
- `backend/telephony/` — carrier, SIP/PSTN and number lifecycle adapters when real provider work begins.
- `backend/messaging/` — SMS/MMS and internal messaging services when implemented.
- `backend/billing/` — wallet, immutable ledger, pricing and payment integrations when separated from API domain code.
- `backend/notifications/` — push and delivery notifications when implemented.
- `backend/shared/` — shared backend contracts only when genuinely reused.
- `web/` — full web client and browser calling.
- `docs/` — product, architecture, integration, security, testing and release records.
- `infra/` — infrastructure-as-code and deployment configuration when adopted.
- `.github/` — CI, dependency updates, review templates and release workflows.

Create target modules incrementally when real source, configuration, tests or migrations are ready. Do not add empty folders or fake service shells.

## 5. Required ownership boundaries
- Android client: presentation, device permissions, local identity, audio routing and client-side connection management.
- Backend: authoritative identity/session state, number inventory, provider adapters, rate decisions, usage records, wallet ledger, payment reconciliation and notifications.
- Web client: browser-compatible experiences; it must not claim native Android radio or SIM access.
- Provider adapters: isolate vendor-specific API behavior and normalize errors, pricing, number types and delivery states.
- Billing: one server-authoritative ledger; clients display balances but do not write authoritative balances.
- Documentation: state whether a capability is planned, source implemented, automated tested, device tested, provider enabled or production verified.

## 6. Repository hygiene and build tooling

Verified root governance files now include:
- `.gitignore`, `.editorconfig`, `.gitattributes`.
- `CONTRIBUTING.md` and `SECURITY.md`.
- `.github/CODEOWNERS`.
- `.github/ISSUE_TEMPLATE/` and `.github/PULL_REQUEST_TEMPLATE.md`.
- `.github/dependabot.yml`.

A Gradle wrapper is still not checked into the repository. Android CI is configured to install Gradle 8.14.4 directly; the current Android CI run is verifying this version. Add a wrapper only after generating and verifying the official wrapper scripts and JAR; do not fabricate a wrapper binary.

## 7. Rules for structural changes

Before moving or renaming source:
1. Inspect imports, Gradle project settings, package references, CI paths and deployment paths.
2. Make one coherent change with all references updated.
3. Run module-specific tests and builds.
4. Verify GitHub Actions and paths after the change.
5. Document the resulting tree.

Do not reorganize working Android source merely for visual neatness. Preserve verified implementation and use clear package ownership for new capabilities.
