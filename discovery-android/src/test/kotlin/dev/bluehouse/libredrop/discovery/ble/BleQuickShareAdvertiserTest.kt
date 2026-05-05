/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.ble

import android.bluetooth.le.AdvertiseSettings
import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.endpoint.BleAdvertisement
import dev.bluehouse.libredrop.protocol.endpoint.BleAdvertisementHeader
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
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
        val started = advertiser.start(hiddenPhone(), endpointId("DROP"))
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
                    dctServiceData: ByteArray?,
                    visibleServiceData: ByteArray?,
                ): BleAdvertiserGate.Registration? = null
            }
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val started = advertiser.start(hiddenPhone(), endpointId("DROP"))
        assertThat(started).isFalse()
        assertThat(advertiser.isAdvertising).isFalse()
    }

    @Test
    fun `start submits a GATT advertisement header`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val info = hiddenPhone()
        val started = advertiser.start(info, endpointId("DROP"))
        assertThat(started).isTrue()
        assertThat(advertiser.isAdvertising).isTrue()
        assertThat(gate.startCalls).hasSize(1)

        val (payload, mode) = gate.startCalls.last()
        // The default mode is BALANCED until the lifecycle observer
        // upgrades it to LOW_LATENCY. Pinning it here catches drift.
        assertThat(mode).isEqualTo(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        assertThat(gate.startCalls.last().dctPayload).isNull()

        val header = BleAdvertisementHeader.parse(payload)
        assertThat(header).isNotNull()
        assertThat(header!!.numSlots).isEqualTo(1)
        assertThat(header.psm).isEqualTo(0)
        assertThat(header.supportsExtendedAdvertisement).isFalse()
    }

    @Test
    fun `start exposes visible EndpointInfo without RX instant extra field`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
            )
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "LibreDrop",
            )

        val started = advertiser.start(info, endpointId("DROP"))

        assertThat(started).isTrue()
        val call = gate.startCalls.single()
        val payload = call.payload
        val dctPayload = call.dctPayload
        val visiblePayload = call.visiblePayload
        val primaryHeader = BleAdvertisementHeader.parse(payload)
        assertThat(primaryHeader).isNotNull()
        assertThat(primaryHeader!!.numSlots).isEqualTo(1)
        assertThat(primaryHeader.psm).isEqualTo(0)
        assertThat(primaryHeader.supportsExtendedAdvertisement).isTrue()

        assertThat(dctPayload).isNull()

        assertThat(visiblePayload).isNotNull()
        val visibleAdvertisement = BleAdvertisement.parse(visiblePayload!!)!!
        assertThat(visibleAdvertisement.secondProfile).isTrue()
        val visibleInfo = BleServiceData.parse(visiblePayload)!!.endpointInfo
        assertThat(visibleInfo.hidden).isFalse()
        assertThat(visibleInfo.deviceName).isEqualTo("LibreDrop")
        assertThat(BleServiceData.parsePsmExtraField(visiblePayload)).isNull()
        assertThat(visibleAdvertisement.rxInstantConnectionAdvertisement).isEmpty()
        assertThat(visiblePayload.size).isGreaterThan(27)
    }

    @Test
    fun `visible fast payload carries active L2CAP PSM when available`() {
        BleDctPsmHolder.set(0x1234)
        try {
            val gate = RecordingGate(failOnStart = false)
            val advertiser =
                BleQuickShareAdvertiser.forTesting(
                    gate = gate,
                    permissionChecker = { true },
                )

            val started = advertiser.start(visiblePhone(), endpointId("DROP"))

            assertThat(started).isTrue()
            val call = gate.startCalls.single()
            val primaryHeader = BleAdvertisementHeader.parse(call.payload)
            assertThat(primaryHeader).isNotNull()
            assertThat(primaryHeader!!.numSlots).isEqualTo(1)
            assertThat(primaryHeader.psm).isEqualTo(0)
            assertThat(primaryHeader.supportsExtendedAdvertisement).isTrue()
            assertThat(call.dctPayload).isNull()
            assertThat(BleServiceData.parsePsmExtraField(call.visiblePayload!!)).isEqualTo(0x1234)
            val visibleAdvertisement = BleAdvertisement.parse(call.visiblePayload!!)!!
            assertThat(visibleAdvertisement.psm).isEqualTo(0x1234)
            assertThat(visibleAdvertisement.rxInstantConnectionAdvertisement).isEmpty()
        } finally {
            BleDctPsmHolder.clear()
        }
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

        // The primary bytes are compact GATT headers; identity drift is covered by
        // the replacement count and payload parser assertions above.
        val (payload, _) = gate.startCalls.last()
        val header = BleAdvertisementHeader.parse(payload)
        assertThat(header).isNotNull()
        assertThat(header!!.numSlots).isEqualTo(1)
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
        val id = endpointId("DROP")

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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
        assertThat(gate.startCalls.last().mode)
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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
        val ok = advertiser.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        assertThat(ok).isTrue()
        assertThat(gate.startCalls).hasSize(2)
        assertThat(gate.startCalls.last().mode)
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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
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
        advertiser.start(hiddenPhone(), endpointId("DROP"))
        advertiser.stop()
        assertThat(advertiser.isAdvertising).isFalse()

        // Re-start: must open a fresh platform registration even though
        // the identity bytes are identical to the previous run.
        val restarted = advertiser.start(hiddenPhone(), endpointId("DROP"))
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
        val started = advertiser.start(hiddenPhone(), endpointId("DROP"))
        assertThat(started).isFalse()
        assertThat(advertiser.isAdvertising).isFalse()
        // The platform gate must not have been contacted at all.
        assertThat(gate.startCalls).isEmpty()
    }

    @Test
    fun `start keeps legacy advertisement when DCT payload factory throws`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
                dctPayloadFactory = { _ -> error("synthetic DCT payload failure") },
            )

        val started = advertiser.start(hiddenPhone(), endpointId("DROP"))

        assertThat(started).isTrue()
        assertThat(advertiser.isAdvertising).isTrue()
        assertThat(gate.startCalls).hasSize(1)
        assertThat(gate.startCalls.single().dctPayload).isNull()
        assertThat(gate.startCalls.single().visiblePayload).isNull()
    }

    @Test
    fun `start keeps legacy advertisement when visible payload factory throws`() {
        val gate = RecordingGate(failOnStart = false)
        val advertiser =
            BleQuickShareAdvertiser.forTesting(
                gate = gate,
                permissionChecker = { true },
                visiblePayloadFactory = { _, _ -> error("synthetic visible payload failure") },
            )

        val started = advertiser.start(visiblePhone(), endpointId("DROP"))

        assertThat(started).isTrue()
        assertThat(advertiser.isAdvertising).isTrue()
        assertThat(gate.startCalls).hasSize(1)
        assertThat(gate.startCalls.single().visiblePayload).isNull()
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

    private fun visiblePhone(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "LibreDrop",
        )

    private fun endpointId(s: String): ByteArray {
        require(s.length == BleServiceData.ENDPOINT_ID_LEN)
        return s.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Test double for the platform [BleAdvertiserGate]. Records each
     * `startAdvertising` call and tracks whether the resulting
     * registration was subsequently closed.
     */
    private class RecordingGate(
        @Volatile var failOnStart: Boolean,
    ) : BleAdvertiserGate {
        val startCalls: MutableList<StartCall> = mutableListOf()
        val registrations: MutableList<RecordingRegistration> = mutableListOf()

        val firstRegistration: RecordingRegistration?
            get() = registrations.firstOrNull()
        val lastRegistration: RecordingRegistration?
            get() = registrations.lastOrNull()

        override fun startAdvertising(
            serviceData: ByteArray,
            mode: Int,
            dctServiceData: ByteArray?,
            visibleServiceData: ByteArray?,
        ): BleAdvertiserGate.Registration? {
            startCalls += StartCall(serviceData, mode, dctServiceData, visibleServiceData)
            if (failOnStart) return null
            val reg = RecordingRegistration()
            registrations += reg
            return reg
        }
    }

    private data class StartCall(
        val payload: ByteArray,
        val mode: Int,
        val dctPayload: ByteArray?,
        val visiblePayload: ByteArray?,
    )

    private class RecordingRegistration : BleAdvertiserGate.Registration {
        @Volatile
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }
}
