# Power instrumentation: BLE pulse scanning

This is a manual test runbook for measuring the battery cost of
LibreDrop's Phase 2 BLE pulse scanner (issue #33) running with
the battery-tuned scan settings introduced in issue #35.

The acceptance criterion in #35 is:

> Measure mAh/hour on a Pixel 7 for a "background scanning, no transfer"
> hour. Document in the test runbook.

We do not have a real device available in CI, so this document is the
place where the engineer running the measurement records the result.
The test setup pins enough of the configuration that two engineers
running it independently should reach comparable numbers.

## What we are measuring

The scanner runs from inside `ReceiverForegroundService` and uses two
scan modes:

| App state                           | `ScanSettings` mode          | Notes                                                                 |
| ----------------------------------- | ---------------------------- | --------------------------------------------------------------------- |
| Background (default, foreground svc)| `SCAN_MODE_BALANCED`         | The mode Android allows a foreground service to run continuously.     |
| Foreground (any activity visible)   | `SCAN_MODE_LOW_LATENCY`      | Brief upgrade for responsiveness while the user is actively in-app.   |

`setReportDelay(0)` is pinned for both modes — non-zero delay batches
advertisements which is unsuitable for an interactive trigger.

The number we want is the **`SCAN_MODE_BALANCED`, no transfer**
draw — that is the steady state the receiver service spends most of its
time in, and the one the AC explicitly asks about.

## Preconditions

### Phone (Pixel 7 or comparable)

- [ ] **Pixel 7** running stock Android, ideally a recent stable release
      (Android 14 / 15). Other devices will not invalidate the test, but
      record the make/model so the result is comparable.
- [ ] Battery health > 80 % (Settings → Battery → Battery health).
      Below 80 %, the discharge profile is no longer representative.
- [ ] Battery level between 60 % and 90 % at the start of the test.
      Edge-of-charge regions discharge non-linearly and confound the
      measurement.
- [ ] Phone temperature in normal range (no recent gaming / fast charge).
- [ ] Adaptive Battery, Battery Saver, and per-app battery restrictions
      OFF for our app.
- [ ] Bluetooth ON, Wi-Fi ON, screen OFF for the duration of the
      measurement.
- [ ] Mobile data toggled OFF if possible — eliminates background
      cellular activity from the measurement.
- [ ] The debug build of `:app` installed and granted `BLUETOOTH_SCAN`
      (the onboarding flow from #31 covers this).

### Workstation

- [ ] `adb` reachable over USB. The phone must NOT be on the charger
      during the measurement window — `dumpsys battery` reports a
      derived "discharge rate" that is meaningless while charging.
      `adb shell dumpsys battery unplug` simulates an unplugged state
      while keeping USB connected for `adb`.

## Procedure

> All commands assume the worktree root is the current working
> directory. Substitute your own device serial in `adb -s <serial>` if
> multiple phones are attached.

### 1. Bring up the receiver

Start the foreground receiver service (the share-intent flow from #24
also brings the service up; or wire a "stay open" entry in MainActivity):

```bash
./gradlew :app:installDebug
adb shell am start -n dev.bluehouse.libredrop.debug/dev.bluehouse.libredrop.MainActivity
# Trigger any flow that calls ReceiverForegroundService.start() —
# in the current build the share-intent path will do.
```

Confirm the foreground service is running and the BLE scan started in
`SCAN_MODE_BALANCED`:

```bash
adb logcat -d -s LibreDropBleScan
# Expect: "start: BLE pulse scan started mode=BALANCED"
```

### 2. Establish the baseline

Lock the phone and let the screen turn off. Wait 2 minutes for any
lingering activity (mDNS announcements, JmDNS settle, Wi-Fi caching) to
quiesce.

Disconnect the charger but keep the USB lead in for `adb`:

```bash
adb shell dumpsys battery unplug
```

Reset the kernel battery counters so the next sample is from a known
zero:

```bash
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats --enable full-wake-history
```

### 3. Run the measurement window

Leave the phone undisturbed (locked, screen off, on a flat non-conducting
surface) for **at least 1 hour**, ideally 2 hours so the measurement
window is large enough that small noise sources don't dominate.

While the run is in progress, do NOT:
- Connect any new BLE peripherals to the phone.
- Send anything to the phone via Quick Share (that would invalidate the
  "no transfer" condition).
- Open any other apps that scan BLE.

### 4. Collect the data

After the measurement window completes:

```bash
adb shell dumpsys batterystats --checkin > batterystats-$(date +%Y%m%d-%H%M).txt
```

The relevant line in the checkin format is the per-UID `wbl` (wake-lock
+ BLE) breakdown. The package UID is `dev.bluehouse.libredrop.debug`;
look up its UID with:

```bash
adb shell dumpsys package dev.bluehouse.libredrop.debug | grep userId=
```

Alternatively, the human-readable form is easier to read:

```bash
adb shell dumpsys batterystats dev.bluehouse.libredrop.debug
```

Look for the `Bluetooth scan results received:` and
`Bluetooth scan time:` lines. Convert the scan time to mAh using the
device's published per-mode BLE scan power:

```
mAh = (scan_time_seconds / 3600) * scan_mode_current_mA
```

Pixel 7 BLE scan currents (from `power_profile.xml` —
`adb pull /system/etc/power_profile.xml` for the exact value on your
firmware):

| Mode          | Current (mA)   |
| ------------- | -------------- |
| BALANCED      | _populate from `power_profile.xml`_ |
| LOW_LATENCY   | _populate from `power_profile.xml`_ |
| LOW_POWER     | _populate from `power_profile.xml`_ |

### 5. Reconnect the charger and record

```bash
adb shell dumpsys battery reset
```

Restore the device to its normal battery state.

## Result template

Fill in this block in the PR or the issue comment when you run the
measurement on real hardware. **Do not invent numbers.**

```
Device:           [Pixel 7 / other]
Android version:  [e.g. Android 14 build TQ3A.230901.001]
Build:            [git sha / build number of :app]
Scan mode:        SCAN_MODE_BALANCED
Test window:      [start ts] -> [end ts] = [duration] minutes
Battery level:    [start %] -> [end %] = [delta %] absolute
Battery capacity: [Wh / mAh from spec sheet]
Per-window draw:  [mAh] / hour
mAh / hour:       [computed]

Notes:
  - [unusual conditions, deviations from procedure, etc.]
```

## Status

- [ ] **TODO — run on real device.** The acceptance criterion in #35
      requires a Pixel-7-on-real-hardware measurement; no measurement
      has been recorded yet. Update this section once a result is
      available.

## See also

- [Interop runbook: NearDrop on macOS](interop-neardrop-macos.md)
- [Interop runbook: stock Quick Share (Android)](interop-stock-quick-share-android.md)
- Source: `discovery-android/src/main/.../ble/BleQuickShareScanner.kt` (`buildScanSettings`)
- Source: `service-android/src/main/.../receiver/ReceiverForegroundService.kt` (`AppLifecycleScanModeObserver`)
