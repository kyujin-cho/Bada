/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.AnyRes
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.use
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.bluehouse.bada.R

/**
 * Applies the Settings > Theme choice to activities and answers theme
 * questions for code that cannot express them in resources.
 *
 * The manifest keeps every activity on its Bada window theme (Theme.Bada,
 * Theme.Bada.SendSheet, ...). Each activity calls [applyTo] before
 * `super.onCreate()`; when the user picked another theme, the manifest theme
 * is swapped for its twin with the same window setup (translucency, dim,
 * window animations). The system's translucency bookkeeping reads the
 * manifest theme, which is why every twin repeats its window flags exactly.
 */
internal object AppThemes {
    private const val TAG = "BadaAppThemes"

    /** Manifest (Bada) window theme to its Material 3 Expressive twin. */
    private val EXPRESSIVE_VARIANTS: Map<Int, Int> =
        mapOf(
            R.style.Theme_Bada to R.style.Theme_Bada_Expressive,
            R.style.Theme_Bada_SendSheet to R.style.Theme_Bada_Expressive_SendSheet,
            R.style.Theme_Bada_SendInApp to R.style.Theme_Bada_Expressive_SendInApp,
            R.style.Theme_Bada_ReceiveSheet to R.style.Theme_Bada_Expressive_ReceiveSheet,
            R.style.Theme_Bada_NameCardTransfer to R.style.Theme_Bada_Expressive_NameCardTransfer,
        )

    /** Every manifest window theme [applyTo] knows how to swap. */
    val manifestThemes: Set<Int> get() = EXPRESSIVE_VARIANTS.keys

    /**
     * The style an activity whose manifest theme is [manifestTheme] should
     * use under [theme]. A manifest theme without a twin (for example a
     * framework translucent theme) is left alone.
     */
    @StyleRes
    fun styleFor(
        theme: AppTheme,
        @StyleRes manifestTheme: Int,
    ): Int =
        when (theme) {
            AppTheme.BADA -> manifestTheme
            AppTheme.MATERIAL_EXPRESSIVE -> EXPRESSIVE_VARIANTS[manifestTheme] ?: manifestTheme
        }

    /** Call from `onCreate` before `super.onCreate()`. */
    fun applyTo(activity: Activity) {
        val theme = AppThemePreferences.from(activity).theme()
        if (theme == AppTheme.BADA) return
        val manifestTheme = manifestThemeOf(activity)
        if (manifestTheme == 0) return
        val style = styleFor(theme, manifestTheme)
        if (style != manifestTheme) activity.setTheme(style)
    }

    /**
     * Dialog builder for the current theme. The Expressive theme needs
     * MaterialAlertDialogBuilder: only it applies the M3 dialog container
     * (extra-large corners, surface-container color) and the M3 title,
     * body, and text-button styles. The Bada theme keeps the AppCompat
     * builder it always used, so its dialogs do not change.
     */
    fun alertDialogBuilder(context: Context): AlertDialog.Builder =
        if (isExpressive(context)) MaterialAlertDialogBuilder(context) else AlertDialog.Builder(context)

    /** True when [context]'s theme is the Material 3 Expressive theme. */
    fun isExpressive(context: Context): Boolean =
        context.obtainStyledAttributes(intArrayOf(R.attr.badaIsExpressive)).use { it.getBoolean(0, false) }

    /** Resolves a color token such as [R.attr.badaColorAccent] against [context]'s theme. */
    @ColorInt
    fun color(
        context: Context,
        @AttrRes attr: Int,
    ): Int = context.obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0) }

    /**
     * Resolves a reference token (a drawable such as [R.attr.badaCardBackground]
     * or a layout such as [R.attr.badaMainLayout]) to its resource id.
     */
    @AnyRes
    fun resourceId(
        context: Context,
        @AttrRes attr: Int,
    ): Int = context.obtainStyledAttributes(intArrayOf(attr)).use { it.getResourceId(0, 0) }

    @StyleRes
    private fun manifestThemeOf(activity: Activity): Int =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.packageManager.getActivityInfo(
                        activity.componentName,
                        PackageManager.ComponentInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getActivityInfo(activity.componentName, 0)
                }
            info.themeResource.takeIf { it != 0 } ?: activity.applicationInfo.theme
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "No manifest entry for ${activity.componentName}; keeping its theme", e)
            0
        }
}
