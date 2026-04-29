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
}
