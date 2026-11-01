/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.gestureexchange

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class GestureNoiseSessionTest {
    @Test
    fun `handshake derives matching binding and bidirectional ciphers`() {
        val (initiator, responder) = connectedSessions()

        assertThat(initiator.channelBindingToken()).isEqualTo(responder.channelBindingToken())
        assertThat(responder.decrypt(initiator.encrypt("request".toByteArray())))
            .isEqualTo("request".toByteArray())
        assertThat(initiator.decrypt(responder.encrypt("response".toByteArray())))
            .isEqualTo("response".toByteArray())
    }

    @Test
    fun `corruption and replay fail without advancing the receive counter`() {
        val (initiator, responder) = connectedSessions()
        val first = initiator.encrypt("first".toByteArray())
        val second = initiator.encrypt("second".toByteArray())
        val corrupted = first.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertThrows<AEADBadTagException> { responder.decrypt(corrupted) }
        assertThat(responder.decrypt(first)).isEqualTo("first".toByteArray())
        assertThrows<AEADBadTagException> { responder.decrypt(first) }
        assertThat(responder.decrypt(second)).isEqualTo("second".toByteArray())
    }

    @Test
    fun `maximum plaintext round trips and larger message is rejected`() {
        val (initiator, responder) = connectedSessions()
        val maximum = ByteArray(64 * 1024) { (it and 0xff).toByte() }

        assertThat(responder.decrypt(initiator.encrypt(maximum))).isEqualTo(maximum)
        assertThrows<IllegalArgumentException> { initiator.encrypt(ByteArray(maximum.size + 1)) }
    }

    @Test
    fun `nonce exhaustion is rejected before cipher use`() {
        val (initiator, _) = connectedSessions()
        val senderField = GestureNoiseSession::class.java.getDeclaredField("senderCipher").apply { isAccessible = true }
        val sender = checkNotNull(senderField.get(initiator))
        sender.javaClass.getDeclaredField("counter").apply {
            isAccessible = true
            setLong(sender, Long.MAX_VALUE)
        }

        assertThrows<IllegalStateException> { initiator.encrypt(byteArrayOf(1)) }
    }

    private fun connectedSessions(): Pair<GestureNoiseSession, GestureNoiseSession> {
        val initiator = GestureNoiseSession(GestureNoiseSession.Role.INITIATOR, seededRandom("initiator"))
        val responder = GestureNoiseSession(GestureNoiseSession.Role.RESPONDER, seededRandom("responder"))
        val initiatorKey = initiator.createInitiatorKey()
        val responderKey = responder.answerInitiatorKey(initiatorKey)
        initiator.acceptResponderKey(responderKey)
        return initiator to responder
    }

    private fun seededRandom(label: String): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply { setSeed(label.toByteArray()) }
}
