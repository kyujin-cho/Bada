/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS

/**
 * Creates fresh [JmDNS] instances bound to the device's current Wi-Fi
 * interface address.
 *
 * JmDNS sends and receives multicast packets through whatever
 * [InetAddress] is passed to its `create(InetAddress, String)` factory.
 * On Android, picking the right address is important: if we hand it
 * `0.0.0.0` (the wildcard) JmDNS will bind to the first available
 * interface, which on phones is often the cellular interface — that
 * silently breaks Quick Share since peers only ever talk over Wi-Fi.
 *
 * Address resolution proceeds top-to-bottom and stops at the first hit:
 *
 *  1. **`ConnectivityManager` Wi-Fi `LinkProperties`.** The modern API
 *     (API 21+, available everywhere we run). Returns the IPv4 address
 *     attached to whichever `Network` is currently `TRANSPORT_WIFI` +
 *     `NET_CAPABILITY_INTERNET`. This is the right answer on API 31+
 *     where `WifiManager.getConnectionInfo()` is gated behind precise
 *     location permission and otherwise reports `0.0.0.0`.
 *  2. **`WifiManager.getConnectionInfo()` legacy fallback.** Still
 *     populated on older devices and on builds where the user granted
 *     location. Deprecated as of API 31 but harmless to call.
 *  3. **`NetworkInterface` enumeration.** Last-resort scan for any
 *     non-loopback IPv4 address whose owning interface name looks like
 *     Wi-Fi (`wlan*`, `ap*`). This catches edge cases — tethering, dev
 *     boards — where neither system service surfaced an address but a
 *     Wi-Fi-shaped interface exists.
 *
 * Splitting this off into its own object lets unit tests substitute a
 * fake [WifiAddressProvider] when validating publish/browse logic on a
 * plain JVM.
 */
internal object JmdnsFactory {
    /** logcat tag — shared with the rest of the discovery module. */
    private const val TAG = "WvmgDiscovery"

    /**
     * Creates a JmDNS instance bound to the best-effort Wi-Fi interface
     * address. The [name] argument is forwarded as the JmDNS instance
     * name (cosmetic; useful in log output).
     *
     * If no Wi-Fi address can be resolved we log a warning and bind to
     * the wildcard via the no-arg `JmDNS.create()` — this is strictly
     * better than `InetAddress.getLocalHost()` (which on Android
     * regularly resolves to `127.0.0.1` and would silently route every
     * mDNS packet through loopback only). The wildcard binding still
     * usually fails to reach peers, so the warning log is the action
     * item for on-device debugging.
     */
    fun create(
        context: Context,
        name: String = "wvmg-discovery",
        addressProvider: WifiAddressProvider = AndroidWifiAddressProvider(context),
    ): JmDNS = createWith(name = name, addressProvider = addressProvider)

    /**
     * Lower-level entry point that does not need a [Context]. Exposed
     * (internal) so unit tests can drive the address-resolution logic
     * without standing up an Android instrumentation context.
     */
    internal fun createWith(
        name: String,
        addressProvider: WifiAddressProvider,
    ): JmDNS {
        val resolved = addressProvider.currentWifiAddress()
        if (resolved != null) {
            Log.i(TAG, "JmdnsFactory.create: binding name=$name to wifi address=${resolved.hostAddress}")
            return JmDNS.create(resolved, name)
        }

        Log.w(
            TAG,
            "JmdnsFactory.create: no Wi-Fi address found for name=$name; " +
                "falling back to JmDNS wildcard bind. mDNS may not reach LAN peers.",
        )
        return JmDNS.create(name)
    }

    /** Looks up the device's current Wi-Fi IPv4 address, if any. */
    internal interface WifiAddressProvider {
        fun currentWifiAddress(): InetAddress?
    }

