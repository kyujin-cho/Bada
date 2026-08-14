/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import android.content.Context

/**
 * Independent master switch for Google-compatible “Tap to Share” file
 * sessions. It does not read or change Name Card preferences; turning either
 * feature off leaves the other feature untouched.
 */
internal class GestureTapToSharePreferences private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS = "bada.gesture_tap_to_share"
        private const val KEY_ENABLED = "enabled"

        fun from(context: Context): GestureTapToSharePreferences = GestureTapToSharePreferences(context)
    }
}
