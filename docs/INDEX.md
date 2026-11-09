# LINKA documentation

This directory is the authoritative documentation hub for the LINKA repository. Start here before changing code or making a product-status claim.

## Product and requirements
- [Product requirements](PRODUCT_REQUIREMENTS.md) — Jamaican and international numbers, free and credit services, calling, messaging, connectivity, Android, web, backend, safety and acceptance criteria.
- [Telephony and billing](TELEPHONY_AND_BILLING.md) — number lifecycle, rate lookup, free-first routing, credit wallet, payment authorization and billing safeguards.
- [Jamaica compatibility](JAMAICA_COMPATIBILITY.md) — Jamaica-first device/carrier validation plus international number scope.

## Engineering and repository
- [Architecture](ARCHITECTURE.md) — current technical design and implemented boundaries.
- [Architecture decisions](ARCHITECTURE_DECISIONS.md) — explicit decisions and constraints for future work.
- [Repository structure](REPOSITORY_STRUCTURE.md) — current source tree, ownership boundaries and rules for adding modules.
- [Render API service notes](RENDER_API_SERVICE.md) — historical repository documentation for the API/browser companion deployment; check current provider state before relying on it.

## Delivery and verification
- [Roadmap](ROADMAP.md) — ordered implementation phases and acceptance gates.
- [Integration matrix](INTEGRATION_MATRIX.md) — what exists, what is tested, what needs devices, and what is blocked by providers.
- [Testing strategy](TESTING.md) — automated checks and required physical-device validation.
- [Offline mesh roadmap](OFFLINE_MESH_ROADMAP.md) — source implementation, automated coverage and remaining field tests.
- [Security policy](../SECURITY.md) — vulnerability reporting and security expectations.
- [Project status and recovery checkpoint](PROJECT_STATUS.md) — verified repository baseline and outstanding work.

## Status vocabulary
- SOURCE IMPLEMENTED — implementation exists in the repository.
- AUTOMATED TESTED — a named automated test has passed.
- DEVICE TEST REQUIRED — automated tests are insufficient; physical device validation remains.
- PROVIDER BLOCKED — an external carrier, number, messaging, payment or infrastructure provider is required.
- NOT IMPLEMENTED — no working implementation has been verified.
- LIVE VERIFIED — a live external service was checked during the stated execution. A repository URL or old deployment note does not by itself establish this status.

A feature can have more than one status. Source can be implemented and unit-tested while real-device testing remains outstanding.
