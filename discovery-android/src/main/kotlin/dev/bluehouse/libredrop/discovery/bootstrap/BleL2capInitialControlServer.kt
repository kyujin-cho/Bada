/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package dev.bluehouse.libredrop.discovery.bootstrap

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.google.location.nearby.mediums.proto.BleFramesProto
import dev.bluehouse.libredrop.discovery.ble.BleDctPsmHolder
import dev.bluehouse.libredrop.discovery.medium.BluetoothL2capIo
import dev.bluehouse.libredrop.discovery.medium.DefaultBluetoothL2capIo
import dev.bluehouse.libredrop.discovery.medium.L2capChannel
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.NearbyServiceId
import dev.bluehouse.libredrop.protocol.medium.Medium
import dev.bluehouse.libredrop.protocol.medium.NearbyBleSocketFrames
import dev.bluehouse.libredrop.protocol.transport.ConnectedTransport
import dev.bluehouse.libredrop.protocol.transport.FramedConnection
import dev.bluehouse.libredrop.protocol.transport.InitialControlServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Receiver-side BLE L2CAP initial-control server for DCT advertisements.
 *
 * Stock Nearby encodes the listening CoC PSM in the `0xFC73` DCT service data.
 * A sender that sees a non-zero PSM connects here and sends a one-byte
 * data-connection request before the normal BLE socket packet stream begins.
 */
