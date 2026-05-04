/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

import dev.bluehouse.libredrop.protocol.connection.InboundConnection
import dev.bluehouse.libredrop.protocol.connection.InboundConnectionState
import dev.bluehouse.libredrop.protocol.connection.TransferMetadata
import dev.bluehouse.libredrop.protocol.server.InboundConnectionCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges
 * [dev.bluehouse.libredrop.service.receiver.ReceiverSession] events to
 * the consent notification surface.
 *
 * ### Responsibilities
 *
 *  1. Subscribe to the session's `activeConnections` flow. Each new
 *     [InboundConnection] gets a fresh observation coroutine that
 *     watches its [InboundConnection.state] for the
 *     `WaitingForUserConsent` transition.
 *  2. On `WaitingForUserConsent`, register the connection in
 *     [ConsentRegistry] and ask the [Sink] to post a consent
 *     notification.
 *  3. On any terminal state — Receiving (the user already accepted),
 *     Rejected, Cancelled, Failed, Completed — unregister the entry
 *     and ask the [Sink] to dismiss the notification.
 *  4. On the session's `results` flow (which fires the final
 *     completion), idempotently dismiss in case the per-connection
 *     state observer was cancelled before reaching a terminal state.
 *
 * ### Why a `Sink` interface
 *
 * The actual notification posting needs an Android `Context`; the
 * coordinator's responsibility is purely the choreography. Pulling
 * the side-effects out behind [Sink] keeps the coordinator on plain
 * JVM and gives unit tests a clean assertion target ("a Post was
 * recorded for connectionId=42 with the right metadata").
 *
 * ### Lifecycle
 *
 * Constructed by the foreground service and started with [start];
 * stopped with [stop]. The coordinator launches its own coroutines
 * under the supplied [scope] but never owns the scope itself.
 */
@Suppress("LongParameterList") // Constructor is plumbing for the test seam; each parameter is a discrete collaborator.
public class ConsentCoordinator(
    private val activeConnections: SharedFlow<InboundConnection>,
    private val results: SharedFlow<InboundConnectionCompletion>,
    private val registry: ConsentRegistry,
    private val sink: Sink,
    private val scope: CoroutineScope,
    private val connectionIdProvider: () -> Long = MonotonicConnectionIds::next,
    private val stateExtractor: (InboundConnection) -> StateFlow<InboundConnectionState> = { it.state },
    private val consentSubmitter: (InboundConnection) -> ((Boolean) -> Unit) = { conn -> conn::submitUserConsent },
) {
    private val idForConnection: MutableMap<InboundConnection, Long> = HashMap()
    private val idLock = Any()
    private var runJob: Job? = null

    /**
     * Start observing. Idempotent against duplicate calls; subsequent
     * invocations are no-ops while the prior observation is alive.
     */
    public fun start() {
        if (runJob != null) return
        runJob =
            scope.launch {
                launch { observeActiveConnections() }
                launch { observeResults() }
            }
    }

    /**
     * Stop observing. Cancels the inner coroutines but does not touch
     * already-registered entries — the foreground service's stop path
     * tears those down explicitly so the registry stays consistent
     * across coordinator restarts.
     */
    public fun stop() {
        runJob?.cancel()
        runJob = null
    }

    /**
     * Per-active-connection observation loop. We pin a unique
     * connection id at observation time (rather than reading
     * [InboundConnectionCompletion.connectionId] post-hoc) so the
     * notification posted for a `WaitingForUserConsent` state can be
     * dismissed even if the connection terminates without producing a
     * completion entry (e.g. a cancellation that beats the
     * results-flow emission).
     */
    private suspend fun observeActiveConnections() {
        activeConnections.collectLatest { connection ->
            val connectionId =
                synchronized(idLock) {
                    idForConnection.getOrPut(connection, connectionIdProvider)
                }
            scope.launch { observeOneConnection(connection, connectionId) }
        }
    }

    private suspend fun observeOneConnection(
        connection: InboundConnection,
        connectionId: Long,
    ) {
        var posted = false
        // StateFlow already deduplicates equal consecutive emissions —
        // we just need a `takeWhile` that closes the loop after a
        // terminal state has been observed (and stops re-entering on
        // the StateFlow's stable terminal value).
        stateExtractor(connection)
            .takeWhile { state -> state !is InboundConnectionState.Idle || !posted }
            .collect { state ->
                when (state) {
                    is InboundConnectionState.WaitingForUserConsent -> {
                        if (!posted) {
                            posted = true
                            val entry = entryFor(connection, state.metadata)
                            registry.register(connectionId, entry)
                            sink.postConsent(connectionId, entry)
                        }
                    }
                    is InboundConnectionState.Receiving,
                    is InboundConnectionState.Rejected,
                    is InboundConnectionState.Cancelled,
                    is InboundConnectionState.Failed,
                    is InboundConnectionState.Completed,
                    -> {
                        if (posted) {
                            registry.unregister(connectionId)
                            sink.dismissConsent(connectionId)
                        }
                    }
                    else -> Unit
                }
            }
    }

    /**
     * Belt-and-braces: when a [InboundConnectionCompletion] flows in,
     * make sure the corresponding consent entry is gone. The
     * per-connection observer above usually already handled it; this
     * loop catches the case where the observer was racing the
     * completion emission.
     */
    private suspend fun observeResults() {
        results.collect { completion ->
            val connectionId =
                synchronized(idLock) {
                    idForConnection.remove(completion.connection)
                } ?: return@collect
            registry.unregister(connectionId)
            sink.dismissConsent(connectionId)
        }
    }

    private fun entryFor(
        connection: InboundConnection,
        metadata: TransferMetadata,
    ): ConsentRegistry.Entry =
        ConsentRegistry.Entry(
            connection = connection,
            sourceDeviceName = metadata.sourceDeviceName,
            pin = metadata.pin,
            itemCount = metadata.items.size,
            totalSize = metadata.totalSize,
            items = metadata.items,
            submitConsent = consentSubmitter(connection),
        )

    /**
     * Side-effect surface for the coordinator. Production wires this
     * to [ConsentNotification.post] / [ConsentNotification.dismiss];
     * tests use a recording fake.
     */
    public interface Sink {
        public fun postConsent(
            connectionId: Long,
            entry: ConsentRegistry.Entry,
        )

        public fun dismissConsent(connectionId: Long)
    }
}

/**
 * Process-monotonic source of consent connection ids. We mint our own
 * (rather than waiting for the
 * [dev.bluehouse.libredrop.protocol.server.TcpReceiverServer]'s
 * post-hoc id) because we need an id at the moment the consent
 * registers, which is before the per-connection result is in flight.
 *
 * Lifted to a top-level singleton so the foreground service and a
 * standalone test can share counter semantics without touching the
 * private TCP server's counter.
 */
internal object MonotonicConnectionIds {
    private val counter = AtomicLong(1)

    fun next(): Long = counter.getAndIncrement()
}
