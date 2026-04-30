/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("DEPRECATION", "MagicNumber", "TooManyFunctions")
@file:android.annotation.SuppressLint("MissingPermission")

package io.github.kyujincho.wvmg.discovery.bootstrap

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.location.nearby.mediums.proto.BleFramesProto
import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import io.github.kyujincho.wvmg.protocol.endpoint.BleServiceData
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.NearbyBleSocketFrames
import io.github.kyujincho.wvmg.protocol.medium.NearbyMultiplexFrames
import io.github.kyujincho.wvmg.protocol.transport.ConnectedTransport
import io.github.kyujincho.wvmg.protocol.transport.InitialControlServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Receiver-side BLE GATT initial-control server for stock Nearby senders.
 *
 * Stock Quick Share's off-LAN path connects to a connectable `0xFEF3`
 * advertiser and establishes a small Weave-over-GATT socket before the normal
 * Nearby OfflineFrame stream begins. This server implements that physical
 * GATT socket and demultiplexes the first virtual Nearby socket back into the
 * existing [ConnectedTransport] pipeline.
 */
public class BleGattInitialControlServer(
    context: Context,
    private val endpointIdProvider: () -> ByteArray,
) : InitialControlServer {
    private val appContext: Context = context.applicationContext
    private val active: AtomicReference<ActiveState?> = AtomicReference(null)
    private val lifecycleLock: Any = Any()

    override val isActive: Boolean
        get() = active.get() != null

    override fun start(
        endpointInfo: EndpointInfo,
        acceptTransport: (ConnectedTransport) -> Unit,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (isActive) return true
            if (!isAvailable()) {
                Log.i(TAG, "BLE GATT bootstrap unavailable")
                return false
            }
            val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
            val callback =
                Callback(
                    endpointInfo = endpointInfo,
                    endpointId = endpointIdProvider(),
                    acceptTransport = acceptTransport,
                )
            val server =
                manager.openGattServer(appContext, callback)
                    ?: run {
                        Log.w(TAG, "BluetoothManager.openGattServer returned null")
                        return false
                    }
            callback.attach(server)
            if (!server.addService(callback.service)) {
                Log.w(TAG, "BluetoothGattServer.addService failed")
                server.close()
                return false
            }
            val state = ActiveState(server = server, callback = callback)
            if (!active.compareAndSet(null, state)) {
                server.close()
                callback.close()
                return true
            }
            Log.i(TAG, "BLE GATT bootstrap active service=$SERVICE_UUID")
            true
        }

    override fun stop() {
        synchronized(lifecycleLock) {
            val state = active.getAndSet(null) ?: return
            state.callback.close()
            runCatching { state.server.clearServices() }
            runCatching { state.server.close() }
        }
    }

    @Suppress("ReturnCount")
    private fun isAvailable(): Boolean {
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        if (!hasConnectPermission()) return false
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSPermission()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    private data class ActiveState(
        val server: BluetoothGattServer,
        val callback: Callback,
    )

    private class Callback(
        endpointInfo: EndpointInfo,
        endpointId: ByteArray,
        private val acceptTransport: (ConnectedTransport) -> Unit,
    ) : BluetoothGattServerCallback() {
        val service: BluetoothGattService = buildService(endpointInfo, endpointId)

        private val sessions: ConcurrentHashMap<String, BleWeaveGattSession> = ConcurrentHashMap()
        private val fromPeripheral: BluetoothGattCharacteristic =
            requireNotNull(service.getCharacteristic(FROM_PERIPHERAL_UUID))
        private var server: BluetoothGattServer? = null

        fun attach(server: BluetoothGattServer) {
            this.server = server
        }

        fun close() {
            sessions.values.forEach { it.close() }
            sessions.clear()
            server = null
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            Log.i(TAG, "gatt state device=${device.safeAddress()} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sessionFor(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessions.remove(device.key())?.close()
            }
        }

        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService,
        ) {
            Log.i(TAG, "service added status=$status uuid=${service.uuid}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Log.i(TAG, "read characteristic=${characteristic.uuid} offset=$offset device=${device.safeAddress()}")
            val value = characteristic.value ?: ByteArray(0)
            val response =
                if (offset in 0..value.size) {
                    value.copyOfRange(offset, value.size)
                } else {
                    ByteArray(0)
                }
            server?.sendResponse(
                device,
                requestId,
                if (offset in 0..value.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                offset,
                response,
            )
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
            Log.i(
                TAG,
                "write characteristic=${characteristic.uuid} len=${value.size} header=${value.firstByteHex()} " +
                    "offset=$offset prepared=$preparedWrite response=$responseNeeded " +
                    "device=${device.safeAddress()}",
            )
            val status =
                if (characteristic.uuid == TO_PERIPHERAL_UUID && offset == 0) {
                    sessionFor(device).onIncomingWeavePacket(value)
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_FAILURE
                }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, status, offset, null)
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
            Log.i(
                TAG,
                "write descriptor=${descriptor.uuid} char=${descriptor.characteristic?.uuid} " +
                    "value=${value.toHex()} offset=$offset device=${device.safeAddress()}",
            )
            val enablesNotifications =
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                descriptor.characteristic?.uuid == FROM_PERIPHERAL_UUID
            ) {
                sessionFor(device).setSubscribed(enablesNotifications)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(
            device: BluetoothDevice,
            mtu: Int,
        ) {
            Log.i(TAG, "mtu changed mtu=$mtu device=${device.safeAddress()}")
            sessionFor(device).setMtu(mtu)
        }

        override fun onNotificationSent(
            device: BluetoothDevice,
            status: Int,
        ) {
            sessionFor(device).onNotificationSent(status)
        }

        private fun sessionFor(device: BluetoothDevice): BleWeaveGattSession {
            val gattServer = server ?: error("GATT server not attached")
            return sessions.getOrPut(device.key()) {
                BleWeaveGattSession(
                    device = device,
                    server = gattServer,
                    outgoingCharacteristic = fromPeripheral,
                    acceptTransport = acceptTransport,
                )
            }
        }

        private companion object {
            private fun buildService(
                endpointInfo: EndpointInfo,
                endpointId: ByteArray,
            ): BluetoothGattService {
                val service =
                    BluetoothGattService(
                        SERVICE_UUID,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY,
                    )
                val toPeripheral =
                    BluetoothGattCharacteristic(
                        TO_PERIPHERAL_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    )
                val fromPeripheral =
                    BluetoothGattCharacteristic(
                        FROM_PERIPHERAL_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ or
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    )
                fromPeripheral.addDescriptor(
                    BluetoothGattDescriptor(
                        CLIENT_CHARACTERISTIC_CONFIG_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
                service.addCharacteristic(toPeripheral)
                service.addCharacteristic(fromPeripheral)
                buildAdvertisementSlots(endpointInfo, endpointId).forEach(service::addCharacteristic)
                return service
            }

            private fun buildAdvertisementSlots(
                endpointInfo: EndpointInfo,
                endpointId: ByteArray,
            ): List<BluetoothGattCharacteristic> {
                val visibleAdvertisement =
                    runCatching { BleServiceData.encodeFramed(endpointId, endpointInfo) }
                        .getOrDefault(ByteArray(0))

                return (0 until GATT_ADVERTISEMENT_SLOT_COUNT).map { slot ->
                    BluetoothGattCharacteristic(
                        advertisementSlotUuid(slot),
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    ).apply {
                        value = if (slot == 0) visibleAdvertisement else ByteArray(0)
                    }
                }
            }
        }
    }

    public companion object {
        internal const val TAG: String = "WvmgBleGatt"

        /** Copresence / Nearby BLE socket service UUID. */
        public val SERVICE_UUID: UUID = UUID.fromString(BleServiceData.SERVICE_UUID_128_STRING)

        /** Central writes Weave packets here. */
        public val TO_PERIPHERAL_UUID: UUID = UUID.fromString("00000100-0004-1000-8000-001a11000101")

        /** Peripheral notifies Weave packets here. */
        public val FROM_PERIPHERAL_UUID: UUID = UUID.fromString("00000100-0004-1000-8000-001a11000102")

        /** GATT-backed advertisement slot zero, used by Nearby's BLE v2 scanner. */
        public val GATT_SLOT_0_UUID: UUID = UUID.fromString("00000000-0000-3000-8000-000000000000")

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val GATT_ADVERTISEMENT_SLOT_COUNT: Int = 16

        private fun advertisementSlotUuid(slot: Int): UUID =
            UUID.fromString("00000000-0000-3000-8000-${slot.toString(radix = 16).padStart(12, '0')}")
    }
}

private class BleWeaveGattSession(
    private val device: BluetoothDevice,
    private val server: BluetoothGattServer,
    private val outgoingCharacteristic: BluetoothGattCharacteristic,
    private val acceptTransport: (ConnectedTransport) -> Unit,
) {
    private val notificationQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private val multiplexBridge =
        BleMultiplexBridge(
            tag = BleGattInitialControlServer.TAG,
            medium = Medium.BLE,
            sendPhysical = ::sendPhysicalSocketBytes,
            acceptTransport = acceptTransport,
        )
    private val incomingMessage = ArrayList<Byte>()
    private var subscribed: Boolean = false
    private var sending: Boolean = false
    private var introReceived: Boolean = false
    private var mtuPayloadSize: Int = WeaveFrames.DEFAULT_ATT_PAYLOAD_SIZE
    private var negotiatedPacketSize: Int = WeaveFrames.MIN_PACKET_SIZE
    private var sendPacketCounter: Int = 0
    private var closed: Boolean = false

    @Synchronized
    fun setSubscribed(enabled: Boolean) {
        subscribed = enabled
        Log.i(BleGattInitialControlServer.TAG, "subscription enabled=$enabled device=${device.safeAddress()}")
        drainNotificationsLocked()
    }

    @Synchronized
    fun setMtu(mtu: Int) {
        mtuPayloadSize = (mtu - GATT_ATT_HEADER_BYTES).coerceAtLeast(WeaveFrames.DEFAULT_ATT_PAYLOAD_SIZE)
    }

    @Synchronized
    fun onIncomingWeavePacket(packetBytes: ByteArray) {
        if (closed) return
        when (val packet = WeaveFrames.parse(packetBytes)) {
            is WeavePacket.ConnectionRequest -> handleConnectionRequest(packet)
            is WeavePacket.Data -> handleDataPacket(packet)
            is WeavePacket.ConnectionClose -> close()
            null -> Log.w(BleGattInitialControlServer.TAG, "invalid Weave packet ${packetBytes.toHex()}")
        }
    }

    @Synchronized
    fun onNotificationSent(status: Int) {
        Log.i(BleGattInitialControlServer.TAG, "notification sent status=$status device=${device.safeAddress()}")
        sending = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(BleGattInitialControlServer.TAG, "notification failed status=$status")
        }
        drainNotificationsLocked()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        notificationQueue.clear()
        multiplexBridge.close()
    }

    private fun handleConnectionRequest(packet: WeavePacket.ConnectionRequest) {
        if (packet.maxVersion < WeaveFrames.VERSION || packet.minVersion > WeaveFrames.VERSION) {
            enqueueNotificationLocked(
                WeaveFrames.connectionClosePacket(
                    packetCounter = sendPacketCounter,
                    reason = WeaveFrames.CLOSE_NO_COMMON_VERSION,
                ),
            )
            sendPacketCounter = (sendPacketCounter + 1) % WeaveFrames.MAX_PACKET_COUNTER
            return
        }
        val requestedPacketSize = packet.maxPacketSize.takeIf { it > 0 } ?: mtuPayloadSize
        negotiatedPacketSize =
            minOf(requestedPacketSize, mtuPayloadSize, WeaveFrames.MAX_PACKET_SIZE)
                .coerceAtLeast(WeaveFrames.MIN_PACKET_SIZE)
        enqueueNotificationLocked(
            WeaveFrames.connectionConfirm(
                version = WeaveFrames.VERSION,
                packetSize = negotiatedPacketSize,
            ),
        )
        sendPacketCounter = 1
        Log.i(
            BleGattInitialControlServer.TAG,
            "Weave connected packetSize=$negotiatedPacketSize peerMax=${packet.maxPacketSize} " +
                "device=${device.safeAddress()}",
        )
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
        if (!introReceived) {
            handleIntroductionMessage(message)
        } else {
            handleServiceMessage(message)
        }
    }

    private fun handleIntroductionMessage(message: ByteArray) {
        val control = NearbyBleSocketFrames.parseControlPacket(message)
        val isIntroduction =
            control?.type == BleFramesProto.SocketControlFrame.ControlFrameType.INTRODUCTION &&
                control.introduction.socketVersion == BleFramesProto.SocketVersion.V2 &&
                control.introduction.serviceIdHash
                    .toByteArray()
                    .contentEquals(NearbyServiceId.hashPrefix)
        if (isIntroduction) {
            introReceived = true
            Log.i(BleGattInitialControlServer.TAG, "BLE socket introduction accepted")
        } else {
            Log.w(BleGattInitialControlServer.TAG, "discarded non-introduction first socket message")
        }
    }

    private fun handleServiceMessage(message: ByteArray) {
        when {
            message.size < NearbyServiceId.hashPrefix.size -> {
                Log.w(BleGattInitialControlServer.TAG, "discarded undersized BLE socket payload")
            }

            !message.copyOfRange(0, NearbyServiceId.hashPrefix.size).contentEquals(NearbyServiceId.hashPrefix) -> {
                Log.w(BleGattInitialControlServer.TAG, "discarded BLE payload for unexpected service hash")
            }

            else -> {
                multiplexBridge.receivePhysicalBytes(
                    message.copyOfRange(NearbyServiceId.hashPrefix.size, message.size),
                )
            }
        }
    }

    private fun sendPhysicalSocketBytes(bytes: ByteArray) {
        val payload = NearbyServiceId.hashPrefix + bytes
        sendWeaveMessage(payload)
    }

    @Synchronized
    private fun sendWeaveMessage(payload: ByteArray) {
        var offset = 0
        do {
            val maxChunkSize = (negotiatedPacketSize - WeaveFrames.HEADER_SIZE).coerceAtLeast(1)
            val chunkSize = maxChunkSize.coerceAtMost(payload.size - offset)
            val nextOffset = offset + chunkSize
            enqueueNotificationLocked(
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

    private fun enqueueNotificationLocked(packet: ByteArray) {
        Log.i(
            BleGattInitialControlServer.TAG,
            "enqueue notification len=${packet.size} header=${packet.firstByteHex()} device=${device.safeAddress()}",
        )
        notificationQueue.add(packet)
        drainNotificationsLocked()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun drainNotificationsLocked() {
        if (!subscribed || sending || closed) return
        val packet = if (notificationQueue.isEmpty()) return else notificationQueue.removeFirst()
        Log.i(
            BleGattInitialControlServer.TAG,
            "send notification len=${packet.size} header=${packet.firstByteHex()} device=${device.safeAddress()}",
        )
        outgoingCharacteristic.value = packet
        sending =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, outgoingCharacteristic, false, packet) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                server.notifyCharacteristicChanged(device, outgoingCharacteristic, false)
            }
        if (!sending) {
            Log.w(BleGattInitialControlServer.TAG, "notifyCharacteristicChanged returned false")
        }
    }

    private companion object {
        private const val GATT_ATT_HEADER_BYTES: Int = 3
    }
}

internal class BleMultiplexBridge(
    private val tag: String,
    private val medium: Medium,
    private val sendPhysical: (ByteArray) -> Unit,
    private val acceptTransport: (ConnectedTransport) -> Unit,
) {
    private var pending: ByteArray = ByteArray(0)
    private var virtualTransport: MultiplexVirtualTransport? = null
    private var rawTransport: RawBleVirtualTransport? = null
    private var closed: Boolean = false

    @Synchronized
    fun receivePhysicalBytes(bytes: ByteArray) {
        if (!closed) {
            pending += bytes
        }
        var keepParsing = !closed
        while (keepParsing && pending.size >= NearbyMultiplexFrames.LENGTH_PREFIX_BYTES) {
            val length = NearbyMultiplexFrames.decodeLength(pending)
            if (length == null || length < 0 || pending.size < NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length) {
                keepParsing = false
            } else {
                val frameBytes =
                    pending.copyOfRange(
                        NearbyMultiplexFrames.LENGTH_PREFIX_BYTES,
                        NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length,
                    )
                val original = pending.copyOfRange(0, NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length)
                pending = pending.copyOfRange(NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length, pending.size)
                handlePhysicalFrame(frameBytes, original)
            }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        virtualTransport?.close()
        rawTransport?.close()
    }

    private fun handlePhysicalFrame(
        frameBytes: ByteArray,
        originalLengthPrefixedBytes: ByteArray,
    ) {
        rawTransport?.let { transport ->
            transport.feedIncoming(originalLengthPrefixedBytes)
            return
        }

        val frame = NearbyMultiplexFrames.parseFrame(frameBytes)
        if (frame == null) {
            virtualTransport?.feedIncoming(originalLengthPrefixedBytes)
                ?: ensureRawTransport().feedIncoming(originalLengthPrefixedBytes)
            return
        }
        when (frame.frameType) {
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME -> handleControlFrame(frame)
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME -> handleDataFrame(frame)
            else -> Unit
        }
    }

    private fun handleControlFrame(frame: MultiplexFramesProto.MultiplexFrame) {
        when (frame.controlFrame.controlFrameType) {
            MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_REQUEST -> {
                val salt = frame.header.serviceIdHashSalt
                val saltedHash = frame.header.saltedServiceIdHash.toByteArray()
                val expected = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)
                val response =
                    if (saltedHash.contentEquals(expected)) {
                        ensureVirtualTransport(saltedHash, salt)
                        MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED
                    } else {
                        MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.NOT_LISTENING
                    }
                sendPhysical(
                    NearbyMultiplexFrames.encodeLengthPrefixed(
                        NearbyMultiplexFrames.encodeConnectionResponseFrame(
                            saltedServiceIdHash = saltedHash,
                            serviceIdHashSalt = salt,
                            responseCode = response,
                        ),
                    ),
                )
            }

            MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.DISCONNECTION -> {
                virtualTransport?.close()
                virtualTransport = null
            }

            else -> Unit
        }
    }

    private fun handleDataFrame(frame: MultiplexFramesProto.MultiplexFrame) {
        val salt = frame.header.serviceIdHashSalt
        val saltedHash = frame.header.saltedServiceIdHash.toByteArray()
        val transport = virtualTransport ?: ensureVirtualTransport(saltedHash, salt)
        transport.feedIncoming(frame.dataFrame.data.toByteArray())
    }

    private fun ensureVirtualTransport(
        saltedHash: ByteArray,
        salt: String,
    ): MultiplexVirtualTransport {
        virtualTransport?.let { return it }
        rawTransport?.close()
        rawTransport = null
        Log.i(tag, "BLE socket using multiplex stream")
        val transport =
            MultiplexVirtualTransport(
                tag = tag,
                transportMedium = medium,
                saltedHash = saltedHash,
                salt = salt,
                sendPhysical = sendPhysical,
            )
        virtualTransport = transport
        acceptTransport(transport)
        return transport
    }

    private fun ensureRawTransport(): RawBleVirtualTransport {
        rawTransport?.let { return it }
        Log.i(tag, "BLE socket using raw Nearby stream")
        val transport = RawBleVirtualTransport(tag = tag, transportMedium = medium, sendPhysical = sendPhysical)
        rawTransport = transport
        acceptTransport(transport)
        return transport
    }
}

private class RawBleVirtualTransport(
    private val tag: String,
    private val transportMedium: Medium,
    private val sendPhysical: (ByteArray) -> Unit,
) : ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private var closed: Boolean = false

    override val medium: Medium = transportMedium

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
                sendPhysical(b.copyOfRange(off, off + len))
            }

            override fun flush() {
                // GATT notifications are queued immediately by write().
            }

            override fun close() {
                this@RawBleVirtualTransport.close()
            }
        }

    @Synchronized
    fun feedIncoming(bytes: ByteArray) {
        if (closed) return
        try {
            inputWriter.write(bytes)
            inputWriter.flush()
        } catch (io: IOException) {
            Log.w(tag, "raw BLE input closed while feeding data", io)
            close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { inputWriter.close() }
        runCatching { input.close() }
    }

    private companion object {
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
    }
}

private class MultiplexVirtualTransport(
    private val tag: String,
    private val transportMedium: Medium,
    private val saltedHash: ByteArray,
    private val salt: String,
    private val sendPhysical: (ByteArray) -> Unit,
) : ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private var closed: Boolean = false

    override val medium: Medium = transportMedium

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
                val data = b.copyOfRange(off, off + len)
                sendPhysical(
                    NearbyMultiplexFrames.encodeLengthPrefixed(
                        NearbyMultiplexFrames.encodeDataFrame(
                            saltedServiceIdHash = saltedHash,
                            data = data,
                        ),
                    ),
                )
            }

            override fun flush() {
                // GATT notifications are queued immediately by write().
            }

            override fun close() {
                this@MultiplexVirtualTransport.close()
            }
        }

    @Synchronized
    fun feedIncoming(bytes: ByteArray) {
        if (closed) return
        try {
            inputWriter.write(bytes)
            inputWriter.flush()
        } catch (io: IOException) {
            Log.w(tag, "virtual BLE input closed while feeding data", io)
            close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            sendPhysical(
                NearbyMultiplexFrames.encodeLengthPrefixed(
                    NearbyMultiplexFrames.encodeDisconnectionFrame(
                        saltedServiceIdHash = saltedHash,
                        serviceIdHashSalt = salt,
                    ),
                ),
            )
        }
        runCatching { inputWriter.close() }
        runCatching { input.close() }
    }

    private companion object {
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
    }
}

