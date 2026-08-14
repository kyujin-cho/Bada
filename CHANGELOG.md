# Changelog

## 2026-08-14 — Google Tap to Share file handoff

- Added an independent Google-compatible Tap to Share file-transfer path using the primary Gesture Exchange AID, exact Noise handshake, encrypted protobuf handover, and live Wi-Fi Direct credentials.
- Routed the preconnected Wi-Fi Direct stream into SuperDrop's existing outbound/inbound transfer, consent, progress, and teardown engines.
- Added a separate Google Tap to Share setting and HCE service. The proprietary Name Card feature remains SuperDrop-to-SuperDrop only and was not replaced.
- Added the mapped bezel edge-light and haptic states plus bounded, automatically uploaded semantic diagnostics.
- Preserved the legacy Quick Share NFC path as the fallback when the new Google-compatible setting is disabled.
- Canonical debug artifact: `bada-fork-debug.apk` (SHA-256 `b50273f8ecc991e1ce1f1edcae92a98a89fdf25c8f6e71214a93ac7145d2da25`).
