/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("LongParameterList") // FakeIo's per-knob constructor reads more cleanly than a builder.

package io.github.kyujincho.wvmg.discovery.medium

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * JVM unit tests for [BleL2capMediumProvider] — exercise every branch
 * of `isSupported`, `prepareUpgrade`, and `adoptUpgrade` via the
 * [BluetoothL2capIo] indirection.
 *
 * The provider's API-level gate (API 29+) is consulted through
 * [BluetoothL2capIo.apiLevel] so tests can simulate any API level
 * without touching `android.os.Build.VERSION` (whose stub-jar bytecode
 * crashes JVM class-resolution at JUnit's discovery stage).
 *
 * The fake IO follows the same pattern as the
 * `BluetoothRfcommMediumProviderTest` shipped by sibling issue #51:
 * straightforward Kotlin classes with explicit per-knob fields, no
 * typealias gymnastics. The previous attempt at #52 stalled trying to
 * over-engineer the fixture — keeping it deliberately concrete is the
 * lesson learned.
 */
class BleL2capMediumProviderTest {
    @Test
    fun `medium identity exposes BLE_L2CAP`() {
        val provider = BleL2capMediumProvider(FakeIo()).asProvider()
        assertThat(provider.medium).isEqualTo(Medium.BLE_L2CAP)
    }

    @Test
    fun `isSupported returns false on API below Q`() {
        val provider = BleL2capMediumProvider(FakeIo(apiLevel = API_PIE)).asProvider()
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns false when BLE hardware is absent`() {
        val io = FakeIo(apiLevel = API_Q, hasBleHardware = false)
        val provider = BleL2capMediumProvider(io).asProvider()
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns false when BLUETOOTH_CONNECT is missing`() {
        val io = FakeIo(apiLevel = API_Q, hasConnectPermission = false)
        val provider = BleL2capMediumProvider(io).asProvider()
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns false when adapter is disabled`() {
        val io = FakeIo(apiLevel = API_Q, isBluetoothEnabled = false)
        val provider = BleL2capMediumProvider(io).asProvider()
        assertThat(provider.isSupported()).isFalse()
    }

    @Test
    fun `isSupported returns true on Q with hardware permission and adapter on`() {
        val provider = BleL2capMediumProvider(FakeIo(apiLevel = API_Q)).asProvider()
        assertThat(provider.isSupported()).isTrue()
    }

    @Test
    fun `prepareUpgrade returns BleL2cap credentials with PSM and MAC`() =
        runTest {
            val io =
                FakeIo(
                    apiLevel = API_Q,
                    listenPsm = 0x1080,
                    localMac = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60),
                )
            val provider = BleL2capMediumProvider(io).asProvider()
            val creds = provider.prepareUpgrade()
            assertThat(creds).isInstanceOf(UpgradePathCredentials.BleL2cap::class.java)
            val bleCreds = creds as UpgradePathCredentials.BleL2cap
            assertThat(bleCreds.psm).isEqualTo(0x1080)
            assertThat(bleCreds.macAddress).isEqualTo(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60))
        }

    @Test
    fun `prepareUpgrade returns null on API below Q`() =
        runTest {
            val provider = BleL2capMediumProvider(FakeIo(apiLevel = API_PIE)).asProvider()
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns null when adapter MAC is unavailable`() =
        runTest {
            val io = FakeIo(apiLevel = API_Q, localMac = null)
            val provider = BleL2capMediumProvider(io).asProvider()
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade returns null when listen returns null`() =
        runTest {
            val io = FakeIo(apiLevel = API_Q, listenReturnsNull = true)
            val provider = BleL2capMediumProvider(io).asProvider()
            assertThat(provider.prepareUpgrade()).isNull()
        }

    @Test
    fun `prepareUpgrade closes the listener and returns null if PSM is invalid`() =
        runTest {
            // PSM 0 is out of the uint16 [1..0xFFFF] range that
            // BleL2cap's init validates — the provider must catch the
            // IllegalArgumentException and clean up the listener.
            val io = FakeIo(apiLevel = API_Q, listenPsm = 0)
            val provider = BleL2capMediumProvider(io).asProvider()
            val creds = provider.prepareUpgrade()
            assertThat(creds).isNull()
            assertThat(io.lastListener?.closeCount).isEqualTo(1)
        }

    @Test
    fun `adoptUpgrade ignores credentials for the wrong medium`() =
        runTest {
            val provider = BleL2capMediumProvider(FakeIo(apiLevel = API_Q)).asProvider()
            val out = provider.adoptUpgrade(UpgradePathCredentials.Generic(Medium.BLUETOOTH))
            assertThat(out).isNull()
        }

    @Test
    fun `adoptUpgrade calls connect with formatted MAC and returns wrapped transport`() =
        runTest {
            val fakeChannel = FakeChannel()
            val io = FakeIo(apiLevel = API_Q, connectReturns = fakeChannel)
            val provider = BleL2capMediumProvider(io).asProvider()
            val creds =
                UpgradePathCredentials.BleL2cap(
                    macAddress = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x12, 0x34),
                    psm = 0x2222,
                )
            val transport = provider.adoptUpgrade(creds)
            assertThat(transport).isInstanceOf(BleL2capUpgradedTransport::class.java)
            assertThat((transport as BleL2capUpgradedTransport).channel).isSameInstanceAs(fakeChannel)
            assertThat(transport.medium).isEqualTo(Medium.BLE_L2CAP)
            // Verify the MAC was formatted in the canonical AA:BB:... form.
            assertThat(io.lastConnectMac).isEqualTo("DE:AD:BE:EF:12:34")
            assertThat(io.lastConnectPsm).isEqualTo(0x2222)
        }

    @Test
    fun `adoptUpgrade returns null when connect fails`() =
        runTest {
            val io = FakeIo(apiLevel = API_Q, connectReturns = null)
            val provider = BleL2capMediumProvider(io).asProvider()
            val out =
                provider.adoptUpgrade(
                    UpgradePathCredentials.BleL2cap(
                        macAddress = ByteArray(UpgradePathCredentials.BleL2cap.MAC_ADDRESS_LENGTH),
                        psm = 0x1234,
                    ),
                )
            assertThat(out).isNull()
        }

    @Test
    fun `adoptUpgrade returns null on API below Q even with valid credentials`() =
        runTest {
            val provider = BleL2capMediumProvider(FakeIo(apiLevel = API_PIE)).asProvider()
            val out =
                provider.adoptUpgrade(
                    UpgradePathCredentials.BleL2cap(
                        macAddress = ByteArray(UpgradePathCredentials.BleL2cap.MAC_ADDRESS_LENGTH),
                        psm = 0x1234,
                    ),
                )
            assertThat(out).isNull()
        }

    private class FakeIo(
        override val apiLevel: Int = API_Q,
        private val hasConnectPermission: Boolean = true,
        private val hasBleHardware: Boolean = true,
        private val isBluetoothEnabled: Boolean = true,
        private val listenPsm: Int = 0x1234,
        private val listenReturnsNull: Boolean = false,
        private val localMac: ByteArray? = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        private val connectReturns: L2capChannel? = null,
    ) : BluetoothL2capIo {
        var lastListener: FakeListener? = null
            private set
        var lastConnectMac: String? = null
            private set
        var lastConnectPsm: Int? = null
            private set

        override fun hasConnectPermission(): Boolean = hasConnectPermission

        override fun hasBleHardware(): Boolean = hasBleHardware

        override fun isBluetoothEnabled(): Boolean = isBluetoothEnabled

        override fun listen(): BluetoothL2capIo.Listener? {
            if (listenReturnsNull) return null
            val listener = FakeListener(listenPsm)
            lastListener = listener
            return listener
        }

        override fun connect(
            macAddress: String,
            psm: Int,
        ): L2capChannel? {
            lastConnectMac = macAddress
            lastConnectPsm = psm
            return connectReturns
        }

        override fun localMacBytes(): ByteArray? = localMac
    }

    private class FakeListener(
        override val psm: Int,
    ) : BluetoothL2capIo.Listener {
        var closeCount: Int = 0
            private set

        override fun accept(): L2capChannel = error("not used in these tests")

        override fun close() {
            closeCount++
        }
    }

    /**
     * Concrete [L2capChannel] for tests — backed by
     * `ByteArrayInputStream` / `ByteArrayOutputStream` so the channel
     * has working but empty streams. The streams are never exercised
     * here; the channel only exists so [BleL2capUpgradedTransport] has
     * something non-null to wrap.
     */
    private class FakeChannel : L2capChannel {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    private companion object {
        // Build.VERSION_CODES.Q = 29; Build.VERSION_CODES.P = 28. Hard-coded
        // here because tests must not transitively load android.os.Build —
        // its fields' bytecode on the AGP unit-test stub jar lacks `Code`
        // attributes and crashes class-resolution at JUnit discovery.
        const val API_Q: Int = 29
        const val API_PIE: Int = 28
    }
}
