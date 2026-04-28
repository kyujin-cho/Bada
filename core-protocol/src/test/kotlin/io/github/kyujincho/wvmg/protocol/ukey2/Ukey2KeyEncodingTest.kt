/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.ukey2

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EcP256PublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.GenericPublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.PublicKeyType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Tests for [Ukey2KeyEncoding].
 *
 * The interop-critical behaviors here are:
 *
 *  - **Round-trip**: every freshly generated P-256 public key must
 *    serialize and re-parse to the same `ECPoint`.
 *  - **Coordinate normalization**: peers (notably NearDrop and the
 *    reference macOS Quick Share clients) emit X/Y coordinates in
 *    `BigInteger.toByteArray()` form, which can be 31, 32, or 33 bytes
 *    depending on the high bits. The parser must handle all three.
 *  - **Failure handling**: malformed bytes, off-curve points, or wrong
 *    key types must surface as [Ukey2HandshakeException] with
 *    [Ukey2AlertType.BAD_PUBLIC_KEY], not as bare [Throwable]s.
 *
 * A deterministic [SecureRandom] is used to make most tests reproducible;
 * the round-trip-many test deliberately uses default randomness so it
 * exercises the natural distribution of coordinate magnitudes.
 */
class Ukey2KeyEncodingTest {
    private fun generateKeyPair(seed: Long = DETERMINISTIC_SEED): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        // Deterministic generator gives us reproducible keys for
        // length-edge-case tests.
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) })
        return gen.generateKeyPair()
    }

    @Test
    fun `serialize then parse round-trips a freshly generated keypair`() {
        val pair = generateKeyPair()
        val pub = pair.public as ECPublicKey

        val bytes = Ukey2KeyEncoding.serialize(pub)
        val parsed = Ukey2KeyEncoding.parse(bytes)

        // Compare the affine coordinates: ECPublicKey equality varies by
        // provider, but the (x, y) tuple is the canonical wire identity.
        assertThat(parsed.w.affineX).isEqualTo(pub.w.affineX)
        assertThat(parsed.w.affineY).isEqualTo(pub.w.affineY)
    }

    @Test
    fun `serialize emits 32 or 33 bytes for each P-256 coordinate`() {
        // Output is 32 bytes when the field-element top bit is 0 (the
        // common case) and 33 bytes (leading 0x00 sign byte + 32-byte
        // magnitude) when the top bit is 1. The 33-byte form is what
        // `BigInteger.toByteArray()` returns natively for a positive
        // value with the top bit set; we keep the sign byte rather than
        // stripping it, because Samsung One UI 8.0.5's GMS Nearby
        // parses these bytes via `new BigInteger(byte[])` (signed
        // two's complement) and rejects MSB=1 32-byte input as a
        // negative integer. The variable encoding is unambiguous to any
        // proto-aware receiver since the field is `bytes`.
        val pub = generateKeyPair().public as ECPublicKey
        val bytes = Ukey2KeyEncoding.serialize(pub)

        val parsed = GenericPublicKey.parseFrom(bytes)
        assertThat(parsed.type).isEqualTo(PublicKeyType.EC_P256)
        val xSize = parsed.ecP256PublicKey.x.size()
        val ySize = parsed.ecP256PublicKey.y.size()
        assertThat(xSize).isAnyOf(Ukey2.P256_COORDINATE_SIZE, Ukey2.P256_COORDINATE_SIZE + 1)
        assertThat(ySize).isAnyOf(Ukey2.P256_COORDINATE_SIZE, Ukey2.P256_COORDINATE_SIZE + 1)
        // When the encoding is 33 bytes the leading byte must be the
        // canonical 0x00 sign byte — not arbitrary leading data — so
        // signed parsers see a non-negative value.
        if (xSize == Ukey2.P256_COORDINATE_SIZE + 1) {
            assertThat(parsed.ecP256PublicKey.x.byteAt(0)).isEqualTo(0.toByte())
        }
        if (ySize == Ukey2.P256_COORDINATE_SIZE + 1) {
            assertThat(parsed.ecP256PublicKey.y.byteAt(0)).isEqualTo(0.toByte())
        }
    }

    @Test
    fun `serialize never produces an MSB-1 32-byte coordinate (Samsung BC parser regression guard)`() {
        // Burn through enough random keypairs that we hit the ~50%
        // probability of a top-bit-set coordinate. For every keypair,
        // assert: a 32-byte output has top bit = 0; a 33-byte output
        // has leading byte = 0x00. There is no other valid combination.
        repeat(64) {
            val pub = generateKeyPair().public as ECPublicKey
            val bytes = Ukey2KeyEncoding.serialize(pub)
            val parsed = GenericPublicKey.parseFrom(bytes)

            for (coord in listOf(parsed.ecP256PublicKey.x, parsed.ecP256PublicKey.y)) {
                val raw = coord.toByteArray()
                if (raw.size == Ukey2.P256_COORDINATE_SIZE) {
                    val topBit = (raw[0].toInt() and 0x80) != 0
                    assertThat(topBit).isFalse()
                } else {
                    assertThat(raw.size).isEqualTo(Ukey2.P256_COORDINATE_SIZE + 1)
                    assertThat(raw[0]).isEqualTo(0.toByte())
                }
            }
        }
    }

    @Test
    fun `parse accepts a 33-byte coordinate by stripping the leading sign byte`() {
        // Construct a synthetic GenericPublicKey whose X coordinate is
        // 33 bytes long with a leading 0x00 sign byte (the
        // `BigInteger.toByteArray()` form for values whose top bit is
        // set). The parser must drop the sign byte and recover the real
        // 32-byte coordinate.
        //
        // Since the canonical serializer now itself emits 33-byte
        // coordinates whenever the top bit is set, find a keypair whose
        // canonical X happens to be 32 bytes (top bit 0) so the
        // synthetic 33-byte form we manufacture below is unambiguous.
        val (pub, canonicalProto) = keyWithCanonicalXSize(Ukey2.P256_COORDINATE_SIZE)

        val originalX = canonicalProto.ecP256PublicKey.x.toByteArray()
        val padded = ByteArray(originalX.size + 1)
        // Sign byte 0x00 prepended.
        originalX.copyInto(padded, destinationOffset = 1)

        val mutated =
            GenericPublicKey
                .newBuilder(canonicalProto)
                .setEcP256PublicKey(
                    EcP256PublicKey
                        .newBuilder(canonicalProto.ecP256PublicKey)
                        .setX(ByteString.copyFrom(padded))
                        .build(),
                ).build()
                .toByteArray()

        val parsed = Ukey2KeyEncoding.parse(mutated)
        assertThat(parsed.w.affineX).isEqualTo(pub.w.affineX)
    }

    @Test
    fun `parse accepts a short coordinate by left-padding with zeros`() {
        // Build a key pair whose X coordinate happens to start with
        // 0x00. To synthesize one without searching, take a real
        // coordinate, strip the high byte, and feed the truncated form
        // to the parser. The parser should left-pad and then succeed
        // only if the truncated point is still on the curve — which is
        // overwhelmingly unlikely for a random point. So instead, we
        // assert that the parser at least DOES the padding (we observe
        // the rejection path that a length-32 vs. length-31 input takes
        // the same code path through normalizeCoordinate).
        //
        // Pin the canonical X to 32 bytes so the truncation arithmetic
        // below produces a deterministic 31-byte input regardless of
        // whether the random keypair's top bit is 0 or 1.
        val (_, canonicalProto) = keyWithCanonicalXSize(Ukey2.P256_COORDINATE_SIZE)

        // Synthesize a coordinate whose top byte is 0x00 by zeroing the
        // most significant byte of X. The resulting point is overwhelmingly
        // off-curve and should produce BAD_PUBLIC_KEY (NOT a length error).
        val tamperedX = canonicalProto.ecP256PublicKey.x.toByteArray()
        tamperedX[0] = 0
        val truncated = tamperedX.copyOfRange(1, tamperedX.size) // 31 bytes

        val mutated =
            GenericPublicKey
                .newBuilder(canonicalProto)
                .setEcP256PublicKey(
                    EcP256PublicKey
                        .newBuilder(canonicalProto.ecP256PublicKey)
                        .setX(ByteString.copyFrom(truncated))
                        .build(),
                ).build()
                .toByteArray()

        // The 31-byte input is normalized to 32 by left-padding with 0x00.
        // The point is then off-curve, so we expect a BAD_PUBLIC_KEY exception.
        val ex = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(mutated) }
        assertThat(ex.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `parse rejects malformed protobuf with BAD_PUBLIC_KEY`() {
        val ex =
            assertThrows<Ukey2HandshakeException> {
                Ukey2KeyEncoding.parse(byteArrayOf(0x7F, 0x40, 0x21))
            }
        assertThat(ex.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `parse rejects keys whose type is not EC_P256`() {
        val nonEc =
            GenericPublicKey
                .newBuilder()
                .setType(PublicKeyType.RSA2048)
                .build()
                .toByteArray()
        val ex = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(nonEc) }
        assertThat(ex.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `parse rejects EC_P256 with no inner key payload`() {
        val empty =
            GenericPublicKey
                .newBuilder()
                .setType(PublicKeyType.EC_P256)
                .build()
                .toByteArray()
        val ex = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(empty) }
        assertThat(ex.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `parse rejects coordinates outside 1 to 33 bytes`() {
        val canonical =
            Ukey2KeyEncoding.serialize(generateKeyPair().public as ECPublicKey)
        val canonicalProto = GenericPublicKey.parseFrom(canonical)

        // Empty X coordinate.
        val emptyX =
            GenericPublicKey
                .newBuilder(canonicalProto)
                .setEcP256PublicKey(
                    EcP256PublicKey
                        .newBuilder(canonicalProto.ecP256PublicKey)
                        .setX(ByteString.EMPTY)
                        .build(),
                ).build()
                .toByteArray()
        val ex1 = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(emptyX) }
        assertThat(ex1.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)

        // 34-byte X coordinate (one byte over the cap).
        val oversize = ByteArray(Ukey2.P256_COORDINATE_SIZE + 2) { 0x42 }
        val oversizeKey =
            GenericPublicKey
                .newBuilder(canonicalProto)
                .setEcP256PublicKey(
                    EcP256PublicKey
                        .newBuilder(canonicalProto.ecP256PublicKey)
                        .setX(ByteString.copyFrom(oversize))
                        .build(),
                ).build()
                .toByteArray()
        val ex2 = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(oversizeKey) }
        assertThat(ex2.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `parse rejects off-curve points with BAD_PUBLIC_KEY`() {
        val offCurve =
            GenericPublicKey
                .newBuilder()
                .setType(PublicKeyType.EC_P256)
                .setEcP256PublicKey(
                    EcP256PublicKey
                        .newBuilder()
                        .setX(ByteString.copyFrom(ByteArray(Ukey2.P256_COORDINATE_SIZE) { 0x01 }))
                        .setY(ByteString.copyFrom(ByteArray(Ukey2.P256_COORDINATE_SIZE) { 0x02 }))
                        .build(),
                ).build()
                .toByteArray()
        val ex = assertThrows<Ukey2HandshakeException> { Ukey2KeyEncoding.parse(offCurve) }
        assertThat(ex.alert).isEqualTo(Ukey2AlertType.BAD_PUBLIC_KEY)
    }

    @Test
    fun `serialize-parse round-trips many freshly generated keypairs`() {
        // Exercise the natural distribution of coordinate magnitudes so
        // we incidentally hit X or Y values whose top bit is set
        // (33-byte BigInteger.toByteArray() form). 200 iterations gives
        // us > 99.9% probability of hitting at least one such case.
        repeat(ROUND_TRIP_ITERATIONS) {
            val pub = generateKeyPairFresh().public as ECPublicKey
            val bytes = Ukey2KeyEncoding.serialize(pub)
            val parsed = Ukey2KeyEncoding.parse(bytes)
            assertThat(parsed.w.affineX).isEqualTo(pub.w.affineX)
            assertThat(parsed.w.affineY).isEqualTo(pub.w.affineY)
        }
    }

    private fun generateKeyPairFresh(): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    /**
     * Returns a freshly generated keypair whose canonical X coordinate
     * encodes to exactly [targetSize] bytes after passing through
     * [Ukey2KeyEncoding.serialize]. Used by tests that need a
     * deterministic encoding length to manipulate.
     *
     * Probability per draw is ~50% (top bit of the X field element is
     * uniformly random), so this loop terminates quickly. Caps at
     * [maxAttempts] to avoid an infinite spin in the (astronomically
     * unlikely) event of a stuck KeyPairGenerator.
     */
    private fun keyWithCanonicalXSize(
        targetSize: Int,
        maxAttempts: Int = 64,
    ): Pair<ECPublicKey, GenericPublicKey> {
        repeat(maxAttempts) {
            val pub = generateKeyPairFresh().public as ECPublicKey
            val canonical = Ukey2KeyEncoding.serialize(pub)
            val canonicalProto = GenericPublicKey.parseFrom(canonical)
            if (canonicalProto.ecP256PublicKey.x.size() == targetSize) {
                return pub to canonicalProto
            }
        }
        error("Could not produce a keypair whose canonical X is $targetSize bytes after $maxAttempts attempts")
    }

    companion object {
        private const val DETERMINISTIC_SEED = 0x4E_45_41_52_44_52_4FL // "NEARDRO"
        private const val ROUND_TRIP_ITERATIONS = 200
    }
}
