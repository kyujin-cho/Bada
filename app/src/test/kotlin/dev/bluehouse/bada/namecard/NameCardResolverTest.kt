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
 * Pure-JVM tests for [NameCardResolver]: only the explicitly configured in-app card is ever
 * resolved — there is NO device "Me"-contact / SIM fallback (a tap must not disclose the owner's
 * identity unless a card was set up) — and the share-selection filter drops unchecked fields.
 */
class NameCardResolverTest {
    @Test
    fun `the stored card resolves as-is`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        assertEquals(card, NameCardResolver({ card }).resolve())
    }

    @Test
    fun `no stored card resolves to null - never a device-identity fallback`() {
        assertNull(NameCardResolver({ null }).resolve())
    }

    @Test
    fun `blank stored fields are treated as absent`() {
        val card = NameCard(displayName = "  ", phoneNumber = "111")
        val resolved = NameCardResolver({ card }).resolve()!!
        assertNull(resolved.displayName)
        assertEquals("111", resolved.phoneNumber)
    }

    @Test
    fun `share selection drops the unchecked fields`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111", email = "m@e.com")
        val resolver = NameCardResolver({ card }, shareSelection = { setOf("name", "email") })
        val resolved = resolver.resolve()!!
        assertEquals("Mike", resolved.displayName)
        assertNull(resolved.phoneNumber) // "phone" unchecked -> dropped
        assertEquals("m@e.com", resolved.email)
    }

    @Test
    fun `null share selection shares every present field`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, shareSelection = { null })
        assertEquals(card, resolver.resolve())
    }

    @Test
    fun `an empty share selection resolves to null`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, shareSelection = { emptySet() })
        assertNull(resolver.resolve())
    }

    @Test
    fun `stored entries pass through`() {
        val entries = listOf(NameCardEntry(NameCardEntryKind.COMPANY, "Acme"))
        val resolver = NameCardResolver({ NameCard(displayName = "Mike", entries = entries) })
        assertEquals(entries, resolver.resolve()!!.entries)
    }

    @Test
    fun `share selection drops unchecked entries by index key`() {
        val card =
            NameCard(
                displayName = "Mike",
                entries =
                    listOf(
                        NameCardEntry(NameCardEntryKind.COMPANY, "Acme"),
                        NameCardEntry(NameCardEntryKind.WEBSITE, "https://x.dev"),
                    ),
            )
        // Share the name + only entry index 1 (the website); drop entry index 0 (company).
        val resolver = NameCardResolver({ card }, shareSelection = { setOf("name", "e1") })
        assertEquals(listOf(NameCardEntry(NameCardEntryKind.WEBSITE, "https://x.dev")), resolver.resolve()!!.entries)
    }

    @Test
    fun `a card with only an entry resolves (no name or number)`() {
        val card = NameCard(entries = listOf(NameCardEntry(NameCardEntryKind.NOTE, "hi")))
        assertEquals(card, NameCardResolver({ card }).resolve())
    }
}
