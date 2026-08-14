/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single-shot caller for `GET /repos/{owner}/{repo}/releases/latest`
 * on the GitHub REST API. No auth token: the unauthenticated quota is
 * 60 req/h per source IP, which is more than enough for one check at
 * each app start.
 *
 * Returned [Result.success] always carries a populated [LatestRelease]
 * — failures (network, HTTP non-2xx, JSON shape mismatch, draft /
 * prerelease) are surfaced as [Result.failure] so the caller decides
 * whether to log, retry, or surface to the user.
 *
 * Built on `java.net.HttpURLConnection` so we do not pull in an extra
 * HTTP dependency: per CLAUDE.md the project keeps third-party deps
 * deliberately narrow.
 */
internal object UpdateChecker {
    /**
     * GitHub upstream repository. The release tag scheme follows the
     * app's `versionName` (`YYYYMMDD.NN`), optionally prefixed with
     * `v` — see CLAUDE.md "Release process".
     */
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/kyujin-cho/Bada/releases/latest"
    private const val USER_AGENT = "Bada-Android-UpdateChecker"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun fetchLatestRelease(): Result<LatestRelease> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = openConnection()
                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        error("GitHub releases query failed: HTTP $responseCode")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("draft", false)) {
                        error("Latest release is a draft; skipping")
                    }
                    if (json.optBoolean("prerelease", false)) {
                        error("Latest release is a prerelease; skipping")
                    }
                    val tag =
                        json.optString("tag_name").takeIf { it.isNotBlank() }
                            ?: error("`tag_name` missing from GitHub response")
                    val htmlUrl =
                        json.optString("html_url").takeIf { it.isNotBlank() }
                            ?: error("`html_url` missing from GitHub response")
                    LatestRelease(
                        version = stripVPrefix(tag),
                        releaseUrl = htmlUrl,
                        apkAssetUrl = UpdateApkAssets.selectAppApkUrl(parseAssets(json.optJSONArray("assets"))),
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * Project the release's `assets` array into plain [ReleaseAsset]s for
     * [UpdateApkAssets.selectAppApkUrl], which picks the app's own APK by
     * its `dev.bluehouse.bada-` filename prefix (releases can also ship
     * companion APKs, so "first `.apk`" is not safe) or `null` when the
     * release carries no installable app APK.
     *
     * This is what makes the update NOTIFICATION adaptive: when a release
     * has the installable APK attached, the notification can offer a direct
     * "Download & install" drop-in update; when no APK is present, the
     * caller falls back to only sending the user to the GitHub release page.
     */
    private fun parseAssets(assets: JSONArray?): List<ReleaseAsset> =
        assets
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .map {
                        ReleaseAsset(
                            name = it.optString("name"),
                            url = it.optString("browser_download_url"),
                        )
                    }
            }.orEmpty()

    private fun openConnection(): HttpURLConnection {
        val connection = URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun stripVPrefix(tag: String): String =
        if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag
}

/**
 * Minimal `releases/latest` projection.
 *
 * @param version    the release version (`tag_name` with any leading `v`
 *                   stripped), compared against `BuildConfig.VERSION_NAME`.
 * @param releaseUrl the human GitHub release page (`html_url`) — the
 *                   "View on GitHub" destination; always present.
 * @param apkAssetUrl direct `browser_download_url` of the release's app APK
 *                   asset (matched by the `dev.bluehouse.bada-` filename
 *                   prefix), or `null` when none is attached. When non-null
 *                   the update notification can offer a direct
 *                   "Download & install"; when null it offers GitHub only.
 */
internal data class LatestRelease(
    val version: String,
    val releaseUrl: String,
    val apkAssetUrl: String?,
)