public class BleL2capInitialControlServer internal constructor(
    private val io: BluetoothL2capIo,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InitialControlServer {
    public constructor(context: Context) : this(DefaultBluetoothL2capIo(context.applicationContext))

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
                Log.i(TAG, "BLE L2CAP bootstrap unavailable")
                return false
            }

            val listener = listenOnQ() ?: return false
            if (listener.psm <= 0) {
                Log.w(TAG, "BLE L2CAP listener returned invalid psm=${listener.psm}")
                listener.close()
                return false
            }

            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val state = ActiveState(listener = listener, psm = listener.psm, scope = scope)
            if (!active.compareAndSet(null, state)) {
                listener.close()
                scope.cancel()
                return true
            }

            BleDctPsmHolder.set(listener.psm)
            scope.launch { runAcceptLoop(state, acceptTransport) }
            Log.i(TAG, "BLE L2CAP bootstrap active psm=${listener.psm}")
            true
        }

    override fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        val state = active.getAndSet(null) ?: return
        BleDctPsmHolder.clear(state.psm)
        runCatching { state.listener.close() }
        state.scope.cancel()
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            io.apiLevel >= Build.VERSION_CODES.Q &&
            io.hasBleHardware() &&
            io.hasConnectPermission() &&
            io.isBluetoothEnabled()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listenOnQ(): BluetoothL2capIo.Listener? = io.listen()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runAcceptLoop(
        state: ActiveState,
        acceptTransport: (ConnectedTransport) -> Unit,
    ) {
        while (active.get() === state) {
            val channel =
                try {
                    state.listener.accept()
                } catch (ioException: IOException) {
                    if (active.get() === state) Log.w(TAG, "BLE L2CAP accept failed", ioException)
                    stop()
                    return
                } catch (t: Throwable) {
                    if (active.get() === state) Log.w(TAG, "BLE L2CAP accept crashed", t)
                    stop()
                    return
                }
            state.scope.launch { acceptL2capChannel(channel, acceptTransport) }
        }
    }

    private fun acceptL2capChannel(
        channel: L2capChannel,
        acceptTransport: (ConnectedTransport) -> Unit,
    ) {
        try {
            val initialBytes = processDataConnectionRequest(channel)
            if (initialBytes == null) {
                channel.close()
                return
            }
            BleL2capInitialTransport(
                channel = channel,
                initialBytes = initialBytes,
                acceptTransport = acceptTransport,
            ).start()
        } catch (t: Throwable) {
            Log.w(TAG, "BLE L2CAP channel setup failed", t)
            channel.close()
        }
    }

    private fun processDataConnectionRequest(channel: L2capChannel): ByteArray? {
        val command = channel.inputStream.read()
        return when {
            command < 0 -> null
            command == COMMAND_REQUEST_DATA_CONNECTION -> {
                channel.outputStream.write(byteArrayOf(COMMAND_RESPONSE_DATA_CONNECTION_READY.toByte()))
                channel.outputStream.flush()
                Log.i(TAG, "BLE L2CAP data connection ready")
                ByteArray(0)
            }
            else -> processInitialSocketPacket(channel, command)
        }
    }

    private fun processInitialSocketPacket(
        channel: L2capChannel,
        command: Int,
    ): ByteArray? {
        val packetPrefix =
            runCatching {
                byteArrayOf(command.toByte()) + channel.inputStream.readExactly(L2CAP_PACKET_LENGTH_BYTES - 1)
            }.getOrElse {
                Log.w(TAG, "BLE L2CAP command prefix truncated", it)
                null
            }
        return packetPrefix?.let { processInitialSocketPacket(channel, it) }
    }

    private fun processInitialSocketPacket(
        channel: L2capChannel,
        packetPrefix: ByteArray,
    ): ByteArray {
        val packetLength = packetPrefix.decodeLength()
        return if (packetLength <= 0 || packetLength > MAX_INITIAL_PACKET_LENGTH) {
            Log.i(
                TAG,
                "BLE L2CAP socket stream started without validation prefix=${packetPrefix.toHex()}",
            )
            packetPrefix
        } else {
            val packet = channel.inputStream.readExactly(packetLength)
            val packetCommand = packet.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            when (packetCommand) {
                COMMAND_REQUEST_DATA_CONNECTION -> {
                    channel.outputStream.writeLengthPrefixedPacket(
                        byteArrayOf(COMMAND_RESPONSE_DATA_CONNECTION_READY.toByte()),
                    )
                    Log.i(TAG, "BLE L2CAP framed data connection ready")
                    ByteArray(0)
                }

                else -> {
                    Log.w(
                        TAG,
                        "unexpected BLE L2CAP framed command=$packetCommand before data connection; " +
                            "treating as socket data",
                    )
                    packetPrefix + packet
                }
            }
        }
    }

    private data class ActiveState(
        val listener: BluetoothL2capIo.Listener,
        val psm: Int,
        val scope: CoroutineScope,
    )

    public companion object {
        internal const val TAG: String = "LibreDropBleL2cap"
        private const val COMMAND_REQUEST_DATA_CONNECTION: Int = 3
        private const val COMMAND_RESPONSE_DATA_CONNECTION_READY: Int = 23
        private const val L2CAP_PACKET_LENGTH_BYTES: Int = 4
        private const val MAX_INITIAL_PACKET_LENGTH: Int = 512
    }
}

