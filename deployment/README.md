# LINKA deployment runbook

This directory contains deployment instructions and safe configuration guidance for the existing LINKA repository. It does not assert that any Render service, production database, provider credential, domain or secret is currently configured.

## Existing service contract

- Repository: `ravaunrichards/Linka-Android`
- Backend working directory: `backend/api/`
- Runtime: Node.js 22+
- Build command: `npm install --omit=dev` (when the Render service root is set to `backend/api`), or `npm --prefix backend/api install --omit=dev` from the repository root.
- Start command from repository root: `npm --prefix backend/api start`
- Health endpoint: `/healthz`
- Readiness endpoint: `/readyz`
- API information: `/v1/info`
- PostgreSQL: provide a private Render PostgreSQL connection through `DATABASE_URL` only after the intended workspace and database are confirmed.
- Browser companion: the API serves the browser assets from the existing `web/` directory. A separate browser origin requires exact HTTPS origins in `CORS_ALLOWED_ORIGINS`.

## Safe deployment checklist

1. Confirm the exact Render workspace, service and repository connection before making any service change.
2. Confirm the configured branch is the intended reviewed branch. Do not deploy a draft feature branch to production.
3. Confirm build and start commands match the selected service root.
4. Configure secrets through Render's environment-variable controls; never commit secrets to this repository.
5. Configure `DATABASE_URL` only for the intended LINKA database. Do not point development deployments at production data.
6. Apply database migrations through the reviewed migration process; CI verifies migrations against disposable PostgreSQL, not the production database.
7. Verify `/healthz`, `/readyz`, `/v1/info`, browser assets, CORS behavior and account/session integration.
8. Configure TLS, service monitoring, structured logs, database backups and restore drills.
9. Keep production release signing keys outside GitHub and outside Render application source.
10. Roll back through the prior known-good deployment if readiness or smoke tests fail.

## Known service limitations

- The API carries authentication, account/device state, call signaling and supported messaging operations; it does not carry voice audio.
- Production WebRTC media requires a tested media route and STUN/TURN/SFU deployment where the network requires it.
- Carrier/PSTN termination, number provisioning, SMS/MMS delivery, payment processing, email recovery and SIM/eSIM provisioning need real approved providers.
- Debug APK artifacts are for testing and are not signed production releases.
- LINKA is not an emergency-calling service.

## Verification record

Update `docs/PROJECT_STATUS.md` after every deployment with the exact service, commit, timestamp, health/readiness result, database migration version and rollback reference. Do not record a deployment as production-verified based only on a successful GitHub Actions run.
