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
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.location.nearby.mediums.proto.BleFramesProto
import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.NearbyBleSocketFrames
import io.github.kyujincho.wvmg.protocol.medium.NearbyMultiplexFrames
import io.github.kyujincho.wvmg.protocol.transport.ConnectedTransport
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
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
import java.security.SecureRandom
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
    private val saltFactory: () -> String = ::randomSalt,
) {
    public constructor(context: Context) : this(context.applicationContext, Dispatchers.IO)

    private val activeTransport: AtomicReference<BleGattClientTransport?> = AtomicReference(null)

    public suspend fun connect(macAddress: String): ConnectedTransport? =
        withContext(dispatcher) {
            if (!isAvailable()) {
                Log.i(TAG, "BLE GATT initial connect unavailable")
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
                    salt = saltFactory(),
                    onClose = { activeTransport.compareAndSet(it, null) },
                )
            activeTransport.getAndSet(transport)?.close()
            if (!transport.start()) {
                transport.close()
                return@withContext null
            }
            val ready = transport.awaitReady(CONNECTION_READY_TIMEOUT_MILLIS)
            if (!ready) {
                Log.w(TAG, "BLE GATT initial connect timed out waiting for multiplex accept")
                transport.close()
                return@withContext null
            }
            Log.i(TAG, "BLE GATT initial connect ready mac=$macAddress")
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
    private val salt: String,
    private val onClose: (BleGattClientTransport) -> Unit,
) : BluetoothGattCallback(),
    ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private val ready = CompletableDeferred<Boolean>()
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private val incomingMessage = ArrayList<Byte>()
    private val saltedHash: ByteArray = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)

    private var pendingMultiplexBytes: ByteArray = ByteArray(0)
    private var gatt: BluetoothGatt? = null
    private var toPeripheral: BluetoothGattCharacteristic? = null
    private var fromPeripheral: BluetoothGattCharacteristic? = null
    private var serviceDiscoveryStarted: Boolean = false
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
                sendMultiplexBytes(
                    NearbyMultiplexFrames.encodeLengthPrefixed(
                        NearbyMultiplexFrames.encodeDataFrame(
                            saltedServiceIdHash = saltedHash,
                            data = b.copyOfRange(off, off + len),
                        ),
                    ),
                )
            }

            override fun flush() {
                // GATT writes are queued immediately by write().
            }

            override fun close() {
                this@BleGattClientTransport.close()
            }
        }

    fun start(): Boolean {
        val opened =
            device.connectGatt(
                context,
                false,
                this,
                BluetoothDevice.TRANSPORT_LE,
            )
        gatt = opened
        return opened != null
    }

    suspend fun awaitReady(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) { ready.await() } == true

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        Log.i(TAG, "gatt state device=${device.safeAddress()} status=$status newState=$newState")
        if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
            close()
            return
        }
        val requested = gatt.requestMtu(REQUESTED_MTU)
        if (!requested) discoverServicesOnce(gatt)
    }

    override fun onMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        Log.i(TAG, "mtu changed mtu=$mtu status=$status device=${device.safeAddress()}")
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
        val service = gatt.getService(BleGattInitialControlServer.SERVICE_UUID)
        if (service == null || !bindCharacteristics(service)) {
            Log.w(TAG, "BLE GATT bootstrap service missing required characteristics")
            close()
            return
        }
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
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            Log.w(TAG, "write notification descriptor returned false")
            close()
        }
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

    private fun bindCharacteristics(service: BluetoothGattService): Boolean {
        toPeripheral = service.getCharacteristic(BleGattInitialControlServer.TO_PERIPHERAL_UUID)
        fromPeripheral = service.getCharacteristic(BleGattInitialControlServer.FROM_PERIPHERAL_UUID)
        return toPeripheral != null && fromPeripheral != null
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
    }

    @Synchronized
    private fun handleNotification(packetBytes: ByteArray) {
        if (closed) return
        Log.i(TAG, "notification len=${packetBytes.size} header=${packetBytes.firstByteHex()}")
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
        Log.i(TAG, "Weave connected packetSize=$negotiatedPacketSize device=${device.safeAddress()}")
        sendWeaveMessage(NearbyBleSocketFrames.encodeIntroductionPacket())
        sendConnectionRequest()
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
                receiveMultiplexBytes(message.copyOfRange(SERVICE_ID_HASH_LEN, message.size))
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
            BleFramesProto.SocketControlFrame.ControlFrameType.PACKET_ACKNOWLEDGEMENT -> Unit
            BleFramesProto.SocketControlFrame.ControlFrameType.DISCONNECTION -> close()
            else -> Log.i(TAG, "BLE GATT control frame type=${control.type}")
        }
    }

    private fun sendConnectionRequest() {
        sendMultiplexBytes(
            NearbyMultiplexFrames.encodeLengthPrefixed(
                NearbyMultiplexFrames.encodeConnectionRequestFrame(
                    saltedServiceIdHash = saltedHash,
                    serviceIdHashSalt = salt,
                ),
            ),
        )
    }

    private fun receiveMultiplexBytes(bytes: ByteArray) {
        pendingMultiplexBytes += bytes
        var keepParsing = true
        while (keepParsing && pendingMultiplexBytes.size >= NearbyMultiplexFrames.LENGTH_PREFIX_BYTES) {
            val length = NearbyMultiplexFrames.decodeLength(pendingMultiplexBytes)
            if (
                length == null ||
                length < 0 ||
                length >= FramedConnection.SANE_FRAME_LENGTH ||
                pendingMultiplexBytes.size < NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length
            ) {
                keepParsing = false
            } else {
                val frameBytes =
                    pendingMultiplexBytes.copyOfRange(
                        NearbyMultiplexFrames.LENGTH_PREFIX_BYTES,
                        NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length,
                    )
                pendingMultiplexBytes =
                    pendingMultiplexBytes.copyOfRange(
                        NearbyMultiplexFrames.LENGTH_PREFIX_BYTES + length,
                        pendingMultiplexBytes.size,
                    )
                handleMultiplexFrame(frameBytes)
            }
        }
    }

    private fun handleMultiplexFrame(frameBytes: ByteArray) {
        val frame =
            NearbyMultiplexFrames.parseFrame(frameBytes) ?: run {
                Log.w(TAG, "discarded invalid multiplex frame=${frameBytes.toHex()}")
                return
            }
        when (frame.frameType) {
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME -> handleMultiplexControlFrame(frame)
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME ->
                feedIncoming(frame.dataFrame.data.toByteArray())
            else -> Unit
        }
    }

    private fun handleMultiplexControlFrame(frame: MultiplexFramesProto.MultiplexFrame) {
        when (frame.controlFrame.controlFrameType) {
            MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_RESPONSE -> {
                val accepted =
                    frame.controlFrame.connectionResponseFrame.connectionResponseCode ==
                        MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED
                if (!ready.isCompleted) ready.complete(accepted)
                if (!accepted) close()
            }

            MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.DISCONNECTION -> close()
            else -> Unit
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

    private fun sendMultiplexBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sendWeaveMessage(NearbyServiceId.hashPrefix + bytes)
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
        Log.i(TAG, "enqueue write len=${packet.size} header=${packet.firstByteHex()} device=${device.safeAddress()}")
        writeQueue.add(packet)
        drainWritesLocked()
    }

    private fun drainWritesLocked() {
        if (closed || writeInFlight) return
        val packet = if (writeQueue.isEmpty()) return else writeQueue.removeFirst()
        val openGatt = gatt ?: return close()
        val outgoing = toPeripheral ?: return close()
        outgoing.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        outgoing.value = packet
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
        private val CONTROL_PACKET_PREFIX: ByteArray = ByteArray(SERVICE_ID_HASH_LEN)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

private fun ArrayList<Byte>.toByteArray(): ByteArray {
    val out = ByteArray(size)
    for (index in indices) out[index] = this[index]
    return out
}

private fun BluetoothDevice.safeAddress(): String = runCatching { address }.getOrNull() ?: "<unknown>"

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.firstByteHex(): String = firstOrNull()?.let { "%02x".format(it) } ?: "--"

private fun randomSalt(): String {
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    return bytes.toHex()
}
