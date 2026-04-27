/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.R

/**
 * Single source of truth for the runtime permissions Phase 1 needs.
 *
 * Compile-time permissions (`CHANGE_WIFI_MULTICAST_STATE`, `INTERNET`,
 * `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) are declared in the
 * manifest and never appear here — they are granted at install time.
 *
 * Runtime permissions are gated by API level:
 *   * `NEARBY_WIFI_DEVICES` and `POST_NOTIFICATIONS` only exist on API 33+.
 *     On older devices they are simply absent from this list and the
 *     onboarding screen reports them as automatically granted, since
 *     pre-33 platforms either rely on different permissions (legacy
 *     Wi-Fi discovery does not need NEARBY_WIFI_DEVICES) or post
 *     notifications without a prompt.
 */
internal object PermissionRequirements {
    /**
     * Describes a single runtime permission for the onboarding UI: the
     * Android permission identifier plus user-facing copy explaining
     * **why** the app needs it.
     */
    internal data class Requirement(
        val permission: String,
        @StringRes val titleRes: Int,
        @StringRes val rationaleRes: Int,
        @StringRes val grantedRes: Int,
        @StringRes val deniedRes: Int,
    )

    /**
     * The set of runtime permissions that need to be requested on the
     * **current** device. Empty on API < 33 — Phase 1 has no runtime
     * permissions on those devices.
     */
    internal fun requirementsFor(): List<Requirement> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tiramisuRequirements()
        } else {
            emptyList()
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tiramisuRequirements(): List<Requirement> =
        listOf(
            Requirement(
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                titleRes = R.string.permission_nearby_wifi_title,
                rationaleRes = R.string.permission_nearby_wifi_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_denied,
            ),
            Requirement(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                titleRes = R.string.permission_notifications_title,
                rationaleRes = R.string.permission_notifications_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_optional_denied,
            ),
        )

    /**
     * Returns the permissions from [requirementsFor] that are not yet
     * granted on this device. Used by [PermissionsOnboardingActivity] to
     * decide what to request, and by `MainActivity` to gate whether
     * onboarding needs to be shown at all.
     */
    internal fun missingPermissions(context: Context): List<String> =
        requirementsFor()
            .map(Requirement::permission)
            .filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }

    /**
     * True when every runtime permission Phase 1 asks for has been
     * granted (or is not applicable to the current API level).
     */
    internal fun allGranted(context: Context): Boolean = missingPermissions(context).isEmpty()

    /**
     * True when the only outstanding permissions are non-blocking ones
     * (currently just `POST_NOTIFICATIONS`). The service can run in
     * degraded mode in that case — see issue #26 acceptance criteria.
     */
    internal fun onlyOptionalMissing(context: Context): Boolean {
        val missing = missingPermissions(context)
        if (missing.isEmpty()) return false
        return missing.all { it == OPTIONAL_POST_NOTIFICATIONS }
    }

    /**
     * `Manifest.permission.POST_NOTIFICATIONS` exists only on API 33+.
     * Holding the literal string as a constant keeps the comparison in
     * [onlyOptionalMissing] safe to call on every API level.
     */
    private const val OPTIONAL_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
}
