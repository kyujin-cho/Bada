/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dev.bluehouse.bada.R

/**
 * The single `app_update` notification channel shared by every auto-update
 * surface: the "update available" alert ([UpdateNotifier]), the download
 * progress / failure notifications ([UpdateDownloadInstaller]), and the
 * install-confirm fallback ([UpdateInstallReceiver]).
 */
internal object UpdateNotificationChannel {
    const val ID = "app_update"

    /** Idempotent create — safe to call before every notify(). */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ID,
                    context.getString(R.string.update_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.update_notification_channel_desc) },
            )
        }
    }
}
