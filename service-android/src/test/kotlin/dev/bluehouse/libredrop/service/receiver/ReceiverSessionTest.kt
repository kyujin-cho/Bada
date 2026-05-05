/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.discovery.AdvertiseHandle
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.medium.Medium
import dev.bluehouse.libredrop.protocol.medium.MediumRegistry
import dev.bluehouse.libredrop.protocol.payload.FileDestinationFactory
import dev.bluehouse.libredrop.protocol.payload.TempFileDestinationFactory
import dev.bluehouse.libredrop.protocol.server.TcpReceiverServer
import dev.bluehouse.libredrop.protocol.transport.ConnectedTransport
import dev.bluehouse.libredrop.protocol.transport.InitialControlServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM unit tests for [ReceiverSession].
 *
 * The session under test is wired to:
 *
 *  - a real [TcpReceiverServer] (it lives in `:core-protocol` and is
 *    pure-JVM), bound on the loopback interface;
 *  - a fake [DiscoveryAdvertiser] that records every advertise call and
 *    yields a closable handle the test can interrogate;
 *  - a per-test [TempFileDestinationFactory] supplier so the production
 *    `MediaStoreDownloadsFactory` (which needs a real `ContentResolver`)
 *    is not needed.
 *
 * Phase 1 of this test also exercised a counting `MulticastLockController`;
 * after the #98 NsdManager migration the receiver no longer holds a
 * multicast lock at all so those assertions were removed.
 *
 * End-to-end pairing with `InboundConnection` over real sockets is
 * deferred to #28; this file deliberately stops at the lifecycle
 * surface so we don't spawn pending coroutines that need that test
 * scaffolding to terminate.
 */
