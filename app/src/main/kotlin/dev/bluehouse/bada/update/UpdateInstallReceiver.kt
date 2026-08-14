/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.bluehouse.bada.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateInstallReceiver` — the [PackageInstaller] status sink for the
 * self-update install started by [UpdateDownloadInstaller]. Manifest-
 * registered, NOT exported (only the app's own session PendingIntent
 * targets it).
 *
 * WHAT IT DOES
 * ------------
 * - `STATUS_PENDING_USER_ACTION` → launches the system install-confirm dialog
 *   (the only step the user sees). Android 14+ background-activity-launch
 *   restrictions can block a startActivity from a background receiver, so
 *   when the app is not foregrounded (or the launch throws) it instead posts
 *   a high-priority notification whose tap fires the same confirm intent —
 *   the user completes the install from the shade.
 * - `STATUS_SUCCESS` → brief "Bada updated" toast (may not render if the
 *   process is replaced by the new APK — best-effort).
 * - anything else → "Update install failed" toast + logs the reason.
 */
internal class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != UpdateDownloadInstaller.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> handlePendingUserAction(context, intent)

            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.update_install_success, Toast.LENGTH_SHORT).show()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Update install failed: status=$status message=$message")
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePendingUserAction(
        context: Context,
        intent: Intent,
    ) {
        val confirm = extractConfirmIntent(intent)
        if (confirm == null) {
            Log.e(TAG, "PENDING_USER_ACTION with no confirm intent")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 14+ blocks background activity launches from receivers, so
        // only try a direct launch when the app is visibly foregrounded;
        // otherwise (and when the launch itself throws) fall back to a
        // notification the user can act on from the shade.
        val launched =
            isAppInForeground() &&
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "Could not launch install confirm dialog directly", it) }
                    .isSuccess
        if (!launched) {
            postConfirmNotification(context, confirm)
        }
    }

    private fun isAppInForeground(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * High-priority "confirm the install" notification whose contentIntent
     * carries the PackageInstaller confirm dialog — tapping a notification is
     * always an allowed activity-launch path, so this sidesteps the BAL
     * restriction entirely.
     */
    private fun postConfirmNotification(
        context: Context,
        confirm: Intent,
    ) {
        UpdateNotificationChannel.ensure(context)
        val pending =
            PendingIntent.getActivity(
                context,
                REQ_CONFIRM,
                confirm,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, UpdateNotificationChannel.ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.update_confirm_notification_title))
                .setContentText(context.getString(R.string.update_confirm_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        private const val TAG = "UpdateInstallReceiver"
        private const val CONFIRM_NOTIFICATION_ID = 0x5544_434E // "UDCN"
        private const val REQ_CONFIRM = 4
    }
}
