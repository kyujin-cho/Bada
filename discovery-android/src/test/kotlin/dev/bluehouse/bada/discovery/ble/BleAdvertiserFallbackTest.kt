/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.ble

import android.bluetooth.le.BluetoothLeAdvertiser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Fallback-path tests for [BleAdvertiser] (#32 acceptance: "device with
 * no BluetoothLeAdvertiser → log warning, continue without BLE pulse").
 *
 * The happy path (`startAdvertising` succeeds and the
 * `AdvertiseCallback.onStartSuccess` fires) is exercised manually on
 * real hardware — see `docs/testing/` — because instantiating a
 * functioning `BluetoothLeAdvertiser` from a host JVM requires
 * Robolectric, which the discovery module deliberately does not pull in.
 *
 * These tests are JVM-only and target the failure paths that determine
 * whether the rest of the share UX still works on devices where BLE
 * advertise is unavailable.
 */
class BleAdvertiserFallbackTest {
    @Test
    fun `start returns null when permission is denied`() {
        val advertiser =
            BleAdvertiser(
                provider =
                    object : BleAdvertiser.AdvertiserProvider {
                        override fun hasAdvertisePermission(): Boolean = false

                        override fun advertiser(): BluetoothLeAdvertiser? =
                            error("must not be called when permission is denied")
                    },
                payloadFactory = { ByteArray(BleAdvertisePayload.PAYLOAD_LEN) },
                now = { 0L },
            )
        val handle = advertiser.start()
        assertThat(handle).isNull()
    }

    @Test
    fun `start returns null when the platform has no BluetoothLeAdvertiser`() {
        val advertiser =
            BleAdvertiser(
                provider =
                    object : BleAdvertiser.AdvertiserProvider {
                        override fun hasAdvertisePermission(): Boolean = true

                        // Production code path on devices without
                        // peripheral mode / with Bluetooth disabled —
                        // BluetoothAdapter.bluetoothLeAdvertiser is null.
                        override fun advertiser(): BluetoothLeAdvertiser? = null
                    },
                payloadFactory = { ByteArray(BleAdvertisePayload.PAYLOAD_LEN) },
                now = { 0L },
            )
        val handle = advertiser.start()
        assertThat(handle).isNull()
    }

    @Test
    fun `start does not invoke payloadFactory when permission is denied`() {
        // We don't want to spend entropy / risk an exception in the
        // payload factory when we already know we can't advertise. This
        // test pins that ordering so a regression that calls
        // payloadFactory before the permission check is caught.
        var built = false
        val advertiser =
            BleAdvertiser(
                provider =
                    object : BleAdvertiser.AdvertiserProvider {
                        override fun hasAdvertisePermission(): Boolean = false

                        override fun advertiser(): BluetoothLeAdvertiser? = null
                    },
                payloadFactory = {
                    built = true
                    ByteArray(BleAdvertisePayload.PAYLOAD_LEN)
                },
                now = { 0L },
            )
        advertiser.start()
        assertThat(built).isFalse()
    }

    @Test
    fun `start does not invoke payloadFactory when there is no advertiser`() {
        var built = false
        val advertiser =
            BleAdvertiser(
                provider =
                    object : BleAdvertiser.AdvertiserProvider {
                        override fun hasAdvertisePermission(): Boolean = true

                        override fun advertiser(): BluetoothLeAdvertiser? = null
                    },
                payloadFactory = {
                    built = true
                    ByteArray(BleAdvertisePayload.PAYLOAD_LEN)
                },
                now = { 0L },
            )
        advertiser.start()
        assertThat(built).isFalse()
    }
}
