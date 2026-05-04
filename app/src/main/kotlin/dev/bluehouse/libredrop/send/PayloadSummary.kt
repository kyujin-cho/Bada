/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

import android.content.Context
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.protocol.connection.FileSource

/**
 * Pure-JVM helpers for rendering "what is being shared" subtitles. Kept
 * separate from the Activity so the formatting can be exercised in
 * isolation if needed.
 */
internal object PayloadSummary {
    /**
     * Build the headline string for a list of file sources, e.g.
     * `"3 files • 12.4 MB"` or `"1 file • 4.2 MB"`. Sizes are
     * formatted via [formatBytes]; if the total size is zero (e.g. all
     * sizes were unknown), the size suffix is omitted.
     */
    fun forFiles(
        context: Context,
        files: List<FileSource>,
    ): String {
        val totalBytes = files.sumOf { it.size }
        val sizeText = if (totalBytes > 0) formatBytes(totalBytes) else null
        return when {
            files.size == 1 && sizeText != null ->
                context.getString(R.string.send_payload_single_file_with_size, sizeText)
            files.size == 1 ->
                context.getString(R.string.send_payload_single_file)
            sizeText != null ->
                context.getString(R.string.send_payload_multiple_files_with_size, files.size, sizeText)
            else ->
                context.getString(R.string.send_payload_multiple_files, files.size)
        }
    }

    /**
     * Format a non-negative byte count as a short human-readable string
     * (`"4.2 MB"`, `"512 KB"`, `"123 B"`).
     *
     * Uses 1024-based units (binary). The Quick Share UIs we model after
     * also use 1024 — Android's own share sheet picks 1000-based for
     * MediaStore but 1024 reads more naturally for "transfer size".
     */
    @Suppress("MagicNumber")
    fun formatBytes(bytes: Long): String =
        when {
            bytes < 0 -> "0 B"
            bytes < KIB -> "$bytes B"
            bytes < MIB -> "%.1f KB".format(bytes.toDouble() / KIB)
            bytes < GIB -> "%.1f MB".format(bytes.toDouble() / MIB)
            else -> "%.2f GB".format(bytes.toDouble() / GIB)
        }

    private const val KIB: Long = 1024L
    private const val MIB: Long = 1024L * 1024L
    private const val GIB: Long = 1024L * 1024L * 1024L
}
