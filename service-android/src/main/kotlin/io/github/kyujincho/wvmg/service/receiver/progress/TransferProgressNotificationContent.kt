/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver.progress

import io.github.kyujincho.wvmg.protocol.connection.TransferProgress

/**
 * Pure-JVM helper that renders the textual content of an in-flight
 * transfer notification (#46).
 *
 * Pulled out of the notification builder so the templating logic can
 * be exercised on plain JVM, without standing up a `Resources`
 * instance. The Android-coupled part (channel, builder, post/dismiss)
 * lives in [TransferProgressNotification].
 *
 * The content has three slots:
 *
 *  - [title]: short, fits the lock-screen title row. Shows the sender
 *    device name when known, falls back to a generic copy otherwise.
 *  - [body]: collapsed-shade body. "12 MB of 100 MB · 4.2 MB/s · 30s
 *    remaining" — the rate and ETA segments are dropped while warming
 *    up so the user does not see "0 B/s, unknown ETA".
 *  - [bigText]: expanded-shade body, full-width. Same content as
 *    [body] today; surfaced separately so future work can add a file
 *    list or per-item progress without breaking the collapsed view.
 *
 * Issue #46 acceptance criterion: "Accessibility-friendly text
 * (e.g. '12 MB of 100 MB, 30 seconds remaining')." We render the
 * spelled-out form (`30 seconds remaining`, not `30s`) when the
 * estimated time is short enough to feel concrete, and switch to
 * higher-level units (`about 2 minutes remaining`, `about 3 hours
 * remaining`) for longer estimates.
 *
 * @property title Short title (lock-screen / collapsed view).
 * @property body Single-line summary including bytes, rate, ETA.
 * @property bigText Expanded-shade body. Always set so the system can
 *   render BigTextStyle without a null guard.
 * @property progressPercent Integer percentage in `[0, 100]`. Drives
 *   the notification progress bar; computed from
 *   [TransferProgress.fraction] and rounded to the nearest integer.
 * @property progressIsDeterminate `false` while the transfer has not
 *   announced a positive total size (very rare; the receiver
 *   foreground notification falls back to an indeterminate spinner in
 *   that case).
 */
