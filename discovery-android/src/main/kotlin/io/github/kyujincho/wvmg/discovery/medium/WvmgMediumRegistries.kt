/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.content.Context
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.MediumRegistry

/**
 * App-side factory for [MediumRegistry] instances that include the
 * Phase 4 Android-only medium adapters (#49–#53).
 *
 * Lives in `:discovery-android` (rather than `:core-protocol`) because
 * each provider it instantiates depends on `android.*`. The orchestrator
 * wiring in `:app` / `:service-android` calls into here once at
 * `Application.onCreate`; the framework defaults
 * ([MediumRegistry.DefaultWifiLan]) remain the source of truth for
 * tests and for callers that opt out of the Android adapters.
 *
 * As more Phase 4 sub-issues land, each adds its provider to
 * [withWifiDirect]'s sibling factories. The `with…` naming makes it
 * obvious at the call site which mediums are being plugged in.
 */
public object WvmgMediumRegistries {
    /**
     * Build a [MediumRegistry] containing the default Wi-Fi LAN
     * provider plus the Wi-Fi Direct provider from #49.
     *
     * The Wi-Fi LAN slot reuses [MediumRegistry.DefaultWifiLan]'s
     * trivial provider so the resulting registry is a strict superset
     * of today's behaviour — adding [WifiDirectMediumProvider] does
     * NOT change Wi-Fi LAN's role as the default discovery medium.
     *
     * Callers that want only Wi-Fi LAN (e.g. unit tests, or a build
     * variant where Wi-Fi Direct is disabled) should keep using
     * [MediumRegistry.DefaultWifiLan] directly.
     */
    @JvmStatic
    public fun withWifiDirect(context: Context): MediumRegistry {
        // Reuse the default provider list rather than duplicating the
        // Wi-Fi LAN entry by name — DefaultWifiLan is the canonical
        // place where "what does WIFI_LAN mean today" is defined, so
        // any future change there propagates here automatically.
        val defaults = MediumRegistry.DefaultWifiLan
        val providers = mutableListOf<MediumProvider>()
        for (medium in defaults.supportedMediums()) {
            defaults.providerFor(medium)?.let { providers += it }
        }
        providers += WifiDirectMediumProvider(context.applicationContext)
        return MediumRegistry(providers = providers)
    }
}
