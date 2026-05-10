/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.ukey2

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Tests for [Ukey2Crypto] — specifically the bit-perfect derivation of
 * `dhs = SHA-256(ECDH(peer_pub, our_priv).x_magnitude)`.
 *
 * The two non-trivial properties to lock down:
 *
 *  1. **Both sides agree.** Running `computeDhs` on Alice's private key
 *     against Bob's public key, and on Bob's private key against
 *     Alice's public key, must produce the same 32-byte hash.
 *
 *  2. **The hash input is the magnitude form, not the fixed-width X.**
 *     If the X coordinate happens to start with `0x00` (~0.4% of the
 *     time on P-256), the magnitude form is shorter and SHA-256 of it
 *     differs from SHA-256 of the padded form. This test compares the
 *     output of `computeDhs` against an independent reference computation
 *     that uses `BigInteger` magnitude semantics directly.
 */
class Ukey2CryptoTest {
    @Test
    fun `computeDhs is symmetric across the two parties`() {
        val alice = generateKeyPair()
        val bob = generateKeyPair()

        val aliceView = Ukey2Crypto.computeDhs(alice.private as ECPrivateKey, bob.public as ECPublicKey)
        val bobView = Ukey2Crypto.computeDhs(bob.private as ECPrivateKey, alice.public as ECPublicKey)

        assertThat(aliceView).isEqualTo(bobView)
        assertThat(aliceView).hasLength(SHA256_LEN)
    }

    @Test
    fun `computeDhs hashes the X-magnitude not the fixed-width X`() {
        // Reference implementation: run JCE ECDH ourselves to obtain the
        // raw 32-byte X coordinate, then strip leading zero bytes via
        // BigInteger semantics, then SHA-256 that. Compare against
        // [Ukey2Crypto.computeDhs].
        repeat(SAMPLE_ITERATIONS) {
            val alice = generateKeyPair()
            val bob = generateKeyPair()

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(alice.private)
            agreement.doPhase(bob.public, true)
            val sharedX = agreement.generateSecret()

            val signed = BigInteger(1, sharedX).toByteArray()
            val magnitude =
                if (signed.size > 1 && signed[0] == 0.toByte()) {
                    signed.copyOfRange(1, signed.size)
                } else {
                    signed
                }
            val expected = MessageDigest.getInstance("SHA-256").digest(magnitude)

            val actual = Ukey2Crypto.computeDhs(alice.private as ECPrivateKey, bob.public as ECPublicKey)
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun `sha512 produces a 64-byte digest matching MessageDigest`() {
        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val expected = MessageDigest.getInstance("SHA-512").digest(input)
        val actual = Ukey2Crypto.sha512(input)
        assertThat(actual).isEqualTo(expected)
        assertThat(actual).hasLength(SHA512_LEN)
    }

    @Test
    fun `generateP256KeyPair produces a P-256 keypair on every call`() {
        val pair = Ukey2Crypto.generateP256KeyPair(SecureRandom())
        assertThat(pair.public).isInstanceOf(ECPublicKey::class.java)
        assertThat(pair.private).isInstanceOf(ECPrivateKey::class.java)
        val params = (pair.public as ECPublicKey).params
        // Order of secp256r1 — this is the cheapest provider-agnostic
        // assertion that confirms we got the right curve.
        assertThat(params.order.bitLength()).isEqualTo(P256_ORDER_BIT_LEN)
    }

    private fun generateKeyPair(): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    companion object {
        private const val SHA256_LEN = 32
        private const val SHA512_LEN = 64
        private const val P256_ORDER_BIT_LEN = 256
        private const val SAMPLE_ITERATIONS = 50
    }
}
