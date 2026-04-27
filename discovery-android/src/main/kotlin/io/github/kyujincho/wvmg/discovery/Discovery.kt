/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.util.Log
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * High-level Quick Share mDNS API.
 *
 * Two operations are exposed:
 *
 *  1. [advertise] — publish ourselves as a Quick Share peer for the
 *     duration of the returned [AdvertiseHandle]. The TXT record carries
 *     the URL-safe-base64-encoded [EndpointInfo] (see [QuickShareMdns]
 *     and [InstanceName] for the wire format).
 *  2. [browse] — observe other peers as a [Flow] of [DiscoveryEvent]s.
 *     The flow is hot for the duration it is collected: starting
 *     collection acquires the multicast lock + opens a JmDNS browser,
 *     and cancelling the collection tears both back down.
 *
 * The same [Discovery] instance can be used for both operations; each
 * advertise / browse owns its own JmDNS instance under the hood (this
 * sidesteps a long-standing JmDNS bug where browsing your own
 * advertisement on a single instance loses TXT updates) but they share
 * one reference-counted [MulticastLockHolder] so we never accidentally
 * release the multicast lock out from under an active operation.
 *
 * The public constructor takes a [Context] and wires up the production
 * Wi-Fi multicast lock plus a JmDNS factory bound to the device's current
 * Wi-Fi interface. Tests use the internal [Discovery.forTesting] factory
 * to substitute in a fake JmDNS provider and a noop lock controller.
 *
 * Diagnostics: call [snapshot] to obtain a [DiscoveryDiagnostics]
 * describing the current bound interface, multicast-lock state, and
 * the most recent JmDNS service events. The receiver service logs this
 * periodically to make silent failures (issue #83) visible in logcat
 * via the `WvmgDiscovery` tag.
 */
public class Discovery internal constructor(
    private val locks: LockController,
    private val jmdnsProvider: (String) -> JmDNS,
    private val networkWatcherFactory: NetworkWatcherFactory = NetworkWatcherFactory.NoOp,
) {
    private val diagnostics: DiagnosticsState = DiagnosticsState()

    /**
     * Production constructor. Builds the multicast-lock controller, the
     * JmDNS factory, and the Wi-Fi network-change watcher against the
     * supplied [Context]; only the application context is retained.
     */
    public constructor(context: Context) : this(
        locks = defaultLockController(context),
        jmdnsProvider = defaultJmdnsProvider(context),
        networkWatcherFactory = AndroidNetworkWatcherFactory(context.applicationContext),
    )

    /**
     * Returns a snapshot of the current discovery state.
     *
     * The receiver service (and any future debug screen) is expected
     * to call this periodically and log the result so on-device tests
     * can correlate "no peers visible" against "lock not held" or
     * "JmDNS bound to loopback". See [DiscoveryDiagnostics] for the
     * fields returned.
     */
    public fun snapshot(): DiscoveryDiagnostics = diagnostics.snapshot(locks.isHeld())

    /**
     * Publish this device as a Quick Share peer.
     *
     * The TXT record is set with the canonical [QuickShareMdns.TXT_KEY_ENDPOINT_INFO]
     * key, whose value is the URL-safe-base64 encoding of [endpointInfo].
     * The instance name is generated freshly on every call via [InstanceName.generate],
     * matching the per-session uniqueness contract Quick Share peers expect.
     *
     * Implementation notes:
     *  - Acquires the multicast lock **before** starting JmDNS, since
     *    JmDNS sends its first announcement during `registerService` and
     *    that announcement would otherwise be silently dropped by Wi-Fi
     *    power-save on Android.
     *  - On any failure during setup the lock and JmDNS instance are
     *    rolled back so we never leak resources past a failed advertise.
     *
     * @param endpointInfo the parsed Quick Share endpoint descriptor.
     * @param port the TCP port the receiver server is listening on.
     * @return a closable handle that unregisters the service when closed.
     */
    public fun advertise(
        endpointInfo: EndpointInfo,
        port: Int,
    ): AdvertiseHandle {
        require(port in 1..MAX_PORT) {
            "port must be in 1..$MAX_PORT (TCP), got $port"
        }
        val instanceName = InstanceName.generate()
        val txtValue = Base64Url.encode(endpointInfo.serialize())
        val serviceInfo =
            ServiceInfo.create(
                QuickShareMdns.SERVICE_TYPE,
                instanceName,
                port,
                0,
                0,
                // JmDNS encodes Map<String, String> values as `key=value`
                // ASCII text records on the wire — passing the
                // already-base64-encoded String here keeps the TXT record
                // human-readable and survives every router and JmDNS
                // version we've tested. Passing a ByteArray instead would
                // be encoded as raw bytes and is not recommended.
                mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to txtValue),
            )

        Log.i(TAG, "advertise: starting publish instance=$instanceName port=$port")
        locks.acquire()
        return try {
            val handle =
                JmdnsAdvertiseHandle(
                    serviceInfo = serviceInfo,
                    instanceName = instanceName,
                    port = port,
                    jmdnsProvider = { jmdnsProvider("advertise") },
                    networkWatcherFactory = networkWatcherFactory,
                    diagnostics = diagnostics,
                    onClose = {
                        diagnostics.setAdvertising(false)
                        diagnostics.setAdvertiseBound(null)
                        locks.release()
                    },
                )
            diagnostics.setAdvertising(true)
            handle
        } catch (
            // JmDNS surfaces a mix of IOException, IllegalStateException, and
            // its own RuntimeException-derived errors during setup; catching
            // the union keeps cleanup simple. The exception is rethrown
            // verbatim so callers see the original cause.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.e(TAG, "advertise: failed to publish instance=$instanceName", t)
            // Lock counter must stay balanced even on the failure path.
            locks.release()
            throw t
        }
    }

    /**
     * Observe Quick Share peers on the LAN.
     *
     * The returned [Flow] is cold; collecting it spins up a private JmDNS
     * instance, attaches a [ServiceListener], and re-emits resolved
     * services as [DiscoveryEvent.Resolved]. Cancelling the collector
     * tears the JmDNS instance and the multicast lock back down.
     *
     * Loopback peers are filtered out — when our own advertisement is
     * also active this flow would otherwise echo back our own service,
     * which is never useful to higher layers.
     *
     * Threading: JmDNS callbacks fire on its internal daemon thread.
     * [callbackFlow] handles the bridge to the collector's coroutine
     * context for us; we additionally `flowOn(Dispatchers.IO)` so the
     * acquire/release calls don't block the main thread when collected
     * from a UI scope.
     *
     * Re-query: after attaching the listener we synchronously call
     * [JmDNS.list] to force a fresh PTR query. JmDNS's
     * `addServiceListener` only triggers a one-shot query and would
     * otherwise depend on cached announcements being delivered between
     * receiver-up and sender-up; explicitly listing closes that race.
     */
    public fun browse(): Flow<DiscoveryEvent> =
        callbackFlow {
            // Acquire the multicast lock *inside* the callbackFlow body so
            // it is owned by the same coroutine that produced the JmDNS
            // instance. `flowOn(Dispatchers.IO)` ensures this acquire never
            // runs on the main thread.
            locks.acquire()
            Log.i(TAG, "browse: starting collector")

            val jmdns =
                try {
                    jmdnsProvider("browse")
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.e(TAG, "browse: JmDNS factory threw; releasing multicast lock", t)
                    locks.release()
                    throw t
                }
            val started = AtomicBoolean(true)

            val boundAddress =
                try {
                    jmdns.inetAddress
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Throwable,
                ) {
                    null
                }
            diagnostics.setBrowseBound(boundAddress)
            diagnostics.setBrowsing(true)
            Log.i(
                TAG,
                "browse: JmDNS opened bound=${boundAddress?.hostAddress ?: "unknown"} " +
                    "listening for ${QuickShareMdns.SERVICE_TYPE}",
            )

            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        Log.d(TAG, "browse: serviceAdded name=${event.name}")
                        diagnostics.recordEvent(
                            DiagnosticEvent(
                                kind = DiagnosticEvent.Kind.ADDED,
                                instanceName = event.name,
                                timestampMillis = System.currentTimeMillis(),
                            ),
                        )
                        // JmDNS frequently fires "added" with no TXT data attached
                        // yet. Triggering an explicit info request causes JmDNS to
                        // send a follow-up DNS-SD query and to redeliver via
                        // serviceResolved when the TXT info is in.
                        // The third argument flips JmDNS into persistent re-query mode so it keeps
                        // sending DNS-SD info requests until TXT data is in or the listener is removed.
                        jmdns.requestServiceInfo(event.type, event.name, true)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Log.d(TAG, "browse: serviceRemoved name=${event.name}")
                        diagnostics.recordEvent(
                            DiagnosticEvent(
                                kind = DiagnosticEvent.Kind.REMOVED,
                                instanceName = event.name,
                                timestampMillis = System.currentTimeMillis(),
                            ),
                        )
                        trySend(DiscoveryEvent.Lost(event.name)).isSuccess
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        Log.d(
                            TAG,
                            "browse: serviceResolved name=${event.name} " +
                                "addrs=${event.info?.inetAddresses?.joinToString { it.hostAddress }} " +
                                "port=${event.info?.port}",
                        )
                        diagnostics.recordEvent(
                            DiagnosticEvent(
                                kind = DiagnosticEvent.Kind.RESOLVED,
                                instanceName = event.name,
                                timestampMillis = System.currentTimeMillis(),
                            ),
                        )
                        val resolved = toDiscoveredService(event.info ?: return) ?: return
                        trySend(DiscoveryEvent.Resolved(resolved)).isSuccess
                    }
                }

            jmdns.addServiceListener(QuickShareMdns.SERVICE_TYPE, listener)

            // Force a synchronous PTR query so that any service already
            // advertised before this collector started gets re-discovered
            // immediately rather than waiting for an unsolicited
            // re-announce. JmDNS.list blocks for up to its internal
            // timeout — we run the whole callbackFlow under
            // Dispatchers.IO via flowOn(...), so this is fine.
            try {
                val seen = jmdns.list(QuickShareMdns.SERVICE_TYPE, LIST_TIMEOUT_MILLIS)
                Log.i(TAG, "browse: jmdns.list returned ${seen?.size ?: 0} cached services")
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // JmDNS occasionally throws on list() if multicast send
                // fails; the listener is still attached so we'll catch
                // the next unsolicited announce. Don't propagate.
                Log.w(TAG, "browse: jmdns.list threw; continuing with listener-only path", t)
            }

            awaitClose {
                Log.i(TAG, "browse: collector closing")
                // Guard against double-close from a second cancellation: the
                // contract for awaitClose blocks is "called exactly once",
                // but defending against a buggy collector is cheap.
                if (started.compareAndSet(true, false)) {
                    try {
                        jmdns.removeServiceListener(QuickShareMdns.SERVICE_TYPE, listener)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Throwable,
                    ) {
                        // JmDNS occasionally throws during shutdown; not actionable.
                    }
                    try {
                        jmdns.close()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Throwable,
                    ) {
                        // Same story — final close is best-effort.
                    }
                    diagnostics.setBrowsing(false)
                    diagnostics.setBrowseBound(null)
                    locks.release()
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Maps a JmDNS [ServiceInfo] into our [DiscoveredService] DTO. Returns
     * `null` if the service is loopback-only (we don't want to surface
     * our own advertisement to higher layers). The `n` TXT key is parsed
     * leniently — any decoding failure yields a `null` [endpointInfo]
     * but does **not** drop the discovery, since callers may still want
     * the address tuple for diagnostic display.
     */
    internal fun toDiscoveredService(info: ServiceInfo): DiscoveredService? {
        val addresses = info.inetAddresses?.toList().orEmpty()
        // NearDrop's `maybeAddFoundDevice` filters out 127.* peers because
        // on macOS JmDNS publishes through the loopback interface alongside
        // the real one. We mirror that behavior.
        if (addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
            return null
        }

        val rawTxt = info.getPropertyString(QuickShareMdns.TXT_KEY_ENDPOINT_INFO)
        val endpointInfo =
            rawTxt?.let { txt ->
                Base64Url.decode(txt)?.let { EndpointInfo.parse(it) }
            }

        val raw = InstanceName.decodeRawBytes(info.name)
        val endpointId = raw?.let(InstanceName::extractEndpointId)

        return DiscoveredService(
            instanceName = info.name,
            endpointId = endpointId,
            addresses = addresses,
            port = info.port,
            endpointInfo = endpointInfo,
        )
    }

    public companion object {
        /** Maximum legal TCP port (`2^16 - 1`). */
        public const val MAX_PORT: Int = 0xFFFF

        /** logcat tag — shared with the rest of the discovery module. */
        internal const val TAG: String = "WvmgDiscovery"

        /**
         * Bound on the synchronous `jmdns.list` call inside
         * [browse]. JmDNS waits up to this many milliseconds for cached
         * answers before returning. Picked to be short enough that a
         * UI-bound caller doesn't notice a stall on browse start, but
         * long enough for one multicast round-trip to land on a typical
         * home Wi-Fi network.
         */
        private const val LIST_TIMEOUT_MILLIS: Long = 1_500L

        /**
         * Test-only factory that wires up [Discovery] without an Android
         * [Context]. The caller supplies the JmDNS factory and the
         * (typically noop) [LockController]; the public constructor remains
         * the only path that consumes a [Context].
         */
        @JvmStatic
        internal fun forTesting(
            locks: LockController,
            jmdnsProvider: (purpose: String) -> JmDNS,
            networkWatcherFactory: NetworkWatcherFactory = NetworkWatcherFactory.NoOp,
        ): Discovery = Discovery(locks, jmdnsProvider, networkWatcherFactory)

        private fun defaultLockController(context: Context): LockController =
            MulticastLockController(MulticastLockHolder(context.applicationContext))

        private fun defaultJmdnsProvider(context: Context): (String) -> JmDNS {
            val appContext = context.applicationContext
            return { purpose -> JmdnsFactory.create(appContext, name = "wvmg-$purpose") }
        }
    }
}

/**
 * Tiny abstraction over the multicast-lock acquire/release pair so the
 * [Discovery] class can be unit-tested on a plain JVM (where there is no
 * [android.net.wifi.WifiManager]). Production code uses
 * [MulticastLockController]; tests use a counting fake.
 */
internal interface LockController {
    fun acquire()

    fun release()

    /** Whether the underlying multicast lock is currently held. */
    fun isHeld(): Boolean
}

/** Production [LockController] backed by [MulticastLockHolder]. */
internal class MulticastLockController(
    private val holder: MulticastLockHolder,
) : LockController {
    override fun acquire(): Unit = holder.acquire()

    override fun release(): Unit = holder.release()

    override fun isHeld(): Boolean = holder.isHeld()
}

/**
 * Concrete [AdvertiseHandle] that owns one JmDNS instance and one share of
 * the multicast lock. Kept package-private — callers receive it only via
 * the [AdvertiseHandle] interface returned from [Discovery.advertise].
 *
 * On Wi-Fi network changes (subscribed via [NetworkWatcher]) the
 * handle tears down the current JmDNS instance and re-registers the
 * same [ServiceInfo] against a fresh one. This guarantees the
 * advertisement keeps pointing at the correct source IP after the
 * device roams between Wi-Fi networks. Re-registration is serialized
 * by an internal lock so concurrent network callbacks can't double-
 * register or race with [close].
 */
internal class JmdnsAdvertiseHandle(
    private val serviceInfo: ServiceInfo,
    override val instanceName: String,
    override val port: Int,
    private val jmdnsProvider: () -> JmDNS,
    networkWatcherFactory: NetworkWatcherFactory,
    private val diagnostics: DiagnosticsState,
    private val onClose: () -> Unit,
) : AdvertiseHandle {
    private val active = AtomicBoolean(true)
    private val lifecycle = Object()

    @Volatile
    private var current: JmDNS? = null

    private val watcher: NetworkWatcher? =
        networkWatcherFactory.create(onChanged = ::onNetworkChanged)

    init {
        // Register synchronously so the caller sees a fully-published
        // service the moment `advertise` returns. If JmDNS startup fails
        // we propagate the throwable to the caller, who handles lock
        // rollback in `Discovery.advertise`.
        register()
        watcher?.start()
    }

    override val isActive: Boolean get() = active.get()

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        Log.i(Discovery.TAG, "advertise: closing handle instance=$instanceName")
        watcher?.stop()
        synchronized(lifecycle) {
            unregisterAndClose(current)
            current = null
        }
        onClose()
    }

    private fun onNetworkChanged() {
        if (!active.get()) return
        Log.i(Discovery.TAG, "advertise: network changed, re-registering instance=$instanceName")
        synchronized(lifecycle) {
            // Re-check inside the lock — `close` may have raced ahead and
            // disabled the handle while this callback was queued.
            if (!active.get()) return@synchronized
            unregisterAndClose(current)
            current = null
            try {
                register()
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // Re-registration on a network change is best-effort: if
                // the new network isn't ready yet, JmDNS startup throws
                // an IOException and we'll get another network callback
                // when the interface fully comes up. Swallowing here
                // keeps the handle usable for the next callback.
                Log.w(Discovery.TAG, "advertise: re-register failed; will retry on next network event", t)
            }
        }
    }

    private fun register() {
        synchronized(lifecycle) {
            if (current != null) return
            val fresh = jmdnsProvider()
            try {
                fresh.registerService(serviceInfo)
                current = fresh
                val bound =
                    try {
                        fresh.inetAddress
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Throwable,
                    ) {
                        null
                    }
                diagnostics.setAdvertiseBound(bound)
                Log.i(
                    Discovery.TAG,
                    "advertise: registered instance=$instanceName " +
                        "bound=${bound?.hostAddress ?: "unknown"} port=$port",
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                unregisterAndClose(fresh)
                throw t
            }
        }
    }

    private fun unregisterAndClose(jmdns: JmDNS?) {
        if (jmdns == null) return
        try {
            jmdns.unregisterService(serviceInfo)
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Throwable,
        ) {
            // JmDNS sometimes throws on shutdown if the network is gone;
            // the close path below still tears everything down.
        }
        try {
            jmdns.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Throwable,
        ) {
            // Same: final close is best-effort.
        }
    }
}

/**
 * A simple start/stop interface for an external network-change observer.
 * Implementations call back into the supplied lambda when the watched
 * network state changes; [JmdnsAdvertiseHandle] consumes that signal as
 * its "re-register the JmDNS instance" trigger.
 */
internal interface NetworkWatcher {
    fun start()

    fun stop()
}

/**
 * Factory for the optional Wi-Fi-network-change watcher. Production
 * builds use [AndroidNetworkWatcherFactory]; tests + the
 * `Discovery.forTesting` factory use [NoOp] so unit tests don't need
 * a `ConnectivityManager`.
 */
internal interface NetworkWatcherFactory {
    fun create(onChanged: () -> Unit): NetworkWatcher?

    object NoOp : NetworkWatcherFactory {
        override fun create(onChanged: () -> Unit): NetworkWatcher? = null
    }
}

/** Production [NetworkWatcherFactory] backed by the Android `ConnectivityManager`. */
internal class AndroidNetworkWatcherFactory(
    private val context: Context,
) : NetworkWatcherFactory {
    override fun create(onChanged: () -> Unit): NetworkWatcher = NetworkChangeWatcher(context, onChanged)
}
