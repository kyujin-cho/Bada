/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.kyujincho.wvmg.R
import io.github.kyujincho.wvmg.databinding.ActivitySendBinding
import io.github.kyujincho.wvmg.databinding.ItemPeerRowBinding
import io.github.kyujincho.wvmg.discovery.DiscoveredService
import io.github.kyujincho.wvmg.discovery.Discovery
import io.github.kyujincho.wvmg.discovery.DiscoveryEvent
import io.github.kyujincho.wvmg.discovery.ble.BleAdvertiseHandle
import io.github.kyujincho.wvmg.discovery.ble.BleAdvertiser
import io.github.kyujincho.wvmg.protocol.connection.FileSource
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnection
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnectionState
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.service.receiver.OutboundSessionActiveHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Sender-side share-intent landing screen (#24).
 *
 * Lifecycle:
 *
 *  1. The system share sheet routes an `ACTION_SEND` /
 *     `ACTION_SEND_MULTIPLE` intent here. [ShareIntentRouter] parses
 *     the extras into a [ShareIntentInput], and [UriFileSourceFactory]
 *     turns each URI into a protocol-level [FileSource].
 *  2. Discovery starts via [Discovery.browse]; the user picks a peer
 *     from the live-updating list.
 *  3. The selected peer's first non-loopback address + advertised port
 *     becomes the target of an [OutboundConnection]. The connection's
 *     [OutboundConnectionState] flow drives the status panel: the PIN
 *     is rendered when [OutboundConnectionState.AwaitingRemoteAcceptance]
 *     fires, and terminal states ([OutboundConnectionState.Completed],
 *     [OutboundConnectionState.Rejected], [OutboundConnectionState.Cancelled],
 *     [OutboundConnectionState.Failed]) lock the UI into a "Done" pose.
 *
 * #28 will add the matching Inbound side and turn the receiver into a
 * real testbed; this Activity intentionally keeps its public surface
 * minimal so the e2e wiring lands cleanly later.
 *
 * Plain-text shares are accepted by the router but **not yet shipped**
 * — Phase 1's protocol-level support for text payloads on the sender
 * side is a follow-up. We surface a clear "Done" with an explanation
 * for now rather than stubbing out a half-broken text path.
 *
 * `@Suppress("TooManyFunctions")` — this Activity owns the share-intent
 * lifecycle, discovery, the picker, and the OutboundConnection driver.
 * Splitting it would require non-trivial separation of UI state from
 * coroutine plumbing and is out of scope for the Galaxy interop fix.
 */
@Suppress("TooManyFunctions")
public class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private lateinit var fileSourceFactory: UriFileSourceFactory
    private lateinit var documentTreeFactory: DocumentTreeFileSourceFactory

    private var files: List<FileSource> = emptyList()
    private val peers: MutableList<DiscoveredService> = mutableListOf()
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var activeConnection: OutboundConnection? = null

    /**
     * Tracks whether the same-Wi-Fi-network hint card (#85) has been
     * shown long enough to count as user-visible. The timer starts on
     * `onCreate` once we know we have files to share; the card is
     * surfaced after [EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS] of
     * continuous empty-peer-list state.
     */
    private val emptyPeerHintTimer: EmptyPeerHintTimer = EmptyPeerHintTimer()
    private var emptyPeerHintJob: Job? = null

    /**
     * BLE service-data advertiser (#32). Started alongside mDNS
     * discovery so receivers' BLE scan loops wake up their mDNS
     * responders before the user has finished picking a peer. Stopped
     * when the outbound TCP connection enters [OutboundConnectionState.Handshaking]
     * (no point continuing to broadcast once the wire link is up) or
     * on `onDestroy` / cancel.
     *
     * Held as a nullable handle because [BleAdvertiser.start] returns
     * `null` on devices without a peripheral-mode BLE radio, with the
     * `BLUETOOTH_ADVERTISE` permission revoked, or with Bluetooth
     * turned off — all of which fall through to the mDNS-only path
     * per the issue's fallback acceptance criterion.
     */
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleAdvertiseHandle: BleAdvertiseHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Veto receiver-side mDNS publish for the duration of this
        // activity. When WVMG concurrently publishes its receiver-side
        // mDNS record AND opens an outbound `OutboundConnection` to the
        // same peer, Samsung One UI 8.0.5's GMS Nearby caches state for
        // our endpoint from the discovered WIFI_LAN service and then
        // fails `securegcm::UKey2Handshake::ParseHandshakeMessage` on
        // our incoming `client_finished` (verified ~73-267 ms after the
        // peer writes server_init). Pausing the gate for the lifetime
        // of `SendActivity` clears that race window.
        OutboundSessionActiveHolder.setOutboundSessionActive(true)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fileSourceFactory = UriFileSourceFactory(contentResolver)
        documentTreeFactory = DocumentTreeFileSourceFactory(contentResolver)

        binding.sendCancelButton.setOnClickListener { onCancelClicked() }
        binding.sendDoneButton.setOnClickListener { finish() }
        binding.sendShowQrButton.setOnClickListener { onShowQrClicked() }
        binding.sendNetworkHintDismiss.setOnClickListener { onHintDismissed() }

        // Resolve the intent's file list. May render a terminal UI
        // state (unsupported / folder empty / folder walk failed) and
        // return null; in that case we leave `files` as the default
        // empty list and bail without starting discovery. The terminal
        // states already display "Done" / explanatory text.
        val resolved = resolveIntentFiles()
        if (resolved == null) return
        files = resolved

        binding.sendPayloadSummary.text = PayloadSummary.forFiles(this, files)
        binding.sendSubtitle.setText(R.string.send_subtitle_discovering)
        startDiscovery()
        startEmptyPeerHintTimer()
        startBleAdvertise()
    }

    /**
     * Walk [intent] into the canonical [FileSource] list the rest of
     * the activity expects, or render a terminal UI state and return
     * null. Centralised here so `onCreate` carries a single early-
     * return rather than threading the share-sheet vs. folder-send
     * branching across multiple bail-out paths.
     *
     * Two distinct entry points:
     *
     *  1. `ACTION_SEND_FOLDER` (#38). The folder-picker on
     *     [io.github.kyujincho.wvmg.MainActivity] forwards a `tree://`
     *     URI here. We walk it via [DocumentTreeFileSourceFactory],
     *     surface a folder-specific empty / failure message if needed,
     *     and otherwise hand the list back.
     *  2. `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (#24). The system
     *     share sheet routes individual file URIs here, which
     *     [ShareIntentRouter] parses into the canonical input shapes.
     */
    @Suppress("ReturnCount") // The branching is inherent — each terminal-state path is its own bail.
    private fun resolveIntentFiles(): List<FileSource>? {
        if (intent.action == ACTION_SEND_FOLDER) {
            val treeUri = intent.data
            if (treeUri == null) {
                renderUnsupportedPayload()
                return null
            }
            val walked = materializeFolder(treeUri) ?: return null
            if (walked.isEmpty()) {
                // `materializeFolder` already swapped in the folder-
                // specific empty-state UI before returning.
                return null
            }
            return walked
        }
        val parsed = ShareIntentRouter.route(toShareIntent(intent))
        if (parsed == null) {
            renderUnsupportedPayload()
            return null
        }
        val files = materializeFiles(parsed)
        if (files.isEmpty()) {
            // Currently true for plain-text shares (sender-side text
            // payload support is a follow-up). Surface the same
            // unsupported message so the user gets clear feedback.
            renderUnsupportedPayload()
            return null
        }
        return files
    }

    override fun onDestroy() {
        super.onDestroy()
        // Coroutine cancellation handles the StateFlow / browse Flow
        // teardown, but we additionally call cancel() on any active
        // OutboundConnection so a CancelFrame is sent on the wire when
        // possible (best-effort — already-terminal states are no-ops).
        activeConnection?.cancel()
        discoveryJob?.cancel()
        connectionJob?.cancel()
        emptyPeerHintJob?.cancel()
        stopBleAdvertise()
        // Lift the gate veto so the receiver-side mDNS record can come
        // back up if any of the gate's existing publish signals
        // (BLE pulse, always-visible override, QR session) call for it.
        OutboundSessionActiveHolder.setOutboundSessionActive(false)
    }

    // -----------------------------------------------------------------
    // Intent parsing
    // -----------------------------------------------------------------

    /**
     * Convert the live Android [Intent] into the
     * platform-agnostic [ShareIntent] the router consumes.
     *
     * The Intent extras-extraction APIs are deprecated on API 33+ and
     * replaced with type-safe variants; we route through both shapes
     * so a single source compiles cleanly on every supported SDK.
     */
    private fun toShareIntent(source: Intent): ShareIntent {
        val streamUri: Uri? =
            when (source.action) {
                Intent.ACTION_SEND -> getParcelableExtraCompat(source, Intent.EXTRA_STREAM)
                else -> null
            }
        val streamUris: List<Uri>? =
            when (source.action) {
                Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtraCompat(source, Intent.EXTRA_STREAM)
                else -> null
            }
        val text: CharSequence? = source.getCharSequenceExtra(Intent.EXTRA_TEXT)
        return ShareIntent(
            action = source.action,
            streamUri = streamUri,
            streamUris = streamUris,
            textExtra = text,
        )
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(
        source: Intent,
        key: String,
    ): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(key, Uri::class.java)
        } else {
            source.getParcelableExtra(key) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun getParcelableArrayListExtraCompat(
        source: Intent,
        key: String,
    ): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            source.getParcelableArrayListExtra(key)
        }

    /**
     * Materialize the parsed input into a list of [FileSource]s. Plain
     * text returns an empty list (sender-side text support is a
     * follow-up); URI shapes return one FileSource per URI.
     */
    private fun materializeFiles(input: ShareIntentInput): List<FileSource> =
        when (input) {
            is ShareIntentInput.SingleUri ->
                listOf(fileSourceFactory.fromUri(input.uri as Uri))
            is ShareIntentInput.MultipleUris ->
                input.uris.map { fileSourceFactory.fromUri(it as Uri) }
            is ShareIntentInput.Text ->
                emptyList()
        }

    /**
     * Walk the SAF tree under [treeUri] and return one [FileSource] per
     * descendant file (#38). Returns:
     *
     * - A non-empty list on success — the regular discovery / send
     *   flow takes over from the caller.
     * - An empty list when the picked folder contains no files (the
     *   walker recurses into subdirectories but Quick Share has no
     *   "create empty directory" frame, so we have nothing to ship).
     *   In this case [renderFolderEmpty] swaps the UI into a
     *   "no files" state and the caller bails out.
     * - `null` when the walk threw (e.g. the SAF provider revoked the
     *   read grant before we got here). [renderFolderWalkFailed] swaps
     *   the UI into a "couldn't read folder" state and the caller bails
     *   out.
     *
     * The walker runs synchronously on the UI thread because:
     *
     * 1. The `OpenDocumentTree` contract just returned, so the system
     *    picker has already displayed and dismissed — the user is
     *    actively waiting for our response.
     * 2. Real-world folder sizes that fit Quick Share's transfer model
     *    (ten-ish files, low-MB to ~tens-of-MB total) walk in single
     *    digit milliseconds. Larger trees would block the UI thread,
     *    but Quick Share's per-transfer payload cap (no formal limit,
     *    but practical sender memory and timeout pressure) bounds the
     *    walk's worst case.
     *
     * If real devices show jank on huge trees, this is the right place
     * to move onto a background dispatcher — the rest of the activity
     * already drives discovery / connection from `lifecycleScope`.
     */
    private fun materializeFolder(treeUri: Uri): List<FileSource>? {
        binding.sendSubtitle.setText(R.string.send_folder_subtitle_walking)
        // Both SecurityException and IllegalArgumentException land on
        // the "couldn't read folder" UI:
        //   - SecurityException: the SAF provider revoked the read
        //     grant before we got here.
        //   - IllegalArgumentException: getTreeDocumentId rejected a
        //     non-tree URI. The OpenDocumentTree contract guarantees a
        //     tree URI on success, so reaching here means a malformed
        //     intent extra (e.g. a third-party app started us via a
        //     future-exported variant of this activity with the wrong
        //     shape). Surface as a user-visible error rather than
        //     crashing.
        val walked =
            runCatching { documentTreeFactory.walk(treeUri) }
                .onFailure { e ->
                    if (e is SecurityException || e is IllegalArgumentException) {
                        Log.e(OUTBOUND_TAG, "ACTION_SEND_FOLDER walk failed for $treeUri", e)
                        renderFolderWalkFailed()
                    } else {
                        // Unknown throwable: let the framework see it.
                        throw e
                    }
                }.getOrNull() ?: return null
        if (walked.isEmpty()) renderFolderEmpty()
        return walked
    }

    // -----------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------

    private fun startDiscovery() {
        val discovery = Discovery(applicationContext)
        discoveryJob =
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    discovery.browse().collect { event -> onDiscoveryEvent(event) }
                }
            }
    }

    private fun onDiscoveryEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.Resolved -> upsertResolvedPeer(event.service)
            is DiscoveryEvent.Lost -> {
                // Lost events fire per-instance-name. Because Resolved
                // events may have replaced an older instance under the
                // same addr+port row, the lost name may not match any
                // current peer's `instanceName` — that's fine, we
                // silently skip. The next mDNS query will re-resolve a
                // still-valid record if Samsung is just rotating ids.
                peers.removeAll { it.instanceName == event.instanceName }
            }
        }
        renderPeerList()
    }

    /**
     * Insert or replace [incoming] in the picker's [peers] list.
     *
     * Dedup by primary address + port, NOT by mDNS instance name. Stock
     * Quick Share rotates instance names rapidly for privacy and
     * frequently publishes multiple records simultaneously (e.g., one
     * with the friendly name and one without) — captured on a Galaxy
     * S24 Ultra, all pointing at the same TCP listener. Keying on
     * instance name leaves the picker with several rows for the same
     * device, some of them stale, and tapping a stale row intermittently
     * triggers a silent UKEY2 FIN on the peer (its current internal
     * state no longer matches the older instance ID baked into the row).
     *
     * We deliberately do NOT filter by EndpointInfo's `hidden` bit.
     * Stock peers publish every device — even "Everyone" mode — with
     * visibility=1 and no plaintext name; the Everyone-vs-Contacts-only
     * decision happens during the connection negotiation, not at the
     * mDNS layer. Filtering here would hide the very peers the user is
     * trying to send to.
     */
    private fun upsertResolvedPeer(incoming: DiscoveredService) {
        val key = peerDedupKey(incoming)
        if (key == null) {
            // Defensive: if the resolved record has no usable address
            // (rare; the discovery layer normally drops these earlier),
            // fall back to instance-name dedup so the row at least
            // appears.
            val ix = peers.indexOfFirst { it.instanceName == incoming.instanceName }
            if (ix >= 0) peers[ix] = incoming else peers.add(incoming)
            return
        }
        val existingIndex = peers.indexOfFirst { peerDedupKey(it) == key }
        if (existingIndex < 0) {
            peers.add(incoming)
            return
        }
        // Keep whichever record carries a friendly device name. Stock
        // Samsung alternates anonymous and named instances on the wire;
        // the picker should settle on the named one once seen rather
        // than flicker back to "Quick Share Device".
        val existing = peers[existingIndex]
        val existingNamed = existing.endpointInfo?.deviceName != null
        val incomingNamed = incoming.endpointInfo?.deviceName != null
        if (incomingNamed || !existingNamed) {
            peers[existingIndex] = incoming
        }
    }

    /**
     * Returns the (primaryAddress, port) tuple used to dedup the picker's
     * peer list, or `null` if [peer] has no usable connect target. Two
     * mDNS records that resolve to the same TCP listener share this key
     * regardless of mDNS instance name; that lets us collapse stock
     * Quick Share's rotating-instance-id record stream into one stable
     * row.
     */
    private fun peerDedupKey(peer: DiscoveredService): Pair<String, Int>? {
        val addr = peer.primaryAddress()?.hostAddress ?: return null
        return addr to peer.port
    }

    private fun renderPeerList() {
        val container = binding.sendPeerList
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (peer in peers) {
            val row = ItemPeerRowBinding.inflate(inflater, container, false)
            row.peerRowTitle.text = peerLabel(peer)
            row.peerRowSubtitle.text = peerSubtitle(peer)
            row.root.setOnClickListener { onPeerSelected(peer) }
            container.addView(row.root)
        }
        binding.sendEmptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        binding.sendSubtitle.setText(
            if (peers.isEmpty()) R.string.send_subtitle_discovering else R.string.send_subtitle_pick_peer,
        )
        updateEmptyPeerHintVisibility()
    }

    /**
     * Start the same-Wi-Fi-network hint timer (#85). After
     * [EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS] of continuous empty
     * peer list, re-evaluate visibility — by then either a peer has
     * shown up (in which case the hint stays hidden) or the timeout
     * fires and the card surfaces.
     */
    private fun startEmptyPeerHintTimer() {
        emptyPeerHintTimer.start(System.currentTimeMillis())
        emptyPeerHintJob =
            lifecycleScope.launch {
                delay(EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS)
                updateEmptyPeerHintVisibility()
            }
    }

    /**
     * Re-evaluate the hint card's visibility against the timer + the
     * current peer list. Called from `renderPeerList` (so a newly
     * arrived peer hides the card) and from the delayed coroutine
     * launched in [startEmptyPeerHintTimer] (so the timeout actually
     * surfaces the card).
     */
    private fun updateEmptyPeerHintVisibility() {
        val show =
            emptyPeerHintTimer.shouldShowHint(
                nowMillis = System.currentTimeMillis(),
                peerListEmpty = peers.isEmpty(),
            )
        binding.sendNetworkHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onHintDismissed() {
        emptyPeerHintTimer.markDismissed()
        binding.sendNetworkHint.visibility = View.GONE
    }

    /**
     * Build a single-line diagnostic string capturing everything we know
     * about [peer] at the moment the user picks it. Logged via
     * [onPeerSelected] before any TCP work so a subsequent failure can
     * be correlated against the exact target shape.
     */
    private fun formatPeerSnapshot(
        peer: DiscoveredService,
        chosenTarget: InetAddress,
    ): String {
        val instanceName = peer.instanceName
        val endpointIdHex =
            peer.endpointId
                ?.joinToString("") { "%02x".format(it) }
                ?: "<none>"
        val addressList =
            if (peer.addresses.isEmpty()) {
                "<none>"
            } else {
                peer.addresses.joinToString(",") { it.hostAddress ?: "<unresolved>" }
            }
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
                    append(info.deviceName?.let { "\"$it\"" } ?: "<null>")
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
        return "instance=$instanceName endpointId=$endpointIdHex " +
            "addrs=[$addressList] picked=${chosenTarget.hostAddress} " +
            "port=${peer.port} endpointInfo={$infoSummary}"
    }

    @Suppress("ReturnCount")
    private fun peerLabel(peer: DiscoveredService): String {
        val name = peer.endpointInfo?.deviceName
        if (!name.isNullOrBlank()) return name
        // Stock Quick Share publishes mDNS without a plaintext name —
        // fall back to the 4-character endpoint id (e.g., "RINE") which
        // is at least short and stable, then to the full instance name
        // as a last resort.
        peer.endpointId
            ?.takeIf { it.isNotEmpty() }
            ?.let { return "Quick Share device (${String(it, Charsets.US_ASCII)})" }
        return peer.instanceName
    }

    private fun peerSubtitle(peer: DiscoveredService): String {
        val addr = peer.primaryAddress()?.hostAddress ?: "?"
        return "$addr:${peer.port}"
    }

    // -----------------------------------------------------------------
    // Outbound connection
    // -----------------------------------------------------------------

    private fun onPeerSelected(peer: DiscoveredService) {
        val target: InetAddress =
            peer.primaryAddress() ?: run {
                renderTerminal(
                    getString(R.string.send_phase_failed),
                    getString(R.string.send_status_failure_reason, "no usable address"),
                )
                return
            }
        // Verbose target snapshot at the moment of pick. Routed through
        // Log.e (and the on-disk outbound log) because Funtouch filters
        // Log.i for non-system apps — without this a Galaxy "peer
        // closed" report leaves us guessing whether we even dialed the
        // right address. Includes everything we know about the peer:
        // mDNS instance name, the 4-byte endpoint id slice, the full
        // address list (so we can spot IPv6 vs IPv4 selection bugs) plus
        // the chosen primary, the TCP port, and the parsed EndpointInfo
        // header byte (visBit / version / deviceType) and device name.
        val targetSnapshot = formatPeerSnapshot(peer, target)
        Log.e(OUTBOUND_TAG, "picked target: $targetSnapshot")
        appendOutboundLog("picked target: $targetSnapshot")
        // Stop discovery — we've made our pick and don't want the JmDNS
        // browser holding the multicast lock during the actual transfer.
        discoveryJob?.cancel()
        discoveryJob = null

        // Hide the picker chrome.
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendStatusPanel.visibility = View.VISIBLE
        emptyPeerHintJob?.cancel()

        binding.sendStatusTarget.text = getString(R.string.send_status_target, peerLabel(peer))

        // Build a valid sender EndpointInfo. Stock Quick Share rejects a
        // ConnectionRequestFrame with an empty `endpoint_info` field by
        // immediately closing the socket, which surfaces here as
        // EndOfFrameStream while the client is awaiting Ukey2ServerInit.
        // Visibility=0 (everyone) is fine — we are the sender, this is
        // not advertised on mDNS.
        val senderEndpointInfoBytes =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN).also { SecureRandom().nextBytes(it) },
                deviceName = senderDeviceLabel(),
            ).serialize()

        val connection =
            OutboundConnection(
                targetAddress = target,
                port = peer.port,
                endpointInfo = senderEndpointInfoBytes,
                logger = { msg ->
                    // vivo Funtouch OS filters Log.i for non-system apps —
                    // use Log.e to bypass the filter, and also append to a
                    // file under getExternalFilesDir so `adb pull` can
                    // recover the log if logcat is empty.
                    Log.e(OUTBOUND_TAG, msg)
                    appendOutboundLog(msg)
                },
            )
        activeConnection = connection

        connectionJob =
            lifecycleScope.launch {
                // Collect the state flow inside a child job so we can
                // cancel it once `run` returns. StateFlow.collect never
                // completes on its own, and leaving it hot past the
                // terminal state would leak the activity scope.
                val collector =
                    launch {
                        connection.state.collect { state ->
                            renderConnectionState(state, peer)
                        }
                    }
                try {
                    connection.run(files)
                } finally {
                    collector.cancel()
                }
            }
    }

    private fun renderConnectionState(
        state: OutboundConnectionState,
        peer: DiscoveredService,
    ) {
        when (state) {
            OutboundConnectionState.Idle -> {
                // No-op — Idle is the initial flow value, the StateFlow
                // also re-emits it once on collection start.
            }
            OutboundConnectionState.Connecting -> {
                binding.sendStatusPhase.setText(R.string.send_phase_connecting)
                binding.sendPin.visibility = View.GONE
                binding.sendStatusMessage.text = getString(R.string.send_status_target, peerLabel(peer))
            }
            OutboundConnectionState.Handshaking -> {
                // TCP socket is up — the BLE wake-up pulse has done its
                // job, stop broadcasting (#32 acceptance: stops as soon
                // as the TCP connection to a recipient is established).
                stopBleAdvertise()
                binding.sendStatusPhase.setText(R.string.send_phase_handshaking)
                binding.sendPin.visibility = View.GONE
            }
            is OutboundConnectionState.AwaitingRemoteAcceptance -> {
                binding.sendStatusPhase.setText(R.string.send_phase_awaiting_acceptance)
                binding.sendPin.text = state.pin
                binding.sendPin.visibility = View.VISIBLE
                binding.sendStatusMessage.setText(R.string.send_status_pin_prompt)
            }
            is OutboundConnectionState.Sending -> {
                binding.sendStatusPhase.setText(R.string.send_phase_sending)
                binding.sendPin.text = state.pin
                binding.sendPin.visibility = View.VISIBLE
                binding.sendStatusMessage.text =
                    getString(
                        R.string.send_status_progress,
                        PayloadSummary.formatBytes(state.bytesSent),
                        PayloadSummary.formatBytes(state.totalSize),
                    )
            }
            OutboundConnectionState.Completed ->
                renderTerminal(
                    getString(R.string.send_phase_completed),
                    getString(R.string.send_status_target, peerLabel(peer)),
                )
            is OutboundConnectionState.Rejected ->
                renderTerminal(
                    getString(R.string.send_phase_rejected),
                    getString(R.string.send_status_failure_reason, state.status.toString()),
                )
            is OutboundConnectionState.Cancelled ->
                renderTerminal(
                    getString(R.string.send_phase_cancelled),
                    getString(R.string.send_status_failure_reason, state.cause.toString()),
                )
            is OutboundConnectionState.Failed ->
                renderTerminal(
                    getString(R.string.send_phase_failed),
                    getString(R.string.send_status_failure_reason, state.reason),
                )
        }
    }

    // -----------------------------------------------------------------
    // Terminal / unsupported / QR
    // -----------------------------------------------------------------

    private fun renderTerminal(
        phaseText: String,
        message: String,
    ) {
        binding.sendStatusPanel.visibility = View.VISIBLE
        binding.sendStatusPhase.text = phaseText
        binding.sendStatusMessage.text = message
        binding.sendDoneButton.visibility = View.VISIBLE
        binding.sendCancelButton.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
    }

    private fun renderUnsupportedPayload() {
        binding.sendPayloadSummary.text = getString(R.string.send_payload_text)
        binding.sendSubtitle.text = getString(R.string.send_unsupported)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendCancelButton.text = getString(R.string.send_done)
    }

    /**
     * Folder send (#38) terminal: the picked folder contains zero files
     * (only empty subdirectories or nothing at all). Quick Share has no
     * "create empty directory" frame, so there is nothing to transfer —
     * surface a clear message and let the user back out.
     */
    private fun renderFolderEmpty() {
        binding.sendPayloadSummary.text = getString(R.string.main_send_folder_button)
        binding.sendSubtitle.text = getString(R.string.send_folder_empty)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendCancelButton.text = getString(R.string.send_done)
    }

    /**
     * Folder send (#38) terminal: the SAF walk threw before we could
     * collect any FileSources. Most likely cause is a provider that
     * revoked the read grant, but a malformed tree URI (someone
     * exported this activity later and a third-party app starts us
     * with the wrong shape) lands here too — see `materializeFolder`.
     */
    private fun renderFolderWalkFailed() {
        binding.sendPayloadSummary.text = getString(R.string.main_send_folder_button)
        binding.sendSubtitle.text = getString(R.string.send_folder_walk_failed)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendCancelButton.text = getString(R.string.send_done)
    }

    private fun onCancelClicked() {
        // If a connection is mid-flight, ask it to cancel cleanly so a
        // CancelFrame goes out on the wire. The terminal state will
        // flow back through the StateFlow collector and finish the UI.
        val connection = activeConnection
        if (connection != null && binding.sendStatusPanel.isVisible) {
            connection.cancel()
            return
        }
        // Cancel before any peer was selected: stop the BLE pulse now
        // so we don't waste the radio while finish() tears the activity
        // down. onDestroy would also catch this, but stopping here keeps
        // the timing tight for the "stops on user cancel" criterion (#32).
        stopBleAdvertise()
        finish()
    }

    private fun onShowQrClicked() {
        startActivity(Intent(this, ShowQrActivity::class.java))
    }

    // -----------------------------------------------------------------
    // BLE service-data pulse (#32)
    // -----------------------------------------------------------------

    /**
     * Start the Quick Share BLE service-data advertisement (#32). The
     * advertisement wakes nearby Quick Share receivers' BLE scan loops,
     * which in turn enable their mDNS responder so our own discovery can
     * find them.
     *
     * Best-effort: devices without peripheral-mode BLE, devices with
     * Bluetooth turned off, and devices where the user revoked
     * `BLUETOOTH_ADVERTISE` after onboarding all flow through the
     * `null` return path of [BleAdvertiser.start] and are silently
     * skipped — the mDNS-only discovery path still works for them.
     */
    @Suppress("MissingPermission") // Permission re-checked inside BleAdvertiser.start.
    private fun startBleAdvertise() {
        val advertiser = BleAdvertiser(applicationContext)
        bleAdvertiser = advertiser
        bleAdvertiseHandle = advertiser.start()
        if (bleAdvertiseHandle == null) {
            Log.i(BLE_TAG, "BLE pulse not started — falling back to mDNS-only discovery")
        } else {
            Log.i(BLE_TAG, "BLE pulse started")
        }
    }

    /**
     * Stop the BLE service-data advertisement if one is active. Called
     * when the outbound TCP connection lands ([OutboundConnectionState.Handshaking]
     * onward), when the user cancels, and from `onDestroy` as a final
     * safety net. Safe to call repeatedly — both the local handle and
     * the underlying [BleAdvertiseHandle.close] are idempotent.
     */
    private fun stopBleAdvertise() {
        bleAdvertiseHandle?.close()
        bleAdvertiseHandle = null
        bleAdvertiser = null
    }

    /**
     * Display name for this device that the receiver will see in its
     * consent UI. Falls back to a stable "WhenVivoMeetsGoogle" string
     * when [Build.MODEL] is empty (rare but happens on some emulators).
     */
    private fun senderDeviceLabel(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "WhenVivoMeetsGoogle"

    /**
     * Append a line to a per-run log file under
     * `getExternalFilesDir(null)/wvmg-outbound.log`. This is a workaround
     * for vivo Funtouch OS, which filters non-system app logcat output
     * even with setprop overrides. Recoverable via:
     *
     *     adb shell run-as io.github.kyujincho.wvmg.debug \\
     *         find /storage/emulated/0/Android/data/io.github.kyujincho.wvmg.debug -name 'wvmg-outbound.log'
     *
     * or simpler:
     *
     *     adb pull /sdcard/Android/data/io.github.kyujincho.wvmg.debug/files/wvmg-outbound.log
     */
    private fun appendOutboundLog(line: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "wvmg-outbound.log")
            f.appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    public companion object {
        /**
         * Custom intent action used by [io.github.kyujincho.wvmg.MainActivity]
         * to forward a `tree://` URI from `ACTION_OPEN_DOCUMENT_TREE`
         * (#38). The URI lives in `intent.data` and the read grant is
         * propagated via [Intent.FLAG_GRANT_READ_URI_PERMISSION] on the
         * launcher side. This action is intentionally NOT exported in
         * the manifest — it is an internal contract between MainActivity
         * and SendActivity.
         */
        public const val ACTION_SEND_FOLDER: String = "io.github.kyujincho.wvmg.action.SEND_FOLDER"

        private const val OUTBOUND_TAG: String = "WvmgOutbound"

        // BLE advertise diagnostics share the discovery tag so a single
        // `adb logcat -s WvmgDiscovery` line surfaces both mDNS and BLE
        // events on real devices.
        private const val BLE_TAG: String = "WvmgDiscovery"
    }
}
