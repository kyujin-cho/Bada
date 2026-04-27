/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
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
 */
public class Discovery internal constructor(
    private val locks: LockController,
    private val jmdnsProvider: (String) -> JmDNS,
) {
    /**
     * Production constructor. Builds the multicast-lock controller and the
     * JmDNS factory against the supplied [Context]; only the application
     * context is retained.
     */
    public constructor(context: Context) : this(
        defaultLockController(context),
        defaultJmdnsProvider(context),
    )

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
                mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to txtValue),
            )

        locks.acquire()
        var jmdns: JmDNS? = null
        return try {
            jmdns = jmdnsProvider("advertise")
            jmdns.registerService(serviceInfo)
            JmdnsAdvertiseHandle(
                jmdns = jmdns,
                serviceInfo = serviceInfo,
                instanceName = instanceName,
                port = port,
                onClose = { locks.release() },
            )
        } catch (
            // JmDNS surfaces a mix of IOException, IllegalStateException, and
            // its own RuntimeException-derived errors during setup; catching
            // the union keeps cleanup simple. The exception is rethrown
            // verbatim so callers see the original cause.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            // Best-effort cleanup. We close JmDNS first because closing it
            // also unregisters services (the registerService call above may
            // have partially succeeded), then release the lock so the
            // counter stays balanced even on the failure path.
            try {
                jmdns?.close()
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                // swallowed: original failure is the one we care about
            }
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
     */
    public fun browse(): Flow<DiscoveryEvent> =
        callbackFlow {
            locks.acquire()
            val jmdns = jmdnsProvider("browse")
            val started = AtomicBoolean(true)

            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        // JmDNS frequently fires "added" with no TXT data attached
                        // yet. Triggering an explicit info request causes JmDNS to
                        // send a follow-up DNS-SD query and to redeliver via
                        // serviceResolved when the TXT info is in.
                        // The third argument flips JmDNS into persistent re-query mode so it keeps
                        // sending DNS-SD info requests until TXT data is in or the listener is removed.
                        jmdns.requestServiceInfo(event.type, event.name, true)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        trySend(DiscoveryEvent.Lost(event.name)).isSuccess
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val resolved = toDiscoveredService(event.info ?: return) ?: return
                        trySend(DiscoveryEvent.Resolved(resolved)).isSuccess
                    }
                }

            jmdns.addServiceListener(QuickShareMdns.SERVICE_TYPE, listener)

            awaitClose {
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
        ): Discovery = Discovery(locks, jmdnsProvider)

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
}

/** Production [LockController] backed by [MulticastLockHolder]. */
internal class MulticastLockController(
    private val holder: MulticastLockHolder,
) : LockController {
    override fun acquire(): Unit = holder.acquire()

    override fun release(): Unit = holder.release()
}

/**
 * Concrete [AdvertiseHandle] that owns one JmDNS instance and one share of
 * the multicast lock. Kept package-private — callers receive it only via
 * the [AdvertiseHandle] interface returned from [Discovery.advertise].
 */
internal class JmdnsAdvertiseHandle(
    private val jmdns: JmDNS,
    private val serviceInfo: ServiceInfo,
    override val instanceName: String,
    override val port: Int,
    private val onClose: () -> Unit,
) : AdvertiseHandle {
    private val active = AtomicBoolean(true)

    override val isActive: Boolean get() = active.get()

    override fun close() {
        if (!active.compareAndSet(true, false)) return
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
        onClose()
    }
}
