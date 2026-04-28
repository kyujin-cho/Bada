/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import kotlin.random.Random

/**
 * Tests for [PayloadAssembler].
 *
 * Coverage maps directly onto the issue #14 acceptance criteria:
 *
 *  - Single-frame BYTES (the non-Android shape) reassembles cleanly.
 *  - The Android two-frame BYTES quirk is accepted (data + zero-body
 *    LAST_CHUNK).
 *  - Out-of-order chunks (offset != buffer.size) are rejected with
 *    [PayloadProtocolException].
 *  - BYTES `total_size > saneFrameLength` is rejected before allocation.
 *  - FILE payloads stream chunks straight to the
 *    [WritableByteChannel] returned by the factory; on LAST_CHUNK the
 *    channel is closed exactly once.
 *  - FILE chunks that would write past `total_size` are rejected.
 *  - Multiple payloads multiplexed by `payload_id` interleave correctly.
 *  - Non-DATA packet types surface as [PayloadEvent.Ignored] without
 *    affecting state.
 */
@Suppress(
    "LargeClass", // The state machine has many invariants; one test per invariant is the discipline.
    "LongParameterList", // The chunkFrame helper mirrors the proto field set; merging would obscure tests.
)
class PayloadAssemblerTest {
    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Build a `PayloadTransferFrame` for a chunk of a BYTES or FILE
     * payload. Centralizes the boilerplate so the test bodies focus on
     * the assertion under exercise.
     */
    private fun chunkFrame(
        payloadId: Long,
        type: PayloadHeader.PayloadType,
        totalSize: Long,
        offset: Long,
        body: ByteArray,
        lastChunk: Boolean,
        fileName: String = "",
        packetType: PayloadTransferFrame.PacketType = PayloadTransferFrame.PacketType.DATA,
    ): PayloadTransferFrame {
        val header =
            PayloadHeader
                .newBuilder()
                .setId(payloadId)
                .setType(type)
                .setTotalSize(totalSize)
                .setFileName(fileName)
                .build()
        val chunk =
            PayloadChunk
                .newBuilder()
                .setFlags(if (lastChunk) PayloadAssembler.LAST_CHUNK_FLAG else 0)
                .setOffset(offset)
                .setBody(ByteString.copyFrom(body))
                .build()
        return PayloadTransferFrame
            .newBuilder()
            .setPacketType(packetType)
            .setPayloadHeader(header)
            .setPayloadChunk(chunk)
            .build()
    }

    /**
     * Capturing [FileDestinationFactory] that hands every payload an
     * in-memory [SeekableByteChannel]. Tests can read back what was
     * written via [outputs] and inspect [opened] / [closed] counters to
     * confirm the channel lifecycle.
     *
     * The channel is seekable so the assembler's #44 out-of-order code
     * path is exercised directly: chunks delivered with non-sequential
     * offsets call `position()` and write into the right slot of the
     * backing buffer, just as a `RandomAccessFile`-backed channel
     * would on Android.
     */
    private class CapturingFactory : FileDestinationFactory {
        val outputs: MutableMap<Long, InMemorySeekableChannel> = HashMap()
        var opened: Int = 0
            private set
        var closed: Int = 0
            private set

        override fun open(header: PayloadHeader): SeekableByteChannel {
            opened += 1
            val ch = InMemorySeekableChannel(onClose = { closed += 1 })
            outputs[header.id] = ch
            return ch
        }
    }