public data class TransferProgressNotificationContent(
    val title: String,
    val body: String,
    val bigText: String,
    val progressPercent: Int,
    val progressIsDeterminate: Boolean,
) {
    public companion object {
        /**
         * Build content from the supplied [progress] snapshot.
         *
         * @param resolver String resolver — production wires this to
         *   `Context.getString`; tests use a recording fake so they
         *   never need a real `Resources`.
         * @param sourceDeviceName Sender device name (from the
         *   consent metadata) or `null` when unknown.
         * @param progress Snapshot from
         *   [io.github.kyujincho.wvmg.protocol.connection.InboundConnectionState.Receiving].
         */
        @JvmStatic
        public fun from(
            resolver: TextResolver,
            sourceDeviceName: String?,
            progress: TransferProgress,
        ): TransferProgressNotificationContent {
            val title =
                if (sourceDeviceName.isNullOrBlank()) {
                    resolver.getString(StringKey.TITLE_UNKNOWN_SENDER)
                } else {
                    resolver.getString(StringKey.TITLE_WITH_NAME, sourceDeviceName)
                }

            val transferred = formatBytes(progress.bytesTransferred)
            val total = formatBytes(progress.totalSize)
            val sizeSegment = resolver.getString(StringKey.BODY_SIZE, transferred, total)

            val rateSegment =
                if (progress.bytesPerSecond > 0L) {
                    resolver.getString(
                        StringKey.BODY_RATE,
                        formatBytes(progress.bytesPerSecond),
                    )
                } else {
                    null
                }

            val etaSegment =
                progress.etaSeconds?.let { eta ->
                    resolver.getString(StringKey.BODY_ETA, formatDuration(resolver, eta))
                }

            val body =
                listOfNotNull(sizeSegment, rateSegment, etaSegment).joinToString(separator = " · ")
            val bigText = body

            val percent =
                if (progress.totalSize <= 0L) {
                    0
                } else {
                    (progress.fraction * PERCENT_FULL).toInt().coerceIn(0, PERCENT_FULL)
                }
            val determinate = progress.totalSize > 0L

            return TransferProgressNotificationContent(
                title = title,
                body = body,
                bigText = bigText,
                progressPercent = percent,
                progressIsDeterminate = determinate,
            )
        }

        /**
         * Format an ETA in seconds as a human-readable duration. The
         * thresholds are tuned for transfer times: <= 90 s shows the
         * spelled-out seconds for accessibility ("about 30 seconds
         * remaining"); larger ETAs round to the nearest minute or
         * hour. Always prefixed with "about" for buckets > 1 minute
         * to signal the imprecision.
         */
        @Suppress("MagicNumber")
        private fun formatDuration(
            resolver: TextResolver,
            etaSeconds: Long,
        ): String =
            when {
                etaSeconds < 1L ->
                    resolver.getString(StringKey.DURATION_FEW_SECONDS)
                etaSeconds <= SECONDS_THRESHOLD ->
                    resolver.getString(StringKey.DURATION_SECONDS, etaSeconds)
                etaSeconds < HOUR_IN_SECONDS -> {
                    val minutes = (etaSeconds + 30L) / 60L
                    resolver.getString(StringKey.DURATION_ABOUT_MINUTES, minutes)
                }
                else -> {
                    val hours = (etaSeconds + 1800L) / HOUR_IN_SECONDS
                    resolver.getString(StringKey.DURATION_ABOUT_HOURS, hours)
                }
            }

        /**
         * Format a non-negative byte count as a short human-readable
         * string (`"4.2 MB"`, `"512 KB"`, `"123 B"`). 1024-based units
         * (binary) match the sender-side `PayloadSummary.formatBytes`
         * helper; the choice is kept consistent across surfaces so a
         * 4.2 MB transfer reads "4.2 MB" everywhere.
         */
        @Suppress("MagicNumber")
        public fun formatBytes(bytes: Long): String =
            when {
                bytes < 0 -> "0 B"
                bytes < KIB -> "$bytes B"
                bytes < MIB -> "%.1f KB".format(bytes.toDouble() / KIB)
                bytes < GIB -> "%.1f MB".format(bytes.toDouble() / MIB)
                else -> "%.2f GB".format(bytes.toDouble() / GIB)
            }

        private const val KIB: Long = 1024L
        private const val MIB: Long = 1024L * 1024L
        private const val GIB: Long = 1024L * 1024L * 1024L

        private const val PERCENT_FULL: Int = 100
        private const val SECONDS_THRESHOLD: Long = 90L
        private const val HOUR_IN_SECONDS: Long = 3600L
    }

    /**
     * Lookup key for one of the localizable strings the content uses.
     * Pulled out as a sealed enum so the Android wiring (which calls
     * `context.getString(R.string.…)`) and the JVM-only tests (which
     * use a recording resolver) share a single closed set of keys.
     */
    public enum class StringKey {
        /** Title shown when the sender device name is known: "%1$s sending files". */
        TITLE_WITH_NAME,

        /** Title shown when the sender device name is unknown. */
        TITLE_UNKNOWN_SENDER,

        /** Body size segment: "%1$s of %2$s". */
        BODY_SIZE,

        /** Body rate segment: "%1$s/s". */
        BODY_RATE,

        /** Body ETA segment: "%1$s remaining". */
        BODY_ETA,

        /** Duration < 1 second: "less than a second". */
        DURATION_FEW_SECONDS,

        /** Duration in seconds: "%1$d seconds" (English-only Phase 1). */
        DURATION_SECONDS,

        /** Duration in minutes: "about %1$d minutes". */
        DURATION_ABOUT_MINUTES,

        /** Duration in hours: "about %1$d hours". */
        DURATION_ABOUT_HOURS,
    }

    /**
     * Side-effect-free string lookup. Production wires this to
     * `context.getString`; unit tests inject a recording fake.
     */
    public fun interface TextResolver {
        public fun getString(
            key: StringKey,
            vararg args: Any,
        ): String
    }
}
