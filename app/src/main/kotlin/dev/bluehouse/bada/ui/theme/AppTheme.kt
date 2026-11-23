/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import androidx.annotation.StringRes
import dev.bluehouse.bada.R

/**
 * The app themes a user can pick under Settings > Theme.
 *
 * [id] is what [AppThemePreferences] persists, so it must never change once
 * shipped; renaming the enum constant is fine. An unknown stored id (from a
 * newer build, or a theme that was removed) falls back to [DEFAULT].
 */
internal enum class AppTheme(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    /** The original Bada look: frosted buttons, MaterialComponents Bridge widgets. */
    BADA("bada", R.string.settings_theme_bada, R.string.settings_theme_bada_description),

    /** Material 3 Expressive, built from the May 2025 M3 Expressive guidance. */
    MATERIAL_EXPRESSIVE(
        "material_expressive",
        R.string.settings_theme_material_expressive,
        R.string.settings_theme_material_expressive_description,
    ),
    ;

    companion object {
        val DEFAULT: AppTheme = BADA

        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
