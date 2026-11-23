/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import dev.bluehouse.bada.protocol.crypto.D2DKeyDerivation
import dev.bluehouse.bada.protocol.crypto.D2DRole
import dev.bluehouse.bada.protocol.crypto.pin.PinDerivation
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.payload.FileDestinationFactory
import dev.bluehouse.bada.protocol.payload.PayloadAssembler
import dev.bluehouse.bada.protocol.payload.PayloadEvent
import dev.bluehouse.bada.protocol.payload.PayloadTransferEncoder
import dev.bluehouse.bada.protocol.sharing.InboundSharingFsm
import dev.bluehouse.bada.protocol.sharing.InboundSharingState
import dev.bluehouse.bada.protocol.sharing.SharingFrame
import dev.bluehouse.bada.protocol.sharing.SharingFrameType
import dev.bluehouse.bada.protocol.sharing.SharingFrames
import dev.bluehouse.bada.protocol.sharing.SharingFsmEffect
import dev.bluehouse.bada.protocol.sharing.SharingFsmEvent
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.EndOfFrameStream
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.ukey2.Ukey2Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.security.SecureRandom

/**
 * Internal lifecycle driver for [InboundConnection].
 *
 * Owns the per-connection mutable state: framed transport, secure
 * channel, FSM, payload assembler, plus announced/received tracking.
 * Consumed once by [InboundConnection.run] and discarded; there is no
 * resume / re-run path.
 *
 * ### Receive-loop concurrency
 *
 * `kotlinx.coroutines.selects.select` cannot directly suspend on
 * `SecureChannel.receiveOfflineFrame()` (no `onReceive`-style
 * SelectClause). The driver therefore runs a small **inbound pump
 * coroutine** that reads frames sequentially and forwards them onto a
 * channel; the main loop then `select`s between that channel and
 * [externalEvents]. The pump terminates on [EndOfFrameStream] (clean
 * half-close) or on cancellation.
 */
