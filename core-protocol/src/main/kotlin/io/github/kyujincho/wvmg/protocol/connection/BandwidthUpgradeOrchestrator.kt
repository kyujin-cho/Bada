/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumRegistry
import io.github.kyujincho.wvmg.protocol.medium.PreparedUpgradeSelection
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.asFramedConnection
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blocking bandwidth-upgrade handshake runner.
 *
 * The sharing FSMs stay focused on Nearby Share payload negotiation. This
 * helper owns the lower Nearby Connections transport swap: it sends and
 * validates `BANDWIDTH_UPGRADE_NEGOTIATION` frames, asks the selected
 * [io.github.kyujincho.wvmg.protocol.medium.MediumProvider] to prepare /
 * adopt / accept, and returns a [SecureChannel] bound to the active
 * transport. The returned channel shares SecureMessage sequence state
 * with [oldChannel].
 */
internal object BandwidthUpgradeOrchestrator {
    @Suppress("ReturnCount")
    suspend fun runServerUpgradeIfAvailable(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        mediumRegistry: MediumRegistry,
        peerSupportedMediums: Set<Medium>,
        peerEndpointId: String,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        val selection = mediumRegistry.prepareBestUpgrade(peerSupportedMediums)
        if (selection !is PreparedUpgradeSelection.Upgrade) {
            logger("medium-upgrade: server staying on current transport selection=$selection")
            return ActiveTransportChannel(oldChannel, currentMedium)
        }

        val credentials = selection.credentials
        val provider = mediumRegistry.providerFor(credentials.medium)
        if (provider == null) {
            logger("medium-upgrade: no provider for prepared medium ${credentials.medium}")
            return ActiveTransportChannel(oldChannel, currentMedium)
        }

        logger("medium-upgrade: server offering ${credentials.medium}")
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradePathAvailable(credentials))

