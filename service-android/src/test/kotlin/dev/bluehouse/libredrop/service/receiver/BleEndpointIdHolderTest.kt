/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BleEndpointIdHolderTest {
    @BeforeEach
    fun setUp() {
        BleEndpointIdHolder.clear()
    }

    @AfterEach
    fun tearDown() {
        BleEndpointIdHolder.clear()
    }

    @Test
    fun `rotate replaces the cached endpoint id used by future lookups`() {
        val cached = "!!!!".toByteArray(Charsets.US_ASCII)
        BleEndpointIdHolder.restore(cached)

        assertThat(BleEndpointIdHolder.bytesFor()).isEqualTo(cached)

        val rotated = BleEndpointIdHolder.rotate()

        assertThat(rotated.size).isEqualTo(BleServiceData.ENDPOINT_ID_LEN)
        assertThat(rotated).isNotEqualTo(cached)
        assertThat(BleEndpointIdHolder.bytesFor()).isEqualTo(rotated)
        assertThat(rotated.all { byte -> byte.toInt().toChar().isLetterOrDigit() }).isTrue()
    }
}
