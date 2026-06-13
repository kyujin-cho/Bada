/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.bluehouse.bada.R
import dev.bluehouse.bada.bugreport.BugReportFlowSupport
import dev.bluehouse.bada.databinding.ActivitySendBinding
import dev.bluehouse.bada.discovery.NearbyPeer
import dev.bluehouse.bada.discovery.NearbyPeerRoute
import dev.bluehouse.bada.discovery.UserFacingMediumFeatures
import dev.bluehouse.bada.discovery.bootstrap.BleGattInitialControlClient
import dev.bluehouse.bada.discovery.bootstrap.BleGattInitialControlServer
import dev.bluehouse.bada.discovery.bootstrap.BleL2capInitialControlClient
import dev.bluehouse.bada.discovery.bootstrap.BluetoothClassicBootstrapClient
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.discovery.medium.MediumRegistries
import dev.bluehouse.bada.protocol.connection.CancelCause
import dev.bluehouse.bada.protocol.connection.FileSource
import dev.bluehouse.bada.protocol.connection.OutboundConnection
import dev.bluehouse.bada.protocol.connection.OutboundConnectionState
import dev.bluehouse.bada.protocol.connection.OutboundResult
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.qr.DerivedQrKeys
import dev.bluehouse.bada.protocol.qr.GeneratedQrKeyData
import dev.bluehouse.bada.protocol.qr.QrKeyData
import dev.bluehouse.bada.protocol.qr.QrKeyDerivation
import dev.bluehouse.bada.protocol.qr.QrTlvMatcher
import dev.bluehouse.bada.protocol.qr.QrUrl
import dev.bluehouse.bada.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.bada.service.receiver.OutboundSessionActiveHolder
import dev.bluehouse.bada.transfer.KeepScreenOnPreferences
import dev.bluehouse.bada.ui.BackdropBlurView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.SecureRandom
import kotlin.math.min

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
 * `@Suppress("TooManyFunctions", "LargeClass")` — this Activity owns the share-intent
 * lifecycle, discovery, the picker, and the OutboundConnection driver.
 * Splitting it would require non-trivial separation of UI state from
 * coroutine plumbing and is out of scope for the Galaxy interop fix.
 */
