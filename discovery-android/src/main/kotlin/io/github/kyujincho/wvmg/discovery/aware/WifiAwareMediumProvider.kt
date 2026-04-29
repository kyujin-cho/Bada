/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("InlinedApi", "MissingPermission", "NewApi")

// Wi-Fi Aware passphrase length / IPv6 byte length are well-known.
// The platform lifecycle is inherently multi-step async; suppressing
// detekt's per-method ReturnCount / CyclomaticComplexity rules at the
// file level keeps the bring-up code readable as a single linear flow
// rather than fragmenting it into cross-cutting helpers that hide the
// overall ordering.
@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "FunctionOnlyReturningConstant",
)

package io.github.kyujincho.wvmg.discovery.aware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Aware (NAN) [MediumProvider] for Android 8.0+ devices whose
 * chipset advertises `PackageManager.FEATURE_WIFI_AWARE`. Hardware
 * coverage is patchy (most flagships from 2020+, very few mid-range
 * chipsets) — the provider is best-effort and falls back to other
 * mediums via [isSupported] returning `false` when the platform path is
 * unavailable.
 *
 * ### Topology
 *
 * Wi-Fi Aware uses publish/subscribe service discovery; the publisher
 * (server / receiver) advertises a service name, the subscriber (client
 * / sender) matches against it, then both sides bring up an IPv6
 * link-local data path through `ConnectivityManager.requestNetwork(...)`
 * with a [WifiAwareNetworkSpecifier]. Per the Quick Share wire format,
 * the publisher embeds its bound TCP port and IPv6 address into the
 * `WifiAwareCredentials.service_info` field of `UpgradePathInfo` (see
 * [io.github.kyujincho.wvmg.protocol.connection.BandwidthUpgradeFrames]
 * for the byte layout); the subscriber reads them back, requests a
 * passphrase-secured network, and connects via
 * `Network.getSocketFactory().createSocket(addr, port)`.
 *
 * ### Hardware/SDK gating
 *
 * [isSupported] returns true iff:
 *   - `Build.VERSION.SDK_INT >= 26` (Wi-Fi Aware APIs landed in O).
 *   - `PackageManager.hasSystemFeature(FEATURE_WIFI_AWARE)` is true
 *     (chipset declares aware capability).
 *   - `WifiAwareManager.isAvailable()` is true (Wi-Fi is on and the
 *     framework hasn't temporarily disabled aware).
 *   - The runtime location/nearby permission is granted (see
 *     [hasDiscoveryPermission]). On API 33+ we accept either
 *     `NEARBY_WIFI_DEVICES` or `ACCESS_FINE_LOCATION`; on older
 *     releases only the legacy `ACCESS_FINE_LOCATION` is checked.
 *
 * Any of these failing returns `false` and the framework selects the
 * next-best ladder rung. The check is O(1), as the [MediumRegistry]
 * contract requires.
 *
 * ### Lifecycle
 *
 * The platform [WifiAwareSession] and any [DiscoverySession] /
 * [Network] objects stay alive until the returned [WifiAwareTransport]
 * closes. The server side keeps one pending reservation between
 * [prepareUpgrade] and [acceptUpgrade], matching the generic
 * [MediumProvider] contract.
 *
 * ### Testability
 *
 * The platform surface is wrapped behind [WifiAwareSupport]. Tests on
 * a plain JVM construct a fake support that drives the lifecycle
 * without touching `android.*`. The production constructor builds the
 * default Android-backed support from a [Context].
 *
 * @param support platform abstraction; tests inject a fake.
 * @param logger leveled log sink. Defaults to a [Log.i] / [Log.w]
 *   adapter under the [TAG]. Tests inject a recording sink.
 */
public class WifiAwareMediumProvider internal constructor(
    private val support: WifiAwareSupport,
    private val logger: AwareLogger = AndroidAwareLogger,
) : MediumProvider {
    /**
     * Production constructor. Wraps a real [WifiAwareManager] and
     * [ConnectivityManager] obtained from the application context.
     */
    public constructor(context: Context) : this(
        support = AndroidWifiAwareSupport(context.applicationContext),
    )

    override val medium: Medium = Medium.WIFI_AWARE

    /**
     * Hardware + SDK + permission gate. See class KDoc for the full
     * predicate list. Must remain O(1); cached state inside [support]
     * makes this safe.
     */
    override fun isSupported(): Boolean = support.isAvailable()

    /**
     * **Server role.** Stand up a Wi-Fi Aware publisher, bind an IPv6
     * `ServerSocket` on the data-path interface, and return the
     * credentials needed by the subscriber to dial back in.
     *
     * Returns `null` when:
     *   - The chipset stops advertising aware between [isSupported]
     *     and this call (Wi-Fi turned off mid-session).
     *   - Attach to [WifiAwareManager] times out.
     *   - The publisher session never receives a subscriber match
     *     within [PREPARE_TIMEOUT_MS].
     *   - Any platform callback reports failure.
     *
     * On any failure path the provider releases the partially-acquired
     * resources before returning `null` — the framework treats this as
     * "fall back to the next rung" without leaking sessions.
     */
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        if (!support.isAvailable()) {
            logger.warn("prepareUpgrade: Wi-Fi Aware not available; refusing upgrade")
            return null
        }
        // Defer to support so a fake can short-circuit and a future
        // production refactor can swap the real implementation without
        // touching the call site.
        return support.prepareUpgrade(generatePassphrase())
    }

    /**
     * **Client role.** Subscribe to the publisher's service, request a
     * passphrase-secured Wi-Fi Aware network, and return a
     * [WifiAwareTransport] wrapping the connected [Socket].
     *
     * @param credentials Must be a [UpgradePathCredentials.WifiAware]
     *   produced by the receiver; any other subtype is rejected with
     *   `null` (defensive — the framework should never route a wrong
     *   medium here, but the contract on [MediumProvider.adoptUpgrade]
     *   requires the provider validate this).
     */
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
        if (credentials !is UpgradePathCredentials.WifiAware) {
            logger.warn("adoptUpgrade: refusing non-WifiAware credentials ($credentials)")
            return null
        }
        if (!support.isAvailable()) {
            logger.warn("adoptUpgrade: Wi-Fi Aware not available; refusing")
            return null
        }
        val handle = support.adoptUpgrade(credentials) ?: return null
        return WifiAwareTransport(handle.socket, handle.teardown)
    }

    override suspend fun acceptUpgrade(): UpgradedTransport? {
        val handle = support.acceptUpgrade() ?: return null
        return WifiAwareTransport(handle.socket, handle.teardown)
    }

    override fun cancelPendingUpgrade() {
        support.cancelPendingUpgrade()
    }

    /**
     * Generate a fresh per-upgrade passphrase. Wi-Fi Aware requires
     * 8..63 ASCII chars; we pick 32 chars from a URL-safe alphabet to
     * stay well above any platform minimum without bumping into
     * encoding corner cases on older Android releases.
     */
    private fun generatePassphrase(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        val out = StringBuilder(PASSPHRASE_BYTES)
        for (b in bytes) {
            out.append(PASSPHRASE_ALPHABET[(b.toInt() and 0xFF) % PASSPHRASE_ALPHABET.length])
        }
        return out.toString()
    }

    public companion object {
        private const val TAG: String = "WvmgWifiAware"

        /**
         * Attach + publish/subscribe combined timeout. The platform
         * sometimes takes a couple of seconds for the chipset to come
         * up after a cold start; 10 s comfortably covers cold-start
         * worst case while still bailing if the chipset is wedged.
         */
        public const val PREPARE_TIMEOUT_MS: Long = 10_000L

        /** Same magnitude as [PREPARE_TIMEOUT_MS], matching upgrade adoption. */
        public const val ADOPT_TIMEOUT_MS: Long = 10_000L

        /**
         * Quick Share service name used for the publish/subscribe
         * pairing. Stable string so a stock peer that hard-codes the
         * same name (none does today, but the protocol allows it) can
         * interoperate.
         */
        public const val SERVICE_NAME: String = "wvmg-quickshare-aware"

        /** Number of ASCII chars in a generated passphrase. */
        private const val PASSPHRASE_BYTES: Int = 32

        /**
         * Alphabet for [generatePassphrase]. URL-safe base64 chars,
         * biased only by the `% length` reduction (negligible on a 32-byte
         * passphrase).
         */
        private const val PASSPHRASE_ALPHABET: String =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private val SECURE_RANDOM: SecureRandom = SecureRandom()
    }
}

