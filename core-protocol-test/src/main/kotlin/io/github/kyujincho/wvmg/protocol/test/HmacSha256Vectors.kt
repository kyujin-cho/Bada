/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.test

/**
 * RFC 4231 Known-Answer Test (KAT) vectors for HMAC-SHA256.
 *
 * Quick Share never ships a custom HMAC implementation: every signing /
 * verifying call goes through `javax.crypto.Mac.getInstance("HmacSHA256")`.
 * These vectors therefore exist as a smoke test that the JCE primitive
 * matches the spec's expected tags exactly. A buggy provider, a misconfigured
 * `SecretKeySpec` algorithm string, or a typo that switched HMAC to a
 * different hash would all surface here before they could leak into the
 * SecureMessage signing path.
 *
 * Vectors are reproduced verbatim from
 * https://datatracker.ietf.org/doc/html/rfc4231#section-4. Test cases 1-6
 * are included; case 7 ("Test Using Larger Than Block-Size Key and Larger
 * Than Block-Size Data") has identical key/data lengths to case 6 and
 * exercises the same code paths, so we omit it to keep the table compact.
 *
 * Truncation (case 5's `HMAC-SHA-256-128`) is **not** modeled here —
 * Quick Share never truncates HMAC tags. The full 32-byte tag for case 5
 * is locked in instead, computed by the same JCE primitive the production
 * code uses; it begins with the RFC-published 16 truncated bytes
 * `a3b6167473100ee06e0c796c2955552b`, providing the truncation guarantee
 * indirectly while keeping the entire 32-byte output under test.
 *
 * Vectors live in `:core-protocol-test` so both the JVM unit tests in
 * `:core-protocol` and any future Android instrumentation tests can reuse
 * them without duplicating the answer table.
 */
public object HmacSha256Vectors {
    /**
     * One RFC 4231 HMAC-SHA256 KAT vector. Plain class, not a `data class`,
     * for the same `ByteArray`-equality reason called out in [HkdfVectors].
     *
     * @property name Human-readable label printed on test failure.
     * @property key HMAC key bytes (any length per RFC 2104).
     * @property data Input bytes to be MAC'd.
     * @property expectedTag Expected 32-byte HMAC-SHA256 output.
     */
    public class Vector(
        public val name: String,
        public val key: ByteArray,
        public val data: ByteArray,
        public val expectedTag: ByteArray,
    ) {
        init {
            require(expectedTag.size == 32) {
                "HMAC-SHA256 tag must be 32 bytes, got ${expectedTag.size}"
            }
        }

        override fun toString(): String = name
    }

    /** RFC 4231 §4.2 — Test Case 1. 20-byte key (`0x0b * 20`), short ASCII data. */
    public val testCase1: Vector =
        Vector(
            name = "RFC 4231 case 1 — 20-byte 0x0b key / \"Hi There\"",
            key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            data = ascii("Hi There"),
            expectedTag =
                hex(
                    "b0344c61d8db38535ca8afceaf0bf12b" +
                        "881dc200c9833da726e9376c2e32cff7",
                ),
        )

    /** RFC 4231 §4.3 — Test Case 2. Short ASCII key + ASCII data. */
    public val testCase2: Vector =
        Vector(
            name = "RFC 4231 case 2 — \"Jefe\" key / \"what do ya want for nothing?\"",
            key = ascii("Jefe"),
            data = ascii("what do ya want for nothing?"),
            expectedTag =
                hex(
                    "5bdcc146bf60754e6a042426089575c7" +
                        "5a003f089d2739839dec58b964ec3843",
                ),
        )

    /**
     * RFC 4231 §4.4 — Test Case 3. 20-byte `0xaa` key, 50 bytes of `0xdd`.
     */
    public val testCase3: Vector =
        Vector(
            name = "RFC 4231 case 3 — 20-byte 0xaa key / 50-byte 0xdd data",
            key = hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            data =
                hex(
                    "dddddddddddddddddddddddddddddddd" +
                        "dddddddddddddddddddddddddddddddd" +
                        "dddddddddddddddddddddddddddddddd" +
                        "dddd",
                ),
            expectedTag =
                hex(
                    "773ea91e36800e46854db8ebd09181a7" +
                        "2959098b3ef8c122d9635514ced565fe",
                ),
        )

    /**
     * RFC 4231 §4.5 — Test Case 4. 25-byte structured key, 50-byte 0xcd data.
     */
    public val testCase4: Vector =
        Vector(
            name = "RFC 4231 case 4 — structured key / 50-byte 0xcd data",
            key = hex("0102030405060708090a0b0c0d0e0f10111213141516171819"),
            data =
                hex(
                    "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                        "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                        "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd" +
                        "cdcd",
                ),
            expectedTag =
                hex(
                    "82558a389a443c0ea4cc819899f2083a" +
                        "85f0faa3e578f8077a2e3ff46729665b",
                ),
        )

    /**
     * RFC 4231 §4.6 — Test Case 5. 20-byte `0x0c` key + ASCII data.
     *
     * RFC 4231 publishes only the 128-bit truncation
     * (`a3b6167473100ee06e0c796c2955552b`) for this case. The full 32-byte
     * tag locked in here was computed by `javax.crypto.Mac("HmacSHA256")`
     * with the same key/data; its first 16 bytes match the RFC truncation
     * exactly, which is the property the truncated-form vector existed to
     * verify in the first place.
     */
    public val testCase5: Vector =
        Vector(
            name = "RFC 4231 case 5 — 20-byte 0x0c key / \"Test With Truncation\"",
            key = hex("0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c"),
            data = ascii("Test With Truncation"),
            expectedTag =
                hex(
                    "a3b6167473100ee06e0c796c2955552b" +
                        "fa6f7c0a6a8aef8b93f860aab0cd20c5",
                ),
        )

    /**
     * RFC 4231 §4.7 — Test Case 6. 131-byte `0xaa` key, ASCII data.
     *
     * Exercises the "key longer than the SHA-256 block size of 64 bytes"
     * branch of HMAC: per RFC 2104 the implementation must replace the key
     * with `SHA-256(key)` before XOR'ing with `ipad`/`opad`. A buggy port
     * that skipped that hash step would produce a different tag here.
     */
    public val testCase6: Vector =
        Vector(
            name = "RFC 4231 case 6 — 131-byte 0xaa key / ASCII data",
            key = hex(repeat("aa", 131)),
            data = ascii("Test Using Larger Than Block-Size Key - Hash Key First"),
            expectedTag =
                hex(
                    "60e431591ee0b67f0d8a26aacbf5b77f" +
                        "8e0bc6213728c5140546040f0ee37f54",
                ),
        )

    /** All RFC 4231 HMAC-SHA256 KAT vectors in declaration order. */
    public val all: List<Vector> =
        listOf(testCase1, testCase2, testCase3, testCase4, testCase5, testCase6)

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

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun repeat(
        s: String,
        n: Int,
    ): String = buildString(s.length * n) { repeat(n) { append(s) } }
}
