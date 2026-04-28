/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver.progress

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.connection.TransferProgress
import org.junit.jupiter.api.Test

/**
 * JVM-only tests for the textual content of the in-flight transfer
 * notification (#46).
 *
 * The format is the most likely to drift between revisions (English
 * copy edits, ETA bucket tuning), so pinning it here lets the
 * Android-side wiring evolve without breaking the rendered string.
 *
 * The tests use a recording [TransferProgressNotificationContent.TextResolver]
 * so we never need a real `Resources`. The resolver echoes a printf
 * of the key + args, which makes assertions trivial to read.
 */
class TransferProgressNotificationContentTest {
    @Test
    fun `title uses sender device name when present`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = "Pixel 8",
                progress = quietProgress(50, 100),
            )
        assertThat(content.title).isEqualTo("Receiving from Pixel 8")
    }

    @Test
    fun `title falls back when device name is null`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                progress = quietProgress(50, 100),
            )
        assertThat(content.title).isEqualTo("Receiving files")
    }

    @Test
    fun `title falls back when device name is blank`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = "  ",
                progress = quietProgress(50, 100),
            )
        assertThat(content.title).isEqualTo("Receiving files")
    }

    @Test
    fun `body shows only size segment while warming up (no rate yet)`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                progress =
                    TransferProgress(
                        bytesTransferred = 1024L,
                        totalSize = 100L * 1024L * 1024L,
                        bytesPerSecond = 0L,
                        etaSeconds = null,
                    ),
            )
        // Just the size segment — no rate, no ETA.
        assertThat(content.body).isEqualTo("1.0 KB of 100.0 MB")
    }

    @Test
    fun `body shows size, rate, and ETA when steady-state`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                progress =
                    TransferProgress.of(
                        bytesTransferred = 50L * 1024L * 1024L,
                        totalSize = 100L * 1024L * 1024L,
                        bytesPerSecond = 5L * 1024L * 1024L,
                    ),
            )
        // 50 MB of 100 MB · 5 MB/s · 10 seconds remaining
        assertThat(content.body).contains("50.0 MB of 100.0 MB")
        assertThat(content.body).contains("5.0 MB/s")
        assertThat(content.body).contains("10 seconds remaining")
    }

    @Test
    fun `body uses about-minutes phrasing for medium ETAs`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                // 50 MB remaining at 100 KB/s = 524 s ≈ 9 min.
                progress =
                    TransferProgress.of(
                        bytesTransferred = 0L,
                        totalSize = 50L * 1024L * 1024L,
                        bytesPerSecond = 100L * 1024L,
                    ),
            )
        assertThat(content.body).contains("about")
        assertThat(content.body).contains("minutes")
        assertThat(content.body).contains("remaining")
    }

    @Test
    fun `body uses about-hours phrasing for long ETAs`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                // 5 GB at 100 KB/s ≈ 14 hours.
                progress =
                    TransferProgress.of(
                        bytesTransferred = 0L,
                        totalSize = 5L * 1024L * 1024L * 1024L,
                        bytesPerSecond = 100L * 1024L,
                    ),
            )
        assertThat(content.body).contains("about")
        assertThat(content.body).contains("hours")
    }

    @Test
    fun `progressPercent reflects fraction rounded to nearest integer`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                progress = quietProgress(33, 100),
            )
        assertThat(content.progressPercent).isEqualTo(33)
        assertThat(content.progressIsDeterminate).isTrue()
    }

    @Test
    fun `progress is indeterminate when totalSize is zero`() {
        val content =
            TransferProgressNotificationContent.from(
                resolver = englishResolver(),
                sourceDeviceName = null,
                progress = TransferProgress.UNKNOWN,
            )
        assertThat(content.progressIsDeterminate).isFalse()
        assertThat(content.progressPercent).isEqualTo(0)
    }

    @Test
    fun `formatBytes produces stable strings across tier boundaries`() {
        // Keep this tight so a tier-boundary change is caught.
        assertThat(TransferProgressNotificationContent.formatBytes(0)).isEqualTo("0 B")
        assertThat(TransferProgressNotificationContent.formatBytes(123)).isEqualTo("123 B")
        assertThat(TransferProgressNotificationContent.formatBytes(1024)).isEqualTo("1.0 KB")
        assertThat(TransferProgressNotificationContent.formatBytes(1024L * 1024L)).isEqualTo("1.0 MB")
        assertThat(TransferProgressNotificationContent.formatBytes(1024L * 1024L * 1024L)).isEqualTo("1.00 GB")
    }

    private fun quietProgress(
        transferred: Long,
        total: Long,
    ): TransferProgress =
        TransferProgress(
            bytesTransferred = transferred,
            totalSize = total,
            bytesPerSecond = 0L,
            etaSeconds = null,
        )

    /**
     * Recording resolver that mirrors the English copy in
     * `service-android/src/main/res/values/strings.xml`. Pinned here
     * so the test suite is the single source of truth for the
     * rendered output — a stray copy edit in `strings.xml` will
     * still be caught by the human-eyed locale review, while the
     * unit tests stay deterministic.
     */
    private fun englishResolver(): TransferProgressNotificationContent.TextResolver =
        TransferProgressNotificationContent.TextResolver { key, args ->
            when (key) {
                TransferProgressNotificationContent.StringKey.TITLE_WITH_NAME ->
                    "Receiving from ${args[0]}"
                TransferProgressNotificationContent.StringKey.TITLE_UNKNOWN_SENDER ->
                    "Receiving files"
                TransferProgressNotificationContent.StringKey.BODY_SIZE ->
                    "${args[0]} of ${args[1]}"
                TransferProgressNotificationContent.StringKey.BODY_RATE ->
                    "${args[0]}/s"
                TransferProgressNotificationContent.StringKey.BODY_ETA ->
                    "${args[0]} remaining"
                TransferProgressNotificationContent.StringKey.DURATION_FEW_SECONDS ->
                    "less than a second"
                TransferProgressNotificationContent.StringKey.DURATION_SECONDS ->
                    "${args[0]} seconds"
                TransferProgressNotificationContent.StringKey.DURATION_ABOUT_MINUTES ->
                    "about ${args[0]} minutes"
                TransferProgressNotificationContent.StringKey.DURATION_ABOUT_HOURS ->
                    "about ${args[0]} hours"
            }
        }
}
