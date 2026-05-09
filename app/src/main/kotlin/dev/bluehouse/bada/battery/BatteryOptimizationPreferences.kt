/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.battery

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny key-value store for the battery-optimization prompt's "shown
 * once" + "user dismissed" flags (issue #47).
 *
 * The issue calls for DataStore but the surface here is a single
 * boolean — pulling in `androidx.datastore` for one flag would add
 * non-trivial transitive Kotlin/coroutines weight to `:app`. We use the
 * platform-stock [SharedPreferences] instead and keep the API narrow so
 * a future migration to DataStore is a one-class swap if a richer
 * settings surface ever justifies the cost.
 *
 * The semantics are deliberately additive:
 *   * [hasBeenDismissed] returns true once the user taps Skip on the
 *     onboarding banner, *or* once the user successfully launches the
 *     OEM Settings page (either way we have nothing useful left to do).
 *   * [markDismissed] is the only mutation; there is no "reset" path
 *     because the prompt is one-time on purpose. Power users can clear
 *     app data via system Settings if they really want to revisit it.
 */
internal class BatteryOptimizationPreferences(
    private val prefs: SharedPreferences,
) {
    /**
     * True once the user has either tapped "Skip" on the onboarding
     * banner or has already successfully reached the OEM/system battery
     * Settings page. The banner stays hidden in either case.
     */
    fun hasBeenDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    /**
     * Marks the prompt as handled. Idempotent — calling this multiple
     * times is a no-op after the first commit.
     */
    fun markDismissed() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    internal companion object {
        /**
         * Storage file name. Scoped per-app so other modules can read
         * adjacent flags later without a naming collision.
         */
        internal const val PREFS_NAME = "bada.battery_optimization"

        private const val KEY_DISMISSED = "battery_optimization_prompt_dismissed"

        /**
         * Returns the application-wide instance. The underlying
         * [SharedPreferences] is process-singleton-cached by the
         * platform, so repeated calls are cheap.
         */
        fun from(context: Context): BatteryOptimizationPreferences =
            BatteryOptimizationPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
