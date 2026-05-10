/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package dev.bluehouse.bada.protocol.medium

import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.EndOfFrameStream
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.transport.OversizedFrameException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom

/**
 * Sender-side wrapper for Nearby's multiplex socket layer.
 *
 * Stock Quick Share may treat the TCP connection discovered through mDNS as
 * only the physical pipe. In that mode the peer waits for a Multiplex
 * CONNECTION_REQUEST first, then exposes DATA_FRAME payloads as the normal
 * OfflineFrame byte stream.
 */
internal class NearbyMultiplexClientTransport(
    private val physicalTransport: ConnectedTransport,
    private val salt: String = randomMultiplexSalt(),
    private val requireConnectionResponse: Boolean = true,
    private val optimisticReadyDelayMillis: Long = 0L,
    private val logger: (String) -> Unit = {},
) : ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private val ready = CompletableDeferred<Boolean>()
    private val writeLock = Any()
    private val saltedHash: ByteArray = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)

    @Volatile
    private var closed: Boolean = false

    override val medium: Medium = physicalTransport.medium

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
                sendPhysicalFrame(
                    NearbyMultiplexFrames.encodeDataFrame(
                        saltedServiceIdHash = saltedHash,
                        data = b.copyOfRange(off, off + len),
                    ),
                )
            }

            override fun flush() {
                physicalTransport.outputStream.flush()
            }

            override fun close() {
                this@NearbyMultiplexClientTransport.close()
            }
        }

    fun start() {
        Thread({ runPump() }, "bada-nearby-multiplex-client").apply {
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
            sendPhysicalFrame(
                NearbyMultiplexFrames.encodeDisconnectionFrame(
                    saltedServiceIdHash = saltedHash,
                    serviceIdHashSalt = salt,
                ),
            )
        }
        runCatching { inputWriter.close() }
        runCatching { input.close() }
        runCatching { physicalTransport.close() }
    }

    private fun runPump() {
        try {
            sendConnectionRequest()
            if (!requireConnectionResponse && !ready.isCompleted) {
                completeOptimisticReadyAfterDelay()
            }
            while (!closed) {
                val frameBytes = physicalTransport.inputStream.readLengthPrefixedPayload()
                handleMultiplexFrame(frameBytes)
            }
        } catch (_: EndOfFrameStream) {
            close()
        } catch (_: EOFException) {
            close()
        } catch (io: IOException) {
            if (!closed) logger("nearby-multiplex: client pump failed: ${io.message ?: io::class.simpleName}")
            close()
        } catch (t: Throwable) {
            if (!closed) logger("nearby-multiplex: client pump crashed: ${t.message ?: t::class.simpleName}")
            close()
        }
    }

    private fun sendConnectionRequest() {
        sendPhysicalFrame(
            NearbyMultiplexFrames.encodeConnectionRequestFrame(
                saltedServiceIdHash = saltedHash,
                serviceIdHashSalt = salt,
            ),
        )
    }

    private fun completeOptimisticReadyAfterDelay() {
        if (optimisticReadyDelayMillis <= 0L) {
            ready.complete(true)
            logger("nearby-multiplex: optimistic virtual socket opened before CONNECTION_RESPONSE")
            return
        }
        Thread(
            {
                runCatching { Thread.sleep(optimisticReadyDelayMillis) }
                if (!closed && !ready.isCompleted) {
                    ready.complete(true)
                    logger("nearby-multiplex: optimistic virtual socket opened after receiver grace")
                }
            },
            "bada-nearby-multiplex-ready",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleMultiplexFrame(frameBytes: ByteArray) {
        val frame =
            NearbyMultiplexFrames.parseFrame(frameBytes) ?: run {
                logger("nearby-multiplex: discarded invalid client frame")
                close()
                return
            }
        if (
            !frame.header.saltedServiceIdHash
                .toByteArray()
                .contentEquals(saltedHash)
        ) {
            logger("nearby-multiplex: discarded client frame for unexpected service hash")
            return
        }
        when (frame.frameType) {
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME ->
                handleControlFrame(frame)
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME ->
                feedIncoming(frame.dataFrame.data.toByteArray())
            else -> Unit
        }
    }

    private fun handleControlFrame(frame: MultiplexFramesProto.MultiplexFrame) {
        when (frame.controlFrame.controlFrameType) {
            MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_RESPONSE -> {
                val accepted =
                    frame.controlFrame.connectionResponseFrame.connectionResponseCode ==
                        MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED
                if (!ready.isCompleted) ready.complete(accepted)
                if (!accepted) {
                    logger("nearby-multiplex: peer rejected virtual socket")
                    close()
                }
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
            logger("nearby-multiplex: client virtual input closed: ${io.message ?: io::class.simpleName}")
            close()
        }
    }

    private fun sendPhysicalFrame(frameBytes: ByteArray) {
        synchronized(writeLock) {
            physicalTransport.outputStream.write(NearbyMultiplexFrames.encodeLengthPrefixed(frameBytes))
            physicalTransport.outputStream.flush()
        }
    }

    private companion object {
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
    }
}

/**
 * Receiver-side probe for a Wi-Fi LAN physical socket.
 *
 * It consumes exactly one length-prefixed physical frame. If that frame is a
 * valid Nearby multiplex CONNECTION_REQUEST, the returned transport is the
 * accepted virtual socket. Otherwise the original bytes are preserved and the
 * returned transport behaves like the raw socket that older Bada/NearDrop
 * peers expect.
 */
internal fun probeNearbyMultiplexServerTransport(
    physicalTransport: ConnectedTransport,
    logger: (String) -> Unit = {},
): ConnectedTransport {
    val firstFrame = physicalTransport.inputStream.readLengthPrefixedFrame()
    val parsed = NearbyMultiplexFrames.parseFrame(firstFrame.payload)
    val control = parsed?.controlFrame
    if (
        parsed?.frameType != MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME ||
        control?.controlFrameType !=
        MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_REQUEST
    ) {
        logger("nearby-multiplex: LAN socket using raw Nearby stream")
        return PrebufferedConnectedTransport(physicalTransport, firstFrame.originalBytes)
    }

    val salt = parsed.header.serviceIdHashSalt
    val saltedHash = parsed.header.saltedServiceIdHash.toByteArray()
    val expected = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)
    val response =
        if (saltedHash.contentEquals(expected)) {
            MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED
        } else {
            MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.NOT_LISTENING
        }
    physicalTransport.outputStream.write(
        NearbyMultiplexFrames.encodeLengthPrefixed(
            NearbyMultiplexFrames.encodeConnectionResponseFrame(
                saltedServiceIdHash = saltedHash,
                serviceIdHashSalt = salt,
                responseCode = response,
            ),
        ),
    )
    physicalTransport.outputStream.flush()
    if (response != MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED) {
        throw IOException("Nearby multiplex service hash did not match")
    }

    logger("nearby-multiplex: LAN socket using multiplex stream")
    return NearbyMultiplexServerTransport(
        physicalTransport = physicalTransport,
        saltedHash = saltedHash,
        salt = salt,
        logger = logger,
    ).also { it.start() }
}

private class NearbyMultiplexServerTransport(
    private val physicalTransport: ConnectedTransport,
    private val saltedHash: ByteArray,
    private val salt: String,
    private val logger: (String) -> Unit,
) : ConnectedTransport {
    private val input = PipedInputStream(INPUT_PIPE_SIZE)
    private val inputWriter = PipedOutputStream(input)
    private val writeLock = Any()

    @Volatile
    private var closed: Boolean = false

    override val medium: Medium = physicalTransport.medium

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
                sendPhysicalFrame(
                    NearbyMultiplexFrames.encodeDataFrame(
                        saltedServiceIdHash = saltedHash,
                        data = b.copyOfRange(off, off + len),
                    ),
                )
            }

            override fun flush() {
                physicalTransport.outputStream.flush()
            }

            override fun close() {
                this@NearbyMultiplexServerTransport.close()
            }
        }

    fun start() {
        Thread({ runPump() }, "bada-nearby-multiplex-server").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            sendPhysicalFrame(
                NearbyMultiplexFrames.encodeDisconnectionFrame(
                    saltedServiceIdHash = saltedHash,
                    serviceIdHashSalt = salt,
                ),
            )
        }
        runCatching { inputWriter.close() }
        runCatching { input.close() }
        runCatching { physicalTransport.close() }
    }

    private fun runPump() {
        try {
            while (!closed) {
                handleMultiplexFrame(physicalTransport.inputStream.readLengthPrefixedPayload())
            }
        } catch (_: EndOfFrameStream) {
            close()
        } catch (_: EOFException) {
            close()
        } catch (io: IOException) {
            if (!closed) logger("nearby-multiplex: server pump failed: ${io.message ?: io::class.simpleName}")
            close()
        } catch (t: Throwable) {
            if (!closed) logger("nearby-multiplex: server pump crashed: ${t.message ?: t::class.simpleName}")
            close()
        }
    }

    private fun handleMultiplexFrame(frameBytes: ByteArray) {
        val frame =
            NearbyMultiplexFrames.parseFrame(frameBytes) ?: run {
                logger("nearby-multiplex: discarded invalid server frame")
                close()
                return
            }
        if (
            !frame.header.saltedServiceIdHash
                .toByteArray()
                .contentEquals(saltedHash)
        ) {
            logger("nearby-multiplex: discarded server frame for unexpected service hash")
            return
        }
        when (frame.frameType) {
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME ->
                if (
                    frame.controlFrame.controlFrameType ==
                    MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.DISCONNECTION
                ) {
                    close()
                }
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME ->
                feedIncoming(frame.dataFrame.data.toByteArray())
            else -> Unit
        }
    }

    private fun feedIncoming(bytes: ByteArray) {
        if (closed) return
        try {
            inputWriter.write(bytes)
            inputWriter.flush()
        } catch (io: IOException) {
            logger("nearby-multiplex: server virtual input closed: ${io.message ?: io::class.simpleName}")
            close()
        }
    }

    private fun sendPhysicalFrame(frameBytes: ByteArray) {
        synchronized(writeLock) {
            physicalTransport.outputStream.write(NearbyMultiplexFrames.encodeLengthPrefixed(frameBytes))
            physicalTransport.outputStream.flush()
        }
    }

    private companion object {
        private const val INPUT_PIPE_SIZE: Int = 1024 * 1024
    }
}

