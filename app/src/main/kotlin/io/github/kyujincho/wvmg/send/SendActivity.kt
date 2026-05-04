/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.kyujincho.wvmg.R
import io.github.kyujincho.wvmg.databinding.ActivitySendBinding
import io.github.kyujincho.wvmg.discovery.NearbyPeer
import io.github.kyujincho.wvmg.discovery.NearbyPeerRoute
import io.github.kyujincho.wvmg.discovery.bootstrap.BleGattInitialControlClient
import io.github.kyujincho.wvmg.discovery.bootstrap.BleGattInitialControlServer
import io.github.kyujincho.wvmg.discovery.bootstrap.BleL2capInitialControlClient
import io.github.kyujincho.wvmg.discovery.bootstrap.BluetoothClassicBootstrapClient
import io.github.kyujincho.wvmg.discovery.medium.MediumRegistries
import io.github.kyujincho.wvmg.protocol.connection.CancelCause
import io.github.kyujincho.wvmg.protocol.connection.FileSource
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnection
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnectionState
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.service.receiver.OutboundSessionActiveHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 *  2. Discovery starts via [NearbyPeerDiscovery]; the user picks a peer
 *     from the live-updating list.
 *  3. The selected peer's preferred initial route becomes the target of
 *     an [OutboundConnection]. The connection's
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
    private lateinit var payloadResolver: SendPayloadResolver
    private lateinit var peerPickerController: SendPeerPickerController

    private var files: List<FileSource> = emptyList()
    private var connectionJob: Job? = null
    private var activeConnection: OutboundConnection? = null
    private var bluetoothBootstrapClient: BluetoothClassicBootstrapClient? = null
    private var bleL2capBootstrapClient: BleL2capInitialControlClient? = null
    private var bleGattBootstrapClient: BleGattInitialControlClient? = null
    private var senderGattServer: BleGattInitialControlServer? = null
    private lateinit var senderEndpointId: String
    private lateinit var senderEndpointInfo: EndpointInfo
    private lateinit var senderEndpointInfoBytes: ByteArray

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
        payloadResolver = SendPayloadResolver(fileSourceFactory, documentTreeFactory)
        // Generate the sender identity up front so the picker controller
        // can thread the endpointId into the BLE FastInitiation pulse's
        // `secret_id_hash`. Stock GMS receivers (Samsung One UI 8.x in
        // particular) classify pulses with an all-zero hash as
        // `type=SILENT`, which causes them to skip the per-peer Weave
        // handler registration on their `0xFEF3` GATT server — every
        // subsequent ATT write to `00000100-…-0101` then throws
        // `No handler registered for characteristic …`. The endpointId
        // bytes form the hash and lift Samsung's classification to
        // `type=NORMAL`, unblocking the BLE GATT bootstrap.
        prepareSenderIdentity()
        startSenderGattServer()
        peerPickerController =
            SendPeerPickerController(
                context = this,
                binding = binding,
                lifecycle = lifecycle,
                scope = lifecycleScope,
                onPeerSelected = ::onPeerSelected,
                logDiagnostic = ::logOutboundDiagnostic,
                senderEndpointId = senderEndpointId,
            )

        binding.sendCancelButton.setOnClickListener { onCancelClicked() }
        binding.sendDoneButton.setOnClickListener { finish() }
        binding.sendShowQrButton.setOnClickListener { onShowQrClicked() }
        binding.sendNetworkHintDismiss.setOnClickListener { peerPickerController.onHintDismissed() }

        // Resolve the intent's file list. May render a terminal UI
        // state (unsupported / folder empty / folder walk failed) and
        // return null; in that case we leave `files` as the default
        // empty list and bail without starting discovery. The terminal
        // states already display "Done" / explanatory text.
        val resolvedFiles =
            when (val resolved = payloadResolver.resolve(intent)) {
                is SendPayloadResolution.Files -> resolved.files
                SendPayloadResolution.Unsupported -> {
                    renderUnsupportedPayload()
                    null
                }
                SendPayloadResolution.FolderEmpty -> {
                    renderFolderEmpty()
                    null
                }
                SendPayloadResolution.FolderWalkFailed -> {
                    renderFolderWalkFailed()
                    null
                }
            } ?: return
        files = resolvedFiles
        logResolvedFiles(files)

        binding.sendPayloadSummary.text = PayloadSummary.forFiles(this, files)
        binding.sendSubtitle.setText(R.string.send_subtitle_discovering)
        peerPickerController.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Coroutine cancellation handles the StateFlow / browse Flow
        // teardown, but we additionally call cancel() on any active
        // OutboundConnection so a CancelFrame is sent on the wire when
        // possible (best-effort — already-terminal states are no-ops).
        activeConnection?.cancel()
        bluetoothBootstrapClient?.cancelPendingConnect()
        bleL2capBootstrapClient?.cancelPendingConnect()
        bleGattBootstrapClient?.cancelPendingConnect()
        peerPickerController.stop()
        senderGattServer?.stop()
        senderGattServer = null
        connectionJob?.cancel()
        // Lift the gate veto so the receiver-side mDNS record can come
        // back up if any of the gate's existing publish signals
        // (BLE pulse, always-visible override, QR session) call for it.
        OutboundSessionActiveHolder.setOutboundSessionActive(false)
    }

    @Suppress("ReturnCount")
    private suspend fun buildOutboundConnection(
        route: NearbyPeerRoute,
        endpointInfo: ByteArray,
    ): OutboundConnection? {
        val mediumRegistry = MediumRegistries.defaultForContext(applicationContext)
        return when (route) {
            is NearbyPeerRoute.Lan ->
                OutboundConnection(
                    targetAddress = route.address,
                    port = route.port,
                    endpointId = senderEndpointId,
                    endpointInfo = endpointInfo,
                    mediumRegistry = mediumRegistry,
                    logger = ::logOutboundWireMessage,
                )
            is NearbyPeerRoute.BluetoothClassic -> {
                val client = BluetoothClassicBootstrapClient(applicationContext)
                bluetoothBootstrapClient = client
                val transport =
                    try {
                        client.connect(route.macAddress)
                    } finally {
                        bluetoothBootstrapClient = null
                    } ?: return null
                OutboundConnection(
                    transport = transport,
                    endpointId = senderEndpointId,
                    endpointInfo = endpointInfo,
                    mediumRegistry = mediumRegistry,
                    logger = ::logOutboundWireMessage,
                )
            }
            is NearbyPeerRoute.BleL2cap -> {
                val client = BleL2capInitialControlClient(applicationContext)
                bleL2capBootstrapClient = client
                val transport =
                    try {
                        client.connect(route.macAddress, route.psm)
                    } finally {
                        bleL2capBootstrapClient = null
                    } ?: return null
                OutboundConnection(
                    transport = transport,
                    endpointId = senderEndpointId,
                    endpointInfo = endpointInfo,
                    mediumRegistry = mediumRegistry,
                    logger = ::logOutboundWireMessage,
                )
            }
            is NearbyPeerRoute.BleGatt -> {
                val client = BleGattInitialControlClient(applicationContext)
                bleGattBootstrapClient = client
                val transport =
                    try {
                        client.connect(route.macAddress)
                    } finally {
                        bleGattBootstrapClient = null
                    } ?: return null
                OutboundConnection(
                    transport = transport,
                    endpointId = senderEndpointId,
                    endpointInfo = endpointInfo,
                    mediumRegistry = mediumRegistry,
                    logger = ::logOutboundWireMessage,
                )
            }
        }
    }

    private suspend fun buildOutboundConnection(
        plan: SendBootstrapPlan,
        endpointInfo: ByteArray,
    ): PreparedConnection =
        when (val action = plan.action) {
            is SendBootstrapPlan.Action.Direct -> {
                val connection = buildOutboundConnection(action.route, endpointInfo)
                if (connection != null) {
                    PreparedConnection.Ready(connection)
                } else {
                    logOutboundDiagnostic("bootstrap: initial direct connect failed route=${action.route}")
                    PreparedConnection.Failed("initial bootstrap connect failed")
                }
            }

            SendBootstrapPlan.Action.Unavailable ->
                PreparedConnection.Failed(plan.failureReason ?: "no usable initial route").also {
                    logOutboundDiagnostic(
                        "bootstrap: unavailable ${plan.diagnosticSummary()}",
                    )
                }
        }

    // -----------------------------------------------------------------
    // Outbound connection
    // -----------------------------------------------------------------

    private fun onPeerSelected(peer: NearbyPeer) {
        val plan = SendBootstrapPlan.resolve(peer = peer)
        val chosenRoute =
            when (val action = plan.action) {
                is SendBootstrapPlan.Action.Direct -> action.route
                SendBootstrapPlan.Action.Unavailable -> null
            }
        if (!plan.isConnectable) {
            renderTerminal(
                getString(R.string.send_phase_failed),
                getString(R.string.send_status_failure_reason, peerPickerController.peerFailureReason(peer)),
            )
            return
        }
        // Pre-flight: BLE-GATT-only into a Samsung receiver is a known
        // dead end (Google-account-bound sender_certificate gate on
        // Samsung's Weave handler — see
        // docs/research/samsung-ble-gatt-cert-gate.md). Surface a
        // confirmation dialog instead of silently letting the user
        // burn a 15s handshake timeout. Caller can still opt to "Try
        // anyway" — leaves a door open if Samsung ever loosens the
        // gate, and avoids a hard block on false-positive heuristic
        // matches.
        if (plan.samsungBleGattCaveat) {
            confirmSamsungBleGattAttempt(peer, plan, chosenRoute)
            return
        }
        proceedWithPeer(peer, plan, chosenRoute)
    }

    private fun confirmSamsungBleGattAttempt(
        peer: NearbyPeer,
        plan: SendBootstrapPlan,
        chosenRoute: NearbyPeerRoute?,
    ) {
        val deviceName = peerPickerController.peerLabel(peer)
        AlertDialog
            .Builder(this)
            .setTitle(R.string.send_samsung_ble_warning_title)
            .setMessage(getString(R.string.send_samsung_ble_warning_body, deviceName))
            .setPositiveButton(R.string.send_samsung_ble_warning_open_wifi) { _, _ ->
                openWifiSettings()
            }.setNegativeButton(R.string.send_samsung_ble_warning_try_anyway) { _, _ ->
                proceedWithPeer(peer, plan, chosenRoute)
            }.setNeutralButton(R.string.send_samsung_ble_warning_cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.send_samsung_ble_warning_open_wifi, Toast.LENGTH_LONG).show()
        }
    }

    private fun proceedWithPeer(
        peer: NearbyPeer,
        plan: SendBootstrapPlan,
        chosenRoute: NearbyPeerRoute?,
    ) {
        // Verbose target snapshot at the moment of pick. Routed through
        // Log.e (and the on-disk outbound log) because Funtouch filters
        // Log.i for non-system apps — without this a Galaxy "peer
        // closed" report leaves us guessing whether we even dialed the
        // right address. Includes everything we know about the peer:
        // aggregated mediums, the chosen initial route, the full LAN
        // address list (so we can spot IPv6 vs IPv4 selection bugs),
        // and the parsed EndpointInfo header byte / device name.
        val targetSnapshot = peerPickerController.formatPeerSnapshot(peer, chosenRoute)
        Log.e(OUTBOUND_TAG, "picked target: $targetSnapshot")
        appendOutboundLog("picked target: $targetSnapshot")
        // Stop discovery and the wake-up pulse once we've made our pick.
        // BLE GATT bootstrap reuses the Bluetooth controller immediately;
        // leaving the pulse active during the dial adds avoidable role
        // churn on stock Quick Share receivers.
        peerPickerController.suspendPicker()
        peerPickerController.stopBleAdvertise()

        // Hide the picker chrome.
        binding.sendPeerList.isVisible = false
        binding.sendEmptyState.isVisible = false
        binding.sendNetworkHint.isVisible = false
        binding.sendShowQrButton.isVisible = false
        binding.sendStatusPanel.isVisible = true

        binding.sendStatusTarget.text = getString(R.string.send_status_target, peerPickerController.peerLabel(peer))
        binding.sendStatusPhase.setText(R.string.send_phase_connecting)
        binding.sendStatusMessage.text = plan.subtitle

        // Build a valid sender EndpointInfo. Stock Quick Share rejects a
        // ConnectionRequestFrame with an empty `endpoint_info` field by
        // immediately closing the socket, which surfaces here as
        // EndOfFrameStream while the client is awaiting Ukey2ServerInit.
        // Visibility=0 (everyone) is fine — we are the sender, this is
        // not advertised on mDNS.
        connectionJob =
            lifecycleScope.launch {
                when (val prepared = buildOutboundConnection(plan, senderEndpointInfoBytes)) {
                    is PreparedConnection.Failed -> {
                        renderTerminal(
                            getString(R.string.send_phase_failed),
                            getString(R.string.send_status_failure_reason, prepared.reason),
                        )
                        return@launch
                    }
                    is PreparedConnection.Ready -> {
                        val connection = prepared.connection
                        activeConnection = connection
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
                            activeConnection = null
                            bluetoothBootstrapClient = null
                            bleL2capBootstrapClient = null
                        }
                    }
                }
            }
    }

    private sealed interface PreparedConnection {
        data class Ready(
            val connection: OutboundConnection,
        ) : PreparedConnection

        data class Failed(
            val reason: String,
        ) : PreparedConnection
    }

    private fun renderConnectionState(
        state: OutboundConnectionState,
        peer: NearbyPeer,
    ) {
        when (state) {
            OutboundConnectionState.Idle -> {
                // No-op — Idle is the initial flow value, the StateFlow
                // also re-emits it once on collection start.
            }
            OutboundConnectionState.Connecting -> {
                binding.sendStatusPhase.setText(R.string.send_phase_connecting)
                binding.sendPin.visibility = View.GONE
                binding.sendStatusMessage.text = peerPickerController.peerSubtitle(peer)
            }
            OutboundConnectionState.Handshaking -> {
                // The transport is up. The wake-up pulse is already
                // stopped on selection; keep this idempotent call for any
                // older path that reaches Handshaking without a picker tap.
                peerPickerController.stopBleAdvertise()
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
                // #46: include smoothed bytes/sec rate + ETA in the
                // status subtitle. The formatter drops the rate / ETA
                // segments while warming up so the user does not see
                // "0 B/s, unknown ETA" — a bare "12 MB of 100 MB"
                // line shows up first, then rate + ETA fade in once
                // the EMA has two samples.
                binding.sendStatusMessage.text =
                    SendProgressFormatter.format(this, state.progress)
            }
            OutboundConnectionState.Completed ->
                renderTerminal(
                    getString(R.string.send_phase_completed),
                    getString(R.string.send_status_target, peerPickerController.peerLabel(peer)),
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
     * with the wrong shape) lands here too — see [SendPayloadResolver].
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

    @Suppress("ReturnCount")
    private fun onCancelClicked() {
        // If a connection is mid-flight, ask it to cancel cleanly so a
        // CancelFrame goes out on the wire. The terminal state will
        // flow back through the StateFlow collector and finish the UI.
        val connection = activeConnection
        if (connection != null && binding.sendStatusPanel.isVisible) {
            connection.cancel()
            return
        }
        val bootstrapClient = bluetoothBootstrapClient
        if (bootstrapClient != null && binding.sendStatusPanel.isVisible) {
            bootstrapClient.cancelPendingConnect()
            connectionJob?.cancel()
            renderTerminal(
                getString(R.string.send_phase_cancelled),
                getString(R.string.send_status_failure_reason, CancelCause.LOCAL.toString()),
            )
            return
        }
        val bleBootstrapClient = bleL2capBootstrapClient
        if (bleBootstrapClient != null && binding.sendStatusPanel.isVisible) {
            bleBootstrapClient.cancelPendingConnect()
            connectionJob?.cancel()
            renderTerminal(
                getString(R.string.send_phase_cancelled),
                getString(R.string.send_status_failure_reason, CancelCause.LOCAL.toString()),
            )
            return
        }
        if (connectionJob?.isActive == true && binding.sendStatusPanel.isVisible) {
            connectionJob?.cancel()
            renderTerminal(
                getString(R.string.send_phase_cancelled),
                getString(R.string.send_status_failure_reason, CancelCause.LOCAL.toString()),
            )
            return
        }
        // Cancel before any peer was selected: stop the BLE pulse now
        // so we don't waste the radio while finish() tears the activity
        // down. onDestroy would also catch this, but stopping here keeps
        // the timing tight for the "stops on user cancel" criterion (#32).
        peerPickerController.stopBleAdvertise()
        finish()
    }

    private fun onShowQrClicked() {
        startActivity(Intent(this, ShowQrActivity::class.java))
    }

    /**
     * Display name for this device that the receiver will see in its
     * consent UI. Falls back to a stable "WhenVivoMeetsGoogle" string
     * when [Build.MODEL] is empty (rare but happens on some emulators).
     */
    private fun senderDeviceLabel(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "WhenVivoMeetsGoogle"

    private fun prepareSenderIdentity() {
        senderEndpointId = OutboundConnection.generateEndpointId()
        senderEndpointInfo =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN).also { SecureRandom().nextBytes(it) },
                deviceName = senderDeviceLabel(),
            )
        senderEndpointInfoBytes = senderEndpointInfo.serialize()
        logOutboundDiagnostic("sender identity: endpointId=$senderEndpointId name=${senderDeviceLabel()}")
    }

    /**
     * Bring up a sender-side GATT server exposing the same `0xFEF3`
     * service shape stock Quick Share peripherals advertise. We do not
     * intend Samsung to actually open a Weave session into us — the
     * server's only job is to expose the advertisement-slot
     * characteristics so Samsung's GMS can read our `EndpointInfo`
     * back when it correlates an inbound GATT-from-us connection
     * against a known peer. Without our own GATT server, Samsung sees
     * us as a "drive-by central" with no reciprocal identity surface,
     * which appears to be one of the predicates gating its per-peer
     * Weave handler registration on `gchu`/`gchk`.
     */
    @Suppress("MissingPermission")
    private fun startSenderGattServer() {
        val server =
            BleGattInitialControlServer(
                context = applicationContext,
                endpointIdProvider = { senderEndpointId.toByteArray(Charsets.US_ASCII) },
            )
        // Sender-side server should never actually accept a real
        // bootstrap from Samsung — log it and immediately close if it
        // somehow does, so we don't leak a half-open transport.
        val started =
            server.start(senderEndpointInfo) { transport ->
                logOutboundDiagnostic(
                    "sender GATT server: unexpected inbound transport medium=${transport.medium}; closing",
                )
                runCatching { transport.close() }
            }
        if (started) {
            senderGattServer = server
            logOutboundDiagnostic("sender GATT server: started")
        } else {
            logOutboundDiagnostic("sender GATT server: not started (unavailable or addService failed)")
        }
    }

    private fun logResolvedFiles(files: List<FileSource>) {
        files.forEachIndexed { index, file ->
            logOutboundDiagnostic(
                "resolved file[$index]: name=${file.name} size=${file.size} " +
                    "mime=${file.mimeType} payloadId=${file.payloadId} parent=${file.parentFolder}",
            )
        }
    }

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

    private fun logOutboundDiagnostic(line: String) {
        Log.e(OUTBOUND_TAG, line)
        appendOutboundLog(line)
    }

    private fun logOutboundWireMessage(line: String) {
        Log.e(OUTBOUND_TAG, line)
        appendOutboundLog(line)
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
    }
}
