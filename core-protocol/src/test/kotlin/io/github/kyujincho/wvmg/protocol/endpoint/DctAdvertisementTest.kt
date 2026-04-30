/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DctAdvertisementTest {
    @Test
    fun `service uuid matches Nearby DCT advertisement UUID`() {
        assertThat(DctAdvertisement.SERVICE_UUID_SHORT).isEqualTo(0xFC73)
        assertThat(DctAdvertisement.SERVICE_UUID_128_STRING)
            .isEqualTo("0000fc73-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun `computeServiceIdHash matches google nearby KAT`() {
        assertThat(DctAdvertisement.computeServiceIdHash("service_id"))
            .isEqualTo(byteArrayOf(0x96.toByte(), 0x77))
    }

    @Test
    fun `encode writes stock DCT data element layout`() {
        val encoded =
            DctAdvertisement.encode(
                serviceId = "service_id",
                deviceName = "device",
                psm = 0x1234,
                dedup = 0x01,
            )

        assertThat(encoded)
            .isEqualTo(
                byteArrayOf(
                    0x20,
                    0x25,
                    0x96.toByte(),
                    0x77,
                    0x24,
                    0x12,
                    0x34,
                    0x87.toByte(),
                    0x07,
                    0x01,
                    0x64,
                    0x65,
                    0x76,
                    0x69,
                    0x63,
                    0x65,
                ),
            )
    }

    @Test
    fun `parse accepts google nearby captured DCT advertisement`() {
        val parsed =
            DctAdvertisement.parse(
                byteArrayOf(
                    0x20,
                    0x25,
                    0x6D,
                    0xFD.toByte(),
                    0x24,
                    0x00,
                    0xC0.toByte(),
                    0x88.toByte(),
                    0x07,
                    0x96.toByte(),
                    0x74,
                    0x65,
                    0x73,
                    0x74,
                    0x64,
                    0x65,
                    0x76,
                ),
            )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.deviceName).isEqualTo("testdev")
        assertThat(parsed.psm).isEqualTo(192)
        assertThat(parsed.isDeviceNameTruncated).isTrue()
        assertThat(parsed.dedup).isEqualTo(0x16)
    }

    @Test
    fun `encode truncates visible name to seven valid UTF-8 bytes`() {
        val parsed =
            DctAdvertisement.parse(
                DctAdvertisement.encode(
                    serviceId = "service_id",
                    deviceName = "WhenVivoMeetsGoogle",
                    psm = DctAdvertisement.DEFAULT_PSM,
                    dedup = DctAdvertisement.DEFAULT_DEDUP,
                ),
            )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.deviceName).isEqualTo("WhenViv")
        assertThat(parsed.isDeviceNameTruncated).isTrue()
        assertThat(parsed.psm).isEqualTo(0)
    }

    @Test
    fun `encode truncates without splitting multi-byte UTF-8 code points`() {
        val parsed =
            DctAdvertisement.parse(
                DctAdvertisement.encode(
                    serviceId = "service_id",
                    deviceName = "\u00e9\u00f1\u00f6\ud83d\ude00",
                    psm = 0x1234,
                    dedup = DctAdvertisement.DEFAULT_DEDUP,
                ),
            )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.deviceName).isEqualTo("\u00e9\u00f1\u00f6")
    }

    @Test
    fun `generateEndpointId matches google nearby KAT`() {
        assertThat(DctAdvertisement.generateEndpointId(0x01, "device")).isEqualTo("IWRE")
        assertThat(DctAdvertisement.generateEndpointId(0x02, "device")).isEqualTo("HEL4")
    }

    @Test
    fun `encode rejects invalid fields`() {
        assertThrows<IllegalArgumentException> {
            DctAdvertisement.encode(serviceId = "", deviceName = "device")
        }
        assertThrows<IllegalArgumentException> {
            DctAdvertisement.encode(serviceId = "service_id", deviceName = "")
        }
        assertThrows<IllegalArgumentException> {
            DctAdvertisement.encode(serviceId = "service_id", deviceName = "device", psm = 0x1_0000)
        }
        assertThrows<IllegalArgumentException> {
            DctAdvertisement.encode(serviceId = "service_id", deviceName = "device", dedup = 0x80)
        }
    }
}