@Suppress("TooManyFunctions", "LargeClass")
public class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private lateinit var bugReportFlowSupport: BugReportFlowSupport
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

    /**
     * Set to `true` while a connection attempt is being made AND there
     * is at least one further fallback route to try if it fails. The
     * StateFlow collector consults this flag in
     * [renderConnectionState]'s `Failed` branch to decide whether to
     * lock the card into a "Transfer failed" terminal — when a
     * fallback is queued we suppress that terminal so the next
     * attempt's "Connecting…" state can re-paint without going
     * through the picker→terminal→picker bounce.
     */
    private var pendingFallback: Boolean = false

    /**
     * QR-code/link share session (#28, direction B). While the QR panel
     * is showing, [qrSession] holds the freshly generated keypair whose
     * public X coordinate is published in the QR/link, and [qrDerivedKeys]
     * the HKDF advertising token + name-encryption key derived from it.
     * The discovery callback matches each resolved peer's EndpointInfo
     * against [qrDerivedKeys] via [dev.bluehouse.bada.protocol.qr.QrTlvMatcher];
     * on a match it auto-connects to that peer. [qrSigningKey] is the
     * private half threaded into the outbound connection so it can sign
     * the UKEY2 authString for `qr_code_handshake_data`. [qrMatchConnectStarted]
     * latches the auto-connect to fire at most once per QR session.
     */
    private var qrSession: GeneratedQrKeyData? = null
    private var qrDerivedKeys: DerivedQrKeys? = null
    private var qrSigningKey: PrivateKey? = null
    private var qrMatchConnectStarted: Boolean = false

    /**
     * `SystemClock.elapsedRealtime()` of the first QR match seen with only
     * a BLE route (no Wi-Fi LAN yet), or 0 if none. Used by [chooseQrMatch]
     * to wait briefly for the QR endpoint's Wi-Fi LAN route to surface
     * before falling back to a BLE connection.
     */
    private var qrFirstBleOnlyMatchAtMs: Long = 0L

    private lateinit var senderEndpointId: String
    private lateinit var senderEndpointInfo: EndpointInfo
    private lateinit var senderEndpointInfoBytes: ByteArray

    /**
     * URI of the first image-MIME attachment from the launching share
     * intent, captured in [onCreate] so the success terminal can render
     * a square preview without re-walking the (potentially-revoked)
     * intent payload after the transfer settles. `null` for non-image
     * shares, multi-file shares whose first item is not an image, and
     * folder shares (the folder picker has no single representative
     * file to preview).
     */
    private var sentImagePreviewUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Veto receiver-side mDNS publish for the duration of this
        // activity. When Bada concurrently publishes its receiver-side
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
        bugReportFlowSupport = BugReportFlowSupport.install(this)

        fileSourceFactory = UriFileSourceFactory(contentResolver)
        documentTreeFactory = DocumentTreeFileSourceFactory(contentResolver)
        payloadResolver = SendPayloadResolver(fileSourceFactory, documentTreeFactory)
        // Generate the sender identity up front so the picker controller
        // can thread the endpointId into the BLE FastInitiation pulse's
        // `secret_id_hash`. Stock GMS receivers classify an all-zero hash
        // as `type=SILENT`; a non-zero hash keeps us in the active
        // `type=NOTIFY` path that makes the receiver expose its GATT
        // bootstrap surface.
        prepareSenderIdentity()
        startSenderGattServer()
        peerPickerController =
            SendPeerPickerController(
                context = this,
                binding = binding,
                lifecycle = lifecycle,
                scope = lifecycleScope,
                onPeerSelected = ::onPeerSelected,
                onPeersResolved = ::onQrPeersResolved,
                logDiagnostic = ::logOutboundDiagnostic,
                senderEndpointId = senderEndpointId,
            )

        binding.sendCancelButton.setOnClickListener { onCancelClicked() }
        binding.sendDoneButton.setOnClickListener { finish() }
        binding.sendShowQrButton.setOnClickListener { onShowQrClicked() }
        binding.sendQrClose.setOnClickListener { onQrDoneClicked() }
        binding.sendQrCopyLink.setOnClickListener { onCopyQrLinkClicked() }
        binding.sendNetworkHintDismiss.setOnClickListener { peerPickerController.onHintDismissed() }
        wireHelpLink()
        wireCancelButtonBlur()

        // Capture the first image attachment URI now so the success
        // terminal can render a preview without re-resolving the intent.
        sentImagePreviewUri = extractFirstImageUri(intent)

        // The toolbar carries a floating pill in place of a static
        // title; populate it with the current advertised device name
        // so the user sees who the share is "from" at a glance. The
        // resolver returns the user-set custom name when present and
        // falls back to the platform device-name chain otherwise.
        binding.sendDevicePillText.text = AdvertisedDeviceNames.resolve(this)

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
        applyPayloadSize()
        binding.sendSubtitle.setText(R.string.send_subtitle_discovering)
        peerPickerController.start()
    }

    /**
     * Render the transfer-size line ([R.id.send_payload_size]) from the
     * currently resolved [files]: the formatted total below the
     * file-count headline, or hidden when the total size is unknown.
     * Called for the file-send path and when restoring the picker after
     * a rejection; the text / folder-terminal states leave the line
     * hidden (it defaults to `gone` in the layout).
     */
    private fun applyPayloadSize() {
        val sizeText = PayloadSummary.sizeFor(files)
        if (sizeText != null) {
            binding.sendPayloadSize.text = sizeText
            binding.sendPayloadSize.visibility = View.VISIBLE
        } else {
            binding.sendPayloadSize.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        setTransferKeepScreenOn(active = false)
        super.onDestroy()
        // Cancel any pending fade-out for the rejection banner so its
        // Runnable does not fire against a recycled binding.
        rejectionBannerHideHandler.removeCallbacks(rejectionBannerHideRunnable)
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
        useNearbyMultiplexInitialTransport: Boolean = false,
    ): OutboundConnection? {
        val mediumRegistry = MediumRegistries.defaultForContext(applicationContext)
        return when (route) {
            is NearbyPeerRoute.Lan ->
                OutboundConnection(
                    targetAddress = route.address,
                    port = route.port,
                    endpointId = senderEndpointId,
                    endpointInfo = endpointInfo,
                    useNearbyMultiplexInitialTransport = useNearbyMultiplexInitialTransport,
                    qrSigningKey = qrSigningKey,
                    mediumRegistry = mediumRegistry,
                    logger = ::logOutboundWireMessage,
                )
            is NearbyPeerRoute.BluetoothClassic -> {
                if (
                    !UserFacingMediumFeatures.BLUETOOTH_CLASSIC_BOOTSTRAP_ROUTE_ENABLED &&
                    !UserFacingMediumFeatures.BLUETOOTH_CLASSIC_USER_FACING_ENABLED
                ) {
                    return null
                }
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
                    qrSigningKey = qrSigningKey,
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
                    qrSigningKey = qrSigningKey,
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
                    qrSigningKey = qrSigningKey,
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
                val connection =
                    buildOutboundConnection(
                        route = action.route,
                        endpointInfo = endpointInfo,
                    )
                if (connection != null) {
                    PreparedConnection.Ready(connection)
                } else {
                    logOutboundDiagnostic("bootstrap: initial direct connect failed route=${action.route}")
                    PreparedConnection.Failed("Initial connect failed: bootstrap route unavailable")
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

    /**
     * Discovery callback for the QR-code/link share path (#28, direction
     * B). While a QR session is active, test each resolved peer's
     * EndpointInfo against the QR-derived keys: the device that opened our
     * QR/link is now advertising a type=1 TLV carrying the advertising
     * token (visible mode) or the AES-GCM-encrypted device name (hidden
     * mode). On the first match, latch [qrMatchConnectStarted], stash the
     * QR private key for `qr_code_handshake_data`, dismiss the QR panel,
     * and auto-connect to that peer.
     */
    private fun onQrPeersResolved(resolved: List<NearbyPeer>) {
        val keys = qrDerivedKeys
        if (keys == null || qrMatchConnectStarted) return
        val matches =
            resolved.mapNotNull { peer ->
                peer.endpointInfo?.let { info ->
                    val result = QrTlvMatcher.matches(info, keys)
                    if (result is QrTlvMatcher.QrMatchResult.NoMatch) null else peer to result
                }
            }
        val chosen = chooseQrMatch(matches) ?: return
        qrMatchConnectStarted = true
        qrSigningKey = qrSession?.keyPair?.private
        val (peer, result) = chosen
        logOutboundDiagnostic(
            "qr: connecting matched peer=${peer.stableId} result=${result::class.simpleName} " +
                "route=${if (peer.lanEndpoint != null) "wifi-lan" else "ble"}",
        )
        dismissQrPanelForConnect()
        onPeerSelected(peer)
    }

    /**
     * Pick which QR-matched peer to connect to. Stock Quick Share's QR
     * receiver first advertises the QR token over BLE only, then upgrades
     * the same endpoint to also carry a Wi-Fi LAN route. Wi-Fi LAN is far
     * more reliable than the BLE bootstrap, so prefer a LAN-capable match;
     * if only BLE-only matches exist, wait up to [QR_LAN_WAIT_GRACE_MS] for
     * the LAN route to surface before falling back to BLE.
     */
    @Suppress("ReturnCount")
    private fun chooseQrMatch(
        matches: List<Pair<NearbyPeer, QrTlvMatcher.QrMatchResult>>,
    ): Pair<NearbyPeer, QrTlvMatcher.QrMatchResult>? {
        if (matches.isEmpty()) return null
        matches.firstOrNull { it.first.lanEndpoint != null }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (qrFirstBleOnlyMatchAtMs == 0L) qrFirstBleOnlyMatchAtMs = now
        return if (now - qrFirstBleOnlyMatchAtMs >= QR_LAN_WAIT_GRACE_MS) matches.first() else null
    }

    /**
     * Instantly tear down the QR panel (no exit animation) so the
     * connection status panel can take over when a QR match auto-connects.
     */
    private fun dismissQrPanelForConnect() {
        binding.sendQrPanel.animate().cancel()
        binding.sendQrScroll.visibility = View.GONE
        binding.sendPickerContent.alpha = 1f
        binding.sendPickerContent.visibility = View.VISIBLE
        binding.sendShowQrButton.visibility = View.GONE
    }

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
        proceedWithPeer(peer, plan, chosenRoute)
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
        DiagnosticLog.e(OUTBOUND_TAG, "picked target: $targetSnapshot")
        appendOutboundLog("picked target: $targetSnapshot")
        // Stop peer-list discovery once we've made our pick, but keep
        // the sender wake-up BLE pulse active through the pre-secure
        // bootstrap. Stock Quick Share receivers can drop back to a
        // "no sender nearby" state between a same-Wi-Fi LAN timeout and
        // a BLE GATT fallback if the pulse disappears too early.
        peerPickerController.suspendPicker()

        // Smoothly resize the card outline as the picker chrome
        // collapses and the status panel grows in.
        beginCardBoundsTransition(BOUNDS_DURATION_MS)

        // Hide the picker chrome. The Cancel button is pinned to the
        // card's bottom edge by a weighted scroll wrapper around the peer
        // list; collapsing the wrapper here releases its weight so the
        // status panel below can re-center inside the card.
        binding.sendPeerList.isVisible = false
        binding.sendEmptyState.isVisible = false
        binding.sendNetworkHint.isVisible = false
        binding.sendPeerScroll.isVisible = false
        binding.sendShowQrButton.isVisible = false
        binding.sendHelpLink.isVisible = false
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
        //
        // Connect-attempt loop with transport fallback. The picker
        // ranks all viable routes ahead of time (Wi-Fi LAN > BLE-L2CAP
        // > BLE-GATT > Bluetooth Classic by [SendBootstrapPlan.viableRoutes]);
        // we try the primary first, and if its TCP / initial-control
        // leg fails before a SecureChannel exists, we retry the
        // remaining routes in order. Once the SecureChannel is up, any
        // subsequent failure (UKEY2 mismatch, peer rejection,
        // payload-streaming I/O error) is protocol-layer and keeps the
        // original terminal so the user sees the actual reason.
        connectionJob =
            lifecycleScope.launch {
                val routes = SendBootstrapPlan.viableRoutes(peer)
                if (routes.isEmpty()) {
                    renderTerminal(
                        getString(R.string.send_phase_failed),
                        getString(
                            R.string.send_status_failure_reason,
                            plan.failureReason ?: "no usable initial route",
                        ),
                    )
                    return@launch
                }
                for ((index, route) in routes.withIndex()) {
                    val primaryRoute = (plan.action as? SendBootstrapPlan.Action.Direct)?.route
                    val attemptPlan =
                        if (index == 0 && primaryRoute == route) {
                            plan
                        } else {
                            SendBootstrapPlan.forRoute(peer, route)
                        }
                    val isLastAttempt = index == routes.lastIndex
                    // Suppress the per-attempt terminal while another
                    // attempt may still follow — either a lower-priority
                    // fallback route, or (for a LAN route) an in-place
                    // re-resolve retry against a refreshed address.
                    pendingFallback = !isLastAttempt || route is NearbyPeerRoute.Lan
                    if (index > 0) {
                        // Re-paint the status panel for the fallback
                        // attempt so the user sees the transport switch
                        // rather than a stuck "Connecting…" pose.
                        binding.sendStatusPhase.setText(R.string.send_phase_connecting)
                        binding.sendStatusMessage.text =
                            getString(R.string.send_status_retrying_route, attemptPlan.subtitle)
                        logOutboundDiagnostic(
                            "retry: attempting fallback route=$route after primary failed",
                        )
                    }
                    val outcome = attemptRouteOutcome(peer, route, attemptPlan)
                    pendingFallback = false
                    val shouldFallback =
                        !isLastAttempt &&
                            outcome is OutboundResult.Failed &&
                            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(outcome.reason)
                    if (shouldFallback) {
                        continue
                    }
                    // Not falling back. If the outcome was Failed —
                    // either transport-level on the last attempt, OR
                    // protocol-level on any attempt — the StateFlow
                    // collector may have suppressed the terminal
                    // because [pendingFallback] was still set when the
                    // Failed state arrived. Render the terminal here so
                    // the card always reaches a stable end-of-flow pose.
                    // Completed / Rejected / Cancelled paths render
                    // their own terminals from inside the collector and
                    // are not affected by [pendingFallback].
                    if (outcome is OutboundResult.Failed) {
                        renderTerminal(
                            getString(R.string.send_phase_failed),
                            getString(R.string.send_status_failure_reason, outcome.reason),
                        )
                    }
                    return@launch
                }
            }
    }

    /**
     * Run one route's connect attempt and, for a LAN route, fold in the
     * re-resolve retry (#203). Keeps [proceedWithPeer]'s fallback loop
     * focused on route ordering: a non-failing attempt is returned as-is,
     * and a failing one is handed to [retryLanAfterReresolve], which only
     * acts on a stale-address LAN failure and otherwise returns the
     * original outcome unchanged.
     */
    private suspend fun attemptRouteOutcome(
        peer: NearbyPeer,
        route: NearbyPeerRoute,
        attemptPlan: SendBootstrapPlan,
    ): OutboundResult {
        val firstOutcome = attemptOutbound(peer, attemptPlan)
        if (firstOutcome !is OutboundResult.Failed) return firstOutcome
        return retryLanAfterReresolve(peer, route, firstOutcome) ?: firstOutcome
    }

    /**
     * LAN re-resolve-on-connect-failure retry (issue #203).
     *
     * When a LAN bootstrap fails before a SecureChannel exists, the
     * cached address may be stale — the peer roamed Wi-Fi or its DHCP
     * lease renewed while we held a frozen route snapshot from pick time
     * and discovery was suspended. Re-resolve the peer's current LAN
     * address and, if a fresh LAN route surfaces, retry the connection
     * once with it.
     *
     * Returns the retry's [OutboundResult], or `null` when no re-resolve
     * was warranted (non-LAN route, or a post-secure failure that a new
     * address cannot fix) or no fresh LAN address surfaced in time — in
     * which case the caller keeps the original failure and proceeds to
     * the next viable route.
     */
    @Suppress("ReturnCount") // Two early bail-outs (no re-resolve warranted /
    // no fresh address) plus the retry result read clearer as guards.
    private suspend fun retryLanAfterReresolve(
        peer: NearbyPeer,
        failedRoute: NearbyPeerRoute,
        failure: OutboundResult.Failed,
    ): OutboundResult? {
        if (!LanReresolvePolicy.shouldReresolveLan(failedRoute, failure.reason)) return null
        val previousLan = failedRoute as NearbyPeerRoute.Lan
        val freshLan =
            peerPickerController.reresolveLan(peer, LanReresolvePolicy.DEFAULT_TIMEOUT_MILLIS)
        if (freshLan == null) {
            logOutboundDiagnostic(
                "reresolve: no fresh LAN address for peer=${peer.stableId}; keeping original failure",
            )
            return null
        }
        logOutboundDiagnostic(
            "reresolve: retrying LAN peer=${peer.stableId} " +
                "old=${previousLan.address.hostAddress}:${previousLan.port} " +
                "new=${freshLan.address.hostAddress}:${freshLan.port} " +
                "changed=${LanReresolvePolicy.addressChanged(previousLan, freshLan)}",
        )
        binding.sendStatusPhase.setText(R.string.send_phase_connecting)
        binding.sendStatusMessage.text =
            getString(
                R.string.send_status_retrying_route,
                SendBootstrapPlan.forRoute(peer, freshLan).subtitle,
            )
        return attemptOutbound(peer, SendBootstrapPlan.forRoute(peer, freshLan))
    }

    /**
     * Single connect attempt: build the connection from [plan], wire a
     * StateFlow collector to [renderConnectionState], and await
     * [OutboundConnection.run] to terminal. Returns the `OutboundResult`
     * the driver produced, or `null` when the bootstrap-side build itself
     * could not produce a connection (mediums missing, BT bootstrap
     * client returned null, etc.) — surfaced to the caller as a
     * synthetic [OutboundResult.Failed] so the retry decision stays in
     * one place.
     *
     * `connectionJob`-scope cancellation is honoured by
     * [OutboundConnection.run] internally; this function does not need
     * to add its own try/catch around it. The `finally` block releases
     * the `activeConnection` reference and the bootstrap-client refs
     * so the next iteration of the retry loop starts from a clean slate.
     */
    private suspend fun attemptOutbound(
        peer: NearbyPeer,
        plan: SendBootstrapPlan,
    ): OutboundResult {
        val targetSnapshot =
            peerPickerController.formatPeerSnapshot(
                peer,
                (plan.action as? SendBootstrapPlan.Action.Direct)?.route,
            )
        DiagnosticLog.e(OUTBOUND_TAG, "attempt: $targetSnapshot")
        appendOutboundLog("attempt: $targetSnapshot")
        return when (val prepared = buildOutboundConnection(plan, senderEndpointInfoBytes)) {
            is PreparedConnection.Failed -> OutboundResult.Failed(prepared.reason)
            is PreparedConnection.Ready -> {
                val connection = prepared.connection
                activeConnection = connection
                val collector =
                    lifecycleScope.launch {
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
        setTransferKeepScreenOn(active = state is OutboundConnectionState.Sending)
        // Animate any bounds change in the status panel triggered by
        // this state — chiefly the PIN appearing on
        // AwaitingRemoteAcceptance / Sending and disappearing on
        // Connecting / Handshaking. Idle is a no-op below so the
        // transition is harmless there; calling beginDelayedTransition
        // without a real bounds change just clears any pending
        // transition for the next layout pass.
        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        // Sky-blue gradient backdrop is on only while the PIN is
        // showing — the AwaitingRemoteAcceptance state. Every other
        // state collapses the backdrop back to the default white
        // card surface; doing the toggle once at the top keeps the
        // visibility correct regardless of which `when` branch
        // matches below.
        binding.sendPinStateBackground.visibility =
            if (state is OutboundConnectionState.AwaitingRemoteAcceptance) View.VISIBLE else View.GONE
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
                // Still pre-secure. Keep the sender wake-up BLE pulse
                // active until the receiver has surfaced the acceptance
                // window or the attempt reaches a terminal state.
                binding.sendStatusPhase.setText(R.string.send_phase_handshaking)
                binding.sendPin.visibility = View.GONE
            }
            is OutboundConnectionState.AwaitingRemoteAcceptance -> {
                peerPickerController.stopBleAdvertise()
                binding.sendStatusPhase.setText(R.string.send_phase_awaiting_acceptance)
                binding.sendPin.text = state.pin
                binding.sendPin.visibility = View.VISIBLE
                binding.sendStatusMessage.setText(R.string.send_status_pin_prompt)
            }
            is OutboundConnectionState.Sending -> {
                binding.sendStatusPhase.setText(R.string.send_phase_sending)
                // PIN comparison is over once both sides have ACCEPT'd
                // and we transition into Sending; the verbose status-
                // message line is replaced by the new circular
                // progress disc with a centered integer percentage.
                // Hide the leftover artifacts so the in-flight UI is
                // just phase + target + circle.
                binding.sendPin.visibility = View.GONE
                binding.sendStatusMessage.visibility = View.GONE
                renderCircularProgress(state.progress.bytesTransferred, state.progress.totalSize)
            }
            OutboundConnectionState.Completed ->
                renderTerminal(
                    getString(R.string.send_phase_completed),
                    getString(R.string.send_status_target, peerPickerController.peerLabel(peer)),
                    isSuccess = true,
                )
            is OutboundConnectionState.Rejected ->
                // Receiver-rejection bounce-back. Instead of staying on a
                // terminal "rejected" card, we tear the connection chrome
                // down, restore the picker list, and show a brief
                // toast-style banner above the Cancel button — the user
                // can then pick a different peer without backing out and
                // re-entering the share. The connectionJob's own `finally`
                // block clears `activeConnection` shortly after this state
                // arrives (Rejected is terminal in the FSM; `connection.run`
                // returns immediately afterward), so we do not need to
                // cancel it manually here.
                bounceBackToPickerAfterRejection(peerPickerController.peerLabel(peer))
            is OutboundConnectionState.Cancelled ->
                renderTerminal(
                    getString(R.string.send_phase_cancelled),
                    getString(R.string.send_status_failure_reason, state.cause.toString()),
                )
            is OutboundConnectionState.Failed ->
                if (pendingFallback) {
                    // The current attempt failed BUT another route is
                    // queued — swallow the terminal render so the
                    // status panel stays mounted. The retry-loop in
                    // [proceedWithPeer] will overwrite the phase /
                    // message lines with the next route's "Connecting…"
                    // pose as soon as it builds the next connection.
                    // We still surface the failed phase + reason
                    // briefly so the user sees the transition rather
                    // than a frozen "Connecting…" through two attempts.
                    binding.sendStatusPhase.setText(R.string.send_phase_failed)
                    binding.sendStatusMessage.text =
                        getString(R.string.send_status_failure_reason, state.reason)
                } else {
                    renderTerminal(
                        getString(R.string.send_phase_failed),
                        getString(R.string.send_status_failure_reason, state.reason),
                    )
                }
        }
    }

    private fun setTransferKeepScreenOn(active: Boolean) {
        val keepScreenOn =
            active &&
                KeepScreenOnPreferences
                    .from(this)
                    .isKeepScreenOnDuringTransfersEnabled()
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // -----------------------------------------------------------------
    // Terminal / unsupported / QR
    // -----------------------------------------------------------------

    /**
     * Render a terminal state inside the status panel. The success
     * variant ([isSuccess] = true) suppresses the PIN + status-message
     * lines and surfaces a square image preview when the share was an
     * image MIME — matching the requested "보냈다는 정보 + 이미지 정보만"
     * layout. Failure variants keep the [message] line because it
     * carries the failure reason.
     *
     * Receiver-rejection (`OutboundConnectionState.Rejected`) does
     * NOT go through this renderer — see
     * [bounceBackToPickerAfterRejection] for the toast-banner-on-picker
     * flow that replaces the previous rejection terminal card.
     */
    private fun renderTerminal(
        phaseText: String,
        message: String,
        isSuccess: Boolean = false,
    ) {
        setTransferKeepScreenOn(active = false)
        peerPickerController.stopBleAdvertise()
        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        binding.sendStatusPanel.visibility = View.VISIBLE
        binding.sendStatusPhase.text = phaseText
        binding.sendDoneButton.visibility = View.VISIBLE
        binding.sendCancelButton.visibility = View.GONE
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendPeerScroll.visibility = View.GONE
        binding.sendHelpLink.visibility = View.GONE

        // The PIN is a pairing artifact — once we've reached a terminal
        // state the comparison window is over, so always hide it.
        binding.sendPin.visibility = View.GONE
        // The Sending-state circle is also a transient — collapse it
        // on every terminal so it does not bleed into the success /
        // failure card.
        binding.sendStatusCircleWrapper.visibility = View.GONE
        // The sky-blue PIN backdrop only belongs to the
        // AwaitingRemoteAcceptance state; explicit GONE here covers
        // the path where renderTerminal is called outside of the
        // state-flow collector (e.g. unsupported payload, folder
        // walk failed) without going through renderConnectionState.
        binding.sendPinStateBackground.visibility = View.GONE

        if (isSuccess) {
            // Success path: hide every line that was meaningful only
            // during the picker / sending phase — we already have
            // phase ("Sent successfully") + target on their own, plus
            // the polaroid preview + blurred backdrop below. The
            // previously-shown payload summary ("1 file • 4.2 MB"),
            // picker subtitle ("Tap a device to send"), and verbose
            // status-message line all become noise once the transfer
            // has settled.
            binding.sendPayloadSummary.visibility = View.GONE
            binding.sendPayloadSize.visibility = View.GONE
            binding.sendSubtitle.visibility = View.GONE
            binding.sendStatusMessage.visibility = View.GONE
            renderSuccessPreview(sentImagePreviewUri)
            applyBlurredCardBackground(sentImagePreviewUri)
        } else {
            // Failure / cancel — keep the reason visible and hide the
            // image preview (no successful payload to show).
            binding.sendStatusMessage.visibility = View.VISIBLE
            binding.sendStatusMessage.text = message
            binding.sendTerminalPreviewCard.visibility = View.GONE
            applyBlurredCardBackground(null)
        }
    }

    /**
     * Receiver-rejection flow: tear the connection chrome down,
     * restore the picker list, restart discovery + BLE advertise, and
     * surface a toast-style banner above the Cancel button with
     * "{peer} declined the file transfer". Replaces the older
     * rejection-terminal card so the user can immediately pick a
     * different peer without backing out of the share intent.
     *
     * The banner auto-dismisses with a fade after [REJECTION_BANNER_VISIBLE_MS];
     * an in-flight banner is replaced (not stacked) if a second
     * rejection arrives before the first has faded.
     */
    private fun bounceBackToPickerAfterRejection(peerName: String) {
        beginCardBoundsTransition(BOUNDS_DURATION_MS)

        // Collapse the connection-state chrome.
        binding.sendStatusPanel.visibility = View.GONE
        binding.sendPin.visibility = View.GONE
        binding.sendStatusCircleWrapper.visibility = View.GONE
        binding.sendPinStateBackground.visibility = View.GONE
        binding.sendTerminalPreviewCard.visibility = View.GONE
        binding.sendCardBlur.visibility = View.GONE
        binding.sendCardOverlay.visibility = View.GONE

        // Restore the picker chrome. The peer list contents +
        // empty-state visibility + subtitle are all driven by the
        // controller's `renderPeerList`, which fires on the next
        // discovery event; we just have to re-show the wrapper +
        // floating QR icon and reset the bottom button row.
        binding.sendPayloadSummary.visibility = View.VISIBLE
        applyPayloadSize()
        binding.sendSubtitle.visibility = View.VISIBLE
        binding.sendPeerScroll.visibility = View.VISIBLE
        binding.sendPeerList.visibility = View.VISIBLE
        binding.sendShowQrButton.visibility = View.VISIBLE
        binding.sendHelpLink.visibility = View.VISIBLE
        binding.sendCancelButton.visibility = View.VISIBLE
        binding.sendCancelButton.setText(R.string.send_cancel)
        binding.sendDoneButton.visibility = View.GONE

        // Resume discovery + BLE advertise so a second peer can be
        // picked. `start()` cancels any prior outboundPresenceJob and
        // re-enters its full kick-off sequence (mDNS browse +
        // empty-peer hint timer + BLE pulse).
        peerPickerController.start()

        // Surface the toast banner.
        showRejectionBanner(getString(R.string.send_phase_rejected_by_named, peerName))
    }

    private val rejectionBannerHideHandler = Handler(Looper.getMainLooper())
    private val rejectionBannerHideRunnable =
        Runnable {
            binding.sendRejectionBanner
                .animate()
                .alpha(0f)
                .setDuration(REJECTION_BANNER_FADE_MS)
                .withEndAction {
                    binding.sendRejectionBanner.visibility = View.GONE
                    binding.sendRejectionBanner.alpha = 1f
                }.start()
        }

    /**
     * One-shot wiring for the "Can't find the device?" help link.
     * Adds the underline paint flag (the layout attribute exists for
     * the EditText subtree only, not arbitrary TextViews) and binds
     * the click handler to surface the bottom sheet. Called from
     * `onCreate` once; the link itself is then toggled VISIBLE/GONE
     * alongside the rest of the picker chrome by the connection /
     * terminal renderers below.
     */
    private fun wireHelpLink() {
        val link = binding.sendHelpLink
        link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        link.setOnClickListener { showHelpSheet() }
    }

    /**
     * Turn the Cancel / Done plate into frosted "liquid glass": the
     * [BackdropBlurView] behind the transparent-background button blurs the
     * peer list scrolling behind it (so rows fade out illegibly rather than
     * showing readable text behind the button), tinted with the same
     * frosted-button fill the rest of the app uses, rounded to the button's
     * 24dp corner. The activity content frame is the hierarchy it captures.
     */
    private fun wireCancelButtonBlur() {
        val root = binding.root as? ViewGroup ?: return
        binding.sendCancelBlur.attachBackdropBlur(
            root = root,
            blurRadiusDp = CANCEL_BLUR_RADIUS_DP,
            cornerRadiusDp = CANCEL_BLUR_CORNER_DP,
            tint = ContextCompat.getColor(this, R.color.frosted_button_fill),
        )
    }

    private fun showHelpSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.bottom_sheet_send_help)
        sheet.findViewById<View>(R.id.send_help_sheet_close)?.setOnClickListener {
            sheet.dismiss()
        }
        // On a short landscape window the default half-open peek shows
        // almost none of the troubleshooting content; open the sheet fully
        // (and skip the collapsed stop) so it rises high enough to read
        // without the user having to drag it up.
        sheet.behavior.skipCollapsed = true
        sheet.setOnShowListener {
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.show()
    }

    private fun showRejectionBanner(message: String) {
        val banner = binding.sendRejectionBanner
        // Cancel any in-flight fade so re-entry replaces (not stacks).
        rejectionBannerHideHandler.removeCallbacks(rejectionBannerHideRunnable)
        banner.animate().cancel()

        banner.text = message
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner
            .animate()
            .alpha(1f)
            .setDuration(REJECTION_BANNER_FADE_MS)
            .start()
        rejectionBannerHideHandler.postDelayed(
            rejectionBannerHideRunnable,
            REJECTION_BANNER_VISIBLE_MS,
        )
    }

    /**
     * Render the in-flight `Sending` progress on the circular
     * disc + center percentage. Both the bar fill and the text
     * track the same ratio; the text is rounded to an integer and
     * coerced into [0, 100] so the user never sees "-1%" or "101%"
     * if the reported counters drift slightly past the announced
     * total in either direction.
     *
     * Uses [com.google.android.material.progressindicator.CircularProgressIndicator.setProgressCompat]
     * so the platform animates between values rather than snapping;
     * `setProgressCompat` is also the API that handles the indeterminate
     * → determinate transition cleanly.
     */
    private fun renderCircularProgress(
        bytesTransferred: Long,
        totalSize: Long,
    ) {
        binding.sendStatusCircleWrapper.visibility = View.VISIBLE
        val pct =
            if (totalSize > 0L) {
                ((bytesTransferred.toDouble() / totalSize.toDouble()) * PERCENT_SCALE).toInt().coerceIn(
                    0,
                    PERCENT_SCALE,
                )
            } else {
                0
            }
        if (binding.sendStatusCircle.isIndeterminate) {
            binding.sendStatusCircle.isIndeterminate = false
        }
        binding.sendStatusCircle.setProgressCompat(pct, true)
        binding.sendStatusCirclePct.text = getString(R.string.transfer_progress_percent, pct)
    }

    /**
     * Bind the success-state polaroid preview. The wrapping
     * `send_terminal_preview_card` is the visible polaroid (white
     * rounded surface + 6dp padding for the border + elevation for
     * the shadow); the inner ImageView only holds the bitmap. Falls
     * back to a hidden card when the share carried no image-MIME
     * payload, which keeps the success layout clean instead of
     * showing an empty white square.
     */
    private fun renderSuccessPreview(uri: Uri?) {
        if (uri == null) {
            binding.sendTerminalPreviewCard.visibility = View.GONE
            return
        }
        try {
            binding.sendTerminalPreview.setImageURI(uri)
            binding.sendTerminalPreviewCard.visibility = View.VISIBLE
        } catch (e: SecurityException) {
            Log.w(OUTBOUND_TAG, "preview load failed: ${e.message}")
            binding.sendTerminalPreviewCard.visibility = View.GONE
        }
    }

    /**
     * Paint the sent image as a heavily-blurred backdrop across the
     * entire card surface, with a translucent white sheet on top to
     * keep the foreground text + buttons readable.
     *
     * Bitmap loading is sampled to [BLUR_BG_TARGET_PX] before being
     * handed to the ImageView. Without sampling, ImageView's intrinsic
     * size is the source bitmap's pixel dimensions (often 4000+px on
     * modern phone cameras), and FrameLayout's `wrap_content`
     * measurement uses that intrinsic size for `match_parent` children
     * — meaning the success card would balloon to image size instead
     * of staying the size dictated by the picker / status content.
     * Sampling to a small target keeps the ImageView's intrinsic size
     * trivial; `centerCrop` then scales the small bitmap to fill
     * whatever bounds the FrameLayout actually lays out.
     *
     * The blur effect itself uses [RenderEffect] (API 31+); on older
     * devices the sampled bitmap is shown un-blurred behind the same
     * overlay, which still reads as a soft accent. Passing `null`
     * collapses both layers back to `GONE` so failure terminals look
     * like the pre-success card.
     */
    private fun applyBlurredCardBackground(uri: Uri?) {
        if (uri == null) {
            binding.sendCardBlur.visibility = View.GONE
            binding.sendCardOverlay.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    decodeSampledBitmap(uri, BLUR_BG_TARGET_PX)
                } ?: return@launch
            binding.sendCardBlur.setImageBitmap(bitmap)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                binding.sendCardBlur.setRenderEffect(buildPrettyBlurEffect())
            }
            binding.sendCardBlur.visibility = View.VISIBLE
            binding.sendCardOverlay.visibility = View.VISIBLE
        }
    }

    /**
     * Compose the success-card blur effect: saturation-boosted color
     * filter chained behind a Gaussian blur. The two-step pipeline
     * (saturation first, then blur) gives the iOS-style "vibrancy"
     * feel — richer colors come through the soft halo than a plain
     * Gaussian blur over the original bitmap, which tends to read as
     * washed-out gray.
     *
     * `RenderEffect.createChainEffect` applies the second argument
     * first and the first argument on top, so `chain(blur, saturation)`
     * means "boost saturation, then blur the saturation-boosted
     * version". A `setSaturation(1.4f)` matrix lifts the colors ~40%
     * before the blur smears them — enough to read as vivid without
     * tipping into oversaturated.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun buildPrettyBlurEffect(): RenderEffect {
        val saturationFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(BLUR_SATURATION_BOOST) })
        val saturationEffect = RenderEffect.createColorFilterEffect(saturationFilter)
        val blurEffect =
            RenderEffect.createBlurEffect(
                BLUR_RADIUS_PX,
                BLUR_RADIUS_PX,
                Shader.TileMode.MIRROR,
            )
        return RenderEffect.createChainEffect(blurEffect, saturationEffect)
    }

    /**
     * Decode an image URI into a memory-friendly bitmap whose larger
     * edge is at most [targetPx]. Uses the platform `ImageDecoder`
     * pipeline on API 28+ (which exposes a clean `setTargetSampleSize`
     * call), and falls back to the classic `BitmapFactory` two-pass
     * sampling loop on older devices. Returns `null` on any decode
     * failure so the caller can leave the blur layer hidden.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decodeSampledBitmap(
        uri: Uri,
        targetPx: Int,
    ): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val ratio =
                        maxOf(
                            info.size.width / targetPx,
                            info.size.height / targetPx,
                            1,
                        )
                    decoder.setTargetSampleSize(ratio)
                }
            } else {
                val bounds =
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample =
                    maxOf(
                        bounds.outWidth / targetPx,
                        bounds.outHeight / targetPx,
                        1,
                    )
                val opts =
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w(OUTBOUND_TAG, "decodeSampledBitmap failed for $uri", e)
            null
        }

    /**
     * Pull the first image-MIME stream URI out of the launching share
     * intent. Handles both `ACTION_SEND` (single Uri) and
     * `ACTION_SEND_MULTIPLE` (ArrayList<Uri>) shapes; the folder send
     * action carries a tree URI with no single representative image
     * and is intentionally not previewed. Returns `null` whenever the
     * MIME lookup fails or the first attachment is not an image.
     */
    @Suppress("DEPRECATION")
    private fun extractFirstImageUri(intent: Intent): Uri? {
        val candidate: Uri? =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val list =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        }
                    list?.firstOrNull()
                }
                else -> null
            }
        if (candidate == null) return null
        val mime =
            try {
                contentResolver.getType(candidate)
            } catch (e: SecurityException) {
                Log.w(OUTBOUND_TAG, "preview MIME lookup denied: ${e.message}")
                null
            }
        return candidate.takeIf { mime?.startsWith("image/") == true }
    }

    private fun renderUnsupportedPayload() {
        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        binding.sendPayloadSummary.text = getString(R.string.send_payload_text)
        binding.sendSubtitle.text = getString(R.string.send_unsupported)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendPeerScroll.visibility = View.GONE
        binding.sendHelpLink.visibility = View.GONE
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
        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        binding.sendPayloadSummary.text = getString(R.string.main_send_folder_button)
        binding.sendSubtitle.text = getString(R.string.send_folder_empty)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendPeerScroll.visibility = View.GONE
        binding.sendHelpLink.visibility = View.GONE
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
        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        binding.sendPayloadSummary.text = getString(R.string.main_send_folder_button)
        binding.sendSubtitle.text = getString(R.string.send_folder_walk_failed)
        binding.sendPeerList.visibility = View.GONE
        binding.sendEmptyState.visibility = View.GONE
        binding.sendNetworkHint.visibility = View.GONE
        binding.sendPeerScroll.visibility = View.GONE
        binding.sendHelpLink.visibility = View.GONE
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
            setTransferKeepScreenOn(active = false)
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

    /**
     * Swap the card content from the peer-picker view to the in-card
     * QR panel and animate the transition. The QR bitmap is rendered
     * fresh from a new ECDSA P-256 keypair on every entry to the
     * panel so the URL the receiver sees is single-use; this matches
     * the historical [ShowQrActivity] behavior.
     *
     * Two animations run in parallel for the elastic feel:
     *   * [TransitionManager.beginDelayedTransition] with
     *     [ChangeBounds] makes the card's outline (and every
     *     resizing ancestor) smoothly grow / shrink between the
     *     picker height and the QR-panel height — without this,
     *     the FrameLayout's `wrap_content` jumps to the new size in
     *     a single frame and the rounded card corners snap.
     *   * The panel itself scales 0.7→1.0 and fades 0→1 with an
     *     [OvershootInterpolator] so it pops in from the card
     *     center after a slight overshoot.
     */
    private fun onShowQrClicked() {
        if (binding.sendQrScroll.isVisible) return

        val generated = QrKeyData.generate()
        // Persist the keypair for this QR session: the discovery callback
        // matches resolved peers against the derived keys and the matched
        // connection signs the UKEY2 authString with the private half.
        qrSession = generated
        qrDerivedKeys = QrKeyDerivation.deriveKeys(generated.qrKeyData)
        qrMatchConnectStarted = false
        qrFirstBleOnlyMatchAtMs = 0L
        val url = QrUrl.build(generated.qrKeyData)
        binding.sendQrUrl.text = url

        // Render the bitmap at a high pixel resolution
        // (`QR_SCREEN_FRACTION × min(screenW, screenH)`) so the
        // matrix stays sharp once the ImageView downsamples it to
        // its 200dp display size. The XML layout pins the ImageView
        // to that 200dp footprint; we no longer override
        // `layoutParams` to the bitmap's pixel size, because doing so
        // re-inflated the ImageView to ~360dp and pushed the URL +
        // action buttons off the bottom of the 480dp card.
        val displayMetrics = resources.displayMetrics
        val qrSize = (min(displayMetrics.widthPixels, displayMetrics.heightPixels) * QR_SCREEN_FRACTION).toInt()
        val bitmap = QrBitmapRenderer.render(url, qrSize)
        binding.sendQrBitmap.setImageBitmap(bitmap)

        beginCardBoundsTransition(BOUNDS_DURATION_MS)
        // Cancel any in-flight picker fade-in from a previous Done
        // press and reset its alpha — otherwise toggling QR on/off
        // rapidly could leave the picker stuck at alpha < 1 the next
        // time it is revealed.
        binding.sendPickerContent.animate().cancel()
        binding.sendPickerContent.alpha = 1f
        binding.sendPickerContent.visibility = View.GONE
        // Hide the floating QR icon while the QR panel is on-screen.
        // The icon's whole purpose is to open this panel; leaving it
        // visible means it overlaps the panel's title / body and acts
        // as a redundant control. Restored to VISIBLE in
        // `onQrDoneClicked` once the panel finishes animating out.
        binding.sendShowQrButton.visibility = View.GONE
        binding.sendQrScroll.visibility = View.VISIBLE
        val panel = binding.sendQrPanel
        panel.scaleX = ENTER_INITIAL_SCALE
        panel.scaleY = ENTER_INITIAL_SCALE
        panel.alpha = 0f
        panel
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(OvershootInterpolator(OVERSHOOT_TENSION))
            .start()
    }

    /**
     * Copy the currently-displayed pairing URL into the system
     * clipboard and surface a short toast acknowledging the action.
     * Reads the URL straight off the [Binding.sendQrUrl] TextView so
     * we never have to thread the live URL through fragment state —
     * `onShowQrClicked` already wrote it there before the panel was
     * revealed, and the URL is regenerated on every panel show, so
     * the TextView is the canonical "URL currently on screen"
     * source of truth.
     */
    private fun onCopyQrLinkClicked() {
        val url =
            binding.sendQrUrl.text
                ?.toString()
                .orEmpty()
        if (url.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.show_qr_title), url))
        // Always acknowledge with our own toast. Android 13+ normally
        // shows a system "Copied" overlay, but several OEM ROMs (vivo
        // OriginOS among them, which we test on) suppress or replace it,
        // leaving the tap with no visible feedback — so we surface the
        // confirmation ourselves regardless of platform version.
        Toast.makeText(this, R.string.show_qr_link_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Reverse [onShowQrClicked] in three smooth phases that read as a
     * single continuous shrink.
     */
    private fun onQrDoneClicked() {
        // User dismissed the QR panel without a match — end the QR session
        // so the discovery callback stops auto-matching resolved peers.
        qrDerivedKeys = null
        qrSession = null
        val panel = binding.sendQrPanel
        panel
            .animate()
            .scaleX(EXIT_TARGET_SCALE)
            .scaleY(EXIT_TARGET_SCALE)
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                val picker = binding.sendPickerContent
                // Bring the picker back to layout but transparent so
                // the ChangeBounds transition resolves the FrameLayout
                // height to the picker's measured size (without the
                // alpha=0, the picker would still measure normally —
                // alpha is purely visual; the alpha=0 is what defers
                // the actual reveal until phase 3 below).
                picker.visibility = View.VISIBLE
                picker.alpha = 0f

                val transition =
                    ChangeBounds().apply {
                        duration = BOUNDS_DURATION_MS
                        addListener(
                            object : Transition.TransitionListener {
                                override fun onTransitionStart(transition: Transition) = Unit

                                override fun onTransitionEnd(transition: Transition) {
                                    picker
                                        .animate()
                                        .alpha(1f)
                                        .setDuration(FADE_IN_DURATION_MS)
                                        .start()
                                }

                                override fun onTransitionCancel(transition: Transition) {
                                    // Defensive: never leave the picker
                                    // permanently invisible if the bounds
                                    // animation is interrupted.
                                    picker.alpha = 1f
                                }

                                override fun onTransitionPause(transition: Transition) = Unit

                                override fun onTransitionResume(transition: Transition) = Unit
                            },
                        )
                    }
                TransitionManager.beginDelayedTransition(binding.root, transition)

                binding.sendQrScroll.visibility = View.GONE
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.alpha = 1f
                // Restore the floating QR icon now that the picker is
                // back on-screen. Pairs with the GONE in
                // `onShowQrClicked`.
                binding.sendShowQrButton.visibility = View.VISIBLE
            }.start()
    }

    /**
     * Schedule a [ChangeBounds] transition on the activity's root
     * scene so any view inside it whose laid-out bounds change as a
     * result of the next visibility toggle animates from the old
     * bounds to the new ones. This catches the FrameLayout that
     * holds the picker / QR panels, the MaterialCardView wrapping
     * it, and the surrounding NestedScrollView content height — so
     * the card's rounded outline grows / shrinks smoothly instead
     * of snapping in a single frame.
     */
    private fun beginCardBoundsTransition(durationMs: Long = BOUNDS_DURATION_MS) {
        val transition = ChangeBounds().apply { duration = durationMs }
        TransitionManager.beginDelayedTransition(binding.root, transition)
    }

    /**
     * Display name for this device that the receiver will see in its
     * consent UI. Resolved via [AdvertisedDeviceNames] so the
     * user-set Quick Share display name (Settings → "Quick Share
     * 표시 이름") propagates to the peer's consent dialog. Falls back
     * to the platform device-name chain (Build.MODEL etc.) when the
     * user has not set a custom name; the resolver itself terminates
     * in a stable "Quick Share" string when every candidate is blank.
     *
     * Without this, the sender's outbound `EndpointInfo.deviceName`
     * would be `Build.MODEL` regardless of what the on-screen pill
     * shows, leaving the receiver to render the pre-customisation
     * model number instead of the user-chosen label.
     */
    private fun senderDeviceLabel(): String = AdvertisedDeviceNames.resolve(this)

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
     * service shape stock Quick Share senders can expose. The active
     * Samsung receive path is still sender-as-central into the Galaxy
     * GATT server; this local server exists only as protocol-parity
     * surface for peers that probe the sender advertisement.
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
     * `getExternalFilesDir(null)/bada-outbound.log`. This is a workaround
     * for vivo Funtouch OS, which filters non-system app logcat output
     * even with setprop overrides. Recoverable via:
     *
     *     adb shell run-as dev.bluehouse.bada.debug \\
     *         find /storage/emulated/0/Android/data/dev.bluehouse.bada.debug -name 'bada-outbound.log'
     *
     * or simpler:
     *
     *     adb pull /sdcard/Android/data/dev.bluehouse.bada.debug/files/bada-outbound.log
     */
    private fun appendOutboundLog(line: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "bada-outbound.log")
            f.appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    private fun logOutboundDiagnostic(line: String) {
        DiagnosticLog.e(OUTBOUND_TAG, line)
        appendOutboundLog(line)
    }

    private fun logOutboundWireMessage(line: String) {
        DiagnosticLog.e(OUTBOUND_TAG, line)
        appendOutboundLog(line)
    }

    public companion object {
        /**
         * Custom intent action used by [dev.bluehouse.bada.MainActivity]
         * to forward a `tree://` URI from `ACTION_OPEN_DOCUMENT_TREE`
         * (#38). The URI lives in `intent.data` and the read grant is
         * propagated via [Intent.FLAG_GRANT_READ_URI_PERMISSION] on the
         * launcher side. This action is intentionally NOT exported in
         * the manifest — it is an internal contract between MainActivity
         * and SendActivity.
         */
        public const val ACTION_SEND_FOLDER: String = "dev.bluehouse.bada.action.SEND_FOLDER"

        private const val OUTBOUND_TAG: String = "BadaOutbound"
        private const val PERCENT_SCALE = 100

        /**
         * How long to wait for a QR-matched peer's Wi-Fi LAN route to
         * surface before falling back to a BLE connection (#28). Stock
         * Quick Share advertises the QR token over BLE first and upgrades
         * the same endpoint to Wi-Fi LAN a beat later.
         */
        private const val QR_LAN_WAIT_GRACE_MS: Long = 5000L

        // In-card QR panel animation tunables. Entry uses an
        // overshoot easing so the panel briefly scales past 1.0 before
        // settling — gives the "elastic pop" feel; exit accelerates
        // toward zero for a snappier dismiss. The card's outline
        // resize runs on a slightly shorter ChangeBounds window so the
        // outline finishes settling just as the panel's overshoot
        // begins to settle, rather than chasing the bouncy panel.
        private const val ENTER_INITIAL_SCALE: Float = 0.7f
        private const val EXIT_TARGET_SCALE: Float = 0.7f
        private const val ENTER_DURATION_MS: Long = 350L
        private const val EXIT_DURATION_MS: Long = 200L
        private const val BOUNDS_DURATION_MS: Long = 280L
        private const val FADE_IN_DURATION_MS: Long = 200L
        private const val OVERSHOOT_TENSION: Float = 1.5f

        // Toast-style banner above the Cancel button that surfaces the
        // "{peer} declined the file transfer" message after a receiver
        // rejection. Visible for [REJECTION_BANNER_VISIBLE_MS] before
        // fading out over [REJECTION_BANNER_FADE_MS]; the same fade
        // duration is reused for the fade-in on show.
        private const val REJECTION_BANNER_VISIBLE_MS: Long = 3000L
        private const val REJECTION_BANNER_FADE_MS: Long = 280L

        // Fraction of the shorter screen edge to use for the QR
        // bitmap rendered into the in-card panel. Mirrors the value
        // used by the historical [ShowQrActivity] so the QR stays
        // square and proportionally large in both portrait and
        // landscape.
        private const val QR_SCREEN_FRACTION: Double = 0.75

        // Blur radius (in pixels) applied to the sent image on the
        // success card backdrop. 80f sits in the iOS-material "soft
        // dreamy" zone — large enough that the image reads as a
        // gradient of color rather than a recognizable photo, but
        // not so large that the result collapses into a single
        // muddy hue.
        private const val CANCEL_BLUR_RADIUS_DP: Float = 16f
        private const val CANCEL_BLUR_CORNER_DP: Float = 24f
        private const val BLUR_RADIUS_PX: Float = 80f
        private const val BLUR_SATURATION_BOOST: Float = 1.4f

        // Target pixel size for the blurred card backdrop. Sampling
        // to this size before handing the bitmap to the ImageView
        // keeps `wrap_content` FrameLayout measurement from
        // ballooning to the source bitmap's pixel dimensions — see
        // [applyBlurredCardBackground] for the full rationale. 720px
        // is roughly twice the on-screen card width on a typical
        // ~3x density device, so the blur has plenty of source detail
        // to smooth into a soft gradient without burning memory.
        private const val BLUR_BG_TARGET_PX: Int = 720
    }
}
