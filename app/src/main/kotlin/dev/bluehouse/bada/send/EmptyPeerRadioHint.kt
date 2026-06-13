/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import androidx.annotation.StringRes
import dev.bluehouse.bada.R

/**
 * Pure-JVM helper that maps the current radio state to the appropriate
 * contextual empty-state string resource for the send picker (#209).
 *
 * When both Bluetooth and Wi-Fi are unavailable the user has no discovery
 * path at all and needs the strongest nudge. When only one radio is
 * missing the hint is scoped to that gap while noting the remaining path
 * still works. When both radios are available the generic message suffices.
 *
 * The mapping is a plain function with no Android imports so it can be
 * driven by JVM unit tests without Robolectric. Radio state is read by the
 * Android-side [RadioStateReader] and passed in as plain Booleans.
 */
internal object EmptyPeerRadioHint {
    /**
     * Return the [StringRes] that best describes why no peers have been
     * found given the current radio state.
     *
     * @param bluetoothEnabled `true` when the Bluetooth adapter is
     *   present and enabled. A null adapter (no BT hardware) counts as
     *   `false`.
     * @param wifiConnected `true` when the active network has
     *   [android.net.NetworkCapabilities.TRANSPORT_WIFI].
     */
    @StringRes
    fun stringResFor(
        bluetoothEnabled: Boolean,
        wifiConnected: Boolean,
    ): Int =
        when {
            !bluetoothEnabled && !wifiConnected ->
                R.string.send_empty_state_no_bt_no_wifi
            !bluetoothEnabled ->
                R.string.send_empty_state_no_bt
            !wifiConnected ->
                R.string.send_empty_state_no_wifi
            else ->
                R.string.send_empty_state
        }
}
