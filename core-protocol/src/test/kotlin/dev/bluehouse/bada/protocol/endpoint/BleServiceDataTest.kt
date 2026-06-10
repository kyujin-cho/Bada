/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

/**
 * Bit-exact regression tests for [BleServiceData] (issue #121).
 *
 * These tests pin the wire format we observed on a Galaxy peer's BLE
 * pulse during the c0150be triage:
 *
 * ```text
 * [ 0x4a frame | 0x17 length | 0x23 PCP | endpoint_id | 0x11 length | 0x32 ... | token ]
 * ```
 *
 * If a regression flips bit packing or field order, every stock Quick
 * Share picker silently stops listing us — and the only signal in the
 * field is "the receiver disappeared from Galaxy's send sheet again."
 * These KATs are the regression net before any device is involved.
 */
class BleServiceDataTest {
    @Test
    fun `service uuid short value matches the stock fast-advertisement UUID`() {
        // 0xFEF3 is the Quick Share fast-advertisement service UUID
        // observed in Galaxy's NearbyConnections dump as
        // `fastAdvertisementServiceUuid: 0000fef3-...`.
        assertThat(BleServiceData.SERVICE_UUID_SHORT).isEqualTo(0xFEF3)
    }

    @Test
    fun `service uuid 128-bit form expands the short uuid into the bluetooth base`() {
        // Bluetooth Base UUID is XXXXXXXX-0000-1000-8000-00805F9B34FB
        // with the 16-bit short UUID slotted into the leading 32 bits.
        // Substituting 0xFEF3 yields the constant we expose.
        assertThat(BleServiceData.SERVICE_UUID_128_STRING)
            .isEqualTo("0000fef3-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun `byte 0 packs version 1 PCP 3 as 0x23 matching the captured Galaxy fingerprint`() {
        // Stock Galaxy emits version=1 in the top 3 bits and PCP=3 in
        // the bottom 5 bits: 001_00011 = 0x23. Pinning the exact byte
        // catches both an MSB/LSB flip and any drift in the default
        // version or PCP value.
        val packed = BleServiceData.packVersionPcp(version = 1, pcp = 3)
        assertThat(packed.toInt() and 0xFF).isEqualTo(0x23)
    }

    @Test
    fun `default version and pcp constants match the canonical stock values`() {
        // version=1 and PCP_HIGH=3 are the values google/nearby's
        // ble_advertisement.cc emits for "phone, line- or
        // battery-powered, peripheral mode". Drift here is what changed
        // commit c0150be's investigation in the first place.
        assertThat(BleServiceData.DEFAULT_VERSION).isEqualTo(1)
        assertThat(BleServiceData.DEFAULT_PCP).isEqualTo(3)
    }

    @Test
    fun `encode places header endpoint_id length-prefix and EndpointInfo at the documented offsets`() {
        val info = hiddenPhoneEndpointInfo()
        val endpointId = byteArrayOf(0x44, 0x52, 0x4F, 0x50) // ASCII "DROP"
        val payload = BleServiceData.encode(endpointId, info)

        // Total = 1 (header) + 4 (endpoint_id) + 1 (length) + 17 (hidden EndpointInfo).
        assertThat(payload).hasLength(BleServiceData.FIXED_HEADER_LEN + info.serialize().size)
        assertThat(payload).hasLength(23)

        // Byte 0 = 0x23 (version=1, PCP=3).
        assertThat(payload[0].toInt() and 0xFF).isEqualTo(0x23)

        // Bytes 1..4 = the supplied endpoint_id verbatim.
        assertThat(payload.copyOfRange(1, 5)).isEqualTo(endpointId)

        // Byte 5 = 0x11 (17, length of the hidden EndpointInfo serialization).
        assertThat(payload[5].toInt() and 0xFF).isEqualTo(0x11)

        // Bytes 6..N = the EndpointInfo serialization byte-for-byte.
        // First inner byte is 0x32 (hidden, version=1, PHONE, reserved=0).
        assertThat(payload[6].toInt() and 0xFF).isEqualTo(0x32)
        assertThat(payload.copyOfRange(6, payload.size)).isEqualTo(info.serialize())
    }

    @Test
    fun `encode roundtrips through parse`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encode("DROP", info)
        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(BleServiceData.DEFAULT_VERSION)
        assertThat(parsed.pcp).isEqualTo(BleServiceData.DEFAULT_PCP)
        assertThat(parsed.endpointId).isEqualTo(byteArrayOf(0x44, 0x52, 0x4F, 0x50))
        assertThat(parsed.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `parse accepts regular non-fast BLE advertisement body`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "Kyujin's S26 Ultra",
            )
        val payload = regularBleBody("LPPM", info)
        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(String(parsed!!.endpointId, Charsets.US_ASCII)).isEqualTo("LPPM")
        assertThat(parsed.version).isEqualTo(BleServiceData.DEFAULT_VERSION)
        assertThat(parsed.pcp).isEqualTo(BleServiceData.DEFAULT_PCP)
        assertThat(parsed.endpointInfo).isEqualTo(info)
        assertThat(parsed.bluetoothMacAddress).isEqualTo("70:4E:E0:12:DE:3A")
    }

