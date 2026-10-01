/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.protocol.connection.FileSource
import dev.bluehouse.bada.protocol.connection.TextSource
import dev.bluehouse.bada.protocol.connection.TransferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.channels.Channels

/** Pure-JVM tests for the context-free parts of [PayloadSummary]. */
class PayloadSummaryTest {
    private fun file(size: Long): FileSource =
        FileSource(
            name = "a.bin",
            size = size,
            mimeType = "application/octet-stream",
            lastModifiedTimestampMillis = 0L,
            payloadId = 1L,
            open = { Channels.newChannel(ByteArrayInputStream(ByteArray(0))) },
        )

    private fun text(title: String): TextSource =
        TextSource("https://youtu.be/x", title, TransferItem.Text.Kind.URL, 2L)

    @Test
    fun `detail line shows the text title for a text-only share`() {
        assertEquals("youtu.be", PayloadSummary.detailFor(files = emptyList(), texts = listOf(text("youtu.be"))))
    }

    @Test
    fun `detail line is hidden for a blank text title`() {
        assertNull(PayloadSummary.detailFor(files = emptyList(), texts = listOf(text(" "))))
    }

    @Test
    fun `detail line shows the total size for a file send`() {
        assertEquals(
            PayloadSummary.formatBytes(2048),
            PayloadSummary.detailFor(files = listOf(file(2048)), texts = emptyList()),
        )
    }

    @Test
    fun `detail line is hidden for a file send of unknown size`() {
        assertNull(PayloadSummary.detailFor(files = listOf(file(0)), texts = emptyList()))
    }
}
