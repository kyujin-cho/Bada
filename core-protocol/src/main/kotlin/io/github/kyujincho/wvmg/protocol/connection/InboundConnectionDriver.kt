/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import io.github.kyujincho.wvmg.protocol.payload.PayloadAssembler
import io.github.kyujincho.wvmg.protocol.payload.PayloadEvent
import io.github.kyujincho.wvmg.protocol.payload.PayloadTransferEncoder
import io.github.kyujincho.wvmg.protocol.sharing.InboundSharingFsm
import io.github.kyujincho.wvmg.protocol.sharing.InboundSharingState
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrames
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEffect
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEvent
import io.github.kyujincho.wvmg.protocol.transport.EndOfFrameStream
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import io.github.kyujincho.wvmg.protocol.ukey2.Ukey2Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.net.Socket
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
)
internal class InboundConnectionDriver(
    private val socket: Socket,
    private val secureRandom: SecureRandom,
    private val externalEvents: Channel<ExternalEvent>,
    private val mutableState: MutableStateFlow<InboundConnectionState>,
    private val factory: FileDestinationFactory,
) {
    private var framedConnection: FramedConnection? = null
    private var secureChannel: SecureChannel? = null
    private var fsm: InboundSharingFsm? = null
    private val assembler: PayloadAssembler = PayloadAssembler(fileDestinationFactory = factory)

    /** Set once the introduction has been observed; null before that. */
    private var transferMetadata: TransferMetadata? = null

    /** Map from announced `payload_id` to its expected payload type. */
    private val announced: MutableMap<Long, PayloadHeader.PayloadType> = HashMap()

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
     * Drive the entire receiver-side lifecycle. Returns the terminal
     * [InboundResult]; throws on coroutine cancellation (handled by
     * the caller).
     */
    suspend fun runLifecycle(): InboundResult {
        mutableState.value = InboundConnectionState.Handshaking
        val transport = FramedConnection(socket).also { framedConnection = it }

        // Step 1: read the unencrypted ConnectionRequest from the peer.
        val initialFrame = readOfflineFrameUnencrypted(transport)
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

        // Step 2: UKEY2 server handshake.
        val handshake = Ukey2Server.performHandshake(transport, secureRandom)

        // Step 3: send unencrypted ConnectionResponse{ACCEPT}.
        transport.sendFrame(OfflineFrames.connectionResponse().toByteArray())

        // Step 4: read peer's unencrypted ConnectionResponse.
        val peerResponse = readOfflineFrameUnencrypted(transport)
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
        val channel = SecureChannel(transport, sessionKeys, secureRandom).also { secureChannel = it }

        // Step 7-9: drive the negotiation FSM through to consent.
        mutableState.value = InboundConnectionState.Negotiating
        val negotiationFsm = InboundSharingFsm(secureRandom = secureRandom).also { fsm = it }
        applyEffects(channel, negotiationFsm.start())

        return runReceiveLoop(channel, negotiationFsm)
    }

    /**
     * Tear down all owned resources. Safe to call multiple times; each
     * closeable swallows its own IOException so a single failing close
     * cannot leak the others. Closes in dependency order (SecureChannel,
     * FramedConnection / socket, then assembler).
     */
    fun tearDown() {
        runCatching { secureChannel?.close() }
        runCatching { framedConnection?.close() }
        runCatching { socket.close() }
        runCatching { assembler.reset() }
    }

    /**
     * Main receive loop. Spawns an inbound pump coroutine, then
     * interleaves wire-frame and external-event delivery via [select].
     */
    private suspend fun runReceiveLoop(
        channel: SecureChannel,
        fsm: InboundSharingFsm,
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
            try {
                dispatchLoop(channel, fsm, wireChannel)
            } finally {
                pumpJob.cancelAndJoin()
            }
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
    ): InboundResult {
        while (true) {
            if (fsm.state == InboundSharingState.Disconnected) {
                return terminalResultFromState()
            }

            val event: DriverEvent =
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
            return if (received.size == announced.size && announced.isNotEmpty()) {
                finalizeCompleted(channel)
            } else {
                publishCancelled(CancelCause.PEER)
                InboundResult.Cancelled(CancelCause.PEER)
            }
        }

        if (!frame.hasV1() || frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) {
            // KEEP_ALIVE and other non-PAYLOAD_TRANSFER frames are
            // ignored: NearDrop does the same. We do not emit ACKs
            // because Quick Share peers we have observed do not
            // require them.
            return null
        }
        val payloadEvent = assembler.onPayloadTransfer(frame.v1.payloadTransfer)
        return handlePayloadEvent(channel, fsm, payloadEvent)
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
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharingFrame))
        applyEffects(channel, effects)
        return null
    }

    /** FILE payloads are always announced. */
    private suspend fun handleFileComplete(event: PayloadEvent.FileComplete): InboundResult? {
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
        mutableState.value =
            InboundConnectionState.Receiving(
                bytesReceived = bytesReceived + itemBytes,
                totalSize = totalSize,
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
            val items = received.toList()
            mutableState.value = InboundConnectionState.Completed(items)
            InboundResult.Completed(items)
        } else {
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
        mutableState.value =
            InboundConnectionState.Receiving(
                bytesReceived = 0L,
                totalSize = totalSize,
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

    /**
     * Read one length-prefixed [OfflineFrame] from the unencrypted
     * transport and parse it. Used pre-UKEY2 (ConnectionRequest /
     * ConnectionResponse).
     */
    private suspend fun readOfflineFrameUnencrypted(transport: FramedConnection): OfflineFrame {
        val bytes = transport.receiveFrame()
        return OfflineFrame.parseFrom(bytes)
    }
}
