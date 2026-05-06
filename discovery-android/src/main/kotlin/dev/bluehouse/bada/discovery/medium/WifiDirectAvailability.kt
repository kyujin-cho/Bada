/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Capability + permission probe for the Wi-Fi Direct medium (#49).
 *
 * Modeled as an interface so [WifiDirectMediumProvider] can be unit-
 * tested with a trivial fake without touching real Android system
 * services. The shipped implementation is [Default]; the provider's
 * public constructor wires it up against the application [Context].
 *
 * The four conditions [Default] gates on:
 *
 *  1. **Hardware feature flag.** Android exposes
 *     [PackageManager.FEATURE_WIFI_DIRECT] for chipsets / vendor builds
 *     that support P2P. Some low-tier OEM SKUs ship Wi-Fi without
 *     advertising the feature even though the API works; we honour the
 *     flag conservatively because [WifiP2pManager.initialize] silently
 *     no-ops on devices that do not have it, leading to `connect` calls
 *     that simply never callback.
 *  2. **System service present.** The platform returns `null` for
 *     `getSystemService(Context.WIFI_P2P_SERVICE)` in headless / TV
 *     profiles even when the feature flag is set. Pure defence-in-depth.
 *  3. **Runtime permission.** API 33+ requires
 *     [Manifest.permission.NEARBY_WIFI_DEVICES] to discover/connect over
 *     P2P; pre-33 falls back to [Manifest.permission.ACCESS_FINE_LOCATION].
 *     Onboarding (#31) requests `NEARBY_WIFI_DEVICES`; we re-check at
 *     every `isSupported` call because the user can revoke from Settings
 *     without restarting the app.
 *
 * Non-goals: [Default] does NOT verify that the device has *peer
 * mode* (peripheral vs. group-owner only); the platform reports both
 * modes as one feature flag. If a device only supports group-owner mode
 * we will discover that at `prepareUpgrade` / `adoptUpgrade` time and
 * fail back to the next ladder rung.
 */
public interface WifiDirectAvailability {
    /**
     * Single `isSupported`-shape entry point. Must be cheap and
     * non-blocking — the framework calls this on every connection.
     */
    public fun isSupported(): Boolean

    /**
     * Production-shipped probe. Resolves every check against the
     * supplied application [Context].
     */
    public class Default(
        private val context: Context,
    ) : WifiDirectAvailability {
        @Suppress("ReturnCount") // One guard per capability gate; flattening obscures which check failed.
        override fun isSupported(): Boolean {
            if (!hasWifiDirectFeature()) return false
            if (!hasWifiP2pManager()) return false
            if (!hasNearbyWifiPermission()) return false
            return true
        }

        private fun hasWifiDirectFeature(): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

        private fun hasWifiP2pManager(): Boolean = context.getSystemService(Context.WIFI_P2P_SERVICE) is WifiP2pManager

        /**
         * Runtime-permission check. The required permission flips at API
         * 33: pre-33 the platform required `ACCESS_FINE_LOCATION`, post-33
         * the dedicated `NEARBY_WIFI_DEVICES` is the canonical grant.
         * We do not try the location fallback on API 33+ — the project
         * does not declare it (#5: we explicitly avoid asking for fine
         * location) and the platform rejects the legacy permission in
         * favour of the new one.
         */
        private fun hasNearbyWifiPermission(): Boolean {
            val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    Manifest.permission.ACCESS_FINE_LOCATION
                }
            return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
