/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.discovery.AdvertiseHandle
import io.github.kyujincho.wvmg.discovery.ble.ScanActivity
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import io.github.kyujincho.wvmg.protocol.payload.TempFileDestinationFactory
import io.github.kyujincho.wvmg.protocol.server.TcpReceiverServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.security.SecureRandom

/**
 * JVM unit tests for [MdnsAdvertisementGate] (#34).
 *
 * The gate logic is exercised against:
 *  * a real (loopback-bound) [TcpReceiverServer] hosted in a
 *    [ReceiverSession] constructed with `advertiseGated = true`,
 *  * a [RecordingAdvertiser] that captures publish/unpublish via
 *    `AdvertiseHandle.close()`,
 *  * three [MutableStateFlow]s for BLE activity, manual override, and
 *    QR session — driven directly from the test.
 *
 * The 30-second debounce window is exercised via `runTest`'s virtual
 * scheduler — `advanceTimeBy` lets us cross the threshold deterministically
 * without sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MdnsAdvertisementGateTest {
    @Test
    fun `idle activity at boot does not publish`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()

            assertThat(advertiser.calls).isEmpty()
            assertThat(session.isAdvertising).isFalse()

            gate.stop()
            session.stop()
        }

    @Test
    fun `BLE active publishes immediately`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()

            ble.value = ScanActivity.Active(lastSeenAtMillis = 1_000L)
            advanceUntilIdle()

            assertThat(advertiser.calls).hasSize(1)
            assertThat(session.isAdvertising).isTrue()
            assertThat(advertiser.calls[0].handle.isActive).isTrue()

            gate.stop()
            session.stop()
        }

    @Test
    fun `BLE active then idle holds publish for 30s before unpublishing`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                    debounceIdleMillis = 30_000L,
                )
            gate.start(this)
            advanceUntilIdle()

            ble.value = ScanActivity.Active(lastSeenAtMillis = 1_000L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            // Flip back to idle: the advertisement must remain up for at
            // least the debounce window. Use `advanceTimeBy` (NOT
            // `advanceUntilIdle` — that would jump forward to the
            // debounce timer's scheduled fire time).
            ble.value = ScanActivity.Idle
            // First a small advance to flush the StateFlow emission
            // through the collector so the debounce timer is actually
            // scheduled.
            advanceTimeBy(1L)
            advanceTimeBy(29_998L)
            assertThat(session.isAdvertising).isTrue()

            // Cross the 30-s threshold — the gate unpublishes.
            advanceTimeBy(10L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()
            assertThat(advertiser.calls[0].handle.isActive).isFalse()

            gate.stop()
            session.stop()
        }

    @Test
    fun `flapping BLE pulse keeps advertisement up`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                    debounceIdleMillis = 30_000L,
                )
            gate.start(this)
            advanceUntilIdle()

            ble.value = ScanActivity.Active(lastSeenAtMillis = 1_000L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            // Idle for 20s — within the debounce window. Use
            // advanceTimeBy (NOT advanceUntilIdle, which would jump to
            // the debounce timer's scheduled fire time).
            ble.value = ScanActivity.Idle
            advanceTimeBy(1L)
            advanceTimeBy(20_000L)
            assertThat(session.isAdvertising).isTrue()

            // Active again before the timer fires; the pending unpublish
            // must be cancelled so a long idle stretch later does not
            // accidentally tear the advertisement down.
            ble.value = ScanActivity.Active(lastSeenAtMillis = 21_000L)
            advanceTimeBy(1L)

            // Wait long enough that, if the original timer were still
            // armed, it would have fired. With the cancel + new Active
            // signal, no debounce is in flight, so advanceUntilIdle
            // doesn't have any pending future-time tasks to consume.
            advanceTimeBy(15_000L)
            advanceUntilIdle()

            // Still up: the only AdvertiseHandle is the original one
            // and it remains active.
            assertThat(session.isAdvertising).isTrue()
            assertThat(advertiser.calls).hasSize(1)
            assertThat(advertiser.calls[0].handle.isActive).isTrue()

            gate.stop()
            session.stop()
        }

    @Test
    fun `manual override forces publish without BLE pulse`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()

            override.value = true
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            // Override flipped off: gate schedules the debounced unpublish.
            // advanceUntilIdle would jump to the timer's fire time anyway,
            // so use it here to deterministically reach the unpublish.
            override.value = false
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()

            gate.stop()
            session.stop()
        }

    @Test
    fun `QR session forces publish and bypasses gating`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()

            qr.value = true
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            // Even after the configured idle window passes the
            // advertisement stays up because QR is still active.
            // No debounce is scheduled (shouldPublish is true), so
            // advanceUntilIdle has nothing to consume past current time.
            advanceTimeBy(60_000L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            qr.value = false
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()

            gate.stop()
            session.stop()
        }

    @Test
    fun `override stays publish when BLE goes idle`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(true)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            // BLE flips active then idle; advertisement must stay up
            // because the override is still true.
            ble.value = ScanActivity.Active(lastSeenAtMillis = 1_000L)
            advanceUntilIdle()
            ble.value = ScanActivity.Idle
            advanceTimeBy(60_000L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            gate.stop()
            session.stop()
        }

    @Test
    fun `gate stop does not unpublish in-flight advertisement`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Active(lastSeenAtMillis = 1_000L))
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()

            gate.stop()
            advanceUntilIdle()
            // Stopping the gate does not in itself tear the advertisement
            // down; the session's stop() owns that order.
            assertThat(session.isAdvertising).isTrue()

            session.stop()
            assertThat(session.isAdvertising).isFalse()
        }

    @Test
    fun `gate start is idempotent`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Active(lastSeenAtMillis = 1_000L))
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                )
            gate.start(this)
            gate.start(this)
            advanceUntilIdle()

            // Only one publish call regardless of how many times start()
            // was invoked.
            assertThat(advertiser.calls).hasSize(1)

            gate.stop()
            session.stop()
        }

    @Test
    fun `BLE broadcaster starts and stops in lock-step with mDNS publish`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)
            val broadcaster = RecordingBleBroadcaster()

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                    debounceIdleMillis = 30_000L,
                    bleBroadcaster = broadcaster,
                )
            gate.start(this)
            advanceUntilIdle()
            // Idle at boot: BLE advertise stays off.
            assertThat(broadcaster.startCount).isEqualTo(0)

            // Pulse activity flips publish on; BLE must start in lock-step.
            ble.value = ScanActivity.Active(lastSeenAtMillis = 1_000L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isTrue()
            assertThat(broadcaster.startCount).isEqualTo(1)

            // Idle again: 30 s debounce holds both channels up. Use
            // advanceTimeBy to step through the debounce — advanceUntilIdle
            // would jump straight to the timer's fire time.
            ble.value = ScanActivity.Idle
            advanceTimeBy(1L)
            advanceTimeBy(29_998L)
            assertThat(session.isAdvertising).isTrue()
            assertThat(broadcaster.stopCount).isEqualTo(0)

            // Cross the debounce threshold: both channels unpublish
            // together.
            advanceTimeBy(10L)
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()
            assertThat(broadcaster.stopCount).isAtLeast(1)

            gate.stop()
            session.stop()
        }

    @Test
    fun `outbound veto stops BLE advertise immediately bypassing debounce`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Active(lastSeenAtMillis = 1_000L))
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)
            val outbound = MutableStateFlow(false)
            val broadcaster = RecordingBleBroadcaster()

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                    outboundSessionActive = outbound,
                    debounceIdleMillis = 30_000L,
                    bleBroadcaster = broadcaster,
                )
            gate.start(this)
            advanceUntilIdle()
            // Active pulse + no outbound: both channels advertising.
            assertThat(session.isAdvertising).isTrue()
            assertThat(broadcaster.startCount).isAtLeast(1)
            val baselineStops = broadcaster.stopCount

            // Outbound flips on: must immediately tear down both
            // channels, not waiting on the debounce.
            outbound.value = true
            advanceUntilIdle()
            assertThat(session.isAdvertising).isFalse()
            assertThat(broadcaster.stopCount).isGreaterThan(baselineStops)

            gate.stop()
            session.stop()
        }

    @Test
    fun `BLE broadcaster throwing on start is swallowed and mDNS still publishes`() =
        runTest {
            val advertiser = RecordingAdvertiser()
            val session = startedGatedSession(advertiser = advertiser)
            val ble = MutableStateFlow<ScanActivity>(ScanActivity.Idle)
            val override = MutableStateFlow(false)
            val qr = MutableStateFlow(false)

            // A broadcaster whose start() throws — should be swallowed by
            // startBleSafely so the gate's mDNS publish path is unaffected.
            val throwingBroadcaster =
                object : BleVisibilityBroadcaster {
                    override fun start() = error("synthetic BLE advertise failure")

                    override fun stop() = Unit
                }

            val gate =
                MdnsAdvertisementGate(
                    session = session,
                    bleActivity = ble,
                    alwaysVisibleOverride = override,
                    qrSessionActive = qr,
                    bleBroadcaster = throwingBroadcaster,
                )
            gate.start(this)
            advanceUntilIdle()

            override.value = true
            advanceUntilIdle()

            // mDNS must have published despite the BLE broadcaster throwing.
            assertThat(session.isAdvertising).isTrue()
            assertThat(advertiser.calls).hasSize(1)

            gate.stop()
            session.stop()
        }

    // --------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------

    /**
     * Build a [ReceiverSession] configured with `advertiseGated = true`,
     * start it (so the TCP listener binds), and return it ready for the
     * gate to drive publish/unpublish against.
     */
    private fun startedGatedSession(advertiser: RecordingAdvertiser): ReceiverSession {
        val session =
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
                factoryProvider = { TempFileDestinationFactory() },
                endpointInfo = sampleEndpointInfo(),
                advertiseGated = true,
            )
        runBlocking { session.start() }
        return session
    }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "Gate Test",
            tlvRecords = emptyList(),
        )

    /**
     * Recording [DiscoveryAdvertiser] that captures every advertise call
     * and produces a fake [AdvertiseHandle] tracking active state. Same
     * shape as the recorder in [ReceiverSessionTest] but lifted here as
     * a top-level fixture rather than a private inner class so the gate
     * tests can extend it if needed.
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
     * Recording [BleVisibilityBroadcaster] that counts start/stop
     * invocations. Used by the lock-step tests to verify BLE-side
     * advertise mirrors mDNS publish exactly.
     */
    private class RecordingBleBroadcaster : BleVisibilityBroadcaster {
        @Volatile
        var startCount: Int = 0
            private set

        @Volatile
        var stopCount: Int = 0
            private set

        override fun start() {
            startCount += 1
        }

        override fun stop() {
            stopCount += 1
        }
    }

    private class FakeAdvertiseHandle(
        override val port: Int,
        override val instanceName: String = "wvmg-gate-test",
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
