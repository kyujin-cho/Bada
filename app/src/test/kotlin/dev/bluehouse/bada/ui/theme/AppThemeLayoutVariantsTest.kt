/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Per-theme layout variants (a ViewStub region whose layout is a theme
 * token): `x.xml` for the Bada theme and `x_m3e.xml` for the Material 3
 * Expressive theme. Code looks views up by id and casts them, so both
 * variants must define the same ids, and each id's widget in the Expressive
 * variant must be usable as the type the Bada variant declares.
 */
class AppThemeLayoutVariantsTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val layoutDirs = listOf(File("src/main/res/layout"), File("src/main/res/layout-land"))

    /** Expressive class -> Bada class it may stand in for (subclass or the type code casts to). */
    private val compatible =
        mapOf(
            "Button" to setOf("FrameLayout", "View", "Button"),
            "com.google.android.material.textfield.TextInputEditText" to setOf("EditText"),
            "androidx.appcompat.widget.SwitchCompat" to setOf("androidx.appcompat.widget.SwitchCompat"),
            "RadioButton" to setOf("RadioButton"),
            "TextView" to setOf("TextView"),
            // MainActivity reads both as NavigationBarView.
            "com.google.android.material.navigationrail.NavigationRailView" to
                setOf("dev.bluehouse.bada.ui.ElasticBottomNavigationView"),
        )

    @Test
    fun `every Expressive variant has a Bada twin with the same ids`() {
        val variants =
            layoutDirs.flatMap { dir ->
                dir.listFiles { f -> f.name.endsWith("_m3e.xml") }.orEmpty().toList()
            }
        assertTrue("Expected Expressive layout variants", variants.size >= 5)
        variants.forEach { m3e ->
            val bada = File(m3e.parentFile, m3e.name.removeSuffix("_m3e.xml") + ".xml")
            assertTrue("${m3e.name} has no Bada variant ${bada.name}", bada.exists())
            val badaIds = ids(bada)
            val m3eIds = ids(m3e)
            // Ids only the Expressive variant has are fine (code finds them null-safely);
            // every Bada id must exist in the Expressive variant, except the
            // button-row Space that the Expressive button group drops.
            val missing = badaIds.keys - m3eIds.keys - setOf("consent_completed_button_gap")
            assertTrue("${m3e.name} lacks ids $missing", missing.isEmpty())
            badaIds.forEach { (id, badaTag) ->
                val m3eTag = m3eIds[id] ?: return@forEach
                if (m3eTag == badaTag) return@forEach
                assertTrue(
                    "${m3e.name}: $id is $m3eTag, Bada declares $badaTag",
                    compatible[m3eTag].orEmpty().contains(badaTag),
                )
            }
        }
    }

    @Test
    fun `theme tokens point at matching variants`() {
        val bada = File("src/main/res/values/themes.xml").readText()
        val m3e = File("src/main/res/values/themes_expressive.xml").readText()
        val layoutTokens =
            Regex("""<item name="(bada\w+Layout)">@layout/(\w+)</item>""")
                .findAll(bada)
                .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("Expected layout tokens in Theme.Bada", layoutTokens.size >= 4)
        layoutTokens.forEach { (token, layout) ->
            assertTrue(
                "$token in themes_expressive.xml should point at ${layout}_m3e",
                m3e.contains("""<item name="$token">@layout/${layout}_m3e</item>"""),
            )
        }
        assertEquals(layoutTokens.size, Regex("""<item name="bada\w+Layout">""").findAll(m3e).count())
    }

    private fun ids(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.hasAttributeNS(androidNs, "id") }
            .associate { it.getAttributeNS(androidNs, "id").removePrefix("@+id/") to it.tagName }
    }
}
