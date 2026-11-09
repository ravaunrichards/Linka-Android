# LINKA project status and recovery checkpoint

## Checkpoint metadata

- Checkpoint date: 2026-11-09.
- Reporting timezone: America/Jamaica.
- Repository: ravaunrichards/Linka-Android.
- Default branch: main.
- Recovery baseline commit: a14baf36ac90dfb1f78324cccaf7e1a26180e81d.
- Repository organization commit: 85c64d8515063c6cffc15424d399953a609a082d.
- Browser status correction commit: d7ca2dce8ba787db60464204e6b85d9f7ad3bd75.
- Main branch head before this checkpoint update: d7ca2dce8ba787db60464204e6b85d9f7ad3bd75.
- Repository ID: 1444736198.
- Repository visibility: public.
- Main tree: 105 entries; recursive Git tree was not truncated.
- Connected GitHub permission snapshot during recovery: admin, maintain and push were true.
- Recovery confidence: CONFIRMED for branch, commits, tree, merged PRs, open issue, file fetches and Actions runs. GitHub read APIs do not expose a local working tree.

## Recovery labels

- RECOVERED — present in prior task context or durable GitHub records.
- VERIFIED — re-read from GitHub during the 2026-11-09 recovery.
- HISTORICAL — a dated GitHub Actions, issue or repository event.
- UNKNOWN — not established by available GitHub evidence.

## Previous work recovered

Closed and merged pull requests:
- PR #1 — LINKA Android improvements and responsive web companion.
- PR #3 — secure direct and multi-hop offline voice calling.
- PR #4 — premium phone hub and Android system integrations.

The inspected main branch already contained:
- Kotlin/Jetpack Compose Android application.
- Nearby Connections discovery, advertising and pairing.
- Android Keystore device identity and signed QR identity.
- Authenticated peer/session security.
- Direct encrypted audio transport.
- Multi-peer and multi-hop offline route implementation with local relay consent.
- Unit tests for audio framing, call state/control, security and mesh routing.
- Node.js backend API with signed device challenge and ephemeral call signaling.
- Static browser companion.
- Android CI and API CI workflows.
- Architecture, security, Jamaica compatibility, offline mesh, Render service and testing documentation.

The latest open release-tracking issue found is issue #2: [LINKA physical-device validation, security review and release gates](https://github.com/ravaunrichards/Linka-Android/issues/2). It records outstanding two-phone direct audio tests, multi-phone relay voice tests, Android lifecycle/audio checks, independent cryptographic review, non-GMS scope decision, Android Telecom/default-dialer/release signing and deployment follow-up.

## Repository changes completed in this organization pass

Commit 85c64d8515063c6cffc15424d399953a609a082d added 19 files and updated three existing documentation/entry files. It added:
- .gitignore, .editorconfig and .gitattributes.
- CONTRIBUTING.md and SECURITY.md.
- GitHub issue templates, pull request template, CODEOWNERS and Dependabot configuration.
- Documentation index.
- Full product requirements.
- Repository structure guide.
- Roadmap.
- Integration matrix.
- Architecture decisions.
- Telephony and billing specification.
- This persistent project status checkpoint.
- Corrected root README, Jamaica/international compatibility plan and offline mesh status.

Commit d7ca2dce8ba787db60464204e6b85d9f7ad3bd75 corrected stale browser companion wording:
- The web page now identifies itself as a development-status browser companion, not a live calling client.
- The browser status reflects multi-hop mesh source implementation.
- Physical-device validation remains visibly outstanding.
- The page distinguishes Jamaica-first testing from the international number-service scope.

The final main tree check found all 22 organization-pass paths present and preserved the existing Android, backend, web and CI paths. The follow-up web file was fetched back and its updated content verified.

## CI and GitHub evidence

