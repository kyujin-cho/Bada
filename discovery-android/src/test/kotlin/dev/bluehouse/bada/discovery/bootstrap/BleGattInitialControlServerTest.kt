/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package dev.bluehouse.bada.discovery.bootstrap

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.BleServiceData
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import org.junit.Test
import java.nio.charset.StandardCharsets

class BleGattInitialControlServerTest {
    @Test
    fun `regular service UUID stays the GATT advertisement slot host`() {
        assertThat(BleGattInitialControlServer.SERVICE_UUID.toString())
            .isEqualTo(BleServiceData.SERVICE_UUID_128_STRING)
    }

    @Test
    fun `second-profile service UUID aliases stock Nearby socket lookup`() {
        assertThat(BleGattInitialControlServer.SECOND_PROFILE_SERVICE_UUID.toString())
            .isEqualTo(BleServiceData.SERVICE_UUID_128_STRING)
    }

    @Test
    fun `stock service exposes advertisement slots and Weave socket when enabled`() {
        val serviceSpecs =
            BleGattInitialControlServer.buildServiceSpecs(
                endpointInfo = sampleEndpointInfo(),
                endpointId = "P0ZK".toByteArray(StandardCharsets.US_ASCII),
                publishAdvertisementSlotService = true,
            )

        assertThat(serviceSpecs.map { it.uuid })
            .containsExactly(BleGattInitialControlServer.SERVICE_UUID)

        val service = serviceSpecs.single()

        assertThat(service.characteristicUuids())
            .contains(BleGattInitialControlServer.GATT_SLOT_0_UUID)
        assertThat(service.characteristicUuids())
            .contains(BleGattInitialControlServer.FROM_PERIPHERAL_UUID)
        assertThat(service.characteristicUuids())
            .contains(BleGattInitialControlServer.TO_PERIPHERAL_UUID)

        val slotValue =
            requireNotNull(
                service.characteristics.firstOrNull {
                    it.uuid == BleGattInitialControlServer.GATT_SLOT_0_UUID
                },
            ).value
        assertThat(slotValue)
            .isNotNull()
        assertThat(slotValue[0].toInt() and 0xFF)
            .isEqualTo(BleServiceData.FRAME_TYPE_FAST_ADVERTISEMENT)
    }

    @Test
    fun `receiver mode publishes only canonical socket when slots are disabled`() {
        val serviceSpecs =
            BleGattInitialControlServer.buildServiceSpecs(
                endpointInfo = sampleEndpointInfo(),
                endpointId = "P0ZK".toByteArray(StandardCharsets.US_ASCII),
                publishAdvertisementSlotService = false,
            )

        assertThat(serviceSpecs.map { it.uuid })
            .containsExactly(BleGattInitialControlServer.SECOND_PROFILE_SERVICE_UUID)

        val socketService = serviceSpecs.single()

        assertThat(socketService.characteristicUuids())
            .containsExactly(
                BleGattInitialControlServer.FROM_PERIPHERAL_UUID,
                BleGattInitialControlServer.TO_PERIPHERAL_UUID,
            )
        assertThat(socketService.characteristicUuids())
            .doesNotContain(BleGattInitialControlServer.GATT_SLOT_0_UUID)
    }

    private fun BleGattInitialControlServer.Companion.GattServiceSpec.characteristicUuids() =
        characteristics.map { it.uuid }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { index -> index.toByte() },
            deviceName = "Bada",
        )
}
