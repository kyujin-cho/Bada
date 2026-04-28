/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel
import io.github.kyujincho.wvmg.protocol.payload.PayloadAssembler
import io.github.kyujincho.wvmg.protocol.payload.PayloadEvent
import io.github.kyujincho.wvmg.protocol.payload.PayloadTransferEncoder
import io.github.kyujincho.wvmg.protocol.sharing.OutboundSharingFsm
import io.github.kyujincho.wvmg.protocol.sharing.OutboundSharingState
import io.github.kyujincho.wvmg.protocol.sharing.PairedKeyEncryptionFrame
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrame
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrameType
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrameVersion
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrames
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEffect
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEvent
import io.github.kyujincho.wvmg.protocol.sharing.SharingV1Frame
import io.github.kyujincho.wvmg.protocol.transport.EndOfFrameStream
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import io.github.kyujincho.wvmg.protocol.ukey2.Ukey2Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.security.SecureRandom

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
    "TooManyFunctions", // The lifecycle inherently has many phases; each is one function for readability.
    "LongParameterList", // Constructor takes the connection's collaborators verbatim.
)
internal class OutboundConnectionDriver(
    private val socket: Socket,
    private val secureRandom: SecureRandom,
    private val externalEvents: Channel<OutboundExternalEvent>,
    private val mutableState: MutableStateFlow<OutboundConnectionState>,
    private val endpointId: String,
    private val endpointInfo: ByteArray,
    private val qrCodeHandshakeData: ByteArray?,
    private val files: List<FileSource>,
    private val onHandshakeComplete: () -> Unit = {},
    private val logger: (String) -> Unit = {},
) {
    private var framedConnection: FramedConnection? = null
    private var secureChannel: SecureChannel? = null
    private var fsm: OutboundSharingFsm? = null

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
    suspend fun runLifecycle(): OutboundResult {
        mutableState.value = OutboundConnectionState.Handshaking
        logger(
            "step 1: TCP socket open (${socket.inetAddress?.hostAddress}:${socket.port}) " +
                "endpointId=$endpointId endpointInfo.size=${endpointInfo.size}",
        )
        val transport = FramedConnection(socket).also { framedConnection = it }

        // Step 1: send unencrypted ConnectionRequest.
        transport.sendFrame(
            OutboundFrames.connectionRequest(endpointId, endpointInfo).toByteArray(),
        )
        logger("step 1: sent ConnectionRequest, awaiting Ukey2ServerInit")

        // Step 2: UKEY2 client handshake (ClientInit, ServerInit, ClientFinish).
        val handshake = Ukey2Client.performHandshake(transport, secureRandom)
        logger("step 2: UKEY2 client handshake complete (dhs.size=${handshake.dhs.size})")

        // Step 3: send our unencrypted ConnectionResponse{ACCEPT}.
        //
        // Order matters: NearDrop's OutboundNearbyConnection sends its
        // ConnectionResponse BEFORE reading the peer's. Stock Quick Share
        // on Android 14+ expects the sender's response on the wire first;
        // if we read instead, both sides deadlock and the peer eventually
        // times out and closes (surfacing here as EndOfFrameStream right
        // after the UKEY2 handshake).
        val responseBytes = OutboundFrames.connectionResponse().toByteArray()
        logger("step 3: sending our ConnectionResponse, size=${responseBytes.size}")
        transport.sendFrame(responseBytes)
        logger("step 3: sent our ConnectionResponse{ACCEPT}")

        // Step 4: read receiver's unencrypted ConnectionResponse.
        logger("step 4: awaiting peer ConnectionResponse...")
        val peerResponse = readOfflineFrameUnencrypted(transport)
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

        // Step 6: SecureChannel takes over.
        val channel = SecureChannel(transport, sessionKeys, secureRandom).also { secureChannel = it }

        // Step 7: build the OutboundSharingFsm with our IntroductionFrame.
        val introduction = buildIntroductionFrame(files)
        val negotiationFsm =
            OutboundSharingFsm(introduction = introduction, secureRandom = secureRandom)
                .also { fsm = it }

        // Step 8: drive the FSM's initial PKE frame onto the wire,
        // optionally attaching qr_code_handshake_data.
        logger("step 8: sending initial PKE frame")
        applyEffects(channel, rewriteForQrIfNeeded(negotiationFsm.start()))

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

        return runReceiveLoop(channel, negotiationFsm)
    }

    /**
     * Tear down all owned resources. Safe to call multiple times; each
     * closeable swallows its own IOException so a single failing close
     * cannot leak the others.
     */
    fun tearDown() {
        runCatching { secureChannel?.close() }
        runCatching { framedConnection?.close() }
        runCatching { socket.close() }
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
    ): OutboundResult =
        coroutineScope {
            // RENDEZVOUS capacity — same back-pressure rationale as
            // InboundConnectionDriver.
            val wireChannel: Channel<OutboundWireMessage> = Channel(Channel.RENDEZVOUS)
            val pumpJob: Job = launch { runInboundPump(channel, wireChannel) }
            try {
                val result = dispatchLoop(channel, fsm, wireChannel)
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
                    withTimeoutOrNull(SAFE_DISCONNECT_ACK_TIMEOUT_MS) {
                        drainSafeDisconnectAck(wireChannel)
                    } ?: logger(
                        "fsm: safe-disconnect drain timed out after " +
                            "${SAFE_DISCONNECT_ACK_TIMEOUT_MS}ms",
                    )
                }
                result
            } finally {
                // Close the socket BEFORE cancelAndJoin: the pump is parked
                // in SecureChannel.receiveOfflineFrame()'s blocking Socket
                // read under withContext(Dispatchers.IO), which does NOT
                // honour coroutine cancellation while parked in a syscall.
                // Closing the socket throws SocketException out of the read
                // (the pump catches it as a Throwable and exits), so
                // cancelAndJoin can complete. TCP guarantees any bytes
                // already in the send buffer (CANCEL / Disconnection) are
                // transmitted before the FIN, so the peer still reads our
                // final frames before observing EOF.
                runCatching { socket.close() }
                pumpJob.cancelAndJoin()
            }
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
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun dispatchLoop(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        wireChannel: Channel<OutboundWireMessage>,
    ): OutboundResult {
        while (true) {
            // Terminal state? Resolve and return.
            if (fsm.state == OutboundSharingState.Disconnected) {
                return terminalResultFromState()
            }

            val event: OutboundDriverEvent =
                select {
                    externalEvents.onReceive { ev -> OutboundDriverEvent.External(ev) }
                    wireChannel.onReceive { msg ->
                        when (msg) {
                            is OutboundWireMessage.Frame -> OutboundDriverEvent.Wire(msg.frame)
                            OutboundWireMessage.Closed -> OutboundDriverEvent.PeerClosed
                            is OutboundWireMessage.Error -> OutboundDriverEvent.PumpError(msg.cause)
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
            is OutboundDriverEvent.PumpError -> {
                val reason = "Inbound pump error: ${event.cause::class.simpleName}: ${event.cause.message}"
                mutableState.value = OutboundConnectionState.Failed(reason)
                OutboundResult.Failed(reason)
            }
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
        mutableState.value =
            OutboundConnectionState.Sending(
                pin = pin,
                bytesSent = 0L,
                totalSize = totalSize,
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
        mutableState.value = OutboundConnectionState.Completed
        return OutboundResult.Completed
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
        mutableState.value =
            OutboundConnectionState.Sending(
                pin = pin,
                bytesSent = bytesSent,
                totalSize = totalSize,
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

    private companion object {
        /**
         * Maximum time the orchestrator waits for the peer's
         * `DisconnectionFrame{ack_safe_to_disconnect=true}` (or peer
         * FIN) after sending its own request. 1500 ms covers Samsung
         * One UI 8.0.5's observed drain time for an 87 KiB single-chunk
         * file (~14 ms socket-to-FIN on a clean network plus headroom);
         * larger payloads dominate over this timeout because the
         * receiver only acks after writing all pending payloads to
         * disk.
         */
        const val SAFE_DISCONNECT_ACK_TIMEOUT_MS: Long = 1500L
    }
}
