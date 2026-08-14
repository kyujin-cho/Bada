/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dev.bluehouse.bada.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateInstallActivity` — an invisible (transparent, no-UI) trampoline
 * Activity launched by the **"Download & install"** action on the
 * [UpdateNotifier] update notification. It exists only because the
 * "install unknown apps" permission flow needs an Activity context.
 *
 * WHAT IT DOES (no visible screen of its own)
 * -------------------------------------------
 * 1. Clears the "update available" notification.
 * 2. On DEBUG builds (`.debug` applicationIdSuffix) → opens the GitHub
 *    release page instead: the release APK's package never matches a debug
 *    install, so the direct-install path can only fail.
 * 3. If this app may not yet install packages (`!canInstallPackages`) → opens
 *    the system "allow this source to install apps" settings page (ONE-TIME
 *    grant, not per-boot), shows a short toast, and finishes.
 * 4. Otherwise → enqueues [UpdateDownloadInstaller.enqueue] (an expedited
 *    WorkManager download → system installer) and finishes immediately.
 *
 * INVOKED BY: PendingIntent in [UpdateNotifier.downloadAndInstallIntent].
 * Manifest: registered transparent, `exported=false`, `excludeFromRecents`,
 * `noHistory` so it never lingers in the task list. Needs the
 * `REQUEST_INSTALL_PACKAGES` manifest permission.
 */
internal class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        val releaseUrl = intent?.getStringExtra(EXTRA_RELEASE_URL)

        // Dismiss the alert that launched us so it does not linger after action.
        UpdateNotifier.cancel(this)

        handleDownloadRequest(apkUrl, version, releaseUrl)
        finish()
    }

    private fun handleDownloadRequest(
        apkUrl: String?,
        version: String,
        releaseUrl: String?,
    ) {
        when {
            apkUrl.isNullOrBlank() -> Unit

            isDebugBuild() -> {
                // applicationIdSuffix ".debug": the downloaded release APK's
                // package (dev.bluehouse.bada) can never update this install
                // (dev.bluehouse.bada.debug), so route to the release page.
                Log.i(
                    TAG,
                    "Debug build ($packageName) cannot self-install the release APK; opening the release page",
                )
                openReleasePage(releaseUrl)
            }

            !canInstallPackages() -> {
                // One-time grant: send the user to enable "install unknown
                // apps", then have them tap Download again.
                runCatching { startActivity(unknownSourcesSettingsIntent()) }
                Toast
                    .makeText(this, R.string.update_install_need_unknown_sources, Toast.LENGTH_LONG)
                    .show()
            }

            else -> {
                UpdateDownloadInstaller.enqueue(applicationContext, apkUrl, version)
                Toast.makeText(this, R.string.update_install_started, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** `true` on debug installs, which carry the `.debug` applicationIdSuffix. */
    private fun isDebugBuild(): Boolean = packageName.endsWith(".debug")

    private fun openReleasePage(releaseUrl: String?) {
        val opened =
            !releaseUrl.isNullOrBlank() &&
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
        if (!opened) {
            Toast.makeText(this, R.string.update_open_release_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * `true` when this app may install APKs without bouncing the user to the
     * "install unknown apps" settings page first. Always true below API 26.
     */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()

    /**
     * Intent that opens the system "allow this source to install apps" screen
     * for this app. The grant is ONE-TIME (it persists; not per-boot).
     */
    private fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )

    internal companion object {
        private const val TAG = "UpdateInstallActivity"
        const val EXTRA_APK_URL = "dev.bluehouse.bada.update.extra.APK_URL"
        const val EXTRA_VERSION = "dev.bluehouse.bada.update.extra.VERSION"
        const val EXTRA_RELEASE_URL = "dev.bluehouse.bada.update.extra.RELEASE_URL"
    }
}
