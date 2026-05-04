/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.medium

import android.content.Context
import dev.bluehouse.libredrop.protocol.medium.MediumRegistry

/**
 * Compatibility factory for [MediumRegistry] instances that include
 * the Android Phase 4 medium adapters (#49-#53).
 *
 * Lives in `:discovery-android` (rather than `:core-protocol`) because
 * each provider it instantiates depends on `android.*`. The orchestrator
 * wiring in `:app` / `:service-android` calls into here once at
 * `Application.onCreate`; the framework defaults
 * ([MediumRegistry.DefaultWifiLan]) remain the source of truth for
 * tests and for callers that opt out of the Android adapters.
 *
 * New production callers should use [MediumRegistries.defaultForContext].
 */
public object LibreDropMediumRegistries {
    /**
     * Build the full production Android medium registry.
     *
     * The name is retained for source compatibility with the partial
     * #49 integration, but returning only Wi-Fi Direct would now be
     * misleading because Phase 4 has providers for all upgrade mediums.
     */
    @JvmStatic
    public fun withWifiDirect(context: Context): MediumRegistry =
        MediumRegistries.defaultForContext(context.applicationContext)
}
