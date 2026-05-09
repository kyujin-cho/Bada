/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MagicNumber", "ReturnCount")

package dev.bluehouse.bada.protocol.endpoint

import java.security.MessageDigest
import java.security.SecureRandom

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
        private const val DUMMY_SERVICE_ID_LEN: Int = 128

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

        /**
         * Encodes a stock Nearby BLE v2 GATT advertisement header for a single
         * hosted advertisement slot.
         *
         * Stock Nearby inserts a random dummy service id into the bloom filter
         * before the real service id so the header does not trivially reveal
         * the exact service set. Its advertisement hash is chained from the
         * dummy bytes and the hosted GATT advertisement body; scanners use it
         * to decide whether the remote GATT slots need to be re-read.
         */
        @JvmStatic
        @JvmOverloads
        public fun encodeSingleSlot(
            serviceId: String,
            gattAdvertisement: ByteArray,
            psm: Int = 0,
            supportsExtendedAdvertisement: Boolean = false,
            dummyServiceId: ByteArray = randomDummyServiceId(),
        ): ByteArray {
            require(psm in 0..MAX_PSM) { "psm must fit in uint16, got $psm" }
            require(dummyServiceId.isNotEmpty()) { "dummyServiceId must not be empty" }
            require(serviceId.isNotEmpty()) { "serviceId must not be empty" }

            val bloomFilter =
                ServiceIdBloomFilter().apply {
                    add(dummyServiceId)
                    add(serviceId.toByteArray(Charsets.UTF_8))
                }
            val advertisementHash =
                advertisementHash(
                    advertisementHash(dummyServiceId) + gattAdvertisement,
                )

            return encodeRaw(
                supportsExtendedAdvertisement = supportsExtendedAdvertisement,
                numSlots = 1,
                serviceIdBloomFilter = bloomFilter.toByteArray(),
                advertisementHash = advertisementHash,
                psm = psm,
            )
        }

        @JvmStatic
        public fun advertisementHash(bytes: ByteArray): ByteArray =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .copyOf(ADVERTISEMENT_HASH_LEN)

        private fun encodeRaw(
            supportsExtendedAdvertisement: Boolean,
            numSlots: Int,
            serviceIdBloomFilter: ByteArray,
            advertisementHash: ByteArray,
            psm: Int,
        ): ByteArray {
            require(numSlots in 0..NUM_SLOTS_MASK) { "numSlots must fit in 4 bits, got $numSlots" }
            require(serviceIdBloomFilter.size == SERVICE_ID_BLOOM_FILTER_LEN) {
                "serviceIdBloomFilter must be $SERVICE_ID_BLOOM_FILTER_LEN bytes"
            }
            require(advertisementHash.size == ADVERTISEMENT_HASH_LEN) {
                "advertisementHash must be $ADVERTISEMENT_HASH_LEN bytes"
            }
            val out = ByteArray(HEADER_WITH_PSM_LEN)
            out[0] =
                (
                    (VERSION shl VERSION_SHIFT) or
                        (if (supportsExtendedAdvertisement) EXTENDED_ADVERTISEMENT_MASK else 0) or
                        numSlots
                ).toByte()
            serviceIdBloomFilter.copyInto(out, destinationOffset = 1)
            advertisementHash.copyInto(out, destinationOffset = 1 + SERVICE_ID_BLOOM_FILTER_LEN)
            val psmOffset = 1 + SERVICE_ID_BLOOM_FILTER_LEN + ADVERTISEMENT_HASH_LEN
            out[psmOffset] = ((psm ushr Byte.SIZE_BITS) and UNSIGNED_BYTE_MASK).toByte()
            out[psmOffset + 1] = (psm and UNSIGNED_BYTE_MASK).toByte()
            return out
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

        private fun randomDummyServiceId(): ByteArray = ByteArray(DUMMY_SERVICE_ID_LEN).also(SecureRandom()::nextBytes)
    }
}

private class ServiceIdBloomFilter {
    private val bits: BooleanArray =
        BooleanArray(BleAdvertisementHeader.SERVICE_ID_BLOOM_FILTER_LEN * Byte.SIZE_BITS)

