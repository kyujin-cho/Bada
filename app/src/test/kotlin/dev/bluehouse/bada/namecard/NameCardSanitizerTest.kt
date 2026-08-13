/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.protocol.namecard.NameCardEntry
import dev.bluehouse.bada.protocol.namecard.NameCardEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [NameCardSanitizer] — the untrusted-peer-card gate at the exchange boundary:
 * entry-count cap, control/bidi stripping, and display-length capping.
 */
class NameCardSanitizerTest {
    @Test
    fun `a clean card passes through unchanged`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "+82 10-0000-0000", email = "m@e.com")
        assertEquals(card, NameCardSanitizer.sanitize(card))
    }

    @Test
    fun `a card over the entry cap is rejected`() {
        val entries = List(NameCardSanitizer.MAX_ENTRIES + 1) { NameCardEntry(NameCardEntryKind.NOTE, "n$it") }
        assertNull(NameCardSanitizer.sanitize(NameCard(displayName = "X", entries = entries)))
    }

    @Test
    fun `a card at the entry cap is allowed`() {
        val entries = List(NameCardSanitizer.MAX_ENTRIES) { NameCardEntry(NameCardEntryKind.NOTE, "n$it") }
        val out = NameCardSanitizer.sanitize(NameCard(displayName = "X", entries = entries))
        assertEquals(NameCardSanitizer.MAX_ENTRIES, out!!.entries.size)
    }

    @Test
    fun `bidi override codepoints are stripped from every string field`() {
        val card =
            NameCard(
                displayName = "\u202Egpj.exe\u202C",
                phoneNumber = "\u200F123\u200E",
                email = "a\u2066@\u2069b.com",
            )
        val out = NameCardSanitizer.sanitize(card)!!
        assertEquals("gpj.exe", out.displayName)
        assertEquals("123", out.phoneNumber)
        assertEquals("a@b.com", out.email)
    }

    @Test
    fun `ISO control characters are stripped`() {
        val out = NameCardSanitizer.sanitize(NameCard(displayName = "Mi\u0007ke\n"))!!
        assertEquals("Mike", out.displayName)
    }

    @Test
    fun `overlong fields are capped`() {
        val long = "x".repeat(NameCardSanitizer.MAX_FIELD_CHARS * 2)
        val out = NameCardSanitizer.sanitize(NameCard(displayName = long))!!
        assertEquals(NameCardSanitizer.MAX_FIELD_CHARS, out.displayName!!.length)
    }

    @Test
    fun `entry values are cleaned too`() {
        val card =
            NameCard(
                displayName = "X",
                entries = listOf(NameCardEntry(NameCardEntryKind.COMPANY, "\u202EAcme\u202C")),
            )
        assertEquals("Acme", NameCardSanitizer.sanitize(card)!!.entries[0].value)
    }

    @Test
    fun `an entry that cleans to nothing is dropped`() {
        val card =
            NameCard(
                displayName = "X",
                entries = listOf(NameCardEntry(NameCardEntryKind.NOTE, "\u202E\u202C  ")),
            )
        assertEquals(emptyList<NameCardEntry>(), NameCardSanitizer.sanitize(card)!!.entries)
    }

    @Test
    fun `a card whose every field cleans to nothing is rejected`() {
        assertNull(NameCardSanitizer.sanitize(NameCard(displayName = "\u202E  ")))
    }

    @Test
    fun `cleanText trims and nulls out blank`() {
        assertEquals("hi", NameCardSanitizer.cleanText("  hi  "))
        assertNull(NameCardSanitizer.cleanText("   "))
        assertNull(NameCardSanitizer.cleanText(null))
    }
}
