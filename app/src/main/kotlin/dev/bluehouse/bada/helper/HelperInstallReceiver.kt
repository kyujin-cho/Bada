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
 * PackageInstaller status receiver for the Settings-managed helper install.
 * Pending confirmation opens directly while Bada is foregrounded; otherwise a
 * high-priority notification provides a user-initiated launch path that remains
 * valid under Android 14+ background-activity restrictions. The receiver is
 * manifest-private and accepts only [HelperInstaller.ACTION_INSTALL_STATUS]. It
 * never reads the APK or controls radios; PackageInstaller owns identity/signing
 * enforcement, and the helper owns Wi-Fi/Bluetooth after installation.
 *
 * Device test: complete or cancel Settings' "Install Radio Helper" action both
 * while Bada remains foregrounded and after backgrounding it. This source is
 * currently device/UI-unverified because Android compilation was not authorized.
 */
internal class HelperInstallReceiver : BroadcastReceiver() {
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
     * channel. Keeping this separate from app-update alerts makes the fallback
     * confirmation accurately identifiable in Android notification settings.
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
