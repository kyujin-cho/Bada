/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.battery

import dev.bluehouse.bada.battery.BatteryOptimizationOemHelper.Candidate
import dev.bluehouse.bada.battery.BatteryOptimizationOemHelper.OemFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the OEM-detection and candidate-list logic in
 * [BatteryOptimizationOemHelper]. The methods exercised here
 * deliberately operate on the helper's pure-data [Candidate] surface;
 * the Android-side translation to `Intent` / `Uri` lives behind
 * `intentsForCurrentDevice` and is not covered here so the unit tests
 * never touch stub `android.jar` constructors (which would fail with
 * `ClassFormatError` on a host JVM).
 */
class BatteryOptimizationOemHelperTest {
    @Test
    fun `samsung manufacturer maps to samsung family`() {
        assertEquals(
            OemFamily.SAMSUNG,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "samsung", brand = "samsung"),
        )
        // Casing must not matter — Build.MANUFACTURER on Galaxy devices
        // historically alternated between "samsung" and "Samsung".
        assertEquals(
            OemFamily.SAMSUNG,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "SAMSUNG", brand = "samsung"),
        )
    }

    @Test
    fun `xiaomi family covers xiaomi redmi and poco brands`() {
        assertEquals(
            OemFamily.XIAOMI,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "Xiaomi", brand = "Xiaomi"),
        )
        assertEquals(
            OemFamily.XIAOMI,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "Xiaomi", brand = "Redmi"),
        )
        assertEquals(
            OemFamily.XIAOMI,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "Xiaomi", brand = "POCO"),
        )
    }

    @Test
    fun `vivo family covers vivo and iqoo brands`() {
        assertEquals(
            OemFamily.VIVO,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "vivo", brand = "vivo"),
        )
        assertEquals(
            OemFamily.VIVO,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "vivo", brand = "iQOO"),
        )
    }

    @Test
    fun `oneplus is detected as its own family`() {
        assertEquals(
            OemFamily.ONEPLUS,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "OnePlus", brand = "OnePlus"),
        )
    }

    @Test
    fun `oppo and realme are independently detected`() {
        assertEquals(
            OemFamily.OPPO,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "OPPO", brand = "OPPO"),
        )
        assertEquals(
            OemFamily.REALME,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "realme", brand = "realme"),
        )
    }

    @Test
    fun `huawei stays huawei when brand matches`() {
        assertEquals(
            OemFamily.HUAWEI,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "HUAWEI", brand = "HUAWEI"),
        )
    }

    @Test
    fun `legacy honor under huawei manufacturer is detected as honor`() {
        // Pre-2020 Honor devices reported Build.MANUFACTURER == "HUAWEI"
        // even though they shipped with their own MagicOS skin and a
        // distinct "Protected apps" Settings page. The brand string is
        // the only reliable signal in that period, so prefer it.
        assertEquals(
            OemFamily.HONOR,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "HUAWEI", brand = "HONOR"),
        )
    }

    @Test
    fun `pixel and other unknown vendors fall back to OTHER`() {
        assertEquals(
            OemFamily.OTHER,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "Google", brand = "google"),
        )
        assertEquals(
            OemFamily.OTHER,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "Sony", brand = "Sony"),
        )
        assertEquals(
            OemFamily.OTHER,
            BatteryOptimizationOemHelper.detectFamily(manufacturer = "", brand = ""),
        )
    }

    @Test
    fun `unknown vendor candidate list is exactly the generic fallback`() {
        val candidates = BatteryOptimizationOemHelper.candidatesFor(OemFamily.OTHER)
        assertEquals("OTHER must produce only the generic fallback", 1, candidates.size)
        assertSame(
            "OTHER fallback must be the GenericIgnoreBatteryOptimizations sentinel",
            Candidate.GenericIgnoreBatteryOptimizations,
            candidates.single(),
        )
    }

    @Test
    fun `vendor candidate list starts with the generic dialog`() {
        // Every vendor list must lead with the standard system dialog
        // because that is the only path that flips
        // `PowerManager.isIgnoringBatteryOptimizations` when the user
        // accepts. Vendor activities (vivo BgStartUpManager, MIUI
        // autostart, etc.) are kept as fall-through entries so devices
        // on which the generic intent unexpectedly fails to resolve
        // still have somewhere to land.
        val vendorFamilies =
            listOf(
                OemFamily.SAMSUNG,
                OemFamily.XIAOMI,
                OemFamily.VIVO,
                OemFamily.OPPO,
                OemFamily.ONEPLUS,
                OemFamily.HUAWEI,
                OemFamily.HONOR,
                OemFamily.REALME,
            )
        for (family in vendorFamilies) {
            val candidates = BatteryOptimizationOemHelper.candidatesFor(family)
            assertTrue(
                "$family must produce the generic dialog plus at least one vendor entry",
                candidates.size >= 2,
            )
            assertSame(
                "$family list must start with GenericIgnoreBatteryOptimizations",
                Candidate.GenericIgnoreBatteryOptimizations,
                candidates.first(),
            )
            // Every following candidate must be a VendorActivity, not
            // another implicit-action sentinel — otherwise the helper
            // would offer two implicit intents racing for the same
            // action.
            for (candidate in candidates.drop(1)) {
                assertTrue(
                    "$family vendor candidate must be a VendorActivity",
                    candidate is Candidate.VendorActivity,
                )
                val vendor = candidate as Candidate.VendorActivity
                assertTrue(
                    "$family vendor candidate must declare a non-empty package name",
                    vendor.packageName.isNotBlank(),
                )
                assertTrue(
                    "$family vendor candidate must declare a non-empty class name",
                    vendor.className.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `every required acceptance-criteria oem is covered`() {
        // Acceptance criteria call out: Samsung, Xiaomi, Oppo, Vivo,
        // OnePlus, Huawei, Honor.
        val required =
            listOf(
                OemFamily.SAMSUNG,
                OemFamily.XIAOMI,
                OemFamily.OPPO,
                OemFamily.VIVO,
                OemFamily.ONEPLUS,
                OemFamily.HUAWEI,
                OemFamily.HONOR,
            )
        for (family in required) {
            val candidates = BatteryOptimizationOemHelper.candidatesFor(family)
            // size > 1 implies at least one vendor activity before the
            // generic fallback.
            assertTrue(
                "$family must contribute at least one vendor-specific candidate",
                candidates.size > 1,
            )
        }
    }
}
