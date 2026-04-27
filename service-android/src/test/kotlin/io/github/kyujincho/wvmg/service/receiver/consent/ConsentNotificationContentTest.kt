/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.connection.InboundConnection
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
    ): ConsentRegistry.Entry =
        ConsentRegistry.Entry(
            connection = InboundConnection(socket = Socket()),
            sourceDeviceName = deviceName,
            pin = pin,
            itemCount = itemCount,
            totalSize = totalSize,
        )
}
