/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.transport

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Behavioural tests for [FramedConnection].
 *
 * The acceptance criteria for issue #7 call out two scenarios that are
 * easy to get subtly wrong:
 *
 *  1. **No over-reads** when the underlying [java.io.InputStream]
 *     happens to deliver multiple frames in a single chunk. Quick Share
 *     pipelines small UKEY2 / SecureMessage frames, so a buggy framer
 *     that "reads up to N" rather than "reads exactly N" would corrupt
 *     the stream the moment two frames coalesce.
 *  2. **Graceful end-of-stream** when the peer closes between frames.
 *     NearDrop distinguishes `handleConnectionClosure()` (clean) from
 *     `protocolError()` (truncated mid-frame); we mirror that with
 *     [EndOfFrameStream] vs. [EOFException].
 *
 * Tests use a real loopback `Socket` pair to exercise the actual
 * `java.net.Socket` path the production code uses, rather than mocking
 * the streams.
 */
class FramedConnectionTest {
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
     * Opens a loopback `Socket` pair and returns `(client, server)`.
     * Both are tracked for teardown.
     */
    private fun connectedSocketPair(): Pair<Socket, Socket> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    @Test
    fun `sendFrame writes a 4-byte big-endian length prefix followed by the payload`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val sender = FramedConnection(client)

            val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
            sender.sendFrame(payload)

            // Read the raw bytes off the server side and inspect the framing.
            val raw = ByteArray(FramedConnection.LENGTH_PREFIX_BYTES + payload.size)
            readFully(server, raw)

