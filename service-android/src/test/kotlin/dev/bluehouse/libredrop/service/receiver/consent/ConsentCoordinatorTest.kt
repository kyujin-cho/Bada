/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.bluehouse.libredrop.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.connection.CancelCause
import dev.bluehouse.libredrop.protocol.connection.InboundConnection
import dev.bluehouse.libredrop.protocol.connection.InboundConnectionState
import dev.bluehouse.libredrop.protocol.connection.TransferItem
import dev.bluehouse.libredrop.protocol.connection.TransferMetadata
import dev.bluehouse.libredrop.protocol.connection.TransferProgress
import dev.bluehouse.libredrop.protocol.server.InboundConnectionCompletion
import dev.bluehouse.libredrop.service.receiver.foreground.InMemoryAppForegroundState
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
 * Pure-JVM tests for [ConsentCoordinator].
 *
 * The coordinator is the choreography that ties the receiver session
 * to the consent notification surface. We exercise it through:
 *
 *  - real (unstarted) [InboundConnection] instances as identity
 *    handles,
 *  - a per-test [MutableStateFlow] keyed by connection that supplies
 *    the state used by the coordinator (injected via the
 *    `stateExtractor` constructor parameter so we don't need to
 *    drive a real lifecycle),
 *  - an in-memory [RecordingSink] capturing every post / dismiss,
 *  - hand-rolled [SharedFlow]s for the session's
 *    `activeConnections` and `results` channels.
 */
