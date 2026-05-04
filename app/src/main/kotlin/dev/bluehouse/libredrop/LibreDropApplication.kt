/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop

import android.app.Application
import dev.bluehouse.libredrop.consent.ConsentTrampolineActivity
import dev.bluehouse.libredrop.service.receiver.ReceiverForegroundService

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
 */
class LibreDropApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReceiverForegroundService.openAppTarget = MainActivity::class.java
        ReceiverForegroundService.consentTrampolineTarget = ConsentTrampolineActivity::class.java
    }
}
