/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MediumLadderTest {
    @Test
    fun `pickBest returns the highest-priority candidate`() {
        val ladder =
            MediumLadder(
                listOf(
                    Medium.WIFI_LAN,
                    Medium.WIFI_DIRECT,
                    Medium.BLE_L2CAP,
                    Medium.BLUETOOTH,
                ),
            )
        val pick =
            ladder.pickBest(
                setOf(Medium.BLUETOOTH, Medium.WIFI_DIRECT, Medium.BLE_L2CAP),
            )
        assertThat(pick).isEqualTo(Medium.WIFI_DIRECT)
    }

    @Test
    fun `pickBest returns null when intersection is empty`() {
        val ladder = MediumLadder(listOf(Medium.WIFI_LAN, Medium.WIFI_DIRECT))
        assertThat(ladder.pickBest(setOf(Medium.BLUETOOTH))).isNull()
    }

    @Test
    fun `pickBest returns null on empty ladder`() {
        val ladder = MediumLadder(emptyList())
        assertThat(ladder.pickBest(setOf(Medium.WIFI_LAN))).isNull()
    }

    @Test
    fun `default ladder ranks upgrade mediums before Wi-Fi LAN fallback`() {
        assertThat(MediumLadder.Default.rungs.take(4))
            .containsExactly(
                Medium.WIFI_AWARE,
                Medium.WIFI_DIRECT,
                Medium.WIFI_HOTSPOT,
                Medium.WIFI_LAN,
            ).inOrder()
    }

    @Test
    fun `default ladder includes every domain medium`() {
        // Future-proof: if a new Medium is added without being placed
        // on the default ladder, the registry's selectBestUpgrade
        // would silently never pick it. Make that explicit.
        assertThat(MediumLadder.Default.rungs.toSet()).isEqualTo(Medium.entries.toSet())
    }

    @Test
    fun `duplicate rungs are coalesced`() {
        val ladder =
            MediumLadder(
                listOf(Medium.WIFI_LAN, Medium.WIFI_DIRECT, Medium.WIFI_LAN),
            )
        assertThat(ladder.rungs).containsExactly(Medium.WIFI_LAN, Medium.WIFI_DIRECT).inOrder()
    }
}
