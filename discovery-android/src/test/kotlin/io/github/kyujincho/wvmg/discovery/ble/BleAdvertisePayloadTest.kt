/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random

/**
 * Bit-exact tests for the Quick Share BLE service-data payload
 * composition (#32, PROTOCOL.md).
 *
 * The wire format is fixed at:
 *
 * ```text
 *   fc 12 8e 01 42 00 00 00 00 00 00 00 00 00 || rand10
 * ```
 *
 * If any of these assertions fail in CI, stock Quick Share / NearDrop
 * receivers will silently ignore our advertisement — these tests are
 * the regression net.
 */
class BleAdvertisePayloadTest {
    @Test
    fun `payload length matches the protocol-mandated 24 bytes`() {
        val payload = BleAdvertisePayload.build(deterministicRandom(seed = 1L))
        assertThat(payload).hasLength(BleAdvertisePayload.PAYLOAD_LEN)
        assertThat(payload).hasLength(24)
    }

    @Test
    fun `prefix is the canonical Quick Share magic`() {
        // Spec: fc 12 8e 01 42 00 00 00 00 00 00 00 00 00 (14 bytes).
        val expected =
            byteArrayOf(
                0xFC.toByte(),
                0x12.toByte(),
                0x8E.toByte(),
                0x01.toByte(),
                0x42.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
            )
        assertThat(BleAdvertisePayload.prefixBytes()).isEqualTo(expected)
    }

    @Test
    fun `prefixBytes returns a defensive copy`() {
        val first = BleAdvertisePayload.prefixBytes()
        first[0] = 0x00
        val second = BleAdvertisePayload.prefixBytes()
        // Mutating the returned array must not poison the canonical magic.
        assertThat(second[0]).isEqualTo(0xFC.toByte())
    }

    @Test
    fun `build copies the prefix verbatim into the first 14 bytes`() {
        val payload = BleAdvertisePayload.build(deterministicRandom(seed = 42L))
        val head = payload.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN)
        assertThat(head).isEqualTo(BleAdvertisePayload.prefixBytes())
    }

    @Test
    fun `build draws exactly 10 bytes of randomness from the supplied source`() {
        // Random.nextBytes is the only entropy call — using a counting
        // wrapper lets us assert on the byte count even with a tiny
        // deterministic seed.
        var calls = 0
        var bytesRead = 0
        val counting =
            object : Random(0L) {
                override fun nextBytes(bytes: ByteArray) {
                    calls++
                    bytesRead += bytes.size
                    super.nextBytes(bytes)
                }
            }
        BleAdvertisePayload.build(counting)
        assertThat(calls).isEqualTo(1)
        assertThat(bytesRead).isEqualTo(BleAdvertisePayload.RANDOM_LEN)
    }

    @Test
    fun `successive build calls produce different randomness tails`() {
        // Pull two payloads from a SecureRandom-equivalent source and
        // assert the random tails differ. A 10-byte collision under a
        // uniform RNG is ~2^-80, well below any flake threshold.
        val first = BleAdvertisePayload.build(Random(1L))
        val second = BleAdvertisePayload.build(Random(2L))
        val firstTail = first.copyOfRange(BleAdvertisePayload.PREFIX_LEN, first.size)
        val secondTail = second.copyOfRange(BleAdvertisePayload.PREFIX_LEN, second.size)
        assertThat(firstTail).isNotEqualTo(secondTail)
    }

    @Test
    fun `buildWith places the supplied tail after the prefix`() {
        val tail = ByteArray(BleAdvertisePayload.RANDOM_LEN) { (0xA0 + it).toByte() }
        val payload = BleAdvertisePayload.buildWith(tail)
        assertThat(payload).hasLength(BleAdvertisePayload.PAYLOAD_LEN)
        assertThat(payload.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN))
            .isEqualTo(BleAdvertisePayload.prefixBytes())
        assertThat(payload.copyOfRange(BleAdvertisePayload.PREFIX_LEN, payload.size))
            .isEqualTo(tail)
    }

    @Test
    fun `buildWith rejects a tail of the wrong length`() {
        // Too short.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(ByteArray(BleAdvertisePayload.RANDOM_LEN - 1))
        }
        // Too long.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(ByteArray(BleAdvertisePayload.RANDOM_LEN + 1))
        }
        // Empty.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(ByteArray(0))
        }
    }

    @Test
    fun `service uuid short value matches PROTOCOL_md`() {
        // 0xFE2C is the 16-bit assigned number Google publishes on the
        // Bluetooth SIG list for Quick Share. Drift here breaks every
        // existing receiver.
        assertThat(BleAdvertisePayload.SERVICE_UUID_SHORT).isEqualTo(0xFE2C)
    }

    @Test
    fun `service uuid 128 form expands the short uuid into the bluetooth base`() {
        // The Bluetooth Base UUID is XXXXXXXX-0000-1000-8000-00805F9B34FB
        // with the 16-bit short UUID slotted into the third / fourth
        // hex octets. Hand-substituting 0xFE2C yields the constant
        // BleAdvertisePayload exposes; this test guards against typos.
        assertThat(BleAdvertisePayload.SERVICE_UUID_128)
            .isEqualTo("0000fe2c-0000-1000-8000-00805f9b34fb")
    }

    private fun deterministicRandom(seed: Long): Random = Random(seed)
}
