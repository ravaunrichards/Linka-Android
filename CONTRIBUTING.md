# Contributing to LINKA

This repository is the source of truth for the Android application, browser application, backend services, tests, architecture, product requirements and release documentation.

## Project boundaries
- Work only in this LINKA repository.
- Preserve existing Android nearby voice, identity, security and mesh-routing code.
- Do not create a duplicate LINKA repository or mix unrelated projects into this one.
- Do not add empty folders or placeholder modules. Add a module when its first real source, test or configuration file is ready.
- Keep implemented behavior separate from planned features in code, documentation and UI.
- Do not claim carrier calling, number provisioning, SMS/MMS, credit billing, video calling, SIM/eSIM service or emergency calling is operational until the actual integration is implemented and verified.

## Before changing code
1. Read README.md and docs/INDEX.md.
2. Read docs/PROJECT_STATUS.md and docs/INTEGRATION_MATRIX.md.
3. Inspect the current implementation and tests before replacing or duplicating anything.
4. Check open GitHub issues, especially issue #2 and its physical-device/release gates.
5. Identify provider, platform, security and regulatory dependencies before implementation.

## Development environment
- Android: JDK 17 and Android SDK 35.
- Current Android CI installs Gradle 8.9 directly. The repository does not yet check in a Gradle wrapper.
- Backend API: Node.js 22 or newer.
- backend/api currently declares no third-party runtime dependency.

## Verification commands
- Android debug build: gradle :app:assembleDebug
- Android unit tests: gradle :app:testDebugUnitTest
- Backend API tests: npm --prefix backend/api test
- Backend API start: npm --prefix backend/api start

A successful build or unit suite does not prove radio performance, intelligible audio on real phones, carrier routing, provider billing or production readiness.

## Pull request expectations
Every pull request should:
- Explain the requirement and user-visible outcome.
- List files, services and data structures changed.
- State what was implemented and what remains provider-dependent.
- Include tests run and their actual outcomes.
- Update relevant documentation and integration status.
- Include migration and rollback notes for data/configuration changes.
- Avoid committing credentials, signing keys, private keys, tokens, customer data or call content.
- Avoid unrelated changes that make review harder.

## Secrets and external services
Use secret-management facilities only when a real integration is introduced. Never place live credentials, production signing keys, payment secrets, carrier credentials or private identity material in Git. Provide redacted example configuration when needed.

External provider integrations need a clear adapter boundary, provider-specific tests and documented unsupported/error states. A free route must not silently become a billable route.

## Status language
Use explicit labels such as SOURCE IMPLEMENTED, AUTOMATED TESTED, DEVICE TEST REQUIRED, PROVIDER BLOCKED, NOT IMPLEMENTED and VERIFIED IN PRODUCTION. A screen or button is not proof that a feature works.
