/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.crypto.securemessage.SequencedOfflineFrame
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.PreparedUpgradeSelection
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.medium.asFramedConnection
import dev.bluehouse.bada.protocol.transport.FramedConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap

/**
 * Blocking bandwidth-upgrade handshake runner.
 *
 * The sharing FSMs stay focused on Nearby Share payload negotiation. This
 * helper owns the lower Nearby Connections transport swap: it sends and
 * validates `BANDWIDTH_UPGRADE_NEGOTIATION` frames, asks the selected
 * [dev.bluehouse.bada.protocol.medium.MediumProvider] to prepare /
 * adopt / accept, and returns a [SecureChannel] bound to the active
 * transport. The returned channel shares SecureMessage sequence state
 * with [oldChannel].
 */
internal object BandwidthUpgradeOrchestrator {
    /**
     * Blocking server-side upgrade: offer, wait for the peer to connect to
     * the prepared medium, then swap. Used on bootstraps that cannot carry
     * the sharing negotiation while the upgrade is pending (BLE GATT /
     * L2CAP). Wi-Fi LAN sessions use the split [offerServerUpgrade] /
     * [ServerUpgradeOffer.launchAccept] / [completeOfferedUpgrade] /
     * [ServerUpgradeOffer.abandon] steps from [InboundConnectionDriver] so
     * the prior channel keeps being serviced while the peer decides whether
     * to take the offer (#288).
     */
    @Suppress("LongParameterList") // Mirrors the client-side twin; the timeout is test-injected.
    suspend fun runServerUpgradeIfAvailable(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        mediumRegistry: MediumRegistry,
        peerSupportedMediums: Set<Medium>,
        peerEndpointId: String,
        logger: (String) -> Unit,
        acceptTimeoutMillis: Long = UPGRADE_TIMEOUT_MILLIS,
    ): ActiveTransportChannel {
        val offer =
            offerServerUpgrade(
                oldChannel = oldChannel,
                currentMedium = currentMedium,
                mediumRegistry = mediumRegistry,
                peerSupportedMediums = peerSupportedMediums,
                logger = logger,
            ) ?: return ActiveTransportChannel(oldChannel, currentMedium)

        // The deadline is enforced here rather than trusted to the provider:
        // a provider parked in a blocking accept() syscall does not observe
        // coroutine cancellation, so on timeout the listener is closed via
        // cancelPendingUpgrade() BEFORE the accept coroutine is joined.
        // Without that ordering the 30 s timeout silently never fired on
        // real devices and the inbound connection hung forever (#288).
        val transport =
            coroutineScope {
                val accept = offer.launchAccept(this, logger)
                withTimeoutOrNull(acceptTimeoutMillis) { accept.await() }.also { accepted ->
                    if (accepted == null) {
                        offer.abandon(
                            oldChannel = oldChannel,
                            reason = "server accept timed out/failed for ${offer.medium}",
                            notifyPeer = true,
                            logger = logger,
                            pendingAccept = accept,
                        )
                    }
                }
            }
        return if (transport == null) {
            ActiveTransportChannel(oldChannel, currentMedium)
        } else {
            completeOfferedUpgrade(
                oldChannel = oldChannel,
                currentMedium = currentMedium,
                offer = offer,
                transport = transport,
                peerEndpointId = peerEndpointId,
                logger = logger,
            )
        }
    }

