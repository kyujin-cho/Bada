/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.test

/**
 * Known-Answer Test (KAT) vectors for the Quick Share 4-digit PIN derivation
 * defined in `NearbyConnection.swift:pinCodeFromAuthKey`.
 *
 * These vectors are computed from a faithful reproduction of the Swift
 * algorithm and cross-checked against the NearDrop reference source at
 * https://github.com/grishka/NearDrop/blob/master/NearbyShare/NearbyConnection.swift.
 *
 * The vectors are grouped to lock down three things at once:
 *
 *  1. **Boundary cases** — empty input, all-zero input, the single-byte
 *     baseline. These keep the loop boundary and `"%04d"` zero-padding
 *     honest.
 *  2. **Sign extension** — at least one vector contains a byte with the
 *     high bit set (`0xFF`). The Swift reference uses
 *     `Int(Int8(bitPattern: _byte))`, which produces `-1` for `0xFF`. The
 *     equivalent on the JVM is `Byte.toInt()` (already sign-extending). A
 *     buggy port that uses `b.toInt() and 0xFF` would produce a different
 *     PIN here, and this vector is what catches that regression.
 *  3. **Modulo wrap-around** — vectors long enough (32 bytes) that the
 *     intermediate `hash` and `multiplier` wrap past the 9973 modulus
 *     several times, exercising the truncated-remainder contract that
 *     differs between Swift/Kotlin/Java (sign-of-dividend) and
 *     Python/Lua (floored).
 *
 * The KAT vectors live in `:core-protocol-test` so the same answer-table can
 * be reused from Android instrumentation tests when the protocol stack
 * lights up later in Phase 1.
 */
public object PinVectors {
    /**
     * One PIN derivation KAT vector.
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
        public val authString: ByteArray,
        public val expectedPin: String,
    ) {
        init {
            require(expectedPin.length == 4) { "Expected PIN must be exactly 4 chars: '$expectedPin'" }
            require(expectedPin.all { it in '0'..'9' }) {
                "Expected PIN must contain only ASCII digits: '$expectedPin'"
            }
        }

        override fun toString(): String = name
    }

    /**
     * Empty input — boundary case for the loop. `hash` stays `0`, `abs(0)`
     * is `0`, padded to `"0000"`. Documents that the implementation does
     * not reject empty input.
     */
    public val empty: Vector =
        Vector(
            name = "empty authString",
            authString = ByteArray(0),
            expectedPin = "0000",
        )

    /**
     * Single zero byte — same expected PIN as the empty case but exercises
     * one full pass through the loop body.
     */
    public val singleZeroByte: Vector =
        Vector(
            name = "single 0x00 byte",
            authString = byteArrayOf(0x00),
            expectedPin = "0000",
        )

    /**
     * Single positive byte. Verifies the trivial path:
     * `hash = (0 + 1 * 1) % 9973 = 1`, formatted as `"0001"`.
     */
    public val singlePositiveByte: Vector =
        Vector(
            name = "single 0x01 byte",
            authString = byteArrayOf(0x01),
            expectedPin = "0001",
        )

    /**
     * Single byte with the high bit set (`0xFF`).
     *
     * Locks down the sign-extension contract: with correct `Byte.toInt()`
     * sign extension, `signedByte = -1`, so
     * `hash = (0 + (-1) * 1) % 9973 = -1`, and `abs(-1) = 1` formats to
     * `"0001"`. A buggy unsigned port (`b.toInt() and 0xFF` -> 255) would
     * produce `"0255"` instead, which this vector deliberately rules out.
     */
    public val singleHighBitByte: Vector =
        Vector(
            name = "single 0xFF byte (sign-extension guard)",
            authString = byteArrayOf(0xFF.toByte()),
            expectedPin = "0001",
        )

    /**
     * Two-byte vector, both high-bit-set. Exercises sign extension across
     * more than one loop iteration:
     *
     *   * iter 0: hash = (0 + (-1)*1) % 9973 = -1; multiplier = 31
     *   * iter 1: hash = (-1 + (-1)*31) % 9973 = -32
     *
     * `abs(-32)` formats to `"0032"`.
     */
    public val twoHighBitBytes: Vector =
        Vector(
            name = "two 0xFF bytes (sign-extension across iterations)",
            authString = byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            expectedPin = "0032",
        )

    /**
     * Two-byte positive vector. Exercises the multiplier update between
     * iterations: `hash = (1 + 2*31) % 9973 = 63`, formatted as `"0063"`.
     */
    public val twoSmallPositiveBytes: Vector =
        Vector(
            name = "0x01 0x02 (multiplier step)",
            authString = byteArrayOf(0x01, 0x02),
            expectedPin = "0063",
        )

    /**
     * 32 bytes of `0x7F` (the largest positive signed-int8 value, `+127`).
     * Long enough that the intermediate `hash` and `multiplier` both
     * exceed the modulus several times, locking down the truncated-mod
     * semantics.
     */
    public val thirtyTwoMaxPositiveBytes: Vector =
        Vector(
            name = "32 bytes of 0x7F (multi-wrap, all positive)",
            authString = ByteArray(32) { 0x7F.toByte() },
            expectedPin = "8857",
        )

    /**
     * 32 bytes of `0xFF` (the most-negative signed-int8 value of `-1`
     * across many iterations). Combines sign extension with multi-wrap
     * modulo behaviour.
     */
    public val thirtyTwoHighBitBytes: Vector =
        Vector(
            name = "32 bytes of 0xFF (multi-wrap, all negative)",
            authString = ByteArray(32) { 0xFF.toByte() },
            expectedPin = "6509",
        )

    /**
     * 32 bytes spanning `0x00..0x1F` — strictly increasing, all positive,
     * gives a hash trajectory unlike the constant-byte vectors above.
     */
    public val incrementingBytes: Vector =
        Vector(
            name = "0x00..0x1F (32 incrementing positive bytes)",
            authString = ByteArray(32) { it.toByte() },
            expectedPin = "5095",
        )

    /**
     * 32 bytes alternating `0x80, 0x7F` (most-negative, most-positive).
     * Forces the running `hash` to swing wide across the modulus in both
     * directions, catching off-by-one errors in either the sign-extension
     * or the truncated-mod path.
     */
    public val alternatingExtremeBytes: Vector =
        Vector(
            name = "0x80,0x7F repeating x16 (alternating extremes)",
            authString = ByteArray(32) { if (it % 2 == 0) 0x80.toByte() else 0x7F.toByte() },
            expectedPin = "9035",
        )

    /** All PIN derivation KAT vectors in declaration order. */
    public val all: List<Vector> =
        listOf(
            empty,
            singleZeroByte,
            singlePositiveByte,
            singleHighBitByte,
            twoHighBitBytes,
            twoSmallPositiveBytes,
            thirtyTwoMaxPositiveBytes,
            thirtyTwoHighBitBytes,
            incrementingBytes,
            alternatingExtremeBytes,
        )
}
