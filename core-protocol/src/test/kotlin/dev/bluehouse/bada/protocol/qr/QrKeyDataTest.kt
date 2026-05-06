/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.qr

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * Tests for [QrKeyData]'s wire layout, parser, and EC-coordinate
 * extraction.
 *
 * Three things are pinned here:
 *
 *  1. Encode/parse round-trip: the 35-byte layout matches PROTOCOL.md and
 *     can be re-parsed back to the original instance.
 *  2. Leading-zero handling on the X coordinate: NearDrop calls out that
 *     `BigInteger.toByteArray()` may prepend a 0x00 byte when the high bit
 *     is set; we strip it. We also left-pad short coordinates.
 *  3. [QrKeyData.generate] returns a real ECDSA P-256 keypair whose public
 *     key X coordinate matches the QR payload.
 */
class QrKeyDataTest {
    @Test
    fun `encode produces 35 bytes with version prefix and X coordinate`() {
        val x = ByteArray(QrKeyData.X_COORDINATE_LEN) { (it + 1).toByte() }
        val keyData = QrKeyData(versionByte = 0x02, xCoordinate = x)
        val encoded = keyData.encode()

        assertThat(encoded.size).isEqualTo(QrKeyData.TOTAL_LEN)
        // Version prefix: 00 00 02
        assertThat(encoded[0].toInt() and 0xFF).isEqualTo(0x00)
        assertThat(encoded[1].toInt() and 0xFF).isEqualTo(0x00)
        assertThat(encoded[2].toInt() and 0xFF).isEqualTo(0x02)
        // X coordinate immediately after.
        assertThat(encoded.copyOfRange(3, 35)).isEqualTo(x)
    }

    @Test
    fun `parse round-trips encoded bytes for both documented version bytes`() {
        for (versionByte in listOf(0x02, 0x03)) {
            val x = ByteArray(QrKeyData.X_COORDINATE_LEN) { (versionByte * it).toByte() }
            val original = QrKeyData(versionByte = versionByte, xCoordinate = x)
            val parsed = QrKeyData.parse(original.encode())
            assertThat(parsed).isEqualTo(original)
        }
    }

    @Test
    fun `parse returns null for wrong total length`() {
        assertThat(QrKeyData.parse(ByteArray(0))).isNull()
        assertThat(QrKeyData.parse(ByteArray(QrKeyData.TOTAL_LEN - 1))).isNull()
        assertThat(QrKeyData.parse(ByteArray(QrKeyData.TOTAL_LEN + 1))).isNull()
    }

    @Test
    fun `parse rejects non-zero leading version bytes`() {
        // Documented format requires the first two version bytes to be zero.
        // A peer that diverges is not interop-compatible; refuse rather than
        // silently accept.
        val bytes = ByteArray(QrKeyData.TOTAL_LEN)
        bytes[0] = 0x01
        bytes[2] = 0x02
        assertThat(QrKeyData.parse(bytes)).isNull()

        bytes[0] = 0x00
        bytes[1] = 0x99.toByte()
        assertThat(QrKeyData.parse(bytes)).isNull()
    }

