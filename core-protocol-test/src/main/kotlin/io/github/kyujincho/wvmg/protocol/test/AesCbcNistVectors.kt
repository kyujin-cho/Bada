/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.test

/**
 * NIST SP 800-38A Known-Answer Test (KAT) vectors for AES-256-CBC.
 *
 * Quick Share's SecureMessage envelope encrypts every frame with AES-256-CBC
 * (see #13's `SecureMessageCodec`). The codec already locks down `(key, iv,
 * plaintext) -> ciphertext` against an internally-computed reference vector
 * via [SecureMessageVectors]; these NIST vectors add a second, independent
 * anchor — published by NIST in SP 800-38A Appendix F — so a regression in
 * the JCE provider, the cipher transformation string, or the key/IV wiring
 * has two unrelated chances to be caught.
 *
 * Both the encrypt (F.2.5) and the decrypt (F.2.6) directions of the same
 * `(key, iv, plaintext, ciphertext)` tuple are exercised by the test, since
 * a one-direction-only check could miss a regression that mangles either
 * `Cipher.ENCRYPT_MODE` or `Cipher.DECRYPT_MODE`.
 *
 * Reference:
 * https://csrc.nist.gov/CSRC/media/Publications/sp/800-38a/final/documents/sp800-38a.pdf
 *
 * Padding is **not** exercised here on purpose: SP 800-38A specifies only the
 * raw block-mode primitive (`AES/CBC/NoPadding`), so the plaintext is exactly
 * four 16-byte blocks and PKCS7 is out of scope. PKCS7 padding correctness is
 * already covered by [SecureMessageVectors].
 */
public object AesCbcNistVectors {
    /**
     * One AES-256-CBC NIST KAT vector. Plain class, not `data class`, for the
     * same `ByteArray`-equality reason called out in [HkdfVectors].
     *
     * @property name Human-readable label printed on test failure.
     * @property key 32-byte AES-256 key.
     * @property iv 16-byte CBC IV.
     * @property plaintext Block-aligned plaintext bytes (multiple of 16).
     * @property ciphertext Expected ciphertext bytes (same length as plaintext
     *   when no padding is used).
     */
    public class Vector(
        public val name: String,
        public val key: ByteArray,
        public val iv: ByteArray,
        public val plaintext: ByteArray,
        public val ciphertext: ByteArray,
    ) {
        init {
            require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
            require(iv.size == 16) { "CBC IV must be 16 bytes, got ${iv.size}" }
            require(plaintext.size % 16 == 0) {
                "Plaintext must be a multiple of the AES block size (16): got ${plaintext.size}"
            }
            require(ciphertext.size == plaintext.size) {
                "Without padding ciphertext must equal plaintext length"
            }
        }

        override fun toString(): String = name
    }

    /**
     * SP 800-38A Appendix F.2.5 / F.2.6 — `CBC-AES256.Encrypt` /
     * `CBC-AES256.Decrypt`.
     *
     * Key and four 16-byte plaintext / ciphertext blocks are reproduced
     * verbatim from the spec. The tuple covers:
     *
     *  - Block 1 → exercises the IV path (CBC's `XOR(iv, pt[0])`).
     *  - Block 2 → exercises the chaining path (`XOR(ct[0], pt[1])`).
     *  - Block 3 → second chaining step.
     *  - Block 4 → final block; together they catch any bug that affected
     *    only the very first or very last block.
     */
    public val sp80038aF2: Vector =
        Vector(
            name = "NIST SP 800-38A F.2.5/F.2.6 — AES-256 CBC",
            key =
                hex(
                    "603deb1015ca71be2b73aef0857d7781" +
                        "1f352c073b6108d72d9810a30914dff4",
                ),
            iv = hex("000102030405060708090a0b0c0d0e0f"),
            plaintext =
                hex(
                    "6bc1bee22e409f96e93d7e117393172a" +
                        "ae2d8a571e03ac9c9eb76fac45af8e51" +
                        "30c81c46a35ce411e5fbc1191a0a52ef" +
                        "f69f2445df4f9b17ad2b417be66c3710",
                ),
            ciphertext =
                hex(
                    "f58c4c04d6e5f1ba779eabfb5f7bfbd6" +
                        "9cfc4e967edb808d679f777bc6702c7d" +
                        "39f23369a9d9bacfa530e26304231461" +
                        "b2eb05e2c39be9fcda6c19078c6a9d1b",
                ),
        )

    /** All NIST SP 800-38A AES-256-CBC KAT vectors in declaration order. */
    public val all: List<Vector> = listOf(sp80038aF2)

    /**
     * Decodes a hex string to bytes. Same strict-decoding policy as the
     * companion fixtures: lowercase, even length, `[0-9a-f]` only.
     */
    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length: ${value.length}" }
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = decodeNibble(value[i * 2])
            val lo = decodeNibble(value[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun decodeNibble(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> throw IllegalArgumentException("Invalid hex character: '$c'")
        }
}
