/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.migration

/**
 * Detector for a coresident pre-Bada install.
 *
 * Before commit `27f2d31` the app shipped under `io.github.kyujincho.wvmg`
 * (release) and `io.github.kyujincho.wvmg.debug` (debug), and the app later
 * shipped as `dev.bluehouse.libredrop` / `.debug`. The rename to
 * `dev.bluehouse.bada` makes the new install coresident with either previous
 * application id because Android does not auto-uninstall a different package.
 * Both processes register their own `0xFEF3` GATT advertisement service on the
 * same Bluetooth controller, so a stock Quick Share sender walking the
 * peripheral's GATT tree finds **two** slot-bearing `0xFEF3-0000-...` services
 * side-by-side. Issue #145 was filed against this exact symptom: Samsung's GMS
 * read the slot from one service but opened the BLE socket against another, so
 * the receiver saw the GATT connection but never saw the `ConnectionRequestFrame`
 * write.
 *
 * The fix is purely additive: detect the legacy package at launcher
 * resume time and surface a non-dismissible banner pointing the user at
 * the system uninstall flow. The banner clears itself the next time the
 * launcher resumes after the package is gone.
 *
 * The shape mirrors [dev.bluehouse.bada.battery.BatteryOptimizationOemHelper]:
 *
 *   1. **Data layer** — [findLegacy] takes a snapshot of installed
 *      package names (a `Set<String>`) and returns the first legacy
 *      identifier present, or `null`. Pure JVM, exercised in
 *      `:app:testDebugUnitTest` without Robolectric. **Lives in this
 *      file and does not import any `android.*` types** so the JVM
 *      verifier can load the class on a host JVM without resolving
 *      stub `android.jar` exceptions whose stripped Code attributes
 *      crash class loading (this is what bit the original draft of
 *      [LegacyPackageDetectorTest] before the Android-side wrapper
 *      moved to `LegacyPackageDetectorAndroid.kt`).
 *   2. **Android layer** — `LegacyPackageDetectorAndroid.findInstalledLegacy`
 *      (separate file) queries the platform `PackageManager`. Wraps
 *      the data layer with the production input source.
 *
 * The package names are intentionally constants on the object so they
 * can also be referenced from [dev.bluehouse.bada.MainActivity] (the
 * uninstall deep-link uses the same string) and from tests.
 */
internal object LegacyPackageDetector {
    /**
     * Previous release package id used before the Bada application-id rename.
     */
    internal const val LEGACY_LIBREDROP_RELEASE_PACKAGE: String = "dev.bluehouse.libredrop"

    /**
     * Previous debug package id used before the Bada application-id rename.
     */
    internal const val LEGACY_LIBREDROP_DEBUG_PACKAGE: String = "dev.bluehouse.libredrop.debug"

    /**
     * Original pre-rename release package id. Users who upgraded from a Play /
     * sideload install built before commit `27f2d31` may still have this package
     * present until they manually uninstall it.
     */
    internal const val LEGACY_WVMG_RELEASE_PACKAGE: String = "io.github.kyujincho.wvmg"

    /**
     * Original pre-rename debug package id (`applicationIdSuffix = ".debug"`).
     * Most often hit by developers and CI machines that have both
     * variants of the same APK installed at once.
     */
    internal const val LEGACY_WVMG_DEBUG_PACKAGE: String = "io.github.kyujincho.wvmg.debug"

    /**
     * Ordered list of legacy package ids to check. The order matters
     * only as a tie-break: surface the newest previous package first, then its
     * debug variant, then the older WhenVivoMeetsGoogle ids.
     */
    internal val LEGACY_PACKAGES: List<String> =
        listOf(
            LEGACY_LIBREDROP_RELEASE_PACKAGE,
            LEGACY_LIBREDROP_DEBUG_PACKAGE,
            LEGACY_WVMG_RELEASE_PACKAGE,
            LEGACY_WVMG_DEBUG_PACKAGE,
        )

    /**
     * Pure-JVM detector. Returns the first legacy package id present in
     * [installedPackages], or `null` if none is present.
     *
     * @param installedPackages snapshot of currently-installed package
     *   names. Production code passes the result of
     *   [PackageManager.getInstalledPackages] (mapped to package names);
     *   tests pass an arbitrary set.
     */
    internal fun findLegacy(installedPackages: Set<String>): String? =
        LEGACY_PACKAGES.firstOrNull { it in installedPackages }
}