    @Test
    fun `equals and hashCode compare X coordinate by content`() {
        val a = QrKeyData(0x02, ByteArray(QrKeyData.X_COORDINATE_LEN) { 0x42 })
        val b = QrKeyData(0x02, ByteArray(QrKeyData.X_COORDINATE_LEN) { 0x42 })
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `constructor rejects X coordinate of wrong length`() {
        assertThrows<IllegalArgumentException> {
            QrKeyData(0x02, ByteArray(QrKeyData.X_COORDINATE_LEN - 1))
        }
        assertThrows<IllegalArgumentException> {
            QrKeyData(0x02, ByteArray(QrKeyData.X_COORDINATE_LEN + 1))
        }
    }

    @Test
    fun `constructor rejects version byte outside 0 to 255`() {
        assertThrows<IllegalArgumentException> {
            QrKeyData(-1, ByteArray(QrKeyData.X_COORDINATE_LEN))
        }
        assertThrows<IllegalArgumentException> {
            QrKeyData(0x100, ByteArray(QrKeyData.X_COORDINATE_LEN))
        }
    }

    @Test
    fun `toFixedWidthUnsigned strips the BigInteger sign byte`() {
        // A 256-bit value with the high bit set serializes via
        // BigInteger.toByteArray() to 33 bytes with a leading 0x00.
        val big = BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        // Sanity: BigInteger(1, ...) creates a positive number, but its
        // toByteArray() will still prepend the sign byte because the high
        // bit of the underlying value is set.
        assertThat(big.toByteArray().size).isEqualTo(33)

        val out = QrKeyData.toFixedWidthUnsigned(big, 32)
        assertThat(out.size).isEqualTo(32)
        assertThat(out).isEqualTo(ByteArray(32) { 0xFF.toByte() })
    }

    @Test
    fun `toFixedWidthUnsigned left-pads short coordinates`() {
        // BigInteger.valueOf(1).toByteArray() == [0x01], which is 1 byte.
        // We must left-pad to the requested width.
        val out = QrKeyData.toFixedWidthUnsigned(BigInteger.ONE, 32)
        assertThat(out.size).isEqualTo(32)
        val expected =
            ByteArray(32).also { arr -> arr[31] = 0x01 }
        assertThat(out).isEqualTo(expected)
    }

    @Test
    fun `toFixedWidthUnsigned passes through exactly-width inputs`() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val big = BigInteger(1, raw)
        val out = QrKeyData.toFixedWidthUnsigned(big, 32)
        assertThat(out).isEqualTo(raw)
    }

    @Test
    fun `toFixedWidthUnsigned rejects negative values`() {
        assertThrows<IllegalArgumentException> {
            QrKeyData.toFixedWidthUnsigned(BigInteger.valueOf(-1), 32)
        }
    }

    @Test
    fun `toFixedWidthUnsigned rejects oversize coordinates`() {
        // 33-byte value with the top byte non-zero would not be a valid
        // 32-byte unsigned coordinate; reject loudly.
        val tooBig = BigInteger(1, ByteArray(33) { 0x42 })
        assertThrows<IllegalArgumentException> {
            QrKeyData.toFixedWidthUnsigned(tooBig, 32)
        }
    }

    @Test
    fun `generate returns a P-256 keypair whose X coordinate matches the QR payload`() {
        val generated = QrKeyData.generate()
        val publicKey = generated.keyPair.public as ECPublicKey

        // Curve sanity: P-256's field is 256 bits = 32 bytes. The X
        // coordinate in the QR payload must equal the curve point's X.
        val expectedX = QrKeyData.toFixedWidthUnsigned(publicKey.w.affineX, 32)
        assertThat(generated.qrKeyData.xCoordinate).isEqualTo(expectedX)
        assertThat(generated.qrKeyData.versionByte).isEqualTo(QrKeyData.DEFAULT_VERSION_BYTE)
        assertThat(generated.qrKeyData.encode().size).isEqualTo(QrKeyData.TOTAL_LEN)
    }

    @Test
    fun `generate honors the requested version byte`() {
        val generated = QrKeyData.generate(versionByte = 0x03)
        assertThat(generated.qrKeyData.versionByte).isEqualTo(0x03)
        // The encoded byte at offset 2 should reflect the override.
        assertThat(generated.qrKeyData.encode()[2].toInt() and 0xFF).isEqualTo(0x03)
    }

    @Test
    fun `generate produces independent keypairs across calls`() {
        val a = QrKeyData.generate()
        val b = QrKeyData.generate()
        // Two fresh P-256 keypairs colliding by accident would be a
        // catastrophic RNG failure, so this is effectively a "is the RNG
        // wired up at all?" sanity check.
        assertThat(a.qrKeyData).isNotEqualTo(b.qrKeyData)
    }
}
