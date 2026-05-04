/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.migration

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
                "dev.bluehouse.libredrop",
                "dev.bluehouse.libredrop.debug",
                "com.google.android.gms",
                "com.samsung.android.app.sharelive",
            )

        assertNull(LegacyPackageDetector.findLegacy(installed))
    }

    @Test
    fun `findLegacy detects the release legacy package`() {
        val installed =
            setOf(
                "dev.bluehouse.libredrop.debug",
                LegacyPackageDetector.LEGACY_RELEASE_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_RELEASE_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `findLegacy detects the debug legacy package`() {
        val installed =
            setOf(
                "dev.bluehouse.libredrop.debug",
                LegacyPackageDetector.LEGACY_DEBUG_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_DEBUG_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `findLegacy prefers the release id over the debug id when both are installed`() {
        // Two coresident legacy installs (release + debug) is the
        // worst case for users who migrated from a Play install AND a
        // local sideload. The detector should pick a stable winner
        // (release) so the banner CTA / Toast text does not flap on
        // every resume.
        val installed =
            setOf(
                "dev.bluehouse.libredrop.debug",
                LegacyPackageDetector.LEGACY_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_DEBUG_PACKAGE,
            )

        assertEquals(
            LegacyPackageDetector.LEGACY_RELEASE_PACKAGE,
            LegacyPackageDetector.findLegacy(installed),
        )
    }

    @Test
    fun `LEGACY_PACKAGES enumerates exactly the two known pre-rename ids`() {
        // Pin the constant list so future renames do not silently
        // expand the detector's surface without an explicit code review.
        assertEquals(
            listOf(
                LegacyPackageDetector.LEGACY_RELEASE_PACKAGE,
                LegacyPackageDetector.LEGACY_DEBUG_PACKAGE,
            ),
            LegacyPackageDetector.LEGACY_PACKAGES,
        )
    }
}
