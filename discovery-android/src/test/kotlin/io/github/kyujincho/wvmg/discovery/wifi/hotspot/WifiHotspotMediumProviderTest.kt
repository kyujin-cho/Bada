/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.wifi.hotspot

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * Pure-JVM exercise of [WifiHotspotMediumProvider]'s logic via fakes
 * for [HotspotController] / [HotspotClient]. Real Android adapters
 * ([AndroidLocalOnlyHotspotController], [AndroidWifiNetworkSpecifierClient])
 * are validated through the manual checklist under
 * `docs/testing/interop-wifi-hotspot.md`.
 */
class WifiHotspotMediumProviderTest {
    @Test
    fun `medium is WIFI_HOTSPOT`() {
        val provider = WifiHotspotMediumProvider()
        assertThat(provider.medium).isEqualTo(Medium.WIFI_HOTSPOT)
    }

    @Test
    fun `isSupported returns false when neither controller nor client are wired`() {
        val provider = WifiHotspotMediumProvider()
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns true when the controller is wired`() {
        val provider =
            WifiHotspotMediumProvider(
                controller = NeverStartingController,
            )
        assertThat(provider.isSupported()).isTrue()
    }

    @Test
    fun `isSupported returns true when the client is wired`() {
        val provider =
            WifiHotspotMediumProvider(
                client = NeverJoiningClient,
            )
        assertThat(provider.isSupported()).isTrue()
    }

    @Test
    fun `isSupported respects an explicit availability override`() {
        val provider =
            WifiHotspotMediumProvider(
                controller = NeverStartingController,
                client = NeverJoiningClient,
                available = { false },
            )
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `prepareUpgrade returns null without a controller`() =
        runTest {
            val provider = WifiHotspotMediumProvider(client = NeverJoiningClient)
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade propagates a null reservation as null`() =
        runTest {
            val provider = WifiHotspotMediumProvider(controller = NeverStartingController)
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns the controllers credentials on success`() =
        runTest {
            val expected =
                UpgradePathCredentials.WifiHotspot(
                    ssid = "DIRECT-XX-WVMG",
                    passphrase = "longenoughpass",
                    port = 41201,
                    gateway = "192.168.49.1",
                )
            // Pin a deterministic free port so the ServerSocket bind
            // attempt below cannot collide with anything else on the
            // test runner. Bind, capture, close immediately — we only
            // need a fixture for the reservation, not an actually live
            // listener.
            val sock = ServerSocket(0)
            val captured = expected.copy(port = sock.localPort)
            val provider =
                WifiHotspotMediumProvider(
                    controller =
                        StaticController(
                            HotspotReservation(
                                credentials = captured,
                                serverSocket = sock,
                                teardown = { sock.close() },
                            ),
                        ),
                )
            assertThat(provider.prepareUpgrade()).isEqualTo(captured)
            sock.close()
        }

    @Test
    fun `adoptUpgrade returns null without a client`() =
        runTest {
            val provider = WifiHotspotMediumProvider(controller = NeverStartingController)
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiHotspot(
                        ssid = "x",
                        passphrase = "y",
                        port = 1,
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade rejects credentials for the wrong medium`() =
        runTest {
            val provider = WifiHotspotMediumProvider(client = NeverJoiningClient)
            val wrong = UpgradePathCredentials.Generic(Medium.BLUETOOTH)
            assertThat(provider.adoptUpgrade(wrong)).isNull()
        }

    @Test
    fun `adoptUpgrade returns null when the client cannot join`() =
        runTest {
            val provider = WifiHotspotMediumProvider(client = NeverJoiningClient)
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiHotspot(
                        ssid = "x",
                        passphrase = "y",
                        port = 1,
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade wraps a successful join in a WifiHotspotTransport`() =
        runTest {
            // Use a loopback ServerSocket as the join target so we can
            // verify the transport carries a real, connected Socket.
            val server = ServerSocket(0)
            try {
                val joinedSocket = Socket("127.0.0.1", server.localPort)
                server.accept().use { /* discard server-side */ }
                var torndown = false
                val provider =
                    WifiHotspotMediumProvider(
                        client =
                            StaticClient(
                                JoinResult(
                                    socket = joinedSocket,
                                    teardown = { torndown = true },
                                ),
                            ),
                    )
                val transport =
                    provider.adoptUpgrade(
                        UpgradePathCredentials.WifiHotspot(
                            ssid = "x",
                            passphrase = "y",
                            port = server.localPort,
                            gateway = "127.0.0.1",
                        ),
                    )
                assertThat(transport).isInstanceOf(WifiHotspotTransport::class.java)
                val hotspotTransport = transport as WifiHotspotTransport
                assertThat(hotspotTransport.medium).isEqualTo(Medium.WIFI_HOTSPOT)
                assertThat(hotspotTransport.socket).isSameInstanceAs(joinedSocket)

                // release() must close the socket and trigger teardown
                // exactly once, and be safe to call twice.
                hotspotTransport.release()
                assertThat(joinedSocket.isClosed).isTrue()
                assertThat(torndown).isTrue()
                hotspotTransport.release() // idempotent
            } finally {
                server.close()
            }
        }

    // --- fakes ---

    private object NeverStartingController : HotspotController {
        override suspend fun start(): HotspotReservation? = null
    }

    private object NeverJoiningClient : HotspotClient {
        override suspend fun join(credentials: UpgradePathCredentials.WifiHotspot): JoinResult? = null
    }

    private class StaticController(
        private val reservation: HotspotReservation,
    ) : HotspotController {
        override suspend fun start(): HotspotReservation = reservation
    }

    private class StaticClient(
        private val joined: JoinResult,
    ) : HotspotClient {
        override suspend fun join(credentials: UpgradePathCredentials.WifiHotspot): JoinResult = joined
    }
}
