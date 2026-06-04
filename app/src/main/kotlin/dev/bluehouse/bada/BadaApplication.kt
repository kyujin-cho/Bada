/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada

import android.app.Application
import dev.bluehouse.bada.consent.ConsentTrampolineActivity
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService

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
    }
}
