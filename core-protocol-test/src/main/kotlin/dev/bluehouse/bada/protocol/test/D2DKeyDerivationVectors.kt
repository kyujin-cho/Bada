/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.test

/**
 * Deterministic Known-Answer Test (KAT) vectors for the Quick Share post-UKEY2
 * D2D + SecureMessage key derivation chain.
 *
 * The vector below is computed off-line from the documented derivation
 * (see `D2DKeyDerivation` in `:core-protocol`). Concretely, the expected
 * outputs were produced by a Python reference that mirrors NearDrop's
 * `NearbyConnection.swift:finalizeKeyExchange` byte-for-byte:
 *
 * ```python
 * # See PR description for issue #11 for the full script.
 * import hmac, hashlib
 * def hkdf_extract(salt, ikm):
 *     if len(salt) == 0: salt = b'\x00' * 32
 *     return hmac.new(salt, ikm, hashlib.sha256).digest()
 * def hkdf_expand(prk, info, length):
 *     out, t, ctr = b'', b'', 1
 *     while len(out) < length:
 *         t = hmac.new(prk, t + info + bytes([ctr]), hashlib.sha256).digest()
 *         out += t; ctr += 1
 *     return out[:length]
 * def hkdf(ikm, salt, info, length):
 *     return hkdf_expand(hkdf_extract(salt, ikm), info, length)
 * ```
 *
 * Vectors live in `:core-protocol-test` so both the JVM unit tests in
 * `:core-protocol` and any future Android instrumentation tests can reuse
 * them. Pinning the expected outputs in source — rather than computing them
 * from the implementation under test — is the whole point: a regression in
 * `Hkdf` or `D2DKeyDerivation` cannot accidentally rewrite its own answers.
 */
public object D2DKeyDerivationVectors {
    /**
     * One D2D key-derivation KAT vector. Plain class, not `data class`, for
     * the same `ByteArray`-equality reason called out in [HkdfVectors].
     *
     * @property name Human-readable label printed on test failure.
     * @property dhs `SHA-256(ECDH-shared-secret-X-magnitude)` input. 32 bytes.
     * @property ukeyClientInitMsg Raw serialized `Ukey2Message` bytes for
     *   ClientInit. NOT length-prefixed.
     * @property ukeyServerInitMsg Raw serialized `Ukey2Message` bytes for
     *   ServerInit. NOT length-prefixed.
     * @property expectedAuthString Expected output of the
     *   `HKDF(salt="UKEY2 v1 auth")` derivation. 32 bytes.
     * @property expectedNextSecret Expected output of the
     *   `HKDF(salt="UKEY2 v1 next")` derivation. 32 bytes.
     * @property expectedD2dClientKey Expected output of the
     *   `HKDF(salt=SHA256("D2D"), info="client")` derivation. 32 bytes.
     * @property expectedD2dServerKey Expected output of the
     *   `HKDF(salt=SHA256("D2D"), info="server")` derivation. 32 bytes.
     * @property expectedClientEncryptKey Expected SecureMessage `ENC:2`
     *   output for the client direction. 32 bytes.
     * @property expectedClientHmacKey Expected SecureMessage `SIG:1` output
     *   for the client direction. 32 bytes.
     * @property expectedServerEncryptKey Expected SecureMessage `ENC:2`
     *   output for the server direction. 32 bytes.
     * @property expectedServerHmacKey Expected SecureMessage `SIG:1` output
     *   for the server direction. 32 bytes.
     */
    @Suppress("LongParameterList") // KAT fixture; one parameter per chain output is intentional.
    public class Vector(
        public val name: String,
        public val dhs: ByteArray,
        public val ukeyClientInitMsg: ByteArray,
        public val ukeyServerInitMsg: ByteArray,
        public val expectedAuthString: ByteArray,
        public val expectedNextSecret: ByteArray,
        public val expectedD2dClientKey: ByteArray,
        public val expectedD2dServerKey: ByteArray,
        public val expectedClientEncryptKey: ByteArray,
        public val expectedClientHmacKey: ByteArray,
        public val expectedServerEncryptKey: ByteArray,
        public val expectedServerHmacKey: ByteArray,
    ) {
        override fun toString(): String = name
    }

    /**
     * Primary KAT vector. Inputs are deterministic byte patterns chosen to
     * avoid accidental zero-byte gotchas:
     *
     *  - `dhs` repeats `00112233445566778899aabbccddeeff` twice — non-zero,
     *    non-uniform, and includes a high-bit byte.
     *  - `clientInitMsg` is the ASCII tag `"CLIENTINIT"` followed by the
     *    32-byte sequence `0x00..0x1f`.
     *  - `serverInitMsg` is the ASCII tag `"SERVERINIT"` followed by the
     *    32-byte sequence `0x20..0x3f`.
     *
     * Expected outputs match a Python reference that bit-for-bit mirrors
     * NearDrop's `finalizeKeyExchange`.
     */
    public val primary: Vector =
        Vector(
            name = "D2D primary KAT",
            dhs =
                hex(
                    "00112233445566778899aabbccddeeff" +
                        "00112233445566778899aabbccddeeff",
                ),
            ukeyClientInitMsg =
                hex(
                    // ASCII "CLIENTINIT"
                    "434c49454e54494e4954" +
                        // 0x00..0x1f
                        "000102030405060708090a0b0c0d0e0f" +
                        "101112131415161718191a1b1c1d1e1f",
                ),
            ukeyServerInitMsg =
                hex(
                    // ASCII "SERVERINIT"
                    "534552564552494e4954" +
                        // 0x20..0x3f
                        "202122232425262728292a2b2c2d2e2f" +
                        "303132333435363738393a3b3c3d3e3f",
                ),
            expectedAuthString =
                hex(
                    "57fbc2bd6859ab0c4a900f5a936249a3" +
                        "4f710c49363a7c4a96134c36a812c4e8",
                ),
            expectedNextSecret =
                hex(
                    "b0c16a7ef577fe20c638354db7ca5c97" +
                        "aa4953a75b2443223e854e2e5a081668",
                ),
            expectedD2dClientKey =
                hex(
                    "dc012fe41bf4d1414318282aee1ad922" +
                        "05fdde7cd20ebcc9c9249d5493b1e238",
                ),
            expectedD2dServerKey =
                hex(
                    "21383b4fab61ada496d32cd72bd3bcde" +
                        "302653b569e5a874134249348a42f1d9",
                ),
            expectedClientEncryptKey =
                hex(
                    "8270749c4000b76f74060f5417577ed7" +
                        "eef1798810beb0583e1fd35e7aa81ad4",
                ),
            expectedClientHmacKey =
                hex(
                    "66324fd288e0aa496aa9265e0f5b46a1" +
                        "bb157889347a11ee767ba829d8745613",
                ),
            expectedServerEncryptKey =
                hex(
                    "faf1c47ac8b37af412816c856681ed33" +
                        "c871345132a3c0252a8b1313e552912f",
                ),
            expectedServerHmacKey =
                hex(
                    "f75d953f97063d53d52ba3b9093604da" +
                        "64fc7cde621dc65779acbaa81fe61d92",
                ),
        )

    /** All D2D-derivation KAT vectors. */
    public val all: List<Vector> = listOf(primary)

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
