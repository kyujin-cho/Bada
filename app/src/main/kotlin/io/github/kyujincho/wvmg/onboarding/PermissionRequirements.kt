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
 * Single source of truth for the runtime permissions onboarding asks for.
 *
 * Compile-time permissions (`CHANGE_WIFI_MULTICAST_STATE`, `INTERNET`,
 * `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, legacy `BLUETOOTH` /
 * `BLUETOOTH_ADMIN` on API ≤ 30) are declared in the manifest and never
 * appear here — they are granted at install time.
 *
 * Runtime permissions are gated by API level:
 *   * `NEARBY_WIFI_DEVICES` and `POST_NOTIFICATIONS` only exist on API 33+.
 *   * `BLUETOOTH_ADVERTISE` and `BLUETOOTH_SCAN` only exist on API 31+ — on
 *     API ≤ 30 the legacy install-time `BLUETOOTH` / `BLUETOOTH_ADMIN`
 *     permissions cover the same capabilities, so we skip the runtime
 *     prompt entirely on those devices.
 *
 * Each requirement is classified as **mandatory** or **optional**:
 *   * Mandatory denials gate the app's primary discovery path.
 *     Currently `NEARBY_WIFI_DEVICES` is the only mandatory permission —
 *     without it Phase 1 cannot run mDNS discovery at all.
 *   * Optional denials let the app run in a degraded mode. Today that
 *     means missing notifications (silent transfers) or missing BLE
 *     auto-discovery (mDNS-only fallback per #31's acceptance criteria).
 *
 * Onboarding is allowed to complete with optional-only denials; the
 * service-start gate re-checks at runtime so the user can grant later
 * via system Settings without revisiting onboarding.
 */
internal object PermissionRequirements {
    /**
     * Describes a single runtime permission for the onboarding UI: the
     * Android permission identifier plus user-facing copy explaining
     * **why** the app needs it, and whether denying it blocks the app.
     */
    internal data class Requirement(
        val permission: String,
        @StringRes val titleRes: Int,
        @StringRes val rationaleRes: Int,
        @StringRes val grantedRes: Int,
        @StringRes val deniedRes: Int,
        /**
         * If true, denying this permission still lets onboarding finish
         * (the app falls back to a degraded mode). If false, onboarding
         * keeps the user on this screen until the permission is granted
         * or the user explicitly chooses "continue without".
         */
        val optional: Boolean,
    )

    /**
     * The set of runtime permissions that need to be requested on the
     * **current** device. Empty on devices with API < 31 that don't run
     * any runtime-permissioned features (currently impossible because
     * minSdk is 24 and we have no runtime permissions for API 24–30).
     */
    internal fun requirementsFor(): List<Requirement> {
        val result = mutableListOf<Requirement>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result += blePermissions()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result += tiramisuPermissions()
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun blePermissions(): List<Requirement> =
        listOf(
            Requirement(
                permission = Manifest.permission.BLUETOOTH_ADVERTISE,
                titleRes = R.string.permission_bluetooth_advertise_title,
                rationaleRes = R.string.permission_bluetooth_advertise_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_optional_denied_ble,
                optional = true,
            ),
            Requirement(
                permission = Manifest.permission.BLUETOOTH_SCAN,
                titleRes = R.string.permission_bluetooth_scan_title,
                rationaleRes = R.string.permission_bluetooth_scan_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_optional_denied_ble,
                optional = true,
            ),
        )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tiramisuPermissions(): List<Requirement> =
        listOf(
            Requirement(
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                titleRes = R.string.permission_nearby_wifi_title,
                rationaleRes = R.string.permission_nearby_wifi_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_denied,
                optional = false,
            ),
            Requirement(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                titleRes = R.string.permission_notifications_title,
                rationaleRes = R.string.permission_notifications_rationale,
                grantedRes = R.string.permission_status_granted,
                deniedRes = R.string.permission_status_optional_denied,
                optional = true,
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
     * True when every runtime permission onboarding asks for has been
     * granted (or is not applicable to the current API level).
     */
    internal fun allGranted(context: Context): Boolean = missingPermissions(context).isEmpty()

    /**
     * True when the only outstanding permissions are non-blocking ones
     * (`POST_NOTIFICATIONS` and the BLE permissions). The service can
     * run in degraded mode in that case — see issue #26 / #31 acceptance
     * criteria. Returns false when no permissions are missing (use
     * [allGranted] to detect that case).
     */
    internal fun onlyOptionalMissing(context: Context): Boolean {
        val missing = missingPermissions(context).toSet()
        if (missing.isEmpty()) return false
        val optionalSet =
            requirementsFor()
                .filter(Requirement::optional)
                .map(Requirement::permission)
                .toSet()
        return missing.all(optionalSet::contains)
    }
}
