/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress(
    "ComplexCondition",
    "DEPRECATION",
    "MagicNumber",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package io.github.kyujincho.wvmg.discovery.bootstrap

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.location.nearby.mediums.proto.BleFramesProto
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.NearbyBleSocketFrames
import io.github.kyujincho.wvmg.protocol.transport.ConnectedTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Sender-side BLE GATT initial-control client for stock Quick Share receivers.
 *
 * Nearby's BLE v2 path first opens a small Weave-over-GATT socket on the
 * connectable `0xFEF3` advertiser, then wraps the normal Nearby multiplex
 * socket inside that stream. This client performs that bootstrap and exposes
 * the accepted virtual socket as a [ConnectedTransport] for the existing
 * outbound protocol driver.
 */
public class BleGattInitialControlClient internal constructor(
    private val appContext: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public constructor(context: Context) : this(context.applicationContext, Dispatchers.IO)

    private val activeTransport: AtomicReference<BleGattClientTransport?> = AtomicReference(null)

    public suspend fun connect(macAddress: String): ConnectedTransport? =
        withContext(dispatcher) {
            if (!isAvailable()) {
                Log.w(TAG, "BLE GATT initial connect unavailable")
                return@withContext null
            }
            val adapter =
                appContext
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?: return@withContext null
            val device =
                try {
                    adapter.getRemoteDevice(macAddress)
                } catch (badAddress: IllegalArgumentException) {
                    Log.w(TAG, "invalid BLE GATT address=$macAddress", badAddress)
                    return@withContext null
                }
            val transport =
                BleGattClientTransport(
                    context = appContext,
                    device = device,
                    onClose = { activeTransport.compareAndSet(it, null) },
                )
            activeTransport.getAndSet(transport)?.close()
            if (!transport.start()) {
                transport.close()
                return@withContext null
            }
            val ready = transport.awaitReady(CONNECTION_READY_TIMEOUT_MILLIS)
            if (!ready) {
                Log.w(TAG, "BLE GATT initial connect timed out waiting for socket ready")
                transport.close()
                return@withContext null
            }
            Log.w(TAG, "BLE GATT initial connect ready mac=$macAddress")
            transport
        }

    public fun cancelPendingConnect() {
        activeTransport.getAndSet(null)?.close()
    }

    private fun isAvailable(): Boolean {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !checkSPermission()) return false
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        private const val TAG: String = "WvmgBleGattClient"
        private const val CONNECTION_READY_TIMEOUT_MILLIS: Long = 10_000L
    }
}

