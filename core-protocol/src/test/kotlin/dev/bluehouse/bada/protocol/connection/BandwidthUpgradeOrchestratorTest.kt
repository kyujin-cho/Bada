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

class BandwidthUpgradeOrchestratorTest {
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
    }

    @Test
    fun `server completes upgrade when peer omits safe-to-close and writes upgraded frame`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, oldServerSocket) = connectedSocketPair()
                val (newClientSocket, newServerSocket) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                val oldServerChannel =
                    SecureChannel(
                        FramedConnection(oldServerSocket),
                        freshSessionKeys(D2DRole.SERVER),
                        SecureRandom(),
                    )
                val newClientFramed = FramedConnection(newClientSocket)
                val registry =
                    MediumRegistry(
                        providers = listOf(serverProvider(newServerSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val upgradedFrame = keepAliveFrame()

                coroutineScope {
                    val server =
                        async {
                            BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable(
                                oldChannel = oldServerChannel,
                                currentMedium = Medium.BLE,
                                mediumRegistry = registry,
                                peerSupportedMediums = setOf(Medium.WIFI_DIRECT),
                                peerEndpointId = ENDPOINT_ID,
                                logger = logs::add,
                            )
                        }
                    val client =
                        async {
                            val offer = oldClientChannel.receiveOfflineFrame()
                            offer.assertUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
                            assertThat(
                                offer.v1.bandwidthUpgradeNegotiation.upgradePathInfo.supportsClientIntroductionAck,
                            ).isTrue()

                            newClientFramed.sendFrame(
                                BandwidthUpgradeFrames
                                    .clientIntroduction(ENDPOINT_ID)
                                    .toByteArray(),
                            )
                            newClientFramed
                                .receiveRawOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                                )
                            val newClientChannel = oldClientChannel.withTransport(newClientFramed)

                            oldClientChannel
                                .receiveOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                                )
                            oldClientChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
                            oldClientChannel
                                .receiveOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                                )
                            newClientChannel.sendOfflineFrame(upgradedFrame)
                        }

                    val activeTransport = server.await()
                    client.await()

                    assertThat(activeTransport.medium).isEqualTo(Medium.WIFI_DIRECT)
                    assertThat(activeTransport.bufferedFrames).containsExactly(upgradedFrame)
                }

                assertThat(logs.joinToString("\n")).contains("peer SAFE_TO_CLOSE omitted")
            }
        }

    @Test
    fun `server sends prior-channel safe-to-close after raw introduction ack`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, oldServerSocket) = connectedSocketPair()
                val (newClientSocket, newServerSocket) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                val oldServerChannel =
                    SecureChannel(
                        FramedConnection(oldServerSocket),
                        freshSessionKeys(D2DRole.SERVER),
                        SecureRandom(),
                    )
                val newClientFramed = FramedConnection(newClientSocket)
                val registry =
                    MediumRegistry(
                        providers = listOf(serverProvider(newServerSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())

                coroutineScope {
                    val server =
                        async {
                            BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable(
                                oldChannel = oldServerChannel,
                                currentMedium = Medium.BLE,
                                mediumRegistry = registry,
                                peerSupportedMediums = setOf(Medium.WIFI_DIRECT),
                                peerEndpointId = ENDPOINT_ID,
                                logger = logs::add,
                            )
                        }
                    val client =
                        async {
                            oldClientChannel
                                .receiveOfflineFrame()
                                .assertUpgradeEvent(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)

                            newClientFramed.sendFrame(
                                BandwidthUpgradeFrames
                                    .clientIntroduction(ENDPOINT_ID)
                                    .toByteArray(),
                            )
                            newClientFramed
                                .receiveRawOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                                )

                            oldClientChannel.sendOfflineFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel())
                            oldClientChannel
                                .receiveOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                                )
                            oldClientChannel
                                .receiveOfflineFrame()
                                .assertUpgradeEvent(
                                    BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,
                                )
                            oldClientChannel.sendOfflineFrame(BandwidthUpgradeFrames.safeToClosePriorChannel())
                        }

                    assertThat(server.await().medium).isEqualTo(Medium.WIFI_DIRECT)
                    client.await()
                }

                assertThat(logs.joinToString("\n")).contains("server consumed peer SAFE_TO_CLOSE on prior channel")
            }
        }

    @Test
    fun `client failure before prior-channel teardown keeps fallback channel usable`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, _) = connectedSocketPair()
                val (newClientSocket, _) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                // The adopted transport is already dead: the very first raw
                // CLIENT_INTRODUCTION write fails before the prior channel is
                // touched, so the failure must hand the prior channel back as
                // still usable.
                newClientSocket.close()
                val registry =
                    MediumRegistry(
                        providers = listOf(clientProvider(newClientSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())

                val result =
                    BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                        oldChannel = oldClientChannel,
                        currentMedium = Medium.BLUETOOTH,
                        offer = BandwidthUpgradeFrames.upgradePathAvailable(wifiDirectCredentials()),
                        mediumRegistry = registry,
                        endpointId = ENDPOINT_ID,
                        logger = logs::add,
                    )

                assertThat(result.channel).isSameInstanceAs(oldClientChannel)
                assertThat(result.medium).isEqualTo(Medium.BLUETOOTH)
                assertThat(result.fallbackChannelUsable).isTrue()
                assertThat(logs.joinToString("\n")).contains("client failed WIFI_DIRECT")
            }
        }

    @Test
    fun `client failure after prior-channel teardown marks fallback channel unusable`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, oldServerSocket) = connectedSocketPair()
                val (newClientSocket, newServerSocket) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                val oldServerChannel =
                    SecureChannel(
                        FramedConnection(oldServerSocket),
                        freshSessionKeys(D2DRole.SERVER),
                        SecureRandom(),
                    )
                val newServerFramed = FramedConnection(newServerSocket)
                val registry =
                    MediumRegistry(
                        providers = listOf(clientProvider(newClientSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())

                coroutineScope {
                    val peer =
                        async {
                            newServerFramed.receiveRawOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION,
                            )
                            newServerFramed.sendFrame(
                                BandwidthUpgradeFrames.clientIntroductionAck().toByteArray(),
                            )
                            oldServerChannel.receiveOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                            )
                            // Answer the client's LAST_WRITE with UPGRADE_FAILURE:
                            // the client has already stopped writing application
                            // frames to the prior channel, so this failure must
                            // NOT be treated as fall-back-able.
                            oldServerChannel.sendOfflineFrame(
                                BandwidthUpgradeFrames.upgradeFailure(Medium.WIFI_DIRECT),
                            )
                        }

                    val result =
                        BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                            oldChannel = oldClientChannel,
                            currentMedium = Medium.BLUETOOTH,
                            offer = BandwidthUpgradeFrames.upgradePathAvailable(wifiDirectCredentials()),
                            mediumRegistry = registry,
                            endpointId = ENDPOINT_ID,
                            logger = logs::add,
                        )
                    peer.await()

                    assertThat(result.channel).isSameInstanceAs(oldClientChannel)
                    assertThat(result.medium).isEqualTo(Medium.BLUETOOTH)
                    assertThat(result.fallbackChannelUsable).isFalse()
                }
            }
        }

    @Test
    fun `server failure before intro ack keeps fallback channel usable`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, oldServerSocket) = connectedSocketPair()
                val (newClientSocket, newServerSocket) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                val oldServerChannel =
                    SecureChannel(
                        FramedConnection(oldServerSocket),
                        freshSessionKeys(D2DRole.SERVER),
                        SecureRandom(),
                    )
                val newClientFramed = FramedConnection(newClientSocket)
                val registry =
                    MediumRegistry(
                        providers = listOf(serverProvider(newServerSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())

                coroutineScope {
                    val server =
                        async {
                            BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable(
                                oldChannel = oldServerChannel,
                                currentMedium = Medium.BLE,
                                mediumRegistry = registry,
                                peerSupportedMediums = setOf(Medium.WIFI_DIRECT),
                                peerEndpointId = ENDPOINT_ID,
                                logger = logs::add,
                            )
                        }
                    val client =
                        async {
                            oldClientChannel.receiveOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                            )
                            // A KEEP_ALIVE where the raw CLIENT_INTRODUCTION
                            // belongs fails the server BEFORE its intro-ack —
                            // the prior channel must come back usable.
                            newClientFramed.sendFrame(keepAliveFrame().toByteArray())
                        }

                    val result = server.await()
                    client.await()

                    assertThat(result.channel).isSameInstanceAs(oldServerChannel)
                    assertThat(result.medium).isEqualTo(Medium.BLE)
                    assertThat(result.fallbackChannelUsable).isTrue()
                    assertThat(logs.joinToString("\n")).contains("server failed WIFI_DIRECT")
                }
            }
        }

    @Test
    fun `server failure after intro ack marks fallback channel unusable`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MILLIS) {
                val (oldClientSocket, oldServerSocket) = connectedSocketPair()
                val (newClientSocket, newServerSocket) = connectedSocketPair()
                val oldClientChannel =
                    SecureChannel(
                        FramedConnection(oldClientSocket),
                        freshSessionKeys(D2DRole.CLIENT),
                        SecureRandom(),
                    )
                val oldServerChannel =
                    SecureChannel(
                        FramedConnection(oldServerSocket),
                        freshSessionKeys(D2DRole.SERVER),
                        SecureRandom(),
                    )
                val newClientFramed = FramedConnection(newClientSocket)
                val registry =
                    MediumRegistry(
                        providers = listOf(serverProvider(newServerSocket)),
                        ladder = MediumLadder(listOf(Medium.WIFI_DIRECT)),
                    )
                val logs = Collections.synchronizedList(mutableListOf<String>())

                coroutineScope {
                    val server =
                        async {
                            BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable(
                                oldChannel = oldServerChannel,
                                currentMedium = Medium.BLE,
                                mediumRegistry = registry,
                                peerSupportedMediums = setOf(Medium.WIFI_DIRECT),
                                peerEndpointId = ENDPOINT_ID,
                                logger = logs::add,
                            )
                        }
                    val client =
                        async {
                            oldClientChannel.receiveOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                            )
                            newClientFramed.sendFrame(
                                BandwidthUpgradeFrames.clientIntroduction(ENDPOINT_ID).toByteArray(),
                            )
                            newClientFramed.receiveRawOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK,
                            )
                            oldClientChannel.receiveOfflineFrame().assertUpgradeEvent(
                                BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,
                            )
                            // Answer the server's LAST_WRITE with UPGRADE_FAILURE:
                            // the intro-ack already went out, so the sender side
                            // has begun its own teardown — no fallback allowed.
                            oldClientChannel.sendOfflineFrame(
                                BandwidthUpgradeFrames.upgradeFailure(Medium.WIFI_DIRECT),
                            )
                        }

                    val result = server.await()
                    client.await()

                    assertThat(result.channel).isSameInstanceAs(oldServerChannel)
                    assertThat(result.medium).isEqualTo(Medium.BLE)
                    assertThat(result.fallbackChannelUsable).isFalse()
                }
            }
        }

    private fun clientProvider(socket: Socket): MediumProvider =
        object : MediumProvider {
            override val medium: Medium = Medium.WIFI_DIRECT

            override fun isSupported(): Boolean = true

            override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport =
                UpgradedTransport.SocketBacked(Medium.WIFI_DIRECT, socket)
        }

    private fun wifiDirectCredentials(): UpgradePathCredentials =
        UpgradePathCredentials.WifiDirect(
            ipAddress = byteArrayOf(127, 0, 0, 1),
            port = 1,
            ssid = "DIRECT-xy-test",
            passphrase = "12345678",
            frequency = 5_180,
        )

    private fun serverProvider(socket: Socket): MediumProvider =
        object : MediumProvider {
            override val medium: Medium = Medium.WIFI_DIRECT

            override fun isSupported(): Boolean = true

            override suspend fun prepareUpgrade(): UpgradePathCredentials =
                UpgradePathCredentials.Generic(Medium.WIFI_DIRECT)

            override suspend fun acceptUpgrade(): UpgradedTransport =
                UpgradedTransport.SocketBacked(Medium.WIFI_DIRECT, socket)
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
        val clientInit = "upgrade-orchestrator-client-init".toByteArray()
        val serverInit = "upgrade-orchestrator-server-init".toByteArray()
        return D2DKeyDerivation.derive(dhs, clientInit, serverInit, role)
    }

    private fun keepAliveFrame(): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().setAck(false)),
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
