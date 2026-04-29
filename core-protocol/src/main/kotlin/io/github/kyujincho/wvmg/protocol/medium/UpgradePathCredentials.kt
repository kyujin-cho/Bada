/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

/**
 * Per-medium credentials carried in a `BANDWIDTH_UPGRADE_NEGOTIATION`
 * `UPGRADE_PATH_AVAILABLE` frame.
 *
 * Each Quick Share medium ships its own bring-up parameters (an SSID +
 * password for Hotspot, a MAC address for Bluetooth RFCOMM, etc.). The
 * proto encodes these as a `oneof` on `UpgradePathInfo`; this sealed
 * interface mirrors that shape in pure Kotlin so [MediumProvider]
 * implementations can hand the framework an opaque value object that
 * the wire-encoder layer translates into the right proto fields without
 * any per-medium framework-side branching.
 *
 * Phase 4 sub-issues #49–#53 each add the Kotlin data class their
 * adapter needs and the matching encoder/decoder hop in
 * [io.github.kyujincho.wvmg.protocol.connection.BandwidthUpgradeFrames].
 * Until then, [Generic] covers tests and any provider that has no
 * extra parameters beyond the medium type itself (e.g. Wi-Fi LAN, where
 * the discovery layer already advertised the IP and port).
 */
public sealed interface UpgradePathCredentials {
    /** The medium these credentials describe. */
    public val medium: Medium

    /**
     * Catch-all credentials carrying nothing beyond the medium type.
     * Useful for tests and for mediums whose bring-up parameters are
     * fully derivable from out-of-band state already known to the peer
     * (e.g. Wi-Fi LAN where the receiver's IP / port were already on
     * the discovery record).
     */
    public data class Generic(
        override val medium: Medium,
    ) : UpgradePathCredentials

    /**
     * Wi-Fi LAN credentials — the receiver-side IP address and port
     * the sender should reconnect to after the upgrade. Present so a
     * future "discover over BLE, transfer over Wi-Fi LAN" path can
     * reuse the framework end-to-end without inventing a side channel.
     *
     * @param ipAddress IPv4 (4 bytes) or IPv6 (16 bytes) network-order
     *   address bytes, matching `UpgradePathInfo.WifiLanSocket.ip_address`.
     * @param port TCP port, matching `UpgradePathInfo.WifiLanSocket.wifi_port`.
     */
    public data class WifiLan(
        val ipAddress: ByteArray,
        val port: Int,
    ) : UpgradePathCredentials {
        override val medium: Medium = Medium.WIFI_LAN

        // ByteArray on a data class needs structural equality wired up
        // explicitly — the auto-generated equals/hashCode would compare
        // by reference, which makes WifiLan a poor map key and breaks
        // unit tests that build two instances from the same source.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WifiLan) return false
            return port == other.port && ipAddress.contentEquals(other.ipAddress)
        }

