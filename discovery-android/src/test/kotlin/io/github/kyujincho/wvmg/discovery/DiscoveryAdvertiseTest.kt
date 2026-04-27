/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS

/**
 * Integration-style smoke tests for [Discovery.advertise] running on the
 * host JVM.
 *
 * These tests bypass the Android multicast-lock and Wi-Fi address lookup
 * by injecting a noop [LockController] and a JmDNS factory that binds to
 * the loopback interface. They cover the publish path (TXT contents,
 * lifecycle, lock counter) but stop short of round-tripping through a
 * real multicast hop — JmDNS multicast loopback delivery is unreliable
 * across OS / JDK combinations. The TXT parsing logic is exercised
 * deterministically by [DiscoveredServiceMappingTest].
 */
class DiscoveryAdvertiseTest {
    @Test
    fun `port outside 1 to 65535 is rejected`() {
        val discovery =
            Discovery.forTesting(
                locks = NoopLockController(),
                jmdnsProvider = { error("not used") },
            )
        assertThrows<IllegalArgumentException> {
            discovery.advertise(sampleEndpointInfo(), port = 0)
        }
        assertThrows<IllegalArgumentException> {
            discovery.advertise(sampleEndpointInfo(), port = 70_000)
        }
    }

    @Test
    fun `advertise registers a service through JmDNS without throwing`() {
        // We can't reliably observe JmDNS's internal cache after register
        // (`list()` triggers a real multicast query on loopback, which is
        // OS-dependent), so this smoke test simply asserts that the
        // publish path opens JmDNS, registers the service, and surfaces a
        // valid handle. The bit-exact TXT-contents path is covered
        // deterministically by [DiscoveredServiceMappingTest], which
        // reads back through the same `getPropertyString(...)` API
        // JmDNS uses internally.
        val locks = NoopLockController()
        val sharedJmdns = JmDNS.create(InetAddress.getLoopbackAddress(), "wvmg-adv-test")
        val publisher =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { sharedJmdns },
            )
        val endpointInfo = sampleEndpointInfo()
        val handle = publisher.advertise(endpointInfo, port = 12_345)
        try {
            assertThat(handle.instanceName).isNotEmpty()
            assertThat(handle.port).isEqualTo(12_345)
            assertThat(handle.isActive).isTrue()
        } finally {
            handle.close()
            sharedJmdns.close()
        }
    }

    @Test
    fun `advertise increments and decrements lock holder`() {
        val locks = NoopLockController()
        val discovery =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { name -> JmDNS.create(InetAddress.getLoopbackAddress(), name) },
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 23_456)
        try {
            assertThat(locks.acquireCount).isEqualTo(1)
            assertThat(locks.releaseCount).isEqualTo(0)
        } finally {
            handle.close()
        }
        assertThat(locks.acquireCount).isEqualTo(1)
        assertThat(locks.releaseCount).isEqualTo(1)
    }

    @Test
    fun `network change triggers JmDNS re-registration`() {
        // Use a counting JmDNS provider so we can observe how many times
        // a fresh instance was opened across the lifecycle.
        val opened = AtomicInteger(0)
        val locks = NoopLockController()
        val fakeWatcher = FakeNetworkWatcher()
        val factory =
            object : NetworkWatcherFactory {
                override fun create(onChanged: () -> Unit): NetworkWatcher {
                    fakeWatcher.onChanged = onChanged
                    return fakeWatcher
                }
            }
        val discovery =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { name ->
                    opened.incrementAndGet()
                    JmDNS.create(InetAddress.getLoopbackAddress(), "$name-${opened.get()}")
                },
                networkWatcherFactory = factory,
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 45_678)
        try {
            assertThat(opened.get()).isEqualTo(1)
            assertThat(fakeWatcher.started).isTrue()

            // Simulate a Wi-Fi network change.
            fakeWatcher.fire()
            assertThat(opened.get()).isEqualTo(2)

            fakeWatcher.fire()
            assertThat(opened.get()).isEqualTo(3)
        } finally {
            handle.close()
        }
        assertThat(fakeWatcher.stopped).isTrue()
    }

    @Test
    fun `closing the advertise handle is idempotent`() {
        val locks = NoopLockController()
        val discovery =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { name -> JmDNS.create(InetAddress.getLoopbackAddress(), name) },
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 34_567)
        handle.close()
        handle.close() // second close must not double-release the lock
        assertThat(locks.releaseCount).isEqualTo(1)
        assertThat(handle.isActive).isFalse()
    }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "Test Device",
            tlvRecords = emptyList(),
        )

    /**
     * A test-only [NetworkWatcher] that records start/stop and lets the
     * test trigger the change callback synchronously via [fire].
     */
    private class FakeNetworkWatcher : NetworkWatcher {
        var onChanged: () -> Unit = {}
        var started: Boolean = false
            private set
        var stopped: Boolean = false
            private set

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        fun fire() {
            onChanged()
        }
    }

    private class NoopLockController : LockController {
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

        override fun isHeld(): Boolean = acquireCount > releaseCount
    }
}