private class BleGattClientTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onClose: (BleGattClientTransport) -> Unit,
) : BluetoothGattCallback(),
    ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private val ready = CompletableDeferred<Boolean>()
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private val incomingMessage = ArrayList<Byte>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var toPeripheral: BluetoothGattCharacteristic? = null
    private var fromPeripheral: BluetoothGattCharacteristic? = null
    private var serviceDiscoveryStarted: Boolean = false
    private var slotCharacteristics: List<BluetoothGattCharacteristic> = emptyList()
    private var slotReadIndex: Int = 0
    private var writeInFlight: Boolean = false
    private var mtuPayloadSize: Int = WeaveFrames.DEFAULT_ATT_PAYLOAD_SIZE
    private var negotiatedPacketSize: Int = WeaveFrames.MIN_PACKET_SIZE
    private var sendPacketCounter: Int = 0
    private var weaveConnected: Boolean = false

    @Volatile
    private var closed: Boolean = false

    override val medium: Medium = Medium.BLE

    override val inputStream: InputStream = input

    override val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()))
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                if (len == 0) return
                sendSocketServiceBytes(b.copyOfRange(off, off + len))
            }

            override fun flush() {
                // GATT writes are queued immediately by write().
            }

            override fun close() {
                this@BleGattClientTransport.close()
            }
        }

    fun start(): Boolean {
        val phyMask = BluetoothDevice.PHY_LE_2M_MASK or BluetoothDevice.PHY_LE_1M_MASK
        val opened =
            device.connectGatt(
                context,
                false,
                this,
                BluetoothDevice.TRANSPORT_LE,
                phyMask,
                mainHandler,
            )
        gatt = opened
        // Force a GATT cache refresh as soon as the connection is
        // available. Android's BLE stack caches discovered services
        // per-MAC across connections; if a previous bootstrap to the
        // same MAC populated the cache with stale handles (e.g. before
        // Samsung published the slot characteristics), our subsequent
        // service discovery will reuse those handles and the
        // characteristic-handle that Samsung's `gchu` is keyed on may
        // not match anymore — which would manifest as `No handler
        // registered for characteristic …` even though the UUIDs all
        // match. The `BluetoothGatt.refresh()` method is hidden but
        // accessible via reflection.
        if (opened != null) {
            runCatching {
                val refresh = BluetoothGatt::class.java.getMethod("refresh")
                val ok = refresh.invoke(opened) as? Boolean
                Log.w(TAG, "BluetoothGatt.refresh() invoked result=$ok")
            }.onFailure { Log.w(TAG, "refresh() reflection failed", it) }
        }
        return opened != null
    }

    suspend fun awaitReady(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) { ready.await() } == true

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        Log.w(TAG, "gatt state device=${device.safeAddress()} status=$status newState=$newState")
        if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
            close()
            return
        }
        // Stock Quick Share receivers (notably Samsung One UI 8.x on
        // Galaxy S22/S26 firmware) register their per-characteristic
        // GATT handlers (`00000100-…-0101` / `…-0102`) lazily after the
        // GATT connection comes up — typically ~1.5 s after
        // `onServerConnectionState(connected=true)` on Samsung. If we
        // start the MTU exchange / CCCD write / Weave CONNECTION_REQUEST
        // immediately, our writes land on the wire before the handler
        // is registered and Samsung logs
        // `No handler registered for characteristic 00000100-…-0101`
        // and silently drops them; the remote ATT layer still ACKs the
        // write so our local stack reports success but Samsung's app
        // never sees the bytes, and the Weave CONNECTION_CONFIRM never
        // comes back. Hold the next step on a 1.5 s grace window so
        // Samsung has enough time to wire its handler and accept the
        // ATT writes when they finally arrive.
        // Keep the connection in CONNECTION_PRIORITY_HIGH (interval=6)
        // for the entire bootstrap. Without this, Samsung downgrades to
        // interval=24 (~30 ms) right after the initial activity burst,
        // which seems to coincide with the per-peer Weave handler not
        // being wired in `gchk`. Stock GMS centrals stay in HIGH for
        // the duration of the Weave + multiplex + Nearby ConnectionRequest
        // exchange and only drop priority once they switch to the
        // upgraded medium.
        runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            .onFailure { Log.w(TAG, "requestConnectionPriority(HIGH) threw", it) }
        mainHandler.postDelayed({
            if (closed) return@postDelayed
            Log.w(TAG, "post-connect grace expired; requesting MTU device=${device.safeAddress()}")
            val requested = gatt.requestMtu(REQUESTED_MTU)
            if (!requested) discoverServicesOnce(gatt)
        }, POST_CONNECT_DELAY_MILLIS)
    }

    override fun onMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        Log.w(TAG, "mtu changed mtu=$mtu status=$status device=${device.safeAddress()}")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mtuPayloadSize = (mtu - GATT_ATT_HEADER_BYTES).coerceAtLeast(WeaveFrames.DEFAULT_ATT_PAYLOAD_SIZE)
        }
        discoverServicesOnce(gatt)
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "service discovery failed status=$status")
            close()
            return
        }
        // Dump every advertised service + characteristic so we can spot
        // peer-side variations in the Weave UUID family. Samsung GMS
        // sometimes exposes non-standard write/notify pairs alongside
        // the canonical 00000100-…-0101 / -0102, and our writes silently
        // fail with `No handler registered` if we picked the wrong pair.
        for (s in gatt.services) {
            val charDump =
                s.characteristics.joinToString(",") { c ->
                    "${c.uuid}(props=${c.properties})"
                }
            Log.w(TAG, "gatt service ${s.uuid} chars=[$charDump]")
        }
        if (!bindWeaveAndSlotsAcrossServices(gatt)) {
            Log.w(TAG, "BLE GATT bootstrap missing Weave write/notify characteristics")
            close()
            return
        }
        // Stock Quick Share centrals read the receiver's
        // `0xFEF3` advertisement-slot characteristics
        // (`00000000-0000-3000-8000-00000000000{0..4}`) BEFORE writing
        // anything to the Weave write characteristic. On Samsung One UI
        // 8.x this read sequence is what triggers the receiver-side
        // GMS to register the per-peer Weave handler — without it our
        // first CCCD/CONNECTION_REQUEST writes get
        // `gchu.onCharacteristicWriteRequest → No handler registered`
        // even though pulse classification is type=NOTIFY and the
        // peer is in EVERYONE-visibility mode. Drain the slot reads
        // in sequence, then proceed to CCCD subscribe + Weave
        // CONNECTION_REQUEST in `onAllSlotsRead`.
        if (slotCharacteristics.isEmpty()) {
            Log.w(TAG, "no advertisement slot characteristics found; subscribing immediately")
            subscribeAndConnect(gatt)
            return
        }
        slotReadIndex = 0
        readNextSlot(gatt)
    }

    private fun readNextSlot(gatt: BluetoothGatt) {
        val slot = slotCharacteristics.getOrNull(slotReadIndex)
        if (slot == null) {
            Log.w(TAG, "all advertisement slots read; waiting before CCCD subscribe")
            // Hold another grace window before CCCD: stock GMS receivers
            // may take additional time after the slot reads to wire the
            // per-peer Weave write handler in `gchi`. Without this
            // second grace, our CCCD/CONNECTION_REQUEST writes still
            // race the registration and get dropped with `No handler
            // registered for characteristic …-0101`.
            mainHandler.postDelayed({
                if (closed) return@postDelayed
                Log.w(TAG, "post-slot grace expired; subscribing CCCD")
                subscribeAndConnect(gatt)
            }, POST_SLOT_DELAY_MILLIS)
            return
        }
        Log.w(TAG, "reading advertisement slot[$slotReadIndex] uuid=${slot.uuid}")
        if (!gatt.readCharacteristic(slot)) {
            Log.w(TAG, "readCharacteristic returned false for slot[$slotReadIndex]; skipping rest")
            subscribeAndConnect(gatt)
        }
    }

    private fun subscribeAndConnect(gatt: BluetoothGatt) {
        val outgoing = fromPeripheral ?: return close()
        if (!gatt.setCharacteristicNotification(outgoing, true)) {
            Log.w(TAG, "setCharacteristicNotification returned false")
            close()
            return
        }
        val descriptor = outgoing.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            Log.w(TAG, "notification descriptor missing")
            close()
            return
        }
        descriptor.value = cccdValueFor(outgoing)
        if (!gatt.writeDescriptor(descriptor)) {
            Log.w(TAG, "write notification descriptor returned false")
            close()
        }
    }

    @Deprecated("Android calls this overload before API 33")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        handleSlotRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handleSlotRead(gatt, characteristic, value, status)
    }

    private fun handleSlotRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        Log.w(
            TAG,
            "slot read uuid=${characteristic.uuid} status=$status " +
                "len=${value.size} preview=${value.previewHex()}",
        )
        slotReadIndex++
        readNextSlot(gatt)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID || status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "descriptor write failed uuid=${descriptor.uuid} status=$status")
            close()
            return
        }
        sendWeaveConnectionRequest()
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        synchronized(this) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "characteristic write failed status=$status")
                close()
                return
            }
            Log.w(TAG, "characteristic write complete device=${device.safeAddress()}")
            drainWritesLocked()
        }
    }

    @Deprecated("Android calls this overload before API 33")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        handleNotification(characteristic.value ?: ByteArray(0))
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        handleNotification(value)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (!ready.isCompleted) ready.complete(false)
        runCatching { inputWriter.close() }
        runCatching { input.close() }
        val openGatt = gatt
        gatt = null
        writeQueue.clear()
        runCatching { openGatt?.disconnect() }
        runCatching { openGatt?.close() }
        onClose(this)
    }

    private fun discoverServicesOnce(gatt: BluetoothGatt) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        if (!gatt.discoverServices()) {
            Log.w(TAG, "discoverServices returned false")
            close()
        }
    }

    /**
     * Stock Quick Share peripherals (Samsung One UI included) expose
     * **two** services on `0xFEF3`: one carrying the Weave write/notify
     * pair (`00000100-0004-1000-8000-001a11000101`/`-0102`) and a
     * separate "advertisement slot" service carrying read-only
     * characteristics under `00000000-0000-3000-8000-00000000000{0..4}`.
     * `BluetoothGatt.getService(uuid)` only returns the first match, so
     * iterate every service the peer published and pick the one that
     * actually has the Weave UUIDs as Weave, then collect any other
     * service's read-only chars as slots.
     */
    private fun bindWeaveAndSlotsAcrossServices(gatt: BluetoothGatt): Boolean {
        val slots = mutableListOf<BluetoothGattCharacteristic>()
        gatt.services
            .filter { it.uuid == BleGattInitialControlServer.SERVICE_UUID }
            .forEach { service -> bindServiceShape(service, slots) }
        slotCharacteristics = slots
        Log.w(TAG, "bound Weave chars=${toPeripheral != null}/${fromPeripheral != null} slots=${slots.size}")
        return toPeripheral != null && fromPeripheral != null
    }

    private fun bindServiceShape(
        service: BluetoothGattService,
        slots: MutableList<BluetoothGattCharacteristic>,
    ) {
        val toPer = service.getCharacteristic(BleGattInitialControlServer.TO_PERIPHERAL_UUID)
        val fromPer = service.getCharacteristic(BleGattInitialControlServer.FROM_PERIPHERAL_UUID)
        if (toPer != null && fromPer != null && toPeripheral == null) {
            toPeripheral = toPer
            fromPeripheral = fromPer
            return
        }
        // Slot 0 (`00000000-0000-3000-8000-000000000000`) is the
        // EndpointInfo slot every Quick Share peripheral populates.
        // Slots 1-4 are present but typically empty. Stock GMS
        // centrals appear to only read the populated slots; reading
        // empty ones might confuse Samsung's per-peer state tracking.
        val slot0 = service.getCharacteristic(SLOT_0_UUID)
        if (slot0 != null) slots += slot0
    }

    private fun cccdValueFor(characteristic: BluetoothGattCharacteristic): ByteArray =
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

    @Synchronized
    private fun sendWeaveConnectionRequest() {
        negotiatedPacketSize =
            minOf(WeaveFrames.MAX_PACKET_SIZE, mtuPayloadSize)
                .coerceAtLeast(WeaveFrames.MIN_PACKET_SIZE)
        enqueueWriteLocked(
            WeaveFrames.connectionRequest(
                packetCounter = sendPacketCounter,
                minVersion = WeaveFrames.VERSION,
                maxVersion = WeaveFrames.VERSION,
                maxPacketSize = negotiatedPacketSize,
            ),
        )
        sendPacketCounter = (sendPacketCounter + 1) % WeaveFrames.MAX_PACKET_COUNTER
        // Stock Quick Share receivers (Samsung One UI 8.x) register
        // their per-characteristic GATT handlers reactively when the
        // first ATT write lands. The first 1-3 writes always fail
        // silently from the receiver's app point of view (the BLE link
        // ACKs them, but `gchu.onCharacteristicWriteRequest` throws
        // `No handler registered for characteristic 00000100-…-0101`),
        // and the receiver only registers the
        // `BlockingQueueStream with size 10` after parsing one of these
        // failed writes. By that time our CONNECTION_REQUEST is already
        // lost and we sit waiting for a CONNECTION_CONFIRM that will
        // never come. Schedule a retry so a second CONNECTION_REQUEST
        // lands on the now-registered handler. We re-enqueue up to
        // [WEAVE_REQUEST_MAX_RETRIES] times, gated on
        // `weaveConnected`; the receive callback aborts the retry as
        // soon as the real CONNECTION_CONFIRM lands.
        scheduleWeaveConnectionRequestRetryLocked(attempt = 1)
    }

    private fun scheduleWeaveConnectionRequestRetryLocked(attempt: Int) {
        if (attempt > WEAVE_REQUEST_MAX_RETRIES) return
        mainHandler.postDelayed({
            synchronized(this) {
                if (closed || weaveConnected) return@synchronized
                Log.w(
                    TAG,
                    "no Weave CONNECTION_CONFIRM after attempt=$attempt; " +
                        "resending CONNECTION_REQUEST device=${device.safeAddress()}",
                )
                enqueueWriteLocked(
                    WeaveFrames.connectionRequest(
                        packetCounter = sendPacketCounter,
                        minVersion = WeaveFrames.VERSION,
                        maxVersion = WeaveFrames.VERSION,
                        maxPacketSize = negotiatedPacketSize,
                    ),
                )
                sendPacketCounter = (sendPacketCounter + 1) % WeaveFrames.MAX_PACKET_COUNTER
                scheduleWeaveConnectionRequestRetryLocked(attempt + 1)
            }
        }, WEAVE_REQUEST_RETRY_DELAY_MILLIS)
    }

    @Synchronized
    private fun handleNotification(packetBytes: ByteArray) {
        if (closed) return
        Log.w(TAG, "notification len=${packetBytes.size} header=${packetBytes.firstByteHex()}")
        when (val packet = WeaveFrames.parse(packetBytes)) {
            is WeavePacket.ConnectionConfirm -> handleConnectionConfirm(packet)
            is WeavePacket.Data -> handleDataPacket(packet)
            is WeavePacket.ConnectionClose -> close()
            is WeavePacket.ConnectionRequest -> Unit
            null -> Log.w(TAG, "invalid Weave packet ${packetBytes.toHex()}")
        }
    }

    private fun handleConnectionConfirm(packet: WeavePacket.ConnectionConfirm) {
        if (packet.version != WeaveFrames.VERSION) {
            Log.w(TAG, "unsupported Weave version=${packet.version}")
            close()
            return
        }
        negotiatedPacketSize =
            packet.packetSize
                .takeIf { it >= WeaveFrames.MIN_PACKET_SIZE }
                ?.coerceAtMost(WeaveFrames.MAX_PACKET_SIZE)
                ?: WeaveFrames.MIN_PACKET_SIZE
        weaveConnected = true
        Log.w(TAG, "Weave connected raw Nearby stream packetSize=$negotiatedPacketSize device=${device.safeAddress()}")
        sendWeaveMessage(NearbyBleSocketFrames.encodeIntroductionPacket())
        // Samsung's GMS raw Nearby handler expects the first app payload
        // immediately after the socket-introduction packet. Waiting after
        // the intro write leaves the BLE Weave stream open, but the
        // ConnectionRequestFrame is never dispatched to Nearby Connections.
        if (!ready.isCompleted) ready.complete(true)
    }

    private fun handleDataPacket(packet: WeavePacket.Data) {
        if (packet.firstPacket) incomingMessage.clear()
        incomingMessage.addAll(packet.data.asList())
        if (!packet.lastPacket) return

        val message = incomingMessage.toByteArray()
        incomingMessage.clear()
        handleSocketMessage(message)
    }

    private fun handleSocketMessage(message: ByteArray) {
        if (message.size < SERVICE_ID_HASH_LEN) {
            Log.w(TAG, "discarded undersized BLE GATT socket message")
            close()
            return
        }
        val prefix = message.copyOfRange(0, SERVICE_ID_HASH_LEN)
        when {
            prefix.contentEquals(CONTROL_PACKET_PREFIX) -> handleControlPacket(message)
            prefix.contentEquals(NearbyServiceId.hashPrefix) -> {
                val payload = message.copyOfRange(SERVICE_ID_HASH_LEN, message.size)
                sendWeaveMessage(NearbyBleSocketFrames.encodePacketAcknowledgementPacket(payload.size))
                feedIncoming(payload)
            }
            else -> {
                Log.w(TAG, "discarded BLE GATT socket message for unexpected service hash")
                close()
            }
        }
    }

    private fun handleControlPacket(packet: ByteArray) {
        val control = NearbyBleSocketFrames.parseControlPacket(packet)
        if (control == null) {
            Log.w(TAG, "BLE GATT unknown control packet raw=${packet.toHex()}")
            return
        }
        when (control.type) {
            BleFramesProto.SocketControlFrame.ControlFrameType.PACKET_ACKNOWLEDGEMENT -> {
                Log.w(TAG, "BLE GATT packet ack size=${control.packetAcknowledgement.receivedSize}")
            }
            BleFramesProto.SocketControlFrame.ControlFrameType.DISCONNECTION -> close()
            else -> Log.w(TAG, "BLE GATT control frame type=${control.type}")
        }
    }

    private fun feedIncoming(bytes: ByteArray) {
        if (closed) return
        try {
            inputWriter.write(bytes)
            inputWriter.flush()
        } catch (io: IOException) {
            Log.w(TAG, "BLE GATT virtual input closed while feeding data", io)
            close()
        }
    }

    private fun sendSocketServiceBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Log.w (not Log.i) here is the Funtouch OS / vivo workaround from PR #144:
        // Funtouch filters Log.i for non-system apps, leaving us blind to the
        // outbound write path during BLE GATT bootstrap diagnostics. Log.w
        // also lands in `getExternalFilesDir(null)/wvmg-outbound.log` via
        // OutboundConnection's logger so on-device logs survive a logcat
        // flush. The function split (sendSocketServiceBytes vs.
        // sendSocketServiceMessage) is from PR #146, where new callers send
        // already-prefixed socket payloads and want the inner helper without
        // the extra logging.
        Log.w(TAG, "BLE GATT service write bytes=${bytes.size} preview=${bytes.previewHex()}")
        sendSocketServiceMessage(bytes)
    }

    private fun sendSocketServiceMessage(payload: ByteArray) {
        sendWeaveMessage(NearbyServiceId.hashPrefix + payload)
    }

    @Synchronized
    private fun sendWeaveMessage(payload: ByteArray) {
        if (closed || !weaveConnected || payload.isEmpty()) return
        var offset = 0
        do {
            val maxChunkSize = (negotiatedPacketSize - WeaveFrames.HEADER_SIZE).coerceAtLeast(1)
            val chunkSize = maxChunkSize.coerceAtMost(payload.size - offset)
            val nextOffset = offset + chunkSize
            enqueueWriteLocked(
                WeaveFrames.dataPacket(
                    packetCounter = sendPacketCounter,
                    firstPacket = offset == 0,
                    lastPacket = nextOffset >= payload.size,
                    data = payload.copyOfRange(offset, nextOffset),
                ),
            )
            sendPacketCounter = (sendPacketCounter + 1) % WeaveFrames.MAX_PACKET_COUNTER
            offset = nextOffset
        } while (offset < payload.size)
    }

    private fun enqueueWriteLocked(packet: ByteArray) {
        if (closed) return
        // Log.w (not Log.i): same Funtouch OS / vivo workaround as
        // sendSocketServiceBytes above — Log.i is filtered by Funtouch
        // for non-system apps, leaving us blind to the per-packet
        // enqueue path during BLE GATT bootstrap diagnostics.
        Log.w(
            TAG,
            "enqueue write len=${packet.size} header=${packet.firstByteHex()} device=${device.safeAddress()}",
        )
        writeQueue.add(packet)
        drainWritesLocked()
    }

    private fun drainWritesLocked() {
        if (closed || writeInFlight) return
        val packet = if (writeQueue.isEmpty()) return else writeQueue.removeFirst()
        val openGatt = gatt ?: return close()
        val outgoing = toPeripheral ?: return close()
        outgoing.writeType = writeTypeFor(outgoing)
        outgoing.value = packet
        Log.i(
            TAG,
            "write characteristic len=${packet.size} header=${packet.firstByteHex()} " +
                "type=${outgoing.writeType} properties=${outgoing.properties} device=${device.safeAddress()}",
        )
        writeInFlight = openGatt.writeCharacteristic(outgoing)
        if (!writeInFlight) {
            Log.w(TAG, "writeCharacteristic returned false")
            close()
        }
    }

    private companion object {
        private const val TAG: String = "WvmgBleGattClient"
        private const val REQUESTED_MTU: Int = 512
        private const val GATT_ATT_HEADER_BYTES: Int = 3
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
        private const val SERVICE_ID_HASH_LEN: Int = 3
        private const val POST_CONNECT_DELAY_MILLIS: Long = 600L
        private const val POST_SLOT_DELAY_MILLIS: Long = 300L
        private const val WEAVE_REQUEST_RETRY_DELAY_MILLIS: Long = 1000L
        private const val WEAVE_REQUEST_MAX_RETRIES: Int = 10
        private val CONTROL_PACKET_PREFIX: ByteArray = ByteArray(SERVICE_ID_HASH_LEN)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SLOT_0_UUID: UUID =
            UUID.fromString("00000000-0000-3000-8000-000000000000")
    }
}

private fun writeTypeFor(characteristic: BluetoothGattCharacteristic): Int =
    if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    } else {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }

private fun ArrayList<Byte>.toByteArray(): ByteArray {
    val out = ByteArray(size)
    for (index in indices) out[index] = this[index]
    return out
}

private fun BluetoothDevice.safeAddress(): String = runCatching { address }.getOrNull() ?: "<unknown>"

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.firstByteHex(): String = firstOrNull()?.let { "%02x".format(it) } ?: "--"

private fun ByteArray.previewHex(limit: Int = 16): String = copyOfRange(0, size.coerceAtMost(limit)).toHex()