    fun add(bytes: ByteArray) {
        val (hash1, hash2) =
            murmurHash3X64Low64(bytes).let { low64 ->
                (low64 and LOWER_32_BITS).toInt() to ((low64 ushr Int.SIZE_BITS) and LOWER_32_BITS).toInt()
            }
        for (i in 1..HASH_REPETITIONS) {
            var combinedHash = hash1 + i * hash2
            if (combinedHash < 0) {
                combinedHash = combinedHash.inv()
            }
            bits[combinedHash % bits.size] = true
        }
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(BleAdvertisementHeader.SERVICE_ID_BLOOM_FILTER_LEN)
        for (byteIndex in out.indices) {
            var value = 0
            for (bitIndex in 0 until Byte.SIZE_BITS) {
                if (bits[byteIndex * Byte.SIZE_BITS + bitIndex]) {
                    value = value or (1 shl bitIndex)
                }
            }
            out[byteIndex] = value.toByte()
        }
        return out
    }

    private fun murmurHash3X64Low64(bytes: ByteArray): Long {
        var h1 = 0L
        var h2 = 0L
        val blockEnd = bytes.size and -16
        var offset = 0
        while (offset < blockEnd) {
            var k1 = bytes.readLittleEndianLong(offset)
            var k2 = bytes.readLittleEndianLong(offset + Long.SIZE_BYTES)

            k1 *= C1
            k1 = java.lang.Long.rotateLeft(k1, 31)
            k1 *= C2
            h1 = h1 xor k1

            h1 = java.lang.Long.rotateLeft(h1, 27)
            h1 += h2
            h1 = h1 * 5 + H1_ADD

            k2 *= C2
            k2 = java.lang.Long.rotateLeft(k2, 33)
            k2 *= C1
            h2 = h2 xor k2

            h2 = java.lang.Long.rotateLeft(h2, 31)
            h2 += h1
            h2 = h2 * 5 + H2_ADD

            offset += 16
        }

        var k1 = 0L
        var k2 = 0L
        val tail = bytes.size - blockEnd
        for (i in 0 until minOf(tail, Long.SIZE_BYTES)) {
            k1 = k1 or ((bytes[blockEnd + i].toLong() and LOWER_8_BITS) shl (Byte.SIZE_BITS * i))
        }
        for (i in Long.SIZE_BYTES until tail) {
            k2 = k2 or ((bytes[blockEnd + i].toLong() and LOWER_8_BITS) shl (Byte.SIZE_BITS * (i - Long.SIZE_BYTES)))
        }
        if (tail > Long.SIZE_BYTES) {
            k2 *= C2
            k2 = java.lang.Long.rotateLeft(k2, 33)
            k2 *= C1
            h2 = h2 xor k2
        }
        if (tail > 0) {
            k1 *= C1
            k1 = java.lang.Long.rotateLeft(k1, 31)
            k1 *= C2
            h1 = h1 xor k1
        }

        h1 = h1 xor bytes.size.toLong()
        h2 = h2 xor bytes.size.toLong()

        h1 += h2
        h2 += h1

        h1 = fmix64(h1)
        h2 = fmix64(h2)

        h1 += h2
        return h1
    }

    private fun ByteArray.readLittleEndianLong(offset: Int): Long {
        var value = 0L
        for (i in 0 until Long.SIZE_BYTES) {
            value = value or ((this[offset + i].toLong() and LOWER_8_BITS) shl (Byte.SIZE_BITS * i))
        }
        return value
    }

    private fun fmix64(input: Long): Long {
        var k = input
        k = k xor (k ushr 33)
        k *= FMIX1
        k = k xor (k ushr 33)
        k *= FMIX2
        k = k xor (k ushr 33)
        return k
    }

    private companion object {
        private const val HASH_REPETITIONS: Int = 5
        private const val LOWER_8_BITS: Long = 0xFFL
        private const val LOWER_32_BITS: Long = 0xFFFF_FFFFL
        private const val C1: Long = -8663945395140668459L
        private const val C2: Long = 5545529020109919103L
        private const val H1_ADD: Long = 1390208809L
        private const val H2_ADD: Long = 944331445L
        private const val FMIX1: Long = -49064778989728563L
        private const val FMIX2: Long = -4265267296055464877L
    }
}
