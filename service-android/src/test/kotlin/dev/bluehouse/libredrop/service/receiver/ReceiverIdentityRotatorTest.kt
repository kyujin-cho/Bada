/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.discovery.AdvertiseHandle
import dev.bluehouse.libredrop.protocol.connection.CancelCause
import dev.bluehouse.libredrop.protocol.connection.InboundResult
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.medium.MediumRegistry
import dev.bluehouse.libredrop.protocol.payload.FileDestinationFactory
import dev.bluehouse.libredrop.protocol.payload.TempFileDestinationFactory
import dev.bluehouse.libredrop.protocol.server.TcpReceiverServer
import dev.bluehouse.libredrop.protocol.transport.ConnectedTransport
import dev.bluehouse.libredrop.protocol.transport.InitialControlServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.security.SecureRandom

class ReceiverIdentityRotatorTest {
    @BeforeEach
    fun setUp() {
        BleEndpointIdHolder.clear()
        EndpointIdentityHolder.snapshot.set(null)
    }

    @AfterEach
    fun tearDown() {
        BleEndpointIdHolder.clear()
        EndpointIdentityHolder.snapshot.set(null)
    }

    @Test
    fun `cancelled inbound result rotates identity to invalidate stale sender cache`() =
        runBlocking {
            val original = sampleEndpointInfo("Original")
            val updated = sampleEndpointInfo("Updated")
            val originalEndpointId = endpointId("AAAA")
            val advertiser = EndpointIdRecordingAdvertiser()
            val initialControlServer = EndpointIdRecordingInitialControlServer()
            val bleBroadcaster = EndpointIdRecordingBleBroadcaster()
            val session =
                startedAdvertisingSession(
                    endpointInfo = original,
                    endpointId = originalEndpointId,
                    advertiser = advertiser,
                    initialControlServer = initialControlServer,
                )
            val rotator =
                ReceiverIdentityRotator(
                    nextIdentityProvider = { updated },
                    bleBroadcasterFactory = { bleBroadcaster },
                )

            try {
                val rotated = rotator.rotateAfterResult(InboundResult.Cancelled(CancelCause.PEER), session)

                assertThat(rotated).isTrue()
                assertThat(EndpointIdentityHolder.snapshot.get()).isEqualTo(updated)
                val rotatedEndpointId = BleEndpointIdHolder.snapshot()
                assertThat(rotatedEndpointId).isNotNull()
                assertThat(rotatedEndpointId).isNotEqualTo(originalEndpointId)
                assertThat(advertiser.attempts.map { it.endpointInfo }).containsExactly(original, updated)
                assertThat(initialControlServer.startCalls.map { it.endpointInfo }).containsExactly(
                    original,
                    updated,
                )
                assertThat(bleBroadcaster.starts.map { it.endpointInfo }).containsExactly(updated)
            } finally {
                session.stop()
            }
        }

    @Test
    fun `completed inbound result republishes mDNS GATT and BLE with a staged endpoint id`() =
        runBlocking {
            val original = sampleEndpointInfo("Original")
            val updated = sampleEndpointInfo("Updated")
            val originalEndpointId = endpointId("AAAA")
            val advertiser = EndpointIdRecordingAdvertiser()
            val initialControlServer = EndpointIdRecordingInitialControlServer()
            val bleBroadcaster = EndpointIdRecordingBleBroadcaster()
            val session =
                startedAdvertisingSession(
                    endpointInfo = original,
                    endpointId = originalEndpointId,
                    advertiser = advertiser,
                    initialControlServer = initialControlServer,
                )
            val rotator =
                ReceiverIdentityRotator(
                    nextIdentityProvider = { updated },
                    bleBroadcasterFactory = { bleBroadcaster },
                )

            try {
                val rotated = rotator.rotateAfterResult(InboundResult.Completed(emptyList()), session)

                assertThat(rotated).isTrue()
                assertThat(EndpointIdentityHolder.snapshot.get()).isEqualTo(updated)
                val rotatedEndpointId = BleEndpointIdHolder.snapshot()
                assertThat(rotatedEndpointId).isNotNull()
                assertThat(rotatedEndpointId).isNotEqualTo(originalEndpointId)
                assertThat(advertiser.attempts.map { it.endpointInfo }).containsExactly(original, updated)
                assertThat(advertiser.attempts.map { it.endpointId }).containsExactly(
                    originalEndpointId,
                    rotatedEndpointId,
                )
                assertThat(initialControlServer.startCalls.map { it.endpointInfo }).containsExactly(
                    original,
                    updated,
                )
                assertThat(initialControlServer.startCalls.map { it.endpointId }).containsExactly(
                    originalEndpointId,
                    rotatedEndpointId,
                )
                assertThat(bleBroadcaster.starts.map { it.endpointInfo }).containsExactly(updated)
                assertThat(bleBroadcaster.starts.map { it.endpointId }).containsExactly(rotatedEndpointId)
            } finally {
                session.stop()
            }
        }

