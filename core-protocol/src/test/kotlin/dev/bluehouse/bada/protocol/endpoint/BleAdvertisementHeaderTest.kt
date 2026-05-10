/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest

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

    @Test
    fun `encode writes stock single-slot GATT header with PSM`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "Galaxy",
            )
        val gattAdvertisement =
            BleAdvertisement.encodeGattAdvertisement(
                endpointId = "RINE".toByteArray(Charsets.US_ASCII),
                endpointInfo = info,
                psm = 0x1234,
            )
        val dummyServiceId = ByteArray(128)

        val bytes =
            BleAdvertisementHeader.encodeSingleSlot(
                serviceId = NearbyServiceId.VALUE,
                gattAdvertisement = gattAdvertisement,
                psm = 0x1234,
                dummyServiceId = dummyServiceId,
            )

        val parsed = BleAdvertisementHeader.parse(bytes)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(2)
        assertThat(parsed.supportsExtendedAdvertisement).isFalse()
        assertThat(parsed.numSlots).isEqualTo(1)
        assertThat(parsed.psm).isEqualTo(0x1234)
        assertThat(parsed.serviceIdBloomFilter)
            .isEqualTo(byteArrayOf(0x64, 0x00, 0x08, 0x14, 0x02, 0x08, 0x00, 0x03, 0x00, 0x00))

        val expectedHash = sha256First4(sha256First4(dummyServiceId) + gattAdvertisement)
        assertThat(parsed.advertisementHash).isEqualTo(expectedHash)
    }

    private fun sha256First4(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(4)
}
