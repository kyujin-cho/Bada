/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.qr

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Round-trip and AAD-binding tests for [QrHiddenNameCipher].
 *
 * The hidden-mode device-name TLV is the only place in the QR-code path
 * where we run AES-GCM. We need to verify that:
 *
 *  1. encrypt → decrypt is loss-less for any UTF-8 device name.
 *  2. Each call uses a fresh random IV (catastrophic to reuse one).
 *  3. AAD (advertising token) is actually bound — flipping it must cause
 *     decrypt to return null, not a garbled string.
 *  4. Tampering with any byte of the ciphertext makes decrypt return null
 *     (GCM is authenticated; the auth tag enforces this).
 *  5. The minimum-length and short-input edge cases are handled.
 */
class QrHiddenNameCipherTest {
    private val key = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN) { (it + 1).toByte() }
    private val aad = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN) { (0x80 + it).toByte() }

    @Test
    fun `encrypt then decrypt round-trips an ASCII device name`() {
        val name = "Galaxy S24"
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, name)
        val recovered = QrHiddenNameCipher.decrypt(key, aad, ciphertext)
        assertThat(recovered).isEqualTo(name)
    }

    @Test
    fun `encrypt then decrypt round-trips a UTF-8 device name`() {
        val name = "내 갤럭시 폴드 5"
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, name)
        assertThat(QrHiddenNameCipher.decrypt(key, aad, ciphertext)).isEqualTo(name)
    }

    @Test
    fun `encrypt then decrypt round-trips an empty device name`() {
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, "")
        // 12 (IV) + 0 (ciphertext) + 16 (tag) = 28 bytes.
        assertThat(ciphertext.size).isEqualTo(QrHiddenNameCipher.MIN_HIDDEN_NAME_TLV_LEN)
        // Decrypt should still recover the empty string when called
        // directly — the >28 heuristic in QrTlvMatcher is a *separate*
        // sender-side gate to avoid spurious matches in the wild, not a
        // restriction of the cipher itself.
        assertThat(QrHiddenNameCipher.decrypt(key, aad, ciphertext)).isEqualTo("")
    }

    @Test
    fun `output layout is IV of 12 then ciphertext then tag of 16 bytes`() {
        val name = "Pixel 9 Pro"
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, name)
        // The plaintext is 11 bytes UTF-8, so the total should be
        // 12 (IV) + 11 (ciphertext) + 16 (tag) = 39 bytes.
        val expectedSize =
            QrHiddenNameCipher.IV_LEN + name.toByteArray().size + QrHiddenNameCipher.TAG_LEN
        assertThat(ciphertext.size).isEqualTo(expectedSize)
    }

    @Test
    fun `each call uses a fresh random IV`() {
        val name = "Foldable"
        val a = QrHiddenNameCipher.encrypt(key, aad, name)
        val b = QrHiddenNameCipher.encrypt(key, aad, name)
        // Same plaintext, same key, same AAD — but the IV is fresh, so the
        // ciphertexts must differ. Reusing an (IV, key) pair is
        // catastrophic in GCM, so this is non-negotiable.
        assertThat(a).isNotEqualTo(b)
        // The IVs themselves should differ.
        val ivA = a.copyOfRange(0, QrHiddenNameCipher.IV_LEN)
        val ivB = b.copyOfRange(0, QrHiddenNameCipher.IV_LEN)
        assertThat(ivA).isNotEqualTo(ivB)
    }

    @Test
    fun `decrypt returns null when the AAD differs from the encrypt-time AAD`() {
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, "Phone")
        val wrongAad = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN) { 0x00 }
        assertThat(QrHiddenNameCipher.decrypt(key, wrongAad, ciphertext)).isNull()
    }

    @Test
    fun `decrypt returns null when the key differs from the encrypt-time key`() {
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, "Phone")
        val wrongKey = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN) { 0x00 }
        assertThat(QrHiddenNameCipher.decrypt(wrongKey, aad, ciphertext)).isNull()
    }

    @Test
    fun `decrypt returns null when any ciphertext byte is tampered with`() {
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, "Tablet 8")
        // Flip a bit in the middle of the ciphertext (not in the IV, which
        // would just decrypt to garbage and then fail the auth tag check
        // anyway — flipping the ciphertext is the more direct test).
        val tampered = ciphertext.copyOf()
        val flipIndex = QrHiddenNameCipher.IV_LEN + 1
        tampered[flipIndex] = (tampered[flipIndex].toInt() xor 0x01).toByte()
        assertThat(QrHiddenNameCipher.decrypt(key, aad, tampered)).isNull()
    }

    @Test
    fun `decrypt returns null when the auth tag is truncated`() {
        val ciphertext = QrHiddenNameCipher.encrypt(key, aad, "Laptop")
        // Drop the last byte of the tag; the GCM auth check will fail.
        val truncated = ciphertext.copyOfRange(0, ciphertext.size - 1)
        assertThat(QrHiddenNameCipher.decrypt(key, aad, truncated)).isNull()
    }

    @Test
    fun `decrypt returns null for input shorter than IV plus tag`() {
        // A 27-byte input is structurally too small to be a valid AES-GCM
        // ciphertext with a 12-byte IV and 16-byte tag. Bail out early.
        assertThat(
            QrHiddenNameCipher.decrypt(key, aad, ByteArray(27)),
        ).isNull()
        assertThat(QrHiddenNameCipher.decrypt(key, aad, ByteArray(0))).isNull()
    }

    @Test
    fun `encrypt rejects keys of the wrong length`() {
        assertThrows<IllegalArgumentException> {
            QrHiddenNameCipher.encrypt(ByteArray(15), aad, "x")
        }
        assertThrows<IllegalArgumentException> {
            QrHiddenNameCipher.encrypt(ByteArray(17), aad, "x")
        }
    }

    @Test
    fun `encrypt rejects AAD of the wrong length`() {
        assertThrows<IllegalArgumentException> {
            QrHiddenNameCipher.encrypt(key, ByteArray(15), "x")
        }
        assertThrows<IllegalArgumentException> {
            QrHiddenNameCipher.encrypt(key, ByteArray(17), "x")
        }
    }

    @Test
    fun `random round-trip across 30 device names with random keys`() {
        val rng = Random(0xBEEF)
        repeat(30) {
            val randomKey = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN).also { rng.nextBytes(it) }
            val randomAad = ByteArray(QrKeyDerivation.DERIVED_KEY_LEN).also { rng.nextBytes(it) }
            val nameLen = rng.nextInt(0, 64)
            val name = randomUtf8String(rng, nameLen)
            val encrypted =
                QrHiddenNameCipher.encrypt(randomKey, randomAad, name, deterministicRng())
            val decrypted = QrHiddenNameCipher.decrypt(randomKey, randomAad, encrypted)
            assertThat(decrypted).isEqualTo(name)
        }
    }

    /**
     * Produces a `SecureRandom` seeded deterministically. Test-only — never
     * use a deterministic RNG for real GCM IVs (catastrophic IV reuse).
     */
    private fun deterministicRng(): SecureRandom = SecureRandom.getInstance("SHA1PRNG")

    private fun randomUtf8String(
        rng: Random,
        approximateByteLen: Int,
    ): String {
        if (approximateByteLen == 0) return ""
        val sb = StringBuilder()
        var bytesUsed = 0
        while (bytesUsed < approximateByteLen) {
            val r = rng.nextInt(3)
            val (cp, byteCost) =
                when (r) {
                    0 -> rng.nextInt(0x20, 0x7F) to 1
                    1 -> rng.nextInt(0xA0, 0x100) to 2
                    else -> rng.nextInt(0xAC00, 0xD7A4) to 3
                }
            if (bytesUsed + byteCost > approximateByteLen) break
            sb.appendCodePoint(cp)
            bytesUsed += byteCost
        }
        return sb.toString()
    }
}
