/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package dev.bluehouse.bada.discovery.bootstrap

import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.google.location.nearby.mediums.proto.BleFramesProto
import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.discovery.medium.BluetoothL2capIo
import dev.bluehouse.bada.discovery.medium.DefaultBluetoothL2capIo
import dev.bluehouse.bada.discovery.medium.L2capChannel
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.NearbyBleSocketFrames
import dev.bluehouse.bada.protocol.medium.NearbyMultiplexFrames
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.FramedConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Sender-side BLE L2CAP initial-control client for stock Quick Share receivers.
 *
 * Receiver fast advertisements may carry a CoC PSM. When present, Bada can
 * connect to that PSM, open Nearby's BLE socket, establish one multiplexed
 * virtual socket, and then hand that stream to the normal outbound protocol.
 */
public class BleL2capInitialControlClient internal constructor(
    private val io: BluetoothL2capIo,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val saltFactory: () -> String = ::randomSalt,
) {
    public constructor(context: Context) : this(DefaultBluetoothL2capIo(context.applicationContext))

    private val activeTransport: AtomicReference<BleL2capClientTransport?> = AtomicReference(null)

    public suspend fun connect(
        macAddress: String,
        psm: Int,
    ): ConnectedTransport? =
        withContext(dispatcher) {
            if (!isAvailable() || psm !in PSM_RANGE) {
                DiagnosticLog.i(TAG, "BLE L2CAP initial connect unavailable psm=$psm")
                return@withContext null
            }
            val channel = connectOnQ(macAddress, psm) ?: return@withContext null
            val transport =
                BleL2capClientTransport(
                    channel = channel,
                    salt = saltFactory(),
                    onClose = { activeTransport.compareAndSet(it, null) },
                )
            activeTransport.getAndSet(transport)?.close()
            transport.start()
            val ready = transport.awaitReady(CONNECTION_READY_TIMEOUT_MILLIS)
            if (!ready) {
                DiagnosticLog.w(TAG, "BLE L2CAP initial connect timed out waiting for multiplex accept")
                transport.close()
                return@withContext null
            }
            DiagnosticLog.i(TAG, "BLE L2CAP initial connect ready mac=$macAddress psm=$psm")
            transport
        }

    public fun cancelPendingConnect() {
        activeTransport.getAndSet(null)?.close()
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            io.apiLevel >= Build.VERSION_CODES.Q &&
            io.hasBleHardware() &&
            io.hasConnectPermission() &&
            io.isBluetoothEnabled()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectOnQ(
        macAddress: String,
        psm: Int,
    ): L2capChannel? = io.connect(macAddress, psm)

    private companion object {
        private const val TAG: String = "BadaBleL2capClient"
        private const val CONNECTION_READY_TIMEOUT_MILLIS: Long = 8_000L
        private val PSM_RANGE: IntRange = 1..0xFFFF
    }
}

private class BleL2capClientTransport(
    private val channel: L2capChannel,
    private val salt: String,
    private val onClose: (BleL2capClientTransport) -> Unit,
) : ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private val ready = CompletableDeferred<Boolean>()
    private val writeLock = Any()
    private val saltedHash: ByteArray = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)
    private var pendingMultiplexBytes: ByteArray = ByteArray(0)

    @Volatile
    private var closed: Boolean = false

    override val medium: Medium = Medium.BLE_L2CAP

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
                channel.outputStream.flush()
            }

            override fun close() {
                this@BleL2capClientTransport.close()
            }
        }

    fun start() {
        Thread({ runPump() }, "bada-ble-l2cap-client").apply {
            isDaemon = true
            start()
        }
    }

    suspend fun awaitReady(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) { ready.await() } == true

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (!ready.isCompleted) ready.complete(false)
        runCatching {
            sendControlPacket(NearbyBleSocketFrames.encodeDisconnectionPacket())
        }
        runCatching {
            sendMultiplexBytes(
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
        runCatching { channel.close() }
        onClose(this)
    }

    private fun runPump() {
        try {
            if (!openDataConnection()) {
                close()
                return
            }
            sendControlPacket(NearbyBleSocketFrames.encodeIntroductionPacket())
            sendConnectionRequest()
            while (!closed) {
                val packetLengthPrefix = channel.inputStream.readExactly(L2CAP_PACKET_LENGTH_BYTES)
                val packetLength = packetLengthPrefix.decodeLength()
                if (packetLength <= 0 || packetLength >= FramedConnection.SANE_FRAME_LENGTH) {
                    DiagnosticLog.w(TAG, "invalid BLE L2CAP packet length=$packetLength")
                    close()
                    return
                }
                handleIncomingPacket(channel.inputStream.readExactly(packetLength))
            }
        } catch (_: EOFException) {
            close()
        } catch (io: IOException) {
            if (!closed) DiagnosticLog.w(TAG, "BLE L2CAP client pump failed", io)
            close()
        } catch (t: Throwable) {
            if (!closed) DiagnosticLog.w(TAG, "BLE L2CAP client pump crashed", t)
            close()
        }
    }

    private fun openDataConnection(): Boolean {
        channel.outputStream.write(COMMAND_REQUEST_DATA_CONNECTION)
        channel.outputStream.flush()
        val response = channel.inputStream.read()
        if (response == COMMAND_RESPONSE_DATA_CONNECTION_READY) {
            DiagnosticLog.i(TAG, "BLE L2CAP data connection ready")
            return true
        }
        DiagnosticLog.w(TAG, "unexpected BLE L2CAP data-connection response=$response")
        return false
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

    private fun handleIncomingPacket(packet: ByteArray) {
        if (packet.size < SERVICE_ID_HASH_LEN) {
            DiagnosticLog.w(TAG, "discarded undersized BLE L2CAP packet=${packet.toHex()}")
            close()
            return
        }
        val prefix = packet.copyOfRange(0, SERVICE_ID_HASH_LEN)
        when {
            prefix.contentEquals(CONTROL_PACKET_PREFIX) -> handleControlPacket(packet)
            prefix.contentEquals(NearbyServiceId.hashPrefix) -> {
                val payload = packet.copyOfRange(SERVICE_ID_HASH_LEN, packet.size)
                sendControlPacket(NearbyBleSocketFrames.encodePacketAcknowledgementPacket(payload.size))
                receiveMultiplexBytes(payload)
            }
            else -> {
                DiagnosticLog.w(TAG, "discarded BLE L2CAP packet for unexpected service hash packet=${packet.toHex()}")
                close()
            }
        }
    }

    private fun handleControlPacket(packet: ByteArray) {
        val control = NearbyBleSocketFrames.parseControlPacket(packet)
        if (control == null) {
            DiagnosticLog.w(TAG, "BLE L2CAP unknown control packet raw=${packet.toHex()}")
            return
        }
        when (control.type) {
            BleFramesProto.SocketControlFrame.ControlFrameType.PACKET_ACKNOWLEDGEMENT -> Unit
            BleFramesProto.SocketControlFrame.ControlFrameType.DISCONNECTION -> close()
            else -> DiagnosticLog.i(TAG, "BLE L2CAP control frame type=${control.type}")
        }
    }

    private fun receiveMultiplexBytes(bytes: ByteArray) {
        pendingMultiplexBytes += bytes
        var keepParsing = true
        while (keepParsing && pendingMultiplexBytes.size >= NearbyMultiplexFrames.LENGTH_PREFIX_BYTES) {
            val length = NearbyMultiplexFrames.decodeLength(pendingMultiplexBytes)
            if (
                length == null ||
                length < 0 ||
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
                DiagnosticLog.w(TAG, "discarded invalid multiplex frame=${frameBytes.toHex()}")
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
            DiagnosticLog.w(TAG, "BLE L2CAP virtual input closed while feeding data", io)
            close()
        }
    }

    private fun sendControlPacket(packet: ByteArray) {
        synchronized(writeLock) {
            channel.outputStream.writeLengthPrefixedPacket(packet)
        }
    }

    private fun sendMultiplexBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(writeLock) {
            channel.outputStream.writeLengthPrefixedPacket(NearbyServiceId.hashPrefix + bytes)
        }
    }

    private companion object {
        private const val TAG: String = "BadaBleL2capClient"
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
        private const val COMMAND_REQUEST_DATA_CONNECTION: Int = 3
        private const val COMMAND_RESPONSE_DATA_CONNECTION_READY: Int = 23
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

private fun randomSalt(): String {
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    return bytes.toHex()
}