private sealed class WeavePacket {
    data class ConnectionRequest(
        val minVersion: Int,
        val maxVersion: Int,
        val maxPacketSize: Int,
    ) : WeavePacket()

    data class Data(
        val counter: Int,
        val firstPacket: Boolean,
        val lastPacket: Boolean,
        val data: ByteArray,
    ) : WeavePacket()

    data class ConnectionClose(
        val reason: Int,
    ) : WeavePacket()
}

private object WeaveFrames {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 1
    const val MIN_PACKET_SIZE: Int = 20
    const val MAX_PACKET_SIZE: Int = 509
    const val DEFAULT_ATT_PAYLOAD_SIZE: Int = 20
    const val MAX_PACKET_COUNTER: Int = 8
    const val CLOSE_NO_COMMON_VERSION: Int = 2

    private const val PACKET_TYPE_CONTROL: Int = 0x80
    private const val PACKET_COUNTER_MASK: Int = 0x70
    private const val PACKET_COUNTER_SHIFT: Int = 4
    private const val CONTROL_COMMAND_MASK: Int = 0x0F
    private const val CONTROL_CONNECTION_REQUEST: Int = 0
    private const val CONTROL_CONNECTION_CONFIRM: Int = 1
    private const val CONTROL_CONNECTION_CLOSE: Int = 2
    private const val DATA_FIRST_FLAG: Int = 0x08
    private const val DATA_LAST_FLAG: Int = 0x04
    private const val MIN_CONNECTION_REQUEST_LEN: Int = 7
    private const val CONNECTION_CLOSE_LEN: Int = 3

