/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import dev.bluehouse.libredrop.discovery.AdvertiseHandle
import dev.bluehouse.libredrop.protocol.connection.InboundConnection
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.medium.MediumRegistry
import dev.bluehouse.libredrop.protocol.payload.FileDestinationFactory
import dev.bluehouse.libredrop.protocol.server.InboundConnectionCompletion
import dev.bluehouse.libredrop.protocol.server.TcpReceiverServer
import dev.bluehouse.libredrop.protocol.transport.InitialControlServer
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
 *     [dev.bluehouse.libredrop.protocol.connection.InboundConnection].
 *  2. An mDNS publisher (#18) — abstracted by [DiscoveryAdvertiser] so
 *     unit tests don't need to stand up real `Discovery` against
 *     loopback.
 *  3. A per-connection [FileDestinationFactory] provider — the
 *     `MediaStoreDownloadsFactory` (#23) holds per-transfer state, so the
 *     server is given a `() -> FileDestinationFactory` rather than one
 *     shared instance.
 *
 * Phase 1 also held a [WifiManager.MulticastLock] across the receiver
 * lifecycle so the Wi-Fi chip would not drop inbound multicast while
 * the screen was off. After the #98 migration to `NsdManager`, the
 * system mDNS responder process owns the multicast filter exemption and
 * the in-app multicast lock is no longer needed — the parameter and
 * the related lifecycle calls were removed from this class.
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
 *  - [start] is one-shot. It binds the listener, registers the mDNS
 *    advertisement against the bound port, and forwards completions
 *    onto [completions]. Any failure during setup rolls back the
 *    partially-acquired resources before throwing.
 *  - [stop] is idempotent and may be called from any thread. It tears
 *    everything down in reverse order: close the advertise handle, stop
 *    the TCP server, cancel the internal coroutine scope.
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
 * @property factoryProvider Per-connection [FileDestinationFactory]
 *   factory. Production wires `DownloadsWriterFactory.create(context)`
 *   (which yields a fresh `MediaStoreDownloadsFactory` each time); tests
 *   inject a no-op or in-memory provider.
 * @property endpointInfo The receiver's identity. The caller seeds it on
 *   construction and may replace it later via [replaceEndpointInfo] so the
 *   advertised device name can change without tearing down the TCP listener or
 *   aborting in-flight receives. Embedded in the mDNS TXT record so peers can
 *   render the device name and salt+key without a separate request.
 * @property secureRandomProvider Supplies a fresh [SecureRandom] per
 *   accepted connection (UKEY2 keypairs / SecureMessage IVs). Tests
 *   inject a deterministic stub.
 * @property advertiseGated When `true`, [start] binds the TCP listener
 *   but does **not** publish the mDNS advertisement. The caller is
 *   then responsible for calling [publishAdvertisement] /
 *   [unpublishAdvertisement] to drive the advertise lifecycle
 *   externally. Used by [MdnsAdvertisementGate] (#34) to bind publish
 *   to BLE pulse activity. Defaults to `false` for
 *   backward-compatibility — `start()` publishes immediately as in
 *   Phase 1.
 * @property initialControlServers Optional non-LAN initial-control
 *   advertisement/listener surfaces. These are published and
 *   unpublished with the same visibility lifecycle as mDNS.
 */
@Suppress("LongParameterList")
public class ReceiverSession(
    private val tcpServerFactory: TcpServerFactory,
    private val advertiser: DiscoveryAdvertiser,
    private val factoryProvider: () -> FileDestinationFactory,
    private var endpointInfo: EndpointInfo,
    private val secureRandomProvider: () -> SecureRandom = { SecureRandom() },
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    private val advertiseGated: Boolean = false,
    private val initialControlServers: List<InitialControlServer> = emptyList(),
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

    private val mutableActiveConnections: MutableSharedFlow<InboundConnection> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = COMPLETIONS_BUFFER)

    /**
     * Stream of newly-accepted [InboundConnection]s.
     *
     * Forwards [TcpReceiverServer.activeConnections] so higher layers
     * (the consent coordinator in #22) can attach their own
     * [InboundConnection.state] observer per connection. Replay = 0;
     * subscribers must subscribe before [start] returns to receive
     * the very first connection.
     */
    public val activeConnections: SharedFlow<InboundConnection> = mutableActiveConnections.asSharedFlow()

    /**
     * The bound TCP port. Valid only between [start] returning and
     * [stop]. Throws [IllegalStateException] if read outside that
     * window.
     */
    public val boundPort: Int
        get() = server?.boundPort ?: error("ReceiverSession has not been started")

    /**
     * Bind the listener, register the mDNS advertisement, and start
     * forwarding completions.
     *
     * On any failure during setup every partially-acquired resource is
     * released before the throwable propagates to the caller. This
     * keeps [start] safe to retry against a fresh [ReceiverSession]
     * without leaking sockets or NSD registrations.
     *
     * @return The bound TCP port (also reachable via [boundPort]).
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    public suspend fun start(): Int {
        check(started.compareAndSet(false, true)) {
            "ReceiverSession.start() may only be invoked once"
        }
        check(!stopped.get()) { "ReceiverSession has already been stopped" }

        val tcpServer: TcpReceiverServer
        val port: Int
        try {
            tcpServer =
                tcpServerFactory.create(
                    scope = scope,
                    factoryProvider = factoryProvider,
                    secureRandomProvider = secureRandomProvider,
                    mediumRegistry = mediumRegistry,
                )
            port = tcpServer.start()
            server = tcpServer
        } catch (t: Throwable) {
            stopped.set(true)
            scope.cancel()
            throw t
        }

        if (!advertiseGated) {
            // Phase 1 / default behaviour: publish the advertisement
            // surfaces synchronously during start so peers see us as
            // soon as the service is up. Issue #34 introduces
            // [advertiseGated] = true for the BLE-pulse-driven flow,
            // where publish is deferred until [publishAdvertisement] is
            // called.
            try {
                publishAdvertisementLocked(tcpServer, port)
            } catch (t: Throwable) {
                stopInitialControlServersLocked()
                runCatching { tcpServer.stop() }
                server = null
                stopped.set(true)
                scope.cancel()
                throw t
            }
        }

        // Forward completions onto our SharedFlow. tryEmit is fine because
        // the underlying SharedFlow has a buffer; a slow collector cannot
        // back-pressure the accept loop.
        tcpServer.results
            .onEach { mutableCompletions.tryEmit(it) }
            .launchIn(scope)

        // Mirror the per-connection emissions so the consent
        // coordinator can attach its own state observer to each
        // accepted [InboundConnection]. Same buffered tryEmit shape so
        // a slow consent collector cannot back-pressure the accept
        // loop.
        tcpServer.activeConnections
            .onEach { mutableActiveConnections.tryEmit(it) }
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
     * "couldn't connect" instead of "device went away").
     */
    public fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        // Serialize advertise teardown with any racing publish/unpublish
        // call: a [MdnsAdvertisementGate] coroutine may invoke
        // [publishAdvertisement] concurrently with `Service.onDestroy`
        // calling stop(), and we must not leave an AdvertiseHandle alive
        // past stop().
        synchronized(advertiseLock) {
            runCatching { advertiseHandle?.close() }
            advertiseHandle = null
            stopInitialControlServersLocked()
            ReceiverAdvertisementStateHolder.setAdvertising(false)
        }

        runCatching { server?.stopBlocking() }
        server = null

        scope.cancel()
    }

    /**
     * Whether [start] has been called and [stop] has not. Used by the
     * Android service to decide whether `onStartCommand` is a no-op.
     */
    public val isRunning: Boolean
        get() = started.get() && !stopped.get()

    /**
     * Whether the main mDNS advertisement is currently published. Used
     * by [MdnsAdvertisementGate] (#34) to decide whether a
     * publish/unpublish call would be a no-op.
     */
    public val isAdvertising: Boolean
        get() = advertiseHandle?.isActive == true

    /**
     * Publish the mDNS advertisement against the bound TCP port. Only
     * meaningful when the session was constructed with
     * `advertiseGated = true`. Idempotent: if an advertisement is
     * already in flight this call is a no-op.
     *
     * Thread-safe: serialises with [unpublishAdvertisement] and [stop]
     * via the same lock so a racing teardown cannot leak a published
     * advertisement past stop.
     *
     * @throws IllegalStateException if the session has not been started
     *   or has been stopped.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun publishAdvertisement() {
        synchronized(advertiseLock) {
            check(started.get()) { "ReceiverSession has not been started" }
            check(!stopped.get()) { "ReceiverSession has been stopped" }
            if (advertiseHandle?.isActive == true) return
            val tcpServer = server ?: error("TCP server is not bound")
            publishAdvertisementLocked(
                tcpServer = tcpServer,
                port = tcpServer.boundPort,
                keepInitialControlOnAdvertiseFailure = advertiseGated,
            )
        }
    }

    /**
     * Unpublish the mDNS advertisement if one is currently in flight.
     * Idempotent: calling this when no advertisement is published is a
     * no-op. The TCP listener is unaffected — the receiver remains
     * reachable for already-resolved peers and can be re-published via
     * [publishAdvertisement].
     */
    public fun unpublishAdvertisement() {
        synchronized(advertiseLock) {
            val handle = advertiseHandle
            if (handle == null && initialControlServers.none { it.isActive }) return
            runCatching { handle?.close() }
            advertiseHandle = null
            stopInitialControlServersLocked()
            ReceiverAdvertisementStateHolder.setAdvertising(false)
        }
    }

    /**
     * Swap the identity used by future advertisement publishes.
     *
     * If an advertisement or initial-control server is already active, this
     * method republishes them in place against the existing bound TCP listener
     * so active inbound connections keep running under the same session.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun replaceEndpointInfo(
        endpointInfo: EndpointInfo,
        requireAdvertisement: Boolean = true,
    ): Boolean =
        synchronized(advertiseLock) {
            val previousEndpointInfo = this.endpointInfo
            this.endpointInfo = endpointInfo

            val tcpServer = server
            val wasPublished = advertiseHandle != null || initialControlServers.any { it.isActive }
            if (tcpServer == null || !wasPublished || stopped.get()) {
                true
            } else {
                runCatching { advertiseHandle?.close() }
                advertiseHandle = null
                stopInitialControlServersLocked()
                ReceiverAdvertisementStateHolder.setAdvertising(false)
                runCatching {
                    publishAdvertisementLocked(
                        tcpServer = tcpServer,
                        port = tcpServer.boundPort,
                        keepInitialControlOnAdvertiseFailure = !requireAdvertisement,
                    )
                    true
                }.getOrElse {
                    if (requireAdvertisement) {
                        this.endpointInfo = previousEndpointInfo
                        false
                    } else {
                        true
                    }
                }
            }
        }

    private val advertiseLock: Any = Any()

    @Suppress("TooGenericExceptionCaught")
    private fun publishAdvertisementLocked(
        tcpServer: TcpReceiverServer,
        port: Int,
        keepInitialControlOnAdvertiseFailure: Boolean = false,
    ) {
        // Bring up BLE GATT / other initial-control listeners before
        // the mDNS registration call. Some Android builds can leave
        // NsdManager registration pending for a long time; the
        // receiver should still be connectible over BLE GATT while
        // that platform callback is pending.
        startInitialControlServersLocked(tcpServer)
        try {
            advertiseHandle = advertiser.advertise(endpointInfo, port)
            ReceiverAdvertisementStateHolder.setAdvertising(true)
        } catch (t: Throwable) {
            advertiseHandle = null
            if (!keepInitialControlOnAdvertiseFailure) {
                stopInitialControlServersLocked()
            }
            ReceiverAdvertisementStateHolder.setAdvertising(false)
            throw t
        }
    }

    private fun startInitialControlServersLocked(tcpServer: TcpReceiverServer) {
        for (initialControlServer in initialControlServers) {
            if (initialControlServer.isActive) continue
            runCatching {
                initialControlServer.start(endpointInfo) { transport ->
                    tcpServer.acceptConnectedTransport(transport)
                }
            }
        }
    }

    private fun stopInitialControlServersLocked() {
        for (initialControlServer in initialControlServers) {
            runCatching { initialControlServer.stop() }
        }
    }

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
        mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    ): TcpReceiverServer

    public companion object {
        /**
         * Production factory: builds a stock [TcpReceiverServer] bound
         * on `0.0.0.0` (all interfaces) so any Wi-Fi peer can connect.
         */
        @JvmStatic
        public fun default(logger: (String) -> Unit = {}): TcpServerFactory =
            object : TcpServerFactory {
                override fun create(
                    scope: CoroutineScope,
                    factoryProvider: () -> FileDestinationFactory,
                    secureRandomProvider: () -> SecureRandom,
                    mediumRegistry: MediumRegistry,
                ): TcpReceiverServer =
                    TcpReceiverServer(
                        parentScope = scope,
                        factoryProvider = factoryProvider,
                        secureRandomProvider = secureRandomProvider,
                        mediumRegistry = mediumRegistry,
                        logger = logger,
                    )
            }
    }
}

/**
 * Abstraction over [dev.bluehouse.libredrop.discovery.Discovery.advertise].
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
