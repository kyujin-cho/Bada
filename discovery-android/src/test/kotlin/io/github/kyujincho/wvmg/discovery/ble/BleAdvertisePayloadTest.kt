/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Random

/**
 * Bit-exact tests for the Quick Share BLE FastInitiation service-data
 * payload (#32, #145).
 *
 * The wire format is fixed at:
 *
 * ```text
 *   fc 12 8e 00 42 00 00 00 00 00 00 00 00 00 || salt(1) || hash(8)
 *   |---------- 14-byte fixed prefix ----------|   per-session salt + secret_id_hash
 * ```
 *
 * Byte 3 = `0x00` packs `version=0, type=kNotify (0), uwb=0,
 * sender_cert=0`. **Drift here breaks Samsung One UI**: a non-zero byte 3
 * (e.g. the legacy `0x01` we shipped before fixing #145) is interpreted
 * by stock GMS as `type=SILENT`, which causes the receiver to skip
 * registering its per-peer Weave handler — every subsequent ATT write to
 * `00000100-…-0101` then throws
 * `BluetoothGattException: No handler registered for characteristic …`
 * and the BLE GATT bootstrap stalls at the Weave handshake.
 *
 * The trailing 8-byte `secret_id_hash` (truncated SHA-256 of the
 * sender's `endpointId`) is the second half of the same gate: an
 * all-zero hash also demotes the pulse to `type=SILENT` regardless of
 * byte 3. These tests are the regression net for both fields.
 */
class BleAdvertisePayloadTest {
    private val sampleEndpointId = "abcd"

    @Test
    fun `payload length matches the protocol-mandated 23 bytes`() {
        val payload = BleAdvertisePayload.build(sampleEndpointId, deterministicRandom(seed = 1L))
        assertThat(payload).hasLength(BleAdvertisePayload.PAYLOAD_LEN)
        assertThat(payload).hasLength(23)
    }

    @Test
    fun `payload structural lengths are 14 + 1 + 8`() {
        // Defensive: surface-area drift in the constants is the cheapest
        // place to catch a bad refactor before it ships.
        assertThat(BleAdvertisePayload.PREFIX_LEN).isEqualTo(14)
        assertThat(BleAdvertisePayload.SALT_LEN).isEqualTo(1)
        assertThat(BleAdvertisePayload.SECRET_ID_HASH_LEN).isEqualTo(8)
        assertThat(BleAdvertisePayload.DYNAMIC_TAIL_LEN).isEqualTo(9)
        assertThat(BleAdvertisePayload.PAYLOAD_LEN).isEqualTo(
            BleAdvertisePayload.PREFIX_LEN + BleAdvertisePayload.DYNAMIC_TAIL_LEN,
        )
    }

