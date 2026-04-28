/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration tests for [OutboundConnection].
 *
 * Each test pairs the SUT against the production [InboundConnection]
 * (issue #16) over a real loopback `Socket` pair. The whole wire
 * protocol — TCP framing, UKEY2, SecureMessage, payload chunking,
 * sharing FSM — runs unmodified on both sides. This is the most
 * valuable interop check available before #28 lands real Quick Share
 * peer testing.
 *
 * Tests cover the four terminal outcomes:
 *
 *  - **Accept path**: peer receives every announced file in full and
 *    both sides reach Completed.
 *  - **Reject path**: peer rejects in the consent sheet, sender
 *    surfaces Rejected.
 *  - **Cancel path**: sender cancels mid-flight, both sides surface
 *    Cancelled.
 *  - **State flow**: the StateFlow emits the documented sequence
 *    (Connecting → Handshaking → AwaitingRemoteAcceptance → Sending
 *    → Completed) including a non-empty PIN.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OutboundConnectionTest {
    private lateinit var serverSocket: ServerSocket
    private val openedSockets: MutableList<Socket> = mutableListOf()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
        if (::serverSocket.isInitialized) {
            runCatching { serverSocket.close() }
        }
    }

    /**
     * In-memory file destination factory for the inbound side. Backs
     * each payload with a [SeekableByteChannel] so the assembler's
     * #44 out-of-order write path is exercised end-to-end.
     */
    private class InMemoryFactory : FileDestinationFactory {
        val output: MutableMap<Long, InMemorySeekable> = HashMap()

        override fun open(header: PayloadHeader): SeekableByteChannel {
            val ch = InMemorySeekable()
            output[header.id] = ch
            return ch
        }
    }

    /**
     * Lightweight in-memory [SeekableByteChannel] used by the test
     * factory. See [InboundConnectionTest.InMemorySeekable] for the
     * full contract; duplicated here to keep tests independent.
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

    /**
     * Bind a [ServerSocket] on the loopback address and accept one
     * connection. Returns the receiver-side `Socket` once
     * [OutboundConnection]'s internal [Socket.connect] completes.
     *
     * The `accept` lambda dispatches onto [Dispatchers.IO] because
     * `ServerSocket.accept()` is blocking — calling it on the
     * `runTest` scheduler would pin the only test thread.
     */
    private suspend fun listenAndAcceptInBackground(): Pair<Int, suspend () -> Socket> {
        serverSocket = withContext(Dispatchers.IO) { ServerSocket(0, 0, InetAddress.getLoopbackAddress()) }
        val accept: suspend () -> Socket = {
            val sock = withContext(Dispatchers.IO) { serverSocket.accept() }
            openedSockets += sock
            sock
        }
        return serverSocket.localPort to accept
    }

    /** Build a [FileSource] backed by an in-memory byte array. */
    private fun bytesSource(
        name: String,
        bytes: ByteArray,
        payloadId: Long,
        mimeType: String = "application/octet-stream",
    ): FileSource =
        FileSource(
            name = name,
            size = bytes.size.toLong(),
            mimeType = mimeType,
            lastModifiedTimestampMillis = 0L,
            payloadId = payloadId,
            open = { Channels.newChannel(ByteArrayInputStream(bytes)) as ReadableByteChannel },
        )

    @Test
    fun `accept path - file payload completes through full lifecycle`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-accept".toByteArray()),
                    )

                val fileBytes = ByteArray(8000) { (it and 0xFF).toByte() }
                val payloadId = 0x4242L
                val files = listOf(bytesSource("hello.bin", fileBytes, payloadId))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    // Accept on the receiver side and run the InboundConnection
                    // SUT against the same connection.
                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-accept".toByteArray()),
                        )

                    // Auto-accept the consent sheet as soon as it appears.
                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }

                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                    assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                }

                // File bytes round-tripped intact?
                val received = factory.output[payloadId]?.toByteArray()
                assertThat(received).isEqualTo(fileBytes)

                assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
            }
        }

    @Test
    fun `reject path - peer rejects and sender surfaces Rejected`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-reject".toByteArray()),
                    )

                val files = listOf(bytesSource("rejected.bin", ByteArray(100), 99L))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-reject".toByteArray()),
                        )

                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = false)
                    }

                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isInstanceOf(OutboundResult.Rejected::class.java)
                    assertThat(inboundResult).isEqualTo(InboundResult.Rejected)
                }

                assertThat(outbound.state.value).isInstanceOf(OutboundConnectionState.Rejected::class.java)
            }
        }

    @Test
    fun `state flow emits Connecting Handshaking AwaitingRemoteAcceptance Sending Completed`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-states".toByteArray()),
                    )

                val files = listOf(bytesSource("a.bin", ByteArray(2048), 7L))
                val seenStates: MutableList<OutboundConnectionState> = mutableListOf()

                coroutineScope {
                    val collector =
                        launch {
                            outbound.state.collect { s ->
                                seenStates += s
                                if (s.isStateTerminal()) return@collect
                            }
                        }

                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-states".toByteArray()),
                        )

                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }

                    inbound.run(factory)
                    val result = outboundJob.await()
                    assertThat(result).isEqualTo(OutboundResult.Completed)
                    collector.cancel()
                }

                // Verify each landmark appeared.
                assertThat(seenStates.any { it is OutboundConnectionState.Idle }).isTrue()
                assertThat(seenStates.any { it is OutboundConnectionState.Connecting }).isTrue()
                assertThat(seenStates.any { it is OutboundConnectionState.Handshaking }).isTrue()
                assertThat(seenStates.any { it is OutboundConnectionState.AwaitingRemoteAcceptance }).isTrue()
                assertThat(seenStates.any { it is OutboundConnectionState.Sending }).isTrue()
                assertThat(seenStates.any { it is OutboundConnectionState.Completed }).isTrue()

                // The PIN surfaced in AwaitingRemoteAcceptance must be 4 digits.
                val pinState =
                    seenStates.first { it is OutboundConnectionState.AwaitingRemoteAcceptance }
                        as OutboundConnectionState.AwaitingRemoteAcceptance
                assertThat(pinState.pin.length).isEqualTo(TransferMetadata.PIN_LENGTH)
                // PIN should be all ASCII digits.
                assertThat(pinState.pin.all { it.isDigit() }).isTrue()
            }
        }

    @Test
    fun `pin parity - sender PIN equals receiver PIN`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-pin".toByteArray()),
                    )

                val files = listOf(bytesSource("p.bin", ByteArray(64), 11L))
                var senderPin: String? = null
                var receiverPin: String? = null

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-pin".toByteArray()),
                        )

                    launch {
                        val s =
                            outbound.state.first { it is OutboundConnectionState.AwaitingRemoteAcceptance }
                                as OutboundConnectionState.AwaitingRemoteAcceptance
                        senderPin = s.pin
                    }
                    launch {
                        val s =
                            inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                                as InboundConnectionState.WaitingForUserConsent
                        receiverPin = s.metadata.pin
                        inbound.submitUserConsent(accepted = true)
                    }

                    inbound.run(factory)
                    outboundJob.await()
                }

                assertThat(senderPin).isNotNull()
                assertThat(receiverPin).isNotNull()
                assertThat(senderPin).isEqualTo(receiverPin)
            }
        }

    @Test
    fun `cancel from user during AwaitingRemoteAcceptance terminates LOCAL`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-cancel".toByteArray()),
                    )

                val files = listOf(bytesSource("c.bin", ByteArray(1024), 55L))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-cancel".toByteArray()),
                        )

                    // Cancel the sender as soon as we see the PIN-display
                    // state — exercises the cancel-during-negotiation path.
                    launch {
                        outbound.state.first { it is OutboundConnectionState.AwaitingRemoteAcceptance }
                        outbound.cancel()
                    }

                    // The receiver will see the CANCEL frame and terminate.
                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isInstanceOf(OutboundResult.Cancelled::class.java)
                    assertThat((outboundResult as OutboundResult.Cancelled).cause).isEqualTo(CancelCause.LOCAL)
                    // The inbound side is expected to observe the cancellation
                    // and surface either Cancelled or Rejected depending on
                    // whether it had reached WaitingForUserConsent yet.
                    assertThat(inboundResult).isAnyOf(
                        InboundResult.Cancelled(CancelCause.PEER),
                        InboundResult.Rejected,
                    )
                }
            }
        }

    @Test
    fun `multi-file accept - two files round-trip in announcement order`() =
        runBlocking {
            withTimeout(LONG_WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-multi".toByteArray()),
                    )

                val a = ByteArray(4096) { (it and 0xFF).toByte() }
                val b = ByteArray(2048) { ((it + 7) and 0xFF).toByte() }
                val files =
                    listOf(
                        bytesSource("a.bin", a, 100L),
                        bytesSource("b.bin", b, 200L),
                    )

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-multi".toByteArray()),
                        )

                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }

                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                    assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                }

                assertThat(factory.output[100L]?.toByteArray()).isEqualTo(a)
                assertThat(factory.output[200L]?.toByteArray()).isEqualTo(b)
            }
        }

    @Test
    fun `large file accept - chunking across multiple 512 KiB chunks works`() =
        runBlocking {
            withTimeout(LONG_WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-large".toByteArray()),
                    )

                // 1.5 MiB ⇒ three 512 KiB chunks.
                val payloadId = 333L
                val largeBytes = ByteArray(1_500_000) { ((it * 31) and 0xFF).toByte() }
                val files = listOf(bytesSource("big.bin", largeBytes, payloadId))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    val inbound =
                        InboundConnection(
                            socket = accept(),
                            secureRandom = SecureRandom("inbound-large".toByteArray()),
                        )

                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }

                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                    assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                }

                val received = factory.output[payloadId]?.toByteArray()
                assertThat(received?.size).isEqualTo(largeBytes.size)
                assertThat(received).isEqualTo(largeBytes)
            }
        }

    @Test
    fun `connect fail - invalid port surfaces Failed`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                // Listen on an ephemeral port and immediately close so we
                // can connect to a port nothing is listening on. We avoid
                // hardcoding "port 1" because some platforms refuse it
                // outright with a different error path.
                val srv = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
                val deadPort = srv.localPort
                srv.close()

                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = deadPort,
                        connectTimeoutMillis = 500,
                        secureRandom = SecureRandom("outbound-fail".toByteArray()),
                    )

                val result = outbound.run(emptyList())
                assertThat(result).isInstanceOf(OutboundResult.Failed::class.java)
                assertThat(outbound.state.value).isInstanceOf(OutboundConnectionState.Failed::class.java)
            }
        }

    @Test
    fun `double run throws IllegalStateException`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                // Bind a server, capture its port, then close it so the
                // port is guaranteed to refuse connections. Using a port
                // from a still-listening server is unreliable: the kernel
                // accepts the TCP handshake into the backlog and connect
                // succeeds even when no one calls accept(), causing the
                // first outbound.run() to hang on the protocol exchange
                // instead of failing fast.
                val deadPort =
                    withContext(Dispatchers.IO) {
                        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
                    }
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = deadPort,
                        connectTimeoutMillis = 500,
                        secureRandom = SecureRandom("outbound-double".toByteArray()),
                    )

                // First run fails on connect (port is now closed) but
                // still consumes the started flag.
                val firstResult = outbound.run(emptyList())
                assertThat(firstResult).isInstanceOf(OutboundResult.Failed::class.java)

                try {
                    outbound.run(emptyList())
                    throw AssertionError("expected IllegalStateException")
                } catch (e: IllegalStateException) {
                    assertThat(e.message).contains("only be invoked once")
                }
            }
        }

    /**
     * Whether [OutboundConnectionState] is one of the four terminal
     * variants. Hoisted to a helper so test predicates do not trip
     * detekt's [ComplexCondition] threshold.
     */
    private fun OutboundConnectionState.isStateTerminal(): Boolean =
        when (this) {
            OutboundConnectionState.Completed,
            is OutboundConnectionState.Cancelled,
            is OutboundConnectionState.Failed,
            is OutboundConnectionState.Rejected,
            -> true
            else -> false
        }

    private companion object {
        // Hard wall-clock cap for individual short tests. Real Socket I/O is
        // blocking, so we use [withTimeout] (not runTest's virtual scheduler)
        // to bound each scenario. 30 s leaves plenty of slack on slow CI.
        private const val WALLCLOCK_TIMEOUT_MS: Long = 30_000L

        // Long-running tests (multi-file, multi-MiB) get a generous cap; the
        // 1.5 MiB scenario is still expected to complete in a couple seconds
        // on a modern dev machine.
        private const val LONG_WALLCLOCK_TIMEOUT_MS: Long = 60_000L
    }
}
