/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.discovery.aware.WifiAwareMediumProvider
import io.github.kyujincho.wvmg.discovery.wifi.hotspot.WifiHotspotMediumProviderFactory
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumLadder
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.MediumRegistry
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device integration coverage for the per-medium support probes.
 *
 * Each test is intentionally gated with [assumeTrue]: unsupported
 * hardware, missing runtime permissions, or OEM-disabled framework
 * features should skip rather than fail the whole suite. The manual
 * runbook in `docs/testing/medium-integration-suite.md` explains how
 * to pair these smoke tests with the two-device transport runbooks.
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceMediumSupportIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context =
        instrumentation.targetContext.applicationContext

    @Before
    fun grantRuntimePermissions() {
        val packageName = context.packageName
        val automation = instrumentation.uiAutomation
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).forEach { permission ->
            runCatching {
                automation.grantRuntimePermission(packageName, permission)
            }
        }
    }

    @Test
    fun wifiDirect_support_probe_registers_with_registry() {
        assertRegistryPicksMedium(WifiDirectMediumProvider(context), Medium.WIFI_DIRECT)
    }

    @Test
    fun wifiHotspot_support_probe_registers_with_registry() {
        assertRegistryPicksMedium(
            WifiHotspotMediumProviderFactory.create(context),
            Medium.WIFI_HOTSPOT,
        )
    }

    @Test
    fun wifiAware_support_probe_registers_with_registry() {
        assertRegistryPicksMedium(WifiAwareMediumProvider(context), Medium.WIFI_AWARE)
    }

    @Test
    fun bleL2cap_support_probe_registers_with_registry() {
        assertRegistryPicksMedium(
            BleL2capMediumProvider(context).asProvider(),
            Medium.BLE_L2CAP,
        )
    }

    @Test
    fun bluetoothRfcomm_support_probe_registers_with_registry() {
        assertRegistryPicksMedium(BluetoothRfcommMediumProvider(context), Medium.BLUETOOTH)
    }

    private fun assertRegistryPicksMedium(
        provider: MediumProvider,
        medium: Medium,
    ) {
        assumeTrue("$medium unsupported on this device", provider.isSupported())
        val lanProvider =
            requireNotNull(MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)) {
                "MediumRegistry.DefaultWifiLan must register WIFI_LAN."
            }
        val registry =
            MediumRegistry(
                providers = listOf(lanProvider, provider),
                ladder = MediumLadder(listOf(medium, Medium.WIFI_LAN)),
            )

        assertThat(provider.medium).isEqualTo(medium)
        assertThat(registry.supportedMediums()).contains(medium)
        assertThat(registry.selectBestUpgrade(setOf(medium, Medium.WIFI_LAN))).isEqualTo(medium)
    }
}
