/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Length-prefixed framing layer for the Quick Share TCP transport.
 *
 * Every protobuf message exchanged on a Quick Share connection is preceded
 * by a **4-byte big-endian unsigned length prefix**. This class is the
 * lowest layer of the protocol stack: it does not interpret the payload at
 * all — it only delimits frames so higher layers (UKEY2, SecureMessage,
 * `OfflineFrame`, payload chunks) can decode whole messages without
 * over-reading.
 *
 * Reference: NearDrop's `NearbyShare/NearbyConnection.swift`
 * (`receiveFrameAsync` / `sendFrameAsync`). We deliberately mirror the same
 * 4-byte big-endian wire format, the same 5 MiB sanity cap on incoming
 * frames, and the same separation between graceful end-of-stream and
 * protocol-level errors.
 *
 * ### Threading model
 *
 * The constructor accepts a plain blocking [Socket]. All read/write work
 * is dispatched onto [Dispatchers.IO] so callers can invoke
 * [sendFrame]/[receiveFrame] from any coroutine context without blocking
 * an event-loop thread. Cancelling the calling coroutine does **not**
 * automatically interrupt an in-flight blocking read; callers that need
 * eager cancellation should also close the socket.
 *
 * ### Concurrency
 *
 * Reads and writes use independent locks (one for the input side, one for
 * the output side). It is therefore safe to call [sendFrame] and
 * [receiveFrame] from two different coroutines concurrently — that is
 * actually the expected pattern, since Quick Share is bidirectional. It
 * is **not** safe to invoke [receiveFrame] from two coroutines at once;
 * the second call will simply block on the read lock.
 *
 * ### Lifecycle
 *
 * [FramedConnection] does not own the socket's network lifecycle. Callers
 * are expected to close the socket (and any owning resource) through the
 * usual `Socket.close()` path; [close] on this object is a convenience
 * that simply forwards to the underlying socket.
 */
