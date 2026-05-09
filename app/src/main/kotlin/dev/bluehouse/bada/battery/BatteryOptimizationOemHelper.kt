/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.battery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * OEM-aware resolver for the "exempt this app from battery optimization"
 * Settings page (issue #47).
 *
 * Several Android OEMs (notably vivo Funtouch / OriginOS, Samsung One UI,
 * Xiaomi MIUI, Oppo ColorOS, OnePlus OxygenOS, Huawei EMUI, Honor MagicOS)
 * ship aggressive background-process killers that put the foreground
 * receiver service to sleep unless the app is on a vendor-maintained
 * allowlist. The vendor allowlists live behind a separate Settings
 * activity from the stock Android `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * dialog and the right shortcut differs per ROM version. Reference:
 * https://dontkillmyapp.com.
 *
 * The helper splits cleanly into two layers:
 *   1. **Data layer** — pure-Kotlin / JVM-testable. [detectFamily] and
 *      [candidatesFor] take strings and produce structured [Candidate]
 *      records. No Android types are constructed, so unit tests in
 *      `:app:testDebugUnitTest` can exercise them without Robolectric or
 *      `Intent`-stub surgery.
 *   2. **Android layer** — [intentsForCurrentDevice] / [isAlreadyExempt]
 *      reach into [Build] and [Context] / [PowerManager] and translate
 *      the [Candidate] list into runnable [Intent]s.
 */
internal object BatteryOptimizationOemHelper {
    /**
     * Stable identifiers for the OEM families we recognise. The values
     * are matched case-insensitively against [Build.MANUFACTURER] (and
     * against [Build.BRAND] for vendors that share a manufacturer string
     * with a sibling brand, e.g. Honor vs. Huawei).
     */
    internal enum class OemFamily {
        SAMSUNG,
        XIAOMI,
        VIVO,
        OPPO,
        ONEPLUS,
        HUAWEI,
        HONOR,
        REALME,
        OTHER,
    }

    /**
     * Pure-data description of a single Settings entry-point candidate.
     * Either an explicit vendor activity (package + class) or the
     * generic stock-Android `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * dialog. Translated to an [Intent] only at the Android layer in
     * [toIntent], which keeps the candidate-selection logic testable on
     * the host JVM.
     */
    internal sealed class Candidate {
        internal data class VendorActivity(
            val packageName: String,
            val className: String,
        ) : Candidate()

        internal data object GenericIgnoreBatteryOptimizations : Candidate()
    }

    /**
     * Ordered table of (family, lowercase keywords) used by
     * [detectFamily]. The order matters — Honor must come before Huawei
     * because pre-2020 Honor devices report `Build.MANUFACTURER ==
     * "HUAWEI"`, so the more-specific brand match has to win. Every
     * keyword is matched against a lowercased concatenation of
     * manufacturer and brand, keeping the runtime check trivial.
     */
    private val oemKeywordTable: List<Pair<OemFamily, List<String>>> =
        listOf(
            OemFamily.HONOR to listOf("honor"),
            OemFamily.SAMSUNG to listOf("samsung"),
            OemFamily.XIAOMI to listOf("xiaomi", "redmi", "poco"),
            OemFamily.VIVO to listOf("vivo", "iqoo"),
            OemFamily.ONEPLUS to listOf("oneplus"),
            OemFamily.OPPO to listOf("oppo"),
            OemFamily.REALME to listOf("realme"),
            OemFamily.HUAWEI to listOf("huawei"),
        )

    /**
     * Detects the OEM family from a (manufacturer, brand) pair. Both
     * inputs come from `Build` on a real device. The check is
     * case-insensitive and tolerates the "different brand under the
     * same manufacturer" pattern (Honor used to ship under
     * `Build.MANUFACTURER == "HUAWEI"`; OnePlus devices report
     * `Build.MANUFACTURER == "OnePlus"` but `Build.BRAND` is sometimes
     * the marketing name).
     */
    internal fun detectFamily(
        manufacturer: String,
        brand: String,
    ): OemFamily {
        val haystack = (manufacturer + " " + brand).lowercase()
        return oemKeywordTable
            .firstOrNull { (_, keywords) -> keywords.any(haystack::contains) }
            ?.first
            ?: OemFamily.OTHER
    }

    /**
     * Computes the ordered list of candidate Settings entry-points for
     * the supplied OEM family. The first entries are vendor-specific
     * shortcuts (most specific landing page first); the last entry is
     * always [Candidate.GenericIgnoreBatteryOptimizations] so the user
     * always has a working fallback.
     *
     * The vendor activity component names are sourced from the
     * dontkillmyapp.com vendor pages and from public bug-tracker
     * reports documenting the activities that survived recent ROM
     * updates. Some activities exist on older ROMs only; the iteration
     * in [resolvableIntents] picks the first one the platform can
     * resolve.
     */
    internal fun candidatesFor(family: OemFamily): List<Candidate> {
        val vendor: List<Candidate> =
            when (family) {
                OemFamily.SAMSUNG ->
                    listOf(
                        // Device care landing on most One UI versions.
                        vendor("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                        vendor(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                        ),
                    )

                OemFamily.XIAOMI ->
                    listOf(
                        // MIUI battery and performance app.
                        vendor("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                        vendor(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        ),
                    )

                OemFamily.VIVO ->
                    listOf(
                        // Funtouch / OriginOS background-app management.
                        vendor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                        vendor(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        ),
                    )

                OemFamily.OPPO, OemFamily.REALME ->
                    listOf(
                        vendor(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                        ),
                        vendor(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity",
                        ),
                        vendor(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity",
                        ),
                    )

                OemFamily.ONEPLUS ->
                    listOf(
                        // OxygenOS battery optimization page.
                        vendor(
                            "com.oneplus.security",
                            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                        ),
                    )

                OemFamily.HUAWEI ->
                    listOf(
                        // EMUI protected apps / phone manager.
                        vendor(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                        ),
                        vendor(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity",
                        ),
                    )

                OemFamily.HONOR ->
                    listOf(
                        vendor(
                            "com.hihonor.systemmanager",
                            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                        ),
                        vendor(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                        ),
                    )

                OemFamily.OTHER -> emptyList()
            }
        // Order matters: the standard
        // `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog has to come
        // first because it is the only entry point that flips
        // [PowerManager.isIgnoringBatteryOptimizations] when the user
        // accepts. Vendor activities (vivo BgStartUpManager, MIUI
        // autostart, etc.) toggle a vendor-side allowlist that does NOT
        // update the platform's standard exemption flag, so a user who
        // grants there would keep seeing "Not exempted" in the Settings
        // tab status forever — that's exactly the bug surface this
        // ordering avoids. Vendor activities are kept as fall-through
        // entries so devices on which the generic intent unexpectedly
        // refuses to resolve still have somewhere to land.
        return listOf(Candidate.GenericIgnoreBatteryOptimizations) + vendor
    }

    /**
     * Translates a [Candidate] into a runnable [Intent], scoped to
     * [packageName] for the generic fallback. Lives on the Android
     * layer because it constructs platform types — the unit tests in
     * the `:app` test source set exercise [candidatesFor] directly and
     * never reach this method.
     */
    private fun Candidate.toIntent(packageName: String): Intent =
        when (this) {
            is Candidate.VendorActivity ->
                Intent().apply {
                    component = ComponentName(this@toIntent.packageName, className)
                    // Each vendor activity is a top-level Settings page;
                    // launching it from a non-Activity context (e.g. a
                    // notification) needs NEW_TASK. We add the flag
                    // preemptively because the helper does not know its
                    // caller; Activities calling startActivity simply
                    // ignore the redundant flag.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            Candidate.GenericIgnoreBatteryOptimizations ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
        }

    /**
     * Returns the subset of [candidates] whose target activity is
     * actually resolvable on the current device. The first element of
     * the result is what the caller should `startActivity` on; the
     * remainder are kept so a UI can offer "try another path" if the
     * primary intent throws an `ActivityNotFoundException` despite
     * resolving (which can happen on a few locked-down ROM variants).
     */
    private fun resolvableIntents(
        context: Context,
        candidates: List<Candidate>,
    ): List<Intent> {
        val pm = context.packageManager
        return candidates.mapNotNull { candidate ->
            val intent = candidate.toIntent(context.packageName)
            // For implicit intents (the generic fallback) we trust the
            // platform — the action is part of the public Android API
            // and has shipped on every device since Marshmallow.
            // Explicit vendor intents are only kept if PackageManager
            // can find a matching activity right now.
            val keep =
                candidate is Candidate.GenericIgnoreBatteryOptimizations ||
                    intent.resolveActivity(pm) != null
            if (keep) intent else null
        }
    }

    /**
     * True when the user has already exempted this package from battery
     * optimization. Returns true on pre-Marshmallow devices because the
     * [PowerManager.isIgnoringBatteryOptimizations] API only exists on
     * API 23+ and the kill-the-background-app behaviour the prompt
     * mitigates was introduced in Doze mode (API 23). Returns false if
     * the [PowerManager] is unavailable for any reason.
     */
    internal fun isAlreadyExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Convenience wrapper that detects the OEM family from `Build.*`,
     * builds the candidate list scoped to [Context.getPackageName], and
     * filters it to entries the platform can actually start. Returns an
     * empty list only if the device exposes no resolvable activity at
     * all — but in practice the generic
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` always resolves, so
     * callers can treat empty as "device offline / package manager
     * dead" rather than a routine state.
     */
    internal fun intentsForCurrentDevice(context: Context): List<Intent> {
        val family =
            detectFamily(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                brand = Build.BRAND.orEmpty(),
            )
        return resolvableIntents(context, candidatesFor(family))
    }

    private fun vendor(
        pkg: String,
        cls: String,
    ): Candidate.VendorActivity = Candidate.VendorActivity(packageName = pkg, className = cls)
}