    @Test
    fun `completed inbound result restores previous identity when republish fails`() =
        runBlocking {
            val original = sampleEndpointInfo("Original")
            val updated = sampleEndpointInfo("Updated")
            val originalEndpointId = endpointId("AAAA")
            val advertiser = EndpointIdRecordingAdvertiser(failOnAttempt = 2)
            val bleBroadcaster = EndpointIdRecordingBleBroadcaster()
            val session = startedAdvertisingSession(original, originalEndpointId, advertiser)
            val rotator =
                ReceiverIdentityRotator(
                    nextIdentityProvider = { updated },
                    bleBroadcasterFactory = { bleBroadcaster },
                )

            try {
                val rotated = rotator.rotateAfterResult(InboundResult.Completed(emptyList()), session)

                assertThat(rotated).isFalse()
                assertThat(EndpointIdentityHolder.snapshot.get()).isEqualTo(original)
                assertThat(BleEndpointIdHolder.snapshot()).isEqualTo(originalEndpointId)
                assertThat(session.isAdvertising).isTrue()
                assertThat(advertiser.attempts.map { it.endpointInfo }).containsExactly(
                    original,
                    updated,
                    original,
                )
                assertThat(advertiser.attempts[1].endpointId).isNotEqualTo(originalEndpointId)
                assertThat(advertiser.attempts[1].succeeded).isFalse()
                assertThat(advertiser.attempts[2].endpointId).isEqualTo(originalEndpointId)
                assertThat(advertiser.attempts[2].succeeded).isTrue()
                assertThat(bleBroadcaster.starts.map { it.endpointInfo }).containsExactly(original)
                assertThat(bleBroadcaster.starts.map { it.endpointId }).containsExactly(originalEndpointId)
            } finally {
                session.stop()
            }
        }

    private suspend fun startedAdvertisingSession(
        endpointInfo: EndpointInfo,
        endpointId: ByteArray,
        advertiser: EndpointIdRecordingAdvertiser,
        initialControlServer: InitialControlServer = EndpointIdRecordingInitialControlServer(),
    ): ReceiverSession {
        EndpointIdentityHolder.snapshot.set(endpointInfo)
        BleEndpointIdHolder.restore(endpointId)
        val session =
            makeSession(
                advertiser = advertiser,
                endpointInfo = endpointInfo,
                advertiseGated = true,
                initialControlServers = listOf(initialControlServer),
            )
        session.start()
        session.publishAdvertisement()
        return session
    }

    private fun makeSession(
        advertiser: DiscoveryAdvertiser,
        endpointInfo: EndpointInfo,
        factoryProvider: () -> FileDestinationFactory = { TempFileDestinationFactory() },
        advertiseGated: Boolean = false,
        initialControlServers: List<InitialControlServer> = emptyList(),
    ): ReceiverSession =
        ReceiverSession(
            tcpServerFactory =
                object : TcpServerFactory {
                    override fun create(
                        scope: CoroutineScope,
                        factoryProvider: () -> FileDestinationFactory,
                        secureRandomProvider: () -> SecureRandom,
                        mediumRegistry: MediumRegistry,
                    ): TcpReceiverServer =
                        TcpReceiverServer(
                            parentScope = scope,
                            factoryProvider = factoryProvider,
                            secureRandomProvider = secureRandomProvider,
                            mediumRegistry = mediumRegistry,
                            bindAddress = InetAddress.getLoopbackAddress(),
                        )
                },
            advertiser = advertiser,
            factoryProvider = factoryProvider,
            endpointInfo = endpointInfo,
            advertiseGated = advertiseGated,
            initialControlServers = initialControlServers,
        )

    private fun sampleEndpointInfo(name: String): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = name,
            tlvRecords = emptyList(),
        )

    private fun endpointId(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private class EndpointIdRecordingAdvertiser(
        private val failOnAttempt: Int? = null,
    ) : DiscoveryAdvertiser {
        data class Attempt(
            val endpointInfo: EndpointInfo,
            val endpointId: ByteArray,
            val succeeded: Boolean,
        )

        val attempts: MutableList<Attempt> = mutableListOf()
        private var callCount: Int = 0

        override fun advertise(
            endpointInfo: EndpointInfo,
            port: Int,
        ): AdvertiseHandle {
            callCount += 1
            val endpointId = BleEndpointIdHolder.bytesFor()
            if (callCount == failOnAttempt) {
                attempts += Attempt(endpointInfo, endpointId, succeeded = false)
                throw java.io.IOException("simulated advertise failure")
            }
            attempts += Attempt(endpointInfo, endpointId, succeeded = true)
            return FakeAdvertiseHandle(port = port)
        }
    }

    private class EndpointIdRecordingInitialControlServer : InitialControlServer {
        data class StartCall(
            val endpointInfo: EndpointInfo,
            val endpointId: ByteArray,
        )

        val startCalls: MutableList<StartCall> = mutableListOf()
        private var active: Boolean = false

        override val isActive: Boolean
            get() = active

        override fun start(
            endpointInfo: EndpointInfo,
            acceptTransport: (ConnectedTransport) -> Unit,
        ): Boolean {
            startCalls += StartCall(endpointInfo, BleEndpointIdHolder.bytesFor())
            active = true
            return true
        }

        override fun stop() {
            active = false
        }
    }

    private class EndpointIdRecordingBleBroadcaster : BleVisibilityBroadcaster {
        data class StartCall(
            val endpointInfo: EndpointInfo?,
            val endpointId: ByteArray,
        )

        val starts: MutableList<StartCall> = mutableListOf()

        override fun start(): Boolean {
            starts += StartCall(EndpointIdentityHolder.snapshot.get(), BleEndpointIdHolder.bytesFor())
            return true
        }

        override fun stop() = Unit
    }

    private class FakeAdvertiseHandle(
        override val port: Int,
        override val instanceName: String = "libredrop-rotator-test",
    ) : AdvertiseHandle {
        @Volatile
        private var active: Boolean = true

        override val isActive: Boolean
            get() = active

        override fun close() {
            active = false
        }
    }
}
