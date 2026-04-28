/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.connection.InboundConnection
import io.github.kyujincho.wvmg.protocol.connection.TransferItem
import io.github.kyujincho.wvmg.service.R
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
    fun `body summarises item count, size, and PIN`() {
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
        assertThat(content.body).contains("PIN 4242")
    }

    @Test
    fun `body handles zero items gracefully`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = 0, totalSize = 0L, pin = "0000"),
            )
        assertThat(content.body).contains("no items")
        assertThat(content.body).contains("PIN 0000")
    }

    @Test
    fun `bigText repeats the PIN line when there is at least one item`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = 1, totalSize = 100L, pin = "1234"),
            )
        // bigText reuses the body and adds a verbatim PIN line for
        // the expanded notification.
        assertThat(content.bigText).contains("Confirm PIN: 1234")
    }

    @Test
    fun `bigText omits the PIN repeat when there are no items`() {
        val content =
            ConsentNotificationContent.from(
                resolver = englishResolver(),
                entry = sampleEntry(itemCount = 0, totalSize = 0L, pin = "0000"),
            )
        assertThat(content.bigText).doesNotContain("Confirm PIN")
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
