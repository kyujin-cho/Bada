/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.ble

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Composes the BLE service-data payload that the sender broadcasts on the
 * Quick Share `0xFE2C` FastInitiation service UUID to wake nearby
 * receivers (#32).
 *
 * Wire format (per `google/nearby`'s
 * `sharing/internal/api/fast_init_ble_beacon.h` and Chromium's
 * `chrome/browser/nearby_sharing/fast_initiation/fast_initiation_advertiser.cc`):
 *
 * ```text
 *   fc 12 8e VV PP UU AA AA AA AA AA AA AA AA SS HH HH HH HH HH HH HH HH
 *   |---- model id ---|        |--- uwb addr (8) ---| salt |---- secret_id_hash ----|
 *                  metadata  uwb_meta
 *                  byte 3
 * ```
 *
 * - `fc 12 8e` — `kFastInitModelId`, the magic bytes for Quick Share.
 * - `VV` (byte 3) — packed metadata. Bitfield layout (high → low):
 *   `version (3 bits) << 5 | type (3 bits) << 2 | uwb_supported (1 bit) << 1
 *   | sender_cert_supported (1 bit)`.
 *   Stock GMS senders that have an active share intent emit `version=0`,
 *   `type=kNotify=0`, `uwb=0`, `sender_cert=0` → byte = `0x00`. Earlier
 *   NearDrop captures (and prior versions of this file) had `0x01`, which
 *   sets the deprecated/reinterpreted `sender_cert_supported` bit and
 *   causes Samsung One UI to mis-classify the pulse as `type=SILENT` (see
 *   `Detected fast init state changed: type=SILENT` in Galaxy logcat)
 *   instead of an active share pulse. Fix: emit `0x00`.
 * - `PP` (byte 4) — `metadata[1]`, the unsigned negation of the adjusted Tx
 *   power. `kAdjustedTxPower = -66 dBm` so the wire byte is `-(-66) = 66 = 0x42`.
 * - `UU` (byte 5) — `uwb_metadata`. Zero when we do not advertise UWB.
 * - `AA × 8` (bytes 6..13) — `uwb_address`. Zero unless UWB is in play.
 * - `SS` (byte 14) — per-share-session salt so receivers can dedupe pulses.
 * - `HH × 8` (bytes 15..22) — `secret_id_hash`. Truncated SHA-256 over the
 *   sender's `endpointId`. Samsung GMS treats an all-zero hash as "no real
 *   share intent" and demotes the pulse to `type=SILENT` regardless of the
 *   metadata-byte type bits. A stable non-zero hash is therefore necessary
 *   to reach active `type=NOTIFY` classification. Samsung may still log
 *   `BluetoothGattException: No handler registered for characteristic …`
 *   from one wildcard GATT callback while the real Weave handler processes
 *   the same writes, so that log alone is not evidence that this hash was
 *   rejected.
 *
 * Total payload = 14 fixed bytes + 1 salt + 8 hash = 23 bytes, comfortably
 * inside the legacy 31-byte advertising-PDU budget alongside the 16-bit
 * service-data AD wrapper.
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
     * Length of the 14-byte fixed prefix (model id + metadata + uwb stub).
     * The first 14 bytes are constant for every share session; only the
     * trailing 9 bytes (1 salt + 8 hash) carry per-session entropy.
     */
    public const val PREFIX_LEN: Int = 14

    /** Length of the per-share-session salt byte. */
    public const val SALT_LEN: Int = 1

    /** Length of the truncated SHA-256 secret-id-hash. */
    public const val SECRET_ID_HASH_LEN: Int = 8

    /** Total dynamic tail = salt + hash = 9 bytes. */
    public const val DYNAMIC_TAIL_LEN: Int = SALT_LEN + SECRET_ID_HASH_LEN

    /** Total service-data payload size = 14 fixed + 9 dynamic = 23 bytes. */
    public const val PAYLOAD_LEN: Int = PREFIX_LEN + DYNAMIC_TAIL_LEN

    /**
     * The 14-byte literal prefix mandated by the protocol. Exposed as a
     * defensive copy via [prefixBytes] so callers cannot mutate the
     * canonical bytes.
     *
     * Byte 3 = `0x00` packs `version=0, type=kNotify (0), uwb=0,
     * sender_cert=0`. **Do not change to `0x01`** — see the kdoc above for
     * the Samsung-One-UI-classifies-as-SILENT regression that introduces.
     */
    private val PREFIX: ByteArray =
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

    /** Defensive copy of the canonical 14-byte prefix. */
    public fun prefixBytes(): ByteArray = PREFIX.copyOf()

    /**
     * Build a fresh 23-byte payload bound to [endpointId]. The trailing
     * 8-byte `secret_id_hash` is the truncated SHA-256 of `endpointId`'s
     * UTF-8 bytes. Any non-zero hash is enough to avoid Samsung's
     * all-zero-hash `type=SILENT` demotion. The single salt byte preceding
     * the hash is drawn from [random] and lets receivers dedupe rapid
     * back-to-back broadcasts within the same share session.
     *
     * @param endpointId the sender's 4-byte ASCII slug (the same value
     *   that appears in the OfflineFrame `ConnectionRequestFrame.endpoint_id`
     *   and in our mDNS instance-name slug). Must be non-empty.
     * @param random source of the salt byte. Defaults to a fresh
     *   [SecureRandom]; tests inject deterministic [java.util.Random].
     */
    @JvmOverloads
    public fun build(
        endpointId: String,
        random: java.util.Random = SecureRandom(),
    ): ByteArray {
        require(endpointId.isNotEmpty()) { "endpointId must not be empty" }
        val out = ByteArray(PAYLOAD_LEN)
        System.arraycopy(PREFIX, 0, out, 0, PREFIX_LEN)
        val saltBuf = ByteArray(SALT_LEN)
        random.nextBytes(saltBuf)
        out[PREFIX_LEN] = saltBuf[0]
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(endpointId.toByteArray(Charsets.UTF_8))
                .copyOf(SECRET_ID_HASH_LEN)
        System.arraycopy(hash, 0, out, PREFIX_LEN + SALT_LEN, SECRET_ID_HASH_LEN)
        return out
    }

    /**
     * Build a payload with the supplied salt byte and `secret_id_hash`.
     * Useful in tests and in scenarios where the caller needs reproducible
     * bytes (e.g. when re-emitting after a Bluetooth restart within the
     * same share session and the salt should stay stable).
     *
     * @throws IllegalArgumentException if [secretIdHash] is not exactly
     *   [SECRET_ID_HASH_LEN] bytes long.
     */
    public fun buildWith(
        salt: Byte,
        secretIdHash: ByteArray,
    ): ByteArray {
        require(secretIdHash.size == SECRET_ID_HASH_LEN) {
            "secretIdHash must be exactly $SECRET_ID_HASH_LEN bytes, got ${secretIdHash.size}"
        }
        val out = ByteArray(PAYLOAD_LEN)
        System.arraycopy(PREFIX, 0, out, 0, PREFIX_LEN)
        out[PREFIX_LEN] = salt
        System.arraycopy(secretIdHash, 0, out, PREFIX_LEN + SALT_LEN, SECRET_ID_HASH_LEN)
        return out
    }
}
