# Interop test: Wi-Fi local-only hotspot (WIFI_HOTSPOT medium)

This is a manual test runbook for verifying that the Wi-Fi
local-only-hotspot bandwidth-upgrade medium (#50) brings up a soft-AP
on the sender, joins the AP from the receiver, and round-trips a
Quick Share file transfer over the new transport without depending on
a shared Wi-Fi router.

The framework hooks for the upgrade swap itself are not yet wired in
(planned for #54). Until then this matrix exercises the lifecycle of
the provider in isolation, end-to-end:

1. The sender's `WifiHotspotMediumProvider.prepareUpgrade` brings up the
   hotspot and surfaces credentials.
2. The receiver's `WifiHotspotMediumProvider.adoptUpgrade` joins the
   advertised SSID and opens a TCP socket to the gateway IP / port.
3. Both sides tear down cleanly via the provider's `release()` /
   reservation `teardown()` callback.

For the proto-layer round-trip, the JVM unit tests in
`BandwidthUpgradeFramesTest` already cover encode/decode parity on
`UpgradePathCredentials.WifiHotspot` — failing those is a CI signal,
not a manual one.

## Preconditions

### Sender device

- [ ] Android 8.0 (API 26) or newer — `WifiManager.startLocalOnlyHotspot`
      is API 26+. Earlier devices return `null` from
      `WifiHotspotMediumProviderFactory.create` and skip this test.
- [ ] App granted `ACCESS_FINE_LOCATION` (or `NEARBY_WIFI_DEVICES` on
      API 33+) — the SSID/passphrase callback only fires when one of
      these is granted.
- [ ] Wi-Fi turned on. The platform tears the STA association down on
      most OEMs while the AP is up; the user's saved network reconnects
      automatically when the hotspot stops.

### Receiver device

- [ ] Android 10.0 (API 29) or newer — `WifiNetworkSpecifier` is API 29+.
      The provider returns `null` from `adoptUpgrade` on older devices.
- [ ] App granted `ACCESS_FINE_LOCATION` or `NEARBY_WIFI_DEVICES`.
- [ ] Same physical proximity as the sender (Wi-Fi-grade range, ~10m
      indoors).

### Test artifacts

- [ ] An installable debug APK on both devices — `./gradlew :app:assembleDebug`.
- [ ] `adb logcat -s LibreDropWifiHotspot LibreDropOutbound LibreDropSend LibreDropDiscovery`
      running on both devices in separate terminals.

## Sender: bring up the hotspot

> Until #54 wires the provider into the actual upgrade flow, drive
> `prepareUpgrade()` from a one-shot debug entry point (e.g. a hidden
> menu in `SendActivity`, or via `adb shell am start-service` against
> a test action). Update this section once the orchestrator is live.

- [ ] Trigger `WifiHotspotMediumProvider.prepareUpgrade()`.
- [ ] Logcat shows `LibreDropWifiHotspot` lines describing the bring-up.
- [ ] No `onFailed` line appears in logcat.
- [ ] The returned `UpgradePathCredentials.WifiHotspot` has:
  - [ ] non-empty `ssid` (typically `AndroidShare_xxxx` or `DIRECT-xx-xxx`).
  - [ ] non-empty `passphrase`.
  - [ ] non-zero `port` (from the kernel ephemeral range, 32768–60999 on
        most Android builds).
  - [ ] `gateway` resolves to an address in `192.168.0.0/16` (typically
        `192.168.49.1`).

## Receiver: join the hotspot

- [ ] Convey the credentials out-of-band (debug log, QR, manual entry)
      from the sender to the receiver's debug entry point.
- [ ] Trigger `WifiHotspotMediumProvider.adoptUpgrade(credentials)`.
- [ ] OEM "Connect to ‹SSID›?" dialog appears on API 29+. **Tap CONNECT.**
- [ ] Logcat on the receiver shows `LibreDropWifiHotspot` `onAvailable` line
      within ~10 seconds of tapping CONNECT.
- [ ] `adoptUpgrade` returns a non-null `WifiHotspotTransport` whose
      `socket.isConnected == true` and `socket.remoteSocketAddress`
      matches the sender's gateway IP and the credentialed port.

## Round-trip: send a small file over the new transport

- [ ] Pump a 1 KiB payload from the sender side of the new socket to
      the receiver side and read it back. Bytes must compare equal —
      the test is purely transport-level, no Quick Share framing
      required at this stage.

## Teardown

- [ ] Receiver: call `WifiHotspotTransport.release()`. The platform
      drops the Wi-Fi association within ~3 seconds, the device
      reassociates with the user's previous Wi-Fi network if any, and
      no `LibreDropWifiHotspot` lines log during reassociation.
- [ ] Sender: invoke the `HotspotReservation.teardown` callback.
      `WifiManager` reports the AP stopped (logcat: `LOHS:onStopped`),
      the device's Wi-Fi STA reassociates, and `wlan1` (or whatever
      interface hosted the AP) disappears from `adb shell ip link`.

## Edge cases (document outcomes — do not block on a perfect pass)

- [ ] **Sender already on a Wi-Fi network.** On Pixel 8 + Android 14 the
      AP-up causes a brief STA disconnect. On Galaxy S23 + One UI 7 the
      AP refuses to start until the user manually disconnects from
      Wi-Fi (logcat shows `onFailed reason=2` ERROR_INCOMPATIBLE_MODE).
      Document the OEM/version + behaviour here as new combinations are
      tested.
- [ ] **Receiver already on a Wi-Fi network.** The platform should
      transparently swap to the hotspot association for the duration of
      the request. On vivo Funtouch OS 14 the swap occasionally
      times out (~30s); the watchdog in
      `AndroidWifiNetworkSpecifierClient.awaitNetwork` resumes with
      `null` and the provider reports failure cleanly.
- [ ] **User dismisses the OEM consent dialog on the receiver.** The
      callback fires `onUnavailable`; the provider returns `null`.
- [ ] **Hotspot torn down mid-transfer (sender powers off).** The
      receiver's socket reads / writes throw `IOException`; the
      provider's transport `release()` is idempotent so the higher
      layers do not see a leaked network callback.

## What to log if a step fails

When a step above fails, capture the following before moving on — the
network state changes immediately on retry and wire traces are gone:

```bash
# Both devices:
adb logcat -d -s LibreDropWifiHotspot LibreDropOutbound LibreDropSend LibreDropDiscovery > libredrop-hotspot-{role}.log

# Sender:
adb shell ip addr show
adb shell dumpsys wifi | head -200

# Receiver:
adb shell dumpsys connectivity | head -120
adb shell cmd wifi list-networks
```

File the resulting bundle on the matching `phase-4` issue thread.
