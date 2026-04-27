/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import java.nio.charset.StandardCharsets

/**
 * Quick Share **endpoint info** — the packed binary descriptor advertised by
 * a Nearby Connections peer.
 *
 * The same byte layout appears in two interop-critical places:
 *
 *  1. As the value of the mDNS `_FC9F5ED42C8A._tcp.` TXT record key `n`,
 *     URL-safe-base64 encoded (no padding).
 *  2. As the `endpoint_info` field of `ConnectionRequestFrame` in
 *     `offline_wire_formats.proto`, sent raw (no base64).
 *
 * The structure is therefore the lingua franca every Quick Share peer must
 * agree on byte-for-byte. The reference layout, taken from
 * [PROTOCOL.md](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md)
 * and the NearDrop Swift implementation
 * (`NearbyShare/NearbyConnectionManager.swift:EndpointInfo`), is:
 *
 * ```
 * +--------+-----------------+-------------+--------+----------------------+
 * | byte 0 |  bytes 1..16    |   byte 17   | byte18 |     trailing TLV     |
 * |--------|-----------------|-------------|--------|----------------------|
 * | bits   | salt(2) + key   | name length | name   | type(1) length(1)    |
 * | packed |     (14)        |   (1 byte)  | (UTF8) |    value(length)     |
 * +--------+-----------------+-------------+--------+----------------------+
 * ```
 *
 * **Bit packing of byte 0** (most-significant-bit first):
 * ```
 *   bit:   7   6   5   4   3   2   1   0
 *        \_______/   |   \_______/   |
 *          version  vis  deviceType  reserved
 * ```
 *
 * - `version` (3 bits, 0..7): Currently always `1`. Other values are
 *   accepted by [parse] for forward compatibility but are surfaced verbatim
 *   so the caller can decide what to do with them.
 * - `visibility` (1 bit): `0` = visible (everyone), `1` = hidden
 *   (contacts-only / temporarily hidden). When hidden, the device name field
 *   is **omitted entirely** — the byte stream ends after the 16-byte
 *   metadata block (or whatever optional TLV records follow it).
 * - `deviceType` (3 bits): see [DeviceType].
 * - `reserved` (1 bit): currently always 0. Round-tripped verbatim by
 *   [parse]/[serialize] via the explicit reserved byte handling so a future
 *   protocol revision that starts using this bit cannot break parity.
 *
 * **Metadata** is a 16-byte blob composed of a 2-byte salt followed by a
 * 14-byte encrypted-metadata-key. Both are produced by Google's contact
 * graph during account-linked Nearby Share enrollment; for the GMS-free
 * Quick Share use-case targeted by this project, random bytes are
 * indistinguishable from real metadata to peers and are perfectly fine.
 *
 * **Trailing TLV records** are zero or more `type(1) | length(1) | value`
 * tuples appended after the device name (or after the metadata block in
 * hidden mode). The length byte is unsigned (`0..255`). Two record types
 * are documented in PROTOCOL.md:
 *
 *   - Type `1` — QR code data: in visible mode, the 4-byte advertising
 *     token; in hidden mode, AES-GCM-encrypted name material.
 *   - Type `2` — vendor ID: a single byte (`0` = none, `1` = Samsung).
 *
 * Unknown TLV types are preserved verbatim across [parse]/[serialize] so we
 * stay forward-compatible with whatever Google adds next.
 *
 * **Why a data class?** Equality is value-based, which makes round-trip
 * testing trivial and lets fixtures be compared with plain `assertEquals`.
 *
 * @see DeviceType
 * @see <a href="https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md">
 *   NearDrop PROTOCOL.md (the canonical informal spec)</a>
 */
