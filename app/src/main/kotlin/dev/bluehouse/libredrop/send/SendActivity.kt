/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

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
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.widget.Toast
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.bugreport.BugReportFlowSupport
import dev.bluehouse.libredrop.databinding.ActivitySendBinding
import dev.bluehouse.libredrop.discovery.NearbyPeer
import dev.bluehouse.libredrop.discovery.NearbyPeerRoute
import dev.bluehouse.libredrop.discovery.UserFacingMediumFeatures
import dev.bluehouse.libredrop.discovery.bootstrap.BleGattInitialControlClient
import dev.bluehouse.libredrop.discovery.bootstrap.BleGattInitialControlServer
import dev.bluehouse.libredrop.discovery.bootstrap.BleL2capInitialControlClient
import dev.bluehouse.libredrop.discovery.bootstrap.BluetoothClassicBootstrapClient
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.libredrop.discovery.medium.MediumRegistries
import dev.bluehouse.libredrop.protocol.connection.CancelCause
import dev.bluehouse.libredrop.protocol.connection.FileSource
import dev.bluehouse.libredrop.protocol.connection.OutboundConnection
import dev.bluehouse.libredrop.protocol.connection.OutboundConnectionState
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.qr.QrKeyData
import dev.bluehouse.libredrop.protocol.qr.QrUrl
import dev.bluehouse.libredrop.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.libredrop.service.receiver.OutboundSessionActiveHolder
import kotlinx.coroutines.Dispatchers
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * `@Suppress("TooManyFunctions")` — this Activity owns the share-intent
 * lifecycle, discovery, the picker, and the OutboundConnection driver.
 * Splitting it would require non-trivial separation of UI state from
 * coroutine plumbing and is out of scope for the Galaxy interop fix.
 */
@Suppress("TooManyFunctions")
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
        // activity. When LibreDrop concurrently publishes its receiver-side
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
        binding.sendSubtitle.setText(R.string.send_subtitle_discovering)
        peerPickerController.start()
    }

    override fun onDestroy() {
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
                if (!UserFacingMediumFeatures.BLUETOOTH_CLASSIC_USER_FACING_ENABLED) return null
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
        // Stop discovery and the wake-up pulse once we've made our pick.
        // BLE GATT bootstrap reuses the Bluetooth controller immediately;
        // leaving the pulse active during the dial adds avoidable role
        // churn on stock Quick Share receivers.
        peerPickerController.suspendPicker()
        peerPickerController.stopBleAdvertise()

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
                renderTerminal(
                    getString(R.string.send_phase_failed),
                    getString(R.string.send_status_failure_reason, state.reason),
                )
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
                }
                .start()
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

    private fun showHelpSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.bottom_sheet_send_help)
        sheet.findViewById<View>(R.id.send_help_sheet_close)?.setOnClickListener {
            sheet.dismiss()
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
                ((bytesTransferred.toDouble() / totalSize.toDouble()) * 100).toInt().coerceIn(0, 100)
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
        val saturationFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) })
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
     * Reverse [onShowQrClicked] in three smooth phases that read as a
     * single continuous shrink:
     *
     *   1. Panel collapses — scale 1.0 → 0.7 + alpha 1 → 0 over
     *      [EXIT_DURATION_MS] with an [AccelerateInterpolator] for a
     *      snappy exit.
     *   2. Card outline shrinks — `withEndAction` schedules a
     *      [ChangeBounds] transition right before flipping
     *      visibility, so the FrameLayout's wrap_content recomputes
     *      from qr-height to picker-height under
     *      [BOUNDS_DURATION_MS] of bounds animation rather than
     *      snapping. The picker is brought to `VISIBLE` with
     *      `alpha = 0` so it counts toward the FrameLayout's measured
     *      end-state height — without this the FrameLayout would size
     *      to the gone qr panel and ChangeBounds would have no end
     *      bounds to animate to.
     *   3. Picker fades in — a `TransitionListener` on the
     *      `ChangeBounds` fires `onTransitionEnd` once the outline
     *      finishes shrinking, at which point the picker (Show QR
     *      button, Cancel button, peer list, etc.) fades from
     *      `alpha = 0` to `alpha = 1` over [FADE_IN_DURATION_MS]. So
     *      the picker chrome appears after the card has settled at
     *      its new size, not during the shrink.
     */
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
        val url = binding.sendQrUrl.text?.toString().orEmpty()
        if (url.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.show_qr_title), url))
        // On Android 12L+ the platform shows its own "Copied to
        // clipboard" overlay so a second toast would be redundant.
        // Versions below that have no system surface for the action,
        // so we provide our own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.show_qr_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onQrDoneClicked() {
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
     * consent UI. Falls back to a stable "LibreDrop" string
     * when [Build.MODEL] is empty (rare but happens on some emulators).
     */
    private fun senderDeviceLabel(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "LibreDrop"

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
     * `getExternalFilesDir(null)/libredrop-outbound.log`. This is a workaround
     * for vivo Funtouch OS, which filters non-system app logcat output
     * even with setprop overrides. Recoverable via:
     *
     *     adb shell run-as dev.bluehouse.libredrop.debug \\
     *         find /storage/emulated/0/Android/data/dev.bluehouse.libredrop.debug -name 'libredrop-outbound.log'
     *
     * or simpler:
     *
     *     adb pull /sdcard/Android/data/dev.bluehouse.libredrop.debug/files/libredrop-outbound.log
     */
    private fun appendOutboundLog(line: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "libredrop-outbound.log")
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
         * Custom intent action used by [dev.bluehouse.libredrop.MainActivity]
         * to forward a `tree://` URI from `ACTION_OPEN_DOCUMENT_TREE`
         * (#38). The URI lives in `intent.data` and the read grant is
         * propagated via [Intent.FLAG_GRANT_READ_URI_PERMISSION] on the
         * launcher side. This action is intentionally NOT exported in
         * the manifest — it is an internal contract between MainActivity
         * and SendActivity.
         */
        public const val ACTION_SEND_FOLDER: String = "dev.bluehouse.libredrop.action.SEND_FOLDER"

        private const val OUTBOUND_TAG: String = "LibreDropOutbound"

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
        private const val BLUR_RADIUS_PX: Float = 80f

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
