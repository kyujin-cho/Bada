/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.connection.BandwidthUpgradeFrames
import org.junit.jupiter.api.Test

/**
 * Coverage for [BandwidthUpgradeNegotiator].
 *
 * The FSM is the source of truth for the BANDWIDTH_UPGRADE_NEGOTIATION
 * event sequence. The orchestrator integration (transport swap) lives
 * outside the FSM and is exercised separately by Phase 4 sub-issue #54.
 */
class BandwidthUpgradeNegotiatorTest {
    private val endpointId = "ABCD"
    private val medium = Medium.WIFI_DIRECT
    private val credentials: UpgradePathCredentials = UpgradePathCredentials.Generic(medium)

    @Test
    fun `server initiates by sending UPGRADE_PATH_AVAILABLE on Start`() {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.SERVER,
                medium = medium,
                endpointId = endpointId,
            )
        val effects = fsm.onEvent(BandwidthUpgradeEvent.Start(credentials))
        val sent = effects.singleSendFrame()
        assertThat(sent.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
        assertThat(fsm.state).isEqualTo(BandwidthUpgradeNegotiator.State.AwaitingClientIntroduction)
    }

    @Test
    fun `client cannot initiate via Start`() {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.CLIENT,
                medium = medium,
                endpointId = endpointId,
            )
        val effects = fsm.onEvent(BandwidthUpgradeEvent.Start(credentials))
        assertThat(effects).contains(
            BandwidthUpgradeEffect.ProtocolError("Client received Start; only server may initiate"),
        )
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.Failed::class.java)
    }

    @Test
    fun `client adopts on UPGRADE_PATH_AVAILABLE`() {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.CLIENT,
                medium = medium,
                endpointId = endpointId,
            )
        val pathFrame = BandwidthUpgradeFrames.upgradePathAvailable(credentials)
        val effects = fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(pathFrame))
        val adopt = effects.filterIsInstance<BandwidthUpgradeEffect.AdoptTransport>().single()
        assertThat(adopt.credentials.medium).isEqualTo(medium)
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.AdoptingTransport::class.java)
    }

    @Test
    fun `client sends CLIENT_INTRODUCTION after AdoptSucceeded`() {
        val fsm = clientPostAdopt()
        val effects = fsm.onEvent(BandwidthUpgradeEvent.AdoptSucceeded)
        val sent = effects.singleSendFrame()
        assertThat(sent.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
        assertThat(sent.clientIntroduction.endpointId).isEqualTo(endpointId)
        assertThat(fsm.state).isEqualTo(BandwidthUpgradeNegotiator.State.AwaitingIntroductionAck)
    }

    @Test
    fun `client emits UPGRADE_FAILURE on AdoptFailed`() {
        val fsm = clientPostAdopt()
        val effects = fsm.onEvent(BandwidthUpgradeEvent.AdoptFailed("hotspot did not come up"))
        val sent = effects.filterIsInstance<BandwidthUpgradeEffect.SendFrame>().single()
        val parsed = sent.frame.v1.bandwidthUpgradeNegotiation
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(medium.wireNumber)
        assertThat(effects).contains(BandwidthUpgradeEffect.UpgradeAborted("hotspot did not come up"))
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.Failed::class.java)
    }

    @Test
    fun `server emits CLIENT_INTRODUCTION_ACK plus LAST_WRITE on receiving CLIENT_INTRODUCTION`() {
        val fsm = serverPostStart()
        val intro = BandwidthUpgradeFrames.clientIntroduction(endpointId)
        val effects = fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(intro))
        val sent = effects.filterIsInstance<BandwidthUpgradeEffect.SendFrame>()
        val types =
            sent.map { it.frame.v1.bandwidthUpgradeNegotiation.eventType }
        assertThat(types)
            .containsExactly(
                BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            ).inOrder()
        assertThat(fsm.state).isEqualTo(BandwidthUpgradeNegotiator.State.LastWritePending)
    }

    @Test
    fun `client transitions LastWritePending after receiving CLIENT_INTRODUCTION_ACK`() {
        val fsm = clientPostIntroduction()
        val ack = BandwidthUpgradeFrames.clientIntroductionAck()
        val effects = fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(ack))
        val sent = effects.singleSendFrame()
        assertThat(sent.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL)
        assertThat(fsm.state).isEqualTo(BandwidthUpgradeNegotiator.State.LastWritePending)
    }

    @Test
    fun `safe-to-close exchange completes the upgrade`() {
        // Walk a server through to AwaitingDrain, send the drain
        // notification, then deliver SAFE_TO_CLOSE and assert Completed.
        val fsm = serverPostStart()
        fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(BandwidthUpgradeFrames.clientIntroduction(endpointId)))
        // Receive the peer's LAST_WRITE on the old channel.
        fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(BandwidthUpgradeFrames.lastWriteToPriorChannel()))
        // Orchestrator says: own writer is drained. FSM emits SAFE_TO_CLOSE.
        val drainEffects = fsm.onEvent(BandwidthUpgradeEvent.PriorChannelDrained)
        assertThat(drainEffects.singleSendFrame().eventType)
            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL)
        // Peer's SAFE_TO_CLOSE arrives → Completed.
        val finalEffects =
            fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(BandwidthUpgradeFrames.safeToClosePriorChannel()))
        assertThat(finalEffects).containsExactly(BandwidthUpgradeEffect.UpgradeCompleted)
        assertThat(fsm.state).isEqualTo(BandwidthUpgradeNegotiator.State.Completed)
    }

    @Test
    fun `inbound UPGRADE_FAILURE aborts on either role`() {
        val fsm = clientPostAdopt()
        val failure = BandwidthUpgradeFrames.upgradeFailure(medium)
        val effects = fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(failure))
        assertThat(effects).contains(BandwidthUpgradeEffect.UpgradeAborted("Peer sent UPGRADE_FAILURE"))
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.Failed::class.java)
    }

    @Test
    fun `Abort emits UPGRADE_FAILURE and transitions to Failed`() {
        val fsm = serverPostStart()
        val effects = fsm.onEvent(BandwidthUpgradeEvent.Abort)
        val sent = effects.filterIsInstance<BandwidthUpgradeEffect.SendFrame>().single()
        assertThat(sent.frame.v1.bandwidthUpgradeNegotiation.eventType)
            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
        assertThat(effects).contains(BandwidthUpgradeEffect.UpgradeAborted("abort requested by orchestrator"))
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.Failed::class.java)
    }

    @Test
    fun `non-BANDWIDTH_UPGRADE frame produces ProtocolError`() {
        val fsm = serverPostStart()
        val keepAlive =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.KEEP_ALIVE)
                        .build(),
                ).build()
        val effects = fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(keepAlive))
        assertThat(effects.filterIsInstance<BandwidthUpgradeEffect.ProtocolError>()).isNotEmpty()
        assertThat(fsm.state).isInstanceOf(BandwidthUpgradeNegotiator.State.Failed::class.java)
    }

    @Test
    fun `server PrepareFailed before Start emits UPGRADE_FAILURE and Aborted`() {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.SERVER,
                medium = medium,
                endpointId = endpointId,
            )
        val effects = fsm.onEvent(BandwidthUpgradeEvent.PrepareFailed("hotspot capability denied"))
        val sent = effects.filterIsInstance<BandwidthUpgradeEffect.SendFrame>().single()
        assertThat(sent.frame.v1.bandwidthUpgradeNegotiation.eventType)
            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
        assertThat(effects).contains(BandwidthUpgradeEffect.UpgradeAborted("hotspot capability denied"))
    }

    private fun serverPostStart(): BandwidthUpgradeNegotiator {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.SERVER,
                medium = medium,
                endpointId = endpointId,
            )
        fsm.onEvent(BandwidthUpgradeEvent.Start(credentials))
        return fsm
    }

    private fun clientPostAdopt(): BandwidthUpgradeNegotiator {
        val fsm =
            BandwidthUpgradeNegotiator(
                role = BandwidthUpgradeNegotiator.Role.CLIENT,
                medium = medium,
                endpointId = endpointId,
            )
        val pathFrame = BandwidthUpgradeFrames.upgradePathAvailable(credentials)
        fsm.onEvent(BandwidthUpgradeEvent.FrameReceived(pathFrame))
        return fsm
    }

    private fun clientPostIntroduction(): BandwidthUpgradeNegotiator {
        val fsm = clientPostAdopt()
        fsm.onEvent(BandwidthUpgradeEvent.AdoptSucceeded)
        return fsm
    }

    private fun List<BandwidthUpgradeEffect>.singleSendFrame(): BandwidthUpgradeNegotiationFrame {
        val sent = filterIsInstance<BandwidthUpgradeEffect.SendFrame>().single()
        return sent.frame.v1.bandwidthUpgradeNegotiation
    }
}
