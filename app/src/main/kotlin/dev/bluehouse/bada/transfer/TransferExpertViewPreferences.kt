/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.transfer

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings-tab toggle for the optional transfer diagnostics row.
 */
internal class TransferExpertViewPreferences(
    private val prefs: SharedPreferences,
) {
    fun isExpertViewEnabled(): Boolean = prefs.getBoolean(KEY_EXPERT_VIEW_ENABLED, false)

    fun setExpertViewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXPERT_VIEW_ENABLED, enabled).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.transfer_expert_view"
        private const val KEY_EXPERT_VIEW_ENABLED = "transfer_expert_view_enabled"

        fun from(context: Context): TransferExpertViewPreferences =
            TransferExpertViewPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
