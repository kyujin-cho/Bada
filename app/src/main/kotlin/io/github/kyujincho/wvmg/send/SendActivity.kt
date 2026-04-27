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
import io.github.kyujincho.wvmg.protocol.connection.FileSource
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnection
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress

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
 */
public class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private lateinit var fileSourceFactory: UriFileSourceFactory

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fileSourceFactory = UriFileSourceFactory(contentResolver)

        binding.sendCancelButton.setOnClickListener { onCancelClicked() }
        binding.sendDoneButton.setOnClickListener { finish() }
        binding.sendShowQrButton.setOnClickListener { onShowQrClicked() }
        binding.sendNetworkHintDismiss.setOnClickListener { onHintDismissed() }

        val parsed = ShareIntentRouter.route(toShareIntent(intent))
        if (parsed == null) {
            renderUnsupportedPayload()
            return
        }

        files = materializeFiles(parsed)
        if (files.isEmpty()) {
            // Currently true for plain-text shares (sender-side text
            // payload support is a follow-up). Surface the same
            // unsupported message so the user gets clear feedback.
            renderUnsupportedPayload()
            return
        }

        binding.sendPayloadSummary.text = PayloadSummary.forFiles(this, files)
        binding.sendSubtitle.setText(R.string.send_subtitle_discovering)
        startDiscovery()
        startEmptyPeerHintTimer()
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
            is DiscoveryEvent.Resolved -> {
                val incoming = event.service
                val existingIndex = peers.indexOfFirst { it.instanceName == incoming.instanceName }
                if (existingIndex >= 0) {
                    peers[existingIndex] = incoming
                } else {
                    peers.add(incoming)
                }
            }
            is DiscoveryEvent.Lost -> {
                peers.removeAll { it.instanceName == event.instanceName }
            }
        }
        renderPeerList()
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

    private fun peerLabel(peer: DiscoveredService): String {
        val name = peer.endpointInfo?.deviceName
        if (!name.isNullOrBlank()) return name
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

        val connection =
            OutboundConnection(
                targetAddress = target,
                port = peer.port,
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

    private fun onCancelClicked() {
        // If a connection is mid-flight, ask it to cancel cleanly so a
        // CancelFrame goes out on the wire. The terminal state will
        // flow back through the StateFlow collector and finish the UI.
        val connection = activeConnection
        if (connection != null && binding.sendStatusPanel.isVisible) {
            connection.cancel()
            return
        }
        finish()
    }

    private fun onShowQrClicked() {
        startActivity(Intent(this, ShowQrActivity::class.java))
    }
}
