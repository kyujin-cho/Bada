/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.crypto.pin

import kotlin.math.abs

/**
 * Derives the 4-digit visual confirmation PIN that Quick Share displays on
 * both peers after the UKEY2 handshake completes.
 *
 * The algorithm is **not** the one specified by UKEY2 itself; it is a
 * Chromium-specific construction that NearDrop ports verbatim in
 * `NearbyConnection.swift:pinCodeFromAuthKey`. Both peers must produce the
 * same digits for the same `authString`, so this implementation is required
 * to be bit-exact with the Swift reference. Reproduced for clarity:
 *
 * ```swift
 * internal static func pinCodeFromAuthKey(_ key: SymmetricKey) -> String {
 *     var hash: Int = 0
 *     var multiplier: Int = 1
 *     let keyBytes: [UInt8] = key.withUnsafeBytes({ return [UInt8]($0) })
 *     for _byte in keyBytes {
 *         let byte = Int(Int8(bitPattern: _byte))
 *         hash = (hash + byte * multiplier) % 9973
 *         multiplier = (multiplier * 31) % 9973
 *     }
 *     return String(format: "%04d", abs(hash))
 * }
 * ```
 *
 * ### Why the implementation is one line of arithmetic but the contract is subtle
 *
 *  1. **Signed bytes.** Swift goes out of its way to reinterpret each `UInt8`
 *     as `Int8` before widening to `Int`. The JVM's `Byte` is already signed
 *     and `Byte.toInt()` sign-extends (`0xFF.toByte().toInt() == -1`), so on
 *     the JVM side `b.toInt()` is sufficient — but only if it goes through
 *     `Byte.toInt()` on a `Byte` value. Going through `Int.and(0xFF)` would
 *     silently produce zero-extended (unsigned) values and break interop on
 *     any peer that ever exchanges an authString with a high bit set. The
 *     test suite pins this down with explicit `0xFF`-byte vectors.
 *
 *  2. **Truncated remainder.** Swift's `%` operator and Kotlin's `%`
 *     operator on `Int` both use **truncated** division (the result carries
 *     the sign of the dividend). Languages that implement floored division
 *     (Python's `%`, Lua's `%`, etc.) would diverge here when `hash` goes
 *     negative. We therefore intentionally do not reach for any
 *     `Math.floorMod` helper.
 *
 *  3. **`abs` on the final hash is bounded.** After the modulo, `hash` is
 *     always in `(-9973, 9973)`, so `kotlin.math.abs(Int)` cannot hit the
 *     `Int.MIN_VALUE` undefined-behaviour edge that bites generic JVM code.
 *
 *  4. **Output is exactly 4 ASCII digits.** Zero-padded with leading zeros.
 *     `"%04d"` in Java/Kotlin matches Swift's format string here because
 *     `abs(hash) <= 9972 < 10000` so the field never overflows past 4
 *     characters.
 *
 *  5. **Pure function — no key material is logged or returned.** Only the
 *     4-digit string escapes this object. Callers are still responsible for
 *     not logging the `authString` argument; this helper has no logging at
 *     all by design.
 *
 *  6. **Empty `authString`.** Defined to return `"0000"`: the loop runs zero
 *     times, `hash` stays `0`, `abs(0) = 0`, formatted as `0000`. We do not
 *     reject empty input — defining the boundary is cheaper than asking
 *     every caller to pre-validate, and matches the Swift reference, which
 *     also returns `"0000"` for an empty key buffer.
 *
 * ### Public API
 *
 * One function: [deriveFourDigitPin]. The companion KAT vectors live in
 * `:core-protocol-test` (`PinVectors`) so the same answer-table can be
 * reused from Android instrumentation tests when the protocol stack lights
 * up later in Phase 1.
 */
public object PinDerivation {
    /**
     * The Chromium PIN modulus. Hard-coded `9973` — the largest prime less
     * than `10000`, picked so that `abs(hash) < 10000` and therefore always
     * fits in 4 decimal digits.
     */
    private const val PIN_MODULUS: Int = 9973

    /**
     * The Chromium PIN multiplier seed. The accumulator `multiplier` is
     * multiplied by `31` (mod [PIN_MODULUS]) on every byte.
     */
    private const val MULTIPLIER_STEP: Int = 31

    /**
     * Number of decimal digits the final PIN is zero-padded to. Constant
     * because the modulus guarantees the result is always 1-4 digits.
     */
    private const val PIN_DIGITS: Int = 4

    /**
     * Derives the 4-digit visual confirmation PIN from a UKEY2 `authString`.
     *
     * Cross-validated against three classes of reference vectors:
     *
     *   * Trivial inputs (empty, single zero byte, single positive byte) —
     *     guards the loop boundary and the zero-padding format.
     *   * Single-byte negative inputs (e.g. `0xFF`) — guards the
     *     sign-extension contract documented above.
     *   * Multi-byte inputs that cause the intermediate `hash` and
     *     `multiplier` to wrap modulo 9973 several times — guards the
     *     truncated-remainder contract.
     *
     * @param authString Raw UKEY2 derived authentication string. Treated as
     *   opaque bytes; never logged. May be empty (returns `"0000"`).
     * @return A 4-character ASCII decimal string, zero-padded on the left.
     *   Always exactly [PIN_DIGITS] characters long.
     */
    public fun deriveFourDigitPin(authString: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (b in authString) {
            // `Byte.toInt()` already sign-extends on the JVM (0xFF -> -1),
            // matching Swift's `Int(Int8(bitPattern: _byte))`. Do not change
            // this to `b.toInt() and 0xFF` — that would zero-extend and
            // break interop with any peer that produces an authString
            // containing bytes >= 0x80.
            val signedByte = b.toInt()
            hash = (hash + signedByte * multiplier) % PIN_MODULUS
            multiplier = (multiplier * MULTIPLIER_STEP) % PIN_MODULUS
        }
        // After the modulo, `hash` is always in (-9973, 9973), so abs(hash)
        // cannot overflow Int.MIN_VALUE (the only undefined case for
        // `kotlin.math.abs(Int)` on the JVM).
        return abs(hash).toString().padStart(PIN_DIGITS, '0')
    }
}