class ConsentCoordinatorTest {
    @Test
    fun `posts a consent notification when connection enters WaitingForUserConsent`() =
        runTest {
            val harness = harness(idProvider = { 100L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(
                    metadata =
                        TransferMetadata(
                            items =
                                listOf(
                                    TransferItem.File(1, "a.bin", 100, "application/octet-stream"),
                                ),
                            pin = "1234",
                            sourceDeviceName = "Pixel 8",
                        ),
                ),
            )
            advanceUntilIdle()

            assertThat(harness.sink.posted).hasSize(1)
            val post = harness.sink.posted.single()
            assertThat(post.connectionId).isEqualTo(100L)
            assertThat(post.entry.sourceDeviceName).isEqualTo("Pixel 8")
            assertThat(post.entry.pin).isEqualTo("1234")
            assertThat(post.entry.itemCount).isEqualTo(1)
            assertThat(harness.registry.lookup(100L)).isNotNull()

            harness.close()
        }

    @Test
    fun `dismisses when connection transitions out of WaitingForUserConsent`() =
        runTest {
            val harness = harness(idProvider = { 50L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()

            harness.transitionTo(connection, InboundConnectionState.Rejected)
            advanceUntilIdle()

            assertThat(harness.sink.posted).hasSize(1)
            assertThat(harness.sink.dismissed).containsExactly(50L)
            assertThat(harness.registry.lookup(50L)).isNull()

            harness.close()
        }

    @Test
    fun `does not post when connection skips WaitingForUserConsent`() =
        runTest {
            val harness = harness()
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            // Failure before consent is reached — coordinator must not
            // post anything for this connection.
            harness.transitionTo(connection, InboundConnectionState.Failed("UKEY2 BAD_VERSION"))
            advanceUntilIdle()

            assertThat(harness.sink.posted).isEmpty()
            assertThat(harness.sink.dismissed).isEmpty()

            harness.close()
        }

    @Test
    fun `terminal cancellation after consent dismisses the notification`() =
        runTest {
            val harness = harness(idProvider = { 7L })
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()

            harness.transitionTo(connection, InboundConnectionState.Cancelled(CancelCause.PEER))
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).containsExactly(7L)
            assertThat(harness.registry.lookup(7L)).isNull()

            harness.close()
        }

    @Test
    fun `does not double-post when WaitingForUserConsent state is re-emitted`() =
        runTest {
            val harness = harness()
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            val md1 = sampleMetadata(pin = "1111")
            val md2 = sampleMetadata(pin = "2222")
            // Two consent emissions back-to-back. The contract is: the
            // first one posts, the second is suppressed because the
            // notification is still up.
            harness.transitionTo(connection, InboundConnectionState.WaitingForUserConsent(md1))
            advanceUntilIdle()
            harness.transitionTo(connection, InboundConnectionState.WaitingForUserConsent(md2))
            advanceUntilIdle()

            assertThat(harness.sink.posted).hasSize(1)
            assertThat(
                harness.sink.posted
                    .single()
                    .entry.pin,
            ).isEqualTo("1111")

            harness.close()
        }

    @Test
    fun `entry's submitConsent forwards to the injected consentSubmitter`() =
        runTest {
            val recorded = mutableListOf<Boolean>()
            val harness =
                harness(
                    idProvider = { 1L },
                    consentSubmitter = { _ -> { accepted -> recorded += accepted } },
                )
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()

            // Simulate the broadcast receiver path: it pulls the entry
            // out of the registry and calls submitConsent.
            val entry = harness.registry.lookup(1L)!!
            entry.submitConsent(true)
            assertThat(recorded).containsExactly(true)

            harness.close()
        }

    @Test
    fun `foreground arrival launches the modal instead of posting a notification`() =
        runTest {
            val harness = harness(idProvider = { 11L }, initialForeground = true)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()

            assertThat(harness.sink.posted).isEmpty()
            assertThat(harness.sink.modalLaunches).hasSize(1)
            assertThat(
                harness.sink.modalLaunches
                    .single()
                    .connectionId,
            ).isEqualTo(11L)
            assertThat(harness.registry.lookup(11L)).isNotNull()

            harness.close()
        }

    @Test
    fun `background arrival posts a notification instead of launching the modal`() =
        runTest {
            val harness = harness(idProvider = { 12L }, initialForeground = false)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()

            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()

            assertThat(harness.sink.modalLaunches).isEmpty()
            assertThat(harness.sink.posted).hasSize(1)
            assertThat(
                harness.sink.posted
                    .single()
                    .connectionId,
            ).isEqualTo(12L)

            harness.close()
        }

    @Test
    fun `backgrounding while a modal is up swaps to the notification surface`() =
        runTest {
            val harness = harness(idProvider = { 21L }, initialForeground = true)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()
            assertThat(harness.sink.modalLaunches).hasSize(1)
            assertThat(harness.sink.posted).isEmpty()

            // User backgrounds LibreDrop without making a decision. The
            // coordinator must dismiss the modal and raise the
            // heads-up so the user can resume from the shade.
            harness.foreground.set(false)
            advanceUntilIdle()

            assertThat(harness.sink.modalDismissals).containsExactly(21L)
            assertThat(harness.sink.posted).hasSize(1)
            assertThat(
                harness.sink.posted
                    .single()
                    .connectionId,
            ).isEqualTo(21L)
            // The notification dismiss must NOT have been called for
            // this id — there was nothing to dismiss yet.
            assertThat(harness.sink.dismissed).doesNotContain(21L)

            harness.close()
        }

    @Test
    fun `foregrounding while a notification is up swaps to the modal surface`() =
        runTest {
            val harness = harness(idProvider = { 22L }, initialForeground = false)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()
            assertThat(harness.sink.posted).hasSize(1)
            assertThat(harness.sink.modalLaunches).isEmpty()

            // User foregrounds LibreDrop with the notification still
            // pending. The coordinator must cancel the heads-up and
            // launch the modal.
            harness.foreground.set(true)
            advanceUntilIdle()

            assertThat(harness.sink.dismissed).containsExactly(22L)
            assertThat(harness.sink.modalLaunches).hasSize(1)
            assertThat(
                harness.sink.modalLaunches
                    .single()
                    .connectionId,
            ).isEqualTo(22L)
            // The modal dismiss must NOT have been called — there was
            // no modal to dismiss when we transitioned out of the
            // notification surface.
            assertThat(harness.sink.modalDismissals).doesNotContain(22L)

            harness.close()
        }

    @Test
    fun `decided connection is not re-surfaced by foreground or background flips`() =
        runTest {
            // This is the per-connection-id scoping requirement from
            // the issue: a notification for an unrelated, already-
            // decided connection B must not be re-raised when the user
            // backgrounds LibreDrop with a separate live consent A
            // still pending.
            val ids = ArrayDeque(listOf(101L, 102L))
            val harness =
                harness(
                    idProvider = { ids.removeFirst() },
                    initialForeground = true,
                )
            harness.start()

            val connectionB = harness.newConnection()
            harness.activeConnections.tryEmit(connectionB)
            advanceUntilIdle()
            harness.transitionTo(
                connectionB,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata("8888")),
            )
            advanceUntilIdle()
            // B's modal is up.
            assertThat(harness.sink.modalLaunches.map { it.connectionId }).containsExactly(101L)

            // User accepts B (terminal -> Receiving). Coordinator
            // marks 101 as Decided and dismisses the modal.
            harness.transitionTo(
                connectionB,
                InboundConnectionState.Receiving(
                    progress = sampleProgress(),
                    currentItemPayloadId = null,
                    currentItemType = null,
                ),
            )
            advanceUntilIdle()
            assertThat(harness.sink.modalDismissals).containsExactly(101L)

            // Reset capture lists so the next assertions are precise.
            harness.sink.modalLaunches.clear()
            harness.sink.modalDismissals.clear()
            harness.sink.posted.clear()
            harness.sink.dismissed.clear()

            // Now connection A arrives while LibreDrop is foregrounded
            // — its modal is up. Then the user backgrounds. Only A
            // should swap to a notification; B is decided and must
            // stay silent.
            val connectionA = harness.newConnection()
            harness.activeConnections.tryEmit(connectionA)
            advanceUntilIdle()
            harness.transitionTo(
                connectionA,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata("9999")),
            )
            advanceUntilIdle()
            assertThat(harness.sink.modalLaunches.map { it.connectionId }).containsExactly(102L)

            harness.foreground.set(false)
            advanceUntilIdle()

            // Only A (id=102) is re-surfaced.
            assertThat(harness.sink.posted.map { it.connectionId }).containsExactly(102L)
            assertThat(harness.sink.modalDismissals).containsExactly(102L)
            // B (id=101) must NOT be re-raised by the background
            // transition — it was already decided.
            assertThat(harness.sink.posted.none { it.connectionId == 101L }).isTrue()
            assertThat(harness.sink.dismissed.none { it == 101L }).isTrue()

            harness.close()
        }

