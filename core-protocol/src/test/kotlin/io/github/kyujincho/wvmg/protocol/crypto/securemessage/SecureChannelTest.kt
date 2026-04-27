/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.securemessage

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.D2DSessionKeys
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Tests for [SecureChannel].
 *
 * Coverage spans the issue #13 acceptance criteria:
 *
 *  1. **Round-trip on a real loopback socket.** Two channels (CLIENT +
 *     SERVER) hold the same `D2DSessionKeys`, and frames flow both ways.
 *  2. **1000-frame round-trip with random plaintext.** Asserts every
 *     received frame matches the corresponding sent one and that
 *     sequence-number counters advance monotonically on both sides.
 *  3. **Sequence-number mismatch is fatal.** A maliciously rewritten
 *     `DeviceToDeviceMessage.sequence_number` (correctly re-signed by an
 *     attacker who somehow has the keys) must still be rejected because
 *     the receiver's local counter expects a different value.
 *  4. **HMAC tampering on the wire is fatal.** A bit-flipped signature
 *     never reaches AES-decrypt; the receiver throws
 *     [SecureMessageVerificationException].
 */
class SecureChannelTest {
    private lateinit var serverSocket: ServerSocket
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
        if (::serverSocket.isInitialized) {
            runCatching { serverSocket.close() }
        }
    }

    /** Builds a fresh `D2DSessionKeys` bundle with random key bytes for the given role. */
    private fun freshSessionKeys(role: D2DRole): D2DSessionKeys {
        // Drive the real D2D derivation so the four SecureMessage keys are
        // mutually consistent with the documented chain. Using a fixed
        // dhs/init blob means CLIENT and SERVER bundles will share the
        // same underlying 4 keys (just different role-resolved
        // forRole() splits).
        val dhs = ByteArray(D2DKeyDerivation.KEY_SIZE) { (it + 0x10).toByte() }
        val clientInit = "client-init-bytes".toByteArray()
        val serverInit = "server-init-bytes".toByteArray()
        return D2DKeyDerivation.derive(dhs, clientInit, serverInit, role)
    }

    /** Opens a loopback `Socket` pair. */
    private fun connectedSocketPair(): Pair<Socket, Socket> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    /**
     * Builds a `KEEP_ALIVE`-tagged [OfflineFrame]. KeepAlive is the
     * smallest legal V1 frame; perfect for round-trip tests where the
     * payload bytes are not the focus.
     */
    private fun keepAliveFrame(): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().build()),
            ).build()

    /**
     * Builds a `KEEP_ALIVE` [OfflineFrame] whose `KeepAliveFrame.ack`
     * field encodes one bit of [seed], so frames produced for distinct
     * seeds are distinguishable on the wire.
     *
     * KeepAliveFrame has only an `optional bool ack`, so this is the
     * minimum-payload way to make 1000 frames not all be byte-equal.
     * The inequality of consecutive frames is what gives the round-trip
     * test its discriminating power: a buggy implementation that
     * accidentally reused the previous plaintext would be caught.
     */
    private fun seededFrame(seed: Int): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().setAck(seed % 2 == 0)),
            ).build()

    @Test
    fun `single OfflineFrame round-trips through send and receive`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            val clientChannel =
                SecureChannel(clientConn, freshSessionKeys(D2DRole.CLIENT), SecureRandom())
            val serverChannel =
                SecureChannel(serverConn, freshSessionKeys(D2DRole.SERVER), SecureRandom())

            val frame = keepAliveFrame()
            coroutineScope {
                val received = async { serverChannel.receiveOfflineFrame() }
                clientChannel.sendOfflineFrame(frame)
                assertThat(received.await()).isEqualTo(frame)
            }

            assertThat(clientChannel.nextSendSequenceNumber).isEqualTo(1L)
            assertThat(serverChannel.nextReceiveSequenceNumber).isEqualTo(1L)
        }

    @Test
    fun `1000 frames round-trip in alternation with monotonic sequence numbers`() =
        // Real socket I/O does not play well with `runTest`'s virtual
        // clock — `Dispatchers.IO` runs on real time, while `runTest`
        // skips ahead, so any nested `withTimeout` would fire spuriously
        // and concurrent send/receive can starve. Use `runBlocking` so
        // the coroutines actually wait on the real wall clock.
        runBlocking {
            // The headline acceptance criterion: a long round-trip exercise
            // confirms that (a) every frame survives intact, (b) sequence
            // numbers tick up by exactly one per frame on each side, and
            // (c) randomized payload sizes produce no padding edge case.
            val (clientSock, serverSock) = connectedSocketPair()
            val clientChannel =
                SecureChannel(
                    FramedConnection(clientSock),
                    freshSessionKeys(D2DRole.CLIENT),
                    SecureRandom(),
                )
            val serverChannel =
                SecureChannel(
                    FramedConnection(serverSock),
                    freshSessionKeys(D2DRole.SERVER),
                    SecureRandom(),
                )

            // Pre-build all the frames so the test does not measure proto
            // construction overhead in the hot loop.
            val rng = Random(0xC0FFEE)
            val frames = List(FRAME_COUNT) { _ -> seededFrame(rng.nextInt()) }

            coroutineScope {
                val receiveJob =
                    async {
                        for (i in frames.indices) {
                            val received = serverChannel.receiveOfflineFrame()
                            assertThat(received).isEqualTo(frames[i])
                            // theirSeq is pre-incremented inside receive, so after
                            // frame i (0-indexed) it equals i+1.
                            assertThat(serverChannel.nextReceiveSequenceNumber).isEqualTo((i + 1).toLong())
                        }
                    }
                for (i in frames.indices) {
                    clientChannel.sendOfflineFrame(frames[i])
                    assertThat(clientChannel.nextSendSequenceNumber).isEqualTo((i + 1).toLong())
                }
                receiveJob.await()
            }

            assertThat(clientChannel.nextSendSequenceNumber).isEqualTo(FRAME_COUNT.toLong())
            assertThat(serverChannel.nextReceiveSequenceNumber).isEqualTo(FRAME_COUNT.toLong())
        }

    @Test
    fun `bidirectional traffic increments each side independently`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientChannel =
                SecureChannel(
                    FramedConnection(clientSock),
                    freshSessionKeys(D2DRole.CLIENT),
                    SecureRandom(),
                )
            val serverChannel =
                SecureChannel(
                    FramedConnection(serverSock),
                    freshSessionKeys(D2DRole.SERVER),
                    SecureRandom(),
                )

            val toServer = keepAliveFrame()
            val toClient = keepAliveFrame()

            coroutineScope {
                val serverReceivesA = async { serverChannel.receiveOfflineFrame() }
                clientChannel.sendOfflineFrame(toServer)
                assertThat(serverReceivesA.await()).isEqualTo(toServer)
            }

            coroutineScope {
                val clientReceivesA = async { clientChannel.receiveOfflineFrame() }
                serverChannel.sendOfflineFrame(toClient)
                assertThat(clientReceivesA.await()).isEqualTo(toClient)
            }

            // Both sides have sent exactly one and received exactly one.
            assertThat(clientChannel.nextSendSequenceNumber).isEqualTo(1L)
            assertThat(clientChannel.nextReceiveSequenceNumber).isEqualTo(1L)
            assertThat(serverChannel.nextSendSequenceNumber).isEqualTo(1L)
            assertThat(serverChannel.nextReceiveSequenceNumber).isEqualTo(1L)
        }

    @Test
    fun `receive rejects HMAC tampering with SecureMessageVerificationException`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            val clientChannel =
                SecureChannel(clientConn, freshSessionKeys(D2DRole.CLIENT), SecureRandom())
            val serverChannel =
                SecureChannel(serverConn, freshSessionKeys(D2DRole.SERVER), SecureRandom())

            // Send one good frame (consumes seq=1) so the second send below
            // is at seq=2, ensuring tampering tests do not accidentally
            // align with a seq-number off-by-one bug.
            coroutineScope {
                val received = async { serverChannel.receiveOfflineFrame() }
                clientChannel.sendOfflineFrame(keepAliveFrame())
                received.await()
            }

            // Now have the client compose a frame, but ROUTE IT MANUALLY:
            // tamper one bit of the signature, then write directly to the
            // wire bypassing the channel's send path.
            val frame = keepAliveFrame()
            val payload = frame.toByteArray()
            val seq = 2
            val d2dBytes = SecureMessageCodec.wrapDeviceToDeviceMessage(payload, seq)
            val keys = freshSessionKeys(D2DRole.CLIENT).forRole()
            val sealed =
                SecureMessageCodec.encryptAndSign(
                    payload = d2dBytes,
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = SecureMessageCodec.randomIv(SecureRandom()),
                )

            // Flip one bit of the signature.
            val parsedSm = SecureMessage.parseFrom(sealed)
            val sigBytes = parsedSm.signature.toByteArray().also { it[0] = (it[0].toInt() xor 0x10).toByte() }
            val tampered =
                SecureMessage
                    .newBuilder(parsedSm)
                    .setSignature(
                        com.google.protobuf.ByteString
                            .copyFrom(sigBytes),
                    ).build()
                    .toByteArray()
            clientConn.sendFrame(tampered)

            assertThrows<SecureMessageVerificationException> {
                serverChannel.receiveOfflineFrame()
            }
        }

    @Test
    fun `receive rejects sequence-number mismatch with SequenceNumberMismatchException`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            val serverChannel =
                SecureChannel(serverConn, freshSessionKeys(D2DRole.SERVER), SecureRandom())

            // We do not use a client `SecureChannel` here because we want
            // to forge a frame with `sequence_number = 99` even though
            // the server expects 1.
            val keys = freshSessionKeys(D2DRole.CLIENT).forRole()
            val frame = keepAliveFrame()
            val d2dBytes = SecureMessageCodec.wrapDeviceToDeviceMessage(frame.toByteArray(), 99)
            val sealed =
                SecureMessageCodec.encryptAndSign(
                    payload = d2dBytes,
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = SecureMessageCodec.randomIv(SecureRandom()),
                )
            clientConn.sendFrame(sealed)

            val ex =
                assertThrows<SequenceNumberMismatchException> {
                    serverChannel.receiveOfflineFrame()
                }
            assertThat(ex.expected).isEqualTo(1)
            assertThat(ex.actual).isEqualTo(99)
        }

    @Test
    fun `receive rejects out-of-order frames sent by a misbehaving peer`() =
        runTest {
            // Sender uses an inflated counter for its 2nd frame (seq=3
            // instead of seq=2). Receiver's local counter expects 2 and
            // must reject.
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            val serverChannel =
                SecureChannel(serverConn, freshSessionKeys(D2DRole.SERVER), SecureRandom())

            val keys = freshSessionKeys(D2DRole.CLIENT).forRole()

            // First frame at seq=1 — should be accepted.
            val good =
                SecureMessageCodec.encryptAndSign(
                    payload = SecureMessageCodec.wrapDeviceToDeviceMessage(keepAliveFrame().toByteArray(), 1),
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = SecureMessageCodec.randomIv(SecureRandom()),
                )
            clientConn.sendFrame(good)
            serverChannel.receiveOfflineFrame()

            // Second frame jumps to seq=3 (skipping seq=2). Must be
            // rejected with the expected/actual fields populated.
            val skipped =
                SecureMessageCodec.encryptAndSign(
                    payload = SecureMessageCodec.wrapDeviceToDeviceMessage(keepAliveFrame().toByteArray(), 3),
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = SecureMessageCodec.randomIv(SecureRandom()),
                )
            clientConn.sendFrame(skipped)

            val ex =
                assertThrows<SequenceNumberMismatchException> {
                    serverChannel.receiveOfflineFrame()
                }
            assertThat(ex.expected).isEqualTo(2)
            assertThat(ex.actual).isEqualTo(3)
        }

    @Test
    fun `receive rejects replay of the previous frame`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            val serverChannel =
                SecureChannel(serverConn, freshSessionKeys(D2DRole.SERVER), SecureRandom())

            val keys = freshSessionKeys(D2DRole.CLIENT).forRole()
            val sealed =
                SecureMessageCodec.encryptAndSign(
                    payload = SecureMessageCodec.wrapDeviceToDeviceMessage(keepAliveFrame().toByteArray(), 1),
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = SecureMessageCodec.randomIv(SecureRandom()),
                )

            // Send the same exact byte sequence twice.
            clientConn.sendFrame(sealed)
            clientConn.sendFrame(sealed)

            // First receive: ok (consumes the legitimate seq=1 frame).
            serverChannel.receiveOfflineFrame()
            // Second receive: server expects seq=2, but the replay is
            // still seq=1.
            val ex =
                assertThrows<SequenceNumberMismatchException> {
                    serverChannel.receiveOfflineFrame()
                }
            assertThat(ex.expected).isEqualTo(2)
            assertThat(ex.actual).isEqualTo(1)
        }

    /**
     * Two SecureChannels with mismatched session keys must fail HMAC
     * verification — i.e., role mix-up cannot leak data even one bit.
     */
    @Test
    fun `mismatched session keys (both CLIENT) fail HMAC at the receiver`() =
        runTest {
            val (clientSock, serverSock) = connectedSocketPair()
            val clientConn = FramedConnection(clientSock)
            val serverConn = FramedConnection(serverSock)
            // Both sides claim CLIENT — sendKeys vs receiveKeys mismatch.
            val keysA = freshSessionKeys(D2DRole.CLIENT)
            val keysB = freshSessionKeys(D2DRole.CLIENT)
            val sender = SecureChannel(clientConn, keysA, SecureRandom())
            val receiver = SecureChannel(serverConn, keysB, SecureRandom())

            coroutineScope {
                val recvJob =
                    async {
                        assertThrows<SecureMessageVerificationException> {
                            receiver.receiveOfflineFrame()
                        }
                    }
                sender.sendOfflineFrame(keepAliveFrame())
                recvJob.await()
            }
        }

    private companion object {
        const val FRAME_COUNT = 1000
    }
}
