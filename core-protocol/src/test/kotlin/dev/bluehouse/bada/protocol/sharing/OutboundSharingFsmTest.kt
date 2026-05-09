/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.sharing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.SecureRandom

/**
 * Tests for [OutboundSharingFsm].
 *
 * Coverage targets the full transition table:
 *
 *  - Happy path: PKE → PKR → INTRODUCTION emitted → RESPONSE{ACCEPT}
 *    → ReadyToSendPayloads → Completed.
 *  - Reject path: each non-ACCEPT `ConnectionResponseFrame.Status`
 *    surfaces as [SharingFsmEffect.Rejected] with the original status.
 *    The issue body lists `REJECT`, `UNKNOWN`, `NOT_ENOUGH_SPACE`,
 *    `TIMED_OUT`, `UNSUPPORTED_ATTACHMENT_TYPE` — we exercise all five.
 *  - User cancel from any non-terminal state.
 *  - Peer cancel from any non-terminal state.
 *  - Misordered frame → ProtocolError.
 *  - UserConsent surfaced to the sender FSM is rejected (sender-only
 *    bug-detection path).
 */
class OutboundSharingFsmTest {
    private val introduction =
        IntroductionFrame
            .newBuilder()
            .addFileMetadata(
                com.google.android.gms.nearby.sharing.Protocol.FileMetadata
                    .newBuilder()
                    .setName("hello.txt")
                    .setPayloadId(7L)
                    .setSize(11L)
                    .build(),
            ).build()

    private fun newFsm(): OutboundSharingFsm =
        OutboundSharingFsm(
            introduction = introduction,
            secureRandom = SecureRandom("seed".toByteArray()),
        )

    @Test
    fun `start emits a single PairedKeyEncryptionFrame and is idempotent`() {
        val fsm = newFsm()
        val effects = fsm.start()

        assertThat(effects).hasSize(1)
        val send = effects[0] as SharingFsmEffect.SendFrame
        assertThat(send.frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_ENCRYPTION)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.SentPairedKeyEncryption)

