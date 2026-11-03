# Security requirements

- Generate device identity keys using Android Keystore where supported.
- Never place private keys, session secrets or authentication tokens in QR codes.
- Pair through an authenticated challenge and explicit user confirmation.
- Use reviewed cryptographic libraries and standard authenticated encryption; do not invent cryptography.
- Establish end-to-end session keys between call endpoints.
- Mesh relays must not possess media decryption keys.
- Include sequence numbers, nonces, replay protection and bounded packet lifetimes.
- Store only necessary call metadata locally.
- Provide device revocation and lost-device recovery.
- Require explicit consent before relaying traffic.
- Threat-model malicious relays, impersonation, replay, denial of service and traffic analysis.
- Complete independent security review before public production release.
