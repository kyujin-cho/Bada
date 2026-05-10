/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.transport.asConnectedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class NearbyMultiplexTransportTest {
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
    fun `client and server exchange virtual bytes over multiplexed physical socket`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val serverDeferred =
                    async(Dispatchers.IO) {
                        probeNearbyMultiplexServerTransport(serverSocket.asConnectedTransport(Medium.WIFI_LAN))
                    }
                val client =
                    NearbyMultiplexClientTransport(
                        physicalTransport = clientSocket.asConnectedTransport(Medium.WIFI_LAN),
                        salt = "client-salt",
                    )
                client.start()

                assertThat(client.awaitReady(CONNECTION_READY_TIMEOUT_MS)).isTrue()
                val server = serverDeferred.await()

                client.outputStream.write("hello".toByteArray(Charsets.UTF_8))
                client.outputStream.flush()
                assertThat(server.inputStream.readExactly(5).decodeToString()).isEqualTo("hello")

                server.outputStream.write("world".toByteArray(Charsets.UTF_8))
                server.outputStream.flush()
                assertThat(client.inputStream.readExactly(5).decodeToString()).isEqualTo("world")

                client.close()
                server.close()
            }
        }

    @Test
    fun `optimistic client sends virtual bytes before connection response`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val client =
                    NearbyMultiplexClientTransport(
                        physicalTransport = clientSocket.asConnectedTransport(Medium.WIFI_LAN),
                        salt = "client-salt",
                        requireConnectionResponse = false,
                    )
                client.start()

                assertThat(client.awaitReady(CONNECTION_READY_TIMEOUT_MS)).isTrue()
                client.outputStream.write("early".toByteArray(Charsets.UTF_8))
                client.outputStream.flush()

                val serverInput = serverSocket.getInputStream()
                val request =
                    NearbyMultiplexFrames.parseFrame(
                        serverInput.readLengthPrefixedPayloadForTest(),
                    )
                val data =
                    NearbyMultiplexFrames.parseFrame(
                        serverInput.readLengthPrefixedPayloadForTest(),
                    )

                assertThat(request?.controlFrame?.controlFrameType)
                    .isEqualTo(
                        com.google.location.nearby.mediums.proto.MultiplexFramesProto
                            .MultiplexControlFrame
                            .MultiplexControlFrameType
                            .CONNECTION_REQUEST,
                    )
                assertThat(
                    data
                        ?.dataFrame
                        ?.data
                        ?.toByteArray()
                        ?.decodeToString(),
                ).isEqualTo("early")
                assertThat(data?.header?.hasServiceIdHashSalt()).isFalse()

                client.close()
            }
        }

    @Test
    fun `optimistic client waits receiver grace before opening virtual socket`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val client =
                    NearbyMultiplexClientTransport(
                        physicalTransport = clientSocket.asConnectedTransport(Medium.WIFI_LAN),
                        salt = "client-salt",
                        requireConnectionResponse = false,
                        optimisticReadyDelayMillis = 200L,
                    )
                client.start()

                val request =
                    NearbyMultiplexFrames.parseFrame(
                        serverSocket.getInputStream().readLengthPrefixedPayloadForTest(),
                    )
                assertThat(request?.controlFrame?.controlFrameType)
                    .isEqualTo(
                        com.google.location.nearby.mediums.proto.MultiplexFramesProto
                            .MultiplexControlFrame
                            .MultiplexControlFrameType
                            .CONNECTION_REQUEST,
                    )
                assertThat(client.awaitReady(50L)).isFalse()
                assertThat(client.awaitReady(CONNECTION_READY_TIMEOUT_MS)).isTrue()

                client.close()
            }
        }

    @Test
    fun `server probe preserves first raw Nearby frame when peer is not multiplexed`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (clientSocket, serverSocket) = connectedSocketPair()
                val serverDeferred =
                    async(Dispatchers.IO) {
                        probeNearbyMultiplexServerTransport(serverSocket.asConnectedTransport(Medium.WIFI_LAN))
                    }
                val payload = byteArrayOf(0x08, 0x01, 0x10, 0x02)
                val framed = NearbyMultiplexFrames.encodeLengthPrefixed(payload)
                withContext(Dispatchers.IO) {
                    clientSocket.getOutputStream().write(framed)
                    clientSocket.getOutputStream().flush()
                }

                val server = serverDeferred.await()
                assertThat(server.inputStream.readExactly(framed.size)).isEqualTo(framed)

                server.outputStream.write("ack".toByteArray(Charsets.UTF_8))
                server.outputStream.flush()
                assertThat(clientSocket.getInputStream().readExactly(3).decodeToString()).isEqualTo("ack")

                server.close()
            }
        }

    private fun connectedSocketPair(): Pair<Socket, Socket> {
        serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val server = serverSocket.accept()
        openedSockets += client
        openedSockets += server
        return client to server
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(out, offset, length - offset)
            check(read >= 0) { "stream closed after $offset of $length bytes" }
            offset += read
        }
        return out
    }

    private fun InputStream.readLengthPrefixedPayloadForTest(): ByteArray {
        val header = readExactly(NearbyMultiplexFrames.LENGTH_PREFIX_BYTES)
        val length = requireNotNull(NearbyMultiplexFrames.decodeLength(header))
        return readExactly(length)
    }

    private companion object {
        private const val CONNECTION_READY_TIMEOUT_MS: Long = 1_000L
        private const val WALLCLOCK_TIMEOUT_MS: Long = 5_000L
    }
}
