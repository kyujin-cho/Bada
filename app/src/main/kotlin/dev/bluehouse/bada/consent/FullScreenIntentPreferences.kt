/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.consent

import android.content.Context
import android.content.SharedPreferences

/**
 * "Shown once" flag for the full-screen-intent first-launch prompt,
 * mirroring [dev.bluehouse.bada.battery.BatteryOptimizationPreferences].
 *
 * The dialog raised by `MainActivity` asks the user to grant the
 * `USE_FULL_SCREEN_INTENT` special access (Android 14+) so background
 * transfer consent prompts can pop full-screen. Once the user taps Skip
 * or Open Settings, [markDismissed] keeps the one-time dialog from
 * re-raising on later cold starts. The Settings-tab row stays available
 * regardless, so the user can revisit the access without clearing app
 * data.
 */
internal class FullScreenIntentPreferences(
    private val prefs: SharedPreferences,
) {
    /**
     * True once the user has tapped Skip or successfully reached the
     * system full-screen-notifications page from the first-launch
     * dialog. The dialog stays hidden in either case.
     */
    fun hasBeenDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    /** Marks the prompt as handled. Idempotent after the first commit. */
    fun markDismissed() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    internal companion object {
        internal const val PREFS_NAME = "bada.full_screen_intent"

        private const val KEY_DISMISSED = "full_screen_intent_prompt_dismissed"

        fun from(context: Context): FullScreenIntentPreferences =
            FullScreenIntentPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
