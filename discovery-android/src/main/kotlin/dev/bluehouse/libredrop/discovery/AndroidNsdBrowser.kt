/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog as Log

/**
 * Production [NsdBrowser] backed by Android's [NsdManager].
 *
 * Two API-quirks are encapsulated here:
 *
 *  1. **Single-flight resolveService on API < 30.** Calling
 *     [NsdManager.resolveService] while another resolve is already in
 *     flight throws `IllegalStateException` with `FailureAlreadyActive`
 *     on Android 11 and below. The fix is a one-at-a-time queue: each
 *     `onServiceFound` callback enqueues a resolve job, and a single
 *     worker coroutine drains the queue. On API 30+ multiple concurrent
 *     resolves are allowed but the queued shape is harmless and we keep
 *     it for code simplicity / cross-version testing parity.
 *  2. **`onServiceFound` may fire before the address is known.** We
 *     emit a `Found` event immediately so consumers can render a
 *     "discovering…" state, then a `Resolved` event once
 *     `resolveService` succeeds. This mirrors the JmDNS-era
 *     `serviceAdded` -> `serviceResolved` ordering [Discovery] already
 *     consumes.
 *
 * The three platform-call seams ([discoverCall], [stopCall],
 * [resolveCall]) default to direct delegation onto [nsdManager] and are
 * lifted into constructor parameters so JVM unit tests can drive the
 * worker without a real `NsdManager`.
 */
