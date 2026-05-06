/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.ukey2

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import javax.crypto.KeyAgreement

/**
 * Cryptographic primitives for the UKEY2 P256_SHA512 handshake.
 *
 * This object owns three pieces of crypto:
 *
 *  1. **P-256 keypair generation** via JCE `KeyPairGenerator("EC")` with
 *     an `ECGenParameterSpec("secp256r1")`. Identical to NearDrop's path
 *     and to what every Quick Share peer in the wild produces.
 *
 *  2. **ECDH shared secret derivation**, then SHA-256 of the X-magnitude
 *     of the shared point. NearDrop derives `dhs` as
 *     `SHA256(sharedPoint.x.asMagnitudeBytes())` — i.e., the affine X
 *     coordinate stripped of any leading zero bytes (the "magnitude"
 *     form). This implementation reproduces that bit-for-bit:
 *     `KeyAgreement("ECDH").generateSecret()` already returns the
 *     fixed-width 32-byte X coordinate of the shared point on every
 *     mainstream JCE provider; we then strip the leading zero bytes via
 *     `BigInteger(1, ...)` plus `toByteArray()` and a sign-byte trim.
 *
 *  3. **SHA-512** over the raw `Ukey2Message` wrapping `Ukey2ClientFinished`,
 *     used both to compute the cipher commitment on the client side and
 *     to verify it on the server side.
 *
 * Everything here is `internal`; callers reach this code through
 * `Ukey2Client` / `Ukey2Server`, not directly.
 */
internal object Ukey2Crypto {
    private const val EC_KEY_ALGORITHM = "EC"
    private const val ECDH_ALGORITHM = "ECDH"
    private const val SHA256_ALGORITHM = "SHA-256"
    private const val SHA512_ALGORITHM = "SHA-512"

    /**
     * Cached parameter spec for `secp256r1`. Resolved once via
     * `AlgorithmParameters` so [Ukey2KeyEncoding] can build
     * `ECPublicKeySpec` instances without spinning up a fresh
     * `KeyPairGenerator` for every parse call.
     */
    private val p256Params: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance(EC_KEY_ALGORITHM)
        params.init(ECGenParameterSpec(Ukey2.P256_CURVE_NAME))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Generates a fresh ephemeral P-256 keypair for one UKEY2 handshake.
     *
     * Uses the JVM's default `SecureRandom` source by default. Tests pass
     * a deterministic [SecureRandom] to make handshake fixtures
     * reproducible; production code should always use the no-argument
     * default.
     */
    fun generateP256KeyPair(secureRandom: SecureRandom = SecureRandom()): KeyPair {
        val generator = KeyPairGenerator.getInstance(EC_KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(Ukey2.P256_CURVE_NAME), secureRandom)
        return generator.generateKeyPair()
    }

    /**
     * Computes `dhs = SHA-256(ECDH(peer_pub, our_priv).x_magnitude)`.
     *
     * The ECDH stage runs through the JCE `KeyAgreement` API, which
     * returns the X coordinate of the shared point as a fixed-width
     * unsigned big-endian byte array (32 bytes for P-256 across SunEC,
     * Conscrypt, and Bouncy Castle). We then take the **magnitude
     * representation** — the unsigned big-endian encoding with leading
     * zero bytes removed — and SHA-256-hash that. This matches NearDrop's
     * `finalizeKeyExchange()` implementation, which uses BigEC's
     * `asMagnitudeBytes()` for the same purpose.
     *
     * Why magnitude rather than the raw 32-byte X coordinate? Because
     * NearDrop and other Swift/iOS Quick Share implementations went down
     * the magnitude path historically, and a single-byte difference in
     * the SHA-256 input changes the entire downstream HKDF chain.
     * Bit-perfect interoperability is the only acceptable outcome here.
     */
    fun computeDhs(
        ourPrivateKey: ECPrivateKey,
        peerPublicKey: ECPublicKey,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        agreement.init(ourPrivateKey)
        // Second arg is `lastPhase`; ECDH always finishes after one phase.
        agreement.doPhase(peerPublicKey, true)
        val sharedX = agreement.generateSecret()

        val magnitude = toMagnitude(sharedX)
        return MessageDigest.getInstance(SHA256_ALGORITHM).digest(magnitude)
    }

    /** Computes SHA-512 over [data]. Used for the UKEY2 cipher commitment. */
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance(SHA512_ALGORITHM).digest(data)

    /**
     * Returns the cached P-256 (`secp256r1`) parameter spec. Used by the
     * key-encoding parser to construct an [java.security.spec.ECPublicKeySpec]
     * that the JCE can validate against the curve.
     */
    fun p256Parameters(): ECParameterSpec = p256Params

    /**
     * Strips leading zero bytes from [bytes], returning the magnitude
     * representation expected by NearDrop's `asMagnitudeBytes()`.
     *
     * If [bytes] is all zeros, returns a single zero byte (matching
     * `BigInteger.ZERO.toByteArray()`). In a real P-256 ECDH this case
     * cannot occur — the shared secret is uniformly random over a 256-bit
     * range — but defensive callers should not be tripped up by an
     * unexpectedly all-zero input.
     */
    private fun toMagnitude(bytes: ByteArray): ByteArray {
        // BigInteger(1, bytes) interprets the input as non-negative
        // unsigned big-endian; toByteArray() then returns the canonical
        // signed two's complement form, which equals the magnitude bytes
        // either as-is or with a leading 0x00 sign byte.
        val signed = BigInteger(1, bytes).toByteArray()
        return if (signed.size > 1 && signed[0] == 0.toByte()) {
            signed.copyOfRange(1, signed.size)
        } else {
            signed
        }
    }
}

/**
 * Convenience accessor used by [Ukey2KeyEncoding] to obtain the cached
 * P-256 parameter spec. Lives at the top level so the encoding object's
 * call site stays short.
 */
internal fun p256Parameters(): ECParameterSpec = Ukey2Crypto.p256Parameters()
