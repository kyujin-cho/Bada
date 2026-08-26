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
 * Source regression guard for the theme-adaptive `peerNameLabel` in
 * [DeviceIconView]. The app unit-test task invokes it; it reads only the owning
 * Kotlin source and rejects an explicit text-color override or restoration of
 * the former fixed-color constant. It performs no Android rendering, lifecycle
 * work, I/O beyond that source read, or device-data access. The equivalent
 * direct source assertion has passed; Gradle execution and rendered day/night
 * UI behavior remain unverified under the current no-compilation gate.
 */
class DeviceIconViewSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/ui/sheet/DeviceIconView.kt")
        assertTrue("DeviceIconView.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `device name inherits the day-night theme text color`() {
        val peerNameLabelBlock =
            source.substringAfter("peerNameLabel =").substringBefore("addView(peerNameLabel")

        assertFalse(
            "The peer name must inherit TextView's theme color instead of overriding it.",
            peerNameLabelBlock.contains("setTextColor"),
        )
        assertFalse("Do not restore the old fixed peer-name color.", source.contains("NAME_COLOR"))
    }
}
