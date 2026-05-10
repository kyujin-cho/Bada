/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SendReceiveFragmentSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/ui/SendReceiveFragment.kt")
        assertTrue("SendReceiveFragment.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `send entry points do not hard gate on wi-fi`() {
        assertFalse(
            "The Send/Receive tab must not require Wi-Fi before launching the file picker; " +
                "BLE routing needs to stay reachable even when Wi-Fi is off.",
            source.contains("ensureWifiEnabled"),
        )
        assertFalse(
            "The Send/Receive tab must not depend on WifiManager.isWifiEnabled for send entry points.",
            source.contains("WifiManager"),
        )
        assertFalse(
            "Wi-Fi settings panel launches would reintroduce the removed send-entry gate.",
            source.contains("ACTION_WIFI"),
        )
    }
}
