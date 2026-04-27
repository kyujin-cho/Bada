/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.qr

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The 35-byte payload embedded in a Quick Share QR-code URL.
 *
 * Wire layout (per the NearDrop PROTOCOL.md "QR codes" section):
 *
 * ```
 * +----------------------------+----------------------------+
 * |       bytes 0..2           |       bytes 3..34          |
 * |----------------------------|----------------------------|
 * |  version prefix (3 bytes)  |  ECDSA P-256 X coordinate  |
 * |  00 00 02  or  00 00 03    |       (32 bytes)           |
 * +----------------------------+----------------------------+
 * ```
 *
 * - The 3-byte version prefix is `00 00 02` for the original QR code variant
 *   shipped by Quick Share and `00 00 03` for the newer revision. Both are
 *   accepted by parsers and pass through round-trips verbatim. The third byte
 *   is the only one that varies in practice; the first two bytes are always
 *   zero on the wire today.
 * - The 32-byte tail is the **X coordinate** of an ECDSA P-256 public key (the
 *   fully-specified curve `secp256r1`). Only the X coordinate is transmitted;
 *   the Y coordinate's sign byte is handled separately by the protocol layer
 *   that signs the receiver→sender frames and is therefore intentionally NOT
 *   part of the QR payload.
 *
 * **Leading-zero pitfall.** ECDSA point coordinates are unsigned 256-bit
 * integers, but Java represents them as signed `BigInteger`s. When the X
 * coordinate's high bit is 1, `BigInteger.toByteArray()` returns 33 bytes
 * with a leading `0x00` sign byte; when X happens to fit in fewer than 256
 * bits, `toByteArray()` returns a short array. Both edge cases must be
 * normalized to exactly 32 bytes before the QR payload is built — NearDrop
 * calls this out explicitly: *"Sometimes there will be a leading zero byte.
 * Strip that, Android really hates it."*
 *
 * The data class itself is immutable and value-equal: two instances with the
 * same version byte and X coordinate compare equal regardless of array
 * identity, which makes round-trip and KAT tests trivial.
 */
