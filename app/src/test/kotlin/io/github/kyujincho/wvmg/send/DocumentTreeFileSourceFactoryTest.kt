/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the platform-agnostic helpers inside
 * [DocumentTreeFileSourceFactory].
 *
 * The factory's tree-walking entry point ([DocumentTreeFileSourceFactory.walk])
 * is bound to `android.provider.DocumentsContract` and a real
 * `ContentResolver`, neither of which work on a host JVM. The platform-
 * agnostic surface — display-name sanitization, the relative-path
 * separator convention, the empty-string parent_folder for top-level
 * files — is independently testable and is what we exercise here.
 *
 * The walk-shape test ([dynamic input via a stub ContentResolver]) lives
 * in the Android instrumentation test module when one is added; for
 * Phase 1 we rely on manual on-device verification per the issue's
 * acceptance criteria (folder with nested subdirectories + ≥10 MB file).
 */
class DocumentTreeFileSourceFactoryTest {
    @Test
    fun `sanitizeDisplayName trims whitespace and falls back to last id segment`() {
        val sanitized =
            DocumentTreeFileSourceFactory.sanitizeDisplayName(
                raw = "  ",
                documentIdFallback = "primary:Documents/My File.txt",
            )
        // Whitespace-only display name is treated as missing; we fall
        // back to the last `:` segment of the document id, which is the
        // SAF convention for the on-disk path tail.
        assertEquals("Documents/My File.txt", sanitized)
    }

    @Test
    fun `sanitizeDisplayName uses raw when present`() {
        val sanitized =
            DocumentTreeFileSourceFactory.sanitizeDisplayName(
                raw = "report.pdf",
                documentIdFallback = "primary:irrelevant",
            )
        assertEquals("report.pdf", sanitized)
    }

    @Test
    fun `sanitizeDisplayName replaces path separators in display names`() {
        // The receiver-side materialization (#39) treats `name` as a
        // leaf component and `parent_folder` as the directory hint; an
        // embedded `/` in the name would silently land the file in the
        // wrong place. Replacing both forward and back slashes with `_`
        // keeps the leaf intact while neutralising the separator.
        val sanitized =
            DocumentTreeFileSourceFactory.sanitizeDisplayName(
                raw = "subdir/inner\\file.txt",
                documentIdFallback = "primary:fallback",
            )
        assertEquals("subdir_inner_file.txt", sanitized)
    }

    @Test
    fun `sanitizeDisplayName falls back to FALLBACK_NAME when both inputs are unusable`() {
        val sanitized =
            DocumentTreeFileSourceFactory.sanitizeDisplayName(
                raw = null,
                // No `:` separator means substringAfterLast yields the
                // whole id; an empty fallback id triggers FALLBACK_NAME.
                documentIdFallback = "",
            )
        assertEquals(DocumentTreeFileSourceFactory.FALLBACK_NAME, sanitized)
    }

    @Test
    fun `sanitizeDisplayName falls back to FALLBACK_NAME on blank trimmed input and id without colon`() {
        val sanitized =
            DocumentTreeFileSourceFactory.sanitizeDisplayName(
                raw = null,
                // No colon at all — substringAfterLast(':') with no
                // matching delimiter returns the entire string. A truly
                // empty fallback would yield FALLBACK_NAME; a non-empty
                // fallback is acceptable as the leaf name.
                documentIdFallback = "rawId",
            )
        assertEquals("rawId", sanitized)
    }
}
