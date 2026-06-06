/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

/**
 * Lifecycle of a single "is there a newer release on GitHub?" check.
 *
 * Modelled as a sealed hierarchy so the UI can render every leaf
 * without re-querying any other source. [latestKnownVersion] /
 * [latestReleaseUrl] are remembered across cold starts via
 * [UpdatePreferences] so the red-dot indicator on the toolbar can
 * stay accurate even when the device is offline at the moment the
 * app starts.
 */
internal sealed interface UpdateState {
    /** No check has been performed yet in this process. */
    data object Idle : UpdateState

    /** A network call is in flight. */
    data object Checking : UpdateState

    /** Latest GitHub release is identical to (or older than) the installed version. */
    data class UpToDate(
        val installedVersion: String,
    ) : UpdateState

    /** Latest GitHub release is strictly newer than the installed version. */
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseUrl: String,
    ) : UpdateState

    /** The check failed (network, JSON, HTTP non-2xx, ...). */
    data class Error(
        val message: String,
    ) : UpdateState
}
