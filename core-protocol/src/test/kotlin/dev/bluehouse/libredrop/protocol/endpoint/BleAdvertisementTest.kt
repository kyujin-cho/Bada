/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BleAdvertisementTest {
    @Test
    fun `GATT advertisement wraps endpoint data with service id hash`() {
        val info = endpointInfo("Galaxy")
        val bytes =
            BleAdvertisement.encodeGattAdvertisement(
                endpointId = "RINE".toByteArray(Charsets.US_ASCII),
                endpointInfo = info,
                psm = 0x1234,
            )

        val parsed = BleAdvertisement.parse(bytes)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(2)
        assertThat(parsed.socketVersion).isEqualTo(2)
        assertThat(parsed.fastAdvertisement).isFalse()
        assertThat(parsed.serviceIdHash).isEqualTo(NearbyServiceId.hashPrefix)
        assertThat(parsed.psm).isEqualTo(0x1234)

        val endpoint = BleServiceData.parse(parsed.data)
        assertThat(endpoint).isNotNull()
        assertThat(String(endpoint!!.endpointId, Charsets.US_ASCII)).isEqualTo("RINE")
        assertThat(endpoint.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `parse accepts fast advertisement wrapper`() {
        val info = endpointInfo("Pixel")
        val bytes = BleServiceData.encodeFramed("ABCD", info)

        val parsed = BleAdvertisement.parse(bytes)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.fastAdvertisement).isTrue()
        assertThat(parsed.serviceIdHash).isNull()
        assertThat(BleServiceData.parse(parsed.data)?.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `parse exposes second-profile fast advertisement wrapper`() {
        val info = endpointInfo("Pixel")
        val bytes = BleServiceData.encodeFramed("ABCD", info, secondProfile = true)

        val parsed = BleAdvertisement.parse(bytes)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.fastAdvertisement).isTrue()
        assertThat(parsed.secondProfile).isTrue()
        assertThat(parsed.serviceIdHash).isNull()
        assertThat(BleServiceData.parse(parsed.data)?.endpointInfo).isEqualTo(info)
    }

    private fun endpointInfo(name: String): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = name,
        )
}
