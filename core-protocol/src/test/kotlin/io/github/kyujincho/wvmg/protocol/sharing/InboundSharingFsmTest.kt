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
 * Tests for [InboundSharingFsm].
 *
 * Coverage targets the full transition table:
 *
 *  - Happy path: PKE → PKR → INTRODUCTION → UserConsent(true) → ACCEPT
 *    → ReadyToReceivePayloads → Completed.
 *  - Reject path: UserConsent(false) → REJECT.
 *  - User cancel from any non-terminal state.
 *  - Peer cancel from any non-terminal state.
 *  - Misordered frame (e.g. INTRODUCTION before paired-key dance) →
 *    ProtocolError.
 *  - Frame with mismatched discriminator/body (e.g. type=INTRODUCTION
 *    but no introduction body) → ProtocolError.
 *  - Idempotency of [InboundSharingFsm.start] and post-disconnect
 *    [InboundSharingFsm.onEvent].
 */
@Suppress("LargeClass") // Negotiation has many invariants; one test per invariant is the discipline.
class InboundSharingFsmTest {
    private fun newFsm(): InboundSharingFsm = InboundSharingFsm(secureRandom = SecureRandom("seed".toByteArray()))

    @Test
    fun `start emits a single PairedKeyEncryptionFrame and is idempotent`() {
        val fsm = newFsm()
        val effects = fsm.start()

        assertThat(effects).hasSize(1)
        val send = effects[0] as SharingFsmEffect.SendFrame
        assertThat(send.frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_ENCRYPTION)
        assertThat(fsm.state).isEqualTo(InboundSharingState.SentPairedKeyEncryption)

        // Second call must not re-emit.
        assertThat(fsm.start()).isEmpty()
    }

