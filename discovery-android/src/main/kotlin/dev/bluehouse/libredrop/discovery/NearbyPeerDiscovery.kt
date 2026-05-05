/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

package dev.bluehouse.libredrop.discovery

import android.content.Context
import dev.bluehouse.libredrop.discovery.ble.BleFastAdvertisementScanner
import dev.bluehouse.libredrop.discovery.classic.BluetoothClassicPeerScanner
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

private const val DISCOVERY_TAG: String = "LibreDropDiscovery"

/**
 * Aggregates sender-side peer discovery across multiple media.
 *
 * The existing LAN mDNS path remains intact, but its output is merged with BLE
 * fast advertisements so the sender UI is no longer hard-wired to
 * [DiscoveredService]. Bluetooth Classic aggregation remains available to
 * tests and future feature re-enable work, but production user-facing flows do
 * not start the Classic scanner.
 */
public class NearbyPeerDiscovery internal constructor(
    private val lanEvents: Flow<DiscoveryEvent>,
    private val bleEvents: Flow<BleFastAdvertisementScanner.Observation>,
    private val bluetoothEvents: Flow<BluetoothClassicPeerScanner.Observation>,
) {
    public constructor(context: Context) : this(
        lanEvents = Discovery(context.applicationContext).browse(),
        bleEvents = BleFastAdvertisementScanner(context.applicationContext).scan(),
        bluetoothEvents =
            if (UserFacingMediumFeatures.BLUETOOTH_CLASSIC_USER_FACING_ENABLED) {
                BluetoothClassicPeerScanner(context.applicationContext).scan()
            } else {
                emptyFlow()
            },
    )

    public fun browse(): Flow<NearbyPeerEvent> =
        callbackFlow {
            val aggregator = PeerAggregator()

            val lanJob =
                launch {
                    lanEvents.collect { event ->
                        when (event) {
                            is DiscoveryEvent.Resolved ->
                                aggregator
                                    .onLanResolved(event.service)
                                    .forEach { trySend(it).isSuccess }
                            is DiscoveryEvent.Lost ->
                                aggregator
                                    .onLanLost(event.instanceName)
                                    .forEach { trySend(it).isSuccess }
                        }
                    }
                }

            val bleJob =
                launch {
                    bleEvents.collect { observation ->
                        aggregator
                            .onBleObserved(observation)
                            .forEach { trySend(it).isSuccess }
                    }
                }

            val bluetoothJob =
                launch {
                    bluetoothEvents.collect { observation ->
                        aggregator
                            .onBluetoothObserved(observation)
                            .forEach { trySend(it).isSuccess }
                    }
                }

            awaitClose {
                lanJob.cancel()
                bleJob.cancel()
                bluetoothJob.cancel()
            }
        }

    public companion object {
        @JvmStatic
        internal fun forTesting(
            lanEvents: Flow<DiscoveryEvent>,
            bleEvents: Flow<BleFastAdvertisementScanner.Observation>,
            bluetoothEvents: Flow<BluetoothClassicPeerScanner.Observation>,
        ): NearbyPeerDiscovery =
            NearbyPeerDiscovery(
                lanEvents = lanEvents,
                bleEvents = bleEvents,
                bluetoothEvents = bluetoothEvents,
            )
    }
}

private class PeerAggregator {
    private val peersById: MutableMap<String, MutablePeer> = LinkedHashMap()
    private val endpointIdIndex: MutableMap<String, String> = HashMap()
    private val lanRouteIndex: MutableMap<Pair<String, Int>, String> = HashMap()
    private val lanInstanceIndex: MutableMap<String, String> = HashMap()
    private val bluetoothIndex: MutableMap<String, String> = HashMap()
    private val bleIndex: MutableMap<String, String> = HashMap()

    fun onLanResolved(service: DiscoveredService): List<NearbyPeerEvent> {
        val endpointId = service.endpointId?.toAsciiLabel()
        val routeKey = service.lanRouteKey()
        val peerId =
            resolvePeerId(
                endpointId = endpointId,
                lanRouteKey = routeKey,
                bluetoothMac = null,
                bleAddress = null,
            )
        val state = peersById.getOrPut(peerId) { MutablePeer(stableId = peerId) }
        val before = state.toPeerOrNull()

        state.endpointId = state.endpointId ?: endpointId
        endpointId?.let { endpointIdIndex[it] = state.stableId }
        state.endpointInfo = chooseEndpointInfo(state.endpointInfo, service.endpointInfo)

        if (state.lanRouteKey != null && state.lanRouteKey != routeKey) {
            lanRouteIndex.remove(state.lanRouteKey)
        }
        state.lanRouteKey = routeKey
        if (routeKey != null) {
            lanRouteIndex[routeKey] = state.stableId
        }
        state.lanInstanceNames += service.instanceName
        lanInstanceIndex[service.instanceName] = state.stableId
        state.lanAddresses = service.addresses
        state.lanPort = service.port

        return changeEvents(before, state.toPeerOrNull())
    }

