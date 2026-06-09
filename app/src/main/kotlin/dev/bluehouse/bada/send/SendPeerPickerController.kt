/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.bluehouse.bada.R
import dev.bluehouse.bada.databinding.ActivitySendBinding
import dev.bluehouse.bada.databinding.ItemPeerRowBinding
import dev.bluehouse.bada.discovery.NearbyPeer
import dev.bluehouse.bada.discovery.NearbyPeerDiscovery
import dev.bluehouse.bada.discovery.NearbyPeerEvent
import dev.bluehouse.bada.discovery.NearbyPeerRoute
import dev.bluehouse.bada.discovery.ble.BleAdvertiseHandle
import dev.bluehouse.bada.discovery.ble.BleAdvertiser
import dev.bluehouse.bada.service.receiver.ReceiverAdvertisementStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog as Log

@Suppress("LongParameterList") // Every collaborator (UI, lifecycle, callbacks, sender id) is needed.
internal class SendPeerPickerController(
    private val context: Context,
    private val binding: ActivitySendBinding,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPeerSelected: (NearbyPeer) -> Unit,
    /**
     * Invoked after every discovery update with the current resolved-peer
     * snapshot. The QR-code/link share path (#28) uses it to match peers
     * against the active QR session and auto-connect; the normal picker
     * flow ignores it. Default no-op so non-QR callers need not supply it.
     */
    private val onPeersResolved: (List<NearbyPeer>) -> Unit = {},
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

    /**
     * Snapshot of the row contents the picker was last rendered with —
     * one entry per visible peer in the order they were drawn, capturing
     * only the fields the row actually displays (stableId for identity,
     * title for the primary line, subtitle for the secondary line).
     *
     * Used to short-circuit [renderPeerList] when a discovery event
     * carries no display-relevant change. Without this gate, every BLE
     * fast-advertisement observation (which includes a fresh `rssi:Int?`
     * value, making the data-class equality on [NearbyPeer] flip every
     * few hundred milliseconds) would trigger a full
     * `container.removeAllViews()` + re-inflate cycle. With three or
     * more peers in the list, that churn lands inside roughly 10% of
     * tap windows and the user has to double-tap to register a click —
     * exactly the symptom reported on multi-peer environments.
     */
    private var lastRenderedRowSnapshot: List<RenderedRowSnapshot> = emptyList()

    private data class RenderedRowSnapshot(
        val stableId: String,
        val title: String,
        val subtitle: String,
    )

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

    /**
     * Re-resolve [peer]'s current LAN address by running a short,
     * transient discovery session, and return the fresh LAN route — or
     * `null` if no matching LAN-capable peer surfaces within [timeoutMs].
     *
     * Used by the sender's LAN re-resolve-on-connect-failure retry
     * (issue #203): the picker stops discovery the moment a peer is
     * picked ([suspendPicker]), so the route's baked-in address can go
     * stale (Wi-Fi roam / DHCP renew) with no live discovery to refresh
     * it. This spins up a fresh [NearbyPeerDiscovery] browse, waits for
     * the same peer to re-resolve, and tears the browse down again as
     * soon as it matches (or the timeout elapses).
     *
     * Matching is by `endpointId` when both sides have one — it is
     * stable across an IP change within a single advertising session —
     * and falls back to `stableId` otherwise. The fresh LAN route is
     * built through [SendBootstrapPlan.viableRoutes] so it honours the
     * same connectability gates (parseable endpoint info, dialable
     * primary address) the picker applies.
     */
    suspend fun reresolveLan(
        peer: NearbyPeer,
        timeoutMs: Long,
    ): NearbyPeerRoute.Lan? {
        logDiagnostic(
            "reresolve: browsing for peer=${peer.stableId} " +
                "endpointId=${peer.endpointId ?: "<none>"} timeoutMs=$timeoutMs",
        )
        val discovery = NearbyPeerDiscovery(context.applicationContext)
        val match =
            withTimeoutOrNull(timeoutMs) {
                discovery.browse().firstOrNull { event ->
                    event is NearbyPeerEvent.Resolved &&
                        matchesReresolveTarget(event.peer, peer) &&
                        freshLanRoute(event.peer) != null
                } as? NearbyPeerEvent.Resolved
            }
        val route = match?.peer?.let(::freshLanRoute)
        logDiagnostic(
            "reresolve: result peer=${peer.stableId} found=${route != null}" +
                (route?.let { " addr=${it.address.hostAddress}:${it.port}" } ?: ""),
        )
        return route
    }

    private fun freshLanRoute(peer: NearbyPeer): NearbyPeerRoute.Lan? =
        SendBootstrapPlan
            .viableRoutes(peer)
            .filterIsInstance<NearbyPeerRoute.Lan>()
            .firstOrNull()

    private fun matchesReresolveTarget(
        candidate: NearbyPeer,
        target: NearbyPeer,
    ): Boolean {
        val targetEndpoint = target.endpointId
        return if (targetEndpoint != null && candidate.endpointId != null) {
            candidate.endpointId == targetEndpoint
        } else {
            candidate.stableId == target.stableId
        }
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
        onPeersResolved(peers.toList())
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

        // Build the target row payloads up-front so we can compare
        // against the last rendered snapshot before deciding whether
        // the row container needs a rebuild.
        data class TargetRow(
            val peer: NearbyPeer,
            val title: String,
            val subtitle: String,
        )
        val targetRows =
            peers.mapNotNull { peer ->
                val plan = planFor(peer)
                if (!plan.isConnectable) return@mapNotNull null
                TargetRow(peer, peerLabel(peer), plan.subtitle)
            }
        val targetSnapshot =
            targetRows.map { row ->
                RenderedRowSnapshot(row.peer.stableId, row.title, row.subtitle)
            }

        // Subtitle ("Looking for nearby devices…" vs "Pick a device")
        // is cheap and depends only on whether peers exist, so we
        // refresh it unconditionally — re-applying the same string is
        // a no-op at the TextView layer.
        binding.sendSubtitle.setText(
            when {
                peers.isEmpty() -> R.string.send_subtitle_discovering
                else -> R.string.send_subtitle_pick_peer
            },
        )

        if (targetSnapshot == lastRenderedRowSnapshot) {
            // No display-relevant change. Skip the row rebuild so a
            // tap that just landed on an existing row keeps its click
            // handler attached, instead of being silently dropped by a
            // `removeAllViews()` + re-inflate cycle that would have run
            // for every BLE RSSI tick. The empty-state TextView is
            // still gated on the timer, not on peer-list churn, so we
            // call its updater below regardless.
            updateEmptyPeerHintVisibility()
            return
        }
        lastRenderedRowSnapshot = targetSnapshot

        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (target in targetRows) {
            val row = ItemPeerRowBinding.inflate(inflater, container, false)
            row.peerRowTitle.text = target.title
            row.peerRowSubtitle.text = target.subtitle
            row.root.isEnabled = true
            row.root.alpha = 1f
            val stableId = target.peer.stableId
            row.root.setOnClickListener {
                peers.firstOrNull { it.stableId == stableId }?.let(onPeerSelected)
            }
            container.addView(row.root)
        }
        // Empty-state visibility is gated on [EmptyPeerHintTimer] inside
        // [updateEmptyPeerHintVisibility] so the "no devices nearby yet"
        // helper text only surfaces after a short discovery window has
        // elapsed. Pinning it true the instant the activity opens
        // produced a confusing pair of conflicting messages — the
        // subtitle would say "Looking for nearby devices…" while the
        // body told the user there were none — even though no scan
        // had actually had time to land its first result.
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
        // Empty-state TextView ("no devices nearby yet…") is gated on
        // the same delay window as the same-Wi-Fi hint card, but the
        // dismiss latch does NOT apply — the body text is purely
        // informational, not a banner the user can close. The result
        // is a clean two-phase render: subtitle alone for the first
        // discovery window, subtitle + helper text once the window
        // expires with no peers found.
        val now = System.currentTimeMillis()
        val isEmpty = peers.isEmpty()
        binding.sendEmptyState.visibility =
            if (emptyPeerHintTimer.shouldShowEmptyState(now, isEmpty)) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // The "Same Wi-Fi network required" inline card is intentionally
        // disabled in favour of the help link + bottom-sheet flow added
        // alongside `send_help_link`. The two surfaces were colliding
        // visually whenever the peer list stayed empty long enough to
        // pop the inline card — the link below it overlapped the
        // dismiss button on the card. The bottom sheet covers the same
        // guidance (and adds the QR fallback section), so the inline
        // card is kept in the layout for now but never raised.
        binding.sendNetworkHint.visibility = View.GONE
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
        private const val BLE_TAG: String = "BadaDiscovery"
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
