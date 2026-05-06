/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.ukey2

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientInit
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2HandshakeCipher
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import dev.bluehouse.bada.protocol.transport.FramedConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * End-to-end tests for the full UKEY2 client+server handshake.
 *
 * These tests run a real `Socket` pair on loopback so the
 * [FramedConnection] transport is exercised exactly as it is in
 * production. Each test wires up a client coroutine and a server
 * coroutine, drives them through one handshake, and asserts on the
 * resulting [Ukey2HandshakeResult]:
 *
 *  - Both sides derive the **same** `dhs`.
 *  - Both sides observe the **same** `clientInitMsg` and `serverInitMsg`
 *    raw bytes — these feed downstream HKDF, so any divergence breaks
 *    every subsequent SecureMessage frame.
 *
 * Negative-path tests inject malformed `ClientInit` frames directly
 * (bypassing the client-side helpers) so we can exercise each
 * `Ukey2Alert` emission path independently.
 */
class Ukey2HandshakeIntegrationTest {
    private lateinit var serverSocket: ServerSocket
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
        if (::serverSocket.isInitialized) {
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `client and server derive identical handshake results`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { Ukey2Server.performHandshake(serverConn) }
                val clientResult = async { Ukey2Client.performHandshake(clientConn) }

                val s = serverResult.await()
                val c = clientResult.await()

                assertThat(c.dhs).isEqualTo(s.dhs)
                assertThat(c.clientInitMsg).isEqualTo(s.clientInitMsg)
                assertThat(c.serverInitMsg).isEqualTo(s.serverInitMsg)
                assertThat(c.dhs).hasLength(Ukey2HandshakeResult.DHS_SIZE)
            }
        }

    @Test
    fun `serverInitMsg parses as a Ukey2Message of type SERVER_INIT`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { Ukey2Server.performHandshake(serverConn) }
                val clientResult = async { Ukey2Client.performHandshake(clientConn) }

                val c = clientResult.await()
                serverResult.await()

                val parsed = Ukey2Message.parseFrom(c.serverInitMsg)
                assertThat(parsed.messageType).isEqualTo(Ukey2Message.Type.SERVER_INIT)
            }
        }

    @Test
    fun `clientInitMsg parses as a Ukey2Message of type CLIENT_INIT with the correct cipher`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { Ukey2Server.performHandshake(serverConn) }
                val clientResult = async { Ukey2Client.performHandshake(clientConn) }

                val s = serverResult.await()
                clientResult.await()

                val outer = Ukey2Message.parseFrom(s.clientInitMsg)
                assertThat(outer.messageType).isEqualTo(Ukey2Message.Type.CLIENT_INIT)
                val inner = Ukey2ClientInit.parseFrom(outer.messageData)
                assertThat(inner.version).isEqualTo(Ukey2.PROTOCOL_VERSION)
                assertThat(inner.random.size()).isEqualTo(Ukey2.RANDOM_SIZE)
                assertThat(inner.nextProtocol).isEqualTo(Ukey2.NEXT_PROTOCOL)
                assertThat(inner.cipherCommitmentsList).hasSize(1)
                assertThat(inner.cipherCommitmentsList[0].handshakeCipher)
                    .isEqualTo(Ukey2HandshakeCipher.P256_SHA512)
            }
        }

    @Test
    fun `server emits BAD_VERSION when client announces a non-1 version`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }
                clientConn.sendFrame(buildSyntheticClientInit(version = 99))

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_VERSION)

                val ex = serverResult.await().exceptionOrNull()
                assertThat(ex).isInstanceOf(Ukey2HandshakeException::class.java)
                assertThat((ex as Ukey2HandshakeException).alert).isEqualTo(Ukey2AlertType.BAD_VERSION)
            }
        }

    @Test
    fun `server emits BAD_RANDOM when client random is the wrong length`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }
                clientConn.sendFrame(buildSyntheticClientInit(randomLength = 16))

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_RANDOM)

                val ex = serverResult.await().exceptionOrNull() as? Ukey2HandshakeException
                assertThat(ex?.alert).isEqualTo(Ukey2AlertType.BAD_RANDOM)
            }
        }

    @Test
    fun `server emits BAD_HANDSHAKE_CIPHER when client offers no P256_SHA512`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }
                clientConn.sendFrame(
                    buildSyntheticClientInit(cipher = Ukey2HandshakeCipher.CURVE25519_SHA512),
                )

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_HANDSHAKE_CIPHER)

                val ex = serverResult.await().exceptionOrNull() as? Ukey2HandshakeException
                assertThat(ex?.alert).isEqualTo(Ukey2AlertType.BAD_HANDSHAKE_CIPHER)
            }
        }

    @Test
    fun `server emits BAD_NEXT_PROTOCOL when client requests a different next-protocol`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }
                clientConn.sendFrame(buildSyntheticClientInit(nextProtocol = "WRONG_PROTOCOL"))

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_NEXT_PROTOCOL)

                val ex = serverResult.await().exceptionOrNull() as? Ukey2HandshakeException
                assertThat(ex?.alert).isEqualTo(Ukey2AlertType.BAD_NEXT_PROTOCOL)
            }
        }

    @Test
    fun `server emits BAD_MESSAGE when ClientFinished SHA-512 mismatches the commitment`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }

                // Send a well-formed ClientInit with a *fake* commitment
                // that won't match anything we send afterwards.
                val fakeCommitment = ByteArray(SHA512_LEN) { 0xAA.toByte() }
                val clientInitBytes = buildSyntheticClientInit(commitment = fakeCommitment)
                clientConn.sendFrame(clientInitBytes)

                // Drain the server's ServerInit reply so the next read
                // sees ClientFinished bytes.
                val serverInitBytes = clientConn.receiveFrame()
                val serverInitMsg = Ukey2Message.parseFrom(serverInitBytes)
                assertThat(serverInitMsg.messageType).isEqualTo(Ukey2Message.Type.SERVER_INIT)

                // Send some unrelated bytes as ClientFinished — SHA-512
                // of these will not match `fakeCommitment`.
                val bogusFinished =
                    Ukey2Message
                        .newBuilder()
                        .setMessageType(Ukey2Message.Type.CLIENT_FINISH)
                        .setMessageData(ByteString.copyFromUtf8("not the real ClientFinished"))
                        .build()
                        .toByteArray()
                clientConn.sendFrame(bogusFinished)

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_MESSAGE)

                val ex = serverResult.await().exceptionOrNull() as? Ukey2HandshakeException
                assertThat(ex?.alert).isEqualTo(Ukey2AlertType.BAD_MESSAGE)
            }
        }

    @Test
    fun `server emits BAD_MESSAGE_TYPE when client sends a non-CLIENT_INIT message first`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val serverResult = async { runCatching { Ukey2Server.performHandshake(serverConn) } }
                val notClientInit =
                    Ukey2Message
                        .newBuilder()
                        .setMessageType(Ukey2Message.Type.SERVER_INIT)
                        .setMessageData(ByteString.EMPTY)
                        .build()
                        .toByteArray()
                clientConn.sendFrame(notClientInit)

                val alert = readAlert(clientConn)
                assertThat(alert).isEqualTo(Ukey2AlertType.BAD_MESSAGE_TYPE)

                val ex = serverResult.await().exceptionOrNull() as? Ukey2HandshakeException
                assertThat(ex?.alert).isEqualTo(Ukey2AlertType.BAD_MESSAGE_TYPE)
            }
        }

    @Test
    fun `client throws when server replies with an alert frame`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val clientResult = async { runCatching { Ukey2Client.performHandshake(clientConn) } }

                // Server side: drain the ClientInit, then send back an alert.
                serverConn.receiveFrame()
                sendUkey2Alert(serverConn, Ukey2AlertType.BAD_VERSION)

                val ex = clientResult.await().exceptionOrNull()
                assertThat(ex).isInstanceOf(Ukey2HandshakeException::class.java)
            }
        }

    @Test
    fun `client throws when server picks a non-P256_SHA512 cipher`() =
        runTest {
            val (clientConn, serverConn) = pairedFramedConnections()

            coroutineScope {
                val clientResult = async { runCatching { Ukey2Client.performHandshake(clientConn) } }

                serverConn.receiveFrame()
                // Hand-craft a ServerInit that picks Curve25519 — a
                // valid cipher in the spec, but not what we offered.
                val malformed =
                    com.google.security.cryptauth.lib.securegcm.UkeyProto
                        .Ukey2ServerInit
                        .newBuilder()
                        .setVersion(Ukey2.PROTOCOL_VERSION)
                        .setRandom(ByteString.copyFrom(ByteArray(Ukey2.RANDOM_SIZE)))
                        .setHandshakeCipher(Ukey2HandshakeCipher.CURVE25519_SHA512)
                        .setPublicKey(ByteString.copyFrom(ByteArray(MIN_PUBLIC_KEY_PLACEHOLDER_LEN)))
                        .build()
                val wrapper =
                    Ukey2Message
                        .newBuilder()
                        .setMessageType(Ukey2Message.Type.SERVER_INIT)
                        .setMessageData(ByteString.copyFrom(malformed.toByteArray()))
                        .build()
                serverConn.sendFrame(wrapper.toByteArray())

                val ex = clientResult.await().exceptionOrNull()
                assertThat(ex).isInstanceOf(Ukey2HandshakeException::class.java)
            }
        }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun pairedFramedConnections(): Pair<FramedConnection, FramedConnection> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return FramedConnection(client) to FramedConnection(server)
    }

    /**
     * Builds a hand-rolled `Ukey2Message{CLIENT_INIT}` byte array that
     * may deliberately violate one or more constraints, to exercise the
     * server's validation paths.
     */
    private fun buildSyntheticClientInit(
        version: Int = Ukey2.PROTOCOL_VERSION,
        randomLength: Int = Ukey2.RANDOM_SIZE,
        cipher: Ukey2HandshakeCipher = Ukey2HandshakeCipher.P256_SHA512,
        nextProtocol: String = Ukey2.NEXT_PROTOCOL,
        commitment: ByteArray = ByteArray(SHA512_LEN) { 0xCC.toByte() },
    ): ByteArray {
        val cc =
            Ukey2ClientInit.CipherCommitment
                .newBuilder()
                .setHandshakeCipher(cipher)
                .setCommitment(ByteString.copyFrom(commitment))
                .build()
        val ci =
            Ukey2ClientInit
                .newBuilder()
                .setVersion(version)
                .setRandom(ByteString.copyFrom(ByteArray(randomLength)))
                .addCipherCommitments(cc)
                .setNextProtocol(nextProtocol)
                .build()
        return Ukey2Message
            .newBuilder()
            .setMessageType(Ukey2Message.Type.CLIENT_INIT)
            .setMessageData(ByteString.copyFrom(ci.toByteArray()))
            .build()
            .toByteArray()
    }

    /**
     * Reads one frame, expects a `Ukey2Message{ALERT}`, and returns the
     * decoded [Ukey2AlertType] (or fails the test if the frame is the
     * wrong shape).
     */
    private suspend fun readAlert(connection: FramedConnection): Ukey2AlertType {
        val raw = connection.receiveFrame()
        val msg = Ukey2Message.parseFrom(raw)
        check(msg.messageType == Ukey2Message.Type.ALERT) {
            "Expected ALERT, got ${msg.messageType}"
        }
        val alert =
            com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Alert
                .parseFrom(msg.messageData)
        return Ukey2AlertType.entries.first { it.protoType == alert.type }
    }

    @Test
    fun `Ukey2HandshakeResult rejects dhs of the wrong length`() {
        assertThrows<IllegalArgumentException> {
            Ukey2HandshakeResult(
                dhs = ByteArray(WRONG_DHS_LEN),
                clientInitMsg = ByteArray(0),
                serverInitMsg = ByteArray(0),
            )
        }
    }

    @Test
    fun `messageDigest isEqual matches our use of it for constant-time equality`() {
        // A trivial sanity check that the JCE provides MessageDigest.isEqual
        // and it returns true for equal arrays. Defence in depth in case a
        // test environment ships a broken JCE.
        val a = ByteArray(SHA512_LEN) { 0x55 }
        val b = a.copyOf()
        assertThat(MessageDigest.isEqual(a, b)).isTrue()
    }

    companion object {
        private const val SHA512_LEN = 64
        private const val WRONG_DHS_LEN = 16
        private const val MIN_PUBLIC_KEY_PLACEHOLDER_LEN = 8
    }
}
