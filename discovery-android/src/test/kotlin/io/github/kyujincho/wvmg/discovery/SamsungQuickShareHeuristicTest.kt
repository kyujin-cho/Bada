/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pattern-set regression tests for [SamsungQuickShareHeuristic]. Lock
 * in the cases that motivated the heuristic in the first place
 * (empirically observed Samsung device names) and the non-Samsung
 * names that must not false-positive (Pixel, OnePlus, Sony, Xiaomi,
 * vivo).
 */
class SamsungQuickShareHeuristicTest {
    @Test
    fun `S26 Ultra with user prefix is matched`() {
        // Real device name observed during the BLE GATT cert-gate
        // research session — the user customized the friendly name but
        // kept the model suffix.
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Kyujin's S26 Ultra")).isTrue()
    }

    @Test
    fun `Galaxy umbrella brand matches regardless of model suffix`() {
        listOf(
            "Galaxy S22",
            "Galaxy A54",
            "Galaxy",
            "GALAXY S25 PLUS",
            "My Galaxy device",
        ).forEach { name ->
            assertThat(SamsungQuickShareHeuristic.matchesDeviceName(name)).isTrue()
        }
    }

    @Test
    fun `Galaxy embedded inside another word does not match (word boundary)`() {
        // The token must be `\b`-bounded so we don't false-positive on
        // arbitrary substrings — e.g., a third-party app or device
        // whose model name happens to contain the literal letters
        // "galaxy" without being a Galaxy device shouldn't trigger
        // the warning. Names like "GalaxyTab Android Phone" still
        // match (whitespace is a word boundary), but "MyGalaxyDevice"
        // (no boundary on either side) does not.
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("MyGalaxyDevice")).isFalse()
    }

    @Test
    fun `S series spans S20 through S29 with optional suffixes`() {
        listOf(
            "S20",
            "S21",
            "S22 Ultra",
            "S23+",
            "S24 FE",
            "S25 Plus",
            "S26 Ultra",
            "S29",
        ).forEach { name ->
            assertThat(SamsungQuickShareHeuristic.matchesDeviceName(name)).isTrue()
        }
    }

    @Test
    fun `Note 10 and Note 20 series match`() {
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Galaxy Note 10")).isTrue()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Note20 Ultra")).isTrue()
    }

    @Test
    fun `Z Fold and Z Flip match with and without trailing digit`() {
        listOf("Z Fold", "Z Fold5", "Z Fold 7", "ZFold6", "Z Flip", "Z Flip4").forEach { name ->
            assertThat(SamsungQuickShareHeuristic.matchesDeviceName(name)).isTrue()
        }
    }

    @Test
    fun `A series and M series and F series match`() {
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("A54")).isTrue()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("M53")).isTrue()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("F62")).isTrue()
    }

    @Test
    fun `Tab S tablets match`() {
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Tab S9")).isTrue()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Galaxy Tab S8 Ultra")).isTrue()
    }

    @Test
    fun `non-Samsung Android devices do not match`() {
        // These are the realistic peers a WVMG user would see and we
        // would burn 15 s on if we false-positive'd: Google Pixel,
        // OnePlus, Sony Xperia, Xiaomi, vivo, Huawei.
        listOf(
            "Pixel 8",
            "Pixel 7a",
            "Pixel Fold",
            "OnePlus 12",
            "OnePlus 10 Pro",
            "Xperia 1 V",
            "Xperia 5 IV",
            "Xiaomi 14",
            "Xiaomi 13T",
            "Redmi Note 12",
            "vivo X300 Ultra",
            "vivo Y200",
            "iQOO 12",
            "Huawei P60",
            "Mate 60",
            "Oppo Find X7",
        ).forEach { name ->
            assertThat(SamsungQuickShareHeuristic.matchesDeviceName(name)).isFalse()
        }
    }

    @Test
    fun `null and blank names do not match`() {
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName(null)).isFalse()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("")).isFalse()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("   ")).isFalse()
    }

    @Test
    fun `matching is case-insensitive`() {
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("galaxy s26 ultra")).isTrue()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("GALAXY S26 ULTRA")).isTrue()
    }

    @Test
    fun `Redmi Note (Xiaomi) does not match Note pattern thanks to anchoring`() {
        // Important non-trivial false-positive guard: Xiaomi's "Redmi
        // Note 12" / "Note 13" share the literal token "Note" with
        // Galaxy Note. The Samsung Note pattern only matches
        // "Note 10" or "Note 20" (the active Galaxy Note models),
        // which Xiaomi's Note line never used.
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Redmi Note 12")).isFalse()
        assertThat(SamsungQuickShareHeuristic.matchesDeviceName("Redmi Note 13 Pro")).isFalse()
    }
}
