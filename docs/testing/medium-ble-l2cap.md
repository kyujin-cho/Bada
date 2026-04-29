# BLE L2CAP CoC medium — manual on-device verification

Issue: #52 (Phase 4 — Alternative bandwidth-upgrade mediums).

This runbook covers the receiver / sender bring-up and a smoke
throughput measurement for the BLE L2CAP Connection-Oriented Channel
medium added by `BleL2capMediumProvider`.

## Prerequisites

- Two Android devices, both **API 29+** (Android 10 or newer). The
  `listenUsingInsecureL2capChannel` / `createInsecureL2capChannel` APIs
  used by the provider are API 29+; on earlier devices `isSupported`
  returns false and the medium is silently omitted from the
  `ConnectionRequestFrame.mediums` advertisement.
- Both devices have **Bluetooth turned on**, are paired (recommended
  but not required — insecure L2CAP does not need pairing) and within
  ~10 m of each other.
- The receiver's `BLUETOOTH_CONNECT` runtime permission is granted (the
  permission is declared by `:discovery-android`'s manifest and merges
  into the host app on API 31+; pre-31 install-time grant is automatic).
- The orchestrator-side bandwidth-upgrade path (#54) is wired up in
  the build under test. Until #54 lands, only the **registration** /
  **wire-credentials** parts of this runbook are exercisable.

## Provider sanity check

On each device, with a debug build that includes `:discovery-android`:

```bash
adb logcat -s WvmgMedium WvmgDiscovery
```

When the app launches and the medium registry is constructed, the
`BleL2capMediumProvider` must be registered. Confirm by running the
included unit tests on a host machine (these do not need a device):

```bash
./gradlew :discovery-android:testDebugUnitTest --tests \
  '*.medium.BleL2capMediumProviderTest'
./gradlew :core-protocol:test --tests \
  '*.connection.BandwidthUpgradeFramesTest.BLE L2CAP*'
```

All BLE-L2CAP-tagged test cases must pass — the JVM tests cover every
`isSupported` / `prepareUpgrade` / `adoptUpgrade` branch and the
encoder/decoder round-trip.

## Receiver-side bring-up

1. On the receiver, start a Quick Share file-receive session as you
   would for a Wi-Fi-LAN transfer. The foreground notification should
   appear with the standard "Ready to receive" copy.
2. With `adb logcat -s WvmgMedium`, look for a line containing
   `BleL2capMediumProvider.prepareUpgrade()` followed by a non-zero
   PSM (decimal), for example:

   ```
   I/WvmgMedium: prepareUpgrade -> BleL2cap(psm=0x1080, mac=02:00:00:00:00:00)
   ```

   The MAC will read back as the system sandbox sentinel
   (`02:00:00:00:00:00`) on API 23+ — that is expected and does not
   prevent the sender from connecting (`BluetoothAdapter.getRemoteDevice`
   only requires a syntactically valid MAC; identity is anchored on the
   peer-supplied UKEY2 keys, not the BD_ADDR).

3. The receiver should NOT produce a stack trace from
   `BluetoothAdapter.listenUsingInsecureL2capChannel()`. If it does,
   confirm BLUETOOTH_CONNECT is granted at runtime
   (`adb shell pm grant <package> android.permission.BLUETOOTH_CONNECT`).

## Sender-side connect

1. Start a share intent on the sender pointing at the receiver picked
   from the Quick Share peer list. Wait for the BLE pulse to wake the
   receiver and for the standard mDNS / TCP / UKEY2 handshake to
   complete.
2. After consent, the orchestrator will issue a
   `BANDWIDTH_UPGRADE_NEGOTIATION{UPGRADE_PATH_AVAILABLE}` containing
   `medium=BLUETOOTH (2)` (BLE_L2CAP rides on the BLUETOOTH wire slot
   because `UpgradePathInfo.Medium` reserves wire 10) plus
   `bluetooth_credentials.service_name = "L2CAP:<psm>"` and the
   receiver MAC.
3. The sender's `BleL2capMediumProvider.adoptUpgrade(...)` then calls
   `BluetoothDevice.createInsecureL2capChannel(psm).connect()`. The
   logcat line to look for:

   ```
   I/WvmgMedium: adoptUpgrade BleL2cap psm=0x1080 mac=02:00:00:... -> connected
   ```

4. Subsequent payload bytes flow over the L2CAP CoC channel until
   either side issues a `Disconnection` frame.

## Throughput sanity measurement

The medium is documented at "a few Mbps with proper MTU + connection
interval tuning". To measure actual throughput once #54 lands:

1. Use a 50 MB binary fixture (`docs/testing/fixtures/50mb.bin`,
   created with `dd if=/dev/urandom of=50mb.bin bs=1M count=50`).
2. Force the medium ladder to put `BLE_L2CAP` first via the developer
   menu (or pass a custom `MediumLadder` to `MediumRegistries`).
3. Send the fixture twice; record `wall_clock` from the
   "Transfer started" log line to "Transfer complete" on both sides.
4. Throughput = `50 MB / wall_clock_seconds`. Record results with
   device pair, Bluetooth chipset, and Android version.

Expected baseline on BT 5+ phones (e.g. Pixel 7 ↔ Pixel 8, Galaxy S22 ↔
Galaxy S23) is **2–3 Mbps**. Older BT 4.2 chipsets cap closer to 700
kbps, putting BLE L2CAP on par with RFCOMM (#51); the ladder's
preference for L2CAP-over-RFCOMM only pays off on BT 5 hardware.

## Failure modes worth a look

- **`onStartFailure(...)` from the LE stack** — typically means BLE
  hardware is busy (existing GATT subscriptions). The provider's
  `isSupported` does not detect this; the upgrade will fail at
  `prepareUpgrade` returning `null`, and the framework should fall
  back to the next ladder rung. Confirm by toggling Bluetooth off/on
  and retrying.
- **PSM = 0** — some kernels report this as a sentinel "no PSM
  available" value when out of L2CAP slots. The provider rejects
  PSM 0 in [UpgradePathCredentials.BleL2cap]'s init validator and
  closes the listener; expect a clean fall-through.
- **Connect timeout on sender** — Android's `connect()` blocks
  indefinitely. If the receiver's listener was torn down between
  `prepareUpgrade` and the sender's `adoptUpgrade`, the sender's
  blocking `connect()` will hang. The orchestrator (#54) is
  responsible for wrapping the call in a timeout; until then this is
  a known limitation.
