/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WHAT THIS IS
 * -----------
 * `UpdateInstallWorker` — the WorkManager job that downloads a release APK
 * and stages it into a [android.content.pm.PackageInstaller] session
 * ([UpdateDownloadInstaller.streamInstall]). Enqueued (expedited, unique) by
 * [UpdateDownloadInstaller.enqueue] when the user taps the notification's
 * "Download & install" action via [UpdateInstallActivity].
 *
 * WHY A WORKER, NOT A THREAD
 * --------------------------
 * The trampoline activity finishes immediately, so a bare `Thread` would
 * leave a multi-MB download running in a process the OS is free to freeze or
 * kill — with the sticky "Downloading…" notification orphaned forever.
 * WorkManager owns the process lifetime for the duration of the job:
 * expedited work runs as a foreground service pre-API-31 (via
 * [getForegroundInfo]) and as an expedited job on API 31+.
 *
 * CLEANUP GUARANTEES
 * ------------------
 * The progress notification is cancelled in a `finally` block, so it is
 * removed on success, failure, AND worker cancellation. Failures surface on
 * a distinct notification ID so the failure alert is never clobbered.
 */
internal class UpdateInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val apkUrl = inputData.getString(KEY_APK_URL)
        if (apkUrl.isNullOrBlank()) return Result.failure()
        val version = inputData.getString(KEY_VERSION).orEmpty()

        // Expedited work MUST run foregrounded pre-API-31. On API 31+
        // setForeground can be refused while the app is backgrounded
        // (ForegroundServiceStartNotAllowedException) — fall back to a plain
        // progress notification so the download stays observable either way.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { UpdateDownloadInstaller.showDownloadingNotification(applicationContext, version) }

        try {
            val outcome =
                runCatching {
                    withContext(Dispatchers.IO) {
                        UpdateDownloadInstaller.streamInstall(applicationContext, apkUrl)
                    }
                }
            return when (val error = outcome.exceptionOrNull()) {
                null -> Result.success()
                is CancellationException -> throw error
                else -> {
                    Log.e(TAG, "Update download/install failed", error)
                    UpdateDownloadInstaller.showFailedNotification(applicationContext)
                    Result.failure()
                }
            }
        } finally {
            // Runs on success, failure, and cancellation alike: the ongoing
            // progress notification must never outlive the download.
            NotificationManagerCompat
                .from(applicationContext)
                .cancel(UpdateDownloadInstaller.PROGRESS_NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        UpdateDownloadInstaller.progressForegroundInfo(
            applicationContext,
            inputData.getString(KEY_VERSION).orEmpty(),
        )

    internal companion object {
        private const val TAG = "UpdateInstallWorker"

        /** Unique name so re-taps while a download runs do not stack jobs. */
        const val UNIQUE_WORK_NAME = "bada-update-install"
        const val KEY_APK_URL = "apk_url"
        const val KEY_VERSION = "version"
    }
}
