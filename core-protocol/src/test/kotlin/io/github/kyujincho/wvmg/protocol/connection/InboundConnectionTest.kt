/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import io.github.kyujincho.wvmg.protocol.payload.PayloadAssembler
import io.github.kyujincho.wvmg.protocol.payload.PayloadEvent
import io.github.kyujincho.wvmg.protocol.payload.PayloadTransferEncoder
import io.github.kyujincho.wvmg.protocol.sharing.IntroductionFrame
import io.github.kyujincho.wvmg.protocol.sharing.OutboundSharingFsm
import io.github.kyujincho.wvmg.protocol.sharing.SharingFrames
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEffect
import io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEvent
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import io.github.kyujincho.wvmg.protocol.ukey2.Ukey2Client
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.security.SecureRandom

/**
 * End-to-end integration tests for [InboundConnection].
 *
 * Each test spins up a real loopback `Socket` pair and runs:
 *  - the SUT (`InboundConnection`) on the server side, and
 *  - a synthetic Quick Share *sender* on the client side (built from
 *    the same primitives the production code uses: [Ukey2Client],
 *    [SecureChannel], [OutboundSharingFsm], [PayloadTransferEncoder]).
 *
 * The synthetic sender drives a real wire-protocol exchange from
 * step 1 (unencrypted ConnectionRequest) through step N
 * (Disconnection), so the InboundConnection lifecycle is exercised
 * against actual TCP, real UKEY2, real SecureMessage, and a real
 * payload assembler. This is the architectural-level test the issue
 * calls for: the loopback proves the pieces tie together correctly.
 */
