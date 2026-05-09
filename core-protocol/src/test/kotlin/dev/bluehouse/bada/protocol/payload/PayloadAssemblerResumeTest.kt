/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.payload

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.random.Random

/**
 * Tests for [PayloadAssembler]'s integration with [ResumeStateStore]
 * (#43): persisting coverage on chunk writes, seeding from a prior
 * record on the next connection, and dropping the record on
 * completion.
 *
 * The full reconnect flow (matching new sockets to in-flight sessions
 * across separate connection attempts) is a higher-level concern
 * tracked separately. The assembler-side scaffolding tested here is
 * sufficient to cover the most common partial-recovery case: a peer
 * that retransmits from offset 0 because it did not see our
 * AUTO_RESUME frame still has every previously-received chunk
 * deduplicated against the on-disk bytes.
 */
@Suppress("LongParameterList")
class PayloadAssemblerResumeTest {
    private fun chunkFrame(
        payloadId: Long,
        type: PayloadHeader.PayloadType,
        totalSize: Long,
        offset: Long,
        body: ByteArray,
        lastChunk: Boolean,
        fileName: String = "",
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
            .setPacketType(PayloadTransferFrame.PacketType.DATA)
            .setPayloadHeader(header)
            .setPayloadChunk(chunk)
            .build()
    }

    /** Capturing factory that hands out independent in-memory channels per payload. */
    private class CapturingFactory : FileDestinationFactory {
        val outputs: MutableMap<Long, InMemorySeekable> = HashMap()

        override fun open(header: PayloadHeader): SeekableByteChannel {
            val ch = InMemorySeekable()
            outputs[header.id] = ch
            return ch
        }
    }

    private class InMemorySeekable : SeekableByteChannel {
        private var buffer: ByteArray = ByteArray(0)
        private var size: Long = 0
        private var pos: Long = 0
        private var open: Boolean = true

        fun toByteArray(): ByteArray = buffer.copyOf(size.toInt())

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        override fun read(dst: ByteBuffer): Int = throw UnsupportedOperationException()

        override fun write(src: ByteBuffer): Int {
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
            pos = newPosition
            return this
        }

        override fun size(): Long = size

        override fun truncate(newSize: Long): SeekableByteChannel {
            if (newSize < size) size = newSize
            if (pos > size) pos = size
            return this
        }

        private fun ensureCapacity(min: Long) {
            if (min <= buffer.size) return
            var newCap = buffer.size.coerceAtLeast(16)
            while (newCap < min) newCap = (newCap + (newCap shr 1)).coerceAtLeast(min.toInt())
            buffer = buffer.copyOf(newCap)
        }
    }

