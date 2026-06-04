/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DiagnosticFileSinkTest {
    private val sinks = mutableListOf<DiagnosticFileSink>()

    private fun sink(
        file: File,
        maxBytes: Long,
    ): DiagnosticFileSink = DiagnosticFileSink(file, maxBytes).also { sinks += it }

    @AfterEach
    fun tearDown() {
        sinks.forEach { it.shutdown() }
        // The sink is also configured on the DiagnosticLog singleton in one
        // test; reset it so no writer thread or path leaks across tests.
        DiagnosticLog.clearForTesting()
    }

    @Test
    fun `append writes lines to the configured file`(
        @TempDir dir: File,
    ) {
        val sink = sink(File(dir, "diag.log"), maxBytes = 1_024)

        sink.append("first")
        sink.append("second")
        sink.flush(FLUSH_TIMEOUT_MILLIS)

        assertThat(File(dir, "diag.log").readText()).isEqualTo("first\nsecond\n")
    }

    @Test
    fun `oversized file rotates to a single backup and starts fresh`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "diag.log")
        val backup = File(dir, "diag.log.1")
        // Cap smaller than any single line, so every append after the first
        // finds the file already over the cap and rotates it.
        val sink = sink(file, maxBytes = 4)

        sink.append("aaaaaaaa") // file empty -> no rotation, file now over cap
        sink.append("bbbbbbbb") // file over cap -> rotate to .1, fresh file gets this
        sink.flush(FLUSH_TIMEOUT_MILLIS)

        assertThat(backup.readText()).isEqualTo("aaaaaaaa\n")
        assertThat(file.readText()).isEqualTo("bbbbbbbb\n")

        // A second rotation must replace (not append to) the existing backup.
        sink.append("cccccccc")
        sink.flush(FLUSH_TIMEOUT_MILLIS)

        assertThat(backup.readText()).isEqualTo("bbbbbbbb\n")
        assertThat(file.readText()).isEqualTo("cccccccc\n")
    }

    @Test
    fun `configureFileSink routes emitted log lines to disk`(
        @TempDir dir: File,
    ) {
        DiagnosticLog.configureFileSink(dir, maxBytes = 64 * 1_024)

        DiagnosticLog.w("BadaBleL2cap", "L2CAP connect failed psm=135: IOException: connect failed")
        DiagnosticLog.flushFileSink(FLUSH_TIMEOUT_MILLIS)

        val written = File(dir, DiagnosticLog.FILE_NAME).readText()
        assertThat(written).contains("W/BadaBleL2cap: L2CAP connect failed psm=135: IOException: connect failed")
    }

    private companion object {
        const val FLUSH_TIMEOUT_MILLIS: Long = 2_000L
    }
}
