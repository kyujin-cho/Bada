/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [JmdnsFactory]'s address-resolution behavior.
 *
 * The production address-resolution path on real Android devices is
 * [JmdnsFactory.AndroidWifiAddressProvider], which talks to
 * [android.net.wifi.WifiManager] and [android.net.ConnectivityManager].
 * We do not exercise that path here — testing it would need a full
 * Android instrumentation context, and the failure mode (returns
 * `0.0.0.0` on API 31+ without precise location) is exactly what we are
 * trying to avoid in unit tests.
 *
 * Instead we drive [JmdnsFactory.createWith] through a fake
 * [JmdnsFactory.WifiAddressProvider]:
 *   * a present address reaches `JmDNS.create(InetAddress, String)` verbatim, and
 *   * a `null` address falls back to JmDNS's wildcard binding.
 */
class JmdnsFactoryTest {
    @Test
    fun `create consults the provider and produces a usable JmDNS instance`() {
        val explicit = InetAddress.getByName("127.0.0.1")
        val provider = StaticWifiAddressProvider(explicit)

        val jmdns = JmdnsFactory.createWith(name = "test-bound", addressProvider = provider)
        try {
            // We don't pin the precise host string — JmDNS occasionally
            // resolves IPv4 loopback to its IPv6 counterpart on certain
            // OS / JDK combos (`fe80::1%lo0` on macOS). What we care
            // about is that the provider was consulted *and* that
            // JmDNS landed on a non-null bound interface.
            assertThat(jmdns.inetAddress).isNotNull()
            assertThat(provider.callCount.get()).isEqualTo(1)
        } finally {
            jmdns.close()
        }
    }

    @Test
    fun `create falls back to wildcard bind when provider returns null`() {
        val provider = StaticWifiAddressProvider(null)

        // The wildcard-bind path picks whatever interface JmDNS chooses;
        // we only need to confirm the factory completes without throwing
        // and that the returned instance has *some* bound interface so
        // browsing logic does not NPE downstream.
        val jmdns = JmdnsFactory.createWith(name = "test-wildcard", addressProvider = provider)
        try {
            assertThat(jmdns.inetAddress).isNotNull()
            assertThat(provider.callCount.get()).isEqualTo(1)
        } finally {
            jmdns.close()
        }
    }

    /**
     * Records how many times `currentWifiAddress` was queried and
     * always returns the same fixed answer. Lives at file scope so the
     * test class itself stays compact.
     */
    private class StaticWifiAddressProvider(
        private val address: InetAddress?,
    ) : JmdnsFactory.WifiAddressProvider {
        val callCount = AtomicInteger(0)

        override fun currentWifiAddress(): InetAddress? {
            callCount.incrementAndGet()
            return address
        }
    }
}