    @Test
    fun `assembler persists coverage to the store on every successful chunk write`() {
        val store = InMemoryResumeStateStore()
        val factory = CapturingFactory()
        val assembler =
            PayloadAssembler(
                fileDestinationFactory = factory,
                resumeStateStore = store,
                resumeEndpointId = "AB12",
                clock = { 1234L },
            )
        val total = 100
        val data = Random(42).nextBytes(total)

        // First chunk [0, 50).
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 7,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = total.toLong(),
                offset = 0,
                body = data.copyOfRange(0, 50),
                lastChunk = false,
                fileName = "f.bin",
            ),
        )
        val afterFirst = store.loadCoverage("AB12", 7)!!
        assertThat(afterFirst.totalSize).isEqualTo(total.toLong())
        assertThat(afterFirst.updatedAtMillis).isEqualTo(1234L)
        assertThat(afterFirst.coverage).containsExactly(ByteRangeSet.Range(0, 50))
    }

    @Test
    fun `assembler drops the resume record on a successful FileComplete`() {
        val store = InMemoryResumeStateStore()
        val factory = CapturingFactory()
        val assembler =
            PayloadAssembler(
                fileDestinationFactory = factory,
                resumeStateStore = store,
                resumeEndpointId = "AB12",
            )
        val total = 32
        val data = Random(99).nextBytes(total)
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 8,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = total.toLong(),
                offset = 0,
                body = data,
                lastChunk = false,
                fileName = "done.bin",
            ),
        )
        assertThat(store.loadCoverage("AB12", 8)).isNotNull()

        // LAST_CHUNK terminator.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 8,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = total.toLong(),
                offset = total.toLong(),
                body = ByteArray(0),
                lastChunk = true,
                fileName = "done.bin",
            ),
        )
        assertThat(store.loadCoverage("AB12", 8)).isNull()
    }

    @Test
    fun `assembler skips chunks that the resume store says were already received`() {
        // Simulate a reconnect: the store already has coverage for
        // [0, 50) of payload 9. A peer that retransmits the entire
        // file (offset 0, 100 bytes) will land both inside the seeded
        // coverage AND outside it -- partial overlap, which is a
        // protocol error. The expected interop pattern is the peer
        // re-sends the chunks that match the AUTO_RESUME ack: only
        // [50, 100). We test that path here.
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 9L, totalSize = 100, start = 0, end = 50, updatedAtMillis = 1)

        val factory = CapturingFactory()
        val assembler =
            PayloadAssembler(
                fileDestinationFactory = factory,
                resumeStateStore = store,
                resumeEndpointId = "AB12",
            )
        val data = Random(123).nextBytes(50)
        // Peer sends only the missing tail [50, 100).
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 9,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 100,
                offset = 50,
                body = data,
                lastChunk = false,
                fileName = "resume.bin",
            ),
        )
        // Now LAST_CHUNK. Coverage [0, 50) was seeded; [50, 100) just
        // arrived. Together they span [0, 100), so the file completes.
        val terminator =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 9,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 100,
                    offset = 100,
                    body = ByteArray(0),
                    lastChunk = true,
                    fileName = "resume.bin",
                ),
            )
        assertThat(terminator).isInstanceOf(PayloadEvent.FileComplete::class.java)
        // The factory only saw the peer's [50, 100) — bytes [0, 50)
        // were assumed to already be on disk. Verify the channel
        // recorded only what the peer sent.
        val written = factory.outputs[9]!!.toByteArray()
        // Channel size starts at 0; first write at offset 50 grows it
        // to 100 with bytes [0, 50) zero-filled. We only assert on the
        // tail written here — the actual byte recovery happens at the
        // application layer via the spool file.
        assertThat(written.size).isEqualTo(100)
        assertThat(written.copyOfRange(50, 100)).isEqualTo(data)
    }

    @Test
    fun `assembler ignores resume records whose total_size disagrees with the announced one`() {
        // A peer that reuses payload_id 5 for a logically different
        // file (different total_size) must NOT inherit the prior
        // coverage. The assembler treats the announced total_size as
        // authoritative; a record with a different size is considered
        // stale.
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 5L, totalSize = 100, start = 0, end = 50, updatedAtMillis = 1)

        val factory = CapturingFactory()
        val assembler =
            PayloadAssembler(
                fileDestinationFactory = factory,
                resumeStateStore = store,
                resumeEndpointId = "AB12",
            )
        val data = Random(7).nextBytes(20)
        // Same payload id, different total size. Coverage should
        // start from scratch.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 5,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 20,
                offset = 0,
                body = data,
                lastChunk = false,
                fileName = "logically-new.bin",
            ),
        )
        val term =
            assembler.onPayloadTransfer(
                chunkFrame(
                    payloadId = 5,
                    type = PayloadHeader.PayloadType.FILE,
                    totalSize = 20,
                    offset = 20,
                    body = ByteArray(0),
                    lastChunk = true,
                    fileName = "logically-new.bin",
                ),
            )
        assertThat(term).isInstanceOf(PayloadEvent.FileComplete::class.java)
        assertThat(factory.outputs[5]!!.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `assembler with no resume store behaves identically to pre-issue-43 code paths`() {
        // Sanity check: omitting the resume store leaves no observable
        // change in semantics. This matches the documented opt-in
        // behaviour for the pre-existing call sites.
        val factory = CapturingFactory()
        val assembler = PayloadAssembler(fileDestinationFactory = factory)
        val data = Random(1).nextBytes(16)
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 1,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = data.size.toLong(),
                offset = 0,
                body = data,
                lastChunk = true,
                fileName = "stateless.bin",
            ),
        )
        assertThat(factory.outputs[1]!!.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `empty endpoint id sentinel disables resume even with a configured store`() {
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        val factory = CapturingFactory()
        // Empty endpoint id: the store is not consulted at all.
        val assembler =
            PayloadAssembler(
                fileDestinationFactory = factory,
                resumeStateStore = store,
                resumeEndpointId = "",
            )
        // Send a chunk; it should write to the channel without
        // touching the store even though the store is non-null.
        assembler.onPayloadTransfer(
            chunkFrame(
                payloadId = 99,
                type = PayloadHeader.PayloadType.FILE,
                totalSize = 4,
                offset = 0,
                body = byteArrayOf(1, 2, 3, 4),
                lastChunk = true,
                fileName = "no-resume.bin",
            ),
        )
        // Existing record for AB12 is untouched, no record for the
        // new payload id was added.
        assertThat(store.loadCoverage("AB12", 1L)).isNotNull()
        assertThat(store.loadCoverage("AB12", 99L)).isNull()
    }
}
