/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.payload

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import dev.bluehouse.libredrop.protocol.crypto.securemessage.SecureMessageCodec
import dev.bluehouse.libredrop.protocol.transport.FramedConnection
import dev.bluehouse.libredrop.protocol.transport.TransportFrameBudget
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
    private val sizingEncryptKey = ByteArray(SecureMessageCodec.AES_KEY_SIZE)
    private val sizingHmacKey = ByteArray(SecureMessageCodec.HMAC_KEY_SIZE)
    private val sizingIv = ByteArray(SecureMessageCodec.IV_SIZE)

    @Test
    fun `selectFileChunkSize uses deterministic size tiers below the framed transport cap`() {
        assertThat(PayloadTransferEncoder.selectFileChunkSize(0)).isEqualTo(256 * 1024)
        assertThat(PayloadTransferEncoder.selectFileChunkSize(1024L * 1024L)).isEqualTo(256 * 1024)
        assertThat(PayloadTransferEncoder.selectFileChunkSize((1024L * 1024L) + 1L)).isEqualTo(512 * 1024)
        assertThat(PayloadTransferEncoder.selectFileChunkSize(8L * 1024L * 1024L)).isEqualTo(512 * 1024)
        assertThat(PayloadTransferEncoder.selectFileChunkSize((8L * 1024L * 1024L) + 1L)).isEqualTo(1024 * 1024)
        assertThat(PayloadTransferEncoder.selectFileChunkSize(64L * 1024L * 1024L)).isEqualTo(1024 * 1024)

        val largestTier = PayloadTransferEncoder.selectFileChunkSize(Long.MAX_VALUE)
        assertThat(largestTier).isEqualTo(2 * 1024 * 1024)
        assertThat(largestTier).isLessThan(FramedConnection.SANE_FRAME_LENGTH)
    }

    @Test
    fun `selectFileChunkSize rejects negative total size`() {
        assertThrows<IllegalArgumentException> {
            PayloadTransferEncoder.selectFileChunkSize(-1)
        }
    }

    @Test
    fun `limitAdaptiveFileChunkSizeForTransport keeps constrained upgraded links on the safe default chunk size`() {
        val unconstrained = 2 * 1024 * 1024

        assertThat(
            PayloadTransferEncoder.limitAdaptiveFileChunkSizeForTransport(
                desiredChunkSize = unconstrained,
                maxEncryptedFrameLength = FramedConnection.SANE_FRAME_LENGTH - 1,
            ),
        ).isEqualTo(unconstrained)

        assertThat(
            PayloadTransferEncoder.limitAdaptiveFileChunkSizeForTransport(
                desiredChunkSize = unconstrained,
                maxEncryptedFrameLength = TransportFrameBudget.PLAY_SERVICES_UPGRADED_MAX_FRAME_LENGTH,
            ),
        ).isEqualTo(PayloadTransferEncoder.DEFAULT_FILE_CHUNK_SIZE)
    }

    @Test
    fun `capFileChunkSizeToEncryptedFrameBudget stays below observed Galaxy wifi direct rejects`() {
        val totalSize = 341_064_318L
        val desiredChunkSize = PayloadTransferEncoder.selectFileChunkSize(totalSize)
        assertThat(desiredChunkSize).isEqualTo(2 * 1024 * 1024)

        val cappedChunkSize =
            PayloadTransferEncoder.capFileChunkSizeToEncryptedFrameBudget(
                payloadId = 3_481_759_869_812_819_740L,
                fileName = "Sonos_85.00.23.apk",
                totalSize = totalSize,
                desiredChunkSize = desiredChunkSize,
                maxEncryptedFrameLength = TransportFrameBudget.PLAY_SERVICES_UPGRADED_MAX_FRAME_LENGTH,
            )

        assertThat(cappedChunkSize).isLessThan(desiredChunkSize)
        assertThat(cappedChunkSize).isGreaterThan(1024 * 1024)

        val header =
            PayloadHeader
                .newBuilder()
                .setId(3_481_759_869_812_819_740L)
                .setType(PayloadHeader.PayloadType.FILE)
                .setTotalSize(totalSize)
                .setFileName("Sonos_85.00.23.apk")
                .setParentFolder("")
                .setLastModifiedTimestampMillis(0L)
                .setIsSensitive(false)
                .build()
        val receiverBudget = TransportFrameBudget.PLAY_SERVICES_UPGRADED_MAX_FRAME_LENGTH

        val oldFrameLength = encryptedFrameLengthForDataChunk(header, desiredChunkSize)
        val cappedFrameLength = encryptedFrameLengthForDataChunk(header, cappedChunkSize)
        val firstRejectedLiveChunkFrameLength = encryptedFrameLengthForDataChunk(header, 2_096_969)
        val laterRejectedLiveChunkFrameLength = encryptedFrameLengthForDataChunk(header, 2_096_953)

        assertThat(oldFrameLength).isGreaterThan(receiverBudget)
        assertThat(firstRejectedLiveChunkFrameLength).isGreaterThan(receiverBudget)
        assertThat(laterRejectedLiveChunkFrameLength).isGreaterThan(receiverBudget)
        assertThat(cappedFrameLength).isAtMost(receiverBudget)
        assertThat(cappedChunkSize).isLessThan(2_096_921)
        assertThat(cappedFrameLength).isLessThan(laterRejectedLiveChunkFrameLength)
        assertThat(encryptedFrameLengthForDataChunk(header, cappedChunkSize + 1)).isGreaterThan(receiverBudget)
    }

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
    fun `encodeFilePayload chunks a small file into data frames plus a terminator`() {
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
        // 2500 / 1024 = 2 full + 452 trailing data chunks + 1 dedicated
        // zero-byte LAST_CHUNK terminator = 4 frames. Mirrors the BYTES
        // two-frame quirk and is what stock Quick Share on Samsung One
        // UI 7+ requires for FILE payloads (see encoder kdoc).
        assertThat(frames).hasSize(4)

        // First three frames: data chunks, all flags=0.
        val expectedSizes = listOf(1024, 1024, 2500 - 2048)
        var runningOffset = 0L
        for ((i, expectedSize) in expectedSizes.withIndex()) {
            val chunk = frames[i].v1.payloadTransfer.payloadChunk
            assertThat(chunk.offset).isEqualTo(runningOffset)
            assertThat(chunk.flags).isEqualTo(0)
            assertThat(chunk.body.size()).isEqualTo(expectedSize)
            runningOffset += expectedSize
        }
        // Final frame: dedicated empty terminator with LAST_CHUNK.
        val terminator = frames[3].v1.payloadTransfer.payloadChunk
        assertThat(terminator.offset).isEqualTo(2500L)
        assertThat(terminator.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(terminator.body.size()).isEqualTo(0)

        // Reassemble data bytes and confirm bit-equal. (Skip the
        // terminator — its body is empty by construction.)
        val rebuilt = ByteArray(data.size)
        var pos = 0
        for (f in frames.dropLast(1)) {
            val chunk = f.v1.payloadTransfer.payloadChunk
            val body = chunk.body.toByteArray()
            System.arraycopy(body, 0, rebuilt, pos, body.size)
            pos += body.size
        }
        assertThat(rebuilt).isEqualTo(data)
    }

    @Test
    fun `encodeFilePayload with totalSize zero produces only the terminator frame`() {
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
    fun `encodeFilePayload single-chunk small file emits data plus terminator`() {
        // Regression guard for the Samsung One UI 7+ fix: a file whose
        // body fits in a single chunk must still produce a separate
        // zero-byte LAST_CHUNK terminator. The previous implementation
        // emitted a single fused frame, which Samsung's Quick Share
        // silently rejected.
        val data = Random(0xBEEF).nextBytes(89_482)
        val source = Channels.newChannel(ByteArrayInputStream(data))
        val frames =
            PayloadTransferEncoder
                .encodeFilePayload(
                    payloadId = 42,
                    fileName = "photo.jpeg",
                    totalSize = data.size.toLong(),
                    source = source,
                    // Default 512 KiB easily covers 89 KiB in one chunk.
                ).toList()
        assertThat(frames).hasSize(2)

        val data0 = frames[0].v1.payloadTransfer.payloadChunk
        assertThat(data0.offset).isEqualTo(0L)
        assertThat(data0.flags).isEqualTo(0)
        assertThat(data0.body.size()).isEqualTo(data.size)

        val terminator = frames[1].v1.payloadTransfer.payloadChunk
        assertThat(terminator.offset).isEqualTo(data.size.toLong())
        assertThat(terminator.flags).isEqualTo(PayloadAssembler.LAST_CHUNK_FLAG)
        assertThat(terminator.body.size()).isEqualTo(0)
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

    private fun encryptedFrameLengthForDataChunk(
        header: PayloadHeader,
        bodySize: Int,
    ): Int {
        val offset = header.totalSize.coerceAtLeast(bodySize.toLong()) - bodySize.toLong()
        val frame =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.PAYLOAD_TRANSFER)
                        .setPayloadTransfer(
                            PayloadTransferFrame
                                .newBuilder()
                                .setPacketType(PayloadTransferFrame.PacketType.DATA)
                                .setPayloadHeader(header)
                                .setPayloadChunk(
                                    PayloadChunk
                                        .newBuilder()
                                        .setFlags(0)
                                        .setOffset(offset)
                                        .setBody(ByteString.copyFrom(ByteArray(bodySize)))
                                        .build(),
                                ).build(),
                        ).build(),
                ).build()
        val d2dBytes =
            SecureMessageCodec.wrapDeviceToDeviceMessage(
                offlineFrame = frame.toByteArray(),
                sequenceNumber = Int.MAX_VALUE,
            )
        return SecureMessageCodec
            .encryptAndSign(
                payload = d2dBytes,
                encryptKey = sizingEncryptKey,
                hmacKey = sizingHmacKey,
                iv = sizingIv,
            ).size
    }
}
