/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import android.bluetooth.le.AdvertiseSettings
import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.BleServiceData
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Lifecycle and fallback tests for [BleQuickShareAdvertiser] (issue #121).
 *
 * The happy path on real hardware (`startAdvertising` succeeds and the
 * `AdvertiseCallback.onStartSuccess` fires) is verified manually via
 * the `docs/testing/` runbooks because instantiating a functioning
 * `BluetoothLeAdvertiser` from a host JVM requires Robolectric, which
 * the discovery module deliberately does not pull in.
 *
 * These tests exercise:
 *  * permission-denied → `start` returns false, no platform call,
 *  * no platform advertiser → `start` returns false, no payload built,
 *  * `start` accepts → `isAdvertising == true`, payload contains the
 *    canonical Galaxy fingerprint,
 *  * `setAdvertiseMode` is idempotent on the current mode and
 *    re-registers when the mode changes,
 *  * `stop` is idempotent and tears the registration down.
 */
class BleQuickShareAdvertiserTest {
    @Test
    fun `start returns false when permission is denied`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { false },
            )
        val started = advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        assertThat(started).isFalse()
        assertThat(gate.startCalls).isEmpty()
        assertThat(advertiser.isAdvertising).isFalse()
    }

    @Test
    fun `start returns false when the platform has no advertiser`() {
        val gate =
            object : BleAdvertiserGate {
                override fun startAdvertising(
                    serviceData: ByteArray,
                    mode: Int,
                ): BleAdvertiserGate.Registration? = null
            }
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val started = advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        assertThat(started).isFalse()
        assertThat(advertiser.isAdvertising).isFalse()
    }

    @Test
    fun `start submits the canonical fast-advertisement payload`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val info = hiddenPhone()
        val started = advertiser.start(info, endpointId("Wvmg"))
        assertThat(started).isTrue()
        assertThat(advertiser.isAdvertising).isTrue()
        assertThat(gate.startCalls).hasSize(1)

        val (payload, mode) = gate.startCalls.last()
        // The default mode is BALANCED until the lifecycle observer
        // upgrades it to LOW_LATENCY. Pinning it here catches drift.
        assertThat(mode).isEqualTo(AdvertiseSettings.ADVERTISE_MODE_BALANCED)

        // Byte 0 is the canonical 0x23 (version=1, PCP=3); byte 5 is
        // the EndpointInfo length (17 for hidden); byte 6 is the
        // EndpointInfo header (0x32 = hidden + version=1 + PHONE).
        assertThat(payload).hasLength(BleServiceData.FIXED_HEADER_LEN + info.serialize().size)
        assertThat(payload[0].toInt() and 0xFF).isEqualTo(0x23)
        assertThat(payload.copyOfRange(1, 5)).isEqualTo(endpointId("Wvmg"))
        assertThat(payload[5].toInt() and 0xFF).isEqualTo(0x11)
        assertThat(payload[6].toInt() and 0xFF).isEqualTo(0x32)
    }

    @Test
    fun `start rejects an endpoint_id of the wrong length`() {
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = RecordingGate(failOnStart = false),
                permissionChecker = { true },
            )
        assertThrows<IllegalArgumentException> {
            advertiser.start(hiddenPhone(), ByteArray(3))
        }
        assertThrows<IllegalArgumentException> {
            advertiser.start(hiddenPhone(), ByteArray(5))
        }
    }

    @Test
    fun `start replaces an in-flight registration with the new identity`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("AAAA"))
        advertiser.start(hiddenPhone(), endpointId("BBBB"))

        // First registration should be closed before the second was opened.
        assertThat(gate.startCalls).hasSize(2)
        assertThat(gate.firstRegistration?.closed).isTrue()
        assertThat(gate.lastRegistration?.closed).isFalse()

        // Bytes 1..4 of the latest payload reflect the second endpoint_id.
        val (payload, _) = gate.startCalls.last()
        assertThat(payload.copyOfRange(1, 5)).isEqualTo(endpointId("BBBB"))
    }

    @Test
    fun `start is idempotent on identity-unchanged calls`() {
        // Regression guard: the MdnsAdvertisementGate decision loop calls
        // start() on every BLE-scan re-arm during steady-state publishing.
        // A naive teardown-then-rebuild leaves a brief gap in the BLE
        // pulse and churns the host BT stack. When the identity bytes are
        // unchanged from the active registration, the second start() must
        // be a true no-op on the platform.
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val info = hiddenPhone()
        val id = endpointId("Wvmg")

        advertiser.start(info, id)
        advertiser.start(info, id.copyOf()) // distinct array, equal contents

        assertThat(gate.startCalls).hasSize(1)
        assertThat(gate.lastRegistration?.closed).isFalse()
    }

    @Test
    fun `stop is idempotent and tears the registration down`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        advertiser.stop()
        assertThat(advertiser.isAdvertising).isFalse()
        assertThat(gate.lastRegistration?.closed).isTrue()

        // Second stop is a no-op.
        advertiser.stop()
        assertThat(advertiser.isAdvertising).isFalse()
    }

    @Test
    fun `setAdvertiseMode while idle just pins the next start mode`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        // Idle: setAdvertiseMode should not invoke the platform.
        val ok = advertiser.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        assertThat(ok).isTrue()
        assertThat(gate.startCalls).isEmpty()
        assertThat(advertiser.activeAdvertiseMode)
            .isEqualTo(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)

        // Subsequent start should use the pinned mode.
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        assertThat(gate.startCalls.last().second)
            .isEqualTo(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    }

    @Test
    fun `setAdvertiseMode is idempotent on the current mode`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        val before = gate.startCalls.size
        // Already on BALANCED — must be a no-op.
        val ok = advertiser.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        assertThat(ok).isTrue()
        assertThat(gate.startCalls).hasSize(before)
    }

    @Test
    fun `setAdvertiseMode while active re-registers with the new mode`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        val ok = advertiser.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        assertThat(ok).isTrue()
        assertThat(gate.startCalls).hasSize(2)
        assertThat(gate.startCalls.last().second)
            .isEqualTo(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        // First registration was torn down; second is still live.
        assertThat(gate.firstRegistration?.closed).isTrue()
        assertThat(gate.lastRegistration?.closed).isFalse()
        assertThat(advertiser.isAdvertising).isTrue()
    }

    @Test
    fun `setAdvertiseMode reverts to stopped state when re-register fails`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        gate.failOnStart = true
        val ok = advertiser.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        assertThat(ok).isFalse()
        assertThat(advertiser.isAdvertising).isFalse()
    }

    @Test
    fun `start after stop re-registers successfully`() {
        // Regression guard: stop() must clear the active registration state
        // so a subsequent start() can open a fresh platform registration.
        // A buggy implementation that leaves currentEndpointInfo non-null
        // would short-circuit on identity-unchanged comparison and return
        // true without touching the gate — hiding the fact that the
        // platform registration was already closed.
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        advertiser.stop()
        assertThat(advertiser.isAdvertising).isFalse()

        // Re-start: must open a fresh platform registration even though
        // the identity bytes are identical to the previous run.
        val restarted = advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        assertThat(restarted).isTrue()
        assertThat(advertiser.isAdvertising).isTrue()
        // Two total calls: first start + re-start (stop clears state so
        // the idempotency guard does not block the second start).
        assertThat(gate.startCalls).hasSize(2)
        assertThat(gate.lastRegistration?.closed).isFalse()
    }

    @Test
    fun `start returns false when payload factory throws`() {
        // The try/catch around payloadFactory.build must swallow the error
        // and return false so the receiver continues in mDNS-only mode
        // instead of crashing or leaving an inconsistent registration.
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
                payloadFactory = { _, _ -> error("synthetic payload build failure") },
            )
        val started = advertiser.start(hiddenPhone(), endpointId("Wvmg"))
        assertThat(started).isFalse()
        assertThat(advertiser.isAdvertising).isFalse()
        // The platform gate must not have been contacted at all.
        assertThat(gate.startCalls).isEmpty()
    }

    private fun hiddenPhone(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = true,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x00 },
            deviceName = null,
        )

    private fun endpointId(s: String): ByteArray {
        require(s.length == BleServiceData.ENDPOINT_ID_LEN)
        return s.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Test double for the platform [BleAdvertiserGate]. Records each
     * `startAdvertising` call as a `(payload, mode)` pair and tracks
     * whether the resulting registration was subsequently closed.
     */
    private class RecordingGate(
        @Volatile var failOnStart: Boolean,
    ) : BleAdvertiserGate {
        val startCalls: MutableList<Pair<ByteArray, Int>> = mutableListOf()
        val registrations: MutableList<RecordingRegistration> = mutableListOf()

        val firstRegistration: RecordingRegistration?
            get() = registrations.firstOrNull()
        val lastRegistration: RecordingRegistration?
            get() = registrations.lastOrNull()

        override fun startAdvertising(
            serviceData: ByteArray,
            mode: Int,
        ): BleAdvertiserGate.Registration? {
            startCalls += serviceData to mode
            if (failOnStart) return null
            val reg = RecordingRegistration()
            registrations += reg
            return reg
        }
    }

    private class RecordingRegistration : BleAdvertiserGate.Registration {
        @Volatile
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }
}
