/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.test

/**
 * Deterministic Known-Answer Test (KAT) vectors for the Quick Share
 * SecureMessage encrypt+sign primitive (issue #13).
 *
 * Two layers of vectors are provided:
 *
 *  1. **AES-256-CBC primitive vectors** — fixed `(key, iv, plaintext)`
 *     tuples with the resulting ciphertext locked in as hex bytes. These
 *     anchor the implementation to the JCE's
 *     `Cipher.getInstance("AES/CBC/PKCS5Padding")` output and are computed
 *     **independently** from the implementation under test (a stand-alone
 *     JVM `javax.crypto` harness fed the same inputs and emitted the
 *     hex). A regression in `SecureMessageCodec` cannot rewrite these
 *     answers.
 *
 *  2. **HMAC-SHA256 primitive vectors** — fixed `(key, data)` tuples with
 *     the HMAC tag locked in. Same independence guarantee. The data field
 *     in vector [hmacOverPrimaryCiphertext] is exactly the ciphertext from
 *     the primary AES vector, so the HMAC and AES vectors compose into a
 *     single end-to-end check.
 *
 * The primary AES vector intentionally uses a 40-byte plaintext (not
 * aligned to AES's 16-byte block size, so PKCS7 padding produces a
 * non-trivial trailing block) and a fixed 16-byte IV (`0x00..0x0f`) so the
 * resulting ciphertext is sensitive to the entire transformation chain.
 *
 * Vectors live in `:core-protocol-test` so JVM unit tests in
 * `:core-protocol` and (eventually) Android instrumentation tests can
 * share the same lookup table.
 */
public object SecureMessageVectors {
    /**
     * One AES-256-CBC primitive vector. Plain class, not `data class`,
     * matching the `ByteArray`-equality reasoning used in
     * [HkdfVectors] and [D2DKeyDerivationVectors].
     *
     * @property name Human-readable label printed on test failure.
     * @property key 32-byte AES-256 key.
     * @property iv 16-byte AES-CBC IV.
     * @property plaintext Input bytes (any length; PKCS7-padded internally).
     * @property expectedCiphertext Expected ciphertext bytes. Length is
     *   `((plaintext.size / 16) + 1) * 16` (PKCS7 always adds at least one
     *   byte and pads to the next block boundary).
     */
    public class AesCbcVector(
        public val name: String,
        public val key: ByteArray,
        public val iv: ByteArray,
        public val plaintext: ByteArray,
        public val expectedCiphertext: ByteArray,
    ) {
        override fun toString(): String = name
    }

    /**
     * One HMAC-SHA256 primitive vector.
     *
     * @property name Human-readable label.
     * @property key HMAC key (32 bytes for our usage).
     * @property data Input bytes to be MAC'd.
     * @property expectedTag Expected 32-byte HMAC-SHA256 output.
     */
    public class HmacVector(
        public val name: String,
        public val key: ByteArray,
        public val data: ByteArray,
        public val expectedTag: ByteArray,
    ) {
        override fun toString(): String = name
    }

    /**
     * Primary KAT key — `0x0123456789abcdef` repeated four times. Public
     * so tests can also drive [SecureMessageCodec.encryptAndSign] directly
     * with this key.
     */
    public val primaryEncryptKey: ByteArray =
        hex(
            "0123456789abcdef0123456789abcdef" +
                "0123456789abcdef0123456789abcdef",
        )

    /**
     * Primary KAT HMAC key — `0xfedcba9876543210` repeated four times.
     * Distinct from [primaryEncryptKey] so that a code regression that
     * accidentally swapped encrypt and HMAC keys would change the
     * ciphertext and tag visibly.
     */
    public val primaryHmacKey: ByteArray =
        hex(
            "fedcba9876543210fedcba9876543210" +
                "fedcba9876543210fedcba9876543210",
        )

    /** Primary KAT IV — `0x00..0x0f`. Picked for visual clarity in test output. */
    public val primaryIv: ByteArray = hex("000102030405060708090a0b0c0d0e0f")

    /**
     * Primary AES vector: 40-byte ASCII plaintext, encrypted with
     * [primaryEncryptKey] and [primaryIv].
     *
     * The ciphertext was computed by an independent `javax.crypto.Cipher`
     * harness — see the issue #13 PR description for the exact program.
     * Re-running the harness from a fresh checkout produces the same hex.
     */
    public val aesPrimary: AesCbcVector =
        AesCbcVector(
            name = "AES-256-CBC primary KAT (40-byte ASCII)",
            key = primaryEncryptKey,
            iv = primaryIv,
            plaintext = "Quick Share KAT plaintext for issue #13.".toByteArray(Charsets.US_ASCII),
            expectedCiphertext =
                hex(
                    "81762578643ceed2b3bfdb58aac6d376" +
                        "6a68d01b47bc3d7cd0f8f1219e169274" +
                        "c82dd71a6168affa9594bb5b62fe4a25",
                ),
        )

    /**
     * Secondary AES vector: 1-byte plaintext. Exercises the smallest
     * non-empty input — PKCS7 pads with 15 bytes of `0x0F`, producing a
     * single 16-byte ciphertext block.
     */
    public val aesOneByte: AesCbcVector =
        AesCbcVector(
            name = "AES-256-CBC 1-byte plaintext",
            key = primaryEncryptKey,
            iv = primaryIv,
            plaintext = byteArrayOf(0x41),
            expectedCiphertext = hex("2840f7d54ef2a3b15de5ae171b5d6fed"),
        )

    /**
     * Tertiary AES vector: exactly 16-byte plaintext. PKCS7 must add a
     * full 16-byte block of `0x10` padding here, producing a 32-byte
     * ciphertext. A naive implementation that skipped padding for
     * block-aligned inputs would fail this vector.
     */
    public val aesBlockAligned: AesCbcVector =
        AesCbcVector(
            name = "AES-256-CBC 16-byte block-aligned plaintext",
            key = primaryEncryptKey,
            iv = primaryIv,
            plaintext = hex("00112233445566778899aabbccddeeff"),
            expectedCiphertext =
                hex(
                    "fb33d502d8f2e35c35246764d8d765ec" +
                        "0401424cf38f8bf5e26e17f0e418986f",
                ),
        )

    /** All AES KAT vectors. */
    public val aes: List<AesCbcVector> = listOf(aesPrimary, aesOneByte, aesBlockAligned)

    /**
     * HMAC-SHA256 over the ciphertext from [aesPrimary], keyed with
     * [primaryHmacKey]. Used by tests as a sanity check on the JCE
     * `Mac.getInstance("HmacSHA256")` wiring.
     *
     * Note: this is **not** the SecureMessage signature — that one is
     * computed over the serialized `HeaderAndBody` (a protobuf), not over
     * the raw ciphertext. The full SecureMessage signature is verified
     * end-to-end by the round-trip tests, where the HMAC input is whatever
     * `HeaderAndBody.toByteArray()` produces. This vector exists to lock
     * down the standalone HMAC primitive.
     */
    public val hmacOverPrimaryCiphertext: HmacVector =
        HmacVector(
            name = "HMAC-SHA256 over primary AES ciphertext",
            key = primaryHmacKey,
            data = aesPrimary.expectedCiphertext,
            expectedTag =
                hex(
                    "ea22a32ff840b4b8f0ac604049789d6e" +
                        "e81a8337d51eac81a9bc1ec15c846633",
                ),
        )

    /** All HMAC KAT vectors. */
    public val hmac: List<HmacVector> = listOf(hmacOverPrimaryCiphertext)

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