    @Test
    fun `prefix is the canonical Quick Share FastInitiation magic`() {
        // Spec: fc 12 8e 00 42 00 00 00 00 00 00 00 00 00.
        // Byte 3 = 0x00 packs version=0, type=kNotify, no flags.
        // Empirically: 0x00 → Galaxy classifies as NOTIFY; 0x01 → SILENT;
        // 0x20 (version=1) → Galaxy stops detecting the pulse entirely.
        val expected =
            byteArrayOf(
                0xFC.toByte(),
                0x12.toByte(),
                0x8E.toByte(),
                0x00.toByte(),
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
    fun `metadata byte three is zero for kNotify classification`() {
        assertThat(BleAdvertisePayload.prefixBytes()[3]).isEqualTo(0x00.toByte())
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
        val payload = BleAdvertisePayload.build(sampleEndpointId, deterministicRandom(seed = 42L))
        val head = payload.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN)
        assertThat(head).isEqualTo(BleAdvertisePayload.prefixBytes())
    }

    @Test
    fun `build draws exactly one byte of salt entropy from the supplied source`() {
        // Random.nextBytes is the only entropy call into the supplied
        // [Random]. SHA-256 over the endpointId is deterministic so the
        // entropy budget is just the salt byte.
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
        BleAdvertisePayload.build(sampleEndpointId, counting)
        assertThat(calls).isEqualTo(1)
        assertThat(bytesRead).isEqualTo(BleAdvertisePayload.SALT_LEN)
    }

    @Test
    fun `secret id hash is the truncated sha-256 of the endpoint id`() {
        // Pin the hash derivation so a refactor that changes the input
        // encoding (e.g. drops UTF-8) is caught immediately.
        val payload = BleAdvertisePayload.build(sampleEndpointId, deterministicRandom(seed = 7L))
        val hashStart = BleAdvertisePayload.PREFIX_LEN + BleAdvertisePayload.SALT_LEN
        val actualHash = payload.copyOfRange(hashStart, hashStart + BleAdvertisePayload.SECRET_ID_HASH_LEN)
        val expectedHash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(sampleEndpointId.toByteArray(Charsets.UTF_8))
                .copyOf(BleAdvertisePayload.SECRET_ID_HASH_LEN)
        assertThat(actualHash).isEqualTo(expectedHash)
    }

    @Test
    fun `secret id hash is non-zero for any non-empty endpoint id`() {
        // The all-zero secret_id_hash failure mode is the exact signal
        // Samsung uses to demote the pulse to type=SILENT. Lock in that
        // every realistic endpointId produces a non-zero hash.
        val payload = BleAdvertisePayload.build(sampleEndpointId, deterministicRandom(seed = 0L))
        val hashStart = BleAdvertisePayload.PREFIX_LEN + BleAdvertisePayload.SALT_LEN
        val hash = payload.copyOfRange(hashStart, hashStart + BleAdvertisePayload.SECRET_ID_HASH_LEN)
        assertThat(hash.any { it != 0.toByte() }).isTrue()
    }

    @Test
    fun `build with the same endpoint id and different salts differs only in the salt byte`() {
        val first = BleAdvertisePayload.build(sampleEndpointId, Random(1L))
        val second = BleAdvertisePayload.build(sampleEndpointId, Random(2L))
        // Prefix and hash are identical (same endpointId, deterministic SHA-256).
        assertThat(first.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN))
            .isEqualTo(second.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN))
        val hashStart = BleAdvertisePayload.PREFIX_LEN + BleAdvertisePayload.SALT_LEN
        assertThat(first.copyOfRange(hashStart, hashStart + BleAdvertisePayload.SECRET_ID_HASH_LEN))
            .isEqualTo(second.copyOfRange(hashStart, hashStart + BleAdvertisePayload.SECRET_ID_HASH_LEN))
        // Salt byte differs (1-byte collision under a uniform RNG is 1/256;
        // the deterministic seeds we picked yield distinct salt bytes).
        assertThat(first[BleAdvertisePayload.PREFIX_LEN])
            .isNotEqualTo(second[BleAdvertisePayload.PREFIX_LEN])
    }

    @Test
    fun `build rejects an empty endpoint id`() {
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.build("", deterministicRandom(seed = 0L))
        }
    }

    @Test
    fun `buildWith places salt at byte 14 and hash at bytes 15-22`() {
        val hash = ByteArray(BleAdvertisePayload.SECRET_ID_HASH_LEN) { (0xA0 + it).toByte() }
        val payload = BleAdvertisePayload.buildWith(salt = 0x77.toByte(), secretIdHash = hash)
        assertThat(payload).hasLength(BleAdvertisePayload.PAYLOAD_LEN)
        assertThat(payload.copyOfRange(0, BleAdvertisePayload.PREFIX_LEN))
            .isEqualTo(BleAdvertisePayload.prefixBytes())
        assertThat(payload[BleAdvertisePayload.PREFIX_LEN]).isEqualTo(0x77.toByte())
        val hashStart = BleAdvertisePayload.PREFIX_LEN + BleAdvertisePayload.SALT_LEN
        assertThat(payload.copyOfRange(hashStart, hashStart + BleAdvertisePayload.SECRET_ID_HASH_LEN))
            .isEqualTo(hash)
    }

    @Test
    fun `buildWith rejects a hash of the wrong length`() {
        // Too short.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(0x00, ByteArray(BleAdvertisePayload.SECRET_ID_HASH_LEN - 1))
        }
        // Too long.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(0x00, ByteArray(BleAdvertisePayload.SECRET_ID_HASH_LEN + 1))
        }
        // Empty.
        assertThrows<IllegalArgumentException> {
            BleAdvertisePayload.buildWith(0x00, ByteArray(0))
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
