/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import dev.bluehouse.bada.protocol.crypto.D2DKeyDerivation
import dev.bluehouse.bada.protocol.crypto.D2DRole
import dev.bluehouse.bada.protocol.crypto.pin.PinDerivation
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumLadder
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.payload.FileDestinationFactory
import dev.bluehouse.bada.protocol.payload.PayloadAssembler
import dev.bluehouse.bada.protocol.payload.PayloadEvent
import dev.bluehouse.bada.protocol.payload.PayloadTransferEncoder
import dev.bluehouse.bada.protocol.sharing.IntroductionFrame
import dev.bluehouse.bada.protocol.sharing.OutboundSharingFsm
import dev.bluehouse.bada.protocol.sharing.SharingFrames
import dev.bluehouse.bada.protocol.sharing.SharingFsmEffect
import dev.bluehouse.bada.protocol.sharing.SharingFsmEvent
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.ukey2.Ukey2Client
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
@Suppress("LargeClass")
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

    private fun customEndpointInfo(name: String): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { index -> (index + 1).toByte() },
            deviceName = name,
        )

    /** Opens a loopback `Socket` pair: returns (sender-side, receiver-side). */
    private fun connectedSocketPair(): Pair<Socket, Socket> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    /**
     * In-memory file destination factory. Captures bytes per payload
     * via a [SeekableByteChannel] so the assembler's #44 out-of-order
     * write path is exercised end-to-end.
     */
    private class InMemoryFactory : FileDestinationFactory {
        val output: MutableMap<Long, InMemorySeekable> = HashMap()

        /** `PayloadHeader.file_name` passed to [open], keyed by payload id. */
        val openedFileNames: MutableMap<Long, String> = HashMap()

        /** Payload ids the orchestrator called [commit] on. */
        val committed: MutableList<Long> = mutableListOf()

        /** Payload ids the orchestrator called [abort] on. */
        val aborted: MutableList<Long> = mutableListOf()

        /** Number of times [abortAll] was invoked. */
        var abortAllCalls: Int = 0

        /** Snapshot of the in-flight payload ids; cleared on commit/abort. */
        private val inFlight: MutableSet<Long> = mutableSetOf()

        override fun open(
            header: com.google.location.nearby.connections.proto.OfflineWireFormatsProto
                .PayloadTransferFrame.PayloadHeader,
        ): SeekableByteChannel {
            val ch = InMemorySeekable()
            output[header.id] = ch
            openedFileNames[header.id] = header.fileName
            inFlight += header.id
            return ch
        }

        override fun commit(payloadId: Long): Boolean {
            committed += payloadId
            return inFlight.remove(payloadId)
        }

        override fun abort(payloadId: Long): Boolean {
            aborted += payloadId
            return inFlight.remove(payloadId)
        }

        override fun abortAll(): Int {
            abortAllCalls += 1
            val count = inFlight.size
            for (id in inFlight.toList()) {
                aborted += id
            }
            inFlight.clear()
            return count
        }
    }

    /**
     * Lightweight in-memory [SeekableByteChannel] used by the test
     * factory. Backs a `ByteArray` sized to the largest write seen so
     * far; supports `position()` + write so chunks can land at any
     * offset.
     */
    private class InMemorySeekable : SeekableByteChannel {
        private var buffer: ByteArray = ByteArray(0)
        private var size: Long = 0
        private var pos: Long = 0
        private var open: Boolean = true

        fun toByteArray(): ByteArray = buffer.copyOf(size.toInt())

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        override fun read(dst: ByteBuffer): Int = throw UnsupportedOperationException()

        override fun write(src: ByteBuffer): Int {
            val n = src.remaining()
            if (n == 0) return 0
            ensureCapacity(pos + n)
            src.get(buffer, pos.toInt(), n)
            pos += n
            if (pos > size) size = pos
            return n
        }

        override fun position(): Long = pos

        override fun position(newPosition: Long): SeekableByteChannel {
            pos = newPosition
            return this
        }

        override fun size(): Long = size

        override fun truncate(newSize: Long): SeekableByteChannel {
            if (newSize < size) size = newSize
            if (pos > size) pos = size
            return this
        }

        private fun ensureCapacity(min: Long) {
            if (min <= buffer.size) return
            var newCap = buffer.size.coerceAtLeast(16)
            while (newCap < min) newCap = (newCap + (newCap shr 1)).coerceAtLeast(min.toInt())
            buffer = buffer.copyOf(newCap)
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
                // A sender that fills PayloadHeader.file_name keeps it,
                // even though the introduction announced "hello.bin"
                // (#325: the introduction name is only a fallback).
                assertThat(file.header.fileName).isEqualTo("file-$filePayloadId")
            }

            // Verify the file bytes round-tripped intact.
            val received = factory.output[filePayloadId]?.toByteArray()
            assertThat(received).isEqualTo(fileBytes)
            assertThat(factory.openedFileNames[filePayloadId]).isEqualTo("file-$filePayloadId")

            // Verify the terminal state propagated to the StateFlow.
            assertThat(inbound.state.value).isInstanceOf(InboundConnectionState.Completed::class.java)
        }

    @Test
    fun `ChromeOS-shaped file payloads with empty header file_name are named from the introduction`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            // ChromeOS Nearby Share sends FILE payloads with
            // PayloadHeader.file_name = "" and announces the real names
            // only in the introduction (#325).
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val inbound = InboundConnection(serverSocket, secureRandom = SecureRandom("inbound-chromeos".toByteArray()))

            val photoId = 0x5151L
            val notesId = 0x5252L
            val photoBytes = ByteArray(9000) { (it * 7 and 0xFF).toByte() }
            val notesBytes = "chromeos notes".toByteArray()
            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("Screenshot 2026-11-01 19.20.33.png")
                            .setPayloadId(photoId)
                            .setSize(photoBytes.size.toLong())
                            .setMimeType("image/png")
                            .build(),
                    ).addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("notes.txt")
                            .setPayloadId(notesId)
                            .setSize(notesBytes.size.toLong())
                            .setMimeType("text/plain")
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }
                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.submitUserConsent(accepted = true)
                }
                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(photoId to photoBytes, notesId to notesBytes),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-chromeos".toByteArray()),
                    options = SenderOptions(fileHeaderName = { "" }),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                val namesById =
                    (result as InboundResult.Completed)
                        .items
                        .filterIsInstance<ReceivedItem.File>()
                        .associate { it.payloadId to it.header.fileName }
                // ReceivedItem.File.header.fileName is what the
                // completion sheet's image-preview lookup reads.
                assertThat(namesById).containsExactly(
                    photoId,
                    "Screenshot 2026-11-01 19.20.33.png",
                    notesId,
                    "notes.txt",
                )
            }

            // The destination factory (MediaStore in production) is
            // opened with the announced name, so the saved file keeps
            // its extension.
            assertThat(factory.openedFileNames).containsExactly(
                photoId,
                "Screenshot 2026-11-01 19.20.33.png",
                notesId,
                "notes.txt",
            )
            assertThat(factory.output[photoId]?.toByteArray()).isEqualTo(photoBytes)
            assertThat(factory.output[notesId]?.toByteArray()).isEqualTo(notesBytes)
            assertThat(factory.committed).containsExactly(photoId, notesId)
        }

    @Test
    fun `NearDrop-shaped file payload without a header file_name is named from the introduction`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            // NearDrop (macOS) never sets PayloadHeader.file_name and leaves
            // FileMetadata.id at 0; the name is only in the introduction
            // (#324, from the reporter's inbound log).
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val inbound = InboundConnection(serverSocket, secureRandom = SecureRandom("inbound-neardrop".toByteArray()))

            val archiveId = 0x6161L
            val archiveBytes = ByteArray(12_000) { (it * 13 and 0xFF).toByte() }
            // The synthetic sender below must leave the field unset, not "".
            val sample =
                PayloadTransferEncoder
                    .encodeFilePayload(
                        payloadId = archiveId,
                        fileName = "",
                        totalSize = 0L,
                        source =
                            java.nio.channels.Channels
                                .newChannel(java.io.ByteArrayInputStream(ByteArray(0))),
                    ).first()
                    .withoutHeaderFileName()
            assertThat(
                sample.v1.payloadTransfer.payloadHeader
                    .hasFileName(),
            ).isFalse()
            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("file-06.zip")
                            .setPayloadId(archiveId)
                            .setSize(archiveBytes.size.toLong())
                            .setMimeType("application/zip")
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }
                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.submitUserConsent(accepted = true)
                }
                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(archiveId to archiveBytes),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-neardrop".toByteArray()),
                    options = SenderOptions(fileHeaderName = { null }),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                val file =
                    (result as InboundResult.Completed)
                        .items
                        .single() as ReceivedItem.File
                assertThat(file.header.fileName).isEqualTo("file-06.zip")
            }

            assertThat(factory.openedFileNames).containsExactly(archiveId, "file-06.zip")
            assertThat(factory.output[archiveId]?.toByteArray()).isEqualTo(archiveBytes)
            assertThat(factory.committed).containsExactly(archiveId)
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
    fun `same Wi-Fi inbound consent surfaces customized Bada sender device name`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-lan-custom-name".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)
            val senderName = "BadaSameWifi"
            val textBytes = "hello from custom Bada".toByteArray()
            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("clip")
                            .setPayloadId(0x5151L)
                            .setSize(textBytes.size.toLong())
                            .setType(Protocol.TextMetadata.Type.TEXT)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    val consentState =
                        inbound.state.first {
                            it is InboundConnectionState.WaitingForUserConsent
                        } as InboundConnectionState.WaitingForUserConsent
                    assertThat(consentState.metadata.sourceDeviceName).isEqualTo(senderName)
                    inbound.submitUserConsent(accepted = true)
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = emptyMap(),
                    texts = mapOf(0x5151L to textBytes),
                    secureRandom = SecureRandom("sender-lan-custom-name".toByteArray()),
                    endpointInfo = customEndpointInfo(senderName).serialize(),
                )

                assertThat(receiverJob.await()).isInstanceOf(InboundResult.Completed::class.java)
            }
        }

    @Test
    fun `successful file payload triggers factory commit and abortAll on teardown`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            // Asserts the integration of #25's commit() wiring on the
            // happy path: every announced FILE payload that arrives
            // cleanly gets a factory.commit() call from the orchestrator
            // before the StateFlow flips to Completed. The teardown
            // path also issues a (no-op) abortAll() so factories with
            // sticky in-flight tables get a guaranteed clean-up.
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-commit".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val payloadId = 0xFEED_BEEFL
            val fileBytes = ByteArray(1024) { (it and 0xFF).toByte() }
            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("commit.bin")
                            .setPayloadId(payloadId)
                            .setSize(fileBytes.size.toLong())
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.submitUserConsent(accepted = true)
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(payloadId to fileBytes),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-commit".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
            }

            // Commit must have fired exactly once for the announced
            // payload. The orchestrator records it BEFORE the
            // ReceivedItem is appended; a missing commit would mean
            // the user observes the file in their Downloads UI in a
            // PENDING state (Android API 29+).
            assertThat(factory.committed).containsExactly(payloadId)
            // No partial-file aborts on the happy path.
            assertThat(factory.aborted).isEmpty()
            // tearDown's abortAll fires unconditionally; in-flight set
            // is empty by now so the count is zero, but the call still
            // happened.
            assertThat(factory.abortAllCalls).isAtLeast(1)
        }

    @Test
    fun `mid-transfer user cancel calls factory abortAll to drop partial files`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            // The receiver-initiated cancel path is the primary
            // motivation for #25's commit/abort wiring: a partial file
            // that already has bytes on disk MUST be deleted on cancel.
            // We verify this contract end-to-end via the InMemoryFactory
            // counters: any payload that opened but did not commit must
            // appear in the aborted list (or be reaped by abortAll).
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-abort".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("partial.bin")
                            .setPayloadId(7L)
                            .setSize(1000L)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.cancel()
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(7L to ByteArray(1000)),
                    texts = emptyMap(),
                    secureRandom = SecureRandom("sender-abort".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Cancelled::class.java)
                assertThat((result as InboundResult.Cancelled).cause).isEqualTo(CancelCause.LOCAL)
            }

            // No payloads survived to be committed.
            assertThat(factory.committed).isEmpty()
            // The cancel happened in WaitingForUserConsent before any
            // FILE bytes arrived, so nothing was opened. abortAll
            // still must have fired during teardown; it is the only
            // safety net for the case where bytes did make it onto
            // disk.
            assertThat(factory.abortAllCalls).isAtLeast(1)
        }

    @Test
    fun `cancel before run does not throw and run reports failure`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            // Pre-handshake fast-path: a UI-side cancel before the
            // dispatch loop is up should close the underlying socket
            // directly and let run() unwind cleanly. We validate the
            // contract by calling cancel() BEFORE the synthetic sender
            // does anything; the receiver should never reach
            // WaitingForUserConsent.
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-pre-cancel".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            // Cancel before run is invoked. The cancel is latched and
            // the socket close happens immediately.
            inbound.cancel()

            val result = inbound.run(factory)
            // Closing the socket from under a blocking read produces
            // an I/O failure that the lifecycle maps to Failed (or, if
            // the close races with the lifecycle starting, possibly
            // Cancelled). Both are acceptable; the strict requirement
            // is that the call returns rather than hanging.
            val ok =
                result is InboundResult.Failed ||
                    result is InboundResult.Cancelled
            assertThat(ok).isTrue()
            // The synthetic sender never ran, so no partial state
            // should have been opened.
            assertThat(factory.output).isEmpty()
            assertThat(factory.committed).isEmpty()
            // Cleanup the loopback. The synthetic sender side was
            // never started; close the dangling client socket.
            runCatching { clientSocket.close() }
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
    // Mixed-introduction acceptance (issue #40)
    // -------------------------------------------------------------

    /**
     * Regression guard for issue #40: an introduction that mixes
     * `file_metadata` and multiple `text_metadata` entries (PLAIN +
     * URL) MUST be accepted end-to-end.
     *
     * NearDrop's reference implementation rejects anything that is not
     * a single file or a single text; the Quick Share wire protocol
     * itself does not impose that restriction, and stock senders
     * routinely pack a clipboard URL alongside a file. Pinning this
     * behavior in a loopback test prevents a future refactor from
     * silently re-introducing NearDrop's homogeneity check.
     */
    @Test
    @Suppress("LongMethod") // End-to-end loopback inherently sets up sender + receiver fixtures inline.
    fun `mixed introduction with file plus multiple text payloads completes`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-mixed".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val fileBytes = ByteArray(2048) { (it and 0xFF).toByte() }
            val filePayloadId = 0xCAFEL
            val urlBytes = "https://example.test/note".toByteArray()
            val urlPayloadId = 0xBEEFL
            val plainBytes = "clipboard contents".toByteArray()
            val plainPayloadId = 0xFACEL

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addFileMetadata(
                        Protocol.FileMetadata
                            .newBuilder()
                            .setName("attachment.bin")
                            .setPayloadId(filePayloadId)
                            .setSize(fileBytes.size.toLong())
                            .setMimeType("application/octet-stream")
                            .build(),
                    ).addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("link")
                            .setPayloadId(urlPayloadId)
                            .setSize(urlBytes.size.toLong())
                            .setType(Protocol.TextMetadata.Type.URL)
                            .build(),
                    ).addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("clip")
                            .setPayloadId(plainPayloadId)
                            .setSize(plainBytes.size.toLong())
                            .setType(Protocol.TextMetadata.Type.TEXT)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    val state =
                        inbound.state.first {
                            it is InboundConnectionState.WaitingForUserConsent
                        } as InboundConnectionState.WaitingForUserConsent
                    // The consent metadata MUST surface every announced
                    // attachment (one file + two texts) so the consent
                    // UI can render an honest summary.
                    assertThat(state.metadata.items).hasSize(3)
                    val texts = state.metadata.items.filterIsInstance<TransferItem.Text>()
                    assertThat(texts.map { it.kind })
                        .containsExactly(
                            TransferItem.Text.Kind.URL,
                            TransferItem.Text.Kind.PLAIN,
                        )
                    inbound.submitUserConsent(accepted = true)
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = mapOf(filePayloadId to fileBytes),
                    texts =
                        mapOf(
                            urlPayloadId to urlBytes,
                            plainPayloadId to plainBytes,
                        ),
                    secureRandom = SecureRandom("sender-mixed".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                val items = (result as InboundResult.Completed).items

                // Every announced item arrived, exactly once each.
                assertThat(items).hasSize(3)
                val byId = items.associateBy { it.payloadId }
                val file = byId[filePayloadId] as ReceivedItem.File
                assertThat(file.bytesWritten).isEqualTo(fileBytes.size.toLong())
                val url = byId[urlPayloadId] as ReceivedItem.Text
                assertThat(url.data).isEqualTo(urlBytes)
                val plain = byId[plainPayloadId] as ReceivedItem.Text
                assertThat(plain.data).isEqualTo(plainBytes)
            }

            // File bytes round-tripped through the in-memory factory.
            val received = factory.output[filePayloadId]?.toByteArray()
            assertThat(received).isEqualTo(fileBytes)
            // Only FILE payloads are committed; the two text payloads
            // are buffered in heap and never touch the destination
            // factory.
            assertThat(factory.committed).containsExactly(filePayloadId)
        }

    /**
     * Regression guard for issue #40: an introduction with multiple
     * `text_metadata` entries and zero files (e.g. clipboard text +
     * a URL) is also accepted end-to-end. The text-only path is the
     * common case for "Send to nearby" from a browser address bar
     * paired with the page title.
     */
    @Test
    fun `multiple text payloads without any files complete`() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val (clientSocket, serverSocket) = connectedSocketPair()
            val factory = InMemoryFactory()
            val rand = SecureRandom("inbound-multitext".toByteArray())
            val inbound = InboundConnection(serverSocket, secureRandom = rand)

            val urlBytes = "https://example.test/page".toByteArray()
            val urlPayloadId = 11L
            val phoneBytes = "+15551234567".toByteArray()
            val phonePayloadId = 12L

            val introduction =
                IntroductionFrame
                    .newBuilder()
                    .addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("page")
                            .setPayloadId(urlPayloadId)
                            .setSize(urlBytes.size.toLong())
                            .setType(Protocol.TextMetadata.Type.URL)
                            .build(),
                    ).addTextMetadata(
                        Protocol.TextMetadata
                            .newBuilder()
                            .setTextTitle("contact")
                            .setPayloadId(phonePayloadId)
                            .setSize(phoneBytes.size.toLong())
                            .setType(Protocol.TextMetadata.Type.PHONE_NUMBER)
                            .build(),
                    ).build()

            coroutineScope {
                val receiverJob = async { inbound.run(factory) }

                launch {
                    inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                    inbound.submitUserConsent(accepted = true)
                }

                runSyntheticSender(
                    socket = clientSocket,
                    introduction = introduction,
                    files = emptyMap(),
                    texts =
                        mapOf(
                            urlPayloadId to urlBytes,
                            phonePayloadId to phoneBytes,
                        ),
                    secureRandom = SecureRandom("sender-multitext".toByteArray()),
                )

                val result = receiverJob.await()
                assertThat(result).isInstanceOf(InboundResult.Completed::class.java)
                val items = (result as InboundResult.Completed).items
                assertThat(items).hasSize(2)
                val byId = items.associateBy { it.payloadId }
                assertThat((byId[urlPayloadId] as ReceivedItem.Text).data).isEqualTo(urlBytes)
                assertThat((byId[phonePayloadId] as ReceivedItem.Text).data).isEqualTo(phoneBytes)
            }

            // No files announced, so commit/abort never fire on the factory.
            assertThat(factory.committed).isEmpty()
        }

    // -------------------------------------------------------------
    // Synthetic sender harness
    // -------------------------------------------------------------

    // ------------------------------------------------------------------
    // #288: an offered bandwidth upgrade must never stall the LAN
    // negotiation. A stock GMS sender that ignores (or fails) the offer
    // keeps negotiating on the prior channel; the receiver has to keep
    // servicing that channel instead of parking on the provider's accept.

    @Test
    fun `pending upgrade offer does not stall negotiation when the peer ignores it`() =
        runBlocking {
            withTimeout(PENDING_UPGRADE_WALLCLOCK_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val factory = InMemoryFactory()
                val provider = HangingServerProvider()
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val inbound =
                    InboundConnection(
                        serverSocket,
                        secureRandom = SecureRandom("inbound-288-ignored".toByteArray()),
                        mediumRegistry = upgradeRegistry(provider),
                        logger = logs::add,
                    )
                val fileBytes = ByteArray(4000) { (it * 7 and 0xFF).toByte() }
                val filePayloadId = 0x2881L
                val options =
                    SenderOptions(
                        advertisedMediums =
                            listOf(ConnectionRequestFrame.Medium.WIFI_LAN, ConnectionRequestFrame.Medium.WIFI_DIRECT),
                    )

                coroutineScope {
                    val receiverJob = async { inbound.run(factory) }
                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }
                    runSyntheticSender(
                        socket = clientSocket,
                        introduction = fileIntroduction(filePayloadId, fileBytes.size),
                        files = mapOf(filePayloadId to fileBytes),
                        texts = emptyMap(),
                        secureRandom = SecureRandom("sender-288-ignored".toByteArray()),
                        options = options,
                    )
                    assertThat(receiverJob.await()).isInstanceOf(InboundResult.Completed::class.java)
                }

                assertThat(factory.output[filePayloadId]?.toByteArray()).isEqualTo(fileBytes)
                assertThat(inbound.activeMedium.value).isEqualTo(Medium.WIFI_LAN)
                assertThat(options.upgradeEvents)
                    .contains(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
                assertThat(logs).contains("medium-upgrade: server offering WIFI_DIRECT")
                // The session ended with the offer still open: the listener
                // must have been released rather than left parked forever.
                assertThat(provider.cancelCalls.get()).isEqualTo(1)
            }
        }

    @Test
    fun `peer UPGRADE_FAILURE settles the pending offer and negotiation continues on LAN`() =
        runBlocking {
            withTimeout(PENDING_UPGRADE_WALLCLOCK_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val factory = InMemoryFactory()
                val provider = HangingServerProvider()
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val inbound =
                    InboundConnection(
                        serverSocket,
                        secureRandom = SecureRandom("inbound-288-declined".toByteArray()),
                        mediumRegistry = upgradeRegistry(provider),
                        logger = logs::add,
                    )
                val fileBytes = ByteArray(2500) { (it * 11 and 0xFF).toByte() }
                val filePayloadId = 0x2882L
                val options =
                    SenderOptions(
                        advertisedMediums =
                            listOf(ConnectionRequestFrame.Medium.WIFI_LAN, ConnectionRequestFrame.Medium.WIFI_DIRECT),
                        declineUpgradeOffer = true,
                    )

                coroutineScope {
                    val receiverJob = async { inbound.run(factory) }
                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }
                    runSyntheticSender(
                        socket = clientSocket,
                        introduction = fileIntroduction(filePayloadId, fileBytes.size),
                        files = mapOf(filePayloadId to fileBytes),
                        texts = emptyMap(),
                        secureRandom = SecureRandom("sender-288-declined".toByteArray()),
                        options = options,
                    )
                    assertThat(receiverJob.await()).isInstanceOf(InboundResult.Completed::class.java)
                }

                assertThat(factory.output[filePayloadId]?.toByteArray()).isEqualTo(fileBytes)
                assertThat(inbound.activeMedium.value).isEqualTo(Medium.WIFI_LAN)
                assertThat(logs)
                    .contains("medium-upgrade: peer reported UPGRADE_FAILURE for WIFI_DIRECT; continuing on WIFI_LAN")
                assertThat(provider.cancelCalls.get()).isEqualTo(1)
            }
        }

    @Test
    fun `pending upgrade offer times out and reports UPGRADE_FAILURE without stalling negotiation`() =
        runBlocking {
            withTimeout(PENDING_UPGRADE_WALLCLOCK_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val factory = InMemoryFactory()
                val provider = HangingServerProvider()
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val inbound =
                    InboundConnection(
                        socket = serverSocket,
                        secureRandom = SecureRandom("inbound-288-timeout".toByteArray()),
                        mediumRegistry = upgradeRegistry(provider),
                        logger = logs::add,
                        upgradeAcceptTimeoutMillis = SHORT_UPGRADE_ACCEPT_TIMEOUT_MS,
                    )
                val fileBytes = ByteArray(1800) { (it * 13 and 0xFF).toByte() }
                val filePayloadId = 0x2883L
                // The sender sits quiet past the receiver's accept window, so
                // the timeout path (not the peer's traffic) settles the offer.
                val options =
                    SenderOptions(
                        advertisedMediums =
                            listOf(ConnectionRequestFrame.Medium.WIFI_LAN, ConnectionRequestFrame.Medium.WIFI_DIRECT),
                        negotiationDelayMillis = SHORT_UPGRADE_ACCEPT_TIMEOUT_MS * 3,
                    )

                coroutineScope {
                    val receiverJob = async { inbound.run(factory) }
                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }
                    runSyntheticSender(
                        socket = clientSocket,
                        introduction = fileIntroduction(filePayloadId, fileBytes.size),
                        files = mapOf(filePayloadId to fileBytes),
                        texts = emptyMap(),
                        secureRandom = SecureRandom("sender-288-timeout".toByteArray()),
                        options = options,
                    )
                    assertThat(receiverJob.await()).isInstanceOf(InboundResult.Completed::class.java)
                }

                assertThat(factory.output[filePayloadId]?.toByteArray()).isEqualTo(fileBytes)
                assertThat(inbound.activeMedium.value).isEqualTo(Medium.WIFI_LAN)
                assertThat(logs)
                    .contains("medium-upgrade: server accept timed out/failed for WIFI_DIRECT; continuing on WIFI_LAN")
                assertThat(options.upgradeEvents)
                    .containsExactly(
                        BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE,
                        BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE,
                    ).inOrder()
                assertThat(provider.cancelCalls.get()).isEqualTo(1)
            }
        }

    @Test
    fun `receiver adopts a late upgrade transport mid-negotiation`() =
        runBlocking {
            withTimeout(PENDING_UPGRADE_WALLCLOCK_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val factory = InMemoryFactory()
                // The peer reaches the offered medium only after the receiver
                // has already opened the sharing negotiation on the LAN
                // channel; the swap must land mid-negotiation and the
                // transfer must finish on the upgraded medium.
                val upgradePair = LoopbackUpgradePair(acceptDelayMillis = LATE_UPGRADE_ACCEPT_DELAY_MS)
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val inbound =
                    InboundConnection(
                        serverSocket,
                        secureRandom = SecureRandom("inbound-288-late".toByteArray()),
                        mediumRegistry = upgradeRegistry(upgradePair.serverProvider),
                        logger = logs::add,
                    )
                val fileBytes = ByteArray(6000) { (it * 5 and 0xFF).toByte() }
                val filePayloadId = 0x2884L
                val options =
                    SenderOptions(
                        advertisedMediums =
                            listOf(ConnectionRequestFrame.Medium.WIFI_LAN, ConnectionRequestFrame.Medium.WIFI_DIRECT),
                        adoptUpgradeRegistry = upgradeRegistry(upgradePair.clientProvider),
                    )

                try {
                    coroutineScope {
                        val receiverJob = async { inbound.run(factory) }
                        launch {
                            inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                            inbound.submitUserConsent(accepted = true)
                        }
                        runSyntheticSender(
                            socket = clientSocket,
                            introduction = fileIntroduction(filePayloadId, fileBytes.size),
                            files = mapOf(filePayloadId to fileBytes),
                            texts = emptyMap(),
                            secureRandom = SecureRandom("sender-288-late".toByteArray()),
                            options = options,
                        )
                        assertThat(receiverJob.await()).isInstanceOf(InboundResult.Completed::class.java)
                    }

                    assertThat(factory.output[filePayloadId]?.toByteArray()).isEqualTo(fileBytes)
                    assertThat(inbound.activeMedium.value).isEqualTo(Medium.WIFI_DIRECT)
                    assertThat(logs).contains("medium-upgrade: server completed WIFI_DIRECT")
                    // The opening PAIRED_KEY_ENCRYPTION went out on the LAN
                    // channel while the offer was still pending — proof the
                    // negotiation did not wait for the swap.
                    val offerIndex = logs.indexOfFirst { it.startsWith("medium-upgrade: server offering") }
                    val firstPkeIndex = logs.indexOfFirst { it.startsWith("sharing: sending PAIRED_KEY_ENCRYPTION") }
                    val completedIndex = logs.indexOfFirst { it.startsWith("medium-upgrade: server completed") }
                    assertThat(offerIndex).isAtLeast(0)
                    assertThat(firstPkeIndex).isGreaterThan(offerIndex)
                    assertThat(firstPkeIndex).isLessThan(completedIndex)
                } finally {
                    upgradePair.close()
                }
            }
        }

    /**
     * Pre-connected loopback socket pair standing in for a Wi-Fi Direct
     * link: the server provider hands out its end (after an optional
     * delay), the client provider adopts the other.
     */
    private class LoopbackUpgradePair(
        private val acceptDelayMillis: Long,
    ) {
        private val credentials =
            UpgradePathCredentials.WifiDirect(
                ipAddress = byteArrayOf(127, 0, 0, 1),
                port = 1,
                ssid = "DIRECT-bada-288",
                passphrase = "12345678",
                frequency = 5_180,
            )
        private val clientSocket: Socket
        private val serverSocket: Socket

        init {
            val listener = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
            clientSocket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            serverSocket = listener.accept()
            listener.close()
        }

        val clientProvider: MediumProvider =
            object : MediumProvider {
                override val medium: Medium = Medium.WIFI_DIRECT

                override fun isSupported(): Boolean = true

                override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport =
                    UpgradedTransport.SocketBacked(Medium.WIFI_DIRECT, clientSocket)
            }

        val serverProvider: MediumProvider =
            object : MediumProvider {
                override val medium: Medium = Medium.WIFI_DIRECT

                override fun isSupported(): Boolean = true

                override suspend fun prepareUpgrade(): UpgradePathCredentials = credentials

                override suspend fun acceptUpgrade(): UpgradedTransport {
                    delay(acceptDelayMillis)
                    return UpgradedTransport.SocketBacked(Medium.WIFI_DIRECT, serverSocket)
                }
            }

        fun close() {
            runCatching { clientSocket.close() }
            runCatching { serverSocket.close() }
        }
    }

    /**
     * Server-side provider whose accept never completes on its own —
     * models a real Wi-Fi Direct listener parked in `accept()` while the
     * peer ignores the offer. Only cancellation ends it.
     */
    private class HangingServerProvider : MediumProvider {
        override val medium: Medium = Medium.WIFI_DIRECT
        val cancelCalls = AtomicInteger()

        override fun isSupported(): Boolean = true

        override suspend fun prepareUpgrade(): UpgradePathCredentials =
            UpgradePathCredentials.Generic(Medium.WIFI_DIRECT)

        override suspend fun acceptUpgrade(): UpgradedTransport? = awaitCancellation()

        override fun cancelPendingUpgrade() {
            cancelCalls.incrementAndGet()
        }
    }

    private fun upgradeRegistry(provider: MediumProvider): MediumRegistry =
        MediumRegistry(
            providers =
                listOf(
                    MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                    provider,
                ),
            ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN)),
        )

    private fun fileIntroduction(
        payloadId: Long,
        size: Int,
    ): IntroductionFrame =
        IntroductionFrame
            .newBuilder()
            .addFileMetadata(
                Protocol.FileMetadata
                    .newBuilder()
                    .setName("file-$payloadId.bin")
                    .setPayloadId(payloadId)
                    .setSize(size.toLong())
                    .setMimeType("application/octet-stream")
                    .build(),
            ).build()

    /**
     * Knobs for [runSyntheticSender] that model how a real sender reacts
     * to the receiver's bandwidth-upgrade offer.
     */
    private class SenderOptions(
        /** `ConnectionRequestFrame.mediums` advertised to the receiver. */
        val advertisedMediums: List<ConnectionRequestFrame.Medium> = emptyList(),
        /** Delay before the first sharing frame goes out. */
        val negotiationDelayMillis: Long = 0L,
        /** Reply to `UPGRADE_PATH_AVAILABLE` with `UPGRADE_FAILURE`. */
        val declineUpgradeOffer: Boolean = false,
        /**
         * When set, take `UPGRADE_PATH_AVAILABLE` offers through this
         * registry's client provider instead of ignoring them.
         */
        val adoptUpgradeRegistry: MediumRegistry? = null,
        /**
         * `PayloadHeader.file_name` the sender puts on each FILE
         * payload's frames. ChromeOS sends `""` here (#325); `null`
         * leaves the field unset, as NearDrop does (#324).
         */
        val fileHeaderName: (Long) -> String? = { id -> "file-$id" },
    ) {
        /** Every bandwidth-upgrade event the sender saw, in arrival order. */
        val upgradeEvents: MutableList<BandwidthUpgradeNegotiationFrame.EventType> =
            Collections.synchronizedList(mutableListOf())
    }

    @Suppress("ReturnCount") // One early return per sender reaction keeps the three modes readable.
    private suspend fun observeUpgradeFrame(
        wire: SenderWire,
        frame: OfflineFrame,
        options: SenderOptions,
    ) {
        val negotiation = frame.v1.bandwidthUpgradeNegotiation
        options.upgradeEvents += negotiation.eventType
        if (negotiation.eventType != BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE) return
        if (options.declineUpgradeOffer) {
            val medium = Medium.fromUpgradePathMedium(negotiation.upgradePathInfo.medium) ?: Medium.WIFI_DIRECT
            wire.channel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(medium))
            return
        }
        val registry = options.adoptUpgradeRegistry ?: return
        // Take the offer the way a stock client does: connect to the
        // offered medium, run the raw introduction + prior-channel close,
        // then continue on the upgraded channel with whatever sharing
        // frames the receiver had already put on the old one.
        val upgraded =
            BandwidthUpgradeOrchestrator.runClientUpgradeFromOffer(
                oldChannel = wire.channel,
                currentMedium = Medium.WIFI_LAN,
                offer = frame,
                mediumRegistry = registry,
                endpointId = "ABCD",
                logger = {},
            )
        assertThat(upgraded.fallbackChannelUsable).isTrue()
        assertThat(upgraded.channel).isNotSameInstanceAs(wire.channel)
        wire.channel = upgraded.channel
        wire.buffered.addAll(upgraded.bufferedFrames)
    }

    private companion object {
        /**
         * Generous wall-clock bound for the pending-upgrade tests. A
         * regression that parks the receiver on the provider's accept
         * again would only surface after the 30 s production timeout, so
         * this bound is what turns the stall into a failure.
         */
        const val PENDING_UPGRADE_WALLCLOCK_MS = 10_000L
        const val SHORT_UPGRADE_ACCEPT_TIMEOUT_MS = 300L
        const val LATE_UPGRADE_ACCEPT_DELAY_MS = 400L
    }

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
        endpointInfo: ByteArray = ByteArray(0),
        options: SenderOptions = SenderOptions(),
    ) {
        val transport = FramedConnection(socket)
        val requestBuilder =
            ConnectionRequestFrame
                .newBuilder()
                .setEndpointId("ABCD")
                .setEndpointName("test-sender")
                .addAllMediums(options.advertisedMediums)
        if (endpointInfo.isNotEmpty()) {
            requestBuilder.setEndpointInfo(ByteString.copyFrom(endpointInfo))
        }

        // Step 1: send unencrypted ConnectionRequest.
        val connReq =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.CONNECTION_REQUEST)
                        .setConnectionRequest(requestBuilder.build())
                        .build(),
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
        val wire = SenderWire(channel)
        val fsm = OutboundSharingFsm(introduction = introduction, secureRandom = secureRandom)
        val assembler = PayloadAssembler()

        // Optional stall before the first sharing frame, so a test can
        // let a receiver-side upgrade window close before negotiating.
        if (options.negotiationDelayMillis > 0L) delay(options.negotiationDelayMillis)

        // Push the FSM's initial PKE.
        applySenderEffects(wire.channel, fsm.start(), secureRandom)

        // Drive the FSM through to SendingPayloads or a terminal state.
        // We treat the loop body as "process one frame" and use a flag
        // to decide whether to continue, rather than nested break/continue.
        var keepRunning = true
        while (keepRunning) {
            keepRunning =
                pumpOneSenderFrame(
                    wire = wire,
                    fsm = fsm,
                    assembler = assembler,
                    secureRandom = secureRandom,
                    files = files,
                    texts = texts,
                    options = options,
                )
        }

        runCatching { wire.channel.close() }
        runCatching { channel.close() }
        runCatching { transport.close() }
    }

    /**
     * The synthetic sender's current channel plus frames handed back by a
     * bandwidth upgrade that must be processed before reading the wire.
     */
    private class SenderWire(
        var channel: SecureChannel,
    ) {
        val buffered: ArrayDeque<OfflineFrame> = ArrayDeque()
    }

    /**
     * Process one inbound OfflineFrame on the synthetic sender side.
     * Returns `true` to continue the loop, `false` to terminate.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun pumpOneSenderFrame(
        wire: SenderWire,
        fsm: OutboundSharingFsm,
        assembler: PayloadAssembler,
        secureRandom: SecureRandom,
        files: Map<Long, ByteArray>,
        texts: Map<Long, ByteArray>,
        options: SenderOptions = SenderOptions(),
    ): Boolean {
        val frame = wire.buffered.removeFirstOrNull() ?: wire.channel.receiveOfflineFrame()
        if (frame.v1.type == V1Frame.FrameType.DISCONNECTION) return false
        if (frame.v1.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
            observeUpgradeFrame(wire, frame, options)
            return true
        }
        if (frame.v1.type != V1Frame.FrameType.PAYLOAD_TRANSFER) return true

        val event = assembler.onPayloadTransfer(frame.v1.payloadTransfer)
        if (event !is PayloadEvent.BytesComplete) return true

        val sharing = SharingFrames.parse(event.data)
        val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharing))
        applySenderEffects(wire.channel, effects, secureRandom)

        if (effects.any { it is SharingFsmEffect.Rejected }) return false
        if (sharing.v1.type == Protocol.V1Frame.FrameType.CANCEL) return false
        if (effects.any { it is SharingFsmEffect.ReadyToSendPayloads }) {
            // ACCEPT received: stream the file / text payloads.
            streamPayloads(wire.channel, files, texts, options.fileHeaderName)
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
        fileHeaderName: (Long) -> String?,
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
            val headerName = fileHeaderName(id)
            for (frame in PayloadTransferEncoder.encodeFilePayload(
                payloadId = id,
                fileName = headerName.orEmpty(),
                totalSize = data.size.toLong(),
                source = source,
            )) {
                channel.sendOfflineFrame(if (headerName == null) frame.withoutHeaderFileName() else frame)
            }
        }
    }
}

/**
 * This FILE frame with `PayloadHeader.file_name` unset rather than `""`
 * (proto2 tracks presence): the shape NearDrop puts on the wire (#324).
 */
private fun OfflineFrame.withoutHeaderFileName(): OfflineFrame {
    val transfer =
        v1.payloadTransfer
            .toBuilder()
            .setPayloadHeader(
                v1.payloadTransfer.payloadHeader
                    .toBuilder()
                    .clearFileName(),
            ).build()
    return toBuilder().setV1(v1.toBuilder().setPayloadTransfer(transfer)).build()
}
