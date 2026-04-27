/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 key derivation as specified in RFC 5869.
 *
 * Quick Share derives every per-connection key with HKDF-SHA256: the UKEY2
 * `authString`/`nextSecret`, the D2D client/server keys, the SecureMessage
 * encrypt+HMAC keys, and (for the QR-code path) the advertising token plus
 * name encryption key. Bit-exact compatibility with NearDrop's
 * `hkdfExtract`/`hkdfExpand` (`NearbyConnection.swift`) and Google Tink's
 * `Hkdf.computeHkdf("HMACSHA256", ...)` is required.
 *
 * This implementation is built on top of `javax.crypto.Mac("HmacSHA256")`
 * for two reasons:
 *
 *  1. Avoiding Google Tink keeps `:core-protocol` free of the
 *     `com.google.protobuf:protobuf-java` transitive dependency, which would
 *     otherwise collide with `protobuf-javalite` once the protocol code
 *     reaches Android modules.
 *  2. HKDF-SHA256 is short enough (RFC 5869 §2) that hand-rolling it on the
 *     JCE primitive yields fewer dependencies, simpler review, and direct
 *     parity with NearDrop's reference Swift implementation.
 *
 * Correctness is locked down by the RFC 5869 Appendix A test vectors (see
 * `HkdfTest`). Do **not** add debug logging that prints `ikm`, `salt`, or
 * the intermediate PRK — those are key material.
 */
public object Hkdf {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** Output size of SHA-256 in bytes. Equal to RFC 5869's `HashLen`. */
    private const val HASH_LEN = 32

    /**
     * Per RFC 5869 §2.3, `L <= 255 * HashLen`. For SHA-256 that ceiling is
     * 8160 bytes — far above any single Quick Share key derivation, so the
     * limit only ever matters as a misuse guard.
     */
    private const val MAX_OUTPUT_LENGTH = 255 * HASH_LEN

    /**
     * Derives `length` bytes of pseudorandom key material from `ikm` using
     * HKDF-SHA256 (RFC 5869).
     *
     * Both the Extract and Expand stages run unconditionally — even when
     * `salt` is empty — to match the RFC and remain interoperable with
     * Quick Share peers.
     *
     * @param ikm Input keying material. Treated as opaque bytes; never logged.
     * @param salt Optional salt. May be empty; per RFC 5869 §2.2 an empty
     *   salt is replaced internally with `HashLen` zero bytes. Empty is
     *   distinct from "not supplied" only in the API surface; the algorithm
     *   handles both identically.
     * @param info Optional context/application-specific info string. May be
     *   empty.
     * @param length Number of output bytes to produce. Must be in
     *   `1..8160` (RFC 5869 §2.3 cap of `255 * HashLen`).
     * @return A freshly allocated `ByteArray` of exactly `length` bytes.
     * @throws IllegalArgumentException if `length` is outside the valid range.
     */
    public fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length > 0) { "HKDF output length must be positive (got $length)" }
        require(length <= MAX_OUTPUT_LENGTH) {
            "HKDF output length $length exceeds RFC 5869 maximum of $MAX_OUTPUT_LENGTH " +
                "(255 * HashLen) for SHA-256"
        }

        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }

    /**
     * RFC 5869 §2.2 — `HKDF-Extract(salt, IKM) -> PRK`.
     *
     * If `salt` is empty, substitute `HashLen` zero bytes per the RFC. The
     * result is always exactly 32 bytes.
     */
    internal fun extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * RFC 5869 §2.3 — `HKDF-Expand(PRK, info, L) -> OKM`.
     *
     * Iteratively builds `T = T(1) | T(2) | ... | T(N)` where
     * `T(i) = HMAC(PRK, T(i-1) | info | i)` and `T(0)` is empty, then
     * truncates to `length` bytes. The iteration counter is encoded as a
     * single big-endian byte (1..255), matching the RFC.
     */
    internal fun expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val output = ByteArray(length)
        var previousBlock = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()

            val toCopy = minOf(HASH_LEN, length - produced)
            previousBlock.copyInto(output, destinationOffset = produced, endIndex = toCopy)
            produced += toCopy
            counter++
        }
        return output
    }
}
