# Samsung One UI BLE GATT bootstrap - cert-gate hypothesis

**Document type:** Reverse-engineering validation note
**Subject:** Samsung One UI Quick Share BLE GATT acceptance behavior
**Status:** Invalidated by live device testing on 2026-05-05
**Validated devices:** vivo X300 Ultra (`V2547A`, LibreDrop debug) -> Galaxy S26 Ultra (`SM-S948N`, stock Quick Share / ShareLive)
**Companion code:** `discovery-android/src/main/kotlin/dev/bluehouse/libredrop/discovery/bootstrap/BleGattInitialControlClient.kt`, `discovery-android/src/main/kotlin/dev/bluehouse/libredrop/discovery/ble/BleAdvertisePayload.kt`, `app/src/main/kotlin/dev/bluehouse/libredrop/send/SendBootstrapPlan.kt`, `app/src/main/kotlin/dev/bluehouse/libredrop/send/SendActivity.kt`

## Current conclusion

The previous version of this note claimed that LibreDrop, or any other
non-GMS sender, could not complete a BLE GATT bootstrap into a Samsung
Quick Share receiver because Samsung enforced a Google-account-bound
`SenderCertificate` gate before registering the per-peer Weave handler.

That conclusion is wrong for the validated Galaxy S26 Ultra + vivo X300
Ultra pair. A BLE-GATT-only transfer from LibreDrop on vivo to stock
Quick Share on Galaxy reached receiver consent and completed the file
transfer successfully.

The Galaxy still emitted repeated `gchm` logs containing
`BluetoothGattException: No handler registered for characteristic ...`.
Those logs therefore cannot be treated as proof of a fatal cert gate.
They can come from Samsung's advertisement-slot or wildcard GATT callback
while the real Weave/Nearby receive path is alive and processing the same
connection.

## Live-device evidence

Environment:

- Galaxy S26 Ultra was on the stock Quick Share receive screen and showed
  `Ready to receive`.
- vivo X300 Ultra ran `dev.bluehouse.libredrop.debug`.
- Both devices had Bluetooth enabled.
- Neither phone was connected to Wi-Fi for the successful transfer, so
  LibreDrop selected `route=ble-gatt`; no Wi-Fi LAN route was available.

Successful run:

- vivo selected the Galaxy row with subtitle `BLE GATT 22:F1:6B:8B:9F:C7`.
- Galaxy displayed the receiver prompt: `V2547A wants to share 1 file with
  you`, with PIN `7421`.
- After tapping `Accept` on Galaxy, vivo completed with `Sent successfully`.
- Galaxy completed with `1 file received from V2547A` and displayed
  `1.05 MB`.

Earlier failed rounds in the same debug loop were test-harness artifacts.
ADB-launched public `file://` and MediaStore `content://` URIs materialized
inside LibreDrop as zero-byte payloads, and Galaxy rejected the
`IntroductionFrame` because file size must be larger than zero. Copying the
test file into LibreDrop's app-private external files directory made the
same 1 MiB payload resolve as `size=1048576`, after which the BLE GATT
transfer succeeded.

## What the `No handler registered` log means now

Treat Samsung `gchm No handler registered` lines as diagnostic noise unless
they correlate with lack of protocol progress. They are useful only when
combined with sender and receiver state:

- If LibreDrop never reaches UKEY2 / `ConnectionResponse` / introduction
  milestones and Galaxy never shows a consent prompt, the log may still
  point at a GATT routing problem.
- If Galaxy shows the consent prompt, or either side reaches Nearby sharing
  frames, the same log line is not evidence that the receiver rejected the
  peer.
- UI state and LibreDrop outbound milestones are the source of truth for
  this path.

## Product behavior

Do not gate Samsung BLE-GATT-only peers behind a Wi-Fi warning. The normal
route order remains:

1. Wi-Fi LAN
2. BLE L2CAP
3. BLE GATT
4. Bluetooth Classic

Wi-Fi LAN is still preferred when available because it is cheaper and more
direct. When BLE GATT is the only route, Samsung peers should remain normal
selectable rows and should proceed without a Samsung-specific confirmation
dialog.

## Guidance for future debugging

Keep the sender FastInitiation pulse in the active-share shape:

- metadata byte 3 is `0x00` (`version=0`, `type=kNotify`, no flags)
- `secret_id_hash` is non-zero and derived from the sender endpoint ID
- the sender advertisement is connectable, matching stock Quick Share
  sender behavior

These fields are still important classification signals. The invalidated
part is the stronger claim that a non-GMS sender is cryptographically
blocked from Samsung's receiver path, or that `No handler registered` alone
proves such a block.

When validating this path on devices, avoid ADB public-storage `file://`
payloads unless the app can read them with a real grant. A reliable harness
payload is an app-private external file such as:

```text
/sdcard/Android/data/dev.bluehouse.libredrop.debug/files/samsung-cert-gate.bin
```

Launch it with `ACTION_SEND`, then verify in LibreDrop logs that the resolved
file size is non-zero before interpreting any receiver failure as a protocol
failure.
