/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the Settings > Theme choices. Stores [AppTheme.id], a stable
 * string, so reordering or renaming the enum cannot change a user's theme,
 * and whether the Expressive theme takes its colors from the wallpaper.
 */
internal class AppThemePreferences(
    private val prefs: SharedPreferences,
) {
    fun theme(): AppTheme = AppTheme.fromId(prefs.getString(KEY_APP_THEME, null))

    /** Written synchronously: the caller restarts the UI right after. */
    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_APP_THEME, theme.id).commit()
    }

    /**
     * Expressive theme only: use the system's wallpaper colors instead of the
     * static brand palette. Off by default: the theme is picked for the vibrant
     * palette its description promises, and many system palettes are muted.
     */
    fun wallpaperColors(): Boolean = prefs.getBoolean(KEY_WALLPAPER_COLORS, false)

    /** Written synchronously: the caller restarts the UI right after. */
    fun setWallpaperColors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER_COLORS, enabled).commit()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.appearance"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_WALLPAPER_COLORS = "wallpaper_colors"

        fun from(context: Context): AppThemePreferences =
            AppThemePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
