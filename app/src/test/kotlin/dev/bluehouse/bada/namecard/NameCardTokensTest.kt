/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [NameCardTokens] — the verification gate the BLE server applies to a peer's
 * HELLO token, and the advertised-id derivation that keeps the raw token off the air.
 */
class NameCardTokensTest {
    private val token = ByteArray(16) { (it * 3).toByte() }

    @Test
    fun `verify accepts the exact token`() {
        assertTrue(NameCardTokens.verify(token, token.copyOf()))
    }

    @Test
    fun `verify rejects a wrong token`() {
        val wrong = token.copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertFalse(NameCardTokens.verify(token, wrong))
    }

    @Test
    fun `verify rejects null and wrong lengths`() {
        assertFalse(NameCardTokens.verify(null, token))
        assertFalse(NameCardTokens.verify(token, null))
        assertFalse(NameCardTokens.verify(token, ByteArray(0)))
        assertFalse(NameCardTokens.verify(token, token + 0x00))
        assertFalse(NameCardTokens.verify(token.copyOf(8), token.copyOf(8)))
    }

    @Test
    fun `advertisedId is a stable 8-byte derivation, not the token`() {
        val id = NameCardTokens.advertisedId(token)
        assertEquals(NameCardTokens.ADVERTISED_ID_LEN, id.size)
        assertArrayEquals(id, NameCardTokens.advertisedId(token)) // deterministic
        assertFalse(id.contentEquals(token.copyOf(NameCardTokens.ADVERTISED_ID_LEN))) // not a prefix leak
    }

    @Test
    fun `different tokens derive different advertised ids`() {
        val other = token.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(NameCardTokens.advertisedId(token).contentEquals(NameCardTokens.advertisedId(other)))
    }
}
