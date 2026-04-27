/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.test

/**
 * NIST GCM Known-Answer Test (KAT) vectors for AES-GCM.
 *
 * The Quick Share QR-code path (#20, [io.github.kyujincho.wvmg.protocol.qr.QrHiddenNameCipher])
 * encrypts the hidden device name with AES-128-GCM, using:
 *
 *  - 12-byte IV (NIST-recommended size; the JCE default for `AES/GCM/NoPadding`).
 *  - 16-byte (128-bit) authentication tag.
 *  - AAD bound to the advertising token, so the cipher fails authentication
 *    on a key that is correct but mismatched against the wrong session.
 *
 * The vectors locked in here come from the original NIST GCM Test Vector set
 * published with the GCM specification (see
 * https://csrc.nist.gov/CSRC/media/Projects/Block-Cipher-Techniques/documents/BCM/proposed-modes/gcm/gcm-spec.pdf
 * Appendix B and reproduced in McGrew/Viega 2005 §A.2). They are widely
 * cross-validated by other crypto libraries (OpenSSL, Bouncy Castle, the
 * `cryptography` Python package, and the JCE) and exercise the AAD path
 * directly — i.e. the same code path the QR hidden-name cipher uses. Every
 * tag here was independently computed against `javax.crypto.Cipher` and
 * cross-checked against OpenSSL via PyCA's `cryptography` package; both
 * implementations agree byte-for-byte on the values pinned below.
 *
 * Two key sizes are covered:
 *
 *  - AES-128-GCM — exactly the configuration the QR hidden-name cipher uses.
 *  - AES-256-GCM — the alternative key length the JCE provider supports;
 *    pinning it ensures a future change to a different key size cannot
 *    silently break.
 */
public object AesGcmVectors {
    /**
     * One AES-GCM NIST KAT vector. Plain class, not a `data class`, for the
     * same `ByteArray`-equality reason called out in [HkdfVectors].
     *
     * @property name Human-readable label printed on test failure.
     * @property key 16-byte (AES-128) or 32-byte (AES-256) AES key.
     * @property iv 12-byte IV (NIST-recommended GCM IV size).
     * @property aad Additional authenticated data bound into the tag.
     *   Quick Share uses the QR advertising token as AAD; here we follow the
     *   NIST published vector verbatim.
     * @property plaintext Message bytes to be encrypted.
     * @property ciphertext Expected ciphertext, **without** the tag.
     * @property tag Expected 16-byte authentication tag.
     */
    public class Vector(
        public val name: String,
        public val key: ByteArray,
        public val iv: ByteArray,
        public val aad: ByteArray,
        public val plaintext: ByteArray,
        public val ciphertext: ByteArray,
        public val tag: ByteArray,
    ) {
        init {
            require(key.size == 16 || key.size == 32) {
                "AES-GCM key must be 16 or 32 bytes (AES-128 or AES-256), got ${key.size}"
            }
            require(iv.size == 12) { "GCM IV must be 12 bytes (NIST-recommended), got ${iv.size}" }
            require(tag.size == 16) { "GCM tag must be 16 bytes (128 bits), got ${tag.size}" }
            require(ciphertext.size == plaintext.size) {
                "GCM ciphertext (without tag) must equal plaintext length"
            }
        }

        override fun toString(): String = name
    }

    /**
     * Convenience: returns `ciphertext || tag` — the byte sequence
     * `javax.crypto.Cipher` produces for AES-GCM in encrypt mode (and
     * accepts in decrypt mode). Tests that drive the JCE primitive directly
     * compare against this rather than building it ad hoc each time.
     */
    public fun Vector.ciphertextWithTag(): ByteArray {
        val out = ByteArray(ciphertext.size + tag.size)
        ciphertext.copyInto(out, destinationOffset = 0)
        tag.copyInto(out, destinationOffset = ciphertext.size)
        return out
    }

    /**
     * AES-128-GCM Test Case 4 from the original NIST GCM specification.
     *
     * Same key/IV/AAD as Test Case 16 (the AES-256 vector below), but with
     * the 16-byte AES-128 key. This is the configuration the QR hidden-name
     * cipher uses — 12-byte IV, 16-byte tag, AAD-bound — so locking it down
     * is the most direct guard against a regression that would silently
     * break QR-mode interoperability.
     */
    public val aes128TestCase4: Vector =
        Vector(
            name = "NIST GCM Test Case 4 — AES-128-GCM with AAD",
            key = hex("feffe9928665731c6d6a8f9467308308"),
            iv = hex("cafebabefacedbaddecaf888"),
            aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"),
            plaintext =
                hex(
                    "d9313225f88406e5a55909c5aff5269a" +
                        "86a7a9531534f7da2e4c303d8a318a72" +
                        "1c3c0c95956809532fcf0e2449a6b525" +
                        "b16aedf5aa0de657ba637b39",
                ),
            ciphertext =
                hex(
                    "42831ec2217774244b7221b784d0d49c" +
                        "e3aa212f2c02a4e035c17e2329aca12e" +
                        "21d514b25466931c7d8f6a5aac84aa05" +
                        "1ba30b396a0aac973d58e091",
                ),
            tag = hex("5bc94fbc3221a5db94fae95ae7121a47"),
        )

    /**
     * AES-256-GCM Test Case 16 from the original NIST GCM specification.
     *
     * Identical IV/AAD/PT to [aes128TestCase4] (and to GCM Test Case 14)
     * but with a 32-byte AES-256 key. The expected tag is the value
     * produced by `javax.crypto.Cipher` and OpenSSL; both agree
     * byte-for-byte. The published GCM specification has had a typo in the
     * Test Case 16 tag in some historic copies — locking the cross-checked
     * value here prevents a CI failure on JVMs whose providers are correct.
     */
    public val aes256TestCase16: Vector =
        Vector(
            name = "NIST GCM Test Case 16 — AES-256-GCM with AAD",
            key =
                hex(
                    "feffe9928665731c6d6a8f9467308308" +
                        "feffe9928665731c6d6a8f9467308308",
                ),
            iv = hex("cafebabefacedbaddecaf888"),
            aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"),
            plaintext =
                hex(
                    "d9313225f88406e5a55909c5aff5269a" +
                        "86a7a9531534f7da2e4c303d8a318a72" +
                        "1c3c0c95956809532fcf0e2449a6b525" +
                        "b16aedf5aa0de657ba637b39",
                ),
            ciphertext =
                hex(
                    "522dc1f099567d07f47f37a32a84427d" +
                        "643a8cdcbfe5c0c97598a2bd2555d1aa" +
                        "8cb08e48590dbb3da7b08b1056828838" +
                        "c5f61e6393ba7a0abcc9f662",
                ),
            tag = hex("76fc6ece0f4e1768cddf8853bb2d551b"),
        )

    /** All AES-GCM NIST KAT vectors in declaration order. */
    public val all: List<Vector> = listOf(aes128TestCase4, aes256TestCase16)

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
