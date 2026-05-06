/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.endpoint

import dev.bluehouse.bada.protocol.crypto.Hkdf
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Nearby Connections DCT BLE advertisement (`0xFC73`) used by stock Quick
 * Share to expose a short receiver name without exceeding the 27-byte fast
 * advertisement budget.
 *
 * Stock Nearby handles this service before normal fast-advertisement parsing:
 * it parses the DCT fields, derives an endpoint id from the truncated device
 * name, then builds a normal Nearby advertisement internally. LibreDrop uses this
 * as the off-LAN identity hint while the canonical `0xFEF3` fast advertisement
 * remains the compact hidden 27-byte shape.
 */
public object DctAdvertisement {
    /** 16-bit DCT service UUID used by Nearby Connections BLE discovery. */
    public const val SERVICE_UUID_SHORT: Int = 0xFC73

    /** Canonical Bluetooth Base UUID expansion of [SERVICE_UUID_SHORT]. */
    public const val SERVICE_UUID_128_STRING: String =
        "0000fc73-0000-1000-8000-00805f9b34fb"

    /** Current DCT advertisement version. Encoded in the top 3 bits of byte 0. */
    public const val VERSION: Int = 1

    /** Maximum UTF-8 byte length of the inline DCT device-name prefix. */
    public const val MAX_DEVICE_NAME_BYTES: Int = 7

    /** Default dedup value used by google/nearby's DCT tests. */
    public const val DEFAULT_DEDUP: Int = 1

    /** DCT PSM value meaning "no L2CAP PSM advertised"; keep BLE GATT as the data path. */
    public const val DEFAULT_PSM: Int = 0

    private const val SERVICE_ID_HASH_LEN = 2
    private const val DEVICE_TOKEN_LEN = 2
    private const val ENDPOINT_ID_LEN = 4
    private const val DEDUP_MASK = 0x7F
    private const val TRUNCATED_FLAG = 0x80
    private const val VERSION_SHIFT = 5
    private const val DATA_TYPE_SERVICE_ID_HASH = 0x05
    private const val DATA_TYPE_PSM = 0x04
    private const val DATA_TYPE_DEVICE_INFORMATION = 0x07
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val TWO_BYTE_DE_FLAG = 0x80
    private const val ONE_BYTE_DE_LENGTH_SHIFT = 4
    private const val ONE_BYTE_DE_LENGTH_MASK = 0x07
    private const val ONE_BYTE_DE_TYPE_MASK = 0x0F
    private const val PSM_LEN = 2
    private const val DEVICE_INFO_FIXED_LEN = 1
    private const val HASH_ALGORITHM = "SHA-256"
    private const val SERVICE_ID_HASH_SALT = "DCT Protocol"
    private const val SERVICE_ID_HASH_INFO = "Service ID Hash"
    private const val ENDPOINT_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    /**
     * Encodes a DCT advertisement service-data value.
     *
     * The production receiver intentionally allows [psm] to be `0`: stock
     * parser code accepts it, and it prevents senders from preferring L2CAP
     * before LibreDrop implements an L2CAP BLE socket server.
     */
    @JvmOverloads
    @JvmStatic
    public fun encode(
        serviceId: String,
        deviceName: String,
        psm: Int = DEFAULT_PSM,
        dedup: Int = DEFAULT_DEDUP,
    ): ByteArray {
        require(serviceId.isNotEmpty()) { "serviceId must not be empty" }
        require(deviceName.isNotEmpty()) { "deviceName must not be empty" }
        require(psm in 0..0xFFFF) { "psm must fit in uint16, got $psm" }
        require(dedup in 0..DEDUP_MASK) { "dedup must fit in 7 bits (0..$DEDUP_MASK), got $dedup" }
        require(hasValidSurrogates(deviceName)) { "deviceName must be valid Unicode" }

        val originalNameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val truncatedName = truncateUtf8(deviceName, MAX_DEVICE_NAME_BYTES)
        val truncatedNameBytes = truncatedName.toByteArray(StandardCharsets.UTF_8)
        val truncated = truncatedNameBytes.size < originalNameBytes.size
        val deviceInfo =
            byteArrayOf(((if (truncated) TRUNCATED_FLAG else 0) or dedup).toByte()) +
                truncatedNameBytes

        val output =
            ByteArrayOutputStream().apply {
                write(VERSION shl VERSION_SHIFT)
                writeOneByteDataElement(DATA_TYPE_SERVICE_ID_HASH, computeServiceIdHash(serviceId))
                writeOneByteDataElement(DATA_TYPE_PSM, psm.toBigEndianU16())
                writeTwoByteDataElement(DATA_TYPE_DEVICE_INFORMATION, deviceInfo)
            }

        return output.toByteArray()
    }

