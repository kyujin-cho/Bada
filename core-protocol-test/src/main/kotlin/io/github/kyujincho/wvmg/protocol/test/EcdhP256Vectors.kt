/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.test

/**
 * RFC 5903 §8.1 Known-Answer Test (KAT) vector for ECDH on the NIST P-256
 * curve.
 *
 * The UKEY2 handshake (#10) negotiates an ephemeral P-256 key pair on each
 * side and computes the shared secret as
 * `dhs = SHA-256(magnitude(ECDH(peer_pub, our_priv).x))`. Bit-exact parity
 * with NearDrop's reference implementation is mandatory — a one-byte error
 * in the X-coordinate magnitude or in the leading-zero strip would produce
 * a `dhs` that no peer in the wild can reproduce, silently breaking every
 * downstream key derivation.
 *
 * RFC 5903 publishes a fixed `(d_A, d_B, Q_A, Q_B, sharedX)` tuple for
 * P-256 (`secp256r1`). Pinning the inputs and the expected shared-X
 * coordinate as KAT lets us:
 *
 *  1. Verify the JCE `KeyAgreement("ECDH")` primitive against an external,
 *     publicly-published vector.
 *  2. Verify that `Ukey2Crypto.computeDhs` strips leading zeros correctly
 *     (the magnitude form) and that the SHA-256 wrapping matches a
 *     pre-computed reference.
 *
 * Public-key encoding here is **uncompressed SEC1** (`0x04 || X || Y`), the
 * format JCE provides through `ECPublicKey`-via-`ECPoint` and that NearDrop
 * exchanges over the wire (the UKEY2 message wraps the same X/Y pair).
 *
 * Reference:
 * https://datatracker.ietf.org/doc/html/rfc5903#section-8.1
 */
public object EcdhP256Vectors {
    /**
     * One P-256 ECDH KAT vector. Plain class, not a `data class`, for the
     * same `ByteArray`-equality reason called out in [HkdfVectors].
     *
     * Both private keys are encoded as 32-byte big-endian integers (the
     * field-element form) and both public keys as 65-byte uncompressed SEC1
     * octet strings. This is the encoding `:core-protocol`'s
     * `Ukey2KeyEncoding` parses, so the vector can drive that surface
     * directly without a second translation step.
     *
     * @property name Human-readable label printed on test failure.
     * @property privateKeyA Alice's private key `d_A` (32 bytes).
     * @property publicKeyA Alice's public key `Q_A`, SEC1 uncompressed (65 bytes).
     * @property privateKeyB Bob's private key `d_B` (32 bytes).
     * @property publicKeyB Bob's public key `Q_B`, SEC1 uncompressed (65 bytes).
     * @property sharedX Expected shared point X coordinate (32 bytes,
     *   fixed-width). Equal on both sides — the proof that ECDH agrees.
     * @property expectedDhs Expected `SHA-256(magnitude(sharedX))`. For this
     *   particular vector `sharedX` has no leading-zero bytes, so the
     *   magnitude form equals the fixed-width X bytes; the dhs is therefore
     *   `SHA-256(sharedX)`. This independence is intentional — a leading-
     *   zero variant would couple the test to the strip step rather than
     *   exercise it.
     */
    public class Vector(
        public val name: String,
        public val privateKeyA: ByteArray,
        public val publicKeyA: ByteArray,
        public val privateKeyB: ByteArray,
        public val publicKeyB: ByteArray,
        public val sharedX: ByteArray,
        public val expectedDhs: ByteArray,
    ) {
        init {
            require(privateKeyA.size == 32) { "P-256 private key A must be 32 bytes" }
            require(publicKeyA.size == 65) { "P-256 public key A must be 65 bytes (SEC1 uncompressed)" }
            require(privateKeyB.size == 32) { "P-256 private key B must be 32 bytes" }
            require(publicKeyB.size == 65) { "P-256 public key B must be 65 bytes (SEC1 uncompressed)" }
            require(sharedX.size == 32) { "P-256 shared X must be 32 bytes" }
            require(expectedDhs.size == 32) { "dhs (SHA-256 output) must be 32 bytes" }
            require(publicKeyA[0] == 0x04.toByte()) { "Public key A must start with 0x04 (SEC1 uncompressed marker)" }
            require(publicKeyB[0] == 0x04.toByte()) { "Public key B must start with 0x04 (SEC1 uncompressed marker)" }
        }

        override fun toString(): String = name
    }

    /**
     * RFC 5903 §8.1 P-256 (`secp256r1`) test vector.
     *
     * The shared X coordinate is given by the RFC as
     * `D6840F6B42F6EDAFD13116E0E12565202FEF8E9ECE7DCE03812464D04B9442DE`,
     * matching the value JCE produces when running the `KeyAgreement` end
     * to end (verified independently by an offline harness using
     * `javax.crypto.KeyAgreement("ECDH")`). The expected dhs is
     * `SHA-256(sharedX)`, computed offline.
     */
    public val rfc5903: Vector =
        Vector(
            name = "RFC 5903 §8.1 — ECDH P-256",
            privateKeyA =
                hex(
                    "c88f01f510d9ac3f70a292daa2316de5" +
                        "44e9aab8afe84049c62a9c57862d1433",
                ),
            publicKeyA =
                hex(
                    "04" +
                        "dad0b65394221cf9b051e1feca5787d0" +
                        "98dfe637fc90b9ef945d0c3772581180" +
                        "5271a0461cdb8252d61f1c456fa3e59a" +
                        "b1f45b33accf5f58389e0577b8990bb3",
                ),
            privateKeyB =
                hex(
                    "c6ef9c5d78ae012a011164acb397ce20" +
                        "88685d8f06bf9be0b283ab46476bee53",
                ),
            publicKeyB =
                hex(
                    "04" +
                        "d12dfb5289c8d4f81208b70270398c34" +
                        "2296970a0bccb74c736fc7554494bf63" +
                        "56fbf3ca366cc23e8157854c13c58d6a" +
                        "ac23f046ada30f8353e74f33039872ab",
                ),
            sharedX =
                hex(
                    "d6840f6b42f6edafd13116e0e1256520" +
                        "2fef8e9ece7dce03812464d04b9442de",
                ),
            expectedDhs =
                hex(
                    "0519dc09b36efad1d00aef1d5b53b100" +
                        "202eb910b5de0dede75f190a357a367d",
                ),
        )

    /** All ECDH P-256 KAT vectors in declaration order. */
    public val all: List<Vector> = listOf(rfc5903)

    /**
     * Decodes a hex string to bytes. Same strict-decoding policy as the
     * companion fixtures: lowercase, even length, `[0-9a-f]` only.
     */
    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length: ${value.length}" }
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = decodeNibble(value[i * 2])
            val lo = decodeNibble(value[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun decodeNibble(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> throw IllegalArgumentException("Invalid hex character: '$c'")
        }
}
