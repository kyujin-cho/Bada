# WhenVivoMeetsGoogle

A standalone Android port of the **Google Quick Share / Nearby Share** protocol,
modeled on [NearDrop](https://github.com/grishka/NearDrop) by @grishka. The
goal is to send and receive files between this app and any existing Quick
Share peer (stock Android Quick Share, NearDrop on macOS, Quick Share on
Windows) **without depending on Google Play Services** for the protocol
logic.

> Apple-side interop (AirDrop, AWDL, iPhone discovery) is explicitly out of
> scope.

## Module layout

```
:app                  UI, share intents, settings (Android application)
:service-android      ForegroundService, notifications, MediaStore writes
:discovery-android    JmDNS / NsdManager wrappers, BLE advertise/scan
:core-protocol        Pure-Kotlin protocol implementation (no Android deps)
:core-protocol-test   KAT vectors, fixtures
```

`:core-protocol` is a plain Kotlin/JVM module — it depends only on
`kotlinx.coroutines`, `protobuf-javalite`, the JCE/JDK, and (optionally) Tink
for HKDF. It must never import anything from `android.*`. This split keeps
the protocol unit-testable on the JVM, which is essential because the cipher
suites and framing have hundreds of edge cases that we need KAT coverage on.

## Toolchain

| Component       | Version |
|-----------------|---------|
| Kotlin          | 2.1.x   |
| AGP             | 8.7.x   |
| Gradle          | 8.10.x  |
| JDK toolchain   | 17      |
| `compileSdk`    | 36      |
| `targetSdk`     | 36      |
| `minSdk`        | 24      |

`minSdk = 24` (Android 7.0) covers ~98% of devices and avoids the JCE/socket
awkwardness present on older releases.

Static analysis is wired up out of the box via
[ktlint](https://github.com/JLLeitschuh/ktlint-gradle) and
[detekt](https://detekt.dev/). Both run under `./gradlew check`.

## Build & test

```bash
# Build a debug APK (acceptance criterion for issue #5).
./gradlew :app:assembleDebug

# Run :core-protocol tests on plain JVM — no emulator required.
./gradlew :core-protocol:test

# Lint + style + tests across the whole project.
./gradlew check

# Run ktlint and detekt explicitly.
./gradlew staticAnalysis
```

## Status

Phase 1 is in active development. Track progress on the
[Phase 1 epic](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/1).

## Reference material

- Protocol spec: <https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md>
- NearDrop source (the implementation we're porting from):
  <https://github.com/grishka/NearDrop>
- Google's UKEY2 handshake spec: <https://github.com/google/ukey2>
- Quick Share `.proto` files (vendored from Chromium):
  <https://github.com/grishka/NearDrop/tree/master/NearbyShare/ProtobufSource>
