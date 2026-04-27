/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.kat

import com.google.common.truth.Truth.assertWithMessage
import io.github.kyujincho.wvmg.protocol.test.AesCbcNistVectors
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIST SP 800-38A Known-Answer Tests for AES-256-CBC (no padding).
 *
 * Quick Share's SecureMessage envelope encrypts every frame with AES-256-CBC
 * (#13). The codec already locks down `(key, iv, plaintext) -> ciphertext`
 * against an internally-computed reference vector via
 * [io.github.kyujincho.wvmg.protocol.test.SecureMessageVectors]; this test
 * file adds a second, **externally-published** anchor (NIST's own SP 800-38A
 * Appendix F vectors) so a regression in the JCE provider, the cipher
 * transformation string, or the key/IV wiring has two unrelated chances to
 * be caught.
 *
 * Both directions are exercised: encrypt (F.2.5) and decrypt (F.2.6). A
 * single-direction check could miss a bug that only mangled encrypt or only
 * decrypt mode.
 */
class AesCbcNistKatTest {
    @TestFactory
    fun `NIST SP 800-38A AES-256-CBC encrypt matches the published ciphertext`(): List<DynamicTest> =
        AesCbcNistVectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (encrypt)") {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(vector.key, "AES"),
                    IvParameterSpec(vector.iv),
                )
                val actual = cipher.doFinal(vector.plaintext)
                assertWithMessage("AES-256-CBC encrypt mismatch for ${vector.name}")
                    .that(actual)
                    .isEqualTo(vector.ciphertext)
            }
        }

    @TestFactory
    fun `NIST SP 800-38A AES-256-CBC decrypt round-trips the published ciphertext`(): List<DynamicTest> =
        AesCbcNistVectors.all.map { vector ->
            DynamicTest.dynamicTest("${vector.name} (decrypt)") {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(vector.key, "AES"),
                    IvParameterSpec(vector.iv),
                )
                val actual = cipher.doFinal(vector.ciphertext)
                assertWithMessage("AES-256-CBC decrypt mismatch for ${vector.name}")
                    .that(actual)
                    .isEqualTo(vector.plaintext)
            }
        }
}
