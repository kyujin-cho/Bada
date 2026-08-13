/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
// BluetoothGattCharacteristic.value get/set + the no-value writeCharacteristic overload are
// deprecated on API 33+; we keep them for the minSdk-24 path and pass values explicitly where
// the new overloads exist. Suppressed file-wide to keep the BLE plumbing readable.
@file:Suppress("DEPRECATION")

package dev.bluehouse.bada.namecard

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.namecard.NameCard
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Name Card Bluetooth exchange** — carries the contact card between two Bada
 * phones AFTER an NFC tap has triggered them and shared a rendezvous token
 * ([dev.bluehouse.bada.protocol.namecard.NameCardBootstrap]). NFC is only the
 * trigger; Bluetooth carries the card.
 *
 * ## Security model (token-authenticated symmetric consent — the ONLY protocol)
 * The 16-byte rendezvous token travels exclusively over the NFC tap (proximity
 * bound). On BLE, the server advertises only an 8-byte SHA-256 *derivation* of
 * the token ([NameCardTokens.advertisedId]) so the token itself is never
 * broadcast. A connecting client must present the full token inside its HELLO
 * write; the server verifies it in constant time and DISCONNECTS any peer whose
 * first consent message is not a valid token-bearing HELLO. The card
 * characteristic answers `GATT_READ_NOT_PERMITTED` unless BOTH
 *  (a) the reading peer presented the correct token, and
 *  (b) the local user has tapped **Share** ([transmitCard] opened the gate).
 * Inbound card writes are accepted only from the authenticated peer. There is
 * no unauthenticated/legacy fallback of any kind.
 *
 * Roles follow the NFC roles:
 *  - [startServerV2] — the CARD phone (the tapped, HCE side). Advertises the
 *    derived token id and runs a GATT server with the gated CARD characteristic
 *    plus the CONSENT characteristic (WRITE + NOTIFY).
 *  - [startClientV2] — the READER phone. Scans for the derived token id,
 *    connects, subscribes to CONSENT, and authenticates with a HELLO carrying
 *    the full token.
 *
 * Effects from [NameCardConsentMachine] map here: `SendChoice`→[sendLocalChoice],
 * `TransmitCard`→[transmitCard], `CloseLink`→[sendByeAndClose]. Peer events go
 * out via [ConsentBleListener] (main thread).
 *
 * Permissions (already declared): BLUETOOTH_ADVERTISE (server), BLUETOOTH_SCAN
 * (client), BLUETOOTH_CONNECT (both GATT). Runtime-checked; a missing one logs
 * and returns false rather than throwing.
 *
 * ## STATUS — COMPILE-ONLY / UNVERIFIED
 * There is no Bluetooth radio or second phone in the build env, so NONE of the
 * BLE path is exercised here. Standard Android BLE APIs; connect / advertise /
 * scan / MTU / long-read behaviour is device-verified only. Driven by
 * [NameCardExchangeService] (server) and [NameCardTransferActivity] (client); a
 * ShareRadioController in each forces Bluetooth on before this runs.
 */
