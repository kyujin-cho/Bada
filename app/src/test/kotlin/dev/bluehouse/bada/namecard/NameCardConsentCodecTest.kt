/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [NameCardConsentCodec]: round-trip every opcode, every malformed case decodes
 * to null — including the token-less legacy HELLO, which MUST stay malformed (no unauthenticated
 * fallback) — and trailing bytes beyond a message are tolerated (forward-compat).
 */
class NameCardConsentCodecTest {
    private val token = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `hello round-trips with version and token`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.Hello(0x02.toByte(), token))
        assertArrayEquals(byteArrayOf(0x01, 0x02) + token, bytes)
        assertEquals(ConsentMessage.Hello(0x02.toByte(), token), NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `helloBytes uses this build's protocol version and carries the token`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02) + token, NameCardConsentCodec.helloBytes(token))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoding a hello with a wrong-length token throws`() {
        NameCardConsentCodec.encode(ConsentMessage.Hello(0x02.toByte(), ByteArray(8)))
    }

    @Test
    fun `choice share round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.ChoiceShare)
        assertArrayEquals(byteArrayOf(0x02), bytes)
        assertEquals(ConsentMessage.ChoiceShare, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `choice receive-only round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.ChoiceReceiveOnly)
        assertArrayEquals(byteArrayOf(0x03), bytes)
        assertEquals(ConsentMessage.ChoiceReceiveOnly, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `bye round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.Bye)
        assertArrayEquals(byteArrayOf(0x04), bytes)
        assertEquals(ConsentMessage.Bye, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `empty array decodes to null`() {
        assertNull(NameCardConsentCodec.decode(ByteArray(0)))
    }

    @Test
    fun `unknown opcode decodes to null`() {
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x7f)))
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x00)))
    }

    @Test
    fun `hello without a full token decodes to null - no unauthenticated fallback`() {
        // Bare opcode, opcode+version (the legacy pre-token HELLO), and a truncated token: all
        // malformed. A server treats any of these as an unauthenticated peer and disconnects.
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x01)))
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x01, 0x01)))
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x01, 0x02) + ByteArray(15)))
    }

    @Test
    fun `trailing bytes beyond a choice are tolerated`() {
        // A future build could append fields; we read only the known prefix.
        assertEquals(ConsentMessage.ChoiceShare, NameCardConsentCodec.decode(byteArrayOf(0x02, 0x55, 0x66)))
        assertEquals(ConsentMessage.Bye, NameCardConsentCodec.decode(byteArrayOf(0x04, 0x01)))
    }

    @Test
    fun `trailing bytes beyond a hello keep the version and token`() {
        val wire = byteArrayOf(0x01, 0x02) + token + byteArrayOf(0x99.toByte())
        assertEquals(ConsentMessage.Hello(0x02.toByte(), token), NameCardConsentCodec.decode(wire))
    }
}
