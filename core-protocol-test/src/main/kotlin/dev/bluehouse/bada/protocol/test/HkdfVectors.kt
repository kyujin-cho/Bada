/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.test

/**
 * RFC 5869 Appendix A Known-Answer Test (KAT) vectors for HKDF-SHA256.
 *
 * These vectors are reproduced verbatim from
 * https://datatracker.ietf.org/doc/html/rfc5869#appendix-A so they can be
 * used by both the JVM unit tests in `:core-protocol` and any future Android
 * instrumentation tests that consume the same fixtures.
 *
 * Test Cases 4-7 use HKDF-SHA1 and are intentionally omitted; the only hash
 * Quick Share negotiates is SHA-256.
 */
public object HkdfVectors {
    /**
     * One RFC 5869 Appendix A KAT vector. All byte arrays are the literal
     * values from the RFC text.
     *
     * Intentionally a regular `class` rather than a `data class`: the
     * auto-generated `equals`/`hashCode` for `ByteArray` use reference
     * identity (a known JVM gotcha) so the data-class default is broken,
     * and we do not need destructuring or `copy()` for read-only fixtures.
     * `toString` is overridden to return the human-readable [name] so test
     * failures stay legible.
     */
    public class Vector(
        public val name: String,
        public val ikm: ByteArray,
        public val salt: ByteArray,
        public val info: ByteArray,
        public val length: Int,
        public val expectedPrk: ByteArray,
        public val expectedOkm: ByteArray,
    ) {
        override fun toString(): String = name
    }

    /**
     * RFC 5869 A.1 — Basic test case with SHA-256.
     *
     * IKM = 22 bytes (0x0b * 22), salt set, info set, L = 42.
     */
    public val testCase1: Vector =
        Vector(
            name = "RFC 5869 A.1 — basic SHA-256",
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
            expectedPrk =
                hex(
                    "077709362c2e32df0ddc3f0dc47bba63" +
                        "90b6c73bb50f9c3122ec844ad7c2b3e5",
                ),
            expectedOkm =
                hex(
                    "3cb25f25faacd57a90434f64d0362f2a" +
                        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                        "34007208d5b887185865",
                ),
        )

    /**
     * RFC 5869 A.2 — Test with SHA-256 and longer inputs/outputs.
     *
     * IKM = 80 bytes, salt = 80 bytes, info = 80 bytes, L = 82.
     */
    public val testCase2: Vector =
        Vector(
            name = "RFC 5869 A.2 — long inputs SHA-256",
            ikm =
                hex(
                    "000102030405060708090a0b0c0d0e0f" +
                        "101112131415161718191a1b1c1d1e1f" +
                        "202122232425262728292a2b2c2d2e2f" +
                        "303132333435363738393a3b3c3d3e3f" +
                        "404142434445464748494a4b4c4d4e4f",
                ),
            salt =
                hex(
                    "606162636465666768696a6b6c6d6e6f" +
                        "707172737475767778797a7b7c7d7e7f" +
                        "808182838485868788898a8b8c8d8e8f" +
                        "909192939495969798999a9b9c9d9e9f" +
                        "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
                ),
            info =
                hex(
                    "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                        "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                        "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
                ),
            length = 82,
            expectedPrk =
                hex(
                    "06a6b88c5853361a06104c9ceb35b45c" +
                        "ef760014904671014a193f40c15fc244",
                ),
            expectedOkm =
                hex(
                    "b11e398dc80327a1c8e7f78c596a4934" +
                        "4f012eda2d4efad8a050cc4c19afa97c" +
                        "59045a99cac7827271cb41c65e590e09" +
                        "da3275600c2f09b8367793a9aca3db71" +
                        "cc30c58179ec3e87c14c01d5c1f3434f" +
                        "1d87",
                ),
        )

    /**
     * RFC 5869 A.3 — Test with SHA-256 and zero-length salt/info.
     *
     * IKM = 22 bytes (0x0b * 22), salt = empty, info = empty, L = 42.
     * Exercises the "default salt = HashLen zero bytes" branch and the
     * "info is empty string" branch.
     */
    public val testCase3: Vector =
        Vector(
            name = "RFC 5869 A.3 — zero-length salt/info SHA-256",
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42,
            expectedPrk =
                hex(
                    "19ef24a32c717b167f33a91d6f648bdf" +
                        "96596776afdb6377ac434c1c293ccb04",
                ),
            expectedOkm =
                hex(
                    "8da4e775a563c18f715f802a063c5a31" +
                        "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                        "9d201395faa4b61a96c8",
                ),
        )

    /** All Quick-Share-relevant RFC 5869 KAT vectors in declaration order. */
    public val all: List<Vector> = listOf(testCase1, testCase2, testCase3)

    /**
     * Decodes a hex string to bytes. Accepts only `[0-9a-f]` (lowercase) and
     * even-length input — strict on purpose so a typo in a vector becomes a
     * loud `IllegalArgumentException` at test load time.
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
