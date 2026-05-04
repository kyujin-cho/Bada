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
import dev.bluehouse.libredrop.service.receiver.foreground.AppForegroundState
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
 * the consent surface (notification or in-app modal, #151).
 *
 * ### Responsibilities
 *
 *  1. Subscribe to the session's `activeConnections` flow. Each new
 *     [InboundConnection] gets a fresh observation coroutine that
 *     watches its [InboundConnection.state] for the
 *     `WaitingForUserConsent` transition.
 *  2. On `WaitingForUserConsent`, register the connection in
 *     [ConsentRegistry] and pick the appropriate consent surface based
 *     on whether any LibreDrop activity is currently foregrounded:
 *      * **Foreground** — ask the [Sink] to launch the in-app modal.
 *      * **Background** — ask the [Sink] to post the heads-up
 *        notification.
 *  3. On `ProcessLifecycleOwner` foreground/background transitions
 *     (#151), per still-pending entry: switch the surface so the modal
 *     follows the user from notification to in-app and back. Decided
 *     entries are *not* re-surfaced — the per-connection state machine
 *     guarantees `Decided` is terminal.
 *  4. On any terminal connection state — Receiving (the user already
 *     accepted), Rejected, Cancelled, Failed, Completed — unregister
 *     the entry and ask the [Sink] to dismiss whichever surface is
 *     currently up.
 *  5. On the session's `results` flow (which fires the final
 *     completion), idempotently dismiss in case the per-connection
 *     state observer was cancelled before reaching a terminal state.
 *
 * ### Per-connection-id surface state
 *
 * The coordinator tracks a small finite-state machine per pending
 * consent: `Notification`, `Modal`, or `Decided`. The transitions are:
 *
 * ```
 *   (none) --bg arrival--> Notification
 *   (none) --fg arrival--> Modal
 *   Notification --fg-->   Modal           (dismiss notification, launch modal)
 *   Modal --bg-->          Notification    (cancel modal, post notification)
 *   Notification --decided/terminal--> Decided
 *   Modal --decided/terminal--> Decided
 *   Decided --any--> Decided                (no-op: dismiss is already done)
 * ```
 *
 * `Decided` is a tombstone that prevents the foreground/background
 * watcher from re-raising a notification for a connection the user has
 * already responded to (issue #151 acceptance criterion: "a
 * notification for connection A must not be suppressed because the
 * user is foregrounded for an unrelated previous connection B that
 * has already been decided").
 *
 * ### Why a `Sink` interface
 *
 * The actual notification posting needs an Android `Context` and the
 * trampoline launch needs an [android.app.Activity] / [Class<*>] hop;
 * the coordinator's responsibility is purely the choreography. Pulling
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
public class ConsentCoordinator(
    private val activeConnections: SharedFlow<InboundConnection>,
    private val results: SharedFlow<InboundConnectionCompletion>,
    private val registry: ConsentRegistry,
    private val sink: Sink,
    private val scope: CoroutineScope,
    private val appForegroundState: AppForegroundState,
    private val connectionIdProvider: () -> Long = MonotonicConnectionIds::next,
    private val stateExtractor: (InboundConnection) -> StateFlow<InboundConnectionState> = { it.state },
    private val consentSubmitter: (InboundConnection) -> ((Boolean) -> Unit) = { conn -> conn::submitUserConsent },
    /**
     * Best-effort diagnostic appender. Production wires this to
     * [ConsentDiagnostic.log]; tests leave it as the no-op default so
     * unit tests never have to provide a real Context.
     */
    private val diagnostic: (String) -> Unit = {},
) {
    private val idForConnection: MutableMap<InboundConnection, Long> = HashMap()
    private val idLock = Any()
    private var runJob: Job? = null
    private var foregroundSubscription: AppForegroundState.Subscription? = null

    /**
     * Per-connection-id surface state, used to route foreground /
     * background transitions and to gate against re-surfacing a
     * notification for a decided consent. Synchronised by [surfaceLock]
     * — entries are read by the foreground listener (main thread) and
     * mutated by the per-connection observers (service scope).
     */
    private val surfaceLock = Any()
    private val surfaceForId: MutableMap<Long, Surface> = HashMap()
    private val entryForId: MutableMap<Long, ConsentRegistry.Entry> = HashMap()

    /**
     * Surface state for a pending consent. Three values:
     *
     *  * [Notification] — a heads-up notification is currently posted.
     *  * [Modal] — the in-app modal trampoline activity is currently
     *    expected to be visible (the coordinator does not own the
     *    actual Activity instance, so this state is "we asked the Sink
     *    to launch it"; whether the activity actually came up is the
     *    Sink's problem).
     *  * [Decided] — the user has either decided, or the connection
     *    has terminated for any other reason, so the surface is gone
     *    and must not be re-raised.
     */
    public enum class Surface { Notification, Modal, Decided }

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
        foregroundSubscription =
            appForegroundState.addListener { isForeground -> onForegroundChanged(isForeground) }
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
        foregroundSubscription?.cancel()
        foregroundSubscription = null
        synchronized(surfaceLock) {
            surfaceForId.clear()
            entryForId.clear()
        }
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
                            raiseConsentSurface(connectionId, entry)
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
                            dismissConsentSurface(connectionId)
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
            dismissConsentSurface(connectionId)
        }
    }

    /**
     * Raise the appropriate consent surface for a freshly-registered
     * pending consent. Picks notification vs modal by reading the
     * current foreground state once, atomically.
     */
    private fun raiseConsentSurface(
        connectionId: Long,
        entry: ConsentRegistry.Entry,
    ) {
        val isForeground = appForegroundState.isForeground
        synchronized(surfaceLock) {
            entryForId[connectionId] = entry
            surfaceForId[connectionId] =
                if (isForeground) Surface.Modal else Surface.Notification
        }
        diagnostic(
            "raise id=$connectionId isForeground=$isForeground target=${if (isForeground) "Modal" else "Notification"}",
        )
        if (isForeground) {
            sink.launchModal(connectionId, entry)
        } else {
            sink.postConsent(connectionId, entry)
        }
    }

    /**
     * Dismiss whichever surface is currently up for [connectionId] and
     * mark the consent decided. Idempotent — calling on an
     * already-decided id (e.g. the per-connection observer racing the
     * results loop) is a no-op.
     */
    private fun dismissConsentSurface(connectionId: Long) {
        val previous =
            synchronized(surfaceLock) {
                val prior = surfaceForId[connectionId]
                if (prior == null || prior == Surface.Decided) {
                    diagnostic("dismiss id=$connectionId prior=$prior noop=true")
                    return
                }
                surfaceForId[connectionId] = Surface.Decided
                entryForId.remove(connectionId)
                prior
            }
        diagnostic("dismiss id=$connectionId prior=$previous")
        when (previous) {
            Surface.Notification -> sink.dismissConsent(connectionId)
            Surface.Modal -> sink.dismissModal(connectionId)
            Surface.Decided -> Unit
        }
    }

    /**
     * Called on every actual foreground/background transition. Walks
     * each pending consent and switches its surface in lock-step:
     * notifications get cancelled and modals raised on a foreground
     * transition; modals get cancelled and notifications raised on a
     * background transition.
     *
     * Decided entries are never touched, satisfying the
     * per-connection-id scoping requirement (issue #151 acceptance).
     */
    private fun onForegroundChanged(isForeground: Boolean) {
        // Snapshot the (id -> entry) pairs we need to switch under the
        // lock so a concurrent terminal can't race a stale entry into
        // the side-effect calls below.
        val target = if (isForeground) Surface.Modal else Surface.Notification
        val transitions: List<Pair<Long, ConsentRegistry.Entry>> =
            synchronized(surfaceLock) {
                surfaceForId
                    .asSequence()
                    .filter { (_, surface) ->
                        // Decided is terminal; same-target is a no-op.
                        surface != Surface.Decided && surface != target
                    }.mapNotNull { (id, _) ->
                        val entry = entryForId[id] ?: return@mapNotNull null
                        id to entry
                    }.onEach { (id, _) -> surfaceForId[id] = target }
                    .toList()
            }
        diagnostic(
            "onForegroundChanged isForeground=$isForeground target=$target transitions=${transitions.map { it.first }}",
        )
        for ((id, entry) in transitions) {
            applySurfaceSwitch(id, entry, target)
        }
    }

    /**
     * Apply a single surface switch: cancel the prior surface and
     * raise the target one. Pulled out of [onForegroundChanged] so the
     * loop body stays simple — detekt prefers single-jump loops, and
     * the side-effect dispatch is a discrete enough piece of logic to
     * sit on its own.
     */
    private fun applySurfaceSwitch(
        connectionId: Long,
        entry: ConsentRegistry.Entry,
        target: Surface,
    ) {
        diagnostic("applySurfaceSwitch id=$connectionId target=$target")
        when (target) {
            Surface.Modal -> {
                sink.dismissConsent(connectionId)
                sink.launchModal(connectionId, entry)
            }
            Surface.Notification -> {
                sink.dismissModal(connectionId)
                sink.postConsent(connectionId, entry)
            }
            Surface.Decided -> Unit
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
     * to [ConsentNotification.post] / [ConsentNotification.dismiss]
     * for the heads-up path and to a launch-the-trampoline-as-modal
     * call for the in-app path; tests use a recording fake.
     */
    public interface Sink {
        /**
         * Post the heads-up consent notification for [connectionId].
         * Idempotent: re-posting under the same id replaces the prior
         * notification.
         */
        public fun postConsent(
            connectionId: Long,
            entry: ConsentRegistry.Entry,
        )

        /**
         * Dismiss the heads-up consent notification for
         * [connectionId]. No-op if no notification was posted.
         */
        public fun dismissConsent(connectionId: Long)

        /**
         * Launch the in-app consent modal for [connectionId] over
         * whichever LibreDrop activity is currently foregrounded.
         * Idempotent: re-launching brings the existing modal forward
         * (singleTask + REORDER_TO_FRONT semantics in production).
         */
        public fun launchModal(
            connectionId: Long,
            entry: ConsentRegistry.Entry,
        )

        /**
         * Cancel the in-app consent modal for [connectionId]. The
         * modal closes itself without submitting a decision so the
         * notification path can take over for the same connection id.
         * No-op if no modal was launched.
         */
        public fun dismissModal(connectionId: Long)
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
