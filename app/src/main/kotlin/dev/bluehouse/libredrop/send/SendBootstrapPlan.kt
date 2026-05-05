/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

import dev.bluehouse.libredrop.discovery.NearbyPeer
import dev.bluehouse.libredrop.discovery.NearbyPeerRoute

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

        private fun direct(
            route: NearbyPeerRoute,
            rejectedCandidates: List<String>,
        ): SendBootstrapPlan {
            val subtitle =
                when (route) {
                    is NearbyPeerRoute.Lan -> "Wi-Fi LAN ${route.address.hostAddress}:${route.port}"
                    is NearbyPeerRoute.BluetoothClassic -> "Bluetooth Classic ${route.macAddress}"
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
            val bleL2capRoute = bleL2capRoute(peer)
            val bleGattRoute = bleGattRoute(peer)
            val bluetoothRoute = bluetoothRoute(peer)
            return when {
                lanRoute != null -> lanRoute
                bleL2capRoute != null -> {
                    rejectedCandidates += lanRejection(peer)
                    bleL2capRoute
                }

                bleGattRoute != null -> {
                    rejectedCandidates += lanRejection(peer)
                    rejectedCandidates += bleL2capRejection(peer)
                    bleGattRoute
                }

                bluetoothRoute != null -> {
                    rejectedCandidates += lanRejection(peer)
                    rejectedCandidates += bleL2capRejection(peer)
                    rejectedCandidates += bleGattRejection(peer)
                    bluetoothRoute
                }

                else -> {
                    rejectedCandidates += lanRejection(peer)
                    rejectedCandidates += bleL2capRejection(peer)
                    rejectedCandidates += bleGattRejection(peer)
                    rejectedCandidates += "bluetooth-classic=missing"
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
                if (lan == null || lanAddress == null) {
                    add("no shared Wi-Fi LAN route")
                }
                if (ble != null && blePsm == null) {
                    add("receiver BLE advertisement has no L2CAP PSM")
                }
                if (ble != null && blePsm == null && !ble.gattConnectable) {
                    add("receiver BLE GATT bootstrap is not verified")
                }
                if (ble != null) {
                    add("no Bluetooth Classic bootstrap identity")
                }
                if (ble != null && peer.endpointInfo?.hidden != false) {
                    add("BLE observation does not confirm a visible receiver")
                }
            }.distinct().takeIf { it.isNotEmpty() }?.joinToString(separator = "; ")

        private fun lanRoute(peer: NearbyPeer): NearbyPeerRoute.Lan? =
            peer.lanEndpoint?.let { lan ->
                lan.primaryAddress()?.let { address ->
                    NearbyPeerRoute.Lan(address = address, port = lan.port)
                }
            }

        private fun lanRejection(peer: NearbyPeer): String {
            val lan = peer.lanEndpoint
            val lanAddress = lan?.primaryAddress()
            return when {
                lan == null -> "wifi-lan=missing"
                lanAddress == null -> "wifi-lan=no-primary-address"
                else -> "wifi-lan=unusable"
            }
        }

        private fun bleL2capRoute(peer: NearbyPeer): NearbyPeerRoute.BleL2cap? =
            peer.bleAdvertisement?.let { ble ->
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
                blePsm == null -> "ble-l2cap=peer-psm-missing"
                else -> "ble-l2cap=unusable"
            }
        }

        private fun bleGattRoute(peer: NearbyPeer): NearbyPeerRoute.BleGatt? =
            peer.bleAdvertisement?.let { ble ->
                val address = ble.advertiserAddress
                if (address != null && ble.gattConnectable && peer.endpointInfo?.hidden == false) {
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
                !ble.gattConnectable -> "ble-gatt=not-verified"
                endpointInfo == null -> "ble-gatt=no-endpoint-info"
                endpointInfo.hidden -> "ble-gatt=peer-hidden"
                else -> "ble-gatt=unusable"
            }
        }

        private fun bluetoothRoute(peer: NearbyPeer): NearbyPeerRoute.BluetoothClassic? =
            peer.bluetoothEndpoint?.let { NearbyPeerRoute.BluetoothClassic(it.macAddress) }
    }
}
