/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount")

package dev.bluehouse.libredrop.protocol.endpoint

import java.security.MessageDigest

/**
 * Nearby Mediums BLE advertisement wrapper.
 *
 * `0xFEF3` legacy fast advertisements use the compact fast form
 * `[version/socket/fast][data_len][data][device_token]`. GATT advertisement
 * slots use the non-fast form
 * `[version/socket][service_id_hash][data_len_u32][data][device_token]`.
 * The inner [data] is the same endpoint advertisement body parsed by
 * [BleServiceData].
 */
public object BleAdvertisement {
    public const val SERVICE_ID_HASH_LEN: Int = 3
    public const val DEVICE_TOKEN_LEN: Int = 2
    public const val VERSION: Int = 2
    public const val SOCKET_VERSION: Int = 2

    private const val VERSION_MASK: Int = 0xE0
    private const val VERSION_SHIFT: Int = 5
    private const val SOCKET_VERSION_MASK: Int = 0x1C
    private const val SOCKET_VERSION_SHIFT: Int = 2
    private const val FAST_ADVERTISEMENT_MASK: Int = 0x02
    private const val SECOND_PROFILE_MASK: Int = 0x01
    private const val FAST_DATA_SIZE_LEN: Int = 1
    private const val DATA_SIZE_LEN: Int = 4
    private const val EXTRA_FIELDS_MASK_LEN: Int = 1
    private const val EXTRA_FIELD_PSM_MASK: Int = 0x01
    private const val EXTRA_FIELD_RX_INSTANT_CONNECTION_MASK: Int = 0x02
    private const val PSM_LEN: Int = 2
    private const val RX_INSTANT_CONNECTION_LEN_BYTES: Int = 1
    private const val MAX_UINT16: Int = 0xFFFF
    private const val UNSIGNED_BYTE_MASK: Int = 0xFF

    @JvmStatic
    public fun encodeGattAdvertisement(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        serviceIdHash: ByteArray = NearbyServiceId.hashPrefix,
        psm: Int = 0,
        secondProfile: Boolean = false,
    ): ByteArray {
        require(serviceIdHash.size == SERVICE_ID_HASH_LEN) {
            "serviceIdHash must be $SERVICE_ID_HASH_LEN bytes"
        }
        require(psm in 0..MAX_UINT16) { "psm must fit in uint16, got $psm" }
        val data = BleServiceData.encode(endpointId, endpointInfo)
        val fixed = ByteArray(1 + SERVICE_ID_HASH_LEN + DATA_SIZE_LEN + data.size + DEVICE_TOKEN_LEN)
        var offset = 0
        fixed[offset++] = packHeader(fastAdvertisement = false, secondProfile = secondProfile)
        serviceIdHash.copyInto(fixed, destinationOffset = offset)
        offset += SERVICE_ID_HASH_LEN
        writeUInt32(data.size, fixed, offset)
        offset += DATA_SIZE_LEN
        data.copyInto(fixed, destinationOffset = offset)
        offset += data.size
        sha256FirstTwo(data).copyInto(fixed, destinationOffset = offset)
        if (psm == 0) return fixed

        val out = ByteArray(fixed.size + EXTRA_FIELDS_MASK_LEN + PSM_LEN)
        fixed.copyInto(out)
        out[fixed.size] = EXTRA_FIELD_PSM_MASK.toByte()
        out[fixed.size + 1] = ((psm ushr Byte.SIZE_BITS) and UNSIGNED_BYTE_MASK).toByte()
        out[fixed.size + 2] = (psm and UNSIGNED_BYTE_MASK).toByte()
        return out
    }

