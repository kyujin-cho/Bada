/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DiagnosticLogBufferTest {
    @Test
    fun `record keeps the newest lines within capacity`() {
        val buffer = RecentLogRingBuffer(maxEntries = 2)

        buffer.record(timestampMillis = 1L, level = 'I', tag = "one", message = "first")
        buffer.record(timestampMillis = 2L, level = 'W', tag = "two", message = "second")
        buffer.record(timestampMillis = 3L, level = 'E', tag = "three", message = "third")

        assertThat(buffer.snapshotSince(cutoffMillis = 0L))
            .containsExactly(
                BufferedLogLine(timestampMillis = 2L, level = 'W', tag = "two", message = "second"),
                BufferedLogLine(timestampMillis = 3L, level = 'E', tag = "three", message = "third"),
            ).inOrder()
    }

    @Test
    fun `snapshotSince filters out older lines`() {
        val buffer = RecentLogRingBuffer(maxEntries = 4)

        buffer.record(timestampMillis = 100L, level = 'I', tag = "old", message = "older")
        buffer.record(timestampMillis = 200L, level = 'I', tag = "new", message = "newer")

        assertThat(buffer.snapshotSince(cutoffMillis = 150L)).containsExactly(
            BufferedLogLine(timestampMillis = 200L, level = 'I', tag = "new", message = "newer"),
        )
    }
}