    fun parse(bytes: ByteArray): WeavePacket? {
        if (bytes.isEmpty()) return null
        val header = bytes[0].toInt() and 0xFF
        val counter = (header and PACKET_COUNTER_MASK) ushr PACKET_COUNTER_SHIFT
        return if ((header and PACKET_TYPE_CONTROL) != 0) {
            parseControl(header, bytes)
        } else {
            WeavePacket.Data(
                counter = counter,
                firstPacket = (header and DATA_FIRST_FLAG) != 0,
                lastPacket = (header and DATA_LAST_FLAG) != 0,
                data = bytes.copyOfRange(HEADER_SIZE, bytes.size),
            )
        }
    }

    fun connectionConfirm(
        version: Int,
        packetSize: Int,
    ): ByteArray =
        byteArrayOf(
            (PACKET_TYPE_CONTROL or CONTROL_CONNECTION_CONFIRM).toByte(),
            (version ushr 8).toByte(),
            version.toByte(),
            (packetSize ushr 8).toByte(),
            packetSize.toByte(),
        )

    fun connectionClosePacket(
        packetCounter: Int,
        reason: Int,
    ): ByteArray {
        val header =
            PACKET_TYPE_CONTROL or
                ((packetCounter % MAX_PACKET_COUNTER) shl PACKET_COUNTER_SHIFT) or
                CONTROL_CONNECTION_CLOSE
        return byteArrayOf(
            header.toByte(),
            (reason ushr 8).toByte(),
            reason.toByte(),
        )
    }

