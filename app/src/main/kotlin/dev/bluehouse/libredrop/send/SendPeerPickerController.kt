/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.databinding.ActivitySendBinding
import dev.bluehouse.libredrop.databinding.ItemPeerRowBinding
import dev.bluehouse.libredrop.discovery.NearbyPeer
import dev.bluehouse.libredrop.discovery.NearbyPeerDiscovery
import dev.bluehouse.libredrop.discovery.NearbyPeerEvent
import dev.bluehouse.libredrop.discovery.NearbyPeerRoute
import dev.bluehouse.libredrop.discovery.ble.BleAdvertiseHandle
import dev.bluehouse.libredrop.discovery.ble.BleAdvertiser
import dev.bluehouse.libredrop.service.receiver.ReceiverAdvertisementStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog as Log

internal class SendPeerPickerController(
    private val context: Context,
    private val binding: ActivitySendBinding,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPeerSelected: (NearbyPeer) -> Unit,
    private val logDiagnostic: (String) -> Unit,
    /**
     * Sender's 4-byte endpoint slug. Threaded into the BLE FastInitiation
     * pulse's `secret_id_hash` so stock GMS receivers classify the pulse
     * as an active `type=NOTIFY` share instead of an all-zero-hash
     * `type=SILENT` pulse.
     */
    private val senderEndpointId: String,
) {
    private val peers: MutableList<NearbyPeer> = mutableListOf()

    private var outboundPresenceJob: Job? = null
    private var discoveryJob: Job? = null
    private var emptyPeerHintJob: Job? = null

    private val emptyPeerHintTimer: EmptyPeerHintTimer = EmptyPeerHintTimer()
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleAdvertiseHandle: BleAdvertiseHandle? = null

    fun start() {
        outboundPresenceJob?.cancel()
        outboundPresenceJob =
            scope.launch {
                val receiverWasAdvertising = ReceiverAdvertisementStateHolder.isAdvertising
                if (receiverWasAdvertising) {
                    logDiagnostic("discovery: waiting for receiver mDNS unpublish before browse")
                }

                val unpublishObserved = ReceiverAdvertisementStateHolder.awaitNotAdvertising()
                if (!unpublishObserved) {
                    logDiagnostic("discovery: receiver mDNS unpublish wait timed out; starting browse")
                } else if (receiverWasAdvertising) {
                    logDiagnostic("discovery: receiver mDNS unpublish observed")
                }

                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch
                startDiscovery()
                startEmptyPeerHintTimer()
                startBleAdvertise()
            }
    }

    fun stop() {
        outboundPresenceJob?.cancel()
        discoveryJob?.cancel()
        emptyPeerHintJob?.cancel()
        stopBleAdvertise()
    }

    fun suspendPicker() {
        discoveryJob?.cancel()
        discoveryJob = null
        emptyPeerHintJob?.cancel()
        emptyPeerHintJob = null
        binding.sendNetworkHint.visibility = View.GONE
    }

    fun stopBleAdvertise() {
        bleAdvertiseHandle?.close()
        bleAdvertiseHandle = null
        bleAdvertiser = null
    }

    fun onHintDismissed() {
        emptyPeerHintTimer.markDismissed()
        binding.sendNetworkHint.visibility = View.GONE
    }

    fun peerLabel(peer: NearbyPeer): String = peer.displayName()

    fun peerSubtitle(peer: NearbyPeer): String = planFor(peer).subtitle

    fun peerFailureReason(peer: NearbyPeer): String = planFor(peer).failureReason ?: "no usable initial route"

    fun formatPeerSnapshot(
        peer: NearbyPeer,
        chosenRoute: NearbyPeerRoute? = null,
    ): String {
        val plan = planFor(peer)
        val endpointId = peer.endpointId ?: "<none>"
        val addressList =
            peer.lanEndpoint
                ?.addresses
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(",") { it.hostAddress ?: "<unresolved>" }
                ?: "<none>"
        val info = peer.endpointInfo
        val infoSummary =
            if (info == null) {
                "<none>"
            } else {
                buildString {
                    append("v=").append(info.version)
                    append(" hidden=").append(info.hidden)
                    append(" type=").append(info.deviceType.name)
                    append(" name=")
                    append(info.deviceName.toQuotedLogValue(nullToken = "<null>"))
                    if (info.tlvRecords.isNotEmpty()) {
                        append(" tlv=").append(
                            info.tlvRecords.joinToString(",") { tlv ->
                                val valueHex = tlv.value.joinToString("") { "%02x".format(it) }
                                "${tlv.type}:$valueHex"
                            },
                        )
                    }
                }
            }
        val routeSummary =
            when (chosenRoute) {
                is NearbyPeerRoute.Lan -> "lan=${chosenRoute.address.hostAddress}:${chosenRoute.port}"
                is NearbyPeerRoute.BluetoothClassic -> "bluetooth=${chosenRoute.macAddress}"
                is NearbyPeerRoute.BleL2cap -> "ble-l2cap=${chosenRoute.macAddress}:${chosenRoute.psm}"
                is NearbyPeerRoute.BleGatt -> "ble-gatt=${chosenRoute.macAddress}"
                null -> plan.action.diagnosticLabel
            }
        val bleIdentitySummary =
            formatBleIdentitySnapshot(peer)
        return "peer=${peer.stableId} endpointId=$endpointId mediums=${peer.candidateMediums} " +
            "addrs=[$addressList] route=$routeSummary displayName=${peer.displayName().toQuotedLogValue()} " +
            "displayNameSource=${peer.displayNameSource()} bootstrap={${plan.diagnosticSummary()}}" +
            "$bleIdentitySummary endpointInfo={$infoSummary}"
    }

    private fun formatBleIdentitySnapshot(peer: NearbyPeer): String {
        val ble = peer.bleAdvertisement ?: return ""
        val displayName = ble.displayName.toQuotedLogValue()
        val displayNameSource = ble.displayNameSource ?: "<none>"
        return " bleName=$displayName bleNameSource=$displayNameSource"
    }

    private fun startDiscovery() {
        val discovery = NearbyPeerDiscovery(context.applicationContext)
        logDiagnostic("discovery: start")
        discoveryJob =
            scope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    logDiagnostic("discovery: browse collector start")
                    try {
                        discovery.browse().collect { event -> onDiscoveryEvent(event) }
                    } finally {
                        logDiagnostic("discovery: browse collector stop")
                    }
                }
            }
    }

    private fun onDiscoveryEvent(event: NearbyPeerEvent) {
        val before = peers.size
        when (event) {
            is NearbyPeerEvent.Resolved -> {
                upsertResolvedPeer(event.peer)
                logDiagnostic(
                    "discovery: resolved ${formatPeerSnapshot(event.peer)} " +
                        "before=$before after=${peers.size} rows=${formatPeerRows()}",
                )
            }
            is NearbyPeerEvent.Lost -> {
                val removed = peers.removeAll { it.stableId == event.stableId }
                logDiagnostic(
                    "discovery: lost peer=${event.stableId} removed=$removed " +
                        "before=$before after=${peers.size} rows=${formatPeerRows()}",
                )
            }
        }
        renderPeerList()
    }

    private fun upsertResolvedPeer(incoming: NearbyPeer) {
        if (!planFor(incoming).isConnectable) {
            peers.removeAll { it.stableId == incoming.stableId }
            return
        }
        val existingIndex = peers.indexOfFirst { it.stableId == incoming.stableId }
        if (existingIndex >= 0) {
            peers[existingIndex] = incoming
        } else {
            peers.add(incoming)
        }
        peers.sortWith(
            compareByDescending<NearbyPeer> { planFor(it).isConnectable }
                .thenBy { it.displayName().lowercase() },
        )
    }

    private fun renderPeerList() {
        val container = binding.sendPeerList
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (peer in peers) {
            val plan = planFor(peer)
            if (!plan.isConnectable) continue
            val row = ItemPeerRowBinding.inflate(inflater, container, false)
            row.peerRowTitle.text = peerLabel(peer)
            row.peerRowSubtitle.text = plan.subtitle
            row.root.isEnabled = true
            row.root.alpha = 1f
            row.root.setOnClickListener { onPeerSelected(peer) }
            container.addView(row.root)
        }
        binding.sendEmptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        binding.sendSubtitle.setText(
            when {
                peers.isEmpty() -> R.string.send_subtitle_discovering
                else -> R.string.send_subtitle_pick_peer
            },
        )
        updateEmptyPeerHintVisibility()
    }

    private fun startEmptyPeerHintTimer() {
        emptyPeerHintTimer.start(System.currentTimeMillis())
        emptyPeerHintJob =
            scope.launch {
                delay(EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS)
                updateEmptyPeerHintVisibility()
            }
    }

    private fun updateEmptyPeerHintVisibility() {
        val show =
            emptyPeerHintTimer.shouldShowHint(
                nowMillis = System.currentTimeMillis(),
                peerListEmpty = peers.isEmpty(),
            )
        binding.sendNetworkHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    @Suppress("MissingPermission")
    private fun startBleAdvertise() {
        val advertiser = BleAdvertiser(context.applicationContext, senderEndpointId)
        bleAdvertiser = advertiser
        bleAdvertiseHandle = advertiser.start()
        if (bleAdvertiseHandle == null) {
            logDiagnostic("ble: pulse not started; falling back to mDNS-only discovery")
            Log.i(BLE_TAG, "BLE pulse not started - falling back to mDNS-only discovery")
        } else {
            logDiagnostic("ble: pulse started")
            Log.i(BLE_TAG, "BLE pulse started")
        }
    }

    private fun formatPeerRows(): String =
        if (peers.isEmpty()) {
            "<empty>"
        } else {
            peers.joinToString(";") { peer ->
                val plan = planFor(peer)
                val route =
                    when (val action = plan.action) {
                        is SendBootstrapPlan.Action.Direct ->
                            when (val route = action.route) {
                                is NearbyPeerRoute.Lan -> "${route.address.hostAddress}:${route.port}"
                                is NearbyPeerRoute.BluetoothClassic -> route.macAddress
                                is NearbyPeerRoute.BleL2cap -> "${route.macAddress}:${route.psm}"
                                is NearbyPeerRoute.BleGatt -> route.macAddress
                            }
                        SendBootstrapPlan.Action.Unavailable -> "<none>"
                    }
                "${peer.stableId}/${peer.endpointId ?: "<none>"}/$route/" +
                    "${peer.displayName().toSanitizedLogValue()}(${peer.displayNameSource()})"
            }
        }

    private fun planFor(peer: NearbyPeer): SendBootstrapPlan = SendBootstrapPlan.resolve(peer = peer)

    private companion object {
        private const val BLE_TAG: String = "LibreDropDiscovery"
    }
}

internal fun String?.toQuotedLogValue(nullToken: String = "<none>"): String =
    this
        ?.toSanitizedLogValue()
        ?.let { "\"$it\"" }
        ?: nullToken

internal fun String.toSanitizedLogValue(): String =
    buildString(length) {
        this@toSanitizedLogValue.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