    /**
     * Prepare the best upgrade medium shared with the peer and put the
     * `UPGRADE_PATH_AVAILABLE` offer on [oldChannel]. Returns `null` (and
     * sends nothing) when there is nothing to upgrade to; the caller then
     * simply stays on [currentMedium].
     */
    @Suppress("ReturnCount")
    suspend fun offerServerUpgrade(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        mediumRegistry: MediumRegistry,
        peerSupportedMediums: Set<Medium>,
        logger: (String) -> Unit,
    ): ServerUpgradeOffer? {
        val selection =
            mediumRegistry.prepareBestUpgradeForCurrentTransport(
                peerSupported = peerSupportedMediums,
                currentMedium = currentMedium,
            )
        if (selection !is PreparedUpgradeSelection.Upgrade) {
            logger("medium-upgrade: server staying on current transport selection=$selection")
            return null
        }

        val credentials = selection.credentials
        val provider = mediumRegistry.providerFor(credentials.medium)
        if (provider == null) {
            logger("medium-upgrade: no provider for prepared medium ${credentials.medium}")
            return null
        }

        logger("medium-upgrade: server offering ${credentials.medium}")
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradePathAvailable(credentials))
        return ServerUpgradeOffer(credentials, provider)
    }

    /**
     * Run the raw-introduction / last-write / safe-to-close swap onto an
     * accepted [transport]. On failure hands the prior channel back, with
     * [ActiveTransportChannel.fallbackChannelUsable] cleared when the peer
     * had already committed to tearing that channel down.
     */
    @Suppress("LongParameterList")
    suspend fun completeOfferedUpgrade(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        offer: ServerUpgradeOffer,
        transport: UpgradedTransport,
        peerEndpointId: String,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        var priorChannelTeardownBegan = false
        return runCatching {
            completeServerUpgrade(
                oldChannel = oldChannel,
                transport = transport,
                credentials = offer.credentials,
                peerEndpointId = peerEndpointId,
                onPriorChannelTeardown = { priorChannelTeardownBegan = true },
                logger = logger,
            )
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            logger(
                "medium-upgrade: server failed ${offer.medium}: " +
                    (failure.message ?: failure::class.simpleName),
            )
            transport.close()
            offer.provider.cancelPendingUpgrade()
            // See the client-side twin above: once the CLIENT_INTRODUCTION_ACK
            // went out, the peer proceeds with its own prior-channel teardown
            // (its LAST_WRITE follows the ack immediately), so the prior
            // channel is no longer a safe place to keep the session.
            ActiveTransportChannel(
                channel = oldChannel,
                medium = currentMedium,
                fallbackChannelUsable = !priorChannelTeardownBegan,
            )
        }
    }

    private suspend fun completeServerUpgrade(
        oldChannel: SecureChannel,
        transport: UpgradedTransport,
        credentials: UpgradePathCredentials,
        peerEndpointId: String,
        onPriorChannelTeardown: () -> Unit,
        logger: (String) -> Unit,
    ): ActiveTransportChannel {
        val bufferedFrames = mutableListOf<OfflineFrame>()
        val newFramedConnection = transport.asFramedConnection()
        val clientIntro =
            receiveRawUpgradeFrame(
                framedConnection = newFramedConnection,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION,
                stage = "server raw client introduction",
            )
        validateServerClientIntroduction(
            intro = clientIntro,
            peerEndpointId = peerEndpointId,
        )
        // The ack is the peer's cue to begin tearing down the prior channel
        // (stock GMS clients send their LAST_WRITE right after receiving it),
        // so fallback safety ends here even though our own LAST_WRITE goes
        // out a few lines later.
        onPriorChannelTeardown()
        newFramedConnection.sendFrame(BandwidthUpgradeFrames.clientIntroductionAck().toByteArray())
        logger("medium-upgrade: server sent raw CLIENT_INTRODUCTION_ACK")
        val newChannel = oldChannel.withTransport(newFramedConnection)
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
        val reader = DualChannelFrameReader(oldChannel, newChannel, logger)
        receiveServerPeerLastWrite(
            reader = reader,
            bufferedFrames = bufferedFrames,
            logger = logger,
        )
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
        logger("medium-upgrade: server sent SAFE_TO_CLOSE_PRIOR_CHANNEL on prior channel")
        val consumedPeerSafe =
            drainServerPostSafeFrames(
                reader = reader,
                bufferedFrames = bufferedFrames,
                logger = logger,
            )
        closePriorChannelAfterUpgrade(oldChannel, consumedPeerSafe, logger)
        logger("medium-upgrade: server completed ${credentials.medium}")
        return ActiveTransportChannel(
            channel = newChannel,
            medium = credentials.medium,
            bufferedFrames = bufferedFrames,
            wifiFrequencyMhz = credentials.wifiDirectFrequencyMhzOrNull(),
        )
    }

    @Suppress("ReturnCount", "LongParameterList") // Mirrors the server twin; the STA hint is optional.
    suspend fun runClientUpgradeFromOffer(
        oldChannel: SecureChannel,
        currentMedium: Medium,
        offer: OfflineFrame,
        mediumRegistry: MediumRegistry,
        endpointId: String,
        logger: (String) -> Unit,
        staFrequencyMhz: Int = BandwidthUpgradeFrames.STA_FREQUENCY_NOT_SET,
    ): ActiveTransportChannel {
        val credentials =
            decodeOfferCredentials(offer) ?: run {
                logger("medium-upgrade: client received malformed upgrade offer")
                return ActiveTransportChannel(oldChannel, currentMedium)
            }
        val expectsClientIntroductionAck =
            offer.v1.bandwidthUpgradeNegotiation.upgradePathInfo.supportsClientIntroductionAck
        // google/nearby clients disable encryption on the upgraded channel
        // when the offer allows it and the strategy is P2P_POINT_TO_POINT,
        // which every Quick Share session is. GMS 26.41+ servers apply the
        // offer's flag even when the CLIENT_INTRODUCTION says false, so a
        // client that keeps SecureMessage framing ends up talking past a
        // plaintext peer: both sides fail to parse each other's frames.
        val disableEncryption =
            offer.v1.bandwidthUpgradeNegotiation.upgradePathInfo.supportsDisablingEncryption
        if (oldChannel.encryptionDisabled) {
            // A second upgrade from an already-plaintext channel has no
            // sequence-numbered prior stream to merge against; decline it
            // before anything is torn down and stay where we are.
            logger("medium-upgrade: client declined ${credentials.medium} offer on a plaintext channel")
            oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium))
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

        var priorChannelTeardownBegan = false
        return runCatching {
            completeClientUpgrade(
                oldChannel = oldChannel,
                transport = transport,
                credentials = credentials,
                endpointId = endpointId,
                expectsClientIntroductionAck = expectsClientIntroductionAck,
                disableEncryption = disableEncryption,
                onPriorChannelTeardown = { priorChannelTeardownBegan = true },
                logger = logger,
                staFrequencyMhz = staFrequencyMhz,
            )
        }.getOrElse { failure ->
            logger(
                "medium-upgrade: client failed ${credentials.medium}: " +
                    (failure.message ?: failure::class.simpleName),
            )
            transport.close()
            provider.cancelPendingUpgrade()
            // Failures before LAST_WRITE_TO_PRIOR_CHANNEL went out (e.g. the
            // new transport died during the raw introduction) leave the prior
            // channel intact and usable. Once the teardown began, the peer may
            // no longer read anything we write there (and the channel may
            // already be closed), so callers that would otherwise fall back to
            // it must treat the failure as terminal.
            ActiveTransportChannel(
                channel = oldChannel,
                medium = currentMedium,
                fallbackChannelUsable = !priorChannelTeardownBegan,
            )
        }
    }

    @Suppress("LongParameterList")
    private suspend fun completeClientUpgrade(
        oldChannel: SecureChannel,
        transport: UpgradedTransport,
        credentials: UpgradePathCredentials,
        endpointId: String,
        expectsClientIntroductionAck: Boolean,
        disableEncryption: Boolean,
        onPriorChannelTeardown: () -> Unit,
        logger: (String) -> Unit,
        staFrequencyMhz: Int,
    ): ActiveTransportChannel {
        val bufferedFrames = mutableListOf<OfflineFrame>()
        val newFramedConnection = transport.asFramedConnection()
        newFramedConnection.sendFrame(
            BandwidthUpgradeFrames
                .clientIntroduction(
                    endpointId = endpointId,
                    supportsDisablingEncryption = disableEncryption,
                ).toByteArray(),
        )
        // Fallback safety ends once the CLIENT_INTRODUCTION is on the wire:
        // a stock GMS server starts sending its own LAST_WRITE on the prior
        // channel as soon as the introduction is delivered, without waiting
        // for ours (#260). Failures before this write (dead adopted
        // transport) still hand the prior channel back intact.
        onPriorChannelTeardown()
        if (expectsClientIntroductionAck) {
            receiveRawUpgradeFrame(
                framedConnection = newFramedConnection,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                stage = "client raw introduction ack",
            )
            logger("medium-upgrade: client received raw CLIENT_INTRODUCTION_ACK")
        }
        val newChannel = oldChannel.withTransport(newFramedConnection, encryptionDisabled = disableEncryption)
        val reader = DualChannelFrameReader(oldChannel, newChannel, logger)
        exchangeClientPriorChannelClose(
            oldChannel = oldChannel,
            reader = reader,
            bufferedFrames = bufferedFrames,
            logger = logger,
            staFrequencyMhz = staFrequencyMhz,
        )
        reader.drainAvailableFrames(
            stage = "client post-safe-to-close",
            applicationFrames = bufferedFrames,
        )
        logger(
            "medium-upgrade: client completed ${credentials.medium} " +
                "encryption=${if (disableEncryption) "plaintext" else "encrypted"}",
        )
        return ActiveTransportChannel(
            channel = newChannel,
            medium = credentials.medium,
            bufferedFrames = bufferedFrames,
            wifiFrequencyMhz = credentials.wifiDirectFrequencyMhzOrNull(),
        )
    }

    private suspend fun exchangeClientPriorChannelClose(
        oldChannel: SecureChannel,
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
        staFrequencyMhz: Int,
    ) {
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
        receiveAndCheckUpgradeFrame(
            reader = reader,
            expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            stage = "client peer last-write",
            applicationFrames = bufferedFrames,
            logger = logger,
        )
        oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel(staFrequencyMhz))
        receiveAndCheckUpgradeFrame(
            reader = reader,
            expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
            stage = "client peer safe-to-close",
            applicationFrames = bufferedFrames,
            logger = logger,
        )
        closePriorChannelAfterUpgrade(oldChannel, consumedPeerSafe = true, logger = logger)
    }

    suspend fun receiveOfferProbe(
        channel: SecureChannel,
        timeoutMillis: Long = OFFER_WAIT_TIMEOUT_MILLIS,
    ): UpgradeOfferProbe =
        withTimeoutOrNull(timeoutMillis) {
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

    private suspend fun receiveServerPeerLastWrite(
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ) {
        val peerLast =
            receiveExpectedUpgradeFrame(
                reader = reader,
                expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                stage = "server peer last-write",
                applicationFrames = bufferedFrames,
                logger = logger,
            )
        checkUpgradeEvent(
            frame = peerLast,
            expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            stage = "server peer last-write",
        )
    }

    private suspend fun drainServerPostSafeFrames(
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ): Boolean {
        val consumedPeerSafe =
            withTimeoutOrNull(SERVER_PEER_SAFE_DRAIN_MILLIS) {
                receiveOptionalPeerSafeToClose(
                    reader = reader,
                    bufferedFrames = bufferedFrames,
                    logger = logger,
                )
            } == true
        logger(
            if (consumedPeerSafe) {
                "medium-upgrade: server consumed peer SAFE_TO_CLOSE on prior channel"
            } else {
                "medium-upgrade: server treating peer LAST_WRITE as sufficient; peer SAFE_TO_CLOSE omitted"
            },
        )
        if (!consumedPeerSafe) {
            reader.drainAvailableFrames(
                stage = "server post-safe-to-close",
                applicationFrames = bufferedFrames,
            )
        }
        return consumedPeerSafe
    }

    private suspend fun receiveOptionalPeerSafeToClose(
        reader: DualChannelFrameReader,
        bufferedFrames: MutableList<OfflineFrame>,
        logger: (String) -> Unit,
    ): Boolean {
        while (true) {
            val frame = reader.receiveNextFrame("server optional peer safe-to-close")
            when {
                frame.isBandwidthUpgradeEvent(
                    BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                ) -> return true
                frame.isBandwidthUpgradeNegotiation() -> {
                    logger(
                        "medium-upgrade: ignored ${frame.describeUpgradeEvent()} " +
                            "while draining optional peer SAFE_TO_CLOSE",
                    )
                }
                else -> {
                    logger(
                        "medium-upgrade: buffered ${frame.describeFrameType()} " +
                            "while draining optional peer SAFE_TO_CLOSE",
                    )
                    bufferedFrames += frame
                }
            }
        }
    }

    private fun decodeOfferCredentials(frame: OfflineFrame): UpgradePathCredentials? {
        if (!frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)) {
            return null
        }
        return BandwidthUpgradeFrames.decodeCredentials(frame.v1.bandwidthUpgradeNegotiation.upgradePathInfo)
    }

    private suspend fun receiveRawUpgradeFrame(
        framedConnection: FramedConnection,
        expected: BandwidthUpgradeNegotiationFrame.EventType,
        stage: String,
    ): OfflineFrame {
        val frame =
            withTimeoutOrNull(UPGRADE_TIMEOUT_MILLIS) {
                while (true) {
                    if (framedConnection.hasBufferedInput()) {
                        return@withTimeoutOrNull parseRawOfflineFrame(framedConnection, stage)
                    }
                    delay(DUAL_CHANNEL_POLL_DELAY_MILLIS)
                }
                error("unreachable")
            } ?: error("Timed out waiting for $stage")
        checkUpgradeEvent(frame = frame, expected = expected, stage = stage)
        return frame
    }

    private suspend fun parseRawOfflineFrame(
        framedConnection: FramedConnection,
        stage: String,
    ): OfflineFrame =
        try {
            OfflineFrame.parseFrom(framedConnection.receiveFrame())
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            error("Malformed raw OfflineFrame during $stage: ${e.message}")
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

    private suspend fun closePriorChannelAfterUpgrade(
        oldChannel: SecureChannel,
        consumedPeerSafe: Boolean,
        logger: (String) -> Unit,
    ) {
        if (consumedPeerSafe) {
            runCatching { oldChannel.sendRawOfflineFrame(OfflineFrames.disconnection()) }
                .onSuccess { logger("medium-upgrade: sent raw prior-channel Disconnection") }
                .onFailure {
                    logger(
                        "medium-upgrade: failed raw prior-channel Disconnection: " +
                            (it.message ?: it::class.simpleName),
                    )
                }
            drainRawPriorChannelDisconnect(oldChannel, logger)
        }
        runCatching { oldChannel.close() }
    }

    private suspend fun drainRawPriorChannelDisconnect(
        oldChannel: SecureChannel,
        logger: (String) -> Unit,
    ) {
        withTimeoutOrNull(PRIOR_CHANNEL_DISCONNECT_DRAIN_MILLIS) {
            while (true) {
                if (oldChannel.hasBufferedInput()) {
                    runCatching { oldChannel.receiveRawOfflineFrame() }
                        .onSuccess {
                            logger("medium-upgrade: drained raw prior-channel frame after safe-to-close")
                        }.onFailure {
                            logger(
                                "medium-upgrade: ignored prior-channel close drain failure: " +
                                    (it.message ?: it::class.simpleName),
                            )
                        }
                    return@withTimeoutOrNull
                }
                delay(DUAL_CHANNEL_POLL_DELAY_MILLIS)
            }
        }
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
            if (v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
                "${v1.type}(${v1.bandwidthUpgradeNegotiation.eventType})"
            } else if (v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY) {
                "${v1.type}(request=${v1.bandwidthUpgradeRetry.isRequest})"
            } else {
                v1.type.toString()
            }
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
        private val upgradedSource = FrameSource("upgraded", newChannel)
        private val sources =
            listOf(
                FrameSource("prior", oldChannel),
                upgradedSource,
            )
        private val pendingFrames: TreeMap<Int, SequencedRead> = TreeMap()

        // A plaintext upgraded channel carries no sequence numbers, so its
        // frames cannot be merged with the prior channel by sequence. The
        // peer only writes there after it switched writers, i.e. after its
        // LAST_WRITE_TO_PRIOR_CHANNEL on the prior channel, so they queue in
        // arrival order and are released once that LAST_WRITE is delivered
        // (google/nearby reads the prior channel up to LAST_WRITE first).
        private val plaintextUpgraded = newChannel.encryptionDisabled
        private val plaintextUpgradedFrames = ArrayDeque<OfflineFrame>()
        private var priorLastWriteDelivered = false
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
            if (plaintextUpgraded && source === upgradedSource) {
                val frame = source.channel.receivePlaintextOfflineFrame()
                plaintextUpgradedFrames.addLast(frame)
                logger("medium-upgrade: decoded ${frame.describeFrameType()} plaintext from ${source.name}")
                return
            }
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
            val next = pendingFrames.remove(nextSequenceNumber) ?: return deliverNextPlaintextFrame()
            val frame = next.source.channel.acceptSequencedOfflineFrame(next.sequenced)
            logger(
                "medium-upgrade: delivered ${frame.describeFrameType()} " +
                    "seq=$nextSequenceNumber from ${next.source.name}",
            )
            nextSequenceNumber += 1
            if (frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL)) {
                priorLastWriteDelivered = true
            }
            return frame
        }

        private fun deliverNextPlaintextFrame(): OfflineFrame? =
            plaintextUpgradedFrames
                .takeIf { priorLastWriteDelivered }
                ?.removeFirstOrNull()
                ?.also { frame ->
                    logger(
                        "medium-upgrade: delivered ${frame.describeFrameType()} " +
                            "plaintext from ${upgradedSource.name}",
                    )
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

    internal const val UPGRADE_TIMEOUT_MILLIS: Long = 30_000L
    internal const val OFFER_WAIT_TIMEOUT_MILLIS: Long = 1_500L
    private const val SERVER_PEER_SAFE_DRAIN_MILLIS: Long = 500L
    private const val PRIOR_CHANNEL_DISCONNECT_DRAIN_MILLIS: Long = 200L
    private const val DUAL_CHANNEL_DRAIN_IDLE_ATTEMPTS: Int = 25
    private const val DUAL_CHANNEL_POLL_DELAY_MILLIS: Long = 10L
}