class ReceiverSessionTest {
    @Test
    fun `start binds tcp listener`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    advertiser = advertiser,
                )

            val port = session.start()

            assertThat(port).isGreaterThan(0)
            assertThat(session.boundPort).isEqualTo(port)
            assertThat(advertiser.calls).hasSize(1)
            assertThat(advertiser.calls[0].port).isEqualTo(port)

            session.stop()
        }

    @Test
    fun `start advertises with the configured endpoint info`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val expected = sampleEndpointInfo(name = "Test-Pixel")
            val session =
                makeSession(
                    advertiser = advertiser,
                    endpointInfo = expected,
                )

            session.start()

            assertThat(advertiser.calls).hasSize(1)
            // The advertiser must see the exact identity the session was
            // configured with — peers depend on the salt+key tuple
            // matching the one the receiver uses for SecureMessage.
            assertThat(advertiser.calls[0].endpointInfo).isEqualTo(expected)

            session.stop()
        }

    @Test
    fun `stop closes the advertise handle`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    advertiser = advertiser,
                )

            session.start()
            session.stop()

            assertThat(advertiser.calls.map { it.handle.isActive }).containsExactly(false)
        }

    @Test
    fun `stop is idempotent and tolerates being called before start`() {
        val advertiser = RecordingAdvertiser()
        val session = makeSession(advertiser = advertiser)

        // Pre-start stop is a no-op.
        session.stop()
        assertThat(advertiser.calls).isEmpty()

        runBlocking { assertThrows<IllegalStateException> { session.start() } }
    }

    @Test
    fun `double stop is a no-op on the second call`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session = makeSession(advertiser = advertiser)

            session.start()
            session.stop()
            session.stop()

            assertThat(advertiser.calls.map { it.handle.isActive }).containsExactly(false)
        }

    @Test
    fun `start may only be invoked once`() =
        runBlocking {
            val session = makeSession()
            session.start()
            assertThrows<IllegalStateException> { runBlocking { session.start() } }
            session.stop()
        }

    @Test
    fun `boundPort throws before start`() {
        val session = makeSession()
        assertThrows<IllegalStateException> { session.boundPort }
    }

    @Test
    fun `failure during advertise rolls back tcp listener`() =
        runBlocking {
            val failing =
                DiscoveryAdvertiser { _, _ ->
                    throw java.io.IOException("boom: simulated mDNS failure")
                }
            val session =
                makeSession(
                    advertiser = failing,
                )

            val thrown =
                assertThrows<java.io.IOException> {
                    runBlocking { session.start() }
                }
            assertThat(thrown).hasMessageThat().contains("simulated mDNS failure")

            // boundPort must throw — the listener was rolled back.
            assertThrows<IllegalStateException> { session.boundPort }
            // After a failed start the session is terminal: stop is a no-op,
            // start cannot be retried.
            session.stop()
            assertThrows<IllegalStateException> { runBlocking { session.start() } }
        }

    @Test
    fun `failure during tcp factory does not invoke the advertiser`() {
        val advertiser = RecordingAdvertiser()
        val failingTcpFactory =
            object : TcpServerFactory {
                override fun create(
                    scope: CoroutineScope,
                    factoryProvider: () -> FileDestinationFactory,
                    secureRandomProvider: () -> SecureRandom,
                    mediumRegistry: MediumRegistry,
                ): TcpReceiverServer = throw java.io.IOException("simulated tcp factory failure")
            }
        val session =
            ReceiverSession(
                tcpServerFactory = failingTcpFactory,
                advertiser = advertiser,
                factoryProvider = { TempFileDestinationFactory() },
                endpointInfo = sampleEndpointInfo(),
            )

        assertThrows<java.io.IOException> { runBlocking { session.start() } }

        // The advertiser must not have been consulted — listener bind
        // failed before that point.
        assertThat(advertiser.calls).isEmpty()
    }

    @Test
    fun `isRunning reflects lifecycle transitions`() =
        runBlocking {
            val session = makeSession()
            assertThat(session.isRunning).isFalse()
            session.start()
            assertThat(session.isRunning).isTrue()
            session.stop()
            assertThat(session.isRunning).isFalse()
        }

    @Test
    fun `factory provider is invoked per accepted connection`() =
        runBlocking {
            val invocations = AtomicInteger(0)
            val provider: () -> FileDestinationFactory = {
                invocations.incrementAndGet()
                TempFileDestinationFactory()
            }
            val session =
                makeSession(
                    factoryProvider = provider,
                )
            session.start()

            // We don't drive a real InboundConnection here (that's #28's
            // scope); we just confirm that the wiring delegates the
            // provider to the underlying TcpReceiverServer rather than
            // calling it eagerly during start. A pre-flight call would
            // be observable as `invocations > 0` immediately.
            assertThat(invocations.get()).isEqualTo(0)

            session.stop()
        }

    @Test
    fun `advertiseGated start does not publish until publishAdvertisement is called`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                )

            session.start()
            assertThat(advertiser.calls).isEmpty()
            assertThat(session.isAdvertising).isFalse()

            session.publishAdvertisement()
            assertThat(advertiser.calls).hasSize(1)
            assertThat(session.isAdvertising).isTrue()
            assertThat(advertiser.calls[0].port).isEqualTo(session.boundPort)

            // Idempotent: a second call with the advertisement up does
            // not double-publish.
            session.publishAdvertisement()
            assertThat(advertiser.calls).hasSize(1)

            session.unpublishAdvertisement()
            assertThat(session.isAdvertising).isFalse()
            assertThat(advertiser.calls[0].handle.isActive).isFalse()

            // Re-publish after an unpublish opens a fresh handle.
            session.publishAdvertisement()
            assertThat(advertiser.calls).hasSize(2)

            session.stop()
        }

    @Test
    fun `non-gated start publishes initial control servers`() =
        runBlocking {
            val initialControlServer = RecordingInitialControlServer()
            val session =
                makeSession(
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()

            assertThat(initialControlServer.startCalls).hasSize(1)
            assertThat(initialControlServer.startCalls[0].endpointInfo).isEqualTo(sampleEndpointInfo())
            assertThat(initialControlServer.isActive).isTrue()

            session.stop()
            assertThat(initialControlServer.stopCount).isEqualTo(1)
        }

    @Test
    fun `gated publish controls initial control server lifecycle`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val initialControlServer = RecordingInitialControlServer()
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()
            assertThat(initialControlServer.startCalls).isEmpty()

            session.publishAdvertisement()
            assertThat(initialControlServer.startCalls).hasSize(1)
            assertThat(initialControlServer.isActive).isTrue()

            session.unpublishAdvertisement()
            assertThat(initialControlServer.stopCount).isEqualTo(1)
            assertThat(initialControlServer.isActive).isFalse()

            session.publishAdvertisement()
            assertThat(initialControlServer.startCalls).hasSize(2)
            assertThat(initialControlServer.isActive).isTrue()

            session.stop()
            assertThat(initialControlServer.stopCount).isEqualTo(2)
        }

    @Test
    fun `gated publish starts initial control servers before mDNS advertise`() =
        runBlocking {
            val order = mutableListOf<String>()
            val advertiser =
                object : DiscoveryAdvertiser {
                    override fun advertise(
                        endpointInfo: EndpointInfo,
                        port: Int,
                    ): AdvertiseHandle {
                        order += "mdns"
                        return FakeAdvertiseHandle(port = port)
                    }
                }
            val initialControlServer =
                object : RecordingInitialControlServer() {
                    override fun start(
                        endpointInfo: EndpointInfo,
                        acceptTransport: (ConnectedTransport) -> Unit,
                    ): Boolean {
                        order += "initial-control"
                        return super.start(endpointInfo, acceptTransport)
                    }
                }
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()
            session.publishAdvertisement()

            assertThat(order).containsExactly("initial-control", "mdns").inOrder()

            session.stop()
        }

    @Test
    fun `gated publish keeps initial control active when mDNS advertise fails`() =
        runBlocking {
            val initialControlServer = RecordingInitialControlServer()
            val session =
                makeSession(
                    advertiser =
                        object : DiscoveryAdvertiser {
                            override fun advertise(
                                endpointInfo: EndpointInfo,
                                port: Int,
                            ): AdvertiseHandle = error("synthetic mDNS publish failure")
                        },
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()

            assertThrows<IllegalStateException> { session.publishAdvertisement() }
            assertThat(session.isAdvertising).isFalse()
            assertThat(initialControlServer.stopCount).isEqualTo(0)
            assertThat(initialControlServer.startCalls).hasSize(1)
            assertThat(initialControlServer.isActive).isTrue()

            assertThrows<IllegalStateException> { session.publishAdvertisement() }
            assertThat(initialControlServer.stopCount).isEqualTo(0)
            assertThat(initialControlServer.startCalls).hasSize(1)

            session.stop()
            assertThat(initialControlServer.stopCount).isEqualTo(1)
        }

    @Test
    fun `replaceEndpointInfo republishes active advertisement without rebinding tcp listener`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val initialControlServer = RecordingInitialControlServer()
            val original = sampleEndpointInfo(name = "Old Name")
            val updated = sampleEndpointInfo(name = "New Name")
            val session =
                makeSession(
                    advertiser = advertiser,
                    endpointInfo = original,
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()
            val originalPort = session.boundPort
            session.publishAdvertisement()

            session.replaceEndpointInfo(updated)

            assertThat(session.boundPort).isEqualTo(originalPort)
            assertThat(advertiser.calls).hasSize(2)
            assertThat(advertiser.calls[0].endpointInfo).isEqualTo(original)
            assertThat(advertiser.calls[0].handle.isActive).isFalse()
            assertThat(advertiser.calls[1].endpointInfo).isEqualTo(updated)
            assertThat(advertiser.calls[1].port).isEqualTo(originalPort)
            assertThat(initialControlServer.stopCount).isEqualTo(1)
            assertThat(initialControlServer.startCalls).hasSize(2)
            assertThat(initialControlServer.startCalls[1].endpointInfo).isEqualTo(updated)

            session.stop()
        }

    @Test
    fun `replaceEndpointInfo updates a gated session before first publish`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val updated = sampleEndpointInfo(name = "Renamed Device")
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                )

            session.start()
            session.replaceEndpointInfo(updated)
            session.publishAdvertisement()

            assertThat(advertiser.calls).hasSize(1)
            assertThat(advertiser.calls[0].endpointInfo).isEqualTo(updated)

            session.stop()
        }

    @Test
    fun `replaceEndpointInfo failure is reported without throwing and can be restored`() =
        runBlocking {
            val advertiser = FailingOnAttemptAdvertiser(failOnAttempt = 2)
            val original = sampleEndpointInfo(name = "Old Name")
            val updated = sampleEndpointInfo(name = "New Name")
            val session =
                makeSession(
                    advertiser = advertiser,
                    endpointInfo = original,
                    advertiseGated = true,
                )

            session.start()
            val originalPort = session.boundPort
            session.publishAdvertisement()

            val replaced = session.replaceEndpointInfo(updated)

            assertThat(replaced).isFalse()
            assertThat(session.boundPort).isEqualTo(originalPort)
            assertThat(session.isAdvertising).isFalse()
            assertThat(advertiser.attempts).hasSize(2)
            assertThat(advertiser.attempts[0].endpointInfo).isEqualTo(original)
            assertThat(advertiser.attempts[0].succeeded).isTrue()
            assertThat(advertiser.attempts[1].endpointInfo).isEqualTo(updated)
            assertThat(advertiser.attempts[1].succeeded).isFalse()

            val restored = session.replaceEndpointInfo(original)

            assertThat(restored).isTrue()
            assertThat(session.isAdvertising).isFalse()

            session.publishAdvertisement()

            assertThat(session.isAdvertising).isTrue()
            assertThat(advertiser.attempts).hasSize(3)
            assertThat(advertiser.attempts[2].endpointInfo).isEqualTo(original)
            assertThat(advertiser.attempts[2].port).isEqualTo(originalPort)
            assertThat(advertiser.attempts[2].succeeded).isTrue()

            session.stop()
        }

    @Test
    fun `replaceEndpointInfo can keep initial control active when mDNS republish fails`() =
        runBlocking {
            val advertiser = FailingOnAttemptAdvertiser(failOnAttempt = 2)
            val initialControlServer = RecordingInitialControlServer()
            val original = sampleEndpointInfo(name = "Old Name")
            val updated = sampleEndpointInfo(name = "New Name")
            val session =
                makeSession(
                    advertiser = advertiser,
                    endpointInfo = original,
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()
            session.publishAdvertisement()

            val replaced =
                session.replaceEndpointInfo(
                    endpointInfo = updated,
                    requireAdvertisement = false,
                )

            assertThat(replaced).isTrue()
            assertThat(session.isAdvertising).isFalse()
            assertThat(initialControlServer.isActive).isTrue()
            assertThat(initialControlServer.stopCount).isEqualTo(1)
            assertThat(initialControlServer.startCalls).hasSize(2)
            assertThat(initialControlServer.startCalls[1].endpointInfo).isEqualTo(updated)
            assertThat(advertiser.attempts).hasSize(2)
            assertThat(advertiser.attempts[1].succeeded).isFalse()

            session.stop()
        }

    @Test
    fun `initial control transports are injected into active connection flow`() =
        runBlocking {
            val initialControlServer = RecordingInitialControlServer()
            val session =
                makeSession(
                    advertiseGated = true,
                    initialControlServers = listOf(initialControlServer),
                )

            session.start()
            val activeConnection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { session.activeConnections.first() }
                }

            session.publishAdvertisement()
            initialControlServer.accept(ClosedTransport())

            assertThat(activeConnection.await()).isNotNull()
            session.stop()
        }

    @Test
    fun `unpublishAdvertisement is a no-op when nothing is published`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                )

            session.start()
            session.unpublishAdvertisement()
            session.unpublishAdvertisement()
            assertThat(advertiser.calls).isEmpty()

            session.stop()
        }

    @Test
    fun `publishAdvertisement throws before start`() {
        val session = makeSession(advertiseGated = true)
        assertThrows<IllegalStateException> { session.publishAdvertisement() }
    }

    @Test
    fun `stop tears down a gated advertisement`() =
        runBlocking {
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    advertiser = advertiser,
                    advertiseGated = true,
                )

            session.start()
            session.publishAdvertisement()
            assertThat(session.isAdvertising).isTrue()

            session.stop()
            assertThat(advertiser.calls.map { it.handle.isActive }).containsExactly(false)
        }

    @Test
    fun `default tcp server factory binds on all interfaces`() =
        runBlocking {
            // A smoke test for the TcpServerFactory.default() helper —
            // verify it produces a server that can be started and
            // stopped without throwing. Bind address is implementation-
            // dependent (0.0.0.0); we just check the round-trip works.
            val factory = TcpServerFactory.default()
            val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            val server =
                factory.create(
                    scope,
                    factoryProvider = { TempFileDestinationFactory() },
                    secureRandomProvider = { SecureRandom() },
                )
            try {
                val port = server.start()
                assertThat(port).isGreaterThan(0)
            } finally {
                server.stop()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    // --------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------

    private fun makeSession(
        advertiser: DiscoveryAdvertiser = RecordingAdvertiser(),
        endpointInfo: EndpointInfo = sampleEndpointInfo(),
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

    private fun sampleEndpointInfo(name: String = "Test Device"): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = name,
            tlvRecords = emptyList(),
        )

    /**
     * Recording [DiscoveryAdvertiser] that captures every advertise call
     * and produces a fake [AdvertiseHandle] tracking active state. The
     * fake handle is sufficient for lifecycle assertions; it does not
     * publish anything on the wire.
     */
    private class RecordingAdvertiser : DiscoveryAdvertiser {
        data class Call(
            val endpointInfo: EndpointInfo,
            val port: Int,
            val handle: FakeAdvertiseHandle,
        )

        val calls: MutableList<Call> = mutableListOf()

        override fun advertise(
            endpointInfo: EndpointInfo,
            port: Int,
        ): AdvertiseHandle {
            val handle = FakeAdvertiseHandle(port = port)
            calls += Call(endpointInfo, port, handle)
            return handle
        }
    }

    private class FailingOnAttemptAdvertiser(
        private val failOnAttempt: Int,
    ) : DiscoveryAdvertiser {
        data class Attempt(
            val endpointInfo: EndpointInfo,
            val port: Int,
            val succeeded: Boolean,
        )

        val attempts: MutableList<Attempt> = mutableListOf()

        private var callCount: Int = 0

        override fun advertise(
            endpointInfo: EndpointInfo,
            port: Int,
        ): AdvertiseHandle {
            callCount += 1
            if (callCount == failOnAttempt) {
                attempts += Attempt(endpointInfo, port, false)
                throw java.io.IOException("simulated advertise failure")
            }
            attempts += Attempt(endpointInfo, port, true)
            return FakeAdvertiseHandle(port = port)
        }
    }

    /**
     * Minimal in-memory [AdvertiseHandle] for tests. `close` flips
     * `isActive` so the session lifecycle assertions can observe
     * teardown.
     */
    private class FakeAdvertiseHandle(
        override val port: Int,
        override val instanceName: String = "libredrop-test-instance",
    ) : AdvertiseHandle {
        @Volatile
        private var active: Boolean = true

        override val isActive: Boolean
            get() = active

        override fun close() {
            active = false
        }
    }

    private open class RecordingInitialControlServer : InitialControlServer {
        data class StartCall(
            val endpointInfo: EndpointInfo,
        )

        val startCalls: MutableList<StartCall> = mutableListOf()

        var stopCount: Int = 0
            private set

        private var active: Boolean = false
        private var acceptTransport: ((ConnectedTransport) -> Unit)? = null

        override val isActive: Boolean
            get() = active

        override fun start(
            endpointInfo: EndpointInfo,
            acceptTransport: (ConnectedTransport) -> Unit,
        ): Boolean {
            startCalls += StartCall(endpointInfo)
            this.acceptTransport = acceptTransport
            active = true
            return true
        }

        override fun stop() {
            if (!active) return
            active = false
            acceptTransport = null
            stopCount += 1
        }

        fun accept(transport: ConnectedTransport) {
            val callback = acceptTransport ?: error("initial control server is not active")
            callback(transport)
        }
    }

    private class ClosedTransport : ConnectedTransport {
        override val medium: Medium = Medium.BLUETOOTH

        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))

        override val outputStream: OutputStream = ByteArrayOutputStream()

        override fun close() {
            inputStream.close()
            outputStream.close()
        }
    }
}
