/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.helper

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.bluehouse.bada.R

/**
 * WHAT THIS IS
 * ------------
 * `HelperInstallReceiver` — the manifest-private [PackageInstaller] status
 * state machine for Settings > Radio Helper > **"Install Radio Helper"**.
 *
 * STATE, OWNERSHIP, AND ORDERING
 * ------------------------------
 * [HelperInstaller] commits an explicit, mutable status PendingIntent carrying
 * [HelperInstaller.ACTION_INSTALL_STATUS]. This receiver rejects other actions:
 * `STATUS_PENDING_USER_ACTION` keeps the duplicate-install latch set and routes
 * the supplied system confirmation Intent; terminal success/failure clears the
 * latch and surfaces a toast. It never reads the APK or controls radios.
 * PackageInstaller owns package/signature checks and the system confirmation;
 * the installed helper owns later Wi-Fi/Bluetooth share-session behavior.
 *
 * BACKGROUND, SECURITY, AND FAILURE BOUNDARIES
 * --------------------------------------------
 * Foreground Bada attempts the confirmation activity directly. Background Bada
 * or a failed direct launch posts the high-importance **"Finish installing
 * Radio Helper"** notification, whose tap launches that same Intent. The
 * notification is best-effort when notification permission is denied; the
 * PackageInstaller session remains system-owned. A missing confirmation Intent
 * is treated as terminal local failure. `exported=false`, the explicit component,
 * and action check prevent another app from driving this state machine.
 *
 * TEST STATUS
 * -----------
 * Complete and cancel installation with Bada foregrounded and backgrounded;
 * test denied notifications, malformed pending confirmation, and success/fail
 * callbacks, then verify the Settings status after returning. Source/static
 * checks are proven; compilation, callback delivery, notification appearance,
 * and system-installer UI remain device-UNVERIFIED.
 */
internal class HelperInstallReceiver : BroadcastReceiver() {
    /**
     * PackageInstaller callback dispatcher. Pending confirmation is non-terminal;
     * every other status is terminal for this app's process-local latch.
     */
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != HelperInstaller.ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> handlePendingUserAction(context, intent)
            PackageInstaller.STATUS_SUCCESS -> {
                HelperInstaller.markInstallFinished()
                Toast
                    .makeText(
                        context,
                        R.string.settings_radio_helper_install_success,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            else -> {
                HelperInstaller.markInstallFinished()
                Toast
                    .makeText(
                        context,
                        R.string.settings_radio_helper_install_failed,
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /** Route system confirmation directly only while visible, otherwise through the shade. */
    private fun handlePendingUserAction(
        context: Context,
        intent: Intent,
    ) {
        val confirm = extractConfirmIntent(intent)
        if (confirm == null) {
            HelperInstaller.markInstallFinished()
            Toast
                .makeText(
                    context,
                    R.string.settings_radio_helper_install_failed,
                    Toast.LENGTH_LONG,
                ).show()
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = isAppInForeground() && runCatching { context.startActivity(confirm) }.isSuccess
        if (!launched) postConfirmNotification(context, confirm)
    }

    private fun isAppInForeground(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * Best-effort background-launch fallback. The full-width notification row
     * shows the download glyph, title "Finish installing Radio Helper", and
     * explanatory text; tapping it opens the PackageInstaller confirmation.
     */
    private fun postConfirmNotification(
        context: Context,
        confirm: Intent,
    ) {
        ensureNotificationChannel(context)
        val pending =
            PendingIntent.getActivity(
                context,
                REQUEST_CONFIRM,
                confirm,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.settings_radio_helper_confirm_title))
                .setContentText(context.getString(R.string.settings_radio_helper_confirm_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION_ID, notification)
    }

    /**
     * Create the dedicated high-importance "Radio Helper installation"
     * channel once on API 26+. Keeping it separate from app-update alerts makes
     * the fallback identifiable and independently configurable in Android
     * notification settings; pre-26 devices use builder priority only.
     */
    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.settings_radio_helper_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.settings_radio_helper_channel_description)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        private const val NOTIFICATION_CHANNEL_ID = "radio_helper_install"
        private const val CONFIRM_NOTIFICATION_ID = 0x5248_434E // "RHCN"
        private const val REQUEST_CONFIRM = 6
    }
}
