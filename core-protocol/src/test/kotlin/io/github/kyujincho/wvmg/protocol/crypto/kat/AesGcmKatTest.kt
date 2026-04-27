/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.kat

import com.google.common.truth.Truth.assertWithMessage
import io.github.kyujincho.wvmg.protocol.test.AesGcmVectors
import io.github.kyujincho.wvmg.protocol.test.AesGcmVectors.ciphertextWithTag
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIST GCM Known-Answer Tests for AES-GCM.
 *
 * The Quick Share QR-code path (#20,
 * [io.github.kyujincho.wvmg.protocol.qr.QrHiddenNameCipher]) encrypts the
 * hidden device name with AES-128-GCM using a 12-byte IV, a 16-byte tag, and
 * an AAD bound to the advertising token. This test file pins the JCE
 * primitive against the published NIST GCM test vectors so that a change in
 * the JCE provider, GCM IV/tag-length mode, or AAD-binding would be caught
 * before it could break interop.
 *
 * Coverage:
 *
 *   1. **Encrypt path** — feed `(key, IV, AAD, plaintext)` into JCE and
 *      assert the resulting `ciphertext || tag` matches the locked-in NIST
 *      values byte-for-byte.
 *   2. **Decrypt path** — feed `(key, IV, AAD, ciphertext || tag)` and
 *      assert the recovered plaintext matches.
 *   3. **AAD-binding negative case** — flip a single bit in the AAD and
 *      assert that decrypt throws [AEADBadTagException]. This is the
 *      property the QR hidden-name cipher's "wrong device" rejection
 *      depends on.
 */
class AesGcmKatTest {
    private companion object {
        const val TAG_LEN_BITS = 128
    }

    @TestFactory
    fun `NIST AES-GCM encrypt matches the published ciphertext and tag`(): List<DynamicTest> =
        AesGcmVectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (encrypt)") {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(vector.key, "AES"),
                    GCMParameterSpec(TAG_LEN_BITS, vector.iv),
                )
                cipher.updateAAD(vector.aad)
                val actual = cipher.doFinal(vector.plaintext)
                assertWithMessage("AES-GCM ciphertext|tag mismatch for ${vector.name}")
                    .that(actual)
                    .isEqualTo(vector.ciphertextWithTag())
            }
        }

    @TestFactory
    fun `NIST AES-GCM decrypt round-trips the published plaintext`(): List<DynamicTest> =
        AesGcmVectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (decrypt)") {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(vector.key, "AES"),
                    GCMParameterSpec(TAG_LEN_BITS, vector.iv),
                )
                cipher.updateAAD(vector.aad)
                val actual = cipher.doFinal(vector.ciphertextWithTag())
                assertWithMessage("AES-GCM decrypt mismatch for ${vector.name}")
                    .that(actual)
                    .isEqualTo(vector.plaintext)
            }
        }

    @TestFactory
    fun `NIST AES-GCM decrypt rejects modified AAD`(): List<DynamicTest> =
        AesGcmVectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (AAD-tampered → AEADBadTagException)") {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(vector.key, "AES"),
                    GCMParameterSpec(TAG_LEN_BITS, vector.iv),
                )
                // Flip the low bit of the first AAD byte; everything else stays
                // intact. The QR hidden-name path's "wrong device" rejection
                // depends on this exact behaviour: AAD mismatch => auth failure.
                val tamperedAad = vector.aad.copyOf()
                tamperedAad[0] = (tamperedAad[0].toInt() xor 0x01).toByte()
                cipher.updateAAD(tamperedAad)
                assertThrows<AEADBadTagException> {
                    cipher.doFinal(vector.ciphertextWithTag())
                }
            }
        }
}
