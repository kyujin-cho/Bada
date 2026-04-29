/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.content.Context
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
 * outbound send activity. Until then this entry point is the
 * single-call construction surface for tests, debug screens, and the
 * forthcoming orchestrator code.
 */
public object MediumRegistries {
    /**
     * Build a registry containing the Wi-Fi-LAN default plus the BLE
     * L2CAP CoC provider (Phase 4 sub-issue #52).
     *
     * The BLE L2CAP provider gates itself on `Build.VERSION.SDK_INT >=
     * Q (29)` and on the BLE hardware feature; on devices that fail
     * either gate the registry transparently falls back to Wi-Fi LAN
     * only — same behaviour as [MediumRegistry.DefaultWifiLan].
     *
     * Sibling Phase 4 sub-issues (#49 Wi-Fi Direct, #50 Wi-Fi Hotspot,
     * #51 RFCOMM, #53 Wi-Fi Aware) will append their providers to the
     * list returned from [defaultProviders] as they land.
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
            // BLE L2CAP CoC, gated on API 29+. Reports unsupported on
            // earlier devices so the framework simply omits it from
            // ConnectionRequestFrame.mediums.
            BleL2capMediumProvider(context).asProvider(),
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