private class BleL2capInitialTransport(
    private val channel: L2capChannel,
    initialBytes: ByteArray,
    acceptTransport: (ConnectedTransport) -> Unit,
) {
    private val sourceInput: InputStream =
        if (initialBytes.isEmpty()) {
            channel.inputStream
        } else {
            SequenceInputStream(ByteArrayInputStream(initialBytes), channel.inputStream)
        }
    private val writeLock = Any()
    private val multiplexBridge =
        BleMultiplexBridge(
            tag = BleL2capInitialControlServer.TAG,
            medium = Medium.BLE_L2CAP,
            sendPhysical = ::sendPhysicalSocketBytes,
            acceptTransport = acceptTransport,
        )

    @Volatile
    private var closed: Boolean = false

    fun start() {
        Thread({ pumpIncoming() }, "libredrop-ble-l2cap-initial").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        multiplexBridge.close()
        runCatching { channel.close() }
    }

    private fun pumpIncoming() {
        try {
            while (!closed && pumpIncomingPacket()) Unit
        } catch (_: EOFException) {
            close()
        } catch (io: IOException) {
            if (!closed) Log.w(BleL2capInitialControlServer.TAG, "BLE L2CAP input pump failed", io)
            close()
        }
    }

    private fun pumpIncomingPacket(): Boolean {
        val packetLengthPrefix = sourceInput.readExactly(L2CAP_PACKET_LENGTH_BYTES)
        val packetLength = packetLengthPrefix.decodeLength()
        return if (packetLength <= 0 || packetLength >= FramedConnection.SANE_FRAME_LENGTH) {
            Log.w(BleL2capInitialControlServer.TAG, "invalid BLE L2CAP packet length=$packetLength")
            close()
            false
        } else {
            handleIncomingPacket(sourceInput.readExactly(packetLength))
        }
    }

    private fun handleIncomingPacket(packet: ByteArray): Boolean =
        when {
            packet.size < SERVICE_ID_HASH_LEN -> {
                Log.w(BleL2capInitialControlServer.TAG, "discarded undersized BLE L2CAP packet=${packet.toHex()}")
                close()
                false
            }

            packet.copyOfRange(0, SERVICE_ID_HASH_LEN).contentEquals(CONTROL_PACKET_PREFIX) -> {
                handleControlPacket(packet)
                true
            }

            !packet.copyOfRange(0, SERVICE_ID_HASH_LEN).contentEquals(NearbyServiceId.hashPrefix) -> {
                Log.w(
                    BleL2capInitialControlServer.TAG,
                    "discarded BLE L2CAP packet for unexpected service hash " +
                        "packet=${packet.toHex()}",
                )
                close()
                false
            }

            else -> {
                val payload = packet.copyOfRange(SERVICE_ID_HASH_LEN, packet.size)
                writeControlPacket(NearbyBleSocketFrames.encodePacketAcknowledgementPacket(payload.size))
                multiplexBridge.receivePhysicalBytes(payload)
                true
            }
        }

    private fun handleControlPacket(packet: ByteArray) {
        val control = NearbyBleSocketFrames.parseControlPacket(packet)
        if (control == null) {
            Log.w(BleL2capInitialControlServer.TAG, "BLE L2CAP unknown control packet raw=${packet.toHex()}")
            return
        }
        val isIntroduction =
            control.type == BleFramesProto.SocketControlFrame.ControlFrameType.INTRODUCTION &&
                control.introduction.socketVersion == BleFramesProto.SocketVersion.V2 &&
                control.introduction.serviceIdHash
                    .toByteArray()
                    .contentEquals(NearbyServiceId.hashPrefix)
        when {
            isIntroduction -> Log.i(BleL2capInitialControlServer.TAG, "BLE L2CAP socket introduction accepted")
            control.type == BleFramesProto.SocketControlFrame.ControlFrameType.DISCONNECTION -> close()
            else -> Log.i(BleL2capInitialControlServer.TAG, "BLE L2CAP control frame type=${control.type}")
        }
    }

    private fun writeControlPacket(packet: ByteArray) {
        synchronized(writeLock) {
            channel.outputStream.writeLengthPrefixedPacket(packet)
        }
    }

    private fun sendPhysicalSocketBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(writeLock) {
            channel.outputStream.writeLengthPrefixedPacket(NearbyServiceId.hashPrefix + bytes)
        }
    }

    private companion object {
        private const val SERVICE_ID_HASH_LEN: Int = 3
        private const val L2CAP_PACKET_LENGTH_BYTES: Int = 4
        private val CONTROL_PACKET_PREFIX: ByteArray = ByteArray(SERVICE_ID_HASH_LEN)
    }
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val out = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(out, offset, length - offset)
        if (read < 0) throw EOFException("stream closed after $offset of $length bytes")
        offset += read
    }
    return out
}

private fun ByteArray.decodeLength(offset: Int = 0): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun Int.encodeLength(): ByteArray =
    byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte(),
    )

private fun OutputStream.writeLengthPrefixedPacket(packet: ByteArray) {
    write(packet.size.encodeLength())
    write(packet)
    flush()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
