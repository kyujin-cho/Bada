/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Context
import dev.bluehouse.bada.R
import dev.bluehouse.bada.protocol.connection.FileSource

/**
 * Pure-JVM helpers for rendering "what is being shared" subtitles. Kept
 * separate from the Activity so the formatting can be exercised in
 * isolation if needed.
 */
internal object PayloadSummary {
    /**
     * Build the headline string for a list of file sources — the file
     * count only, e.g. `"1 file"` or `"3 files"`. The transfer size is
     * rendered separately via [sizeFor] on its own line so the count
     * stays the dominant element (and long localized strings no longer
     * collide with the size on a single line).
     */
    fun forFiles(
        context: Context,
        files: List<FileSource>,
    ): String =
        if (files.size == 1) {
            context.getString(R.string.send_payload_single_file)
        } else {
            context.getString(R.string.send_payload_multiple_files, files.size)
        }

    /**
     * The human-readable total transfer size for [files] (e.g.
     * `"4.2 MB"`), or `null` when the total is zero — typically because
     * every source reported an unknown size. Callers hide the size line
     * when this returns `null`.
     */
    fun sizeFor(files: List<FileSource>): String? {
        val totalBytes = files.sumOf { it.size }
        return if (totalBytes > 0) formatBytes(totalBytes) else null
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
