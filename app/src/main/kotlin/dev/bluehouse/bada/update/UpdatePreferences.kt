/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the last successful "latest release" snapshot from GitHub
 * so the red-dot indicator on the toolbar overflow can stay accurate
 * across cold starts even when the device is offline.
 *
 * Stores:
 *  - the most recent release version + release-page URL (for the red-dot
 *    badge; comparison against `BuildConfig.VERSION_NAME` happens at read
 *    time so an app upgrade auto-clears the badge);
 *  - the version we LAST posted an "update available" notification for, so
 *    the periodic background [UpdateCheckWorker] does not re-notify for the
 *    same release every run (only when a strictly newer one appears);
 *  - whether the automatic background check is enabled (default `true`).
 */
internal class UpdatePreferences(
    private val prefs: SharedPreferences,
) {
    fun latestKnownVersion(): String? = prefs.getString(KEY_LATEST_VERSION, null)

    fun latestKnownReleaseUrl(): String? = prefs.getString(KEY_LATEST_URL, null)

    fun saveLatestRelease(
        version: String,
        releaseUrl: String,
    ) {
        prefs
            .edit()
            .putString(KEY_LATEST_VERSION, version)
            .putString(KEY_LATEST_URL, releaseUrl)
            .apply()
    }

    /**
     * The release version the background worker most recently raised a
     * notification for, or `null` if it never has. De-duplicates the
     * "update available" alert across periodic polls.
     */
    fun lastNotifiedVersion(): String? = prefs.getString(KEY_NOTIFIED_VERSION, null)

    fun saveNotifiedVersion(version: String) {
        prefs.edit().putString(KEY_NOTIFIED_VERSION, version).apply()
    }

    /**
     * Whether the automatic background GitHub update check runs. Defaults to
     * `true`; the Settings tab's "Automatic update check" toggle flips
     * [setAutoCheckEnabled], which cancels/re-enqueues the periodic worker
     * (via `BadaApplication.applyAutoUpdateCheckPolicy`) — the worker's own
     * check of this flag is only a fallback.
     */
    fun autoCheckEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true)

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.update"
        private const val KEY_LATEST_VERSION = "latest_release_version"
        private const val KEY_LATEST_URL = "latest_release_url"
        private const val KEY_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"

        fun from(context: Context): UpdatePreferences =
            UpdatePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
