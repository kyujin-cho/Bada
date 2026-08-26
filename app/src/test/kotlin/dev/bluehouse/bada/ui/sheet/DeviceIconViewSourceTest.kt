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

/** Source guard for the theme-adaptive peer-name label in [DeviceIconView]. */
class DeviceIconViewSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/ui/sheet/DeviceIconView.kt")
        assertTrue("DeviceIconView.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `device name inherits the day-night theme text color`() {
        val nameViewBlock = source.substringAfter("nameView =").substringBefore("addView(nameView")

        assertFalse(
            "The peer name must inherit TextView's theme color instead of overriding it.",
            nameViewBlock.contains("setTextColor"),
        )
        assertFalse("Do not restore the old fixed peer-name color.", source.contains("NAME_COLOR"))
    }
}
