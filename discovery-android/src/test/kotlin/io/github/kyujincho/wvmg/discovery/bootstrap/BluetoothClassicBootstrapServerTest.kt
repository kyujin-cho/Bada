/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.bootstrap

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.BluetoothDeviceName
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.transport.ConnectedTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothClassicBootstrapServerTest {
    @Test
    fun `start sets Nearby Bluetooth name and listens on service-id uuid`() =
        runTest {
            val io = FakeBluetoothIo()
            val server =
                BluetoothClassicBootstrapServer(
                    bluetooth = io,
                    endpointIdProvider = { byteArrayOf(0x57, 0x76, 0x6D, 0x67) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

            val started = server.start(sampleEndpointInfo()) {}

            assertThat(started).isTrue()
            assertThat(io.setNames).hasSize(1)
            val parsed = BluetoothDeviceName.parse(io.setNames.single())
            assertThat(parsed).isNotNull()
            assertThat(parsed!!.endpointId).isEqualTo(byteArrayOf(0x57, 0x76, 0x6D, 0x67))
            assertThat(parsed.serviceIdHash).isEqualTo(NearbyServiceId.hashPrefix)
            assertThat(io.listenCalls.single().name).isEqualTo(NearbyServiceId.VALUE)
            assertThat(io.listenCalls.single().uuid).isEqualTo(NearbyServiceId.bluetoothServiceUuid)

            server.stop()
            assertThat(io.currentName).isEqualTo(FakeBluetoothIo.ORIGINAL_NAME)
        }

    @Test
    fun `start returns false without touching name when bluetooth is unavailable`() {
        val io = FakeBluetoothIo(available = false)
        val server =
            BluetoothClassicBootstrapServer(
                bluetooth = io,
                endpointIdProvider = { byteArrayOf(0x57, 0x76, 0x6D, 0x67) },
                dispatcher = StandardTestDispatcher(),
            )

        val started = server.start(sampleEndpointInfo()) {}

        assertThat(started).isFalse()
        assertThat(io.setNames).isEmpty()
        assertThat(io.listenCalls).isEmpty()
    }

    @Test
    fun `accepted transports are forwarded to the receiver callback`() =
        runTest {
            val transport = ClosedTransport()
            val fakeServerSocket = FakeServerSocket()
            fakeServerSocket.acceptResults += transport
            val io = FakeBluetoothIo(serverSocket = fakeServerSocket)
            val accepted = mutableListOf<ConnectedTransport>()
            val server =
                BluetoothClassicBootstrapServer(
                    bluetooth = io,
                    endpointIdProvider = { byteArrayOf(0x57, 0x76, 0x6D, 0x67) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

            server.start(sampleEndpointInfo()) { accepted += it }
            advanceUntilIdle()

            assertThat(accepted).containsExactly(transport)
            assertThat(server.isActive).isFalse()
        }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "WVMG",
            tlvRecords = emptyList(),
        )

    private class FakeBluetoothIo(
        private val available: Boolean = true,
        private val serverSocket: FakeServerSocket = FakeServerSocket(),
    ) : BluetoothClassicBootstrapIo {
        data class ListenCall(
            val name: String,
            val uuid: UUID,
        )

        var currentName: String? = ORIGINAL_NAME
        val setNames: MutableList<String> = mutableListOf()
        val listenCalls: MutableList<ListenCall> = mutableListOf()

        override fun isAvailable(): Boolean = available

        override fun currentName(): String? = currentName

        override fun setName(name: String): Boolean {
            currentName = name
            setNames += name
            return true
        }

        override fun listen(
            name: String,
            uuid: UUID,
        ): BluetoothClassicServerSocket? {
            listenCalls += ListenCall(name, uuid)
            return serverSocket
        }

        companion object {
            const val ORIGINAL_NAME: String = "Original"
        }
    }

    private class FakeServerSocket : BluetoothClassicServerSocket {
        val acceptResults: ArrayDeque<ConnectedTransport> = ArrayDeque()

        var closed: Boolean = false
            private set

        override fun accept(): ConnectedTransport? {
            if (closed) throw IOException("closed")
            return acceptResults.removeFirstOrNull() ?: throw IOException("done")
        }

        override fun close() {
            closed = true
        }
    }

    private class ClosedTransport : ConnectedTransport {
        override val medium: Medium = Medium.BLUETOOTH

        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))

        override val outputStream: OutputStream = ByteArrayOutputStream()

        override fun close() {
            inputStream.close()
            outputStream.close()
        }
    }
}
