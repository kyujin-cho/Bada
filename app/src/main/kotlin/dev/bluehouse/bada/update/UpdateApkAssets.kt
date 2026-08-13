/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

/**
 * Picks the installable app APK out of a GitHub release's asset list.
 *
 * Releases can carry more than one `.apk` (e.g. the radio-helper companion
 * app), so "first `.apk` wins" is not safe. The app APK is deterministically
 * named `<applicationId>-<versionName>.apk` by the release build
 * (`app/build.gradle.kts` sets `outputFileName = "$applicationId-$versionName.apk"`),
 * so we match on that prefix and treat "no match" as "release has no
 * installable app APK" (the caller then offers the GitHub page only).
 */
internal object UpdateApkAssets {
    /** Release APK naming rule — see `app/build.gradle.kts` output renaming. */
    private const val APP_APK_PREFIX = "dev.bluehouse.bada-"

    fun selectAppApkUrl(assets: List<ReleaseAsset>): String? =
        assets
            .firstOrNull { asset ->
                asset.name.startsWith(APP_APK_PREFIX, ignoreCase = true) &&
                    asset.name.endsWith(".apk", ignoreCase = true) &&
                    asset.url.isNotBlank()
            }?.url
}

/** One GitHub release asset: its file `name` + `browser_download_url`. */
internal data class ReleaseAsset(
    val name: String,
    val url: String,
)