private class PrebufferedConnectedTransport(
    private val physicalTransport: ConnectedTransport,
    prebufferedBytes: ByteArray,
) : ConnectedTransport {
    override val medium: Medium = physicalTransport.medium

    override val inputStream: InputStream = PrebufferedInputStream(prebufferedBytes, physicalTransport.inputStream)

    override val outputStream: OutputStream = physicalTransport.outputStream

    override fun close() {
        physicalTransport.close()
    }
}

private class PrebufferedInputStream(
    private val prebufferedBytes: ByteArray,
    private val delegate: InputStream,
) : InputStream() {
    private var offset: Int = 0

    override fun read(): Int {
        if (offset < prebufferedBytes.size) {
            return prebufferedBytes[offset++].toInt() and BYTE_MASK
        }
        return delegate.read()
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        return if (offset >= prebufferedBytes.size) {
            delegate.read(b, off, len)
        } else {
            val count = minOf(len, prebufferedBytes.size - offset)
            prebufferedBytes.copyInto(b, destinationOffset = off, startIndex = offset, endIndex = offset + count)
            offset += count
            count
        }
    }

    override fun available(): Int = (prebufferedBytes.size - offset) + delegate.available()

    override fun close() {
        delegate.close()
    }

    private companion object {
        private const val BYTE_MASK: Int = 0xFF
    }
}

