/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import dev.bluehouse.bada.protocol.crypto.D2DKeyDerivation
import dev.bluehouse.bada.protocol.crypto.D2DRole
import dev.bluehouse.bada.protocol.crypto.pin.PinDerivation
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.payload.PayloadAssembler
import dev.bluehouse.bada.protocol.payload.PayloadEvent
import dev.bluehouse.bada.protocol.payload.PayloadTransferEncoder
import dev.bluehouse.bada.protocol.qr.QrHandshakeSigner
import dev.bluehouse.bada.protocol.sharing.OutboundSharingFsm
import dev.bluehouse.bada.protocol.sharing.OutboundSharingState
import dev.bluehouse.bada.protocol.sharing.PairedKeyEncryptionFrame
import dev.bluehouse.bada.protocol.sharing.SharingFrame
import dev.bluehouse.bada.protocol.sharing.SharingFrameType
import dev.bluehouse.bada.protocol.sharing.SharingFrameVersion
import dev.bluehouse.bada.protocol.sharing.SharingFrames
import dev.bluehouse.bada.protocol.sharing.SharingFsmEffect
import dev.bluehouse.bada.protocol.sharing.SharingFsmEvent
import dev.bluehouse.bada.protocol.sharing.SharingV1Frame
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.EndOfFrameStream
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.ukey2.Ukey2Client
import dev.bluehouse.bada.protocol.ukey2.Ukey2HandshakeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal lifecycle driver for [OutboundConnection].
 *
 * Owns the per-connection mutable state: framed transport, secure
 * channel, FSM, and outbound payload bookkeeping. Consumed once by
 * [OutboundConnection.run] and discarded; there is no resume / re-run
 * path.
 *
 * ### Receive-loop concurrency
 *
 * Same shape as [InboundConnectionDriver]. `select` cannot directly
 * suspend on `SecureChannel.receiveOfflineFrame()`, so the driver
 * spawns a small **inbound pump coroutine** that reads frames
 * sequentially and forwards them onto a channel; the main loop then
 * `select`s between that channel and [externalEvents].
 */
