/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.migration

import android.content.Context
import android.content.pm.PackageManager

/**
 * Android-side wrapper around [LegacyPackageDetector] (issue #145).
 *
 * Lives in its own file — separate from the pure-data layer in
 * [LegacyPackageDetector] — so JVM unit tests in
 * `:app:testDebugUnitTest` never have to load this object. The host
 * JVM cannot verify the class because the stub `android.jar` ships
 * `android.content.pm.PackageManager$NameNotFoundException` with no
 * Code attribute on its `<init>` chain, and the Kotlin compiler emits
 * a `Class.forName` resolution for the catch clause below. Keeping
 * the catch out of the testable file is the same trick
 * [dev.bluehouse.libredrop.battery.BatteryOptimizationOemHelper] uses
 * for its `Intent` / `PowerManager` translation.
 *
 * Production callers (currently [dev.bluehouse.libredrop.MainActivity])
 * route through [findInstalledLegacy]; tests stop at
 * [LegacyPackageDetector.findLegacy] above.
 */
internal object LegacyPackageDetectorAndroid {
    /**
     * Returns the first legacy package id that
     * [PackageManager.getPackageInfo] reports as installed for the
     * current user, or `null` when nothing legacy is installed.
     *
     * Uses per-package `getPackageInfo` rather than
     * `getInstalledPackages` so we do not need the
     * `QUERY_ALL_PACKAGES` permission on API 30+. The visibility
     * carve-out for the legacy package ids is declared as a
     * `<queries><package />` block in
     * `app/src/main/AndroidManifest.xml`; without that, the platform
     * silently throws `NameNotFoundException` for installed packages
     * our app does not declare a relationship to.
     */
    internal fun findInstalledLegacy(context: Context): String? {
        val packageManager = context.packageManager
        return LegacyPackageDetector.LEGACY_PACKAGES.firstOrNull { isInstalled(packageManager, it) }
    }

    @Suppress("SwallowedException")
    private fun isInstalled(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
