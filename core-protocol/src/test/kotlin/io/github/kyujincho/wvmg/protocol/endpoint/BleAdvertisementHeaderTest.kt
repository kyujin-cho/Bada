/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BleAdvertisementHeaderTest {
    @Test
    fun `parse accepts stock base64-url GATT advertisement header`() {
        val parsed =
            BleAdvertisementHeader.parse(
                "VSAUARACAAADAAo8Vu4sAAA".toByteArray(Charsets.US_ASCII),
            )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(2)
        assertThat(parsed.supportsExtendedAdvertisement).isTrue()
        assertThat(parsed.numSlots).isEqualTo(5)
        assertThat(parsed.serviceIdBloomFilter)
            .isEqualTo(byteArrayOf(0x20, 0x14, 0x01, 0x10, 0x02, 0x00, 0x00, 0x03, 0x00, 0x0a))
        assertThat(parsed.advertisementHash).isEqualTo(byteArrayOf(0x3c, 0x56, 0xee.toByte(), 0x2c))
        assertThat(parsed.psm).isEqualTo(0)
    }

    @Test
    fun `parse accepts raw GATT advertisement header with PSM`() {
        val parsed =
            BleAdvertisementHeader.parse(
                byteArrayOf(
                    0x55,
                    0x20,
                    0x00,
                    0x00,
                    0x10,
                    0x02,
                    0x01,
                    0x21,
                    0x13,
                    0x00,
                    0x00,
                    0x73,
                    0x6d,
                    0x2e,
                    0x8b.toByte(),
                    0x12,
                    0x34,
                ),
            )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.numSlots).isEqualTo(5)
        assertThat(parsed.psm).isEqualTo(0x1234)
    }

    @Test
    fun `parse ignores normal fast advertisement bytes`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "Galaxy",
            )
        val fast = BleServiceData.encodeFramed("RINE", info)

        assertThat(BleAdvertisementHeader.parse(fast)).isNull()
    }
}