            assertThat(raw[0]).isEqualTo(0x00.toByte())
            assertThat(raw[1]).isEqualTo(0x00.toByte())
            assertThat(raw[2]).isEqualTo(0x00.toByte())
            assertThat(raw[3]).isEqualTo(payload.size.toByte())
            assertThat(raw.copyOfRange(4, raw.size)).isEqualTo(payload)
        }

    @Test
    fun `receiveFrame round-trips a payload sent by sendFrame`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val sender = FramedConnection(client)
            val receiver = FramedConnection(server)

            val payload = ByteArray(1024) { (it and 0xFF).toByte() }
            coroutineScope {
                val received = async { receiver.receiveFrame() }
                sender.sendFrame(payload)
                assertThat(received.await()).isEqualTo(payload)
            }
        }

    @Test
    fun `receiveFrame handles concatenated frames without over-reading`() =
        runTest {
            // This is the headline acceptance criterion: when several frames
            // arrive coalesced into a single TCP segment (which is the
            // common case on loopback), the framer must hand them back one
            // at a time and never consume past the declared length.
            val (client, server) = connectedSocketPair()
            val sender = FramedConnection(client)
            val receiver = FramedConnection(server)

            val frames =
                listOf(
                    byteArrayOf(0x01),
                    byteArrayOf(0x02, 0x03),
                    byteArrayOf(0x04, 0x05, 0x06),
                    ByteArray(1500) { (it and 0xFF).toByte() },
                )

            // Pre-buffer everything in one write so the receiver is forced
            // to demarcate using only the length prefixes.
            coroutineScope {
                val readBack =
                    async {
                        frames.map { receiver.receiveFrame() }
                    }
                frames.forEach { sender.sendFrame(it) }
                val got = readBack.await()
                assertThat(got).hasSize(frames.size)
                frames.forEachIndexed { i, expected ->
                    assertThat(got[i]).isEqualTo(expected)
                }
            }
        }

    @Test
    fun `receiveFrame surfaces graceful end-of-stream when peer closes between frames`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val sender = FramedConnection(client)
            val receiver = FramedConnection(server)

            val payload = byteArrayOf(0x11, 0x22, 0x33)
            sender.sendFrame(payload)
            client.close()

            // First read: complete frame, succeeds.
            assertThat(receiver.receiveFrame()).isEqualTo(payload)

            // Second read: peer closed cleanly at a frame boundary, so we
            // expect [EndOfFrameStream] rather than a truncation error.
            assertThrows<EndOfFrameStream> {
                receiver.receiveFrame()
            }
        }

    @Test
    fun `receiveFrame throws EOFException when the peer closes mid-header`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val receiver = FramedConnection(server)

            // Write only 2 bytes of a 4-byte length header, then close.
            client.getOutputStream().apply {
                write(byteArrayOf(0x00, 0x00))
                flush()
            }
            client.close()

            assertThrows<EOFException> {
                receiver.receiveFrame()
            }
        }

    @Test
    fun `receiveFrame throws EOFException when the peer closes mid-payload`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val receiver = FramedConnection(server)

            // Announce a 10-byte frame, deliver only 4 bytes, then close.
            client.getOutputStream().apply {
                write(byteArrayOf(0x00, 0x00, 0x00, 0x0A))
                write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
                flush()
            }
            client.close()

            assertThrows<EOFException> {
                receiver.receiveFrame()
            }
        }

    @Test
    fun `receiveFrame rejects frames at or above SANE_FRAME_LENGTH without consuming the payload`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val receiver = FramedConnection(server)

            // Declare exactly SANE_FRAME_LENGTH — should be rejected (the
            // bound is exclusive, matching NearDrop's `>=` check).
            val oversize = FramedConnection.SANE_FRAME_LENGTH
            client.getOutputStream().apply {
                write(
                    byteArrayOf(
                        (oversize ushr 24).toByte(),
                        (oversize ushr 16).toByte(),
                        (oversize ushr 8).toByte(),
                        oversize.toByte(),
                    ),
                )
                flush()
            }

            val ex =
                assertThrows<OversizedFrameException> {
                    receiver.receiveFrame()
                }
            assertThat(ex.declaredLength).isEqualTo(oversize)
        }

    @Test
    fun `receiveFrame rejects negative declared lengths`() =
        runTest {
            // High bit set: as an unsigned u32 this is ~2 GiB; as a signed
            // Int it would wrap to negative. A naive implementation that
            // forgets the unsigned interpretation would treat it as a
            // 0-length read and silently desynchronize — guard against
            // that.
            val (client, server) = connectedSocketPair()
            val receiver = FramedConnection(server)

            client.getOutputStream().apply {
                write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                flush()
            }

            assertThrows<OversizedFrameException> {
                receiver.receiveFrame()
            }
        }

    @Test
    fun `receiveFrame rejects zero-length frames`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val receiver = FramedConnection(server)

            client.getOutputStream().apply {
                write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
                flush()
            }

            assertThrows<OversizedFrameException> {
                receiver.receiveFrame()
            }
        }

    @Test
    fun `receiveFrame accepts the largest legal payload size`() =
        runTest {
            val (client, server) = connectedSocketPair()
            val sender = FramedConnection(client)
            val receiver = FramedConnection(server)

            // SANE_FRAME_LENGTH - 1 is the largest length we accept (the
            // bound is exclusive). Use a small but distinctive payload so
            // the test stays cheap; correctness here is about the
            // boundary check, not throughput.
            val payload = ByteArray(64) { 0x7E }
            coroutineScope {
                val received = async { receiver.receiveFrame() }
                sender.sendFrame(payload)
                assertThat(received.await()).isEqualTo(payload)
            }
        }

    @Test
    fun `sendFrame and receiveFrame are usable from concurrent coroutines on the same connection`() =
        runTest {
            // Quick Share is bidirectional; the read and write halves of
            // a single FramedConnection must be drivable from two
            // different coroutines without deadlocking on a shared lock.
            val (client, server) = connectedSocketPair()
            val a = FramedConnection(client)
            val b = FramedConnection(server)

            val toServer = byteArrayOf(0x01, 0x02, 0x03)
            val toClient = byteArrayOf(0x09, 0x08, 0x07, 0x06)

            coroutineScope {
                val serverReceives = async { b.receiveFrame() }
                val clientReceives = async { a.receiveFrame() }
                a.sendFrame(toServer)
                b.sendFrame(toClient)
                assertThat(serverReceives.await()).isEqualTo(toServer)
                assertThat(clientReceives.await()).isEqualTo(toClient)
            }
        }

    /**
     * Reads [buf.size] bytes from [socket]'s input stream, looping over
     * partial reads. Used by the raw-bytes inspection test only; the
     * production receiver does this internally.
     */
    private fun readFully(
        socket: Socket,
        buf: ByteArray,
    ) {
        val input = socket.getInputStream()
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            check(n >= 0) { "Unexpected EOF after $read of ${buf.size} bytes" }
            read += n
        }
    }
}