        override fun hashCode(): Int {
            var result = ipAddress.contentHashCode()
            result = 31 * result + port
            return result
        }
    }

    /**
     * Bluetooth RFCOMM credentials — the receiver-side device MAC and
     * the SDP service-record UUID the sender should connect to after
     * the upgrade (see issue #51).
     *
     * The receiver listens for incoming RFCOMM connections via
     * `BluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid)`.
     * The sender opens an RFCOMM socket via
     * `BluetoothDevice.createInsecureRfcommSocketToServiceRecord(uuid)`
     * after looking the device up by [macAddress] (the peer is already
     * known out-of-band — typically through the BLE pulse layer or a
     * prior in-process pairing — so no fresh BT discovery is required).
     *
     * Pairing is intentionally not required (the **insecure** variant
     * is used) because the application data is already encrypted by
     * UKEY2 + SecureChannel; insisting on RFCOMM-layer pairing would
     * just trigger a redundant OS pairing prompt without adding security
     * the protocol does not already provide. This matches the acceptance
     * criteria on issue #51.
     *
     * @param macAddress 6 raw MAC bytes (network order, MSB first), as
     *   used by the proto's `BluetoothCredentials.mac_address` field.
     *   The Android adapter side rebuilds the colon-separated string via
     *   the formatter in this class (`bytesToMacString`).
     * @param serviceUuid The SDP service-record UUID, formatted as the
     *   canonical 8-4-4-4-12 hex string Android's `UUID.fromString`
     *   accepts. The receiver advertised exactly this UUID via
     *   `listenUsingInsecureRfcommWithServiceRecord`. Carried on the
     *   wire in `BluetoothCredentials.service_name` (Google Nearby
     *   Connections repurposes that proto field as the SDP UUID
     *   identifier — Android's RFCOMM API needs a UUID to look up the
     *   service record so a free-form display name would be useless on
     *   its own).
     */
    public data class Bluetooth(
        val macAddress: ByteArray,
        val serviceUuid: String,
    ) : UpgradePathCredentials {
        init {
            require(macAddress.size == MAC_ADDRESS_BYTES) {
                "Bluetooth MAC address must be exactly $MAC_ADDRESS_BYTES bytes, got ${macAddress.size}"
            }
            require(serviceUuid.isNotEmpty()) { "Bluetooth serviceUuid must not be empty" }
        }

        override val medium: Medium = Medium.BLUETOOTH

        // ByteArray equality: same rationale as WifiLan above. Without
        // this, two Bluetooth credentials decoded from the same wire
        // bytes compare unequal because the auto-generated equals
        // compares MAC arrays by reference.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bluetooth) return false
            return serviceUuid == other.serviceUuid &&
                macAddress.contentEquals(other.macAddress)
        }

        override fun hashCode(): Int {
            var result = macAddress.contentHashCode()
            result = 31 * result + serviceUuid.hashCode()
            return result
        }

        /**
         * Format [macAddress] as the colon-separated uppercase string
         * the Android `BluetoothAdapter.getRemoteDevice(String)` API
         * accepts (e.g. `AA:BB:CC:DD:EE:FF`).
         */
        public fun macAddressString(): String = bytesToMacString(macAddress)

        public companion object {
            /** Standard EUI-48 size in bytes. */
            public const val MAC_ADDRESS_BYTES: Int = 6

            /**
             * Parse a colon- or hyphen-separated MAC address string
             * (e.g. `AA:BB:CC:DD:EE:FF`) into a 6-byte network-order
             * array. Throws [IllegalArgumentException] for any input
             * that does not parse to exactly 6 bytes.
             */
            public fun macStringToBytes(mac: String): ByteArray {
                val cleaned = mac.replace(":", "").replace("-", "")
                require(cleaned.length == MAC_ADDRESS_BYTES * 2) {
                    "MAC address must contain exactly ${MAC_ADDRESS_BYTES * 2} hex digits, got: $mac"
                }
                val bytes = ByteArray(MAC_ADDRESS_BYTES)
                for (i in 0 until MAC_ADDRESS_BYTES) {
                    val hi = Character.digit(cleaned[i * 2], HEX_RADIX)
                    val lo = Character.digit(cleaned[i * 2 + 1], HEX_RADIX)
                    require(hi >= 0 && lo >= 0) {
                        "MAC address contains non-hex digit: $mac"
                    }
                    bytes[i] = ((hi shl HEX_NIBBLE_BITS) or lo).toByte()
                }
                return bytes
            }

            /**
             * Format 6 raw MAC bytes as the canonical
             * `AA:BB:CC:DD:EE:FF` colon-separated uppercase string.
             */
            public fun bytesToMacString(bytes: ByteArray): String {
                require(bytes.size == MAC_ADDRESS_BYTES) {
                    "MAC address must be exactly $MAC_ADDRESS_BYTES bytes, got ${bytes.size}"
                }
                return bytes.joinToString(separator = ":") { byte ->
                    val v = byte.toInt() and BYTE_MASK
                    val hi = HEX_DIGITS[v ushr HEX_NIBBLE_BITS]
                    val lo = HEX_DIGITS[v and HEX_LOW_NIBBLE_MASK]
                    "$hi$lo"
                }
            }

            private const val HEX_RADIX: Int = 16
            private const val HEX_NIBBLE_BITS: Int = 4
            private const val HEX_LOW_NIBBLE_MASK: Int = 0x0F
            private const val BYTE_MASK: Int = 0xFF
            private const val HEX_DIGITS: String = "0123456789ABCDEF"
        }
    }
}
