/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.content.Context
import io.github.kyujincho.wvmg.discovery.aware.WifiAwareMediumProvider
import io.github.kyujincho.wvmg.discovery.wifi.hotspot.WifiHotspotMediumProviderFactory
import io.github.kyujincho.wvmg.protocol.medium.MediumLadder
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.MediumRegistry

/**
 * Convenience factories that assemble a [MediumRegistry] populated with
 * the per-medium adapters this module ships. Lives in
 * `:discovery-android` (not `:core-protocol`) because the providers
 * themselves bind to `android.bluetooth.*` / `android.net.*` types and
 * cannot live in the JVM-only protocol module.
 *
 * Phase 4 sub-issue #54 wires up the orchestrator hook that consumes
 * these registries from the foreground receiver service and the
 * outbound send activity.
 */
public object MediumRegistries {
    /**
     * Build a registry containing the Wi-Fi LAN default plus every
     * Android Phase 4 medium provider shipped by this module.
     *
     * Each provider owns its own SDK / feature / runtime-permission
     * gates. Unsupported providers remain registered but report
     * `isSupported() == false`, so [MediumRegistry.supportedMediums]
     * naturally falls back to the mediums available on the current
     * device.
     */
    @JvmStatic
    public fun defaultForContext(
        context: Context,
        ladder: MediumLadder = MediumLadder.Default,
    ): MediumRegistry =
        MediumRegistry(
            providers = defaultProviders(context),
            ladder = ladder,
        )

    /**
     * Open list of providers the registry should advertise. Exposed so
     * tests (and the eventual #54 orchestrator hook) can compose a
     * registry with extra fakes or with a subset of the production
     * providers.
     */
    @JvmStatic
    public fun defaultProviders(context: Context): List<MediumProvider> =
        listOf(
            // Wi-Fi LAN provider matches the Phase 1 default and is
            // always supported when a TCP socket is already open.
            WifiLanProvider,
            WifiAwareMediumProvider(context),
            WifiDirectMediumProvider(context),
            WifiHotspotMediumProviderFactory.create(context),
            BleL2capMediumProvider(context).asProvider(),
            // Bluetooth Classic RFCOMM is intentionally omitted from the
            // production ladder. Stock Quick Share's off-LAN tap path is
            // BLE/GATT, and keeping Classic active causes discovery and
            // permission side effects without helping Galaxy interop.
        )

    /**
     * Trivial Wi-Fi LAN provider mirroring
     * [MediumRegistry.DefaultWifiLan]'s internal default. Hoisted so
     * [defaultProviders] can compose the production list cleanly.
     */
    private object WifiLanProvider : MediumProvider {
        override val medium: io.github.kyujincho.wvmg.protocol.medium.Medium =
            io.github.kyujincho.wvmg.protocol.medium.Medium.WIFI_LAN

        override fun isSupported(): Boolean = true
    }
}
