/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.content.Context
import dev.bluehouse.bada.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the "is there a newer release?" check.
 *
 * Process-singleton (object) so the toolbar overflow's red-dot
 * indicator on every MainActivity instance reads the same state
 * and a refresh from the foreground triggers the dot to clear on
 * every observing surface at once. Survives configuration changes
 * because it lives on the process, not on the activity.
 *
 * State derivation order on cold start:
 *   1. Seed [state] from [UpdatePreferences] so the dot is correct
 *      from the very first frame even without a network call.
 *   2. Kick a background [refresh] which updates [state] to
 *      [UpdateState.UpToDate] / [UpdateState.UpdateAvailable] /
 *      [UpdateState.Error] once the GitHub call lands.
 *
 * Version comparison: `versionName` is `YYYYMMDD.NN` everywhere in
 * the project, so a plain lexicographic comparison is monotonic with
 * release order — no Semver parsing required.
 */
internal object UpdateRepository {
    private val _state: MutableStateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    /**
     * Initialise [state] from the cached snapshot — call once early
     * (e.g. from `MainActivity.onCreate`) so the toolbar's red dot is
     * rendered correctly before the network check completes. Safe to
     * call repeatedly: the second call is a no-op when [state] has
     * already advanced past [UpdateState.Idle].
     */
    fun seedFromCache(context: Context) {
        if (_state.value != UpdateState.Idle) return
        val prefs = UpdatePreferences.from(context)
        val cachedVersion = prefs.latestKnownVersion()
        val cachedUrl = prefs.latestKnownReleaseUrl()
        if (cachedVersion != null && cachedUrl != null) {
            _state.value =
                if (isNewer(cachedVersion, BuildConfig.VERSION_NAME)) {
                    UpdateState.UpdateAvailable(cachedVersion, cachedUrl)
                } else {
                    UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                }
        }
    }

    /**
     * Fire a one-shot GitHub `releases/latest` query and update [state]
     * with the result. Concurrent callers are coalesced through
     * [refreshMutex] so the network call only happens once at a time.
     */
    suspend fun refresh(context: Context) {
        refreshMutex.withLock {
            _state.value = UpdateState.Checking
            val result = UpdateChecker.fetchLatestRelease()
            _state.value =
                result.fold(
                    onSuccess = { release ->
                        UpdatePreferences
                            .from(context)
                            .saveLatestRelease(release.version, release.releaseUrl)
                        if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                            UpdateState.UpdateAvailable(release.version, release.releaseUrl)
                        } else {
                            UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                        }
                    },
                    onFailure = { throwable ->
                        UpdateState.Error(throwable.message ?: throwable.javaClass.simpleName)
                    },
                )
        }
    }

    /**
     * True iff the [_state] currently surfaces an
     * [UpdateState.UpdateAvailable] — used by the toolbar overflow
     * indicator to decide whether to paint a red dot.
     */
    fun hasPendingUpdate(): Boolean = _state.value is UpdateState.UpdateAvailable

    /**
     * Strict-greater string comparison. Works for the project's fixed
     * `YYYYMMDD.NN` versionName scheme because every field is
     * zero-padded to a fixed width.
     */
    private fun isNewer(
        candidate: String,
        installed: String,
    ): Boolean = candidate > installed
}
