/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
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
 * We therefore look up the IPv4 address attached to the active Wi-Fi
 * connection via [WifiManager.getConnectionInfo] and fall back to
 * [InetAddress.getLocalHost] (which on Android resolves to the device's
 * primary network address) if Wi-Fi info is unavailable for any reason.
 *
 * Splitting this off into its own object lets unit tests substitute a
 * fake [WifiAddressProvider] when validating publish/browse logic on a
 * plain JVM.
 */
internal object JmdnsFactory {
    /**
     * Creates a JmDNS instance bound to the best-effort Wi-Fi interface
     * address. The [name] argument is forwarded as the JmDNS instance
     * name (cosmetic; useful in log output).
     */
    fun create(
        context: Context,
        name: String = "wvmg-discovery",
        addressProvider: WifiAddressProvider = AndroidWifiAddressProvider(context),
    ): JmDNS {
        val address = addressProvider.currentWifiAddress() ?: InetAddress.getLocalHost()
        return JmDNS.create(address, name)
    }

    /** Looks up the device's current Wi-Fi IPv4 address, if any. */
    internal interface WifiAddressProvider {
        fun currentWifiAddress(): InetAddress?
    }

    /**
     * Production [WifiAddressProvider] backed by [WifiManager]. The
     * legacy `getConnectionInfo()` API is the broadest-compatible source
     * of the current Wi-Fi IPv4 address — `ConnectivityManager.LinkProperties`
     * gives more detail but requires API 21+ scaffolding we don't need
     * here, and we don't have a target above the existing minSdk floor.
     */
    private class AndroidWifiAddressProvider(
        context: Context,
    ) : WifiAddressProvider {
        private val wifi: WifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        override fun currentWifiAddress(): InetAddress? {
            // `connectionInfo` is documented to return null on no Wi-Fi
            // and `ipAddress = 0` when associated but not yet IPv4-assigned;
            // collapsing both cases to a single null return keeps the
            // function's return statements within the project-wide ReturnCount limit.
            val raw = wifi.connectionInfo?.ipAddress?.takeIf { it != 0 } ?: return null
            // WifiInfo.ipAddress is in little-endian host order on every
            // Android device shipping today. Decode the four octets and
            // construct an Inet4Address — JmDNS specifically wants an
            // Inet4Address (it asserts on this internally).
            val bytes =
                ByteArray(IPV4_OCTETS) { i ->
                    ((raw ushr (BITS_PER_BYTE * i)) and BYTE_MASK).toByte()
                }
            return Inet4Address.getByAddress(bytes)
        }

        private companion object {
            const val IPV4_OCTETS: Int = 4
            const val BITS_PER_BYTE: Int = 8
            const val BYTE_MASK: Int = 0xFF
        }
    }
}
