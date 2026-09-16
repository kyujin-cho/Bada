/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source regression guard for the device-type field in the send
 * picker's render-skip snapshot (#277). The gate in
 * [SendPeerPickerController.renderPeerList] compares the last rendered
 * row snapshot; a device type that arrives after the row was first
 * drawn must still trigger a repaint with the right device-type icon.
 * Dropping `deviceType` from [SendPeerPickerController.RenderedRowSnapshot]
 * or from the row build would silently freeze the icon on the fallback
 * glyph, so this guard rejects both.
 *
 * It performs no Android rendering, lifecycle work, or device-data
 * access.
 */
class SendPeerPickerControllerSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/send/SendPeerPickerController.kt")
        assertTrue("SendPeerPickerController.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `the render-skip snapshot captures the device type`() {
        val snapshotBlock =
            source.substringAfter("private data class RenderedRowSnapshot(").substringBefore(")")
        assertTrue(
            "RenderedRowSnapshot must capture deviceType so late type changes repaint (#277).",
            snapshotBlock.contains("val deviceType: DeviceType"),
        )
        val snapshotBuild = source.substringAfter("val targetSnapshot =")
        assertTrue(
            "The snapshot build must thread the row's deviceType into RenderedRowSnapshot.",
            snapshotBuild.substringBefore("if (targetSnapshot").contains("row.deviceType"),
        )
    }

    @Test
    fun `the row build threads deviceType into the icon view`() {
        assertTrue(
            "DeviceIconView construction must pass the peer's deviceType (#277).",
            source.contains("DeviceIconView(context, stableId, target.title, target.deviceType)"),
        )
    }

    @Test
    fun `peers without endpoint info fall back to UNKNOWN`() {
        assertTrue(
            "The peerDeviceType helper must keep the UNKNOWN fallback for peers without endpoint info.",
            source.contains("peer.endpointInfo?.deviceType ?: DeviceType.UNKNOWN"),
        )
    }
}
