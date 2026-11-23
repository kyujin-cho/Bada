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
 * Static guards for the theme token layer (res/values/attrs_theme.xml).
 *
 * A token that one app theme forgets to set does not fail the build: the
 * layout inflates fine under the other theme and throws (or renders
 * nothing) only on the screen that uses it, under that theme. These checks
 * catch that on the JVM. AppThemeInflationTest then inflates every layout
 * under every theme.
 */
class AppThemeTokensTest {
    private val resDir = File("src/main/res")
    private val sourceDir = File("src/main/kotlin")

    private val styles: Map<String, Element> by lazy {
        val valuesFiles = File(resDir, "values").listFiles { f -> f.extension == "xml" }.orEmpty()
        valuesFiles
            .flatMap { file -> elements(file, "style") }
            .associateBy { it.getAttribute("name") }
    }

    private val declaredTokens: Set<String> by lazy {
        elements(File(resDir, "values/attrs_theme.xml"), "attr").map { it.getAttribute("name") }.toSet()
    }

    @Test
    fun `every token is set by every app theme`() {
        assertTrue("attrs_theme.xml should declare the bada tokens", declaredTokens.size > 20)
        listOf("Theme.Bada", "Theme.Bada.Expressive").forEach { theme ->
            val missing = declaredTokens - LAYOUT_MARKERS - itemNames(theme)
            assertTrue("$theme does not set: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `every referenced token is declared`() {
        val tokenRef = Regex("""(?:\?attr/|R\.attr\.)(bada[A-Za-z0-9]+)""")
        val referenced =
            (resDir.walkTopDown() + sourceDir.walkTopDown())
                .filter { it.isFile && (it.extension == "xml" || it.extension == "kt") }
                .flatMap { file -> tokenRef.findAll(file.readText()).map { it.groupValues[1] to file.name } }
                .toList()
        assertTrue("Expected token references in layouts and code", referenced.size > 50)
        val undeclared = referenced.filter { (token, _) -> token !in declaredTokens }
        assertTrue("Undeclared tokens: $undeclared", undeclared.isEmpty())
    }

    @Test
    fun `each Expressive window theme repeats its Bada twin's window setup`() {
        // The system decides whether an activity is translucent from the
        // manifest (Bada) theme, so a twin with different window flags
        // would render a sheet over a black window, or the reverse.
        val windowAttrs =
            listOf(
                "android:windowIsTranslucent",
                "android:windowBackground",
                "android:windowContentOverlay",
                "windowNoTitle",
                "android:windowNoTitle",
                "android:backgroundDimEnabled",
                "android:backgroundDimAmount",
                "android:windowAnimationStyle",
            )
        val twins =
            mapOf(
                "Theme.Bada.SendSheet" to "Theme.Bada.Expressive.SendSheet",
                "Theme.Bada.SendInApp" to "Theme.Bada.Expressive.SendInApp",
                "Theme.Bada.ReceiveSheet" to "Theme.Bada.Expressive.ReceiveSheet",
                "Theme.Bada.NameCardTransfer" to "Theme.Bada.Expressive.NameCardTransfer",
            )
        twins.forEach { (bada, expressive) ->
            windowAttrs.forEach { attr ->
                assertEquals("$expressive $attr", item(bada, attr), item(expressive, attr))
            }
        }
    }

    @Test
    fun `every manifest activity theme has an Expressive twin`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val themes =
            Regex("""android:theme="@style/(Theme\.Bada[A-Za-z.]*)"""")
                .findAll(manifest)
                .map { it.groupValues[1] }
                .toSet()
        assertTrue("Expected Bada themes in the manifest", "Theme.Bada" in themes)
        themes.forEach { theme ->
            val twin = theme.replaceFirst("Theme.Bada", "Theme.Bada.Expressive")
            assertTrue("$theme has no $twin in themes_expressive.xml", styles.containsKey(twin))
        }
    }

    /** Item names a style sets, including those inherited through dotted or explicit parents. */
    private fun itemNames(style: String): Set<String> {
        val element = styles[style] ?: return emptySet()
        val own =
            (0 until element.childNodes.length)
                .mapNotNull { element.childNodes.item(it) as? Element }
                .filter { it.tagName == "item" }
                .map { it.getAttribute("name") }
                .toSet()
        val parent =
            element
                .getAttribute(
                    "parent",
                ).ifEmpty { style.substringBeforeLast('.', "").takeIf { it.startsWith("Theme.Bada") } }
        return own + (parent?.let(::itemNames) ?: emptySet())
    }

    private fun item(
        style: String,
        name: String,
    ): String? =
        styles[style]?.let { element ->
            (0 until element.childNodes.length)
                .mapNotNull { element.childNodes.item(it) as? Element }
                .firstOrNull { it.tagName == "item" && it.getAttribute("name") == name }
                ?.textContent
                ?.trim()
        }

    private fun elements(
        file: File,
        tag: String,
    ): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        /** Attributes set on layout elements, not by themes (see attrs_theme.xml). */
        val LAYOUT_MARKERS = setOf("badaButtonGroup")
    }
}