        assertThat(fsm.start()).isEmpty()
    }

    @Test
    fun `happy path drives SentPKE through SendingPayloads`() {
        val fsm = newFsm()
        fsm.start()

        // Step 1: peer sends PKE.
        val afterPke =
            fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyEncryption()))
        val pkrSend = afterPke[0] as SharingFsmEffect.SendFrame
        assertThat(pkrSend.frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_RESULT)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.SentPairedKeyResult)

        // Step 2: peer sends PKR — sender emits introduction.
        val afterPkr =
            fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()))
        assertThat(afterPkr).hasSize(1)
        val introSend = afterPkr[0] as SharingFsmEffect.SendFrame
        assertThat(introSend.frame.v1.type).isEqualTo(SharingFrameType.INTRODUCTION)
        assertThat(introSend.frame.v1.introduction).isEqualTo(introduction)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.SentIntroduction)

        // Step 3: peer sends RESPONSE{ACCEPT}.
        val afterAccept =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(
                    SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT),
                ),
            )
        assertThat(afterAccept).hasSize(2)
        assertThat(afterAccept[0]).isInstanceOf(SharingFsmEffect.ReadyToSendPayloads::class.java)
        assertThat(afterAccept[1]).isInstanceOf(SharingFsmEffect.Completed::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.SendingPayloads)
    }

    @ParameterizedTest
    @EnumSource(
        value = ConnectionResponseStatus::class,
        names = ["REJECT", "UNKNOWN", "NOT_ENOUGH_SPACE", "TIMED_OUT", "UNSUPPORTED_ATTACHMENT_TYPE"],
    )
    fun `non-accept response status surfaces as Rejected with that exact status`(status: ConnectionResponseStatus) {
        val fsm = driveToSentIntroduction()

        val effects =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(SharingFrames.connectionResponse(status)),
            )

        assertThat(effects).hasSize(2)
        val rejected = effects[0] as SharingFsmEffect.Rejected
        assertThat(rejected.status).isEqualTo(status)
        assertThat(effects[1]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `user cancel from SentPairedKeyEncryption emits CancelFrame and Cancelled`() {
        val fsm = newFsm()
        fsm.start()

        val effects = fsm.onEvent(SharingFsmEvent.UserCancel)

        assertThat(effects).hasSize(2)
        val send = effects[0] as SharingFsmEffect.SendFrame
        assertThat(send.frame.v1.type).isEqualTo(SharingFrameType.CANCEL)
        assertThat(effects[1]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `user cancel from SentIntroduction also emits CancelFrame and Cancelled`() {
        val fsm = driveToSentIntroduction()

        val effects = fsm.onEvent(SharingFsmEvent.UserCancel)

        assertThat(effects).hasSize(2)
        val send = effects[0] as SharingFsmEffect.SendFrame
        assertThat(send.frame.v1.type).isEqualTo(SharingFrameType.CANCEL)
        assertThat(effects[1]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `peer cancel emits Cancelled with no outbound frame`() {
        val fsm = newFsm()
        fsm.start()

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))

        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `misordered RESPONSE before introduction is a protocol error`() {
        val fsm = newFsm()
        fsm.start()

        val effects =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(
                    SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT),
                ),
            )

        assertThat(effects).hasSize(1)
        val err = effects[0] as SharingFsmEffect.ProtocolError
        assertThat(err.reason).contains("PAIRED_KEY_ENCRYPTION")
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `RESPONSE frame with no body is a protocol error`() {
        val fsm = driveToSentIntroduction()

        val malformed =
            SharingFrame
                .newBuilder()
                .setVersion(SharingFrameVersion.V1)
                .setV1(
                    com.google.android.gms.nearby.sharing.Protocol.V1Frame
                        .newBuilder()
                        .setType(SharingFrameType.RESPONSE)
                        .build(),
                ).build()

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(malformed))
        assertThat(effects).hasSize(1)
        val err = effects[0] as SharingFsmEffect.ProtocolError
        assertThat(err.reason).contains("missing connection_response body")
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `UserConsent surfaced to sender FSM is a protocol error`() {
        val fsm = newFsm()
        fsm.start()

        val effects = fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))

        assertThat(effects).hasSize(1)
        val err = effects[0] as SharingFsmEffect.ProtocolError
        assertThat(err.reason).contains("receiver-only")
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `events after Disconnected return empty list`() {
        val fsm = newFsm()
        fsm.start()
        fsm.onEvent(SharingFsmEvent.UserCancel)

        assertThat(fsm.onEvent(SharingFsmEvent.UserCancel)).isEmpty()
        assertThat(fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))).isEmpty()
    }

    @Test
    fun `stray frame in SendingPayloads is a protocol error`() {
        val fsm = driveToSentIntroduction()
        fsm.onEvent(
            SharingFsmEvent.FrameReceived(
                SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT),
            ),
        )
        // Now in SendingPayloads.

        val effects =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()),
            )
        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.ProtocolError::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    @Test
    fun `peer cancel during SendingPayloads cleanly disconnects`() {
        val fsm = driveToSentIntroduction()
        fsm.onEvent(
            SharingFsmEvent.FrameReceived(
                SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT),
            ),
        )

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))
        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(OutboundSharingState.Disconnected)
    }

    /**
     * Drives the FSM through PKE / PKR / outbound INTRODUCTION so the
     * test body starts in [OutboundSharingState.SentIntroduction].
     */
    private fun driveToSentIntroduction(): OutboundSharingFsm {
        val fsm = newFsm()
        fsm.start()
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyEncryption()))
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()))
        check(fsm.state == OutboundSharingState.SentIntroduction) {
            "fixture state setup failed: ${fsm.state}"
        }
        return fsm
    }
}
