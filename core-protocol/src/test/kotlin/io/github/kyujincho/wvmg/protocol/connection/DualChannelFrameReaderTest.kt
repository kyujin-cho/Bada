/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.D2DSessionKeys
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureMessageCodec
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections

class DualChannelFrameReaderTest {
    private val serverSockets = mutableListOf<ServerSocket>()
    private val openedSockets = mutableListOf<Socket>()

    @AfterEach
    fun tearDown() {
        openedSockets.forEach { runCatching { it.close() } }
        serverSockets.forEach { runCatching { it.close() } }
    }

    @Test
    fun `reader buffers upgraded-channel frame until missing prior-channel sequence arrives`() =
        runBlocking {
            val (oldClientSocket, oldServerSocket) = connectedSocketPair()
            val (newClientSocket, newServerSocket) = connectedSocketPair()
            val oldClient = FramedConnection(oldClientSocket)
            val newClient = FramedConnection(newClientSocket)
            val oldServer =
                SecureChannel(
                    FramedConnection(oldServerSocket),
                    freshSessionKeys(D2DRole.SERVER),
                    SecureRandom(),
                )
            val newServer = oldServer.withTransport(FramedConnection(newServerSocket))
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val reader =
                BandwidthUpgradeOrchestrator.DualChannelFrameReader(
                    oldChannel = oldServer,
                    newChannel = newServer,
                    logger = logs::add,
                )
            val firstFrame = keepAliveFrame(ack = false)
            val secondFrame = keepAliveFrame(ack = true)

            newClient.sendSequencedFrame(sequenceNumber = 2, frame = secondFrame)

            coroutineScope {
                val firstRead = async { reader.receiveNextFrame("first interleaved frame") }
                waitForLog(logs, "seq=2 from upgraded")
                assertThat(firstRead.isCompleted).isFalse()

                oldClient.sendSequencedFrame(sequenceNumber = 1, frame = firstFrame)

                assertThat(firstRead.await()).isEqualTo(firstFrame)
                assertThat(reader.receiveNextFrame("second interleaved frame")).isEqualTo(secondFrame)
            }

            assertThat(oldServer.nextReceiveSequenceNumber).isEqualTo(2L)
            assertThat(logs.joinToString("\n")).contains("delivered KEEP_ALIVE seq=1 from prior")
            assertThat(logs.joinToString("\n")).contains("delivered KEEP_ALIVE seq=2 from upgraded")
        }

    private suspend fun waitForLog(
        logs: List<String>,
        needle: String,
    ) {
        repeat(LOG_WAIT_ATTEMPTS) {
            if (logs.any { it.contains(needle) }) return
            delay(LOG_WAIT_DELAY_MILLIS)
        }
        error("Timed out waiting for log containing '$needle'; logs=$logs")
    }

    private suspend fun FramedConnection.sendSequencedFrame(
        sequenceNumber: Int,
        frame: OfflineFrame,
    ) {
        val keys = freshSessionKeys(D2DRole.CLIENT).forRole()
        val payload =
            SecureMessageCodec.wrapDeviceToDeviceMessage(
                offlineFrame = frame.toByteArray(),
                sequenceNumber = sequenceNumber,
            )
        val sealed =
            SecureMessageCodec.encryptAndSign(
                payload = payload,
                encryptKey = keys.sendEncryptKey,
                hmacKey = keys.sendHmacKey,
                iv = SecureMessageCodec.randomIv(SecureRandom()),
            )
        sendFrame(sealed)
    }

    private fun connectedSocketPair(): Pair<Socket, Socket> {
        val serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        serverSockets += serverSocket
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    private fun freshSessionKeys(role: D2DRole): D2DSessionKeys {
        val dhs = ByteArray(D2DKeyDerivation.KEY_SIZE) { (it + 0x20).toByte() }
        val clientInit = "dual-reader-client-init".toByteArray()
        val serverInit = "dual-reader-server-init".toByteArray()
        return D2DKeyDerivation.derive(dhs, clientInit, serverInit, role)
    }

    private fun keepAliveFrame(ack: Boolean): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().setAck(ack)),
            ).build()

    private companion object {
        const val LOG_WAIT_ATTEMPTS = 50
        const val LOG_WAIT_DELAY_MILLIS = 10L
    }
}
