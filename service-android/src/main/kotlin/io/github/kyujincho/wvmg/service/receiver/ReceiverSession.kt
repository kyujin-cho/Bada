/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import io.github.kyujincho.wvmg.discovery.AdvertiseHandle
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import io.github.kyujincho.wvmg.protocol.server.InboundConnectionCompletion
import io.github.kyujincho.wvmg.protocol.server.TcpReceiverServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM coordinator for a single receiver lifecycle. Wires together:
 *
 *  1. A [TcpReceiverServer] (#19) — bound on an ephemeral port, accepts
 *     inbound Quick Share connections, runs each through
 *     [io.github.kyujincho.wvmg.protocol.connection.InboundConnection].
 *  2. An mDNS publisher (#18) — abstracted by [DiscoveryAdvertiser] so
 *     unit tests don't need to stand up real JmDNS / `Discovery` against
 *     loopback.
 *  3. A [MulticastLockController] — the foreground service owns the
 *     `WifiManager.MulticastLock` while the receiver is active so JmDNS
 *     multicast actually reaches the chip on Android.
 *  4. A per-connection [FileDestinationFactory] provider — the
 *     `MediaStoreDownloadsFactory` (#23) holds per-transfer state, so the
 *     server is given a `() -> FileDestinationFactory` rather than one
 *     shared instance.
 *
 * ### Why this is a pure-JVM class
 *
 * The Android `Service` (`ReceiverForegroundService`) holds nothing but
 * plumbing: the notification surface, the foreground-state transitions,
 * and the binders into Android lifecycle callbacks. Every piece of
 * actual coordination — start order, teardown order, idempotency,
 * forwarding completions onto a public `Flow` — lives here so it can be
 * exercised on a plain JVM with no Robolectric or instrumentation
 * harness.
 *
 * ### Lifecycle contract
 *
 *  - [start] is one-shot. It binds the listener, acquires the multicast
 *    lock, registers the mDNS advertisement against the bound port, and
 *    forwards completions onto [completions]. Any failure during setup
 *    rolls back the partially-acquired resources before throwing.
 *  - [stop] is idempotent and may be called from any thread. It tears
 *    everything down in reverse order: close the advertise handle, stop
 *    the TCP server, release the multicast lock, cancel the internal
 *    coroutine scope.
 *  - [completions] re-emits every [InboundConnectionCompletion] from the
 *    underlying server. Higher layers (the consent UI in #22, transfer
 *    history) collect this; for now the foreground service forwards it
 *    via a `LocalBroadcastManager`-style hook.
 *
 * ### Concurrency
 *
 * Setup and teardown serialize on a per-instance lock so a racing
 * `stop()` cannot observe a half-built session. Once running, the only
 * work this class does is forward [TcpReceiverServer.results] onto
 * [completions]; that runs in [scope] under a [SupervisorJob] so a
 * single buggy collector cannot poison the rest of the receiver.
 *
 * @property tcpServerFactory Builds the TCP server given the parent
 *   scope and per-connection factory provider. Production wires a
 *   default `TcpReceiverServer`; tests inject a fake.
 * @property advertiser Publishes the receiver via mDNS and returns an
 *   [AdvertiseHandle]. Production wires `Discovery::advertise`; tests
 *   inject a recording fake.
 * @property multicastLock Acquire / release the WifiManager multicast
 *   lock. Production wires the [MulticastLockController] backed by a
 *   real `WifiManager.MulticastLock`; tests inject a counting fake.
 * @property factoryProvider Per-connection [FileDestinationFactory]
 *   factory. Production wires `DownloadsWriterFactory.create(context)`
 *   (which yields a fresh `MediaStoreDownloadsFactory` each time); tests
 *   inject a no-op or in-memory provider.
 * @property endpointInfo The receiver's identity. Stable for a service
 *   instance (random-generated on first start, persisted by the caller).
 *   Embedded in the mDNS TXT record so peers can render the device
 *   name and salt+key without a separate request.
 * @property secureRandomProvider Supplies a fresh [SecureRandom] per
 *   accepted connection (UKEY2 keypairs / SecureMessage IVs). Tests
 *   inject a deterministic stub.
 */
public class ReceiverSession(
    private val tcpServerFactory: TcpServerFactory,
    private val advertiser: DiscoveryAdvertiser,
    private val multicastLock: MulticastLockController,
    private val factoryProvider: () -> FileDestinationFactory,
    private val endpointInfo: EndpointInfo,
    private val secureRandomProvider: () -> SecureRandom = { SecureRandom() },
) {
    private val supervisor: Job = SupervisorJob()

    /**
     * The internal coroutine scope used to forward [TcpReceiverServer.results]
     * onto [completions]. Cancelled in [stop]; never re-used.
     */
    private val scope: CoroutineScope = CoroutineScope(supervisor + Dispatchers.IO)

    private val started: AtomicBoolean = AtomicBoolean(false)
    private val stopped: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    private var server: TcpReceiverServer? = null

    @Volatile
    private var advertiseHandle: AdvertiseHandle? = null

    @Volatile
    private var holdingLock: Boolean = false

    private val mutableCompletions: MutableSharedFlow<InboundConnectionCompletion> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = COMPLETIONS_BUFFER)

    /**
     * Stream of per-connection terminal outcomes. Higher layers — the
     * consent UI in #22, transfer history, diagnostics — collect this.
     *
     * Replay = 0 so late subscribers don't see historical events; buffer
     * = [COMPLETIONS_BUFFER] so a brief consumer stall doesn't push back
     * onto the accept loop. The forwarding coroutine uses [tryEmit] for
     * the same reason.
     */
    public val completions: SharedFlow<InboundConnectionCompletion> = mutableCompletions.asSharedFlow()

    /**
     * The bound TCP port. Valid only between [start] returning and
     * [stop]. Throws [IllegalStateException] if read outside that
     * window.
     */
    public val boundPort: Int
        get() = server?.boundPort ?: error("ReceiverSession has not been started")

    /**
     * Bind the listener, acquire the multicast lock, register the mDNS
     * advertisement, and start forwarding completions.
     *
     * On any failure during setup every partially-acquired resource is
     * released before the throwable propagates to the caller. This
     * keeps [start] safe to retry against a fresh
     * [ReceiverSession] without leaking sockets, locks, or JmDNS
     * instances.
     *
     * @return The bound TCP port (also reachable via [boundPort]).
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun start(): Int {
        check(started.compareAndSet(false, true)) {
            "ReceiverSession.start() may only be invoked once"
        }
        check(!stopped.get()) { "ReceiverSession has already been stopped" }

        // Order matters here. JmDNS' first announcement is sent inside
        // registerService, and that announcement is silently dropped on
        // Android Wi-Fi power-save unless the multicast lock is held.
        // So: acquire lock -> bind TCP -> publish mDNS, and unwind in
        // strict reverse on failure.
        try {
            multicastLock.acquire()
            holdingLock = true
        } catch (t: Throwable) {
            holdingLock = false
            stopped.set(true)
            throw t
        }

        val tcpServer: TcpReceiverServer
        val port: Int
        try {
            tcpServer = tcpServerFactory.create(scope, factoryProvider, secureRandomProvider)
            port = tcpServer.start()
            server = tcpServer
        } catch (t: Throwable) {
            // Listener bind failed. Roll back the lock and mark
            // terminal so a stray stop() doesn't double-release.
            runCatching { multicastLock.release() }
            holdingLock = false
            stopped.set(true)
            scope.cancel()
            throw t
        }

        try {
            advertiseHandle = advertiser.advertise(endpointInfo, port)
        } catch (t: Throwable) {
            // mDNS publish failed. Roll back the listener and the lock.
            runCatching { tcpServer.stop() }
            server = null
            runCatching { multicastLock.release() }
            holdingLock = false
            stopped.set(true)
            scope.cancel()
            throw t
        }

        // Forward completions onto our SharedFlow. tryEmit is fine because
        // the underlying SharedFlow has a buffer; a slow collector cannot
        // back-pressure the accept loop.
        tcpServer.results
            .onEach { mutableCompletions.tryEmit(it) }
            .launchIn(scope)

        return port
    }

    /**
     * Tear everything down in reverse order. Idempotent and safe to
     * call from any thread, including from `Service.onDestroy`.
     *
     * The teardown order is critical: closing the advertise handle
     * before stopping the listener avoids a race where a peer that just
     * resolved our mDNS record connects to a listener we are about to
     * close (they would observe a `ConnectException`, but the user sees
     * "couldn't connect" instead of "device went away"). Releasing the
     * multicast lock last keeps JmDNS' final goodbye packet flowing on
     * power-save Wi-Fi.
     */
    public fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        runCatching { advertiseHandle?.close() }
        advertiseHandle = null

        runCatching { server?.stopBlocking() }
        server = null

        if (holdingLock) {
            runCatching { multicastLock.release() }
            holdingLock = false
        }

        scope.cancel()
    }

    /**
     * Whether [start] has been called and [stop] has not. Used by the
     * Android service to decide whether `onStartCommand` is a no-op.
     */
    public val isRunning: Boolean
        get() = started.get() && !stopped.get()

    public companion object {
        /**
         * Buffer size for [completions]. Matches [TcpReceiverServer]'s
         * own buffer so we don't introduce a tighter bottleneck on top
         * of it.
         */
        public const val COMPLETIONS_BUFFER: Int = 64
    }
}

/**
 * Factory for the TCP listener. Lifted out as an interface so unit tests
 * can inject a stand-in that returns a controllable
 * [TcpReceiverServer] (or, more often, a stub that captures the
 * arguments).
 */
public interface TcpServerFactory {
    public fun create(
        scope: CoroutineScope,
        factoryProvider: () -> FileDestinationFactory,
        secureRandomProvider: () -> SecureRandom,
    ): TcpReceiverServer

    public companion object {
        /**
         * Production factory: builds a stock [TcpReceiverServer] bound
         * on `0.0.0.0` (all interfaces) so any Wi-Fi peer can connect.
         */
        @JvmStatic
        public fun default(): TcpServerFactory =
            object : TcpServerFactory {
                override fun create(
                    scope: CoroutineScope,
                    factoryProvider: () -> FileDestinationFactory,
                    secureRandomProvider: () -> SecureRandom,
                ): TcpReceiverServer =
                    TcpReceiverServer(
                        parentScope = scope,
                        factoryProvider = factoryProvider,
                        secureRandomProvider = secureRandomProvider,
                    )
            }
    }
}

/**
 * Abstraction over [io.github.kyujincho.wvmg.discovery.Discovery.advertise].
 *
 * Lifted out as a single-method interface so a unit test can replace
 * the production wiring (which needs a real Android `Context`) with a
 * deterministic recording fake. The production binding is a one-line
 * lambda inside the foreground service.
 */
public fun interface DiscoveryAdvertiser {
    public fun advertise(
        endpointInfo: EndpointInfo,
        port: Int,
    ): AdvertiseHandle
}

/**
 * Acquire / release the WifiManager multicast lock. Mirrors the
 * package-private contract of
 * [io.github.kyujincho.wvmg.discovery.MulticastLockHolder] but exposed
 * publicly so the foreground service in this module can inject the
 * lock without dragging the Android `WifiManager` into [ReceiverSession].
 */
public interface MulticastLockController {
    public fun acquire()

    public fun release()
}