    @Test
    fun `parse extracts the Bluetooth Classic MAC from a captured stock regular advertisement body`() {
        // 49-byte regular advertisement body captured by HCI snoop from a
        // Xiaomi 17 Ultra stock receiver (GMS 26.20.31) in
        // foreground-visible mode during the #214 investigation. Layout:
        // [0x23 PCP | fc9f5e hash | "17NT" | 0x20 | EndpointInfo(32) |
        //  BT MAC 08:B3:39:10:C2:6A | 2 trailing bytes]. Stock senders use
        // this MAC for the RFCOMM initial connection.
        val payload =
            hex(
                "23fc9f5e31374e542022fce18cb548757c1582bdd2cf9c" +
                    "f9eb910eeab79ceca78427732070686f6e6508b33910c26a0000",
            )

        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(String(parsed!!.endpointId, Charsets.US_ASCII)).isEqualTo("17NT")
        assertThat(parsed.endpointInfo.deviceName).isEqualTo("규진's phone")
        assertThat(parsed.bluetoothMacAddress).isEqualTo("08:B3:39:10:C2:6A")
    }

    @Test
    fun `parse returns a null MAC when the regular advertisement body zeroes it out`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = regularBleBody("LPPM", info)
        val macOffset =
            BleServiceData.REGULAR_FIXED_HEADER_LEN + info.serialize().size
        for (i in 0 until BleServiceData.BLUETOOTH_MAC_LEN) {
            payload[macOffset + i] = 0x00
        }

        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.bluetoothMacAddress).isNull()
    }

    @Test
    fun `parse leaves the MAC null for fast advertisement bodies`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encodeFramed("DROP", info)

        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.bluetoothMacAddress).isNull()
    }

    @Test
    fun `encodeFramed wraps the fast-advertisement body like stock Nearby`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encodeFramed("DROP", info)

        assertThat(payload).hasLength(27)
        assertThat(payload[0].toInt() and 0xFF)
            .isEqualTo(BleServiceData.FRAME_TYPE_FAST_ADVERTISEMENT)
        assertThat(payload[1].toInt() and 0xFF).isEqualTo(23)
        assertThat(payload[2].toInt() and 0xFF).isEqualTo(0x23)
        assertThat(payload.copyOfRange(3, 7)).isEqualTo(byteArrayOf(0x44, 0x52, 0x4F, 0x50))
        assertThat(payload[7].toInt() and 0xFF).isEqualTo(0x11)
        assertThat(payload.copyOfRange(25, 27))
            .isEqualTo(sha256FirstTwo(payload.copyOfRange(2, 25)))
        assertThat(BleServiceData.parse(payload)?.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `encodeFramedWithPsm appends stock extended-advertisement PSM extra field`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encodeFramedWithPsm("DROP", info, psm = 0x1234)

        assertThat(payload).hasLength(30)
        assertThat(payload[27].toInt() and 0xFF).isEqualTo(BleServiceData.EXTRA_FIELD_PSM_MASK)
        assertThat(payload[28].toInt() and 0xFF).isEqualTo(0x12)
        assertThat(payload[29].toInt() and 0xFF).isEqualTo(0x34)
        assertThat(BleServiceData.parsePsmExtraField(payload)).isEqualTo(0x1234)
        assertThat(BleServiceData.parse(payload)?.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `encodeFramed can set the second-profile bit`() {
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encodeFramed("DROP", info, secondProfile = true)

        assertThat(payload).hasLength(27)
        assertThat(payload[0].toInt() and 0xFF)
            .isEqualTo(BleServiceData.FRAME_TYPE_SECOND_PROFILE_FAST_ADVERTISEMENT)
        assertThat(BleServiceData.parse(payload)?.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `encodeFramedWithPsm rejects invalid PSM values`() {
        val info = hiddenPhoneEndpointInfo()
        assertThrows<IllegalArgumentException> {
            BleServiceData.encodeFramedWithPsm("DROP", info, psm = 0)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encodeFramedWithPsm("DROP", info, psm = 0x1_0000)
        }
    }

    @Test
    fun `parse accepts captured stock framed fast advertisement`() {
        val payload =
            byteArrayOf(
                0x4A,
                0x17,
                0x23,
                0x30,
                0x51,
                0x52,
                0x52,
                0x11,
                0x32,
                0x82.toByte(),
                0x49,
                0x92.toByte(),
                0x9C.toByte(),
                0x32,
                0xFF.toByte(),
                0x2B,
                0x2C,
                0xC2.toByte(),
                0x38,
                0xA4.toByte(),
                0x62,
                0xE7.toByte(),
                0xB1.toByte(),
                0x35,
                0xE3.toByte(),
                0x4A,
                0xF9.toByte(),
            )

        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(String(parsed!!.endpointId, Charsets.US_ASCII)).isEqualTo("0QRR")
        assertThat(parsed.version).isEqualTo(BleServiceData.DEFAULT_VERSION)
        assertThat(parsed.pcp).isEqualTo(BleServiceData.DEFAULT_PCP)
        assertThat(parsed.endpointInfo.hidden).isTrue()
        assertThat(parsed.endpointInfo.deviceType).isEqualTo(DeviceType.PHONE)
    }

    @Test
    fun `encode supports a visible EndpointInfo with inline UTF-8 device name`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x00 },
                deviceName = "Pixel-9",
            )
        val payload = BleServiceData.encode("AbCd", info)

        // 1 + 4 + 1 + 1 (header) + 16 (metadata) + 1 (name length) + 7 (name bytes) = 31.
        // Visible byte 0 of EndpointInfo: version=1 (001), hidden=0, PHONE (001), reserved=0
        //   -> 001_0_001_0 = 0x22.
        assertThat(payload).hasLength(31)
        assertThat(payload[0].toInt() and 0xFF).isEqualTo(0x23)
        assertThat(payload[5].toInt() and 0xFF).isEqualTo(info.serialize().size)
        assertThat(payload[6].toInt() and 0xFF).isEqualTo(0x22)
        assertThat(BleServiceData.parse(payload)?.endpointInfo).isEqualTo(info)
    }

    @Test
    fun `encode rejects an endpoint_id of the wrong byte length`() {
        val info = hiddenPhoneEndpointInfo()
        // Too short.
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode(byteArrayOf(0x41, 0x42, 0x43), info)
        }
        // Too long.
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode(byteArrayOf(0x41, 0x42, 0x43, 0x44, 0x45), info)
        }
        // Empty.
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode(ByteArray(0), info)
        }
    }

    @Test
    fun `encode rejects a non-ASCII endpoint_id string`() {
        val info = hiddenPhoneEndpointInfo()
        // Non-ASCII characters do not fit cleanly in the 4-byte slug.
        assertThrows<IllegalArgumentException> {
            // Korean characters "한" (3 UTF-8 bytes each) — string length is 4 but
            // byte length differs, which the ASCII guard catches.
            BleServiceData.encode("한국어임", info)
        }
    }

    @Test
    fun `encode rejects out-of-range version and pcp`() {
        val info = hiddenPhoneEndpointInfo()
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("DROP", info, version = 8)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("DROP", info, version = -1)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("DROP", info, pcp = 32)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("DROP", info, pcp = -1)
        }
    }

    @Test
    fun `parse rejects truncated payloads`() {
        // Less than the 6-byte fixed header.
        assertThat(BleServiceData.parse(ByteArray(0))).isNull()
        assertThat(BleServiceData.parse(ByteArray(5))).isNull()

        // Length prefix says 17 but the buffer only has 16 EndpointInfo bytes.
        val shortPayload =
            byteArrayOf(0x23, 0x44, 0x52, 0x4F, 0x50, 0x11) +
                ByteArray(16) { 0x00 }
        assertThat(BleServiceData.parse(shortPayload)).isNull()
    }

    @Test
    fun `parse rejects a regular body whose declared EndpointInfo leaves no room for the MAC`() {
        // Regular-form header (hash prefix present) with a valid EndpointInfo
        // length, but the buffer is cut into the trailing 6-byte MAC (the
        // helper appends 2 bytes after the MAC, so drop those plus one MAC
        // byte). parseRegularBody must reject rather than read past the buffer
        // when extracting the Bluetooth MAC (#214).
        val info = hiddenPhoneEndpointInfo()
        val full = regularBleBody("LPPM", info)
        val truncated = full.copyOfRange(0, full.size - 3)
        assertThat(BleServiceData.parse(truncated)).isNull()
    }

    @Test
    fun `parse returns null when the embedded EndpointInfo cannot be decoded`() {
        // The 1-byte length prefix says 4 EndpointInfo bytes follow, but
        // EndpointInfo.parse requires at least 17 bytes (1 header + 16
        // metadata). The parser returns null rather than throwing — the
        // caller treats malformed peers as "ignore".
        val malformed = byteArrayOf(0x23, 0x41, 0x42, 0x43, 0x44, 0x04, 0x32, 0x00, 0x00, 0x00)
        assertThat(BleServiceData.parse(malformed)).isNull()
    }

    @Test
    fun `parse preserves a non-default version and pcp value`() {
        // Forge a payload with version=5 and pcp=7 to assert the parser
        // does not silently coerce them back to defaults.
        val info = hiddenPhoneEndpointInfo()
        val payload = BleServiceData.encode("DROP", info, version = 5, pcp = 7)

        // Byte 0 = 101_00111 = 0xA7.
        assertThat(payload[0].toInt() and 0xFF).isEqualTo(0xA7)
        val parsed = BleServiceData.parse(payload)!!
        assertThat(parsed.version).isEqualTo(5)
        assertThat(parsed.pcp).isEqualTo(7)
    }

    /**
     * Hidden, version=1, PHONE EndpointInfo with deterministic zero
     * metadata. Mirrors what `ReceiverForegroundService.defaultEndpointInfo`
     * generates in production (modulo the random metadata bytes).
     */
    private fun hiddenPhoneEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = true,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x00 },
            deviceName = null,
        )

    private fun regularBleBody(
        endpointId: String,
        endpointInfo: EndpointInfo,
    ): ByteArray {
        val infoBytes = endpointInfo.serialize()
        val payload =
            ByteArray(
                BleServiceData.REGULAR_FIXED_HEADER_LEN +
                    infoBytes.size +
                    BleServiceData.BLUETOOTH_MAC_LEN +
                    2,
            )
        var offset = 0
        payload[offset++] =
            BleServiceData.packVersionPcp(BleServiceData.DEFAULT_VERSION, BleServiceData.DEFAULT_PCP)
        NearbyServiceId.hashPrefix.copyInto(payload, destinationOffset = offset)
        offset += BleServiceData.SERVICE_ID_HASH_LEN
        endpointId.toByteArray(Charsets.US_ASCII).copyInto(payload, destinationOffset = offset)
        offset += BleServiceData.ENDPOINT_ID_LEN
        payload[offset++] = infoBytes.size.toByte()
        infoBytes.copyInto(payload, destinationOffset = offset)
        offset += infoBytes.size
        byteArrayOf(0x70, 0x4E, 0xE0.toByte(), 0x12, 0xDE.toByte(), 0x3A).copyInto(
            payload,
            destinationOffset = offset,
        )
        offset += BleServiceData.BLUETOOTH_MAC_LEN
        payload[offset++] = 0x00
        payload[offset] = 0x01
        return payload
    }

    private fun sha256FirstTwo(bytes: ByteArray): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .copyOfRange(0, BleServiceData.DEVICE_TOKEN_LEN)

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
