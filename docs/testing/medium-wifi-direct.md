# Manual test: Wi-Fi Direct bandwidth-upgrade medium

This is the on-device runbook for verifying the Wi-Fi Direct (P2P)
bandwidth-upgrade adapter shipped in
[#49](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/49) under
the [Phase 4 epic](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/4).

The Phase 4 framework (#48 / #54) is the layer that *decides* to upgrade
to Wi-Fi Direct after the initial Wi-Fi LAN connection has been
established. This runbook covers the per-medium adapter only — once the
orchestrator wiring lands in #54 the same checklist will apply to
end-to-end transfers.

---

## What is being verified

1. **Capability gating.** `WifiDirectAvailability.Default` reports
   `isSupported() == true` only when:
   * the device exposes `PackageManager.FEATURE_WIFI_DIRECT`,
   * `getSystemService(Context.WIFI_P2P_SERVICE)` returns a
     `WifiP2pManager`, and
   * `NEARBY_WIFI_DEVICES` is granted on API 33+ (or
     `ACCESS_FINE_LOCATION` on API 32 and below).
2. **Receiver-side group bring-up.** `WifiDirectGroupController.createGroupAsServer`
   calls `WifiP2pManager.createGroup`, observes the
   `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast, and returns
   `UpgradePathCredentials.WifiDirect` populated with the GO IP, port,
   SSID, and passphrase from `WifiP2pInfo` / `WifiP2pGroup`.
3. **Sender-side join.** `WifiDirectGroupController.connectAsClient`
   calls `WifiP2pManager.connect` with the credentials from the wire,
   waits for the connection broadcast, and opens a TCP socket to the
   GO at `credentials.ipAddress:credentials.port`.
4. **Throughput uplift.** A 1 GB transfer over the freshly-formed Wi-Fi
   Direct link sustains ≥ 150 Mbps — the acceptance criterion from #49.
5. **Graceful fallback.** When any step above fails, the adapter
   returns `null` from `prepareUpgrade` / `adoptUpgrade` and the
   negotiator FSM emits `UPGRADE_FAILURE`, leaving the existing
   Wi-Fi LAN transfer running.

---

## Pre-flight

* Two Android devices, both API 31+. Ideal pairs:
  * Pixel 8 Pro (Android 14) ↔ Galaxy S24 (One UI 6).
  * Pixel 7 (Android 13) ↔ Pixel 8 (Android 14).
* Both devices on the same Wi-Fi network for the initial Wi-Fi LAN
  connection. The router itself is not used by the upgraded transport
  — Wi-Fi Direct is a peer-to-peer link — but discovery needs LAN.
* `adb` connected to *both* devices simultaneously. Use
  `adb devices -l` to confirm and address them with `adb -s <serial>`.
* App build: install the same debug APK on both devices.
  ```
  ./gradlew :app:installDebug
  ```
* Ensure the Wi-Fi Direct grant dialog has been accepted at least once
  per device. Some OEM skins (Samsung, vivo) hide the toggle inside
  Settings → Connections → Wi-Fi → Advanced → Wi-Fi Direct; trigger it
  there if the platform never asks.

---

## Logcat tags

```
adb logcat -s WvmgWifiDirect WvmgOutbound WvmgSend
```

Expected lines on the receiver during a successful upgrade:

```
WvmgWifiDirect: WifiP2pManager.createGroup OK
WvmgWifiDirect: WIFI_P2P_CONNECTION_CHANGED_ACTION groupFormed=true isGroupOwner=true
```

On the sender:

```
WvmgWifiDirect: WifiP2pManager.connect OK
WvmgWifiDirect: WIFI_P2P_CONNECTION_CHANGED_ACTION groupFormed=true isGroupOwner=false
WvmgWifiDirect: TCP connect to 192.168.49.1:<port> OK
```

Failures are logged as `Log.w(TAG, …)` and accompanied by the platform
reason code (`BUSY`, `P2P_UNSUPPORTED`, `ERROR`, `NO_SERVICE_REQUESTS`).

---

## Test cases

### TC1 — Capability check matrix

| Device                   | Expected `isSupported()` | Notes |
|--------------------------|--------------------------|-------|
| Pixel 8 Pro (API 34)     | `true` after grant       | `NEARBY_WIFI_DEVICES` |
| Galaxy S24 (One UI 6)    | `true` after grant       | Same |
| Pixel 4a (API 30)        | `true` after grant       | Falls back to `ACCESS_FINE_LOCATION` |
| Android emulator (x86)   | `false`                  | No `FEATURE_WIFI_DIRECT` |

Verification: send via the app once; in logcat, confirm
`MediumRegistry.supportedMediums()` includes `WIFI_DIRECT`. (Add a
`Log.i(TAG, supportedMediums().toString())` in
`WvmgMediumRegistries.withWifiDirect` while debugging.)

### TC2 — Receiver creates a group, sender joins, GO IP matches

1. Open WhenVivoMeetsGoogle on both devices.
2. On the sender, share a small file (1 MB) to the receiver via the
   share sheet → WhenVivoMeetsGoogle.
3. On the receiver, accept the consent prompt.
4. **Before the file actually starts transferring**, in another shell,
   run on the receiver:
   ```
   adb -s <receiver-serial> shell dumpsys wifip2p | grep -E 'mGroupOwner|mDeviceAddress|networkName|passphrase'
   ```
5. Confirm `groupOwner=true` on the receiver and the SSID/passphrase
   match what `WvmgWifiDirect` logged.
6. On the sender, run the same `dumpsys wifip2p` and confirm the
   group-owner address matches the receiver.

### TC3 — 1 GB throughput

1. Pick a ~1 GB file (e.g. an Android system image).
2. Time the transfer end-to-end with `time` on the wrapping shell:
   ```
   time adb shell …
   ```
   The app's progress notification also reports MiB/s once #46 lands.
3. Compute throughput: `1024 MiB / elapsed_seconds`. Acceptance
   criterion is ≥ 150 Mbps sustained (≈ 18.75 MiB/s).
4. Compare against the same transfer over Wi-Fi LAN only (uninstall the
   #49 build, reinstall the #48 baseline, repeat). The Wi-Fi Direct
   path should be measurably faster, especially on a congested
   2.4 GHz network.

### TC4 — Graceful fallback when Wi-Fi Direct fails

Setup the failure path by either:
* **Revoking `NEARBY_WIFI_DEVICES`** on the sender mid-transfer.
* **Force-stopping the WifiP2pService** with
  `adb shell pkill -f wifip2p` (root or `userdebug` build only).

Expected: the negotiator FSM emits `BandwidthUpgradeEvent.AdoptFailed`
followed by `UPGRADE_FAILURE` on the wire; the transfer continues over
the original Wi-Fi LAN socket and finishes successfully. Verify with:

```
adb logcat -s WvmgOutbound | grep -E 'UPGRADE_FAILURE|UpgradeAborted'
```

### TC5 — Receiver tears down the group on completion

1. Trigger any successful Wi-Fi Direct transfer.
2. After the transfer completes, on the receiver:
   ```
   adb shell dumpsys wifip2p | grep -E 'GroupOwner|networkName'
   ```
3. Expected: no live group. The receiver's `WifiDirectMediumProvider.cancelPending`
   removed it.

### TC6 — Two back-to-back transfers reuse the radio cleanly

1. Send file A. Wait for completion.
2. Immediately send file B.
3. Expected: both transfers complete; the second does not stall
   waiting for a stale group to release. If it stalls, check
   `WvmgWifiDirect: WifiP2pManager.removeGroup` ran during step 1's
   completion path.

---

## Known platform quirks

* **Samsung One UI 7+** sometimes ignores
  `WifiP2pConfig.groupOwnerIntent = 0` and elects itself GO. The
  adapter copes by reading the actual `isGroupOwner` from the
  broadcast and re-routing accordingly; if you observe the sender
  side claiming GO, that's the fallback at work.
* **Pixel devices on Android 15** require the foreground app to hold
  `NEARBY_WIFI_DEVICES`; background services trying to call
  `WifiP2pManager.createGroup` get `ERROR` (5). Quick Share runs in a
  foreground-service-with-`connectedDevice` type, which qualifies.
* **vivo Funtouch OS** silently drops `WIFI_P2P_CONNECTION_CHANGED_ACTION`
  when the activity is paused. The adapter registers the receiver with
  `ContextCompat.RECEIVER_NOT_EXPORTED` on the application context, so
  this is not an issue in practice; if you see missed broadcasts on a
  vivo device, double-check the receiver lifecycle isn't tied to an
  activity.
