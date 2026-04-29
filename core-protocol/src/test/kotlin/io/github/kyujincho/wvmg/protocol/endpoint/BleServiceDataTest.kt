/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Bit-exact regression tests for [BleServiceData] (issue #121).
 *
 * These tests pin the wire format we observed on a Galaxy peer's BLE
 * pulse during the c0150be triage:
 *
 * ```text
 * [ 0x23 PCP | endpoint_id | 0x11 length | 0x32 ... ]
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
        val endpointId = byteArrayOf(0x57, 0x76, 0x6D, 0x67) // ASCII "Wvmg"
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
        val payload = BleServiceData.encode("Wvmg", info)
        val parsed = BleServiceData.parse(payload)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(BleServiceData.DEFAULT_VERSION)
        assertThat(parsed.pcp).isEqualTo(BleServiceData.DEFAULT_PCP)
        assertThat(parsed.endpointId).isEqualTo(byteArrayOf(0x57, 0x76, 0x6D, 0x67))
        assertThat(parsed.endpointInfo).isEqualTo(info)
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
            BleServiceData.encode("Wvmg", info, version = 8)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("Wvmg", info, version = -1)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("Wvmg", info, pcp = 32)
        }
        assertThrows<IllegalArgumentException> {
            BleServiceData.encode("Wvmg", info, pcp = -1)
        }
    }

    @Test
    fun `parse rejects truncated payloads`() {
        // Less than the 6-byte fixed header.
        assertThat(BleServiceData.parse(ByteArray(0))).isNull()
        assertThat(BleServiceData.parse(ByteArray(5))).isNull()

        // Length prefix says 17 but the buffer only has 16 EndpointInfo bytes.
        val shortPayload =
            byteArrayOf(0x23, 0x57, 0x76, 0x6D, 0x67, 0x11) +
                ByteArray(16) { 0x00 }
        assertThat(BleServiceData.parse(shortPayload)).isNull()
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
        val payload = BleServiceData.encode("Wvmg", info, version = 5, pcp = 7)

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
}
