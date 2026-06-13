/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [EmptyPeerRadioHint], the helper that maps the
 * current radio state to a contextual empty-state string resource (#209).
 *
 * No Android framework is involved — the mapping is a plain function that
 * takes two Booleans and returns an @StringRes Int, so Robolectric is not
 * needed and the test runs on the JVM directly.
 */
class EmptyPeerRadioHintTest {
    @Test
    fun `both radios off returns strongest hint`() {
        val resId =
            EmptyPeerRadioHint.stringResFor(
                bluetoothEnabled = false,
                wifiConnected = false,
            )
        assertEquals(R.string.send_empty_state_no_bt_no_wifi, resId)
    }

    @Test
    fun `bluetooth off wifi on returns bluetooth hint`() {
        val resId =
            EmptyPeerRadioHint.stringResFor(
                bluetoothEnabled = false,
                wifiConnected = true,
            )
        assertEquals(R.string.send_empty_state_no_bt, resId)
    }

    @Test
    fun `bluetooth on wifi off returns wifi hint`() {
        val resId =
            EmptyPeerRadioHint.stringResFor(
                bluetoothEnabled = true,
                wifiConnected = false,
            )
        assertEquals(R.string.send_empty_state_no_wifi, resId)
    }

    @Test
    fun `both radios on returns generic message`() {
        val resId =
            EmptyPeerRadioHint.stringResFor(
                bluetoothEnabled = true,
                wifiConnected = true,
            )
        assertEquals(R.string.send_empty_state, resId)
    }
}
