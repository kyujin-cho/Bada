/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

package io.github.kyujincho.wvmg.discovery

import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.medium.Medium
import java.net.InetAddress

/**
 * Sender-side Quick Share peer model independent of any single discovery
 * surface.
 *
 * A peer can be discovered by one or more mediums at the same time:
 * Wi-Fi LAN mDNS, Bluetooth Classic device-name discovery, and BLE fast
 * advertisements. The sender UI and bootstrap path consume this model
 * instead of directly binding themselves to [DiscoveredService].
 */
public data class NearbyPeer(
    val stableId: String,
    val endpointId: String?,
    val endpointInfo: EndpointInfo?,
    val lanEndpoint: LanEndpoint? = null,
    val bluetoothEndpoint: BluetoothEndpoint? = null,
    val bleAdvertisement: BleAdvertisement? = null,
) {
    /**
     * Discovery / candidate mediums currently known for this peer.
     *
     * BLE here means "observed nearby advertisement", not necessarily a
     * connectable initial-control path.
     */
    public val candidateMediums: Set<Medium>
        get() =
            buildSet {
                if (lanEndpoint != null) add(Medium.WIFI_LAN)
                if (bluetoothEndpoint != null) add(Medium.BLUETOOTH)
                if (bleAdvertisement != null) {
                    add(Medium.BLE)
                    if (bleAdvertisement.l2capPsm != null) add(Medium.BLE_L2CAP)
                }
            }

    /** True when the peer currently exposes at least one bootstrap route. */
    public val isConnectable: Boolean
        get() = preferredRoute() != null

    /**
     * Select the initial-control route.
     *
     * LAN remains first priority to preserve the pre-#137 behaviour when
     * a peer is already reachable by mDNS + TCP. BLE L2CAP is the preferred
     * off-LAN bootstrap path when the receiver advertises a PSM. BLE GATT is
     * the stock off-LAN fallback when the receiver exposes only a GATT-backed
     * advertisement header; Bluetooth Classic remains only a last fallback for
     * peers that still expose it.
     */
    public fun preferredRoute(): NearbyPeerRoute? {
        val lan = lanEndpoint
        val primaryAddress = lan?.primaryAddress()
        if (lan != null && primaryAddress != null) {
            return NearbyPeerRoute.Lan(
                address = primaryAddress,
                port = lan.port,
            )
        }
        val ble = bleAdvertisement
        val bleAddress = ble?.advertiserAddress
        val l2capPsm = ble?.l2capPsm
        if (bleAddress != null && l2capPsm != null) {
            return NearbyPeerRoute.BleL2cap(macAddress = bleAddress, psm = l2capPsm)
        }
        if (bleAddress != null && ble.gattConnectable) {
            return NearbyPeerRoute.BleGatt(macAddress = bleAddress)
        }
        val bluetooth = bluetoothEndpoint
        if (bluetooth != null) {
            return NearbyPeerRoute.BluetoothClassic(bluetooth.macAddress)
        }
        return null
    }

    /** User-facing fallback label shared by the sender UI and tests. */
    public fun displayName(): String {
        val name = endpointInfo?.deviceName.toDisplayNameOrNull()
        if (name != null) return name
        val bleName = bleAdvertisement?.displayName.toDisplayNameOrNull()
        if (bleName != null) return bleName
        if (!endpointId.isNullOrBlank()) return "Quick Share device ($endpointId)"
        if (bleAdvertisement?.gattConnectable == true) return "Quick Share BLE device"
        return stableId
    }

    /** Diagnostic source for the label selected by [displayName]. */
    public fun displayNameSource(): String {
        val endpointName = endpointInfo?.deviceName.toDisplayNameOrNull()
        val bleName = bleAdvertisement?.displayName.toDisplayNameOrNull()
        return when {
            endpointName != null ->
                if (endpointName == bleName) {
                    bleAdvertisement?.displayNameSource ?: "endpoint-info"
                } else {
                    "endpoint-info"
                }
            bleName != null ->
                bleAdvertisement?.displayNameSource ?: "ble-metadata"
            !endpointId.isNullOrBlank() -> "endpoint-id"
            bleAdvertisement?.gattConnectable == true -> "ble-gatt-fallback"
            else -> "stable-id"
        }
    }

    /** Wi-Fi LAN candidate surfaced by mDNS browse. */
    public data class LanEndpoint(
        val instanceNames: Set<String>,
        val addresses: List<InetAddress>,
        val port: Int,
    ) {
        public fun primaryAddress(): InetAddress? =
            addresses.firstOrNull { !it.isLoopbackAddress } ?: addresses.firstOrNull()
    }

    /**
     * Bluetooth Classic discovery candidate surfaced via the Nearby
     * device-name advertisement.
     */
    public data class BluetoothEndpoint(
        val macAddress: String,
        val advertisedName: String,
    )

    /**
     * BLE fast-advertisement candidate. Observation-only today; useful for
     * dedupe/aggregation even when Bluetooth Classic provides the actual
     * initial-control path.
     */
    public data class BleAdvertisement(
        val advertiserAddress: String?,
        val rssi: Int?,
        val l2capPsm: Int?,
        val gattConnectable: Boolean,
        val displayName: String? = null,
        val displayNameSource: String? = null,
    )
}

private fun String?.toDisplayNameOrNull(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Sender-side peer-list events emitted by [NearbyPeerDiscovery]. */
public sealed class NearbyPeerEvent {
    public data class Resolved(
        val peer: NearbyPeer,
    ) : NearbyPeerEvent()

    public data class Lost(
        val stableId: String,
    ) : NearbyPeerEvent()
}

/** Initial-control route chosen from a [NearbyPeer]. */
public sealed interface NearbyPeerRoute {
    public data class Lan(
        val address: InetAddress,
        val port: Int,
    ) : NearbyPeerRoute

    public data class BluetoothClassic(
        val macAddress: String,
    ) : NearbyPeerRoute

    public data class BleL2cap(
        val macAddress: String,
        val psm: Int,
    ) : NearbyPeerRoute

    public data class BleGatt(
        val macAddress: String,
    ) : NearbyPeerRoute
}
