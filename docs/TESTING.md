# Testing strategy

## Automated
- Unit-test route selection, hop limits, call state transitions and packet validation.
- Test identity serialization and QR payload validation.
- Test duplicate packet suppression and route expiry.
- Run Android build and unit-test checks in CI.

## Physical-device tests
A simulator cannot prove Bluetooth/Wi-Fi Direct range, radio concurrency, microphone reliability, background execution or mesh forwarding.

Minimum lab: two Android phones for direct discovery and bidirectional voice; three or more Android phones for mesh proof-of-concept; at least two manufacturers and Android versions; SIM removed and mobile data disabled; Wi-Fi internet disconnected; airplane mode with local radios re-enabled where supported.

Do not label offline calling as working until a physical-device test demonstrates no SIM, no internet, authenticated pairing, bidirectional intelligible audio, safe disconnect/recovery and no unapproved SIM fallback. Mesh additionally requires a demonstrated multi-hop route with encrypted payload forwarding.
