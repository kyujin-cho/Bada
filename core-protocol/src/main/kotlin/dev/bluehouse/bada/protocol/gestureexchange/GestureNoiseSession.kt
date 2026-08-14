/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.gestureexchange

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exact `Noise_NN_secp256r1_AESGCM_SHA256` transport used by Google Tap to
 * Share. One instance owns one NFC session and one role. It exchanges only
 * ephemeral public keys; [channelBindingToken] is the final transcript hash.
 *
 * This is the user-facing “Tap to Share” bootstrap, not SuperDrop Name Card.
 * Name Card keeps its proprietary AID/BLE protocol unchanged.
 */
@Suppress("MagicNumber") // Exact Noise prefixes, HKDF counters, and SEC1 offsets are protocol constants.
public class GestureNoiseSession(
    public val role: Role,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    public enum class Role { INITIATOR, RESPONDER }

    private var transcriptHash: ByteArray = sha256(PROTOCOL_NAME.toByteArray(Charsets.UTF_8))
    private var chainingKey: ByteArray = transcriptHash.clone()
    private var localKeyPair: KeyPair? = null
    private var senderCipher: CounterCipher? = null
    private var receiverCipher: CounterCipher? = null

    public val isComplete: Boolean
        get() = senderCipher != null && receiverCipher != null

    /** Creates the initiator key request. May be called exactly once. */
    public fun createInitiatorKey(): ByteArray {
        check(role == Role.INITIATOR) { "Only the initiator creates the first key" }
        check(localKeyPair == null) { "Initiator key already created" }
        return generateLocalKey().also(::mixHash)
    }

    /** Consumes the initiator key and returns the responder key. */
    public fun answerInitiatorKey(remoteKey: ByteArray): ByteArray {
        check(role == Role.RESPONDER) { "Only the responder answers the first key" }
        check(localKeyPair == null) { "Responder key already created" }
        mixHash(remoteKey)
        val local = generateLocalKey()
        mixHash(local)
        finishHandshake(remoteKey)
        return local
    }

    /** Consumes the responder key and completes the initiator handshake. */
    public fun acceptResponderKey(remoteKey: ByteArray) {
        check(role == Role.INITIATOR) { "Only the initiator accepts the responder key" }
        check(localKeyPair != null) { "Initiator key has not been created" }
        check(!isComplete) { "Handshake already complete" }
        mixHash(remoteKey)
        finishHandshake(remoteKey)
    }

    public fun encrypt(plaintext: ByteArray): ByteArray = requireSender().encrypt(plaintext)

    public fun decrypt(ciphertext: ByteArray): ByteArray = requireReceiver().decrypt(ciphertext)

    public fun channelBindingToken(): ByteArray {
        check(isComplete) { "Handshake is not complete" }
        return transcriptHash.clone()
    }

    /** Drops all session-owned key/cipher references at NFC teardown. */
    public fun clear() {
        transcriptHash.fill(0)
        chainingKey.fill(0)
        localKeyPair = null
        senderCipher?.clear()
        receiverCipher?.clear()
        senderCipher = null
        receiverCipher = null
    }

    private fun generateLocalKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator
            .generateKeyPair()
            .also { localKeyPair = it }
            .public
            .toWireKey()
    }

    private fun finishHandshake(remoteKey: ByteArray) {
        val pair = checkNotNull(localKeyPair)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(parseWireKey(remoteKey), true)
        val mixed = hkdf2(chainingKey, agreement.generateSecret())
        chainingKey.fill(0)
        chainingKey = mixed.first
        val split = hkdf2(chainingKey, ByteArray(0))
        val first = CounterCipher(split.first)
        val second = CounterCipher(split.second)
        if (role == Role.INITIATOR) {
            senderCipher = first
            receiverCipher = second
        } else {
            receiverCipher = first
            senderCipher = second
        }
    }

    private fun mixHash(data: ByteArray) {
        transcriptHash = sha256(transcriptHash, data)
    }

    private fun requireSender(): CounterCipher = checkNotNull(senderCipher) { "Handshake is not complete" }

    private fun requireReceiver(): CounterCipher = checkNotNull(receiverCipher) { "Handshake is not complete" }

    private class CounterCipher(
        keyBytes: ByteArray,
    ) {
        private var key: ByteArray? = keyBytes.clone()
        private var counter: Long = 0

        @Synchronized
        fun encrypt(plaintext: ByteArray): ByteArray = crypt(Cipher.ENCRYPT_MODE, plaintext)

        @Synchronized
        fun decrypt(ciphertext: ByteArray): ByteArray = crypt(Cipher.DECRYPT_MODE, ciphertext)

        @Synchronized
        fun clear() {
            key?.fill(0)
            key = null
            counter = 0
        }

        private fun crypt(
            mode: Int,
            input: ByteArray,
        ): ByteArray {
            val material = checkNotNull(key) { "Cipher has been cleared" }
            check(counter != Long.MAX_VALUE) { "AES-GCM nonce counter exhausted" }
            val nonce =
                ByteBuffer
                    .allocate(NONCE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(4, counter)
                    .array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(material, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(ByteArray(0))
            counter++
            return cipher.doFinal(input)
        }
    }

    public companion object {
        public const val PROTOCOL_NAME: String = "Noise_NN_secp256r1_AESGCM_SHA256"
        private const val CURVE: String = "secp256r1"
        private const val WIRE_KEY_BYTES: Int = 65
        private const val COORDINATE_BYTES: Int = 32
        private const val NONCE_BYTES: Int = 12
        private const val GCM_TAG_BITS: Int = 128

        private fun hkdf2(
            chainKey: ByteArray,
            inputKey: ByteArray,
        ): Pair<ByteArray, ByteArray> {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(chainKey, "HmacSHA256"))
            val temporaryKey = extract.doFinal(inputKey)
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(temporaryKey, "HmacSHA256"))
            val first = expand.doFinal(byteArrayOf(1)).copyOf(COORDINATE_BYTES)
            expand.reset()
            expand.update(first)
            val second = expand.doFinal(byteArrayOf(2)).copyOf(COORDINATE_BYTES)
            temporaryKey.fill(0)
            return first to second
        }

        private fun sha256(vararg values: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                values.forEach(::update)
                digest()
            }

        private fun PublicKey.toWireKey(): ByteArray {
            val point = (this as ECPublicKey).w
            return byteArrayOf(4) + point.affineX.fixed32() + point.affineY.fixed32()
        }

        private fun BigInteger.fixed32(): ByteArray {
            val raw = toByteArray()
            val unsigned =
                if (raw.size == COORDINATE_BYTES + 1 &&
                    raw[0] == 0.toByte()
                ) {
                    raw.copyOfRange(1, raw.size)
                } else {
                    raw
                }
            require(unsigned.size <= COORDINATE_BYTES) { "P-256 coordinate exceeds 32 bytes" }
            return ByteArray(COORDINATE_BYTES - unsigned.size) + unsigned
        }

        private fun parseWireKey(bytes: ByteArray): PublicKey {
            require(bytes.size == WIRE_KEY_BYTES && bytes[0] == 4.toByte()) {
                "P-256 key must be 65-byte uncompressed SEC1"
            }
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }
            val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
            val point =
                ECPoint(
                    BigInteger(1, bytes.copyOfRange(1, 33)),
                    BigInteger(1, bytes.copyOfRange(33, 65)),
                )
            require(isOnCurve(point, spec)) { "P-256 public key is not on the curve" }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))
        }

        @Suppress("ReturnCount", "ComplexCondition") // Guarded P-256 validation is clearest as early rejects.
        private fun isOnCurve(
            point: ECPoint,
            spec: ECParameterSpec,
        ): Boolean {
            val field = spec.curve.field as? java.security.spec.ECFieldFp ?: return false
            val p = field.p
            val x = point.affineX
            val y = point.affineY
            if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
            val left = y.modPow(BigInteger.TWO, p)
            val right =
                x
                    .modPow(BigInteger.valueOf(3), p)
                    .add(spec.curve.a.multiply(x))
                    .add(spec.curve.b)
                    .mod(p)
            return left == right
        }
    }
}
