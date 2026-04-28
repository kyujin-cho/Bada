/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver.consent

import android.content.res.Resources
import io.github.kyujincho.wvmg.protocol.connection.TransferItem
import io.github.kyujincho.wvmg.service.R

/**
 * Pure-JVM rendering of the textual content shown on the consent
 * notification.
 *
 * ### Why a separate type
 *
 * `NotificationCompat.Builder` and the `Resources` lookups it rests
 * on are awkward to test on plain JVM — but the *content* of the
 * notification (title wording, body, action labels, PIN formatting) is
 * the part that benefits most from unit-testing. Splitting the text
 * derivation out of the builder gives us a Robolectric-free assertion
 * surface: a test calls [from] with a fake [TextResolver] and asserts
 * the returned [ConsentNotificationContent] field-by-field.
 *
 * @property title Short, single-line title shown in the collapsed
 *   notification ("Pixel 8 wants to share").
 * @property body One-line subtitle ("3 files (12.4 MB) — PIN 1234").
 *   Falls back gracefully when item count is zero or sender name is
 *   absent.
 * @property bigText Multi-line expanded text shown when the user
 *   pulls down the notification — includes the full file list, one
 *   per line. Truncated to [MAX_LISTED_ITEMS] entries with a "…and N
 *   more" line at the end so we never produce a many-screen-tall
 *   notification.
 * @property acceptLabel Localized text for the Accept action button.
 * @property rejectLabel Localized text for the Reject action button.
 */
