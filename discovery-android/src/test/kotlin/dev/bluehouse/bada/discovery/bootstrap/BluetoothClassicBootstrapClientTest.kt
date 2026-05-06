/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.bootstrap

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.medium.Medium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BluetoothClassicBootstrapClientTest {
    @Test
    fun `connect uses stock Nearby UUID and returns bluetooth transport`() =
        runBlocking {
            val io = FakeIo()
            val client =
                BluetoothClassicBootstrapClient(
                    bluetooth = io,
                    dispatcher = Dispatchers.IO,
                )

            val transport = client.connect("AA:BB:CC:DD:EE:FF")

            assertThat(io.openCalls).hasSize(1)
            assertThat(io.openCalls.single().uuid).isEqualTo(NearbyServiceId.bluetoothServiceUuid)
            assertThat(io.openCalls.single().macAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
            assertThat(transport).isNotNull()
            assertThat(transport!!.medium).isEqualTo(Medium.BLUETOOTH)
        }

    @Test
    fun `cancelPendingConnect closes in-flight socket and returns null`() =
        runBlocking {
            val io = FakeIo(socket = BlockingSocket())
            val client =
                BluetoothClassicBootstrapClient(
                    bluetooth = io,
                    dispatcher = Dispatchers.IO,
                )
            val socket = io.socket as BlockingSocket
            val result = AtomicReference<Any?>("pending")
            val worker =
                Thread {
                    result.set(
                        runBlocking {
                            client.connect("11:22:33:44:55:66")
                        },
                    )
                }
            worker.start()
            assertThat(socket.connectStarted.await(5, TimeUnit.SECONDS)).isTrue()
            client.cancelPendingConnect()
            worker.join(5_000)

            assertThat(worker.isAlive).isFalse()
            assertThat(result.get()).isNull()
            assertThat(socket.closed).isTrue()
        }

    private class FakeIo(
        val socket: BluetoothClassicClientSocket = ImmediateSocket(),
    ) : BluetoothClassicBootstrapClientIo {
        val openCalls: MutableList<OpenCall> = mutableListOf()

        override fun isAvailable(): Boolean = true

        override fun openSocket(
            macAddress: String,
            uuid: UUID,
        ): BluetoothClassicClientSocket {
            openCalls += OpenCall(macAddress, uuid)
            return socket
        }
    }

    private data class OpenCall(
        val macAddress: String,
        val uuid: UUID,
    )

    private interface TestSocket : BluetoothClassicClientSocket {
        val closed: Boolean
    }

    private class ImmediateSocket : TestSocket {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override val closed: Boolean = false

        override fun connect() {}

        override fun close() {}
    }

    private class BlockingSocket : TestSocket {
        val connectStarted = CountDownLatch(1)

        @Volatile
        override var closed: Boolean = false

        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()

        override fun connect() {
            connectStarted.countDown()
            while (!closed) {
                Thread.sleep(10)
            }
            throw IOException("closed")
        }

        override fun close() {
            closed = true
        }
    }
}
