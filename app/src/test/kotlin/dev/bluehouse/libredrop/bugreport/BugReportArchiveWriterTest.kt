/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class BugReportArchiveWriterTest {
    @Test
    fun write_createsStableEntryLayout() {
        val tempFile = File.createTempFile("bugreport-writer-test", ".zip")

        BugReportArchiveWriter.write(
            destination = tempFile,
            entries =
                listOf(
                    BugReportArchiveEntry("README.txt", "hello".encodeToByteArray()),
                    BugReportArchiveEntry("metadata.json", "{}".encodeToByteArray()),
                    BugReportArchiveEntry("logs/ringbuffer.txt", "lines".encodeToByteArray()),
                ),
        )

        ZipFile(tempFile).use { zip ->
            assertEquals(
                listOf("README.txt", "metadata.json", "logs/ringbuffer.txt"),
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList(),
            )
            assertEquals(
                "hello",
                zip.getInputStream(zip.getEntry("README.txt")).bufferedReader().readText(),
            )
        }
    }
}
