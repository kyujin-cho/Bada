/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

/**
 * JVM-only fallback-path coverage for [BluetoothRfcommMediumProvider]
 * (#51).
 *
 * Happy-path coverage (real RFCOMM accept + connect against another
 * device) is intentionally manual — see
 * `docs/testing/interop-bluetooth-rfcomm.md`. Instantiating real
 * `BluetoothServerSocket` / `BluetoothSocket` from a host JVM requires
 * Robolectric, which the module deliberately does not pull in (see the
 * BleAdvertiserFallbackTest for the same precedent).
 *
 * These tests target the decision paths that determine whether the
 * surrounding UX still works on devices where Bluetooth is off, the
 * permission was revoked, the listener fails, or the peer sent
 * misshapen credentials.
 */
class BluetoothRfcommMediumProviderTest {
    @Test
    fun `medium is BLUETOOTH`() {
        val provider = providerWith(FakeBluetoothIo(available = false))
        assertThat(provider.medium).isEqualTo(Medium.BLUETOOTH)
    }

    @Test
    fun `isSupported returns false when the device is unavailable`() {
        val provider = providerWith(FakeBluetoothIo(available = false))
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns true when the device is available`() {
        val provider = providerWith(FakeBluetoothIo(available = true))
        assertThat(provider.isSupported()).isTrue()
    }

    @Test
    fun `prepareUpgrade returns null when the local MAC is unavailable`() =
        runTest {
            // localMacAddressBytes returning null means "Android handed
            // us the 02:00:00:00:00:00 sentinel" — the framework drops
            // the upgrade attempt rather than emitting credentials no
            // sender can use to reach us.
            val provider = providerWith(FakeBluetoothIo(localMac = null))
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns null when listen throws`() =
        runTest {
            val provider =
                providerWith(
                    FakeBluetoothIo(
                        localMac = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                        listenError = IOException("SDP record registration failed"),
                    ),
                )
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns null when listen returns null`() =
        runTest {
            // Simulates the Android edge-case where the platform returns
            // a null server socket (rare but documented when the adapter
            // toggles off mid-call).
            val provider =
                providerWith(
                    FakeBluetoothIo(
                        localMac = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                        serverSocket = null,
                    ),
                )
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `adoptUpgrade rejects non-Bluetooth credentials`() =
        runTest {
            val provider = providerWith(FakeBluetoothIo(available = true))
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.WifiLan(
                        ipAddress = byteArrayOf(10, 0, 0, 1),
                        port = 4433,
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade rejects malformed service UUID`() =
        runTest {
            // Simulate a peer (or a corrupted decode path) handing us a
            // non-UUID string in serviceUuid. The adopt path must drop
            // it gracefully so the orchestrator can fall through to
            // UPGRADE_FAILURE on the wire.
            val provider = providerWith(FakeBluetoothIo(available = true))
            // Build the credentials object directly so we can stuff a
            // non-UUID string past the constructor's "must not be empty"
            // guard. The proto decode path already rejects malformed
            // MAC bytes before constructing this; serviceUuid is only
            // validated by parse-time consumers like adoptUpgrade.
            val malformed =
                UpgradePathCredentials.Bluetooth(
                    macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                    serviceUuid = "not-a-uuid",
                )
            assertThat(provider.adoptUpgrade(malformed)).isNull()
        }

    @Test
    fun `adoptUpgrade returns null when connect throws IOException`() =
        runTest {
            val provider =
                providerWith(
                    FakeBluetoothIo(
                        connectError = IOException("connection refused"),
                    ),
                )
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.Bluetooth(
                        macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                        serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade returns null when connect returns null socket`() =
        runTest {
            val provider =
                providerWith(
                    FakeBluetoothIo(clientSocket = null),
                )
            val result =
                provider.adoptUpgrade(
                    UpgradePathCredentials.Bluetooth(
                        macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                        serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
                    ),
                )
            assertThat(result).isNull()
        }

    @Test
    fun `adoptUpgrade reformats raw MAC bytes back to colon-separated string before connecting`() =
        runTest {
            val io = FakeBluetoothIo(connectError = IOException("stop after recording call"))
            val provider = providerWith(io)
            val mac =
                byteArrayOf(
                    0xAA.toByte(),
                    0xBB.toByte(),
                    0xCC.toByte(),
                    0xDD.toByte(),
                    0xEE.toByte(),
                    0xFF.toByte(),
                )
            provider.adoptUpgrade(
                UpgradePathCredentials.Bluetooth(
                    macAddress = mac,
                    serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
                ),
            )
            // The connect path is the load-bearing piece: the platform
            // expects a colon-separated string MAC, not raw bytes.
            assertThat(io.lastConnectMac).isEqualTo("AA:BB:CC:DD:EE:FF")
            assertThat(io.lastConnectUuid)
                .isEqualTo(UUID.fromString("a82efa21-ae5c-3dde-9bbc-f16da7b16c1a"))
        }

    @Test
    fun `default service UUID is the documented fixed value`() {
        // Pinned so a future refactor can't silently change the wire
        // value the receiver advertises without updating
        // docs/testing/interop-bluetooth-rfcomm.md alongside.
        assertThat(BluetoothRfcommMediumProvider.DEFAULT_SERVICE_UUID.toString())
            .isEqualTo("a82efa21-ae5c-3dde-9bbc-f16da7b16c1a")
    }

    @Test
    fun `default service name matches DEFAULT_SERVICE_NAME constant`() {
        assertThat(BluetoothRfcommMediumProvider.DEFAULT_SERVICE_NAME)
            .isEqualTo("WvmgQuickShareRfcomm")
    }

    private fun providerWith(io: BluetoothRfcommMediumProvider.BluetoothIo): BluetoothRfcommMediumProvider =
        BluetoothRfcommMediumProvider(
            bluetooth = io,
            serviceUuid = UUID.fromString("a82efa21-ae5c-3dde-9bbc-f16da7b16c1a"),
            serviceName = "test-service",
        )

    /**
     * In-memory fake of [BluetoothRfcommMediumProvider.BluetoothIo].
     * Returning real `BluetoothServerSocket` / `BluetoothSocket`
     * instances from a host JVM is not possible without Robolectric, so
     * the tests that need a non-null socket exercise a "no, it returned
     * null" path instead — that's the same edge-case the platform
     * documents under "adapter toggled off mid-call".
     *
     * Setting [listenError] / [connectError] makes the corresponding
     * call throw; setting them null and the matching socket field to
     * null makes the call return null. The two paths converge on the
     * same observable outcome (provider returns null upgrade) but go
     * through different code paths in the provider — both are covered.
     */
    private class FakeBluetoothIo(
        val available: Boolean = true,
        val localMac: ByteArray? = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        val serverSocket: BluetoothServerSocket? = null,
        val listenError: Throwable? = null,
        val clientSocket: BluetoothSocket? = null,
        val connectError: Throwable? = null,
    ) : BluetoothRfcommMediumProvider.BluetoothIo {
        var lastConnectMac: String? = null
        var lastConnectUuid: UUID? = null

        override fun isAvailable(): Boolean = available

        override fun localMacAddressBytes(): ByteArray? = localMac

        override fun listen(
            name: String,
            uuid: UUID,
        ): BluetoothServerSocket? {
            listenError?.let { throw it }
            return serverSocket
        }

        override fun connect(
            macAddress: String,
            uuid: UUID,
        ): BluetoothSocket? {
            lastConnectMac = macAddress
            lastConnectUuid = uuid
            connectError?.let { throw it }
            return clientSocket
        }
    }
}
