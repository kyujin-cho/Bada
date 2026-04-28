/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

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
 */
internal class AndroidNsdBrowser(
    private val nsdManager: NsdManager,
) : NsdBrowser {
    override fun discover(serviceType: String): Flow<NsdBrowserEvent> =
        callbackFlow {
            val supervisorJob = SupervisorJob()
            val workerScope = CoroutineScope(Dispatchers.IO + supervisorJob)
            val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
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
                        // Queue the resolve so we serialise pre-API-30
                        // single-flight constraints. Channel is unlimited
                        // — the worker is fast enough that backlog never
                        // grows beyond the live peer count.
                        resolveQueue.trySend(serviceInfo)
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        val name = serviceInfo.serviceName ?: return
                        trySend(NsdBrowserEvent.Lost(name))
                    }
                }

            try {
                nsdManager.discoverServices(
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
                close(t)
                return@callbackFlow
            }

            awaitClose {
                if (started.compareAndSet(true, false)) {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    resolveQueue.close()
                    resolveWorker.cancel()
                    workerScope.cancel()
                }
            }
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
    @Suppress("DEPRECATION")
    private suspend fun runResolve(
        info: NsdServiceInfo,
        emit: (NsdBrowserEvent) -> Any,
    ) {
        val name = info.serviceName ?: return
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
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
            nsdManager.resolveService(info, resolveListener)
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

        // Bound the wait. The whole point of migrating to NsdManager is
        // to fix discovery on OEM Android skins (vivo / Funtouch /
        // OriginOS, Xiaomi MIUI, OPPO ColorOS, …) where the system mDNS
        // responder occasionally hangs or silently swallows callbacks
        // under stress. Without a timeout, a single misbehaving resolve
        // would block the single-flight worker and stall every later
        // resolve in the discovery session — exactly the failure mode
        // this PR is meant to remove. 5 s matches the spirit of stock
        // Quick Share's "must pop up within 5 s of advertise start"
        // acceptance criterion.
        if (withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) { signal.await() } == null) {
            Log.w(TAG, "resolveService($name) timed out after ${RESOLVE_TIMEOUT_MILLIS}ms")
            emit(
                NsdBrowserEvent.Error(
                    instanceName = name,
                    message = "resolveService timed out after ${RESOLVE_TIMEOUT_MILLIS}ms",
                ),
            )
        }
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

    private companion object {
        private const val TAG = Discovery.TAG

        /**
         * Maximum time to wait for a `resolveService` callback before
         * emitting an [NsdBrowserEvent.Error] and unblocking the
         * single-flight queue. Longer than the typical sub-second mDNS
         * round-trip but still bounded so a single hung resolve cannot
         * starve every follow-on resolve in the same browse session.
         */
        private const val RESOLVE_TIMEOUT_MILLIS: Long = 5_000L
    }
}
