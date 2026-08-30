/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import android.content.res.Resources
import dev.bluehouse.bada.protocol.connection.TransferItem
import dev.bluehouse.bada.service.R

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
 * @property body One-line subtitle describing WHAT is incoming, broken
 *   down by media kind ("3 photos + 1 video (12.4 MB)"). The PIN is no
 *   longer crammed in here — it is surfaced separately via [pin] so a
 *   long summary can never ellipsize the security digits away.
 * @property pin The 4-digit confirmation PIN, rendered as its own
 *   prominent element in the notification body.
 * @property fileList Newline-separated per-item list shown only in the
 *   expanded state — one line per file ("name · size"). Truncated to
 *   [MAX_LISTED_ITEMS] entries with a "…and N more" trailer so we never
 *   produce a many-screen-tall notification. Empty when there are no
 *   announced items.
 * @property acceptLabel Localized text for the Accept action button.
 * @property rejectLabel Localized text for the Reject action button.
 */
public data class ConsentNotificationContent(
    val title: String,
    val body: String,
    val pin: String,
    val fileList: String,
    val acceptLabel: String,
    val rejectLabel: String,
) {
    public companion object {
        /**
         * Cap on the number of file lines included verbatim in
         * [buildFileList]. A typical Quick Share consent prompt shows
         * at most a handful of files; capping protects against a
         * malicious or buggy peer announcing thousands of items.
         * (The notification itself no longer renders this list — the
         * consent bottom sheet is the detail surface — but the capped
         * builder stays for any text surface that needs the summary.)
         */
        public const val MAX_LISTED_ITEMS: Int = 7

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

            val folderName = sharedRootFolder(entry.items)
            val itemSummary =
                when {
                    entry.itemCount <= 0 ->
                        resolver.formatted(R.string.consent_notification_summary_no_items)
                    folderName != null ->
                        // Issue #39: every announced file shares a root
                        // parent_folder, so the peer is sending a folder.
                        // Folder summary trumps the kind-breakdown form
                        // because Quick Share folder shares are
                        // homogeneous (file-only) by construction —
                        // showing "3 file(s)" without the folder name
                        // would lose information.
                        resolver.formatted(
                            R.string.consent_notification_summary_folder,
                            folderName,
                            entry.itemCount,
                            humanReadableSize(entry.totalSize),
                        )
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

            return ConsentNotificationContent(
                title = title,
                body = itemSummary,
                pin = entry.pin,
                fileList = buildFileList(resolver, entry.items),
                acceptLabel = resolver.formatted(R.string.consent_notification_action_accept),
                rejectLabel = resolver.formatted(R.string.consent_notification_action_reject),
            )
        }

        /**
         * Build the newline-separated per-item list for the expanded
         * notification: one "name · size" line per file (or the text
         * title for non-file items), capped at [MAX_LISTED_ITEMS] with a
         * localized "…and N more" trailer. Returns an empty string when
         * there are no announced items (e.g. a legacy entry without an
         * items list), which the builder uses to keep the expanded view
         * collapsed.
         */
        public fun buildFileList(
            resolver: TextResolver,
            items: List<TransferItem>,
        ): String {
            if (items.isEmpty()) return ""
            val shown = items.take(MAX_LISTED_ITEMS)
            return buildString {
                shown.forEachIndexed { index, item ->
                    if (index > 0) append('\n')
                    // Names and titles are raw peer-supplied strings:
                    // sanitize them so an embedded newline / control char
                    // cannot forge extra list lines, and fall back to the
                    // kind name for a blank text title (same fallback the
                    // consent trampoline uses) so no line renders empty.
                    val line =
                        when (item) {
                            is TransferItem.File ->
                                resolver.formatted(
                                    R.string.consent_notification_filelist_item,
                                    sanitizeDisplayLine(item.name),
                                    humanReadableSize(item.size),
                                )
                            is TransferItem.Text ->
                                sanitizeDisplayLine(item.title).ifBlank { item.kind.name }
                        }
                    append(line)
                }
                val remaining = items.size - shown.size
                if (remaining > 0) {
                    append('\n')
                    append(resolver.formatted(R.string.consent_notification_filelist_more, remaining))
                }
            }
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
            // Break the file count down by media kind so the user sees
            // "3 photos + 1 video" instead of an opaque "4 files". The
            // type comes from the announced mime_type, which is present
            // at consent time even though the file bytes are not — so
            // this is the reliable, sender-agnostic substitute for a real
            // thumbnail preview (which Quick Share does not send us).
            val photos =
                items.count { it is TransferItem.File && it.mimeType.startsWith("image/") }
            val videos =
                items.count { it is TransferItem.File && it.mimeType.startsWith("video/") }
            val files =
                items.count {
                    it is TransferItem.File &&
                        !it.mimeType.startsWith("image/") &&
                        !it.mimeType.startsWith("video/")
                }
            val urls = items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.URL }
            val addresses =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.ADDRESS }
            val phones =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.PHONE_NUMBER }
            val texts =
                items.count { it is TransferItem.Text && it.kind == TransferItem.Text.Kind.PLAIN }

            // Fixed group ordering (photos → videos → files → URLs →
            // addresses → phone numbers → text). Empty groups drop out;
            // each surviving group renders through its own segment string.
            val segments =
                listOf(
                    photos to R.string.consent_notification_segment_photos,
                    videos to R.string.consent_notification_segment_videos,
                    files to R.string.consent_notification_segment_files,
                    urls to R.string.consent_notification_segment_urls,
                    addresses to R.string.consent_notification_segment_addresses,
                    phones to R.string.consent_notification_segment_phone_numbers,
                    texts to R.string.consent_notification_segment_texts,
                ).filter { (count, _) -> count > 0 }
                    .map { (count, resId) -> resolver.formatted(resId, count) }

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
         * Collapse a raw peer-supplied display string onto a single
         * line: every run of Unicode control characters (newlines,
         * tabs, NUL, …) becomes one space, then the result is trimmed.
         * Used for the expanded file list, where an embedded `\n` in a
         * filename or text title would otherwise forge extra list
         * lines (e.g. a fake "…and N more" trailer) and eat the
         * `maxLines` budget.
         */
        public fun sanitizeDisplayLine(raw: String): String = raw.replace(CONTROL_CHARS, " ").trim()

        private val CONTROL_CHARS = Regex("\\p{Cc}+")

        /**
         * Inspect the announced [items] and return the common root
         * parent folder when every file shares one — i.e. when the
         * peer is sending a folder rather than a flat collection of
         * files.
         *
         * "Common root" means the FIRST segment of `parent_folder` is
         * the same non-empty value across every file, and there are
         * no text items mixed in (Quick Share folder shares are
         * file-only). Returns `null` for any of:
         *
         *  - no files at all
         *  - some files have an empty `parent_folder`
         *  - files announce different roots (`A/sub` vs `B/sub`)
         *  - a non-file (text) item is present
         *
         * The returned string is the raw peer-supplied root folder
         * name. Callers MUST treat it as untrusted display text — the
         * destination factory sanitizes the value before it ever
         * reaches the filesystem.
         */
        @Suppress("ReturnCount")
        public fun sharedRootFolder(items: List<TransferItem>): String? {
            if (items.isEmpty()) return null
            // Single-pass scan: collect each item's first parent_folder
            // segment and require the set to converge to one non-empty
            // value across all items. Any text item / empty parent
            // immediately disqualifies the run.
            var root: String? = null
            for (item in items) {
                if (item !is TransferItem.File) return null
                val firstSegment =
                    item.parentFolder
                        .split('/', '\\')
                        .firstOrNull()
                        ?.takeIf { it.isNotEmpty() } ?: return null
                if (root == null) {
                    root = firstSegment
                } else if (root != firstSegment) {
                    return null
                }
            }
            return root
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