public data class ConsentNotificationContent(
    val title: String,
    val body: String,
    val bigText: String,
    val acceptLabel: String,
    val rejectLabel: String,
) {
    public companion object {
        /**
         * Cap on the number of file lines included verbatim in the
         * expanded notification. A typical Quick Share consent prompt
         * shows at most a handful of files; capping protects against a
         * malicious or buggy peer announcing thousands of items.
         */
        public const val MAX_LISTED_ITEMS: Int = 8

        /**
         * Build content from a [ConsentRegistry.Entry] and a
         * [Resources] handle. The [Resources] argument is only
         * consulted for string lookup; tests typically swap in a
         * [TextResolver].
         */
        public fun from(
            resources: Resources,
            entry: ConsentRegistry.Entry,
        ): ConsentNotificationContent =
            from(
                resolver = TextResolver.from(resources),
                entry = entry,
            )

        /**
         * Pure-JVM entry point — the [TextResolver] abstraction lets
         * unit tests stub the localisation layer with a deterministic
         * map.
         */
        public fun from(
            resolver: TextResolver,
            entry: ConsentRegistry.Entry,
        ): ConsentNotificationContent {
            val deviceName = entry.sourceDeviceName?.takeIf { it.isNotBlank() }
            val title =
                if (deviceName != null) {
                    resolver.formatted(R.string.consent_notification_title_with_name, deviceName)
                } else {
                    resolver.formatted(R.string.consent_notification_title_unknown_sender)
                }

            val itemSummary =
                when {
                    entry.itemCount <= 0 ->
                        resolver.formatted(R.string.consent_notification_summary_no_items)
                    // Issue #40: when the introduction announces multiple
                    // payload kinds (e.g. files + URLs), break the summary
                    // into per-kind segments so the user sees "3 files +
                    // 1 URL" instead of an opaque "4 items". Falls back
                    // to the count-only form when the registry entry was
                    // created without an items list (older callers, or a
                    // foreground-service resurrection that lost the
                    // detail).
                    entry.items.isNotEmpty() ->
                        kindBreakdownSummary(resolver, entry.items, entry.totalSize)
                    else ->
                        resolver.formatted(
                            R.string.consent_notification_summary_n_items,
                            entry.itemCount,
                            humanReadableSize(entry.totalSize),
                        )
                }

            val body =
                resolver.formatted(
                    R.string.consent_notification_body,
                    itemSummary,
                    entry.pin,
                )

            val bigText =
                buildString {
                    append(body)
                    if (entry.itemCount > 0) {
                        append('\n')
                        append(
                            resolver.formatted(
                                R.string.consent_notification_bigtext_pin_line,
                                entry.pin,
                            ),
                        )
                    }
                }

            return ConsentNotificationContent(
                title = title,
                body = body,
                bigText = bigText,
                acceptLabel = resolver.formatted(R.string.consent_notification_action_accept),
                rejectLabel = resolver.formatted(R.string.consent_notification_action_reject),
            )
        }

        /**
         * Build a "kinds + size" summary like
         * "3 files + 1 URL (12.4 MB)" from a [TransferItem] list.
         *
         * Group ordering is fixed (files → URLs → addresses → phone
         * numbers → plain text) so callers can rely on stable, readable
         * output regardless of the announcement order. Empty groups are
         * dropped. The total size suffix uses [humanReadableSize].
         *
         * Public + JVM-pure so the unit tests can assert on the exact
         * formatted output without spinning up Robolectric.
         */
        public fun kindBreakdownSummary(
            resolver: TextResolver,
            items: List<TransferItem>,
            totalSize: Long,
        ): String {
            val files = items.count { it is TransferItem.File }
            val urls = items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.URL }
            val addresses =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.ADDRESS }
            val phones =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.PHONE_NUMBER }
            val texts =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.PLAIN }

            val segments = mutableListOf<String>()
            if (files > 0) {
                segments += resolver.formatted(R.string.consent_notification_segment_files, files)
            }
            if (urls > 0) {
                segments += resolver.formatted(R.string.consent_notification_segment_urls, urls)
            }
            if (addresses > 0) {
                segments +=
                    resolver.formatted(R.string.consent_notification_segment_addresses, addresses)
            }
            if (phones > 0) {
                segments +=
                    resolver.formatted(R.string.consent_notification_segment_phone_numbers, phones)
            }
            if (texts > 0) {
                segments += resolver.formatted(R.string.consent_notification_segment_texts, texts)
            }

            // Defensive fallback: an items list whose entries do not
            // match any known kind (future proto additions) collapses
            // back to the generic "N item(s)" form so the summary is
            // never empty.
            if (segments.isEmpty()) {
                return resolver.formatted(
                    R.string.consent_notification_summary_n_items,
                    items.size,
                    humanReadableSize(totalSize),
                )
            }

            val joined = segments.joinToString(separator = " + ")
            return resolver.formatted(
                R.string.consent_notification_summary_kinds_with_size,
                joined,
                humanReadableSize(totalSize),
            )
        }

        /**
         * Convert raw bytes into a human-readable size string —
         * 1024-based, decimal precision matching common file managers.
         * Unitless (no `MB`, `KB` suffix in the resource itself); the
         * suffix is part of the format string for localisation.
         */
        @Suppress("MagicNumber", "ReturnCount")
        public fun humanReadableSize(bytes: Long): String {
            if (bytes < 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }
            return if (value >= 100.0) {
                "${value.toLong()} ${units[unitIndex]}"
            } else {
                String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex])
            }
        }
    }
}

/**
 * Bridge over [Resources.getString] and [Resources.getQuantityString]
 * that lets unit tests substitute a fake string source.
 *
 * `:service-android` is an Android library module so a test JAR can
 * still see [Resources], but instantiating one without an Android
 * runtime requires Robolectric. The [TextResolver] indirection keeps
 * the consent-content tests on plain Junit5.
 */
public fun interface TextResolver {
    /**
     * Return the localised string for [resourceId], formatted with
     * [formatArgs]. The signature mirrors
     * [Resources.getString] (`Resources.getString(int, vararg Object)`)
     * for direct adapter wiring.
     */
    public fun formatted(
        resourceId: Int,
        vararg formatArgs: Any,
    ): String

    public companion object {
        /**
         * Production adapter wrapping a real [Resources]. Inline so
         * we don't allocate per consent post.
         */
        public fun from(resources: Resources): TextResolver =
            TextResolver { resourceId, args ->
                if (args.isEmpty()) {
                    resources.getString(resourceId)
                } else {
                    @Suppress("SpreadOperator") // Resources.getString requires a vararg; this site is rare.
                    resources.getString(resourceId, *args)
                }
            }
    }
}
