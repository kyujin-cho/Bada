/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BluetoothDeviceNameTest {
    @Test
    fun `encode places fields at the Nearby Bluetooth device-name offsets`() {
        val info = hiddenPhoneEndpointInfo()
        val endpointId = byteArrayOf(0x57, 0x76, 0x6D, 0x67)
        val raw = BluetoothDeviceName.encodeBytes(endpointId, info)

        assertThat(raw).hasLength(BluetoothDeviceName.FIXED_HEADER_LEN + info.serialize().size)
        assertThat(raw[0].toInt() and 0xFF).isEqualTo(0x23)
        assertThat(raw.copyOfRange(1, 5)).isEqualTo(endpointId)
        assertThat(raw.copyOfRange(5, 8)).isEqualTo(NearbyServiceId.hashPrefix)
        assertThat(raw[8].toInt() and 0xFF).isEqualTo(0)
        assertThat(raw.copyOfRange(9, 15)).isEqualTo(ByteArray(BluetoothDeviceName.RESERVED_LEN))
        assertThat(raw[15].toInt() and 0xFF).isEqualTo(info.serialize().size)
        assertThat(raw.copyOfRange(16, raw.size)).isEqualTo(info.serialize())
    }

    @Test
    fun `encode wraps raw bytes in URL-safe base64 without padding`() {
        val info = hiddenPhoneEndpointInfo()
        val encoded = BluetoothDeviceName.encode("Wvmg", info)

        assertThat(encoded).doesNotContain("=")
        assertThat(Base64Url.decode(encoded)).isEqualTo(BluetoothDeviceName.encodeBytes("Wvmg".toByteArray(), info))
    }

    @Test
    fun `parse roundtrips encoded Bluetooth device name`() {
        val info = visiblePhoneEndpointInfo()
        val encoded = BluetoothDeviceName.encode("AbCd", info, webRtcConnectable = true)
        val parsed = BluetoothDeviceName.parse(encoded)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(BleServiceData.DEFAULT_VERSION)
        assertThat(parsed.pcp).isEqualTo(BleServiceData.DEFAULT_PCP)
        assertThat(parsed.endpointId).isEqualTo(byteArrayOf(0x41, 0x62, 0x43, 0x64))
        assertThat(parsed.serviceIdHash).isEqualTo(NearbyServiceId.hashPrefix)
        assertThat(parsed.webRtcConnectable).isTrue()
        assertThat(parsed.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `encode rejects endpoint info that exceeds Nearby Bluetooth name budget`() {
        val tooLarge =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "A".repeat(120),
            )

        assertThrows<IllegalArgumentException> {
            BluetoothDeviceName.encode("Wvmg", tooLarge)
        }
    }

    @Test
    fun `encode rejects malformed endpoint ids`() {
        val info = hiddenPhoneEndpointInfo()

        assertThrows<IllegalArgumentException> {
            BluetoothDeviceName.encode(byteArrayOf(0x41, 0x42, 0x43), info)
        }
        assertThrows<IllegalArgumentException> {
            BluetoothDeviceName.encode("한글12", info)
        }
    }

    private fun hiddenPhoneEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = true,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = null,
            tlvRecords = emptyList(),
        )

    private fun visiblePhoneEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { (0xF0 - it).toByte() },
            deviceName = "Pixel",
            tlvRecords = emptyList(),
        )
}
