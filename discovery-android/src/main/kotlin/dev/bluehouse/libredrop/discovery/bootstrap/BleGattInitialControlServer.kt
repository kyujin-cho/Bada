/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("DEPRECATION", "MagicNumber", "TooManyFunctions")
@file:android.annotation.SuppressLint("MissingPermission")

package dev.bluehouse.libredrop.discovery.bootstrap

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
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.NearbyServiceId
import dev.bluehouse.libredrop.protocol.medium.Medium
import dev.bluehouse.libredrop.protocol.medium.NearbyBleSocketFrames
import dev.bluehouse.libredrop.protocol.medium.NearbyMultiplexFrames
import dev.bluehouse.libredrop.protocol.transport.ConnectedTransport
import dev.bluehouse.libredrop.protocol.transport.InitialControlServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val publishAdvertisementSlotService: Boolean = true,
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
                    publishAdvertisementSlotService = publishAdvertisementSlotService,
                    acceptTransport = acceptTransport,
                )
            val server =
                manager.openGattServer(appContext, callback)
                    ?: run {
                        Log.w(TAG, "BluetoothManager.openGattServer returned null")
                        return false
                    }
            callback.attach(server)
            if (!callback.startAddingServices() || !callback.awaitServicesReady()) {
                runCatching { server.clearServices() }
                server.close()
                callback.close()
                return false
            }
            val state = ActiveState(server = server, callback = callback)
            if (!active.compareAndSet(null, state)) {
                server.close()
                callback.close()
                return true
            }
            Log.i(TAG, "BLE GATT bootstrap active services=${callback.services.joinToString { it.uuid.toString() }}")
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
        publishAdvertisementSlotService: Boolean,
        private val acceptTransport: (ConnectedTransport) -> Unit,
    ) : BluetoothGattServerCallback() {
        val services: List<BluetoothGattService> =
            buildServices(
                endpointInfo = endpointInfo,
                endpointId = endpointId,
                publishAdvertisementSlotService = publishAdvertisementSlotService,
            )

        private val sessions: ConcurrentHashMap<String, BleWeaveGattSession> = ConcurrentHashMap()
        private val fromPeripheral: BluetoothGattCharacteristic =
            requireNotNull(services.firstNotNullOfOrNull { it.getCharacteristic(FROM_PERIPHERAL_UUID) })
        private var server: BluetoothGattServer? = null
        private val serviceAddLock: Any = Any()
        private val serviceAddLatch: CountDownLatch = CountDownLatch(services.size)
        private var nextServiceIndex: Int = 0
        private var serviceAddFailed: Boolean = false

        fun attach(server: BluetoothGattServer) {
            this.server = server
        }

        fun startAddingServices(): Boolean = addNextService()

        fun awaitServicesReady(): Boolean {
            val ready = serviceAddLatch.await(SERVICE_ADD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!ready) {
                Log.w(TAG, "timed out adding BLE GATT services")
            }
            return ready && !serviceAddFailed
        }

        fun close() {
            sessions.values.forEach { it.close() }
            sessions.clear()
            server = null
            markServiceAddFailed()
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                markServiceAddFailed()
                return
            }
            serviceAddLatch.countDown()
            addNextService()
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
            val subscriptionMode =
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ->
                        GattSubscriptionMode.NOTIFICATION
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ->
                        GattSubscriptionMode.INDICATION
                    else -> GattSubscriptionMode.DISABLED
                }
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                descriptor.characteristic?.uuid == FROM_PERIPHERAL_UUID
            ) {
                sessionFor(device).setSubscription(subscriptionMode)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            Log.i(
                TAG,
                "read descriptor=${descriptor.uuid} char=${descriptor.characteristic?.uuid} " +
                    "offset=$offset device=${device.safeAddress()}",
            )
            val value =
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic?.uuid == FROM_PERIPHERAL_UUID
                ) {
                    sessionFor(device).cccdValue()
                } else {
                    ByteArray(0)
                }
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

        private fun addNextService(): Boolean {
            val gattServer = server
            val service =
                synchronized(serviceAddLock) {
                    if (serviceAddFailed || nextServiceIndex >= services.size) {
                        null
                    } else {
                        services[nextServiceIndex++]
                    }
                }

            val accepted =
                if (gattServer == null) {
                    false
                } else if (service == null) {
                    true
                } else {
                    // Android's BluetoothGattServer keeps one mPendingService internally.
                    // Queue services from onServiceAdded so descriptor handles are copied
                    // back onto the right local service before the next add starts.
                    gattServer.addService(service).also { serviceAccepted ->
                        if (!serviceAccepted) {
                            Log.w(TAG, "BluetoothGattServer.addService failed uuid=${service.uuid}")
                            markServiceAddFailed()
                        }
                    }
                }
            if (gattServer == null) {
                markServiceAddFailed()
            }
            return accepted
        }

        private fun markServiceAddFailed() {
            synchronized(serviceAddLock) {
                serviceAddFailed = true
            }
            while (serviceAddLatch.count > 0) {
                serviceAddLatch.countDown()
            }
        }
    }

    public companion object {
        internal const val TAG: String = "LibreDropBleGatt"

        /** Copresence / Nearby BLE socket service UUID. */
        public val SERVICE_UUID: UUID = UUID.fromString(BleServiceData.SERVICE_UUID_128_STRING)

        /** Nearby BLE second-profile socket service UUID used when GMS owns the first profile. */
        public val SECOND_PROFILE_SERVICE_UUID: UUID =
            UUID.fromString("0000fef3-0004-1000-8000-001a11000100")

        /** Central writes Weave packets here. */
        public val TO_PERIPHERAL_UUID: UUID = UUID.fromString("00000100-0004-1000-8000-001a11000101")

        /** Peripheral notifies Weave packets here. */
        public val FROM_PERIPHERAL_UUID: UUID = UUID.fromString("00000100-0004-1000-8000-001a11000102")

        /** GATT-backed advertisement slot zero, used by Nearby's BLE v2 scanner. */
        public val GATT_SLOT_0_UUID: UUID = UUID.fromString("00000000-0000-3000-8000-000000000000")

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val GATT_ADVERTISEMENT_SLOT_COUNT: Int = 16
        private const val SERVICE_ADD_TIMEOUT_MILLIS: Long = 2_000L

        internal data class GattServiceSpec(
            val uuid: UUID,
            val characteristics: List<GattCharacteristicSpec>,
        )

        internal data class GattCharacteristicSpec(
            val uuid: UUID,
            val properties: Int,
            val permissions: Int,
            val descriptors: List<GattDescriptorSpec> = emptyList(),
            val value: ByteArray = ByteArray(0),
        )

        internal data class GattDescriptorSpec(
            val uuid: UUID,
            val permissions: Int,
        )

        internal fun buildServiceSpecs(
            endpointInfo: EndpointInfo,
            endpointId: ByteArray,
            publishAdvertisementSlotService: Boolean = true,
        ): List<GattServiceSpec> {
            val socketService = buildSocketServiceSpec()
            if (!publishAdvertisementSlotService) return listOf(socketService)
            val advertisementService = buildAdvertisementServiceSpec(endpointInfo, endpointId)
            return listOf(advertisementService, socketService)
        }

        internal fun buildServices(
            endpointInfo: EndpointInfo,
            endpointId: ByteArray,
            publishAdvertisementSlotService: Boolean = true,
        ): List<BluetoothGattService> =
            buildServiceSpecs(
                endpointInfo = endpointInfo,
                endpointId = endpointId,
                publishAdvertisementSlotService = publishAdvertisementSlotService,
            ).map { it.toBluetoothGattService() }

        internal fun advertisementSlotUuid(slot: Int): UUID =
            UUID.fromString("00000000-0000-3000-8000-${slot.toString(radix = 16).padStart(12, '0')}")

        private fun buildAdvertisementServiceSpec(
            endpointInfo: EndpointInfo,
            endpointId: ByteArray,
        ): GattServiceSpec =
            GattServiceSpec(
                uuid = SERVICE_UUID,
                characteristics = buildAdvertisementSlots(endpointInfo, endpointId),
            )

        private fun buildSocketServiceSpec(): GattServiceSpec {
            val fromPeripheral =
                GattCharacteristicSpec(
                    uuid = FROM_PERIPHERAL_UUID,
                    properties = BluetoothGattCharacteristic.PROPERTY_INDICATE,
                    permissions = 0,
                    descriptors =
                        listOf(
                            GattDescriptorSpec(
                                uuid = CLIENT_CHARACTERISTIC_CONFIG_UUID,
                                permissions =
                                    BluetoothGattDescriptor.PERMISSION_READ or
                                        BluetoothGattDescriptor.PERMISSION_WRITE,
                            ),
                        ),
                )
            val toPeripheral =
                GattCharacteristicSpec(
                    uuid = TO_PERIPHERAL_UUID,
                    properties = BluetoothGattCharacteristic.PROPERTY_WRITE,
                    permissions = BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            return GattServiceSpec(
                uuid = SECOND_PROFILE_SERVICE_UUID,
                characteristics = listOf(fromPeripheral, toPeripheral),
            )
        }

        private fun buildAdvertisementSlots(
            endpointInfo: EndpointInfo,
            endpointId: ByteArray,
        ): List<GattCharacteristicSpec> {
            // LibreDrop runs beside Google Play services on Android. GMS may
            // already own the first 0xFEF3 GATT socket profile, so the
            // regular service is read-only and advertises the second-profile
            // bit. Stock senders can still fetch the endpoint from the normal
            // slot path, then open the socket on the isolated second profile.
            val visibleAdvertisement =
                runCatching {
                    BleServiceData.encodeFramed(
                        endpointId = endpointId,
                        endpointInfo = endpointInfo,
                        secondProfile = true,
                    )
                }.getOrDefault(ByteArray(0))

            return (0 until GATT_ADVERTISEMENT_SLOT_COUNT).map { slot ->
                GattCharacteristicSpec(
                    uuid = advertisementSlotUuid(slot),
                    properties = BluetoothGattCharacteristic.PROPERTY_READ,
                    permissions = BluetoothGattCharacteristic.PERMISSION_READ,
                    value = if (slot == 0) visibleAdvertisement else ByteArray(0),
                )
            }
        }

        private fun GattServiceSpec.toBluetoothGattService(): BluetoothGattService {
            val spec = this
            return BluetoothGattService(
                uuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            ).apply {
                spec.characteristics.forEach { addCharacteristic(it.toBluetoothGattCharacteristic()) }
            }
        }

        private fun GattCharacteristicSpec.toBluetoothGattCharacteristic(): BluetoothGattCharacteristic =
            BluetoothGattCharacteristic(
                uuid,
                properties,
                permissions,
            ).also { characteristic ->
                characteristic.value = value
                descriptors.forEach { descriptor ->
                    characteristic.addDescriptor(
                        BluetoothGattDescriptor(
                            descriptor.uuid,
                            descriptor.permissions,
                        ),
                    )
                }
            }
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
    private var subscriptionMode: GattSubscriptionMode = GattSubscriptionMode.DISABLED
    private var sending: Boolean = false
    private var introReceived: Boolean = false
    private var mtuPayloadSize: Int = WeaveFrames.DEFAULT_ATT_PAYLOAD_SIZE
    private var negotiatedPacketSize: Int = WeaveFrames.MIN_PACKET_SIZE
    private var sendPacketCounter: Int = 0
    private var closed: Boolean = false

    @Synchronized
    fun setSubscription(mode: GattSubscriptionMode) {
        subscriptionMode = mode
        subscribed = mode != GattSubscriptionMode.DISABLED
        Log.i(
            BleGattInitialControlServer.TAG,
            "subscription mode=$mode enabled=$subscribed device=${device.safeAddress()}",
        )
        drainNotificationsLocked()
    }

    @Synchronized
    fun cccdValue(): ByteArray =
        when (subscriptionMode) {
            GattSubscriptionMode.NOTIFICATION -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            GattSubscriptionMode.INDICATION -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            GattSubscriptionMode.DISABLED -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
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
            is WeavePacket.ConnectionConfirm -> Unit
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
                val payload = message.copyOfRange(NearbyServiceId.hashPrefix.size, message.size)
                sendWeaveMessage(NearbyBleSocketFrames.encodePacketAcknowledgementPacket(payload.size))
                multiplexBridge.receivePhysicalBytes(
                    payload,
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
        val confirm = subscriptionMode == GattSubscriptionMode.INDICATION
        Log.i(
            BleGattInitialControlServer.TAG,
            "send notification len=${packet.size} header=${packet.firstByteHex()} " +
                "confirm=$confirm device=${device.safeAddress()}",
        )
        outgoingCharacteristic.value = packet
        sending =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, outgoingCharacteristic, confirm, packet) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                server.notifyCharacteristicChanged(device, outgoingCharacteristic, confirm)
            }
        if (!sending) {
            Log.w(BleGattInitialControlServer.TAG, "notifyCharacteristicChanged returned false")
        }
    }

    private companion object {
        private const val GATT_ATT_HEADER_BYTES: Int = 3
    }
}

private enum class GattSubscriptionMode {
    DISABLED,
    NOTIFICATION,
    INDICATION,
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

private fun ArrayList<Byte>.toByteArray(): ByteArray {
    val out = ByteArray(size)
    for (index in indices) out[index] = this[index]
    return out
}

private fun BluetoothDevice.key(): String = address ?: toString()

private fun BluetoothDevice.safeAddress(): String = runCatching { address }.getOrNull() ?: "<unknown>"

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.firstByteHex(): String = firstOrNull()?.let { "%02x".format(it) } ?: "--"
