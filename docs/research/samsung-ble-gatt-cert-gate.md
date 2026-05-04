# Samsung One UI BLE GATT bootstrap — sender-certificate gate

**Document type:** Reverse-engineering analysis / interop limitation note
**Subject:** Samsung One UI 8.x Quick Share BLE GATT acceptance gate (per-peer Weave handler registration)
**Status:** GMS APK decompilation + on-device empirical verification (18 test rounds against Galaxy S26 Ultra running stock Quick Share / `com.samsung.android.app.sharelive`). Cross-checked against an independent parallel investigation on the same project on 2026-05-01..02 that arrived at the same `No handler registered` symptom from the protocol-timing angle but did not pivot to APK decompilation; this document's cert-gate analysis is unique to it.
**GMS version analyzed:** `com.google.android.gms@261731035@26.17.31 (260400-906420463)`
**Companion code:** `discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery/bootstrap/BleGattInitialControlClient.kt`, `discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery/ble/BleAdvertisePayload.kt`, `discovery-android/src/main/kotlin/io/github/kyujincho/wvmg/discovery/SamsungQuickShareHeuristic.kt`, `app/src/main/kotlin/io/github/kyujincho/wvmg/send/SendBootstrapPlan.kt`, `app/src/main/kotlin/io/github/kyujincho/wvmg/send/SendActivity.kt`
**Last verified:** 2026-05-04 (post-#142/#144/#146 merge to `main`)
**Audience:** Future contributors who would otherwise re-walk this loop trying to make WVMG's BLE GATT bootstrap reach a stock Samsung receiver.

> **TL;DR.** WVMG (or any non-GMS app) cannot complete a BLE GATT bootstrap into Samsung One UI's Quick Share receiver. The block is a Google-account-bound `SenderCertificate` lookup that gates per-peer Weave handler registration on the receiver. Without a certificate that Samsung's GMS already has in its `nearby_sharing_sender_certificate_book_*` files, every ATT write to characteristic `00000100-0004-1000-8000-001A11000101` is rejected with `BluetoothGattException: No handler registered for characteristic …`. The gate is cryptographic, not behavioral; protocol-level workarounds do not exist. The same finding is what `rquickshare`/`NearDrop` ran into and bailed on. Samsung is reachable from WVMG via Wi-Fi LAN (mDNS) without this restriction; that is the supported path.

---

## 1. Symptom

Sender (WVMG on a non-Samsung Android, e.g., vivo X300) detects a stock Galaxy S26 receiver by its BLE FastInitiation pulse on `0xFE2C`, picks a `route=ble-gatt` bootstrap, opens a GATT connection to the FEF3 service, and is rejected at the Weave layer. Concretely (timestamps from a single representative Round 16 capture):

```
[vivo  ] 01:02:29.385 enqueue write len=7 header=80           (Weave CONNECTION_REQUEST)
[vivo  ] 01:02:29.413 characteristic write complete            (link-layer ACK)
[vivo  ] 01:02:29.414 notification len=5 header=81             (Weave CONFIRM-shaped response)
[vivo  ] 01:02:29.445 enqueue write len=15 header=1c           (NearbyConnections introduction)
[vivo  ] 01:02:29.464 BLE GATT service write bytes=78          (ConnectionRequestFrame part 1)
[vivo  ] 01:02:29.469 enqueue write len=144 header=3c          (ConnectionRequestFrame part 2)
[vivo  ] 01:02:44.467 step 2: initial handshake timed out after 15000ms

[Galaxy] 01:02:30.063 gchm: Could not write descriptor 00002902-… on characteristic 00000100-…-0102 …
                      BluetoothGattException: No handler registered for characteristic 00000100-…-0102.
                          at gchi.a (29)
                          at gchk.c (26)
                          at gchu.onDescriptorWriteRequest (9)
                          at djpp.onDescriptorWriteRequest (68)
[Galaxy] 01:02:30.093 gchm: Could not write characteristic 00000100-…-0101 …
                      BluetoothGattException: No handler registered for characteristic 00000100-…-0101.
                          at gchi.a (29)
                          at gchk.b (15)
                          at gchu.onCharacteristicWriteRequest (9)
                          at djpp.onCharacteristicWriteRequest (68)
[Galaxy] 01:02:30.151 gchm: Could not write characteristic 00000100-…-0101 …  (4 more, one per write)
[Galaxy] 01:02:30.182 gchm: Could not write characteristic 00000100-…-0101 …
[Galaxy] 01:02:30.215 gchm: Could not write characteristic 00000100-…-0101 …
[Galaxy] 01:02:46.204 NearbyMediums: Weave socket onDisconnected callback is called, finish socket cleanup.
```

The error pattern is **bit-identical across rounds, peers, MACs, pulse contents, and timing tweaks** — the unmistakable signature of a deny-by-default ACL rather than a race or framing mistake.

---

## 2. The CONFIRM-shaped notification is not a sign of progress

`vivo` receives a 5-byte notification with header `0x81` ≈ 30 ms after writing CONNECTION_REQUEST. This decodes cleanly as Weave `CONNECTION_CONFIRM` v1 with `packetSize=509`. It is not, in fact, the Samsung GMS Weave-handshake handler responding to us:

- Round 18 ablation: skip the CCCD ATT write (still call `setCharacteristicNotification(true)` so BlueDroid forwards) and re-issue the CONNECTION_REQUEST. The CONFIRM-shaped notification **disappears entirely**, and our request retries fire 10× with no response. So the "CONFIRM" is contingent on the CCCD ATT write.
- Galaxy's gchm log shows the CCCD write itself being rejected with `No handler registered for characteristic 00000100-…-0102.` at the same instant the "CONFIRM" arrives at vivo.
- The `gchu.onDescriptorWriteRequest` rejection happens after gchu has already returned `GATT_SUCCESS` at the link layer (Android's `BluetoothGattServer$1` dispatches the success response synchronously before the gchk handler runs), so vivo's stack sees the descriptor write as successful and proceeds.

Working hypothesis: the `0x81 00 01 01 fd` indication is generated by a lower BlueDroid layer (or by Galaxy's Quick Share advertisement-slot server's wildcard handler reflexively echoing handshake state), not by an app-layer Weave handler. It is structural noise that decodes as a real CONFIRM by coincidence of the framing. The receiver-side per-peer dispatcher remains unregistered.

This is important because it explains why early rounds appeared to be "almost working" (we got CONFIRM, set `weaveConnected=true`, sent the introduction packet, then timed out at the next step). Subsequent writes were always going to be rejected — we just didn't notice the rejection because GMS doesn't propagate it to the link layer.

---

## 3. The gate, mechanically (GMS decompilation)

GMS's BLE GATT server stack on the receiver side is structured as follows (obfuscated names, classes11.dex unless noted):

```
djpq            singleton GATT-server multiplexer.
                Owns the single BluetoothGattServer instance and dispatches
                onCharacteristicWriteRequest / onDescriptorWriteRequest
                etc. across all registered BluetoothGattServerCallback
                instances based on UUID filtering.

djpp            djpq's BluetoothGattServerCallback implementation.
                Loops over djpq.e (registered callbacks) and routes to
                each callback whose hffc-set in djpq.f covers the target
                UUID (or to every callback if the registration was
                wildcard, hffcVar == null).

gchm            per-server wrapper. Holds:
                - g (gchc):   handler registry (service UUID → gchb)
                - h (gcht):   the actual BluetoothGattServer
                - i (Map):    peer device → gchi instance
                - d (gchk):   the BluetoothGattServerCallback for this
                              gchm; registered with djpq.

gchu            BluetoothGattServerCallback that just delegates to a
                gchv (gchk in practice).

gchk extends gchv     dispatch logic. On peer connect:
                gchk.d() → gchm.i.put(peer, new gchi(gchm, peer, gchm.g))
                On characteristic write:
                gchk.b() → gchi gchi = gchm.a(peer);
                          gchn handler = gchi.a(characteristic);  ← THROWS
                          handler.dispatch(...)

gchi            per-peer state. Built from gchm.g.a (a Map<UUID, gchb>)
                merged into a single Map<BluetoothGattCharacteristic, gchn>
                in gchi.g. The throw site:

                public final gchn a(BluetoothGattCharacteristic c)
                        throws BluetoothGattException {
                    gchn h = (gchn) this.g.get(c);
                    if (h != null) return h;
                    throw new BluetoothGattException(
                        "No handler registered for characteristic " + c.getUuid(),
                        6);
                }
```

There are **two `gchm` instances** on the wire that matter for Quick Share, registered with djpq independently:

1. **Slot / advertisement GATT server** — created in `dozb` via `doxx.a()`. Its `gchc.a` map registers handlers only for the slot characteristics `00000000-0000-3000-8000-00000000000{0..4}`. It is registered with djpq as a **wildcard** (`hffcVar = null`), so djpq routes *every* incoming UUID's events to it (because `djpq.h` falls back to `djpq.m`, the master union of FEF3 + Weave + slot UUIDs). This server is what holds slot 0 (the receiver's own EndpointInfo, served when a sender does an initial GATT-read to verify identity).

2. **Weave GATT server (BleServerSocket)** — created in `doza.a()` via `dpba(…, hdix, …)`. Its `hdix` instance has the actual handlers for `00000100-…-0101` (write) / `00000100-…-0102` (notify) and dispatches to `hdix.b()`. It is registered with djpq for the FEF3 service + Weave char UUIDs.

When a sender (us) writes to `0x0101`, djpp routes the event to **both** registered callbacks (slot-server wildcard + Weave server). The Weave server, *if running*, would handle it correctly — `hdix.b()` checks `this.d.equals(uuid)` (Weave WRITE char), looks up `hdiv` (the per-device Weave session), and dispatches the bytes onto its Executor for Weave parsing. The slot server has no handler for `0x0101` and throws.

**The actual gate is therefore: is the Weave server (`doza`/`dpba`) running for our peer?** The answer in our test runs is *no* — and that's the entire phenomenon. The throw we see is the slot-server's wildcard handler going through the motions in the absence of the Weave server.

---

## 4. Why the Weave server isn't started for our peer

`dozb.aa(serviceId, dkgkVar)` is the public method to *start accepting BLE GATT incoming connections* for a given service id. It calls `dozb.ab` which creates the `doza` MediumOperation, registers it with `dpde`, and stores `(serviceId, dkgkVar)` in `dozb.K`. `doza.a()` is what actually:

```java
hdix hdixVar = new hdix(uuidE, uuid, uuid2, new gchv());
djpqVarB.h(context, hdixVar.c, /* hffc UUIDs or null */);  // register with djpq
gcht gchtVarA = gcht.a(djpqVarB.a());                     // wrap the BluetoothGattServer
…
new doyz(this, dpbaVar).start();                          // accept loop
```

If `dozb.aa(...)` is never called for the service ID our pulse advertises, the Weave server never starts, `hdix.c` is never registered with djpq, and no `gchi.g` entry will ever exist for `0x0101` for our peer.

`dozb.aa` is invoked by the upper-layer NearbyConnections / NearbySharing receiver flow when a discovery target is decided to be acceptable for incoming BLE GATT bootstrap. The decision predicate is what we care about. From the on-device GMS log on Galaxy after our pulse stops:

```
NearbySharing: Detected fast init state changed: version=0, type=NOTIFY, state=LOST.
NearbySharing: isFastInitSilent is false.
NearbySharing: 'listSenderCertificates' succeeded for parent: users/me/devices/VEBNNCULWW, count = 6.
NearbySharing: Saved sender certificates to file
   nearby_sharing_sender_certificate_book_from_self_share.
NearbySharing: Saved sender certificates to file
   nearby_sharing_sender_certificate_book_from_all_contacts.
NearbySharing: Saved sender certificates to file
   nearby_sharing_sender_certificate_book_from_selected_contacts.
NearbySharing: 'listSenderCertificates' succeeded for parent: users/me/devices/VEBNNCULWW, count = 6.
```

Galaxy maintains a database of 6 `SenderCertificate` instances synced from Google's `nearbysharing-pa.googleapis.com` backend. The lookup key Galaxy uses to match an incoming pulse to one of these certs is the BLE FastInitiation `secret_id_hash` (bytes 15..22 of our pulse — the truncated SHA-256 of the cert's id). Galaxy classifies our pulse as `version=0, type=NOTIFY` (good — we got past the SILENT filter via `BleAdvertisePayload`'s byte-3 = `0x00` and non-zero hash), runs `secret_id_hash` against the cert book, **fails to match anything**, and never invokes `dozb.aa` for our peer. Hence: no Weave server, no `gchi` handler, deny-by-default at `gchi.a`.

This is consistent with every rounds-of-attempts observation — the rejection pattern doesn't change with stable identity, write order, CCCD timing, MTU, connection priority, PHY, or retries, because none of those touch the cert lookup.

---

## 5. The certificate model

```
┌────────────────────────────────────┐         nearbysharing-pa.googleapis.com
│  Device A (signed in to acct@…)    │   gRPC  location.nearby.sharing.v1.NearbySharingService
│  GMS:                              │  ────►  - UpdateDevice
│   gen keypair → KeyStore (TEE)     │  ◄────  - ListPublicCertificates
│   upload public cert (id, pubkey)  │         - ListSenderCertificates
│                                    │         - RegisterReceiver
└────────────────────────────────────┘         OAuth scope:
                                                 https://www.googleapis.com/auth/nearbysharing-pa
                                                 https://www.googleapis.com/auth/nearbypresence-pa

┌────────────────────────────────────┐
│  Device B (same acct or contact)   │   syncs A's cert into one of:
│  GMS:                              │     nearby_sharing_sender_certificate_book_from_self_share
│   ListSenderCertificates           │     nearby_sharing_sender_certificate_book_from_all_contacts
│                                    │     nearby_sharing_sender_certificate_book_from_selected_contacts
└────────────────────────────────────┘

When A sends to B:
  A's BLE pulse:
     secret_id_hash = SHA256(A.cert.id)[:8]   (bytes 15..22 of the 23-byte FastInit payload)

  B detects pulse, looks up secret_id_hash in its three cert books.
  ┌─ no match ─► (Samsung path) dozb.aa never called → no Weave server → 
  │              gchi.a throws "No handler registered" on every ATT write
  └─ match ────► dozb.aa(serviceId, callback) called → Weave server starts →
                 hdix registered → gchi.g has 0x0101 → writes dispatched →
                 PairedKeyEncryption signed with cert's private key (KeyStore alias) →
                 NearbyConnections handshake → file transfer
```

The cert id hash is what the pulse advertises; the cert's *private key* is also load-bearing — the receiver expects the sender to sign a `PairedKeyEncryptionFrame` with it during the Sharing.Nearby handshake. Spoofing only the hash gets you past the BLE GATT acceptance gate but stalls at PairedKeyEncryption.

---

## 6. The gate is not bypassable from a third-party app

We considered five paths and ruled each out:

### 6.1 Same Google account on both devices (self-share) — extract from local GMS
- Cert files live under `/data/data/com.google.android.gms/files/nearby/sharing/…` (private to GMS uid).
- Reading them requires root.
- Even with root, the cert's private key is in Android KeyStore under a GMS-uid-bound alias; on Tensor / StrongBox-backed devices the key material lives in the TEE and **cannot be exported even with root**. The KeyStore APIs let you *use* the key only if you're running as the gms uid.
- Outcome: spoofable hash, unreachable private key. Gets us past `gchi.a` but dies at PairedKeyEncryption.

### 6.2 Be in the user's Google Contacts — same as above
Mechanically identical: contact's GMS uploads, B's GMS syncs, WVMG would need *contact's* cert id + private key. Same dead-end.

### 6.3 Reverse-engineer Google's Nearby Sharing API and call it from WVMG
- gRPC service: `nearbysharing-pa.googleapis.com` (`location.nearby.sharing.v1.NearbySharingService`)
- Companion service for presence/contacts: `nearbypresence-pa.googleapis.com` (`location.nearby.presence.v1.NearbyPresenceService`)
- OAuth scopes:
  - `https://www.googleapis.com/auth/nearbysharing-pa`
  - `https://www.googleapis.com/auth/nearbypresence-pa`
- Methods we'd need: `UpdateDevice` (register WVMG's device under user's account), `RegisterReceiver`, `ListSenderCertificates`, plus our own keypair generation and cert upload.
- **The OAuth scopes are first-party-restricted.** `AccountManager.getAuthToken(account, "oauth2:https://…/nearbysharing-pa", …)` from a non-Google-signed app returns `INVALID_SCOPE` / consent denied. The whitelist is an internal Google list of GMS + a handful of OEM partner apps; there is no public application form.
- Outcome: cleanest design, completely closed off in practice.

### 6.4 Bind to GMS as a service via signature-restricted permission
- The relevant permission is `com.google.android.gms.permission.ACCESS_NEARBY_SHARE_API` declared in GMS's manifest with `protectionLevel="signature"`.
- Only apps signed with Google's platform signature can hold it. WVMG cannot.
- Outcome: closed.

### 6.5 Delegate to GMS Quick Share via `Intent.ACTION_SEND` / `com.google.android.gms.SHARE_NEARBY`
- The Quick Share entry point is `com.google.android.gms/.nearby.sharing.main.MainActivity` and its trampoline `…/.nearby.sharing.migration.TransparentTrampolineActivity`. Both accept `SEND` / `SEND_MULTIPLE` / `SHARE_NEARBY` / `nearby.sharing.UNIFIED` / `nearby.QUICK_SHARE` / `nearby.SEND_FOLDER`.
- On Pixel and Galaxy these activities are enabled by GMS's Chimera loader at runtime.
- **On vivo X300 (and most non-Google-partner Android) the activities are declared but `enabled=false` and Chimera never enables them.** We confirmed:
  ```
  $ adb -s vivo am start -a com.google.android.gms.SHARE_NEARBY -t image/* …
  Error: Activity not started, unable to resolve Intent

  $ adb -s vivo pm enable com.google.android.gms/.nearby.sharing.main.MainActivity
  SecurityException: Shell cannot change component state …
  ```
  `pm query-activities -a android.intent.action.SEND -t image/*` on vivo returns 56 hits, **none** of them Quick Share. The system share sheet has nothing to offer.
- Outcome: cannot delegate to GMS Quick Share on vivo because GMS never installs/enables the UI on this device class.

This is a Google-side gatekeeping decision (Chimera config), not something WVMG can route around at app level.

---

## 7. What rounds 1–18 actually ruled out

For the record so this loop doesn't get re-walked:

| Round | Lever | Outcome |
|------:|---|---|
| 1–9 | various delays between connect/MTU/discoverServices/CCCD/CONNECTION_REQUEST (0–2 s grids) | no change in rejection |
| 10 | `requestConnectionPriority(HIGH)` | no change |
| 11 | `PHY_LE_2M_MASK` on `connectGatt` | no change |
| 12 | skip CCCD subscribe entirely | breaks: no CONFIRM-shaped notification at all (proves CONFIRM is contingent on CCCD attempt, not on app-layer success) |
| 13 | `device.createBond()` before discoverServices | bond fails with "incorrect PIN" (Just-Works pairing mismatch); even when bond is active, gate doesn't open |
| 14 | spin up sender-side GATT server hosting our own FEF3 + slot 0 with our EndpointInfo | Galaxy connects to our server but does not read or write — confirming Galaxy isn't waiting on us to expose anything |
| 15 | byte 3 = `0x20` (Weave version=1) | Galaxy stops detecting the pulse entirely (worse) |
| 16 | extend Weave CONNECTION_REQUEST retries from 3×700 ms to 10×1000 ms | retry timer never fires because we falsely set `weaveConnected=true` from the phantom CONFIRM |
| 17 | stable `endpointId` across runs (SharedPreferences-backed); same secret_id_hash on every attempt | no change — gate is not "has-this-hash-been-seen-recently" |
| 18 | no CCCD ATT write at all (only `setCharacteristicNotification(true)`); resend CONNECTION_REQUEST 10× | no CONFIRM, all attempts time out, peer sees nothing useful (proves CCCD is necessary plumbing but not sufficient) |

The only rounds that *materially changed* something on Galaxy's side were:
- byte-3 fix (`0x01` → `0x00`): pulse classification flips from `SILENT` to `NOTIFY` (necessary precondition; not sufficient)
- non-zero `secret_id_hash`: same precondition flip (necessary; not sufficient)

Both of those are kept in `BleAdvertisePayload`. They are real interop wins that benefit non-Samsung peers too.

---

## 8. What actually works against Samsung today

| Direction | Wi-Fi LAN (mDNS) | BLE GATT |
|---|---|---|
| Galaxy → WVMG (vivo as receiver) | ✅ works | ✅ works as of PR #146 (`fix: complete Galaxy to vivo BLE GATT handoff`) — WVMG-as-receiver doesn't enforce a cert gate on incoming peers; the Galaxy-side sender stack completes Weave handshake into our `BleGattInitialControlServer`, then upgrades to Wi-Fi Direct via the `BandwidthUpgrade*` flow. The cert-gate is a Samsung-*receiver* policy, not a Samsung-sender one. |
| WVMG (vivo) → Galaxy | ✅ works (Galaxy's Wi-Fi LAN acceptance path doesn't enforce the cert gate; mDNS-discovered peers complete the Sharing.Nearby handshake without certificate match) | ❌ blocked by §3–§4 above |

So for the user's sending-from-non-Samsung-to-Samsung case, **Wi-Fi LAN is the supported route**. WVMG's existing implementation handles it. No additional certificate or GMS dependency is required.

---

## 9. Product behavior

### Shipped

WVMG's peer picker classifies Samsung-class peers via
`SamsungQuickShareHeuristic.isLikelySamsungReceiver(peer)` — a
word-boundary-anchored matcher over `EndpointInfo.deviceName` and the
BLE fast-advertisement display name covering `Galaxy`, `S20`–`S29`,
`Note 10/20`, `Z Fold`, `Z Flip`, `A`/`M`/`F` series, and `Tab S`. The
matcher is conservative — `Pixel`, `OnePlus`, `Sony Xperia`, `Xiaomi`,
`Redmi Note`, `vivo`, `Huawei`, `Oppo` do not match; tests in
`SamsungQuickShareHeuristicTest.kt` lock both the positive and
negative cases.

When a Samsung-class peer is reachable only via BLE GATT,
`SendBootstrapPlan` sets `samsungBleGattCaveat = true` and the picker
subtitle reads `"BLE GATT <mac> — Wi-Fi recommended for Samsung"`. On
tap, `SendActivity.confirmSamsungBleGattAttempt` raises an
`AlertDialog` with three buttons:

- **Open Wi-Fi settings** — `startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))`.
- **Try anyway** — proceeds with the BLE GATT attempt regardless (leaves a door open for the rare environment where the cert match might happen to land, or for future Samsung policy loosening).
- **Cancel** — dismiss, picker stays up.

Wi-Fi LAN is still tried first when available — the caveat only
surfaces when BLE GATT is the only direct route the planner could pick
(mirroring `SendBootstrapPlan.directRoute`'s ordering: LAN → BLE
L2CAP → BLE GATT → Bluetooth Classic).

### Not shipped (future enhancements considered)

- **Greying out** the Samsung BLE-GATT-only row instead of the
  confirmation dialog. We picked the dialog because greying-out kills
  the "Try anyway" affordance without explaining anything; the dialog
  is the same number of taps but carries the explanation.
- **Demoting the BLE-GATT route after a fast-fail probe** (e.g., write
  CONNECTION_REQUEST and abort if no real Weave data frame arrives
  ~5 s after the phantom CONFIRM, instead of waiting the full
  15 s `step 2: initial handshake timed out` window). Worth doing as a
  follow-up if the dialog still leaves users with a long-feeling
  failure path; current `BleGattInitialControlClient` retains the 15 s
  timeout (`step 2: initial handshake timed out after 15000ms`).

---

## 10. Cross-references

- `discovery-android/.../discovery/ble/BleAdvertisePayload.kt` — the 23-byte FastInitiation pulse format with the byte-3 fix and `secret_id_hash`. Kdoc on that file documents *why* each byte matters (which Samsung empirics it preserves).
- `discovery-android/.../discovery/bootstrap/BleGattInitialControlClient.kt` — the central-side BLE GATT bootstrap (writes Weave CONNECTION_REQUEST, expects CONFIRM). The `WEAVE_REQUEST_MAX_RETRIES` and post-connect / post-slot grace constants are residue from rounds 1–18 trying to find a timing window; they're harmless for Pixel and the rare working Samsung-edge cases, but they do not help against the Samsung cert gate. Documented in this file for context.
- `discovery-android/.../discovery/bootstrap/BleGattInitialControlServer.kt` — the receiver-side server. WVMG **does not** enforce a cert gate on incoming peers; this is intentional — WVMG-as-receiver wants to accept anyone, including Samsung senders that *do* have a valid cert chain in their own GMS. PR #146 completes the Galaxy → vivo Weave handshake into this server, plus the Wi-Fi Direct upgrade flow that follows.
- `discovery-android/.../discovery/SamsungQuickShareHeuristic.kt` (+ `SamsungQuickShareHeuristicTest.kt`) — the model-name pattern matcher used to detect Samsung-class peers. The patterns and false-positive guards are defended by tests; update both together when Samsung adds a new model line.
- `app/.../send/SendBootstrapPlan.kt` — the `samsungBleGattCaveat: Boolean` field on the plan, set when `route is BleGatt && SamsungQuickShareHeuristic.isLikelySamsungReceiver(peer)`. Drives both the picker subtitle and the dialog gate in `SendActivity`.
- `app/.../send/SendActivity.kt` (`confirmSamsungBleGattAttempt`, `openWifiSettings`, `proceedWithPeer`) — the `AlertDialog` that intercepts taps on Samsung BLE-GATT-only peers, plus the `Settings.ACTION_WIFI_SETTINGS` deeplink.
- `app/src/main/res/values/strings.xml` — the `send_samsung_ble_warning_*` strings shown in the dialog.

External corroboration: `rquickshare`'s README explicitly bails on Samsung BLE GATT with the note "Samsung did something shady"; `NearDrop` has no Samsung BLE GATT story at all. Both projects independently arrived at the same wall via different paths; this document captures *exactly which wall*, so the next contributor doesn't have to.
