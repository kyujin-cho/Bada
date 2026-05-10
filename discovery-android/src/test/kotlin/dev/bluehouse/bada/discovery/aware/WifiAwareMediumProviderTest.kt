/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.aware

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * Unit tests for [WifiAwareMediumProvider]. The platform layer
 * ([WifiAwareSupport]) is faked so these tests run on a plain JVM
 * without `android.*` and without a real Wi-Fi Aware-capable device.
 */
class WifiAwareMediumProviderTest {
    @Test
    fun `medium is WIFI_AWARE`() {
        val provider = WifiAwareMediumProvider(FakeSupport())
        assertThat(provider.medium).isEqualTo(Medium.WIFI_AWARE)
    }

    @Test
    fun `isSupported reflects the support layer`() {
        val unavailable = WifiAwareMediumProvider(FakeSupport(available = false))
        val available = WifiAwareMediumProvider(FakeSupport(available = true))
        assertThat(unavailable.isSupported()).isFalse()
        assertThat(available.isSupported()).isTrue()
    }

    @Test
    fun `prepareUpgrade returns null when support is unavailable`() =
        runTest {
            val provider = WifiAwareMediumProvider(FakeSupport(available = false))
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns credentials produced by support`() =
        runTest {
            val expected =
                UpgradePathCredentials.WifiAware(
                    serviceName = "bada-quickshare-aware",
                    ipv6Address = ByteArray(16) { it.toByte() },
                    port = 9999,
                    passphrase = "from-fake-support",
                )
            val support =
                FakeSupport(
                    available = true,
                    prepareResult = expected,
                )
            val provider = WifiAwareMediumProvider(support)
            assertThat(provider.prepareUpgrade()).isEqualTo(expected)
            // The provider feeds support.prepareUpgrade a generated
            // passphrase; the fake records it for assertion.
            assertThat(support.lastGeneratedPassphrase).isNotNull()
            assertThat(support.lastGeneratedPassphrase!!.length).isEqualTo(32)
        }

    @Test
    fun `prepareUpgrade generates a passphrase that fits the Wi-Fi Aware contract`() =
        runTest {
            val support = FakeSupport(available = true)
            val provider = WifiAwareMediumProvider(support)
            provider.prepareUpgrade()
            val pw = support.lastGeneratedPassphrase
            checkNotNull(pw)
            // Wi-Fi Aware accepts 8..63 ASCII; we always emit 32.
            assertThat(pw.length).isAtLeast(8)
            assertThat(pw.length).isAtMost(63)
            assertThat(pw.all { it.code in 0x20..0x7E }).isTrue()
        }

    @Test
    fun `adoptUpgrade rejects non-WifiAware credentials`() =
        runTest {
            val provider = WifiAwareMediumProvider(FakeSupport(available = true))
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiLan(
                        ipAddress = byteArrayOf(10, 0, 0, 1),
                        port = 1,
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade returns null when support is unavailable`() =
        runTest {
            val provider = WifiAwareMediumProvider(FakeSupport(available = false))
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiAware(
                        serviceName = "svc",
                        ipv6Address = ByteArray(16),
                        port = 1,
                        passphrase = "abcdefghij",
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade wraps the support socket in a WifiAwareTransport`() =
        runTest {
            // Build a real loopback socket pair so the test exercises a
            // genuine Socket instance — no need for a real Wi-Fi Aware
            // network to validate the wrapping contract.
            val server = ServerSocket(0)
            // Connect from a worker thread so accept() does not deadlock
            // the test coroutine. The thread is short-lived and joined
            // before the test completes.
            val clientHolder = arrayOfNulls<Socket>(1)
            val connectThread =
                Thread {
                    clientHolder[0] = Socket("127.0.0.1", server.localPort)
                }.apply { start() }
            val accepted = server.accept()
            connectThread.join()
            val client = clientHolder[0]!!
            try {
                val support =
                    FakeSupport(
                        available = true,
                        adoptResult = WifiAwareSocketHandle(client) {},
                    )
                val provider = WifiAwareMediumProvider(support)
                val transport =
                    provider.adoptUpgrade(
                        UpgradePathCredentials.WifiAware(
                            serviceName = "svc",
                            ipv6Address = ByteArray(16),
                            port = server.localPort,
                            passphrase = "abcdefghij1234567890",
                        ),
                    )
                assertThat(transport).isInstanceOf(WifiAwareTransport::class.java)
                val wrapped = (transport as WifiAwareTransport).socket
                assertThat(wrapped).isSameInstanceAs(client)
                assertThat(transport.medium).isEqualTo(Medium.WIFI_AWARE)
            } finally {
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }

    @Test
    fun `acceptUpgrade wraps support socket and transport close runs teardown`() =
        runTest {
            val server = ServerSocket(0)
            val clientHolder = arrayOfNulls<Socket>(1)
            val connectThread =
                Thread {
                    clientHolder[0] = Socket("127.0.0.1", server.localPort)
                }.apply { start() }
            val accepted = server.accept()
            connectThread.join()
            val client = clientHolder[0]!!
            var teardownCalled = false
            try {
                val support =
                    FakeSupport(
                        available = true,
                        acceptResult =
                            WifiAwareSocketHandle(client) {
                                teardownCalled = true
                            },
                    )
                val provider = WifiAwareMediumProvider(support)
                val transport = provider.acceptUpgrade()

                assertThat(transport).isInstanceOf(WifiAwareTransport::class.java)
                assertThat((transport as WifiAwareTransport).socket).isSameInstanceAs(client)

                transport.close()
                assertThat(teardownCalled).isTrue()
            } finally {
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }

    /**
     * Fake [WifiAwareSupport] that records the arguments the provider
     * passes in and returns whatever the test sets.
     */
    private class FakeSupport(
        private val available: Boolean = true,
        private val prepareResult: UpgradePathCredentials.WifiAware? = null,
        private val adoptResult: WifiAwareSocketHandle? = null,
        private val acceptResult: WifiAwareSocketHandle? = null,
    ) : WifiAwareSupport {
        var lastGeneratedPassphrase: String? = null
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun prepareUpgrade(passphrase: String): UpgradePathCredentials.WifiAware? {
            lastGeneratedPassphrase = passphrase
            return prepareResult
        }

        override suspend fun adoptUpgrade(credentials: UpgradePathCredentials.WifiAware): WifiAwareSocketHandle? =
            adoptResult

        override suspend fun acceptUpgrade(): WifiAwareSocketHandle? = acceptResult
    }
}
