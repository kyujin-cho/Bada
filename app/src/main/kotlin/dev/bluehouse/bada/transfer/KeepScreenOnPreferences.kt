/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.transfer

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing display preference for long-running transfers.
 *
 * Kept as a narrow SharedPreferences wrapper to match the rest of the
 * Settings tab's lightweight boolean stores.
 */
internal class KeepScreenOnPreferences(
    private val prefs: SharedPreferences,
) {
    fun isKeepScreenOnDuringTransfersEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_SCREEN_ON_DURING_TRANSFERS, true)

    fun setKeepScreenOnDuringTransfersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON_DURING_TRANSFERS, enabled).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.transfer_display"
        private const val KEY_KEEP_SCREEN_ON_DURING_TRANSFERS = "keep_screen_on_during_transfers"

        fun from(context: Context): KeepScreenOnPreferences =
            KeepScreenOnPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
