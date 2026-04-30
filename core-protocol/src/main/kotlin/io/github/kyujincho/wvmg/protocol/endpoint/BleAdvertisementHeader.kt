/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

package io.github.kyujincho.wvmg.protocol.endpoint

/**
 * Nearby BLE v2 GATT advertisement header.
 *
 * Stock Quick Share may put this compact header in the `0xFEF3`
 * service-data value instead of the full fast-advertisement body. The header
 * tells the scanner to connect to the advertiser's GATT service and read one
 * or more advertisement slots (`00000000-0000-3000-8000-...`) for the actual
 * [BleServiceData] payload.
 */
public data class BleAdvertisementHeader(
    public val version: Int,
    public val supportsExtendedAdvertisement: Boolean,
    public val numSlots: Int,
    public val serviceIdBloomFilter: ByteArray,
    public val advertisementHash: ByteArray,
    public val psm: Int,
) {
    init {
        require(version == VERSION) { "Only BLE advertisement-header v2 is supported, got $version" }
        require(numSlots in 0..NUM_SLOTS_MASK) { "numSlots must fit in 4 bits, got $numSlots" }
        require(serviceIdBloomFilter.size == SERVICE_ID_BLOOM_FILTER_LEN) {
            "serviceIdBloomFilter must be $SERVICE_ID_BLOOM_FILTER_LEN bytes"
        }
        require(advertisementHash.size == ADVERTISEMENT_HASH_LEN) {
            "advertisementHash must be $ADVERTISEMENT_HASH_LEN bytes"
        }
        require(psm in 0..MAX_PSM) { "psm must fit in uint16, got $psm" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleAdvertisementHeader) return false
        return version == other.version &&
            supportsExtendedAdvertisement == other.supportsExtendedAdvertisement &&
            numSlots == other.numSlots &&
            serviceIdBloomFilter.contentEquals(other.serviceIdBloomFilter) &&
            advertisementHash.contentEquals(other.advertisementHash) &&
            psm == other.psm
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + supportsExtendedAdvertisement.hashCode()
        result = 31 * result + numSlots
        result = 31 * result + serviceIdBloomFilter.contentHashCode()
        result = 31 * result + advertisementHash.contentHashCode()
        result = 31 * result + psm
        return result
    }

    public companion object {
        public const val VERSION: Int = 2
        public const val SERVICE_ID_BLOOM_FILTER_LEN: Int = 10
        public const val ADVERTISEMENT_HASH_LEN: Int = 4
        public const val MIN_HEADER_LEN: Int = 1 + SERVICE_ID_BLOOM_FILTER_LEN + ADVERTISEMENT_HASH_LEN
        public const val HEADER_WITH_PSM_LEN: Int = MIN_HEADER_LEN + 2

        private const val VERSION_MASK: Int = 0xE0
        private const val VERSION_SHIFT: Int = 5
        private const val EXTENDED_ADVERTISEMENT_MASK: Int = 0x10
        private const val NUM_SLOTS_MASK: Int = 0x0F
        private const val MAX_PSM: Int = 0xFFFF
        private const val UNSIGNED_BYTE_MASK: Int = 0xFF

        /**
         * Parses either the stock base64-url encoded service-data header or
         * the raw decoded bytes. Returns `null` for unrelated `0xFEF3`
         * payloads, malformed base64, unsupported versions, and truncation.
         */
        @Suppress("ReturnCount")
        @JvmStatic
        public fun parse(serviceData: ByteArray): BleAdvertisementHeader? {
            val raw = decodeHeaderBytes(serviceData) ?: return null
            if (raw.size != MIN_HEADER_LEN && raw.size != HEADER_WITH_PSM_LEN) return null

            val first = raw[0].toInt() and UNSIGNED_BYTE_MASK
            val version = (first and VERSION_MASK) ushr VERSION_SHIFT
            if (version != VERSION) return null
            val supportsExtendedAdvertisement = (first and EXTENDED_ADVERTISEMENT_MASK) != 0
            val numSlots = first and NUM_SLOTS_MASK

            var offset = 1
            val serviceIdBloomFilter = raw.copyOfRange(offset, offset + SERVICE_ID_BLOOM_FILTER_LEN)
            offset += SERVICE_ID_BLOOM_FILTER_LEN
            val advertisementHash = raw.copyOfRange(offset, offset + ADVERTISEMENT_HASH_LEN)
            offset += ADVERTISEMENT_HASH_LEN
            val psm =
                if (raw.size == HEADER_WITH_PSM_LEN) {
                    ((raw[offset].toInt() and UNSIGNED_BYTE_MASK) shl Byte.SIZE_BITS) or
                        (raw[offset + 1].toInt() and UNSIGNED_BYTE_MASK)
                } else {
                    0
                }

            return BleAdvertisementHeader(
                version = version,
                supportsExtendedAdvertisement = supportsExtendedAdvertisement,
                numSlots = numSlots,
                serviceIdBloomFilter = serviceIdBloomFilter,
                advertisementHash = advertisementHash,
                psm = psm,
            )
        }

        private fun decodeHeaderBytes(serviceData: ByteArray): ByteArray? {
            if (serviceData.size == MIN_HEADER_LEN || serviceData.size == HEADER_WITH_PSM_LEN) {
                return serviceData
            }
            val encoded =
                runCatching { String(serviceData, Charsets.US_ASCII) }
                    .getOrNull()
                    ?: return null
            return Base64Url.decode(encoded)
        }
    }
}
