/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import dev.bluehouse.bada.protocol.endpoint.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM test for the [DeviceIconView.iconResIdFor] device-type → icon
 * mapping (#277). The app unit-test classpath does not carry the
 * module's own generated R class, so this asserts the behavioural
 * contract (distinct icons for distinct types, shared fallback for the
 * rest); the exact drawable-resource wiring is guarded by
 * [DeviceIconViewSourceTest] and by resource compilation in
 * `:app:assembleDebug`.
 */
class DeviceIconViewGlyphMappingTest {
    @Test
    fun `laptop and tablet peers get distinct icons`() {
        val laptop = DeviceIconView.iconResIdFor(DeviceType.LAPTOP)
        val tablet = DeviceIconView.iconResIdFor(DeviceType.TABLET)
        val phone = DeviceIconView.iconResIdFor(DeviceType.PHONE)

        assertNotEquals("Laptop must not share the phone's icon.", phone, laptop)
        assertNotEquals("Tablet must not share the phone's icon.", phone, tablet)
        assertNotEquals("Laptop and tablet must not share an icon.", laptop, tablet)
    }

    @Test
    fun `unknown device types fall back to the phone icon`() {
        // CAR / FOLDABLE / XR icons are a follow-up; UNKNOWN and the
        // uniconned types must all share the phone glyph so the disc
        // never renders empty (#277).
        val phone = DeviceIconView.iconResIdFor(DeviceType.PHONE)
        for (type in listOf(DeviceType.UNKNOWN, DeviceType.CAR, DeviceType.FOLDABLE, DeviceType.XR)) {
            assertEquals("Type $type must fall back to the phone icon.", phone, DeviceIconView.iconResIdFor(type))
        }
    }
}
