# Interop test: Wi-Fi Aware (NAN) bandwidth-upgrade medium

Manual on-device runbook for verifying the Wi-Fi Aware (`WIFI_AWARE`)
[`MediumProvider`](../../discovery-android/src/main/kotlin/dev/bluehouse/libredrop/discovery/aware/WifiAwareMediumProvider.kt)
added in [#53](https://github.com/kyujin-cho/LibreDrop/issues/53)
under [Phase 4 — alternative bandwidth-upgrade mediums](https://github.com/kyujin-cho/LibreDrop/issues/4).

> **Hardware coverage warning.** Wi-Fi Aware (NAN) requires both an SDK
> floor of API 26 **and** a chipset that declares
> `android.hardware.wifi.aware`. Many flagships from 2020+ qualify
> (Pixel 4 and later, Galaxy S20 and later); most mid-range and budget
> SoCs do not. The provider is best-effort: when the chipset is not
> capable, [`WifiAwareMediumProvider.isSupported`] returns `false` and
> the orchestrator falls back to the next ladder rung. Devices without
> Wi-Fi Aware should not crash and should not log noise.

---

## Hardware support check

Before running the runbook, confirm both peers actually support Wi-Fi
Aware:

```bash
adb shell pm list features | grep -i wifi.aware
# Expected: feature:android.hardware.wifi.aware
```

If the line is absent, this device cannot serve as a peer. Pick a
different device or skip Wi-Fi Aware testing.

Confirm the runtime availability flag (which can flip when Wi-Fi is
turned off):

```bash
adb shell dumpsys wifiaware | head
# Look for "Aware Service: enabled" / "Available: true"
```

---

## What is being verified

1. `WifiAwareMediumProvider.isSupported()` returns `true` iff:
   - SDK >= 26
   - `PackageManager.FEATURE_WIFI_AWARE` is present
   - `WifiAwareManager.isAvailable()` is true (Wi-Fi on)
   - Either `NEARBY_WIFI_DEVICES` (API 33+) or `ACCESS_FINE_LOCATION`
     is granted at runtime.
2. `prepareUpgrade()` (publisher / receiver side) brings up a Wi-Fi
   Aware publisher, allocates an IPv6 link-local server socket on the
   data-path interface, and produces `UpgradePathCredentials.WifiAware`
   with the bound port and a fresh per-upgrade passphrase.
3. `adoptUpgrade()` (subscriber / sender side) consumes those
   credentials, requests a passphrase-secured Wi-Fi Aware network via
   `ConnectivityManager.requestNetwork`, and connects a
   `Network.socketFactory` socket to the publisher's IPv6 + port.
4. The orchestrator (#54, the integration that actually swaps the
   transport) re-runs the Quick Share handshake over the new socket.

Steps 1–3 belong to this issue. Step 4 lands when the framework wires
the registry into `OutboundConnection` / `InboundConnection`.

---

## Runbook

### Setup

1. Two devices (A, B) that both have Wi-Fi Aware (see check above).
2. Wi-Fi turned on on both. They do **not** need to be on the same SSID
   — that is the whole point of Wi-Fi Aware.
3. Install the LibreDrop debug APK on both:
   ```bash
   ./gradlew :app:assembleDebug
   adb -s <A-serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <B-serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Grant `NEARBY_WIFI_DEVICES` on both (API 33+) — onboarding prompts
   for it.

### Capability advertisement (step 1)

Open the app on each device and check logcat for the support flag:

```bash
adb logcat -s LibreDropWifiAware
# Expect on a supported device: nothing logged (clean path).
# Expect on an unsupported device: a single warn at startup
#   "prepareUpgrade: Wi-Fi Aware not available; refusing upgrade"
```

A device whose `isSupported()` returns `false` MUST NOT appear in the
peer's `WIFI_AWARE` ladder pick. Look for `LibreDropWifiAware: refusing` in
its logcat as a positive signal that the provider declined cleanly.

### Bring-up (steps 2–3)

Once the framework wires `WifiAwareMediumProvider` into the registry
(#54), the bring-up will run automatically as part of any send. Until
then, drive the provider manually from the integration smoke test:

1. On device A (publisher / receiver), open the receive screen.
2. On device B (subscriber / sender), open the share sheet and pick
   device A as the peer.
3. Watch logcat on both:
   ```bash
   adb logcat -s LibreDropWifiAware LibreDropOutbound LibreDropSend
   ```
4. Acceptance:
   - Both sides log a successful publish/subscribe attach.
   - Device A logs the bound port and IPv6 used in the credentials.
   - Device B logs the matching connection (no "match timed out", no
     "network request timed out").
   - Throughput on the upgraded socket is significantly higher than
     pure Wi-Fi LAN (Wi-Fi Aware on a clean radio path typically peaks
     at 100–250 Mbps).

### Negative paths

- Turn Wi-Fi off on either device, retry: provider must report
  `not available; refusing` and the connection must fall back to
  Wi-Fi LAN without crashing.
- Revoke `NEARBY_WIFI_DEVICES` (API 33+) and retry: same as above.
- Run on a device that does not declare `FEATURE_WIFI_AWARE` (any
  pre-2018 phone, most current mid-range): `isSupported()` returns
  `false` immediately with no logcat noise.

---

## Known interop quirks

- **IPv6 scope id.** `Inet6Address.getByAddress(null, bytes, 0)`
  intentionally passes `scope_id = 0`; the `Network.getSocketFactory()`
  attaches the right interface scope on connect. Do NOT pass the
  publisher's local interface index — that breaks the subscriber's
  routing.
- **Passphrase length.** Wi-Fi Aware requires 8..63 ASCII characters;
  the provider generates 32 random URL-safe-base64 chars. Stock peers
  reject shorter values silently.
- **service_info layout.** Quick Share packs the publisher's IPv6 +
  port into `WifiAwareCredentials.service_info` as `[16 bytes IPv6]
  [2 zero bytes][2 BE bytes port]`. See
  [`BandwidthUpgradeFrames.encodeWifiAwareServiceInfo`](../../core-protocol/src/main/kotlin/dev/bluehouse/libredrop/protocol/connection/BandwidthUpgradeFrames.kt)
  for the exact byte layout.
- **Coexistence with Wi-Fi STA.** Wi-Fi Aware on most chipsets shares
  the same radio as Wi-Fi STA, so the chipset cannot maintain a Wi-Fi
  Aware data path AND a high-throughput STA association at the same
  time. Expect a brief STA throughput dip during transfer.

---

## Reporting back

If you successfully complete a Wi-Fi-Aware-upgraded transfer between
two devices, attach the relevant `adb logcat -s LibreDropWifiAware` excerpt
and the device model + Android version of both ends to the issue
([#53](https://github.com/kyujin-cho/LibreDrop/issues/53)).
Hardware-coverage data is the most valuable thing this runbook can
collect because the platform's `FEATURE_WIFI_AWARE` flag is the only
ahead-of-time signal we have.
