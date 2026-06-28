/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.connection.InboundConnection
import dev.bluehouse.bada.protocol.connection.TransferItem
import dev.bluehouse.bada.service.R
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * Pure-JVM tests for [ConsentNotificationContent.from].
 *
 * The textual content of the consent notification is the part most
 * likely to drift between revisions (English copy edits, PIN format
 * tweaks, action labels). Pinning the templating logic here lets the
 * `:app` and `:service-android` UI layers evolve their strings
 * independently without breaking the wire-up between
 * [ConsentRegistry.Entry] and the rendered notification.
 *
 * The tests use a [TextResolver] stub so we never need a real
 * Android `Resources` instance.
 */
class ConsentNotificationContentTest {
    @Test
    fun `title uses the device name when present`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(deviceName = "Pixel 8"),
            )
        assertThat(content.title).isEqualTo("Pixel 8 wants to share")
    }

    @Test
    fun `title falls back when device name is null`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(deviceName = null),
            )
        assertThat(content.title).isEqualTo("A nearby device wants to share")
    }

    @Test
    fun `title falls back when device name is blank`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(deviceName = "   "),
            )
        assertThat(content.title).isEqualTo("A nearby device wants to share")
    }

    @Test
    fun `body summarises item count and size, and PIN is a separate field`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry =
                    sampleEntry(
                        itemCount = 3,
                        totalSize = 12L * 1024 * 1024,
                        pin = "4242",
                    ),
            )
        assertThat(content.body).contains("3 item(s)")
        assertThat(content.body).contains("12.0 MB")
        // The PIN moved out of the body into its own field so a long
        // summary can never ellipsize the security digits away.
        assertThat(content.body).doesNotContain("4242")
        assertThat(content.pin).isEqualTo("4242")
    }

    @Test
    fun `body handles zero items gracefully and still carries the PIN`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = 0, totalSize = 0L, pin = "0000"),
            )
        assertThat(content.body).contains("no items")
        assertThat(content.pin).isEqualTo("0000")
    }

    @Test
    fun `body breaks the file count down by photo and video kind`() {
        val items: List<TransferItem> =
            listOf(
                TransferItem.File(1L, "a.jpg", 100L, "image/jpeg"),
                TransferItem.File(2L, "b.png", 200L, "image/png"),
                TransferItem.File(3L, "c.mp4", 300L, "video/mp4"),
                TransferItem.File(4L, "d.bin", 400L, "application/octet-stream"),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 1000L, items = items),
            )
        assertThat(content.body).contains("2 photo(s)")
        assertThat(content.body).contains("1 video(s)")
        assertThat(content.body).contains("1 file(s)")
    }

    @Test
    fun `fileList lists each file with its size, one per line`() {
        val items: List<TransferItem> =
            listOf(
                TransferItem.File(1L, "vacation.jpg", 2L * 1024 * 1024, "image/jpeg"),
                TransferItem.File(2L, "clip.mp4", 5L * 1024 * 1024, "video/mp4"),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 7L * 1024 * 1024, items = items),
            )
        assertThat(content.fileList).contains("vacation.jpg")
        assertThat(content.fileList).contains("2.0 MB")
        assertThat(content.fileList).contains("clip.mp4")
        assertThat(content.fileList).contains("\n")
    }

    @Test
    fun `fileList caps the list and appends an and-N-more trailer`() {
        val items: List<TransferItem> =
            (1..12).map { TransferItem.File(it.toLong(), "f$it.bin", 10L, "application/octet-stream") }
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 120L, items = items),
            )
        // MAX_LISTED_ITEMS is 8, so 12 items leave 4 in the trailer.
        assertThat(content.fileList).contains("…and 4 more")
        assertThat(content.fileList).doesNotContain("f12.bin")
    }

    @Test
    fun `fileList is empty when there is no items list`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = 0, totalSize = 0L, items = emptyList()),
            )
        assertThat(content.fileList).isEmpty()
    }

    @Test
    fun `accept and reject labels are populated`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(),
            )
        assertThat(content.acceptLabel).isEqualTo("Accept")
        assertThat(content.rejectLabel).isEqualTo("Reject")
    }

    /**
     * Issue #40: when the registry entry carries an explicit items
     * list, the consent summary breaks the count down by kind
     * ("3 files + 1 URL") rather than showing an opaque "4 item(s)".
     * Without this, the user cannot tell from the notification alone
     * that the peer is sending a clipboard URL alongside a file.
     */
    @Test
    fun `body breaks down mixed file and url introduction by kind`() {
        val items: List<TransferItem> =
            listOf(
                TransferItem.File(
                    payloadId = 1L,
                    name = "a.bin",
                    size = 1000L,
                    mimeType = "application/octet-stream",
                ),
                TransferItem.File(
                    payloadId = 2L,
                    name = "b.bin",
                    size = 2000L,
                    mimeType = "application/octet-stream",
                ),
                TransferItem.File(
                    payloadId = 3L,
                    name = "c.bin",
                    size = 3000L,
                    mimeType = "application/octet-stream",
                ),
                TransferItem.Text(
                    payloadId = 4L,
                    title = "page",
                    size = 40L,
                    kind = TransferItem.Text.Kind.URL,
                ),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry =
                    sampleEntry(
                        itemCount = items.size,
                        totalSize = items.sumOf { it.size },
                        items = items,
                    ),
            )
        assertThat(content.body).contains("3 file(s)")
        assertThat(content.body).contains("1 URL(s)")
        assertThat(content.body).contains("+")
    }

    /**
     * Edge case: introductions that carry only one payload kind still
     * render through the per-kind path so the resulting summary
     * mentions the kind explicitly ("1 file") instead of the generic
     * "1 item".
     */
    @Test
    fun `body uses kind-specific segment when only texts present`() {
        val items: List<TransferItem> =
            listOf(
                TransferItem.Text(
                    payloadId = 1L,
                    title = "page",
                    size = 40L,
                    kind = TransferItem.Text.Kind.URL,
                ),
                TransferItem.Text(
                    payloadId = 2L,
                    title = "memo",
                    size = 10L,
                    kind = TransferItem.Text.Kind.PLAIN,
                ),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry =
                    sampleEntry(
                        itemCount = items.size,
                        totalSize = items.sumOf { it.size },
                        items = items,
                    ),
            )
        assertThat(content.body).contains("1 URL(s)")
        assertThat(content.body).contains("1 text(s)")
        assertThat(content.body).doesNotContain("item(s)")
    }

    /**
     * The legacy code path — an Entry without an items list — must
     * still render via the generic "N item(s)" form so older callers
     * keep working without churn.
     */
    @Test
    fun `body falls back to N items when items list is empty`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry =
                    sampleEntry(
                        itemCount = 4,
                        totalSize = 1024L,
                        items = emptyList(),
                    ),
            )
        assertThat(content.body).contains("4 item(s)")
    }

    // ---------------------------------------------------------------
    // Folder-share summary (#39).
    // ---------------------------------------------------------------

    @Test
    fun `body uses folder summary when every file shares a root parent_folder`() {
        val items =
            listOf(
                TransferItem.File(
                    payloadId = 1L,
                    name = "a.txt",
                    size = 100L,
                    mimeType = "text/plain",
                    parentFolder = "MyTrip",
                ),
                TransferItem.File(
                    payloadId = 2L,
                    name = "b.txt",
                    size = 200L,
                    mimeType = "text/plain",
                    parentFolder = "MyTrip/sub",
                ),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 300L, items = items),
            )
        assertThat(content.body).contains("Folder \"MyTrip\"")
        assertThat(content.body).contains("2 file(s)")
    }

    @Test
    fun `body falls back to kind breakdown when files have different roots`() {
        val items =
            listOf(
                TransferItem.File(1L, "a.txt", 100L, "text/plain", parentFolder = "First"),
                TransferItem.File(2L, "b.txt", 200L, "text/plain", parentFolder = "Second"),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 300L, items = items),
            )
        // Mixed roots -> not a folder share; with PR #40's kind
        // breakdown precedence we render "2 file(s)" without a
        // "Folder" prefix and without the legacy generic
        // "item(s)" form.
        assertThat(content.body).contains("2 file(s)")
        assertThat(content.body).doesNotContain("Folder")
    }

    @Test
    fun `body falls back to kind breakdown when at least one file has no parent_folder`() {
        val items =
            listOf(
                TransferItem.File(1L, "a.txt", 100L, "text/plain", parentFolder = "MyTrip"),
                TransferItem.File(2L, "b.txt", 200L, "text/plain", parentFolder = ""),
            )
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = items.size, totalSize = 300L, items = items),
            )
        assertThat(content.body).contains("2 file(s)")
        assertThat(content.body).doesNotContain("Folder")
    }

    @Test
    fun `sharedRootFolder returns null when text items are mixed in`() {
        val items =
            listOf(
                TransferItem.File(1L, "a.txt", 100L, "text/plain", parentFolder = "MyTrip"),
                TransferItem.Text(2L, "url", 30L, TransferItem.Text.Kind.URL),
            )
        // Folder shares are file-only by Quick Share's design — any
        // text item present means we should fall back to the generic
        // summary.
        assertThat(ConsentNotificationContent.sharedRootFolder(items)).isNull()
    }

    @Test
    fun `sharedRootFolder accepts mixed forward and backslash separators`() {
        val items =
            listOf(
                TransferItem.File(1L, "a.txt", 100L, "text/plain", parentFolder = "MyTrip/photos"),
                TransferItem.File(2L, "b.txt", 200L, "text/plain", parentFolder = "MyTrip\\videos"),
            )
        // Both separators split out the same root segment.
        assertThat(ConsentNotificationContent.sharedRootFolder(items)).isEqualTo("MyTrip")
    }

    @Test
    fun `humanReadableSize handles bytes through gigabytes`() {
        with(ConsentNotificationContent) {
            assertThat(humanReadableSize(0)).isEqualTo("0 B")
            assertThat(humanReadableSize(512)).isEqualTo("512 B")
            assertThat(humanReadableSize(1024)).isEqualTo("1.0 KB")
            assertThat(humanReadableSize(1024L * 1024)).isEqualTo("1.0 MB")
            assertThat(humanReadableSize(1024L * 1024 * 1024)).isEqualTo("1.0 GB")
            // Negative bytes never appear from a real proto, but
            // defensively normalise to "0 B".
            assertThat(humanReadableSize(-1)).isEqualTo("0 B")
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per resource id is the simplest, most readable shape.
    private fun englishResolver(): TextResolver =
        TextResolver { resourceId, args ->
            when (resourceId) {
                R.string.consent_notification_title_with_name ->
                    String.format(java.util.Locale.ROOT, "%s wants to share", args[0])
                R.string.consent_notification_title_unknown_sender ->
                    "A nearby device wants to share"
                R.string.consent_notification_summary_n_items ->
                    String.format(java.util.Locale.ROOT, "%d item(s) (%s)", args[0], args[1])
                R.string.consent_notification_summary_no_items -> "no items"
                R.string.consent_notification_summary_kinds_with_size ->
                    String.format(java.util.Locale.ROOT, "%s (%s)", args[0], args[1])
                R.string.consent_notification_segment_files ->
                    String.format(java.util.Locale.ROOT, "%d file(s)", args[0])
                R.string.consent_notification_segment_urls ->
                    String.format(java.util.Locale.ROOT, "%d URL(s)", args[0])
                R.string.consent_notification_segment_addresses ->
                    String.format(java.util.Locale.ROOT, "%d address(es)", args[0])
                R.string.consent_notification_segment_phone_numbers ->
                    String.format(java.util.Locale.ROOT, "%d phone number(s)", args[0])
                R.string.consent_notification_segment_texts ->
                    String.format(java.util.Locale.ROOT, "%d text(s)", args[0])
                R.string.consent_notification_segment_photos ->
                    String.format(java.util.Locale.ROOT, "%d photo(s)", args[0])
                R.string.consent_notification_segment_videos ->
                    String.format(java.util.Locale.ROOT, "%d video(s)", args[0])
                R.string.consent_notification_filelist_item ->
                    String.format(java.util.Locale.ROOT, "%s · %s", args[0], args[1])
                R.string.consent_notification_filelist_more ->
                    String.format(java.util.Locale.ROOT, "…and %d more", args[0])
                R.string.consent_notification_summary_folder ->
                    String.format(java.util.Locale.ROOT, "Folder \"%s\" (%d file(s), %s)", args[0], args[1], args[2])
                R.string.consent_notification_body ->
                    String.format(java.util.Locale.ROOT, "%s · PIN %s", args[0], args[1])
                R.string.consent_notification_bigtext_pin_line ->
                    String.format(java.util.Locale.ROOT, "Confirm PIN: %s", args[0])
                R.string.consent_notification_action_accept -> "Accept"
                R.string.consent_notification_action_reject -> "Reject"
                else -> error("Unmocked resource id: $resourceId")
            }
        }

    private fun sampleEntry(
        deviceName: String? = "Pixel",
        pin: String = "1234",
        itemCount: Int = 1,
        totalSize: Long = 1024L,
        items: List<TransferItem> = emptyList(),
    ): ConsentRegistry.Entry =
        ConsentRegistry.Entry(
            connection = InboundConnection(socket = Socket()),
            sourceDeviceName = deviceName,
            pin = pin,
            itemCount = itemCount,
            totalSize = totalSize,
            items = items,
        )
}
