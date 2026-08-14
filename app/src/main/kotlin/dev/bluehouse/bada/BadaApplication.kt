/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.bluehouse.bada.consent.ConsentTrampolineActivity
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.update.UpdateCheckWorker
import dev.bluehouse.bada.update.UpdatePreferences
import java.util.concurrent.TimeUnit

/**
 * Application bootstrap that wires the `:app`-side activity classes
 * into the `:service-android` library at process start.
 *
 * The service module deliberately keeps no compile-time dependency on
 * `:app` — it would otherwise become a circular reference. Instead
 * the service exposes a pair of `@Volatile` `Class<*>` slots
 * ([ReceiverForegroundService.openAppTarget] and
 * [ReceiverForegroundService.consentTrampolineTarget]) that the host
 * application populates here, before any service `onCreate` runs.
 *
 * The wiring happens in `Application.onCreate`, which Android
 * guarantees to invoke before any other component (`Service`,
 * `BroadcastReceiver`, `Activity`) of the app, so by the time the
 * receiver service first tries to build a notification PendingIntent
 * the targets are already set.
 *
 * It also points [DiagnosticLog]'s on-disk sink at the app's external
 * files dir so BLE/discovery diagnostics persist into the bug report past
 * the 15-minute in-memory ring-buffer window (#201).
 */
class BadaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReceiverForegroundService.openAppTarget = MainActivity::class.java
        ReceiverForegroundService.consentTrampolineTarget = ConsentTrampolineActivity::class.java
        // Must match where BugReportCollector reads the log back from
        // (getExternalFilesDir(null)); a filesDir fallback would write logs
        // the collector never picks up.
        getExternalFilesDir(null)?.let { DiagnosticLog.configureFileSink(it) }

        applyAutoUpdateCheckPolicy(this)
    }

    companion object {
        /** How often the automatic update check runs, in hours. */
        private const val UPDATE_CHECK_INTERVAL_HOURS = 6L

        /**
         * Reconcile the 6-hourly automatic GitHub update check
         * ([UpdateCheckWorker]) with the user's Settings toggle
         * ([UpdatePreferences.autoCheckEnabled]).
         *
         * Enabled → enqueue a UNIQUE PeriodicWork so re-running onCreate
         * (every process start) never stacks duplicate jobs, with
         * `ExistingPeriodicWorkPolicy.UPDATE` so a future interval/constraint
         * change is picked up without losing the persisted schedule. The
         * CONNECTED network constraint means a run only fires when there is
         * connectivity to reach GitHub. WorkManager persists the schedule
         * across reboots, so the poll self-restarts on boot with no user
         * action.
         *
         * Disabled → cancel the unique work so the poll fully stops (the
         * worker's own preference check is only a belt-and-braces fallback).
         *
         * Called from [onCreate] and from the Settings toggle
         * ([dev.bluehouse.bada.ui.SettingsFragment]) whenever it flips.
         */
        fun applyAutoUpdateCheckPolicy(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            if (!UpdatePreferences.from(context).autoCheckEnabled()) {
                workManager.cancelUniqueWork(UpdateCheckWorker.UNIQUE_WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(UPDATE_CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()
            workManager.enqueueUniquePeriodicWork(
                UpdateCheckWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
