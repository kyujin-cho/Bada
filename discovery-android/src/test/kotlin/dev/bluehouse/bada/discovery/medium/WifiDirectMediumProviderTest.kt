/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * JVM-only coverage for [WifiDirectMediumProvider]'s pure-Kotlin
 * surface (#49).
 *
 * The Android `WifiP2pManager` choreography lives in
 * [WifiDirectGroupController] and is exercised manually on hardware
 * (see `docs/testing/medium-wifi-direct.md`); the provider itself is
 * thin enough to test by injecting a controller factory that returns
 * either `null` (controller unavailable) or a fake. The tests below
 * lock down the contract the framework relies on:
 *
 *  1. `medium == WIFI_DIRECT` (registry uniqueness key).
 *  2. `isSupported` defers to the supplied [WifiDirectAvailability].
 *  3. `prepareUpgrade` short-circuits when the controller factory
 *     yields `null`, leaving no allocated ServerSocket behind.
 *  4. `adoptUpgrade` rejects mismatched-medium credentials without
 *     touching the controller.
 *  5. `adoptUpgrade` rejects `Generic(WIFI_DIRECT)` (no concrete
 *     bring-up parameters).
 *  6. `cancelPending` / `consumePending*` are idempotent on a fresh
 *     provider.
 *
 * Robolectric / on-device coverage of the actual P2P calls is
 * intentionally out-of-scope here — those code paths require platform
 * I/O and are documented in the manual checklist.
 */
class WifiDirectMediumProviderTest {
    private class FakeAvailability(
        private val supported: Boolean,
    ) : WifiDirectAvailability {
        override fun isSupported(): Boolean = supported
    }

    @Test
    fun `medium is WIFI_DIRECT`() {
        val provider = newProvider(supported = false)
        assertThat(provider.medium).isEqualTo(Medium.WIFI_DIRECT)
    }

    @Test
    fun `isSupported defers to the availability probe`() {
        assertThat(newProvider(supported = true).isSupported()).isTrue()
        assertThat(newProvider(supported = false).isSupported()).isFalse()
    }

    @Test
    fun `prepareUpgrade returns null when controller factory yields null`() =
        runTest {
            val socketFactoryCalls = IntArray(1)
            val provider =
                WifiDirectMediumProvider(
                    availability = FakeAvailability(true),
                    controllerFactory = { null },
                    serverSocketFactory = {
                        socketFactoryCalls[0]++
                        ServerSocket(0)
                    },
                )
            assertThat(provider.prepareUpgrade()).isNull()
            // Short-circuit before allocating the listener — no port
            // pinned by a failed prepare.
            assertThat(socketFactoryCalls[0]).isEqualTo(0)
        }

    @Test
    fun `adoptUpgrade rejects mismatched medium`() =
        runTest {
            val provider = newProvider(supported = true)
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiLan(
                        ipAddress = byteArrayOf(10, 0, 0, 1),
                        port = 1234,
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade rejects Generic credentials with WIFI_DIRECT medium`() =
        runTest {
            // The peer advertised WIFI_DIRECT but did not include the
            // sub-message — decoder collapses to Generic. The provider
            // cannot bring up a group without SSID/passphrase, so this
            // must fail back rather than NPE.
            val provider = newProvider(supported = true)
            val result = provider.adoptUpgrade(UpgradePathCredentials.Generic(Medium.WIFI_DIRECT))
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade returns null when controller factory yields null even with valid credentials`() =
        runTest {
            val provider = newProvider(supported = true)
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiDirect(
                        ipAddress = byteArrayOf(192.toByte(), 168.toByte(), 49, 1),
                        port = 8443,
                        ssid = "DIRECT-aa-test",
                        passphrase = "secret",
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `cancelPending is a no-op when nothing is in flight`() {
        val provider = newProvider(supported = true)
        provider.cancelPending() // Must not throw.
        provider.cancelPending() // Idempotent.
    }

    @Test
    fun `consumePendingServerSocket returns null when prepareUpgrade was never called`() {
        val provider = newProvider(supported = true)
        assertThat(provider.consumePendingServerSocket()).isNull()
    }

    @Test
    fun `consumePendingClientTransport returns null when adoptUpgrade was never successful`() {
        val provider = newProvider(supported = true)
        assertThat(provider.consumePendingClientTransport()).isNull()
    }

    @Test
    fun `Wi-Fi Direct generated network names match Nearby-compatible shape`() {
        val generated = WifiDirectCredentialShape.generateNetworkName()

        assertThat(WifiDirectCredentialShape.isValidNetworkName(generated)).isTrue()
        assertThat(generated).startsWith("DIRECT-")
        assertThat(generated).contains("-Bada-")
        assertThat(generated.length).isAtMost(32)
    }

    @Test
    fun `Wi-Fi Direct network name validation rejects OEM device-name suffixes`() {
        assertThat(
            WifiDirectCredentialShape.isValidNetworkName("DIRECT-O3-Kyujin's vivo X300 Ult"),
        ).isFalse()
        assertThat(WifiDirectCredentialShape.isValidNetworkName("DIRECT-14F768FDC")).isTrue()
        assertThat(WifiDirectCredentialShape.isValidNetworkName("DIRECT-ab-Bada")).isTrue()
    }

    @Test
    fun `Wi-Fi Direct generated passphrase fits platform constraints`() {
        val generated = WifiDirectCredentialShape.generatePassphrase()

        assertThat(WifiDirectCredentialShape.isValidPassphrase(generated)).isTrue()
        assertThat(generated.length).isAtLeast(8)
        assertThat(generated.length).isAtMost(63)
    }

    private fun newProvider(supported: Boolean): WifiDirectMediumProvider =
        WifiDirectMediumProvider(
            availability = FakeAvailability(supported),
            controllerFactory = { null },
            serverSocketFactory = { ServerSocket(0) },
        )
}
