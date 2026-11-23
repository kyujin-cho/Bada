/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test

/**
 * Pins the narrow trigger for [withAnnouncedFileName] (#325): only a FILE
 * header with an empty `file_name` and an announced name is rewritten.
 * Every other frame must come back as the same instance so existing
 * senders keep the old receive path.
 */
class AnnouncedFileNameTest {
    private val payloadId = 0x1234L
    private val announced = mapOf(payloadId to "photo.png")

    private fun frame(
        type: PayloadHeader.PayloadType = PayloadHeader.PayloadType.FILE,
        fileName: String? = "",
        id: Long = payloadId,
    ): PayloadTransferFrame {
        val header =
            PayloadHeader
                .newBuilder()
                .setId(id)
                .setType(type)
                .setTotalSize(TOTAL_SIZE)
                .setParentFolder("")
        if (fileName != null) header.setFileName(fileName)
        return PayloadTransferFrame
            .newBuilder()
            .setPacketType(PayloadTransferFrame.PacketType.DATA)
            .setPayloadHeader(header)
            .setPayloadChunk(
                PayloadChunk
                    .newBuilder()
                    .setOffset(CHUNK_OFFSET)
                    .setFlags(0)
                    .setBody(ByteString.copyFromUtf8("chunk")),
            ).build()
    }

    @Test
    fun `empty FILE header name takes the announced name and keeps every other field`() {
        val original = frame(fileName = "")

        val named = original.withAnnouncedFileName(announced)

        assertThat(named.payloadHeader.fileName).isEqualTo("photo.png")
        assertThat(named.payloadHeader.id).isEqualTo(payloadId)
        assertThat(named.payloadHeader.type).isEqualTo(PayloadHeader.PayloadType.FILE)
        assertThat(named.payloadHeader.totalSize).isEqualTo(TOTAL_SIZE)
        assertThat(named.payloadChunk).isEqualTo(original.payloadChunk)
        assertThat(named.packetType).isEqualTo(original.packetType)
    }

    @Test
    fun `missing FILE header name takes the announced name`() {
        val named = frame(fileName = null).withAnnouncedFileName(announced)

        assertThat(named.payloadHeader.fileName).isEqualTo("photo.png")
    }

    @Test
    fun `non-empty header name is kept even when the introduction announced a different name`() {
        val original = frame(fileName = "IMG_0001.jpg")

        assertThat(original.withAnnouncedFileName(announced)).isSameInstanceAs(original)
    }

    @Test
    fun `empty header name without an announced entry is left alone`() {
        val original = frame(fileName = "", id = 0x9999L)

        assertThat(original.withAnnouncedFileName(announced)).isSameInstanceAs(original)
    }

    @Test
    fun `empty announced name does not replace an empty header name`() {
        val original = frame(fileName = "")

        assertThat(original.withAnnouncedFileName(mapOf(payloadId to ""))).isSameInstanceAs(original)
    }

    @Test
    fun `BYTES payload is never renamed`() {
        val original = frame(type = PayloadHeader.PayloadType.BYTES, fileName = "")

        assertThat(original.withAnnouncedFileName(announced)).isSameInstanceAs(original)
    }

    @Test
    fun `frame without a payload header is left alone`() {
        val original =
            PayloadTransferFrame
                .newBuilder()
                .setPacketType(PayloadTransferFrame.PacketType.CONTROL)
                .build()

        assertThat(original.withAnnouncedFileName(announced)).isSameInstanceAs(original)
    }

    private companion object {
        const val TOTAL_SIZE: Long = 4096L
        const val CHUNK_OFFSET: Long = 1024L
    }
}
