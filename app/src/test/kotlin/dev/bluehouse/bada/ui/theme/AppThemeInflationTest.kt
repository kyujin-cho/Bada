/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.use
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import dev.bluehouse.bada.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.google.android.material.R as MaterialR

/**
 * Inflates every layout of :app under every app theme, in light and dark.
 *
 * A token or style that resolves under one theme but not the other only
 * fails at inflation time, under that theme, on that screen, and some of
 * those screens (the consent sheet, the send sheet) are on the transfer
 * path. This test is the net for that whole class of crash. It also checks
 * that each theme's view inflater produced the widgets it is meant to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppThemeInflationTest {
    private val layouts: List<Pair<String, Int>> =
        R.layout::class.java.fields
            .map { it.name to it.getInt(null) }
            .sortedBy { it.first }

    private val themes =
        listOf(
            "Theme.Bada" to R.style.Theme_Bada,
            "Theme.Bada.SendSheet" to R.style.Theme_Bada_SendSheet,
            "Theme.Bada.ReceiveSheet" to R.style.Theme_Bada_ReceiveSheet,
            "Theme.Bada.Expressive" to R.style.Theme_Bada_Expressive,
            "Theme.Bada.Expressive.SendSheet" to R.style.Theme_Bada_Expressive_SendSheet,
            "Theme.Bada.Expressive.ReceiveSheet" to R.style.Theme_Bada_Expressive_ReceiveSheet,
        )

    @Test
    fun `every layout inflates under every theme in light and dark`() {
        assertTrue("Expected the app's layouts in R.layout", layouts.size > 10)
        val failures = mutableListOf<String>()
        // Day, night, and landscape (layout-land variants such as the
        // Expressive navigation rail). Expressive themes also run with the
        // wallpaper colors overlay on top.
        for (qualifiers in listOf("notnight", "night", "land")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            for ((themeName, theme) in themes) {
                failures += inflationFailures(hostActivity(theme), "$themeName ($qualifiers)")
                if (themeName.startsWith("Theme.Bada.Expressive")) {
                    val dynamic = hostActivity(theme).also { AppThemes.applyWallpaperColors(it) }
                    failures += inflationFailures(dynamic, "$themeName + wallpaper colors ($qualifiers)")
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION") // android:statusBarColor: the theme still sets it for Android 14 and older.
    fun `wallpaper colors replace the static Expressive palette`() {
        setThemePreferences(AppTheme.MATERIAL_EXPRESSIVE, wallpaperColors = true)
        val activity = appliedActivity()

        assertTrue(AppThemes.wallpaperColorsAvailable())
        assertTrue(dynamicColorApplied(activity))
        val dynamicPrimary = activity.getColor(MaterialR.color.m3_sys_color_dynamic_light_primary)
        assertEquals(dynamicPrimary, AppThemes.color(activity, androidx.appcompat.R.attr.colorPrimary))
        assertNotEquals(
            "wallpaper primary should differ from the static one for this test to mean anything",
            activity.getColor(R.color.m3e_primary),
            dynamicPrimary,
        )
        // Window, popup and status bar backgrounds follow the overlay too.
        val surface = AppThemes.color(activity, MaterialR.attr.colorSurface)
        assertEquals(activity.getColor(MaterialR.color.m3_sys_color_dynamic_light_surface), surface)
        assertEquals(surface, AppThemes.color(activity, android.R.attr.windowBackground))
        assertEquals(surface, AppThemes.color(activity, android.R.attr.statusBarColor))
        assertEquals(
            AppThemes.color(activity, MaterialR.attr.colorSurfaceContainerHigh),
            AppThemes.color(activity, android.R.attr.colorBackgroundFloating),
        )
    }

    @Test
    fun `turning wallpaper colors off keeps the static Expressive palette`() {
        setThemePreferences(AppTheme.MATERIAL_EXPRESSIVE, wallpaperColors = false)
        val activity = appliedActivity()

        assertTrue(AppThemes.isExpressive(activity))
        assertFalse(dynamicColorApplied(activity))
        assertEquals(
            activity.getColor(R.color.m3e_primary),
            AppThemes.color(activity, androidx.appcompat.R.attr.colorPrimary),
        )
        assertEquals(activity.getColor(R.color.m3e_surface), AppThemes.color(activity, android.R.attr.windowBackground))
    }

    @Test
    fun `the Bada theme ignores wallpaper colors`() {
        setThemePreferences(AppTheme.BADA, wallpaperColors = true)
        val activity = appliedActivity()

        assertFalse(AppThemes.isExpressive(activity))
        assertFalse(dynamicColorApplied(activity))
        assertEquals(
            activity.getColor(R.color.brand_primary),
            AppThemes.color(activity, R.attr.badaColorAccent),
        )
    }

    @Test
    @Config(sdk = [28])
    fun `below Android 12 the Expressive theme keeps its static palette`() {
        setThemePreferences(AppTheme.MATERIAL_EXPRESSIVE, wallpaperColors = true)
        val activity = appliedActivity()

        assertFalse(AppThemes.wallpaperColorsAvailable())
        assertTrue(AppThemes.isExpressive(activity))
        assertFalse(dynamicColorApplied(activity))
        assertEquals(
            activity.getColor(R.color.m3e_primary),
            AppThemes.color(activity, androidx.appcompat.R.attr.colorPrimary),
        )
    }

    private fun inflationFailures(
        activity: AppCompatActivity,
        label: String,
    ): List<String> {
        val expressive = AppThemes.isExpressive(activity)
        return layouts
            // Expressive-only layout variants (*_m3e) hold M3 widgets that need
            // an M3 theme; the Bada theme never inflates them.
            .filter { (layoutName, _) -> expressive || !layoutName.endsWith("_m3e") }
            .mapNotNull { (layoutName, layout) ->
                runCatching { activity.layoutInflater.inflate(layout, FrameLayout(activity), false) }
                    .exceptionOrNull()
                    ?.let { e -> "$layoutName under $label: $e" }
            }
    }

    @Test
    fun `the Bada theme keeps AppCompat buttons and switches`() {
        val settings = inflateSettings(R.style.Theme_Bada)
        assertFalse(settings.findViewById<Button>(R.id.main_save_location_pick) is MaterialButton)
        assertFalse(settings.findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch) is MaterialSwitch)
        assertEquals(null, settings.findViewById<View>(R.id.settings_wallpaper_colors_switch))
    }

    @Test
    fun `the Expressive theme inflates M3 buttons and switches`() {
        val settings = inflateSettings(R.style.Theme_Bada_Expressive)
        assertTrue(settings.findViewById<Button>(R.id.main_save_location_pick) is MaterialButton)
        assertTrue(
            "settings button rows should be M3 Expressive button groups",
            settings.findViewById<View>(R.id.main_save_location_pick).parent is BadaButtonGroup,
        )
        assertTrue(
            "switch settings should be an expressive segmented list",
            settings.findViewById<View>(R.id.settings_switch_group_list) != null,
        )
        assertTrue(
            "advertised name should be an M3 text field",
            settings.findViewById<View>(R.id.main_advertised_name_input) is TextInputEditText,
        )
        assertTrue(settings.findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch) is MaterialSwitch)
        assertTrue(settings.findViewById<SwitchCompat>(R.id.settings_master_switch) is MaterialSwitch)
        assertTrue(settings.findViewById<SwitchCompat>(R.id.settings_wallpaper_colors_switch) is MaterialSwitch)

        val home = inflate(R.style.Theme_Bada_Expressive, R.layout.fragment_send_receive)
        val toggle = home.findViewById<Button>(R.id.main_always_visible_button)
        assertTrue("visibility toggle should be a MaterialButton", toggle is MaterialButton)
        assertTrue("visibility toggle should be checkable", (toggle as MaterialButton).isCheckable)
    }

    @Test
    fun `isExpressive follows the theme`() {
        assertFalse(AppThemes.isExpressive(hostActivity(R.style.Theme_Bada)))
        assertTrue(AppThemes.isExpressive(hostActivity(R.style.Theme_Bada_Expressive)))
        assertEquals(
            "Bada accent token must stay the brand blue",
            RuntimeEnvironment.getApplication().getColor(R.color.brand_primary),
            AppThemes.color(hostActivity(R.style.Theme_Bada), R.attr.badaColorAccent),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxxhdpi")
    fun `the Bada bottom bar keeps its calibrated Material 1_12 item geometry`() {
        // Material 1.14 re-laid out navigation items; Widget.Bada.BottomNav pins
        // the 1.12 geometry, calibrated by pixel diff against the 1.12 build on
        // a device (Korean labels). Robolectric's fallback font is shorter, so
        // these are this test's readings of that calibrated layout, not dp
        // values from the device: a Material upgrade that moves the items
        // again changes them and fails here.
        val activity = hostActivity(R.style.Theme_Bada)
        val nav =
            activity.layoutInflater
                .inflate(R.layout.activity_main, FrameLayout(activity), false)
                .findViewById<BottomNavigationView>(R.id.main_bottom_nav)
        (nav.parent as ViewGroup).removeView(nav)
        val density = activity.resources.displayMetrics.density
        val host = FrameLayout(activity).apply { addView(nav, FrameLayout.LayoutParams(-1, (64 * density).toInt())) }
        activity.setContentView(host)
        host.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((64 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)

        val item = (nav.getChildAt(0) as ViewGroup).getChildAt(0)
        val icon = item.findViewById<View>(MaterialR.id.navigation_bar_item_icon_view)
        val label = item.findViewById<TextView>(MaterialR.id.navigation_bar_item_small_label_view)
        val iconTop = topIn(host, icon) / density
        val labelBaseline = (topIn(host, label) + label.baseline) / density
        assertEquals("icon top", 12.5f, iconTop, 0.5f)
        assertEquals("label baseline", 51.5f, labelBaseline, 0.5f)
    }

    private fun topIn(
        ancestor: View,
        view: View,
    ): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** fragment_settings with its theme-variant regions inflated, as SettingsFragment does. */
    private fun inflateSettings(theme: Int): View =
        inflate(theme, R.layout.fragment_settings).also {
            ExpressiveLists.inflateStub(it, R.id.main_advertised_name_input_stub)
            ExpressiveLists.inflateStub(it, R.id.settings_switch_group_stub)
            ExpressiveLists.inflateStub(it, R.id.settings_theme_extras_stub)
        }

    private fun setThemePreferences(
        theme: AppTheme,
        wallpaperColors: Boolean,
    ) {
        val prefs = AppThemePreferences.from(RuntimeEnvironment.getApplication())
        prefs.setTheme(theme)
        prefs.setWallpaperColors(wallpaperColors)
    }

    private fun dynamicColorApplied(activity: AppCompatActivity): Boolean =
        activity.obtainStyledAttributes(intArrayOf(MaterialR.attr.isMaterial3DynamicColorApplied)).use {
            it.getBoolean(0, false)
        }

    /**
     * An activity on the Bada manifest theme that runs [AppThemes.applyTo]
     * before super.onCreate, as every app activity does, so the saved theme
     * preferences decide its theme.
     */
    private fun appliedActivity(): AppCompatActivity =
        Robolectric.buildActivity(AppliedThemeHostActivity::class.java).create().get()

    private fun inflate(
        theme: Int,
        layout: Int,
    ): View {
        val activity = hostActivity(theme)
        return activity.layoutInflater.inflate(layout, FrameLayout(activity), false).also {
            assertTrue(it is ViewGroup)
        }
    }

    /** An AppCompatActivity themed like the app's activities, so AppCompat's view inflater runs. */
    private fun hostActivity(theme: Int): AppCompatActivity {
        val application = RuntimeEnvironment.getApplication()
        val component = ComponentName(application, ThemeHostActivity::class.java)
        shadowOf(application.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = component.className
                packageName = component.packageName
                this.theme = theme
            },
        )
        return Robolectric.buildActivity(ThemeHostActivity::class.java).create().get()
    }
}

/** Bare host for [AppThemeInflationTest]; exists only on the test classpath. */
class ThemeHostActivity : AppCompatActivity()

/**
 * Host that applies the saved app theme the way the app's activities do,
 * for the Bada manifest theme. It names that theme itself: Robolectric does
 * not implement the Android 13+ activity info lookup AppThemes.applyTo uses.
 */
class AppliedThemeHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemes.applyTo(this, R.style.Theme_Bada)
        super.onCreate(savedInstanceState)
    }
}