    /**
     * Parses a DCT service-data value. Returns `null` for malformed or
     * unsupported data instead of throwing so scanners can ignore bad peers.
     */
    @Suppress("ReturnCount")
    @JvmStatic
    public fun parse(bytes: ByteArray): Parsed? {
        val reader = Reader(bytes)
        val header = reader.readU8() ?: return null
        if (header != VERSION shl VERSION_SHIFT) return null

        val serviceIdHash = reader.readDataElement() ?: return null
        if (serviceIdHash.type != DATA_TYPE_SERVICE_ID_HASH ||
            serviceIdHash.value.size != SERVICE_ID_HASH_LEN
        ) {
            return null
        }

        val psmElement = reader.readDataElement() ?: return null
        if (psmElement.type != DATA_TYPE_PSM || psmElement.value.size != PSM_LEN) return null
        val psm =
            ((psmElement.value[0].toInt() and UNSIGNED_BYTE_MASK) shl Byte.SIZE_BITS) or
                (psmElement.value[1].toInt() and UNSIGNED_BYTE_MASK)

        val deviceInfo = reader.readDataElement() ?: return null
        if (deviceInfo.type != DATA_TYPE_DEVICE_INFORMATION ||
            deviceInfo.value.size < DEVICE_INFO_FIXED_LEN
        ) {
            return null
        }
        val flags = deviceInfo.value[0].toInt() and UNSIGNED_BYTE_MASK
        val nameBytes = deviceInfo.value.copyOfRange(DEVICE_INFO_FIXED_LEN, deviceInfo.value.size)
        val deviceName =
            runCatching { String(nameBytes, StandardCharsets.UTF_8) }
                .getOrNull()
                ?: return null

        return Parsed(
            serviceIdHash = serviceIdHash.value.copyOf(),
            psm = psm,
            deviceName = deviceName,
            isDeviceNameTruncated = (flags and TRUNCATED_FLAG) != 0,
            dedup = flags and DEDUP_MASK,
        )
    }

    /** HKDF-SHA256 hash used by DCT to match a Nearby service id. */
    @JvmStatic
    public fun computeServiceIdHash(serviceId: String): ByteArray =
        Hkdf.derive(
            ikm = serviceId.toByteArray(StandardCharsets.UTF_8),
            salt = SERVICE_ID_HASH_SALT.toByteArray(StandardCharsets.US_ASCII),
            info = SERVICE_ID_HASH_INFO.toByteArray(StandardCharsets.US_ASCII),
            length = SERVICE_ID_HASH_LEN,
        )

    /** Device token stock Nearby derives from the DCT device name. */
    @JvmStatic
    public fun generateDeviceToken(deviceName: String): ByteArray =
        sha256(deviceName.toByteArray(StandardCharsets.UTF_8)).copyOf(DEVICE_TOKEN_LEN)

