# Interop test runbook: stock Quick Share on Android

This is the manual interoperability test runbook for verifying that
WhenVivoMeetsGoogle (WVMG) can send to and receive from stock **Google
Quick Share** on Android, both on a clean Pixel (GMS) device and on a
Samsung One UI device with Samsung's forked Quick Share UI.

This runbook tracks issue
[#30](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/30) under
the [Phase 1 epic](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/1).

> **Phase 1 scope reminder.** Phase 1 is Wi-Fi LAN parity with NearDrop.
> BLE auto-trigger (the "ping nearby devices" pulse that lights up a
> stock receiver's "Sharing nearby" sheet automatically) is **out of
> scope** and is tracked under Phase 2. In Phase 1, the **only reliable
> path** to start a transfer to a stock Android device is the **QR-code
> path**: WVMG renders a QR code, the peer scans it with their camera,
> and stock Quick Share opens with our endpoint pre-selected. Discovery
> in the other direction (stock Android sends to WVMG) is also Wi-Fi-only
> in Phase 1: WVMG must already be advertising on mDNS for the peer's
> device picker to list us.

---

## Preconditions

### Networking requirements

Phase 1 uses Wi-Fi LAN discovery (mDNS), so both devices **must** be on
the same Wi-Fi network. BLE auto-discovery is Phase 2 and out of scope
for this runbook. The receiver's persistent notification surfaces the
current Wi-Fi SSID (`Receiving on "<SSID>"`) — use it to confirm both
ends match before you start each test.

### Network

- [ ] Both devices are on the **same Wi-Fi SSID and the same VLAN** —
      mDNS does not cross routed subnets.
- [ ] The Wi-Fi network has **mDNS / multicast traffic enabled**. Many
      enterprise / guest networks block multicast and silently break
      Quick Share. Use a phone hotspot if in doubt.
- [ ] Both devices have **Wi-Fi turned on**. Bluetooth is **not**
      required in Phase 1 (BLE pulse is Phase 2).
- [ ] Location permission is granted to Google Play Services on the
      stock device (Quick Share requires it for Wi-Fi scans).

### Devices under test (DUTs)

- [ ] **WVMG device:** Android device running the WVMG debug APK from
      `./gradlew :app:assembleDebug`. `minSdk = 24`, but for this
      runbook prefer Android 12+ to match common test devices.
- [ ] **Stock Pixel peer:** A Pixel device with **clean GMS**, on a
      reasonably recent Android version (Android 13 / 14 / 15
      recommended; record the exact build fingerprint in the test log).
      Google Play services must be up to date — Quick Share rolls out
      via Play services, not via OS images.
- [ ] **Stock Samsung peer:** A Samsung Galaxy device with **One UI 6.0
      or newer** (One UI 6.x = Android 14, One UI 7.x = Android 15).
      Earlier One UI versions shipped Samsung's pre-merger "Quick
      Share" which is a different protocol; only the post-merger
      unified Quick Share is in scope.
- [ ] All devices on the same time zone / wall clock (UKEY2 doesn't
      strictly require synced clocks, but mDNS lease behavior is easier
      to reason about when wall clocks agree).

### Quick Share visibility on the stock peer

Set the stock peer's Quick Share visibility to one of these states for
each test, and record which:

- [ ] **"Everyone" / "Visible to everyone"** — easiest mode for testing.
      On Pixel: Settings → Connected devices → Connection preferences →
      Quick Share → "Everyone" (or the 10-minute toggle).
- [ ] **"Contacts"** — only works if the WVMG endpoint is treated as a
      contact, which it is **not** in Phase 1. Skip.
- [ ] **"Your devices"** — only works if both devices are signed in to
      the same Google account, which WVMG is not. Skip.

### WVMG configuration

- [ ] Receiver `ForegroundService` (`ReceiverForegroundService`) is
      running on the WVMG device — confirm via the persistent receiver
      notification.
- [ ] mDNS advertise is up. The TXT `n` record contains a valid packed
      `EndpointInfo` per
      [`core-protocol/.../endpoint/EndpointInfo.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/endpoint/EndpointInfo.kt).
- [ ] Service type is `_FC9F5ED42C8A._tcp.local.` (see
      [`core-protocol/.../ProtocolInfo.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/ProtocolInfo.kt)).
- [ ] Wi-Fi `MulticastLock` is held (validated via `adb shell dumpsys
      wifi | grep -i multicast` showing the WVMG package as a holder).

---

## Test matrix

Every cell in the matrix must be exercised once per RC build.

| # | Direction        | Peer    | Path used                | Visibility setting on peer | Result |
|---|------------------|---------|--------------------------|----------------------------|--------|
| 1 | WVMG -> Pixel    | Pixel   | QR code (Phase 1)        | Everyone                   |        |
| 2 | WVMG -> Samsung  | Samsung | QR code (Phase 1)        | Everyone                   |        |
| 3 | Pixel -> WVMG    | Pixel   | Pixel device picker      | n/a (sender side picks us) |        |
| 4 | Samsung -> WVMG  | Samsung | Samsung Quick Share UI   | n/a                        |        |

For each cell, run **both** a small file (≤ 1 MB, e.g. a JPEG) and a
large file (≥ 200 MB, e.g. a video). The large-file run is what catches
`IS_PENDING` MediaStore bleed bugs. The small-file run (single-chunk FILE
payload) is the regression guard for the Samsung One UI 7+ two-frame quirk:
if the terminator is fused into the data chunk the receiver silently discards
the attachment and shows "couldn't receive file" — but only on small files
that fit in one chunk, so it was invisible in large-file-only testing.

---

## Procedure

### Test 1 / Test 2: Send file from WVMG to a stock Android phone (QR-code path)

The QR path is the only Phase-1-supported path. Stock Android only
implements QR scanning as the receiver-side entry point — there is no
"manual IP entry" — so the flow below is mandatory.

- [ ] On the **stock peer**: open Settings → Connected devices →
      Connection preferences → Quick Share, and set visibility to
      **Everyone** (or use the temporary "Everyone for 10 minutes"
      toggle if available). Pull down the notification shade and
      confirm Quick Share is enabled.
- [ ] On the **WVMG device**: open the system share sheet for the file
      under test (e.g. long-press a photo in Files, share to WVMG).
      WVMG's `ShareIntentRouter`
      ([`app/.../send/ShareIntentRouter.kt`](../../app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShareIntentRouter.kt))
      should route the intent into `SendActivity`.
- [ ] In `SendActivity`, choose the **"Show QR code"** option. WVMG
      shows a QR rendered by `ShowQrActivity`
      ([`app/.../send/ShowQrActivity.kt`](../../app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShowQrActivity.kt)).
- [ ] On the **stock peer**: open the **system camera app** (or Google
      Lens) and aim at the QR. Stock Quick Share intercepts the
      `https://qsr.gs/...` deep-link and opens the Quick Share receive
      sheet with our endpoint pre-selected.
- [ ] The peer taps **Accept** on its sheet.
- [ ] On the **WVMG device**: the consent / progress UI shows a
      4-digit PIN. The PIN must match what the peer displays.
- [ ] Wait for the transfer to complete. Note total wall-clock seconds.
- [ ] On the peer, locate the received file (typically in
      Downloads / "Quick Share" folder).
- [ ] Compute `sha256sum` of the source file on WVMG and the received
      file on the peer; **they must match**. Use `adb shell sha256sum
      /sdcard/Download/<file>` on each side, or `Termux` on the peer.

#### Things to record

- Android version + build number on each side
- Google Play services version on the peer (Settings → Apps → Google
  Play services → Version)
- Time-to-connect (QR scan → consent dialog visible)
- Time-to-complete (consent accepted → progress 100%)
- Source SHA-256, received SHA-256

#### Samsung-specific checks (Test 2 only)

- [ ] The Samsung peer shows "File received" (not "Couldn't receive
      file"). The latter is the symptom of the One UI 7+ FILE two-frame
      quirk or a missing safe-disconnect handshake; both are fixed in
      PR #108.
- [ ] Run the test with a **small file (≤ 512 KiB, single chunk)** in
      addition to the large-file run. The single-chunk case is what
      catches a fused terminator.
- [ ] In the WVMG logcat, confirm `fsm: safe-disconnect peer
      Disconnection ack=true` (or `fsm: safe-disconnect peer FIN
      observed`) appears after the transfer completes. Absence means
      the drain timed out and the socket may have closed before the
      receiver finished writing.
- [ ] `adb logcat -s WvmgOutbound` on the WVMG device shows
      `fsm: streamOneFile DONE` for each file and
      `fsm: all files streamed, sending Disconnection` before the
      safe-disconnect drain line.

### Test 3 / Test 4: Receive file from stock Android (peer initiates)

- [ ] On the **WVMG device**: confirm the receiver `ForegroundService`
      is running (persistent notification visible).
- [ ] On the **stock peer**: open **Google Files** (or any app with a
      shareable item) → Share → **Quick Share**.
- [ ] In the device picker, scroll until **the WVMG device's display
      name appears**. The name comes from the packed `EndpointInfo`
      (see preconditions). If the device does **not** appear within
      ~15 seconds, see the troubleshooting section below.
- [ ] Pick the WVMG device.
- [ ] On the **WVMG device**: a consent notification appears (handled
      by
      [`service-android/.../consent/ConsentNotification.kt`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/receiver/consent/ConsentNotification.kt)).
      Tap "Accept" — or open the consent activity for the in-app
      experience.
- [ ] Confirm the 4-digit PIN matches between both devices.
- [ ] Wait for the transfer to complete.
- [ ] On the **WVMG device**, the file lands under **Downloads** via
      `MediaStoreDownloadsFactory`
      ([`service-android/.../downloads`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/downloads)).
- [ ] `sha256sum` the source on the peer and the received file on
      WVMG. They **must match**.

#### Things to record

- Whether the WVMG device showed up in the picker on the **first**
  attempt (no need to toggle Wi-Fi, no need to restart Quick Share).
- The `IS_PENDING` state of the received MediaStore entry: it must be
  cleared (`IS_PENDING = 0`) by the time the transfer completes.

---

## Common Quick Share quirks

These are real-world quirks every tester should know about. None of
them are bugs in WVMG; they are properties of the stock Quick Share
UX. If a test step fails, walk this list before assuming a WVMG bug.

### Pixel / GMS quirks

- **"Visible to everyone" auto-disables after 10 minutes** on recent
  Android versions. If discovery worked once and now fails, re-enable
  it.
- **Quick Share is gated behind GMS being up to date.** A Pixel that
  has gone offline for weeks may be running an old Play services build
  whose Quick Share isn't compatible with current EndpointInfo
  format. Update via Play Store before retesting.
- **Battery saver disables Wi-Fi scans.** Ensure both devices have
  battery saver off.
- **Private DNS on "Strict" with a custom hostname** can interfere with
  mDNS resolution on some Pixel builds. Set Private DNS to "Automatic"
  for testing.
- The system camera's QR detection on Pixel sometimes refuses
  `https://qsr.gs/...` if Lens is disabled. Use the dedicated **Google
  Lens** app as a fallback.

### Samsung / One UI quirks

- **Samsung Quick Share has a "hidden" / "phone" / "no one" visibility
  layer** in the Quick Share quick-tile that is **separate** from the
  Android Settings page. Both must say "Everyone" — set them in the
  Quick Share quick-tile **and** in Settings.
- **One UI < 6.0** ships an older Samsung Quick Share that does **not**
  share a protocol with Google Quick Share. Verify the One UI version
  before debugging anything else.
- **Samsung's Galaxy Store auto-update** has been observed to roll
  Samsung Quick Share back to a Samsung-only build after a factory
  reset. Confirm the active Quick Share app is the Google one
  (`com.google.android.gms` / `com.google.android.apps.nbu.files` flow,
  not `com.samsung.android.app.sharelive`).
- Samsung devices aggressively kill background services. The receiver
  side of WVMG is a foreground service, but the Samsung peer's Quick
  Share may not be — if you can't see the WVMG device on Samsung's
  picker, **toggle Quick Share off and back on** to force a fresh
  scan.
- Samsung Knox DeX or Secure Folder profiles may have a separate Quick
  Share visibility setting; do testing on the main user profile only.
- **One UI 7+ requires a separate empty `LAST_CHUNK` terminator for FILE
  payloads.** If a transfer appears to complete (safe-disconnect ack fires,
  progress reaches 100%) but the file is not on disk and the receiver shows
  "couldn't receive file", this is the symptom. The fix (`encodeFilePayload`
  emitting a zero-byte terminator frame after all data chunks) landed in
  PR #108. Verify by sending an ≤ 512 KiB file (single data chunk) and
  confirming `sha256sum` matches.
- **One UI 7+ enforces the safe-disconnect handshake.** We advertise
  `safe_to_disconnect_version = 1` in `ConnectionResponseFrame`, so every
  outbound `DisconnectionFrame` must set `request_safe_to_disconnect = true`
  and we must wait for the peer's ack (or FIN) before closing the socket.
  An abrupt FIN while payloads are in the receiver's socket buffer causes
  "couldn't receive file" even though the bytes were already transmitted.
  The orchestrator drains for up to 1500 ms; the logcat line to look for is
  `fsm: safe-disconnect peer Disconnection ack=true`.
- **One UI 8.0.5 requires non-zero `FileMetadata.id` and
  `IntroductionFrame.use_case = NEARBY_SHARE`.** Leaving `id` at the proto
  default (0) makes Samsung silently discard the announced attachment with
  only a `NULL_MESSAGE` line at the medium layer. Both fields are set by
  `buildIntroductionFrame` since PR #108.

### Both

- Quick Share **caches the last-seen device list** for a few seconds.
  If you change WVMG's display name, restart Quick Share on the peer
  before re-checking.
- Quick Share is **picky about EndpointInfo length**. Names longer
  than ~63 UTF-8 bytes get truncated by some peers. Keep the WVMG
  device name short during interop.
- Some peers close the connection if the consent dialog isn't
  responded to within ~30 seconds. Don't let the WVMG device's screen
  lock during the consent step.

---

## What to log if a step fails

When any step above fails, capture **all** of the following before
filing a follow-up issue. The combination of these logs is what makes
Quick Share interop bugs reproducible.

### From the WVMG device

```bash
# Service + protocol logs from WVMG.
adb logcat -d \
    'wvmg:V' \
    'ReceiverForegroundService:V' \
    'ReceiverSession:V' \
    'OutboundConnection:V' \
    'InboundConnection:V' \
    'Discovery:V' \
    'QuickShareMdns:V' \
    'JmDNS:V' \
    'NsdManager:V' \
    'AndroidRuntime:E' \
    '*:S' > wvmg-logcat.txt

# Bug report (system-wide). Useful for Wi-Fi state, multicast lock holders.
adb bugreport wvmg-bugreport.zip

# Live multicast lock holders.
adb shell dumpsys wifi | grep -A2 -i multicast > wvmg-multicast.txt

# mDNS state visible to the OS.
adb shell dumpsys nsd > wvmg-nsd.txt
```

### From the stock peer

- Open Settings → System → Developer options → Bug report → Interactive
  report. Save the bug report and attach.
- For Pixel: Settings → Apps → Google Play services → "About this app"
  screenshot (so the Play services build is recorded).
- For Samsung: Settings → Apps → Quick Share → "About" screenshot. If
  Samsung's "Quick Share" appears to be missing, check Galaxy Store
  for updates and screenshot the version it lists.

### Network capture (optional but extremely useful)

- [ ] Run a Wi-Fi packet capture on a **third device** (laptop in
      monitor mode, or a router with `tcpdump`) for the duration of
      the failed transfer. Save as `failed-transfer.pcap`. Filter for
      `mdns or port 8080-65535` once captured.
- [ ] Note the timestamp of the failed step in the WVMG logcat to
      correlate against the pcap.

### File integrity

- [ ] If the file transferred but the SHA-256 mismatches, save **both**
      the source and the corrupted destination. Do **not** delete the
      destination — it is the primary evidence.

---

## Acceptance criteria for issue #30

- [ ] All 4 cells of the test matrix pass (small + large file each) on
      the current Phase 1 RC build.
- [ ] SHA-256 hashes match in every direction.
- [ ] Any new platform-specific quirks discovered get added to
      "Common Quick Share quirks" above and filed as follow-up issues
      (with `phase-1` or `phase-2` labels as appropriate).

---

## Related project files

- mDNS / discovery wrapper:
  [`discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery`](../../discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery)
- Receiver foreground service + consent UI:
  [`service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/receiver`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/receiver)
- MediaStore Downloads writer:
  [`service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/downloads`](../../service-android/src/main/kotlin/io/github/kyujincho/wvmg/service/downloads)
- Outbound (sender) connection driver:
  [`core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/OutboundConnection.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/OutboundConnection.kt)
- Inbound (receiver) connection:
  [`core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/InboundConnection.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/connection/InboundConnection.kt)
- `EndpointInfo` packed binary descriptor (interop-critical):
  [`core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/endpoint/EndpointInfo.kt`](../../core-protocol/src/main/kotlin/io/github/kyujincho/wvmg/protocol/endpoint/EndpointInfo.kt)
- QR-code generation (Phase 1 trigger):
  [`app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShowQrActivity.kt`](../../app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShowQrActivity.kt)
- Share-intent router:
  [`app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShareIntentRouter.kt`](../../app/src/main/kotlin/io/github/kyujincho/wvmg/send/ShareIntentRouter.kt)
- Companion runbook for NearDrop / macOS interop (issue #29):
  [`docs/testing/interop-neardrop-macos.md`](./interop-neardrop-macos.md)
