# Interop test: stock Quick Share BLE auto-pop-up

This is a manual interoperability test runbook for verifying that
LibreDrop's Phase 2 BLE service-data pulse triggers the
stock Android Quick Share "Sharing nearby" notification automatically,
exactly the way a stock Quick Share sender would.

This runbook tracks issue
[#36](https://github.com/kyujin-cho/LibreDrop/issues/36) under
the [Phase 2 epic](https://github.com/kyujin-cho/LibreDrop/issues/2).

> **Phase 2 scope reminder.** Phase 2 adds BLE auto-discovery on top of
> Phase 1's Wi-Fi LAN parity with NearDrop. The whole point of Phase 2
> is to make our app's sender-side BLE pulse light up a nearby stock
> Quick Share receiver's "Sharing nearby" sheet **before** the user
> ever scans a QR code. The actual file transfer still rides on the
> Phase 1 Wi-Fi LAN path; BLE only carries the wake-up advertisement.
> Apple-side interop (AirDrop, AWDL) remains explicitly out of scope.

---

## What is being verified

Three independent code paths cooperate to produce the auto-pop-up:

1. **Sender BLE advertise** —
   [`BleAdvertiser`](../../discovery-android/src/main/kotlin/dev/bluehouse/bada/discovery/ble/BleAdvertiser.kt)
   broadcasts the 24-byte service-data payload built by
   [`BleAdvertisePayload`](../../discovery-android/src/main/kotlin/dev/bluehouse/bada/discovery/ble/BleAdvertisePayload.kt)
   under the Quick Share service UUID `0000fe2c-0000-1000-8000-00805f9b34fb`.
2. **Stock receiver BLE scan + pop-up** — Google Play services on the
   peer device sees our advertisement, recognises the service UUID, and
   surfaces the "Sharing nearby" / "Device sharing nearby" notification
   that opens the Quick Share receive sheet.
3. **Wi-Fi LAN follow-up** — once the receiver acknowledges the pulse
   it enables its mDNS responder and our app discovers it via
   `_FC9F5ED42C8A._tcp.local.`. From there the connection follows the
   Phase 1 wire spec (UKEY2 → Sharing.Nearby.Frame → payload transfer).

Phase 2 only owns step 1. This runbook measures step 2 (the receiver's
visible reaction) and uses step 3 to confirm the end-to-end path is
intact when Wi-Fi is available.

---

## Preconditions

### Networking requirements

The BLE pulse itself does **not** require Wi-Fi — it is a pure
Bluetooth-layer advertisement. The downstream file transfer still needs
both devices on the same Wi-Fi LAN (mDNS does not cross routed subnets,
and BLE is not a transport for the payload). Two of the four cells in
the matrix below deliberately exercise the BLE-pulse-only state.

### Devices under test (DUTs)

- [ ] **LibreDrop sender:** Android device running the LibreDrop debug APK from
      `./gradlew :app:assembleDebug`. `minSdk = 24`, but Phase 2 BLE
      advertise requires API 26+ in practice (older platforms lack
      `BluetoothLeAdvertiser`); for this runbook prefer Android 12+.
- [ ] **Stock Pixel peer:** A Pixel device with **clean GMS**, on a
      reasonably recent Android version (Android 13 / 14 / 15
      recommended; record the exact build fingerprint). Google Play
      services must be up to date — Quick Share rolls out via Play
      services, not via OS images.
- [ ] **Stock Samsung peer:** A Samsung Galaxy device with **One UI 6.0
      or newer** (One UI 6.x = Android 14, One UI 7.x = Android 15).
      Earlier One UI versions ship Samsung's pre-merger "Quick Share"
      which is a different protocol; only the post-merger unified
      Quick Share is in scope.

### Sender configuration (LibreDrop)

- [ ] BLE permissions granted: `BLUETOOTH_ADVERTISE` on API 31+ (or the
      legacy install-time `BLUETOOTH` / `BLUETOOTH_ADMIN` on API 24–30).
      Onboarding requests this in Phase 2 (#31). Confirm with:
      ```bash
      adb shell dumpsys package dev.bluehouse.bada.debug \
          | grep -E 'BLUETOOTH_ADVERTISE|BLUETOOTH_SCAN'
      ```
- [ ] Bluetooth is **on** on the sender. The advertiser returns `null`
      and silently falls back to mDNS-only when Bluetooth is off — that
      would invalidate this test.
- [ ] The device actually supports BLE peripheral mode. Check with:
      ```bash
      adb shell dumpsys bluetooth_manager | grep -i 'multipleAdv\|leAdvert'
      ```
      If `BluetoothLeAdvertiser` is `null` on this hardware, this whole
      runbook is N/A on this device — skip and use a different sender.
- [ ] No other app is currently using the BLE advertising slot — stock
      Quick Share and Find My Device can occupy the legacy advertising
      PDU. Force-stop those before testing if you see
      `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` in logcat.

### Receiver configuration (stock peer)

- [ ] **Quick Share visibility** set to **"Everyone"** for each test.
      On Pixel: Settings → Connected devices → Connection preferences →
      Quick Share → "Everyone" (or the 10-minute toggle).
      On Samsung: also enable "Everyone" in the Quick Share quick-tile —
      One UI exposes a *separate* visibility layer there that overrides
      the Settings page (see
      [`interop-stock-quick-share-android.md`](interop-stock-quick-share-android.md#samsung--one-ui-quirks)).
- [ ] **Bluetooth** is on. Required even if the receiver is mobile-data
      only — BLE is the trigger.
- [ ] **Location services** are on. Stock Quick Share's BLE-scan path
      requires location to be enabled at the OS level on most builds.
- [ ] Battery saver / data saver disabled. Aggressive Doze can suppress
      Quick Share's background scan and mask a real bug.
- [ ] Google Play services up to date. A stale Play services build can
      ship a different Quick Share BLE filter than the current one.

### Quick Share visibility caveat (mDNS hidden bit)

The mDNS visibility bit on our endpoint is always 1, even when the user
has flipped a "hidden" toggle in LibreDrop — the Everyone-vs-Contacts choice
is enforced during the Quick Share negotiation, not at the mDNS layer.
This is why the stock receiver's pop-up never relies on the LibreDrop
endpoint being mDNS-discoverable first. See `CLAUDE.md` →
"Quick Share interop quirks worth knowing".

---

## Test matrix

Every cell in this matrix must be exercised once per Phase 2 RC build.
The acceptance criterion below is gated on cells **1** and **2**; cells
**3** and **4** are graceful-degradation checks documented for future
debugging.

| # | Sender | Peer    | Peer Wi-Fi | Peer screen | Expected pop-up | Expected mDNS follow-up                              |
|---|--------|---------|------------|-------------|-----------------|------------------------------------------------------|
| 1 | LibreDrop   | Pixel   | On (same Wi-Fi as sender) | On  | Yes, within 5 s | Yes — receiver's mDNS visible from sender within ~5 s |
| 2 | LibreDrop   | Samsung | On (same Wi-Fi as sender) | On  | Yes, within 5 s | Yes — receiver's mDNS visible from sender within ~5 s |
| 3 | LibreDrop   | Pixel or Samsung | **Off, mobile data only** | On  | Yes, within 5 s | **No — mDNS discovery fails (acceptable Phase 2 outcome)** |
| 4 | LibreDrop   | Pixel or Samsung | On (same Wi-Fi as sender) | **Off** | Yes, within 5 s | Yes — receiver's mDNS visible from sender within ~5 s |

For cell 3 the user-visible expectation is: pop-up appears, user taps
"Accept", Quick Share UI then shows a "couldn't connect" / "couldn't
find sender" error after a few seconds because the mDNS path can't
complete. That is the intended Phase 2 outcome — BLE-only triggers
without a Wi-Fi LAN path are not in scope to fix.

---

## Procedure

### Setup (one-time per session)

1. Install / reinstall the debug APK on the LibreDrop sender and grant all
   permissions:
   ```bash
   ./gradlew :app:installDebug
   adb shell pm grant dev.bluehouse.bada.debug \
       android.permission.BLUETOOTH_ADVERTISE 2>/dev/null
   adb shell pm grant dev.bluehouse.bada.debug \
       android.permission.BLUETOOTH_SCAN 2>/dev/null
   adb shell pm grant dev.bluehouse.bada.debug \
       android.permission.BLUETOOTH_CONNECT 2>/dev/null
   ```
   `pm grant` is silently a no-op on API 24–30 where the permissions
   are install-time; that is fine.
2. Stage a small test file on the sender (≤ 1 MB JPEG works). The
   actual transfer is not what we measure here; we just need a real
   share intent to drive `SendActivity` into its advertising state.
3. Start streaming logs from the sender so we can record exactly when
   the BLE advertisement begins:
   ```bash
   adb logcat -c
   adb logcat -s LibreDropDiscovery LibreDropSend LibreDropOutbound | tee libredrop-sender.log
   ```
   `LibreDropDiscovery` is the shared BLE/mDNS tag (see `BleAdvertiser.TAG`
   and `BLE_TAG` in `SendActivity.kt`).

### Cell 1: Pixel receiver (clean GMS, both on Wi-Fi)

- [ ] On the **Pixel peer**: confirm Quick Share visibility is set to
      "Everyone". Pull down the notification shade — Quick Share should
      *not* already be open.
- [ ] On the **LibreDrop sender**: open the system share sheet for the test
      file and route into LibreDrop. `SendActivity` parses the intent, binds
      the receiver service, **and immediately starts both** the mDNS
      browse loop and the BLE pulse advertise (see `startBleAdvertise`
      in `app/.../send/SendActivity.kt`).
- [ ] The instant `LibreDropDiscovery` shows `BLE advertise: startAdvertising
      submitted bytes=24 uuid=0000fe2c-...`, **start a stopwatch**.
      Better: capture the line's timestamp from logcat.
- [ ] Watch the Pixel peer. The "Sharing nearby — Device sharing nearby"
      notification should appear within **5 seconds**.
- [ ] Record the elapsed time (logcat `submitted` timestamp ➜ user-
      observed pop-up time). Acceptance criterion below.
- [ ] Tap the pop-up on the Pixel peer, then tap our sender's tile in
      the receive sheet. The Phase 1 mDNS path takes over — confirm the
      file transfer completes and SHA-256 hashes match (cross-reference
      [`interop-stock-quick-share-android.md`](interop-stock-quick-share-android.md#test-1--test-2-send-file-from-libredrop-to-a-stock-android-phone-qr-code-path)
      for the post-pop-up procedure).

#### Things to record

- Pixel build fingerprint (`adb shell getprop ro.build.fingerprint`)
- Google Play services version on the Pixel (Settings → Apps →
  Google Play services → "About this app")
- Wall-clock timestamp from logcat for `BLE advertise: startAdvertising
  submitted` and the wall-clock timestamp the pop-up was first visible
- Whether the pop-up was the system Quick Share sheet or only the
  notification (both count as a positive)

#### Result

- [ ] **TODO — run on real device.** Time-to-pop-up: ____ s.

### Cell 2: Samsung receiver (One UI 6+, both on Wi-Fi)

- [ ] On the **Samsung peer**: confirm Quick Share visibility is set
      to "Everyone" both in Settings and in the Quick Share quick-tile.
      Samsung's tile-level visibility silently overrides the Settings
      page — see the
      [Samsung quirks list](interop-stock-quick-share-android.md#samsung--one-ui-quirks).
- [ ] Repeat the Cell 1 procedure unchanged. `LibreDropDiscovery` should
      again log `submitted bytes=24` the moment `SendActivity` starts.
- [ ] Watch the Samsung peer. The Quick Share notification should
      appear within **5 seconds**. On One UI 7+ this is sometimes a
      heads-up notification rather than a full sheet; either counts.

#### Things to record

- One UI version (Settings → About phone → Software information →
  One UI version)
- Samsung Quick Share app version (Settings → Apps → Quick Share →
  About — confirm it is the post-merger Google build, **not**
  `com.samsung.android.app.sharelive`)
- Whether the user had to first swipe down the notification shade for
  the pop-up to register (some One UI builds delay the heads-up)

#### Result

- [ ] **TODO — run on real device.** Time-to-pop-up: ____ s.

### Cell 3: Receiver on mobile data only (no Wi-Fi)

This cell verifies that the BLE pulse and the receiver's pop-up logic
are independent of Wi-Fi state. The downstream Wi-Fi LAN follow-up will
fail, and that failure is the **intended** Phase 2 behaviour.

- [ ] On the **peer** (Pixel or Samsung — pick one, ideally the same
      device used in Cell 1 or 2): turn **Wi-Fi off**, leave **mobile
      data on**, leave **Bluetooth on**. Confirm the device has no
      cached Wi-Fi association by toggling airplane mode briefly.
- [ ] Run the same LibreDrop-sender procedure as Cell 1.
- [ ] **Pop-up:** the Quick Share notification should still appear
      within 5 s. The receiver's BLE-scan-and-notify path does not
      require Wi-Fi.
- [ ] **mDNS follow-up:** when the user taps the pop-up and opens the
      receive sheet, Quick Share will fail to find our endpoint via
      mDNS. The expected user-visible outcome is a "couldn't connect"
      / "tried to connect to … but failed" error, sometimes after a
      ~10–15 s timeout. **This is acceptable for Phase 2.** Do not
      file a bug for this state alone.
- [ ] Restore the peer's Wi-Fi after the test.

#### Things to record

- Whether the pop-up appeared at all (this is the only assertion that
  matters for this cell)
- The exact error string the Quick Share UI showed once the user tapped
  through (so we can correlate it with future fixes if BLE-only
  transfer is ever scoped in)

#### Result

- [ ] **TODO — run on real device.** Pop-up appeared (Y/N): ____.
      Time-to-pop-up: ____ s. Wi-Fi follow-up error string: ____.

### Cell 4: Receiver with screen off

This cell verifies that the receiver's BLE scan still wakes the Quick
Share pop-up while the device is in a screen-off / Doze-eligible state.
Stock Quick Share runs its scan inside Google Play services' foreground
service, which on recent Android versions is allowed to scan BLE while
the screen is off; this is the property we are confirming.

- [ ] On the **peer**: lock the screen (power button). Wait ~30 s so
      Doze settles into "light Doze" without your tap activity keeping
      it awake. **Do not** plug the peer into a charger during this
      cell — charging suppresses Doze.
- [ ] Run the same LibreDrop-sender procedure as Cell 1.
- [ ] Watch the peer's screen. The pop-up arriving should wake the
      screen (or surface as a heads-up notification on the lock
      screen). Both behaviours count; record which one you saw.
- [ ] Time-to-pop-up should still be within 5 s.
- [ ] Unlock the peer, tap the notification, and confirm the Phase 1
      Wi-Fi LAN path completes the transfer.

#### Things to record

- Peer's Doze state at the moment the advertisement landed
  (`adb shell dumpsys deviceidle get deep` and `... get light` from a
  separate adb session against the peer if you have USB debugging
  enabled on it)
- Whether the screen was woken by the pop-up or only by the user

#### Result

- [ ] **TODO — run on real device.** Pop-up appeared (Y/N): ____.
      Time-to-pop-up: ____ s. Screen woke automatically (Y/N): ____.

---

## Acceptance criterion

> **The pop-up must appear within 5 seconds of advertise start on at
> least one Pixel device AND at least one Samsung device.**

- [ ] Cell 1 (Pixel) measured pop-up latency ≤ 5 s — **TODO — run on
      real device**.
- [ ] Cell 2 (Samsung) measured pop-up latency ≤ 5 s — **TODO — run
      on real device**.

Cells 3 and 4 do not gate the acceptance criterion. They are recorded
to document graceful-degradation behaviour and to give future
engineers a baseline to compare against if a Play services / One UI
update regresses the BLE wake-up path.

---

## Vivo / Funtouch / OriginOS specific quirks

Real-device verification on a vivo X300 Ultra (Funtouch 16, OriginOS 6,
Android 16) surfaced several behaviours that any tester running the
matrix on a vivo device must be aware of. Other Funtouch / OriginOS
versions (and likely most other Chinese-OEM Android skins — Xiaomi
MIUI, Honor MagicOS, OPPO ColorOS) layer similar restrictions on top
of stock Android, so these notes apply more broadly than just vivo.

### What changed in PR #99 / issue #98

The mDNS publish + browse path was migrated from the JmDNS library
in-process to Android's `NsdManager`. `NsdManager` delegates to the
system mDNS responder process, which has the multicast filter
exemption baked in; the in-app `WifiManager.MulticastLock` (and the
`WIFI_MODE_FULL_HIGH_PERF` companion Wi-Fi lock that an earlier
workaround tried to use) are no longer needed and have been removed.

**Why this matters on vivo specifically.** Pre-#99 the JmDNS-based
implementation silently failed to receive any inbound mDNS multicast on
vivo devices regardless of `MulticastLock` state — the radio layer on
those OEM skins drops inbound IPv4 multicast for non-system apps at a
level below the lock mechanism (`dumpsys wifi` reports
`ipv4RxMulticast=0` even with `MulticastLock.isHeld == true`). The
`NsdManager` path bypasses this restriction because the system
responder has kernel `CAP_NET_RAW` privileges that third-party
processes cannot obtain. The LibreDrop peer picker should now populate
within 5 s of the BLE pulse landing on vivo hardware.

#### How to verify the migration on a vivo device

1. Install a post-#99 build and open the share flow on the vivo sender.
2. Verify no in-process multicast lock is held by the app:
   ```bash
   adb shell dumpsys wifi | grep -A 10 "Multicast Locks held"
   ```
   The section should either be absent or show zero locks held by
   `dev.bluehouse.bada.debug`. If a lock entry still appears,
   the migration is incomplete on this build.
3. Confirm the LibreDrop peer picker populates within 5 s of a Pixel or
   Samsung Quick Share sender issuing a BLE pulse (cells 1 and 2 of
   the test matrix above). On pre-#99 builds this would reliably fail
   on vivo; on post-#99 builds it should succeed.
4. Check `dumpsys servicediscovery` for the registered listener and
   the cached peers:
   ```bash
   adb shell dumpsys servicediscovery | grep -A 3 -E "Register a DiscoveryListener|onServiceFound"
   ```
   You should see at least one `Register a DiscoveryListener … for
   service type:_FC9F5ED42C8A._tcp.local` line and `onServiceFound`
   entries for each visible Quick Share peer.

### Same-service publish / browse sequencing

Funtouch 16 can wedge a process-local `NsdManager.discoverServices`
listener if LibreDrop starts browsing `_FC9F5ED42C8A._tcp` while its own
receiver-side `registerService` for the same type is still active or
tearing down. The sender flow therefore flips the outbound-session veto,
waits briefly for the receiver mDNS advertisement to report stopped,
then starts the peer-picker browse.

When verifying issue #107 on a vivo device:

1. Start the receiver service and enable Always Visible.
2. Open a share intent while a Samsung / Pixel peer is visible.
3. Confirm logcat or `libredrop-outbound.log` contains:
   `discovery: waiting for receiver mDNS unpublish before browse`
   followed by `discovery: receiver mDNS unpublish observed`.
4. Confirm the peer picker re-acquires the Samsung / Pixel peer within
   about 5 s. If it does not, collect `dumpsys servicediscovery` before
   force-stopping LibreDrop so the platform listener/cache state is preserved.

### App freezing must be disabled

Funtouch's "App Freezer" / "Background process management" suspends
non-system app processes a few minutes after they leave the foreground —
even when they own a `connectedDevice` foreground service. While
frozen, the app keeps its persistent notification visible but cannot
process incoming BLE callbacks or service the system NSD responder. To
the peer it looks like our receiver simply went silent.

Before any test cell, enable background activity for the LibreDrop app:

1. **Settings → Battery → Background power consumption management**
   (the exact path depends on Funtouch version; on OriginOS 6 it is
   *Settings → Apps & permissions → Background apps*).
2. Find **LibreDrop**.
3. Set background activity to *Allow* and disable battery optimisation.
4. If the device exposes an *Auto-start* toggle, enable that too —
   Funtouch's freezer treats unfreezing as auto-start for some
   transitions.

You can confirm the freezer is no longer interfering by running:

```bash
adb shell dumpsys activity broadcasts | grep -B 1 -A 1 "vivo.intent.action.PACKAGE_FREEZE.*libredrop"
```

After whitelisting, this should not show new FREEZE entries while the
app is running. Pre-existing entries from before the whitelist are
fine to ignore.

### Non-Pixel/Samsung peers may not be installed

The vivo X300 Ultra ships with full GMS but **does NOT** ship with
stock Quick Share installed. `adb shell pm list packages | grep -i
nearby` returns nothing on this device class. This is exactly the use
case LibreDrop was built for, but it means a vivo device cannot be used as
a Quick Share *peer* in the test matrix — only as the LibreDrop sender or
LibreDrop receiver. Use a Pixel or Samsung phone for the matrix's peer
role.

### Logcat filters out app `Log.i` output

Funtouch / OriginOS filters `Log.i` calls from non-system apps —
`adb logcat -s LibreDropDiscovery:*` will return nothing for our success
paths even when the corresponding code is running. The success-path
log lines in `BleAdvertiser`, `BleQuickShareScanner`, and
`MdnsAdvertisementGate` therefore log at `Log.w` level (the same
mitigation `OutboundConnection` uses with `Log.e`); `Log.w` and
`Log.e` come through normally. When a test cell on a vivo device
needs to verify that the BLE advertise actually started, prefer
`adb shell dumpsys bluetooth_manager | grep -B 1 -A 12 libredrop`
over logcat.

---

## Common Quick Share BLE quirks

These are real-world properties of stock Quick Share's BLE side that
every tester should know about. None are bugs in LibreDrop; they are
properties of the receiver's BLE-trigger UX. Walk this list before
filing a follow-up issue.

### Sender side (LibreDrop)

- **`BluetoothLeAdvertiser` is null on devices without peripheral
  mode.** Many emulators and a non-trivial fraction of older budget
  phones return `null` from
  `BluetoothManager.adapter.bluetoothLeAdvertiser`. `BleAdvertiser`
  surfaces this as the "BLE pulse not started — falling back to
  mDNS-only discovery" log line and we accept the silent fallback.
  Cell 1/2 cannot be run on such devices; use a different sender.
- **`BLUETOOTH_ADVERTISE` revoked from Settings.** On API 31+ the
  user can revoke at any time. `BleAdvertiser.start` re-checks on
  every call and returns `null` on revocation rather than throwing
  `SecurityException`. Re-grant via Settings or onboarding flow.
- **`ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` (errorCode = 2)** is the
  most common platform failure, surfaced as a `LibreDropDiscovery` warn
  line. Caused by another app holding all available legacy advertise
  slots — typically Find My Device or stock Quick Share itself
  running concurrently. Force-stop competitors and retry.
- **`ADVERTISE_FAILED_DATA_TOO_LARGE` (errorCode = 1)** must not
  happen for our 24-byte payload, which is well within the legacy
  31-byte advertising-PDU budget. If it does, look for a regression
  in `BleAdvertisePayload` or in `buildAdvertiseData`'s field set
  (a future `setIncludeDeviceName(true)` would push us over).
- **Bluetooth toggled off mid-flight.** `BluetoothLeAdvertiser` may
  silently stop the advertisement when the user toggles Bluetooth
  off. We do not currently re-arm; the user must close and re-open
  the share sheet. Document this in any failure report.

### Receiver side (Pixel / Samsung)

- **Quick Share "Everyone" auto-disables after 10 minutes** on recent
  Android versions. If the pop-up worked once and now fails, re-enable
  visibility and retry. This is identical to the Phase 1 quirk and
  applies to Phase 2 BLE wake-ups too.
- **Stale Play services build.** A Pixel that has been offline for
  weeks may run an old Play services that doesn't include the post-
  merger Quick Share BLE filter. Update Play Store before retesting.
- **Samsung's separate quick-tile visibility** silently overrides the
  Settings page — see `interop-stock-quick-share-android.md`. Both
  layers must say "Everyone".
- **Battery saver / Doze on the receiver** can suppress the BLE scan
  even with Bluetooth on. Cell 4 explicitly tests Doze behaviour;
  but on devices with manufacturer-specific aggressive battery
  managers (Xiaomi, OPPO, Vivo) you may also need to whitelist
  Google Play services from the OEM's battery management screen.
- **Pop-up may be the heads-up notification only**, not the full
  Quick Share sheet, depending on Android version, lock-screen
  notification settings, and OEM. Either counts as a positive for
  this runbook's acceptance criterion.

---

## What to log if a step fails

When any cell above fails, capture **all** of the following before
filing a follow-up issue.

### From the LibreDrop sender

```bash
# BLE + send-flow logs.
adb logcat -d \
    'LibreDropDiscovery:V' \
    'LibreDropSend:V' \
    'LibreDropOutbound:V' \
    'BluetoothLeAdvertiser:V' \
    'AndroidRuntime:E' \
    '*:S' > libredrop-sender-logcat.txt

# vivo Funtouch OS swallows non-system logcat output; pull the
# OutboundConnection fallback log too.
adb pull /sdcard/Android/data/dev.bluehouse.bada.debug/files/libredrop-outbound.log

# Capture the platform's view of advertising slots.
adb shell dumpsys bluetooth_manager > libredrop-bt-dumpsys.txt
```

The most diagnostic line is
`BLE advertise: startAdvertising submitted bytes=24 uuid=0000fe2c-...`
followed (or not) by `onStartSuccess` from the platform callback. If
`onStartFailure` shows up instead, the four-letter error description
(`DATA_TOO_LARGE`, `TOO_MANY_ADVERTISERS`, `ALREADY_STARTED`,
`INTERNAL_ERROR`, `FEATURE_UNSUPPORTED`) tells you which quirk applies.

### From the stock peer

- Settings → System → Developer options → Bug report → Interactive
  report. Save and attach.
- For Pixel: Settings → Apps → Google Play services → "About this app"
  screenshot.
- For Samsung: Settings → Apps → Quick Share → "About" screenshot,
  plus a screenshot of the Quick Share quick-tile visibility setting.

### Wireless capture (optional but very useful)

A BLE sniffer (nRF52 dongle running Wireshark with the Nordic plugin,
or `btmon` on a Linux laptop with a USB BT dongle) that captures the
Quick Share advertisements during the failed window is the single
most useful artefact. Filter for the 16-bit service UUID `0xFE2C` and
correlate the timestamps to the LibreDrop `submitted` log line.

---

## Related project files

- Sender BLE advertiser:
  [`discovery-android/.../ble/BleAdvertiser.kt`](../../discovery-android/src/main/kotlin/dev/bluehouse/bada/discovery/ble/BleAdvertiser.kt)
- 24-byte service-data payload:
  [`discovery-android/.../ble/BleAdvertisePayload.kt`](../../discovery-android/src/main/kotlin/dev/bluehouse/bada/discovery/ble/BleAdvertisePayload.kt)
- Receiver BLE pulse scanner (used to gate our own mDNS, not exercised
  here but the symmetric counterpart of stock Quick Share's behaviour):
  [`discovery-android/.../ble/BleQuickShareScanner.kt`](../../discovery-android/src/main/kotlin/dev/bluehouse/bada/discovery/ble/BleQuickShareScanner.kt)
- mDNS gating (publish on BLE pulse, debounced unpublish):
  [`service-android/.../receiver/MdnsAdvertisementGate.kt`](../../service-android/src/main/kotlin/dev/bluehouse/bada/service/receiver/MdnsAdvertisementGate.kt)
- Sender lifecycle wiring (start/stop advertise on share intent):
  [`app/.../send/SendActivity.kt`](../../app/src/main/kotlin/dev/bluehouse/bada/send/SendActivity.kt)
  (`startBleAdvertise` / `stopBleAdvertise`)
- Quick Share protocol spec (BLE advertise format, mDNS service type):
  <https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md>
- Phase 1 interop runbook (Wi-Fi LAN, QR-code path):
  [`interop-stock-quick-share-android.md`](interop-stock-quick-share-android.md)
- Companion runbook (NearDrop on macOS):
  [`interop-neardrop-macos.md`](interop-neardrop-macos.md)
- Power-instrumentation runbook (battery cost of receiver-side BLE
  scan, complementary to this sender-side trigger test):
  [`ble-scan-power.md`](ble-scan-power.md)