@Suppress(
    "LargeClass", // The driver intentionally keeps the full wire lifecycle in one state-owning type.
    "TooManyFunctions", // The lifecycle inherently has many phases; each is one function for readability.
    "LongParameterList", // Constructor takes the connection's collaborators verbatim.
)
internal class OutboundConnectionDriver(
    private val initialTransport: ConnectedTransport,
    private val secureRandom: SecureRandom,
    private val externalEvents: Channel<OutboundExternalEvent>,
    private val mutableState: MutableStateFlow<OutboundConnectionState>,
    private val mutableActiveMedium: MutableStateFlow<Medium>,
    private val mutableActiveWifiFrequencyMhz: MutableStateFlow<Int?>,
    private val endpointId: String,
    private val endpointInfo: ByteArray,
    private val qrSigningKey: PrivateKey?,
    private val files: List<FileSource>,
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    private val onHandshakeComplete: () -> Unit = {},
    private val logger: (String) -> Unit = {},
    private val initialHandshakeTimeoutMillis: Long =
        OutboundConnection.DEFAULT_INITIAL_HANDSHAKE_TIMEOUT_MILLIS,
    private val remoteAcceptanceTimeoutMillis: Long =
        OutboundConnection.DEFAULT_REMOTE_ACCEPTANCE_TIMEOUT_MILLIS,
    /**
     * Wall-clock source for the rate estimator. Defaults to
     * [System.currentTimeMillis]; tests inject deterministic
     * timestamps to make EMA samples stable.
     */
    private val nowMillisSource: () -> Long = System::currentTimeMillis,
    /**
     * Rate estimator backing [OutboundConnectionState.Sending.progress].
     * Fed on every chunk write; the publisher reads
     * [TransferRateEstimator.bytesPerSecond] back into the published
     * [TransferProgress] so the sender UI can render rate + ETA.
     */
    private val rateEstimator: TransferRateEstimator = TransferRateEstimator(),
) {
    private var framedConnection: FramedConnection? = null
    private var secureChannel: SecureChannel? = null
    private var fsm: OutboundSharingFsm? = null

    /**
     * `qr_code_handshake_data` for the outgoing PairedKeyEncryption frame:
     * an IEEE-P1363 ECDSA signature of the UKEY2 `authString` made with
     * [qrSigningKey]. Computed once the session keys are derived (the
     * authString is not known until then) and consumed by
     * [rewriteForQrIfNeeded]. Stays null when this is not a QR-bonded send.
     */
    private var qrCodeHandshakeData: ByteArray? = null

    /** 4-digit confirmation PIN derived from UKEY2 `authString`. */
    private var pin: String = ""

    /** Cumulative bytes pushed onto the wire across all FILE payloads. */
    private var bytesSent: Long = 0L

    /** Sum of [FileSource.size] over [files]. */
    private val totalSize: Long = files.sumOf { it.size }

    /** The most recent active payload, for progress UI. */
    private var currentItemPayloadId: Long? = null

    /**
     * Drive the entire sender-side lifecycle. Returns the terminal
     * [OutboundResult]; throws on coroutine cancellation (handled by
     * the caller).
     */
    @Suppress("ReturnCount")
    suspend fun runLifecycle(): OutboundResult {
        mutableState.value = OutboundConnectionState.Handshaking

        val preSecureTransport =
            when (val upgrade = openPreSecureTransport()) {
                is PreUkey2Negotiation.Ready -> upgrade.transport
                is PreUkey2Negotiation.Failed -> {
                    val reason = upgrade.reason
                    mutableState.value = OutboundConnectionState.Failed(reason)
                    return OutboundResult.Failed(reason)
                }
            }
        logger("step 1: pre-secure active medium=${preSecureTransport.medium}")
        publishActiveTransport(preSecureTransport.medium, preSecureTransport.wifiFrequencyMhz)

        val (handshake, peerResponse) =
            runInitialHandshakeWithTimeout(preSecureTransport.connection)
                ?: return initialHandshakeTimedOut()

        // Step 3: send our unencrypted ConnectionResponse{ACCEPT}.
        //
        // Order matters: NearDrop's OutboundNearbyConnection sends its
        // ConnectionResponse BEFORE reading the peer's. Stock Quick Share
        // on Android 14+ expects the sender's response on the wire first;
        // if we read instead, both sides deadlock and the peer eventually
        // times out and closes (surfacing here as EndOfFrameStream right
        // after the UKEY2 handshake).

        // Step 4: read receiver's unencrypted ConnectionResponse.
        val hasResp = peerResponse.v1.hasConnectionResponse()
        logger("step 4: received peer ConnectionResponse type=${peerResponse.v1.type} hasConnectionResponse=$hasResp")
        if (peerResponse.v1.hasConnectionResponse()) {
            val cr = peerResponse.v1.connectionResponse

            @Suppress("DEPRECATION")
            val status = cr.status
            logger(
                "step 4: peer.response=${cr.response} status=$status " +
                    "osType=${if (cr.hasOsInfo()) cr.osInfo.type else "<none>"}",
            )
        }
        check(peerResponse.isConnectionResponse()) {
            "Expected ConnectionResponse, got ${peerResponse.v1.type}"
        }

        // Step 5: derive D2DSessionKeys (role = CLIENT, since the
        // sender drove UKEY2). This is the role-key swap point; see
        // OutboundConnection's KDoc.
        val sessionKeys =
            D2DKeyDerivation.derive(
                dhs = handshake.dhs,
                ukeyClientInitMsg = handshake.clientInitMsg,
                ukeyServerInitMsg = handshake.serverInitMsg,
                role = D2DRole.CLIENT,
            )
        pin = PinDerivation.deriveFourDigitPin(sessionKeys.authString)
        logger("step 5: D2D keys derived, PIN=$pin")

        // QR-bonded send: sign the UKEY2 authString with the QR keypair's
        // private key now that the authString exists. The signature rides
        // the PairedKeyEncryption frame via rewriteForQrIfNeeded, proving
        // to the QR receiver that this sender owns the published QR key.
        qrSigningKey?.let { key ->
            qrCodeHandshakeData = QrHandshakeSigner.sign(key, sessionKeys.authString)
            logger("step 5: signed UKEY2 authString for QR handshake (${qrCodeHandshakeData?.size} bytes)")
        }

        // Step 6: SecureChannel takes over.
        val channel =
            SecureChannel(preSecureTransport.connection, sessionKeys, secureRandom)
                .also { secureChannel = it }

        // Step 7: if the receiver offers a higher-bandwidth medium,
        // adopt it before the Nearby Share payload negotiation begins.
        // Peers that stay on Wi-Fi LAN send the first sharing payload;
        // buffer that frame so the existing receive loop sees it.
        val negotiated =
            when (val negotiation = negotiateBandwidthUpgrade(channel, preSecureTransport.medium)) {
                is BandwidthNegotiation.Ready -> negotiation
                is BandwidthNegotiation.Failed -> {
                    logger("medium-upgrade: ${negotiation.reason}")
                    runCatching { sendTerminalDisconnection(channel) }
                    mutableState.value = OutboundConnectionState.Failed(negotiation.reason)
                    return OutboundResult.Failed(negotiation.reason)
                }
            }
        publishActiveTransport(negotiated.medium, negotiated.wifiFrequencyMhz)

        // Step 8: build the OutboundSharingFsm with our IntroductionFrame.
        val introduction = buildIntroductionFrame(files)
        val negotiationFsm =
            OutboundSharingFsm(introduction = introduction, secureRandom = secureRandom)
                .also { fsm = it }

        // Step 9: drive the FSM's initial PKE frame onto the wire,
        // optionally attaching qr_code_handshake_data.
        logger("step 8: sending initial PKE frame")
        applyEffects(negotiated.channel, rewriteForQrIfNeeded(negotiationFsm.start()))

        // Mark the handshake as complete so a racing UI-side cancel()
        // takes the cooperative FSM path (CANCEL + DISCONNECTION on the
        // wire) instead of the pre-handshake fast-path (raw socket
        // close). The dispatch loop drains externalEvents from this
        // point on.
        //
        // NOTE: AwaitingRemoteAcceptance is published later, when the FSM
        // actually reaches SentIntroduction (peer's PKE + PKR have been
        // exchanged, our IntroductionFrame is on the wire). Publishing it
        // here would let consumers think the receiver is about to ACCEPT
        // when in reality both peers are still mid-PKE-handshake — and a
        // cancel() in that race would crash the peer with Broken pipe.
        onHandshakeComplete()

        val shouldRunBleWifiDirectLoop =
            requiresWifiDirectUpgradeForBle(preSecureTransport.medium) &&
                negotiated.medium != Medium.WIFI_DIRECT
        return if (shouldRunBleWifiDirectLoop) {
            runBleWifiDirectReceiveLoop(
                channel = negotiated.channel,
                fsm = negotiationFsm,
                initialWireFrames = negotiated.initialWireFrames,
                initialMedium = negotiated.medium,
                initialWifiFrequencyMhz = negotiated.wifiFrequencyMhz,
            )
        } else {
            runReceiveLoop(negotiated.channel, negotiationFsm, negotiated.initialWireFrames)
        }
    }

    private suspend fun openPreSecureTransport(): PreUkey2Negotiation {
        logger(
            "step 1: initial transport open medium=${initialTransport.medium} " +
                "endpointId=$endpointId endpointInfo.size=${endpointInfo.size}",
        )
        val transport = FramedConnection(initialTransport).also { framedConnection = it }

        // BLE/GATT bootstraps still carry the legacy Wi-Fi LAN marker because
        // stock Quick Share validates that shape before it dispatches the
        // incoming connection. Same-Wi-Fi LAN, even when the underlying TCP
        // socket is Nearby-multiplexed, keeps the historical Wi-Fi-only
        // request shape.
        val advertisedMediums = advertisedMediumsForInitialTransport()
        logger("step 1: advertising mediums=$advertisedMediums")
        transport.sendFrame(
            OutboundFrames
                .connectionRequest(
                    endpointId = endpointId,
                    endpointInfo = endpointInfo,
                    supportedMediums = advertisedMediums,
                ).toByteArray(),
        )
        logger("step 1: sent ConnectionRequest, probing pre-UKEY2 upgrade")

        return upgradePreUkey2IfOffered(transport)
    }

    private suspend fun upgradePreUkey2IfOffered(transport: FramedConnection): PreUkey2Negotiation {
        if (!shouldProbePreUkey2Upgrade()) {
            return PreUkey2Negotiation.Ready(PreSecureTransport(transport, initialTransport.medium))
        }
        logger(
            "medium-upgrade: probing ${PRE_UKEY2_UPGRADE_OFFER_WAIT_TIMEOUT_MILLIS}ms " +
                "for pre-UKEY2 BLE upgrade offer before sending ClientInit",
        )
        return when (
            val probe =
                PreUkey2BandwidthUpgrade.receiveOfferProbe(
                    framedConnection = transport,
                    timeoutMillis = PRE_UKEY2_UPGRADE_OFFER_WAIT_TIMEOUT_MILLIS,
                )
        ) {
            UpgradeOfferProbe.None -> {
                logger("medium-upgrade: no pre-UKEY2 upgrade offer; continuing on ${initialTransport.medium}")
                PreUkey2Negotiation.Ready(PreSecureTransport(transport, initialTransport.medium))
            }
            is UpgradeOfferProbe.Other ->
                PreUkey2Negotiation.Failed(
                    "Expected pre-UKEY2 upgrade offer, got ${probe.frame.describeFrameType()}",
                )
            is UpgradeOfferProbe.Offer ->
                when (
                    val upgraded =
                        PreUkey2BandwidthUpgrade.runClientUpgradeFromOffer(
                            oldTransport = transport,
                            offer = probe.frame,
                            mediumRegistry = mediumRegistry,
                            endpointId = endpointId,
                            logger = logger,
                        )
                ) {
                    is PreUkey2UpgradeResult.Ready -> {
                        framedConnection = upgraded.transport
                        PreUkey2Negotiation.Ready(
                            PreSecureTransport(
                                connection = upgraded.transport,
                                medium = upgraded.medium,
                                wifiFrequencyMhz = upgraded.wifiFrequencyMhz,
                            ),
                        )
                    }
                    is PreUkey2UpgradeResult.Failed -> PreUkey2Negotiation.Failed(upgraded.reason)
                }
        }
    }

    private fun advertisedMediumsForInitialTransport(): Set<Medium> {
        val supportedMediums =
            mediumRegistry.supportedMediumsForCurrentTransport(initialTransport.medium)
        return when {
            initialTransport.medium == Medium.WIFI_LAN ->
                setOf(Medium.WIFI_LAN)
            !initialTransport.medium.isBleBased() -> supportedMediums
            else ->
                buildSet {
                    add(Medium.WIFI_LAN)
                    add(initialTransport.medium)
                    if (Medium.WIFI_DIRECT in supportedMediums) {
                        add(Medium.WIFI_DIRECT)
                    }
                }
        }
    }

    private suspend fun negotiateBandwidthUpgrade(
        channel: SecureChannel,
        currentMedium: Medium,
    ): BandwidthNegotiation {
        val initialWireFrames = mutableListOf<OfflineFrame>()
        val requiresWifiDirect = requiresWifiDirectUpgradeForBle(currentMedium)
        val activeTransport =
            when (
                val probe =
                    BandwidthUpgradeOrchestrator.receiveOfferProbe(
                        channel = channel,
                        timeoutMillis =
                            if (requiresWifiDirect) {
                                BLE_WIFI_DIRECT_UPGRADE_TIMEOUT_MILLIS
                            } else {
                                BandwidthUpgradeOrchestrator.OFFER_WAIT_TIMEOUT_MILLIS
                            },
                    )
            ) {
                UpgradeOfferProbe.None -> ActiveTransportChannel(channel, currentMedium)
                is UpgradeOfferProbe.Other -> {
                    initialWireFrames += probe.frame
                    ActiveTransportChannel(channel, currentMedium)
                }
                is UpgradeOfferProbe.Offer -> {
                    val transport =
                        BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                            oldChannel = channel,
                            currentMedium = currentMedium,
                            offer = probe.frame,
                            mediumRegistry = mediumRegistry,
                            endpointId = endpointId,
                            logger = logger,
                        )
                    if (requiresWifiDirect && transport.medium != Medium.WIFI_DIRECT) {
                        return BandwidthNegotiation.Failed(
                            "Wi-Fi Direct upgrade failed after BLE pairing; " +
                                "stayed on ${transport.medium}",
                        )
                    }
                    initialWireFrames += transport.bufferedFrames
                    transport.also { secureChannel = it.channel }
                }
            }
        return BandwidthNegotiation.Ready(
            activeTransport.channel,
            activeTransport.medium,
            initialWireFrames,
            activeTransport.wifiFrequencyMhz,
        )
    }

    private fun shouldProbePreUkey2Upgrade(): Boolean =
        initialTransport.medium.isBleBased() &&
            Medium.WIFI_DIRECT in mediumRegistry.supportedMediumsForCurrentTransport(initialTransport.medium)

    private fun requiresWifiDirectUpgradeForBle(currentMedium: Medium): Boolean =
        currentMedium.isBleBased() &&
            Medium.WIFI_DIRECT in mediumRegistry.supportedMediumsForCurrentTransport(currentMedium)

    private fun publishActiveTransport(activeTransport: ActiveTransportChannel) {
        publishActiveTransport(activeTransport.medium, activeTransport.wifiFrequencyMhz)
    }

    private fun publishActiveTransport(
        medium: Medium,
        wifiFrequencyMhz: Int?,
    ) {
        mutableActiveMedium.value = medium
        mutableActiveWifiFrequencyMhz.value = wifiFrequencyMhz.takeIf { medium == Medium.WIFI_DIRECT }
    }

    /**
     * Tear down all owned resources. Safe to call multiple times; each
     * closeable swallows its own IOException so a single failing close
     * cannot leak the others.
     */
    fun tearDown() {
        runCatching { secureChannel?.close() }
        runCatching { framedConnection?.close() }
        runCatching { initialTransport.close() }
    }

    /**
     * Run the pre-secure handshake under a wall-clock guard. Blocking
     * reads inside [FramedConnection] do not wake up on coroutine
     * cancellation alone; the timeout job closes the underlying
     * transport first, which unblocks sockets and the BLE virtual
     * streams used by non-LAN bootstrap paths.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runInitialHandshakeWithTimeout(
        transport: FramedConnection,
    ): Pair<Ukey2HandshakeResult, OfflineFrame>? =
        coroutineScope {
            val timedOut = AtomicBoolean(false)
            val timeoutJob =
                launch {
                    delay(initialHandshakeTimeoutMillis)
                    timedOut.set(true)
                    logger(
                        "step 2: initial handshake timed out after " +
                            "${initialHandshakeTimeoutMillis}ms; closing transport",
                    )
                    runCatching { transport.close() }
                    runCatching { initialTransport.close() }
                }
            try {
                val handshake = Ukey2Client.performHandshake(transport, secureRandom)

                if (timedOut.get()) {
                    null
                } else {
                    logger("step 2: UKEY2 client handshake complete (dhs.size=${handshake.dhs.size})")

                    val responseBytes = OutboundFrames.connectionResponse().toByteArray()
                    logger("step 3: sending our ConnectionResponse, size=${responseBytes.size}")
                    transport.sendFrame(responseBytes)
                    logger("step 3: sent our ConnectionResponse{ACCEPT}")

                    logger("step 4: awaiting peer ConnectionResponse...")
                    val peerResponse = readOfflineFrameUnencrypted(transport)
                    if (timedOut.get()) {
                        null
                    } else {
                        handshake to peerResponse
                    }
                }
            } catch (e: Throwable) {
                if (timedOut.get()) {
                    null
                } else {
                    throw e
                }
            } finally {
                timeoutJob.cancelAndJoin()
            }
        }

    private fun initialHandshakeTimedOut(): OutboundResult.Failed {
        val reason = "Initial handshake timed out after ${initialHandshakeTimeoutMillis}ms"
        mutableState.value = OutboundConnectionState.Failed(reason)
        return OutboundResult.Failed(reason)
    }

    /**
     * If [qrCodeHandshakeData] is non-null and the FSM's first effect
     * is the seed `PairedKeyEncryption` SendFrame, replace it with a
     * frame that also carries `qr_code_handshake_data`. The byte
     * counts on the random fill bytes are preserved.
     *
     * Why intercept here rather than thread the QR data through the
     * FSM constructor? The FSM is shared between sender and receiver
     * (issue #15) and only the sender has a meaningful QR token to
     * send. Adding a sender-only field there would muddy the FSM's
     * surface for no benefit; rewriting at the orchestrator boundary
     * keeps the FSM pure.
     */
    private fun rewriteForQrIfNeeded(effects: List<SharingFsmEffect>): List<SharingFsmEffect> {
        val qrBytes = qrCodeHandshakeData ?: return effects
        return effects.map { effect ->
            if (effect is SharingFsmEffect.SendFrame &&
                effect.frame.v1.type == SharingFrameType.PAIRED_KEY_ENCRYPTION
            ) {
                SharingFsmEffect.SendFrame(attachQrToken(effect.frame, qrBytes))
            } else {
                effect
            }
        }
    }

    /**
     * Builds a copy of [original] with `qr_code_handshake_data` set to
     * [qrBytes]. Preserves the original `secret_id_hash` and
     * `signed_data` fill bytes so the over-the-wire shape is otherwise
     * identical to a non-QR PKE.
     */
    private fun attachQrToken(
        original: SharingFrame,
        qrBytes: ByteArray,
    ): SharingFrame {
        val originalPke = original.v1.pairedKeyEncryption
        val newPke =
            PairedKeyEncryptionFrame
                .newBuilder()
                .setSecretIdHash(originalPke.secretIdHash)
                .setSignedData(originalPke.signedData)
                .setQrCodeHandshakeData(ByteString.copyFrom(qrBytes))
                .build()
        return SharingFrame
            .newBuilder()
            .setVersion(SharingFrameVersion.V1)
            .setV1(
                SharingV1Frame
                    .newBuilder()
                    .setType(SharingFrameType.PAIRED_KEY_ENCRYPTION)
                    .setPairedKeyEncryption(newPke)
                    .build(),
            ).build()
    }

    /**
     * Main receive loop. Spawns an inbound pump coroutine, interleaves
     * wire-frame and external-event delivery via [select], and runs
     * the safe-disconnect drain (for terminals where we sent the
     * `request_safe_to_disconnect=true` Disconnection ourselves)
     * before letting the `finally` block close the socket.
     */
    private suspend fun runReceiveLoop(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        initialWireFrames: List<OfflineFrame> = emptyList(),
    ): OutboundResult =
        coroutineScope {
            // RENDEZVOUS capacity — same back-pressure rationale as
            // InboundConnectionDriver.
            val wireChannel: Channel<OutboundWireMessage> = Channel(Channel.RENDEZVOUS)
            val pumpJob: Job = launch { runInboundPump(channel, wireChannel) }
            // Outbound KEEP_ALIVE ticker (issue #37). PROTOCOL.md:
            // "Android sends offline frames of type KEEP_ALIVE every
            // 10 seconds and expects the server to do the same. If you
            // don't, it will terminate the connection after a while
            // thinking your app crashed or something." Without this
            // ticker, our advertised keep_alive_timeout_millis (10 min)
            // is the upper bound on how long an idle large-file send
            // can take before the receiver tears the connection down.
            //
            // SecureChannel's send half is mutex-serialized internally,
            // so the ticker can race with payload writes / FSM frame
            // emissions safely — the next chunk simply waits behind a
            // queued KEEP_ALIVE rather than interleaving inside it.
            val keepAliveJob: Job = launch { runKeepAliveTicker(channel) }
            try {
                val result = dispatchLoop(channel, fsm, wireChannel, initialWireFrames)
                // Safe-disconnect drain: we advertised
                // safe_to_disconnect_version=1 in our ConnectionResponseFrame,
                // so any DISCONNECTION we ourselves emit goes out with
                // request_safe_to_disconnect=true and Samsung One UI 7+
                // enforces that contract — an abrupt FIN before its read
                // pipeline drains marks every in-flight PAYLOAD_TRANSFER as
                // failed and surfaces "couldn't receive file". Wait briefly
                // for the peer's ack_safe_to_disconnect=true (or its own
                // FIN) before closing.
                //
                // Only drain on terminals where WE sent the request:
                //   - Completed: terminal Disconnection from streamFilesAndComplete
                //   - Rejected: applyEffects emitted Disconnection
                //   - Cancelled(LOCAL): user cancel emitted Disconnection
                // On Cancelled(PEER) and Failed paths we never sent the
                // request, so the peer has no reason to ack and the drain
                // would just block for the full timeout window.
                if (shouldDrainForSafeDisconnect(result)) {
                    val timeoutMillis = safeDisconnectAckTimeoutMillis()
                    withTimeoutOrNull(timeoutMillis) {
                        drainSafeDisconnectAck(wireChannel)
                    } ?: logger(
                        "fsm: safe-disconnect drain timed out after " +
                            "${timeoutMillis}ms",
                    )
                }
                publishCompletedIfNeeded(result)
            } finally {
                // Cancel the KEEP_ALIVE ticker FIRST, before any socket
                // close: the ticker spends almost all its life parked
                // in delay() (which is cancellable, unlike a blocking
                // socket read), so cancelAndJoin returns near-instantly
                // and we avoid racing a tick against the in-flight
                // terminal Disconnection. If the ticker happens to be
                // mid-send when we cancel, it observes
                // CancellationException out of the SecureChannel write
                // and exits cleanly.
                keepAliveJob.cancelAndJoin()
                // Close the active channel BEFORE cancelAndJoin'ing the pump:
                // the pump is parked in SecureChannel.receiveOfflineFrame()'s
                // blocking read under withContext(Dispatchers.IO), which
                // does NOT honour coroutine cancellation while parked in
                // a syscall. Closing the channel breaks that read even
                // after a bandwidth upgrade swaps the wire from the
                // original TCP socket to a provider-owned transport.
                // Close the original socket as well because the upgraded
                // channel no longer owns it.
                runCatching { channel.close() }
                runCatching { initialTransport.close() }
                pumpJob.cancelAndJoin()
            }
        }

    /**
     * Outbound KEEP_ALIVE ticker. Once the SecureChannel is up,
     * emits `KEEP_ALIVE{ack=false}` every
     * [KeepAliveTicker.DEFAULT_INTERVAL_MILLIS] (10 s) to keep the
     * peer's "peer crashed" watchdog from firing during long idle
     * stretches (in particular: large-file payloads on slow Wi-Fi
     * where chunk-to-chunk gaps can rival the advertised
     * `keep_alive_timeout_millis`).
     *
     * Cancellation: structured-concurrency child of [runReceiveLoop]'s
     * `coroutineScope`. The first `delay` is cancellable, so on
     * teardown the ticker exits near-instantly without firing one last
     * tick. If a `delay` elapses concurrently with teardown the next
     * `sendOfflineFrame` either succeeds (race, harmless — the bytes
     * sit in the TCP send buffer alongside the terminal frames) or
     * throws a socket I/O exception out of the write, which the
     * shared [KeepAliveTicker] forwards to the supplied error handler
     * so cancellation isn't noisy. CancellationException is the
     * explicit termination path and is rethrown.
     */
    private suspend fun runKeepAliveTicker(channel: SecureChannel) {
        KeepAliveTicker.run(
            send = channel::sendOfflineFrame,
            onError = { e ->
                logger("keep-alive: ticker stopped (${e::class.simpleName}: ${e.message ?: "null"})")
            },
        )
    }

    /**
     * BLE/GATT sender path for stock Android receivers that advertise
     * Wi-Fi Direct but do not auto-upgrade before the Nearby Share
     * consent exchange. Keep the read side single-threaded here: the
     * bandwidth-upgrade handshake needs to keep using the prior BLE
     * channel, so the normal inbound pump must not be parked in a
     * blocking read on that same channel when an offer arrives.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun runBleWifiDirectReceiveLoop(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        initialWireFrames: List<OfflineFrame> = emptyList(),
        initialMedium: Medium,
        initialWifiFrequencyMhz: Int?,
    ): OutboundResult {
        var activeChannel = channel
        var activeMedium = initialMedium
        var activeWifiFrequencyMhz = initialWifiFrequencyMhz
        val bufferedFrames =
            ArrayDeque<OfflineFrame>().apply {
                addAll(initialWireFrames)
            }
        var remoteAcceptanceDeadlineMillis: Long? = null
        try {
            while (true) {
                if (fsm.state == OutboundSharingState.Disconnected) {
                    return terminalResultFromState()
                }

                remoteAcceptanceDeadlineMillis =
                    updateRemoteAcceptanceDeadline(
                        fsm = fsm,
                        currentDeadlineMillis = remoteAcceptanceDeadlineMillis,
                    )
                val remoteAcceptanceRemainingMillis = remoteAcceptanceRemainingMillis(remoteAcceptanceDeadlineMillis)
                if (remoteAcceptanceRemainingMillis == 0L) {
                    return remoteAcceptanceTimedOut()
                }

                val event =
                    nextBleWifiDirectEvent(
                        channel = activeChannel,
                        bufferedFrames = bufferedFrames,
                        remoteAcceptanceRemainingMillis = remoteAcceptanceRemainingMillis,
                    )
                logger("fsm: dispatch event=${describeDriverEvent(event)} fsmState=${fsm.state::class.simpleName}")

                if (event is OutboundDriverEvent.Wire &&
                    event.frame.isBandwidthUpgradeEvent(
                        BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                    )
                ) {
                    val upgraded =
                        adoptBleWifiDirectOffer(
                            channel = activeChannel,
                            currentMedium = activeMedium,
                            offer = event.frame,
                        )
                    if (upgraded.medium != Medium.WIFI_DIRECT) {
                        return failBleWifiDirect(
                            "Wi-Fi Direct upgrade failed after BLE pairing; stayed on ${upgraded.medium}",
                            activeChannel,
                        )
                    }
                    activeChannel = upgraded.channel
                    activeMedium = upgraded.medium
                    activeWifiFrequencyMhz = upgraded.wifiFrequencyMhz
                    publishActiveTransport(upgraded)
                    bufferedFrames.addAll(upgraded.bufferedFrames)
                    continue
                }

                when (val outcome = handleBleWifiDirectEvent(activeChannel, fsm, event)) {
                    BleWifiDirectOutcome.Continue -> Unit
                    is BleWifiDirectOutcome.Terminal -> {
                        if (shouldDrainForSafeDisconnect(outcome.result)) {
                            drainSafeDisconnectAckDirect(activeChannel)
                        }
                        return publishCompletedIfNeeded(outcome.result)
                    }
                    BleWifiDirectOutcome.ReadyToSendPayloads -> {
                        val upgraded =
                            ensureBleWifiDirectBeforePayloads(
                                channel = activeChannel,
                                currentMedium = activeMedium,
                                currentWifiFrequencyMhz = activeWifiFrequencyMhz,
                            )
                        if (upgraded is PayloadChannelSelection.Failed) {
                            return failBleWifiDirect(upgraded.reason, activeChannel)
                        }
                        val ready = upgraded as PayloadChannelSelection.Ready
                        activeChannel = ready.channel
                        activeMedium = ready.medium
                        activeWifiFrequencyMhz = ready.wifiFrequencyMhz
                        publishActiveTransport(activeMedium, activeWifiFrequencyMhz)
                        val result =
                            coroutineScope {
                                val keepAliveJob = launch { runKeepAliveTicker(activeChannel) }
                                try {
                                    val result = streamFilesAndComplete(activeChannel)
                                    if (shouldDrainForSafeDisconnect(result)) {
                                        drainSafeDisconnectAckDirect(activeChannel)
                                    }
                                    publishCompletedIfNeeded(result)
                                } finally {
                                    keepAliveJob.cancelAndJoin()
                                }
                            }
                        return result
                    }
                }
            }
        } finally {
            runCatching { activeChannel.close() }
            runCatching { initialTransport.close() }
        }
    }

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught")
    private suspend fun nextBleWifiDirectEvent(
        channel: SecureChannel,
        bufferedFrames: ArrayDeque<OfflineFrame>,
        remoteAcceptanceRemainingMillis: Long?,
    ): OutboundDriverEvent {
        if (bufferedFrames.isNotEmpty()) {
            return OutboundDriverEvent.Wire(bufferedFrames.removeFirst())
        }
        val deadlineMillis = remoteAcceptanceRemainingMillis?.let { nowMillisSource() + it }
        while (true) {
            externalEvents.tryReceive().getOrNull()?.let { return OutboundDriverEvent.External(it) }
            if (channel.hasBufferedInput()) {
                return try {
                    OutboundDriverEvent.Wire(channel.receiveOfflineFrame())
                } catch (e: EndOfFrameStream) {
                    OutboundDriverEvent.PeerClosed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OutboundDriverEvent.PumpError(e)
                }
            }
            if (deadlineMillis != null && nowMillisSource() >= deadlineMillis) {
                return OutboundDriverEvent.RemoteAcceptanceTimedOut
            }
            delay(BLE_WIFI_DIRECT_POLL_DELAY_MILLIS)
        }
    }

    private suspend fun handleBleWifiDirectEvent(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: OutboundDriverEvent,
    ): BleWifiDirectOutcome =
        when (event) {
            is OutboundDriverEvent.External -> {
                handleExternalEvent(channel, fsm, event.event)
                BleWifiDirectOutcome.Continue
            }
            is OutboundDriverEvent.Wire -> handleBleWifiDirectInboundFrame(channel, fsm, event.frame)
            OutboundDriverEvent.PeerClosed -> BleWifiDirectOutcome.Terminal(handlePeerClosed())
            OutboundDriverEvent.RemoteAcceptanceTimedOut -> BleWifiDirectOutcome.Terminal(remoteAcceptanceTimedOut())
            is OutboundDriverEvent.PumpError -> {
                val reason = "Inbound pump error: ${event.cause::class.simpleName}: ${event.cause.message}"
                mutableState.value = OutboundConnectionState.Failed(reason)
                BleWifiDirectOutcome.Terminal(OutboundResult.Failed(reason))
            }
        }

    @Suppress("ReturnCount")
    private suspend fun handleBleWifiDirectInboundFrame(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        frame: OfflineFrame,
    ): BleWifiDirectOutcome {
        if (frame.isDisconnection()) {
            return BleWifiDirectOutcome.Terminal(cancelFromPeer())
        }
        if (frame.hasV1() &&
            frame.v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION
        ) {
            logger(
                "fsm: inbound BANDWIDTH_UPGRADE_NEGOTIATION event_type=" +
                    frame.v1.bandwidthUpgradeNegotiation.eventType,
            )
            return BleWifiDirectOutcome.Continue
        }
        if (!frame.hasV1() || frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) {
            return BleWifiDirectOutcome.Continue
        }
        return when (val payloadEvent = inboundAssembler.onPayloadTransfer(frame.v1.payloadTransfer)) {
            is PayloadEvent.BytesComplete -> handleBleWifiDirectBytesComplete(channel, fsm, payloadEvent)
            is PayloadEvent.FileComplete,
            is PayloadEvent.Progress,
            is PayloadEvent.Ignored,
            -> BleWifiDirectOutcome.Continue
        }
    }

    private suspend fun handleBleWifiDirectBytesComplete(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: PayloadEvent.BytesComplete,
    ): BleWifiDirectOutcome {
        val sharingFrame = SharingFrames.parse(event.data)
        logger("fsm: rx SharingFrame type=${sharingFrame.v1.type}")
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharingFrame))
        applyEffects(channel, effects)
        return if (effects.any { it is SharingFsmEffect.ReadyToSendPayloads }) {
            BleWifiDirectOutcome.ReadyToSendPayloads
        } else {
            BleWifiDirectOutcome.Continue
        }
    }

    private suspend fun ensureBleWifiDirectBeforePayloads(
        channel: SecureChannel,
        currentMedium: Medium,
        currentWifiFrequencyMhz: Int?,
    ): PayloadChannelSelection {
        if (currentMedium == Medium.WIFI_DIRECT) {
            return PayloadChannelSelection.Ready(channel, currentMedium, currentWifiFrequencyMhz)
        }
        channel.sendOfflineFrame(
            BandwidthUpgradeFrames.upgradePathRequest(setOf(Medium.WIFI_DIRECT)),
        )
        logger("medium-upgrade: requested receiver Wi-Fi Direct upgrade before streaming payloads")
        logger("medium-upgrade: waiting for receiver Wi-Fi Direct offer before streaming payloads")
        return when (val probe = pollBleWifiDirectOffer(channel, BLE_WIFI_DIRECT_UPGRADE_TIMEOUT_MILLIS)) {
            UpgradeOfferProbe.None -> {
                logger(
                    "medium-upgrade: Wi-Fi Direct upgrade was not offered before payload streaming; " +
                        "continuing on $currentMedium",
                )
                PayloadChannelSelection.Ready(channel, currentMedium)
            }
            is UpgradeOfferProbe.Other ->
                PayloadChannelSelection.Failed(
                    "Wi-Fi Direct upgrade was required before payload streaming, " +
                        "but peer sent ${probe.frame.describeFrameType()} first",
                )
            is UpgradeOfferProbe.Offer -> {
                val upgraded =
                    adoptBleWifiDirectOffer(
                        channel = channel,
                        currentMedium = currentMedium,
                        offer = probe.frame,
                    )
                if (upgraded.medium == Medium.WIFI_DIRECT) {
                    PayloadChannelSelection.Ready(
                        channel = upgraded.channel,
                        medium = upgraded.medium,
                        wifiFrequencyMhz = upgraded.wifiFrequencyMhz,
                    )
                } else {
                    PayloadChannelSelection.Failed(
                        "Wi-Fi Direct upgrade failed before payload streaming; stayed on ${upgraded.medium}",
                    )
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun pollBleWifiDirectOffer(
        channel: SecureChannel,
        timeoutMillis: Long,
    ): UpgradeOfferProbe {
        val deadlineMillis = nowMillisSource() + timeoutMillis
        while (nowMillisSource() < deadlineMillis) {
            if (channel.hasBufferedInput()) {
                val frame = channel.receiveOfflineFrame()
                when {
                    frame.isBandwidthUpgradeEvent(
                        BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                    ) -> return UpgradeOfferProbe.Offer(frame)
                    frame.hasV1() && frame.v1.type == V1Frame.FrameType.KEEP_ALIVE -> {
                        logger("medium-upgrade: ignoring KEEP_ALIVE while waiting for Wi-Fi Direct offer")
                    }
                    else -> return UpgradeOfferProbe.Other(frame)
                }
            }
            delay(BLE_WIFI_DIRECT_POLL_DELAY_MILLIS)
        }
        return UpgradeOfferProbe.None
    }

    private suspend fun adoptBleWifiDirectOffer(
        channel: SecureChannel,
        currentMedium: Medium,
        offer: OfflineFrame,
    ): ActiveTransportChannel {
        val upgraded =
            BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                oldChannel = channel,
                currentMedium = currentMedium,
                offer = offer,
                mediumRegistry = mediumRegistry,
                endpointId = endpointId,
                logger = logger,
            )
        secureChannel = upgraded.channel
        return upgraded
    }

    private suspend fun failBleWifiDirect(
        reason: String,
        channel: SecureChannel,
    ): OutboundResult.Failed {
        logger("medium-upgrade: $reason")
        mutableState.value = OutboundConnectionState.Failed(reason)
        runCatching { sendTerminalDisconnection(channel) }
        return OutboundResult.Failed(reason)
    }

    private suspend fun drainSafeDisconnectAckDirect(channel: SecureChannel) {
        val timeoutMillis = safeDisconnectAckTimeoutMillis()
        val acked =
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    if (channel.hasBufferedInput()) {
                        val frame = channel.receiveOfflineFrame()
                        if (frame.hasV1() && frame.v1.type == V1Frame.FrameType.DISCONNECTION) {
                            logger(
                                "fsm: safe-disconnect peer Disconnection ack=" +
                                    frame.v1.disconnection.ackSafeToDisconnect,
                            )
                            return@withTimeoutOrNull true
                        }
                    }
                    delay(BLE_WIFI_DIRECT_POLL_DELAY_MILLIS)
                }
                false
            } == true
        if (!acked) {
            logger(
                "fsm: safe-disconnect drain timed out after " +
                    "${timeoutMillis}ms",
            )
        }
    }

    private fun safeDisconnectAckTimeoutMillis(): Long {
        if (totalSize <= 0L) return SAFE_DISCONNECT_ACK_TIMEOUT_MIN_MS
        val chunkSize = PayloadTransferEncoder.DEFAULT_FILE_CHUNK_SIZE.toLong()
        val chunkCount = ((totalSize + chunkSize - 1L) / chunkSize).coerceAtLeast(1L)
        val sizeScaled = chunkCount * SAFE_DISCONNECT_ACK_TIMEOUT_PER_CHUNK_MS
        return sizeScaled
            .coerceAtLeast(SAFE_DISCONNECT_ACK_TIMEOUT_MIN_MS)
            .coerceAtMost(SAFE_DISCONNECT_ACK_TIMEOUT_MAX_MS)
    }

    /**
     * True when [result] is a terminal we ourselves drove a
     * `request_safe_to_disconnect=true` Disconnection for; only then
     * is it worth waiting for the peer's ack. Cancelled-by-peer and
     * Failed paths never sent that request, so the peer has nothing
     * to ack and the drain would only block for the full timeout.
     */
    private fun shouldDrainForSafeDisconnect(result: OutboundResult): Boolean =
        when (result) {
            OutboundResult.Completed -> true
            is OutboundResult.Rejected -> true
            is OutboundResult.Cancelled -> result.cause == CancelCause.LOCAL
            is OutboundResult.Failed -> false
        }

    /**
     * Body of the safe-disconnect drain loop, wrapped in
     * `withTimeoutOrNull(SAFE_DISCONNECT_ACK_TIMEOUT_MS)` at the call
     * site so the timeout text can be logged on null return without
     * threading another logger reference through this helper.
     */
    @Suppress("ReturnCount") // Three branches × early-return is the cleanest dispatch shape.
    private suspend fun drainSafeDisconnectAck(ch: Channel<OutboundWireMessage>) {
        while (true) {
            val msg = ch.receive()
            when (msg) {
                is OutboundWireMessage.Frame -> {
                    val isDisc =
                        msg.frame.hasV1() &&
                            msg.frame.v1.type == V1Frame.FrameType.DISCONNECTION
                    if (isDisc) {
                        val ack = msg.frame.v1.disconnection.ackSafeToDisconnect
                        logger("fsm: safe-disconnect peer Disconnection ack=$ack")
                        return
                    }
                    // Non-Disconnection frame mid-drain (e.g. a stale
                    // PAYLOAD_TRANSFER): ignore and keep waiting.
                }
                OutboundWireMessage.Closed -> {
                    logger("fsm: safe-disconnect peer FIN observed")
                    return
                }
                is OutboundWireMessage.Error -> {
                    logger(
                        "fsm: safe-disconnect drain pump error " +
                            (msg.cause::class.simpleName ?: "<unknown>"),
                    )
                    return
                }
            }
        }
    }

    /**
     * Inbound pump. Reads frames from the [SecureChannel] in a loop and
     * forwards them onto [wireChannel].
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun runInboundPump(
        channel: SecureChannel,
        wireChannel: Channel<OutboundWireMessage>,
    ) {
        try {
            while (true) {
                val frame = channel.receiveOfflineFrame()
                wireChannel.send(OutboundWireMessage.Frame(frame))
            }
        } catch (e: EndOfFrameStream) {
            wireChannel.send(OutboundWireMessage.Closed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            wireChannel.trySend(OutboundWireMessage.Error(e))
        }
    }

    /**
     * Dispatch loop — runs until a terminal state is reached.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun dispatchLoop(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        wireChannel: Channel<OutboundWireMessage>,
        initialWireFrames: List<OfflineFrame> = emptyList(),
    ): OutboundResult {
        val bufferedFrames =
            ArrayDeque<OfflineFrame>().apply {
                addAll(initialWireFrames)
            }
        var remoteAcceptanceDeadlineMillis: Long? = null
        while (true) {
            // Terminal state? Resolve and return.
            if (fsm.state == OutboundSharingState.Disconnected) {
                return terminalResultFromState()
            }

            remoteAcceptanceDeadlineMillis =
                updateRemoteAcceptanceDeadline(
                    fsm = fsm,
                    currentDeadlineMillis = remoteAcceptanceDeadlineMillis,
                )
            val remoteAcceptanceRemainingMillis = remoteAcceptanceRemainingMillis(remoteAcceptanceDeadlineMillis)
            if (remoteAcceptanceRemainingMillis == 0L) {
                return remoteAcceptanceTimedOut()
            }

            val event: OutboundDriverEvent =
                if (bufferedFrames.isNotEmpty()) {
                    bufferedFrames
                        .removeFirst()
                        .let { frame -> OutboundDriverEvent.Wire(frame) }
                } else {
                    select {
                        externalEvents.onReceive { ev -> OutboundDriverEvent.External(ev) }
                        wireChannel.onReceive { msg ->
                            when (msg) {
                                is OutboundWireMessage.Frame -> OutboundDriverEvent.Wire(msg.frame)
                                OutboundWireMessage.Closed -> OutboundDriverEvent.PeerClosed
                                is OutboundWireMessage.Error -> OutboundDriverEvent.PumpError(msg.cause)
                            }
                        }
                        remoteAcceptanceRemainingMillis?.let { remainingMillis ->
                            onTimeout(remainingMillis) {
                                OutboundDriverEvent.RemoteAcceptanceTimedOut
                            }
                        }
                    }
                }

            logger("fsm: dispatch event=${describeDriverEvent(event)} fsmState=${fsm.state::class.simpleName}")

            val terminal = handleDriverEvent(channel, fsm, event)
            if (terminal != null) {
                logger("fsm: dispatch terminal=${terminal::class.simpleName}")
                return terminal
            }
        }
    }

    private fun describeDriverEvent(event: OutboundDriverEvent): String =
        when (event) {
            is OutboundDriverEvent.External -> "External(${event.event::class.simpleName})"
            is OutboundDriverEvent.Wire -> {
                val v1Type = if (event.frame.hasV1()) "${event.frame.v1.type}" else "no-v1"
                "Wire($v1Type)"
            }
            OutboundDriverEvent.PeerClosed -> "PeerClosed"
            OutboundDriverEvent.RemoteAcceptanceTimedOut -> "RemoteAcceptanceTimedOut"
            is OutboundDriverEvent.PumpError ->
                "PumpError(${event.cause::class.simpleName}: ${event.cause.message ?: "null"})"
        }

    /** Handle one driver-level event. Non-null result triggers termination. */
    private suspend fun handleDriverEvent(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: OutboundDriverEvent,
    ): OutboundResult? =
        when (event) {
            is OutboundDriverEvent.External -> {
                handleExternalEvent(channel, fsm, event.event)
                null
            }
            is OutboundDriverEvent.Wire -> handleInboundOfflineFrame(channel, fsm, event.frame)
            OutboundDriverEvent.PeerClosed -> handlePeerClosed()
            OutboundDriverEvent.RemoteAcceptanceTimedOut -> remoteAcceptanceTimedOut()
            is OutboundDriverEvent.PumpError -> {
                val reason = "Inbound pump error: ${event.cause::class.simpleName}: ${event.cause.message}"
                mutableState.value = OutboundConnectionState.Failed(reason)
                OutboundResult.Failed(reason)
            }
        }

    private fun updateRemoteAcceptanceDeadline(
        fsm: OutboundSharingFsm,
        currentDeadlineMillis: Long?,
    ): Long? =
        when {
            fsm.state != OutboundSharingState.SentIntroduction -> null
            currentDeadlineMillis != null -> currentDeadlineMillis
            else -> {
                logger("fsm: awaiting receiver acceptance timeout=${remoteAcceptanceTimeoutMillis}ms")
                nowMillisSource() + remoteAcceptanceTimeoutMillis
            }
        }

    private fun remoteAcceptanceRemainingMillis(deadlineMillis: Long?): Long? =
        deadlineMillis?.let { deadline ->
            (deadline - nowMillisSource()).coerceAtLeast(0L)
        }

    private fun remoteAcceptanceTimedOut(): OutboundResult.Failed {
        val reason = "Timed out waiting for receiver acceptance after ${remoteAcceptanceTimeoutMillis}ms"
        logger("fsm: $reason")
        mutableState.value = OutboundConnectionState.Failed(reason)
        return OutboundResult.Failed(reason)
    }

    /**
     * Handle one inbound `OfflineFrame` from the SecureChannel.
     * Returns a non-null [OutboundResult] when the frame caused a
     * terminal transition; null to continue the receive loop.
     */
    @Suppress("ReturnCount")
    private suspend fun handleInboundOfflineFrame(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        frame: OfflineFrame,
    ): OutboundResult? {
        if (frame.isDisconnection()) {
            // Peer is leaving. From the sender's perspective, if we
            // were already in the SendingPayloads stage (i.e. the
            // peer accepted), treat as a clean cancel rather than a
            // protocol error — Quick Share receivers can sometimes
            // hang up early after acknowledging.
            return cancelFromPeer()
        }

        if (frame.hasV1() &&
            frame.v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION
        ) {
            // Inbound BANDWIDTH_UPGRADE_NEGOTIATION: the receiver wants
            // to switch transports. Phase 4 (#48–#54) wires the
            // negotiator FSM in here; today we observe the event and
            // drop it (the peer falls back to staying on Wi-Fi LAN
            // when no UPGRADE_PATH_AVAILABLE answer arrives). Logging
            // it makes the eventual integration test predicate easy
            // to write.
            logger(
                "fsm: inbound BANDWIDTH_UPGRADE_NEGOTIATION event_type=" +
                    frame.v1.bandwidthUpgradeNegotiation.eventType +
                    " (no negotiator wired yet; ignored)",
            )
            return null
        }

        if (!frame.hasV1() || frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) {
            // KEEP_ALIVE and other non-PAYLOAD_TRANSFER frames are
            // ignored; same policy as InboundConnectionDriver.
            return null
        }
        // Reuse a per-call assembler — each negotiation BYTES payload
        // arrives as two `PayloadTransferFrame`s (data + LAST_CHUNK
        // terminator) and the assembler stitches them together.
        val payloadEvent = inboundAssembler.onPayloadTransfer(frame.v1.payloadTransfer)
        return handlePayloadEvent(channel, fsm, payloadEvent)
    }

    /**
     * Dedicated assembler for incoming negotiation BYTES payloads
     * (PKE, PKR, ConnectionResponse). The sender does not receive FILE
     * or text payloads; if the receiver ever sends one we surface it
     * as a protocol error via the FSM's unexpected-frame path.
     */
    private val inboundAssembler: PayloadAssembler = PayloadAssembler()

    /**
     * Decode a [PayloadEvent] into FSM events. Returns a terminal
     * [OutboundResult] when the event drove the connection to
     * completion.
     */
    private suspend fun handlePayloadEvent(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: PayloadEvent,
    ): OutboundResult? =
        when (event) {
            is PayloadEvent.BytesComplete -> handleBytesComplete(channel, fsm, event)
            // The sender doesn't expect FILE payloads. The assembler
            // would have written to a temp file via the default
            // factory; we ignore the data.
            is PayloadEvent.FileComplete,
            is PayloadEvent.Progress,
            is PayloadEvent.Ignored,
            -> null
        }

    /**
     * Parse a complete BYTES payload as a [SharingFrame] and feed it
     * to the FSM. Stream files when the FSM emits
     * [SharingFsmEffect.ReadyToSendPayloads].
     */
    private suspend fun handleBytesComplete(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: PayloadEvent.BytesComplete,
    ): OutboundResult? {
        val sharingFrame = SharingFrames.parse(event.data)
        logger("fsm: rx SharingFrame type=${sharingFrame.v1.type}")
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharingFrame))
        applyEffects(channel, effects)
        if (effects.any { it is SharingFsmEffect.ReadyToSendPayloads }) {
            return streamFilesAndComplete(channel)
        }
        return null
    }

    /**
     * Stream every announced file in 512 KiB chunks, then send
     * `Disconnection`. This is the happy-path terminal: returns
     * [OutboundResult.Completed] on success.
     *
     * Cancellation during streaming is cooperative: the dispatch loop
     * is suspended while we run, but [externalEvents] is polled on
     * each chunk so a `cancel()` call still emits a CANCEL frame
     * before we close.
     */
    private suspend fun streamFilesAndComplete(channel: SecureChannel): OutboundResult {
        logger("fsm: streamFilesAndComplete START files=${files.size} totalSize=$totalSize")
        // Seed the rate estimator with a zero-bytes sample so the
        // first chunk write produces a non-degenerate Δt for the EMA.
        rateEstimator.sample(bytesTransferred = 0L, nowMillis = nowMillisSource())
        mutableState.value =
            OutboundConnectionState.Sending(
                pin = pin,
                progress =
                    TransferProgress.of(
                        bytesTransferred = 0L,
                        totalSize = totalSize,
                        bytesPerSecond = 0L,
                    ),
                currentItemPayloadId = files.firstOrNull()?.payloadId,
            )

        for (file in files) {
            currentItemPayloadId = file.payloadId
            logger("fsm: streamOneFile START name=${file.name} size=${file.size} payloadId=${file.payloadId}")
            val cancelled = streamOneFile(channel, file)
            if (cancelled != null) {
                logger("fsm: streamOneFile EARLY-RETURN ${cancelled::class.simpleName}")
                return cancelled
            }
            logger("fsm: streamOneFile DONE name=${file.name}")
        }

        // All files streamed. Emit Disconnection and complete.
        logger("fsm: all files streamed, sending Disconnection")
        runCatching { sendTerminalDisconnection(channel) }
        return OutboundResult.Completed
    }

    private fun publishCompletedIfNeeded(result: OutboundResult): OutboundResult {
        if (result == OutboundResult.Completed) {
            mutableState.value = OutboundConnectionState.Completed
        }
        return result
    }

    /**
     * Stream a single [file] in 512 KiB chunks. Returns a non-null
     * terminal result if the user cancelled mid-file; null on
     * successful completion of this single file.
     */
    private suspend fun streamOneFile(
        channel: SecureChannel,
        file: FileSource,
    ): OutboundResult? {
        val source = file.openChannel()
        var chunkIdx = 0
        try {
            val frames =
                PayloadTransferEncoder.encodeFilePayload(
                    payloadId = file.payloadId,
                    fileName = file.name,
                    totalSize = file.size,
                    source = source,
                    chunkSize = PayloadTransferEncoder.DEFAULT_FILE_CHUNK_SIZE,
                    lastModifiedTimestampMillis = file.lastModifiedTimestampMillis,
                    // `parent_folder` is also carried on every PayloadHeader,
                    // not just on FileMetadata. Quick Share receivers can
                    // reconcile the two sources of truth — but we set both
                    // to the same value to match stock Android's behaviour
                    // and to remain robust against receivers that key
                    // exclusively off the PayloadHeader (which the chunk
                    // assembler sees first, before negotiation finalizes).
                    parentFolder = file.parentFolder,
                )
            for (frame in frames) {
                // Poll for cancellation between chunks. trySend semantics
                // for an UNLIMITED channel never block — receive() also
                // never blocks if there's no event waiting.
                val pending = externalEvents.tryReceive().getOrNull()
                if (pending == OutboundExternalEvent.UserCancel) {
                    return userCancelDuringTransfer(channel)
                }
                channel.sendOfflineFrame(frame)
                bytesSent += chunkBodySize(frame)
                publishSendingProgress()
                if (chunkIdx == 0) {
                    logger("fsm: streamOneFile first chunk written bytesSent=$bytesSent")
                }
                chunkIdx++
            }
            logger("fsm: streamOneFile loop end chunks=$chunkIdx bytesSent=$bytesSent")
        } finally {
            runCatching { source.close() }
        }
        return null
    }

    /** Returns the body size (data bytes) of the chunk in [frame]. */
    private fun chunkBodySize(frame: OfflineFrame): Long =
        frame.v1.payloadTransfer.payloadChunk.body
            .size()
            .toLong()

    private fun publishSendingProgress() {
        // Feed the rate estimator on every chunk so its EMA tracks the
        // instantaneous wire throughput, then publish the smoothed
        // bytes/sec back through TransferProgress so the sender UI can
        // render rate + ETA.
        rateEstimator.sample(bytesTransferred = bytesSent, nowMillis = nowMillisSource())
        mutableState.value =
            OutboundConnectionState.Sending(
                pin = pin,
                progress =
                    TransferProgress.of(
                        bytesTransferred = bytesSent,
                        totalSize = totalSize,
                        bytesPerSecond = rateEstimator.bytesPerSecond(),
                    ),
                currentItemPayloadId = currentItemPayloadId,
            )
    }

    /**
     * Mid-transfer user cancel: send a CANCEL Sharing frame, then
     * Disconnection, then surface Cancelled.
     */
    private suspend fun userCancelDuringTransfer(channel: SecureChannel): OutboundResult {
        runCatching {
            sendSharingFrame(channel, SharingFrames.cancel())
            channel.sendOfflineFrame(OfflineFrames.disconnection(requestSafeToDisconnect = true))
        }
        mutableState.value = OutboundConnectionState.Cancelled(CancelCause.LOCAL)
        return OutboundResult.Cancelled(CancelCause.LOCAL)
    }

    /**
     * Peer cleanly closed the TCP half-channel between frames.
     */
    private fun handlePeerClosed(): OutboundResult {
        val current = mutableState.value
        logger("fsm: peerClosed observed (currentState=${current::class.simpleName})")
        // If the FSM/state already resolved this transfer (e.g. we
        // just finished streaming and the peer hung up after our
        // Disconnection), keep the existing terminal.
        return when (current) {
            OutboundConnectionState.Completed -> OutboundResult.Completed
            is OutboundConnectionState.Rejected -> OutboundResult.Rejected(current.status)
            is OutboundConnectionState.Cancelled -> OutboundResult.Cancelled(current.cause)
            is OutboundConnectionState.Failed -> OutboundResult.Failed(current.reason)
            else -> {
                publishCancelled(CancelCause.PEER)
                OutboundResult.Cancelled(CancelCause.PEER)
            }
        }
    }

    /**
     * Drain one [OutboundExternalEvent] (cancel) into the FSM.
     */
    private suspend fun handleExternalEvent(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        event: OutboundExternalEvent,
    ) {
        when (event) {
            OutboundExternalEvent.UserCancel -> {
                val effects = fsm.onEvent(SharingFsmEvent.UserCancel)
                applyEffects(channel, effects)
            }
        }
    }

    /**
     * Peer-disconnected during negotiation or transfer: route as a
     * peer-cancel.
     */
    private fun cancelFromPeer(): OutboundResult {
        logger("fsm: cancelFromPeer (peer Disconnection observed)")
        publishCancelled(CancelCause.PEER)
        return OutboundResult.Cancelled(CancelCause.PEER)
    }

    /**
     * Apply an FSM effect list. The FSM guarantees `SendFrame`
     * precedes terminal notifications, so iterating top-to-bottom puts
     * outbound bytes onto the wire before the connection tears down.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per SharingFsmEffect variant is the cleanest dispatch.
    private suspend fun applyEffects(
        channel: SecureChannel,
        effects: List<SharingFsmEffect>,
    ) {
        for (effect in effects) {
            logger("fsm: effect=${describeEffect(effect)}")
            when (effect) {
                is SharingFsmEffect.SendFrame -> {
                    sendSharingFrame(channel, effect.frame)
                    // The IntroductionFrame is the last negotiation frame
                    // we send before genuinely awaiting the receiver's
                    // ACCEPT/REJECT decision. Publishing the state here
                    // (rather than at FSM-start) ensures consumers only
                    // observe AwaitingRemoteAcceptance after the PKE/PKR
                    // dance has finished — see the note in runLifecycle
                    // for the cancel-race rationale.
                    if (effect.frame.v1.type == SharingFrameType.INTRODUCTION) {
                        mutableState.value =
                            OutboundConnectionState.AwaitingRemoteAcceptance(pin)
                    }
                }
                is SharingFsmEffect.Rejected -> {
                    mutableState.value = OutboundConnectionState.Rejected(effect.status)
                    runCatching { sendTerminalDisconnection(channel) }
                }
                SharingFsmEffect.Cancelled -> {
                    val rejected = effects.any { it is SharingFsmEffect.Rejected }
                    if (rejected) {
                        // Already published Rejected above; keep that
                        // terminal. Do not overwrite with Cancelled.
                    } else if (mutableState.value !is OutboundConnectionState.Cancelled) {
                        publishCancelled(CancelCause.LOCAL)
                        runCatching { sendTerminalDisconnection(channel) }
                    }
                }
                SharingFsmEffect.Completed -> Unit
                is SharingFsmEffect.ProtocolError -> {
                    mutableState.value = OutboundConnectionState.Failed(effect.reason)
                    runCatching { sendTerminalDisconnection(channel) }
                }
                SharingFsmEffect.ReadyToSendPayloads -> Unit
                // Receiver-only effects — should never be produced by
                // OutboundSharingFsm. If one does, treat as protocol
                // error so the bug surfaces loudly.
                SharingFsmEffect.ReadyToReceivePayloads,
                is SharingFsmEffect.IntroductionReceived,
                -> {
                    mutableState.value =
                        OutboundConnectionState.Failed(
                            "OutboundSharingFsm produced receiver-only effect: ${effect::class.simpleName}",
                        )
                }
            }
        }
    }

    private fun describeEffect(effect: SharingFsmEffect): String =
        when (effect) {
            is SharingFsmEffect.SendFrame -> "SendFrame(${effect.frame.v1.type})"
            is SharingFsmEffect.Rejected -> "Rejected(status=${effect.status})"
            SharingFsmEffect.Cancelled -> "Cancelled"
            SharingFsmEffect.Completed -> "Completed"
            is SharingFsmEffect.ProtocolError -> "ProtocolError(${effect.reason})"
            SharingFsmEffect.ReadyToSendPayloads -> "ReadyToSendPayloads"
            SharingFsmEffect.ReadyToReceivePayloads -> "ReadyToReceivePayloads"
            is SharingFsmEffect.IntroductionReceived -> "IntroductionReceived"
        }

    /**
     * Encode a [SharingFrame] as a BYTES payload and push every
     * resulting [OfflineFrame] through the SecureChannel. Each
     * negotiation BYTES payload uses a fresh random `payload_id`.
     */
    private suspend fun sendSharingFrame(
        channel: SecureChannel,
        frame: SharingFrame,
    ) {
        val payloadId = nonZeroRandomLong()
        val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId, frame.toByteArray())
        for (f in frames) {
            channel.sendOfflineFrame(f)
        }
    }

    /**
     * Draws a non-zero random `Long` for use as a Quick Share
     * `payload_id`. Zero is excluded because the proto's default-int
     * value is `0`, and a `payload_id` of `0` collides with the
     * "no id supplied" path in the receiver's reassembler.
     */
    private fun nonZeroRandomLong(): Long {
        var v = secureRandom.nextLong()
        while (v == 0L) v = secureRandom.nextLong()
        return v
    }

    /**
     * Resolve the FSM's terminal state into a final [OutboundResult].
     */
    private fun terminalResultFromState(): OutboundResult {
        val s = mutableState.value
        logger("fsm: terminalResultFromState resolving state=${s::class.simpleName}")
        return when (s) {
            OutboundConnectionState.Completed -> OutboundResult.Completed
            is OutboundConnectionState.Rejected -> OutboundResult.Rejected(s.status)
            is OutboundConnectionState.Cancelled -> OutboundResult.Cancelled(s.cause)
            is OutboundConnectionState.Failed -> OutboundResult.Failed(s.reason)
            else -> {
                val reason = "FSM disconnected without a terminal state (state=$s)"
                mutableState.value = OutboundConnectionState.Failed(reason)
                OutboundResult.Failed(reason)
            }
        }
    }

    private fun publishCancelled(cause: CancelCause) {
        mutableState.value = OutboundConnectionState.Cancelled(cause)
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

    /**
     * Send the terminal `DisconnectionFrame` with
     * `request_safe_to_disconnect = true`. Receiver must drain its
     * read pipeline (writing any in-flight payloads to disk) before
     * acknowledging. Wrap call sites in `runCatching` because the
     * socket may already be half-closed by the time we get here on
     * the cancel/reject paths.
     */
    private suspend fun sendTerminalDisconnection(channel: SecureChannel) {
        channel.sendOfflineFrame(OfflineFrames.disconnection(requestSafeToDisconnect = true))
    }

    private fun OfflineFrame.describeFrameType(): String =
        if (hasV1()) {
            when (v1.type) {
                V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION ->
                    "${v1.type}(${v1.bandwidthUpgradeNegotiation.eventType})"
                V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY ->
                    "${v1.type}(request=${v1.bandwidthUpgradeRetry.isRequest})"
                else -> v1.type.toString()
            }
        } else {
            "NO_V1"
        }

    private fun OfflineFrame.isBandwidthUpgradeEvent(expected: BandwidthUpgradeNegotiationFrame.EventType): Boolean =
        hasV1() &&
            v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
            v1.bandwidthUpgradeNegotiation.eventType == expected

    private companion object {
        // How long to wait for a pre-UKEY2 BLE bandwidth-upgrade offer
        // before giving up and sending ClientInit on the current medium.
        // This bounds ONLY the no-offer path: the probe returns the instant
        // an offer is buffered, so a receiver that does send one is detected
        // just as fast regardless of this value. Stock GMS receivers never
        // send a pre-UKEY2 offer (issue #216) — they sit silent waiting for
        // ClientInit and drop the BLE GATT link ~1.05–1.24s after our
        // ConnectionRequest. The old 1500ms wait pushed ClientInit past that
        // window, so the receiver tore us down before UKEY2 began. 400ms is
        // comfortably under the earliest observed drop (≥650ms margin) while
        // still leaving ample headroom to catch a real proactive offer.
        private const val PRE_UKEY2_UPGRADE_OFFER_WAIT_TIMEOUT_MILLIS: Long = 400L
        private const val BLE_WIFI_DIRECT_UPGRADE_TIMEOUT_MILLIS: Long = 3_000L
        private const val BLE_WIFI_DIRECT_POLL_DELAY_MILLIS: Long = 25L

        /**
         * Minimum time the orchestrator waits for the peer's
         * `DisconnectionFrame{ack_safe_to_disconnect=true}` (or peer
         * FIN) after sending its own request. 1500 ms covers Samsung
         * One UI 8.0.5's observed drain time for small payloads, plus
         * headroom for stock Quick Share builds that do not send an ack
         * before their receive UI leaves "Preparing..."; larger payloads
         * scale this window by FILE chunk count because the receiver only
         * acks after writing all pending payloads to disk.
         */
        private const val SAFE_DISCONNECT_ACK_TIMEOUT_MIN_MS: Long = 5_000L
        private const val SAFE_DISCONNECT_ACK_TIMEOUT_PER_CHUNK_MS: Long = 1_000L
        private const val SAFE_DISCONNECT_ACK_TIMEOUT_MAX_MS: Long = 60_000L
    }

    private sealed interface BandwidthNegotiation {
        data class Ready(
            val channel: SecureChannel,
            val medium: Medium,
            val initialWireFrames: List<OfflineFrame>,
            val wifiFrequencyMhz: Int? = null,
        ) : BandwidthNegotiation

        data class Failed(
            val reason: String,
        ) : BandwidthNegotiation
    }

    private data class PreSecureTransport(
        val connection: FramedConnection,
        val medium: Medium,
        val wifiFrequencyMhz: Int? = null,
    )

    private sealed interface PreUkey2Negotiation {
        data class Ready(
            val transport: PreSecureTransport,
        ) : PreUkey2Negotiation

        data class Failed(
            val reason: String,
        ) : PreUkey2Negotiation
    }

    private sealed interface BleWifiDirectOutcome {
        data object Continue : BleWifiDirectOutcome

        data object ReadyToSendPayloads : BleWifiDirectOutcome

        data class Terminal(
            val result: OutboundResult,
        ) : BleWifiDirectOutcome
    }

    private sealed interface PayloadChannelSelection {
        data class Ready(
            val channel: SecureChannel,
            val medium: Medium,
            val wifiFrequencyMhz: Int? = null,
        ) : PayloadChannelSelection

        data class Failed(
            val reason: String,
        ) : PayloadChannelSelection
    }

    private fun Medium.isBleBased(): Boolean = this == Medium.BLE || this == Medium.BLE_L2CAP
}
