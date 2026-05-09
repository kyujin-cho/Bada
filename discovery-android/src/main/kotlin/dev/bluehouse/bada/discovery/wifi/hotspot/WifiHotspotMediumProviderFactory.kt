/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.wifi.hotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry

/**
 * Convenience wiring for [WifiHotspotMediumProvider] in Android apps.
 *
 * App-side glue (`:app`, `:service-android`) creates the provider with:
 *
 * ```kotlin
 * val hotspotProvider = WifiHotspotMediumProviderFactory.create(applicationContext)
 * val registry = MediumRegistry(listOf(WifiLanDefaultProvider, hotspotProvider))
 * ```
 *
 * The factory hides the API-level branching so callers don't have to
 * juggle `Build.VERSION.SDK_INT` checks. On unsupported devices it
 * still returns a [WifiHotspotMediumProvider] but with both controller
 * and client null'd out — the provider then reports `isSupported() ==
 * false` and the framework treats it as a no-op rung in the ladder.
 *
 * The Phase 4 #54 orchestrator ("upgrade hook") is the planned single
 * entry point for installing the resulting registry into
 * [dev.bluehouse.bada.protocol.connection.OutboundConnection] /
 * [dev.bluehouse.bada.protocol.connection.InboundConnection];
 * until it lands the registry can still be exercised through tests
 * and through manual on-device runs.
 */
public object WifiHotspotMediumProviderFactory {
    /**
     * Build a [WifiHotspotMediumProvider] tuned for [context]'s API
     * level. Returns a provider with no controller / client (i.e.
     * `isSupported() == false`) when the device lacks Wi-Fi or runs
     * a pre-API-26 system image.
     */
    public fun create(context: Context): WifiHotspotMediumProvider {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            return WifiHotspotMediumProvider()
        }
        val controller =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AndroidLocalOnlyHotspotController(appContext)
            } else {
                null
            }
        val client =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AndroidWifiNetworkSpecifierClient(appContext)
            } else {
                null
            }
        return WifiHotspotMediumProvider(
            controller = controller,
            client = client,
            available = { hasRequiredPermissions(appContext) && (controller != null || client != null) },
        )
    }

    /**
     * Convenience: build a [MediumRegistry] containing the project's
     * default Wi-Fi LAN provider plus this medium's provider. Apps
     * that want a different mix should build their own registry
     * directly.
     */
    public fun registryWithDefaults(context: Context): MediumRegistry {
        val hotspot = create(context)
        // The project's MediumRegistry.DefaultWifiLan companion object
        // owns the trivial Wi-Fi LAN provider; pull it back out by
        // grabbing the registered provider for Wi-Fi LAN. This keeps
        // the LAN default in lockstep with whatever :core-protocol
        // ships, even if its internals change.
        val lanProvider =
            requireNotNull(MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)) {
                "MediumRegistry.DefaultWifiLan must register a Wi-Fi LAN provider."
            }
        return MediumRegistry(listOf(lanProvider, hotspot))
    }

    @Suppress("ReturnCount") // One early return per granted-permission path keeps the gate explicit.
    private fun hasRequiredPermissions(context: Context): Boolean {
        val fineLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (fineLocation) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby =
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            if (nearby) return true
        }
        return false
    }
}
