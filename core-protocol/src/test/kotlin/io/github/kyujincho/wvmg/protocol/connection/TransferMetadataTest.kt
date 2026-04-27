/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.sharing.IntroductionFrame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [TransferMetadata.fromIntroductionFrame].
 *
 * These tests guard the proto-to-DTO mapping; the higher-level
 * end-to-end loopback test in [InboundConnectionTest] does not look
 * at the metadata field-by-field, so it's important to pin the
 * mapping here.
 */
class TransferMetadataTest {
    @Test
    fun `maps a single FileMetadata into a TransferItem File`() {
        val frame =
            IntroductionFrame
                .newBuilder()
                .addFileMetadata(
                    Protocol.FileMetadata
                        .newBuilder()
                        .setName("Cookbook.pdf")
                        .setPayloadId(7L)
                        .setSize(2048L)
                        .setMimeType("application/pdf")
                        .build(),
                ).build()

        val md = TransferMetadata.fromIntroductionFrame(frame, pin = "1234")

        assertThat(md.items).hasSize(1)
        val item = md.items.single() as TransferItem.File
        assertThat(item.payloadId).isEqualTo(7L)
        assertThat(item.name).isEqualTo("Cookbook.pdf")
        assertThat(item.size).isEqualTo(2048L)
        assertThat(item.mimeType).isEqualTo("application/pdf")
        assertThat(md.pin).isEqualTo("1234")
        assertThat(md.totalSize).isEqualTo(2048L)
    }

    @Test
    fun `maps TextMetadata kinds onto TransferItem Text Kind enum exhaustively`() {
        val frame =
            IntroductionFrame
                .newBuilder()
                .addTextMetadata(
                    Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("https example")
                        .setPayloadId(11L)
                        .setSize(12L)
                        .setType(Protocol.TextMetadata.Type.URL)
                        .build(),
                ).addTextMetadata(
                    Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("address")
                        .setPayloadId(12L)
                        .setSize(20L)
                        .setType(Protocol.TextMetadata.Type.ADDRESS)
                        .build(),
                ).addTextMetadata(
                    Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("phone")
                        .setPayloadId(13L)
                        .setSize(8L)
                        .setType(Protocol.TextMetadata.Type.PHONE_NUMBER)
                        .build(),
                ).addTextMetadata(
                    Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("plain")
                        .setPayloadId(14L)
                        .setSize(5L)
                        .setType(Protocol.TextMetadata.Type.TEXT)
                        .build(),
                ).build()

        val md = TransferMetadata.fromIntroductionFrame(frame, pin = "0000")

        assertThat(md.items.map { (it as TransferItem.Text).kind })
            .containsExactly(
                TransferItem.Text.Kind.URL,
                TransferItem.Text.Kind.ADDRESS,
                TransferItem.Text.Kind.PHONE_NUMBER,
                TransferItem.Text.Kind.PLAIN,
            ).inOrder()
        assertThat(md.totalSize).isEqualTo(12L + 20L + 8L + 5L)
    }

    @Test
    fun `mixed file and text manifests preserve announcement order`() {
        val frame =
            IntroductionFrame
                .newBuilder()
                .addFileMetadata(
                    Protocol.FileMetadata
                        .newBuilder()
                        .setName("a.bin")
                        .setPayloadId(1L)
                        .setSize(100L)
                        .build(),
                ).addTextMetadata(
                    Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("note")
                        .setPayloadId(2L)
                        .setSize(7L)
                        .build(),
                ).build()

        val md = TransferMetadata.fromIntroductionFrame(frame, pin = "9999")

        // Files come first, then texts (the mapping preserves the
        // proto's repeated-field order within each kind).
        assertThat(md.items[0]).isInstanceOf(TransferItem.File::class.java)
        assertThat(md.items[1]).isInstanceOf(TransferItem.Text::class.java)
    }

    @Test
    fun `rejects pin of incorrect length`() {
        assertThrows<IllegalArgumentException> {
            TransferMetadata(items = emptyList(), pin = "12")
        }
        assertThrows<IllegalArgumentException> {
            TransferMetadata(items = emptyList(), pin = "12345")
        }
    }
}
