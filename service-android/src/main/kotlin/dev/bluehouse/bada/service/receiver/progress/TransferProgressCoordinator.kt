/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.progress

import dev.bluehouse.bada.protocol.connection.InboundConnection
import dev.bluehouse.bada.protocol.connection.InboundConnectionState
import dev.bluehouse.bada.protocol.connection.TransferProgress
import dev.bluehouse.bada.protocol.server.InboundConnectionCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the in-flight transfer progress notification surface (#46).
 *
 * Mirrors the shape of
 * [dev.bluehouse.bada.service.receiver.consent.ConsentCoordinator]
 * — subscribe to the receiver session's `activeConnections` flow,
 * spawn a per-connection observation coroutine that watches
 * [InboundConnection.state] for the `Receiving` transition, and
 * surface that as side-effects through a [Sink].
 *
 * ### Throttling
 *
 * The receiver-side payload pipeline can produce a `Receiving`
 * StateFlow update on every 512 KiB chunk — at LAN speeds that fires
 * faster than NotificationManager can render. The coordinator
 * coalesces updates by holding the most recent state and re-posting
 * the notification at most every [POST_INTERVAL_MILLIS] ms (issue
 * #46 acceptance: "updates every ~500 ms"). Terminal transitions
 * (Completed / Failed / Cancelled / Rejected) bypass the throttle so
 * the dismiss path runs immediately.
 *
 * ### Why a `Sink` interface
 *
 * Same rationale as the consent coordinator: the actual notification
 * post / dismiss / cancel-registration calls need an Android
 * `Context`; the coordinator's responsibility is the choreography
 * (when to post vs when to dismiss, what message to render). Pulling
 * the side-effects out behind [Sink] keeps the coordinator on plain
 * JVM and gives unit tests a clean assertion target.
 *
 * ### Lifecycle
 *
 * Constructed by the foreground service and started with [start];
 * stopped with [stop]. The coordinator launches its own coroutines
 * under the supplied [scope] but never owns the scope itself.
 */
@Suppress("LongParameterList") // Constructor is plumbing for the test seam; each parameter is a discrete collaborator.
public class TransferProgressCoordinator(
    private val activeConnections: SharedFlow<InboundConnection>,
    private val results: SharedFlow<InboundConnectionCompletion>,
    private val sink: Sink,
    private val scope: CoroutineScope,
    private val connectionIdProvider: () -> Long = MonotonicProgressIds::next,
    private val stateExtractor: (InboundConnection) -> StateFlow<InboundConnectionState> = { it.state },
    private val cancelInvoker: (InboundConnection) -> (() -> Unit) = { conn -> conn::cancel },
    /**
     * Coalesce-and-post interval in milliseconds. Defaults to
     * [DEFAULT_POST_INTERVAL_MILLIS] (500 ms, matching the issue
     * acceptance criterion). Tests inject a smaller value so the
     * throttle window does not dominate test wall-clock time.
     */
    private val postIntervalMillis: Long = DEFAULT_POST_INTERVAL_MILLIS,
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
     * already-registered cancel callbacks — the foreground service's
     * stop path tears those down explicitly so the registry stays
     * consistent across coordinator restarts.
     */
    public fun stop() {
        runJob?.cancel()
        runJob = null
    }

    private suspend fun observeActiveConnections() {
        activeConnections.collectLatest { connection ->
            val connectionId =
                synchronized(idLock) {
                    idForConnection.getOrPut(connection, connectionIdProvider)
                }
            scope.launch { observeOneConnection(connection, connectionId) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun observeOneConnection(
        connection: InboundConnection,
        connectionId: Long,
    ) {
        var posted = false
        var sourceDeviceName: String? = null
        // Pending snapshot held back by the throttle; null when
        // nothing is pending, non-null when we have a fresh state we
        // have not posted yet.
        var pendingProgress: TransferProgress? = null
        // Last wall-clock timestamp we posted at; 0 means "never".
        // Uses System.currentTimeMillis so the throttle is robust to
        // coroutine context jumps. Tests that need to pin the clock
        // can lower [postIntervalMillis] instead — the throttle
        // semantics are the same.
        var lastPostMillis = 0L

        stateExtractor(connection).collect { state ->
            when (state) {
                is InboundConnectionState.WaitingForUserConsent -> {
                    // Capture sender name early so the progress
                    // notification can render "Pixel 8 sending files"
                    // even though the consent metadata is no longer
                    // available once Receiving fires (the consent
                    // registry entry is unregistered on accept).
                    sourceDeviceName = state.metadata.sourceDeviceName
                }
                is InboundConnectionState.Receiving -> {
                    if (!posted) {
                        posted = true
                        sink.registerCancel(connectionId, cancelInvoker(connection))
                        sink.postProgress(connectionId, sourceDeviceName, state.progress)
                        lastPostMillis = currentMillis()
                        pendingProgress = null
                    } else {
                        val now = currentMillis()
                        if (now - lastPostMillis >= postIntervalMillis) {
                            sink.postProgress(connectionId, sourceDeviceName, state.progress)
                            lastPostMillis = now
                            pendingProgress = null
                        } else {
                            pendingProgress = state.progress
                        }
                    }
                }
                is InboundConnectionState.Completed,
                is InboundConnectionState.Cancelled,
                is InboundConnectionState.Failed,
                is InboundConnectionState.Rejected,
                -> {
                    if (posted) {
                        sink.dismissProgress(connectionId)
                    }
                    sink.unregisterCancel(connectionId)
                    return@collect
                }
                else -> Unit
            }
        }
        // The collect terminated (StateFlow always stays hot; this
        // path runs on coroutine cancellation only). Drop the
        // potentially-pending throttled update — there is no surface
        // left to render it on.
        if (posted) {
            // Best-effort: post the held-back snapshot before we let
            // go. Skipped when the StateFlow already moved to a
            // terminal state above (we returned early in that case).
            pendingProgress?.let { sink.postProgress(connectionId, sourceDeviceName, it) }
        }
    }

    private suspend fun observeResults() {
        results.collect { completion ->
            val connectionId =
                synchronized(idLock) {
                    idForConnection.remove(completion.connection)
                } ?: return@collect
            sink.unregisterCancel(connectionId)
            sink.dismissProgress(connectionId)
        }
    }

    /**
     * Indirection to make tests independent of wall-clock timing.
     * Production reads `System.currentTimeMillis`; tests can lower
     * the throttle interval via [postIntervalMillis] to make the
     * gating deterministic without monkey-patching the clock.
     */
    private fun currentMillis(): Long = System.currentTimeMillis()

    /** Yield delay used when stopping a coordinator under test (currently unused). */
    @Suppress("unused")
    private suspend fun yieldShort() {
        delay(0L)
    }

    /**
     * Side-effect surface for the coordinator. Production wires this
     * to [TransferProgressNotification.post] / dismiss + the cancel
     * registry; tests use a recording fake.
     */
    public interface Sink {
        public fun postProgress(
            connectionId: Long,
            sourceDeviceName: String?,
            progress: TransferProgress,
        )

        public fun dismissProgress(connectionId: Long)

        public fun registerCancel(
            connectionId: Long,
            onCancel: () -> Unit,
        )

        public fun unregisterCancel(connectionId: Long)
    }

    public companion object {
        /**
         * Default coalesce-and-post interval. 500 ms matches the
         * issue acceptance criterion ("updates every ~500 ms") and
         * sits well above the system's heads-up rate-limit, so
         * NotificationManager renders every update.
         */
        public const val DEFAULT_POST_INTERVAL_MILLIS: Long = 500L
    }
}

/**
 * Process-monotonic source of progress connection ids. Mirrors the
 * approach taken by `ConsentCoordinator`'s `MonotonicConnectionIds` —
 * we mint our own ids at observation time so the post path doesn't
 * race the underlying `TcpReceiverServer.results` id surface.
 *
 * The id space is intentionally separate from the consent
 * coordinator's: a single connection may flow through both
 * coordinators, but the registry id and the notification id derive
 * from independent counters because the two coordinators' lifecycles
 * do not overlap on the same connection (consent ends when receiving
 * begins).
 */
internal object MonotonicProgressIds {
    private val counter = AtomicLong(1)

    fun next(): Long = counter.getAndIncrement()
}
