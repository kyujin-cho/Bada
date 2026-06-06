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
 * Only a single tuple is stored: the most recent version + the URL
 * for the release page. Comparison against `BuildConfig.VERSION_NAME`
 * happens at read time so an app upgrade automatically clears the
 * "update available" badge without needing an explicit clear.
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

    internal companion object {
        private const val PREFS_NAME = "bada.update"
        private const val KEY_LATEST_VERSION = "latest_release_version"
        private const val KEY_LATEST_URL = "latest_release_url"

        fun from(context: Context): UpdatePreferences =
            UpdatePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