@Suppress("TooManyFunctions", "ReturnCount", "MagicNumber")
internal class NameCardBleExchange(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    /** Safety-timeout handler: auto-[stop] a session that never completes (battery backstop). */
    private val mainHandler = Handler(Looper.getMainLooper())

    // Server-side handles.
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null

    // Client-side handles.
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    /** Non-null only in a live session; the coordinator we raise peer events to (main-thread). */
    private var consentListener: ConsentBleListener? = null

    /** This session's own card, kept so a machine `TransmitCard` effect can send/serve it. */
    private var v2LocalCard: NameCard? = null

    /** This session's rendezvous token (from the NFC tap); the HELLO auth reference. */
    private var sessionToken: ByteArray? = null

    // @Volatile on the fields written on one thread (main / a binder callback) and read on another,
    // so the CARD-read gate and notify target are never read stale.

    /** Server: whether OUR user tapped Share — one half of the CARD-read gate. */
    @Volatile private var v2LocalSharing = false

    /** Server: the bytes served on a CARD read — the (field-filtered) card set by [transmitCard]. */
    @Volatile private var v2ServeBytes: ByteArray? = null

    /** Server: the peer that presented a valid token in HELLO — the other half of the gate. */
    @Volatile private var v2AuthedDevice: BluetoothDevice? = null

    /** Server handles for the CONSENT characteristic + the device that subscribed to its notifies. */
    private var v2ServerConsentChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2SubscribedDevice: BluetoothDevice? = null

    /** Client handles: the peer's CONSENT + CARD characteristics once services are discovered. */
    @Volatile private var v2ClientConsentChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2ClientCardChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2ClientGatt: BluetoothGatt? = null

    /** Client: once-only guard so a second scan result never overwrites a live GATT connection. */
    private val connectAttempted = AtomicBoolean(false)

    /**
     * Client single-GATT-op serializer: Android allows one outstanding GATT operation
     * per connection; a second before the prior callback SILENTLY fails. Every client GATT op goes
     * through [enqueueClientOp]; each completion callback calls [clientOpDone] to flush the next.
     */
    private var v2OpInFlight = false
    private val v2OpQueue = ArrayDeque<() -> Unit>()

    /** Client: true while the HELLO write is outstanding, so its ack becomes the link-ready signal. */
    @Volatile private var v2AwaitingHelloAck = false

    /** Fire [ConsentBleListener.onLinkReady] exactly once per session. */
    @Volatile private var v2ReadyFired = false

    /** True in a session once we know our role — server advertises+serves, client scans+connects. */
    private var v2IsServer = false

    /**
     * Card side: advertise the token derivation + serve a GATT service holding the CARD
     * characteristic (read gated on peer token auth AND our user's Share) and the CONSENT
     * characteristic (WRITE+NOTIFY). Peer events reach [listener] on the main thread. Returns false
     * if BLE is unavailable.
     */
    fun startServerV2(
        localCard: NameCard,
        token: ByteArray,
        listener: ConsentBleListener,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(advertisePermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "serverV2: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "serverV2: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }
        v2IsServer = true
        consentListener = listener
        v2LocalCard = localCard
        sessionToken = token.copyOf()
        val server =
            manager.openGattServer(appContext, serverCallbackV2()) ?: run {
                DiagnosticLog.w(TAG, "serverV2: openGattServer null → skip")
                running.set(false)
                return false
            }
        gattServer = server
        // CARD: READ (gated in the handler on token auth + local Share) + WRITE (peer's card back).
        // NO value bake — the handler is the single source.
        val cardChar =
            BluetoothGattCharacteristic(
                CARD_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        // CONSENT: WRITE (client→server messages) + NOTIFY (server→client), with the CCCD.
        val consentChar =
            BluetoothGattCharacteristic(
                CONSENT_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also {
                it.addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            }
        v2ServerConsentChar = consentChar
        val service =
            BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).also {
                it.addCharacteristic(cardChar)
                it.addCharacteristic(consentChar)
            }
        server.addService(service)

        val advertiser =
            adapter.bluetoothLeAdvertiser ?: run {
                DiagnosticLog.w(TAG, "serverV2: no advertiser → skip")
                stop()
                return false
            }
        this.advertiser = advertiser
        val cb = advertiseCallbackImpl()
        advertiseCallback = cb
        return try {
            advertiser.startAdvertising(advertiseSettings(), advertiseData(token), cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS_V2)
            DiagnosticLog.w(TAG, "serverV2: advertising token id + serving CARD(gated)+CONSENT")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "serverV2: startAdvertising threw: ${t.message}")
            stop()
            false
        }
    }

    /**
     * Reader side: scan for the derived token id, connect, discover services, subscribe to CONSENT,
     * and authenticate with a HELLO carrying the full [token]. A server without the CONSENT
     * characteristic is not a valid peer — the session stops. Peer events reach [listener] on the
     * main thread.
     */
    fun startClientV2(
        token: ByteArray,
        listener: ConsentBleListener,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(scanPermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "clientV2: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "clientV2: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }
        val scanner =
            adapter.bluetoothLeScanner ?: run {
                DiagnosticLog.w(TAG, "clientV2: no scanner → skip")
                running.set(false)
                return false
            }
        v2IsServer = false
        consentListener = listener
        sessionToken = token.copyOf()
        this.scanner = scanner
        val filter =
            ScanFilter
                .Builder()
                .setServiceData(ParcelUuid(SERVICE_DATA_UUID), NameCardTokens.advertisedId(token))
                .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    if (!running.get()) return
                    // Once-only: a burst of scan results must not spawn parallel GATT clients.
                    if (!connectAttempted.compareAndSet(false, true)) return
                    runCatching { scanCallback?.let { scanner.stopScan(it) } }
                    DiagnosticLog.w(TAG, "clientV2: token id match → connecting")
                    v2ClientGatt = result.device.connectGatt(appContext, false, gattClientCallbackV2())
                }

                override fun onScanFailed(errorCode: Int) {
                    DiagnosticLog.w(TAG, "clientV2: scan failed code=$errorCode")
                }
            }
        scanCallback = cb
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS_V2)
            DiagnosticLog.w(TAG, "clientV2: scanning for token id")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "clientV2: startScan threw: ${t.message}")
            stop()
            false
        }
    }

    /** Machine `SendChoice` effect: tell the peer my choice (server notifies; client writes). */
    fun sendLocalChoice(share: Boolean) {
        val bytes =
            NameCardConsentCodec.encode(
                if (share) ConsentMessage.ChoiceShare else ConsentMessage.ChoiceReceiveOnly,
            )
        if (v2IsServer) notifyConsent(bytes) else enqueueClientOp { writeConsent(bytes) }
    }

    /**
     * Machine `TransmitCard` effect: send my card. Server opens its gated CARD read (sets
     * [v2LocalSharing] and snapshots the — possibly field-filtered — [localCard] as the served
     * bytes). Client WRITES its card to the peer's CARD characteristic.
     */
    fun transmitCard(localCard: NameCard) {
        v2LocalCard = localCard
        if (v2IsServer) {
            v2ServeBytes = localCard.serialize()
            v2LocalSharing = true
            DiagnosticLog.w(TAG, "serverV2: TransmitCard → CARD read gate open")
        } else {
            enqueueClientOp { writeCard(localCard.serialize()) }
        }
    }

    /**
     * Machine `CloseLink` effect: send BYE (so the peer knows it's a clean finish, not a drop), then
     * tear down after a short grace so any final in-flight card read/write drains.
     */
    fun sendByeAndClose() {
        val bye = NameCardConsentCodec.encode(ConsentMessage.Bye)
        if (v2IsServer) notifyConsent(bye) else enqueueClientOp { writeConsent(bye) }
        mainHandler.postDelayed({ stop() }, SESSION_CLOSE_GRACE_MS)
    }

    /** Tear down all BLE handles. Idempotent. */
    fun stop() {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        consentListener = null
        v2LocalCard = null
        sessionToken = null
        v2LocalSharing = false
        v2ServeBytes = null
        v2AuthedDevice = null
        v2ServerConsentChar = null
        v2SubscribedDevice = null
        v2ClientConsentChar = null
        v2ClientCardChar = null
        v2OpInFlight = false
        v2OpQueue.clear()
        v2AwaitingHelloAck = false
        v2ReadyFired = false
        connectAttempted.set(false)
        runCatching { v2ClientGatt?.disconnect() }
        runCatching { v2ClientGatt?.close() }
        v2ClientGatt = null
        runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        scanner = null
        scanCallback = null
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        advertiser = null
        advertiseCallback = null
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    // ---- server GATT callback ----

    private fun serverCallbackV2(): BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DiagnosticLog.w(TAG, "serverV2: peer disconnected status=$status")
                    notifyDisconnected()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != CARD_CHARACTERISTIC_UUID) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                // Serve ONLY to the token-authenticated peer, and ONLY once our user tapped Share.
                val serveBytes = v2ServeBytes
                if (device != v2AuthedDevice || !v2LocalSharing || serveBytes == null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                    return
                }
                serveCard(device, requestId, offset, serveBytes)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                when (characteristic.uuid) {
                    CARD_CHARACTERISTIC_UUID -> {
                        // Inbound cards are accepted only from the token-authenticated peer.
                        if (device != v2AuthedDevice) {
                            DiagnosticLog.w(TAG, "serverV2: CARD write from unauthenticated peer → rejected")
                            if (responseNeeded) {
                                gattServer?.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                                    offset,
                                    null,
                                )
                            }
                            return
                        }
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        }
                        val peer = NameCard.parse(value)?.let { NameCardSanitizer.sanitize(it) }
                        if (peer != null) {
                            DiagnosticLog.w(TAG, "serverV2: peer card written (${value.size}B)")
                            notifyPeerCard(peer)
                        } else {
                            DiagnosticLog.w(TAG, "serverV2: peer wrote unparseable/oversized card (${value.size}B)")
                        }
                    }
                    CONSENT_CHARACTERISTIC_UUID -> {
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        }
                        handleServerConsent(device, value)
                    }
                    else -> {
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                        }
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                // TRAP: ALWAYS respond or the client's subscribe stalls forever. Subscription alone
                // grants nothing — link-ready (and every gate) waits for the token-bearing HELLO.
                v2SubscribedDevice = device
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

    /**
     * Server: decode a peer CONSENT write. The FIRST message from any peer must be a HELLO carrying
     * this session's token; anything else — or a wrong/missing token — rejects the peer and drops
     * the connection. Post-auth messages from a different device are ignored.
     */
    private fun handleServerConsent(
        device: BluetoothDevice,
        value: ByteArray?,
    ) {
        val msg = value?.let { NameCardConsentCodec.decode(it) }
        val authed = v2AuthedDevice
        if (authed == null) {
            if (msg is ConsentMessage.Hello && NameCardTokens.verify(sessionToken, msg.token)) {
                v2AuthedDevice = device
                DiagnosticLog.w(TAG, "serverV2: peer HELLO token verified")
                notifyPeerHello()
                notifyLinkReady()
            } else {
                DiagnosticLog.w(TAG, "serverV2: unauthenticated consent message → disconnecting peer")
                runCatching { gattServer?.cancelConnection(device) }
            }
            return
        }
        if (device != authed) {
            DiagnosticLog.w(TAG, "serverV2: consent message from a different device → ignored")
            return
        }
        when (msg) {
            is ConsentMessage.Hello -> Unit // duplicate HELLO from the authed peer: no-op
            ConsentMessage.ChoiceShare -> notifyPeerChoice(true)
            ConsentMessage.ChoiceReceiveOnly -> notifyPeerChoice(false)
            ConsentMessage.Bye -> notifyDisconnected()
            null -> DiagnosticLog.w(TAG, "serverV2: undecodable consent message (${value?.size ?: 0}B)")
        }
    }

    /** Server: answer a CARD read with the offset-aware slice (long-read across MTU). */
    private fun serveCard(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        cardBytes: ByteArray,
    ) {
        val ok = offset in 0..cardBytes.size
        val slice = if (ok) cardBytes.copyOfRange(offset, cardBytes.size) else ByteArray(0)
        val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
        gattServer?.sendResponse(device, requestId, status, offset, slice)
    }

    // ---- client GATT callback ----

    private fun gattClientCallbackV2(): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DiagnosticLog.w(TAG, "clientV2: disconnected status=$status")
                    notifyDisconnected()
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                val service = gatt.getService(SERVICE_UUID)
                val consent = service?.getCharacteristic(CONSENT_CHARACTERISTIC_UUID)
                val cardChar = service?.getCharacteristic(CARD_CHARACTERISTIC_UUID)
                if (consent == null || cardChar == null) {
                    // Not a token-authenticated Name Card peer — there is no fallback protocol.
                    DiagnosticLog.w(TAG, "clientV2: peer lacks the consent service → stop")
                    stop()
                    return
                }
                v2ClientConsentChar = consent
                v2ClientCardChar = cardChar
                v2ClientGatt = gatt
                gatt.setCharacteristicNotification(consent, true)
                val cccd = consent.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    DiagnosticLog.w(TAG, "clientV2: CONSENT has no CCCD → cannot subscribe")
                    stop()
                    return
                }
                enqueueClientOp { writeCccdEnable(gatt, cccd) }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                clientOpDone()
                // Subscribed → authenticate with the token-bearing HELLO; its write-ack = link-ready.
                val token = sessionToken ?: return
                v2AwaitingHelloAck = true
                enqueueClientOp { writeConsent(NameCardConsentCodec.helloBytes(token)) }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                clientOpDone()
                if (v2AwaitingHelloAck) {
                    v2AwaitingHelloAck = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        notifyLinkReady()
                    } else {
                        DiagnosticLog.w(TAG, "clientV2: HELLO write failed status=$status → stop")
                        stop()
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == CONSENT_CHARACTERISTIC_UUID) handleClientConsent(characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == CONSENT_CHARACTERISTIC_UUID) handleClientConsent(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                onClientCardRead(characteristic.value, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                onClientCardRead(value, status)
            }
        }

    /** Client: a CARD read completed — parse + surface the peer's card, then free the op slot. */
    private fun onClientCardRead(
        value: ByteArray?,
        status: Int,
    ) {
        clientOpDone()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            DiagnosticLog.w(TAG, "clientV2: CARD read failed status=$status")
            return
        }
        val peer = value?.let { NameCard.parse(it) }?.let { NameCardSanitizer.sanitize(it) }
        if (peer != null) {
            DiagnosticLog.w(TAG, "clientV2: read peer card (${value.size}B)")
            notifyPeerCard(peer)
        } else {
            DiagnosticLog.w(TAG, "clientV2: peer card unreadable/oversized")
        }
    }

    /** Client: decode a peer CONSENT notify → listener. */
    private fun handleClientConsent(value: ByteArray?) {
        when (val msg = value?.let { NameCardConsentCodec.decode(it) }) {
            is ConsentMessage.Hello -> DiagnosticLog.w(TAG, "clientV2: unexpected HELLO notify → ignored")
            ConsentMessage.ChoiceShare -> {
                notifyPeerChoice(true)
                // Peer shared ⇒ its card is now readable — go read it.
                val gatt = v2ClientGatt
                val card = v2ClientCardChar
                if (gatt != null && card != null) enqueueClientOp { gatt.readCharacteristic(card) }
            }
            ConsentMessage.ChoiceReceiveOnly -> notifyPeerChoice(false)
            ConsentMessage.Bye -> notifyDisconnected()
            null -> DiagnosticLog.w(TAG, "clientV2: undecodable consent message (${value?.size ?: 0}B)")
            else -> DiagnosticLog.w(TAG, "clientV2: unhandled consent message ${msg.javaClass.simpleName}")
        }
    }

    // ---- client single-op serializer (main-thread) ----

    private fun enqueueClientOp(op: () -> Unit) =
        runOnMain {
            if (v2OpInFlight) {
                v2OpQueue.addLast(op)
            } else {
                v2OpInFlight = true
                op()
            }
        }

    private fun clientOpDone() =
        runOnMain {
            v2OpInFlight = false
            val next = v2OpQueue.removeFirstOrNull() ?: return@runOnMain
            v2OpInFlight = true
            next()
        }

    private fun writeCccdEnable(
        gatt: BluetoothGatt,
        cccd: BluetoothGattDescriptor,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeDescriptor threw ${it.message}")
            clientOpDone()
        }
    }

    private fun writeConsent(bytes: ByteArray) {
        val gatt = v2ClientGatt
        val ch = v2ClientConsentChar
        if (gatt == null || ch == null) {
            clientOpDone()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(ch)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeConsent threw ${it.message}")
            clientOpDone()
        }
    }

    private fun writeCard(bytes: ByteArray) {
        val gatt = v2ClientGatt
        val ch = v2ClientCardChar
        if (gatt == null || ch == null) {
            clientOpDone()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(ch)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeCard threw ${it.message}")
            clientOpDone()
        }
    }

    private fun notifyConsent(bytes: ByteArray) {
        // Choices/BYE go only to the token-authenticated peer, never a bare subscriber.
        val dev = v2AuthedDevice?.takeIf { it == v2SubscribedDevice }
        val ch = v2ServerConsentChar
        val server = gattServer
        if (dev == null || ch == null || server == null) {
            DiagnosticLog.w(TAG, "serverV2: notify skipped (no authenticated subscriber)")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(dev, ch, false, bytes)
            } else {
                ch.value = bytes
                server.notifyCharacteristicChanged(dev, ch, false)
            }
        }.onFailure { DiagnosticLog.w(TAG, "serverV2: notify threw ${it.message}") }
    }

    // ---- listener marshaling (binder thread → main) ----

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun notifyLinkReady() =
        runOnMain {
            if (!v2ReadyFired) {
                v2ReadyFired = true
                consentListener?.onLinkReady()
            }
        }

    private fun notifyPeerHello() = runOnMain { consentListener?.onPeerHello() }

    private fun notifyPeerChoice(share: Boolean) = runOnMain { consentListener?.onPeerChoice(share) }

    private fun notifyPeerCard(card: NameCard) = runOnMain { consentListener?.onPeerCardArrived(card) }

    private fun notifyDisconnected() = runOnMain { consentListener?.onDisconnected() }

    // ---- advertising helpers (idiom from BleAdvertiser) ----

    private fun advertiseSettings(): AdvertiseSettings =
        AdvertiseSettings
            .Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

    /**
     * Advertise only the 8-byte SHA-256 derivation of the token — never the token itself (a BLE
     * sniffer must not learn the HELLO credential). The short id also keeps the 128-bit service-data
     * UUID + payload inside the 31-byte legacy advertisement.
     */
    private fun advertiseData(token: ByteArray): AdvertiseData =
        AdvertiseData
            .Builder()
            .addServiceData(ParcelUuid(SERVICE_DATA_UUID), NameCardTokens.advertisedId(token))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

    private fun advertiseCallbackImpl(): AdvertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                DiagnosticLog.w(TAG, "server: advertise onStartFailure code=$errorCode")
            }
        }

    // ---- permission helpers ----

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun advertisePermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            Manifest.permission.BLUETOOTH
        }

    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        }

    companion object {
        private const val TAG = "NameCardBle"

        /** Default ATT MTU to request so a ~200-byte card writes in one go. */
        private const val REQUESTED_MTU = 247

        /** GATT service holding the CARD + CONSENT characteristics. */
        val SERVICE_UUID: UUID = UUID.fromString("f0534443-0001-4000-8000-534443415244")

        /** CARD characteristic: READ serves our card (gated), WRITE receives the peer's. */
        val CARD_CHARACTERISTIC_UUID: UUID = UUID.fromString("f0534443-0002-4000-8000-534443415244")

        /**
         * Project-random 128-bit UUID under which the derived rendezvous id rides in advertisement
         * service data. Deliberately NOT a 16-bit SIG-style alias — those are Bluetooth SIG member
         * allocations this project holds no claim to (the repo's 0xFE2C/0xFEF3 use elsewhere is
         * deliberate Quick Share interop; this exchange is Bada-only).
         */
        val SERVICE_DATA_UUID: UUID = UUID.fromString("918f0bd7-3b58-46e5-ae4f-ad2206205c51")

        /**
         * CONSENT characteristic (WRITE + NOTIFY): the client WRITES its [NameCardConsentCodec]
         * messages here (starting with the token-bearing HELLO); the server NOTIFIES its own back.
         */
        val CONSENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("7b2fdd3e-9a41-4e2c-b7a4-5c1e6f3d0a11")

        /** Standard Client Characteristic Configuration Descriptor (enables NOTIFY on CONSENT). */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Session backstop: longer than the 30s UX timer so that resolves first. */
        private const val MAX_SESSION_MS_V2 = 60_000L

        /**
         * Grace after a `CloseLink`/BYE before the actual radio teardown, so any in-flight final
         * card read (server side, which has no read-completion callback) or write drains first. A
         * heuristic — TODO-DEVICE: tune against real two-phone timing.
         */
        private const val SESSION_CLOSE_GRACE_MS = 1_500L
    }
}

/**
 * Callback surface the BLE layer raises to the consent coordinator (the transfer activity via
 * [NameCardLinkHolder]). All methods are delivered on the MAIN thread (the exchange marshals from
 * the binder-thread GATT callbacks).
 */
internal interface ConsentBleListener {
    /**
     * The link can now carry a choice: the server verified the peer's token-bearing HELLO / the
     * client's HELLO write was acked. The UI keeps the Share/Receive-Only buttons disabled
     * ("Connecting…") until this fires so a fast tap is never lost before the transport is ready.
     */
    fun onLinkReady()

    /** The peer sent a HELLO whose token verified against this session. */
    fun onPeerHello()

    /** The peer reported its choice ([share] = Share, else Receive Only). */
    fun onPeerChoice(share: Boolean)

    /** The peer's card BYTES arrived, parsed, and passed sanitization. */
    fun onPeerCardArrived(card: NameCard)

    /** The link dropped or the peer sent BYE. */
    fun onDisconnected()
}
