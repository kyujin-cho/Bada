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

    /**
     * Wi-Fi Aware (NAN) credentials — the bring-up parameters that the
     * subscriber (sender) needs in order to request a Wi-Fi Aware data
     * path to the publisher (receiver) after the discovery match.
     *
     * Wi-Fi Aware is one of the higher-throughput Phase 4 mediums when
     * supported by the chipset. Discovery uses a publish/subscribe
     * service-name pairing; the data path is brought up out-of-band by
     * exchanging a passphrase (or PMK) and an IPv6 link-local address +
     * port over the framework's `BANDWIDTH_UPGRADE_NEGOTIATION` channel.
     *
     * @param serviceName ASCII service name the publisher used in
     *   `PublishConfig.setServiceName(...)`. Subscriber must use the
     *   same string when calling `subscribe(...)`.
     * @param port Receiver-side TCP port bound to the Wi-Fi Aware
     *   network interface. The sender connects to this on the IPv6
     *   address obtained from the data-path callback.
     * @param ipv6Address IPv6 address bytes (16 bytes, link-local
     *   `fe80::/10`) of the publisher's Wi-Fi Aware interface. Sent
     *   alongside the port so the subscriber doesn't have to derive it
     *   from the peer handle. Senders typically need to combine this
     *   with the network's scope id (interface index) when constructing
     *   an `Inet6Address`.
     * @param passphrase Out-of-band passphrase used to secure the
     *   Wi-Fi Aware data path (8–63 ASCII chars per
     *   `WifiAwareNetworkSpecifier.Builder.setPskPassphrase`). Must
     *   match on both sides; the receiver generates a fresh one per
     *   upgrade.
     */
    public data class WifiAware(
        val serviceName: String,
        val port: Int,
        val ipv6Address: ByteArray,
        val passphrase: String,
    ) : UpgradePathCredentials {
        override val medium: Medium = Medium.WIFI_AWARE

        // ByteArray equality, same rationale as WifiLan above.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WifiAware) return false
            return serviceName == other.serviceName &&
                port == other.port &&
                passphrase == other.passphrase &&
                ipv6Address.contentEquals(other.ipv6Address)
        }

        override fun hashCode(): Int {
            var result = serviceName.hashCode()
            result = 31 * result + port
            result = 31 * result + ipv6Address.contentHashCode()
            result = 31 * result + passphrase.hashCode()
            return result
        }
    }

    /**
     * Wi-Fi Direct (P2P) bring-up parameters (#49).
     *
     * The receiver becomes a Wi-Fi P2P group owner via
     * `WifiP2pManager.createGroup(...)` and reads the resulting
     * SSID/passphrase/group-owner-IP from `WifiP2pInfo` /
     * `WifiP2pGroup`. The sender consumes these credentials, calls
     * `WifiP2pManager.connect(...)` with a matching network config, and
     * opens a TCP socket to [ipAddress]:[port] once the group has
     * formed.
     *
     * Field shape mirrors `UpgradePathInfo.WifiDirectCredentials` from
     * the vendored proto:
     *
     *   - [ssid] — group-owner SSID, typically `DIRECT-xx-…` (Android
     *     prepends `DIRECT-` automatically).
     *   - [passphrase] — pre-shared key the platform generated for the
     *     group. Treat as ST_ACCOUNT_CREDENTIAL: never log it.
     *   - [ipAddress] — IPv4 group-owner address bytes (4 bytes,
     *     network order). Sent over the wire via the proto's `gateway`
     *     string field; we decode the dotted-quad to bytes here so
     *     consumers do not have to re-parse it.
     *   - [port] — TCP port the receiver is listening on for the new
     *     transport.
     *   - [frequency] — channel frequency in MHz, mirrors
     *     `WifiDirectCredentials.frequency`. -1 (== [FREQUENCY_NOT_SET])
     *     when no hint is available.
     *
     * @param ipAddress IPv4 group-owner address, exactly 4 bytes.
     * @param port TCP port to connect to on the group owner.
     * @param ssid Group-owner SSID announced by the GO (`DIRECT-…`).
     * @param passphrase Pre-shared key for the group. Sensitive.
     * @param frequency Operating channel frequency (MHz), or
     *   [FREQUENCY_NOT_SET].
     */
    public data class WifiDirect(
        val ipAddress: ByteArray,
        val port: Int,
        val ssid: String,
        val passphrase: String,
        val frequency: Int = FREQUENCY_NOT_SET,
    ) : UpgradePathCredentials {
        override val medium: Medium = Medium.WIFI_DIRECT

        init {
            require(ipAddress.size == IPV4_ADDRESS_LENGTH) {
                "Wi-Fi Direct group-owner ipAddress must be 4 bytes (IPv4); got ${ipAddress.size}"
            }
        }

        // ByteArray on a data class needs explicit structural equality —
        // the auto-generated equals/hashCode compares ByteArray by
        // reference, which breaks credential round-trip tests that
        // re-parse the wire bytes.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WifiDirect) return false
            return port == other.port &&
                frequency == other.frequency &&
                ssid == other.ssid &&
                passphrase == other.passphrase &&
                ipAddress.contentEquals(other.ipAddress)
        }

        override fun hashCode(): Int {
            var result = ipAddress.contentHashCode()
            result = 31 * result + port
            result = 31 * result + ssid.hashCode()
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + frequency
            return result
        }

        public companion object {
            /** Length of an IPv4 address in bytes. */
            public const val IPV4_ADDRESS_LENGTH: Int = 4

            /**
             * Sentinel matching the proto's "no frequency hint" value.
             * `WifiDirectCredentials.frequency` has no proto default;
             * `google/nearby` uses -1.
             */
            public const val FREQUENCY_NOT_SET: Int = -1
        }
    }

    /**
     * Wi-Fi local-only hotspot (soft-AP) credentials — the bring-up
     * parameters the receiver needs to associate with the sender's
     * temporary access point and then open a TCP socket on the
     * hotspot subnet.
     *
     * Maps 1:1 onto `UpgradePathInfo.WifiHotspotCredentials`:
     *
     * ```
     * message WifiHotspotCredentials {
     *   optional string ssid = 1;
     *   optional string password = 2;
     *   optional int32 port = 3;
     *   optional string gateway = 4 [default = "0.0.0.0"];
     *   optional int32 frequency = 5 [default = -1];
     * }
     * ```
     *
     * `gateway` doubles as the server IP on the hotspot subnet — the
     * proto carries no separate `ip_address` field for hotspot because
     * Android's `LocalOnlyHotspotReservation` always installs the
     * server-side socket on the gateway IP (typically 192.168.49.1).
     * The peer reaches us at `gateway:port` after associating with
     * `ssid` using `password`.
     *
     * @param ssid Hotspot SSID broadcast by `WifiManager.startLocalOnlyHotspot`.
     * @param passphrase WPA2 passphrase from the same callback.
     * @param port TCP port the server-side ServerSocket bound to on
     *   the hotspot interface.
     * @param gateway Dotted-quad IPv4 address of the hotspot gateway
     *   (server side). Defaults to the proto sentinel `"0.0.0.0"`,
     *   which the receiver must treat as "use the DHCP-supplied
     *   gateway".
     * @param frequencyMhz Operating frequency in MHz (proto sentinel
     *   `-1` = unset). Hint only; the receiver cannot influence the
     *   AP's channel choice.
     */
    public data class WifiHotspot(
        val ssid: String,
        val passphrase: String,
        val port: Int,
        val gateway: String = DEFAULT_GATEWAY,
        val frequencyMhz: Int = FREQUENCY_NOT_SET,
    ) : UpgradePathCredentials {
        override val medium: Medium = Medium.WIFI_HOTSPOT

        public companion object {
            /**
             * Proto default for `WifiHotspotCredentials.gateway`. A
             * receiver that sees this value MUST fall back to the
             * gateway address its DHCP client received after joining
             * the hotspot's SSID.
             */
            public const val DEFAULT_GATEWAY: String = "0.0.0.0"

            /**
             * Proto default for `WifiHotspotCredentials.frequency`
             * meaning "field not set". Matches `google/nearby` and
             * the `STA_FREQUENCY_NOT_SET` sentinel used elsewhere in
             * the bandwidth-upgrade frames.
             */
            public const val FREQUENCY_NOT_SET: Int = -1
        }
    }

    /**
     * BLE L2CAP CoC credentials — receiver-side MAC address plus the
     * PSM (Protocol/Service Multiplexer) that
     * `BluetoothAdapter.listenUsingInsecureL2capChannel().getPsm()`
     * assigned for the listening channel. The sender re-opens the
     * channel via `BluetoothDevice.createInsecureL2capChannel(psm)`.
     *
     * ### Wire encoding caveat
     *
     * `BandwidthUpgradeNegotiationFrame.UpgradePathInfo.Medium` reserves
     * wire number 10 (the BLE_L2CAP slot), so [Medium.BLE_L2CAP] cannot
     * appear directly on `UpgradePathInfo.medium`. To stay strictly
     * within the vendored proto we ride on the `BLUETOOTH` slot:
     *
     *  * `UpgradePathInfo.medium = BLUETOOTH (2)`,
     *  * `bluetooth_credentials.mac_address = "AA:BB:CC:DD:EE:FF"`
     *    (canonical hex), and
     *  * `bluetooth_credentials.service_name = "L2CAP:<psm>"` — a
     *    discriminator the decoder uses to lift the frame back into a
     *    [BleL2cap] (vs the legacy RFCOMM-style [Bluetooth] service
     *    advertisement on the same wire slot).
     *
     * The Android adapter constructs and consumes these credentials,
     * but the wire shape and discriminator stay in `:core-protocol` so
     * the encoder/decoder can be JVM-tested.
     *
     * @param macAddress 6-byte big-endian MAC address bytes for the
     *   receiver-side `BluetoothAdapter`. The encoder formats this as
     *   the canonical colon-delimited hex string the proto expects.
     * @param psm The PSM returned by
     *   `BluetoothServerSocket.getPsm()`. Wire-level 16-bit unsigned
     *   value but Android's API exposes it as `Int`; the decoder
     *   round-trips through [Int].
     */
    public data class BleL2cap(
        val macAddress: ByteArray,
        val psm: Int,
    ) : UpgradePathCredentials {
        init {
            require(macAddress.size == MAC_ADDRESS_LENGTH) {
                "BLE L2CAP MAC address must be 6 bytes, was ${macAddress.size}"
            }
            require(psm in PSM_RANGE) {
                "BLE L2CAP PSM must fit in a uint16, was $psm"
            }
        }

        override val medium: Medium = Medium.BLE_L2CAP

        // ByteArray equality semantics — same rationale as WifiLan.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BleL2cap) return false
            return psm == other.psm && macAddress.contentEquals(other.macAddress)
        }

        override fun hashCode(): Int {
            var result = macAddress.contentHashCode()
            result = 31 * result + psm
            return result
        }

        public companion object {
            /** Length of a Bluetooth MAC address in bytes. */
            public const val MAC_ADDRESS_LENGTH: Int = 6

            /** Valid range for an L2CAP PSM (uint16). */
            public val PSM_RANGE: IntRange = 1..0xFFFF
        }
    }
}
