/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import android.os.Build
import androidx.annotation.RequiresApi

internal data class SocDiagnostics(
    val manufacturer: String,
    val model: String,
)

internal object BugReportSocDiagnostics {
    internal const val UNAVAILABLE_PRE_API31: String = "unavailable_pre_api31"

    fun read(): SocDiagnostics = resolveForSdkInt(Build.VERSION.SDK_INT) { readApi31Values() }

    internal fun resolveForSdkInt(
        sdkInt: Int,
        readApi31Values: () -> SocDiagnostics,
    ): SocDiagnostics =
        if (sdkInt >= Build.VERSION_CODES.S) {
            readApi31Values()
        } else {
            fallback()
        }

    private fun fallback(): SocDiagnostics = SocDiagnostics(UNAVAILABLE_PRE_API31, UNAVAILABLE_PRE_API31)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readApi31Values(): SocDiagnostics =
        SocDiagnostics(
            manufacturer = Build.SOC_MANUFACTURER,
            model = Build.SOC_MODEL,
        )
}
