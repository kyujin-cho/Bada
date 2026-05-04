/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.endpoint

import java.nio.charset.StandardCharsets

/**
 * Nearby Connections Bluetooth Classic device-name advertisement.
 *
 * Google's open-source Nearby stack serializes Bluetooth Classic discovery
 * data into the local adapter name before base64-url encoding it. Stock Quick
 * Share senders can use this path when same-LAN TCP is not reachable yet,
 * making it a candidate initial-control bootstrap for client-isolated Wi-Fi.
 */
public object BluetoothDeviceName {
    /** Raw byte length of the endpoint id slug. */
    public const val ENDPOINT_ID_LEN: Int = 4

    /** Raw byte length of the SHA-256 service-id hash prefix. */
    public const val SERVICE_ID_HASH_LEN: Int = 3

    /** Raw byte length reserved by the Nearby V1 format. */
    public const val RESERVED_LEN: Int = 6

    /** Maximum endpoint-info bytes Google Nearby serializes into the name. */
    public const val MAX_ENDPOINT_INFO_LEN: Int = 131

    /** Fixed bytes before the endpoint-info payload. */
    public const val FIXED_HEADER_LEN: Int =
        1 + ENDPOINT_ID_LEN + SERVICE_ID_HASH_LEN + 1 + RESERVED_LEN + 1

    private const val WEB_RTC_CONNECTABLE_FLAG: Int = 0x01
    private const val UNSIGNED_BYTE_MASK: Int = 0xFF
    private const val ASCII_MAX: Int = 0x7F
    private const val VERSION_SHIFT: Int = 5
    private const val VERSION_MASK: Int = 0b111
    private const val PCP_MASK: Int = 0b11111

    /**
     * Encode the raw Bluetooth device-name bytes and wrap them in URL-safe
     * base64 without padding.
     */
    @JvmOverloads
    @JvmStatic
    public fun encode(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        version: Int = BleServiceData.DEFAULT_VERSION,
        pcp: Int = BleServiceData.DEFAULT_PCP,
        webRtcConnectable: Boolean = false,
    ): String =
        Base64Url.encode(
            encodeBytes(
                endpointId = endpointId,
                endpointInfo = endpointInfo,
                version = version,
                pcp = pcp,
                webRtcConnectable = webRtcConnectable,
            ),
        )

    /**
     * Convenience wrapper accepting a four-character ASCII endpoint id.
     */
    @JvmOverloads
    @JvmStatic
    public fun encode(
        endpointId: String,
        endpointInfo: EndpointInfo,
        version: Int = BleServiceData.DEFAULT_VERSION,
        pcp: Int = BleServiceData.DEFAULT_PCP,
        webRtcConnectable: Boolean = false,
    ): String {
        for (ch in endpointId) {
            require(ch.code in 0..ASCII_MAX) {
                "endpointId must be ASCII (got non-ASCII char in '$endpointId')"
            }
        }
        return encode(
            endpointId = endpointId.toByteArray(StandardCharsets.US_ASCII),
            endpointInfo = endpointInfo,
            version = version,
            pcp = pcp,
            webRtcConnectable = webRtcConnectable,
        )
    }

    /**
     * Encode the raw byte shape before the outer base64-url wrapper.
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeBytes(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        version: Int = BleServiceData.DEFAULT_VERSION,
        pcp: Int = BleServiceData.DEFAULT_PCP,
        webRtcConnectable: Boolean = false,
    ): ByteArray {
        require(version in 0..VERSION_MASK) {
            "version must fit in 3 bits (0..$VERSION_MASK), got $version"
        }
        require(pcp in 0..PCP_MASK) {
            "pcp must fit in 5 bits (0..$PCP_MASK), got $pcp"
        }
        require(endpointId.size == ENDPOINT_ID_LEN) {
            "endpointId must be exactly $ENDPOINT_ID_LEN bytes, got ${endpointId.size}"
        }
        require(NearbyServiceId.hashPrefix.size == SERVICE_ID_HASH_LEN) {
            "Nearby service id hash must be $SERVICE_ID_HASH_LEN bytes"
        }

        val infoBytes = endpointInfo.serialize()
        require(infoBytes.size <= MAX_ENDPOINT_INFO_LEN) {
            "EndpointInfo.serialize() must fit in Bluetooth device-name payload " +
                "(0..$MAX_ENDPOINT_INFO_LEN), got ${infoBytes.size}"
        }

        val out = ByteArray(FIXED_HEADER_LEN + infoBytes.size)
        var offset = 0
        out[offset++] = BleServiceData.packVersionPcp(version, pcp)
        endpointId.copyInto(out, destinationOffset = offset)
        offset += ENDPOINT_ID_LEN
        NearbyServiceId.hashPrefix.copyInto(out, destinationOffset = offset)
        offset += SERVICE_ID_HASH_LEN
        out[offset++] = if (webRtcConnectable) WEB_RTC_CONNECTABLE_FLAG.toByte() else 0
        offset += RESERVED_LEN
        out[offset++] = infoBytes.size.toByte()
        infoBytes.copyInto(out, destinationOffset = offset)
        return out
    }

    /** Parse a Bluetooth device-name string produced by [encode]. */
    @Suppress("ReturnCount")
    @JvmStatic
    public fun parse(encodedName: String): Parsed? {
        val bytes = Base64Url.decode(encodedName) ?: return null
        if (bytes.size < FIXED_HEADER_LEN) return null

        val header = bytes[0].toInt() and UNSIGNED_BYTE_MASK
        val version = (header ushr VERSION_SHIFT) and VERSION_MASK
        val pcp = header and PCP_MASK

        var offset = 1
        val endpointId = bytes.copyOfRange(offset, offset + ENDPOINT_ID_LEN)
        offset += ENDPOINT_ID_LEN
        val serviceIdHash = bytes.copyOfRange(offset, offset + SERVICE_ID_HASH_LEN)
        offset += SERVICE_ID_HASH_LEN
        val fieldByte = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
        offset += RESERVED_LEN
        val endpointInfoLength = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
        if (offset + endpointInfoLength > bytes.size) return null
        val endpointInfoBytes = bytes.copyOfRange(offset, offset + endpointInfoLength)
        val endpointInfo = EndpointInfo.parse(endpointInfoBytes) ?: return null
        return Parsed(
            version = version,
            pcp = pcp,
            endpointId = endpointId,
            serviceIdHash = serviceIdHash,
            webRtcConnectable = fieldByte and WEB_RTC_CONNECTABLE_FLAG == WEB_RTC_CONNECTABLE_FLAG,
            endpointInfo = endpointInfo,
        )
    }

    /** Parsed view of a Bluetooth Classic device-name advertisement. */
    public data class Parsed(
        public val version: Int,
        public val pcp: Int,
        public val endpointId: ByteArray,
        public val serviceIdHash: ByteArray,
        public val webRtcConnectable: Boolean,
        public val endpointInfo: EndpointInfo,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return version == other.version &&
                pcp == other.pcp &&
                endpointId.contentEquals(other.endpointId) &&
                serviceIdHash.contentEquals(other.serviceIdHash) &&
                webRtcConnectable == other.webRtcConnectable &&
                endpointInfo == other.endpointInfo
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + pcp
            result = 31 * result + endpointId.contentHashCode()
            result = 31 * result + serviceIdHash.contentHashCode()
            result = 31 * result + webRtcConnectable.hashCode()
            result = 31 * result + endpointInfo.hashCode()
            return result
        }
    }
}
