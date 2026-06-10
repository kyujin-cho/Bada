/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.discovery.NearbyPeer
import dev.bluehouse.bada.discovery.NearbyPeerRoute

internal data class SendBootstrapPlan(
    val action: Action,
    val subtitle: String,
    val failureReason: String?,
    val rejectedCandidates: List<String>,
) {
    val isConnectable: Boolean
        get() = action !is Action.Unavailable

    fun diagnosticSummary(): String =
        buildString {
            append("selected=").append(action.diagnosticLabel)
            if (rejectedCandidates.isNotEmpty()) {
                append(" rejected=[").append(rejectedCandidates.joinToString()).append(']')
            }
            when (action) {
                Action.Unavailable ->
                    failureReason?.let {
                        append(" reason=").append(it.toQuotedLogValue())
                    }

                is Action.Direct -> Unit
            }
        }

    internal sealed interface Action {
        val diagnosticLabel: String

        data class Direct(
            val route: NearbyPeerRoute,
        ) : Action {
            override val diagnosticLabel: String
                get() =
                    when (route) {
                        is NearbyPeerRoute.Lan -> "wifi-lan"
                        is NearbyPeerRoute.BluetoothClassic -> "bluetooth-classic"
                        is NearbyPeerRoute.BleL2cap -> "ble-l2cap"
                        is NearbyPeerRoute.BleGatt -> "ble-gatt"
                    }
        }

        data object Unavailable : Action {
            override val diagnosticLabel: String = "unavailable"
        }
    }

    companion object {
        fun resolve(peer: NearbyPeer): SendBootstrapPlan {
            val rejected = mutableListOf<String>()
            val directRoute = directRoute(peer, rejected)
            val action =
                when {
                    directRoute != null -> Action.Direct(directRoute)
                    else -> Action.Unavailable
                }
            return buildPlan(
                peer = peer,
                action = action,
                rejectedCandidates = rejected,
            )
        }

        /**
         * All connect-attempt-viable routes for [peer] in the same
         * priority order as [resolve], with the highest-priority entry
         * first. Used by the sender's retry-with-fallback loop: the
         * primary attempt uses the first entry; if its TCP /
         * initial-control leg fails before the SecureChannel exists,
         * the loop walks down the list and retries each remaining route
         * until one bootstrap completes the UKEY2 handshake. At that
         * point any subsequent failure is protocol-layer, not a
         * bootstrap-route failure, so we stop falling back.
         *
         * The order mirrors [directRoute]'s `when` chain:
         * Wi-Fi LAN → Bluetooth RFCOMM → BLE-L2CAP → BLE-GATT. RFCOMM
         * leads the off-LAN ladder because stock GMS receivers bootstrap
         * stock senders over it, while their BLE L2CAP/GATT server paths
         * are unreliable (#214). An empty list means no usable route; the
         * caller falls through to the same "no route" terminal that
         * [Action.Unavailable] would render.
         */
        fun viableRoutes(peer: NearbyPeer): List<NearbyPeerRoute> =
            listOfNotNull(
                lanRoute(peer),
                bluetoothRoute(peer),
                bleL2capRoute(peer),
                bleGattRoute(peer),
            )

        /**
         * Build a plan whose `action` targets exactly [route] for the
         * given [peer]. The standard [resolve] entry point picks the
         * top-priority route and returns the corresponding plan; this
         * helper exists so a fallback-retry path can construct a plan
         * around an already-known non-primary route without re-running
         * the priority `when` chain.
         *
         * The `rejectedCandidates` field is left empty here — those
         * are diagnostic strings used by the picker to explain WHY a
         * particular medium was passed over. On a fallback attempt the
         * "rejection" is "the previous route failed at TCP", which is
         * more usefully captured in the outbound diagnostic log than
         * in this per-attempt plan's metadata.
         */
        fun forRoute(
            @Suppress("UNUSED_PARAMETER") peer: NearbyPeer,
            route: NearbyPeerRoute,
        ): SendBootstrapPlan = direct(route, rejectedCandidates = emptyList())

        private fun direct(
            route: NearbyPeerRoute,
            rejectedCandidates: List<String>,
        ): SendBootstrapPlan {
            val subtitle =
                when (route) {
                    is NearbyPeerRoute.Lan -> "Wi-Fi LAN ${route.address.hostAddress}:${route.port}"
                    is NearbyPeerRoute.BluetoothClassic -> "Bluetooth RFCOMM ${route.macAddress}"
                    is NearbyPeerRoute.BleL2cap -> "BLE L2CAP ${route.macAddress} psm=${route.psm}"
                    is NearbyPeerRoute.BleGatt -> "BLE GATT ${route.macAddress}"
                }
            return SendBootstrapPlan(
                action = Action.Direct(route),
                subtitle = subtitle,
                failureReason = null,
                rejectedCandidates = rejectedCandidates,
            )
        }

        private fun directRoute(
            peer: NearbyPeer,
            rejectedCandidates: MutableList<String>,
        ): NearbyPeerRoute? {
            val lanRoute = lanRoute(peer)
            val bluetoothRoute = bluetoothRoute(peer)
            val bleL2capRoute = bleL2capRoute(peer)
            val bleGattRoute = bleGattRoute(peer)
            return when {
                lanRoute != null -> lanRoute
                bluetoothRoute != null -> bluetoothRoute
                bleL2capRoute != null -> {
                    rejectedCandidates += bluetoothClassicRejection(peer)
                    bleL2capRoute
                }

                bleGattRoute != null -> {
                    rejectedCandidates += bluetoothClassicRejection(peer)
                    rejectedCandidates += bleL2capRejection(peer)
                    bleGattRoute
                }

                else -> {
                    rejectedCandidates += bluetoothClassicRejection(peer)
                    rejectedCandidates += bleL2capRejection(peer)
                    rejectedCandidates += bleGattRejection(peer)
                    rejectedCandidates += lanRejection(peer)
                    null
                }
            }
        }

        private fun buildPlan(
            peer: NearbyPeer,
            action: Action,
            rejectedCandidates: List<String>,
        ): SendBootstrapPlan =
            when (action) {
                is Action.Direct ->
                    direct(
                        route = action.route,
                        rejectedCandidates = rejectedCandidates,
                    )

                Action.Unavailable ->
                    SendBootstrapPlan(
                        action = Action.Unavailable,
                        subtitle = "No supported transfer route is available yet.",
                        failureReason = unavailableFailureReason(peer),
                        rejectedCandidates = rejectedCandidates,
                    )
            }

        private fun unavailableFailureReason(peer: NearbyPeer): String? =
            buildList {
                val lan = peer.lanEndpoint
                val lanAddress = lan?.primaryAddress()
                val ble = peer.bleAdvertisement
                val blePsm = ble?.l2capPsm
                if (peer.endpointInfo == null) {
                    add("peer endpoint info could not be parsed")
                }
                if (lan == null || lanAddress == null) {
                    add("no shared Wi-Fi LAN route")
                }
                if (ble != null && blePsm == null) {
                    add("receiver BLE advertisement has no L2CAP PSM")
                }
                if (ble != null && blePsm == null && !ble.gattConnectable) {
                    add("receiver BLE GATT bootstrap is not verified")
                }
            }.distinct().takeIf { it.isNotEmpty() }?.joinToString(separator = "; ")

        private fun lanRoute(peer: NearbyPeer): NearbyPeerRoute.Lan? =
            peer.lanEndpoint?.takeIf { peer.endpointInfo != null }?.let { lan ->
                lan.primaryAddress()?.let { address ->
                    NearbyPeerRoute.Lan(address = address, port = lan.port)
                }
            }

        private fun lanRejection(peer: NearbyPeer): String {
            val lan = peer.lanEndpoint
            val lanAddress = lan?.primaryAddress()
            return when {
                lan == null -> "wifi-lan=missing"
                peer.endpointInfo == null -> "wifi-lan=endpoint-info-unparseable"
                lanAddress == null -> "wifi-lan=no-primary-address"
                else -> "wifi-lan=unusable"
            }
        }

        private fun bleL2capRoute(peer: NearbyPeer): NearbyPeerRoute.BleL2cap? =
            peer.bleAdvertisement?.takeIf { peer.endpointInfo != null }?.let { ble ->
                val address = ble.advertiserAddress
                val psm = ble.l2capPsm
                if (address != null && psm != null) {
                    NearbyPeerRoute.BleL2cap(macAddress = address, psm = psm)
                } else {
                    null
                }
            }

        private fun bleL2capRejection(peer: NearbyPeer): String {
            val ble = peer.bleAdvertisement
            val bleAddress = ble?.advertiserAddress
            val blePsm = ble?.l2capPsm
            return when {
                ble == null -> "ble=missing"
                bleAddress == null -> "ble=no-address"
                peer.endpointInfo == null -> "ble-l2cap=no-endpoint-info"
                blePsm == null -> "ble-l2cap=peer-psm-missing"
                else -> "ble-l2cap=unusable"
            }
        }

        private fun bleGattRoute(peer: NearbyPeer): NearbyPeerRoute.BleGatt? =
            peer.bleAdvertisement?.takeIf { peer.endpointInfo != null }?.let { ble ->
                val address = ble.advertiserAddress
                if (address != null && ble.gattConnectable) {
                    NearbyPeerRoute.BleGatt(macAddress = address)
                } else {
                    null
                }
            }

        private fun bleGattRejection(peer: NearbyPeer): String {
            val ble = peer.bleAdvertisement
            val bleAddress = ble?.advertiserAddress
            val endpointInfo = peer.endpointInfo
            return when {
                ble == null -> "ble-gatt=missing"
                bleAddress == null -> "ble-gatt=no-address"
                endpointInfo == null -> "ble-gatt=no-endpoint-info"
                !ble.gattConnectable -> "ble-gatt=not-verified"
                else -> "ble-gatt=unusable"
            }
        }

        private fun bluetoothRoute(peer: NearbyPeer): NearbyPeerRoute.BluetoothClassic? =
            peer.takeIf { it.endpointInfo != null }?.bluetoothClassicRoute()

        private fun bluetoothClassicRejection(peer: NearbyPeer): String {
            val macKnown =
                peer.publishedBluetoothMac != null ||
                    peer.bluetoothEndpoint != null
            return when {
                !macKnown -> "bluetooth-classic=no-mac"
                peer.endpointInfo == null -> "bluetooth-classic=no-endpoint-info"
                peer.bluetoothClassicRoute() == null -> "bluetooth-classic=disabled"
                else -> "bluetooth-classic=unusable"
            }
        }
    }
}
