/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.ble

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.Base64Url
import dev.bluehouse.bada.protocol.endpoint.BleAdvertisementHeader
import dev.bluehouse.bada.protocol.endpoint.BleServiceData
import dev.bluehouse.bada.protocol.endpoint.DctAdvertisement
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
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
    fun `extended regular advertisement decodes endpoint info and PSM`() {
        val info = endpointInfo("Galaxy")
        val payload = regularAdvertisementServiceData("RINE", info, psm = 0x1234)

        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = payload,
                advertiserAddress = "77:88:99:AA:BB:CC",
                rssi = -47,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.endpointId).isEqualTo("RINE")
        assertThat(observed.endpointInfo).isEqualTo(info)
        assertThat(observed.l2capPsm).isEqualTo(0x1234)
        assertThat(observed.displayName).isEqualTo("Galaxy")
        assertThat(observed.displayNameSource)
            .isEqualTo(BleFastAdvertisementScanner.DisplayNameSource.FAST_ADVERTISEMENT_ENDPOINT_INFO)
    }

    @Test
    fun `GATT advertisement header is observation-only before slot verification`() {
        val rawHeader = legacyGattHeaderRawBytes()

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
        assertThat(observed.gattConnectable).isFalse()
        assertThat(observed.displayName).isNull()
        assertThat(observed.displayNameSource).isNull()
    }

    @Test
    fun `GATT advertisement header keeps BLE local name fallback`() {
        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = legacyGattHeaderServiceData(),
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
                fallbackDisplayName = " Galaxy S26 ",
                fallbackDisplayNameSource = BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME,
            )

        assertThat(observed).isNotNull()
        assertThat(observed!!.endpointId).isNull()
        assertThat(observed.endpointInfo).isNull()
        assertThat(observed.gattConnectable).isFalse()
        assertThat(observed.displayName).isEqualTo("Galaxy S26")
        assertThat(observed.displayNameSource)
            .isEqualTo(BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME)
    }

    @Test
    fun `extended-capable GATT header waits for regular advertisement bytes`() {
        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = "VSAUARACAAADAAo8Vu4sAAA".toByteArray(Charsets.US_ASCII),
                advertiserAddress = "28:1B:3E:BA:B1:1B",
                rssi = -41,
            )

        assertThat(observed).isNull()
    }

    @Test
    fun `GATT advertisement header preserves non-connectable scan result`() {
        val observed =
            BleFastAdvertisementScanner.parseFastServiceData(
                serviceData = legacyGattHeaderServiceData(),
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

    private fun legacyGattHeaderServiceData(): ByteArray =
        Base64Url.encode(legacyGattHeaderRawBytes()).toByteArray(Charsets.US_ASCII)

    private fun legacyGattHeaderRawBytes(): ByteArray =
        byteArrayOf(
            0x45,
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

    private fun regularAdvertisementServiceData(
        endpointId: String,
        endpointInfo: EndpointInfo,
        psm: Int,
    ): ByteArray {
        val body = regularBleBody(endpointId, endpointInfo)
        val out =
            ByteArray(
                1 +
                    NearbyServiceId.hashPrefix.size +
                    4 +
                    body.size +
                    BleServiceData.DEVICE_TOKEN_LEN +
                    BleServiceData.EXTRA_FIELDS_MASK_LEN +
                    BleServiceData.PSM_LEN,
            )
        var offset = 0
        out[offset++] = 0x48
        NearbyServiceId.hashPrefix.copyInto(out, destinationOffset = offset)
        offset += NearbyServiceId.hashPrefix.size
        out[offset++] = ((body.size ushr 24) and 0xFF).toByte()
        out[offset++] = ((body.size ushr 16) and 0xFF).toByte()
        out[offset++] = ((body.size ushr 8) and 0xFF).toByte()
        out[offset++] = (body.size and 0xFF).toByte()
        body.copyInto(out, destinationOffset = offset)
        offset += body.size
        offset += BleServiceData.DEVICE_TOKEN_LEN
        out[offset++] = BleServiceData.EXTRA_FIELD_PSM_MASK.toByte()
        out[offset++] = ((psm ushr 8) and 0xFF).toByte()
        out[offset] = (psm and 0xFF).toByte()
        return out
    }

    private fun regularBleBody(
        endpointId: String,
        endpointInfo: EndpointInfo,
    ): ByteArray {
        val infoBytes = endpointInfo.serialize()
        val out =
            ByteArray(
                1 +
                    NearbyServiceId.hashPrefix.size +
                    BleServiceData.ENDPOINT_ID_LEN +
                    1 +
                    infoBytes.size +
                    BleServiceData.BLUETOOTH_MAC_LEN +
                    2,
            )
        var offset = 0
        out[offset++] = ((BleServiceData.DEFAULT_VERSION shl 5) or BleServiceData.DEFAULT_PCP).toByte()
        NearbyServiceId.hashPrefix.copyInto(out, destinationOffset = offset)
        offset += NearbyServiceId.hashPrefix.size
        endpointId.toByteArray(Charsets.US_ASCII).copyInto(out, destinationOffset = offset)
        offset += BleServiceData.ENDPOINT_ID_LEN
        out[offset++] = infoBytes.size.toByte()
        infoBytes.copyInto(out, destinationOffset = offset)
        offset += infoBytes.size
        byteArrayOf(0x70, 0x4E, 0xE0.toByte(), 0x12, 0xDE.toByte(), 0x3A).copyInto(
            out,
            destinationOffset = offset,
        )
        offset += BleServiceData.BLUETOOTH_MAC_LEN
        out[offset++] = 0x00
        out[offset] = 0x01
        return out
    }
}
