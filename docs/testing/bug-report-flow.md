# Bug report flow runbook

Issue [#162](https://github.com/kyujin-cho/LibreDrop/issues/162) adds an
opt-in "shake to report a bug" flow that packages recent diagnostics,
device state, and a screenshot into a user-saved zip archive.

This runbook verifies the flow manually on at least one Pixel device and
one Samsung device.

## Preconditions

- Build and install the debug APK:

```bash
./gradlew :app:assembleDebug
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

- Use a device pair that can exercise LibreDrop normally:
  - one Pixel or other AOSP-like GMS device
  - one Samsung Galaxy device with current Quick Share
- Clear any previous saved bug-report archives from Downloads or the
  test folder you plan to use, so the new archive is easy to identify.

## Core flow

Run this on both the Pixel and Samsung device:

1. Open LibreDrop and confirm the new launcher toggle "Shake to report a bug"
   is visible and off by default.
2. Turn the toggle on.
3. With LibreDrop still foregrounded, shake the device firmly.
4. Confirm the modal appears with:
   - "Trying to report a bug?"
   - Yes / No actions
   - an unchecked "Include the current Wi-Fi BSSID" checkbox
5. Tap No and confirm nothing is collected.
6. Shake again, leave the BSSID checkbox unchecked, and tap Yes.
7. Confirm the app shows the temporary "Preparing bug report…" dialog.
8. When the SAF save sheet appears, save the archive to a user-visible
   location such as Downloads with the suggested filename.
9. Confirm the post-save dialog appears and offers "Open GitHub issue".
10. Tap Dismiss once, then repeat the flow and verify that "Open GitHub issue"
    launches the browser to LibreDrop's new-issue page.

## Archive checks

After saving, inspect the archive:

```bash
adb -s <device> shell ls /sdcard/Download | grep libredrop-bugreport
adb -s <device> pull /sdcard/Download/<archive-name>.zip /tmp/
unzip -l /tmp/<archive-name>.zip
```

Verify the zip contains exactly these paths:

- `README.txt`
- `metadata.json`
- `device.txt`
- `permissions.txt`
- `discovery.txt`
- `logs/outbound.log`
- `logs/ringbuffer.txt`
- `screenshot.png`

Inspect the text files:

```bash
unzip -p /tmp/<archive-name>.zip metadata.json
unzip -p /tmp/<archive-name>.zip device.txt
unzip -p /tmp/<archive-name>.zip permissions.txt
unzip -p /tmp/<archive-name>.zip discovery.txt
unzip -p /tmp/<archive-name>.zip logs/ringbuffer.txt
```

Confirm:

- `metadata.json` records whether Wi-Fi BSSID inclusion was enabled.
- `device.txt` includes app/device/runtime state and does not contain
  raw Bluetooth MAC addresses.
- `logs/ringbuffer.txt` contains recent lines from the `LibreDropDiscovery`,
  `LibreDropBleScan`, `LibreDropBleAdv`, `LibreDropMdnsGate`, or
  `LibreDropOutbound` tags after exercising the app.

## BSSID privacy check

1. Save one archive with the BSSID checkbox unchecked.
2. Save a second archive with the checkbox checked.
3. Compare `device.txt` in both archives.

Expected result:

- unchecked archive: `wifiBssid=<redacted>`
- checked archive: real BSSID when the app can read it, otherwise
  `not_available`

## Sensitive-screen redaction

Verify both sensitive-screen cases on at least one device:

### QR screen

1. Open LibreDrop's QR screen.
2. Shake the device and save a bug report.
3. Extract `screenshot.png`.

Expected result:

- the screenshot is a placeholder image
- the live QR code is not visible

### Consent screen

1. Trigger an incoming share so `ConsentTrampolineActivity` is visible.
2. Shake the device and save a bug report.
3. Extract `screenshot.png`.

Expected result:

- the screenshot is a placeholder image
- the 4-digit consent PIN is not visible

## Debounce check

1. With the toggle still enabled, shake the device repeatedly during one
   active report flow.
2. Confirm only one confirmation modal / save flow is active at a time.

## Regression notes

- No new privileged permissions should appear in the manifest or runtime
  prompts.
- If the flow fails on one device family only, capture:

```bash
adb logcat -s LibreDropDiscovery LibreDropSend LibreDropOutbound LibreDropBleScan LibreDropBleAdv LibreDropMdnsGate
```

- Record whether the failure is collection, SAF save, screenshot capture,
  or browser launch.
