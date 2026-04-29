# Medium integration suite runbook

Issue #54 adds two pieces of coverage:

1. A JVM fallback-selection test that proves the registry chooses Wi-Fi
   LAN when a higher-priority medium cannot prepare.
2. A real-device instrumentation suite with one support-probe test per
   medium, each gated by `assumeTrue(...)` so unsupported hardware skips
   instead of failing.

The full transport bring-up and connection-driver transport swap still
need two real devices and OEM-specific permissions, so CI cannot be the
source of truth for the complete matrix.

## What CI can run

Run the pure JVM fallback coverage locally or in CI:

```bash
./gradlew :core-protocol:test --tests '*.medium.MediumRegistryTest'
```

The key fallback-selection case is:

- `prepareBestUpgrade falls back to Wi-Fi LAN when Wi-Fi Direct setup fails`

## What needs real devices

Run the device-gated support-probe smoke tests on each candidate device:

```bash
./gradlew :discovery-android:connectedDebugAndroidTest
```

The suite class is:

- `io.github.kyujincho.wvmg.discovery.medium.RealDeviceMediumSupportIntegrationTest`

Each test skips unless the device actually supports the medium and the app
holds the required runtime permission:

- `wifiDirect_support_probe_registers_with_registry`
- `wifiHotspot_support_probe_registers_with_registry`
- `wifiAware_support_probe_registers_with_registry`
- `bleL2cap_support_probe_registers_with_registry`
- `bluetoothRfcomm_support_probe_registers_with_registry`

## Full manual matrix

Use two physical devices and pair the support probes above with these
transport-specific runbooks:

- Wi-Fi LAN control path: [`interop-stock-quick-share-android.md`](interop-stock-quick-share-android.md) or [`interop-neardrop-macos.md`](interop-neardrop-macos.md)
- Wi-Fi Direct: [`medium-wifi-direct.md`](medium-wifi-direct.md)
- Wi-Fi Hotspot: [`interop-wifi-hotspot.md`](interop-wifi-hotspot.md)
- Wi-Fi Aware: [`interop-wifi-aware.md`](interop-wifi-aware.md)
- BLE L2CAP: [`medium-ble-l2cap.md`](medium-ble-l2cap.md)
- Bluetooth RFCOMM: [`interop-bluetooth-rfcomm.md`](interop-bluetooth-rfcomm.md)

## Recommended operator flow

1. Install the debug APK on both devices:

```bash
./gradlew :app:assembleDebug
adb -s <device-a> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-b> install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Grant the runtime permissions each medium needs on both devices.
3. Run `:discovery-android:connectedDebugAndroidTest` on each device and
   confirm unsupported mediums report `skipped`, not `failed`.
4. Run the Wi-Fi LAN control transfer and record throughput.
5. Walk the upgraded-medium runbooks one by one, reusing the same payload
   size for throughput comparisons.
6. Record the measured results in [`docs/medium-perf.md`](../medium-perf.md)
   when a device pair establishes a stable benchmark.

## Upgraded-medium success vs fallback

Issue #133 adds a dual-channel receive handoff: while a stock sender is
switching to a direct medium, WVMG can keep draining the original Wi-Fi
LAN channel and the upgraded channel, then deliver SecureMessage frames
to the sharing FSM in sequence-number order.

When validating Wi-Fi Direct or another upgraded medium, record the
result as **upgraded-medium success** only if logs show both:

- `medium-upgrade: delivered ... from prior`
- `medium-upgrade: delivered ... from upgraded`

If the transfer completes but `WvmgMedium` / `WvmgInbound` only reports
Wi-Fi LAN as the active medium, record it as **Wi-Fi LAN fallback
success**. That is still a pass for basic transfer stability, but it is
not evidence that the direct-medium handoff worked.

## Failure handling

- If Wi-Fi Direct group creation fails, the JVM fallback-selection test is
  the guard that the registry chooses Wi-Fi LAN instead.
- The JVM dual-channel reorder test proves the connection layer can
  accept interleaved prior/upgraded SecureMessage frames, but real-device
  validation still requires the medium-specific two-device runbooks
  above.
- If an instrumentation test unexpectedly fails on supported hardware,
  capture `adb logcat` for the medium-specific tag before retrying.
- If a device skips a medium that should be available, re-check runtime
  permissions first; the support probes intentionally treat missing grants
  as "unsupported for this run".
