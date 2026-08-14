/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [UpdateApkAssets.selectAppApkUrl] — the release-asset
 * matcher behind the notification's "Download & install" action. Releases
 * can ship more than one `.apk` (the radio-helper companion app), so the
 * selector must key on the app APK's `dev.bluehouse.bada-` filename prefix
 * rather than "first `.apk` wins".
 */
class UpdateApkAssetsTest {
    @Test
    fun `selects the app apk by its applicationId prefix`() {
        val assets =
            listOf(
                ReleaseAsset("dev.bluehouse.bada-20260701.01.apk", "https://example.com/app.apk"),
            )
        assertEquals("https://example.com/app.apk", UpdateApkAssets.selectAppApkUrl(assets))
    }

    @Test
    fun `skips companion apks that precede the app apk`() {
        // The radio-helper companion APK sorts before the app APK in some
        // release listings; a naive "first .apk" selector would install it.
        val assets =
            listOf(
                ReleaseAsset("dev.bluehouse.bada.radiohelper-1.0.apk", "https://example.com/helper.apk"),
                ReleaseAsset("dev.bluehouse.bada-20260701.01.apk", "https://example.com/app.apk"),
            )
        assertEquals("https://example.com/app.apk", UpdateApkAssets.selectAppApkUrl(assets))
    }

    @Test
    fun `returns null when only companion or unrelated assets exist`() {
        val assets =
            listOf(
                ReleaseAsset("dev.bluehouse.bada.radiohelper-1.0.apk", "https://example.com/helper.apk"),
                ReleaseAsset("checksums.txt", "https://example.com/checksums.txt"),
            )
        assertNull(UpdateApkAssets.selectAppApkUrl(assets))
    }

    @Test
    fun `returns null for an empty asset list`() {
        assertNull(UpdateApkAssets.selectAppApkUrl(emptyList()))
    }

    @Test
    fun `ignores a matching name with a blank download url`() {
        val assets =
            listOf(
                ReleaseAsset("dev.bluehouse.bada-20260701.01.apk", ""),
            )
        assertNull(UpdateApkAssets.selectAppApkUrl(assets))
    }

    @Test
    fun `requires the apk file extension`() {
        val assets =
            listOf(
                ReleaseAsset("dev.bluehouse.bada-20260701.01.aab", "https://example.com/app.aab"),
            )
        assertNull(UpdateApkAssets.selectAppApkUrl(assets))
    }

    @Test
    fun `matches case-insensitively`() {
        val assets =
            listOf(
                ReleaseAsset("Dev.Bluehouse.Bada-20260701.01.APK", "https://example.com/app.apk"),
            )
        assertEquals("https://example.com/app.apk", UpdateApkAssets.selectAppApkUrl(assets))
    }
}
