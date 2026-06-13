/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.transfer

import android.content.Context
import android.text.format.Formatter
import dev.bluehouse.bada.R
import dev.bluehouse.bada.protocol.connection.TransferProgress
import dev.bluehouse.bada.protocol.medium.Medium

internal object TransferExpertDetailsFormatter {
    fun format(
        context: Context,
        progress: TransferProgress,
        activeMedium: Medium,
        wifiFrequencyMhz: Int?,
    ): String {
        val speed =
            if (progress.bytesPerSecond > 0L) {
                context.getString(
                    R.string.transfer_expert_speed_value,
                    Formatter.formatShortFileSize(context, progress.bytesPerSecond),
                )
            } else {
                context.getString(R.string.transfer_expert_calculating)
            }
        val eta =
            progress.etaSeconds?.let { formatDuration(context, it) }
                ?: context.getString(R.string.transfer_expert_calculating)
        val rows =
            mutableListOf(
                context.getString(R.string.transfer_expert_speed, speed),
                context.getString(R.string.transfer_expert_eta, eta),
                context.getString(R.string.transfer_expert_medium, activeMedium.label(context)),
            )
        if (activeMedium == Medium.WIFI_DIRECT) {
            rows +=
                context.getString(
                    R.string.transfer_expert_wifi_band,
                    wifiBandLabel(context, wifiFrequencyMhz),
                )
        }
        return rows.joinToString(separator = "\n")
    }

    @Suppress("MagicNumber")
    private fun wifiBandLabel(
        context: Context,
        frequencyMhz: Int?,
    ): String =
        when (frequencyMhz) {
            null -> context.getString(R.string.transfer_expert_unknown)
            in 2_400..2_500 -> context.getString(R.string.transfer_expert_wifi_band_24)
            in 4_900..5_900 -> context.getString(R.string.transfer_expert_wifi_band_5)
            else -> context.getString(R.string.transfer_expert_unknown)
        }

    @Suppress("MagicNumber")
    private fun formatDuration(
        context: Context,
        etaSeconds: Long,
    ): String =
        when {
            etaSeconds < 1L -> context.getString(R.string.send_status_duration_few_seconds)
            etaSeconds <= SECONDS_THRESHOLD ->
                context.getString(R.string.send_status_duration_seconds, etaSeconds)
            etaSeconds < HOUR_IN_SECONDS -> {
                val minutes = (etaSeconds + 30L) / 60L
                context.getString(R.string.send_status_duration_about_minutes, minutes)
            }
            else -> {
                val hours = (etaSeconds + 1800L) / HOUR_IN_SECONDS
                context.getString(R.string.send_status_duration_about_hours, hours)
            }
        }

    private fun Medium.label(context: Context): String =
        when (this) {
            Medium.BLUETOOTH -> context.getString(R.string.transfer_expert_medium_bluetooth)
            Medium.WIFI_HOTSPOT -> context.getString(R.string.transfer_expert_medium_wifi_hotspot)
            Medium.BLE -> context.getString(R.string.transfer_expert_medium_ble)
            Medium.WIFI_LAN -> context.getString(R.string.transfer_expert_medium_wifi_lan)
            Medium.WIFI_AWARE -> context.getString(R.string.transfer_expert_medium_wifi_aware)
            Medium.WIFI_DIRECT -> context.getString(R.string.transfer_expert_medium_wifi_direct)
            Medium.WEB_RTC -> context.getString(R.string.transfer_expert_medium_web_rtc)
            Medium.BLE_L2CAP -> context.getString(R.string.transfer_expert_medium_ble_l2cap)
        }

    private const val SECONDS_THRESHOLD: Long = 90L
    private const val HOUR_IN_SECONDS: Long = 3_600L
}