        val transport =
            withTimeoutOrNull(UPGRADE_TIMEOUT_MILLIS) {
                provider.acceptUpgrade()
            }
        if (transport == null) {
            logger("medium-upgrade: server accept timed out/failed for ${credentials.medium}")
            provider.cancelPendingUpgrade()
            runCatching { oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium)) }
            return ActiveTransportChannel(oldChannel, currentMedium)
        }

        return runCatching {
            val newChannel = oldChannel.withTransport(transport.asFramedConnection())
            val intro = receiveUpgradeFrame(newChannel, "CLIENT_INTRODUCTION on upgraded transport")
            val clientIntro = intro.v1.bandwidthUpgradeNegotiation.clientIntroduction
            checkUpgradeEvent(
                frame = intro,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION,
                stage = "server client introduction",
            )
            if (clientIntro.endpointId != peerEndpointId) {
                error(
                    "CLIENT_INTRODUCTION endpoint_id ${clientIntro.endpointId} " +
                        "did not match original $peerEndpointId",
                )
            }

            newChannel.sendOfflineFrame(BandwidthUpgradeFrames.clientIntroductionAck())
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
            val peerLast = receiveUpgradeFrame(oldChannel, "peer LAST_WRITE")
            checkUpgradeEvent(
                frame = peerLast,
                expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                stage = "server peer last-write",
            )
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
            val peerSafe = receiveUpgradeFrame(oldChannel, "peer SAFE_TO_CLOSE")
            checkUpgradeEvent(
                frame = peerSafe,
                expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                stage = "server peer safe-to-close",
            )
            logger("medium-upgrade: server completed ${credentials.medium}")
            ActiveTransportChannel(newChannel, credentials.medium)
        }.getOrElse { failure ->
            logger(
                "medium-upgrade: server failed ${credentials.medium}: " +
                    (failure.message ?: failure::class.simpleName),
            )
            transport.close()
            provider.cancelPendingUpgrade()
            ActiveTransportChannel(oldChannel, currentMedium)
        }
    }

    @Suppress("ReturnCount")
    suspend fun runClientUpgradeFromOffer(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        offer: OfflineFrame,
        mediumRegistry: MediumRegistry,
        endpointId: String,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        val credentials =
            decodeOfferCredentials(offer) ?: run {
                logger("medium-upgrade: client received malformed upgrade offer")
                return ActiveTransportChannel(oldChannel, currentMedium)
            }
        val provider = mediumRegistry.providerFor(credentials.medium)
        if (provider == null) {
            logger("medium-upgrade: client has no provider for ${credentials.medium}")
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium))
            return ActiveTransportChannel(oldChannel, currentMedium)
        }

        val transport =
            withTimeoutOrNull(UPGRADE_TIMEOUT_MILLIS) {
                provider.adoptUpgrade(credentials)
            }
        if (transport == null) {
            logger("medium-upgrade: client adopt failed for ${credentials.medium}")
            provider.cancelPendingUpgrade()
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium))
            return ActiveTransportChannel(oldChannel, currentMedium)
        }

        return runCatching {
            val newChannel = oldChannel.withTransport(transport.asFramedConnection())
            newChannel.sendOfflineFrame(BandwidthUpgradeFrames.clientIntroduction(endpointId))
            val ack = receiveUpgradeFrame(newChannel, "CLIENT_INTRODUCTION_ACK on upgraded transport")
            checkUpgradeEvent(
                frame = ack,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                stage = "client introduction ack",
            )
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
            val peerLast = receiveUpgradeFrame(oldChannel, "peer LAST_WRITE")
            checkUpgradeEvent(
                frame = peerLast,
                expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                stage = "client peer last-write",
            )
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
            val peerSafe = receiveUpgradeFrame(oldChannel, "peer SAFE_TO_CLOSE")
            checkUpgradeEvent(
                frame = peerSafe,
                expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                stage = "client peer safe-to-close",
            )
            logger("medium-upgrade: client completed ${credentials.medium}")
            ActiveTransportChannel(newChannel, credentials.medium)
        }.getOrElse { failure ->
            logger(
                "medium-upgrade: client failed ${credentials.medium}: " +
                    (failure.message ?: failure::class.simpleName),
            )
            transport.close()
            provider.cancelPendingUpgrade()
            ActiveTransportChannel(oldChannel, currentMedium)
        }
    }

    suspend fun receiveOfferProbe(channel: SecureChannel): UpgradeOfferProbe =
        withTimeoutOrNull(OFFER_WAIT_TIMEOUT_MILLIS) {
            val frame = channel.receiveOfflineFrame()
            if (frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)) {
                UpgradeOfferProbe.Offer(frame)
            } else {
                UpgradeOfferProbe.Other(frame)
            }
        } ?: UpgradeOfferProbe.None

    private fun decodeOfferCredentials(frame: OfflineFrame): UpgradePathCredentials? {
        if (!frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)) {
            return null
        }
        return BandwidthUpgradeFrames.decodeCredentials(frame.v1.bandwidthUpgradeNegotiation.upgradePathInfo)
    }

    private suspend fun receiveUpgradeFrame(
        channel: SecureChannel,
        stage: String,
    ): OfflineFrame =
        withTimeoutOrNull(UPGRADE_TIMEOUT_MILLIS) {
            channel.receiveOfflineFrame()
        } ?: error("Timed out waiting for $stage")

    private fun checkUpgradeEvent(
        frame: OfflineFrame,
        expected: BandwidthUpgradeNegotiationFrame.EventType,
        stage: String,
    ) {
        if (!frame.isBandwidthUpgradeEvent(expected)) {
            val actual = frame.describeUpgradeEvent()
            error("Expected $expected during $stage, got $actual")
        }
    }

    private fun OfflineFrame.describeUpgradeEvent(): String =
        if (hasV1() && v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
            v1.bandwidthUpgradeNegotiation.eventType.toString()
        } else {
            v1.type.toString()
        }

    private fun OfflineFrame.isBandwidthUpgradeEvent(expected: BandwidthUpgradeNegotiationFrame.EventType): Boolean =
        hasV1() &&
            v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
            v1.bandwidthUpgradeNegotiation.eventType == expected

    private const val UPGRADE_TIMEOUT_MILLIS: Long = 30_000L
    private const val OFFER_WAIT_TIMEOUT_MILLIS: Long = 1_500L
}

internal data class ActiveTransportChannel(
    val channel: SecureChannel,
    val medium: Medium,
)

internal sealed interface UpgradeOfferProbe {
    data object None : UpgradeOfferProbe

    data class Offer(
        val frame: OfflineFrame,
    ) : UpgradeOfferProbe

    data class Other(
        val frame: OfflineFrame,
    ) : UpgradeOfferProbe
}