    @Suppress("LongMethod")
    @JvmStatic
    public fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < 1) return null
        val header = bytes[0].toInt() and UNSIGNED_BYTE_MASK
        val version = (header and VERSION_MASK) ushr VERSION_SHIFT
        val socketVersion = (header and SOCKET_VERSION_MASK) ushr SOCKET_VERSION_SHIFT
        val fastAdvertisement = (header and FAST_ADVERTISEMENT_MASK) != 0
        val secondProfile = (header and SECOND_PROFILE_MASK) != 0
        if (version !in 1..VERSION || socketVersion !in 1..SOCKET_VERSION) return null

        var offset = 1
        val serviceIdHash: ByteArray?
        val dataLength: Int
        if (fastAdvertisement) {
            if (offset + FAST_DATA_SIZE_LEN > bytes.size) return null
            serviceIdHash = null
            dataLength = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
        } else {
            if (offset + SERVICE_ID_HASH_LEN + DATA_SIZE_LEN > bytes.size) return null
            serviceIdHash = bytes.copyOfRange(offset, offset + SERVICE_ID_HASH_LEN)
            offset += SERVICE_ID_HASH_LEN
            dataLength = readUInt32(bytes, offset) ?: return null
            offset += DATA_SIZE_LEN
        }
        if (dataLength < 0 || offset + dataLength > bytes.size) return null
        val data = bytes.copyOfRange(offset, offset + dataLength)
        offset += dataLength

        val deviceToken =
            if (offset + DEVICE_TOKEN_LEN <= bytes.size) {
                bytes.copyOfRange(offset, offset + DEVICE_TOKEN_LEN).also { offset += DEVICE_TOKEN_LEN }
            } else {
                ByteArray(0)
            }

        var psm = 0
        var rxInstantConnectionAdvertisement = ByteArray(0)
        if (offset + EXTRA_FIELDS_MASK_LEN <= bytes.size) {
            val mask = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
            if ((mask and EXTRA_FIELD_PSM_MASK) != 0) {
                if (offset + PSM_LEN > bytes.size) return null
                psm =
                    ((bytes[offset].toInt() and UNSIGNED_BYTE_MASK) shl Byte.SIZE_BITS) or
                    (bytes[offset + 1].toInt() and UNSIGNED_BYTE_MASK)
                offset += PSM_LEN
            }
            if ((mask and EXTRA_FIELD_RX_INSTANT_CONNECTION_MASK) != 0) {
                if (offset + RX_INSTANT_CONNECTION_LEN_BYTES > bytes.size) return null
                val len = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
                if (offset + len > bytes.size) return null
                rxInstantConnectionAdvertisement = bytes.copyOfRange(offset, offset + len)
                offset += len
            }
        }
        if (offset != bytes.size) return null

        return Parsed(
            version = version,
            socketVersion = socketVersion,
            fastAdvertisement = fastAdvertisement,
            secondProfile = secondProfile,
            serviceIdHash = serviceIdHash,
            data = data,
            deviceToken = deviceToken,
            psm = psm,
            rxInstantConnectionAdvertisement = rxInstantConnectionAdvertisement,
        )
    }

    private fun packHeader(
        fastAdvertisement: Boolean,
        secondProfile: Boolean = false,
    ): Byte {
        var header = (VERSION shl VERSION_SHIFT) or (SOCKET_VERSION shl SOCKET_VERSION_SHIFT)
        if (fastAdvertisement) header = header or FAST_ADVERTISEMENT_MASK
        if (secondProfile) header = header or SECOND_PROFILE_MASK
        return header.toByte()
    }

    private fun writeUInt32(
        value: Int,
        out: ByteArray,
        offset: Int,
    ) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun readUInt32(
        bytes: ByteArray,
        offset: Int,
    ): Int? {
        if (offset + DATA_SIZE_LEN > bytes.size) return null
        val value =
            ((bytes[offset].toInt() and UNSIGNED_BYTE_MASK) shl 24) or
                ((bytes[offset + 1].toInt() and UNSIGNED_BYTE_MASK) shl 16) or
                ((bytes[offset + 2].toInt() and UNSIGNED_BYTE_MASK) shl 8) or
                (bytes[offset + 3].toInt() and UNSIGNED_BYTE_MASK)
        return value.takeIf { it >= 0 }
    }

    private fun sha256FirstTwo(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(DEVICE_TOKEN_LEN)

    public data class Parsed(
        public val version: Int,
        public val socketVersion: Int,
        public val fastAdvertisement: Boolean,
        public val secondProfile: Boolean,
        public val serviceIdHash: ByteArray?,
        public val data: ByteArray,
        public val deviceToken: ByteArray,
        public val psm: Int,
        public val rxInstantConnectionAdvertisement: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return version == other.version &&
                socketVersion == other.socketVersion &&
                fastAdvertisement == other.fastAdvertisement &&
                secondProfile == other.secondProfile &&
                serviceIdHash.contentEqualsNullable(other.serviceIdHash) &&
                data.contentEquals(other.data) &&
                deviceToken.contentEquals(other.deviceToken) &&
                psm == other.psm &&
                rxInstantConnectionAdvertisement.contentEquals(other.rxInstantConnectionAdvertisement)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + socketVersion
            result = 31 * result + fastAdvertisement.hashCode()
            result = 31 * result + secondProfile.hashCode()
            result = 31 * result + (serviceIdHash?.contentHashCode() ?: 0)
            result = 31 * result + data.contentHashCode()
            result = 31 * result + deviceToken.contentHashCode()
            result = 31 * result + psm
            result = 31 * result + rxInstantConnectionAdvertisement.contentHashCode()
            return result
        }
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
