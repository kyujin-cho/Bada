/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.qr

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-GCM encrypt/decrypt helpers for the **hidden-mode** Quick Share
 * QR-code path.
 *
 * In hidden mode the receiver does not advertise its plaintext device name
 * in the EndpointInfo. Instead, the TLV record of type=1 carries:
 *
 * ```
 * IV(12 bytes) || AES-GCM-ciphertext(deviceName) || tag(16 bytes)
 * ```
 *
 * with:
 *
 *  - `key` = `nameEncryptionKey` (16 bytes, derived per [QrKeyDerivation])
 *  - `AAD` = `advertisingToken`  (16 bytes, derived per [QrKeyDerivation])
 *
 * Only a sender that scanned the same QR code can derive both pieces and
 * therefore decrypt. The AAD binding means an attacker who somehow obtained
 * the encryption key but not the matching advertising token still cannot
 * forge a hidden-name TLV that any sender will accept.
 *
 * The minimum on-the-wire length is 12 (IV) + 0 (ciphertext, when name is
 * empty) + 16 (tag) = 28 bytes. The sender's "is this a hidden-name TLV?"
 * heuristic in [QrTlvMatcher] therefore tests `value.size > 28` before
 * attempting decryption — at exactly 28 bytes the ciphertext is empty and
 * decoding succeeds with an empty string, which is not a useful device
 * name; treating it as "not a hidden-name TLV" matches NearDrop's behavior
 * documented in the issue body.
 *
 * **Thread safety.** `javax.crypto.Cipher` is **not** thread-safe; each
 * call here allocates a fresh instance. [SecureRandom] is thread-safe per
 * the JCA contract.
 */
public object QrHiddenNameCipher {
    /** Length of the AES-GCM IV (a.k.a. nonce) in bytes. NIST recommends 12. */
    public const val IV_LEN: Int = 12

    /** Length of the AES-GCM authentication tag in bytes (128 bits). */
    public const val TAG_LEN: Int = 16

    /** Length of the AES-GCM authentication tag in **bits**, as JCE expects. */
    private const val TAG_LEN_BITS: Int = TAG_LEN * 8

    /** JCE transformation string for AES in GCM mode with no padding. */
    private const val TRANSFORMATION: String = "AES/GCM/NoPadding"

    /** JCE algorithm name for AES key material. */
    private const val KEY_ALGORITHM: String = "AES"

    /**
     * Minimum length of a valid hidden-name TLV value (IV + tag, with empty
     * ciphertext). Senders use a strict-greater-than check against this in
     * [QrTlvMatcher] so an empty-name TLV (which has no useful payload) is
     * not mistaken for a hidden name.
     */
    public const val MIN_HIDDEN_NAME_TLV_LEN: Int = IV_LEN + TAG_LEN

    /**
     * Encrypts [deviceName] for embedding in a TLV record.
     *
     * Generates a fresh random IV via [random] (default: a platform
     * `SecureRandom`). The output is `IV || ciphertext || tag` — exactly the
     * byte string that goes into the TLV record's value field.
     *
     * @param nameEncryptionKey 16-byte AES-128 key from
     *   [QrKeyDerivation.deriveNameEncryptionKey].
     * @param advertisingToken 16-byte AAD from
     *   [QrKeyDerivation.deriveAdvertisingToken].
     * @param deviceName UTF-8 device name to encrypt. May be any length up
     *   to whatever the TLV layer allows (the 1-byte length prefix caps the
     *   serialized TLV value at 255 bytes total, leaving 255 − 28 = 227
     *   bytes of plaintext budget; the cipher itself does not enforce that
     *   limit because it is a TLV-layer concern).
     * @param random RNG used to generate the IV. Reusing an `(IV, key)`
     *   pair would catastrophically break GCM, so callers MUST NOT pass
     *   a deterministic RNG outside test code.
     */
    public fun encrypt(
        nameEncryptionKey: ByteArray,
        advertisingToken: ByteArray,
        deviceName: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(nameEncryptionKey.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            "nameEncryptionKey must be ${QrKeyDerivation.DERIVED_KEY_LEN} bytes, " +
                "got ${nameEncryptionKey.size}"
        }
        require(advertisingToken.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            "advertisingToken must be ${QrKeyDerivation.DERIVED_KEY_LEN} bytes, " +
                "got ${advertisingToken.size}"
        }
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(nameEncryptionKey, KEY_ALGORITHM),
            GCMParameterSpec(TAG_LEN_BITS, iv),
        )
        cipher.updateAAD(advertisingToken)
        val ciphertextAndTag = cipher.doFinal(deviceName.toByteArray(StandardCharsets.UTF_8))

        // Prepend the IV. The output layout is IV || ciphertext || tag.
        val out = ByteArray(IV_LEN + ciphertextAndTag.size)
        iv.copyInto(out, destinationOffset = 0)
        ciphertextAndTag.copyInto(out, destinationOffset = IV_LEN)
        return out
    }

    /**
     * Attempts to decrypt the byte string `IV || ciphertext || tag` produced
     * by [encrypt].
     *
     * Returns `null` (not throws) on any failure: short input, wrong key,
     * wrong AAD, malformed UTF-8 in the recovered plaintext. The matching
     * loop on the sender side iterates over every discovered device's TLV
     * and only one of them is the intended target; throwing on the others
     * would force the caller to write a try/catch around every iteration
     * for what is a *normal* "this device isn't us" outcome.
     *
     * @param nameEncryptionKey 16-byte AES-128 key. Must equal the value
     *   used by [encrypt].
     * @param advertisingToken 16-byte AAD. Must equal the value used by
     *   [encrypt].
     * @param tlvValue The raw TLV value (`IV || ciphertext || tag`).
     */
    @Suppress("ReturnCount")
    public fun decrypt(
        nameEncryptionKey: ByteArray,
        advertisingToken: ByteArray,
        tlvValue: ByteArray,
    ): String? {
        if (nameEncryptionKey.size != QrKeyDerivation.DERIVED_KEY_LEN) return null
        if (advertisingToken.size != QrKeyDerivation.DERIVED_KEY_LEN) return null
        if (tlvValue.size < MIN_HIDDEN_NAME_TLV_LEN) return null

        val cipher =
            try {
                Cipher.getInstance(TRANSFORMATION).also {
                    it.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(nameEncryptionKey, KEY_ALGORITHM),
                        GCMParameterSpec(TAG_LEN_BITS, tlvValue, 0, IV_LEN),
                    )
                    it.updateAAD(advertisingToken)
                }
            } catch (_: GeneralSecurityException) {
                return null
            }

        val plaintext =
            try {
                cipher.doFinal(tlvValue, IV_LEN, tlvValue.size - IV_LEN)
            } catch (_: GeneralSecurityException) {
                // AEADBadTagException, IllegalBlockSizeException, etc. — all
                // mean "this TLV is not for us" in the sender matching loop.
                return null
            }

        return try {
            decodeUtf8Strict(plaintext)
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /**
     * Strict UTF-8 decode that rejects malformed sequences. Quick Share
     * device names are UTF-8 by spec; silently substituting U+FFFD here
     * would make two peers disagree on the name byte-for-byte and break
     * any downstream code that fingerprints the advertisement.
     */
    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return decoder.decode(buffer).toString()
    }
}
