/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.medium.asFramedConnection
import dev.bluehouse.bada.protocol.transport.FramedConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raw bandwidth-upgrade helper for receivers that offer a higher
 * bandwidth transport before UKEY2 has started.
 *
 * The normal [BandwidthUpgradeOrchestrator] runs after SecureChannel is
 * established. Stock Galaxy receivers can instead send
 * `UPGRADE_PATH_AVAILABLE` on the BLE bootstrap channel immediately
 * after `ConnectionRequest`; this helper accepts that raw offer and
 * returns the upgraded framed transport so UKEY2 can begin there.
 */
internal object PreUkey2BandwidthUpgrade {
    suspend fun receiveOfferProbe(
        framedConnection: FramedConnection,
        timeoutMillis: Long,
    ): UpgradeOfferProbe =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                if (framedConnection.hasBufferedInput()) {
                    val frame = parseRawOfflineFrame(framedConnection, "pre-UKEY2 upgrade probe")
                    return@withTimeoutOrNull if (
                        frame.isBandwidthUpgradeEvent(
                            BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                        )
                    ) {
                        UpgradeOfferProbe.Offer(frame)
                    } else {
                        UpgradeOfferProbe.Other(frame)
                    }
                }
                delay(POLL_DELAY_MILLIS)
            }
            error("unreachable")
        } ?: UpgradeOfferProbe.None

    @Suppress("ReturnCount")
    suspend fun runClientUpgradeFromOffer(
        oldTransport: FramedConnection,
        offer: OfflineFrame,
        mediumRegistry: MediumRegistry,
        endpointId: String,
        logger: (String) -> Unit,
    ): PreUkey2UpgradeResult {
        val credentials =
            decodeOfferCredentials(offer) ?: run {
                val reason = "Malformed pre-UKEY2 upgrade offer"
                logger("medium-upgrade: $reason")
                return PreUkey2UpgradeResult.Failed(reason)
            }
        val expectsClientIntroductionAck =
            offer.v1.bandwidthUpgradeNegotiation.upgradePathInfo.supportsClientIntroductionAck
        val provider = mediumRegistry.providerFor(credentials.medium)
        if (provider == null) {
            val reason = "No provider for pre-UKEY2 upgrade medium ${credentials.medium}"
            logger("medium-upgrade: $reason")
            oldTransport.sendFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium).toByteArray())
            return PreUkey2UpgradeResult.Failed(reason)
        }

        val transport =
            withTimeoutOrNull(UPGRADE_TIMEOUT_MILLIS) {
                provider.adoptUpgrade(credentials)
            }
        if (transport == null) {
            val reason = "Pre-UKEY2 adopt failed for ${credentials.medium}"
            logger("medium-upgrade: $reason")
            provider.cancelPendingUpgrade()
            oldTransport.sendFrame(BandwidthUpgradeFrames.upgradeFailure(credentials.medium).toByteArray())
            return PreUkey2UpgradeResult.Failed(reason)
        }

        return runCatching {
            completeClientUpgrade(
                oldTransport = oldTransport,
                transport = transport,
                credentials = credentials,
                endpointId = endpointId,
                expectsClientIntroductionAck = expectsClientIntroductionAck,
                logger = logger,
            )
        }.getOrElse { failure ->
            val reason =
                "Pre-UKEY2 upgrade to ${credentials.medium} failed: " +
                    (failure.message ?: failure::class.simpleName)
            logger("medium-upgrade: $reason")
            transport.close()
            provider.cancelPendingUpgrade()
            PreUkey2UpgradeResult.Failed(reason)
        }
    }

    private suspend fun completeClientUpgrade(
        oldTransport: FramedConnection,
        transport: UpgradedTransport,
        credentials: UpgradePathCredentials,
        endpointId: String,
        expectsClientIntroductionAck: Boolean,
        logger: (String) -> Unit,
    ): PreUkey2UpgradeResult.Ready {
        val newFramedConnection = transport.asFramedConnection()
        newFramedConnection.sendFrame(BandwidthUpgradeFrames.clientIntroduction(endpointId).toByteArray())
        if (expectsClientIntroductionAck) {
            receiveRawUpgradeFrame(
                framedConnection = newFramedConnection,
                expected = BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                stage = "pre-UKEY2 client raw introduction ack",
            )
            logger("medium-upgrade: pre-UKEY2 client received raw CLIENT_INTRODUCTION_ACK")
        }
        finishPriorChannel(oldTransport, logger)
        logger("medium-upgrade: pre-UKEY2 client completed ${credentials.medium}")
        return PreUkey2UpgradeResult.Ready(
            transport = newFramedConnection,
            medium = credentials.medium,
            wifiFrequencyMhz = credentials.wifiDirectFrequencyMhzOrNull(),
        )
    }

    private suspend fun finishPriorChannel(
        oldTransport: FramedConnection,
        logger: (String) -> Unit,
    ) {
        runCatching { oldTransport.sendFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel().toByteArray()) }
            .onFailure {
                logger(
                    "medium-upgrade: failed pre-UKEY2 LAST_WRITE on prior channel: " +
                        (it.message ?: it::class.simpleName),
                )
            }
        val sawPeerLastWrite =
            receiveOptionalRawUpgradeEvent(
                framedConnection = oldTransport,
                expected = BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                stage = "pre-UKEY2 peer last-write",
                logger = logger,
            )
        if (sawPeerLastWrite) {
            runCatching { oldTransport.sendFrame(BandwidthUpgradeFrames.safeToClosePriorChannel().toByteArray()) }
                .onFailure {
                    logger(
                        "medium-upgrade: failed pre-UKEY2 SAFE_TO_CLOSE on prior channel: " +
                            (it.message ?: it::class.simpleName),
                    )
                }
            receiveOptionalRawUpgradeEvent(
                framedConnection = oldTransport,
                expected = BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                stage = "pre-UKEY2 peer safe-to-close",
                logger = logger,
            )
        }
        runCatching { oldTransport.close() }
    }

    private suspend fun receiveOptionalRawUpgradeEvent(
        framedConnection: FramedConnection,
        expected: BandwidthUpgradeNegotiationFrame.EventType,
        stage: String,
        logger: (String) -> Unit,
    ): Boolean =
        withTimeoutOrNull(PRIOR_CHANNEL_DRAIN_MILLIS) {
            while (true) {
                if (framedConnection.hasBufferedInput()) {
                    val frame = parseRawOfflineFrame(framedConnection, stage)
                    if (frame.isBandwidthUpgradeEvent(expected)) {
                        return@withTimeoutOrNull true
                    }
                    logger(
                        "medium-upgrade: ignored ${frame.describeUpgradeEvent()} " +
                            "during $stage",
                    )
                }
                delay(POLL_DELAY_MILLIS)
            }
            error("unreachable")
        } == true

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
                    delay(POLL_DELAY_MILLIS)
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

    private fun decodeOfferCredentials(frame: OfflineFrame): UpgradePathCredentials? {
        if (!frame.isBandwidthUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)) {
            return null
        }
        return BandwidthUpgradeFrames.decodeCredentials(frame.v1.bandwidthUpgradeNegotiation.upgradePathInfo)
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

    private fun OfflineFrame.isBandwidthUpgradeEvent(expected: BandwidthUpgradeNegotiationFrame.EventType): Boolean =
        hasV1() &&
            v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
            v1.bandwidthUpgradeNegotiation.eventType == expected

    private const val UPGRADE_TIMEOUT_MILLIS: Long = 30_000L
    private const val PRIOR_CHANNEL_DRAIN_MILLIS: Long = 500L
    private const val POLL_DELAY_MILLIS: Long = 10L
}

internal sealed interface PreUkey2UpgradeResult {
    data class Ready(
        val transport: FramedConnection,
        val medium: Medium,
        val wifiFrequencyMhz: Int? = null,
    ) : PreUkey2UpgradeResult

    data class Failed(
        val reason: String,
    ) : PreUkey2UpgradeResult
}