internal class AndroidNsdBrowser(
    private val nsdManager: NsdManager,
    private val discoverCall: (String, Int, NsdManager.DiscoveryListener) -> Unit =
        { type, protocol, listener -> nsdManager.discoverServices(type, protocol, listener) },
    private val stopCall: (NsdManager.DiscoveryListener) -> Unit =
        { listener -> nsdManager.stopServiceDiscovery(listener) },
    private val resolveCall: (NsdServiceInfo, NsdManager.ResolveListener) -> Unit =
        { info, listener ->
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, listener)
        },
    /**
     * How long to wait for a `resolveService` callback before emitting
     * an [NsdBrowserEvent.Error] and unblocking the queue. Defaults to
     * [RESOLVE_TIMEOUT_MILLIS]. Overridable by tests to shorten the
     * real-wall-clock wait in integration tests without virtual time.
     */
    internal val resolveTimeoutMillis: Long = RESOLVE_TIMEOUT_MILLIS,
) : NsdBrowser {
    override fun discover(serviceType: String): Flow<NsdBrowserEvent> =
        callbackFlow {
            val supervisorJob = SupervisorJob()
            val workerScope = CoroutineScope(Dispatchers.IO + supervisorJob)
            // Bounded queue: drop the oldest pending resolve under
            // sustained name-churn rather than letting backlog grow
            // unboundedly. Capacity 32 covers worst-case real-world
            // peer counts (typical Quick Share sessions see <10 peers
            // visible on the LAN at once).
            val resolveQueue =
                Channel<NsdServiceInfo>(
                    capacity = RESOLVE_QUEUE_CAPACITY,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            val started = AtomicBoolean(true)

            val resolveWorker: Job =
                workerScope.launch {
                    for (info in resolveQueue) {
                        runResolve(info, this@callbackFlow::trySend)
                    }
                }

            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        trySend(
                            NsdBrowserEvent.Error(
                                instanceName = null,
                                message = "startDiscoveryFailed errorCode=$errorCode",
                            ),
                        )
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "NsdManager.stopDiscovery failed errorCode=$errorCode")
                    }

                    override fun onDiscoveryStarted(serviceType: String) {
                        Log.i(TAG, "NsdBrowser: discovery started for $serviceType")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.i(TAG, "NsdBrowser: discovery stopped for $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName ?: return
                        trySend(NsdBrowserEvent.Found(name))

                        // The post-resolve fast-path predicate is lifted
                        // into a JVM-testable helper [shouldSkipResolve]
                        // so a regression test can pin the contract
                        // without instantiating the platform-typed
                        // NsdServiceInfo (which is unavailable on a JVM
                        // unit-test classpath — the AGP unit-test stub
                        // jar throws ClassFormatError on allocation).
                        // See [shouldSkipResolve] for the full why.
                        if (shouldSkipResolve(serviceInfo.port)) {
                            trySend(mapResolved(serviceInfo))
                        } else {
                            // Legacy / pre-MdnsDiscoveryManager pipeline:
                            // queue the resolve so we serialise
                            // pre-API-30 single-flight constraints.
                            // Bounded capacity with DROP_OLDEST under
                            // sustained churn — see
                            // RESOLVE_QUEUE_CAPACITY for sizing.
                            resolveQueue.trySend(serviceInfo)
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName ?: return
                        trySend(NsdBrowserEvent.Lost(name))
                    }
                }

            // Single teardown path so any failure during start or any
            // close path runs the same cleanup. Otherwise an exception
            // from `discoverServices` would leak `workerScope`,
            // `resolveQueue`, and `resolveWorker`.
            val teardown = {
                if (started.compareAndSet(true, false)) {
                    runCatching { stopCall(listener) }
                    resolveQueue.close()
                    resolveWorker.cancel()
                    workerScope.cancel()
                }
            }

            try {
                discoverCall(
                    serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                trySend(
                    NsdBrowserEvent.Error(
                        instanceName = null,
                        message = "discoverServices threw: ${t.message}",
                    ),
                )
                teardown()
                close(t)
                return@callbackFlow
            }

            awaitClose { teardown() }
        }.flowOn(Dispatchers.IO)

    /**
     * Resolve one [NsdServiceInfo] and emit a [NsdBrowserEvent.Resolved]
     * (or [NsdBrowserEvent.Error]) once the platform callback fires.
     *
     * We use the older `resolveService(NsdServiceInfo, ResolveListener)`
     * overload across all API levels for behavioural parity. The
     * Executor-based overload exists on API 33+ but does not change the
     * semantics we care about — and we still need the single-flight
     * queue to remain compatible with API 24-29.
     */
    private suspend fun runResolve(
        info: NsdServiceInfo,
        emit: (NsdBrowserEvent) -> Any,
    ) {
        val name = info.serviceName ?: return
        val signal = CompletableDeferred<Unit>()
        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    emit(
                        NsdBrowserEvent.Error(
                            instanceName = name,
                            message = "resolveService failed errorCode=$errorCode",
                        ),
                    )
                    signal.complete(Unit)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val resolved = mapResolved(serviceInfo)
                    emit(resolved)
                    signal.complete(Unit)
                }
            }

        try {
            resolveCall(info, resolveListener)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            emit(
                NsdBrowserEvent.Error(
                    instanceName = name,
                    message = "resolveService threw: ${t.message}",
                ),
            )
            return
        }

        awaitResolveSignalWithTimeout(
            name = name,
            timeoutMillis = resolveTimeoutMillis,
            signal = signal,
            emit = emit,
        )
    }

    private fun mapResolved(serviceInfo: NsdServiceInfo): NsdBrowserEvent.Resolved {
        val name = serviceInfo.serviceName ?: ""
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceInfo.hostAddresses ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(serviceInfo.host)
            }
        val port = serviceInfo.port
        val attrs: Map<String, ByteArray> =
            serviceInfo.attributes
                ?.mapValues { (_, value) -> value ?: ByteArray(0) }
                .orEmpty()
        return NsdBrowserEvent.Resolved(
            instanceName = name,
            addresses = addresses,
            port = port,
            attributes = attrs,
        )
    }

    internal companion object {
        private const val TAG = Discovery.TAG

        /**
         * Maximum time to wait for a `resolveService` callback before
         * emitting an [NsdBrowserEvent.Error] and unblocking the
         * single-flight queue. Longer than the typical sub-second mDNS
         * round-trip but still bounded so a single hung resolve cannot
         * starve every follow-on resolve in the same browse session.
         *
         * The whole point of migrating to NsdManager is to fix
         * discovery on OEM Android skins (vivo / Funtouch / OriginOS,
         * Xiaomi MIUI, OPPO ColorOS, …) where the system mDNS responder
         * occasionally hangs or silently swallows callbacks under
         * stress. 5 s matches the spirit of stock Quick Share's "must
         * pop up within 5 s of advertise start" acceptance criterion.
         */
        internal const val RESOLVE_TIMEOUT_MILLIS: Long = 5_000L

        /**
         * Whether `onServiceFound` should emit `Resolved` directly,
         * skipping the resolve queue + worker round-trip.
         *
         * The Android 12+ MdnsDiscoveryManager pipeline (Conscrypt
         * mainline module) delivers fully-resolved [NsdServiceInfo] to
         * `onServiceFound` — port, host, and TXT records are populated
         * up front. Calling [NsdManager.resolveService] on an
         * already-resolved info silently no-ops on some platform
         * versions, leaving the resolve worker waiting on a callback
         * that never fires (then timing out at
         * [RESOLVE_TIMEOUT_MILLIS] and emitting an Error rather than a
         * Resolved). The picker stays empty as a result.
         *
         * Detected via: a port > 0 in the `onServiceFound` payload —
         * the legacy pre-MdnsDiscoveryManager pipeline always delivered
         * port=0 here and required an explicit resolve. When the system
         * has already done the resolve for us, this predicate returns
         * `true` and `onServiceFound` emits Resolved directly.
         *
         * Lifted into the companion as a `@JvmStatic internal` helper
         * so JVM unit tests can pin the contract without instantiating
         * the platform-typed [NsdServiceInfo] (the AGP unit-test stub
         * jar throws `ClassFormatError` on allocation, and Robolectric
         * setup is non-trivial in this project — see issue #100). The
         * regression guard is in [AndroidNsdBrowserTest].
         *
         * Found while running the on-device verification of #99 against
         * a Vivo X300 Ultra + Galaxy S24 Ultra peer; see commit
         * `f1bdcb5` for the full diagnosis.
         */
        @JvmStatic
        internal fun shouldSkipResolve(port: Int): Boolean = port > 0

        /**
         * Wait for a `resolveService` callback signal up to
         * [timeoutMillis], emitting a timeout-marked
         * [NsdBrowserEvent.Error] and returning `false` if the wait
         * elapses without completion. Returns `true` if [signal]
         * completed within the deadline (in which case the listener
         * already emitted [NsdBrowserEvent.Resolved] or
         * [NsdBrowserEvent.Error] for the resolve outcome itself).
         *
         * Lifted into the companion as a `@JvmStatic internal` helper
         * so JVM unit tests can drive the timeout / signal-completion
         * path without instantiating the platform `NsdManager` /
         * `NsdServiceInfo` (the AGP unit-test stub jar throws
         * `ClassFormatError` when the test merely allocates a
         * platform NSD type).
         */
        @JvmStatic
        internal suspend fun awaitResolveSignalWithTimeout(
            name: String,
            timeoutMillis: Long,
            signal: CompletableDeferred<Unit>,
            emit: (NsdBrowserEvent) -> Any,
        ): Boolean {
            val completed = withTimeoutOrNull(timeoutMillis) { signal.await() } != null
            if (!completed) {
                Log.w(TAG, "resolveService($name) timed out after ${timeoutMillis}ms")
                emit(
                    NsdBrowserEvent.Error(
                        instanceName = name,
                        message = "resolveService timed out after ${timeoutMillis}ms",
                    ),
                )
            }
            return completed
        }

        /**
         * Bound the resolve queue so name-churn under stress (e.g.
         * dozens of peers flickering on/off) cannot grow it without
         * limit. With [BufferOverflow.DROP_OLDEST], the freshest peer
         * names always win; that matches the user-facing intent of a
         * picker UI.
         */
        private const val RESOLVE_QUEUE_CAPACITY: Int = 32
    }
}
