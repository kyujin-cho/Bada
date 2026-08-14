/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import android.content.Context
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityCapabilityMetadata
import dev.bluehouse.bada.protocol.gestureexchange.proto.MediumRole
import dev.bluehouse.bada.protocol.gestureexchange.proto.WifiDirectAuthType

/** Builds only locally measured Gesture capability fields; it never fabricates channels. */
internal object GestureCapabilityMetadataFactory {
    fun create(context: Context): ConnectivityCapabilityMetadata {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connection = runCatching { wifi.connectionInfo }.getOrNull()
        val builder =
            ConnectivityCapabilityMetadata
                .newBuilder()
                .setSupports5Ghz(runCatching { wifi.is5GHzBandSupported }.getOrDefault(false))
                .setSupports6Ghz(runCatching { wifi.is6GHzBandSupported }.getOrDefault(false))
                .setHasMobileRadio(
                    runCatching {
                        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).isDataCapable
                    }.getOrDefault(false),
                ).setApFrequency(connection?.frequency ?: -1)
                .setMediumRole(MediumRole.getDefaultInstance())
                .addWifiDirectAuthTypes(WifiDirectAuthType.WIFI_DIRECT_WITH_PASSWORD)
        connection?.bssid?.takeIf(::isValidBssid)?.let(builder::setBssid)
        return builder.build()
    }

    private fun isValidBssid(value: String): Boolean =
        BSSID.matches(value) && !value.equals("02:00:00:00:00:00", ignoreCase = true)

    private val BSSID = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
}
