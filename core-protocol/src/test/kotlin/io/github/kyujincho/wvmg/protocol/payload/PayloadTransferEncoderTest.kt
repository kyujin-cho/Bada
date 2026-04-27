/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import kotlin.random.Random

/**
 * Tests for [PayloadTransferEncoder].
 *
 * The encoder is purely functional, so the tests inspect the produced
 * `OfflineFrame`s directly and assert on the proto fields. The
 * round-trip integration with [PayloadAssembler] lives in
 * [PayloadAssemblerTest].
 */
class PayloadTransferEncoderTest {
    @Test
    fun `encodeBytesPayload emits exactly two frames in the Android quirk shape`() {
        val data = "abcde".toByteArray()
        val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId = 1, data = data)
        assertThat(frames).hasSize(2)

        // Both frames are wrapped as OfflineFrame{V1, PAYLOAD_TRANSFER}.
        for (f in frames) {
            assertThat(f.version).isEqualTo(OfflineFrame.Version.V1)
            assertThat(f.v1.type).isEqualTo(V1Frame.FrameType.PAYLOAD_TRANSFER)
            val transfer = f.v1.payloadTransfer
            assertThat(transfer.packetType).isEqualTo(PayloadTransferFrame.PacketType.DATA)
            assertThat(transfer.payloadHeader.id).isEqualTo(1L)
            assertThat(transfer.payloadHeader.type).isEqualTo(PayloadHeader.PayloadType.BYTES)
            assertThat(transfer.payloadHeader.totalSize).isEqualTo(data.size.toLong())
        }

        // First frame: full body, no LAST_CHUNK.
        val data1 = frames[0].v1.payloadTransfer.payloadChunk
        assertThat(data1.offset).isEqualTo(0L)
        assertThat(data1.flags).isEqualTo(0)
        assertThat(data1.body.toByteArray()).isEqualTo(data)

        // Second frame: empty body, LAST_CHUNK set, offset = data.size.
        val term = frames[1].v1.payloadTransfer.payloadChunk
        assertThat(term.offset).isEqualTo(data.size.toLong())
        assertThat(term.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(term.body.size()).isEqualTo(0)
    }

    @Test
    fun `encodeBytesPayload with empty data still emits a terminator frame`() {
        // The Android quirk applies to zero-length BYTES payloads too.
        val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId = 2, data = ByteArray(0))
        assertThat(frames).hasSize(2)
        val dataChunk = frames[0].v1.payloadTransfer.payloadChunk
        val terminatorChunk = frames[1].v1.payloadTransfer.payloadChunk
        // First chunk: empty body, flags=0.
        assertThat(dataChunk.flags).isEqualTo(0)
        assertThat(dataChunk.body.size()).isEqualTo(0)
        // Terminator: empty body, flags=LAST_CHUNK, offset=0.
        assertThat(terminatorChunk.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(terminatorChunk.offset).isEqualTo(0L)
        assertThat(terminatorChunk.body.size()).isEqualTo(0)
    }

    @Test
    fun `encodeFilePayload chunks a small file into frames with the right offsets`() {
        val data = Random(0xC0DE).nextBytes(2500)
        val source = Channels.newChannel(ByteArrayInputStream(data))
        val frames =
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 11,
                    fileName = "small.bin",
                    totalSize = data.size.toLong(),
                    source = source,
                    chunkSize = 1024,
                ).toList()
        // 2500 / 1024 = 2 full + 452 trailing = 3 frames.
        assertThat(frames).hasSize(3)

        // First two frames: 1024 bytes each, no LAST_CHUNK.
        for ((i, f) in frames.withIndex().take(2)) {
            val chunk = f.v1.payloadTransfer.payloadChunk
            assertThat(chunk.offset).isEqualTo((i * 1024).toLong())
            assertThat(chunk.flags).isEqualTo(0)
            assertThat(chunk.body.size()).isEqualTo(1024)
        }
        // Last frame: trailing bytes + LAST_CHUNK.
        val last = frames[2].v1.payloadTransfer.payloadChunk
        assertThat(last.offset).isEqualTo(2048L)
        assertThat(last.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(last.body.size()).isEqualTo(2500 - 2048)

        // Reassemble bytes and confirm bit-equal.
        val rebuilt = ByteArray(data.size)
        var pos = 0
        for (f in frames) {
            val chunk = f.v1.payloadTransfer.payloadChunk
            val body = chunk.body.toByteArray()
            System.arraycopy(body, 0, rebuilt, pos, body.size)
            pos += body.size
        }
        assertThat(rebuilt).isEqualTo(data)
    }

    @Test
    fun `encodeFilePayload with totalSize zero still produces a terminator frame`() {
        val source = Channels.newChannel(ByteArrayInputStream(ByteArray(0)))
        val frames =
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 12,
                    fileName = "empty.bin",
                    totalSize = 0,
                    source = source,
                ).toList()
        assertThat(frames).hasSize(1)
        val chunk = frames[0].v1.payloadTransfer.payloadChunk
        assertThat(chunk.offset).isEqualTo(0L)
        assertThat(chunk.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(chunk.body.size()).isEqualTo(0)
    }

    @Test
    fun `encodeFilePayload rejects non-positive chunk size`() {
        val source = Channels.newChannel(ByteArrayInputStream(ByteArray(0)))
        assertThrows<IllegalArgumentException> {
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 1,
                    fileName = "x",
                    totalSize = 0,
                    source = source,
                    chunkSize = 0,
                ).toList()
        }
    }

    @Test
    fun `encodeFilePayload rejects negative total size`() {
        val source = Channels.newChannel(ByteArrayInputStream(ByteArray(0)))
        assertThrows<IllegalArgumentException> {
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 1,
                    fileName = "x",
                    totalSize = -1,
                    source = source,
                ).toList()
        }
    }

    @Test
    fun `encodeFilePayload propagates fileName parentFolder and timestamp`() {
        val source = Channels.newChannel(ByteArrayInputStream(ByteArray(10)))
        val frames =
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 99,
                    fileName = "report.pdf",
                    totalSize = 10,
                    source = source,
                    parentFolder = "Documents",
                    lastModifiedTimestampMillis = 1_700_000_000_000L,
                ).toList()
        val header = frames[0].v1.payloadTransfer.payloadHeader
        assertThat(header.fileName).isEqualTo("report.pdf")
        assertThat(header.parentFolder).isEqualTo("Documents")
        assertThat(header.lastModifiedTimestampMillis).isEqualTo(1_700_000_000_000L)
        assertThat(header.type).isEqualTo(PayloadHeader.PayloadType.FILE)
    }
}
