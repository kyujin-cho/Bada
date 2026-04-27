/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.sharing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * End-to-end loopback exercising both FSMs together.
 *
 * The point is to confirm the **two FSMs speak the same wire format**:
 * an outbound `SendFrame` bytes-encodes, parses back, and is accepted
 * by the inbound FSM as a legal next event (and vice-versa). The
 * individual transition tests already cover each FSM in isolation;
 * this test catches accidental asymmetries — for example, if the
 * sender ever started emitting a frame type the receiver does not
 * accept in that state, the loopback would diverge.
 *
 * The test simulates the wire by:
 *  1. Calling `start()` on both FSMs.
 *  2. Pumping each `SendFrame` effect through `toByteArray()` /
 *     [SharingFrames.parse] and feeding it back to the peer FSM as a
 *     [SharingFsmEvent.FrameReceived]. (Round-tripping through bytes
 *     ensures the FSM only relies on serialized fields, not on
 *     transient builder state.)
 *  3. Driving the user-consent decision when the receiver enters
 *     `WaitingForUserConsent`.
 *
 * Both the accept and reject paths are exercised end-to-end.
 */
class SharingFsmLoopbackTest {
    private val introduction =
        IntroductionFrame
            .newBuilder()
            .addFileMetadata(
                com.google.android.gms.nearby.sharing.Protocol.FileMetadata
                    .newBuilder()
                    .setName("loopback.bin")
                    .setPayloadId(1234L)
                    .setSize(2048L)
                    .build(),
            ).build()

    @Test
    fun `accept path completes with both FSMs in their post-negotiation states`() {
        val (inbound, outbound, transcript) = runLoopback(userConsent = true)

        assertThat(inbound.state).isEqualTo(InboundSharingState.ReceivingPayloads)
        assertThat(outbound.state).isEqualTo(OutboundSharingState.SendingPayloads)
        assertThat(transcript).contains(SharingFrameType.PAIRED_KEY_ENCRYPTION)
        assertThat(transcript).contains(SharingFrameType.PAIRED_KEY_RESULT)
        assertThat(transcript).contains(SharingFrameType.INTRODUCTION)
        assertThat(transcript).contains(SharingFrameType.RESPONSE)
        assertThat(transcript).doesNotContain(SharingFrameType.CANCEL)
    }

    @Test
    fun `reject path leaves both FSMs Disconnected`() {
        val (inbound, outbound, transcript) = runLoopback(userConsent = false)

        assertThat(inbound.state).isEqualTo(InboundSharingState.Disconnected)
        assertThat(outbound.state).isEqualTo(OutboundSharingState.Disconnected)
        // Reject still travels through a RESPONSE frame.
        assertThat(transcript).contains(SharingFrameType.RESPONSE)
    }

    /**
     * Coroutine-free turn-taking driver. Each iteration:
     *  - drains the outbound queue from both FSMs,
     *  - bytes-encodes / decodes each frame,
     *  - feeds it to the peer FSM,
     *  - if the inbound FSM has just emitted [SharingFsmEffect.IntroductionReceived],
     *    delivers the user-consent decision to it.
     *
     * Loop bounded by `maxTurns` to fail fast if a state machine ever
     * fails to terminate.
     */
    @Suppress("LoopWithTooManyJumpStatements") // Turn-taking loop is naturally guarded.
    private fun runLoopback(userConsent: Boolean): LoopbackResult {
        val rand = SecureRandom("loopback".toByteArray())
        val inbound = InboundSharingFsm(secureRandom = rand)
        val outbound = OutboundSharingFsm(introduction = introduction, secureRandom = rand)

        val transcript = mutableListOf<SharingFrameType>()
        val inboundOutbox = ArrayDeque<SharingFrame>()
        val outboundOutbox = ArrayDeque<SharingFrame>()
        var consentDelivered = false

        fun absorbInbound(effects: List<SharingFsmEffect>) {
            for (e in effects) {
                if (e is SharingFsmEffect.SendFrame) {
                    transcript.add(e.frame.v1.type)
                    inboundOutbox.addLast(e.frame)
                }
            }
        }

        fun absorbOutbound(effects: List<SharingFsmEffect>) {
            for (e in effects) {
                if (e is SharingFsmEffect.SendFrame) {
                    transcript.add(e.frame.v1.type)
                    outboundOutbox.addLast(e.frame)
                }
            }
        }

        absorbInbound(inbound.start())
        absorbOutbound(outbound.start())

        val maxTurns = 16
        repeat(maxTurns) {
            var progressed = false

            // Inbound's outbox -> outbound FSM.
            while (inboundOutbox.isNotEmpty()) {
                progressed = true
                val frame = inboundOutbox.removeFirst()
                val parsed = SharingFrames.parse(frame.toByteArray())
                absorbOutbound(outbound.onEvent(SharingFsmEvent.FrameReceived(parsed)))
            }

            // Outbound's outbox -> inbound FSM.
            while (outboundOutbox.isNotEmpty()) {
                progressed = true
                val frame = outboundOutbox.removeFirst()
                val parsed = SharingFrames.parse(frame.toByteArray())
                val effects = inbound.onEvent(SharingFsmEvent.FrameReceived(parsed))
                absorbInbound(effects)
                if (!consentDelivered &&
                    effects.any { it is SharingFsmEffect.IntroductionReceived }
                ) {
                    consentDelivered = true
                    absorbInbound(inbound.onEvent(SharingFsmEvent.UserConsent(accept = userConsent)))
                }
            }

            if (!progressed) return@repeat
        }

        return LoopbackResult(inbound, outbound, transcript)
    }

    private data class LoopbackResult(
        val inbound: InboundSharingFsm,
        val outbound: OutboundSharingFsm,
        val transcript: List<SharingFrameType>,
    )
}