public class FramedConnection(
    private val socket: Socket,
) : AutoCloseable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    // Separate locks for read vs. write so the two halves of a duplex
    // connection do not contend with each other. Within each direction we
    // serialize calls so frames cannot interleave on the wire.
    private val readLock = Any()
    private val writeLock = Any()

    /**
     * Encodes [bytes] with a 4-byte big-endian length prefix and writes the
     * resulting frame to the socket.
     *
     * The output stream is flushed before returning so callers can rely on
     * frame boundaries for request/response handshakes (UKEY2 in
     * particular needs each ClientInit/ServerInit flush to be visible to
     * the peer before the next read).
     *
     * Note: the outgoing-frame size is **not** checked against
     * [SANE_FRAME_LENGTH]. The cap exists to bound peer-controlled
     * allocations on the receive side; on the send side the caller is
     * already trusted, and Quick Share file payloads are chunked by an
     * upper layer (`PayloadTransferFrame`) well below this limit.
     *
     * @throws IOException if the socket write fails or the connection is
     *   already closed.
     */
    public suspend fun sendFrame(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                // Build the prefix + payload as a single write so the kernel
                // is more likely to coalesce it into one TCP segment. This
                // is not strictly required for correctness — the receiver
                // re-buffers — but it avoids two flushes on small frames
                // (which UKEY2 has many of).
                val length = bytes.size
                val framed = ByteArray(LENGTH_PREFIX_BYTES + length)
                framed[BYTE_OFFSET_0] = (length ushr BIT_SHIFT_24).toByte()
                framed[BYTE_OFFSET_1] = (length ushr BIT_SHIFT_16).toByte()
                framed[BYTE_OFFSET_2] = (length ushr BIT_SHIFT_8).toByte()
                framed[BYTE_OFFSET_3] = length.toByte()
                bytes.copyInto(framed, destinationOffset = LENGTH_PREFIX_BYTES)
                output.write(framed)
                output.flush()
            }
        }
    }

    /**
     * Reads exactly one length-prefixed frame from the socket and returns
     * its payload (the prefix is stripped).
     *
     * Behavior on stream end:
     *  - If the peer closes the connection cleanly **between** frames
     *    (i.e. before any byte of the next length prefix is observed),
     *    this method throws [EndOfFrameStream]. Callers should treat that
     *    as graceful shutdown rather than an error, mirroring NearDrop's
     *    `handleConnectionClosure()` path.
     *  - If the peer closes mid-frame (after the length header has been
     *    partially or fully read but before the payload arrives in full),
     *    this is a truncation and surfaces as [EOFException] — that
     *    really is a protocol-level failure.
     *
     * Frames whose declared length is `>= SANE_FRAME_LENGTH` are rejected
     * with [OversizedFrameException] without consuming the payload bytes,
     * so the caller can decide whether to close the connection or
     * resynchronize. (In practice the connection is unrecoverable at that
     * point; the cap is there to bound attacker-controlled allocations.)
     *
     * Negative lengths (high bit set in the 32-bit header) are likewise
     * rejected with [OversizedFrameException]; treating the prefix as
     * unsigned matches NearDrop and Quick Share's wire format.
     *
     * @throws EndOfFrameStream when the peer closes cleanly between frames.
     * @throws OversizedFrameException when the declared length is outside
     *   `1..(SANE_FRAME_LENGTH - 1)`.
     * @throws EOFException when the stream ends partway through a frame.
     * @throws IOException for any other socket-level failure.
     */
    public suspend fun receiveFrame(): ByteArray =
        withContext(Dispatchers.IO) {
            synchronized(readLock) {
                val length = readFrameLength()
                readExactly(length)
            }
        }

    /**
     * Closes the underlying socket. Idempotent: subsequent calls are
     * no-ops.
     */
    override fun close() {
        socket.close()
    }

    /**
     * Reads the 4-byte big-endian length header.
     *
     * Returns the parsed length on success. Throws [EndOfFrameStream] if
     * EOF is observed *before any byte* of the header is available
     * (graceful close between frames). Throws [EOFException] if EOF is
     * observed mid-header (truncated frame). Throws
     * [OversizedFrameException] if the parsed length is unacceptable
     * (negative, zero, or `>= SANE_FRAME_LENGTH`).
     */
    private fun readFrameLength(): Int {
        val header = readHeaderBytes()

        // Big-endian decode. `and 0xFF` keeps each byte in 0..255 before
        // shifting; we then range-check the result so a header with the
        // high bit set (i.e. > 2 GiB on a signed Int) is treated as
        // oversize rather than wrapping to a negative `Int` and being
        // accepted as a 0-length read. Zero-length frames are also
        // rejected — NearDrop never emits them and accepting them would
        // let a peer waste CPU on no-op reads.
        val length =
            ((header[BYTE_OFFSET_0].toInt() and BYTE_MASK) shl BIT_SHIFT_24) or
                ((header[BYTE_OFFSET_1].toInt() and BYTE_MASK) shl BIT_SHIFT_16) or
                ((header[BYTE_OFFSET_2].toInt() and BYTE_MASK) shl BIT_SHIFT_8) or
                (header[BYTE_OFFSET_3].toInt() and BYTE_MASK)

        if (length <= 0 || length >= SANE_FRAME_LENGTH) {
            throw OversizedFrameException(length)
        }
        return length
    }

    /**
     * Reads the raw 4-byte length header from [input], looping over
     * partial reads. Returns the populated buffer.
     *
     * Distinguishes a clean half-close at a frame boundary
     * ([EndOfFrameStream], which is not a protocol error) from a
     * mid-header truncation ([EOFException], which is).
     */
    private fun readHeaderBytes(): ByteArray {
        val header = ByteArray(LENGTH_PREFIX_BYTES)
        var read = 0
        while (read < LENGTH_PREFIX_BYTES) {
            val n = input.read(header, read, LENGTH_PREFIX_BYTES - read)
            if (n < 0) {
                // Clean half-close at a frame boundary surfaces as a
                // dedicated, non-error signal so callers can treat it as
                // graceful shutdown. Anything mid-header is truncation.
                if (read == 0) throw EndOfFrameStream()
                throw EOFException(
                    "Stream closed mid-header after $read of $LENGTH_PREFIX_BYTES bytes",
                )
            }
            read += n
        }
        return header
    }

    /**
     * Reads exactly [length] bytes from [input], looping over partial
     * reads. Throws [EOFException] if the stream ends before `length`
     * bytes have been delivered.
     */
    private fun readExactly(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) {
                throw EOFException(
                    "Stream closed mid-frame after $read of $length payload bytes",
                )
            }
            read += n
        }
        return buffer
    }

    public companion object {
        /**
         * Width of the length prefix in bytes. Quick Share fixes this at
         * 4 (big-endian unsigned 32-bit).
         */
        public const val LENGTH_PREFIX_BYTES: Int = 4

        /**
         * Maximum tolerated incoming frame size, in bytes (5 MiB). Any
         * declared length `>=` this value is rejected as oversize.
         *
         * Matches NearDrop's `SANE_FRAME_LENGTH` and is well above the
         * largest single Quick Share control frame (`OfflineFrame`
         * payloads are chunked by `PayloadTransferFrame` to ~512 KiB), so
         * legitimate peers never bump into it. The cap exists purely as a
         * defense against attacker-controlled allocations.
         */
        public const val SANE_FRAME_LENGTH: Int = 5 * 1024 * 1024

        // Big-endian byte positions and shift counts. Hoisted into named
        // constants because detekt's MagicNumber rule (correctly) flags
        // bare `0`, `8`, `16`, `24`, `0xFF` in the encode/decode loops.
        private const val BYTE_OFFSET_0 = 0
        private const val BYTE_OFFSET_1 = 1
        private const val BYTE_OFFSET_2 = 2
        private const val BYTE_OFFSET_3 = 3
        private const val BIT_SHIFT_8 = 8
        private const val BIT_SHIFT_16 = 16
        private const val BIT_SHIFT_24 = 24
        private const val BYTE_MASK = 0xFF
    }
}

/**
 * Thrown by [FramedConnection.receiveFrame] when the peer closes the TCP
 * connection cleanly *between* frames. This is a normal end-of-stream
 * signal — not a protocol error — and should be handled as graceful
 * shutdown.
 *
 * Modeled on NearDrop's separation of `handleConnectionClosure()` from
 * `protocolError()`: a clean half-close at a frame boundary is expected
 * during ordinary connection teardown.
 */
public class EndOfFrameStream : IOException("Peer closed the connection at a frame boundary")

/**
 * Thrown by [FramedConnection.receiveFrame] when the declared frame length
 * is outside the acceptable range `1..(SANE_FRAME_LENGTH - 1)`.
 *
 * The connection is no longer trustworthy after this exception: the
 * payload bytes were never consumed (we refuse to allocate that much),
 * so the stream is desynchronized and the socket must be closed.
 */
public class OversizedFrameException(
    public val declaredLength: Int,
) : IOException(
        "Declared frame length $declaredLength is outside " +
            "1..${FramedConnection.SANE_FRAME_LENGTH - 1}",
    )
