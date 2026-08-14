/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.bluehouse.bada.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * WHAT THIS IS
 * -----------
 * `UpdateDownloadInstaller` — the "download directly" half of the auto-update
 * feature. Given a GitHub release `.apk` `browser_download_url`, it STREAMS the
 * APK over HTTP straight into a [PackageInstaller] session (no temp file, no
 * FileProvider) and commits it, producing a drop-in in-place update.
 *
 * WHO CALLS IT
 * ------------
 * [UpdateInstallActivity] (the trampoline launched by the notification's
 * "Download & install" action) calls [enqueue] AFTER it has confirmed
 * `canRequestPackageInstalls()`.
 *
 * THREADING
 * ---------
 * [enqueue] hands the work to [UpdateInstallWorker] — an expedited unique
 * WorkManager job — so the multi-MB network read + session write survives the
 * trampoline activity finishing immediately and never touches the main
 * thread. The system install-confirm dialog is launched later by
 * [UpdateInstallReceiver] from the session's status callback.
 *
 * OBSERVABILITY
 * -------------
 * Posts an indeterminate **"Downloading update…"** notification while the
 * stream is in flight (as the worker's foreground notification where the
 * platform allows), posts a **"Download failed"** notification on a DISTINCT
 * id on any IO error (so cancelling the progress id never hides the
 * failure), and abandons the staged [PackageInstaller] session on every
 * failure path so repeated failures cannot leak sessions until
 * `createSession` starts throwing.
 */
internal object UpdateDownloadInstaller {
    const val PROGRESS_NOTIFICATION_ID = 0x5544_4C44 // "UDLD"
    const val FAILURE_NOTIFICATION_ID = 0x5544_4C46 // "UDLF"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Action used by the PackageInstaller status [PendingIntent]. */
    const val ACTION_INSTALL_STATUS = "dev.bluehouse.bada.update.INSTALL_STATUS"

    /**
     * Enqueue the download+install of [apkUrl] as an expedited unique
     * [UpdateInstallWorker]. [version] is used only for the progress
     * notification text. Safe to call only after the caller has verified
     * `canRequestPackageInstalls()`. `ExistingWorkPolicy.KEEP` means a
     * re-tap while a download is already running is a no-op.
     */
    fun enqueue(
        context: Context,
        apkUrl: String,
        version: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<UpdateInstallWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        UpdateInstallWorker.KEY_APK_URL to apkUrl,
                        UpdateInstallWorker.KEY_VERSION to version,
                    ),
                ).build()
        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(UpdateInstallWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Blocking download → [PackageInstaller] commit. Runs inside
     * [UpdateInstallWorker] on [kotlinx.coroutines.Dispatchers.IO]. Throws on
     * any failure; the staged session (if one was created) is abandoned
     * before the rethrow so failures never leak installer sessions.
     */
    fun streamInstall(
        appContext: Context,
        apkUrl: String,
    ) {
        val connection =
            (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub release assets 302-redirect to a CDN host (same https
                // scheme); HttpURLConnection follows same-protocol redirects.
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Bada-Android-UpdateInstaller")
                setRequestProperty("Accept", "application/octet-stream")
            }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("APK download failed: HTTP $responseCode")
            }
            val declaredLength = connection.contentLengthLong.takeIf { it > 0L } ?: -1L

            val installer = appContext.packageManager.packageInstaller
            val params =
                PackageInstaller
                    .SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        // This APK IS our own package — declaring it lets the
                        // installer treat the operation as an update of us.
                        setAppPackageName(appContext.packageName)
                    }
            val sessionId = installer.createSession(params)
            // Any failure between createSession and commit leaves a staged
            // session behind; abandon it or repeated failures accumulate
            // until createSession itself starts throwing.
            runCatching { commitSession(appContext, installer, sessionId, connection, declaredLength) }
                .onFailure { runCatching { installer.abandonSession(sessionId) } }
                .getOrThrow()
        } finally {
            connection.disconnect()
        }
    }

    private fun commitSession(
        appContext: Context,
        installer: PackageInstaller,
        sessionId: Int,
        connection: HttpURLConnection,
        declaredLength: Long,
    ) {
        installer.openSession(sessionId).use { session ->
            writeApkToSession(session, connection, declaredLength)
            val statusIntent =
                Intent(appContext, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .setPackage(appContext.packageName)
            // MUTABLE so the platform can fill in EXTRA_INTENT (the confirm
            // dialog) on API 31+.
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending =
                PendingIntent.getBroadcast(appContext, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    /**
     * Stream the connection's body into the installer session and fsync it.
     * Extracted from [commitSession] to keep that method's block nesting
     * shallow (detekt NestedBlockDepth) — the three nested `use {}` scopes
     * live here.
     */
    private fun writeApkToSession(
        session: PackageInstaller.Session,
        connection: HttpURLConnection,
        declaredLength: Long,
    ) {
        connection.inputStream.use { input ->
            session.openWrite("update", 0, declaredLength).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
    }

    /**
     * The progress notification wrapped as [ForegroundInfo] for the
     * expedited worker. `dataSync` is the closest FGS type for "stream a
     * file from the network" and is what the manifest declares for
     * WorkManager's SystemForegroundService.
     */
    fun progressForegroundInfo(
        appContext: Context,
        version: String,
    ): ForegroundInfo {
        UpdateNotificationChannel.ensure(appContext)
        val notification = progressNotification(appContext, version)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /** Plain-notification fallback when foregrounding the worker is refused. */
    fun showDownloadingNotification(
        appContext: Context,
        version: String,
    ) {
        UpdateNotificationChannel.ensure(appContext)
        NotificationManagerCompat
            .from(appContext)
            .notify(PROGRESS_NOTIFICATION_ID, progressNotification(appContext, version))
    }

    private fun progressNotification(
        appContext: Context,
        version: String,
    ): Notification =
        NotificationCompat
            .Builder(appContext, UpdateNotificationChannel.ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.update_downloading_title))
            .setContentText(
                appContext.getString(R.string.update_downloading_text, version),
            ).setOngoing(true)
            .setProgress(0, 0, true) // indeterminate horizontal bar
            .build()

    /**
     * Posted on [FAILURE_NOTIFICATION_ID] — deliberately DISTINCT from
     * [PROGRESS_NOTIFICATION_ID], which the worker cancels on completion.
     * Posting both on the same id made every failure invisible.
     */
    fun showFailedNotification(appContext: Context) {
        UpdateNotificationChannel.ensure(appContext)
        val notification =
            NotificationCompat
                .Builder(appContext, UpdateNotificationChannel.ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(appContext.getString(R.string.update_download_failed_title))
                .setContentText(appContext.getString(R.string.update_download_failed_text))
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(appContext).notify(FAILURE_NOTIFICATION_ID, notification)
    }
}