    /** Endpoint id stock Nearby derives from the DCT device name and dedup byte. */
    @JvmStatic
    public fun generateEndpointId(
        dedup: Int,
        deviceName: String,
    ): String? {
        if (deviceName.isEmpty() || dedup !in 0..DEDUP_MASK || !hasValidSurrogates(deviceName)) {
            return null
        }
        val truncatedName = truncateUtf8(deviceName, MAX_DEVICE_NAME_BYTES)
        val hashInput =
            truncatedName.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(dedup.toByte())
        val hash = sha256(hashInput)
        return buildString(ENDPOINT_ID_LEN) {
            repeat(ENDPOINT_ID_LEN) { index ->
                val unsigned = hash[index].toInt() and UNSIGNED_BYTE_MASK
                append(ENDPOINT_ID_ALPHABET[unsigned % ENDPOINT_ID_ALPHABET.length])
            }
        }
    }

    private fun ByteArrayOutputStream.writeOneByteDataElement(
        type: Int,
        value: ByteArray,
    ) {
        require(type in 1..ONE_BYTE_DE_TYPE_MASK) { "one-byte data-element type out of range: $type" }
        require(value.size <= ONE_BYTE_DE_LENGTH_MASK) {
            "one-byte data-element value too large: ${value.size}"
        }
        write((value.size shl ONE_BYTE_DE_LENGTH_SHIFT) or type)
        write(value)
    }

    private fun ByteArrayOutputStream.writeTwoByteDataElement(
        type: Int,
        value: ByteArray,
    ) {
        require(type in 1..UNSIGNED_BYTE_MASK) { "two-byte data-element type out of range: $type" }
        require(value.size <= DEDUP_MASK) { "two-byte data-element value too large: ${value.size}" }
        write(TWO_BYTE_DE_FLAG or value.size)
        write(type)
        write(value)
    }

    private fun Int.toBigEndianU16(): ByteArray =
        byteArrayOf(
            ((this ushr Byte.SIZE_BITS) and UNSIGNED_BYTE_MASK).toByte(),
            (this and UNSIGNED_BYTE_MASK).toByte(),
        )

    private fun truncateUtf8(
        value: String,
        maxBytes: Int,
    ): String {
        val out = StringBuilder()
        var offset = 0
        var used = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val next = String(Character.toChars(codePoint))
            val nextSize = next.toByteArray(StandardCharsets.UTF_8).size
            if (used + nextSize > maxBytes) break
            out.append(next)
            used += nextSize
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    @Suppress("ReturnCount")
    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return false
                    }
                    index += 2
                }
                ch.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes)

    /** Parsed DCT advertisement fields. */
    public data class Parsed(
        public val serviceIdHash: ByteArray,
        public val psm: Int,
        public val deviceName: String,
        public val isDeviceNameTruncated: Boolean,
        public val dedup: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return serviceIdHash.contentEquals(other.serviceIdHash) &&
                psm == other.psm &&
                deviceName == other.deviceName &&
                isDeviceNameTruncated == other.isDeviceNameTruncated &&
                dedup == other.dedup
        }

        override fun hashCode(): Int {
            var result = serviceIdHash.contentHashCode()
            result = 31 * result + psm
            result = 31 * result + deviceName.hashCode()
            result = 31 * result + isDeviceNameTruncated.hashCode()
            result = 31 * result + dedup
            return result
        }
    }

    private data class DataElement(
        val type: Int,
        val value: ByteArray,
    )

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var offset = 0

        fun readU8(): Int? {
            if (offset >= bytes.size) return null
            return bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
        }

        @Suppress("ReturnCount")
        fun readDataElement(): DataElement? {
            val first = readU8() ?: return null
            val type: Int
            val length: Int
            if ((first and TWO_BYTE_DE_FLAG) != 0) {
                length = first and DEDUP_MASK
                type = readU8() ?: return null
            } else {
                length = (first ushr ONE_BYTE_DE_LENGTH_SHIFT) and ONE_BYTE_DE_LENGTH_MASK
                type = first and ONE_BYTE_DE_TYPE_MASK
            }
            if (type == 0 || offset + length > bytes.size) return null
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return DataElement(type, value)
        }
    }
}