private data class LengthPrefixedFrame(
    val payload: ByteArray,
    val originalBytes: ByteArray,
)

private fun InputStream.readLengthPrefixedPayload(): ByteArray = readLengthPrefixedFrame().payload

private fun InputStream.readLengthPrefixedFrame(): LengthPrefixedFrame {
    val header = readHeaderBytes()
    val length = NearbyMultiplexFrames.decodeLength(header) ?: throw EOFException("missing length prefix")
    if (length <= 0 || length >= FramedConnection.SANE_FRAME_LENGTH) {
        throw OversizedFrameException(length)
    }
    val payload = readExactly(length)
    return LengthPrefixedFrame(payload = payload, originalBytes = header + payload)
}

private fun InputStream.readHeaderBytes(): ByteArray {
    val header = ByteArray(NearbyMultiplexFrames.LENGTH_PREFIX_BYTES)
    var read = 0
    while (read < header.size) {
        val n = read(header, read, header.size - read)
        if (n < 0) {
            if (read == 0) throw EndOfFrameStream()
            throw EOFException("Stream closed mid-header after $read of ${header.size} bytes")
        }
        read += n
    }
    return header
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = read(buffer, read, length - read)
        if (n < 0) {
            throw EOFException("Stream closed mid-frame after $read of $length payload bytes")
        }
        read += n
    }
    return buffer
}

private fun randomMultiplexSalt(): String {
    val bytes = ByteArray(SALT_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val SALT_BYTES: Int = 8
