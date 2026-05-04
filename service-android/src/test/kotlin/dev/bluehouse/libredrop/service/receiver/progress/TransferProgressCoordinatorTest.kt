/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.bluehouse.libredrop.service.receiver.progress

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import dev.bluehouse.libredrop.protocol.connection.CancelCause
import dev.bluehouse.libredrop.protocol.connection.InboundConnection
import dev.bluehouse.libredrop.protocol.connection.InboundConnectionState
import dev.bluehouse.libredrop.protocol.connection.TransferItem
import dev.bluehouse.libredrop.protocol.connection.TransferMetadata
import dev.bluehouse.libredrop.protocol.connection.TransferProgress
import dev.bluehouse.libredrop.protocol.server.InboundConnectionCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM tests for [TransferProgressCoordinator]. Mirrors the shape
 * of `ConsentCoordinatorTest` so the two coordinators behave
 * symmetrically under the same harness pattern.
 *
 * The coordinator is the choreography that surfaces the in-flight
 * progress notification (#46). We exercise:
 *
 *  - first `Receiving` post,
 *  - dismiss on terminal,
 *  - cancel-callback registration,
 *  - cancel-callback unregistration on terminal.
 *
 * The 500 ms throttle is overridden to 1 ms in the harness so the
 * test does not depend on wall-clock pacing — the throttle's
 * boundary semantics are exercised separately via the in-process
 * clock.
 */
class TransferProgressCoordinatorTest {
    @Test
    fun `posts a progress notification when connection enters Receiving`() =
        runTest {
            val harness = harness(idProvider = { 100L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()

            assertThat(harness.sink.posted).hasSize(1)
            assertThat(
                harness.sink.posted
                    .single()
                    .connectionId,
            ).isEqualTo(100L)
            assertThat(
                harness.sink.posted
                    .single()
                    .sourceDeviceName,
            ).isEqualTo("Pixel 8")

            harness.close()
        }

    @Test
    fun `dismisses progress when connection completes`() =
        runTest {
            val harness = harness(idProvider = { 50L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).isEmpty()

            harness.transitionTo(connection, InboundConnectionState.Completed(items = emptyList()))
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).containsExactly(50L)
            harness.close()
        }

    @Test
    fun `dismisses progress when connection fails`() =
        runTest {
            val harness = harness(idProvider = { 7L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()
            harness.transitionTo(connection, InboundConnectionState.Failed(reason = "I/O"))
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).containsExactly(7L)
            harness.close()
        }

    @Test
    fun `dismisses progress when connection is cancelled by peer`() =
        runTest {
            val harness = harness(idProvider = { 8L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.Cancelled(cause = CancelCause.PEER),
            )
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).containsExactly(8L)
            harness.close()
        }

    @Test
    fun `registers a cancel callback on first Receiving post`() =
        runTest {
            val harness = harness(idProvider = { 42L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()

            assertThat(harness.sink.cancelsRegistered).hasSize(1)
            assertThat(
                harness.sink.cancelsRegistered
                    .single()
                    .connectionId,
            ).isEqualTo(42L)

            harness.close()
        }

    @Test
    fun `unregisters cancel callback on terminal state`() =
        runTest {
            val harness = harness(idProvider = { 11L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()
            harness.transitionTo(connection, InboundConnectionState.Completed(items = emptyList()))
            advanceUntilIdle()

            assertThat(harness.sink.cancelsUnregistered).contains(11L)
            harness.close()
        }

    @Test
    fun `dismisses on results flow completion as belt-and-braces`() =
        runTest {
            val harness = harness(idProvider = { 99L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(connection, waitingState())
            advanceUntilIdle()
            harness.transitionTo(connection, receivingState())
            advanceUntilIdle()

            // Simulate the TcpReceiverServer.results flow firing a
            // completion in parallel with the per-connection state
            // observer (the realistic race the belt-and-braces is
            // there for).
            harness.results.tryEmit(
                InboundConnectionCompletion(
                    connectionId = 99L,
                    connection = connection,
                    result =
                        dev.bluehouse.libredrop.protocol.connection.InboundResult.Completed(
                            items = emptyList(),
                        ),
                ),
            )
            advanceUntilIdle()

            // The dismiss path runs at least once. We tolerate the
            // duplicate from the per-connection observer (it is a
            // no-op at the platform NotificationManager layer).
            assertThat(harness.sink.dismissed).contains(99L)
            harness.close()
        }

    private fun waitingState(): InboundConnectionState.WaitingForUserConsent =
        InboundConnectionState.WaitingForUserConsent(
            metadata =
                TransferMetadata(
                    items =
                        listOf(
                            TransferItem.File(1L, "a.bin", 100L, "application/octet-stream"),
                        ),
                    pin = "1234",
                    sourceDeviceName = "Pixel 8",
                ),
        )

    private fun receivingState(): InboundConnectionState.Receiving =
        InboundConnectionState.Receiving(
            progress =
                TransferProgress(
                    bytesTransferred = 50L,
                    totalSize = 100L,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                ),
            currentItemPayloadId = 1L,
            currentItemType = PayloadHeader.PayloadType.FILE,
        )

    private fun harness(idProvider: () -> Long = AtomicLong(1)::getAndIncrement): Harness = Harness(idProvider)

    /**
     * Per-test fixture wiring up the coordinator against in-memory
     * fakes. Mirrors `ConsentCoordinatorTest.Harness` so the two
     * tests stay easy to read side by side.
     */
    private class Harness(
        idProvider: () -> Long,
    ) {
        val activeConnections: MutableSharedFlow<InboundConnection> =
            MutableSharedFlow(extraBufferCapacity = 8)
        val results: MutableSharedFlow<InboundConnectionCompletion> =
            MutableSharedFlow(extraBufferCapacity = 8)
        val sink: RecordingSink = RecordingSink()
        private val flows: IdentityHashMap<InboundConnection, MutableStateFlow<InboundConnectionState>> =
            IdentityHashMap()
        private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val coordinator: TransferProgressCoordinator =
            TransferProgressCoordinator(
                activeConnections = activeConnections.asSharedFlow(),
                results = results.asSharedFlow(),
                sink = sink,
                scope = scope,
                connectionIdProvider = idProvider,
                stateExtractor = { conn -> stateFor(conn) },
                cancelInvoker = { _ -> { /* no-op */ } },
                // Drop the throttle so each Receiving emission posts.
                postIntervalMillis = 0L,
            )

        fun start() {
            coordinator.start()
        }

        fun newConnection(): InboundConnection = InboundConnection(socket = Socket())

        fun transitionTo(
            connection: InboundConnection,
            state: InboundConnectionState,
        ) {
            mutableStateFor(connection).value = state
        }

        fun close() {
            coordinator.stop()
            scope.cancel()
        }

        private fun stateFor(connection: InboundConnection): StateFlow<InboundConnectionState> =
            mutableStateFor(connection).asStateFlow()

        private fun mutableStateFor(connection: InboundConnection): MutableStateFlow<InboundConnectionState> =
            synchronized(flows) {
                flows.getOrPut(connection) {
                    MutableStateFlow(InboundConnectionState.Idle)
                }
            }
    }

    /**
     * In-memory [TransferProgressCoordinator.Sink] capturing every
     * post / dismiss / cancel-registration call in order.
     */
    private class RecordingSink : TransferProgressCoordinator.Sink {
        data class Post(
            val connectionId: Long,
            val sourceDeviceName: String?,
            val progress: TransferProgress,
        )

        data class CancelRegistration(
            val connectionId: Long,
            val onCancel: () -> Unit,
        )

        val posted: MutableList<Post> = mutableListOf()
        val dismissed: MutableList<Long> = mutableListOf()
        val cancelsRegistered: MutableList<CancelRegistration> = mutableListOf()
        val cancelsUnregistered: MutableList<Long> = mutableListOf()

        override fun postProgress(
            connectionId: Long,
            sourceDeviceName: String?,
            progress: TransferProgress,
        ) {
            posted += Post(connectionId, sourceDeviceName, progress)
        }

        override fun dismissProgress(connectionId: Long) {
            dismissed += connectionId
        }

        override fun registerCancel(
            connectionId: Long,
            onCancel: () -> Unit,
        ) {
            cancelsRegistered += CancelRegistration(connectionId, onCancel)
        }

        override fun unregisterCancel(connectionId: Long) {
            cancelsUnregistered += connectionId
        }
    }
}
