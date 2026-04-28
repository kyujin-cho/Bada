/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.discovery.AdvertiseHandle
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import io.github.kyujincho.wvmg.protocol.payload.TempFileDestinationFactory
import io.github.kyujincho.wvmg.protocol.server.TcpReceiverServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
 *  - a counting [MulticastLockController] that exposes the running
 *    acquire/release tally;
 *  - a per-test [TempFileDestinationFactory] supplier so the production
 *    `MediaStoreDownloadsFactory` (which needs a real `ContentResolver`)
 *    is not needed.
 *
 * End-to-end pairing with `InboundConnection` over real sockets is
 * deferred to #28; this file deliberately stops at the lifecycle
 * surface so we don't spawn pending coroutines that need that test
 * scaffolding to terminate.
 */
class ReceiverSessionTest {
    @Test
    fun `start binds tcp listener and acquires multicast lock`() =
        runBlocking {
            val locks = CountingLockController()
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    locks = locks,
                    advertiser = advertiser,
                )

            val port = session.start()

            assertThat(port).isGreaterThan(0)
            assertThat(session.boundPort).isEqualTo(port)
            assertThat(locks.acquireCount).isEqualTo(1)
            assertThat(locks.releaseCount).isEqualTo(0)
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
    fun `stop releases the lock and closes the advertise handle`() =
        runBlocking {
            val locks = CountingLockController()
            val advertiser = RecordingAdvertiser()
            val session =
                makeSession(
                    locks = locks,
                    advertiser = advertiser,
                )

            session.start()
            session.stop()

            assertThat(locks.releaseCount).isEqualTo(1)
            assertThat(advertiser.calls.map { it.handle.isActive }).containsExactly(false)
        }

    @Test
    fun `stop is idempotent and tolerates being called before start`() {
        val locks = CountingLockController()
        val advertiser = RecordingAdvertiser()
        val session = makeSession(locks = locks, advertiser = advertiser)

        // Pre-start stop is a no-op.
        session.stop()
        assertThat(locks.acquireCount).isEqualTo(0)
        assertThat(locks.releaseCount).isEqualTo(0)

        runBlocking { assertThrows<IllegalStateException> { session.start() } }
    }

    @Test
    fun `double stop only releases once`() =
        runBlocking {
            val locks = CountingLockController()
            val session = makeSession(locks = locks)

            session.start()
            session.stop()
            session.stop()

            assertThat(locks.acquireCount).isEqualTo(1)
            assertThat(locks.releaseCount).isEqualTo(1)
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
    fun `failure during advertise rolls back tcp listener and lock`() =
        runBlocking {
            val locks = CountingLockController()
            val failing =
                DiscoveryAdvertiser { _, _ ->
                    throw java.io.IOException("boom: simulated mDNS failure")
                }
            val session =
                makeSession(
                    locks = locks,
                    advertiser = failing,
                )

            val thrown =
                assertThrows<java.io.IOException> {
                    runBlocking { session.start() }
                }
            assertThat(thrown).hasMessageThat().contains("simulated mDNS failure")

            // Lock counter must be balanced.
            assertThat(locks.releaseCount).isEqualTo(locks.acquireCount)
            // boundPort must throw — the listener was rolled back.
            assertThrows<IllegalStateException> { session.boundPort }
            // After a failed start the session is terminal: stop is a no-op,
            // start cannot be retried.
            session.stop()
            assertThrows<IllegalStateException> { runBlocking { session.start() } }
        }

    @Test
    fun `failure during lock acquire does not start tcp or advertise`() {
        val advertiser = RecordingAdvertiser()
        val tcpFactoryCalls = AtomicInteger(0)
        val countingTcpFactory =
            object : TcpServerFactory {
                override fun create(
                    scope: CoroutineScope,
                    factoryProvider: () -> FileDestinationFactory,
                    secureRandomProvider: () -> SecureRandom,
                ): TcpReceiverServer {
                    tcpFactoryCalls.incrementAndGet()
                    return TcpReceiverServer(
                        parentScope = scope,
                        factoryProvider = factoryProvider,
                        secureRandomProvider = secureRandomProvider,
                        bindAddress = InetAddress.getLoopbackAddress(),
                    )
                }
            }
        val locks =
            object : MulticastLockController {
                override fun acquire(): Unit = throw SecurityException("missing CHANGE_WIFI_MULTICAST_STATE")

                override fun release() = error("release should not be called")
            }
        val session =
            ReceiverSession(
                tcpServerFactory = countingTcpFactory,
                advertiser = advertiser,
                multicastLock = locks,
                factoryProvider = { TempFileDestinationFactory() },
                endpointInfo = sampleEndpointInfo(),
            )

        assertThrows<SecurityException> { runBlocking { session.start() } }

        // Neither downstream side-effect should have happened.
        assertThat(tcpFactoryCalls.get()).isEqualTo(0)
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
        locks: MulticastLockController = CountingLockController(),
        advertiser: DiscoveryAdvertiser = RecordingAdvertiser(),
        endpointInfo: EndpointInfo = sampleEndpointInfo(),
        factoryProvider: () -> FileDestinationFactory = { TempFileDestinationFactory() },
        advertiseGated: Boolean = false,
    ): ReceiverSession =
        ReceiverSession(
            tcpServerFactory =
                object : TcpServerFactory {
                    override fun create(
                        scope: CoroutineScope,
                        factoryProvider: () -> FileDestinationFactory,
                        secureRandomProvider: () -> SecureRandom,
                    ): TcpReceiverServer =
                        TcpReceiverServer(
                            parentScope = scope,
                            factoryProvider = factoryProvider,
                            secureRandomProvider = secureRandomProvider,
                            bindAddress = InetAddress.getLoopbackAddress(),
                        )
                },
            advertiser = advertiser,
            multicastLock = locks,
            factoryProvider = factoryProvider,
            endpointInfo = endpointInfo,
            advertiseGated = advertiseGated,
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
     * In-memory [MulticastLockController] that tracks acquire/release
     * counts so tests can assert resource balance without a real
     * `WifiManager`.
     */
    private class CountingLockController : MulticastLockController {
        var acquireCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set

        override fun acquire() {
            acquireCount++
        }

        override fun release() {
            releaseCount++
        }
    }

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

    /**
     * Minimal in-memory [AdvertiseHandle] for tests. `close` flips
     * `isActive` so the session lifecycle assertions can observe
     * teardown.
     */
    private class FakeAdvertiseHandle(
        override val port: Int,
        override val instanceName: String = "wvmg-test-instance",
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
