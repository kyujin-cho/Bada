/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import dev.bluehouse.bada.protocol.crypto.D2DKeyDerivation
import dev.bluehouse.bada.protocol.crypto.D2DRole
import dev.bluehouse.bada.protocol.crypto.D2DSessionKeys
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumLadder
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.transport.FramedConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections

/**
 * Client-side bandwidth upgrades whose offer sets
 * `supports_disabling_encryption`. GMS 26.41+ group owners switch the
 * upgraded channel to plaintext from their own offer even when the
 * CLIENT_INTRODUCTION says false, so the client has to follow the offer.
 */
class PlaintextUpgradeChannelTest {
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
    }

    @Test
    fun `client follows an offer that disables encryption and runs the upgraded channel in plaintext`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val link = UpgradeLink()
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val earlyUpgradedFrame = keepAliveFrame(ack = false)
                val lateUpgradedFrame = keepAliveFrame(ack = true)

                coroutineScope {
                    // Models a GMS 26.41 group owner: it switches the endpoint to
                    // the plaintext Wi-Fi Direct channel right after the ack, so an
                    // application frame can land there before its LAST_WRITE on
                    // the prior channel.
                    val peer =
                        async {
                            val intro = link.newServerFramed.receiveRawOfflineFrame()
                            intro.assertUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
                            assertThat(
                                intro.v1.bandwidthUpgradeNegotiation.clientIntroduction.supportsDisablingEncryption,
                            ).isTrue()
                            link.newServerFramed.sendFrame(BandwidthUpgradeFrames.clientIntroductionAck().toByteArray())
                            link.newServerFramed.sendFrame(earlyUpgradedFrame.toByteArray())
                            link.answerPriorChannelClose()
                            // Everything the client writes on the upgraded channel
                            // from here on must be a bare OfflineFrame.
                            val fromClient = link.newServerFramed.receiveRawOfflineFrame()
                            link.newServerFramed.sendFrame(lateUpgradedFrame.toByteArray())
                            fromClient
                        }

                    val result = link.runClientUpgrade(upgradeOfferDisablingEncryption(), logs)

                    assertThat(result.medium).isEqualTo(Medium.WIFI_DIRECT)
                    assertThat(result.channel.encryptionDisabled).isTrue()
                    assertThat(result.bufferedFrames).containsExactly(earlyUpgradedFrame)

                    val sendSeqBefore = result.channel.nextSendSequenceNumber
                    result.channel.sendOfflineFrame(keepAliveFrame(ack = false))
                    assertThat(peer.await()).isEqualTo(keepAliveFrame(ack = false))
                    assertThat(result.channel.receiveOfflineFrame()).isEqualTo(lateUpgradedFrame)
                    assertThat(result.channel.nextSendSequenceNumber).isEqualTo(sendSeqBefore)
                }

                assertThat(logs.joinToString("\n")).contains("client completed WIFI_DIRECT encryption=plaintext")
            }
        }

    @Test
    fun `client keeps the upgraded channel encrypted when the offer does not allow disabling`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val link = UpgradeLink()
                val newServerChannel = link.oldServerChannel.withTransport(link.newServerFramed)
                val logs = Collections.synchronizedList(mutableListOf<String>())

                coroutineScope {
                    val peer =
                        async {
                            val intro = link.newServerFramed.receiveRawOfflineFrame()
                            assertThat(
                                intro.v1.bandwidthUpgradeNegotiation.clientIntroduction
                                    .hasSupportsDisablingEncryption(),
                            ).isFalse()
                            link.newServerFramed.sendFrame(BandwidthUpgradeFrames.clientIntroductionAck().toByteArray())
                            link.answerPriorChannelClose()
                            newServerChannel.receiveOfflineFrame()
                        }

                    val result =
                        link.runClientUpgrade(
                            BandwidthUpgradeFrames.upgradePathAvailable(wifiDirectCredentials()),
                            logs,
                        )

                    assertThat(result.medium).isEqualTo(Medium.WIFI_DIRECT)
                    assertThat(result.channel.encryptionDisabled).isFalse()
                    result.channel.sendOfflineFrame(keepAliveFrame(ack = false))
                    assertThat(peer.await()).isEqualTo(keepAliveFrame(ack = false))
                }

                assertThat(logs.joinToString("\n")).contains("client completed WIFI_DIRECT encryption=encrypted")
            }
        }

    @Test
    fun `client declines a second upgrade offered over a plaintext channel`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (plainClientSocket, plainServerSocket) = connectedSocketPair()
                val plaintextChannel =
                    SecureChannel(
                        FramedConnection(connectedSocketPair().first),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    ).withTransport(FramedConnection(plainClientSocket), encryptionDisabled = true)
                val logs = Collections.synchronizedList(mutableListOf<String>())

                val result =
                    BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                        oldChannel = plaintextChannel,
                        currentMedium = Medium.WIFI_DIRECT,
                        offer = upgradeOfferDisablingEncryption(),
                        mediumRegistry = wifiDirectRegistry(connectedSocketPair().first),
                        endpointId = ENDPOINT_ID,
                        logger = logs::add,
                    )

                assertThat(result.channel).isSameInstanceAs(plaintextChannel)
                assertThat(result.fallbackChannelUsable).isTrue()
                FramedConnection(plainServerSocket).receiveRawOfflineFrame().assertUpgradeEvent(
                    BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE,
                )
                assertThat(logs.joinToString("\n")).contains("declined WIFI_DIRECT offer on a plaintext channel")
            }
        }

    /** One encrypted prior channel plus the raw socket the upgrade adopts. */
    private inner class UpgradeLink {
        private val oldSockets = connectedSocketPair()
        private val newSockets = connectedSocketPair()
        val oldClientChannel =
            SecureChannel(FramedConnection(oldSockets.first), freshSessionKeys(D2DRole.CLIENT), SecureRandom())
        val oldServerChannel =
            SecureChannel(FramedConnection(oldSockets.second), freshSessionKeys(D2DRole.SERVER), SecureRandom())
        val newServerFramed = FramedConnection(newSockets.second)

        suspend fun runClientUpgrade(
            offer: OfflineFrame,
            logs: MutableList<String>,
        ): ActiveTransportChannel =
            BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                oldChannel = oldClientChannel,
                currentMedium = Medium.BLUETOOTH,
                offer = offer,
                mediumRegistry = wifiDirectRegistry(newSockets.first),
                endpointId = ENDPOINT_ID,
                logger = logs::add,
            )

        /** The GMS side of the prior-channel close: LAST_WRITE, then SAFE_TO_CLOSE. */
        suspend fun answerPriorChannelClose() {
            oldServerChannel.receiveOfflineFrame().assertUpgradeEvent(
                BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
            )
            oldServerChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
            oldServerChannel.receiveOfflineFrame().assertUpgradeEvent(
                BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
            )
            oldServerChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
        }
    }

    private fun wifiDirectRegistry(socket: Socket): MediumRegistry =
        MediumRegistry(
            providers =
                listOf(
                    object : MediumProvider {
                        override val medium: Medium = Medium.WIFI_DIRECT

                        override fun isSupported(): Boolean = true

                        override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport =
                            UpgradedTransport.SocketBacked(Medium.WIFI_DIRECT, socket)
                    },
                ),
            ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
        )

    private fun wifiDirectCredentials(): UpgradePathCredentials =
        UpgradePathCredentials.WifiDirect(
            ipAddress = byteArrayOf(127, 0, 0, 1),
            port = 1,
            ssid = "DIRECT-xy-test",
            passphrase = "12345678",
            frequency = 5_180,
        )

    private fun upgradeOfferDisablingEncryption(): OfflineFrame {
        val offer = BandwidthUpgradeFrames.upgradePathAvailable(wifiDirectCredentials())
        val negotiation = offer.v1.bandwidthUpgradeNegotiation
        val pathInfo = negotiation.upgradePathInfo.toBuilder().setSupportsDisablingEncryption(true)
        return offer
            .toBuilder()
            .setV1(
                offer.v1
                    .toBuilder()
                    .setBandwidthUpgradeNegotiation(negotiation.toBuilder().setUpgradePathInfo(pathInfo)),
            ).build()
    }

    private fun connectedSocketPair(): Pair<Socket, Socket> {
        val listener = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val server = listener.accept()
        listener.close()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    private fun freshSessionKeys(role: D2DRole): D2DSessionKeys {
        val dhs = ByteArray(D2DKeyDerivation.KEY_SIZE) { (it + 0x40).toByte() }
        val clientInit = "plaintext-upgrade-client-init".toByteArray()
        val serverInit = "plaintext-upgrade-server-init".toByteArray()
        return D2DKeyDerivation.derive(dhs, clientInit, serverInit, role)
    }

    private fun keepAliveFrame(ack: Boolean): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().setAck(ack)),
            ).build()

    private suspend fun FramedConnection.receiveRawOfflineFrame(): OfflineFrame = OfflineFrame.parseFrom(receiveFrame())

    private fun OfflineFrame.assertUpgradeEvent(expected: BandwidthUpgradeNegotiationFrame.EventType) {
        assertThat(v1.type).isEqualTo(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
        assertThat(v1.bandwidthUpgradeNegotiation.eventType).isEqualTo(expected)
    }

    private companion object {
        const val ENDPOINT_ID = "ABCD"
        const val WALLCLOCK_TIMEOUT_MILLIS = 7_000L
    }
}