class InboundConnectionTest {
    private lateinit var serverSocket: ServerSocket
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
        if (::serverSocket.isInitialized) {
            runCatching { serverSocket.close() }
        }
    }

    /**
     * Whether [InboundConnectionState] is one of the four terminal
     * variants. Hoisted to a helper so test predicates do not trip
     * detekt's [ComplexCondition] threshold.
     */
    private fun InboundConnectionState.isStateTerminal(): Boolean =
        when (this) {
            is InboundConnectionState.Completed,
            is InboundConnectionState.Cancelled,
            is InboundConnectionState.Failed,
            -> true
            InboundConnectionState.Rejected -> true
            else -> false
        }

    /** Opens a loopback `Socket` pair: returns (sender-side, receiver-side). */
    private fun connectedSocketPair(): Pair<Socket, Socket> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    /** In-memory file destination factory. Captures bytes per payload. */
    private class InMemoryFactory : FileDestinationFactory {
        val output: MutableMap<Long, ByteArrayOutputStream> = HashMap()

        override fun open(
            header: com.google.location.nearby.connections.proto.OfflineWireFormatsProto
                .PayloadTransferFrame.PayloadHeader,
        ): WritableByteChannel {
            val buf = ByteArrayOutputStream()
            output[header.id] = buf
            return object : WritableByteChannel {
                private var open = true

                override fun write(src: ByteBuffer): Int {
                    val n = src.remaining()
                    val arr = ByteArray(n)
                    src.get(arr)
                    buf.write(arr)
                    return n
                }

                override fun close() {
                    open = false
                }

                override fun isOpen(): Boolean = open
            }
        }
    }

    @Test
    fun `accept path - file payload completes through full lifecycle`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-accept".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val fileBytes = ByteArray(8000) { (it and 0xFF).toByte() }
            val filePayloadId = 0x4242L
            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("hello.bin")
                            .setPayloadId(filePayloadId)
                            .setSize(fileBytes.size.toLong())
                            .setMimeType("application/octet-stream")
                            .build(),
                    ).build()

            coroutineScope {
                // Receiver side -- the SUT.
                val receiverJob = async { inbound.run(factory) }

                // Subscribe to state to wait for WaitingForUserConsent
                // before submitting consent. We do this in a separate
                // launch so the receiver's run() can keep advancing.
                launch {
                    inbound.state.first {
                        it is InboundConnectionState.WaitingForUserConsent
                    }
                    inbound.submitUserConsent(accepted = true)
                }

                // Sender side -- a synthetic peer doing the full handshake.
                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(filePayloadId to fileBytes),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-accept".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                val items = (result as InboundResult.Completed).items
                assertThat(items).hasSize(1)
                val file = items.single() as ReceivedItem.File
                assertThat(file.payloadId).isEqualTo(filePayloadId)
                assertThat(file.bytesWritten).isEqualTo(fileBytes.size.toLong())
            }

            // Verify the file bytes round-tripped intact.
            val received = factory.output[filePayloadId]?.toByteArray()
            assertThat(received).isEqualTo(fileBytes)

            // Verify the terminal state propagated to the StateFlow.
            assertThat(inbound.state.value).isInstanceOf(InboundConnectionState.Completed::class.java)
        }

    @Test
    fun `reject path - user reject sends RESPONSE REJECT and terminates`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-reject".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("rejected.bin")
                            .setPayloadId(99L)
                            .setSize(100L)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    inbound.state.first {
                        it is InboundConnectionState.WaitingForUserConsent
                    }
                    inbound.submitUserConsent(accepted = false)
                }

                // The sender will get REJECT in response and terminate;
                // we still need it to perform the handshake / introduction.
                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(99L to ByteArray(100)),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-reject".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isEqualTo(InboundResult.Rejected)
            }

            assertThat(inbound.state.value).isEqualTo(InboundConnectionState.Rejected)
        }

    @Test
    fun `state flow emits handshaking then negotiating then waitingForUserConsent`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-states".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val seenStates = mutableListOf<InboundConnectionState>()

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("hello")
                            .setPayloadId(7L)
                            .setSize(5L)
                            .setType(Protocol.TextMetadata.Type.TEXT)
                            .build(),
                    ).build()

            coroutineScope {
                val collector =
                    launch {
                        inbound.state.collect { s ->
                            seenStates += s
                            if (s.isStateTerminal()) {
                                // Terminal -- collector can stop.
                                return@collect
                            }
                        }
                    }

                val receiverJob = async { inbound.run(factory) }

                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.submitUserConsent(accepted = true)
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = emptyMap(),
                    texts = mapOf(7L to "hello".toByteArray()),
                    secureRandom = SecureRandom("sender-states".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                collector.cancel()
            }

            // Verify the lifecycle visited each phase. We don't assert
            // strict equality on the full sequence (timing depends on
            // dispatcher scheduling), just that each landmark appears.
            assertThat(seenStates.any { it is InboundConnectionState.Idle }).isTrue()
            assertThat(seenStates.any { it is InboundConnectionState.Handshaking }).isTrue()
            assertThat(seenStates.any { it is InboundConnectionState.Negotiating }).isTrue()
            assertThat(seenStates.any { it is InboundConnectionState.WaitingForUserConsent }).isTrue()
            assertThat(seenStates.any { it is InboundConnectionState.Receiving }).isTrue()
            assertThat(seenStates.any { it is InboundConnectionState.Completed }).isTrue()

            val consentState =
                seenStates.first { it is InboundConnectionState.WaitingForUserConsent }
                    as InboundConnectionState.WaitingForUserConsent
            assertThat(consentState.metadata.pin.length).isEqualTo(TransferMetadata.PIN_LENGTH)
            assertThat(consentState.metadata.items).hasSize(1)
        }

    @Test
    fun `cancel from user emits CancelFrame and terminates with LOCAL cause`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-cancel".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("cancel.bin")
                            .setPayloadId(55L)
                            .setSize(1000L)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                // Cancel as soon as we are in WaitingForUserConsent --
                // this exercises the cancel-from-consent-state path.
                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.cancel()
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(55L to ByteArray(1000)),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-cancel".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Cancelled::class.java)
                assertThat((result as InboundResult.Cancelled).cause).isEqualTo(CancelCause.LOCAL)
            }
        }

    // -------------------------------------------------------------
    // Synthetic sender harness
    // -------------------------------------------------------------

    /**
     * Drives the entire sender-side wire protocol against the given
     * [socket]. Mirrors what a real Quick Share sender does:
     *
     *  1. Send unencrypted ConnectionRequest.
     *  2. Run UKEY2 client handshake.
     *  3. Send unencrypted ConnectionResponse.
     *  4. Read receiver's unencrypted ConnectionResponse.
     *  5. Run OutboundSharingFsm + SecureChannel through introduction
     *     and (if accepted) payload streaming.
     *  6. Send Disconnection.
     */
    @Suppress("LongMethod", "LongParameterList", "ComplexMethod")
    private suspend fun runSyntheticSender(
        socket: Socket,
        introduction: IntroductionFrame,
        files: Map<Long, ByteArray>,
        texts: Map<Long, ByteArray>,
        secureRandom: SecureRandom,
    ) {
        val transport = FramedConnection(socket)

        // Step 1: send unencrypted ConnectionRequest.
        val connReq =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.CONNECTION_REQUEST)
                        .setConnectionRequest(
                            ConnectionRequestFrame
                                .newBuilder()
                                .setEndpointId("ABCD")
                                .setEndpointName("test-sender")
                                .build(),
                        ).build(),
                ).build()
        transport.sendFrame(connReq.toByteArray())

        // Step 2: UKEY2 client handshake.
        val handshake = Ukey2Client.performHandshake(transport, secureRandom)

        // Step 3: read receiver's unencrypted ConnectionResponse.
        val theirConnResp = OfflineFrame.parseFrom(transport.receiveFrame())
        assertThat(theirConnResp.v1.type).isEqualTo(V1Frame.FrameType.CONNECTION_RESPONSE)

        // Step 4: send our unencrypted ConnectionResponse.
        val ourConnResp =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                        .setConnectionResponse(
                            com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
                                .newBuilder()
                                .setResponse(
                                    com.google.location.nearby.connections.proto
                                        .OfflineWireFormatsProto.ConnectionResponseFrame.ResponseStatus.ACCEPT,
                                ).build(),
                        ).build(),
                ).build()
        transport.sendFrame(ourConnResp.toByteArray())

        // Step 5: derive D2DSessionKeys with role = CLIENT (we sent
        // UKEY2 ClientInit).
        val sessionKeys =
            D2DKeyDerivation.derive(
                dhs = handshake.dhs,
                ukeyClientInitMsg = handshake.clientInitMsg,
                ukeyServerInitMsg = handshake.serverInitMsg,
                role = D2DRole.CLIENT,
            )
        // Both sides should derive the same authString and therefore
        // the same PIN -- this is the property the InboundConnection
        // surfaces to the consent UI.
        val ourPin = PinDerivation.deriveFourDigitPin(sessionKeys.authString)
        assertThat(ourPin.length).isEqualTo(TransferMetadata.PIN_LENGTH)

        val channel = SecureChannel(transport, sessionKeys, secureRandom)
        val fsm = OutboundSharingFsm(introduction = introduction, secureRandom = secureRandom)
        val assembler = PayloadAssembler()

        // Push the FSM's initial PKE.
        applySenderEffects(channel, fsm.start(), secureRandom)

        // Drive the FSM through to SendingPayloads or a terminal state.
        // We treat the loop body as "process one frame" and use a flag
        // to decide whether to continue, rather than nested break/continue.
        var keepRunning = true
        while (keepRunning) {
            keepRunning =
                pumpOneSenderFrame(
                    channel = channel,
                    fsm = fsm,
                    assembler = assembler,
                    secureRandom = secureRandom,
                    files = files,
                    texts = texts,
                )
        }

        runCatching { channel.close() }
        runCatching { transport.close() }
    }

    /**
     * Process one inbound OfflineFrame on the synthetic sender side.
     * Returns `true` to continue the loop, `false` to terminate.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun pumpOneSenderFrame(
        channel: SecureChannel,
        fsm: OutboundSharingFsm,
        assembler: PayloadAssembler,
        secureRandom: SecureRandom,
        files: Map<Long, ByteArray>,
        texts: Map<Long, ByteArray>,
    ): Boolean {
        val frame = channel.receiveOfflineFrame()
        if (frame.v1.type == V1Frame.FrameType.DISCONNECTION) return false
        if (frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) return true

        val event = assembler.onPayloadTransfer(frame.v1.payloadTransfer)
        if (event !is PayloadEvent.BytesComplete) return true

        val sharing = SharingFrames.parse(event.data)
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharing))
        applySenderEffects(channel, effects, secureRandom)

        if (effects.any { it is SharingFsmEffect.Rejected }) return false
        if (sharing.v1.type == Protocol.V1Frame.FrameType.CANCEL) return false
        if (effects.any { it is SharingFsmEffect.ReadyToSendPayloads }) {
            // ACCEPT received: stream the file / text payloads.
            streamPayloads(channel, files, texts)
            // Receiver will send Disconnection back to us. Loop round
            // to consume it.
        }
        return true
    }

    private suspend fun applySenderEffects(
        channel: SecureChannel,
        effects: List<SharingFsmEffect>,
        secureRandom: SecureRandom,
    ) {
        for (e in effects) {
            if (e is SharingFsmEffect.SendFrame) {
                val payloadId = secureRandom.nextLong()
                val frames = PayloadTransferEncoder.encodeBytesPayload(payloadId, e.frame.toByteArray())
                for (frame in frames) channel.sendOfflineFrame(frame)
            }
        }
    }

    private suspend fun streamPayloads(
        channel: SecureChannel,
        files: Map<Long, ByteArray>,
        texts: Map<Long, ByteArray>,
    ) {
        for ((id, data) in texts) {
            for (frame in PayloadTransferEncoder.encodeBytesPayload(id, data)) {
                channel.sendOfflineFrame(frame)
            }
        }
        for ((id, data) in files) {
            // Wrap the bytes in a ReadableByteChannel.
            val source =
                java.nio.channels.Channels
                    .newChannel(java.io.ByteArrayInputStream(data))
            for (frame in PayloadTransferEncoder.encodeFilePayload(
                payloadId = id,
                fileName = "file-$id",
                totalSize = data.size.toLong(),
                source = source,
            )) {
                channel.sendOfflineFrame(frame)
            }
        }
    }
}
