# Interop test: NearDrop on macOS

This is a manual test runbook for verifying that the WhenVivoMeetsGoogle
Android app can send to and receive from
[NearDrop](https://github.com/grishka/NearDrop) on macOS without errors.
NearDrop is the open-source reference implementation of the Quick Share /
Nearby Share protocol that this project is modeled on, so passing the
matrix below is a strong signal that we will also interoperate with stock
Quick Share peers (Android, Windows, ChromeOS).

The goal is twofold:

1. Exercise every path in the test matrix on at least one engineer's
   hardware before issue
   [#29](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/29) is
   closed.
2. Give a future engineer enough context to reproduce a failure and file
   a follow-up bug with the right code pointers attached.

When something fails, jump to the
[What to log if a step fails](#what-to-log-if-a-step-fails) section before
moving on — once the network state changes, wire traces are gone.

## Preconditions

### Mac (NearDrop)

- [ ] macOS 13 (Ventura) or newer.
- [ ] NearDrop installed from
      <https://github.com/grishka/NearDrop/releases>. The README on the
      project page is the source of truth for install steps; at the time
      of writing it ships as a notarised `.dmg` that drags into
      `/Applications`.
- [ ] NearDrop launched at least once and granted the macOS
      "Local Network" permission prompt. Without this it will not see
      mDNS announcements from the phone.
- [ ] `Bluetooth on` (NearDrop uses BLE for the discovery beacon even
      though file transfer itself rides on Wi-Fi).
- [ ] Mac is **not** asleep during the test — even Power Nap can suspend
      the mDNS responder mid-handshake and produce misleading failures.

### Android phone (this app)

- [ ] Debug build of `:app` installed: `./gradlew :app:installDebug`.
- [ ] Android 7.0 (API 24) or newer. The app's `minSdk` is 24 (see
      [`README.md`](../../README.md#toolchain)), but for this checklist
      use API 29+ so MediaStore Downloads writes behave consistently.
- [ ] All runtime permissions granted to the app: nearby Wi-Fi
      devices / location (depending on Android version), notifications,
      and storage for received files.
- [ ] Notifications **not** silenced for this app — the receive consent
      UI surfaces through the foreground-service notification.
- [ ] Battery saver / data saver disabled. Aggressive Doze can break
      mDNS without producing a clear error.

### Networking requirements

Phase 1 uses Wi-Fi LAN discovery (mDNS), so both devices **must** be on
the same Wi-Fi network. BLE auto-discovery is Phase 2 and out of scope
for this runbook. The receiver's persistent notification surfaces the
current Wi-Fi SSID (`Receiving on "<SSID>"`) — use it to confirm both
ends match before you start.

### Network

- [ ] Both devices on the same Wi-Fi SSID **and** the same VLAN. mDNS
      `_FC9F5ED42C8A._tcp.local.` queries do not cross VLAN boundaries,
      so a typical "Guest" SSID will silently break discovery. We
      explicitly test this in the wrong-network case at the bottom.
- [ ] AP isolation / "client isolation" off on the access point.
- [ ] IPv4 multicast permitted on the LAN. Some enterprise APs drop
      multicast frames by default.
- [ ] On the Mac: confirm with `dns-sd -B _FC9F5ED42C8A._tcp.` that the
      phone's mDNS service appears within ~5 s of the app being
      foregrounded.

### Test fixtures

Pre-stage these on both sides so each run is reproducible:

- [ ] `small.bin` — 1 MiB random bytes
      (`head -c 1m </dev/urandom > small.bin`).
- [ ] `large.bin` — 1 GiB random bytes
      (`head -c 1g </dev/urandom > large.bin`).
- [ ] `clip.txt` — a known UTF-8 string ("Hello from
      WhenVivoMeetsGoogle, run YYYY-MM-DD").
- [ ] `link.url` — a stable HTTPS URL
      (e.g. `https://example.com/`).
- [ ] Pre-computed SHA-256 hashes for `small.bin` and `large.bin`:
      `shasum -a 256 small.bin large.bin > fixtures.sha256`.

## Test matrix

Tick each row once it passes end-to-end. If a row fails, leave it
unticked, capture the artefacts described in
[What to log if a step fails](#what-to-log-if-a-step-fails), and open a
new issue linking back to #29.

### Phone -> Mac

- [ ] **Small file.** Share `small.bin` from the Android share sheet to
      the Mac. NearDrop shows the consent prompt with the correct
      filename, size, and 4-digit PIN that matches the phone's PIN.
      After accepting, `small.bin` lands in `~/Downloads/` on the Mac
      and `shasum -a 256` matches the fixture.
- [ ] **Large file.** Same, but with `large.bin`. Confirm the progress
      bar advances smoothly (no long stalls > 2 s) and that the final
      hash matches. This exercises multi-frame `PayloadTransferFrame`
      chunking on the encoder side; see
      [`PayloadTransferEncoder.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/payload/PayloadTransferEncoder.kt).
- [ ] **Text.** Share `clip.txt`'s contents as a text payload (long-press
      and "Share text"). NearDrop's notification shows the exact string;
      copying it into a terminal yields a byte-for-byte match.
- [ ] **URL.** Share `link.url` from a browser. NearDrop opens it as a
      URL (not as a file) and the URL string matches exactly.

### Mac -> Phone

- [ ] **Small file.** From the Mac, drag `small.bin` onto the NearDrop
      menubar item, pick the phone, and confirm the PIN matches on
      both screens before accepting. The file lands in `Downloads/` on
      the phone (via MediaStore — see
      [`MediaStoreDownloadsFactory.kt`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/downloads/MediaStoreDownloadsFactory.kt))
      and `shasum -a 256` matches.
- [ ] **Large file.** Same with `large.bin`. Confirm the progress
      notification updates and hashes match. This exercises the
      receive-side reassembly in
      [`PayloadAssembler.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/payload/PayloadAssembler.kt).
- [ ] **Text.** From the Mac, share text via NearDrop. The phone shows
      a notification with the exact string.
- [ ] **URL.** From the Mac, share a URL. The phone treats it as a URL
      payload (offers to open in a browser) rather than a file.

### Cancellation

- [ ] **Cancel from Mac mid-transfer.** Start a Phone -> Mac transfer of
      `large.bin`, then click "Cancel" on the NearDrop popover during
      the progress phase. The phone shows the transfer as cancelled
      within ~2 s, the foreground notification clears, and no partial
      file is left in `Downloads/` on the Mac. The disconnect path lives
      in
      [`OutboundConnection.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/OutboundConnection.kt)
      / `Disconnection` frame handling.
- [ ] **Cancel from Phone mid-transfer.** Start a Phone -> Mac transfer
      of `large.bin`, then tap "Cancel" on the phone's progress
      notification. NearDrop shows "Cancelled by sender" (or equivalent)
      and removes the partial file from `~/Downloads/`. Reverse the
      direction (Mac -> Phone) and cancel from the phone too, verifying
      no zero-byte / `IS_PENDING` orphan stays behind in MediaStore.

### QR-code path

- [ ] On the Mac, NearDrop's "Hidden mode / scan code" UI surfaces a QR
      code. On the phone, use the app's send flow's "Scan code" entry
      and point the camera at the Mac's screen. The phone connects
      directly without needing the Mac to be visible via mDNS. After
      scanning, run a normal small-file transfer end-to-end to confirm
      the QR-derived endpoint info works through the rest of the stack.
      QR parsing logic lives under
      [`core-protocol/.../qr/`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/qr/).

### Invalid PIN (UX test)

- [ ] Run a Phone -> Mac transfer. The phone displays a 4-digit PIN
      derived from the UKEY2 `authString`
      (see [`PinDerivation.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/crypto/pin/PinDerivation.kt)).
      The Mac displays a 4-digit PIN derived the same way. **Visually
      verify the two PINs match** before tapping "Accept" on either
      side. If they ever differ on a clean run, that is a P0 bug —
      capture the wire trace and `logcat` immediately, do not retry.
- [ ] Sanity-check the negative path: tap "Accept" on the phone without
      looking at the Mac, then look at the Mac. Even if the PINs happen
      to match, confirm the UI made it possible to compare (i.e. the
      PIN was on screen at the same time on both devices).

### Wrong-network (negative test)

- [ ] Move the Mac to a different VLAN (e.g. a "Guest" SSID, or a
      separate router). Wait 30 s for caches to settle. Confirm the
      phone does **not** see the Mac in its peer list and NearDrop does
      **not** see the phone. This is the expected behavior (mDNS
      doesn't cross VLANs); a positive result here would mean the
      discovery layer was leaking onto the wrong interface.
- [ ] Move the Mac back to the test VLAN; confirm both peers reappear
      within ~5 s.

## Hash verification

After every file transfer:

```bash
# On macOS (both sender and receiver side):
shasum -a 256 path/to/file
```

```bash
# On Android, use the Termux app or `adb shell` from a host:
adb shell sha256sum /storage/emulated/0/Download/path/to/file
```

The hashes must match the pre-staged `fixtures.sha256` exactly. Any
mismatch indicates a payload-layer bug; jump straight to capturing
the secure-channel trace described below — the bug is most likely in
[`SecureMessageCodec.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/crypto/securemessage/SecureMessageCodec.kt)
or
[`PayloadAssembler.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/payload/PayloadAssembler.kt).

## What to log if a step fails

When a row fails, capture **all** of the following before retrying. Once
the network state changes (Wi-Fi reconnect, app restart, Mac sleep) the
evidence is usually unrecoverable.

### 1. Wire trace

Capture the full TCP exchange so we can replay it offline:

```bash
# On the Mac, before triggering the failing step.
# Replace en0 with the active interface from `route get default`.
sudo tcpdump -i en0 -s 0 -w /tmp/quickshare-fail.pcap \
  'tcp portrange 1024-65535 or udp port 5353'
```

Stop with Ctrl-C as soon as the failure manifests. For a richer view,
open the same `.pcap` in [Wireshark](https://www.wireshark.org/) and
filter on the phone's IP. Quick Share TCP frames are length-prefixed
(see
[`FramedConnection.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/transport/FramedConnection.kt));
the first few frames before encryption kicks in are UKEY2 ClientInit /
ServerInit / ClientFinished and are decodable as protobufs against the
NearDrop `.proto` files
([`core-protocol/src/main/proto/`](../../core-protocol/src/main/proto)).

### 2. Android `logcat`

```bash
# From a host with adb installed:
adb logcat -c          # clear the buffer first
# trigger the failing step on the phone, then immediately:
adb logcat -d -v threadtime > /tmp/wvmg-failure.logcat
```

Filter for the app's process and the protocol tags:

```bash
adb logcat -d -v threadtime \
  | grep -E '(io.github.kyujincho.wvmg|Ukey2|SecureChannel|Inbound|Outbound|TcpReceiver|Discovery)'
```

### 3. NearDrop debug logs

NearDrop logs to the macOS Unified Logging system. Capture them with:

```bash
log stream --predicate 'process == "NearDrop"' --info --debug
```

For a one-shot dump after the fact:

```bash
log show --predicate 'process == "NearDrop"' --info --debug \
  --last 5m > /tmp/neardrop.log
```

If NearDrop has been built from source with verbose logging, link to
that build's revision in the bug report so the log lines can be mapped
back to source.

### 4. Environment snapshot

Attach a short text file with:

- macOS version (`sw_vers`).
- NearDrop version (from "About NearDrop" or the `.dmg` filename).
- Android device model and OS build (`adb shell getprop ro.build.fingerprint`).
- App build commit (`git rev-parse HEAD` on the branch the APK was built from).
- Wi-Fi SSID, channel, and AP model if known.

### 5. Cross-references for triage

Use the failure mode to point the bug report at the right module:

| Failure mode | Likely module | Source pointer |
|---|---|---|
| Devices never see each other on same VLAN | discovery / mDNS | [`discovery-android/.../Discovery.kt`](../../discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery/Discovery.kt) |
| Connect succeeds, handshake fails before consent UI | UKEY2 handshake | [`core-protocol/.../ukey2/`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/ukey2/) |
| PINs differ across devices on a clean run | UKEY2 `authString` -> PIN derivation | [`core-protocol/.../crypto/pin/PinDerivation.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/crypto/pin/PinDerivation.kt) |
| Consent accepted but transfer aborts immediately | secure-channel framing | [`core-protocol/.../crypto/securemessage/`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/crypto/securemessage/) |
| Mid-transfer corruption / hash mismatch | payload reassembly | [`core-protocol/.../payload/PayloadAssembler.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/payload/PayloadAssembler.kt) |
| Cancel doesn't propagate / partial file left behind | connection lifecycle | [`core-protocol/.../connection/`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/) and [`service-android/.../receiver/ReceiverSession.kt`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/receiver/ReceiverSession.kt) |
| QR-code scan succeeds but transfer never starts | QR endpoint info | [`core-protocol/.../qr/`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/qr/) |
| Receive completes but file is missing from Downloads | MediaStore writer | [`service-android/.../downloads/`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/downloads/) |

## Sign-off

- [ ] All matrix rows above ticked on at least one engineer's hardware.
- [ ] Any failures filed as new issues, each linking back to #29 and
      including the artefacts above.
- [ ] This file updated with the engineer's name, hardware
      (Mac model + Android device), and date once the run passes
      end-to-end:

```
Run log:
- YYYY-MM-DD  <name>  <Mac model + macOS version>  <Android device + OS>
```
