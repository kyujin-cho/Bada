/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.securemessage

import com.google.security.cryptauth.lib.securegcm.DeviceToDeviceMessagesProto.DeviceToDeviceMessage
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.GcmMetadata
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.Type
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EncScheme
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.Header
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SigScheme
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless encrypt+sign / decrypt+verify primitive for the Quick Share
 * SecureMessage envelope.
 *
 * After UKEY2 finishes, every frame on the wire is a `SecureMessage`
 * carrying a `DeviceToDeviceMessage` body that wraps a single `OfflineFrame`.
 * The complete on-the-wire shape is:
 *
 * ```
 * SecureMessage {
 *   header_and_body = serialize(HeaderAndBody {
 *     header = Header {
 *       signature_scheme  = HMAC_SHA256
 *       encryption_scheme = AES_256_CBC
 *       iv                = <16 random bytes per frame>
 *       public_metadata   = serialize(GcmMetadata {
 *         type    = DEVICE_TO_DEVICE_MESSAGE
 *         version = 1
 *       })
 *     }
 *     body = AES-256-CBC( PKCS7-pad( serialize(DeviceToDeviceMessage{seq, payload}) ),
 *                        key = sendEncryptKey, iv = <iv from header> )
 *   })
 *   signature = HMAC-SHA256(header_and_body, key = sendHmacKey)
 * }
 * ```
 *
 * This object owns:
 *
 *  - **AES-256-CBC + PKCS7** encryption / decryption via JCE
 *    (`Cipher.getInstance("AES/CBC/PKCS5Padding")` — JCE's `PKCS5Padding`
 *    is the same byte-stream as PKCS7 for any AES block size).
 *  - **HMAC-SHA256** signing / verifying via `Mac.getInstance("HmacSHA256")`,
 *    with constant-time comparison of received signatures via
 *    [MessageDigest.isEqual].
 *  - **IV generation** via [SecureRandom] (16 random bytes per frame, never
 *    reused — checked downstream in tests).
 *  - **`HMAC-then-decrypt` order.** Signature verification ALWAYS happens
 *    before any AES work. A tampered ciphertext can never trigger the
 *    `Cipher.doFinal()` code path in the receiver.
 *
 * The codec is intentionally **stateless** — it neither holds keys nor
 * sequence counters. State (counters, key material) lives in
 * [SecureChannel], which composes this codec with a `FramedConnection`.
 * Two reasons for the split:
 *
 *  1. The KAT tests (issue #13 acceptance criteria) need to drive the
 *     primitive with a fixed key/IV and check the resulting bytes. A
 *     stateless function is the easiest contract to test.
 *  2. Future re-use: if Quick Share ever exposes a non-Connection use of
 *     this primitive, the codec is ready to be lifted out of
 *     [SecureChannel].
 *
 * ### Sensitive data discipline
 *
 * - Keys are accepted as raw `ByteArray` (32 bytes each). They are NOT
 *   stored anywhere on this object.
 * - `toString()` is the JVM default for an `object` and reveals nothing
 *   useful. There is no logging in this file. Do not add any.
 */
public object SecureMessageCodec {
    /** AES-256 key size in bytes. */
    public const val AES_KEY_SIZE: Int = 32

    /** HMAC-SHA256 key size in bytes (any size is legal for HMAC, but we pin to 32). */
    public const val HMAC_KEY_SIZE: Int = 32

    /**
     * AES-CBC initialization-vector size in bytes. Equal to the AES block
     * size. Quick Share's SecureMessage framing requires this exactly:
     * receivers read 16 bytes from `header.iv` and feed it straight into
     * `Cipher.init`.
     */
    public const val IV_SIZE: Int = 16

    /** HMAC-SHA256 output size in bytes. */
    public const val HMAC_OUTPUT_SIZE: Int = 32

    /**
     * GCM metadata version embedded in every outgoing header. Quick Share
     * peers send `1` for `DEVICE_TO_DEVICE_MESSAGE`; receivers do not
     * validate it (NearDrop ignores it too) but we emit it for byte-for-byte
     * parity.
     */
    private const val GCM_METADATA_VERSION = 1

    private const val AES_KEY_ALGORITHM = "AES"
    private const val AES_CIPHER_TRANSFORM = "AES/CBC/PKCS5Padding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    // The fixed `public_metadata` field encoded once at class load: a
    // serialized `GcmMetadata{type=DEVICE_TO_DEVICE_MESSAGE, version=1}`.
    // Pre-serializing avoids a tiny allocation per send and, more usefully,
    // keeps the value reviewable as a constant in tests.
    private val GCM_METADATA_DEVICE_TO_DEVICE_V1: ByteArray =
        GcmMetadata
            .newBuilder()
            .setType(Type.DEVICE_TO_DEVICE_MESSAGE)
            .setVersion(GCM_METADATA_VERSION)
            .build()
            .toByteArray()

    /**
     * Encrypts [payload] (a serialized `DeviceToDeviceMessage`), signs the
     * resulting `HeaderAndBody`, and returns a serialized [SecureMessage]
     * ready to be handed to a length-prefixed framing layer.
     *
     * @param payload Already-serialized `DeviceToDeviceMessage` bytes. The
     *   D2D wrapping (sequence number assignment, body field) is the
     *   caller's responsibility; this primitive only sees a byte string.
     * @param encryptKey 32-byte AES-256 key (the local "send" encrypt key
     *   from [io.github.kyujincho.wvmg.protocol.crypto.DirectionalKeys]).
     *   The byte array is NOT defensively copied — callers must not mutate
     *   it for the duration of the call.
     * @param hmacKey 32-byte HMAC-SHA256 key (the local "send" HMAC key).
     *   Same non-copy contract as [encryptKey].
     * @param iv 16-byte AES-CBC IV. Production callers MUST pass a freshly
     *   generated [SecureRandom] output (see [randomIv]); tests pass fixed
     *   IVs to drive Known-Answer Tests.
     * @return The serialized [SecureMessage] envelope, ready for
     *   [io.github.kyujincho.wvmg.protocol.transport.FramedConnection.sendFrame].
     * @throws IllegalArgumentException if any byte length is wrong.
     */
    public fun encryptAndSign(
        payload: ByteArray,
        encryptKey: ByteArray,
        hmacKey: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(encryptKey.size == AES_KEY_SIZE) {
            "encryptKey must be $AES_KEY_SIZE bytes, got ${encryptKey.size}"
        }
        require(hmacKey.size == HMAC_KEY_SIZE) {
            "hmacKey must be $HMAC_KEY_SIZE bytes, got ${hmacKey.size}"
        }
        require(iv.size == IV_SIZE) {
            "iv must be $IV_SIZE bytes, got ${iv.size}"
        }

        val ciphertext = aesCbcEncrypt(plaintext = payload, key = encryptKey, iv = iv)
        val headerAndBody = buildHeaderAndBody(iv = iv, ciphertext = ciphertext)
        val signature = hmacSha256(data = headerAndBody, key = hmacKey)

        return SecureMessage
            .newBuilder()
            .setHeaderAndBody(
                com.google.protobuf.ByteString
                    .copyFrom(headerAndBody),
            ).setSignature(
                com.google.protobuf.ByteString
                    .copyFrom(signature),
            ).build()
            .toByteArray()
    }

    /**
     * Verifies the HMAC signature on a serialized [SecureMessage], decrypts
     * the body, and returns the inner `DeviceToDeviceMessage` payload bytes.
     *
     * The verification order is fixed and **must not** be reordered:
     *
     *   1. Parse [SecureMessage] → `header_and_body`, `signature`.
     *   2. Recompute `HMAC-SHA256(header_and_body, hmacKey)`.
     *   3. Compare in **constant time** with [MessageDigest.isEqual]. If the
     *      MAC differs, throw immediately — DO NOT touch the AES side.
     *   4. Parse [HeaderAndBody], extract `header` and `body`.
     *   5. Validate `header.signature_scheme == HMAC_SHA256`,
     *      `header.encryption_scheme == AES_256_CBC`, and that `header.iv`
     *      is exactly [IV_SIZE] bytes.
     *   6. AES-256-CBC decrypt `body` with `decryptKey` and the IV from the
     *      header; return the plaintext.
     *
     * @param secureMessageBytes Serialized [SecureMessage] as received off
     *   the wire (i.e., the payload of a single
     *   [io.github.kyujincho.wvmg.protocol.transport.FramedConnection.receiveFrame]).
     * @param decryptKey 32-byte AES-256 key (the local "receive" encrypt
     *   key from [io.github.kyujincho.wvmg.protocol.crypto.DirectionalKeys]).
     * @param hmacKey 32-byte HMAC-SHA256 key (the local "receive" HMAC key).
     * @return The decrypted `DeviceToDeviceMessage` byte string. Caller is
     *   responsible for proto-parsing it.
     * @throws SecureMessageVerificationException if the HMAC signature does
     *   not match. Constant-time compared.
     * @throws SecureMessageFormatException if the [SecureMessage] header
     *   declares an unsupported scheme, an empty/wrong IV, or fails to
     *   parse as a valid protobuf.
     * @throws SecureMessageCryptoException if AES decryption fails (bad
     *   padding, truncated ciphertext, etc.). This indicates a tampered or
     *   corrupted body that nevertheless somehow passed HMAC — in practice
     *   this should never happen because the HMAC binds the entire body,
     *   but the JCE may still surface BadPaddingException on edge cases and
     *   we surface that as a protocol error.
     */
    @Suppress("ThrowsCount") // Each throw is a distinct protocol-level error type the caller disambiguates on.
    public fun verifyAndDecrypt(
        secureMessageBytes: ByteArray,
        decryptKey: ByteArray,
        hmacKey: ByteArray,
    ): ByteArray {
        require(decryptKey.size == AES_KEY_SIZE) {
            "decryptKey must be $AES_KEY_SIZE bytes, got ${decryptKey.size}"
        }
        require(hmacKey.size == HMAC_KEY_SIZE) {
            "hmacKey must be $HMAC_KEY_SIZE bytes, got ${hmacKey.size}"
        }

        val secureMessage =
            try {
                SecureMessage.parseFrom(secureMessageBytes)
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                throw SecureMessageFormatException("SecureMessage proto failed to parse", e)
            }

        if (!secureMessage.hasHeaderAndBody() || !secureMessage.hasSignature()) {
            throw SecureMessageFormatException(
                "SecureMessage missing header_and_body or signature",
            )
        }
        val headerAndBodyBytes = secureMessage.headerAndBody.toByteArray()
        val receivedSignature = secureMessage.signature.toByteArray()

        // STEP 1: HMAC verify BEFORE any AES work. Constant-time compare so
        // signature mismatches do not leak a per-byte timing oracle.
        val expectedSignature = hmacSha256(data = headerAndBodyBytes, key = hmacKey)
        if (!MessageDigest.isEqual(expectedSignature, receivedSignature)) {
            throw SecureMessageVerificationException("HMAC signature mismatch")
        }

        // STEP 2: parse and validate the header now that we trust the bytes.
        val headerAndBody =
            try {
                HeaderAndBody.parseFrom(headerAndBodyBytes)
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                throw SecureMessageFormatException("HeaderAndBody proto failed to parse", e)
            }
        if (!headerAndBody.hasHeader() || !headerAndBody.hasBody()) {
            throw SecureMessageFormatException("HeaderAndBody missing header or body")
        }
        val header = headerAndBody.header
        validateHeader(header)

        val iv = header.iv.toByteArray()
        val ciphertext = headerAndBody.body.toByteArray()

        // STEP 3: AES-256-CBC decrypt with the IV from the header. Any JCE
        // failure (bad padding, truncated ciphertext) becomes a
        // [SecureMessageCryptoException] — not a generic exception — so
        // callers can disambiguate from sequence-number errors.
        return try {
            aesCbcDecrypt(ciphertext = ciphertext, key = decryptKey, iv = iv)
        } catch (e: javax.crypto.BadPaddingException) {
            throw SecureMessageCryptoException("AES-CBC decrypt failed: bad padding", e)
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            throw SecureMessageCryptoException("AES-CBC decrypt failed: bad block size", e)
        }
    }

    /**
     * Generates a fresh 16-byte random AES-CBC IV using [secureRandom].
     *
     * Production callers should pass a default [SecureRandom] instance (one
     * per channel is plenty — `SecureRandom` is thread-safe). Tests pass a
     * deterministic source to reproduce exact ciphertexts in KATs.
     */
    public fun randomIv(secureRandom: SecureRandom): ByteArray {
        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Wraps a serialized `OfflineFrame` (the inner protocol payload) inside
     * a `DeviceToDeviceMessage{sequence_number, message=offlineFrame}` and
     * returns the serialized D2D message.
     *
     * Provided here (rather than in [SecureChannel]) so the KAT tests can
     * drive the primitive end-to-end with a fixed sequence number without
     * spinning up a channel.
     */
    public fun wrapDeviceToDeviceMessage(
        offlineFrame: ByteArray,
        sequenceNumber: Int,
    ): ByteArray =
        DeviceToDeviceMessage
            .newBuilder()
            .setMessage(
                com.google.protobuf.ByteString
                    .copyFrom(offlineFrame),
            ).setSequenceNumber(sequenceNumber)
            .build()
            .toByteArray()

    /**
     * Inverse of [wrapDeviceToDeviceMessage]: parses a serialized
     * `DeviceToDeviceMessage` and returns `(sequenceNumber, payload)`.
     *
     * @throws SecureMessageFormatException if the proto is malformed or
     *   missing required fields. The proto schema marks both fields as
     *   `optional`, but the Quick Share contract requires both — a peer
     *   that omits one of them is violating the protocol.
     */
    public fun unwrapDeviceToDeviceMessage(d2dBytes: ByteArray): D2DUnwrapped {
        val d2d =
            try {
                DeviceToDeviceMessage.parseFrom(d2dBytes)
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                throw SecureMessageFormatException("DeviceToDeviceMessage proto failed to parse", e)
            }
        if (!d2d.hasSequenceNumber() || !d2d.hasMessage()) {
            throw SecureMessageFormatException(
                "DeviceToDeviceMessage missing sequence_number or message",
            )
        }
        return D2DUnwrapped(
            sequenceNumber = d2d.sequenceNumber,
            payload = d2d.message.toByteArray(),
        )
    }

    /**
     * Rebuilds the [HeaderAndBody] proto for a single outgoing frame and
     * returns its serialized bytes (the input to the HMAC).
     */
    private fun buildHeaderAndBody(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val header =
            Header
                .newBuilder()
                .setSignatureScheme(SigScheme.HMAC_SHA256)
                .setEncryptionScheme(EncScheme.AES_256_CBC)
                .setIv(
                    com.google.protobuf.ByteString
                        .copyFrom(iv),
                ).setPublicMetadata(
                    com.google.protobuf.ByteString
                        .copyFrom(GCM_METADATA_DEVICE_TO_DEVICE_V1),
                ).build()
        return HeaderAndBody
            .newBuilder()
            .setHeader(header)
            .setBody(
                com.google.protobuf.ByteString
                    .copyFrom(ciphertext),
            ).build()
            .toByteArray()
    }

    /**
     * Validates that a received [Header] declares the crypto schemes Quick
     * Share actually uses. We accept exactly one combination — anything
     * else aborts the connection.
     */
    @Suppress("ThrowsCount") // One distinct format-error message per malformed-header field.
    private fun validateHeader(header: Header) {
        if (!header.hasSignatureScheme() || header.signatureScheme != SigScheme.HMAC_SHA256) {
            throw SecureMessageFormatException(
                "Header signature_scheme is not HMAC_SHA256 (got ${header.signatureScheme})",
            )
        }
        if (!header.hasEncryptionScheme() || header.encryptionScheme != EncScheme.AES_256_CBC) {
            throw SecureMessageFormatException(
                "Header encryption_scheme is not AES_256_CBC (got ${header.encryptionScheme})",
            )
        }
        if (!header.hasIv() || header.iv.size() != IV_SIZE) {
            throw SecureMessageFormatException(
                "Header iv is missing or has the wrong size (need $IV_SIZE bytes)",
            )
        }
    }

    /**
     * AES-256-CBC encrypt with PKCS7 padding. Returns ciphertext (no IV
     * prepended; the caller propagates the IV out-of-band via
     * `Header.iv`).
     */
    private fun aesCbcEncrypt(
        plaintext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_CIPHER_TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, AES_KEY_ALGORITHM),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-CBC decrypt with PKCS7 padding. Surfaces JCE failures to the
     * caller; [verifyAndDecrypt] wraps them in
     * [SecureMessageCryptoException] so the public surface stays narrow.
     */
    private fun aesCbcDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_CIPHER_TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, AES_KEY_ALGORITHM),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(ciphertext)
    }

    /** Computes `HMAC-SHA256(data)` under the given 32-byte key. */
    private fun hmacSha256(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}

/**
 * Result of [SecureMessageCodec.unwrapDeviceToDeviceMessage].
 *
 * @property sequenceNumber The peer-asserted sequence number for this
 *   frame. The caller (typically [SecureChannel]) checks this against its
 *   own monotonic counter and rejects out-of-order frames.
 * @property payload The inner serialized `OfflineFrame` bytes.
 */
public data class D2DUnwrapped(
    public val sequenceNumber: Int,
    public val payload: ByteArray,
) {
    // ByteArray equals/hashCode on a data class is reference-based; override
    // for content-based comparison so tests can `assertEquals` cleanly.
    //
    // `payload` is the *plaintext* inner `OfflineFrame` bytes that have
    // already been HMAC-verified by [SecureMessageCodec.verifyAndDecrypt],
    // not a MAC tag or key. Plain `contentEquals` is fine here. See
    // `:core-protocol/README.md` for the project-wide rule on when
    // constant-time compare is required.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is D2DUnwrapped) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int = 31 * sequenceNumber + payload.contentHashCode()
}

/**
 * Thrown by [SecureMessageCodec.verifyAndDecrypt] when the HMAC signature
 * on a received frame does not match the expected MAC.
 *
 * **This is the loudest-possible signal of tampering or a wrong key
 * pair.** Callers MUST treat it as fatal — abort the connection and do not
 * retry.
 */
public class SecureMessageVerificationException(
    message: String,
) : RuntimeException(message)

/**
 * Thrown by [SecureMessageCodec.verifyAndDecrypt] (or by
 * [SecureMessageCodec.unwrapDeviceToDeviceMessage]) when a received proto
 * is structurally malformed or declares unsupported crypto schemes.
 *
 * Like [SecureMessageVerificationException], this is fatal: there is no
 * way to recover an out-of-spec frame.
 */
public class SecureMessageFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thrown by [SecureMessageCodec.verifyAndDecrypt] when the JCE
 * `Cipher.doFinal()` step fails (bad padding, truncated ciphertext).
 *
 * In practice this should never trigger because the HMAC signature already
 * binds the entire `HeaderAndBody`, so any tampered ciphertext fails the
 * HMAC check first. The case is kept distinct so callers do not have to
 * catch raw `BadPaddingException` from JCE.
 */
public class SecureMessageCryptoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
