/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only unit tests for the pure-data layer of [LegacyPackageDetector].
 *
 * The Android-side wrapper [LegacyPackageDetector.findInstalledLegacy]
 * needs a real `Context` / `PackageManager`, so it is exercised on-device
 * during the #145 migration debug-loop validation rather than here.
 *
 * The data-layer entry point [LegacyPackageDetector.findLegacy] takes a
 * pre-collected `Set<String>` of installed package names, which is
 * trivially constructible in JVM tests and decouples the detection logic
 * from any platform stub.
 */
class LegacyPackageDetectorTest {
    @Test
    fun `findLegacy returns null when no legacy package is installed`() {
        val installed =
            setOf(
                "dev.bluehouse.bada",
                "dev.bluehouse.bada.debug",
                "com.google.android.gms",
                "com.samsung.android.app.sharelive",
            )

        assertNull(LegacyPackageDetector.findLegacy(installed))
    }

    @Test
    fun `findLegacy detects the previous LibreDrop release package`() {
        val installed =
            setOf(
                "dev.bluehouse.bada.debug",
                LegacyPackageDetector.LEGACY_LIBREDROP_RELEASE_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_LIBREDROP_RELEASE_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `findLegacy detects the previous LibreDrop debug package`() {
        val installed =
            setOf(
                "dev.bluehouse.bada.debug",
                LegacyPackageDetector.LEGACY_LIBREDROP_DEBUG_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_LIBREDROP_DEBUG_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `findLegacy still detects the original WVMG package`() {
        val installed =
            setOf(
                "dev.bluehouse.bada.debug",
                LegacyPackageDetector.LEGACY_WVMG_RELEASE_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_WVMG_RELEASE_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `findLegacy prefers the latest previous release id when several are installed`() {
        // Multiple coresident legacy installs are the worst case for users who
        // migrated across app ids and local sideloads. Pick a stable winner so
        // the banner CTA / Toast text does not flap on every resume.
        val installed =
            setOf(
                "dev.bluehouse.bada.debug",
                LegacyPackageDetector.LEGACY_LIBREDROP_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_LIBREDROP_DEBUG_PACKAGE,
                LegacyPackageDetector.LEGACY_WVMG_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_WVMG_DEBUG_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_LIBREDROP_RELEASE_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `LEGACY_PACKAGES enumerates exactly the known pre-Bada ids`() {
        // Pin the constant list so future renames do not silently
        // expand the detector's surface without an explicit code review.
        assertEquals(
            listOf(
                LegacyPackageDetector.LEGACY_LIBREDROP_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_LIBREDROP_DEBUG_PACKAGE,
                LegacyPackageDetector.LEGACY_WVMG_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_WVMG_DEBUG_PACKAGE,
            ),
            LegacyPackageDetector.LEGACY_PACKAGES,
        )
    }
}
