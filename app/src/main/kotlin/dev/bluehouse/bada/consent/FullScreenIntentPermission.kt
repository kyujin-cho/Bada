/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.consent

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Helpers for the `USE_FULL_SCREEN_INTENT` special app access that the
 * incoming-transfer consent popup (#22) relies on to raise
 * [ConsentTrampolineActivity] over the lock screen / other apps when a
 * transfer arrives while Bada is in the background.
 *
 * Android 14 (API 34) turned `USE_FULL_SCREEN_INTENT` from an
 * install-time grant into a special app access that is auto-denied for
 * everything except the default calendar / alarm apps. When it is not
 * granted, `ConsentNotification`'s `setFullScreenIntent(...)` silently
 * degrades to a heads-up notification: the PIN and Accept / Reject
 * actions are still shown, but the full-screen prompt no longer pops
 * automatically. This helper lets the app detect that state and route
 * the user to the system page that toggles it.
 *
 * On API <= 33 the permission is granted at install time, so
 * [isApplicable] is false (nothing to ask for) and [isGranted] is always
 * true.
 */
internal object FullScreenIntentPermission {
    /**
     * True when the platform will honour a full-screen intent for this
     * app. Always true below API 34 (install-time grant); on API 34+ it
     * reflects [NotificationManager.canUseFullScreenIntent].
     */
    @Suppress("ReturnCount")
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager = context.getSystemService<NotificationManager>() ?: return false
        return notificationManager.canUseFullScreenIntent()
    }

    /**
     * True on API 34+ regardless of grant state — i.e. the special
     * access exists as a user-grantable toggle on this device. The
     * Settings tab uses this to decide whether to show the row at all;
     * the concept does not exist below API 34.
     */
    fun isApplicable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * True only when the access both exists (API 34+) AND is not
     * currently granted. Used to decide whether to surface the
     * first-launch prompt — there is nothing to ask for otherwise.
     */
    fun isRequestable(context: Context): Boolean = isApplicable() && !isGranted(context)

    /**
     * Open the system "Full screen notifications" special-access page
     * for this app. Falls back to the app's notification settings, then
     * the app details page, if the dedicated action is unavailable on
     * the device. Logs and returns on a fully unresolvable device rather
     * than throwing.
     */
    fun openSettings(context: Context) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val candidates =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                add(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                add(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Full-screen-intent settings not launchable: ${intent.action}", e)
            }
        }
    }

    private const val TAG = "BadaFsi"
}
