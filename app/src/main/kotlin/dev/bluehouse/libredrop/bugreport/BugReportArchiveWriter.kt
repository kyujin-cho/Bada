/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BugReportArchiveEntry(
    val path: String,
    val bytes: ByteArray,
)

internal object BugReportArchiveWriter {
    fun write(
        destination: File,
        entries: List<BugReportArchiveEntry>,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
    }
}
