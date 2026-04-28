/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level Quick Share mDNS API.
 *
 * Two operations are exposed:
 *
 *  1. [advertise] — publish ourselves as a Quick Share peer for the
 *     duration of the returned [AdvertiseHandle]. The TXT record carries
 *     the binary [EndpointInfo] under [QuickShareMdns.TXT_KEY_ENDPOINT_INFO]
 *     (see [InstanceName] for the wire format of the instance name).
 *  2. [browse] — observe other peers as a [Flow] of [DiscoveryEvent]s.
 *     The flow is hot for the duration it is collected: starting
 *     collection opens a new `NsdManager` discovery session, and
 *     cancelling the collection tears it back down.
 *
 * ### Migrated from JmDNS to NsdManager (#98)
 *
 * Phase 1 used JmDNS to publish and browse mDNS records out of the app
 * process; the app held a `WifiManager.MulticastLock` so the Wi-Fi chip
 * would not drop inbound multicast while the screen was off. On vivo
 * Funtouch / OriginOS (and likely other Chinese-OEM Android skins), the
 * radio layer silently drops inbound IPv4 multicast for non-system apps
 * regardless of `MulticastLock` state, breaking JmDNS browse entirely.
 *
 * `NsdManager` delegates publish + browse to the system mDNS responder
 * process, which has the multicast filter exemption baked in. The app
 * no longer needs a multicast lock at all — see the issue #98 PR
 * description for the matching `dumpsys wifi` evidence.
 *
 * Diagnostics: call [snapshot] to obtain a [DiscoveryDiagnostics]
 * describing the current advertise / browse state and the most recent
 * NsdManager events. The receiver service logs this periodically to
 * make silent failures visible in logcat via the `WvmgDiscovery` tag.
 */
