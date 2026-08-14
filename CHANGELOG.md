# Changelog

## 2026-08-14 — Google Tap to Share file handoff

- Added an independent Google-compatible Tap to Share file-transfer path using the primary Gesture Exchange AID, exact Noise handshake, encrypted protobuf handover, and live Wi-Fi Direct credentials.
- Routed the preconnected Wi-Fi Direct stream into SuperDrop's existing outbound/inbound transfer, consent, progress, and teardown engines.
- Added a separate Google Tap to Share setting and HCE service. The proprietary Name Card feature remains SuperDrop-to-SuperDrop only and was not replaced.
- Added the mapped bezel edge-light and haptic states plus bounded, automatically uploaded semantic diagnostics.
- Preserved the legacy Quick Share NFC path as the fallback when the new Google-compatible setting is disabled.
- Added ordered Android Share Sheet URI intake, first-class UTF-8 text/link payloads, exact-length staging for unknown-size providers, storage/stall limits, and terminal cleanup.
- Added one-role/one-tag session gating, radio-readiness arming, strict handover validation, API-37 capability-gated NFC policy, reduced-motion rendering, and replayable private diagnostic fallback.
- Added deterministic Noise corruption/replay/limit/exhaustion coverage and a complete outbound text-to-inbound Quick Share loopback test.
- Raised compile/target SDK to 37 while retaining minSdk 24; API-37 references remain isolated behind runtime gates.
- Canonical debug artifact: `bada-fork-debug.apk` (SHA-256 `2523cb55b93a1cb82097a5a7da6c817c3bd506fa0330f1b40ef91c3de3c7e762`).
