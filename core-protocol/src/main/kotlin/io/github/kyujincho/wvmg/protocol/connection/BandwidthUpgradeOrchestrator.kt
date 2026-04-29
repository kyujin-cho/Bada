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
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SequencedOfflineFrame
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumRegistry
import io.github.kyujincho.wvmg.protocol.medium.PreparedUpgradeSelection
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import io.github.kyujincho.wvmg.protocol.medium.asFramedConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap

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
        val selection =
            mediumRegistry.prepareBestUpgradeForCurrentTransport(
                peerSupported = peerSupportedMediums,
                currentMedium = currentMedium,
            )
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
            completeServerUpgrade(
                oldChannel = oldChannel,
                transport = transport,
                credentials = credentials,
                peerEndpointId = peerEndpointId,
                logger = logger,
            )
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

    private suspend fun completeServerUpgrade(
        oldChannel: SecureChannel,
        transport: UpgradedTransport,
        credentials: UpgradePathCredentials,
        peerEndpointId: String,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        val bufferedFrames = mutableListOf<OfflineFrame>()
        val newChannel = oldChannel.withTransport(transport.asFramedConnection())
        val reader = DualChannelFrameReader(oldChannel, newChannel, logger)
        val clientIntro =
            receiveExpectedUpgradeFrame(
                reader = reader,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION,
                stage = "server client introduction",
                applicationFrames = bufferedFrames,
                logger = logger,
            )
        validateServerClientIntroduction(
            intro = clientIntro,
            peerEndpointId = peerEndpointId,
        )
        newChannel.sendOfflineFrame(BandwidthUpgradeFrames.clientIntroductionAck())
        exchangePriorChannelClose(
            oldChannel = oldChannel,
            reader = reader,
            bufferedFrames = bufferedFrames,
            logger = logger,
            stagePrefix = "server",
        )
        reader.drainAvailableFrames(
            stage = "server post-safe-to-close",
            applicationFrames = bufferedFrames,
        )
        logger("medium-upgrade: server completed ${credentials.medium}")
        return ActiveTransportChannel(newChannel, credentials.medium, bufferedFrames)
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
            completeClientUpgrade(
                oldChannel = oldChannel,
                transport = transport,
                credentials = credentials,
                endpointId = endpointId,
                logger = logger,
            )
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

    private suspend fun completeClientUpgrade(
        oldChannel: SecureChannel,
        transport: UpgradedTransport,
        credentials: UpgradePathCredentials,
        endpointId: String,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        val bufferedFrames = mutableListOf<OfflineFrame>()
        val newChannel = oldChannel.withTransport(transport.asFramedConnection())
        val reader = DualChannelFrameReader(oldChannel, newChannel, logger)
        newChannel.sendOfflineFrame(BandwidthUpgradeFrames.clientIntroduction(endpointId))
        receiveAndCheckUpgradeFrame(
            reader = reader,
            expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
            stage = "client introduction ack",
            applicationFrames = bufferedFrames,
            logger = logger,
        )
        exchangeClientPriorChannelClose(
            oldChannel = oldChannel,
            reader = reader,
            bufferedFrames = bufferedFrames,
            logger = logger,
        )
        reader.drainAvailableFrames(
            stage = "client post-safe-to-close",
            applicationFrames = bufferedFrames,
        )
        logger("medium-upgrade: client completed ${credentials.medium}")
        return ActiveTransportChannel(newChannel, credentials.medium, bufferedFrames)
    }

    private suspend fun exchangeClientPriorChannelClose(
        oldChannel: SecureChannel,
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ) {
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
        receiveAndCheckUpgradeFrame(
            reader = reader,
            expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            stage = "client peer last-write",
            applicationFrames = bufferedFrames,
            logger = logger,
        )
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
        receiveAndCheckUpgradeFrame(
            reader = reader,
            expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
            stage = "client peer safe-to-close",
            applicationFrames = bufferedFrames,
            logger = logger,
        )
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

    private fun validateServerClientIntroduction(
        intro: OfflineFrame,
        peerEndpointId: String,
    ) {
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
    }

    private suspend fun exchangePriorChannelClose(
        oldChannel: SecureChannel,
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
        stagePrefix: String,
    ) {
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
        val peerLast =
            receiveExpectedUpgradeFrame(
                reader = reader,
                expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                stage = "$stagePrefix peer last-write",
                applicationFrames = bufferedFrames,
                logger = logger,
            )
        checkUpgradeEvent(
            frame = peerLast,
            expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            stage = "$stagePrefix peer last-write",
        )

        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
        val peerSafe =
            receiveExpectedUpgradeFrame(
                reader = reader,
                expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                stage = "$stagePrefix peer safe-to-close",
                applicationFrames = bufferedFrames,
                logger = logger,
            )
        checkUpgradeEvent(
            frame = peerSafe,
            expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
            stage = "$stagePrefix peer safe-to-close",
        )
    }

    private fun decodeOfferCredentials(frame: OfflineFrame): UpgradePathCredentials? {
        if (!frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)) {
            return null
        }
        return BandwidthUpgradeFrames.decodeCredentials(frame.v1.bandwidthUpgradeNegotiation.upgradePathInfo)
    }

    private suspend fun receiveExpectedUpgradeFrame(
        reader: DualChannelFrameReader,
        expected: BandwidthUpgradeNegotiationFrame.EventType,
        stage: String,
        applicationFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ): OfflineFrame {
        while (true) {
            val frame = reader.receiveNextFrame(stage)
            if (frame.isBandwidthUpgradeNegotiation()) {
                return frame
            }
            logger(
                "medium-upgrade: buffered ${frame.describeFrameType()} " +
                    "while waiting for $expected during $stage",
            )
            applicationFrames += frame
        }
    }

    private suspend fun receiveAndCheckUpgradeFrame(
        reader: DualChannelFrameReader,
        expected: BandwidthUpgradeNegotiationFrame.EventType,
        stage: String,
        applicationFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ): OfflineFrame =
        receiveExpectedUpgradeFrame(
            reader = reader,
            expected = expected,
            stage = stage,
            applicationFrames = applicationFrames,
            logger = logger,
        ).also { frame ->
            checkUpgradeEvent(
                frame = frame,
                expected = expected,
                stage = stage,
            )
        }

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

    private fun OfflineFrame.describeFrameType(): String =
        if (hasV1()) {
            v1.type.toString()
        } else {
            "NO_V1"
        }

    private fun OfflineFrame.isBandwidthUpgradeNegotiation(): Boolean =
        hasV1() && v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION

    private fun OfflineFrame.isBandwidthUpgradeEvent(expected: BandwidthUpgradeNegotiationFrame.EventType): Boolean =
        hasV1() &&
            v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
            v1.bandwidthUpgradeNegotiation.eventType == expected

    internal class DualChannelFrameReader(
        oldChannel: SecureChannel,
        newChannel: SecureChannel,
        private val logger: (String) -> Unit,
    ) {
        private val sources =
            listOf(
                FrameSource("prior", oldChannel),
                FrameSource("upgraded", newChannel),
            )
        private val pendingFrames: TreeMap<Int, SequencedRead> = TreeMap()
        private var nextSequenceNumber: Int =
            (oldChannel.nextReceiveSequenceNumber + 1)
                .also { next ->
                    check(next <= Int.MAX_VALUE.toLong()) {
                        "Receive sequence number overflowed Int.MAX_VALUE; close the channel"
                    }
                }.toInt()

        suspend fun receiveNextFrame(stage: String): OfflineFrame =
            withTimeoutOrNull<OfflineFrame>(UPGRADE_TIMEOUT_MILLIS) {
                while (true) {
                    deliverNextPendingFrame()?.let { return@withTimeoutOrNull it }

                    var readAny = false
                    for (source in sources) {
                        if (source.hasBufferedInput()) {
                            readFrom(source)
                            readAny = true
                        }
                    }
                    if (!readAny) {
                        delay(DUAL_CHANNEL_POLL_DELAY_MILLIS)
                    }
                }
                error("unreachable")
            } ?: error("Timed out waiting for $stage")

        suspend fun drainAvailableFrames(
            stage: String,
            applicationFrames: MutableList<OfflineFrame>,
        ) {
            var idleAttempts = 0
            while (idleAttempts < DUAL_CHANNEL_DRAIN_IDLE_ATTEMPTS) {
                val delivered = deliverNextPendingFrame()
                if (delivered != null) {
                    logger("medium-upgrade: buffered ${delivered.describeFrameType()} during $stage")
                    applicationFrames += delivered
                    idleAttempts = 0
                    continue
                }

                var readAny = false
                for (source in sources) {
                    if (source.hasBufferedInput()) {
                        readFrom(source)
                        readAny = true
                    }
                }
                if (readAny) {
                    idleAttempts = 0
                } else {
                    idleAttempts += 1
                    delay(DUAL_CHANNEL_POLL_DELAY_MILLIS)
                }
            }
        }

        private suspend fun readFrom(source: FrameSource) {
            val sequenced = source.channel.receiveSequencedOfflineFrame()
            check(sequenced.sequenceNumber >= nextSequenceNumber) {
                "Received stale SecureMessage sequence ${sequenced.sequenceNumber} from " +
                    "${source.name}; next expected is $nextSequenceNumber"
            }
            val previous =
                pendingFrames.putIfAbsent(
                    sequenced.sequenceNumber,
                    SequencedRead(source, sequenced),
                )
            check(previous == null) {
                "Received duplicate SecureMessage sequence ${sequenced.sequenceNumber} " +
                    "from ${source.name} and ${previous?.source?.name}"
            }
            logger(
                "medium-upgrade: decoded ${sequenced.frame.describeFrameType()} " +
                    "seq=${sequenced.sequenceNumber} from ${source.name}",
            )
        }

        private suspend fun deliverNextPendingFrame(): OfflineFrame? {
            val next = pendingFrames.remove(nextSequenceNumber) ?: return null
            val frame = next.source.channel.acceptSequencedOfflineFrame(next.sequenced)
            logger(
                "medium-upgrade: delivered ${frame.describeFrameType()} " +
                    "seq=$nextSequenceNumber from ${next.source.name}",
            )
            nextSequenceNumber += 1
            return frame
        }

        private class FrameSource(
            val name: String,
            val channel: SecureChannel,
        ) {
            fun hasBufferedInput(): Boolean = runCatching { channel.hasBufferedInput() }.getOrDefault(false)
        }

        private data class SequencedRead(
            val source: FrameSource,
            val sequenced: SequencedOfflineFrame,
        )
    }

    private const val UPGRADE_TIMEOUT_MILLIS: Long = 30_000L
    private const val OFFER_WAIT_TIMEOUT_MILLIS: Long = 1_500L
    private const val DUAL_CHANNEL_DRAIN_IDLE_ATTEMPTS: Int = 25
    private const val DUAL_CHANNEL_POLL_DELAY_MILLIS: Long = 10L
}

internal data class ActiveTransportChannel(
    val channel: SecureChannel,
    val medium: Medium,
    val bufferedFrames: List<OfflineFrame> = emptyList(),
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
