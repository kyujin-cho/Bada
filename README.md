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
`kotlinx.coroutines`, `protobuf-javalite`, and the JCE/JDK. It must never
import anything from `android.*`. This split keeps the protocol
unit-testable on the JVM, which is essential because the cipher suites and
framing have hundreds of edge cases that we need KAT coverage on. HKDF-SHA256
is implemented directly on `javax.crypto.Mac("HmacSHA256")` (see
`:core-protocol`'s `Hkdf` object) rather than via Google Tink, avoiding
Tink's transitive `protobuf-java` dependency that would clash with
`protobuf-javalite` on Android. Length-prefixed TCP framing (the lowest
layer of the Quick Share transport) lives in the same module as
`FramedConnection` under `...protocol.transport`. The UKEY2 P256_SHA512
key-exchange handshake (`Ukey2Client`, `Ukey2Server`) lives under
`...protocol.ukey2` and runs over `FramedConnection`. The post-handshake
HKDF chain that derives the four AES-256 / HMAC-SHA256 traffic keys
(`D2DKeyDerivation`, `D2DSessionKeys`) lives next to `Hkdf` in
`...protocol.crypto` and is locked down by KAT vectors in
`:core-protocol-test`.

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
