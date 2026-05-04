/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.medium

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import org.junit.jupiter.api.Test

/**
 * Round-trip guards for the domain [Medium] enum against the two proto
 * enums it shadows.
 *
 * The proto's `LINT.IfChange` annotation pins the wire numbers across
 * the two enums (ConnectionRequestFrame.Medium and
 * UpgradePathInfo.Medium) so the same domain entry can map onto either
 * one. These tests make that pinning explicit so a future proto vendor
 * cannot drift the mapping silently.
 */
class MediumWireMappingTest {
    @Test
    fun `every domain Medium round-trips through ConnectionRequestFrame Medium`() {
        for (m in Medium.entries) {
            val proto = m.toConnectionRequestMedium()
            val back = Medium.fromConnectionRequestMedium(proto)
            assertThat(back).isEqualTo(m)
        }
    }

    @Test
    fun `every domain Medium with an UpgradePathInfo wire value round-trips`() {
        for (m in Medium.entries) {
            val proto = m.toUpgradePathMediumOrNull() ?: continue
            val back = Medium.fromUpgradePathMedium(proto)
            assertThat(back).isEqualTo(m)
        }
    }

    @Test
    fun `BLE_L2CAP has no UpgradePathInfo wire value (reserved)`() {
        // Per the proto: `// 10 is reserved.` on
        // UpgradePathInfo.Medium. BLE_L2CAP is its own bulk-transfer
        // medium (BT 5+ data channel) rather than a bandwidth-upgrade
        // target, which matches Quick Share's design.
        assertThat(Medium.BLE_L2CAP.toUpgradePathMediumOrNull()).isNull()
    }

    @Test
    fun `unsupported ConnectionRequestFrame Medium values decode to null`() {
        // The ones we deliberately do not surface as domain entries
        // (Apple-side or out-of-scope mediums) MUST decode to null —
        // not throw — so a peer advertising one of them does not crash
        // the receiver-side intersection.
        val outOfScope =
            listOf(
                ConnectionRequestFrame.Medium.UNKNOWN_MEDIUM,
                ConnectionRequestFrame.Medium.MDNS,
                ConnectionRequestFrame.Medium.NFC,
                ConnectionRequestFrame.Medium.USB,
                ConnectionRequestFrame.Medium.WEB_RTC_NON_CELLULAR,
                ConnectionRequestFrame.Medium.AWDL,
            )
        for (m in outOfScope) {
            assertThat(Medium.fromConnectionRequestMedium(m)).isNull()
        }
    }

    @Test
    fun `unsupported UpgradePathInfo Medium values decode to null`() {
        val outOfScope =
            listOf(
                UpgradePathInfo.Medium.UNKNOWN_MEDIUM,
                UpgradePathInfo.Medium.MDNS,
                UpgradePathInfo.Medium.NFC,
                UpgradePathInfo.Medium.USB,
                UpgradePathInfo.Medium.WEB_RTC_NON_CELLULAR,
                UpgradePathInfo.Medium.AWDL,
            )
        for (m in outOfScope) {
            assertThat(Medium.fromUpgradePathMedium(m)).isNull()
        }
    }

    @Test
    fun `wire numbers are pinned to the documented Quick Share assignments`() {
        // Spot-check the values that show up in PROTOCOL.md so a
        // vendoring drift is loud. These numbers are stable across
        // every proto revision NearDrop has shipped against.
        assertThat(Medium.WIFI_LAN.wireNumber).isEqualTo(5)
        assertThat(Medium.BLUETOOTH.wireNumber).isEqualTo(2)
        assertThat(Medium.BLE.wireNumber).isEqualTo(4)
        assertThat(Medium.BLE_L2CAP.wireNumber).isEqualTo(10)
        assertThat(Medium.WIFI_DIRECT.wireNumber).isEqualTo(8)
        assertThat(Medium.WIFI_HOTSPOT.wireNumber).isEqualTo(3)
        assertThat(Medium.WIFI_AWARE.wireNumber).isEqualTo(6)
        assertThat(Medium.WEB_RTC.wireNumber).isEqualTo(9)
    }
}