@Suppress(
    "TooManyFunctions", // The lifecycle inherently has many phases; each is one function for readability.
    "LongParameterList", // Constructor takes the connection's collaborators verbatim.
    "LargeClass", // Same shape as OutboundConnectionDriver: one driver per role, documented phase by phase.
)
internal class InboundConnectionDriver(
    private val transport: ConnectedTransport,
    private val secureRandom: SecureRandom,
    private val externalEvents: Channel<ExternalEvent>,
    private val mutableState: MutableStateFlow<InboundConnectionState>,
    private val mutableActiveMedium: MutableStateFlow<Medium>,
    private val mutableActiveWifiFrequencyMhz: MutableStateFlow<Int?>,
    private val factory: FileDestinationFactory,
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    private val onHandshakeComplete: () -> Unit = {},
    private val logger: (String) -> Unit = {},
    /**
     * Wall-clock source for the rate estimator. Defaults to
     * [System.currentTimeMillis]; tests inject a deterministic
     * `LongArray.iterator`-style supplier so EMA samples have stable
     * timestamps.
     */
    private val nowMillisSource: () -> Long = System::currentTimeMillis,
    /**
     * Rate estimator backing [InboundConnectionState.Receiving.progress].
     * One per connection — the driver feeds it on every chunk and the
     * progress publisher reads its [TransferRateEstimator.bytesPerSecond]
     * back out into the published snapshot. Injectable for unit tests
     * that want to exercise rate-aware code paths without timing
     * sensitivity.
     */
    private val rateEstimator: TransferRateEstimator = TransferRateEstimator(),
    /**
     * `safe_to_disconnect_version` advertised on our outgoing
     * `ConnectionResponseFrame` (field 7). Defaults to
     * [OfflineFrames.SAFE_TO_DISCONNECT_VERSION] (1) in production; tests
     * override it (e.g. 0) to emulate a peer with safe-to-disconnect
     * disabled — see issue #200 / [OutboundConnectionDriver].
     */
    private val safeToDisconnectVersion: Int = OfflineFrames.SAFE_TO_DISCONNECT_VERSION,
    /**
     * How long an `UPGRADE_PATH_AVAILABLE` offer waits for the peer to
     * connect to the offered medium before the receiver gives up on it
     * and continues on the bootstrap channel. Tests inject shorter values.
     */
    private val upgradeAcceptTimeoutMillis: Long = BandwidthUpgradeOrchestrator.UPGRADE_TIMEOUT_MILLIS,
) {
    private var framedConnection: FramedConnection? = null
    private var secureChannel: SecureChannel? = null
    private var fsm: InboundSharingFsm? = null
    private val assembler: PayloadAssembler = PayloadAssembler(fileDestinationFactory = factory)

    /** Set once the introduction has been observed; null before that. */
    private var transferMetadata: TransferMetadata? = null

    /** Map from announced `payload_id` to its expected payload type. */
    private val announced: MutableMap<Long, PayloadHeader.PayloadType> = HashMap()

    /**
     * Map from announced FILE `payload_id` to the introduction's
     * `FileMetadata.name`. Used only to fill an empty
     * `PayloadHeader.file_name` (#324 NearDrop, #325 ChromeOS).
     */
    private val announcedFileNames: MutableMap<Long, String> = HashMap()

    /** FILE payload ids already logged as named from the introduction. */
    private val payloadsNamedFromIntroduction: MutableSet<Long> = HashSet()

    /** Successfully received items, in announcement order. */
    private val received: MutableList<ReceivedItem> = mutableListOf()

    /** Cumulative bytes received across all announced payloads. */
    private var bytesReceived: Long = 0L

    /** Sum of `total_size` over the announced items. */
    private var totalSize: Long = 0L

    /** The most recent active payload, for progress UI. */
    private var currentItemPayloadId: Long? = null

    private var currentItemType: PayloadHeader.PayloadType? = null

    /**
     * 4-digit confirmation PIN derived from the UKEY2 `authString`. Set
     * in [runLifecycle] after the handshake; empty before the handshake
     * so an early-failure code path cannot leak uninitialised state.
     */
    private var pin: String = ""

    /**
     * Sender's device name decoded from the peer's `endpoint_info`
     * (carried in the unencrypted `ConnectionRequest` at step 1 of the
     * lifecycle). `null` when the peer advertised in hidden visibility
     * mode (no name on the wire) or when `EndpointInfo.parse` rejects
     * the bytes. The receiver UI in #22 surfaces this on the consent
     * notification so the user knows which device is asking.
     *
     * Captured early so a malformed introduction frame cannot prevent
     * the consent UI from showing the sender identity.
     */
    private var sourceDeviceName: String? = null

    /**
     * Mediums the peer advertised on `ConnectionRequestFrame.mediums`,
     * decoded into the domain enum. Empty before step 1; populated as
     * soon as the unencrypted ConnectionRequest arrives.
     */
    private var peerSupportedMediums: Set<Medium> = emptySet()

    /**
     * The medium the registry selected as the best upgrade target for
     * this connection — i.e. the highest-priority entry shared by both
     * peers, per the ladder. `null` when the intersection is just
     * Wi-Fi LAN (no upgrade is meaningful) or empty. The current driver
     * records this for observability; the transport-swap orchestrator
     * is a separate integration step.
     */
    internal var chosenUpgradeMedium: Medium? = null
        private set

    /**
     * Drive the entire receiver-side lifecycle. Returns the terminal
     * [InboundResult]; throws on coroutine cancellation (handled by
     * the caller).
     */
    suspend fun runLifecycle(): InboundResult {
        mutableState.value = InboundConnectionState.Handshaking
        publishActiveTransport(transport.medium, wifiFrequencyMhz = null)
        val framedTransport = FramedConnection(transport).also { framedConnection = it }

        // Step 1: read the unencrypted ConnectionRequest from the peer.
        val initialFrame = readOfflineFrameUnencrypted(framedTransport)
        check(initialFrame.isConnectionRequest()) {
            "First frame must be ConnectionRequest, got ${initialFrame.v1.type}"
        }

        // Capture the sender's device name from the embedded endpoint_info
        // for the consent UI (#22). EndpointInfo.parse returns null on
        // malformed bytes — that's fine; the UI falls back to a generic
        // label rather than blocking the consent flow.
        sourceDeviceName =
            initialFrame.v1.connectionRequest
                .takeIf { it.hasEndpointInfo() }
                ?.endpointInfo
                ?.toByteArray()
                ?.let { EndpointInfo.parse(it)?.deviceName }

        // Decode the peer's advertised mediums (Phase 4 framework). The
        // intersection with our local registry's current-medium-aware
        // supported set, resolved by the registry's ladder, picks the
        // upgrade target. WIFI_LAN is only meaningful when the existing
        // control channel is already LAN-backed; on a Bluetooth/BLE
        // bootstrap we must ignore it or we would suppress real upgrade
        // candidates by treating "stay on current socket" as if the
        // current socket were LAN. The current driver records the
        // choice for observability; the full
        // BANDWIDTH_UPGRADE_NEGOTIATION transport swap is wired by a
        // later orchestrator integration.
        peerSupportedMediums =
            initialFrame.v1.connectionRequest.mediumsList
                .mapNotNull { Medium.fromConnectionRequestMedium(it) }
                .toSet()
        val chosen =
            mediumRegistry.selectBestUpgradeForCurrentTransport(
                peerSupported = peerSupportedMediums,
                currentMedium = transport.medium,
            )
        // WIFI_LAN is the discovery medium; selecting it means "stay on
        // the current transport". Treat that as `null` so callers do
        // not interpret it as an upgrade trigger.
        chosenUpgradeMedium = chosen?.takeIf { it != Medium.WIFI_LAN }

        // Step 2: UKEY2 server handshake.
        val handshake = Ukey2Server.performHandshake(framedTransport, secureRandom)

        // Step 3: send unencrypted ConnectionResponse{ACCEPT}.
        framedTransport.sendFrame(OfflineFrames.connectionResponse(safeToDisconnectVersion).toByteArray())

        // Step 4: read peer's unencrypted ConnectionResponse.
        val peerResponse = readOfflineFrameUnencrypted(framedTransport)
        check(peerResponse.isConnectionResponse()) {
            "Expected ConnectionResponse, got ${peerResponse.v1.type}"
        }

        // Step 5: derive D2DSessionKeys (role = SERVER, since the
        // receiver responds to UKEY2). This is the role-key swap point;
        // see InboundConnection's KDoc.
        val sessionKeys =
            D2DKeyDerivation.derive(
                dhs = handshake.dhs,
                ukeyClientInitMsg = handshake.clientInitMsg,
                ukeyServerInitMsg = handshake.serverInitMsg,
                role = D2DRole.SERVER,
            )
        pin = PinDerivation.deriveFourDigitPin(sessionKeys.authString)

        // Step 6: SecureChannel takes over.
        val channel = SecureChannel(framedTransport, sessionKeys, secureRandom).also { secureChannel = it }

        // Stock Android may send its first Nearby Share BYTES payload
        // immediately after connection success, before it processes our
        // bandwidth-upgrade offer. Drain one already-buffered frame so the
        // SecureMessage receive sequence remains aligned across the upgrade.
        // BLE/GATT senders may also send UPGRADE_PATH_REQUEST here when
        // their opening ConnectionRequest had to omit higher-bandwidth
        // mediums for receiver compatibility; consume that request and use
        // it as the upgrade candidate set instead of buffering it into the
        // later sharing loop.
        val initialWireFrame = pollBufferedInitialWireFrame(channel)
        val requestedUpgradeMediums =
            initialWireFrame?.let(BandwidthUpgradeFrames::decodeUpgradePathRequestMediums)
        if (requestedUpgradeMediums != null) {
            logger("medium-upgrade: peer requested upgrade mediums=$requestedUpgradeMediums")
        }

        // Step 7: as the Nearby Connections server role, offer the best
        // prepared upgrade medium before the Nearby Share payload
        // negotiation starts. If the sender has already put a sharing
        // payload on the original channel, keep it buffered while the
        // upgrade orchestrator drains the old and upgraded channels in
        // global SecureMessage sequence order.
        val upgradeCandidates = requestedUpgradeMediums ?: peerSupportedMediums
        val peerEndpointId = initialFrame.v1.connectionRequest.endpointId
        val preUpgradeFrames = listOfNotNull(initialWireFrame.takeIf { requestedUpgradeMediums == null })
        if (!transport.medium.carriesNegotiationDuringUpgrade()) {
            return runBlockingUpgradeThenNegotiate(channel, upgradeCandidates, peerEndpointId, preUpgradeFrames)
        }

        // Wi-Fi LAN bootstrap (#288): put the offer on the wire but do NOT
        // park on the provider's accept. A stock GMS sender that ignores
        // (or fails) the offer keeps negotiating on the LAN channel, and a
        // receiver that stops reading that channel while it waits for a
        // Wi-Fi Direct peer that never comes looks dead to the sender —
        // consent never surfaces and the sender times out. Start the
        // sharing FSM on the prior channel right away and let
        // runPendingUpgradeLoop adopt the upgraded transport whenever (if
        // ever) the peer actually connects to it.
        val offer =
            BandwidthUpgradeOrchestrator.offerServerUpgrade(
                oldChannel = channel,
                currentMedium = transport.medium,
                mediumRegistry = mediumRegistry,
                peerSupportedMediums = upgradeCandidates,
                logger = logger,
            )
        val negotiationFsm = startNegotiation(channel)
        val outcome =
            if (offer == null) {
                PendingUpgradeOutcome.Continue(channel, preUpgradeFrames)
            } else {
                runPendingUpgradeLoop(
                    channel = channel,
                    fsm = negotiationFsm,
                    offer = offer,
                    peerEndpointId = peerEndpointId,
                    initialWireFrames = preUpgradeFrames,
                )
            }
        return when (outcome) {
            is PendingUpgradeOutcome.Terminal -> outcome.result
            is PendingUpgradeOutcome.Continue ->
                runReceiveLoop(outcome.channel, negotiationFsm, outcome.bufferedFrames)
        }
    }

    /**
     * Legacy ordering for BLE-based bootstraps: complete (or give up on)
     * the bandwidth upgrade first, then start the sharing negotiation on
     * whichever channel won. BLE GATT / L2CAP control channels keep their
     * quiet negotiation phase — #216 timing is sensitive there and the
     * peer expects the payload-capable medium before any sharing frame.
     */
    private suspend fun runBlockingUpgradeThenNegotiate(
        channel: SecureChannel,
        upgradeCandidates: Set<Medium>,
        peerEndpointId: String,
        preUpgradeFrames: List<OfflineFrame>,
    ): InboundResult {
        val activeTransport =
            BandwidthUpgradeOrchestrator
                .runServerUpgradeIfAvailable(
                    oldChannel = channel,
                    currentMedium = transport.medium,
                    mediumRegistry = mediumRegistry,
                    peerSupportedMediums = upgradeCandidates,
                    peerEndpointId = peerEndpointId,
                    logger = logger,
                    acceptTimeoutMillis = upgradeAcceptTimeoutMillis,
                )
        if (!activeTransport.fallbackChannelUsable) return failDirtyUpgradeFallback(activeTransport)
        val activeChannel = activeTransport.channel.also { secureChannel = it }
        publishActiveTransport(activeTransport.medium, activeTransport.wifiFrequencyMhz)
        val initialWireFrames = preUpgradeFrames + activeTransport.bufferedFrames

        if (activeTransport.medium != transport.medium) {
            logger(
                "medium-upgrade: delaying sharing negotiation " +
                    "${POST_UPGRADE_SHARING_DELAY_MILLIS}ms after ${activeTransport.medium}",
            )
            delay(POST_UPGRADE_SHARING_DELAY_MILLIS)
        }
        val negotiationFsm = startNegotiation(activeChannel)
        return runReceiveLoop(activeChannel, negotiationFsm, initialWireFrames)
    }

    /**
     * Steps 8-10 entry: publish `Negotiating`, send the FSM's opening
     * PAIRED_KEY_ENCRYPTION on [channel], and mark the handshake complete
     * so a racing UI-side cancel() takes the cooperative FSM path (CANCEL
     * + DISCONNECTION on the wire) instead of the pre-handshake fast-path
     * (raw socket close). External events are drained from this point on.
     */
    private suspend fun startNegotiation(channel: SecureChannel): InboundSharingFsm {
        mutableState.value = InboundConnectionState.Negotiating
        val negotiationFsm = InboundSharingFsm(secureRandom = secureRandom).also { fsm = it }
        applyEffects(channel, negotiationFsm.start())
        onHandshakeComplete()
        return negotiationFsm
    }

    /**
     * Service the prior channel (and user events) while [offer] waits for
     * the peer to connect to the upgraded medium. Mirrors
     * `OutboundConnectionDriver.runWifiDirectUpgradeReceiveLoop`: a
     * polling reader rather than the blocking pump, because completing the
     * upgrade needs exclusive, sequence-ordered access to both channels.
     *
     * Exits with [PendingUpgradeOutcome.Continue] once the offer is
     * settled either way (upgraded, timed out, declined by the peer, or
     * failed) so the regular blocking receive loop takes over on the
     * winning channel, or with [PendingUpgradeOutcome.Terminal] when the
     * session ended before that.
     */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun runPendingUpgradeLoop(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        offer: ServerUpgradeOffer,
        peerEndpointId: String,
        initialWireFrames: List<OfflineFrame>,
    ): PendingUpgradeOutcome =
        coroutineScope {
            val bufferedFrames = ArrayDeque(initialWireFrames)
            val accept = offer.launchAccept(this, logger)
            val deadlineMillis = nowMillisSource() + upgradeAcceptTimeoutMillis
            var nextKeepAliveDueMillis = nowMillisSource() + KeepAliveTicker.DEFAULT_INTERVAL_MILLIS
            val keepAliveTick: suspend () -> DriverEvent? = tick@{
                if (nowMillisSource() < nextKeepAliveDueMillis) return@tick null
                nextKeepAliveDueMillis = nowMillisSource() + KeepAliveTicker.DEFAULT_INTERVAL_MILLIS
                sendNegotiationKeepAlive(channel)
            }
            var settled = false
            try {
                while (true) {
                    if (fsm.state == InboundSharingState.Disconnected) {
                        return@coroutineScope PendingUpgradeOutcome.Terminal(terminalResultFromState())
                    }
                    if (accept.isCompleted) {
                        settled = true
                        return@coroutineScope adoptAcceptedTransport(
                            channel = channel,
                            offer = offer,
                            transport = accept.await(),
                            peerEndpointId = peerEndpointId,
                            bufferedFrames = bufferedFrames,
                        )
                    }
                    if (nowMillisSource() >= deadlineMillis) {
                        settled = true
                        return@coroutineScope abandonPendingUpgrade(
                            channel = channel,
                            offer = offer,
                            accept = accept,
                            reason = "server accept timed out/failed for ${offer.medium}",
                            notifyPeer = true,
                            bufferedFrames = bufferedFrames,
                        )
                    }

                    val event = nextPendingUpgradeEvent(channel, bufferedFrames) ?: keepAliveTick()
                    if (event == null) {
                        delay(PENDING_UPGRADE_POLL_DELAY_MILLIS)
                        continue
                    }
                    if (event is DriverEvent.Wire && event.frame.isUpgradeFailure()) {
                        settled = true
                        return@coroutineScope abandonPendingUpgrade(
                            channel = channel,
                            offer = offer,
                            accept = accept,
                            reason = "peer reported UPGRADE_FAILURE for ${offer.medium}",
                            notifyPeer = false,
                            bufferedFrames = bufferedFrames,
                        )
                    }
                    val terminal = handleDriverEvent(channel, fsm, event)
                    if (terminal != null) return@coroutineScope PendingUpgradeOutcome.Terminal(terminal)
                }
                error("unreachable")
            } finally {
                if (!settled) {
                    // Session ended (peer disconnect, local cancel, failure)
                    // with the offer still open: close the listener first so
                    // a provider parked in accept() unblocks, then join.
                    offer.provider.cancelPendingUpgrade()
                    accept.cancelAndJoin()
                }
            }
        }

    /** Settle an open offer without upgrading and keep the prior channel. */
    @Suppress("LongParameterList")
    private suspend fun abandonPendingUpgrade(
        channel: SecureChannel,
        offer: ServerUpgradeOffer,
        accept: Deferred<UpgradedTransport?>,
        reason: String,
        notifyPeer: Boolean,
        bufferedFrames: ArrayDeque<OfflineFrame>,
    ): PendingUpgradeOutcome.Continue {
        offer.abandon(
            oldChannel = channel,
            reason = "$reason; continuing on ${transport.medium}",
            notifyPeer = notifyPeer,
            logger = logger,
            pendingAccept = accept,
        )
        return PendingUpgradeOutcome.Continue(channel, bufferedFrames.toList())
    }

    /**
     * The peer connected to the offered medium (or the provider gave up):
     * finish the swap and hand back the channel the receive loop should
     * continue on.
     */
    private suspend fun adoptAcceptedTransport(
        channel: SecureChannel,
        offer: ServerUpgradeOffer,
        transport: UpgradedTransport?,
        peerEndpointId: String,
        bufferedFrames: ArrayDeque<OfflineFrame>,
    ): PendingUpgradeOutcome {
        if (transport == null) {
            offer.abandon(
                oldChannel = channel,
                reason = "server accept failed for ${offer.medium}; continuing on ${this.transport.medium}",
                notifyPeer = true,
                logger = logger,
            )
            return PendingUpgradeOutcome.Continue(channel, bufferedFrames.toList())
        }
        val activeTransport =
            BandwidthUpgradeOrchestrator.completeOfferedUpgrade(
                oldChannel = channel,
                currentMedium = this.transport.medium,
                offer = offer,
                transport = transport,
                peerEndpointId = peerEndpointId,
                logger = logger,
            )
        return if (!activeTransport.fallbackChannelUsable) {
            PendingUpgradeOutcome.Terminal(failDirtyUpgradeFallback(activeTransport))
        } else {
            bufferedFrames.addAll(activeTransport.bufferedFrames)
            if (activeTransport.channel !== channel) settleOnUpgradedChannel(activeTransport)
            PendingUpgradeOutcome.Continue(activeTransport.channel, bufferedFrames.toList())
        }
    }

    /**
     * Switch bookkeeping to the upgraded channel and hold the same settle
     * window the blocking path keeps before the first write on it.
     */
    private suspend fun settleOnUpgradedChannel(activeTransport: ActiveTransportChannel) {
        secureChannel = activeTransport.channel
        publishActiveTransport(activeTransport.medium, activeTransport.wifiFrequencyMhz)
        logger(
            "medium-upgrade: delaying sharing traffic " +
                "${POST_UPGRADE_SHARING_DELAY_MILLIS}ms after ${activeTransport.medium}",
        )
        delay(POST_UPGRADE_SHARING_DELAY_MILLIS)
    }

    /**
     * Non-blocking event source for [runPendingUpgradeLoop]: buffered
     * frames first, then a pending user event, then one wire frame if the
     * socket already has bytes for us. `null` means "nothing right now".
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    private suspend fun nextPendingUpgradeEvent(
        channel: SecureChannel,
        bufferedFrames: ArrayDeque<OfflineFrame>,
    ): DriverEvent? {
        if (bufferedFrames.isNotEmpty()) return DriverEvent.Wire(bufferedFrames.removeFirst())
        externalEvents.tryReceive().getOrNull()?.let { return DriverEvent.External(it) }
        if (!channel.hasBufferedInput()) return null
        return try {
            DriverEvent.Wire(channel.receiveOfflineFrame())
        } catch (e: EndOfFrameStream) {
            DriverEvent.PeerClosed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DriverEvent.PumpError(e)
        }
    }

    /**
     * Inline KEEP_ALIVE for the pending-upgrade phase (the ticker
     * coroutine only runs inside [runReceiveLoop]). A failed write doubles
     * as the peer-gone detector: the polling reader cannot see EOF because
     * `available()` reports 0 for a closed socket too.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendNegotiationKeepAlive(channel: SecureChannel): DriverEvent? =
        try {
            channel.sendOfflineFrame(OfflineFrames.keepAlive(ack = false))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(
                "keep-alive: pending-upgrade tick failed " +
                    "(${e::class.simpleName}: ${e.message}); treating peer as gone",
            )
            DriverEvent.PeerClosed
        }

    private fun OfflineFrame.isUpgradeFailure(): Boolean =
        hasV1() &&
            v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
            v1.bandwidthUpgradeNegotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE

    /**
     * Whether the bootstrap medium can keep carrying Nearby Share
     * negotiation frames while a bandwidth upgrade is pending. BLE-based
     * control channels cannot (see [runBlockingUpgradeThenNegotiate]).
     */
    private fun Medium.carriesNegotiationDuringUpgrade(): Boolean = this != Medium.BLE && this != Medium.BLE_L2CAP

    private sealed interface PendingUpgradeOutcome {
        data class Terminal(
            val result: InboundResult,
        ) : PendingUpgradeOutcome

        data class Continue(
            val channel: SecureChannel,
            val bufferedFrames: List<OfflineFrame>,
        ) : PendingUpgradeOutcome
    }

    /**
     * Terminal for a server upgrade that failed after the peer's
     * teardown-commitment point (our CLIENT_INTRODUCTION_ACK went out, so
     * the sender has stopped reading the prior channel): running the whole
     * sharing session on the handed-back channel would stall against a dead
     * socket (#260).
     */
    private fun failDirtyUpgradeFallback(activeTransport: ActiveTransportChannel): InboundResult {
        val reason =
            "bandwidth upgrade failed after prior-channel teardown began; " +
                "cannot continue on ${activeTransport.medium}"
        logger("medium-upgrade: $reason")
        mutableState.value = InboundConnectionState.Failed(reason)
        return InboundResult.Failed(reason)
    }

    private fun publishActiveTransport(
        medium: Medium,
        wifiFrequencyMhz: Int?,
    ) {
        mutableActiveMedium.value = medium
        mutableActiveWifiFrequencyMhz.value = wifiFrequencyMhz.takeIf { medium == Medium.WIFI_DIRECT }
    }

    private suspend fun pollBufferedInitialWireFrame(channel: SecureChannel): OfflineFrame? {
        repeat(INITIAL_FRAME_PROBE_ATTEMPTS) {
            val available = runCatching { transport.inputStream.available() }.getOrDefault(0)
            if (available > 0) {
                return channel.receiveOfflineFrame()
            }
            delay(INITIAL_FRAME_PROBE_DELAY_MILLIS)
        }
        return null
    }

    /**
     * Tear down all owned resources. Safe to call multiple times; each
     * closeable swallows its own IOException so a single failing close
     * cannot leak the others. Closes in dependency order (SecureChannel,
     * FramedConnection / socket, assembler), then drops every still-
     * pending FILE destination via [FileDestinationFactory.abortAll] so
     * partial files do not leak into the user's Downloads.
     *
     * The factory call MUST come after [PayloadAssembler.reset] so that
     * the assembler has already closed its writable channels; otherwise
     * the abort path could observe a still-open `OutputStream` and
     * silently lose the in-progress chunk's bytes. (NearDrop accepts
     * this exact ordering.)
     */
    fun tearDown() {
        runCatching { secureChannel?.close() }
        runCatching { framedConnection?.close() }
        runCatching { transport.close() }
        runCatching { assembler.reset() }
        // Best-effort drop every reserved-but-not-committed destination.
        // For factories without commit semantics (TempFile, in-memory)
        // this is a documented no-op; for MediaStoreDownloadsFactory
        // this is the path that deletes partial Downloads rows.
        runCatching { factory.abortAll() }
    }

    /**
     * Main receive loop. Spawns an inbound pump coroutine, then
     * interleaves wire-frame and external-event delivery via [select].
     */
    private suspend fun runReceiveLoop(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        initialWireFrames: List<OfflineFrame> = emptyList(),
    ): InboundResult =
        coroutineScope {
            // RENDEZVOUS capacity (the default) means the inbound pump
            // suspends on `send` until the dispatch loop is ready to
            // `receive`. This back-pressures a peer that tries to fire
            // frames faster than we process them: the pump simply
            // stops reading the SecureChannel, the SecureChannel stops
            // reading the FramedConnection, and the FramedConnection's
            // TCP receive buffer fills up. Anything looser (UNLIMITED,
            // BUFFERED) would let an attacker grow this process's heap
            // without bound by spamming small frames.
            val wireChannel: Channel<WireMessage> = Channel(Channel.RENDEZVOUS)
            val pumpJob: Job = launch { runInboundPump(channel, wireChannel) }
            val keepAliveJob: Job = launch { runKeepAliveTicker(channel) }
            try {
                dispatchLoop(channel, fsm, wireChannel, initialWireFrames)
            } finally {
                keepAliveJob.cancelAndJoin()
                // Close the active channel BEFORE cancelAndJoin: the pump is parked
                // in SecureChannel.receiveOfflineFrame()'s blocking Socket
                // read under withContext(Dispatchers.IO), which does NOT
                // honour coroutine cancellation while parked in a syscall.
                // Closing the channel breaks that read even after a
                // bandwidth upgrade swaps the wire from the original TCP
                // socket to a provider-owned transport. Any final writes
                // (e.g. Disconnection from the Cancelled-state path in
                // terminalResultFromState) must run BEFORE the dispatchLoop
                // returns — by the time we reach this finally there are no
                // more outbound writes pending.
                runCatching { channel.close() }
                runCatching { transport.close() }
                pumpJob.cancelAndJoin()
            }
        }

    /**
     * Receiver-side KEEP_ALIVE ticker. Stock Android expects both peers
     * to emit keep-alives after the secure channel is established; without
     * this, long Galaxy -> Bada transfers can be cancelled by the sender
     * even while FILE payload chunks are still flowing.
     */
    private suspend fun runKeepAliveTicker(channel: SecureChannel) {
        KeepAliveTicker.run(
            send = channel::sendOfflineFrame,
        )
    }

    /**
     * Inbound pump. Reads frames from the [SecureChannel] in a loop and
     * forwards them onto [wireChannel]. On clean half-close emits
     * [WireMessage.Closed]; on any other I/O failure emits
     * [WireMessage.Error]. Closing the [SecureChannel] is enough to
     * break out of `receiveOfflineFrame`.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun runInboundPump(
        channel: SecureChannel,
        wireChannel: Channel<WireMessage>,
    ) {
        try {
            while (true) {
                val frame = channel.receiveOfflineFrame()
                wireChannel.send(WireMessage.Frame(frame))
            }
        } catch (e: EndOfFrameStream) {
            wireChannel.send(WireMessage.Closed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            wireChannel.trySend(WireMessage.Error(e))
        }
    }

    /**
     * Dispatch loop -- runs until a terminal state is reached. Selects
     * between wire frames and external events; the FSM is the source of
     * truth for termination.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun dispatchLoop(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        wireChannel: Channel<WireMessage>,
        initialWireFrames: List<OfflineFrame> = emptyList(),
    ): InboundResult {
        val bufferedFrames =
            ArrayDeque<OfflineFrame>().apply {
                addAll(initialWireFrames)
            }
        while (true) {
            if (fsm.state == InboundSharingState.Disconnected) {
                return terminalResultFromState()
            }

            val event: DriverEvent =
                if (bufferedFrames.isNotEmpty()) {
                    bufferedFrames
                        .removeFirst()
                        .let { frame -> DriverEvent.Wire(frame) }
                } else {
                    select {
                        externalEvents.onReceive { ev -> DriverEvent.External(ev) }
                        wireChannel.onReceive { msg ->
                            when (msg) {
                                is WireMessage.Frame -> DriverEvent.Wire(msg.frame)
                                WireMessage.Closed -> DriverEvent.PeerClosed
                                is WireMessage.Error -> DriverEvent.PumpError(msg.cause)
                            }
                        }
                    }
                }

            val terminal = handleDriverEvent(channel, fsm, event)
            if (terminal != null) return terminal
        }
    }

    /** Handle one driver-level event. Non-null result triggers termination. */
    private suspend fun handleDriverEvent(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        event: DriverEvent,
    ): InboundResult? =
        when (event) {
            is DriverEvent.External -> {
                handleExternalEvent(channel, fsm, event.event)
                null
            }
            is DriverEvent.Wire -> handleInboundOfflineFrame(channel, fsm, event.frame)
            DriverEvent.PeerClosed -> handlePeerClosed()
            is DriverEvent.PumpError -> {
                val reason = "Inbound pump error: ${event.cause::class.simpleName}: ${event.cause.message}"
                mutableState.value = InboundConnectionState.Failed(reason)
                InboundResult.Failed(reason)
            }
        }

    /**
     * Handles one inbound `OfflineFrame` from the SecureChannel. Returns
     * a non-null [InboundResult] when the frame caused a terminal
     * transition; null to continue the receive loop.
     */
    @Suppress("ReturnCount") // One return per frame-shape branch keeps the dispatch readable.
    private suspend fun handleInboundOfflineFrame(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        frame: OfflineFrame,
    ): InboundResult? {
        if (frame.isDisconnection()) {
            // Peer is leaving. Treat as completion if every announced
            // item arrived; otherwise as cancel.
            val disconnection = frame.v1.disconnection
            logger(
                "wire: received DISCONNECTION " +
                    "requestSafe=${disconnection.requestSafeToDisconnect} " +
                    "ackSafe=${disconnection.ackSafeToDisconnect} " +
                    "received=${received.size}/${announced.size} " +
                    "currentPayloadId=$currentItemPayloadId",
            )
            if (disconnection.requestSafeToDisconnect) {
                runCatching {
                    channel.sendOfflineFrame(
                        OfflineFrames.disconnection(ackSafeToDisconnect = true),
                    )
                }.onFailure { cause ->
                    logger(
                        "wire: failed safe-disconnect ack " +
                            "${cause::class.simpleName}: ${cause.message}",
                    )
                }
            }
            return if (received.size == announced.size && announced.isNotEmpty()) {
                finalizeCompleted(channel)
            } else {
                publishCancelled(CancelCause.PEER)
                InboundResult.Cancelled(CancelCause.PEER)
            }
        }

        if (frame.hasV1() &&
            frame.v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION
        ) {
            // Inbound BANDWIDTH_UPGRADE_NEGOTIATION on the receiver
            // side: stock Quick Share senders do not initiate the
            // upgrade (the protocol gives the SERVER role to the
            // receiver), so this branch fires only when the peer is a
            // non-conforming implementation. Phase 4 sub-issue #54
            // wires the negotiator FSM in here; today we observe the
            // event and drop it.
            return null
        }

        if (!frame.hasV1() || frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) {
            // KEEP_ALIVE and other non-PAYLOAD_TRANSFER frames are
            // ignored: NearDrop does the same. We do not emit ACKs
            // because Quick Share peers we have observed do not
            // require them.
            return null
        }
        val payloadTransfer = nameFromIntroductionIfMissing(frame.v1.payloadTransfer)
        val payloadEvent = assembler.onPayloadTransfer(payloadTransfer)
        return handlePayloadEvent(channel, fsm, payloadEvent)
    }

    /**
     * Give a FILE payload whose header has an empty or missing
     * `file_name` the name announced in the introduction (#324, #325). Frames that already
     * carry a name pass through unchanged; see [withAnnouncedFileName].
     */
    private fun nameFromIntroductionIfMissing(frame: PayloadTransferFrame): PayloadTransferFrame {
        val named = frame.withAnnouncedFileName(announcedFileNames)
        if (named !== frame && payloadsNamedFromIntroduction.add(frame.payloadHeader.id)) {
            logger(
                "payload: FILE payloadId=${frame.payloadHeader.id} has an empty " +
                    "file_name; using the introduction's FileMetadata.name",
            )
        }
        return named
    }

    /**
     * Decode a [PayloadEvent] into FSM events / received items / progress.
     * Returns a terminal [InboundResult] when the event drove the
     * connection to completion.
     */
    @Suppress("ReturnCount")
    private suspend fun handlePayloadEvent(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        event: PayloadEvent,
    ): InboundResult? {
        when (event) {
            is PayloadEvent.BytesComplete -> return handleBytesComplete(channel, fsm, event)
            is PayloadEvent.FileComplete -> return handleFileComplete(event)
            is PayloadEvent.Progress -> updateProgress(event)
            is PayloadEvent.Ignored -> Unit
        }
        return null
    }

    /**
     * Either a negotiation BYTES payload (Sharing.Nearby.Frame) or a
     * text payload announced in the introduction. Disambiguated by
     * whether `payload_id` is in [announced].
     */
    private suspend fun handleBytesComplete(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        event: PayloadEvent.BytesComplete,
    ): InboundResult? {
        val announcedType = announced[event.payloadId]
        if (announcedType != null) {
            received += ReceivedItem.Text(payloadId = event.payloadId, data = event.data)
            currentItemPayloadId = null
            currentItemType = null
            updateProgressForCompletion()
            return maybeFinalize(channel)
        }
        // Otherwise it's a negotiation frame. Parse and feed the FSM.
        val sharingFrame = SharingFrames.parse(event.data)
        logger(
            "sharing: received ${sharingFrame.v1.type} payloadId=${event.payloadId} " +
                "bytes=${event.data.size} state=${fsm.state} ${sharingFrame.summary()}",
        )
        // Disambiguate peer-cancel from local-cancel before applyEffects
        // sees the Cancelled effect — both code paths emit the same FSM
        // effect, so the driver has to set the cause itself based on the
        // event source. Publishing here also short-circuits the
        // `else if (... !is Cancelled)` guard inside applyEffects, so the
        // effect handler does not overwrite this with LOCAL.
        if (sharingFrame.v1.type == SharingFrameType.CANCEL &&
            mutableState.value !is InboundConnectionState.Cancelled
        ) {
            publishCancelled(CancelCause.PEER)
        }
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharingFrame))
        applyEffects(channel, effects)
        return null
    }

    /** FILE payloads are always announced. */
    private suspend fun handleFileComplete(event: PayloadEvent.FileComplete): InboundResult? {
        // Publish the destination now that the assembler reported a
        // clean LAST_CHUNK. For MediaStoreDownloadsFactory this clears
        // IS_PENDING so the file becomes visible in the system Downloads
        // UI; for stateless factories (TempFile, in-memory) it is a
        // documented no-op. Doing this BEFORE recording the
        // ReceivedItem is deliberate: a commit failure should surface
        // to the caller as a Failed terminal, not as a "successful"
        // received-item that secretly never made it past the pending
        // bucket.
        runCatching { factory.commit(event.payloadId) }
        received +=
            ReceivedItem.File(
                payloadId = event.payloadId,
                header = event.header,
                bytesWritten = event.bytesWritten,
            )
        currentItemPayloadId = null
        currentItemType = null
        updateProgressForCompletion()
        return maybeFinalize(secureChannel ?: error("SecureChannel must be set"))
    }

    /**
     * Update [InboundConnectionState.Receiving] in response to a
     * non-final DATA chunk. No-op for unannounced (negotiation) BYTES
     * payloads.
     */
    private fun updateProgress(event: PayloadEvent.Progress) {
        if (event.payloadId !in announced) return
        currentItemPayloadId = event.payloadId
        currentItemType = event.type
        publishReceiving(itemBytes = event.bytesReceived)
    }

    /** Bump the cumulative byte counter and republish. */
    private fun updateProgressForCompletion() {
        val last = received.lastOrNull() ?: return
        val itemSize =
            when (last) {
                is ReceivedItem.File -> last.bytesWritten
                is ReceivedItem.Text -> last.data.size.toLong()
            }
        bytesReceived += itemSize
        publishReceiving(itemBytes = 0L)
    }

    private fun publishReceiving(itemBytes: Long) {
        val cumulative = bytesReceived + itemBytes
        // Feed the rate estimator on every chunk so its EMA tracks the
        // instantaneous Wi-Fi LAN throughput, then publish the smoothed
        // bytes/sec back through TransferProgress so observers can
        // render a rate + ETA without re-deriving the maths.
        rateEstimator.sample(bytesTransferred = cumulative, nowMillis = nowMillisSource())
        mutableState.value =
            InboundConnectionState.Receiving(
                progress =
                    TransferProgress.of(
                        bytesTransferred = cumulative,
                        totalSize = totalSize,
                        bytesPerSecond = rateEstimator.bytesPerSecond(),
                    ),
                currentItemPayloadId = currentItemPayloadId,
                currentItemType = currentItemType,
            )
    }

    /**
     * If every announced item has arrived, close gracefully. Returns
     * the terminal [InboundResult.Completed] in that case; null
     * otherwise.
     */
    private suspend fun maybeFinalize(channel: SecureChannel): InboundResult? {
        if (received.size != announced.size || announced.isEmpty()) return null
        return finalizeCompleted(channel)
    }

    private suspend fun finalizeCompleted(channel: SecureChannel): InboundResult {
        runCatching { channel.sendOfflineFrame(OfflineFrames.disconnection()) }
        val items = received.toList()
        mutableState.value = InboundConnectionState.Completed(items)
        return InboundResult.Completed(items)
    }

    /**
     * Peer cleanly closed the TCP half-channel between frames. Treat as
     * completion if every announced item arrived; otherwise cancel-ish.
     */
    private fun handlePeerClosed(): InboundResult =
        if (received.size == announced.size && announced.isNotEmpty()) {
            logger("wire: peer closed after all payloads arrived received=${received.size}")
            val items = received.toList()
            mutableState.value = InboundConnectionState.Completed(items)
            InboundResult.Completed(items)
        } else {
            logger(
                "wire: peer closed before payload completion " +
                    "received=${received.size}/${announced.size} " +
                    "currentPayloadId=$currentItemPayloadId",
            )
            publishCancelled(CancelCause.PEER)
            InboundResult.Cancelled(CancelCause.PEER)
        }

    /**
     * Apply an FSM effect list. The FSM guarantees `SendFrame` precedes
     * any terminal notification in the same list, so iterating
     * top-to-bottom puts outbound bytes onto the wire before the
     * connection tears down.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per SharingFsmEffect variant is the cleanest dispatch.
    private suspend fun applyEffects(
        channel: SecureChannel,
        effects: List<SharingFsmEffect>,
    ) {
        for (effect in effects) {
            when (effect) {
                is SharingFsmEffect.SendFrame -> sendSharingFrame(channel, effect)
                is SharingFsmEffect.IntroductionReceived -> handleIntroduction(effect)
                SharingFsmEffect.ReadyToReceivePayloads -> publishInitialReceiving()
                is SharingFsmEffect.Rejected -> {
                    mutableState.value = InboundConnectionState.Rejected
                }
                SharingFsmEffect.Cancelled -> {
                    val rejected = effects.any { it is SharingFsmEffect.Rejected }
                    if (rejected) {
                        mutableState.value = InboundConnectionState.Rejected
                    } else if (mutableState.value !is InboundConnectionState.Cancelled) {
                        publishCancelled(CancelCause.LOCAL)
                    }
                }
                SharingFsmEffect.Completed -> Unit
                is SharingFsmEffect.ProtocolError -> {
                    mutableState.value = InboundConnectionState.Failed(effect.reason)
                }
                SharingFsmEffect.ReadyToSendPayloads -> Unit
            }
        }
    }

    /**
     * Encode a Sharing.Nearby.Frame as a BYTES payload and push every
     * resulting [OfflineFrame] through the SecureChannel. Each
     * negotiation BYTES payload uses a fresh random `payload_id`
     * (`SecureRandom.nextLong()` matches NearDrop); reusing an id would
     * collide with an in-flight payload on the peer's reassembler.
     */
    private suspend fun sendSharingFrame(
        channel: SecureChannel,
        send: SharingFsmEffect.SendFrame,
    ) {
        val payloadId = secureRandom.nextLong()
        val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId, send.frame.toByteArray())
        logger(
            "sharing: sending ${send.frame.v1.type} payloadId=$payloadId " +
                "frames=${frames.size} state=${fsm?.state}",
        )
        for (frame in frames) {
            channel.sendOfflineFrame(frame)
        }
    }

    /**
     * Convert the FSM's `IntroductionReceived` into [TransferMetadata],
     * record the announced manifest, and park the lifecycle in
     * [InboundConnectionState.WaitingForUserConsent].
     */
    private fun handleIntroduction(effect: SharingFsmEffect.IntroductionReceived) {
        val metadata =
            TransferMetadata.fromIntroductionFrame(
                introduction = effect.introduction,
                pin = pin,
                sourceDeviceName = sourceDeviceName,
            )
        transferMetadata = metadata

        for (file in effect.introduction.fileMetadataList) {
            announced[file.payloadId] = PayloadHeader.PayloadType.FILE
            announcedFileNames[file.payloadId] = file.name
            totalSize += file.size
        }
        for (text in effect.introduction.textMetadataList) {
            announced[text.payloadId] = PayloadHeader.PayloadType.BYTES
            totalSize += text.size
        }
        mutableState.value = InboundConnectionState.WaitingForUserConsent(metadata)
    }

    /** Surface the initial Receiving snapshot so the UI can render 0% immediately. */
    private fun publishInitialReceiving() {
        // Seed the rate estimator with a zero-bytes sample so the
        // first real chunk sample produces a non-degenerate Δt. The
        // estimator's first sample is a seed-only no-op for the EMA
        // itself, so this does not bias the rate.
        rateEstimator.sample(bytesTransferred = 0L, nowMillis = nowMillisSource())
        mutableState.value =
            InboundConnectionState.Receiving(
                progress =
                    TransferProgress.of(
                        bytesTransferred = 0L,
                        totalSize = totalSize,
                        bytesPerSecond = 0L,
                    ),
                currentItemPayloadId = null,
                currentItemType = null,
            )
    }

    /** Drain one [ExternalEvent] (user consent or cancel) into the FSM. */
    private suspend fun handleExternalEvent(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
        event: ExternalEvent,
    ) {
        val effects =
            when (event) {
                is ExternalEvent.UserConsent ->
                    fsm.onEvent(SharingFsmEvent.UserConsent(event.accepted))
                ExternalEvent.UserCancel ->
                    fsm.onEvent(SharingFsmEvent.UserCancel)
            }
        applyEffects(channel, effects)
    }

    /**
     * Resolve the FSM's terminal state into a final [InboundResult].
     * Called when the dispatch loop notices the FSM transitioned to
     * [InboundSharingState.Disconnected].
     */
    private suspend fun terminalResultFromState(): InboundResult =
        when (val s = mutableState.value) {
            is InboundConnectionState.Completed -> InboundResult.Completed(s.items)
            InboundConnectionState.Rejected -> {
                // Send Disconnection so the peer sees a clean teardown
                // rather than an abrupt close. Best-effort: if the
                // SecureChannel is broken (peer hung up first) we
                // ignore the failure.
                runCatching { secureChannel?.sendOfflineFrame(OfflineFrames.disconnection()) }
                InboundResult.Rejected
            }
            is InboundConnectionState.Cancelled -> {
                runCatching { secureChannel?.sendOfflineFrame(OfflineFrames.disconnection()) }
                InboundResult.Cancelled(s.cause)
            }
            is InboundConnectionState.Failed -> InboundResult.Failed(s.reason)
            else -> {
                val reason = "FSM disconnected without a terminal state (state=$s)"
                mutableState.value = InboundConnectionState.Failed(reason)
                InboundResult.Failed(reason)
            }
        }

    private fun publishCancelled(cause: CancelCause) {
        mutableState.value = InboundConnectionState.Cancelled(cause)
    }

    @Suppress(
        "DEPRECATION", // ProgressUpdateFrame is deprecated but still emitted by One UI senders.
        "ReturnCount", // One return per frame type keeps diagnostic formatting simple.
    )
    private fun SharingFrame.summary(): String {
        val v1 = v1
        if (v1.type == SharingFrameType.PROGRESS_UPDATE && v1.hasProgressUpdate()) {
            val progress = v1.progressUpdate
            return "progress=${progress.progress} startTransfer=${progress.startTransfer}"
        }
        if (v1.type == SharingFrameType.INTRODUCTION && v1.hasIntroduction()) {
            return v1.introduction.fileMetadataList.joinToString(
                prefix = "files=[",
                postfix = "]",
                limit = MAX_LOGGED_INTRODUCTION_ITEMS,
            ) { file ->
                "payloadId=${file.payloadId},id=${file.id}," +
                    "hash=${if (file.hasAttachmentHash()) file.attachmentHash else "absent"}," +
                    "size=${file.size}"
            }
        }
        if (v1.type == SharingFrameType.RESPONSE && v1.hasConnectionResponse()) {
            return "response=${v1.connectionResponse.status}"
        }
        return ""
    }

    /**
     * Read one length-prefixed [OfflineFrame] from the unencrypted
     * transport and parse it. Used pre-UKEY2 (ConnectionRequest /
     * ConnectionResponse).
     */
    private suspend fun readOfflineFrameUnencrypted(transport: FramedConnection): OfflineFrame {
        val bytes = transport.receiveFrame()
        return OfflineFrame.parseFrom(bytes)
    }

    private companion object {
        private const val INITIAL_FRAME_PROBE_ATTEMPTS = 20
        private const val INITIAL_FRAME_PROBE_DELAY_MILLIS = 10L
        private const val MAX_LOGGED_INTRODUCTION_ITEMS = 5
        private const val POST_UPGRADE_SHARING_DELAY_MILLIS = 750L
        private const val PENDING_UPGRADE_POLL_DELAY_MILLIS = 10L
    }
}