/**
 * Concrete [UpgradedTransport] for Wi-Fi Aware. Wraps the [Socket]
 * connected over the bring-up data-path so the framework's
 * SecureChannel rebuild step (#54) can read/write through it.
 */
public class WifiAwareTransport(
    public val socket: Socket,
    private val teardown: () -> Unit = {},
) : UpgradedTransport {
    override val medium: Medium = Medium.WIFI_AWARE

    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    override fun close() {
        runCatching { socket.close() }
        runCatching { teardown() }
    }
}

/**
 * Connected Wi-Fi Aware socket plus the platform resources that must
 * stay alive while the socket is in use.
 */
public data class WifiAwareSocketHandle(
    val socket: Socket,
    val teardown: () -> Unit,
)

/**
 * Platform-abstraction surface so the provider can be unit-tested
 * without `android.*`. Production binding lives in
 * [AndroidWifiAwareSupport]; tests inject a fake.
 */
public interface WifiAwareSupport {
    /** Combined hardware + SDK + permission availability gate. */
    public fun isAvailable(): Boolean

    /**
     * Run the publisher-side bring-up. Returns the credentials the
     * subscriber needs, or `null` on any failure. Implementations
     * MUST release any partial resources (sessions, sockets) before
     * returning.
     */
    public suspend fun prepareUpgrade(passphrase: String): UpgradePathCredentials.WifiAware?

