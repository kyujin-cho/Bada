/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MediumRegistryTest {
    /**
     * Test fixture: a [MediumProvider] whose support state is
     * controllable from the test body.
     */
    private class FakeProvider(
        override val medium: Medium,
        var supported: Boolean = true,
    ) : MediumProvider {
        override fun isSupported(): Boolean = supported
    }

    @Test
    fun `default registry advertises Wi-Fi LAN only`() {
        val supported = MediumRegistry.DefaultWifiLan.supportedMediums()
        assertThat(supported).containsExactly(Medium.WIFI_LAN)
    }

    @Test
    fun `default registry is not empty`() {
        // isEmpty() reports the registry's "no provider at all" state,
        // which the orchestrator uses as a cheap gate. The default
        // shipped Wi-Fi LAN provider counts.
        assertThat(MediumRegistry.DefaultWifiLan.isEmpty()).isFalse()
    }

    @Test
    fun `supportedMediums excludes providers reporting isSupported false`() {
        val wifi = FakeProvider(Medium.WIFI_LAN, supported = true)
        val direct = FakeProvider(Medium.WIFI_DIRECT, supported = false)
        val registry = MediumRegistry(listOf(wifi, direct))
        assertThat(registry.supportedMediums()).containsExactly(Medium.WIFI_LAN)
    }

    @Test
    fun `supportedMediums tracks runtime support flips`() {
        val direct = FakeProvider(Medium.WIFI_DIRECT, supported = true)
        val registry = MediumRegistry(listOf(direct))
        assertThat(registry.supportedMediums()).containsExactly(Medium.WIFI_DIRECT)
        direct.supported = false
        assertThat(registry.supportedMediums()).isEmpty()
    }

    @Test
    fun `providerFor returns null for unregistered medium`() {
        val registry = MediumRegistry(listOf(FakeProvider(Medium.WIFI_LAN)))
        assertThat(registry.providerFor(Medium.BLE_L2CAP)).isNull()
    }

    @Test
    fun `duplicate provider for same medium is rejected`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                MediumRegistry(
                    listOf(
                        FakeProvider(Medium.WIFI_LAN),
                        FakeProvider(Medium.WIFI_LAN),
                    ),
                )
            }
        assertThat(ex).hasMessageThat().contains("Duplicate")
    }

    @Test
    fun `selectBestUpgrade returns null when intersection is empty`() {
        val registry =
            MediumRegistry(
                listOf(
                    FakeProvider(Medium.WIFI_LAN),
                    FakeProvider(Medium.WIFI_DIRECT),
                ),
            )
        val pick = registry.selectBestUpgrade(setOf(Medium.BLUETOOTH))
        assertThat(pick).isNull()
    }

    @Test
    fun `selectBestUpgrade picks via ladder ordering`() {
        val registry =
            MediumRegistry(
                listOf(
                    FakeProvider(Medium.WIFI_LAN),
                    FakeProvider(Medium.WIFI_DIRECT),
                    FakeProvider(Medium.BLE_L2CAP),
                ),
                ladder =
                    MediumLadder(
                        listOf(
                            Medium.WIFI_DIRECT,
                            Medium.BLE_L2CAP,
                            Medium.WIFI_LAN,
                        ),
                    ),
            )
        val pick =
            registry.selectBestUpgrade(
                setOf(Medium.BLE_L2CAP, Medium.WIFI_LAN, Medium.WIFI_DIRECT),
            )
        assertThat(pick).isEqualTo(Medium.WIFI_DIRECT)
    }

    @Test
    fun `selectBestUpgrade respects a per-call ladder override`() {
        val registry =
            MediumRegistry(
                listOf(
                    FakeProvider(Medium.WIFI_LAN),
                    FakeProvider(Medium.WIFI_DIRECT),
                    FakeProvider(Medium.BLUETOOTH),
                ),
            )
        val override = MediumLadder(listOf(Medium.BLUETOOTH, Medium.WIFI_LAN))
        val pick =
            registry.selectBestUpgrade(
                peerSupported = setOf(Medium.WIFI_DIRECT, Medium.WIFI_LAN, Medium.BLUETOOTH),
                ladderOverride = override,
            )
        assertThat(pick).isEqualTo(Medium.BLUETOOTH)
    }

    @Test
    fun `selectBestUpgrade ignores peer-only mediums we do not support`() {
        // The local registry supports only Wi-Fi LAN; the peer
        // advertises a richer set. Intersection collapses back to
        // Wi-Fi LAN.
        val registry = MediumRegistry.DefaultWifiLan
        val pick =
            registry.selectBestUpgrade(
                setOf(Medium.WIFI_LAN, Medium.WIFI_DIRECT, Medium.BLUETOOTH),
            )
        assertThat(pick).isEqualTo(Medium.WIFI_LAN)
    }

    @Test
    fun `empty registry reports empty supported set`() {
        val registry = MediumRegistry()
        assertThat(registry.supportedMediums()).isEmpty()
        assertThat(registry.isEmpty()).isTrue()
        assertThat(registry.selectBestUpgrade(setOf(Medium.WIFI_LAN))).isNull()
    }
}
