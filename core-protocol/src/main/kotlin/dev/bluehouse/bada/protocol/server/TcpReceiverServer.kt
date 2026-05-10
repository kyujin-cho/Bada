/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.server

import dev.bluehouse.bada.protocol.connection.InboundConnection
import dev.bluehouse.bada.protocol.connection.InboundResult
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.payload.FileDestinationFactory
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.asConnectedTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP listener that accepts inbound Quick Share connections and runs each
 * one through the receiver-side state machine
 * ([dev.bluehouse.bada.protocol.connection.InboundConnection]).
 *
 * ## Why this lives in `:core-protocol`
 *
 * Despite owning long-lived sockets, the implementation is pure-JVM: it
 * uses [java.net.ServerSocket] / [Socket] / [java.io.InputStream] only.
 * No `android.*` imports are required, so the natural home is
 * `:core-protocol` next to the rest of the protocol stack. Android-side
 * concerns (foreground service hosting, MediaStore-backed
 * [FileDestinationFactory], notifications) wrap this class from
 * `:service-android` (#21).
 *
 * ## Lifecycle
 *
 * 1. Caller invokes [start]; this binds a [ServerSocket] on `0.0.0.0:0`
 *    (kernel-assigned ephemeral port) and returns the bound port.
 * 2. A background coroutine runs an `accept()` loop on [Dispatchers.IO].
 * 3. Each accepted [Socket] is handed to a fresh [InboundConnection],
 *    launched as a child of this server's scope using [SupervisorJob] so
 *    one transfer's failure cannot kill the listener or sibling
 *    transfers.
 * 4. Per-connection results (success / reject / cancel / failure) flow
 *    out through [results] for higher layers to observe.
 * 5. [stop] closes the listener (which interrupts the blocked `accept()`),
 *    cancels every in-flight per-connection coroutine, and joins them.
 *
 * The chosen port is what the discovery layer (#18) advertises in its
 * Quick Share mDNS SRV record. Calling [start] before publishing mDNS is
 * mandatory: peers connect by IP+port, so the SRV record needs a real
 * bound value, not `0`.
 *
 * ## Concurrency
 *
 * - The listener owns a [SupervisorJob] child of the caller's scope. We
 *   use [SupervisorJob] (not a plain [Job]) so a per-connection failure
 *   does not cancel the accept loop or other in-flight transfers — Quick
 *   Share permits parallel inbound connections from different peers, and
 *   one bad sender must not poison the others.
 * - `accept()` is wrapped in [runInterruptible] on [Dispatchers.IO] so
 *   coroutine cancellation eagerly interrupts the blocked thread. Closing
 *   the [ServerSocket] from [stop] additionally surfaces a
 *   [SocketException] which the loop treats as the graceful exit signal.
 * - TCP_NODELAY is set on every accepted [Socket] (matches NearDrop's
 *   `tcp.noDelay = true` in `startOutgoingTransfer`) so small
 *   protocol frames are not Nagle-delayed.
 *
 * ## Public API
 *
 * The contract is intentionally tight:
 *
 *  - [start] -- bind, spawn the accept loop, return the bound port.
 *    Idempotent against repeated calls (throws [IllegalStateException]).
 *  - [stop] -- close the listener and cancel every per-connection job.
 *    Safe to call before [start] (no-op) and to call repeatedly.
 *  - [boundPort] -- the chosen ephemeral port; valid only between
 *    [start] returning and [stop].
 *  - [results] -- a [SharedFlow] of [InboundResult]s, one per connection
 *    that ran to completion. Replay = 0 (a result observed once is
 *    consumed); buffer = 64 to absorb bursts.
 *
 * @param parentScope The caller's coroutine scope. The server creates a
 *   [SupervisorJob] child of this scope; cancelling the parent
 *   propagates to every per-connection coroutine. The natural choice on
 *   Android is the foreground service's `lifecycleScope`.
 * @param factoryProvider Supplies a fresh [FileDestinationFactory] per
 *   accepted connection. Why a `() -> FileDestinationFactory` rather
 *   than a single shared factory? Some factories (notably the Android
 *   `MediaStore`-backed one) hold per-transfer state — an opened
 *   `ContentResolver` connection, a transfer-scoped sub-directory, etc.
 *   Re-using the same factory across connections would bleed state from
 *   one transfer into the next. The provider is invoked exactly once
 *   per `accept()`; tests pass `{ TempFileDestinationFactory() }`.
 * @param secureRandomProvider Source of randomness for each
 *   [InboundConnection] (UKEY2 keypairs, IVs). Default is a fresh
 *   [SecureRandom] per connection; tests inject a deterministic stub.
 * @param bindAddress Optional bind address. Defaults to `0.0.0.0`
 *   (`InetAddress.getByName("0.0.0.0")` -- accept on every interface).
 *   Tests typically pass `InetAddress.getLoopbackAddress()` to avoid
 *   binding to the host's external NIC.
 * @param logger diagnostic sink for per-connection protocol events.
 */
public class TcpReceiverServer(
    private val parentScope: CoroutineScope,
    private val factoryProvider: () -> FileDestinationFactory,
    private val secureRandomProvider: () -> SecureRandom = { SecureRandom() },
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    private val bindAddress: InetAddress? = null,
    private val logger: (String) -> Unit = {},
) {
    /**
     * Per-connection [Mutex] table. A single Quick Share connection is
     * inherently single-threaded on the wire (the sequence-number
     * invariant from #1.9 forbids concurrent sends), but we still
     * defensively guard the `InboundConnection.run` invocation with a
     * [Mutex] so a future caller cannot accidentally invoke `run` twice
     * on the same connection from two different coroutines.
     *
     * The map is keyed by the connection id (monotonic counter) and
     * cleaned up when the connection coroutine completes.
     */
    private val connectionMutexes: MutableMap<Long, Mutex> = mutableMapOf()

    /**
     * Live client transports, keyed by connection id. We track these so
     * [stop] can eagerly close them: the receive path inside
     * [InboundConnection] uses `withContext(Dispatchers.IO)` for blocking
     * reads, which is **not** auto-interruptible on coroutine
     * cancellation. Closing the underlying transport is the only reliable
     * way to unblock a thread parked in `read()`.
     */
    private val activeTransports: MutableMap<Long, ConnectedTransport> = mutableMapOf()
    private val connectionMutexesLock = Any()

    /**
     * Per-server supervisor scope. Every connection coroutine and the
     * accept loop are children of this scope; [stop] cancels it.
     *
     * We use [SupervisorJob] -- not a plain [Job] -- so that one
     * failing connection does not cancel siblings. The accept loop
     * itself catches and surfaces all per-connection exceptions, but
     * the supervisor is belt-and-braces.
     */
    private val supervisor: Job =
        SupervisorJob(parentScope.coroutineContext[Job]).also { job ->
            // When the supervisor is cancelled out-of-band (e.g. the
            // caller cancels the parent scope without going through
            // [stop]), eagerly close the listener and every active
            // client socket. ServerSocket.accept() and InboundConnection's
            // blocking reads under withContext(Dispatchers.IO) do not
            // honour Thread.interrupt() on every JDK build; closing the
            // socket is the only reliable wake-up, and it lets cancelAndJoin
            // on the parent's job complete instead of hanging on parked
            // accept() / read() syscalls.
            job.invokeOnCompletion {
                runCatching { serverSocket?.close() }
                val inFlight =
                    synchronized(connectionMutexesLock) {
                        val snapshot = activeTransports.values.toList()
                        activeTransports.clear()
                        snapshot
                    }
                inFlight.forEach { runCatching { it.close() } }
            }
        }

    /**
     * Coroutine context for everything we launch. We compose the
     * caller's context with our supervisor and pin to [Dispatchers.IO]
     * because the entire server is dominated by blocking socket I/O.
     */
    private val ioContext = parentScope.coroutineContext + supervisor + Dispatchers.IO

    /**
     * The internal scope every per-connection coroutine and the accept
     * loop launch into. Implemented as an explicit field (rather than
     * just calling `parentScope.launch(ioContext)`) so [stop] has a
     * single object to cancel.
     */
    private val internalScope: CoroutineScope = CoroutineScope(ioContext)

    /**
     * The bound listener. `null` until [start] is called and after
     * [stop] returns. Volatile because the accept loop reads it on a
     * different thread than the one that calls [stop].
     */
    @Volatile
    private var serverSocket: ServerSocket? = null

    /**
     * The accept loop's job handle. Held so [stop] can join it after
     * closing the listener. `null` outside [start]/[stop].
     */
    @Volatile
    private var acceptJob: Job? = null

    /**
     * Mutex guarding [start]/[stop]. Without it, two concurrent [stop]
     * calls could double-close the [ServerSocket], or a [start] racing a
     * [stop] could leak the listener.
     */
    private val lifecycleMutex = Mutex()

    /**
     * Whether [start] has been called. Single-shot: once [stop] runs,
     * the server is terminal -- callers must construct a new
     * [TcpReceiverServer] to listen again. This avoids subtle bugs from
     * resurrecting a stopped supervisor scope.
     */
    @Volatile
    private var started: Boolean = false

    /**
     * Whether [stop] has been called (or completed). Latched true once
     * teardown begins so the accept loop and per-connection paths
     * recognise an in-progress shutdown.
     */
    @Volatile
    private var stopped: Boolean = false

    /**
     * Monotonic counter for connection identifiers in logs / diagnostics.
     * The Quick Share protocol itself does not use this -- it is purely
     * for the [InboundConnectionCompletion.connectionId] field on
     * [results].
     */
    private val connectionCounter = AtomicLong(0)

    private val mutableResults: MutableSharedFlow<InboundConnectionCompletion> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = RESULTS_BUFFER)

    /**
     * Stream of completed connection outcomes. One [InboundConnectionCompletion]
     * is emitted per accepted connection that finishes (success, reject,
     * cancel, fail). Buffered (capacity = [RESULTS_BUFFER]) so a slow
     * collector does not back-pressure the accept loop.
     *
     * Replay = 0: late subscribers do not see historical results, since
     * by the time the UI is interested it should already be observing
     * `InboundConnection.state` directly via [activeConnections].
     */
    public val results: SharedFlow<InboundConnectionCompletion> = mutableResults.asSharedFlow()

    private val mutableActiveConnections: MutableSharedFlow<InboundConnection> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = ACTIVE_BUFFER)

    /**
     * Stream of newly-accepted [InboundConnection]s. Higher layers (the
     * Android service, the host UI) subscribe to this to attach to each
     * connection's [InboundConnection.state] flow before the connection
     * begins exchanging frames.
     *
     * The connection has already had `run()` invoked by the time it is
     * emitted -- subscribers must NOT call [InboundConnection.run]
     * again. They are free to call [InboundConnection.submitUserConsent]
     * and [InboundConnection.cancel].
     */
    public val activeConnections: SharedFlow<InboundConnection> = mutableActiveConnections.asSharedFlow()

    /**
     * The bound TCP port. Valid only between [start] returning and
     * [stop]. Throws [IllegalStateException] if read before [start] or
     * after [stop].
     */
    public val boundPort: Int
        get() =
            serverSocket?.localPort
                ?: error("TcpReceiverServer is not running")

    /**
     * Bind a [ServerSocket] on an ephemeral port and start the accept
     * loop. Returns the kernel-assigned port number, which the caller
     * must publish via mDNS (see #18).
     *
     * The accept loop runs on [Dispatchers.IO] under this server's
     * supervisor; it only terminates when [stop] is called or the
     * caller's parent scope is cancelled.
     *
     * @return The bound TCP port (`1 <= port <= 65535`).
     * @throws IllegalStateException if [start] has already been called.
     * @throws IOException if `ServerSocket` creation fails.
     */
    public suspend fun start(): Int =
        lifecycleMutex.withLock {
            check(!started) { "TcpReceiverServer.start() may only be called once" }
            check(!stopped) { "TcpReceiverServer has already been stopped" }
            started = true

            // Open the listener on the IO dispatcher; bind() can block
            // briefly on slow systems, and constructing ServerSocket
            // itself performs the bind eagerly.
            val socket =
                withContext(Dispatchers.IO) {
                    // Args: port = 0 (kernel-assigned ephemeral), backlog = ACCEPT_BACKLOG.
                    if (bindAddress != null) {
                        ServerSocket(0, ACCEPT_BACKLOG, bindAddress)
                    } else {
                        ServerSocket(0, ACCEPT_BACKLOG)
                    }
                }
            serverSocket = socket

            acceptJob =
                internalScope.launch {
                    runAcceptLoop(socket)
                }

            socket.localPort
        }

    /**
     * Close the listener and cancel every in-flight connection.
     *
     * Idempotent: calling [stop] before [start], or twice in a row, is
     * a no-op on the second invocation. After [stop] returns, the
     * server is terminal -- it cannot be re-started.
     */
    public suspend fun stop() {
        lifecycleMutex.withLock {
            if (stopped) return@withLock
            stopped = true

            // Close the listener first. This unblocks any thread
            // currently parked in accept() with a SocketException, so
            // the accept loop coroutine wakes up promptly.
            runCatching { serverSocket?.close() }

            // Eagerly close every live client socket. InboundConnection's
            // blocking reads run under withContext(Dispatchers.IO), which
            // does NOT honour coroutine cancellation while parked in a
            // syscall. Closing the socket throws SocketException out of
            // the read, which propagates through InboundConnection.run as
            // an InboundResult.Failed and unblocks the coroutine so
            // cancelAndJoin can complete.
            val transportsToClose: List<ConnectedTransport> =
                synchronized(connectionMutexesLock) {
                    val snapshot = activeTransports.values.toList()
                    activeTransports.clear()
                    snapshot
                }
            transportsToClose.forEach { runCatching { it.close() } }

            // Cancel everything under the supervisor and join. Cancelling
            // the supervisor propagates to the accept loop and to every
            // per-connection coroutine; cancelAndJoin makes stop()
            // observable as "all teardown finished".
            supervisor.cancelAndJoin()

            serverSocket = null
            acceptJob = null
        }
    }

    /**
     * Synchronous variant of [stop] for callers that lack a coroutine
     * scope (e.g. an Android `Service.onDestroy`). Bridges to [stop] via
     * [runBlocking]; will block the calling thread until teardown
     * completes. Prefer the suspend [stop] when possible.
     */
    public fun stopBlocking() {
        runBlocking { stop() }
    }

    /**
     * The accept loop. Runs until the listener is closed or the
     * coroutine is cancelled. Each successful accept hands the socket
     * to [handleAccepted].
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runAcceptLoop(socket: ServerSocket) {
        while (!stopped && !socket.isClosed) {
            val client: Socket =
                try {
                    runInterruptible(Dispatchers.IO) { socket.accept() }
                } catch (cancel: CancellationException) {
                    // Coroutine cancellation: close the listener if it
                    // is not already closed and exit cleanly.
                    runCatching { socket.close() }
                    throw cancel
                } catch (
                    @Suppress("SwallowedException") closed: SocketException,
                ) {
                    // The listener was closed underneath us by stop().
                    // Treat as the graceful shutdown signal.
                    return
                } catch (
                    @Suppress("SwallowedException") io: IOException,
                ) {
                    // Any other IOException on accept() means the
                    // listener is in a bad state (FD exhaustion, NIC
                    // teardown). Bail out -- the supervisor takes care
                    // of cleaning up siblings.
                    return
                }

            // Configure the freshly-accepted socket. TCP_NODELAY matches
            // NearDrop's choice and avoids Nagle-induced latency on the
            // small protocol frames that dominate the early handshake.
            // Failure to set it is non-fatal -- log-and-continue.
            runCatching { client.tcpNoDelay = true }

            acceptConnectedTransport(client.asConnectedTransport())
        }
    }

    /**
     * Inject an already-connected non-TCP initial control transport into the
     * same inbound protocol stack used by the LAN listener.
     *
     * Ownership transfers to the server on success. If the server is already
     * stopping, the transport is closed immediately and ignored.
     */
    public fun acceptConnectedTransport(transport: ConnectedTransport) {
        if (stopped) {
            runCatching { transport.close() }
            return
        }
        launchConnection(transport)
    }

    /**
     * Launch a per-connection coroutine that runs the inbound
     * lifecycle. Each connection is its own [InboundConnection] and
     * runs under a per-connection [Mutex] (defense-in-depth against
     * accidental concurrent `run`).
     */
    private fun launchConnection(transport: ConnectedTransport) {
        val connectionId = connectionCounter.incrementAndGet()
        val mutex = Mutex()
        synchronized(connectionMutexesLock) {
            connectionMutexes[connectionId] = mutex
            activeTransports[connectionId] = transport
        }

        val inbound =
            InboundConnection(
                transport = transport,
                secureRandom = secureRandomProvider(),
                mediumRegistry = mediumRegistry,
                logger = logger,
            )

        internalScope.launch {
            // Emit the active connection BEFORE invoking run() so
            // subscribers can race to attach to its state flow before
            // the first state transition happens.
            mutableActiveConnections.tryEmit(inbound)

            val result: InboundResult =
                try {
                    mutex.withLock {
                        inbound.run(factoryProvider())
                    }
                } catch (cancel: CancellationException) {
                    // Server is shutting down or this specific connection
                    // was externally cancelled. Make sure the socket is
                    // closed even though InboundConnection.run already
                    // does this in its finally block -- belt and braces.
                    runCatching { transport.close() }
                    throw cancel
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    // InboundConnection.run is documented as never
                    // throwing (failures map to InboundResult.Failed),
                    // but any unexpected escape is funnelled into the
                    // results flow rather than propagated up the
                    // supervisor (it would not cancel siblings, but
                    // would leak as an UnhandledCoroutineExceptionHandler
                    // log).
                    runCatching { transport.close() }
                    @Suppress("UnsafeCallOnNullableType")
                    InboundResult.Failed("Unexpected ${e::class.simpleName}: ${e.message ?: ""}")
                } finally {
                    synchronized(connectionMutexesLock) {
                        connectionMutexes.remove(connectionId)
                        activeTransports.remove(connectionId)
                    }
                }

            mutableResults.tryEmit(InboundConnectionCompletion(connectionId, inbound, result))
        }
    }

    private companion object {
        /**
         * `accept()` backlog. Quick Share rarely needs more than one or
         * two pending connections at a time -- peers connect on demand
         * after seeing the mDNS advertisement -- but keep a small
         * cushion so a brief flurry does not get refused at the kernel.
         */
        private const val ACCEPT_BACKLOG = 8

        /**
         * Buffer size for [results]. Sized so the accept loop can keep
         * running through a burst of completing transfers even if the
         * collector is briefly behind.
         */
        private const val RESULTS_BUFFER = 64

        /**
         * Buffer size for [activeConnections]. Same rationale as
         * [RESULTS_BUFFER]; one slot per pending unobserved connection.
         */
        private const val ACTIVE_BUFFER = 64
    }
}

/**
 * One terminal outcome of an accepted connection.
 *
 * @property connectionId Monotonic id assigned at accept time. Useful
 *   for log correlation; the protocol itself does not surface this.
 * @property connection The [InboundConnection] that produced this
 *   result. Its [InboundConnection.state] is already terminal.
 * @property result Final outcome from [InboundConnection.run].
 */
public data class InboundConnectionCompletion(
    val connectionId: Long,
    val connection: InboundConnection,
    val result: InboundResult,
)
