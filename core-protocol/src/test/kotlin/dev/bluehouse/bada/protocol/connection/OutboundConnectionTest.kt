/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.MediumMetadata
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumLadder
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import dev.bluehouse.bada.protocol.medium.probeNearbyMultiplexServerTransport
import dev.bluehouse.bada.protocol.payload.FileDestinationFactory
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.transport.asConnectedTransport
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
@Suppress("LargeClass")
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

    private suspend fun connectedSocketPair(): Pair<Socket, Socket> {
        val listener = withContext(Dispatchers.IO) { ServerSocket(0, 0, InetAddress.getLoopbackAddress()) }
        val client = withContext(Dispatchers.IO) { Socket(InetAddress.getLoopbackAddress(), listener.localPort) }
        val server = withContext(Dispatchers.IO) { listener.accept() }
        runCatching { listener.close() }
        openedSockets += client
        openedSockets += server
        return client to server
    }

    private class LoopbackUpgradePair(
        private val medium: Medium = Medium.BLUETOOTH,
    ) {
        val wifiFrequencyMhz: Int? =
            if (medium == Medium.WIFI_DIRECT) {
                WIFI_DIRECT_TEST_FREQUENCY_MHZ
            } else {
                null
            }
        private val credentials: UpgradePathCredentials =
            if (medium == Medium.WIFI_DIRECT) {
                UpgradePathCredentials.WifiDirect(
                    ipAddress = byteArrayOf(127, 0, 0, 1),
                    port = 1,
                    ssid = "DIRECT-bada-test",
                    passphrase = "12345678",
                    frequency = WIFI_DIRECT_TEST_FREQUENCY_MHZ,
                )
            } else {
                UpgradePathCredentials.Generic(medium)
            }
        private val clientSocket: Socket
        private val serverSocket: Socket

        init {
            val (client, server) = createPair()
            clientSocket = client
            serverSocket = server
        }

        val clientProvider: MediumProvider =
            object : MediumProvider {
                override val medium: Medium = this@LoopbackUpgradePair.medium

                override fun isSupported(): Boolean = true

                override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? =
                    UpgradedTransport.SocketBacked(medium, clientSocket)
            }

        val serverProvider: MediumProvider =
            object : MediumProvider {
                override val medium: Medium = this@LoopbackUpgradePair.medium

                override fun isSupported(): Boolean = true

                override suspend fun prepareUpgrade(): UpgradePathCredentials = credentials

                override suspend fun acceptUpgrade(): UpgradedTransport =
                    UpgradedTransport.SocketBacked(medium, serverSocket)
            }

        fun close() {
            runCatching { clientSocket.close() }
            runCatching { serverSocket.close() }
        }

        private fun createPair(): Pair<Socket, Socket> {
            val listener = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
            val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            val server = listener.accept()
            listener.close()
            return client to server
        }
    }

    private class SupportedProvider(
        override val medium: Medium,
    ) : MediumProvider {
        override fun isSupported(): Boolean = true
    }

    private fun customEndpointInfo(name: String = "Bada177Lab"): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { index -> (index + 1).toByte() },
            deviceName = name,
        )

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

    private class CloseAwareBlockingInputStream : InputStream() {
        private val lock = Object()
        private var closed: Boolean = false

        override fun read(): Int {
            synchronized(lock) {
                while (!closed) {
                    lock.wait()
                }
            }
            return -1
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = read()

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }

    private class HangingConnectedTransport : ConnectedTransport {
        override val medium: Medium = Medium.BLE
        override val inputStream: CloseAwareBlockingInputStream = CloseAwareBlockingInputStream()
        override val outputStream: ByteArrayOutputStream = ByteArrayOutputStream()
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            inputStream.close()
            outputStream.close()
        }
    }

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
    fun `accept path - preconnected transport completes through full lifecycle`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val factory = InMemoryFactory()
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLUETOOTH),
                        secureRandom = SecureRandom("outbound-preconnected".toByteArray()),
                    )

                val fileBytes = ByteArray(2048) { (it and 0xFF).toByte() }
                val payloadId = 0x5152L
                val files = listOf(bytesSource("preconnected.bin", fileBytes, payloadId))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }
                    val inbound =
                        InboundConnection(
                            transport = server.asConnectedTransport(Medium.BLUETOOTH),
                            secureRandom = SecureRandom("inbound-preconnected".toByteArray()),
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

                assertThat(factory.output[payloadId]?.toByteArray()).isEqualTo(fileBytes)
                assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
            }
        }

    @Test
    fun `accept path - bandwidth upgrade swaps to provider transport before file payload`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val factory = InMemoryFactory()
                val upgradePair = LoopbackUpgradePair()
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        secureRandom = SecureRandom("outbound-upgrade".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        upgradePair.clientProvider,
                                    ),
                                ladder = MediumLadderForBluetoothFirst,
                            ),
                    )

                val fileBytes = ByteArray(4096) { (255 - (it and 0xFF)).toByte() }
                val payloadId = 0x5151L
                val files = listOf(bytesSource("upgrade.bin", fileBytes, payloadId))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(files) }

                        val inbound =
                            InboundConnection(
                                socket = accept(),
                                secureRandom = SecureRandom("inbound-upgrade".toByteArray()),
                                mediumRegistry =
                                    MediumRegistry(
                                        providers =
                                            listOf(
                                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                                upgradePair.serverProvider,
                                            ),
                                        ladder = MediumLadderForBluetoothFirst,
                                    ),
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

                    assertThat(factory.output[payloadId]?.toByteArray()).isEqualTo(fileBytes)
                    assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
                } finally {
                    upgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected bluetooth bootstrap ignores Wi-Fi LAN placeholder and upgrades to WebRTC`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val factory = InMemoryFactory()
                val upgradePair = LoopbackUpgradePair(Medium.WEB_RTC)
                val registry =
                    MediumRegistry(
                        providers =
                            listOf(
                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                upgradePair.clientProvider,
                            ),
                        ladder = MediumLadder(listOf(Medium.WIFI_LAN, Medium.WEB_RTC)),
                    )
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLUETOOTH),
                        secureRandom = SecureRandom("outbound-bt-bootstrap-upgrade".toByteArray()),
                        mediumRegistry = registry,
                    )

                val fileBytes = ByteArray(1024) { (it and 0x7F).toByte() }
                val payloadId = 0x5153L
                val files = listOf(bytesSource("bootstrap-upgrade.bin", fileBytes, payloadId))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(files) }
                        val inbound =
                            InboundConnection(
                                transport = server.asConnectedTransport(Medium.BLUETOOTH),
                                secureRandom = SecureRandom("inbound-bt-bootstrap-upgrade".toByteArray()),
                                mediumRegistry =
                                    MediumRegistry(
                                        providers =
                                            listOf(
                                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                                upgradePair.serverProvider,
                                            ),
                                        ladder = MediumLadder(listOf(Medium.WIFI_LAN, Medium.WEB_RTC)),
                                    ),
                            )

                        launch {
                            inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                            inbound.submitUserConsent(accepted = true)
                        }

                        val inboundResult = inbound.run(factory)
                        val outboundResult = outboundJob.await()

                        assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                        assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                        assertThat(inbound.activeMedium.value).isEqualTo(Medium.WEB_RTC)
                    }

                    assertThat(factory.output[payloadId]?.toByteArray()).isEqualTo(fileBytes)
                    assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
                } finally {
                    upgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected BLE bootstrap upgrades to Wi-Fi Direct from initial medium advertisement`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val factory = InMemoryFactory()
                val directUpgradePair = LoopbackUpgradePair(Medium.WIFI_DIRECT)
                val hotspotUpgradePair = LoopbackUpgradePair(Medium.WIFI_HOTSPOT)
                val logs = mutableListOf<String>()
                val ladder =
                    MediumLadder(
                        listOf(
                            Medium.WIFI_HOTSPOT,
                            Medium.WIFI_DIRECT,
                            Medium.WIFI_LAN,
                            Medium.BLE,
                        ),
                    )
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        secureRandom = SecureRandom("outbound-ble-bootstrap-direct".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        hotspotUpgradePair.clientProvider,
                                        directUpgradePair.clientProvider,
                                        SupportedProvider(Medium.BLE),
                                        SupportedProvider(Medium.BLE_L2CAP),
                                    ),
                                ladder = ladder,
                            ),
                        logger = logs::add,
                    )

                val fileBytes = ByteArray(1024) { (it and 0x7F).toByte() }
                val payloadId = 0x5154L
                val files = listOf(bytesSource("ble-bootstrap-direct.bin", fileBytes, payloadId))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(files) }
                        val inbound =
                            InboundConnection(
                                transport = server.asConnectedTransport(Medium.BLE),
                                secureRandom = SecureRandom("inbound-ble-bootstrap-direct".toByteArray()),
                                mediumRegistry =
                                    MediumRegistry(
                                        providers =
                                            listOf(
                                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                                hotspotUpgradePair.serverProvider,
                                                directUpgradePair.serverProvider,
                                                SupportedProvider(Medium.BLE),
                                                SupportedProvider(Medium.BLE_L2CAP),
                                            ),
                                        ladder = ladder,
                                    ),
                            )

                        launch {
                            inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                            inbound.submitUserConsent(accepted = true)
                        }

                        val inboundResult = inbound.run(factory)
                        val outboundResult = outboundJob.await()

                        assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                        assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                        assertWifiDirectMetadata(outbound, inbound, directUpgradePair.wifiFrequencyMhz)
                    }

                    assertThat(factory.output[payloadId]?.toByteArray()).isEqualTo(fileBytes)
                    assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
                    assertThat(logs)
                        .contains("medium-upgrade: no pre-UKEY2 upgrade offer; continuing on BLE")
                } finally {
                    directUpgradePair.close()
                    hotspotUpgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected BLE bootstrap streams payloads when Wi-Fi Direct offer never arrives`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val factory = InMemoryFactory()
                val logs = mutableListOf<String>()
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        secureRandom = SecureRandom("outbound-ble-bootstrap-no-direct-offer".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        SupportedProvider(Medium.WIFI_DIRECT),
                                        SupportedProvider(Medium.BLE),
                                    ),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                        logger = logs::add,
                    )
                val inbound =
                    InboundConnection(
                        transport = server.asConnectedTransport(Medium.BLE),
                        secureRandom = SecureRandom("inbound-ble-bootstrap-no-direct-offer".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers = listOf(SupportedProvider(Medium.BLE)),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                    )

                val fileBytes = ByteArray(768) { (it xor 0x55).toByte() }
                val payloadId = 0x5156L
                val files = listOf(bytesSource("ble-no-direct-offer.bin", fileBytes, payloadId))

                coroutineScope {
                    val outboundJob = async { outbound.run(files) }

                    launch {
                        inbound.state.first { it is InboundConnectionState.WaitingForUserConsent }
                        inbound.submitUserConsent(accepted = true)
                    }

                    val inboundResult = inbound.run(factory)
                    val outboundResult = outboundJob.await()

                    assertThat(outboundResult).isEqualTo(OutboundResult.Completed)
                    assertThat(inboundResult).isInstanceOf(InboundResult.Completed::class.java)
                }

                assertThat(factory.output[payloadId]?.toByteArray()).isEqualTo(fileBytes)
                assertThat(outbound.state.value).isEqualTo(OutboundConnectionState.Completed)
                assertThat(inbound.activeMedium.value).isEqualTo(Medium.BLE)
                assertThat(logs)
                    .contains(
                        "medium-upgrade: Wi-Fi Direct upgrade was not offered before payload streaming; " +
                            "continuing on BLE",
                    )
            }
        }

    @Test
    fun `preconnected BLE bootstrap consumes pre-UKEY2 Wi-Fi Direct offer before UKEY2`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val directUpgradePair = LoopbackUpgradePair(Medium.WIFI_DIRECT)
                val endpointId = "ABCD"
                val logs = mutableListOf<String>()
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        endpointId = endpointId,
                        secureRandom = SecureRandom("outbound-ble-pre-ukey2-direct".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        directUpgradePair.clientProvider,
                                        SupportedProvider(Medium.BLE),
                                    ),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                        logger = logs::add,
                    )
                val oldWire = FramedConnection(server.asConnectedTransport(Medium.BLE))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(emptyList()) }
                        val request =
                            OfflineFrame
                                .parseFrom(oldWire.receiveFrame())
                                .v1
                                .connectionRequest

                        assertThat(request.endpointId).isEqualTo(endpointId)
                        oldWire.sendFrame(
                            BandwidthUpgradeFrames
                                .upgradePathAvailable(UpgradePathCredentials.Generic(Medium.WIFI_DIRECT))
                                .toByteArray(),
                        )

                        val upgradedWire = FramedConnection(directUpgradePair.serverProvider.acceptUpgrade()!!)
                        val clientIntro = OfflineFrame.parseFrom(upgradedWire.receiveFrame())
                        assertThat(clientIntro.v1.bandwidthUpgradeNegotiation.eventType)
                            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
                        assertThat(clientIntro.v1.bandwidthUpgradeNegotiation.clientIntroduction.endpointId)
                            .isEqualTo(endpointId)
                        upgradedWire.sendFrame(BandwidthUpgradeFrames.clientIntroductionAck().toByteArray())
                        completeRawPriorChannelClose(oldWire)

                        val ukey2ClientInit = Ukey2Message.parseFrom(upgradedWire.receiveFrame())
                        assertThat(ukey2ClientInit.messageType).isEqualTo(Ukey2Message.Type.CLIENT_INIT)

                        upgradedWire.close()
                        assertThat(outboundJob.await()).isInstanceOf(OutboundResult.Failed::class.java)
                    }

                    assertThat(logs).contains("medium-upgrade: pre-UKEY2 client completed WIFI_DIRECT")
                } finally {
                    runCatching { oldWire.close() }
                    directUpgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected BLE bootstrap sends ClientInit when no pre-UKEY2 offer arrives`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val directUpgradePair = LoopbackUpgradePair(Medium.WIFI_DIRECT)
                val logs = mutableListOf<String>()
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        secureRandom = SecureRandom("outbound-ble-no-offer-clientinit".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        directUpgradePair.clientProvider,
                                        SupportedProvider(Medium.BLE),
                                    ),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                        logger = logs::add,
                    )
                val oldWire = FramedConnection(server.asConnectedTransport(Medium.BLE))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(emptyList()) }
                        // Peer reads ConnectionRequest and, like a stock GMS
                        // receiver, sends NO pre-UKEY2 upgrade offer.
                        assertThat(OfflineFrame.parseFrom(oldWire.receiveFrame()).isConnectionRequest()).isTrue()
                        // Issue #216: rather than stalling until the receiver
                        // drops the link, the sender must give up waiting for an
                        // offer and send ClientInit on the BLE medium.
                        val next = Ukey2Message.parseFrom(oldWire.receiveFrame())
                        assertThat(next.messageType).isEqualTo(Ukey2Message.Type.CLIENT_INIT)

                        oldWire.close()
                        assertThat(outboundJob.await()).isInstanceOf(OutboundResult.Failed::class.java)
                    }

                    assertThat(logs).contains("medium-upgrade: no pre-UKEY2 upgrade offer; continuing on BLE")
                } finally {
                    runCatching { oldWire.close() }
                    directUpgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected BLE bootstrap times out when pre-UKEY2 peer stays silent`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val directUpgradePair = LoopbackUpgradePair(Medium.WIFI_DIRECT)
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        secureRandom = SecureRandom("outbound-ble-pre-ukey2-silent".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        directUpgradePair.clientProvider,
                                        SupportedProvider(Medium.BLE),
                                    ),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                    )
                val oldWire = FramedConnection(server.asConnectedTransport(Medium.BLE))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(emptyList()) }
                        assertThat(OfflineFrame.parseFrom(oldWire.receiveFrame()).isConnectionRequest()).isTrue()
                        val result = outboundJob.await()

                        assertThat(result).isInstanceOf(OutboundResult.Failed::class.java)
                        assertThat((result as OutboundResult.Failed).reason)
                            .contains("Initial handshake timed out")
                    }
                } finally {
                    runCatching { oldWire.close() }
                    directUpgradePair.close()
                }
            }
        }

    @Test
    fun `preconnected BLE bootstrap request advertises LAN marker BLE and Wi-Fi Direct`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val directUpgradePair = LoopbackUpgradePair(Medium.WIFI_DIRECT)
                val endpointInfo = customEndpointInfo("Bada177Lab")
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.BLE),
                        endpointInfo = endpointInfo.serialize(),
                        secureRandom = SecureRandom("outbound-ble-bootstrap-request".toByteArray()),
                        mediumRegistry =
                            MediumRegistry(
                                providers =
                                    listOf(
                                        MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                        directUpgradePair.clientProvider,
                                        SupportedProvider(Medium.BLE),
                                    ),
                                ladder = MediumLadder(listOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLE)),
                            ),
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                    )
                val wire = FramedConnection(server.asConnectedTransport(Medium.BLE))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(emptyList()) }
                        val request =
                            OfflineFrame
                                .parseFrom(wire.receiveFrame())
                                .v1
                                .connectionRequest

                        assertThat(request.mediumsList.map { it.number })
                            .containsExactly(
                                Medium.BLE.wireNumber,
                                Medium.WIFI_LAN.wireNumber,
                                Medium.WIFI_DIRECT.wireNumber,
                            ).inOrder()
                        assertThat(EndpointInfo.parse(request.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)
                        assertThat(EndpointInfo.parse(request.connectionsDevice.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)
                        assertThat(request.mediumMetadata.supportedWifiDirectAuthTypesList)
                            .containsExactly(MediumMetadata.WifiDirectAuthType.WIFI_DIRECT_WITH_PASSWORD)
                        assertThat(request.mediumMetadata.hasMediumRole()).isFalse()

                        wire.close()
                        assertThat(outboundJob.await()).isInstanceOf(OutboundResult.Failed::class.java)
                    }
                } finally {
                    runCatching { wire.close() }
                    directUpgradePair.close()
                }
            }
        }

    @Test
    fun `same Wi-Fi raw LAN bootstrap advertises LAN only and preserves custom sender device name`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (client, server) = connectedSocketPair()
                val endpointInfo = customEndpointInfo("BadaSameWifi")
                val logs = mutableListOf<String>()
                val registry =
                    MediumRegistry(
                        providers =
                            listOf(
                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                SupportedProvider(Medium.WIFI_DIRECT),
                                SupportedProvider(Medium.WIFI_HOTSPOT),
                                SupportedProvider(Medium.BLE),
                                SupportedProvider(Medium.BLE_L2CAP),
                            ),
                    )
                val outbound =
                    OutboundConnection(
                        transport = client.asConnectedTransport(Medium.WIFI_LAN),
                        endpointInfo = endpointInfo.serialize(),
                        secureRandom = SecureRandom("outbound-lan-custom-name-request".toByteArray()),
                        mediumRegistry = registry,
                        logger = logs::add,
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                    )
                val wire = FramedConnection(server.asConnectedTransport(Medium.WIFI_LAN))

                try {
                    coroutineScope {
                        val outboundJob = async { outbound.run(emptyList()) }
                        val request =
                            OfflineFrame
                                .parseFrom(wire.receiveFrame())
                                .v1
                                .connectionRequest

                        assertThat(request.mediumsList.map { it.number })
                            .containsExactly(Medium.WIFI_LAN.wireNumber)
                        assertThat(EndpointInfo.parse(request.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)
                        assertThat(EndpointInfo.parse(request.connectionsDevice.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)

                        wire.close()
                        assertThat(outboundJob.await()).isInstanceOf(OutboundResult.Failed::class.java)
                    }

                    assertThat(logs).doesNotContain("medium-upgrade: probing for pre-UKEY2 BLE upgrade offer")
                    assertThat(logs).contains("step 1: advertising mediums=[WIFI_LAN]")
                } finally {
                    runCatching { wire.close() }
                }
            }
        }

    @Test
    fun `same Wi-Fi multiplex LAN bootstrap advertises Wi-Fi only and preserves custom sender device name`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val (port, accept) = listenAndAcceptInBackground()
                val endpointInfo = customEndpointInfo("BadaSameWifi")
                val logs = mutableListOf<String>()
                val registry =
                    MediumRegistry(
                        providers =
                            listOf(
                                MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)!!,
                                SupportedProvider(Medium.WIFI_DIRECT),
                                SupportedProvider(Medium.WIFI_HOTSPOT),
                                SupportedProvider(Medium.BLE),
                                SupportedProvider(Medium.BLE_L2CAP),
                            ),
                    )
                val outbound =
                    OutboundConnection(
                        targetAddress = InetAddress.getLoopbackAddress(),
                        port = port,
                        endpointInfo = endpointInfo.serialize(),
                        secureRandom = SecureRandom("outbound-lan-multiplex-request".toByteArray()),
                        mediumRegistry = registry,
                        logger = logs::add,
                        useNearbyMultiplexInitialTransport = true,
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                    )

                coroutineScope {
                    val outboundJob = async { outbound.run(emptyList()) }
                    val physical = accept().asConnectedTransport(Medium.WIFI_LAN)
                    val virtual =
                        withContext(Dispatchers.IO) {
                            probeNearbyMultiplexServerTransport(physical, logs::add)
                        }
                    val wire = FramedConnection(virtual)
                    try {
                        val request =
                            OfflineFrame
                                .parseFrom(wire.receiveFrame())
                                .v1
                                .connectionRequest

                        assertThat(request.mediumsList.map { it.number })
                            .containsExactly(
                                Medium.WIFI_LAN.wireNumber,
                            ).inOrder()
                        assertThat(EndpointInfo.parse(request.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)
                        assertThat(EndpointInfo.parse(request.connectionsDevice.endpointInfo.toByteArray()))
                            .isEqualTo(endpointInfo)

                        wire.close()
                        assertThat(outboundJob.await()).isInstanceOf(OutboundResult.Failed::class.java)
                    } finally {
                        runCatching { wire.close() }
                    }
                }

                assertThat(logs).contains("step 0: Nearby multiplex virtual socket opened over WIFI_LAN")
                assertThat(logs).contains("step 1: advertising mediums=[WIFI_LAN]")
                assertThat(logs).contains("nearby-multiplex: LAN socket using multiplex stream")
            }
        }

    private suspend fun completeRawPriorChannelClose(oldWire: FramedConnection) {
        val clientLastWrite = OfflineFrame.parseFrom(oldWire.receiveFrame())
        assertThat(clientLastWrite.v1.bandwidthUpgradeNegotiation.eventType)
            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL)

        oldWire.sendFrame(BandwidthUpgradeFrames.lastWriteToPriorChannel().toByteArray())

        val clientSafeToClose = OfflineFrame.parseFrom(oldWire.receiveFrame())
        assertThat(clientSafeToClose.v1.bandwidthUpgradeNegotiation.eventType)
            .isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL)

        oldWire.sendFrame(BandwidthUpgradeFrames.safeToClosePriorChannel().toByteArray())
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
    fun `initial handshake timeout closes a silent preconnected transport`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val transport = HangingConnectedTransport()
                val logs = mutableListOf<String>()
                val outbound =
                    OutboundConnection(
                        transport = transport,
                        initialHandshakeTimeoutMillis = SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS,
                        secureRandom = SecureRandom("outbound-initial-timeout".toByteArray()),
                        logger = logs::add,
                    )

                val result = outbound.run(emptyList())
                val failedState = outbound.state.value as OutboundConnectionState.Failed

                assertThat(result).isInstanceOf(OutboundResult.Failed::class.java)
                assertThat(failedState.reason).contains("Initial handshake timed out")
                assertThat(transport.closed).isTrue()
                assertThat(logs.any { it.contains("initial handshake timed out") }).isTrue()
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

    private fun assertWifiDirectMetadata(
        outbound: OutboundConnection,
        inbound: InboundConnection,
        frequencyMhz: Int?,
    ) {
        assertThat(inbound.activeMedium.value).isEqualTo(Medium.WIFI_DIRECT)
        assertThat(outbound.activeMedium.value).isEqualTo(Medium.WIFI_DIRECT)
        assertThat(inbound.activeWifiFrequencyMhz.value).isEqualTo(frequencyMhz)
        assertThat(outbound.activeWifiFrequencyMhz.value).isEqualTo(frequencyMhz)
    }

    private companion object {
        private const val WIFI_DIRECT_TEST_FREQUENCY_MHZ: Int = 5_180

        // Hard wall-clock cap for individual short tests. Real Socket I/O is
        // blocking, so we use [withTimeout] (not runTest's virtual scheduler)
        // to bound each scenario. 30 s leaves plenty of slack on slow CI.
        private const val WALLCLOCK_TIMEOUT_MS: Long = 30_000L

        // Long-running tests (multi-file, multi-MiB) get a generous cap; the
        // 1.5 MiB scenario is still expected to complete in a couple seconds
        // on a modern dev machine.
        private const val LONG_WALLCLOCK_TIMEOUT_MS: Long = 60_000L

        private const val SHORT_INITIAL_HANDSHAKE_TIMEOUT_MS: Long = 50L

        private val MediumLadderForBluetoothFirst: MediumLadder =
            MediumLadder(listOf(Medium.BLUETOOTH, Medium.WIFI_LAN))
    }
}
