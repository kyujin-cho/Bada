# Samsung BLE GATT limitation rebuttal

**Document type:** Contradiction report / interop evidence record
**Date:** 2026-05-05
**Status:** BLE GATT limitation claim contradicted by live-device success
**Scope:** vivo X300 Ultra (`V2547A`, LibreDrop debug) sending to Galaxy S26 Ultra (`SM-S948N`, stock Samsung Quick Share / ShareLive)
**Related document:** `docs/research/samsung-ble-gatt-cert-gate.md`

## Executive summary

The earlier Samsung BLE GATT limitation documentation claimed that a non-GMS
sender could not complete BLE GATT bootstrap into a Samsung Quick Share
receiver because Samsung enforced a Google-account-bound `SenderCertificate`
gate before registering the per-peer Weave handler.

Live-device testing contradicts that claim. LibreDrop on vivo completed a
Samsung receive flow over BLE GATT, upgraded to Wi-Fi Direct, streamed a
non-zero 1 MiB file, and both devices reached successful terminal UI states.
The same run still emitted Samsung `gchm No handler registered` logs, proving
that those logs are not sufficient evidence of a fatal receiver-side gate.

## Contradicted claim

The earlier limitation report asserted:

- Samsung BLE GATT receive from LibreDrop is impossible for non-GMS senders.
- `BluetoothGattException: No handler registered for characteristic ...`
  proves Samsung did not register the real Weave handler for the peer.
- Samsung BLE-GATT-only peers should be surfaced as a likely-failing route,
  with Wi-Fi recommended as the only supported user path.

The successful device run disproves all three conclusions for the validated
Galaxy S26 Ultra and vivo X300 Ultra pair.

## Live-device result

### Device state

- Galaxy S26 Ultra was on the stock Quick Share receive surface and showed
  `Ready to receive...`.
- vivo X300 Ultra ran `dev.bluehouse.libredrop.debug`.
- The payload was an app-private external file:
  `/sdcard/Android/data/dev.bluehouse.libredrop.debug/files/samsung-cert-gate.bin`.
- LibreDrop resolved the payload as `size=1048576`.
- LibreDrop selected `route=ble-gatt`; the planner logged
  `wifi-lan=missing` and `ble-l2cap=peer-psm-missing`, so this was not a
  Wi-Fi LAN send.

### User-visible success

- vivo picker row: `Kyujin's S26 Ultra`, subtitle `BLE GATT 08:42:18:5E:6C:0A`.
- No Samsung-specific warning dialog appeared.
- Galaxy consent prompt: `V2547A wants to share 1 file with you`, PIN `4129`.
- vivo terminal state: `Sent successfully`.
- Galaxy terminal state: `1 file received from V2547A`, `1.05 MB`.

### Sender-side protocol milestones

The vivo outbound log showed:

- `picked target ... route=ble-gatt=08:42:18:5E:6C:0A`
- `UKEY2 client handshake complete`
- `peer.response=ACCEPT status=0 osType=ANDROID`
- `D2D keys derived, PIN=4129`
- `fsm: effect=ReadyToSendPayloads`
- `medium-upgrade: client completed WIFI_DIRECT`
- `streamOneFile START name=samsung-cert-gate.bin size=1048576`
- `streamOneFile loop end chunks=3 bytesSent=1048576`
- `streamOneFile DONE name=samsung-cert-gate.bin`

### Galaxy-side protocol milestones

Galaxy logcat still contained the old scary signal:

```text
BluetoothGattException: No handler registered for characteristic 00000100-0004-1000-8000-001a11000101.
```

But the same log window also contained successful Nearby/ShareLive progress:

- `StartConnectToSender(senderId=9, endpointId=q6Jl, ...)`
- `ConnectionResolved(endpointId=q6Jl)`
- `ReceiveConnectSuccess(endpointId=q6Jl, ...)`
- `WaitForLocalConfirmation(... deviceName=V2547A ... size=1048576 ... token=4129 ...)`
- `Client accepted incoming file from ShareTarget<... deviceName: V2547A ... fileAttachmentSize: 1 ...>`
- `TRANSFER_FINISHED`
- ShareLive view state reached `previewStatus=SUCCESS`.

Therefore `No handler registered` is a noisy callback-level symptom, not a
terminal protocol verdict.

## Why the earlier document reached the wrong conclusion

### 1. A real log was treated as fatal

The decompilation-backed observation that Samsung logs through `gchm` was
real. The incorrect inference was that this log meant the actual Weave path
was absent. The successful run shows both can happen: a wildcard or slot GATT
callback can log `No handler registered` while another path processes Weave
and Nearby frames normally.

### 2. Negative rounds were generalized past their evidence

The old report listed many failed rounds that changed timing, CCCD ordering,
retry count, bonding, and FastInitiation bytes. Those rounds ruled out those
specific variants. They did not prove that Samsung BLE GATT receive was
cryptographically impossible for all non-GMS senders.

### 3. The test harness produced invalid payload metadata

Earlier ADB-launched public `file://` and MediaStore `content://` sends
materialized in LibreDrop as zero-byte payloads. Samsung then rejected the
introduction because file metadata with size `0` is invalid for a file
attachment. That failure mode was a harness artifact, not a BLE GATT
certificate gate.

The successful run used an app-private external file and verified
`resolvedSize=1048576` before interpreting protocol behavior.

### 4. The report was introduced alongside changing code

The limitation note entered the repository in the same large change series
that also added the FastInitiation pulse corrections, connectable sender
advertising, GATT bootstrap parity work, and BLE/Wi-Fi Direct handoff fixes.
The conclusion appears to have been carried forward from mid-investigation
state rather than revalidated after the combined final behavior was available.

### 5. Prior evidence already contradicted an absolute limitation

Earlier Samsung BLE GATT validation had already reached a successful picker,
accept prompt, and send completion path. That should have kept the
certificate-gate analysis framed as a hypothesis until the contradiction was
resolved with a controlled payload and final code.

## Product impact

Samsung BLE-GATT-only peers should not be blocked or demoted solely because
they are Samsung-class receivers. The sender route order remains:

1. Wi-Fi LAN
2. BLE L2CAP
3. BLE GATT
4. Bluetooth Classic

Wi-Fi LAN is still preferred when available, but BLE GATT is a valid route
when it is the selected reachable path and reaches receiver consent.

## Debugging guidance

Future Samsung BLE GATT investigations should use these acceptance signals:

- Payload resolution must show non-zero file sizes before starting protocol
  interpretation.
- UI state matters: Galaxy consent prompt, matching PIN, transfer progress,
  and terminal receive state outrank isolated low-level GATT errors.
- Sender milestones matter: UKEY2, `ConnectionResponse ACCEPT`, sharing FSM
  introduction, receiver response, Wi-Fi Direct upgrade, and completed stream.
- Samsung `No handler registered` logs are actionable only when they correlate
  with absence of Nearby frames and absence of UI progress.

## Code and documentation consequences

The following code behavior is supported by this report:

- `SendBootstrapPlan` should keep Samsung BLE GATT as a normal selectable
  route when it is the best available route.
- `SendActivity` should not show a Samsung-specific Wi-Fi warning before BLE
  GATT attempts.
- `SamsungQuickShareHeuristic` is not needed for route gating.
- `BleAdvertisePayload`, `BleAdvertiser`, and `BleGattInitialControlClient`
  should retain their active-share FastInitiation and protocol-parity behavior,
  but their comments should not claim that Samsung BLE GATT is blocked by an
  unavoidable cert gate.

The older `samsung-ble-gatt-cert-gate.md` document should be read as an
invalidated hypothesis record, not as a limitation statement.
