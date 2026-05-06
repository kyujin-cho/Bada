/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.crypto.securemessage

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.security.cryptauth.lib.securegcm.DeviceToDeviceMessagesProto.DeviceToDeviceMessage
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.GcmMetadata
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.Type
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EncScheme
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.Header
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SigScheme
import dev.bluehouse.bada.protocol.test.SecureMessageVectors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for [SecureMessageCodec].
 *
 * Two layers of coverage:
 *
 *  1. **Primitive KAT** — fixed `(key, iv, plaintext)` AES-CBC and
 *     fixed `(key, data)` HMAC-SHA256 vectors from
 *     [SecureMessageVectors]. Independently computed; locks the
 *     implementation to the right JCE transforms.
 *  2. **End-to-end round-trip** — encrypts with a fixed IV and verifies
 *     that [SecureMessageCodec.verifyAndDecrypt] recovers the original
 *     payload, including the structural validation of the inner
 *     [Header] (correct schemes, correct IV, correct GcmMetadata).
 *  3. **Negative paths** — HMAC tampering, header tampering, body
 *     tampering, schema violations.
 */
class SecureMessageCodecTest {
    /**
     * Sanity: AES-256-CBC ciphertext for `(primaryKey, primaryIv, primaryPlaintext)`
     * must match the locked-in hex bytes in [SecureMessageVectors.aesPrimary].
     */
    @Test
    fun `AES-256-CBC primitive matches the primary KAT ciphertext`() {
        for (vector in SecureMessageVectors.aes) {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(vector.key, "AES"),
                IvParameterSpec(vector.iv),
            )
            val actual = cipher.doFinal(vector.plaintext)
            assertWithMessage(vector.name).that(actual).isEqualTo(vector.expectedCiphertext)
        }
    }

    /**
     * Sanity: HMAC-SHA256 over the primary ciphertext, keyed with the
     * primary HMAC key, must match the locked-in tag.
     */
    @Test
    fun `HMAC-SHA256 primitive matches the primary KAT tag`() {
        for (vector in SecureMessageVectors.hmac) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(vector.key, "HmacSHA256"))
            val actual = mac.doFinal(vector.data)
            assertWithMessage(vector.name).that(actual).isEqualTo(vector.expectedTag)
        }
    }

    /**
     * KAT for the public [SecureMessageCodec.encryptAndSign] surface:
     * encrypt the primary plaintext with the primary key/IV, then verify
     * three things:
     *
     *   1. The serialized `SecureMessage` parses back into proto with the
     *      expected `HeaderAndBody` shape (HMAC_SHA256 + AES_256_CBC, IV
     *      = primaryIv).
     *   2. The body field of the inner `HeaderAndBody` matches the
     *      independently-computed primary ciphertext from
     *      [SecureMessageVectors.aesPrimary].
     *   3. The signature equals `HMAC-SHA256(serialized(HeaderAndBody))`
     *      under the primary HMAC key — re-computed here using the same
     *      JCE transform but a *different* code path than the codec's,
     *      so a regression in the codec's HMAC wiring would surface.
     *
     * This is the headline "fixed key/IV/plaintext → known ciphertext +
     * known signature" KAT called out by issue #13.
     */
    @Test
    fun `encryptAndSign produces the expected ciphertext and a valid signature`() {
        val v = SecureMessageVectors.aesPrimary

        val secureMessageBytes =
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = v.iv,
            )

        // Parse the on-the-wire SecureMessage and inspect each field.
        val secureMessage = SecureMessage.parseFrom(secureMessageBytes)
        val headerAndBody = HeaderAndBody.parseFrom(secureMessage.headerAndBody)
        val header = headerAndBody.header

        assertThat(header.signatureScheme).isEqualTo(SigScheme.HMAC_SHA256)
        assertThat(header.encryptionScheme).isEqualTo(EncScheme.AES_256_CBC)
        assertThat(header.iv.toByteArray()).isEqualTo(v.iv)

        // public_metadata must be a serialized GcmMetadata{type=DEVICE_TO_DEVICE_MESSAGE, version=1}.
        val gcm = GcmMetadata.parseFrom(header.publicMetadata)
        assertThat(gcm.type).isEqualTo(Type.DEVICE_TO_DEVICE_MESSAGE)
        assertThat(gcm.version).isEqualTo(1)

        // Body must equal the locked-in AES-CBC ciphertext for this plaintext.
        assertThat(headerAndBody.body.toByteArray()).isEqualTo(v.expectedCiphertext)

        // Signature must equal HMAC-SHA256 over serialized(HeaderAndBody),
        // computed via an independent JCE Mac instance.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SecureMessageVectors.primaryHmacKey, "HmacSHA256"))
        val expectedSignature = mac.doFinal(secureMessage.headerAndBody.toByteArray())
        assertThat(secureMessage.signature.toByteArray()).isEqualTo(expectedSignature)
    }

    /**
     * Round-trip the primary KAT through [SecureMessageCodec.verifyAndDecrypt]
     * and confirm the decrypted bytes match the original plaintext exactly.
     */
    @Test
    fun `verifyAndDecrypt round-trips the primary KAT plaintext`() {
        val v = SecureMessageVectors.aesPrimary
        val secureMessageBytes =
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = v.iv,
            )

        val recovered =
            SecureMessageCodec.verifyAndDecrypt(
                secureMessageBytes = secureMessageBytes,
                decryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
            )

        assertThat(recovered).isEqualTo(v.plaintext)
    }

    /**
     * Round-trip a wide range of plaintext sizes (0..2048 bytes) using a
     * fixed key/HMAC key and per-iteration random IVs. Each round-trip
     * must recover the exact original bytes.
     *
     * The 0-byte case is included specifically because PKCS7 still pads
     * to one full block of `0x10`s and a buggy implementation could fall
     * through to a zero-byte ciphertext.
     */
    @Test
    fun `round-trip handles plaintext sizes from 0 to 2048 bytes`() {
        val rng = SecureRandom()
        val sizes = listOf(0, 1, 15, 16, 17, 31, 32, 100, 511, 512, 1023, 1024, 2048)
        for (size in sizes) {
            val payload = ByteArray(size) { (it and 0xFF).toByte() }
            val iv = SecureMessageCodec.randomIv(rng)
            val sealed =
                SecureMessageCodec.encryptAndSign(
                    payload = payload,
                    encryptKey = SecureMessageVectors.primaryEncryptKey,
                    hmacKey = SecureMessageVectors.primaryHmacKey,
                    iv = iv,
                )
            val recovered =
                SecureMessageCodec.verifyAndDecrypt(
                    secureMessageBytes = sealed,
                    decryptKey = SecureMessageVectors.primaryEncryptKey,
                    hmacKey = SecureMessageVectors.primaryHmacKey,
                )
            assertWithMessage("size=$size").that(recovered).isEqualTo(payload)
        }
    }

    /**
     * Tampering with a single byte of the signature must reject before
     * any AES work. We assert the exception type is the dedicated
     * verification-failure subclass.
     */
    @Test
    fun `verifyAndDecrypt rejects a tampered signature`() {
        val v = SecureMessageVectors.aesPrimary
        val sealed =
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = v.iv,
            )

        // Flip one bit in the signature field.
        val parsed = SecureMessage.parseFrom(sealed)
        val tamperedSig = parsed.signature.toByteArray().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered =
            SecureMessage
                .newBuilder(parsed)
                .setSignature(
                    com.google.protobuf.ByteString
                        .copyFrom(tamperedSig),
                ).build()
                .toByteArray()

        assertThrows<SecureMessageVerificationException> {
            SecureMessageCodec.verifyAndDecrypt(
                secureMessageBytes = tampered,
                decryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
            )
        }
    }

    /**
     * Tampering with a single byte of the ciphertext (without re-signing)
     * must also be rejected at HMAC time, NOT at AES time. This is the
     * critical "HMAC verify before decrypt" property — verified by
     * assertion on the exception type.
     */
    @Test
    fun `verifyAndDecrypt rejects a tampered ciphertext at the HMAC stage`() {
        val v = SecureMessageVectors.aesPrimary
        val sealed =
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = v.iv,
            )

        // Flip a bit inside the body (ciphertext) of HeaderAndBody, then
        // re-serialize SecureMessage WITHOUT updating the signature.
        val sm = SecureMessage.parseFrom(sealed)
        val hab = HeaderAndBody.parseFrom(sm.headerAndBody)
        val tamperedBody = hab.body.toByteArray().also { it[0] = (it[0].toInt() xor 0x40).toByte() }
        val tamperedHab =
            HeaderAndBody
                .newBuilder(hab)
                .setBody(
                    com.google.protobuf.ByteString
                        .copyFrom(tamperedBody),
                ).build()
                .toByteArray()
        val tampered =
            SecureMessage
                .newBuilder(sm)
                .setHeaderAndBody(
                    com.google.protobuf.ByteString
                        .copyFrom(tamperedHab),
                ).build()
                .toByteArray()

        // Must surface as a verification failure (HMAC mismatch), not an
        // AES failure — the order of operations contract demands HMAC
        // first.
        val ex =
            assertThrows<SecureMessageVerificationException> {
                SecureMessageCodec.verifyAndDecrypt(
                    secureMessageBytes = tampered,
                    decryptKey = v.key,
                    hmacKey = SecureMessageVectors.primaryHmacKey,
                )
            }
        assertThat(ex).isNotNull()
    }

    /**
     * A [SecureMessage] whose header declares `signature_scheme` other
     * than `HMAC_SHA256` must be rejected even if its HMAC happens to
     * match. We construct such a frame by hand and re-sign it, then check
     * the codec rejects the unsupported scheme at header validation.
     */
    @Test
    fun `verifyAndDecrypt rejects unsupported signature_scheme`() {
        val v = SecureMessageVectors.aesPrimary
        val ciphertext =
            run {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(v.key, "AES"), IvParameterSpec(v.iv))
                cipher.doFinal(v.plaintext)
            }
        val gcm =
            GcmMetadata
                .newBuilder()
                .setType(Type.DEVICE_TO_DEVICE_MESSAGE)
                .setVersion(1)
                .build()
                .toByteArray()
        val badHeader =
            Header
                .newBuilder()
                .setSignatureScheme(SigScheme.ECDSA_P256_SHA256)
                .setEncryptionScheme(EncScheme.AES_256_CBC)
                .setIv(
                    com.google.protobuf.ByteString
                        .copyFrom(v.iv),
                ).setPublicMetadata(
                    com.google.protobuf.ByteString
                        .copyFrom(gcm),
                ).build()
        val habBytes =
            HeaderAndBody
                .newBuilder()
                .setHeader(badHeader)
                .setBody(
                    com.google.protobuf.ByteString
                        .copyFrom(ciphertext),
                ).build()
                .toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SecureMessageVectors.primaryHmacKey, "HmacSHA256"))
        val sig = mac.doFinal(habBytes)
        val sealed =
            SecureMessage
                .newBuilder()
                .setHeaderAndBody(
                    com.google.protobuf.ByteString
                        .copyFrom(habBytes),
                ).setSignature(
                    com.google.protobuf.ByteString
                        .copyFrom(sig),
                ).build()
                .toByteArray()

        assertThrows<SecureMessageFormatException> {
            SecureMessageCodec.verifyAndDecrypt(
                secureMessageBytes = sealed,
                decryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
            )
        }
    }

    /** A header with a 12-byte IV (e.g. someone confusing this with GCM) must be rejected. */
    @Test
    fun `verifyAndDecrypt rejects an IV of the wrong size`() {
        val v = SecureMessageVectors.aesPrimary
        val ciphertext =
            run {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(v.key, "AES"), IvParameterSpec(v.iv))
                cipher.doFinal(v.plaintext)
            }
        val gcm =
            GcmMetadata
                .newBuilder()
                .setType(Type.DEVICE_TO_DEVICE_MESSAGE)
                .setVersion(1)
                .build()
                .toByteArray()
        val truncatedIv = v.iv.copyOfRange(0, 12)
        val badHeader =
            Header
                .newBuilder()
                .setSignatureScheme(SigScheme.HMAC_SHA256)
                .setEncryptionScheme(EncScheme.AES_256_CBC)
                .setIv(
                    com.google.protobuf.ByteString
                        .copyFrom(truncatedIv),
                ).setPublicMetadata(
                    com.google.protobuf.ByteString
                        .copyFrom(gcm),
                ).build()
        val habBytes =
            HeaderAndBody
                .newBuilder()
                .setHeader(badHeader)
                .setBody(
                    com.google.protobuf.ByteString
                        .copyFrom(ciphertext),
                ).build()
                .toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SecureMessageVectors.primaryHmacKey, "HmacSHA256"))
        val sig = mac.doFinal(habBytes)
        val sealed =
            SecureMessage
                .newBuilder()
                .setHeaderAndBody(
                    com.google.protobuf.ByteString
                        .copyFrom(habBytes),
                ).setSignature(
                    com.google.protobuf.ByteString
                        .copyFrom(sig),
                ).build()
                .toByteArray()

        assertThrows<SecureMessageFormatException> {
            SecureMessageCodec.verifyAndDecrypt(
                secureMessageBytes = sealed,
                decryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
            )
        }
    }

    /** Garbage bytes must surface as a format exception, not crash. */
    @Test
    fun `verifyAndDecrypt rejects garbage that fails proto parsing`() {
        val garbage = ByteArray(64) { 0xFF.toByte() }
        assertThrows<SecureMessageFormatException> {
            SecureMessageCodec.verifyAndDecrypt(
                secureMessageBytes = garbage,
                decryptKey = SecureMessageVectors.primaryEncryptKey,
                hmacKey = SecureMessageVectors.primaryHmacKey,
            )
        }
    }

    /** Wrong sized keys must be caught by `require()`. */
    @Test
    fun `encryptAndSign rejects wrong sized keys`() {
        val v = SecureMessageVectors.aesPrimary
        assertThrows<IllegalArgumentException> {
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = ByteArray(16),
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = v.iv,
            )
        }
        assertThrows<IllegalArgumentException> {
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = ByteArray(16),
                iv = v.iv,
            )
        }
        assertThrows<IllegalArgumentException> {
            SecureMessageCodec.encryptAndSign(
                payload = v.plaintext,
                encryptKey = v.key,
                hmacKey = SecureMessageVectors.primaryHmacKey,
                iv = ByteArray(8),
            )
        }
    }

    /** The IV produced by [SecureMessageCodec.randomIv] is exactly 16 bytes. */
    @Test
    fun `randomIv returns 16 bytes`() {
        val iv = SecureMessageCodec.randomIv(SecureRandom())
        assertThat(iv.size).isEqualTo(SecureMessageCodec.IV_SIZE)
    }

    /** Two consecutive [SecureMessageCodec.randomIv] draws are not equal. */
    @Test
    fun `randomIv yields different IVs across calls`() {
        val rng = SecureRandom()
        val a = SecureMessageCodec.randomIv(rng)
        val b = SecureMessageCodec.randomIv(rng)
        assertThat(a).isNotEqualTo(b)
    }

    /** D2D wrap/unwrap is a clean round-trip. */
    @Test
    fun `D2D wrap and unwrap round-trip preserves payload and sequence number`() {
        val payload = "hello".toByteArray(Charsets.US_ASCII)
        val seq = 12345
        val wrapped = SecureMessageCodec.wrapDeviceToDeviceMessage(payload, seq)

        // Cross-check with the proto API directly.
        val parsed = DeviceToDeviceMessage.parseFrom(wrapped)
        assertThat(parsed.sequenceNumber).isEqualTo(seq)
        assertThat(parsed.message.toByteArray()).isEqualTo(payload)

        val unwrapped = SecureMessageCodec.unwrapDeviceToDeviceMessage(wrapped)
        assertThat(unwrapped.sequenceNumber).isEqualTo(seq)
        assertThat(unwrapped.payload).isEqualTo(payload)
    }

    /** Missing required fields in the inner D2D wrapper surface as a format error. */
    @Test
    fun `unwrapDeviceToDeviceMessage rejects missing fields`() {
        val empty = DeviceToDeviceMessage.newBuilder().build().toByteArray()
        assertThrows<SecureMessageFormatException> {
            SecureMessageCodec.unwrapDeviceToDeviceMessage(empty)
        }
    }
}
