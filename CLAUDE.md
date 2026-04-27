# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Standalone Android port of Google's **Quick Share / Nearby Share** protocol, modelled on [NearDrop](https://github.com/grishka/NearDrop). The goal is to send and receive files between this app and any existing Quick Share peer (stock Android Quick Share, NearDrop on macOS, Quick Share on Windows) **without depending on Google Play Services for the protocol logic**.

The wire spec we target is the one documented at <https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md>. Apple-side interop (AirDrop, AWDL, iPhone discovery) is explicitly **out of scope**.

Phase 1 (Wi-Fi LAN parity with NearDrop) is complete; Phase 2 will add BLE auto-discovery.

## Common commands

```bash
# Build the debug APK.
./gradlew :app:assembleDebug

# Run the JVM-only protocol tests (no emulator).
./gradlew :core-protocol:test

# Lint + style on all modules (single source of truth — wired by the root build).
./gradlew staticAnalysis        # alias for ktlintCheck + detekt across every subproject

# Full check (lint + style + tests across every module).
./gradlew check

# Run one test class on the JVM.
./gradlew :core-protocol:test --tests '*.crypto.HkdfTest'

# Run one test method (Kotlin backtick names need quoting; in Gradle, plain method
# name with spaces in single quotes works).
./gradlew :core-protocol:test --tests 'io.github.kyujincho.wvmg.protocol.crypto.HkdfTest.RFC 5869 test case 1'

# Auto-format ktlint violations.
./gradlew ktlintFormat
```

CI (`.github/workflows/ci.yml`) runs `staticAnalysis`, `:core-protocol:test`, `:app:testDebugUnitTest`, and `:app:assembleDebug` on JDK 17.

### On-device debugging

Several diagnostic logcat tags are wired up for real-device testing — useful when discovery or transfer behaves differently from JVM tests:

```bash
adb logcat -s WvmgDiscovery WvmgSend WvmgOutbound
```

If a manufacturer's logcat filter swallows the app's `Log.i` output (vivo Funtouch OS does this), `OutboundConnection`'s logger uses `Log.e` and also appends to `getExternalFilesDir(null)/wvmg-outbound.log` — pull it with:

```bash
adb shell cat /sdcard/Android/data/io.github.kyujincho.wvmg.debug/files/wvmg-outbound.log
```

## Architecture

Five Gradle modules. The split is driven by one hard rule: the protocol implementation must be JVM-testable, so anything that imports `android.*` lives outside `:core-protocol`.

```
:core-protocol         pure Kotlin/JVM — wire framing, UKEY2, SecureMessage, payload
                       reassembly, sharing FSM, Inbound/OutboundConnection orchestrators.
                       NO android.* imports. Adding one is a regression — guard in review.
:core-protocol-test    KAT vectors and shared fixtures. Pure JVM.
:discovery-android     mDNS publish/browse via JmDNS, multicast lock, network-change watcher.
                       Android-only; wraps :core-protocol's EndpointInfo.
:service-android       Foreground receiver service (connectedDevice type), MediaStore-backed
                       FileDestinationFactory, consent notification + broadcast receiver.
:app                   UI: permissions onboarding, share-intent SendActivity, ShowQrActivity,
                       consent trampoline.
```

`:core-protocol` enables `explicitApi()` and `allWarningsAsErrors`. Everything public must be intentional.

### Protocol layers (reading guide)

The Quick Share stack is best read bottom-up:

1. **`...protocol.transport.FramedConnection`** — 4-byte big-endian length prefix over `java.net.Socket`, with `SANE_FRAME_LENGTH = 5 * 1024 * 1024`. `EndOfFrameStream` is the dedicated signal for a clean half-close at a frame boundary; mid-header truncation throws `EOFException`.
2. **`...protocol.crypto.Hkdf`** — RFC 5869 HKDF-SHA256, hand-rolled on `javax.crypto.Mac("HmacSHA256")`. Tink is intentionally NOT a dependency: its transitive `protobuf-java` clashes with `protobuf-javalite` on Android.
3. **`...protocol.ukey2.{Ukey2Client,Ukey2Server}`** — P256_SHA512 key-exchange handshake over `FramedConnection`. Computes `dhs = SHA256(magnitude(ECDH.x))`, exposes the raw serialized `Ukey2Message` bytes for downstream HKDF input. Includes explicit on-curve validation because SunEC's `KeyFactory.generatePublic` only range-checks.
4. **`...protocol.crypto.D2DKeyDerivation` + `D2DSessionKeys`** — derives `authString` (PIN material), `nextSecret`, the four AES-256 / HMAC-SHA256 traffic keys (client/server × encrypt/HMAC). All salt/info bytes are pinned by KAT vectors in `:core-protocol-test`.
5. **`...protocol.crypto.securemessage.{SecureMessageCodec,SecureChannel}`** — AES-256-CBC + HMAC-SHA256 envelope with **HMAC-verify-before-decrypt** order and **pre-incremented sequence numbers** (replay-protected). `SecureChannel` is the per-connection wrapper that reads/writes `OfflineFrame` protos.
6. **`...protocol.payload.{PayloadAssembler,PayloadTransferEncoder}`** — chunk reassembly for BYTES + FILE payloads, including the Android "two-frame BYTES" quirk (data chunk followed by a zero-body LAST_CHUNK). FILE bytes stream through a caller-supplied `FileDestinationFactory`. The encoder emits the same two-frame shape on send.
7. **`...protocol.sharing.{Inbound,Outbound}SharingFsm`** — pure FSMs that drive the Sharing.Nearby.Frame negotiation (PairedKeyEncryption, PairedKeyResult, Introduction, ConnectionResponse, Cancel). Inputs are events; outputs are an ordered `List<SharingFsmEffect>`. No I/O.
8. **`...protocol.connection.{Inbound,Outbound}Connection`** — top-level orchestrators. Tie 1–7 together over a single `Socket`; expose `suspend fun run(...)`, `StateFlow<…ConnectionState>`, and a thread-safe `cancel()`. Receiver glue (consent, file destinations, transfer metadata) goes through here.