    @Test
    fun `terminal state after modal dismisses the modal surface`() =
        runTest {
            val harness = harness(idProvider = { 33L }, initialForeground = true)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()
            assertThat(harness.sink.modalLaunches).hasSize(1)

            // User accepts via the modal -> Receiving.
            harness.transitionTo(
                connection,
                InboundConnectionState.Receiving(
                    progress = sampleProgress(),
                    currentItemPayloadId = null,
                    currentItemType = null,
                ),
            )
            advanceUntilIdle()

            assertThat(harness.sink.modalDismissals).containsExactly(33L)
            // Notification path was never the active surface; do NOT
            // call its dismiss.
            assertThat(harness.sink.dismissed).isEmpty()

            harness.close()
        }

    @Test
    fun `repeated foreground transitions while modal is up are no-ops`() =
        runTest {
            val harness = harness(idProvider = { 44L }, initialForeground = true)
            harness.start()

            val connection = harness.newConnection()
            harness.activeConnections.tryEmit(connection)
            advanceUntilIdle()
            harness.transitionTo(
                connection,
                InboundConnectionState.WaitingForUserConsent(metadata = sampleMetadata()),
            )
            advanceUntilIdle()
            assertThat(harness.sink.modalLaunches).hasSize(1)

            // The InMemoryAppForegroundState already coalesces equal
            // sets; this is a defensive double-check that the
            // coordinator does not re-launch the modal even if a
            // listener does fire spuriously.
            harness.foreground.set(true)
            advanceUntilIdle()

            assertThat(harness.sink.modalLaunches).hasSize(1)
            assertThat(harness.sink.dismissed).isEmpty()

            harness.close()
        }

