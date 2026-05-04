/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BleEndpointIdHolderTest {
    @AfterEach
    fun tearDown() {
        BleEndpointIdHolder.clear()
    }

    @Test
    fun `rotate replaces the cached endpoint id used by future lookups`() {
        val cached = "!!!!".toByteArray(Charsets.US_ASCII)
        val info = endpointInfo()
        BleEndpointIdHolder.restore(cached)

        assertThat(BleEndpointIdHolder.bytesFor(info)).isEqualTo(cached)

        val rotated = BleEndpointIdHolder.rotate()

        assertThat(rotated.size).isEqualTo(BleServiceData.ENDPOINT_ID_LEN)
        assertThat(rotated).isNotEqualTo(cached)
        assertThat(BleEndpointIdHolder.bytesFor(info)).isEqualTo(rotated)
        assertThat(rotated.all { byte -> byte.toInt().toChar().isLetterOrDigit() }).isTrue()
    }

    private fun endpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "V2547A",
            tlvRecords = emptyList(),
        )
}
