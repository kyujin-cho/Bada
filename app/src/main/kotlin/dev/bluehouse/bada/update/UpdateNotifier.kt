/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.bluehouse.bada.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateNotifier` — builds and posts the **"Update available"** status-bar
 * notification for the automatic update check. Invoked by [UpdateCheckWorker]
 * when its periodic GitHub poll finds a release newer than the installed
 * `BuildConfig.VERSION_NAME`.
 *
 * WHAT THE USER SEES (the notification)
 * -------------------------------------
 * - Small icon: the platform download glyph (`stat_sys_download`).
 * - Title "Bada update available", text "Version <v> is ready to install".
 * - Tapping the BODY opens the in-app [CheckForUpdatesActivity].
 * - Action **"View on GitHub"** — always present — opens the release page
 *   (`html_url`) in a browser.
 * - Action **"Download & install"** — present ONLY when the release has an
 *   `.apk` asset attached. Launches [UpdateInstallActivity], which downloads
 *   that APK and fires the system installer for a drop-in update. When no APK
 *   is attached the notification offers GitHub-only.
 *
 * WHY IT EXISTS
 * -------------
 * Extends the pre-existing MANUAL update flow (overflow-menu → screen, #211)
 * with a proactive "a new version is out" alert.
 *
 * POST_NOTIFICATIONS (API 33+) is already requested by onboarding; if denied
 * the notification is silently dropped (degraded, by design).
 */
internal object UpdateNotifier {
    private const val NOTIFICATION_ID = 0x5544_4154 // "UDAT"

    /**
     * Post (or refresh) the "update available" notification.
     *
     * @param version     the newer release version, e.g. `20260701.01`.
     * @param releaseUrl  GitHub release page — the "View on GitHub" target.
     * @param apkAssetUrl direct `.apk` download URL, or `null` when the release
     *                    has no APK attached (then no "Download & install").
     */
    fun notifyUpdateAvailable(
        context: Context,
        version: String,
        releaseUrl: String,
        apkAssetUrl: String?,
    ) {
        val appContext = context.applicationContext
        UpdateNotificationChannel.ensure(appContext)

        val builder =
            NotificationCompat
                .Builder(appContext, UpdateNotificationChannel.ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(appContext.getString(R.string.update_notification_title))
                .setContentText(
                    appContext.getString(R.string.update_notification_text, version),
                ).setAutoCancel(true)
                .setContentIntent(openUpdateScreenIntent(appContext))
                .addAction(
                    0,
                    appContext.getString(R.string.update_notification_action_github),
                    viewOnGitHubIntent(appContext, releaseUrl),
                )

        // Adaptive: only offer a direct install when the release hosts the APK.
        if (apkAssetUrl != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.update_notification_action_download),
                downloadAndInstallIntent(appContext, apkAssetUrl, version, releaseUrl),
            )
        }

        // notify() no-ops if POST_NOTIFICATIONS is not granted on 33+.
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
    }

    /** Clear the alert once the user has acted on it from the trampoline. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    /** Body tap → the in-app "Check for updates" screen. */
    private fun openUpdateScreenIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, CheckForUpdatesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQ_OPEN_SCREEN, intent, immutableFlags())
    }

    /** "View on GitHub" → release page in a browser. */
    private fun viewOnGitHubIntent(
        context: Context,
        releaseUrl: String,
    ): PendingIntent {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_VIEW_GITHUB, intent, immutableFlags())
    }

    /**
     * "Download & install" → the trampoline that gates + starts the install.
     * The release URL rides along so debug builds (which cannot self-install
     * the release APK) can fall back to opening the release page.
     */
    private fun downloadAndInstallIntent(
        context: Context,
        apkAssetUrl: String,
        version: String,
        releaseUrl: String,
    ): PendingIntent {
        val intent =
            Intent(context, UpdateInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(UpdateInstallActivity.EXTRA_APK_URL, apkAssetUrl)
                .putExtra(UpdateInstallActivity.EXTRA_VERSION, version)
                .putExtra(UpdateInstallActivity.EXTRA_RELEASE_URL, releaseUrl)
        return PendingIntent.getActivity(context, REQ_DOWNLOAD, intent, immutableFlags())
    }

    private fun immutableFlags(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private const val REQ_OPEN_SCREEN = 1
    private const val REQ_VIEW_GITHUB = 2
    private const val REQ_DOWNLOAD = 3
}