`:service-android` wraps `OutboundConnection` (called by `SendActivity` after the user picks a peer) and runs `TcpReceiverServer` + `InboundConnection` from `ReceiverForegroundService` (foreground-service type `connectedDevice`).

### Quick Share interop quirks worth knowing

These are baked into the codebase as comments at the relevant call sites; surfacing them here so future work doesn't relearn them the hard way:

- **mDNS visibility bit is always 1 for stock Quick Share.** The "Everyone vs Contacts only" decision is enforced during the negotiation, not at the mDNS layer. The picker must not filter peers by `EndpointInfo.hidden`.
- **Wi-Fi address resolution must use `ConnectivityManager.LinkProperties`**, not the deprecated `WifiManager.connectionInfo`. The latter returns sentinel `0.0.0.0` on API 31+ for apps without precise-location permission, and we don't ask for that.
- **`ConnectionRequestFrame` minimum required fields**: `endpoint_id`, `endpoint_info`, `endpoint_name` (legacy, can be empty), `mediums = [WIFI_LAN]`, `keep_alive_interval_millis`, `keep_alive_timeout_millis`. Without `mediums`, Android 14+ Nearby Connections rejects the request.
- **`ConnectionResponseFrame`** must have `response = ACCEPT`, `os_info.type = ANDROID` (`LINUX = 100` is g3-test-only and Samsung One UI silently FINs on it), and `safe_to_disconnect_version = 1` (Samsung One UI 7+ requires it). The deprecated legacy `status = 0` is also set for older receivers.
- **ConnectionResponse exchange order is send-first-then-receive** (matches NearDrop's `OutboundNearbyConnection`). Receive-first deadlocks both peers until the receiver times out.
- **Blocking Socket I/O under `withContext(Dispatchers.IO)` does NOT honour coroutine cancellation while parked in a syscall.** `runReceiveLoop` in both connection drivers (and `TcpReceiverServer.stop`) closes the socket BEFORE `cancelAndJoin`'ing the pump, otherwise teardown deadlocks.

### Tests

JVM tests under `:core-protocol/src/test/...` are the safety net for the entire wire stack — they cover bit-level KATs (HKDF / HMAC / AES-CBC / AES-GCM / ECDH P-256), sequence-number invariants on the SecureMessage envelope, and end-to-end loopback integration that pairs `OutboundConnection` with `InboundConnection` over real loopback `Socket` pairs (with `@Timeout(SEPARATE_THREAD)` so a hung integration fails the run instead of the whole JVM).

Manual on-device interop is documented as Markdown checklists under `docs/testing/`:
- `docs/testing/interop-neardrop-macos.md` — reference implementation on Mac.
- `docs/testing/interop-stock-quick-share-android.md` — Pixel + Samsung coverage.

## Conventions

- **Branch names**: `<type>/<short-description>` where type is `feature`, `bugfix`, `hotfix`, `refactor`, `docs`, `test`, or `chore`. Issue-scoped branches include the issue number, e.g. `bugfix/issue-83-discovery-real-devices`.
- **Commit / PR messages**: English only. No AI attribution lines (no `Co-Authored-By: Claude`, no `Generated with` blurbs). Use the `feat:` / `fix:` / `chore:` / `refactor:` / `docs:` / `test:` prefix that matches the change.
- **Merge strategy**: squash-merge (`gh pr merge <N> --squash --delete-branch`) is the default. The `--delete-branch` flag will fail to delete the local branch if a worktree is using it; clean up the worktree afterwards with `git worktree remove --force <path>`, then `git branch -D <branch>`.
- **Worktrees**: parallel work uses sibling worktrees at `../wt-epic-<E>-issue-<N>` or `../wt-issue-<N>`. Always copy `local.properties` from the primary tree (it's gitignored): `cp /Users/kyujin/Projects/WhenVivoMeetsGoogle/local.properties .` — Android builds need it for `sdk.dir`.
