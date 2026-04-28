/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import android.content.Context
import io.github.kyujincho.wvmg.R
import io.github.kyujincho.wvmg.protocol.connection.TransferProgress

/**
 * Renders the sender-side progress string surfaced in the status
 * panel of [SendActivity] (#46).
 *
 * The output mirrors the receiver foreground notification's body:
 * "12 MB of 100 MB · 4.2 MB/s · 30 seconds remaining". Rate / ETA
 * segments are dropped while warming up so the user does not see
 * "0 B/s, unknown ETA". The duration phrasing ("30 seconds remaining"
 * rather than "30s remaining") matches the issue's accessibility
 * acceptance criterion.
 *
 * Pulled out of [SendActivity] so the formatting can be unit-tested
 * in isolation if needed; the activity body just calls
 * [SendProgressFormatter.format].
 */
internal object SendProgressFormatter {
    /**
     * Build the status-panel subtitle for [progress]. Always returns
     * a non-blank string — at minimum a "0 B of 0 B" segment when no
     * bytes have moved yet.
     */
    fun format(
        context: Context,
        progress: TransferProgress,
    ): String {
        val sizeSegment =
            context.getString(
                R.string.send_status_progress,
                PayloadSummary.formatBytes(progress.bytesTransferred),
                PayloadSummary.formatBytes(progress.totalSize),
            )
        val rateSegment =
            if (progress.bytesPerSecond > 0L) {
                context.getString(
                    R.string.send_status_rate,
                    PayloadSummary.formatBytes(progress.bytesPerSecond),
                )
            } else {
                null
            }
        val etaSegment =
            progress.etaSeconds?.let { eta ->
                context.getString(
                    R.string.send_status_eta,
                    formatDuration(context, eta),
                )
            }
        return listOfNotNull(sizeSegment, rateSegment, etaSegment).joinToString(separator = " · ")
    }

    /**
     * Format an ETA in seconds using the same buckets as the
     * receiver-side [io.github.kyujincho.wvmg.service.receiver.progress.TransferProgressNotificationContent]
     * — "<= 90 s" gets spelled-out seconds for accessibility,
     * larger values round to the nearest minute or hour and get an
     * "about" prefix to signal imprecision.
     */
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

    private const val SECONDS_THRESHOLD: Long = 90L
    private const val HOUR_IN_SECONDS: Long = 3600L
}
