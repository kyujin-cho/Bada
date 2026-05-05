/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import dev.bluehouse.libredrop.discovery.DiscoveryDiagnostics

/**
 * Narrow bug-report-facing bridge into receiver runtime state.
 *
 * `:app` owns the UI flow that packages a user-visible bug report, but the
 * high-signal receiver diagnostics live inside `:service-android`. This object
 * exposes read-only snapshots without opening the rest of the service internals
 * to the app module.
 */
public object ReceiverBugReportDiagnostics {
    public fun discoverySnapshot(): DiscoveryDiagnostics? = ActiveDiscoveryHolder.current()?.snapshot()

    public fun isReceiverServiceLikelyRunning(): Boolean =
        ActiveDiscoveryHolder.current() != null || ActiveBleScannerHolder.current() != null
}