    @Test
    fun `happy path drives SentPKE through ReceivingPayloads`() {
        val fsm = newFsm()
        fsm.start()

        // Step 1: peer sends PKE.
        val afterPke =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyEncryption()),
            )
        assertThat(afterPke).hasSize(1)
        val pkrSend = afterPke[0] as SharingFsmEffect.SendFrame
        assertThat(pkrSend.frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_RESULT)
        assertThat(pkrSend.frame.v1.pairedKeyResult.status).isEqualTo(PairedKeyResultStatus.UNABLE)
        assertThat(fsm.state).isEqualTo(InboundSharingState.SentPairedKeyResult)

        // Step 2: peer sends PKR.
        val afterPkr =
            fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()))
        assertThat(afterPkr).isEmpty()
        assertThat(fsm.state).isEqualTo(InboundSharingState.ReceivedPairedKeyResult)

        // Step 3: peer sends Introduction.
        val intro =
            IntroductionFrame
                .newBuilder()
                .addFileMetadata(
                    com.google.android.gms.nearby.sharing.Protocol.FileMetadata
                        .newBuilder()
                        .setName("photo.jpg")
                        .setPayloadId(42L)
                        .setSize(1024L)
                        .build(),
                ).build()
        val afterIntro =
            fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.introduction(intro)))
        assertThat(afterIntro).hasSize(1)
        val introNotify = afterIntro[0] as SharingFsmEffect.IntroductionReceived
        assertThat(introNotify.introduction).isEqualTo(intro)
        assertThat(fsm.state).isEqualTo(InboundSharingState.WaitingForUserConsent)

        // Step 4: user accepts.
        val afterAccept = fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))
        assertThat(afterAccept).hasSize(3)
        val acceptSend = afterAccept[0] as SharingFsmEffect.SendFrame
        assertThat(acceptSend.frame.v1.type).isEqualTo(SharingFrameType.RESPONSE)
        assertThat(acceptSend.frame.v1.connectionResponse.status)
            .isEqualTo(ConnectionResponseStatus.ACCEPT)
        assertThat(afterAccept[1]).isInstanceOf(SharingFsmEffect.ReadyToReceivePayloads::class.java)
        assertThat(afterAccept[2]).isInstanceOf(SharingFsmEffect.Completed::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.ReceivingPayloads)
    }

    @Test
    fun `reject path emits SendFrame REJECT then Rejected then Cancelled`() {
        val fsm = driveToWaitingForUserConsent()

        val effects = fsm.onEvent(SharingFsmEvent.UserConsent(accept = false))

        assertThat(effects).hasSize(3)
        val rejectSend = effects[0] as SharingFsmEffect.SendFrame
        assertThat(rejectSend.frame.v1.type).isEqualTo(SharingFrameType.RESPONSE)
        assertThat(rejectSend.frame.v1.connectionResponse.status)
            .isEqualTo(ConnectionResponseStatus.REJECT)
        val rejected = effects[1] as SharingFsmEffect.Rejected
        assertThat(rejected.status).isEqualTo(ConnectionResponseStatus.REJECT)
        assertThat(effects[2]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
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
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `user cancel from WaitingForUserConsent still emits CancelFrame and Cancelled`() {
        val fsm = driveToWaitingForUserConsent()

        val effects = fsm.onEvent(SharingFsmEvent.UserCancel)

        assertThat(effects).hasSize(2)
        val send = effects[0] as SharingFsmEffect.SendFrame
        assertThat(send.frame.v1.type).isEqualTo(SharingFrameType.CANCEL)
        assertThat(effects[1]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `peer cancel emits Cancelled with no outbound frame`() {
        val fsm = newFsm()
        fsm.start()

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))

        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `misordered frame INTRODUCTION before paired-key dance is a protocol error`() {
        val fsm = newFsm()
        fsm.start()

        val intro = IntroductionFrame.newBuilder().build()
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.introduction(intro)))

        assertThat(effects).hasSize(1)
        val err = effects[0] as SharingFsmEffect.ProtocolError
        assertThat(err.reason).contains("PAIRED_KEY_ENCRYPTION")
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `frame with type=PAIRED_KEY_ENCRYPTION but no body is a protocol error`() {
        val fsm = newFsm()
        fsm.start()

        // Build a frame with the right type but no paired_key_encryption body.
        val malformed =
            SharingFrame
                .newBuilder()
                .setVersion(SharingFrameVersion.V1)
                .setV1(
                    com.google.android.gms.nearby.sharing.Protocol.V1Frame
                        .newBuilder()
                        .setType(SharingFrameType.PAIRED_KEY_ENCRYPTION)
                        .build(),
                ).build()

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(malformed))

        assertThat(effects).hasSize(1)
        val err = effects[0] as SharingFsmEffect.ProtocolError
        assertThat(err.reason).contains("missing paired_key_encryption body")
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `UserConsent in wrong state is a protocol error`() {
        val fsm = newFsm()
        fsm.start()

        val effects = fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))

        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.ProtocolError::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `events after Disconnected return empty list`() {
        val fsm = newFsm()
        fsm.start()
        fsm.onEvent(SharingFsmEvent.UserCancel) // Disconnected

        assertThat(fsm.onEvent(SharingFsmEvent.UserCancel)).isEmpty()
        assertThat(fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))).isEmpty()
        assertThat(fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))).isEmpty()
    }

    @Test
    fun `frame in ReceivingPayloads is a protocol error`() {
        val fsm = driveToWaitingForUserConsent()
        fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))
        // Now in ReceivingPayloads.

        // Sending a stray frame in ReceivingPayloads should be flagged.
        val effects =
            fsm.onEvent(
                SharingFsmEvent.FrameReceived(
                    SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT),
                ),
            )
        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.ProtocolError::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    @Test
    fun `peer cancel during ReceivingPayloads cleanly disconnects`() {
        val fsm = driveToWaitingForUserConsent()
        fsm.onEvent(SharingFsmEvent.UserConsent(accept = true))

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.cancel()))

        assertThat(effects).hasSize(1)
        assertThat(effects[0]).isInstanceOf(SharingFsmEffect.Cancelled::class.java)
        assertThat(fsm.state).isEqualTo(InboundSharingState.Disconnected)
    }

    /**
     * Regression guard for issue #40: an introduction that mixes
     * `file_metadata` with multiple `text_metadata` entries MUST be
     * surfaced verbatim to the orchestrator (which, in turn, drives
     * the consent UI). NearDrop rejects the mixed shape; we accept it
     * because the Quick Share wire protocol does not impose
     * homogeneity.
     */
    @Test
    fun `mixed introduction with files and multiple texts is accepted verbatim`() {
        val fsm = newFsm()
        fsm.start()
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyEncryption()))
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()))

        val intro =
            IntroductionFrame
                .newBuilder()
                .addFileMetadata(
                    com.google.android.gms.nearby.sharing.Protocol.FileMetadata
                        .newBuilder()
                        .setName("photo.jpg")
                        .setPayloadId(101L)
                        .setSize(2048L)
                        .build(),
                ).addTextMetadata(
                    com.google.android.gms.nearby.sharing.Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("link")
                        .setPayloadId(201L)
                        .setSize(40L)
                        .setType(com.google.android.gms.nearby.sharing.Protocol.TextMetadata.Type.URL)
                        .build(),
                ).addTextMetadata(
                    com.google.android.gms.nearby.sharing.Protocol.TextMetadata
                        .newBuilder()
                        .setTextTitle("memo")
                        .setPayloadId(202L)
                        .setSize(20L)
                        .setType(com.google.android.gms.nearby.sharing.Protocol.TextMetadata.Type.TEXT)
                        .build(),
                ).build()

        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.introduction(intro)))

        assertThat(effects).hasSize(1)
        val notify = effects[0] as SharingFsmEffect.IntroductionReceived
        // Verbatim: the FSM does not strip / reorder / reject any of
        // the announced metadata, so the orchestrator and consent UI
        // see exactly what the peer sent.
        assertThat(notify.introduction).isEqualTo(intro)
        assertThat(notify.introduction.fileMetadataCount).isEqualTo(1)
        assertThat(notify.introduction.textMetadataCount).isEqualTo(2)
        assertThat(fsm.state).isEqualTo(InboundSharingState.WaitingForUserConsent)
    }

    /**
     * Drives the FSM through PKE / PKR / INTRODUCTION so the test body
     * starts in [InboundSharingState.WaitingForUserConsent].
     */
    private fun driveToWaitingForUserConsent(): InboundSharingFsm {
        val fsm = newFsm()
        fsm.start()
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyEncryption()))
        fsm.onEvent(SharingFsmEvent.FrameReceived(SharingFrames.pairedKeyResult()))
        fsm.onEvent(
            SharingFsmEvent.FrameReceived(
                SharingFrames.introduction(IntroductionFrame.newBuilder().build()),
            ),
        )
        check(fsm.state == InboundSharingState.WaitingForUserConsent) {
            "fixture state setup failed: ${fsm.state}"
        }
        return fsm
    }
}
