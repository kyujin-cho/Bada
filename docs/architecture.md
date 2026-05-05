# LibreDrop

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
:discovery-android    NsdManager wrappers, BLE advertise/scan, Bluetooth Classic bootstrap
:core-protocol        Pure-Kotlin protocol implementation (no Android deps)
:core-protocol-test   KAT vectors, fixtures
```

`:core-protocol` is a plain Kotlin/JVM module that depends only on
`kotlinx.coroutines`, `protobuf-javalite`, and the JCE/JDK. It must never
import anything from `android.*`. This split keeps the protocol
unit-testable on the JVM — essential because the cipher suites and framing
have hundreds of edge cases that need KAT coverage.

### `:core-protocol` package map

- **`...protocol.transport`** — `FramedConnection`, the length-prefixed TCP
  framing that is the lowest layer of the Quick Share transport.
- **`...protocol.ukey2`** — `Ukey2Client` / `Ukey2Server` implementing the
  P256_SHA512 key-exchange handshake over `FramedConnection`.
- **`...protocol.crypto`** — `Hkdf` (HKDF-SHA256, implemented directly on
  `javax.crypto.Mac("HmacSHA256")` to avoid Tink's transitive
  `protobuf-java` clashing with `protobuf-javalite` on Android) and
  `D2DKeyDerivation` / `D2DSessionKeys` (the post-handshake chain that
  derives the four AES-256 / HMAC-SHA256 traffic keys, locked down by KAT
  vectors in `:core-protocol-test`).
- **`...protocol.crypto.securemessage`** — `SecureMessageCodec` (stateless
  AES-256-CBC + HMAC-SHA256 envelope primitive) and `SecureChannel`
  (per-connection wrapper around `FramedConnection` that reads and writes
  `OfflineFrame` protos with pre-incremented sequence numbers and
  HMAC-before-decrypt order).
- **`...protocol.payload`** — `PayloadAssembler` reassembles
  `PayloadTransferFrame` chunks into BYTES and FILE payloads, validating
  per-`payload_id` offsets and tolerating Android's "two-frame" quirk on
  receive. FILE bytes stream through a caller-supplied
  `FileDestinationFactory` (the Android wiring substitutes a `MediaStore`
  content-URI factory at this seam). `PayloadTransferEncoder` emits the
  same shape on send for both payload types: data chunks with `flags=0`
  followed by a dedicated empty `LAST_CHUNK` terminator at
  `offset=totalSize`. Samsung One UI 7+ requires the split terminator for
  FILE payloads; fusing it into the last data chunk causes silent
  discard on the receiver.
- **`...protocol.connection`** — `InboundConnection` ties everything
  together: accepts a TCP connection, runs UKEY2, derives the
  `D2DSessionKeys` with the correct role swap, drives the
  `InboundSharingFsm` through user consent, streams payloads through the
  assembler, and signals completion via `Disconnection`. Public surface is
  a coroutine-based `suspend fun run(factory)`, a
  `StateFlow<InboundConnectionState>` for UI observation, and a
  thread-safe `submitUserConsent(accepted)` / `cancel()` pair. The same
  module surfaces the `TransferMetadata` (filenames, sizes, MIME types,
  4-digit confirmation PIN derived from the UKEY2 `authString`) that the
  consent UI renders.

### Android wiring (`:service-android`)

`ReceiverForegroundService` (foreground-service type `connectedDevice`)
brings the stack online: it acquires the Wi-Fi `MulticastLock`, binds the
`TcpReceiverServer` accept loop on an ephemeral port, registers the
`Discovery.advertise` mDNS record against that port, and supplies a fresh
`MediaStoreDownloadsFactory` per accepted connection so the per-payload
`IS_PENDING` state never bleeds across transfers. The bulk of the
lifecycle logic lives in a pure-JVM `ReceiverSession` helper that the
`Service` only thinly wraps, keeping the start/stop/error-rollback paths
exhaustively unit-testable without Robolectric.

The launcher also exposes a persisted "Advertised Quick Share name"
override for the receiver. When unset, LibreDrop resolves the advertised
name from Android's device-name chain (`Settings.Global.DEVICE_NAME` on
API 25+, then the Bluetooth adapter name when it is safely readable,
then `Build.MODEL`, then the app label) and clamps the final
`EndpointInfo.deviceName` to 19 UTF-8 bytes to avoid stock Quick Share
interop regressions with longer names.

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

## Testing

Manual interop runbooks (markdown checklists) live under
[`docs/testing/`](docs/testing/):

- [NearDrop on macOS interop checklist](docs/testing/interop-neardrop-macos.md)
  — start here when verifying end-to-end behavior against the reference
  implementation.
- [Stock Android Quick Share interop](docs/testing/interop-stock-quick-share-android.md)
  — Pixel (clean GMS) and Samsung (One UI) coverage.

## Status

Phase 1 is complete. Track the
[Phase 1 epic](https://github.com/kyujin-cho/LibreDrop/issues/1)
for the full sub-issue list and merged PRs.

## Networking requirements

Shared Wi-Fi remains the baseline Quick Share path, but sender-side
bootstrap is no longer limited to pure LAN discovery:

- **LibreDrop sender -> stock Quick Share receiver** can start either from the
  shared-LAN mDNS path or from a nearby Bluetooth-assisted bootstrap path
  when the devices are off-LAN. For the off-LAN path, keep Bluetooth on
  at both ends and use the stock peer's visible-to-everyone mode.
- **Stock Quick Share sender -> LibreDrop receiver** still depends on the
  shared-LAN receiver discovery path today, so keep the devices on the
  same Wi-Fi network for that direction.
- For the shared-LAN regression path, both devices must be on the same
  Wi-Fi network and on the same VLAN. mDNS multicasts
  (`_FC9F5ED42C8A._tcp.local.`) do not cross routed subnets, so a
  typical "Guest" SSID will silently break discovery.
- The Wi-Fi network must permit IPv4 multicast / mDNS traffic. Some
  enterprise APs drop multicast frames by default.
- AP isolation / "client isolation" must be off on the access point.
- The stock-device interop matrix and current manual validation checklist
  live in [docs/testing/interop-stock-quick-share-android.md](docs/testing/interop-stock-quick-share-android.md).

The receiver's persistent notification surfaces the current Wi-Fi SSID
(`Receiving on "<SSID>"`) so you can verify both ends match without
leaving the app.

## Reference material

- Protocol spec: <https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md>
- NearDrop source (the implementation we're porting from):
  <https://github.com/grishka/NearDrop>
- Google's UKEY2 handshake spec: <https://github.com/google/ukey2>
- Quick Share `.proto` files (vendored from Chromium):
  <https://github.com/grishka/NearDrop/tree/master/NearbyShare/ProtobufSource>
