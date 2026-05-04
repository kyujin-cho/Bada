/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.endpoint

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Quick Share **fast advertisement** BLE service-data payload (issue #121).
 *
 * This is the wire-shape stock Quick Share receivers (Galaxy / Pixel /
 * Windows Quick Share) emit on the `0xFEF3` "fast advertisement" service
 * UUID. It is what makes a peer appear in another peer's send sheet:
 * the mDNS TXT record alone is not enough — Samsung's One UI in
 * particular cross-references discovered Wi-Fi LAN services against
 * recently-seen BLE pulses on this UUID, and silently drops mDNS-only
 * peers from its picker. Issue #121 closes that loop on the receiver
 * side.
 *
 * ### Wire format
 *
 * Stock Nearby Connections wraps the fast-advertisement body in a BLE v2
 * frame before placing it in the `0xFEF3` service-data value:
 *
 * ```text
 * [ 0x4a | body_len | versPCP | endpoint_id | info_len | EndpointInfo | device_token[2] ]
 * ```
 *
 * The inner body shape captured from a Galaxy peer is:
 *
 * ```text
 * +--------+-------------------+--------+----------------------+
 * | byte 0 |   bytes 1..4      | byte 5 |   bytes 6..N         |
 * |--------|-------------------|--------|----------------------|
 * | versPCP|   endpoint_id     |  len   |    EndpointInfo      |
 * |  byte  | (4 ASCII bytes)   | (u8)   |    (`len` bytes)     |
 * +--------+-------------------+--------+----------------------+
 * ```
 *
 * - **body byte 0** packs `version (3 bits, MSB) | pcp (5 bits)`. Stock peers
 *   emit `version = 1`, `pcp = 3` (`PCP_HIGH` in google/nearby's
 *   `connections/implementation/mediums/ble_v2/ble_advertisement.cc`),
 *   yielding `0x23`.
 * - **body bytes 1..4** are an ASCII-printable, 4-byte endpoint identifier.
 *   The same string also surfaces as `mDNS instance name`'s 4-byte slug
 *   prefix and in the protocol-level `endpoint_id` of
 *   `ConnectionRequestFrame`. Stock peers use base64-url-style
 *   characters (uppercase + lowercase + digits + `-` / `_`); we accept
 *   any byte in ASCII printable range to stay forward-compatible.
 * - **body byte 5** is an unsigned 8-bit length giving how many EndpointInfo
 *   bytes follow. Same byte that gates the abbreviated 17-byte hidden
 *   form in NearDrop's BLE captures (`0x11`) and the longer
 *   "name-included" form on Pixel.
 * - **body bytes 6..N** are the **raw** [EndpointInfo.serialize] output —
 *   the exact same byte sequence we already put under the mDNS TXT key
 *   `n`, sans the URL-safe base64 wrapper. That keeps the on-the-wire
 *   identity of a peer aligned across the two channels.
 *
 * The payload is intentionally compact: with a hidden EndpointInfo
 * (no inline name, no TLV records) the inner body is **23 bytes**
 * (1 + 4 + 1 + 17). Stock peers append a 2-byte device token after the
 * length-delimited body, making the framed service-data value
 * **27 bytes**. That still fits alongside the 16-bit `service-data` AD
 * header inside the legacy 31-byte advertising-PDU budget without
 * needing extended advertising.
 *
 * ### Why this lives in `:core-protocol`
 *
 * The encoder is bit-exact against a captured Galaxy fingerprint and
 * shares all of its EndpointInfo logic with the mDNS path. Keeping it
 * in `:core-protocol` lets [BleServiceDataTest] in
 * `:core-protocol-test` pin the byte layout under JVM unit tests
 * without ever touching `BluetoothLeAdvertiser`. The Android-side
 * `BleQuickShareAdvertiser` in `:discovery-android` only translates the
 * resulting bytes into a platform `AdvertiseData`.
 *
 * @see EndpointInfo for the inner descriptor's bit packing.
 */
public object BleServiceData {
    /**
     * 16-bit "fast advertisement" service UUID stock Quick Share emits.
     * The 128-bit canonical form expanded onto the Bluetooth Base UUID
     * is in [SERVICE_UUID_128_STRING].
     *
     * The Quick Share BLE pulse we **scan** for (#33) uses a different
     * UUID, `0xFE2C` — the sender-side pulse — see
     * `BleQuickShareScanner.QUICK_SHARE_SERVICE_UUID_STRING` for the
     * full distinction.
     */
    public const val SERVICE_UUID_SHORT: Int = 0xFEF3

    /** Canonical 128-bit form of [SERVICE_UUID_SHORT]. */
    public const val SERVICE_UUID_128_STRING: String =
        "0000fef3-0000-1000-8000-00805f9b34fb"

    /** Length of the version/PCP header byte. */
    public const val HEADER_LEN: Int = 1

    /** BLE v2 frame type used by stock Nearby for fast advertisements. */
    public const val FRAME_TYPE_FAST_ADVERTISEMENT: Int = 0x4A

    /**
     * BLE v2 fast-advertisement frame type with the Nearby "second profile"
     * bit set.
     */
    public const val FRAME_TYPE_SECOND_PROFILE_FAST_ADVERTISEMENT: Int = 0x4B

    /** Length of the BLE v2 frame type + frame-length prefix. */
    public const val FRAME_HEADER_LEN: Int = 2

    /** Length of the trailing Nearby Mediums device token. */
    public const val DEVICE_TOKEN_LEN: Int = 2

    /** Length of the service-id hash prefix in regular BLE advertisements. */
    public const val SERVICE_ID_HASH_LEN: Int = 3

    /** One-byte extra-field mask appended by stock Nearby extended BLE advertisements. */
    public const val EXTRA_FIELDS_MASK_LEN: Int = 1

    /** Extra-field bit that indicates a following two-byte BLE L2CAP PSM value. */
    public const val EXTRA_FIELD_PSM_MASK: Int = 0x01

    /** Extra-field bit that indicates an inline RX instant-connection advertisement. */
    public const val EXTRA_FIELD_RX_INSTANT_CONNECTION_MASK: Int = 0x02

    /** Length of a PSM value in Nearby's BLE extra-field trailer. */
    public const val PSM_LEN: Int = 2

    /** Length prefix for the RX instant-connection advertisement extra field. */
    public const val RX_INSTANT_CONNECTION_LEN_BYTES: Int = 1

    /** Maximum unsigned 16-bit PSM value. */
    public const val MAX_PSM: Int = 0xFFFF

    /** Length of the ASCII endpoint_id slug. */
    public const val ENDPOINT_ID_LEN: Int = 4

    /** Length of the EndpointInfo length prefix (1-byte unsigned). */
    public const val ENDPOINT_INFO_LEN_BYTES: Int = 1

    /** Total fixed overhead before the EndpointInfo bytes. */
    public const val FIXED_HEADER_LEN: Int = HEADER_LEN + ENDPOINT_ID_LEN + ENDPOINT_INFO_LEN_BYTES

    /** Total fixed overhead before EndpointInfo in the regular non-fast body. */
    public const val REGULAR_FIXED_HEADER_LEN: Int =
        HEADER_LEN + SERVICE_ID_HASH_LEN + ENDPOINT_ID_LEN + ENDPOINT_INFO_LEN_BYTES

    /** Bluetooth MAC bytes carried after EndpointInfo in regular BLE advertisements. */
    public const val BLUETOOTH_MAC_LEN: Int = 6

    /**
     * Default protocol version stock Quick Share emits in the high
     * 3 bits of byte 0. Pinned at `1`; `0` is reserved by the spec and
     * `2..7` is unallocated as of 2026-04.
     */
    public const val DEFAULT_VERSION: Int = 1

    /**
     * Default Power-Class-of-Peer (`Pcp`) stock peers advertise — `3`
     * (`PCP_HIGH` in google/nearby's `ble_advertisement.cc`). We always
     * emit HIGH because a phone running the receiver service is, by
     * definition, line-powered or on battery in the user's hand —
     * neither qualifies as "low power" in the Nearby Connections sense.
     */
    public const val DEFAULT_PCP: Int = 3

    /** 3-bit mask for the version field (top of byte 0). */
    private const val VERSION_MASK: Int = 0b111

    /** 5-bit mask for the PCP field (bottom of byte 0). */
    private const val PCP_MASK: Int = 0b11111

    /** Bit position of the version field within byte 0 (top 3 bits). */
    private const val VERSION_SHIFT: Int = 5

    /** Mask used to convert a signed `Byte` into an unsigned int (`0..255`). */
    private const val UNSIGNED_BYTE_MASK: Int = 0xFF

    /** Maximum ASCII code point (`0x7F`). Inclusive upper bound. */
    private const val ASCII_MAX: Int = 0x7F

    /**
     * Maximum [EndpointInfo.serialize] byte length the 1-byte length
     * prefix can describe (`0..255`). EndpointInfo enforces its own
     * sub-field limits; this only constrains the BLE framing.
     */
    public const val MAX_ENDPOINT_INFO_LEN: Int = 0xFF

    /** Maximum inner body length describable by the BLE v2 frame prefix. */
    public const val MAX_FRAME_BODY_LEN: Int = 0xFF

    /**
     * Encodes the canonical fast-advertisement service-data payload.
     *
     * @param endpointId 4-byte ASCII identifier matching the same field
     *   in `ConnectionRequestFrame` and the mDNS instance-name slug.
     *   See [validateEndpointIdBytes] for the byte-shape contract.
     * @param endpointInfo the receiver's identity descriptor; the bytes
     *   placed after the length prefix are exactly [EndpointInfo.serialize].
     * @param version the 3-bit protocol version. Defaults to
     *   [DEFAULT_VERSION] (`1`).
     * @param pcp the 5-bit power-class-of-peer. Defaults to
     *   [DEFAULT_PCP] (`3`, `PCP_HIGH`).
     * @return a freshly-allocated byte array of length
     *   `6 + endpointInfo.serialize().size`.
     * @throws IllegalArgumentException for malformed inputs
     *   (wrong-length endpoint_id, EndpointInfo too large for the 1-byte
     *   length prefix, or out-of-range version/PCP).
     */
    @JvmOverloads
    @JvmStatic
    public fun encode(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
    ): ByteArray {
        require(version in 0..VERSION_MASK) {
            "version must fit in 3 bits (0..$VERSION_MASK), got $version"
        }
        require(pcp in 0..PCP_MASK) {
            "pcp must fit in 5 bits (0..$PCP_MASK), got $pcp"
        }
        validateEndpointIdBytes(endpointId)

        val infoBytes = endpointInfo.serialize()
        require(infoBytes.size <= MAX_ENDPOINT_INFO_LEN) {
            "EndpointInfo.serialize() must fit in 1 byte (0..$MAX_ENDPOINT_INFO_LEN), " +
                "got ${infoBytes.size}"
        }

        val out = ByteArray(FIXED_HEADER_LEN + infoBytes.size)
        out[0] = packVersionPcp(version, pcp)
        endpointId.copyInto(out, destinationOffset = HEADER_LEN)
        out[HEADER_LEN + ENDPOINT_ID_LEN] = infoBytes.size.toByte()
        infoBytes.copyInto(out, destinationOffset = FIXED_HEADER_LEN)
        return out
    }

    /**
     * Encodes the stock `0xFEF3` service-data value.
     *
     * This wraps [encode]'s fast-advertisement body in the two-byte BLE
     * v2 frame prefix captured from stock peers:
     *
     * ```text
     * [ FRAME_TYPE_FAST_ADVERTISEMENT (0x4a) | body_len | body... | device_token[2] ]
     * ```
     *
     * The trailing two bytes are outside the length-delimited body.
     * Stock Nearby Mediums surfaces them as `deviceToken`; leaving them
     * absent still lets the lower Nearby Connections scanner report an
     * endpoint, but Samsung's Quick Share UI does not promote that peer
     * to a visible share target in off-LAN discovery.
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeFramed(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray {
        val body = encode(endpointId, endpointInfo, version, pcp)
        require(body.size <= MAX_FRAME_BODY_LEN) {
            "fast-advertisement body must fit in 1 byte (0..$MAX_FRAME_BODY_LEN), got ${body.size}"
        }
        val deviceToken = deriveDeviceToken(body)
        val out = ByteArray(FRAME_HEADER_LEN + body.size + DEVICE_TOKEN_LEN)
        out[0] =
            if (secondProfile) {
                FRAME_TYPE_SECOND_PROFILE_FAST_ADVERTISEMENT.toByte()
            } else {
                FRAME_TYPE_FAST_ADVERTISEMENT.toByte()
            }
        out[1] = body.size.toByte()
        body.copyInto(out, destinationOffset = FRAME_HEADER_LEN)
        deviceToken.copyInto(out, destinationOffset = FRAME_HEADER_LEN + body.size)
        return out
    }

    /**
     * Encodes the extended-advertising form of [encodeFramed] with Nearby's
     * extra-field trailer carrying the BLE L2CAP PSM:
     *
     * ```text
     * [ framed_fast_advertisement | extra_mask(0x01) | psm_be16 ]
     * ```
     *
     * Stock Nearby only appends these extra fields to extended advertisements;
     * the compact legacy 27-byte shape has no remaining AD budget.
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeFramedWithPsm(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        psm: Int,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray =
        encodeFramedWithExtraFields(
            endpointId = endpointId,
            endpointInfo = endpointInfo,
            psm = psm,
            rxInstantConnectionAdvertisement = null,
            version = version,
            pcp = pcp,
            secondProfile = secondProfile,
        )

    /**
     * Encodes the extended-advertising form of [encodeFramed] with Nearby's
     * RX instant-connection extra field. The nested advertisement tells stock
     * senders which GATT socket profile to open for the selected visible peer.
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeFramedWithRxInstantConnection(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        rxInstantConnectionAdvertisement: ByteArray,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray =
        encodeFramedWithExtraFields(
            endpointId = endpointId,
            endpointInfo = endpointInfo,
            psm = null,
            rxInstantConnectionAdvertisement = rxInstantConnectionAdvertisement,
            version = version,
            pcp = pcp,
            secondProfile = secondProfile,
        )

    /**
     * Encodes the extended-advertising form of [encodeFramed] with one or more
     * Nearby BLE extra fields. Extra fields are emitted in the same order the
     * parser consumes them: optional PSM, then optional RX instant connection.
     */
    @Suppress("LongParameterList") // Mirrors the fast-advertisement frame knobs without another wrapper type.
    @JvmStatic
    public fun encodeFramedWithExtraFields(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
        psm: Int?,
        rxInstantConnectionAdvertisement: ByteArray?,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray {
        require(psm == null || psm in 1..MAX_PSM) {
            "psm must fit in uint16 and be non-zero, got $psm"
        }
        val rxAdvertisementTooLarge =
            rxInstantConnectionAdvertisement != null &&
                rxInstantConnectionAdvertisement.size > MAX_FRAME_BODY_LEN
        require(!rxAdvertisementTooLarge) {
            "rxInstantConnectionAdvertisement must fit in 1 byte (0..$MAX_FRAME_BODY_LEN), " +
                "got ${rxInstantConnectionAdvertisement?.size}"
        }

        val framed = encodeFramed(endpointId, endpointInfo, version, pcp, secondProfile)
        var mask = 0
        var extraLen = EXTRA_FIELDS_MASK_LEN
        if (psm != null) {
            mask = mask or EXTRA_FIELD_PSM_MASK
            extraLen += PSM_LEN
        }
        if (rxInstantConnectionAdvertisement != null) {
            mask = mask or EXTRA_FIELD_RX_INSTANT_CONNECTION_MASK
            extraLen += RX_INSTANT_CONNECTION_LEN_BYTES + rxInstantConnectionAdvertisement.size
        }
        if (mask == 0) return framed

        val out = ByteArray(framed.size + extraLen)
        framed.copyInto(out)
        var offset = framed.size
        out[offset++] = mask.toByte()
        if (psm != null) {
            out[offset++] = ((psm ushr Byte.SIZE_BITS) and UNSIGNED_BYTE_MASK).toByte()
            out[offset++] = (psm and UNSIGNED_BYTE_MASK).toByte()
        }
        if (rxInstantConnectionAdvertisement != null) {
            out[offset++] = rxInstantConnectionAdvertisement.size.toByte()
            rxInstantConnectionAdvertisement.copyInto(out, destinationOffset = offset)
        }
        return out
    }

    /**
     * String overload of [encodeFramedWithPsm].
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeFramedWithPsm(
        endpointId: String,
        endpointInfo: EndpointInfo,
        psm: Int,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray =
        encodeFramedWithPsm(
            endpointId = asciiEndpointId(endpointId),
            endpointInfo = endpointInfo,
            psm = psm,
            version = version,
            pcp = pcp,
            secondProfile = secondProfile,
        )

    /**
     * String overload of [encodeFramed].
     */
    @JvmOverloads
    @JvmStatic
    public fun encodeFramed(
        endpointId: String,
        endpointInfo: EndpointInfo,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
        secondProfile: Boolean = false,
    ): ByteArray =
        encodeFramed(
            endpointId = asciiEndpointId(endpointId),
            endpointInfo = endpointInfo,
            version = version,
            pcp = pcp,
            secondProfile = secondProfile,
        )

    /**
     * Convenience wrapper that accepts the endpoint_id as an ASCII
     * [String]. The string must be exactly [ENDPOINT_ID_LEN] code units
     * long and contain only ASCII bytes — all stock peers do.
     */
    @JvmOverloads
    @JvmStatic
    public fun encode(
        endpointId: String,
        endpointInfo: EndpointInfo,
        version: Int = DEFAULT_VERSION,
        pcp: Int = DEFAULT_PCP,
    ): ByteArray = encode(asciiEndpointId(endpointId), endpointInfo, version, pcp)

    /**
     * Parses a fast-advertisement service-data payload back into its
     * components. Returns `null` for any malformed input — truncated
     * header, length-prefix exceeds the buffer, or the embedded
     * EndpointInfo cannot be decoded.
     *
     * The parser is forgiving: a non-ASCII endpoint_id slug or an
     * unknown version / PCP value is preserved verbatim rather than
     * rejected, since other peers in the wild may legitimately experiment
     * with new values. The caller can always sanity-check the parsed
     * fields.
     */
    @Suppress("ReturnCount")
    @JvmStatic
    public fun parse(bytes: ByteArray): Parsed? {
        unwrapFrame(bytes)?.let { framed ->
            return parseFastBody(framed) ?: parseRegularBody(framed)
        }
        return parseFastBody(bytes) ?: parseRegularBody(bytes)
    }

    /**
     * Returns the BLE L2CAP PSM from a framed fast-advertisement extra-field
     * trailer, or `null` when the payload is legacy, malformed, or has no PSM.
     */
    @Suppress("ReturnCount")
    @JvmStatic
    public fun parsePsmExtraField(bytes: ByteArray): Int? {
        val offset = extraFieldsOffset(bytes) ?: return null
        if (offset >= bytes.size) return null
        val mask = bytes[offset].toInt() and UNSIGNED_BYTE_MASK
        if ((mask and EXTRA_FIELD_PSM_MASK) == 0) return null
        val psmOffset = offset + EXTRA_FIELDS_MASK_LEN
        if (psmOffset + PSM_LEN > bytes.size) return null
        return ((bytes[psmOffset].toInt() and UNSIGNED_BYTE_MASK) shl Byte.SIZE_BITS) or
            (bytes[psmOffset + 1].toInt() and UNSIGNED_BYTE_MASK)
    }

    @Suppress("ReturnCount")
    private fun parseFastBody(bytes: ByteArray): Parsed? {
        if (bytes.size < FIXED_HEADER_LEN) return null
        val header = bytes[0].toInt() and UNSIGNED_BYTE_MASK
        val version = (header ushr VERSION_SHIFT) and VERSION_MASK
        val pcp = header and PCP_MASK
        val endpointId = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + ENDPOINT_ID_LEN)
        val len = bytes[HEADER_LEN + ENDPOINT_ID_LEN].toInt() and UNSIGNED_BYTE_MASK
        if (FIXED_HEADER_LEN + len > bytes.size) return null
        val infoBytes = bytes.copyOfRange(FIXED_HEADER_LEN, FIXED_HEADER_LEN + len)
        val info = EndpointInfo.parse(infoBytes) ?: return null
        return Parsed(
            version = version,
            pcp = pcp,
            endpointId = endpointId,
            endpointInfo = info,
        )
    }

    @Suppress("ReturnCount")
    private fun parseRegularBody(bytes: ByteArray): Parsed? {
        if (bytes.size < REGULAR_FIXED_HEADER_LEN + BLUETOOTH_MAC_LEN) return null
        val header = bytes[0].toInt() and UNSIGNED_BYTE_MASK
        val version = (header ushr VERSION_SHIFT) and VERSION_MASK
        val pcp = header and PCP_MASK

        var offset = HEADER_LEN
        val serviceIdHash = bytes.copyOfRange(offset, offset + SERVICE_ID_HASH_LEN)
        offset += SERVICE_ID_HASH_LEN
        if (!serviceIdHash.contentEquals(NearbyServiceId.hashPrefix)) return null

        val endpointId = bytes.copyOfRange(offset, offset + ENDPOINT_ID_LEN)
        offset += ENDPOINT_ID_LEN
        val len = bytes[offset++].toInt() and UNSIGNED_BYTE_MASK
        if (offset + len + BLUETOOTH_MAC_LEN > bytes.size) return null
        val infoBytes = bytes.copyOfRange(offset, offset + len)
        val info = EndpointInfo.parse(infoBytes) ?: return null
        return Parsed(
            version = version,
            pcp = pcp,
            endpointId = endpointId,
            endpointInfo = info,
        )
    }

    @Suppress("ReturnCount")
    private fun unwrapFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < FRAME_HEADER_LEN) return null
        if (!isFastFrameType(bytes[0].toInt() and UNSIGNED_BYTE_MASK)) return null
        val len = bytes[1].toInt() and UNSIGNED_BYTE_MASK
        if (FRAME_HEADER_LEN + len > bytes.size) return null
        return bytes.copyOfRange(FRAME_HEADER_LEN, FRAME_HEADER_LEN + len)
    }

    @Suppress("ReturnCount")
    private fun extraFieldsOffset(bytes: ByteArray): Int? {
        if (bytes.size < FRAME_HEADER_LEN) return null
        if (!isFastFrameType(bytes[0].toInt() and UNSIGNED_BYTE_MASK)) return null
        val len = bytes[1].toInt() and UNSIGNED_BYTE_MASK
        val offset = FRAME_HEADER_LEN + len + DEVICE_TOKEN_LEN
        if (offset > bytes.size) return null
        return offset
    }

    private fun isFastFrameType(value: Int): Boolean =
        value == FRAME_TYPE_FAST_ADVERTISEMENT ||
            value == FRAME_TYPE_SECOND_PROFILE_FAST_ADVERTISEMENT

    private fun asciiEndpointId(endpointId: String): ByteArray {
        // String.toByteArray(US_ASCII) silently substitutes '?' for any
        // non-ASCII code point, so length-equality alone misses the case
        // where every non-ASCII char encodes to a single byte. Walk the
        // string explicitly and reject any code unit outside ASCII range.
        for (ch in endpointId) {
            require(ch.code in 0..ASCII_MAX) {
                "endpointId must be ASCII (got non-ASCII char in '$endpointId')"
            }
        }
        return endpointId.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * Pack the version (top 3 bits) and PCP (bottom 5 bits) into byte 0.
     * `version=1, pcp=3` yields `0x23`, matching the captured Galaxy
     * fingerprint.
     */
    internal fun packVersionPcp(
        version: Int,
        pcp: Int,
    ): Byte = (((version and VERSION_MASK) shl VERSION_SHIFT) or (pcp and PCP_MASK)).toByte()

    private fun deriveDeviceToken(body: ByteArray): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(body)
            .copyOfRange(0, DEVICE_TOKEN_LEN)

    /**
     * Validate that [bytes] is exactly [ENDPOINT_ID_LEN] long. We do not
     * enforce ASCII printable here — Samsung's stock builds emit
     * base64-url alphabet by convention but the wire format itself is
     * 4 raw bytes.
     */
    private fun validateEndpointIdBytes(bytes: ByteArray) {
        require(bytes.size == ENDPOINT_ID_LEN) {
            "endpointId must be exactly $ENDPOINT_ID_LEN bytes, got ${bytes.size}"
        }
    }

    /**
     * Parsed view of a fast-advertisement service-data payload. Returned
     * by [parse]; round-trips back through [encode] with the same
     * arguments.
     */
    public data class Parsed(
        public val version: Int,
        public val pcp: Int,
        public val endpointId: ByteArray,
        public val endpointInfo: EndpointInfo,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return version == other.version &&
                pcp == other.pcp &&
                endpointId.contentEquals(other.endpointId) &&
                endpointInfo == other.endpointInfo
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + pcp
            result = 31 * result + endpointId.contentHashCode()
            result = 31 * result + endpointInfo.hashCode()
            return result
        }
    }
}
