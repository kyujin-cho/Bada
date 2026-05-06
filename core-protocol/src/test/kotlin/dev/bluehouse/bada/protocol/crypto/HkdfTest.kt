/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.crypto

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.test.HkdfVectors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Bit-exact correctness tests for [Hkdf] against RFC 5869 Appendix A vectors
 * 1, 2, and 3 (the three SHA-256 vectors). Vectors 4-6 use SHA-1 and are
 * deliberately omitted; SHA-1 is not negotiated by Quick Share.
 *
 * The vectors live in `:core-protocol-test` (`HkdfVectors`) so the same
 * Known-Answer Tests can be reused from Android instrumentation tests when
 * the protocol stack lights up later in Phase 1.
 */
class HkdfTest {
    @Test
    fun `RFC 5869 test case 1 — basic SHA-256`() {
        val v = HkdfVectors.testCase1
        assertThat(Hkdf.extract(v.salt, v.ikm)).isEqualTo(v.expectedPrk)
        assertThat(Hkdf.derive(v.ikm, v.salt, v.info, v.length)).isEqualTo(v.expectedOkm)
    }

    @Test
    fun `RFC 5869 test case 2 — long inputs SHA-256`() {
        val v = HkdfVectors.testCase2
        assertThat(Hkdf.extract(v.salt, v.ikm)).isEqualTo(v.expectedPrk)
        assertThat(Hkdf.derive(v.ikm, v.salt, v.info, v.length)).isEqualTo(v.expectedOkm)
    }

    @Test
    fun `RFC 5869 test case 3 — empty salt and info SHA-256`() {
        val v = HkdfVectors.testCase3
        assertThat(Hkdf.extract(v.salt, v.ikm)).isEqualTo(v.expectedPrk)
        assertThat(Hkdf.derive(v.ikm, v.salt, v.info, v.length)).isEqualTo(v.expectedOkm)
    }

    @Test
    fun `derive returns a fresh array of exactly the requested length`() {
        val ikm = ByteArray(16) { it.toByte() }
        val out = Hkdf.derive(ikm, ByteArray(0), ByteArray(0), length = 17)
        assertThat(out.size).isEqualTo(17)
    }

    @Test
    fun `derive supports the maximum HKDF-SHA256 output length`() {
        // RFC 5869 §2.3: L <= 255 * HashLen (= 8160 for SHA-256).
        val ikm = ByteArray(32) { 0x42 }
        val out = Hkdf.derive(ikm, ByteArray(0), ByteArray(0), length = 255 * 32)
        assertThat(out.size).isEqualTo(255 * 32)
    }

    @Test
    fun `derive rejects zero or negative length`() {
        val ikm = ByteArray(16)
        assertThrows<IllegalArgumentException> {
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), length = 0)
        }
        assertThrows<IllegalArgumentException> {
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), length = -1)
        }
    }

    @Test
    fun `derive rejects output length above RFC 5869 cap`() {
        val ikm = ByteArray(16)
        assertThrows<IllegalArgumentException> {
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), length = 255 * 32 + 1)
        }
    }

    @Test
    fun `empty salt is equivalent to HashLen zero bytes per RFC 5869`() {
        val ikm = ByteArray(16) { 0x0b }
        val info = byteArrayOf(0x01, 0x02, 0x03)
        val withEmptySalt = Hkdf.derive(ikm, ByteArray(0), info, length = 64)
        val withZeroSalt = Hkdf.derive(ikm, ByteArray(32), info, length = 64)
        assertThat(withEmptySalt).isEqualTo(withZeroSalt)
    }

    @Test
    fun `derive does not mutate caller-provided byte arrays`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val info = byteArrayOf(0x10, 0x20)
        val ikmSnapshot = ikm.copyOf()
        val saltSnapshot = salt.copyOf()
        val infoSnapshot = info.copyOf()

        Hkdf.derive(ikm, salt, info, length = 50)

        assertThat(ikm).isEqualTo(ikmSnapshot)
        assertThat(salt).isEqualTo(saltSnapshot)
        assertThat(info).isEqualTo(infoSnapshot)
    }
}
