/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.content.SharedPreferences
import dev.bluehouse.bada.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AppThemeTest {
    @Test
    fun `stored ids round-trip to their theme`() {
        AppTheme.entries.forEach { theme -> assertEquals(theme, AppTheme.fromId(theme.id)) }
    }

    @Test
    fun `missing or unknown ids fall back to the Bada theme`() {
        assertEquals(AppTheme.BADA, AppTheme.DEFAULT)
        assertEquals(AppTheme.BADA, AppTheme.fromId(null))
        assertEquals(AppTheme.BADA, AppTheme.fromId("a-theme-from-a-newer-build"))
    }

    @Test
    fun `persisted ids stay stable`() {
        // These strings live in users' SharedPreferences. Changing one silently
        // resets everyone who picked that theme back to the default.
        assertEquals("bada", AppTheme.BADA.id)
        assertEquals("material_expressive", AppTheme.MATERIAL_EXPRESSIVE.id)
        assertEquals(
            AppTheme.entries.size,
            AppTheme.entries
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `preferences default to Bada and keep the last choice`() {
        val backing = fakePreferences()
        val prefs = AppThemePreferences(backing)
        assertEquals(AppTheme.BADA, prefs.theme())

        prefs.setTheme(AppTheme.MATERIAL_EXPRESSIVE)

        assertEquals(AppTheme.MATERIAL_EXPRESSIVE, prefs.theme())
        assertEquals(AppTheme.MATERIAL_EXPRESSIVE, AppThemePreferences(backing).theme())
    }

    @Test
    fun `wallpaper colors default to off and keep the last choice`() {
        val backing = fakePreferences()
        val prefs = AppThemePreferences(backing)
        assertFalse(prefs.wallpaperColors())

        prefs.setWallpaperColors(true)

        assertTrue(prefs.wallpaperColors())
        assertTrue(AppThemePreferences(backing).wallpaperColors())
    }

    @Test
    fun `the Bada theme keeps every manifest theme`() {
        AppThemes.manifestThemes.forEach { manifestTheme ->
            assertEquals(manifestTheme, AppThemes.styleFor(AppTheme.BADA, manifestTheme))
        }
    }

    @Test
    fun `the Expressive theme swaps each manifest theme for its twin`() {
        val expected =
            mapOf(
                R.style.Theme_Bada to R.style.Theme_Bada_Expressive,
                R.style.Theme_Bada_SendSheet to R.style.Theme_Bada_Expressive_SendSheet,
                R.style.Theme_Bada_SendInApp to R.style.Theme_Bada_Expressive_SendInApp,
                R.style.Theme_Bada_ReceiveSheet to R.style.Theme_Bada_Expressive_ReceiveSheet,
                R.style.Theme_Bada_NameCardTransfer to R.style.Theme_Bada_Expressive_NameCardTransfer,
            )
        assertEquals(expected.keys, AppThemes.manifestThemes)
        expected.forEach { (manifestTheme, twin) ->
            assertEquals(twin, AppThemes.styleFor(AppTheme.MATERIAL_EXPRESSIVE, manifestTheme))
        }
        // A manifest theme without a twin is left alone instead of crashing.
        val frameworkTheme = android.R.style.Theme_Translucent_NoTitleBar
        assertEquals(frameworkTheme, AppThemes.styleFor(AppTheme.MATERIAL_EXPRESSIVE, frameworkTheme))
    }

    /** Minimal string and boolean SharedPreferences backed by a map. */
    private fun fakePreferences(): SharedPreferences {
        val store = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> {
                        store[args[0] as String] = args[1]
                        editor
                    }
                    "apply" -> Unit
                    "commit" -> true
                    else -> error("Unexpected Editor.${method.name}")
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> store[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected SharedPreferences.${method.name}")
            }
        } as SharedPreferences
    }
}