    fun onLanLost(instanceName: String): List<NearbyPeerEvent> {
        val peerId = lanInstanceIndex.remove(instanceName) ?: return emptyList()
        val state = peersById[peerId] ?: return emptyList()
        val before = state.toPeerOrNull()

        state.lanInstanceNames.remove(instanceName)
        if (state.lanInstanceNames.isEmpty()) {
            state.lanRouteKey?.let(lanRouteIndex::remove)
            state.lanRouteKey = null
            state.lanAddresses = emptyList()
            state.lanPort = null
        }

        return finalizeChange(state, before)
    }

    fun onBleObserved(observation: BleFastAdvertisementScanner.Observation): List<NearbyPeerEvent> {
        val peerId =
            resolvePeerId(
                endpointId = observation.endpointId,
                lanRouteKey = null,
                bluetoothMac = null,
                bleAddress = observation.advertiserAddress,
            )
        val state = peersById.getOrPut(peerId) { MutablePeer(stableId = peerId) }
        val before = state.toPeerOrNull()

        state.endpointId = state.endpointId ?: observation.endpointId
        observation.endpointId?.let { endpointIdIndex[it] = state.stableId }
        state.endpointInfo = chooseEndpointInfo(state.endpointInfo, observation.endpointInfo)
        if (state.bleAddress != observation.advertiserAddress) {
            state.bleGattConnectable = false
        }
        state.bleAddress = observation.advertiserAddress
        state.bleRssi = observation.rssi
        state.bleL2capPsm = observation.l2capPsm
        state.bleGattConnectable = state.bleGattConnectable || observation.gattConnectable
        observation.displayName?.toDisplayNameOrNull()?.let { displayName ->
            state.bleDisplayName = displayName
            state.bleDisplayNameSource = observation.displayNameSource?.logLabel
        }
        observation.advertiserAddress?.let { bleIndex[it] = state.stableId }

        return changeEvents(before, state.toPeerOrNull())
    }

    fun onBluetoothObserved(observation: BluetoothClassicPeerScanner.Observation): List<NearbyPeerEvent> {
        val peerId =
            resolvePeerId(
                endpointId = observation.endpointId,
                lanRouteKey = null,
                bluetoothMac = observation.macAddress,
                bleAddress = null,
            )
        val state = peersById.getOrPut(peerId) { MutablePeer(stableId = peerId) }
        val before = state.toPeerOrNull()

        state.endpointId = state.endpointId ?: observation.endpointId
        endpointIdIndex[observation.endpointId] = state.stableId
        state.endpointInfo = chooseEndpointInfo(state.endpointInfo, observation.endpointInfo)
        state.bluetoothMac = observation.macAddress
        state.bluetoothAdvertisedName = observation.advertisedName
        bluetoothIndex[observation.macAddress] = state.stableId

        return changeEvents(before, state.toPeerOrNull())
    }

    private fun finalizeChange(
        state: MutablePeer,
        before: NearbyPeer?,
    ): List<NearbyPeerEvent> {
        val after = state.toPeerOrNull()
        if (after != null) {
            return changeEvents(before, after)
        }
        peersById.remove(state.stableId)
        state.endpointId?.let(endpointIdIndex::remove)
        state.bluetoothMac?.let(bluetoothIndex::remove)
        state.bleAddress?.let(bleIndex::remove)
        state.lanRouteKey?.let(lanRouteIndex::remove)
        return if (before == null) emptyList() else listOf(NearbyPeerEvent.Lost(state.stableId))
    }

    private fun resolvePeerId(
        endpointId: String?,
        lanRouteKey: Pair<String, Int>?,
        bluetoothMac: String?,
        bleAddress: String?,
    ): String {
        endpointId?.let(endpointIdIndex::get)?.let { return it }
        lanRouteKey?.let(lanRouteIndex::get)?.let { return it }
        bluetoothMac?.let(bluetoothIndex::get)?.let { return it }
        bleAddress?.let(bleIndex::get)?.let { return it }
        return when {
            endpointId != null -> "endpoint:$endpointId"
            lanRouteKey != null -> "lan:${lanRouteKey.first}:${lanRouteKey.second}"
            bluetoothMac != null -> "bt:$bluetoothMac"
            bleAddress != null -> "ble:$bleAddress"
            else -> error("peer discovery update without any identity key")
        }
    }

