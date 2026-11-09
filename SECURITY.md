# Security policy

LINKA handles device identity and communication metadata, and may eventually handle telephone numbers and payments. Security reports must be treated carefully.

## Reporting a vulnerability
Do not publish exploitable details, credentials, private keys, personal data, call recordings or attack instructions in a public issue.

Use GitHub's private vulnerability reporting feature for this repository if it is enabled. If private reporting is not enabled, contact the repository owner through the GitHub profile and request a private channel before sharing sensitive details.

Include the affected commit/release, component, impact, safe reproduction summary and relevant redacted logs. Remove phone numbers, personal data, tokens, keys and call content.

Do not test against other users, production carrier accounts or third-party systems without authorization.

## Security expectations
- Never commit secrets, signing keys, private keys, bearer tokens or production credentials.
- Keep device private keys in platform-protected keystores.
- QR identity data must never contain private keys or reusable secrets.
- Keep relays unable to decrypt caller-to-recipient media.
- Use authenticated, bounded and expiring protocol messages.
- Use idempotent payment webhooks and a server-authoritative credit ledger when billing is implemented.
- Require explicit authorization before a paid call, message, number rental or top-up.
- Do not silently fall back to a billable carrier route.
- Do not claim emergency calling or independently audited cryptographic security without evidence.

## Current limitations
The source has automated tests for parts of nearby identity, encryption and routing. It has not been independently cryptographically audited and has not completed the physical-device release tests in issue #2. Carrier, number-provisioning and payment integrations are not production services in the current source.
