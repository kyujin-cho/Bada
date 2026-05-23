/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.bluehouse.bada.MainActivity
import dev.bluehouse.bada.R
import dev.bluehouse.bada.onboarding.PermissionRequirements
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService

/**
 * Quick Settings tile that turns Bada "on" — i.e. forces the receiver to
 * be discoverable to nearby Quick Share senders — straight from the
 * system Quick Settings panel, without opening the app.
 *
 * Tapping the tile toggles the same process-wide
 * [MdnsVisibilityOverrideHolder] "always visible" override that the
 * in-app Send/Receive pill drives (see `SendReceiveFragment`), and brings
 * the [ReceiverForegroundService] up/down to back it. ON publishes the
 * mDNS + BLE fast advertisement unconditionally; OFF clears the override
 * and stops the receiver.
 *
 * State model: the tile mirrors [MdnsVisibilityOverrideHolder.isActive].
 * The override lives in memory only and resets on process death, so a
 * cold Quick Settings panel correctly shows the tile OFF (nothing is
 * actually advertising). [onStartListening] re-syncs every time the panel
 * opens, which keeps the tile consistent with the in-app pill even though
 * one cannot change the override while the other is on screen.
 *
 * This is a standard (listening) tile rather than an active tile: the
 * override has no cross-module way to push `requestListeningState`, and
 * re-reading the holder in [onStartListening] is enough for eventual
 * consistency.
 */
internal class BadaQuickShareTileService : TileService() {
    /**
     * Fires each time the Quick Settings panel becomes visible. Re-read
     * the current override so the tile reflects state changed elsewhere
     * (the in-app pill, or a process restart that reset the override).
     */
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        if (MdnsVisibilityOverrideHolder.isActive) {
            turnOff()
        } else {
            turnOn()
        }
        syncTile()
    }

    private fun turnOn() {
        // Being discoverable needs the mandatory discovery permission
        // (NEARBY_WIFI_DEVICES on API 33+). If it isn't granted yet,
        // toggling the override would light up a switch that can never
        // actually advertise — so bounce the user into the app instead,
        // which routes to the permissions onboarding. This mirrors
        // MainActivity's own service-start gate.
        if (!PermissionRequirements.allGranted(this) &&
            !PermissionRequirements.onlyOptionalMissing(this)
        ) {
            openApp()
            return
        }

        MdnsVisibilityOverrideHolder.setAlwaysVisible(true)
        try {
            ReceiverForegroundService.start(this)
        } catch (e: IllegalStateException) {
            // Android 12+ forbids most background foreground-service
            // starts; ForegroundServiceStartNotAllowedException (API 31+)
            // extends IllegalStateException, and a Quick Settings tap is
            // NOT one of the platform's documented start exemptions. When
            // Bada is fully backgrounded the start can therefore be
            // rejected. Fall back to opening the app: MainActivity starts
            // the receiver from a visible (exempt) context, and the
            // override we just set makes the gate advertise immediately.
            Log.w(TAG, "Foreground-service start from tile rejected; opening app instead", e)
            openApp()
        }
    }

    private fun turnOff() {
        MdnsVisibilityOverrideHolder.setAlwaysVisible(false)
        // stop() delivers ACTION_STOP through startService to the
        // already-running service. Re-delivering to a running service is
        // allowed from the background, so this needs no FGS-start handling.
        ReceiverForegroundService.stop(this)
    }

    /**
     * Push the current override state onto the tile. Safe to call from
     * any callback; no-ops if the platform has not handed us a [Tile]
     * yet (e.g. before the service is fully bound).
     */
    private fun syncTile() {
        val tile = qsTile ?: return
        val active = MdnsVisibilityOverrideHolder.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_bada_visible)
        val statusRes = if (active) R.string.qs_tile_subtitle_on else R.string.qs_tile_subtitle_off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(statusRes)
        }
        tile.contentDescription =
            getString(
                if (active) R.string.qs_tile_content_desc_on else R.string.qs_tile_content_desc_off,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(statusRes)
        }
        tile.updateTile()
    }

    /**
     * Collapse the panel and bring up [MainActivity]. Used both when a
     * mandatory permission is missing (MainActivity routes to onboarding)
     * and as the fallback when a background foreground-service start is
     * rejected.
     *
     * API 34 made [startActivityAndCollapse] with a bare `Intent` throw;
     * the `PendingIntent` overload it added is the supported path there,
     * while older platforms still take the `Intent` form.
     */
    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "BadaQsTile"
    }
}
