# Interop test: Bluetooth RFCOMM bandwidth-upgrade medium

This is a manual interoperability runbook for verifying issue
[#51](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/51) —
the Phase 4 Bluetooth RFCOMM bandwidth-upgrade medium implemented by
[`BluetoothRfcommMediumProvider`](../../discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery/medium/BluetoothRfcommMediumProvider.kt).

This is part of the
[Phase 4 epic](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/4)
(alternative bandwidth-upgrade mediums). Apple-side interop (AirDrop,
AWDL) remains explicitly out of scope.

---

## What is being verified

Three independent code paths cooperate to deliver an RFCOMM upgrade:

1. **Receiver-side listen** — `prepareUpgrade()` opens an insecure
   RFCOMM listener via
   `BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid)`
   and emits a `BluetoothCredentials{ mac_address, service_name=UUID }`
   sub-message inside `BANDWIDTH_UPGRADE_NEGOTIATION{UPGRADE_PATH_AVAILABLE}`.
2. **Sender-side connect** — `adoptUpgrade()` parses the credentials,
   calls `BluetoothAdapter.getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(uuid).connect()`,
   and hands the resulting `BluetoothSocket` back to the framework as a
   `BluetoothRfcommTransport`.
3. **Framework swap** — once #54 wires the per-medium adoption hook,
   the SecureChannel rebuilds around the new transport's input/output
   streams; until then this step is exercised in isolation by the
   provider's unit tests and this runbook only verifies step 1 + 2.

The RFCOMM service UUID currently used as the default is
`a82efa21-ae5c-3dde-9bbc-f16da7b16c1a` (UUIDv4, project-specific). It is
carried on the wire in the proto's `BluetoothCredentials.service_name`
field, so a future change to the default does not break already-deployed
peers — both ends agree on whatever string the **receiver** picked.

---

## Preconditions

### Networking requirements

RFCOMM does not require Wi-Fi or any local network. Both devices need
working Bluetooth Classic radios that can complete an SDP discovery and
an RFCOMM connect — every shipping Android phone qualifies; if it has
a Bluetooth icon in the status bar, it can RFCOMM.

The downstream file transfer over the upgraded RFCOMM channel is still
gated on the Phase 4 #54 framework swap; until that lands, the test only
verifies that prepareUpgrade / adoptUpgrade reach a connected state on
both sides.

### Devices under test (DUTs)

- [ ] **WVMG receiver:** Android device running the WVMG debug APK from
      `./gradlew :app:assembleDebug`. Bluetooth toggled **on**.
- [ ] **WVMG sender:** Second Android device on the same build. Bluetooth
      toggled **on**. The two devices do **not** need to be paired —
      the provider uses the *insecure* RFCOMM variants, which connect
      without an OS pairing prompt.

### Permissions

Onboarding (#31) requests both `BLUETOOTH_SCAN` and `BLUETOOTH_ADVERTISE`
on API 31+. After #51 the same flow also requests `BLUETOOTH_CONNECT`.
On both devices verify under **Settings → Apps → WhenVivoMeetsGoogle →
Permissions → Nearby devices** that the runtime grant is held.

On API ≤ 30 the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` permissions are
install-time only (always granted if declared).

---

## Test matrix

| Receiver state | Sender state | Expected outcome |
|---|---|---|
| Bluetooth ON, CONNECT granted | Bluetooth ON, CONNECT granted | RFCOMM upgrade succeeds, sender's `BluetoothSocket.isConnected` is true after `adoptUpgrade()`. |
| Bluetooth OFF | Bluetooth ON | `prepareUpgrade()` returns `null` on the receiver, no UPGRADE_PATH_AVAILABLE on the wire, framework stays on Wi-Fi LAN. |
| Bluetooth ON | Bluetooth OFF | Receiver advertises Bluetooth in `ConnectionRequestFrame.mediums`, but `MediumRegistry.selectBestUpgrade()` on the sender excludes BLUETOOTH because `isSupported()` returns false locally. |
| Bluetooth ON, CONNECT denied | Bluetooth ON, CONNECT granted | Receiver's `prepareUpgrade()` returns null (the manifest declares the permission but Android's runtime check fails); framework stays on Wi-Fi LAN. |

---

## Procedure

### 1. Smoke-test isSupported on both devices

```bash
adb -s <receiver_serial> logcat -c
adb -s <receiver_serial> logcat -s WvmgBtRfcomm
```

Trigger any path that calls `MediumRegistry.supportedMediums()` — the
quickest is to start the receiver service. With Bluetooth on and
CONNECT granted, no warning should fire. Toggle Bluetooth off and
re-trigger; expect no UPGRADE_PATH_AVAILABLE attempt.

### 2. Smoke-test prepareUpgrade on the receiver

After the framework asks the registry to upgrade (#54 will wire this
in), inspect logcat:

- [ ] `WvmgBtRfcomm: RFCOMM listen failed; declining upgrade` — only
      expected when Bluetooth is off or CONNECT is denied.
- [ ] No log line means listen succeeded; `prepareUpgrade()` returned a
      non-null `UpgradePathCredentials.Bluetooth(macAddress, serviceUuid)`.

Verify the wire frame on the sender side via the existing
`OutboundConnection` debug log (or capture via a Wi-Fi MITM if
inspection of the upgrade frame is desired):

- [ ] `BANDWIDTH_UPGRADE_NEGOTIATION{event_type=UPGRADE_PATH_AVAILABLE,
      upgrade_path_info{ medium=BLUETOOTH, bluetooth_credentials{
      mac_address="<receiver MAC>", service_name="<UUID string>" } } }`

### 3. Smoke-test adoptUpgrade on the sender

After the sender parses UPGRADE_PATH_AVAILABLE and calls
`adoptUpgrade()`:

- [ ] Logcat tag `WvmgBtRfcomm` is silent (success path).
- [ ] OR `RFCOMM connect to <MAC>/<UUID> failed` (failure path) —
      verify the failure mode in the stack trace; the most common is
      Android's "service discovery failed" when the UUID does not
      match a registered SDP record on the receiver.

### 4. Throughput sanity (after #54 lands)

Once the framework actually transfers payload over the upgraded RFCOMM
channel, measure throughput by transferring a 10 MB file and watching
the existing `TransferRateEstimator` output:

- [ ] Bluetooth Classic 4.x devices: ~1 Mbps sustained.
- [ ] Bluetooth Classic 5.x devices: ~2 Mbps sustained.

These match the project README's documented expectation for the slow
fallback rung; any throughput much below ~500 kbps suggests the OS is
silently degrading to BR (instead of EDR) and warrants investigation.

---

## Logcat tags worth watching

```bash
adb logcat -s WvmgBtRfcomm WvmgDiscovery WvmgOutbound
```

`WvmgBtRfcomm` is the dedicated tag for this provider; the others give
context on the surrounding connection lifecycle.

---

## Known platform quirks

- **`BluetoothAdapter.getAddress()` returns the sentinel
  `02:00:00:00:00:00` on API 23+** unless the caller holds the
  un-grantable `LOCAL_MAC_ADDRESS` permission (system apps only). The
  provider treats the sentinel as "no usable MAC" and returns `null`
  from `prepareUpgrade()`. On affected devices the upgrade simply does
  not happen — the framework stays on Wi-Fi LAN. This is per-design
  and not a regression.
- **Some manufacturers (notably older Samsung / vivo builds)** do not
  expose the local MAC at all — `adapter.address` returns an empty
  string. Same fallback applies.
- **Cancelling a pending `BluetoothSocket.connect()`** requires closing
  the socket from another thread; coroutine cancellation alone does not
  unblock the syscall (this is the close-before-cancel pattern called
  out in the project's `CLAUDE.md`). The orchestrator (#54) will own
  that close-on-cancel behaviour.
