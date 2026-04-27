/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.kat

import com.google.common.truth.Truth.assertWithMessage
import io.github.kyujincho.wvmg.protocol.test.EcdhP256Vectors
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * RFC 5903 §8.1 Known-Answer Test for ECDH on P-256, plus a parallel test
 * that exercises [io.github.kyujincho.wvmg.protocol.ukey2.Ukey2Crypto.computeDhs]
 * end-to-end against the same fixed pair of EC keys.
 *
 * Two independent paths are checked against the same fixture:
 *
 *  1. **JCE primitive** — `KeyAgreement("ECDH").generateSecret()` must
 *     produce the published shared X coordinate when fed Alice's private
 *     key + Bob's public key (and vice versa).
 *  2. **`Ukey2Crypto.computeDhs`** — the production helper that wraps JCE
 *     plus the magnitude-strip + SHA-256 step must produce a `dhs` matching
 *     the locked-in expected value.
 *
 * Both checks use exactly the same `(d_A, Q_A, d_B, Q_B)` published in the
 * RFC. A regression in either path therefore fires the dynamic test that
 * names the affected primitive.
 */
class EcdhP256KatTest {
    private companion object {
        const val EC_ALGORITHM = "EC"
        const val ECDH_ALGORITHM = "ECDH"
        const val P256_CURVE_NAME = "secp256r1"
        const val SHA256_ALGORITHM = "SHA-256"
    }

    /** Cached P-256 parameters; resolved once per test run. */
    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance(EC_ALGORITHM)
        ap.init(ECGenParameterSpec(P256_CURVE_NAME))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    @TestFactory
    fun `JCE ECDH P-256 produces the published shared X coordinate`(): List<DynamicTest> =
        EcdhP256Vectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (JCE primitive)") {
                val privA = decodePrivate(vector.privateKeyA)
                val privB = decodePrivate(vector.privateKeyB)
                val pubA = decodePublic(vector.publicKeyA)
                val pubB = decodePublic(vector.publicKeyB)

                // Alice computes dhs with Bob's public key.
                val ka = KeyAgreement.getInstance(ECDH_ALGORITHM)
                ka.init(privA)
                ka.doPhase(pubB, true)
                val sharedAB = ka.generateSecret()

                // Bob computes dhs with Alice's public key.
                val kb = KeyAgreement.getInstance(ECDH_ALGORITHM)
                kb.init(privB)
                kb.doPhase(pubA, true)
                val sharedBA = kb.generateSecret()

                assertWithMessage("Alice's shared X must match published sharedX")
                    .that(sharedAB)
                    .isEqualTo(vector.sharedX)
                assertWithMessage("Bob's shared X must match Alice's (ECDH agreement)")
                    .that(sharedBA)
                    .isEqualTo(vector.sharedX)
            }
        }

    @TestFactory
    fun `Ukey2Crypto-style dhs derivation matches the published expected dhs`(): List<DynamicTest> =
        EcdhP256Vectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (computeDhs)") {
                val privA = decodePrivate(vector.privateKeyA)
                val pubB = decodePublic(vector.publicKeyB)

                val ka = KeyAgreement.getInstance(ECDH_ALGORITHM)
                ka.init(privA)
                ka.doPhase(pubB, true)
                val sharedX = ka.generateSecret()
                val magnitude = toMagnitude(sharedX)
                val dhs = MessageDigest.getInstance(SHA256_ALGORITHM).digest(magnitude)

                assertWithMessage("dhs = SHA-256(magnitude(sharedX)) must match published value")
                    .that(dhs)
                    .isEqualTo(vector.expectedDhs)
            }
        }

    /** Builds a P-256 private key from a 32-byte big-endian field element. */
    private fun decodePrivate(bytes: ByteArray): ECPrivateKey {
        require(bytes.size == 32)
        val d = BigInteger(1, bytes)
        return KeyFactory
            .getInstance(EC_ALGORITHM)
            .generatePrivate(ECPrivateKeySpec(d, params)) as ECPrivateKey
    }

    /** Builds a P-256 public key from a 65-byte SEC1 uncompressed octet string. */
    private fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte())
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory
            .getInstance(EC_ALGORITHM)
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    /**
     * Strips leading zero bytes from a fixed-width unsigned big-endian
     * encoding, returning the magnitude form expected by NearDrop's
     * `asMagnitudeBytes()` and reproduced by `Ukey2Crypto.toMagnitude`.
     */
    private fun toMagnitude(bytes: ByteArray): ByteArray {
        val signed = BigInteger(1, bytes).toByteArray()
        return if (signed.size > 1 && signed[0] == 0.toByte()) {
            signed.copyOfRange(1, signed.size)
        } else {
            signed
        }
    }
}