    private fun changeEvents(
        before: NearbyPeer?,
        after: NearbyPeer?,
    ): List<NearbyPeerEvent> {
        if (after == null) return emptyList()

        val beforeVisible = before?.isConnectable == true
        val afterVisible = after.isConnectable

        if (!afterVisible) {
            logFilteredPeer(after)
            return if (beforeVisible) listOf(NearbyPeerEvent.Lost(after.stableId)) else emptyList()
        }
        if (before == after && beforeVisible) return emptyList()
        return listOf(NearbyPeerEvent.Resolved(after))
    }

    private fun logFilteredPeer(peer: NearbyPeer) {
        DiagnosticLog.i(
            DISCOVERY_TAG,
            "picker filter: hiding peer=${peer.stableId} " +
                "endpointId=${peer.endpointId ?: "<none>"} reason=${filterReason(peer)}",
        )
    }

    private fun filterReason(peer: NearbyPeer): String =
        buildList {
            if (peer.endpointInfo == null) {
                add("endpoint-info-unparseable")
            }

            val lan = peer.lanEndpoint
            when {
                lan == null -> add("wifi-lan-missing")
                lan.primaryAddress() == null -> add("wifi-lan-no-primary-address")
            }

            val ble = peer.bleAdvertisement
            when {
                ble == null -> add("ble-missing")
                ble.advertiserAddress == null -> add("ble-no-address")
                ble.l2capPsm != null -> Unit
                ble.gattConnectable -> add("ble-gatt-pending-endpoint-info")
                else -> add("ble-no-verified-bootstrap")
            }

            if (
                peer.bluetoothEndpoint != null &&
                !UserFacingMediumFeatures.BLUETOOTH_CLASSIC_USER_FACING_ENABLED
            ) {
                add("bluetooth-classic-disabled")
            }
        }.distinct().joinToString(separator = ",")
}

private data class MutablePeer(
    val stableId: String,
    var endpointId: String? = null,
    var endpointInfo: EndpointInfo? = null,
    val lanInstanceNames: MutableSet<String> = LinkedHashSet(),
    var lanAddresses: List<InetAddress> = emptyList(),
    var lanPort: Int? = null,
    var lanRouteKey: Pair<String, Int>? = null,
    var bluetoothMac: String? = null,
    var bluetoothAdvertisedName: String? = null,
    var bleAddress: String? = null,
    var bleRssi: Int? = null,
    var bleL2capPsm: Int? = null,
    var bleGattConnectable: Boolean = false,
    var bleDisplayName: String? = null,
    var bleDisplayNameSource: String? = null,
) {
    fun toPeerOrNull(): NearbyPeer? {
        val lan =
            lanPort?.let { port ->
                NearbyPeer.LanEndpoint(
                    instanceNames = lanInstanceNames.toSet(),
                    addresses = lanAddresses,
                    port = port,
                )
            }
        val bluetooth =
            bluetoothMac?.let { mac ->
                NearbyPeer.BluetoothEndpoint(
                    macAddress = mac,
                    advertisedName = bluetoothAdvertisedName ?: "",
                )
            }
        val ble =
            if (bleAddress != null || bleRssi != null) {
                NearbyPeer.BleAdvertisement(
                    advertiserAddress = bleAddress,
                    rssi = bleRssi,
                    l2capPsm = bleL2capPsm,
                    gattConnectable = bleGattConnectable,
                    displayName = bleDisplayName,
                    displayNameSource = bleDisplayNameSource,
                )
            } else {
                null
            }
        if (lan == null && bluetooth == null && ble == null) return null
        return NearbyPeer(
            stableId = stableId,
            endpointId = endpointId,
            endpointInfo = endpointInfo,
            lanEndpoint = lan,
            bluetoothEndpoint = bluetooth,
            bleAdvertisement = ble,
        )
    }
}

private fun chooseEndpointInfo(
    existing: EndpointInfo?,
    incoming: EndpointInfo?,
): EndpointInfo? {
    if (existing == null) return incoming
    if (incoming == null) return existing
    val existingNamed = !existing.deviceName.isNullOrBlank()
    val incomingNamed = !incoming.deviceName.isNullOrBlank()
    return when {
        incomingNamed && !existingNamed -> incoming
        !incoming.hidden && existing.hidden -> incoming
        else -> existing
    }
}

private fun DiscoveredService.lanRouteKey(): Pair<String, Int>? =
    primaryAddress()?.hostAddress?.let { host -> host to port }

private fun ByteArray.toAsciiLabel(): String = String(this, Charsets.US_ASCII)

private fun String?.toDisplayNameOrNull(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