public class Discovery internal constructor(
    private val registrar: NsdRegistrar,
    private val browser: NsdBrowser,
    private val networkWatcherFactory: NetworkWatcherFactory = NetworkWatcherFactory.NoOp,
) {
    private val diagnostics: DiagnosticsState = DiagnosticsState()

    /**
     * Production constructor. Wires up the platform [NsdManager] under
     * the supplied [Context]; only the application context is retained.
     */
    public constructor(context: Context) : this(
        registrar = AndroidNsdRegistrar(systemNsdManager(context)),
        browser = AndroidNsdBrowser(systemNsdManager(context)),
        networkWatcherFactory = AndroidNetworkWatcherFactory(context.applicationContext),
    )

    /**
     * Returns a snapshot of the current discovery state. See
     * [DiscoveryDiagnostics] for the fields returned. Post-#98 the
     * `multicastLockHeld` field always reports `false`; the receiver
     * service no longer holds an in-process multicast lock because
     * `NsdManager` runs in the system mDNS responder process which has
     * the multicast filter exemption baked in.
     */
    public fun snapshot(): DiscoveryDiagnostics = diagnostics.snapshot(multicastLockHeld = false)

    /**
     * Publish this device as a Quick Share peer.
     *
     * The TXT record is set with the canonical [QuickShareMdns.TXT_KEY_ENDPOINT_INFO]
     * key whose value is the **URL-safe-base64 (no padding)** encoding
     * of the binary-serialised [endpointInfo]. This matches the format
     * google/nearby's `WifiLanServiceInfo` reads (`Base64Utils::Decode`
     * on the TXT value, see `connections/implementation/wifi_lan_service_info.cc`).
     * Publishing raw bytes — which we did before — caused GMS Nearby on
     * a Galaxy peer to log `EndpointParsingFailure` and drop us from
     * the picker even though our service was visible at the NSD layer.
     * The instance name is generated freshly on every call via
     * [InstanceName.generate].
     *
     * Implementation notes:
     *  - The synchronous publish happens through `NsdManager.registerService`,
     *    which delegates to the system mDNS responder process. The
     *    helper here suspends until the platform fires
     *    `onServiceRegistered` (or, on failure, `onRegistrationFailed`).
     *  - Android may auto-suffix the instance name on collision (`" (1)"`,
     *    `" (2)"`, …); the returned [AdvertiseHandle.instanceName] is
     *    the actual published name, not necessarily the requested one.
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
        // GMS Nearby's WifiLanServiceInfo (google/nearby
        // `wifi_lan_service_info.cc`) calls `Base64Utils::Decode` on the
        // TXT `n` value before parsing. We therefore publish the
        // base64-url (no padding) string as US-ASCII bytes; the
        // base64 alphabet is a strict subset of ASCII so the bytes
        // round-trip cleanly through any String/byte[] mDNS attribute
        // bridge on the platform.
        val encodedEndpointInfo =
            Base64Url.encode(endpointInfo.serialize()).toByteArray(Charsets.US_ASCII)
        val attributes = mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to encodedEndpointInfo)

        Log.i(TAG, "advertise: starting publish instance=$instanceName port=$port")

        return try {
            val handle =
                NsdAdvertiseHandle(
                    request =
                        NsdAdvertiseRequest(
                            requestedInstanceName = instanceName,
                            port = port,
                            attributes = attributes,
                            serviceType = QuickShareMdns.SERVICE_TYPE_NSD,
                        ),
                    registrar = registrar,
                    networkWatcherFactory = networkWatcherFactory,
                    diagnostics = diagnostics,
                    onClose = {
                        diagnostics.setAdvertising(false)
                        diagnostics.setAdvertiseBound(null)
                    },
                )
            diagnostics.setAdvertising(true)
            handle
        } catch (
            // The registrar surfaces I/O-style failures as IOException
            // and cancellation as CancellationException; we let the
            // caller handle either by rethrowing the original cause.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.e(TAG, "advertise: failed to publish instance=$instanceName", t)
            diagnostics.setAdvertising(false)
            diagnostics.setAdvertiseBound(null)
            throw t
        }
    }

    /**
     * Observe Quick Share peers on the LAN.
     *
     * The returned [Flow] is cold; collecting it spins up a new
     * `NsdManager.discoverServices` session and re-emits resolved
     * services as [DiscoveryEvent.Resolved]. Cancelling the collector
     * stops the discovery session.
     *
     * Loopback peers are filtered out — when our own advertisement is
     * also active this flow would otherwise echo back our own service,
     * which is never useful to higher layers.
     */
    public fun browse(): Flow<DiscoveryEvent> =
        callbackFlow {
            Log.i(TAG, "browse: starting collector")
            diagnostics.setBrowsing(true)
            diagnostics.setBrowseBound(null)

            val collector =
                launch {
                    browser.discover(QuickShareMdns.SERVICE_TYPE_NSD).collect { event ->
                        when (event) {
                            is NsdBrowserEvent.Found -> {
                                Log.d(TAG, "browse: serviceFound name=${event.instanceName}")
                                diagnostics.recordEvent(
                                    DiagnosticEvent(
                                        kind = DiagnosticEvent.Kind.ADDED,
                                        instanceName = event.instanceName,
                                        timestampMillis = System.currentTimeMillis(),
                                    ),
                                )
                            }

                            is NsdBrowserEvent.Resolved -> {
                                Log.d(
                                    TAG,
                                    "browse: serviceResolved name=${event.instanceName} " +
                                        "addrs=${event.addresses.joinToString { it.hostAddress }} " +
                                        "port=${event.port}",
                                )
                                diagnostics.recordEvent(
                                    DiagnosticEvent(
                                        kind = DiagnosticEvent.Kind.RESOLVED,
                                        instanceName = event.instanceName,
                                        timestampMillis = System.currentTimeMillis(),
                                    ),
                                )
                                val resolved = toDiscoveredService(event) ?: return@collect
                                diagnostics.setBrowseBound(resolved.primaryAddress())
                                trySend(DiscoveryEvent.Resolved(resolved)).isSuccess
                            }

                            is NsdBrowserEvent.Lost -> {
                                Log.d(TAG, "browse: serviceLost name=${event.instanceName}")
                                diagnostics.recordEvent(
                                    DiagnosticEvent(
                                        kind = DiagnosticEvent.Kind.REMOVED,
                                        instanceName = event.instanceName,
                                        timestampMillis = System.currentTimeMillis(),
                                    ),
                                )
                                trySend(DiscoveryEvent.Lost(event.instanceName)).isSuccess
                            }

                            is NsdBrowserEvent.Error -> {
                                Log.w(
                                    TAG,
                                    "browse: NsdManager error instance=${event.instanceName ?: "<none>"} " +
                                        "msg=${event.message}",
                                )
                            }
                        }
                    }
                }

            awaitClose {
                Log.i(TAG, "browse: collector closing")
                collector.cancel()
                diagnostics.setBrowsing(false)
                diagnostics.setBrowseBound(null)
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Maps an [NsdBrowserEvent.Resolved] into our [DiscoveredService] DTO.
     * Returns `null` if the service is loopback-only (we don't want to
     * surface our own advertisement to higher layers). The TXT record
     * is parsed leniently — any decoding failure yields a `null`
     * [endpointInfo] but does **not** drop the discovery, since callers
     * may still want the address tuple for diagnostic display.
     */
    @Suppress("ReturnCount") // Three independent defensive guards plus the
    // happy path are clearer as early returns than nested when/if branches.
    internal fun toDiscoveredService(event: NsdBrowserEvent.Resolved): DiscoveredService? {
        // Defensive validation: a misbehaving mDNS responder (we have
        // seen this on a couple of OEM skins) can occasionally surface
        // a "resolved" event with port=0 or a blank instance name,
        // both of which would crash downstream callers that assume a
        // dialable peer. Drop those rather than emit a degenerate
        // DiscoveredService.
        if (event.instanceName.isBlank()) {
            Log.w(TAG, "browse: dropping resolved event with blank instanceName")
            return null
        }
        if (event.port !in 1..MAX_PORT) {
            Log.w(
                TAG,
                "browse: dropping resolved event ${event.instanceName} with invalid port=${event.port}",
            )
            return null
        }
        val addresses = event.addresses
        if (addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
            return null
        }

        val rawTxt = event.attributes[QuickShareMdns.TXT_KEY_ENDPOINT_INFO]
        val decodedTxt = decodeTxtEndpointInfo(rawTxt)
        val endpointInfo = decodedTxt?.let(EndpointInfo::parse)

        if (endpointInfo == null || endpointInfo.hidden) {
            logUnparseableEndpointInfo(event.instanceName, rawTxt, decodedTxt, endpointInfo)
        }

        val raw = InstanceName.decodeRawBytes(event.instanceName)
        val endpointId = raw?.let(InstanceName::extractEndpointId)

        return DiscoveredService(
            instanceName = event.instanceName,
            endpointId = endpointId,
            addresses = addresses,
            port = event.port,
            endpointInfo = endpointInfo,
        )
    }

    /**
     * Decode the raw bytes stored under TXT key `n` into the binary
     * EndpointInfo bytes that [EndpointInfo.parse] expects. Modern peers
     * (and post-fix WVMG builds) publish the value as URL-safe-base64
     * ASCII per google/nearby's `WifiLanServiceInfo`. Older peers (and
     * our own pre-fix builds) published it as raw binary; tolerate that
     * by falling back to the raw bytes when base64 decode fails.
     */
    private fun decodeTxtEndpointInfo(rawTxt: ByteArray?): ByteArray? {
        if (rawTxt == null) return null
        val asAscii = runCatching { String(rawTxt, Charsets.US_ASCII) }.getOrNull()
        return asAscii?.let(Base64Url::decode) ?: rawTxt
    }

    /**
     * Verbose logging for endpoint records that failed to parse or were
     * marked hidden. Splitting this off keeps [toDiscoveredService] under
     * detekt's cyclomatic-complexity threshold; the diagnostic surface
     * here is only useful when a peer drops us at the parse step (e.g.
     * issue #83 / GMS Nearby's `EndpointParsingFailure`).
     */
    private fun logUnparseableEndpointInfo(
        instanceName: String,
        rawTxt: ByteArray?,
        decodedTxt: ByteArray?,
        endpointInfo: EndpointInfo?,
    ) {
        val rawTxtHex = rawTxt?.joinToString("") { "%02x".format(it) } ?: "<no decode>"
        val decodedTxtHex = decodedTxt?.joinToString("") { "%02x".format(it) } ?: "<no decode>"
        Log.i(
            TAG,
            "EndpointInfo for $instanceName: " +
                "rawSize=${rawTxt?.size ?: 0} rawHex=$rawTxtHex " +
                "decodedSize=${decodedTxt?.size ?: 0} decodedHex=$decodedTxtHex " +
                "parsed=$endpointInfo",
        )
        if (decodedTxt != null && decodedTxt.isNotEmpty()) {
            @Suppress("MagicNumber") // Bit positions defined by PROTOCOL.md.
            val byte0 = decodedTxt[0].toInt() and 0xFF

            @Suppress("MagicNumber")
            val version = (byte0 ushr 5) and 0b111

            @Suppress("MagicNumber")
            val visibility = (byte0 ushr 4) and 0b1

            @Suppress("MagicNumber")
            val deviceType = (byte0 ushr 1) and 0b111
            val reserved = byte0 and 0b1
            Log.i(
                TAG,
                "EndpointInfo byte0=0x${"%02x".format(byte0)} " +
                    "version=$version visibility=$visibility " +
                    "deviceType=$deviceType reserved=$reserved",
            )
        }
    }

    public companion object {
        /** Maximum legal TCP port (`2^16 - 1`). */
        public const val MAX_PORT: Int = 0xFFFF

        /** logcat tag — shared with the rest of the discovery module. */
        internal const val TAG: String = "WvmgDiscovery"

        /**
         * Test-only factory that wires up [Discovery] with caller-supplied
         * [NsdRegistrar] / [NsdBrowser] fakes. The public constructor
         * remains the only path that consumes a [Context].
         */
        @JvmStatic
        internal fun forTesting(
            registrar: NsdRegistrar,
            browser: NsdBrowser,
            networkWatcherFactory: NetworkWatcherFactory = NetworkWatcherFactory.NoOp,
        ): Discovery = Discovery(registrar, browser, networkWatcherFactory)

        private fun systemNsdManager(context: Context): NsdManager =
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
}

/**
 * Aggregated registration request consumed by [NsdAdvertiseHandle].
 * Lifted into a value type so the handle's primary constructor stays
 * under detekt's `LongParameterList` cap.
 */
internal data class NsdAdvertiseRequest(
    val requestedInstanceName: String,
    val port: Int,
    val attributes: Map<String, ByteArray>,
    val serviceType: String,
)

/**
 * Concrete [AdvertiseHandle] that owns one [NsdRegistrationHandle].
 *
 * On Wi-Fi network changes (subscribed via [NetworkWatcher]) the
 * handle tears down the current registration and re-registers the same
 * service info. The system mDNS responder picks up the new IP from the
 * platform's `LinkProperties` automatically; the unregister/register
 * dance is here mainly to flush the responder's cache so peers see a
 * fresh advertise burst rather than waiting for the next periodic
 * announce. Re-registration is serialised by an internal lock so
 * concurrent network callbacks can't double-register or race with [close].
 */
internal class NsdAdvertiseHandle(
    private val request: NsdAdvertiseRequest,
    private val registrar: NsdRegistrar,
    networkWatcherFactory: NetworkWatcherFactory,
    private val diagnostics: DiagnosticsState,
    private val onClose: () -> Unit,
) : AdvertiseHandle {
    override val port: Int
        get() = request.port
    private val active = AtomicBoolean(true)
    private val lifecycle = Object()

    @Volatile
    private var current: NsdRegistrationHandle? = null

    @Volatile
    private var registeredName: String = request.requestedInstanceName

    private val watcher: NetworkWatcher? =
        networkWatcherFactory.create(onChanged = ::onNetworkChanged)

    init {
        // Register synchronously so the caller sees a fully-published
        // service the moment `advertise` returns. The runBlocking here
        // is bounded by `NsdManager`'s own platform timeout (~1 s on
        // recent AOSP builds); failure throws to the caller so the
        // outer Discovery.advertise can roll back its diagnostics state.
        register()
        watcher?.start()
    }

    override val instanceName: String
        get() = registeredName

    override val isActive: Boolean
        get() = active.get()

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        Log.i(Discovery.TAG, "advertise: closing handle instance=$registeredName")
        watcher?.stop()
        synchronized(lifecycle) {
            current?.close()
            current = null
        }
        onClose()
    }

    private fun onNetworkChanged() {
        if (!active.get()) return
        Log.i(Discovery.TAG, "advertise: network changed, re-registering instance=$registeredName")
        synchronized(lifecycle) {
            if (!active.get()) return@synchronized
            current?.close()
            current = null
            try {
                register()
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // Re-registration on a network change is best-effort: if
                // the new network isn't ready yet, NsdManager fails and
                // we'll get another network callback when the interface
                // fully comes up. Swallowing here keeps the handle
                // usable for the next callback.
                Log.w(Discovery.TAG, "advertise: re-register failed; will retry on next network event", t)
            }
        }
    }

    private fun register() {
        synchronized(lifecycle) {
            if (current != null) return
            // The registrar's register() is suspending so it can wait
            // for the platform's onServiceRegistered callback. We use
            // runBlocking inside the synchronized block because the
            // contract of [Discovery.advertise] is "publish synchronously
            // before returning"; the call is bounded by NsdManager's
            // own timeout.
            val handle =
                runBlocking {
                    registrar.register(
                        serviceType = request.serviceType,
                        instanceName = request.requestedInstanceName,
                        port = request.port,
                        attributes = request.attributes,
                    )
                }
            current = handle
            registeredName = handle.instanceName
            diagnostics.setAdvertiseBound(handle.hostAddress)
            Log.i(
                Discovery.TAG,
                "advertise: registered instance=$registeredName " +
                    "bound=${handle.hostAddress?.hostAddress ?: "unknown"} port=${request.port}",
            )
        }
    }
}

/**
 * A simple start/stop interface for an external network-change observer.
 * Implementations call back into the supplied lambda when the watched
 * network state changes; [NsdAdvertiseHandle] consumes that signal as
 * its "re-register the NSD service" trigger.
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
