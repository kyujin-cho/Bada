/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.protocol.connection.TransferItem.Text.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [SharedTextClassifier] (#301): which share-sheet
 * strings travel as a Quick Share URL and what title the receiver sees.
 */
class SharedTextClassifierTest {
    @Test
    fun `YouTube share link is a URL titled by its host`() {
        // YouTube's share sheet hands over the bare short link, often
        // with a trailing newline.
        val result = SharedTextClassifier.classify("https://youtu.be/dQw4w9WgXcQ?si=abc123\n")

        assertEquals(Kind.URL, result.kind)
        assertEquals("youtu.be", result.title)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc123", result.body)
    }

    @Test
    fun `scheme match ignores case and accepts plain http`() {
        assertEquals(Kind.URL, SharedTextClassifier.classify("HTTPS://Example.com/Path").kind)
        assertEquals("www.example.com", SharedTextClassifier.classify("http://www.example.com").title)
    }

    @Test
    fun `host is taken past userinfo and before the port`() {
        val result = SharedTextClassifier.classify("http://user@example.com:8080/a?b=c#d")

        assertEquals(Kind.URL, result.kind)
        assertEquals("example.com", result.title)
    }

    @Test
    fun `bracketed IPv6 host is kept as the title`() {
        val result = SharedTextClassifier.classify("http://[::1]:8080/")

        assertEquals(Kind.URL, result.kind)
        assertEquals("[::1]", result.title)
    }

    @Test
    fun `message with a link inside stays plain text and is sent unchanged`() {
        val shared = "Watch this: https://youtu.be/dQw4w9WgXcQ "
        val result = SharedTextClassifier.classify(shared)

        assertEquals(Kind.PLAIN, result.kind)
        assertEquals(shared, result.body)
    }

    @Test
    fun `non-http schemes and scheme-less hosts stay plain text`() {
        assertEquals(Kind.PLAIN, SharedTextClassifier.classify("ftp://example.com/file").kind)
        assertEquals(Kind.PLAIN, SharedTextClassifier.classify("www.example.com").kind)
        assertEquals(Kind.PLAIN, SharedTextClassifier.classify("https://").kind)
    }

    @Test
    fun `dates and phone-like strings stay plain text`() {
        assertEquals(Kind.PLAIN, SharedTextClassifier.classify("2026-10-01").kind)
        assertEquals(Kind.PLAIN, SharedTextClassifier.classify("+82 10-1234-5678").kind)
    }

    @Test
    fun `plain text title collapses whitespace onto one line`() {
        val result = SharedTextClassifier.classify("  line one\n\nline\ttwo  ")

        assertEquals("line one line two", result.title)
        assertEquals("  line one\n\nline\ttwo  ", result.body)
    }

    @Test
    fun `long plain text title is cut to 32 code points with an ellipsis`() {
        val result = SharedTextClassifier.classify("a".repeat(40))

        assertEquals("a".repeat(32) + "…", result.title)
    }

    @Test
    fun `title cut never splits a surrogate pair`() {
        // 31 ASCII chars, then an emoji (two UTF-16 units) as the 32nd
        // code point, then more text past the limit.
        val emoji = "😀"
        val result = SharedTextClassifier.classify("b".repeat(31) + emoji + "tail")

        assertEquals("b".repeat(31) + emoji + "…", result.title)
    }
}
