/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source regression guard for the vector device-type icon in
 * [RingProgressView] (#277). The disc glyph moved from a hardcoded
 * phone emoji to VectorDrawable resources drawn on the canvas; this
 * rejects a restoration of the former emoji rendering or the
 * string-glyph API. It performs no Android rendering, lifecycle work,
 * or device-data access.
 */
class RingProgressViewSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/ui/sheet/RingProgressView.kt")
        assertTrue("RingProgressView.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `the disc icon is a vector drawable, not an emoji glyph`() {
        assertFalse("Do not restore the emoji glyph default.", source.contains("📱"))
        assertFalse("Do not restore emoji rendering via drawText.", source.contains("drawText"))
        assertFalse("Do not restore the string-glyph API; use setIconResId.", source.contains("setGlyph"))
    }

    @Test
    fun `a bare view still defaults to the smartphone icon`() {
        assertTrue(
            "The icon default must stay a real drawable resource (#277).",
            source.contains("R.drawable.ic_device_smartphone_24"),
        )
        assertTrue(
            "The icon swap API must stay available for the device-type mapping.",
            source.contains("setIconResId"),
        )
    }
}