    fun dataPacket(
        packetCounter: Int,
        firstPacket: Boolean,
        lastPacket: Boolean,
        data: ByteArray,
    ): ByteArray {
        val header =
            ((packetCounter % MAX_PACKET_COUNTER) shl PACKET_COUNTER_SHIFT) or
                (if (firstPacket) DATA_FIRST_FLAG else 0) or
                (if (lastPacket) DATA_LAST_FLAG else 0)
        return byteArrayOf(header.toByte()) + data
    }

    private fun parseControl(
        header: Int,
        bytes: ByteArray,
    ): WeavePacket? {
        return when (header and CONTROL_COMMAND_MASK) {
            CONTROL_CONNECTION_REQUEST -> {
                if (bytes.size < MIN_CONNECTION_REQUEST_LEN) return null
                WeavePacket.ConnectionRequest(
                    minVersion = bytes.readUInt16(1),
                    maxVersion = bytes.readUInt16(3),
                    maxPacketSize = bytes.readUInt16(5),
                )
            }

            CONTROL_CONNECTION_CLOSE -> {
                WeavePacket.ConnectionClose(
                    reason = if (bytes.size >= CONNECTION_CLOSE_LEN) bytes.readUInt16(1) else 0,
                )
            }

            else -> null
        }
    }
}

private fun ByteArray.readUInt16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ArrayList<Byte>.toByteArray(): ByteArray {
    val out = ByteArray(size)
    for (index in indices) out[index] = this[index]
    return out
}

private fun BluetoothDevice.key(): String = address ?: toString()

private fun BluetoothDevice.safeAddress(): String = runCatching { address }.getOrNull() ?: "<unknown>"

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.firstByteHex(): String = firstOrNull()?.let { "%02x".format(it) } ?: "--"
