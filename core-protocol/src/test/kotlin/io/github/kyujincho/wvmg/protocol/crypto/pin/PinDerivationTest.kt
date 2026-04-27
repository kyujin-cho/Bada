/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.pin

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.test.PinVectors
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Bit-exact correctness tests for [PinDerivation] against the KAT vectors
 * in `:core-protocol-test` (`PinVectors`). The vectors are computed from a
 * faithful reproduction of the Swift reference algorithm and chosen to lock
 * down each subtle property of the implementation:
 *
 *   * boundary cases (empty input, single byte),
 *   * **signed-byte interpretation** (`0xFF` -> `-1`),
 *   * **truncated-remainder** modulo behaviour over many iterations,
 *   * **zero-padded 4-digit ASCII** output format.
 */
class PinDerivationTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    fun `KAT vector matches NearDrop reference`(vector: PinVectors.Vector) {
        val actual = PinDerivation.deriveFourDigitPin(vector.authString)
        assertThat(actual).isEqualTo(vector.expectedPin)
    }

    @Test
    fun `output is always exactly 4 ASCII digits`() {
        for (vector in PinVectors.all) {
            val pin = PinDerivation.deriveFourDigitPin(vector.authString)
            assertThat(pin).hasLength(4)
            assertThat(pin.all { it in '0'..'9' }).isTrue()
        }
    }

    @Test
    fun `empty authString returns 0000`() {
        // Documented boundary: the loop runs zero times and the formatter
        // pads `abs(0)` to four digits.
        assertThat(PinDerivation.deriveFourDigitPin(ByteArray(0))).isEqualTo("0000")
    }

    @Test
    fun `single 0xFF byte produces 0001 — sign-extension guard`() {
        // This is the load-bearing test for the signed-byte contract. A
        // buggy port that zero-extends (`b.toInt() and 0xFF`) would return
        // "0255" here. With the correct `Byte.toInt()` sign extension,
        // `0xFF` widens to `-1` and `abs(-1)` formats to "0001".
        assertThat(PinDerivation.deriveFourDigitPin(byteArrayOf(0xFF.toByte())))
            .isEqualTo("0001")
    }

    @Test
    fun `result is deterministic — same input twice gives same PIN`() {
        // Pure-function property: no hidden state, no RNG involvement.
        val input = ByteArray(64) { (it * 7 - 3).toByte() }
        val first = PinDerivation.deriveFourDigitPin(input)
        val second = PinDerivation.deriveFourDigitPin(input)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `does not mutate the input authString`() {
        // Defence in depth: callers may pass a buffer they intend to wipe
        // later. The function must read but not write.
        val input = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        val snapshot = input.copyOf()
        PinDerivation.deriveFourDigitPin(input)
        assertThat(input).isEqualTo(snapshot)
    }

    companion object {
        @JvmStatic
        fun vectors(): List<PinVectors.Vector> = PinVectors.all
    }
}
