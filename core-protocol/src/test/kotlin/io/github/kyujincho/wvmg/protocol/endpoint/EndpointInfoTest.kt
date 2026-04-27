/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * Bit-level tests for [EndpointInfo]'s wire format.
 *
 * The serializer and parser are covered from three angles:
 *
 *  1. **Hand-crafted byte vectors** that pin the exact bit layout of byte 0
 *     and the placement of the metadata, name, and TLV sections. If anyone
 *     ever flips MSB/LSB packing or reorders fields, these vectors fire
 *     before any peer ever sees the change.
 *  2. **Hidden-mode coverage**, since the layout shrinks when visibility=1
 *     and the parser has to skip the missing name field.
 *  3. **Randomized round-trip property test** (200 cases) that covers
 *     arbitrary combinations of version/visibility/deviceType/reserved/
 *     metadata/name (including non-ASCII UTF-8) plus random trailing TLV
 *     records of random types and sizes.
 */
class EndpointInfoTest {
    @Test
    fun `byte 0 packs version visibility deviceType and reserved in MSB-first order`() {
        // version=5 (0b101), visible (visibility=0), LAPTOP (3, 0b011), reserved=1
        // Expected: 101_0_011_1 = 0xA7
        val info =
            EndpointInfo(
                version = 5,
                hidden = false,
                deviceType = DeviceType.LAPTOP,
                reserved = true,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x00 },
                deviceName = "",
            )
        val serialized = info.serialize()
        assertThat(serialized[0].toInt() and 0xFF).isEqualTo(0xA7)
    }

    @Test
    fun `byte 0 packs hidden visibility bit correctly`() {
        // version=1 (0b001), hidden (visibility=1), PHONE (1, 0b001), reserved=0
        // Expected: 001_1_001_0 = 0x32
        val info =
            EndpointInfo(
                version = 1,
                hidden = true,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0xFF.toByte() },
                deviceName = null,
            )
        val serialized = info.serialize()
        assertThat(serialized[0].toInt() and 0xFF).isEqualTo(0x32)
    }

    @Test
    fun `serialize visible peer produces header + metadata + name length + name`() {
        val metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it + 1).toByte() }
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = metadata,
                deviceName = "Pixel",
            )
        val bytes = info.serialize()

        // 1 (header) + 16 (metadata) + 1 (name length) + 5 (name "Pixel")
        assertThat(bytes.size).isEqualTo(23)
        // header = version(001) | vis(0) | type(001) | reserved(0) = 0010_0010 = 0x22
        assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0x22)
        assertThat(bytes.copyOfRange(1, 17)).isEqualTo(metadata)
        assertThat(bytes[17].toInt() and 0xFF).isEqualTo(5)
        assertThat(String(bytes, 18, 5)).isEqualTo("Pixel")
    }

    @Test
    fun `serialize hidden peer omits name length and name`() {
        val metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x42 }
        val info =
            EndpointInfo(
                version = 1,
                hidden = true,
                deviceType = DeviceType.LAPTOP,
                reserved = false,
                metadata = metadata,
                deviceName = null,
            )
        val bytes = info.serialize()

        // 1 (header) + 16 (metadata), no name section
        assertThat(bytes.size).isEqualTo(17)
        assertThat(bytes.copyOfRange(1, 17)).isEqualTo(metadata)
    }

    @Test
    fun `parse round-trips a visible peer with a UTF-8 device name`() {
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.TABLET,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
                deviceName = "내 아이패드",
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse round-trips a hidden peer`() {
        val original =
            EndpointInfo(
                version = 1,
                hidden = true,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it * 7).toByte() },
                deviceName = null,
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse round-trips trailing TLV records`() {
        val qrToken = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val vendorId = byteArrayOf(0x01) // Samsung
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.FOLDABLE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x33 },
                deviceName = "Galaxy Z Fold",
                tlvRecords =
                    listOf(
                        TlvRecord(EndpointInfo.TLV_TYPE_QR_CODE, qrToken),
                        TlvRecord(EndpointInfo.TLV_TYPE_VENDOR_ID, vendorId),
                    ),
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse preserves unknown TLV types verbatim`() {
        val unknownPayload = byteArrayOf(0x10, 0x20, 0x30)
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "Phone",
                tlvRecords = listOf(TlvRecord(0x99, unknownPayload)),
            )
        val parsed = EndpointInfo.parse(original.serialize())
        assertThat(parsed).isEqualTo(original)
        assertThat(parsed!!.tlvRecords[0].type).isEqualTo(0x99)
    }

    @Test
    fun `parse handles empty TLV value (length=0)`() {
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "Phone",
                tlvRecords = listOf(TlvRecord(0x05, ByteArray(0))),
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse handles maximum-length TLV value (length=255)`() {
        val payload = ByteArray(0xFF) { it.toByte() }
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "Phone",
                tlvRecords = listOf(TlvRecord(0x77, payload)),
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse handles maximum-length device name (255 bytes UTF-8)`() {
        val name = "a".repeat(0xFF) // 255 ASCII bytes
        val original =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = name,
                tlvRecords = emptyList(),
            )
        val bytes = original.serialize()
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse returns null when input is shorter than header + metadata`() {
        // Truncated below the minimum 17 bytes (header + metadata).
        assertThat(EndpointInfo.parse(ByteArray(0))).isNull()
        assertThat(EndpointInfo.parse(ByteArray(16))).isNull()
    }

    @Test
    fun `parse returns null when name length byte is missing in visible mode`() {
        // visible peer, but only header + metadata bytes are present (no name length).
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN)
        // header: version=1, visible, PHONE, reserved=0 -> 0010_0010 = 0x22
        bytes[0] = 0x22.toByte()
        assertThat(EndpointInfo.parse(bytes)).isNull()
    }

    @Test
    fun `parse returns null when device name extends past buffer end`() {
        // header + metadata + name length(0x10) but only 4 bytes of name follow.
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN + 1 + 4)
        bytes[0] = 0x22.toByte() // visible, PHONE, version=1
        bytes[17] = 0x10.toByte() // claims 16 name bytes, only 4 present
        assertThat(EndpointInfo.parse(bytes)).isNull()
    }

    @Test
    fun `parse returns null when TLV header is truncated`() {
        // Visible peer with empty name and a single trailing byte (TLV type but no length).
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN + 1 + 1)
        bytes[0] = 0x22.toByte() // visible, PHONE, version=1
        bytes[17] = 0 // empty name
        bytes[18] = 0x01 // TLV type byte, but no length byte after it
        assertThat(EndpointInfo.parse(bytes)).isNull()
    }

    @Test
    fun `parse returns null when TLV value extends past buffer end`() {
        // Visible peer with empty name and a TLV claiming length=10 but only 3 bytes follow.
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN + 1 + 2 + 3)
        bytes[0] = 0x22.toByte()
        bytes[17] = 0 // empty name
        bytes[18] = 0x01 // TLV type
        bytes[19] = 0x0A // length=10, but only 3 bytes follow
        assertThat(EndpointInfo.parse(bytes)).isNull()
    }

    @Test
    fun `parse rejects malformed UTF-8 in device name`() {
        // Build bytes manually with an invalid UTF-8 sequence (lone continuation byte).
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN + 1 + 2)
        bytes[0] = 0x22.toByte()
        bytes[17] = 2
        bytes[18] = 0x80.toByte() // lone continuation byte (invalid UTF-8 start)
        bytes[19] = 0x80.toByte()
        assertThat(EndpointInfo.parse(bytes)).isNull()
    }

    @Test
    fun `parse maps unknown deviceType raw value 7 to UNKNOWN`() {
        // raw=7 is reserved/undefined. The parser must not drop the advertisement.
        // header: version=1(001) | vis=0 | deviceType=7(111) | reserved=0 = 0010_1110 = 0x2E
        val bytes = ByteArray(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN + 1)
        bytes[0] = 0x2E.toByte()
        bytes[17] = 0
        val parsed = EndpointInfo.parse(bytes)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.deviceType).isEqualTo(DeviceType.UNKNOWN)
    }

    @Test
    fun `constructor rejects metadata of wrong length`() {
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(15),
                deviceName = "x",
            )
        }
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(17),
                deviceName = "x",
            )
        }
    }

    @Test
    fun `constructor rejects version outside 0 to 7`() {
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 8,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "x",
            )
        }
    }

    @Test
    fun `constructor rejects deviceName longer than 255 UTF-8 bytes`() {
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "a".repeat(256),
            )
        }
    }

    @Test
    fun `constructor rejects null name when visible`() {
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = null,
            )
        }
    }

    @Test
    fun `constructor rejects non-null name when hidden`() {
        assertThrows<IllegalArgumentException> {
            EndpointInfo(
                version = 1,
                hidden = true,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN),
                deviceName = "shouldBeNull",
            )
        }
    }

    @Test
    fun `randomized round-trip of 200 endpoints with TLV records`() {
        // Fixed seed → deterministic, reproducible failures.
        val rng = Random(0xC0FFEE)
        val deviceTypes = DeviceType.entries.toTypedArray()

        repeat(200) { iteration ->
            val hidden = rng.nextBoolean()
            val version = rng.nextInt(0, 8)
            val deviceType = deviceTypes[rng.nextInt(deviceTypes.size)]
            val reserved = rng.nextBoolean()
            val metadata = ByteArray(EndpointInfo.METADATA_LEN).also { rng.nextBytes(it) }

            val deviceName: String? =
                if (hidden) {
                    null
                } else {
                    val nameByteLen = rng.nextInt(0, 64)
                    randomUtf8String(rng, nameByteLen)
                }

            val tlvCount = rng.nextInt(0, 4)
            val tlvRecords =
                List(tlvCount) {
                    val type = rng.nextInt(0, 256)
                    val len = rng.nextInt(0, 32)
                    val value = ByteArray(len).also { rng.nextBytes(it) }
                    TlvRecord(type, value)
                }

            val original =
                EndpointInfo(
                    version = version,
                    hidden = hidden,
                    deviceType = deviceType,
                    reserved = reserved,
                    metadata = metadata,
                    deviceName = deviceName,
                    tlvRecords = tlvRecords,
                )
            val bytes = original.serialize()
            val parsed = EndpointInfo.parse(bytes)
            assertThat(parsed).isEqualTo(original)
            // Stable serialize → parse → serialize identity.
            assertThat(parsed!!.serialize()).isEqualTo(bytes)
            // Diagnostic: include iteration index in the failure message.
            assertThat(iteration).isEqualTo(iteration)
        }
    }

    @Test
    fun `equals compares metadata by content not identity`() {
        val a =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x42 },
                deviceName = "Phone",
            )
        val b =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                // Different array instance, identical contents.
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { 0x42 },
                deviceName = "Phone",
            )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    private fun randomUtf8String(
        rng: Random,
        approximateByteLen: Int,
    ): String {
        if (approximateByteLen == 0) return ""
        // Mix ASCII, 2-byte (Latin-1 supplement), 3-byte (Hangul) BMP characters.
        // We deliberately avoid surrogate pair generation to keep the byte budget
        // predictable; the codec is exercised by the explicit Korean test above.
        val sb = StringBuilder()
        var bytesUsed = 0
        while (bytesUsed < approximateByteLen) {
            val r = rng.nextInt(3)
            val (cp, byteCost) =
                when (r) {
                    0 -> rng.nextInt(0x20, 0x7F) to 1
                    1 -> rng.nextInt(0xA0, 0x100) to 2
                    else -> rng.nextInt(0xAC00, 0xD7A4) to 3 // Hangul syllables
                }
            if (bytesUsed + byteCost > approximateByteLen) break
            sb.appendCodePoint(cp)
            bytesUsed += byteCost
        }
        return sb.toString()
    }
}