    private fun sampleMetadata(pin: String = "1234"): TransferMetadata =
        TransferMetadata(
            items = listOf(TransferItem.File(1L, "a.bin", 100L, "application/octet-stream")),
            pin = pin,
            sourceDeviceName = "Pixel",
        )

    private fun sampleProgress(): TransferProgress =
        TransferProgress(
            bytesTransferred = 0L,
            totalSize = 100L,
            bytesPerSecond = 0L,
            etaSeconds = null,
        )

    private fun harness(
        idProvider: () -> Long = AtomicLong(1)::getAndIncrement,
        consentSubmitter: (InboundConnection) -> ((Boolean) -> Unit) = { conn -> conn::submitUserConsent },
        initialForeground: Boolean = false,
    ): Harness = Harness(idProvider, consentSubmitter, initialForeground)

    /**
     * Per-test fixture wiring up the coordinator against in-memory
     * fakes. Tracks per-connection [MutableStateFlow]s in an
     * [IdentityHashMap] so the same `InboundConnection` instance
     * always sees the same flow back through the extractor.
     */
    private class Harness(
        idProvider: () -> Long,
        consentSubmitter: (InboundConnection) -> ((Boolean) -> Unit),
        initialForeground: Boolean,
    ) {
        val activeConnections: MutableSharedFlow<InboundConnection> =
            MutableSharedFlow(extraBufferCapacity = 8)
        val results: MutableSharedFlow<InboundConnectionCompletion> =
            MutableSharedFlow(extraBufferCapacity = 8)
        val sink: RecordingSink = RecordingSink()
        val registry: ConsentRegistry = ConsentRegistry()
        val foreground: InMemoryAppForegroundState =
            InMemoryAppForegroundState(initial = initialForeground)
        private val flows: IdentityHashMap<InboundConnection, MutableStateFlow<InboundConnectionState>> =
            IdentityHashMap()
        private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val coordinator: ConsentCoordinator =
            ConsentCoordinator(
                activeConnections = activeConnections.asSharedFlow(),
                results = results.asSharedFlow(),
                registry = registry,
                sink = sink,
                scope = scope,
                appForegroundState = foreground,
                connectionIdProvider = idProvider,
                stateExtractor = { conn -> stateFor(conn) },
                consentSubmitter = consentSubmitter,
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
     * In-memory [ConsentCoordinator.Sink] that records every post /
     * dismiss / launchModal / dismissModal invocation in order. Tests
     * assert against the lists.
     */
    private class RecordingSink : ConsentCoordinator.Sink {
        data class Post(
            val connectionId: Long,
            val entry: ConsentRegistry.Entry,
        )

        val posted: MutableList<Post> = mutableListOf()
        val dismissed: MutableList<Long> = mutableListOf()
        val modalLaunches: MutableList<Post> = mutableListOf()
        val modalDismissals: MutableList<Long> = mutableListOf()

        override fun postConsent(
            connectionId: Long,
            entry: ConsentRegistry.Entry,
        ) {
            posted += Post(connectionId, entry)
        }

        override fun dismissConsent(connectionId: Long) {
            dismissed += connectionId
        }

        override fun launchModal(
            connectionId: Long,
            entry: ConsentRegistry.Entry,
        ) {
            modalLaunches += Post(connectionId, entry)
        }

        override fun dismissModal(connectionId: Long) {
            modalDismissals += connectionId
        }
    }
}