    /**
     * Run the subscriber-side bring-up against [credentials]. Returns
     * a connected socket handle on success, `null` on failure (after
     * releasing any partial resources).
     */
    public suspend fun adoptUpgrade(credentials: UpgradePathCredentials.WifiAware): WifiAwareSocketHandle?

    /**
     * Accept the subscriber's TCP connection on the server-side Aware
     * data path prepared by [prepareUpgrade].
     */
    public suspend fun acceptUpgrade(): WifiAwareSocketHandle? = null

    /** Release any server-side pending Aware data path resources. */
    public fun cancelPendingUpgrade() {}
}

/**
 * Production [WifiAwareSupport] backed by the Android Wi-Fi Aware
 * stack. Gated to API 26+ at runtime via [Build.VERSION.SDK_INT]
 * checks; older devices return `false` from [isAvailable] without ever
 * touching the API surface.
 *
 * The actual session lifecycle is verbose (attach, publish, match,
 * network request, accept/connect). It lives here, behind the
 * interface, so the lifecycle complexity does not bleed into the
 * pure-Kotlin provider.
 */
public class AndroidWifiAwareSupport(
    private val context: Context,
) : WifiAwareSupport {
    private val handlerThread: HandlerThread by lazy {
        HandlerThread("WvmgWifiAwareCb").apply { start() }
    }
    private val handler: Handler by lazy { Handler(handlerThread.looper) }
    private val pendingServer: AtomicReference<PendingAwareServer?> = AtomicReference(null)

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
        val mgr =
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return false
        if (!mgr.isAvailable) return false
        return hasDiscoveryPermission()
    }

    /**
     * Returns true iff the runtime permission needed for Wi-Fi Aware
     * discovery is granted. On API 33+ either `NEARBY_WIFI_DEVICES`
     * (preferred — flagged `neverForLocation`) or the legacy
     * `ACCESS_FINE_LOCATION` is acceptable; on older releases only
     * `ACCESS_FINE_LOCATION` works.
     */
    private fun hasDiscoveryPermission(): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (fine) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ],
    )
    @Suppress("LongMethod") // Wi-Fi Aware lifecycle is inherently long; splitting hides intent.
    override suspend fun prepareUpgrade(passphrase: String): UpgradePathCredentials.WifiAware? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "prepareUpgrade: responder-side peerless data path requires API 31+")
            return null
        }
        val mgr =
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return null
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

        cancelPendingUpgrade()
        val attached =
            attachSession(mgr) ?: run {
                Log.w(TAG, "prepareUpgrade: Wi-Fi Aware attach failed or timed out")
                return null
            }

        var publishSession: PublishDiscoverySession? = null
        var serverSocket: ServerSocket? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var completed = false
        try {
            publishSession =
                publish(attached, WifiAwareMediumProvider.SERVICE_NAME)
                    ?: return null
            // Bind a server socket on the IPv6 wildcard so any data-path
            // interface (the platform picks one when the network is
            // requested) can route the inbound connect.
            serverSocket = ServerSocket(0, BACKLOG, InetAddress.getByName("::"))
            val boundPort = serverSocket.localPort

            val specifier =
                buildPublisherAwareNetworkSpecifier(
                    publishSession = publishSession,
                    passphrase = passphrase,
                    port = boundPort,
                ) ?: return null
            val (req, cb, deferred) = buildNetworkRequest(specifier)
            cm.requestNetwork(req, cb)
            networkCallback = cb

            val networkResult =
                withTimeoutOrNullCompat(WifiAwareMediumProvider.PREPARE_TIMEOUT_MS) { deferred.await() }
                    ?: run {
                        Log.w(TAG, "prepareUpgrade: network request timed out")
                        return null
                    }
            val previous =
                pendingServer.getAndSet(
                    PendingAwareServer(
                        serverSocket = serverSocket,
                        connectivityManager = cm,
                        networkCallback = cb,
                        publishSession = publishSession,
                        awareSession = attached,
                    ),
                )
            previous?.teardown()
            completed = true

            return UpgradePathCredentials.WifiAware(
                serviceName = WifiAwareMediumProvider.SERVICE_NAME,
                ipv6Address = networkResult.ipv6Address,
                port = boundPort,
                passphrase = passphrase,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "prepareUpgrade: failure", t)
            return null
        } finally {
            if (!completed) {
                networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                publishSession?.close()
                attached.close()
                runCatching { serverSocket?.close() }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ],
    )
    @Suppress("LongMethod")
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials.WifiAware): WifiAwareSocketHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val mgr =
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return null
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

        val attached =
            attachSession(mgr) ?: run {
                Log.w(TAG, "adoptUpgrade: Wi-Fi Aware attach failed or timed out")
                return null
            }

        var subscribeHandle: DiscoveryHandle<SubscribeDiscoverySession>? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var completed = false
        try {
            subscribeHandle =
                subscribe(attached, credentials.serviceName)
                    ?: return null
            val match =
                withTimeoutOrNullCompat(WifiAwareMediumProvider.ADOPT_TIMEOUT_MS) {
                    subscribeHandle.matches.await()
                } ?: run {
                    Log.w(TAG, "adoptUpgrade: publisher match timed out")
                    return null
                }
            val specifier =
                buildPeerAwareNetworkSpecifier(
                    discoverySession = subscribeHandle.session,
                    peerHandle = match.peerHandle,
                    passphrase = credentials.passphrase,
                    // Subscriber side does not bind a server socket; the
                    // builder ignores port for the subscriber role.
                    port = null,
                ) ?: return null
            val (req, cb, deferred) = buildNetworkRequest(specifier)
            cm.requestNetwork(req, cb)
            networkCallback = cb

            val networkResult =
                withTimeoutOrNullCompat(WifiAwareMediumProvider.ADOPT_TIMEOUT_MS) { deferred.await() }
                    ?: run {
                        Log.w(TAG, "adoptUpgrade: network request timed out")
                        return null
                    }

            // Connect via the requested Network's socket factory so the
            // OS routes the TCP through the Aware interface.
            // scope_id = 0; the Network's socket factory attaches the
            // correct interface scope when the socket is created.
            val targetBytes =
                if (credentials.ipv6Address.isUnspecifiedIpv6()) {
                    networkResult.ipv6Address
                } else {
                    credentials.ipv6Address
                }
            val target = Inet6Address.getByAddress(null, targetBytes, 0)
            val socket =
                networkResult.network
                    .socketFactory
                    .createSocket(target, credentials.port)
            completed = true
            return WifiAwareSocketHandle(socket) {
                networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                subscribeHandle.session.close()
                attached.close()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "adoptUpgrade: failure", t)
            return null
        } finally {
            if (!completed) {
                networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                subscribeHandle?.session?.close()
                attached.close()
            }
        }
    }

    override suspend fun acceptUpgrade(): WifiAwareSocketHandle? =
        withContext(Dispatchers.IO) {
            val pending = pendingServer.get() ?: return@withContext null
            try {
                val socket = pending.serverSocket.accept()
                runCatching { pending.serverSocket.close() }
                WifiAwareSocketHandle(socket) {
                    cancelPendingUpgrade()
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "acceptUpgrade: failure", t)
                cancelPendingUpgrade()
                null
            }
        }

    override fun cancelPendingUpgrade() {
        pendingServer.getAndSet(null)?.teardown()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun attachSession(mgr: WifiAwareManager): WifiAwareSession? {
        val deferred = CompletableDeferred<WifiAwareSession?>()
        val cb =
            object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    deferred.complete(session)
                }

                override fun onAttachFailed() {
                    deferred.complete(null)
                }
            }
        try {
            mgr.attach(cb, handler)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "attach: throw", t)
            return null
        }
        return withTimeoutOrNullCompat(WifiAwareMediumProvider.PREPARE_TIMEOUT_MS) { deferred.await() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun publish(
        session: WifiAwareSession,
        serviceName: String,
    ): PublishDiscoverySession? {
        val deferred = CompletableDeferred<PublishDiscoverySession?>()
        val cb =
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(s: PublishDiscoverySession) {
                    deferred.complete(s)
                }

                override fun onSessionConfigFailed() {
                    deferred.complete(null)
                }
            }
        val config = PublishConfig.Builder().setServiceName(serviceName).build()
        session.publish(config, cb, handler)
        return withTimeoutOrNullCompat(WifiAwareMediumProvider.PREPARE_TIMEOUT_MS) { deferred.await() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun subscribe(
        session: WifiAwareSession,
        serviceName: String,
    ): DiscoveryHandle<SubscribeDiscoverySession>? {
        val deferred = CompletableDeferred<SubscribeDiscoverySession?>()
        val matches = CompletableDeferred<Match>()
        val cb =
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                    deferred.complete(s)
                }

                override fun onSessionConfigFailed() {
                    deferred.complete(null)
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: MutableList<ByteArray>,
                ) {
                    if (!matches.isCompleted) {
                        matches.complete(Match(peerHandle))
                    }
                }

                override fun onServiceDiscoveredWithinRange(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: MutableList<ByteArray>,
                    distanceMm: Int,
                ) {
                    if (!matches.isCompleted) {
                        matches.complete(Match(peerHandle))
                    }
                }
            }
        val config = SubscribeConfig.Builder().setServiceName(serviceName).build()
        session.subscribe(config, cb, handler)
        val started =
            withTimeoutOrNullCompat(WifiAwareMediumProvider.PREPARE_TIMEOUT_MS) {
                deferred.await()
            } ?: return null
        return DiscoveryHandle(started, matches)
    }

    /**
     * Build a [WifiAwareNetworkSpecifier] for the given role + peer +
     * passphrase. Uses the API 29+ [WifiAwareNetworkSpecifier.Builder]
     * when available (lets us pin a server-side port for the publisher
     * role), falling back to the deprecated
     * [DiscoverySession.createNetworkSpecifierPassphrase] on older
     * releases.
     *
     * @param port Server-side TCP port the publisher bound to. Only
     *   meaningful for the publisher role; ignored for subscribers.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPeerAwareNetworkSpecifier(
        discoverySession: DiscoverySession,
        peerHandle: PeerHandle,
        passphrase: String,
        port: Int?,
    ): android.net.NetworkSpecifier? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            builder.setPskPassphrase(passphrase)
            if (port != null && port in 1..0xFFFF) {
                builder.setPort(port)
            }
            return builder.build()
        }
        // API 26..28 fallback: createNetworkSpecifierPassphrase on
        // DiscoverySession takes only (peerHandle, passphrase). The
        // role (publisher / subscriber) is inferred from the session
        // type (publish session => responder, subscribe session =>
        // initiator). The pre-API-29 path cannot pin a publisher port,
        // so the publisher's caller will need to pick an ephemeral port
        // and read it back from the bound ServerSocket — which is what
        // the prepareUpgrade body above already does.
        @Suppress("DEPRECATION")
        return discoverySession.createNetworkSpecifierPassphrase(peerHandle, passphrase)
    }

    /**
     * Build the responder-side peerless network specifier. Android only
     * exposes this shape from API 31 onward; older devices fall back to
     * the next medium because the server cannot prepare a usable Aware
     * data path before the subscriber has received credentials.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPublisherAwareNetworkSpecifier(
        publishSession: PublishDiscoverySession,
        passphrase: String,
        port: Int,
    ): android.net.NetworkSpecifier? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val builder = WifiAwareNetworkSpecifier.Builder(publishSession)
        builder.setPskPassphrase(passphrase)
        builder.setPort(port)
        builder.setTransportProtocol(IPPROTO_TCP)
        return builder.build()
    }

    private fun buildNetworkRequest(
        specifier: android.net.NetworkSpecifier,
    ): Triple<NetworkRequest, ConnectivityManager.NetworkCallback, CompletableDeferred<NetworkResult>> {
        val deferred = CompletableDeferred<NetworkResult>()
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                @Volatile
                private var pendingNetwork: Network? = null

                override fun onAvailable(network: Network) {
                    pendingNetwork = network
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    val ipv6 = firstIpv6(linkProperties)
                    if (ipv6 != null && !deferred.isCompleted) {
                        deferred.complete(NetworkResult(network, ipv6))
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    nc: NetworkCapabilities,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val info = nc.transportInfo as? WifiAwareNetworkInfo
                        val ipv6 = info?.peerIpv6Addr?.address
                        if (ipv6 != null && !deferred.isCompleted) {
                            deferred.complete(NetworkResult(network, ipv6))
                        }
                    }
                }

                override fun onUnavailable() {
                    // Cancel the deferred so the caller's
                    // withTimeoutOrNullCompat resolves to null without
                    // waiting for the full timeout. We avoid completing
                    // with a fake NetworkResult here because that would
                    // give the caller a network whose socketFactory
                    // throws on use; null-via-timeout is cleaner.
                    deferred.cancel()
                }
            }
        val req =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
        return Triple(req, cb, deferred)
    }

    private fun firstIpv6(linkProperties: LinkProperties): ByteArray? {
        for (la in linkProperties.linkAddresses) {
            val addr = la.address
            if (addr is Inet6Address && addr.isLinkLocalAddress) {
                return addr.address
            }
        }
        return null
    }

    /**
     * Matched-peer descriptor passed between the discovery callback and
     * the network-request stage.
     */
    private data class Match(
        val peerHandle: PeerHandle,
    )

    /**
     * Discovery session plus the first peer match observed by its
     * callback.
     */
    private data class DiscoveryHandle<T : DiscoverySession>(
        val session: T,
        val matches: CompletableDeferred<Match>,
    )

    /**
     * Server-side resources that must stay alive after
     * [prepareUpgrade] returns and until the accepted transport closes.
     */
    private data class PendingAwareServer(
        val serverSocket: ServerSocket,
        val connectivityManager: ConnectivityManager,
        val networkCallback: ConnectivityManager.NetworkCallback,
        val publishSession: PublishDiscoverySession,
        val awareSession: WifiAwareSession,
    ) {
        fun teardown() {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            runCatching { serverSocket.close() }
            publishSession.close()
            awareSession.close()
        }
    }

    /**
     * Resolution of a `requestNetwork` call: the bound [Network] plus
     * the peer-side IPv6 address the caller will dial.
     */
    private data class NetworkResult(
        val network: Network,
        val ipv6Address: ByteArray,
    )

    private companion object {
        const val TAG = "WvmgWifiAware"
        const val BACKLOG = 1
        const val IPPROTO_TCP = 6
    }
}

private fun ByteArray.isUnspecifiedIpv6(): Boolean = size == IPV6_ADDRESS_LENGTH && all { it == 0.toByte() }

private const val IPV6_ADDRESS_LENGTH: Int = 16

/**
 * Small kotlinx.coroutines wrapper so [withTimeoutOrNull] usage is
 * uniform across the callsites and so a future profile-driven retry
 * policy can attach in one place.
 */
private suspend fun <T> withTimeoutOrNullCompat(
    timeoutMillis: Long,
    block: suspend () -> T,
): T? =
    try {
        withTimeoutOrNull(timeoutMillis) { block() }
    } catch (
        @Suppress("SwallowedException") _: TimeoutCancellationException,
    ) {
        null
    }

/**
 * Logger surface so unit tests can assert without depending on
 * `android.util.Log`. Production binding is [AndroidAwareLogger].
 */
public interface AwareLogger {
    public fun warn(message: String)
}

internal object AndroidAwareLogger : AwareLogger {
    override fun warn(message: String) {
        Log.w("WvmgWifiAware", message)
    }
}