/**
 * An `UPGRADE_PATH_AVAILABLE` offer that is on the wire and whose
 * [provider] is listening for the peer. Settled by exactly one of
 * [BandwidthUpgradeOrchestrator.completeOfferedUpgrade] or [abandon].
 */
internal class ServerUpgradeOffer(
    val credentials: UpgradePathCredentials,
    val provider: MediumProvider,
) {
    val medium: Medium
        get() = credentials.medium

    /**
     * Start accepting the peer on the offered medium without waiting for
     * it. The caller polls [Deferred.isCompleted] (or awaits) and MUST
     * settle the job through [abandon] when it stops caring, so a listener
     * parked in `accept()` is closed rather than leaked. Provider failures
     * are folded into `null` so a completed-with-null job means "no
     * transport"; only cancellation escapes.
     */
    fun launchAccept(
        scope: CoroutineScope,
        logger: (String) -> Unit,
    ): Deferred<UpgradedTransport?> =
        scope.async {
            try {
                provider.acceptUpgrade()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger(
                    "medium-upgrade: server accept threw for $medium: " +
                        (e.message ?: e::class.simpleName),
                )
                null
            }
        }

    /**
     * Give up on the pending offer: release the provider's listener and,
     * when [notifyPeer] is set, tell the peer the offered path failed so a
     * stock client stops trying to reach it. [pendingAccept] (when given)
     * is joined after the listener is closed so the join cannot hang on a
     * blocking `accept()`.
     */
    suspend fun abandon(
        oldChannel: SecureChannel,
        reason: String,
        notifyPeer: Boolean,
        logger: (String) -> Unit,
        pendingAccept: Deferred<UpgradedTransport?>? = null,
    ) {
        logger("medium-upgrade: $reason")
        provider.cancelPendingUpgrade()
        pendingAccept?.cancelAndJoin()
        if (notifyPeer) {
            runCatching { oldChannel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(medium)) }
        }
    }
}

internal data class ActiveTransportChannel(
    val channel: SecureChannel,
    val medium: Medium,
    val bufferedFrames: List<OfflineFrame> = emptyList(),
    val wifiFrequencyMhz: Int? = null,
    /**
     * False only when this is a failure result whose [channel] is the prior
     * channel AND the failed upgrade had already passed the point where the
     * peer commits to tearing the prior channel down — continuing the session
     * on it would write into a socket the peer stopped reading. True for
     * every other result, including the pre-commitment failure paths that
     * hand the prior channel back intact.
     *
     * The commitment boundary is peer-behavior-driven, not our-writes-driven
     * (#260): the client path marks it once its CLIENT_INTRODUCTION is on the
     * wire (a stock GMS server LAST_WRITEs the prior channel as soon as the
     * introduction is delivered), and the server path marks it once its
     * CLIENT_INTRODUCTION_ACK goes out (a stock GMS client LAST_WRITEs right
     * after receiving the ack). Both
     * [BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer] and
     * [BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable] maintain it.
     */
    val fallbackChannelUsable: Boolean = true,
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
