/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import java.security.SecureRandom

/**
 * Composes the 24-byte BLE service-data payload that the sender broadcasts
 * to wake nearby Quick Share receivers (#32).
 *
 * Wire format (per
 * [PROTOCOL.md](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md)):
 *
 * ```text
 *   fc 12 8e 01 42 00 00 00 00 00 00 00 00 00 || rand10
 *   |---------------- 14 fixed bytes ----------------|  |--10--|
 * ```
 *
 * The 14-byte prefix is a literal Quick Share magic. The trailing 10 bytes
 * are session-scoped randomness — receivers use them to debounce
 * advertisements from a single sender across short BLE airtime windows.
 *
 * This object is pure-JVM (no `android.*` imports) so it is exhaustively
 * unit-testable. The Android-side advertiser ([BleAdvertiser]) consumes it
 * and hands the resulting bytes to `BluetoothLeAdvertiser`.
 */
public object BleAdvertisePayload {
    /**
     * Quick Share BLE service UUID as a 16-bit short UUID. Stock Quick
     * Share, NearDrop, and Windows Quick Share all use this assigned
     * number. Wire form on Bluetooth is `0xFE2C`; the 128-bit canonical
     * form (the UUID receivers compare against in their scan filters) is
     * derived by substituting it into the Bluetooth Base UUID:
     * `0000XXXX-0000-1000-8000-00805F9B34FB`.
     */
    public const val SERVICE_UUID_SHORT: Int = 0xFE2C

    /**
     * Canonical 128-bit form of [SERVICE_UUID_SHORT]. Pre-computed so
     * callers don't have to know the Bluetooth Base UUID format and so
     * tests can assert against an explicit string.
     */
    public const val SERVICE_UUID_128: String = "0000fe2c-0000-1000-8000-00805f9b34fb"

    /**
     * Total size of the service-data payload in bytes (14 fixed prefix +
     * 10 random suffix). This must fit alongside the service-UUID field
     * inside the legacy 31-byte advertising-PDU budget.
     */
    public const val PAYLOAD_LEN: Int = 24

    /** Length of the randomness tail. */
    public const val RANDOM_LEN: Int = 10

    /**
     * Length of the fixed Quick Share prefix that precedes the randomness.
     */
    public const val PREFIX_LEN: Int = PAYLOAD_LEN - RANDOM_LEN // 14

    /**
     * The 14-byte literal prefix mandated by the protocol. Exposed as a
     * defensive copy via [prefixBytes] so callers cannot mutate the
     * canonical bytes.
     */
    private val PREFIX: ByteArray =
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

    /** Defensive copy of the canonical 14-byte prefix. */
    public fun prefixBytes(): ByteArray = PREFIX.copyOf()

    /**
     * Build a fresh 24-byte payload. The prefix is the canonical Quick
     * Share magic; the trailing 10 bytes are drawn from [random].
     *
     * @param random source of the 10 trailing bytes. Defaults to a fresh
     *   [SecureRandom]; tests may inject a deterministic [java.util.Random]
     *   subclass to assert against fixed bytes.
     */
    @JvmOverloads
    public fun build(random: java.util.Random = SecureRandom()): ByteArray {
        val out = ByteArray(PAYLOAD_LEN)
        System.arraycopy(PREFIX, 0, out, 0, PREFIX_LEN)
        val tail = ByteArray(RANDOM_LEN)
        random.nextBytes(tail)
        System.arraycopy(tail, 0, out, PREFIX_LEN, RANDOM_LEN)
        return out
    }

    /**
     * Build a payload with the supplied 10-byte randomness tail. Useful
     * in tests and in scenarios where the caller needs the same random
     * suffix to be reused (e.g. when re-emitting after a Bluetooth restart
     * within the same share session).
     *
     * @throws IllegalArgumentException if [randomTail] is not exactly
     *   [RANDOM_LEN] bytes long.
     */
    public fun buildWith(randomTail: ByteArray): ByteArray {
        require(randomTail.size == RANDOM_LEN) {
            "randomTail must be exactly $RANDOM_LEN bytes, got ${randomTail.size}"
        }
        val out = ByteArray(PAYLOAD_LEN)
        System.arraycopy(PREFIX, 0, out, 0, PREFIX_LEN)
        System.arraycopy(randomTail, 0, out, PREFIX_LEN, RANDOM_LEN)
        return out
    }
}