public data class EndpointInfo(
    /** 0..7. Currently `1` for all peers in the wild. */
    val version: Int,
    /** `false` = visible, `true` = hidden (device name omitted on the wire). */
    val hidden: Boolean,
    /** See [DeviceType]. Unknown raw values are surfaced as [DeviceType.UNKNOWN]. */
    val deviceType: DeviceType,
    /** Reserved bit (1 bit). Always `false` today; round-tripped for future use. */
    val reserved: Boolean,
    /** Exactly [METADATA_LEN] bytes (2-byte salt + 14-byte encrypted key). */
    val metadata: ByteArray,
    /** UTF-8 device name. Must be `null` when [hidden] is `true`. */
    val deviceName: String?,
    /** Optional trailing TLV records, preserved verbatim across round-trips. */
    val tlvRecords: List<TlvRecord> = emptyList(),
) {
    init {
        require(version in 0..MAX_VERSION) {
            "version must fit in 3 bits (0..$MAX_VERSION), got $version"
        }
        require(metadata.size == METADATA_LEN) {
            "metadata must be exactly $METADATA_LEN bytes, got ${metadata.size}"
        }
        if (hidden) {
            require(deviceName == null) {
                "deviceName must be null when hidden=true (visibility bit hides it on the wire)"
            }
        } else {
            require(deviceName != null) {
                "deviceName must be non-null when hidden=false"
            }
            val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
            require(nameBytes.size <= MAX_NAME_LEN) {
                "deviceName UTF-8 byte length must fit in 1 byte (0..$MAX_NAME_LEN), " +
                    "got ${nameBytes.size}"
            }
        }
    }

    /**
     * Encodes this descriptor into the canonical Quick Share wire format.
     *
     * The result is suitable both as raw bytes for `ConnectionRequestFrame.endpoint_info`
     * and (via [Base64Url.encode]) as the value of the mDNS TXT record key `n`.
     *
     * The output is freshly allocated; mutating it never affects this instance.
     */
    public fun serialize(): ByteArray {
        val nameBytes =
            if (hidden) ByteArray(0) else deviceName!!.toByteArray(StandardCharsets.UTF_8)

        val tlvSize = tlvRecords.sumOf { TLV_HEADER_LEN + it.value.size }
        val totalSize =
            HEADER_LEN +
                METADATA_LEN +
                (if (hidden) 0 else NAME_LEN_BYTES + nameBytes.size) +
                tlvSize

        val out = ByteArray(totalSize)
        out[0] = packHeader(version, hidden, deviceType, reserved)
        metadata.copyInto(out, destinationOffset = HEADER_LEN)

        var offset = HEADER_LEN + METADATA_LEN
        if (!hidden) {
            out[offset] = nameBytes.size.toByte()
            offset += NAME_LEN_BYTES
            nameBytes.copyInto(out, destinationOffset = offset)
            offset += nameBytes.size
        }

        for (record in tlvRecords) {
            out[offset] = record.type.toByte()
            out[offset + 1] = record.value.size.toByte()
            record.value.copyInto(out, destinationOffset = offset + TLV_HEADER_LEN)
            offset += TLV_HEADER_LEN + record.value.size
        }
        check(offset == totalSize) {
            // Defensive: catches any future arithmetic drift between the size
            // calculation above and the offset bookkeeping below.
            "internal serializer offset drift: wrote $offset bytes, expected $totalSize"
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Equality / hashCode — required because data classes don't compare
    // ByteArray contents structurally by default.
    // ---------------------------------------------------------------------

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EndpointInfo) return false
        return version == other.version &&
            hidden == other.hidden &&
            deviceType == other.deviceType &&
            reserved == other.reserved &&
            metadata.contentEquals(other.metadata) &&
            deviceName == other.deviceName &&
            tlvRecords == other.tlvRecords
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + hidden.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + reserved.hashCode()
        result = 31 * result + metadata.contentHashCode()
        result = 31 * result + (deviceName?.hashCode() ?: 0)
        result = 31 * result + tlvRecords.hashCode()
        return result
    }

    public companion object {
        /** Length of the packed header byte (`version|visibility|deviceType|reserved`). */
        public const val HEADER_LEN: Int = 1

        /** Length of the salt + encrypted-metadata-key blob. */
        public const val METADATA_LEN: Int = 16

        /** Length of the salt prefix inside [METADATA_LEN]. */
        public const val SALT_LEN: Int = 2

        /** Length of the encrypted-metadata-key suffix inside [METADATA_LEN]. */
        public const val ENCRYPTED_KEY_LEN: Int = 14

        /** Length of the device-name length byte (when present). */
        public const val NAME_LEN_BYTES: Int = 1

        /** Length of the TLV header (`type(1) + length(1)`). */
        public const val TLV_HEADER_LEN: Int = 2

        /** Maximum value of the 3-bit version field. */
        public const val MAX_VERSION: Int = 0b111

        /** Maximum UTF-8 byte length of the device name (1-byte length prefix). */
        public const val MAX_NAME_LEN: Int = 0xFF

        /** TLV type byte for QR code data (advertising token / encrypted name). */
        public const val TLV_TYPE_QR_CODE: Int = 1

        /** TLV type byte for vendor ID. */
        public const val TLV_TYPE_VENDOR_ID: Int = 2

        // -----------------------------------------------------------------
        // Bit packing constants for byte 0:
        //   bit 7..5 = version (3 bits)
        //   bit 4    = visibility (1 bit, 1 = hidden)
        //   bit 3..1 = deviceType (3 bits)
        //   bit 0    = reserved (1 bit)
        // -----------------------------------------------------------------

        /** 3-bit mask for fields that are 3 bits wide (version, deviceType). */
        private const val THREE_BIT_MASK: Int = 0b111

        /** 1-bit mask for fields that are a single bit (visibility, reserved). */
        private const val ONE_BIT_MASK: Int = 0b1

        /** Mask used to convert a signed `Byte` into an unsigned int (`0..255`). */
        private const val UNSIGNED_BYTE_MASK: Int = 0xFF

        /** Bit position of the top of the version field (bits 7..5). */
        private const val VERSION_SHIFT: Int = 5

        /** Bit position of the visibility flag (bit 4). */
        private const val VISIBILITY_SHIFT: Int = 4

        /** Bit position of the top of the deviceType field (bits 3..1). */
        private const val DEVICE_TYPE_SHIFT: Int = 1

        /**
         * Parses an `EndpointInfo` from the canonical wire format.
         *
         * Returns `null` for any malformed input (truncated header,
         * truncated metadata, name length exceeding the buffer, malformed
         * TLV record). Callers must treat parse failures as "ignore this
         * peer" — never as a hard error — because in the wild we will see
         * truncated, padded, or experimental advertisements from peers we
         * do not control.
         *
         * UTF-8 decoding of the device name is **strict**: invalid byte
         * sequences cause [parse] to return `null` rather than silently
         * substituting U+FFFD, since two peers that disagree on the device
         * name byte-for-byte cannot agree on the SHA-256 advertising
         * fingerprint either.
         */
        @Suppress("ReturnCount")
        public fun parse(bytes: ByteArray): EndpointInfo? {
            if (bytes.size < HEADER_LEN + METADATA_LEN) return null

            val header = bytes[0].toInt() and UNSIGNED_BYTE_MASK
            val version = (header ushr VERSION_SHIFT) and THREE_BIT_MASK
            val hidden = ((header ushr VISIBILITY_SHIFT) and ONE_BIT_MASK) == 1
            val deviceTypeRaw = (header ushr DEVICE_TYPE_SHIFT) and THREE_BIT_MASK
            val reserved = (header and ONE_BIT_MASK) == 1

            val metadata = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + METADATA_LEN)
            var offset = HEADER_LEN + METADATA_LEN

            val deviceName: String?
            if (hidden) {
                deviceName = null
            } else {
                if (offset + NAME_LEN_BYTES > bytes.size) return null
                val nameLen = bytes[offset].toInt() and UNSIGNED_BYTE_MASK
                offset += NAME_LEN_BYTES
                if (offset + nameLen > bytes.size) return null
                deviceName =
                    try {
                        decodeUtf8Strict(bytes, offset, nameLen)
                    } catch (_: java.nio.charset.CharacterCodingException) {
                        return null
                    }
                offset += nameLen
            }

            val tlvRecords = mutableListOf<TlvRecord>()
            while (offset < bytes.size) {
                if (offset + TLV_HEADER_LEN > bytes.size) return null
                val type = bytes[offset].toInt() and UNSIGNED_BYTE_MASK
                val length = bytes[offset + 1].toInt() and UNSIGNED_BYTE_MASK
                offset += TLV_HEADER_LEN
                if (offset + length > bytes.size) return null
                val value = bytes.copyOfRange(offset, offset + length)
                tlvRecords += TlvRecord(type, value)
                offset += length
            }

            return EndpointInfo(
                version = version,
                hidden = hidden,
                deviceType = DeviceType.fromRaw(deviceTypeRaw),
                reserved = reserved,
                metadata = metadata,
                deviceName = deviceName,
                tlvRecords = tlvRecords.toList(),
            )
        }

        internal fun packHeader(
            version: Int,
            hidden: Boolean,
            deviceType: DeviceType,
            reserved: Boolean,
        ): Byte {
            val visBit = if (hidden) 1 else 0
            val resBit = if (reserved) 1 else 0
            val packed =
                ((version and THREE_BIT_MASK) shl VERSION_SHIFT) or
                    ((visBit and ONE_BIT_MASK) shl VISIBILITY_SHIFT) or
                    ((deviceType.raw and THREE_BIT_MASK) shl DEVICE_TYPE_SHIFT) or
                    (resBit and ONE_BIT_MASK)
            return packed.toByte()
        }

        private fun decodeUtf8Strict(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): String {
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val buffer = java.nio.ByteBuffer.wrap(bytes, offset, length)
            return decoder.decode(buffer).toString()
        }
    }
}