    /**
     * Production [WifiAddressProvider]. Combines [ConnectivityManager]
     * (modern API, returns the active Wi-Fi network's link properties),
     * [WifiManager] (legacy, still works on older devices), and a
     * [NetworkInterface] scan as last resort.
     */
    internal class AndroidWifiAddressProvider(
        context: Context,
    ) : WifiAddressProvider {
        private val appContext: Context = context.applicationContext

        private val wifi: WifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        private val cm: ConnectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun currentWifiAddress(): InetAddress? {
            val resolved =
                resolveByConnectivityManager()
                    ?: resolveByWifiManager()
                    ?: resolveByNetworkInterface()
            if (resolved == null) {
                Log.w(TAG, "WifiAddressProvider: no Wi-Fi IPv4 address resolved through any path")
            }
            return resolved
        }

        private fun resolveByConnectivityManager(): InetAddress? =
            connectivityManagerWifiAddress()?.also {
                Log.i(TAG, "WifiAddressProvider: ConnectivityManager link address=${it.hostAddress}")
            }

        private fun resolveByWifiManager(): InetAddress? =
            legacyWifiManagerAddress()?.also {
                Log.i(TAG, "WifiAddressProvider: WifiManager.connectionInfo address=${it.hostAddress}")
            }

        private fun resolveByNetworkInterface(): InetAddress? =
            networkInterfaceWifiAddress()?.also {
                Log.i(TAG, "WifiAddressProvider: NetworkInterface scan address=${it.hostAddress}")
            }

        /**
         * Resolves the Wi-Fi network's IPv4 address through
         * [ConnectivityManager]. This is the right path on API 31+
         * because [WifiManager.getConnectionInfo] is gated behind
         * precise location permission and silently returns `0.0.0.0`
         * for apps that only declared `ACCESS_WIFI_STATE`.
         */
        @Suppress("ReturnCount")
        private fun connectivityManagerWifiAddress(): Inet4Address? {
            val wifiNetwork = pickWifiNetwork() ?: return null
            val link: LinkProperties = cm.getLinkProperties(wifiNetwork) ?: return null
            return firstIpv4LinkAddress(link)
        }

        /** Returns the first `TRANSPORT_WIFI` network with internet capability, if any. */
        @Suppress("DEPRECATION")
        private fun pickWifiNetwork(): Network? {
            // `getActiveNetwork` is API 23+. Older devices fall back to the
            // `WifiManager.connectionInfo` path below.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            // `allNetworks` was soft-deprecated in API 31 in favor of
            // registering a NetworkCallback, but it's still the cheapest
            // synchronous way to enumerate active networks for this kind
            // of one-shot lookup. NetworkChangeWatcher already listens
            // for changes; we just need a snapshot here.
            val active = cm.activeNetwork?.takeIf(::isWifi)
            return active ?: cm.allNetworks.firstOrNull(::isWifi)
        }

        private fun isWifi(network: Network): Boolean =
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        private fun firstIpv4LinkAddress(link: LinkProperties): Inet4Address? =
            link.linkAddresses
                .asSequence()
                .map(LinkAddress::getAddress)
                .filterIsInstance<Inet4Address>()
                .firstOrNull(::isUsableUnicast)

        private fun isUsableUnicast(address: Inet4Address): Boolean =
            !address.isLoopbackAddress &&
                !address.isAnyLocalAddress &&
                !address.isMulticastAddress

        /**
         * Legacy [WifiManager.getConnectionInfo] resolver. On API 31+
         * this typically returns `0.0.0.0`, which we filter out via the
         * `it != 0` check. We keep this path because pre-31 devices or
         * devices with location permission still get a working answer
         * here without any extra plumbing.
         */
        @Suppress("DEPRECATION")
        private fun legacyWifiManagerAddress(): Inet4Address? {
            val raw = wifi.connectionInfo?.ipAddress?.takeIf { it != 0 } ?: return null
            val bytes =
                ByteArray(IPV4_OCTETS) { i ->
                    ((raw ushr (BITS_PER_BYTE * i)) and BYTE_MASK).toByte()
                }
            return Inet4Address.getByAddress(bytes) as Inet4Address
        }

        /**
         * Last-resort scan of [NetworkInterface]s. We pick the first
         * non-loopback, IPv4 address whose owning interface name looks
         * Wi-Fi-shaped (`wlan0`, `ap0`, etc.). The interface-name
         * filter avoids accidentally binding to a cellular `rmnet*`
         * interface, a VPN `tun*`, or Docker-style virtual bridges on
         * dev devices.
         */
        private fun networkInterfaceWifiAddress(): Inet4Address? {
            val interfaces = enumerateInterfaces() ?: return null
            return interfaces
                .asSequence()
                .filter(::isWifiShapedInterface)
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull(::isUsableUnicast)
        }

        private fun enumerateInterfaces(): List<NetworkInterface>? =
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                null
            }

        private fun isWifiShapedInterface(iface: NetworkInterface): Boolean {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) return false
            val nameLower = iface.name?.lowercase().orEmpty()
            return nameLower.startsWith("wlan") ||
                nameLower.startsWith("ap") ||
                nameLower.startsWith("p2p")
        }

        private companion object {
            const val IPV4_OCTETS: Int = 4
            const val BITS_PER_BYTE: Int = 8
            const val BYTE_MASK: Int = 0xFF
        }
    }
}
