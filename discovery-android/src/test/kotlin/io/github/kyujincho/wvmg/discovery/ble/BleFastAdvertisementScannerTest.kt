/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import io.github.kyujincho.wvmg.protocol.endpoint.BleAdvertisementHeader
import io.github.kyujincho.wvmg.protocol.endpoint.BleServiceData
import io.github.kyujincho.wvmg.protocol.endpoint.DctAdvertisement
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import org.junit.jupiter.api.Test

class BleFastAdvertisementScannerTest {
    @Test
    fun `fast advertisement service data preserves L2CAP PSM`() {
        val info = endpointInfo("Galaxy")
        val payload =
            BleServiceData.encodeFramedWithPsm(
                endpointId = "RINE",
                endpointInfo = info,
                psm = 0x1234,
            )

        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = payload,
                advertiserAddress = "77:88:99:AA:BB:CC",
                rssi = -47,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.endpointId).isEqualTo("RINE")
        assertThat(observed.endpointInfo).isEqualTo(info)
        assertThat(observed.advertiserAddress).isEqualTo("77:88:99:AA:BB:CC")
        assertThat(observed.rssi).isEqualTo(-47)
        assertThat(observed.l2capPsm).isEqualTo(0x1234)
        assertThat(observed.gattConnectable).isTrue()
        assertThat(observed.displayName).isEqualTo("Galaxy")
        assertThat(observed.displayNameSource)
            .isEqualTo(BleFastAdvertisementScanner.DisplayNameSource.FAST_ADVERTISEMENT_ENDPOINT_INFO)
    }

    @Test
    fun `GATT advertisement header becomes a connectable BLE observation`() {
        val rawHeader =
            byteArrayOf(
                0x55,
                0x20,
                0x14,
                0x01,
                0x10,
                0x02,
                0x00,
                0x00,
                0x03,
                0x00,
                0x0a,
                0x3c,
                0x56,
                0xee.toByte(),
                0x2c,
                0x00,
                0x00,
            )

        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = Base64Url.encode(rawHeader).toByteArray(Charsets.US_ASCII),
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
            )

        assertThat(observed).isNotNull()
        assertThat(BleAdvertisementHeader.parse(Base64Url.encode(rawHeader).toByteArray(Charsets.US_ASCII))).isNotNull()
        assertThat(observed!!.endpointId).isNull()
        assertThat(observed.endpointInfo).isNull()
        assertThat(observed.advertiserAddress).isEqualTo("28:1B:3E:BA:B1:1B")
        assertThat(observed.rssi).isEqualTo(-41)
        assertThat(observed.l2capPsm).isNull()
        assertThat(observed.gattConnectable).isTrue()
        assertThat(observed.displayName).isNull()
        assertThat(observed.displayNameSource).isNull()
    }

    @Test
    fun `GATT advertisement header keeps BLE local name fallback`() {
        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = "VSAUARACAAADAAo8Vu4sAAA".toByteArray(Charsets.US_ASCII),
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
                fallbackDisplayName = " Galaxy S26 ",
                fallbackDisplayNameSource = BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.endpointId).isNull()
        assertThat(observed.endpointInfo).isNull()
        assertThat(observed.displayName).isEqualTo("Galaxy S26")
        assertThat(observed.displayNameSource)
            .isEqualTo(BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME)
    }

    @Test
    fun `GATT advertisement header preserves non-connectable scan result`() {
        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = "VSAUARACAAADAAo8Vu4sAAA".toByteArray(Charsets.US_ASCII),
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
                gattConnectable = false,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.gattConnectable).isFalse()
    }

    @Test
    fun `DCT service data becomes a named L2CAP observation`() {
        val payload =
            DctAdvertisement.encode(
                serviceId = NearbyServiceId.VALUE,
                deviceName = "Galaxy S25",
                psm = 0x00C0,
                dedup = 0x16,
            )
        val dct = DctAdvertisement.parse(payload)!!

        val observed =
            BleFastAdvertisementScanner.parseDctServiceData(
                serviceData = payload,
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.endpointId)
            .isEqualTo(DctAdvertisement.generateEndpointId(dct.dedup, dct.deviceName))
        assertThat(observed.endpointInfo!!.hidden).isFalse()
        assertThat(observed.endpointInfo.deviceType).isEqualTo(DeviceType.PHONE)
        assertThat(observed.endpointInfo.deviceName).isEqualTo(dct.deviceName)
        assertThat(observed.advertiserAddress).isEqualTo("28:1B:3E:BA:B1:1B")
        assertThat(observed.rssi).isEqualTo(-41)
        assertThat(observed.l2capPsm).isEqualTo(0x00C0)
        assertThat(observed.gattConnectable).isTrue()
        assertThat(observed.displayName).isEqualTo("Galaxy")
        assertThat(observed.displayNameSource)
            .isEqualTo(BleFastAdvertisementScanner.DisplayNameSource.DCT_ADVERTISEMENT)
    }

    @Test
    fun `DCT service data ignores other Nearby service ids`() {
        val payload =
            DctAdvertisement.encode(
                serviceId = "service_id",
                deviceName = "Galaxy",
                psm = 0x00C0,
                dedup = 0x16,
            )

        val observed =
            BleFastAdvertisementScanner.parseDctServiceData(
                serviceData = payload,
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
            )

        assertThat(observed).isNull()
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
