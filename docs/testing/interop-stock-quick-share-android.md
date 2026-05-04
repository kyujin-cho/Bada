# Interop test runbook: stock Quick Share on Android

This is the manual interoperability test runbook for verifying that
LibreDrop (LibreDrop) can send to and receive from stock **Google
Quick Share** on Android, both on a clean Pixel (GMS) device and on a
Samsung One UI device with Samsung's forked Quick Share UI.

This runbook tracks issue
[#30](https://github.com/kyujin-cho/LibreDrop/issues/30) under
the [Phase 1 epic](https://github.com/kyujin-cho/LibreDrop/issues/1).

> **Current scope note.** Phase 1 originally shipped as Wi-Fi LAN parity
> with NearDrop, and the QR path below remains the lowest-risk fallback.
> Issue #137 adds a sender-side non-LAN bootstrap path: LibreDrop can now
> discover stock peers through Bluetooth-adjacent discovery surfaces and
> start the initial control channel without requiring the same Wi-Fi LAN.
> Issue #145 adds the reciprocal receiver path for stock Samsung senders:
> LibreDrop can be discovered through the stock Quick Share picker, accept the
> initial BLE GATT socket, and hand the transfer off to Wi-Fi Direct.
> Shared-LAN mDNS is still the baseline regression path, and stock sender
> -> LibreDrop receiver regression should now be exercised both on a shared
> LAN and on the BLE GATT -> Wi-Fi Direct path when hardware supports it.

---

## Preconditions

### Networking requirements

Two network topologies matter now:

- **Shared-LAN regression path:** both devices on the same Wi-Fi network
  so mDNS discovery can work exactly as it did before issue #137.
- **Off-LAN sender path:** LibreDrop sender and the stock receiver are on
  different Wi-Fi networks, on a client-isolated Wi-Fi, or otherwise
  unable to reach each other by LAN mDNS alone. Bluetooth must stay on
  so the initial control channel can bootstrap, and the subsequent
  bandwidth upgrade may or may not move to Wi-Fi depending on what the
  peer offers.

### Network

- [ ] For the **shared-LAN regression** cells below, both devices are on
      the **same Wi-Fi SSID and the same VLAN** — mDNS does not cross
      routed subnets.
- [ ] For the **off-LAN sender** cells below, deliberately place the
      devices on different Wi-Fi networks (or an AP with client
      isolation) and record the topology in the test log.
- [ ] The Wi-Fi network has **mDNS / multicast traffic enabled**. Many
      enterprise / guest networks block multicast and silently break
      Quick Share. Use a phone hotspot if in doubt.
- [ ] Both devices have **Wi-Fi turned on**.
- [ ] For the **off-LAN sender** cells, both devices also have
      **Bluetooth turned on**.
- [ ] For the **stock sender -> LibreDrop receiver BLE GATT** cells, Bluetooth
      is on for discovery/bootstrap, and Wi-Fi Direct is available on
      both devices for the bandwidth upgrade. The sender's infrastructure
      Wi-Fi may be off; the transfer should still complete after the
      Wi-Fi Direct handoff.
- [ ] Location permission is granted to Google Play Services on the
      stock device (Quick Share requires it for Wi-Fi scans).

### Devices under test (DUTs)

- [ ] **LibreDrop device:** Android device running the LibreDrop debug APK from
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

### Receiver name under test

- [ ] Record which receiver-name mode LibreDrop is using for this run:
      **custom advertised name** from the launcher settings, or the
      **default fallback chain** (`Settings.Global.DEVICE_NAME` on
      API 25+, then Bluetooth adapter name when safely readable, then
      `Build.MODEL`, then the app label).
- [ ] If you are exercising the custom-name path, keep the configured
      value within LibreDrop's documented 19-byte UTF-8 budget and note the
      exact string in the test log.

### Quick Share visibility on the stock peer

Set the stock peer's Quick Share visibility to one of these states for
each test, and record which:

- [ ] **"Everyone" / "Visible to everyone"** — easiest mode for testing.
      On Pixel: Settings → Connected devices → Connection preferences →
      Quick Share → "Everyone" (or the 10-minute toggle).
- [ ] **"Contacts"** — only works if the LibreDrop endpoint is treated as a
      contact, which it is **not** in Phase 1. Skip.
- [ ] **"Your devices"** — only works if both devices are signed in to
      the same Google account, which LibreDrop is not. Skip.

### LibreDrop configuration

- [ ] Receiver `ForegroundService` (`ReceiverForegroundService`) is
      running on the LibreDrop device — confirm via the persistent receiver
      notification.
- [ ] mDNS advertise is up. The TXT `n` record contains a valid packed
      `EndpointInfo` per
      [`core-protocol/.../endpoint/EndpointInfo.kt`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/endpoint/EndpointInfo.kt).
- [ ] Service type is `_FC9F5ED42C8A._tcp.local.` (see
      [`core-protocol/.../ProtocolInfo.kt`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/ProtocolInfo.kt)).
- [ ] Wi-Fi `MulticastLock` is held (validated via `adb shell dumpsys
      wifi | grep -i multicast` showing the LibreDrop package as a holder).

---

## Test matrix

Every cell in the matrix must be exercised once per RC build.

| # | Direction        | Peer    | Path used                                | Visibility setting on peer | Result |
|---|------------------|---------|------------------------------------------|----------------------------|--------|
| 1 | LibreDrop -> Pixel    | Pixel   | Shared-LAN picker / mDNS regression      | Everyone                   |        |
| 2 | LibreDrop -> Pixel    | Pixel   | Off-LAN sender bootstrap (#137)          | Everyone                   |        |
| 3 | LibreDrop -> Samsung  | Samsung | Shared-LAN picker / mDNS regression      | Everyone                   |        |
| 4 | LibreDrop -> Samsung  | Samsung | Off-LAN sender bootstrap (#137)          | Everyone                   |        |
| 5 | Pixel -> LibreDrop    | Pixel   | Pixel device picker                      | n/a (sender side picks us) |        |
| 6 | Samsung -> LibreDrop  | Samsung | Samsung Quick Share UI / BLE GATT -> Wi-Fi Direct | n/a                        |        |

For each cell, run **both** a small file (≤ 1 MB, e.g. a JPEG) and a
large file (≥ 200 MB, e.g. a video). The large-file run is what catches
`IS_PENDING` MediaStore bleed bugs. The small-file run (single-chunk FILE
payload) is the regression guard for the Samsung One UI 7+ two-frame quirk:
if the terminator is fused into the data chunk the receiver silently discards
the attachment and shows "couldn't receive file" — but only on small files
that fit in one chunk, so it was invisible in large-file-only testing.

---

## Procedure

### Test 1 / Test 3: Send file from LibreDrop to a stock Android phone (shared-LAN regression)

- [ ] Put both devices on the **same Wi-Fi SSID / VLAN**.
- [ ] On the **LibreDrop device**: open the system share sheet for the file
      under test and route into `SendActivity`.
- [ ] Wait for the peer row to appear in the picker. Record the
      `picked target:` log line from `adb logcat -s LibreDropOutbound` after
      selection; for the shared-LAN regression it should show
      `route=lan=<ip>:<port>`.
- [ ] Select the peer row, compare the 4-digit PIN on both sides, and
      complete the transfer.
- [ ] Verify the received file and SHA-256 hash on the peer.

### Test 2 / Test 4: Send file from LibreDrop to a stock Android phone (off-LAN sender bootstrap)

This is the issue #137 checklist. It validates that the sender can
discover and start the session without relying on same-LAN mDNS.

- [ ] Put the devices on **different Wi-Fi networks** (or on a network
      with client isolation) so a pure LAN dial would fail.
- [ ] On the **stock peer**: set Quick Share visibility to **Everyone**
      and confirm **Bluetooth is on**.
- [ ] On the **LibreDrop device**: open the system share sheet for the file
      under test and route into `SendActivity`.
- [ ] Wait for the peer row to appear in the picker. A BLE-only stock
      receiver with no L2CAP PSM should stay disabled until LibreDrop verifies
      the Nearby GATT socket service, then surface as a direct
      `BLE GATT <mac>` route.
- [ ] Capture `adb logcat -s LibreDropOutbound LibreDropBtScan LibreDropBleFastScan LibreDropBleGattClient`
      while the picker is open. Record the discovery surfaces reported
      in the `picked target:` line's `mediums=[...]` field.
- [ ] Select the peer row and record the initial control route from the
      same `picked target:` line. For the stock BLE-only bootstrap path it
      should show `route=ble-gatt=<mac>` and
      `bootstrap={selected=ble-gatt rejected=[wifi-lan=missing, ble-l2cap=peer-psm-missing]}`.
- [ ] Confirm the 4-digit PIN matches on both devices.
- [ ] Wait for the transfer to complete and verify the received file's
      SHA-256 hash on the peer.
- [ ] Record whether the connection stayed on the initial medium or
      upgraded. Look for `step 1: initial transport open medium=...`
      and any `medium-upgrade:` lines in `LibreDropOutbound`.
- [ ] Immediately rerun the same peer pair on a **shared LAN** and
      confirm the regression path still chooses `route=lan=<ip>:<port>`.

### Test 5 / Test 6: Receive file from a stock Android phone

Use this checklist for the stock sender -> LibreDrop receiver path. It covers
the shared-LAN regression and the BLE GATT -> Wi-Fi Direct receiver path.

- [ ] Install the LibreDrop debug APK on the receiver and start the receiver
      foreground service. If the install stalls on vivo for more than 5 s,
      clear the vendor Security Care consent prompt and continue.
- [ ] Open LibreDrop on the receiver and enable **Always Visible**.
- [ ] Capture receiver logs:
      `adb logcat -s LibreDropBleGatt LibreDropBleAdv LibreDropMdnsGate LibreDropDiscovery LibreDropReceive LibreDropInbound LibreDropWifiDirect`
- [ ] Capture Samsung sender logs:
      `adb logcat -s NearbySharing ShareLive NearbyConnections NearbyMediums BtGatt BluetoothGatt WifiP2pService WifiDirectController`
- [ ] On the stock sender, share a small file through the Samsung Quick
      Share UI and select the LibreDrop row.
- [ ] Confirm the BLE GATT bootstrap reaches LibreDrop. Receiver logs should
      show a CCCD write on `00000100-0004-1000-8000-001a11000102`, a
      write on `00000100-0004-1000-8000-001a11000101`, and
      `BLE socket introduction accepted`.
- [ ] Confirm the transfer upgrades to Wi-Fi Direct. Receiver logs should
      show `WifiP2pManager.createGroup onSuccess`, a `Wi-Fi Direct group
      ready` line, `medium-upgrade: server offering WIFI_DIRECT`, and
      `medium-upgrade: server completed WIFI_DIRECT`.
- [ ] On the sender, confirm the channel changes from encrypted BLE to
      encrypted Wi-Fi Direct, then the Quick Share UI reports **Sent**.
- [ ] On the receiver, accept consent, wait for `Completed`, and verify
      the received file's SHA-256 hash matches the sender source.

#### Validated BLE GATT -> Wi-Fi Direct receiver result (#145)

2026-05-02 actual-device run:

- **Sender:** Galaxy S26 Ultra (`SM-S948N`) using Samsung ShareLive /
  stock Quick Share.
- **Receiver:** vivo X300 Ultra (`V2547A`) running LibreDrop debug.
- **Topology:** Galaxy Wi-Fi disabled, Bluetooth on; receiver formed the
  Wi-Fi Direct group after BLE GATT bootstrap.
- **Receiver evidence:** LibreDrop published the regular `0xFEF3` slot service
  and the Nearby second-profile socket service
  `0000fef3-0004-1000-8000-001a11000100`; Samsung wrote the socket CCCD
  and TO-peripheral characteristic, LibreDrop logged
  `BLE socket using raw Nearby stream`, then completed `WIFI_DIRECT`.
- **Sender evidence:** Samsung logged a successful connection to
  `DIRECT-pX-LibreDrop-PlRuIQ` and replaced the endpoint channel from
  `ENCRYPTED_BLE` to `ENCRYPTED_WIFI_DIRECT`; ShareLive reported `Sent`.
- **Payload evidence:** `issue-145-galaxy-to-vivo-repro.txt` was written
  to `/sdcard/Download/LibreDrop/` with 52 bytes and SHA-256
  `95e6c8f9462c24715a79e956b92956d7ce4f0677a0f8cf37284ce1a145e8e648`,
  matching the Galaxy source file.

#### BLE-only / no-shared-LAN sender repro (#143)

Use this focused checklist when the stock receiver is visible only
through BLE advertisements and does not expose a direct LAN or peer-PSM
route.

- [ ] Connect two adb devices and export serials:
      `export VIVO_SERIAL=<libredrop-sender-serial>`
      `export STOCK_SERIAL=<stock-receiver-serial>`
- [ ] Confirm the devices do **not** share a usable LAN route:
      `adb -s "$VIVO_SERIAL" shell ip route`
      `adb -s "$STOCK_SERIAL" shell ip route`
- [ ] Open the stock Quick Share receive UI on the receiver and keep it
      visible.
- [ ] Start `adb -s "$VIVO_SERIAL" logcat -c` and then capture:
      `adb -s "$VIVO_SERIAL" logcat -s LibreDropOutbound LibreDropDiscovery LibreDropBleFastScan LibreDropBleGattClient LibreDropBleL2cap`
- [ ] Launch LibreDrop's send flow on the vivo and wait for the receiver row.
      The discovery log should first show an observation-only BLE peer,
      then either `slot-read service discovered socket=true ...` or
      `BLE GATT socket service verified ...` once the stock receiver's
      Nearby GATT socket service is confirmed.
- [ ] Tap the receiver row. Record:
      `picked target: ... route=ble-gatt=<mac> ... bootstrap={selected=ble-gatt ...}`
- [ ] Confirm LibreDrop then logs `BLE GATT initial connect ready`, followed by
      `step 1: initial transport open medium=BLE` and
      `step 2: UKEY2 client handshake complete`.
- [ ] Verify there is **no** `ConnectionRequest` / UKEY2 hang on a
      dead header-only path. Header-only BLE/GATT observations must stay
      disabled unless the slot probe yields an advertisement or verifies
      the socket service for a visible receiver.
- [ ] Continue through PIN confirmation and verify the received file hash
      on the stock receiver.

### Legacy fallback: QR-code path

Keep this path around as a manual fallback when the nearby-picker path is
under active debugging. Stock Android still implements QR scanning as a
receiver-side entry point, and it remains useful for isolating
discovery/bootstrap failures from later protocol failures.

- [ ] On the **stock peer**: open Settings → Connected devices →
      Connection preferences → Quick Share, and set visibility to
      **Everyone** (or use the temporary "Everyone for 10 minutes"
      toggle if available). Pull down the notification shade and
      confirm Quick Share is enabled.
- [ ] On the **LibreDrop device**: open the system share sheet for the file
      under test (e.g. long-press a photo in Files, share to LibreDrop).
      LibreDrop's `ShareIntentRouter`
      ([`app/.../send/ShareIntentRouter.kt`](../../app/src/main/kotlin/dev/bluehouse/libredrop/send/ShareIntentRouter.kt))
      should route the intent into `SendActivity`.
- [ ] In `SendActivity`, choose the **"Show QR code"** option. LibreDrop
      shows a QR rendered by `ShowQrActivity`
      ([`app/.../send/ShowQrActivity.kt`](../../app/src/main/kotlin/dev/bluehouse/libredrop/send/ShowQrActivity.kt)).
- [ ] On the **stock peer**: open the **system camera app** (or Google
      Lens) and aim at the QR. Stock Quick Share intercepts the
      `https://qsr.gs/...` deep-link and opens the Quick Share receive
      sheet with our endpoint pre-selected.
- [ ] The peer taps **Accept** on its sheet.
- [ ] On the **LibreDrop device**: the consent / progress UI shows a
      4-digit PIN. The PIN must match what the peer displays.
- [ ] Wait for the transfer to complete. Note total wall-clock seconds.
- [ ] On the peer, locate the received file (typically in
      Downloads / "Quick Share" folder).
- [ ] Compute `sha256sum` of the source file on LibreDrop and the received
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
- [ ] In the LibreDrop logcat, confirm `fsm: safe-disconnect peer
      Disconnection ack=true` (or `fsm: safe-disconnect peer FIN
      observed`) appears after the transfer completes. Absence means
      the drain timed out and the socket may have closed before the
      receiver finished writing.
- [ ] `adb logcat -s LibreDropOutbound` on the LibreDrop device shows
      `fsm: streamOneFile DONE` for each file and
      `fsm: all files streamed, sending Disconnection` before the
      safe-disconnect drain line.

### Test 3 / Test 4: Receive file from stock Android (peer initiates)

- [ ] On the **LibreDrop device**: confirm the receiver `ForegroundService`
      is running (persistent notification visible).
- [ ] On the **stock peer**: open **Google Files** (or any app with a
      shareable item) → Share → **Quick Share**.
- [ ] In the device picker, scroll until **the LibreDrop device's display
      name appears**. The name comes from the packed `EndpointInfo`
      (see preconditions). If the device does **not** appear within
      ~15 seconds, see the troubleshooting section below.
- [ ] Verify the displayed name matches the selected receiver-name mode:
      the launcher's custom advertised name when one is set, otherwise
      the resolved Android default name for the LibreDrop device.
- [ ] Pick the LibreDrop device.
- [ ] On the **LibreDrop device**: consent is surfaced in one of two ways
      depending on whether LibreDrop is foregrounded (#151):
      - **App in foreground** — the in-app consent modal opens automatically
        over whichever LibreDrop activity is on screen. Tap "Accept" or
        "Reject" directly in the modal.
      - **App in background** — a heads-up notification appears (handled by
        [`service-android/.../consent/ConsentNotification.kt`](../../service-android/src/main/kotlin/dev/bluehouse/libredrop/service/receiver/consent/ConsentNotification.kt)).
        Tap "Accept" in the notification, or open LibreDrop to have the
        coordinator automatically switch to the in-app modal.
      If you background the app while the modal is up, the coordinator
      cancels the modal and raises the notification so you can resume from
      the shade. Foregrounding while the notification is pending cancels the
      notification and launches the modal.
- [ ] Confirm the 4-digit PIN matches between both devices.
- [ ] Wait for the transfer to complete.
- [ ] On the **LibreDrop device**, the file lands under **Downloads** via
      `MediaStoreDownloadsFactory`
      ([`service-android/.../downloads`](../../service-android/src/main/kotlin/dev/bluehouse/libredrop/service/downloads)).
- [ ] `sha256sum` the source on the peer and the received file on
      LibreDrop. They **must match**.

#### Things to record

- Whether the LibreDrop device showed up in the picker on the **first**
  attempt (no need to toggle Wi-Fi, no need to restart Quick Share).
- The `IS_PENDING` state of the received MediaStore entry: it must be
  cleared (`IS_PENDING = 0`) by the time the transfer completes.

---

## Common Quick Share quirks

These are real-world quirks every tester should know about. None of
them are bugs in LibreDrop; they are properties of the stock Quick Share
UX. If a test step fails, walk this list before assuming a LibreDrop bug.

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
  side of LibreDrop is a foreground service, but the Samsung peer's Quick
  Share may not be — if you can't see the LibreDrop device on Samsung's
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
  If you change LibreDrop's display name, restart Quick Share on the peer
  before re-checking.
- Quick Share is **picky about EndpointInfo length**. Names longer
  than LibreDrop's 19-byte UTF-8 receiver-name budget are now clamped before
  advertisement. BLE DCT secondary hints may truncate even further to a
  shorter prefix, so keep test names short and compare against the
  canonical mDNS / share-sheet label.
- Some peers close the connection if the consent dialog isn't
  responded to within ~30 seconds. Don't let the LibreDrop device's screen
  lock during the consent step.

---

## What to log if a step fails

When any step above fails, capture **all** of the following before
filing a follow-up issue. The combination of these logs is what makes
Quick Share interop bugs reproducible.

### From the LibreDrop device

```bash
# Service + protocol logs from LibreDrop.
adb logcat -d \
    'libredrop:V' \
    'ReceiverForegroundService:V' \
    'ReceiverSession:V' \
    'OutboundConnection:V' \
    'InboundConnection:V' \
    'Discovery:V' \
    'QuickShareMdns:V' \
    'JmDNS:V' \
    'NsdManager:V' \
    'AndroidRuntime:E' \
    '*:S' > libredrop-logcat.txt

# Bug report (system-wide). Useful for Wi-Fi state, multicast lock holders.
adb bugreport libredrop-bugreport.zip

# Live multicast lock holders.
adb shell dumpsys wifi | grep -A2 -i multicast > libredrop-multicast.txt

# mDNS state visible to the OS.
adb shell dumpsys nsd > libredrop-nsd.txt
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
- [ ] Note the timestamp of the failed step in the LibreDrop logcat to
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
  [`discovery-android/src/main/kotlin/dev/bluehouse/libredrop/discovery`](../../discovery-android/src/main/kotlin/dev/bluehouse/libredrop/discovery)
- Receiver foreground service + consent UI:
  [`service-android/src/main/kotlin/dev/bluehouse/libredrop/service/receiver`](../../service-android/src/main/kotlin/dev/bluehouse/libredrop/service/receiver)
- MediaStore Downloads writer:
  [`service-android/src/main/kotlin/dev/bluehouse/libredrop/service/downloads`](../../service-android/src/main/kotlin/dev/bluehouse/libredrop/service/downloads)
- Outbound (sender) connection driver:
  [`core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/connection/OutboundConnection.kt`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/connection/OutboundConnection.kt)
- Inbound (receiver) connection:
  [`core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/connection/InboundConnection.kt`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/connection/InboundConnection.kt)
- `EndpointInfo` packed binary descriptor (interop-critical):
  [`core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/endpoint/EndpointInfo.kt`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/endpoint/EndpointInfo.kt)
- QR-code generation (Phase 1 trigger):
  [`app/src/main/kotlin/dev/bluehouse/libredrop/send/ShowQrActivity.kt`](../../app/src/main/kotlin/dev/bluehouse/libredrop/send/ShowQrActivity.kt)
- Share-intent router:
  [`app/src/main/kotlin/dev/bluehouse/libredrop/send/ShareIntentRouter.kt`](../../app/src/main/kotlin/dev/bluehouse/libredrop/send/ShareIntentRouter.kt)
- Companion runbook for NearDrop / macOS interop (issue #29):
  [`docs/testing/interop-neardrop-macos.md`](./interop-neardrop-macos.md)