public data class QrKeyData(
    /** Third version byte (the first two are always zero). `2` or `3` in the wild. */
    val versionByte: Int,
    /** Exactly 32 bytes of ECDSA P-256 X coordinate, big-endian, unsigned. */
    val xCoordinate: ByteArray,
) {
    init {
        require(versionByte in 0..MAX_BYTE_VALUE) {
            "versionByte must fit in 1 byte (0..$MAX_BYTE_VALUE), got $versionByte"
        }
        require(xCoordinate.size == X_COORDINATE_LEN) {
            "xCoordinate must be exactly $X_COORDINATE_LEN bytes, got ${xCoordinate.size}"
        }
    }

    /**
     * Encodes this QR key data to its 35-byte wire form.
     *
     * The output is freshly allocated; mutating it never affects this
     * instance. Used directly as the HKDF input keying material for both
     * `advertisingToken` and `nameEncryptionKey`.
     */
    public fun encode(): ByteArray {
        val out = ByteArray(TOTAL_LEN)
        // First two bytes are always zero in the documented format. The third
        // byte carries the variant (2 or 3 in the wild today).
        out[2] = versionByte.toByte()
        xCoordinate.copyInto(out, destinationOffset = VERSION_PREFIX_LEN)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrKeyData) return false
        return versionByte == other.versionByte && xCoordinate.contentEquals(other.xCoordinate)
    }

    override fun hashCode(): Int = 31 * versionByte + xCoordinate.contentHashCode()

    public companion object {
        /** Total wire length: 3-byte version prefix + 32-byte X coordinate. */
        public const val TOTAL_LEN: Int = 35

        /** Length of the leading version prefix (`00 00 ??`). */
        public const val VERSION_PREFIX_LEN: Int = 3

        /** Length of the ECDSA P-256 X coordinate in bytes. */
        public const val X_COORDINATE_LEN: Int = 32

        /** Default version byte for newly generated QR payloads. */
        public const val DEFAULT_VERSION_BYTE: Int = 0x02

        /** Maximum value of a 1-byte unsigned field. */
        public const val MAX_BYTE_VALUE: Int = 0xFF

        /** Curve name passed to `KeyPairGenerator` to obtain P-256 keys. */
        private const val EC_CURVE_NAME: String = "secp256r1"

        /** JCA algorithm name for elliptic-curve keypair generation. */
        private const val EC_ALGORITHM: String = "EC"

        /**
         * Parses a 35-byte QR payload back into a [QrKeyData].
         *
         * Returns `null` for any malformed input (wrong length, non-zero
         * leading bytes). Like the rest of the protocol surface, this never
         * throws on bad peer data — it just signals "ignore this QR code".
         */
        @Suppress("ReturnCount")
        public fun parse(bytes: ByteArray): QrKeyData? {
            if (bytes.size != TOTAL_LEN) return null
            // The first two bytes of the version prefix are documented as
            // always zero. We refuse anything else rather than silently
            // accepting it, because a peer that diverges here is using a
            // protocol revision we have not been told about and our derived
            // keys would not interoperate anyway.
            if (bytes[0].toInt() != 0 || bytes[1].toInt() != 0) return null
            val versionByte = bytes[2].toInt() and MAX_BYTE_VALUE
            val x = bytes.copyOfRange(VERSION_PREFIX_LEN, TOTAL_LEN)
            return QrKeyData(versionByte = versionByte, xCoordinate = x)
        }

        /**
         * Generates a fresh ECDSA P-256 keypair and returns the QR payload
         * paired with the originating [KeyPair].
         *
         * The caller keeps the [KeyPair] for the **signing** path
         * (receiver→sender authenticated frames) while only the X coordinate
         * is published in the QR code. Splitting the return value this way
         * keeps the public API obvious about which half is sensitive: the
         * private key never leaves the sender device, the QR payload is fine
         * to embed in a 2D barcode and broadcast to anyone with a camera.
         *
         * @param random Secure RNG to use. Defaults to a platform-default
         *   `SecureRandom`, which on Android maps to the kernel CSPRNG.
         * @param versionByte Third version byte. Defaults to
         *   [DEFAULT_VERSION_BYTE]; pass `0x03` to opt into the newer variant
         *   when interoperating with peers that require it.
         */
        public fun generate(
            random: SecureRandom = SecureRandom(),
            versionByte: Int = DEFAULT_VERSION_BYTE,
        ): GeneratedQrKeyData {
            val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
            generator.initialize(ECGenParameterSpec(EC_CURVE_NAME), random)
            val keyPair = generator.generateKeyPair()
            val x = extractXCoordinate(keyPair.public as ECPublicKey)
            return GeneratedQrKeyData(
                keyPair = keyPair,
                qrKeyData = QrKeyData(versionByte = versionByte, xCoordinate = x),
            )
        }

        /**
         * Extracts the X coordinate of an ECDSA P-256 public key as a
         * fixed-width 32-byte big-endian unsigned integer.
         *
         * Handles the two `BigInteger.toByteArray()` edge cases:
         *
         *  1. **High-bit set** → `toByteArray()` prepends a `0x00` sign byte,
         *     producing a 33-byte array. Strip it.
         *  2. **Coordinate fits in fewer than 256 bits** → `toByteArray()`
         *     returns fewer than 32 bytes. Left-pad with zeros.
         *
         * Both cases occur with non-negligible probability over the curve and
         * must be handled correctly or interop breaks (NearDrop calls this
         * out: *"Sometimes there will be a leading zero byte. Strip that,
         * Android really hates it"*).
         */
        internal fun extractXCoordinate(publicKey: ECPublicKey): ByteArray =
            toFixedWidthUnsigned(publicKey.w.affineX, X_COORDINATE_LEN)

        /**
         * Converts a non-negative `BigInteger` to a fixed-width big-endian
         * unsigned byte array.
         *
         * @throws IllegalArgumentException if [value] is negative or does not
         *   fit in [width] bytes after sign-byte stripping.
         */
        internal fun toFixedWidthUnsigned(
            value: BigInteger,
            width: Int,
        ): ByteArray {
            require(value.signum() >= 0) { "EC coordinate must be non-negative" }
            val raw = value.toByteArray()
            return when {
                // The common "high bit set, sign byte prepended" case: drop
                // the leading 0x00 produced by BigInteger.toByteArray().
                raw.size == width + 1 && raw[0].toInt() == 0 ->
                    raw.copyOfRange(1, raw.size)
                // Already the right width.
                raw.size == width -> raw
                // Coordinate value fits in fewer than `width` bytes; left-pad
                // with zeros to keep the on-the-wire layout fixed.
                raw.size < width -> {
                    val padded = ByteArray(width)
                    raw.copyInto(padded, destinationOffset = width - raw.size)
                    padded
                }
                else -> throw IllegalArgumentException(
                    "EC coordinate is ${raw.size} bytes, does not fit in $width bytes",
                )
            }
        }
    }
}

/**
 * Bundle returned by [QrKeyData.generate]: the originating ECDSA P-256
 * [keyPair] (kept private by the sender) and the [qrKeyData] payload that
 * goes into the QR code.
 */
public data class GeneratedQrKeyData(
    /** ECDSA P-256 keypair. The private key MUST NOT leave the sender. */
    val keyPair: KeyPair,
    /** Public 35-byte QR payload that is safe to embed in a 2D barcode. */
    val qrKeyData: QrKeyData,
)