- Android CI run 42607627743 for main commit 85c64d8515063c6cffc15424d399953a609a082d completed with conclusion success.
- Android CI run 42607905650 for main commit d7ca2dce8ba787db60464204e6b85d9f7ad3bd75 completed with conclusion success. The debug APK build, unit tests, source-revision metadata and APK artifact upload steps all passed.
- LINKA API CI run 41780783071 for commit aaeacf7d225f315c55d7b0aaeceac3fd3ed905ca completed successfully on 2026-11-04.
- These are GitHub Actions results. No external Render dashboard, carrier account, payment-provider account or device farm was accessed in this GitHub-only pass.
- Historical GitHub Pages deployment run 41780817544 failed at Configure GitHub Pages with Resource not accessible by integration; upload and deploy were skipped. Later repository history removed that Pages workflow and documented a single Render origin.
- Dependabot opened update PRs #5 through #10 after the new configuration was added. They are separate review items and none was merged in this pass.

## Known LINKA product gaps

The repository organization phase does not complete the entire LINKA product. The following are not yet implemented as production integrations:
- Carrier/SIP/PSTN calls to ordinary telephone numbers.
- Real Jamaican or international number provisioning.
- Integrated external SMS/MMS.
- Fully integrated Android/browser WebRTC media calls.
- Durable account/user database and production account authentication.
- Server-authoritative credit wallet and transaction ledger.
- Payment processing and settlement reconciliation.
- Provider-backed international rate catalog.
- SIM/eSIM service provisioning.
- End-to-end automatic connection switching.
- Emergency calling.

The existing system dialer and SMS app handoffs are Android system-app integrations, not LINKA's own carrier calling or messaging services. The backend signaling runtime keeps sessions and queues in memory; it is not a durable account, number, message or billing database.

Offline direct and routed voice are implemented in Android source and have automated protocol tests, but real two-phone/four-phone physical-device operation and independent security review remain release gates.

## Repository organization phase progress model

This tracker measures the assigned GitHub repository organization and orientation phase only. It does not represent completion of all LINKA product features.

Milestone weights and verified status:
- Recovery and verified baseline: 15% — VERIFIED.
- Product requirements and acceptance scope: 20% — VERIFIED.
- Architecture and integration boundaries: 15% — VERIFIED.
- Repository organization and navigation: 15% — VERIFIED.
- Contributor, security and GitHub collaboration hygiene: 10% — VERIFIED.
- Roadmap and persistent recovery checkpoint: 10% — VERIFIED.
- Correction of stale compatibility/mesh/browser status documentation: 5% — VERIFIED.
- GitHub file, tree, commit, fetch-back and relevant CI verification: 10% — VERIFIED.

Previous verified completion at the first recovery checkpoint: 15%.
Current verified completion for the repository organization phase: 100%.
Remaining for the repository organization phase: 0%.
Progress since the first recovery checkpoint: +85 percentage points.

## Remaining work outside the completed organization phase

The broader LINKA product remains unfinished. Its next roadmap phases are:
- Complete source, physical-device and security release validation for the existing offline voice foundation.
- Build durable account, identity and PostgreSQL backend services.
- Integrate provider-backed Jamaican and international number inventory.
- Integrate SIP/PSTN calling and internet audio/video media.
- Implement free calling eligibility, the optional wallet, credits, rate catalog, payment and reconciliation.
- Implement internal and external messaging.
- Integrate supported SIM/eSIM functionality and consent-aware connection switching.
- Build the full account-based website.
- Complete regulatory, security, operational and release validation.

Easy independent documentation/configuration tasks for the repository organization phase: NONE.
Medium and hard tasks: the product implementation phases listed above; they are not complete and include provider, device, security and regulatory dependencies.

## Execution boundaries

- Work only in this GitHub repository.
- Preserve existing implementation.
- Do not create a duplicate repository.
- Keep all product requirements, architecture, source, tests, integrations and release documentation inside this repository.
- Do not deploy or change external service settings during a GitHub-only pass.
- Do not add provider credentials or private signing material to source control.
- Do not claim physical-device tests, provider enablement or independent security review without evidence.
- Do not describe the whole LINKA product as complete because the repository organization phase is complete.