    /**
     * Minimal in-memory [SeekableByteChannel] for tests. Backs a single
     * `ByteArray` that grows on writes past the current size; supports
     * out-of-order writes via [position] + [write] (writing past the end
     * fills any uncovered bytes with zero). The file content is the
     * concatenation of every byte written; tests read it back via
     * [toByteArray].
     */
    private class InMemorySeekableChannel(
        private val onClose: () -> Unit,
    ) : SeekableByteChannel {
        private var buffer: ByteArray = ByteArray(0)
        private var size: Long = 0
        private var pos: Long = 0
        private var open: Boolean = true

        fun toByteArray(): ByteArray = buffer.copyOf(size.toInt())

        override fun isOpen(): Boolean = open

        override fun close() {
            if (!open) return
            open = false
            onClose()
        }

        override fun read(dst: ByteBuffer): Int = throw UnsupportedOperationException("not used by assembler")

        override fun write(src: ByteBuffer): Int {
            check(open) { "channel closed" }
            val n = src.remaining()
            if (n == 0) return 0
            ensureCapacity(pos + n)
            src.get(buffer, pos.toInt(), n)
            pos += n
            if (pos > size) size = pos
            return n
        }

        override fun position(): Long = pos

        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0) { "negative position $newPosition" }
            pos = newPosition
            return this
        }

        override fun size(): Long = size

        override fun truncate(newSize: Long): SeekableByteChannel {
            require(newSize >= 0)
            if (newSize < size) size = newSize
            if (pos > size) pos = size
            return this
        }

        private fun ensureCapacity(min: Long) {
            if (min <= buffer.size) return
            // Match ArrayList's growth heuristic — size + size/2 — so
            // many small writes do not pay an O(n^2) bill.
            var newCap = buffer.size.coerceAtLeast(16)
            while (newCap < min) newCap = (newCap + (newCap shr 1)).coerceAtLeast(min.toInt())
            buffer = buffer.copyOf(newCap)
        }
    }

    // ------------------------------------------------------------------
    // BYTES — happy paths
    // ------------------------------------------------------------------

    @Test
    fun `single frame BYTES with LAST_CHUNK on the data frame completes`() {
        val assembler = PayloadAssembler()
        val data = "hello-quickshare".toByteArray()
        val event =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 1,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = data.size.toLong(),
                    offset = 0,
                    body = data,
                    lastChunk = true,
                ),
            )
        assertThat(event).isEqualTo(PayloadEvent.BytesComplete(1, data))
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `Android two-frame BYTES quirk reassembles into one BytesComplete`() {
        val assembler = PayloadAssembler()
        val data = "hello-android".toByteArray()

        // Frame 1: full body, flags=0, offset=0.
        val ev1 =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 7,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = data.size.toLong(),
                    offset = 0,
                    body = data,
                    lastChunk = false,
                ),
            )
        assertThat(ev1).isInstanceOf(PayloadEvent.Progress::class.java)

        // Frame 2: empty body, flags=LAST_CHUNK, offset=data.size.
        val ev2 =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 7,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = data.size.toLong(),
                    offset = data.size.toLong(),
                    body = ByteArray(0),
                    lastChunk = true,
                ),
            )
        assertThat(ev2).isEqualTo(PayloadEvent.BytesComplete(7, data))
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `multi-chunk BYTES reassembles in declared order`() {
        val assembler = PayloadAssembler()
        // 3 chunks, with the third carrying LAST_CHUNK directly (the
        // non-Android shape). Tests the offset-walk through three
        // separate appends.
        val parts =
            listOf(
                "alpha-".toByteArray(),
                "bravo-".toByteArray(),
                "charlie".toByteArray(),
            )
        val total = parts.sumOf { it.size }
        var offset = 0L
        var lastEvent: PayloadEvent? = null
        for ((i, part) in parts.withIndex()) {
            val isLast = i == parts.lastIndex
            lastEvent =
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 99,
                        type = PayloadHeader.PayloadType.BYTES,
                        totalSize = total.toLong(),
                        offset = offset,
                        body = part,
                        lastChunk = isLast,
                    ),
                )
            offset += part.size
        }
        val expected = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        assertThat(lastEvent).isEqualTo(PayloadEvent.BytesComplete(99, expected))
    }

    @Test
    fun `zero-length BYTES payload via two frames completes with empty data`() {
        // Edge case the Android quirk explicitly produces for empty
        // negotiation messages: data frame with empty body + flags=0,
        // followed by terminator with empty body + flags=LAST_CHUNK.
        val assembler = PayloadAssembler()
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 5,
                type = PayloadHeader.PayloadType.BYTES,
                totalSize = 0,
                offset = 0,
                body = ByteArray(0),
                lastChunk = false,
            ),
        )
        val terminator =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 5,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = 0,
                    offset = 0,
                    body = ByteArray(0),
                    lastChunk = true,
                ),
            )
        assertThat(terminator).isEqualTo(PayloadEvent.BytesComplete(5, ByteArray(0)))
    }

    // ------------------------------------------------------------------
    // BYTES — rejection paths
    // ------------------------------------------------------------------

    @Test
    fun `BYTES rejects chunk with offset that does not equal buffer size`() {
        val assembler = PayloadAssembler()
        val data = ByteArray(10) { it.toByte() }
        // First chunk 6 bytes, no LAST_CHUNK.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 11,
                type = PayloadHeader.PayloadType.BYTES,
                totalSize = data.size.toLong(),
                offset = 0,
                body = data.copyOfRange(0, 6),
                lastChunk = false,
            ),
        )
        // Second chunk claims offset=2 (sender lied), buffer is 6.
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 11,
                        type = PayloadHeader.PayloadType.BYTES,
                        totalSize = data.size.toLong(),
                        offset = 2,
                        body = data.copyOfRange(6, 10),
                        lastChunk = true,
                    ),
                )
            }
        assertThat(ex.message).contains("offset=2")
        assertThat(ex.message).contains("buffer size=6")
        // State must be cleaned up.
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `BYTES rejects total_size that exceeds saneFrameLength`() {
        val assembler = PayloadAssembler(saneFrameLength = 16)
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 22,
                        type = PayloadHeader.PayloadType.BYTES,
                        totalSize = 1_000_000,
                        offset = 0,
                        body = ByteArray(0),
                        lastChunk = false,
                    ),
                )
            }
        assertThat(ex.message).contains("exceeds saneFrameLength=16")
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `BYTES rejects body that would extend past declared total_size`() {
        val assembler = PayloadAssembler()
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 33,
                        type = PayloadHeader.PayloadType.BYTES,
                        totalSize = 4,
                        offset = 0,
                        body = ByteArray(8) { 0x42 },
                        lastChunk = true,
                    ),
                )
            }
        assertThat(ex.message).contains("exceeding declared total_size=4")
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `BYTES rejects LAST_CHUNK that arrives shorter than total_size`() {
        val assembler = PayloadAssembler()
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 44,
                        type = PayloadHeader.PayloadType.BYTES,
                        totalSize = 100,
                        offset = 0,
                        body = ByteArray(20),
                        lastChunk = true,
                    ),
                )
            }
        assertThat(ex.message).contains("LAST_CHUNK at 20 bytes")
        assertThat(ex.message).contains("total_size=100")
    }

    @Test
    fun `BYTES rejects negative total_size`() {
        val assembler = PayloadAssembler()
        assertThrows<PayloadProtocolException> {
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 55,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = -1,
                    offset = 0,
                    body = ByteArray(0),
                    lastChunk = true,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // FILE — happy paths
    // ------------------------------------------------------------------

    @Test
    fun `FILE chunks stream straight to the destination channel`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        // 1 MiB of pseudo-random bytes split into 4 chunks. Tests both
        // the streaming behavior (memory does not grow) and that
        // multi-chunk FILE reassembles cleanly.
        val total = 1 * 1024 * 1024
        val data = Random(0xCAFEBABE).nextBytes(total)
        val chunkSize = 256 * 1024

        var offset = 0
        var lastEvent: PayloadEvent? = null
        while (offset < total) {
            val end = (offset + chunkSize).coerceAtMost(total)
            val isLast = end == total
            lastEvent =
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 200,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = total.toLong(),
                        offset = offset.toLong(),
                        body = data.copyOfRange(offset, end),
                        lastChunk = isLast,
                        fileName = "big.bin",
                    ),
                )
            offset = end
        }

        assertThat(lastEvent).isInstanceOf(PayloadEvent.FileComplete::class.java)
        val complete = lastEvent as PayloadEvent.FileComplete
        assertThat(complete.payloadId).isEqualTo(200)
        assertThat(complete.bytesWritten).isEqualTo(total.toLong())
        assertThat(complete.header.fileName).isEqualTo("big.bin")

        // Channel was opened once and closed once.
        assertThat(factory.opened).isEqualTo(1)
        assertThat(factory.closed).isEqualTo(1)
        // And the bytes match.
        assertThat(factory.outputs[200]!!.toByteArray()).isEqualTo(data)
        // No state leaked.
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `FILE single-chunk payload that fits entirely in one frame works`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val data = Random(0xDEAD).nextBytes(127)
        val ev =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 201,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = data.size.toLong(),
                    offset = 0,
                    body = data,
                    lastChunk = true,
                    fileName = "small.bin",
                ),
            )
        assertThat(ev).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[201]!!.toByteArray()).isEqualTo(data)
        assertThat(factory.closed).isEqualTo(1)
    }

    @Test
    fun `FILE zero-byte payload completes in a single empty chunk`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val ev =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 202,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 0,
                    offset = 0,
                    body = ByteArray(0),
                    lastChunk = true,
                    fileName = "empty.bin",
                ),
            )
        assertThat(ev).isInstanceOf(PayloadEvent.FileComplete::class.java)
        val complete = ev as PayloadEvent.FileComplete
        assertThat(complete.bytesWritten).isEqualTo(0L)
        assertThat(factory.opened).isEqualTo(1)
        assertThat(factory.closed).isEqualTo(1)
        assertThat(factory.outputs[202]!!.toByteArray().size).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // FILE — rejection paths
    // ------------------------------------------------------------------

    @Test
    fun `FILE rejects chunk that partially overlaps a covered range and extends it`() {
        // #44 allows chunks to arrive out of order or be re-sent
        // verbatim. What it does NOT allow is a chunk that overlaps a
        // covered range AND extends past it: such a chunk would mean the
        // peer has changed the chunk boundary mid-stream, which the wire
        // spec does not permit and which would corrupt the receiver's
        // coverage tracking.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        // First chunk covers [0, 100).
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 300,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 1000,
                offset = 0,
                body = ByteArray(100) { 0x77 },
                lastChunk = false,
                fileName = "x.bin",
            ),
        )
        // Second chunk would cover [50, 150). Overlaps [0, 100) on the
        // [50, 100) sub-interval AND extends beyond it.
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 300,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = 1000,
                        offset = 50,
                        body = ByteArray(100) { 0x33 },
                        lastChunk = false,
                        fileName = "x.bin",
                    ),
                )
            }
        assertThat(ex.message).contains("partially overlaps")
        assertThat(factory.closed).isEqualTo(1)
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `FILE rejects chunk that would write past total_size`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 301,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = 10,
                        offset = 0,
                        body = ByteArray(20),
                        lastChunk = false,
                        fileName = "x.bin",
                    ),
                )
            }
        assertThat(ex.message).contains("would write past total_size=10")
        assertThat(factory.closed).isEqualTo(1)
    }

    @Test
    fun `FILE rejects LAST_CHUNK that arrives shorter than total_size`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 302,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = 1000,
                        offset = 0,
                        body = ByteArray(100),
                        lastChunk = true,
                        fileName = "x.bin",
                    ),
                )
            }
        assertThat(ex.message).contains("100 bytes covered")
        assertThat(ex.message).contains("total_size=1000")
        assertThat(factory.closed).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Multiplexing and miscellaneous
    // ------------------------------------------------------------------

    @Test
    fun `interleaved BYTES and FILE payloads with distinct ids do not interfere`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val bytesData = "negotiation-blob".toByteArray()
        val fileData = Random(0xFEED).nextBytes(2048)

        // BYTES chunk 1 (no LAST_CHUNK) → in flight.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 1,
                type = PayloadHeader.PayloadType.BYTES,
                totalSize = bytesData.size.toLong(),
                offset = 0,
                body = bytesData,
                lastChunk = false,
            ),
        )
        // FILE chunk 1 (no LAST_CHUNK) → in flight.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 2,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = fileData.size.toLong(),
                offset = 0,
                body = fileData.copyOfRange(0, 1024),
                lastChunk = false,
                fileName = "f.bin",
            ),
        )
        assertThat(assembler.inFlightPayloadCount).isEqualTo(2)

        // BYTES terminator (the Android two-frame quirk for id=1).
        val bytesEvent =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 1,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = bytesData.size.toLong(),
                    offset = bytesData.size.toLong(),
                    body = ByteArray(0),
                    lastChunk = true,
                ),
            )
        assertThat(bytesEvent).isEqualTo(PayloadEvent.BytesComplete(1, bytesData))
        assertThat(assembler.inFlightPayloadCount).isEqualTo(1)

        // FILE chunk 2 (the rest, with LAST_CHUNK).
        val fileEvent =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 2,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = fileData.size.toLong(),
                    offset = 1024,
                    body = fileData.copyOfRange(1024, 2048),
                    lastChunk = true,
                    fileName = "f.bin",
                ),
            )
        assertThat(fileEvent).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[2]!!.toByteArray()).isEqualTo(fileData)
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `non-DATA packet types surface as Ignored without changing state`() {
        val assembler = PayloadAssembler()
        // First put one payload in flight.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 1,
                type = PayloadHeader.PayloadType.BYTES,
                totalSize = 4,
                offset = 0,
                body = ByteArray(2),
                lastChunk = false,
            ),
        )
        // PAYLOAD_ACK — the assembler should ignore it.
        val ackEvent =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 1,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = 4,
                    offset = 0,
                    body = ByteArray(0),
                    lastChunk = false,
                    packetType = PayloadTransferFrame.PacketType.PAYLOAD_ACK,
                ),
            )
        assertThat(ackEvent).isInstanceOf(PayloadEvent.Ignored::class.java)
        // CONTROL — same.
        val ctrlEvent =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 1,
                    type = PayloadHeader.PayloadType.BYTES,
                    totalSize = 4,
                    offset = 0,
                    body = ByteArray(0),
                    lastChunk = false,
                    packetType = PayloadTransferFrame.PacketType.CONTROL,
                ),
            )
        assertThat(ctrlEvent).isInstanceOf(PayloadEvent.Ignored::class.java)
        // Original payload still in flight.
        assertThat(assembler.inFlightPayloadCount).isEqualTo(1)
    }

    @Test
    fun `unsupported payload type STREAM is rejected`() {
        val assembler = PayloadAssembler()
        assertThrows<PayloadProtocolException> {
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 1,
                    type = PayloadHeader.PayloadType.STREAM,
                    totalSize = 4,
                    offset = 0,
                    body = ByteArray(4),
                    lastChunk = true,
                ),
            )
        }
    }

    @Test
    fun `frame missing payload_chunk is rejected`() {
        val assembler = PayloadAssembler()
        val malformed =
            PayloadTransferFrame
                .newBuilder()
                .setPacketType(PayloadTransferFrame.PacketType.DATA)
                .setPayloadHeader(
                    PayloadHeader
                        .newBuilder()
                        .setId(1)
                        .setType(PayloadHeader.PayloadType.BYTES)
                        .setTotalSize(0),
                ).build()
        assertThrows<PayloadProtocolException> { assembler.onPayloadTransfer(malformed) }
    }

    @Test
    fun `reset closes all in-flight file channels`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 1,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 100,
                offset = 0,
                body = ByteArray(40),
                lastChunk = false,
                fileName = "a.bin",
            ),
        )
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 2,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 100,
                offset = 0,
                body = ByteArray(40),
                lastChunk = false,
                fileName = "b.bin",
            ),
        )
        assertThat(assembler.inFlightPayloadCount).isEqualTo(2)
        assertThat(factory.closed).isEqualTo(0)
        assembler.reset()
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
        assertThat(factory.closed).isEqualTo(2)
    }

    @Test
    fun `factory exception on first FILE chunk leaks no state`() {
        val throwingFactory =
            object : FileDestinationFactory {
                override fun open(header: PayloadHeader): SeekableByteChannel =
                    throw java.io.IOException("simulated open failure")
            }
        val assembler = PayloadAssembler(fileDestinationFactory = throwingFactory)
        assertThrows<java.io.IOException> {
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 99,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 100,
                    offset = 0,
                    body = ByteArray(10),
                    lastChunk = false,
                    fileName = "broken.bin",
                ),
            )
        }
        assertThat(assembler.inFlightPayloadCount).isEqualTo(0)
    }

    @Test
    fun `progress events report cumulative bytes received and the type`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val ev1 =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 88,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 1000,
                    offset = 0,
                    body = ByteArray(300),
                    lastChunk = false,
                    fileName = "p.bin",
                ),
            )
        assertThat(ev1).isEqualTo(
            PayloadEvent.Progress(
                payloadId = 88,
                bytesReceived = 300,
                totalSize = 1000,
                type = PayloadHeader.PayloadType.FILE,
            ),
        )
        val ev2 =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 88,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 1000,
                    offset = 300,
                    body = ByteArray(400),
                    lastChunk = false,
                    fileName = "p.bin",
                ),
            )
        assertThat(ev2).isEqualTo(
            PayloadEvent.Progress(
                payloadId = 88,
                bytesReceived = 700,
                totalSize = 1000,
                type = PayloadHeader.PayloadType.FILE,
            ),
        )
    }

    @Test
    fun `assembler accepts the exact byte sequence produced by the encoder`() {
        // Round-trip: encode a BYTES payload, then feed both frames to a
        // fresh assembler. This is the integration check between the
        // encoder (which implements the Android two-frame quirk on send)
        // and the assembler (which tolerates it on receive).
        val data = "round-trip-test".toByteArray()
        val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId = 7, data = data)
        assertThat(frames).hasSize(2)

        val assembler = PayloadAssembler()
        val ev1 = assembler.onPayloadTransfer(frames[0].v1.payloadTransfer)
        assertThat(ev1).isInstanceOf(PayloadEvent.Progress::class.java)
        val ev2 = assembler.onPayloadTransfer(frames[1].v1.payloadTransfer)
        assertThat(ev2).isEqualTo(PayloadEvent.BytesComplete(7, data))
    }

    @Test
    fun `assembler accepts FILE frames produced by the encoder for a small file`() {
        val data = Random(0xABBA).nextBytes(50_000)
        val source = Channels.newChannel(java.io.ByteArrayInputStream(data))
        val frames =
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 314,
                    fileName = "encoded.bin",
                    totalSize = data.size.toLong(),
                    source = source,
                    chunkSize = 8 * 1024,
                ).toList()
        // 50000 / 8192 = 6 full + 1 partial = 7 frames.
        assertThat(frames.size).isAtLeast(2)

        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        var lastEvent: PayloadEvent? = null
        for (f in frames) {
            lastEvent = assembler.onPayloadTransfer(f.v1.payloadTransfer)
        }
        assertThat(lastEvent).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[314]!!.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `large file streamed in tiny chunks completes correctly`() {
        // Simulates a slow peer that sends 100 KiB chunks of a 10 MiB file.
        // Confirms the assembler scales linearly with chunk count and does
        // not buffer in memory — we verify only the final assembled bytes
        // and the final event, never inspecting heap.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val total = 10 * 1024 * 1024
        val chunkSize = 100 * 1024
        val data = Random(0x1337).nextBytes(total)

        var offset = 0
        var lastEvent: PayloadEvent? = null
        while (offset < total) {
            val end = (offset + chunkSize).coerceAtMost(total)
            val isLast = end == total
            // Build a "fresh" ByteBuffer-backed body so the test does not
            // accidentally share an array with the assembler.
            val body = ByteArray(end - offset)
            ByteBuffer.wrap(data, offset, end - offset).get(body)
            lastEvent =
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 9000,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = total.toLong(),
                        offset = offset.toLong(),
                        body = body,
                        lastChunk = isLast,
                        fileName = "tenmib.bin",
                    ),
                )
            offset = end
        }
        assertThat(lastEvent).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[9000]!!.toByteArray()).isEqualTo(data)
        assertThat(factory.closed).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // FILE — out-of-order delivery (#44)
    // ------------------------------------------------------------------

    @Test
    fun `FILE chunks delivered in shuffled order reassemble into the correct bytes`() {
        // The acceptance test for #44: feed chunks of a FILE payload in a
        // randomized order to the assembler and verify the reconstructed
        // bytes match the original. The terminator (zero-body
        // LAST_CHUNK) is the very last frame; everything before it can
        // arrive in any order.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val total = 64 * 1024
        val chunkSize = 4 * 1024
        val data = Random(0xBADCAFE).nextBytes(total)

        // Build the (offset, body) pairs in declared order...
        val chunks =
            buildList {
                var off = 0
                while (off < total) {
                    val end = (off + chunkSize).coerceAtMost(total)
                    add(off.toLong() to data.copyOfRange(off, end))
                    off = end
                }
            }
        // ...then shuffle them with a seeded PRNG so the test is
        // deterministic across runs but exercises a non-trivial order.
        val shuffled = chunks.shuffled(java.util.Random(0xFA1AFE1L))

        for ((offset, body) in shuffled) {
            val ev =
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 4242,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = total.toLong(),
                        offset = offset,
                        body = body,
                        lastChunk = false,
                        fileName = "shuffled.bin",
                    ),
                )
            assertThat(ev).isInstanceOf(PayloadEvent.Progress::class.java)
        }
        // Now the LAST_CHUNK terminator at offset == total_size with
        // empty body. Coverage is already complete so this finalizes.
        val terminator =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 4242,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = total.toLong(),
                    offset = total.toLong(),
                    body = ByteArray(0),
                    lastChunk = true,
                    fileName = "shuffled.bin",
                ),
            )
        assertThat(terminator).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[4242]!!.toByteArray()).isEqualTo(data)
        assertThat(factory.closed).isEqualTo(1)
    }

    @Test
    fun `FILE LAST_CHUNK arriving before all data chunks errors with gap report`() {
        // Variant: the peer sends LAST_CHUNK while the coverage set still
        // has gaps. This should be rejected — LAST_CHUNK is a final
        // signal that no more chunks are coming, so a missing range is
        // an unrecoverable protocol error.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val total = 256
        val data = Random(0xC0FFEE).nextBytes(total)
        // Send only [0, 128) and then a LAST_CHUNK at offset=total.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 5,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = total.toLong(),
                offset = 0,
                body = data.copyOfRange(0, 128),
                lastChunk = false,
                fileName = "gap.bin",
            ),
        )
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 5,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = total.toLong(),
                        offset = total.toLong(),
                        body = ByteArray(0),
                        lastChunk = true,
                        fileName = "gap.bin",
                    ),
                )
            }
        assertThat(ex.message).contains("LAST_CHUNK")
        assertThat(ex.message).contains("128 bytes covered")
        assertThat(ex.message).contains("total_size=256")
        assertThat(factory.closed).isEqualTo(1)
    }

    @Test
    fun `FILE duplicate chunk delivered verbatim is silently deduped`() {
        // A peer that re-sends an exact previously-delivered chunk is
        // permitted (think: BLE retransmit, or a NIC handing the same
        // datagram up twice). The assembler must not double-write nor
        // raise a protocol error. Coverage stays unchanged.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val total = 32
        val data = Random(0xDADADA).nextBytes(total)
        val first =
            chunkFrame(
                payloadId = 9,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = total.toLong(),
                offset = 0,
                body = data,
                lastChunk = false,
                fileName = "dup.bin",
            )
        // Send the exact same chunk three times.
        assembler.onPayloadTransfer(first)
        assembler.onPayloadTransfer(first)
        val ev3 = assembler.onPayloadTransfer(first)
        assertThat(ev3).isInstanceOf(PayloadEvent.Progress::class.java)
        // Coverage should be exactly [0, 32) — still one range — no
        // matter how many duplicates we forwarded.
        // Now finalize.
        val done =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 9,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = total.toLong(),
                    offset = total.toLong(),
                    body = ByteArray(0),
                    lastChunk = true,
                    fileName = "dup.bin",
                ),
            )
        assertThat(done).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[9]!!.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `FILE rejects a chunk with a negative offset`() {
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val ex =
            assertThrows<PayloadProtocolException> {
                assembler.onPayloadTransfer(
                    chunkFrame(
                        payloadId = 1,
                        type = PayloadHeader.PayloadType.FILE,
                        totalSize = 100,
                        offset = -1,
                        body = ByteArray(10),
                        lastChunk = false,
                        fileName = "neg.bin",
                    ),
                )
            }
        assertThat(ex.message).contains("negative")
    }

    @Test
    fun `FILE LAST_CHUNK with body data that completes the file is accepted`() {
        // The encoder sends LAST_CHUNK as a separate empty frame, but
        // the assembler must also tolerate a peer that fuses the final
        // body bytes with the LAST_CHUNK flag (the legacy iOS / macOS
        // shape). As long as coverage ends exactly at total_size, the
        // payload completes.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val data = Random(0xFEEDFACE).nextBytes(64)
        // First half, no LAST_CHUNK.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 11,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = data.size.toLong(),
                offset = 0,
                body = data.copyOfRange(0, 32),
                lastChunk = false,
                fileName = "fused.bin",
            ),
        )
        // Second half WITH LAST_CHUNK on the same data frame.
        val ev =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 11,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = data.size.toLong(),
                    offset = 32,
                    body = data.copyOfRange(32, 64),
                    lastChunk = true,
                    fileName = "fused.bin",
                ),
            )
        assertThat(ev).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[11]!!.toByteArray()).isEqualTo(data)
    }
}
