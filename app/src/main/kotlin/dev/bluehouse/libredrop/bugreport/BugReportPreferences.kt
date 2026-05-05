/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import android.content.Context
import android.content.SharedPreferences

internal class BugReportPreferences(
    private val prefs: SharedPreferences,
) {
    fun isShakeToReportEnabled(): Boolean = prefs.getBoolean(KEY_SHAKE_TO_REPORT_ENABLED, false)

    fun setShakeToReportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHAKE_TO_REPORT_ENABLED, enabled).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "libredrop.bug_report"
        private const val KEY_SHAKE_TO_REPORT_ENABLED = "shake_to_report_enabled"

        fun from(context: Context): BugReportPreferences =
            BugReportPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
