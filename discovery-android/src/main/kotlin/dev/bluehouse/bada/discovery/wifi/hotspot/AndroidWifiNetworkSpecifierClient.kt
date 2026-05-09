/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MagicNumber") // Connect timeout / wait window are well-known constants.

package dev.bluehouse.bada.discovery.wifi.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Production [HotspotClient] backed by `WifiNetworkSpecifier` +
 * `ConnectivityManager.requestNetwork`.
 *
 * Only available on API 29+ (`WifiNetworkSpecifier` itself is API 29+).
 * On older devices the medium provider's wiring layer should null this
 * out and either fall back to the legacy `WifiManager.addNetwork` path
 * (we deliberately don't, because it pollutes the user's saved Wi-Fi
 * list and forces a global reconnect) or skip the client role entirely.
 *
 * The interesting trick: once the platform reports the network is
 * available via [ConnectivityManager.NetworkCallback.onAvailable], we
 * MUST open the socket through that specific [Network] —
 * `Network.getSocketFactory()` produces a `Socket` whose route is bound
 * to the new network. Without it, the kernel routes the connect
 * through whatever default network exists (mobile data, the previous
 * Wi-Fi association if it survived), which would fail because the
 * sender's gateway IP is only reachable on the hotspot subnet.
 */
@RequiresApi(Build.VERSION_CODES.Q)
public class AndroidWifiNetworkSpecifierClient(
    context: Context,
    /**
     * Maximum time to wait for the platform to report
     * [ConnectivityManager.NetworkCallback.onAvailable] after we file
     * the [NetworkRequest]. Includes the user pressing "Connect" on
     * the OEM consent dialog, so the default is generous.
     */
    private val joinTimeoutMillis: Long = 30_000L,
    /** TCP connect timeout once the route is bound. */
    private val connectTimeoutMillis: Int = 5_000,
) : HotspotClient {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("ReturnCount") // One early return per failure mode keeps the contract readable.
    override suspend fun join(credentials: UpgradePathCredentials.WifiHotspot): JoinResult? {
        val gateway =
            try {
                InetAddress.getByName(credentials.gateway).takeUnless { it.isAnyLocalAddress }
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "Invalid gateway address in upgrade credentials: ${credentials.gateway}", t)
                return null
            } ?: run {
                Log.w(TAG, "Gateway 0.0.0.0 (proto sentinel); receiver must use DHCP-supplied gateway.")
                return null
            }

        val (network, callback) = awaitNetwork(credentials) ?: return null

        val torndown = AtomicBoolean(false)
        val teardown: () -> Unit = {
            if (torndown.compareAndSet(false, true)) {
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "unregisterNetworkCallback threw", t)
                }
            }
        }

        val socket =
            try {
                network.socketFactory.createSocket().apply {
                    connect(java.net.InetSocketAddress(gateway, credentials.port), connectTimeoutMillis)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "TCP connect to hotspot server failed", t)
                teardown()
                return null
            }

        return JoinResult(socket = socket, teardown = teardown)
    }

    @Suppress("LongMethod") // The callback lifecycle is intrinsically procedural.
    private suspend fun awaitNetwork(
        credentials: UpgradePathCredentials.WifiHotspot,
    ): Pair<Network, ConnectivityManager.NetworkCallback>? =
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())

            val specifier =
                WifiNetworkSpecifier
                    .Builder()
                    .setSsid(credentials.ssid)
                    .setWpa2Passphrase(credentials.passphrase)
                    .build()

            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    // Local-only hotspots have no internet route; explicitly
                    // dropping NET_CAPABILITY_INTERNET stops the platform
                    // from waiting forever for a DNS / captive-portal probe
                    // to succeed before reporting onAvailable.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (resumed.compareAndSet(false, true)) {
                            cont.resume(network to this)
                        }
                    }

                    override fun onUnavailable() {
                        Log.w(TAG, "WifiNetworkSpecifier reported onUnavailable.")
                        if (resumed.compareAndSet(false, true)) {
                            try {
                                connectivityManager.unregisterNetworkCallback(this)
                            } catch (_: Exception) {
                                // Best-effort.
                            }
                            cont.resume(null)
                        }
                    }

                    override fun onLost(network: Network) {
                        // Only meaningful while we're still waiting for
                        // onAvailable; once onAvailable resumes, the
                        // teardown ownership transfers to the caller.
                        if (resumed.compareAndSet(false, true)) {
                            try {
                                connectivityManager.unregisterNetworkCallback(this)
                            } catch (_: Exception) {
                                // Best-effort.
                            }
                            cont.resume(null)
                        }
                    }
                }

            cont.invokeOnCancellation {
                if (resumed.compareAndSet(false, true)) {
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {
                        // Best-effort.
                    }
                }
            }

            try {
                connectivityManager.requestNetwork(request, callback, joinTimeoutMillis.toInt())
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "ConnectivityManager.requestNetwork threw", t)
                if (resumed.compareAndSet(false, true)) cont.resume(null)
                return@suspendCancellableCoroutine
            }

            // Defensive watchdog. requestNetwork's `timeoutMs` overload
            // is supposed to fire onUnavailable after the timeout, but
            // some OEM ROMs (vivo Funtouch in particular) silently drop
            // the timeout and never resume the callback. Schedule our
            // own fallback so the suspending caller is guaranteed to
            // make progress.
            mainHandler.postDelayed({
                if (resumed.compareAndSet(false, true)) {
                    Log.w(TAG, "Hotspot join timed out after ${joinTimeoutMillis}ms.")
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {
                        // Best-effort.
                    }
                    cont.resume(null)
                }
            }, joinTimeoutMillis + 1_000L)
        }

    private companion object {
        const val TAG = "BadaWifiHotspot"
    }
}
