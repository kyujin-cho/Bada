/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the button-row layout rule from #307.
 *
 * `Widget.Bada.Button` allows two-line labels. In a horizontal
 * `LinearLayout` with baseline alignment on (the platform default), a
 * two-line button is shifted down so its first line sits on the one-line
 * neighbour's baseline, while `measureHorizontal` still sizes a
 * `wrap_content` row to the tallest child. The shifted button's bottom is
 * then clipped, which is what the Settings "Choose folder" pill showed on
 * a 360dp-wide phone. Every row that holds two or more buttons must set
 * `android:baselineAligned="false"`.
 */
class ButtonRowBaselineTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val buttonTags = setOf("Button", "AppCompatButton", "MaterialButton")

    private val layoutFiles: List<File> by lazy {
        val resDir = File("src/main/res")
        assertTrue("res dir should exist at ${resDir.absolutePath}", resDir.isDirectory)
        resDir
            .listFiles { dir -> dir.isDirectory && dir.name.startsWith("layout") }
            .orEmpty()
            .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
            .sortedBy { it.path }
    }

    @Test
    fun `horizontal button rows disable baseline alignment`() {
        val rows = layoutFiles.flatMap { file -> buttonRows(file).map { row -> file to row } }
        assertTrue(
            "Expected to find the Settings save-location row; the layout scan is not seeing the res dir.",
            rows.any { (_, row) -> row.childButtonIds().contains("@+id/main_save_location_pick") },
        )

        val offenders =
            rows
                .filter { (_, row) -> row.getAttributeNS(androidNs, "baselineAligned") != "false" }
                .map { (file, row) -> "${file.parentFile.name}/${file.name}: ${row.childButtonIds()}" }
        assertTrue(
            "These horizontal button rows must set android:baselineAligned=\"false\" so a two-line " +
                "label is not shifted down and clipped (#307):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun buttonRows(file: File): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file)
        val linearLayouts = document.getElementsByTagName("LinearLayout")
        return (0 until linearLayouts.length)
            .map { linearLayouts.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "orientation") != "vertical" }
            .filter { it.childButtons().size >= 2 }
    }

    private fun Element.childButtons(): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { it.tagName.substringAfterLast('.') in buttonTags }

    private fun Element.childButtonIds(): List<String> = childButtons().map { it.getAttributeNS(androidNs, "id") }
}
