/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Exercises [MediaStoreDownloadsFactory] against a [FakeDownloadsEnvironment].
 *
 * The factory is the public bridge between the
 * [io.github.kyujincho.wvmg.protocol.payload.PayloadAssembler]
 * (`:core-protocol`) and the Android storage layer. These tests verify
 * the `:core-protocol`-facing behavior — that opening a destination
 * yields a writable channel, that commit makes the slot visible, and
 * that abort deletes it — without standing up an `InboundConnection`
 * or a real MediaStore.
 */
class MediaStoreDownloadsFactoryTest {
    // ------------------------------------------------------------------
    // open() — returns a usable WritableByteChannel.
    // ------------------------------------------------------------------

    @Test
    fun `open returns a channel that writes bytes into the environment`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 100, name = "doc.pdf", totalSize = 4)

        val channel = factory.open(header)
        channel.write(ByteBuffer.wrap(byteArrayOf(0x10, 0x20, 0x30, 0x40)))
        // The assembler is the one that closes the channel — emulate
        // that here so we cover the close-then-commit ordering used
        // by production code.
        channel.close()
        factory.commit(header.id)

        val slot = env.slots["doc.pdf"]!!
        assertThat(slot.buffer.toByteArray()).isEqualTo(byteArrayOf(0x10, 0x20, 0x30, 0x40))
        assertThat(slot.committed).isTrue()
        assertThat(slot.pending).isFalse()
    }

    @Test
    fun `open sanitizes the peer-supplied filename`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 1, name = "../../etc/passwd", totalSize = 0)

        factory.open(header).close()

        // No directory separators leak into the slot map keys: the
        // sanitized name lives directly under the (fake) Downloads dir.
        for (key in env.slots.keys) {
            assertThat(key).doesNotContain("/")
            assertThat(key).doesNotContain("\\")
        }
    }

    @Test
    fun `open with empty filename uses the fallback name`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 99, name = "", totalSize = 0)

        factory.open(header).close()

        // FilenameSanitizer.DEFAULT_FALLBACK is the agreed-on
        // placeholder for empty / fully-stripped names.
        assertThat(env.slots).containsKey(FilenameSanitizer.DEFAULT_FALLBACK)
    }

    // ------------------------------------------------------------------
    // commit / abort lifecycle.
    // ------------------------------------------------------------------

    @Test
    fun `commit forwards the senders last_modified timestamp to the environment`() {
        // Issue #41: PayloadHeader.last_modified_timestamp_millis must
        // round-trip from the assembler's open() through the factory's
        // commit() down to the underlying environment so the
        // MediaStore row (or legacy file) carries the original mtime.
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val ts = 1_700_000_000_000L
        val header =
            PayloadHeader
                .newBuilder()
                .setId(123L)
                .setType(PayloadHeader.PayloadType.FILE)
                .setFileName("dated.bin")
                .setTotalSize(0)
                .setLastModifiedTimestampMillis(ts)
                .build()

        factory.open(header).close()
        assertThat(factory.commit(header.id)).isTrue()

        val slot = env.slots["dated.bin"]!!
        assertThat(slot.committed).isTrue()
        assertThat(slot.committedLastModifiedTimestampMillis).isEqualTo(ts)
    }

    @Test
    fun `commit forwards zero when the sender omitted the timestamp`() {
        // A peer that left last_modified_timestamp_millis at the proto
        // default (0) must NOT cause us to overwrite the platform's
        // default mtime — the environment receives 0L and ignores it.
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 200L, name = "no-ts.bin", totalSize = 0)

        factory.open(header).close()
        assertThat(factory.commit(header.id)).isTrue()

        val slot = env.slots["no-ts.bin"]!!
        assertThat(slot.committedLastModifiedTimestampMillis).isEqualTo(0L)
    }

    @Test
    fun `commit returns true once and false on second call`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 7, name = "first.bin", totalSize = 0)

        factory.open(header).close()
        assertThat(factory.commit(header.id)).isTrue()
        assertThat(factory.commit(header.id)).isFalse()
    }

    @Test
    fun `abort deletes the destination and removes it from in-flight`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 8, name = "to-cancel.bin", totalSize = 0)

        factory.open(header)
        assertThat(factory.inFlightCount).isEqualTo(1)
        assertThat(factory.abort(header.id)).isTrue()

        assertThat(factory.inFlightCount).isEqualTo(0)
        val slot = env.slots["to-cancel.bin"]!!
        assertThat(slot.discarded).isTrue()
        assertThat(slot.committed).isFalse()
    }

    @Test
    fun `abort on unknown payloadId is a no-op`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)

        assertThat(factory.abort(payloadId = 999_999L)).isFalse()
        assertThat(env.slots).isEmpty()
    }

    @Test
    fun `abortAll discards every in-flight destination`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)

        factory.open(fileHeader(id = 1, name = "a.bin", totalSize = 0))
        factory.open(fileHeader(id = 2, name = "b.bin", totalSize = 0))
        factory.open(fileHeader(id = 3, name = "c.bin", totalSize = 0))

        assertThat(factory.inFlightCount).isEqualTo(3)
        assertThat(factory.abortAll()).isEqualTo(3)
        assertThat(factory.inFlightCount).isEqualTo(0)
        // Every slot is now in `discarded` state.
        assertThat(env.slots.values.all { it.discarded }).isTrue()
    }

    @Test
    fun `commit after abort returns false because handle was already disposed`() {
        val env = FakeDownloadsEnvironment()
        val factory = DownloadsWriterFactory.fromEnvironment(env)
        val header = fileHeader(id = 42, name = "aborted-then-committed.bin", totalSize = 0)

        factory.open(header)
        factory.abort(header.id)
        assertThat(factory.commit(header.id)).isFalse()

        val slot = env.slots["aborted-then-committed.bin"]!!
        assertThat(slot.discarded).isTrue()
        assertThat(slot.committed).isFalse()
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * Build a minimal `PayloadHeader` for a FILE payload. Tests only
     * need `id`, `file_name`, `total_size`, and `type`; the rest of
     * the proto's fields are irrelevant to the factory.
     */
    private fun fileHeader(
        id: Long,
        name: String,
        totalSize: Long,
    ): PayloadHeader =
        PayloadHeader
            .newBuilder()
            .setId(id)
            .setType(PayloadHeader.PayloadType.FILE)
            .setFileName(name)
            .setTotalSize(totalSize)
            .build()
}
