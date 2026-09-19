/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import dev.bluehouse.bada.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WHAT THIS IS
 * ------------
 * `HelperInstaller` — the process-side installer for Settings' user-facing
 * **Radio Helper** card and its **"Install Radio Helper"** action.
 *
 * INVOCATION AND OWNERSHIP
 * ------------------------
 * [dev.bluehouse.bada.ui.SettingsFragment] queries package/install state, opens
 * the one-time "Install unknown apps" system page when required, then calls
 * [installBundledHelper]. `:app` embeds the matching debug/release helper as
 * `assets/radio-helper.apk`; [HelperInstallReceiver] owns PackageInstaller
 * confirmation and terminal status after commit. This object never toggles a
 * radio—the installed helper service owns that separate share-session lifecycle.
 *
 * SECURITY, THREADING, AND FAILURE CONTRACT
 * -----------------------------------------
 * `:app` embeds the debug helper in debug builds and the release helper in
 * release builds. The two APKs use the same signing configuration because the
 * helper's `BIND_RADIO` service permission is signature protected. The asset is
 * local-only: it is streamed into PackageInstaller on the dedicated
 * `radio-helper-install` thread, with no network, browser, or temporary APK
 * file. SessionParams pins the expected variant package; Android owns final APK
 * identity/signature validation. Every failure before commit abandons the
 * staged session and clears the latch. The [AtomicBoolean] is intentionally
 * process-local: it prevents double taps while this process lives, remains set
 * through pending confirmation, clears on terminal callback, and naturally
 * resets if Android recreates the process. No app-level cancellation control is
 * exposed after commit; the system installer owns the user's confirm/cancel UI.
 *
 * TEST STATUS
 * -----------
 * Exercise Bada > Settings > Radio Helper in missing, permission-denied,
 * installing, installed, cancelled, and failed states; confirm the debug build
 * installs `.debug`, release installs the release package, and "Open Radio
 * Helper setup" launches the matching package. Source/diff checks are proven;
 * compilation and the real device click path remain UNVERIFIED.
 */
internal object HelperInstaller {
    const val ACTION_INSTALL_STATUS = "dev.bluehouse.bada.helper.INSTALL_STATUS"

    private const val ASSET_NAME = "radio-helper.apk"
    private const val HELPER_BASE_PACKAGE = "dev.bluehouse.bada.radiohelper"
    private val installInFlight = AtomicBoolean(false)

    /** Variant identity shared by installed-state lookup, session pinning, and setup launch. */
    fun helperPackage(context: Context): String =
        if (context.packageName.endsWith(".debug")) {
            "$HELPER_BASE_PACKAGE.debug"
        } else {
            HELPER_BASE_PACKAGE
        }

    /** PackageManager is the durable source of truth; the in-flight latch is not. */
    @Suppress("DEPRECATION")
    fun isHelperInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(helperPackage(context), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun isInstallInFlight(): Boolean = installInFlight.get()

    /** Precondition checked before any asset/session work or unknown-source round trip. */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** App-scoped system Settings route used by SettingsFragment's result launcher. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /** Launch the matching helper's setup activity; false means no resolvable launcher. */
    fun openHelper(context: Context): Boolean {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(helperPackage(context))
                ?: return false
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    /**
     * Stage the bundled helper for system confirmation after Settings has
     * established unknown-source permission. Repeated taps while this process
     * owns a live request are no-ops. The background thread creates one full-
     * install session, pins [helperPackage], copies the local asset, fsyncs it,
     * and commits an explicit status PendingIntent. Every pre-commit exception
     * abandons the session, clears the latch, and posts the visible failure
     * toast on the main looper; success here means only "staged for system
     * confirmation," not installed.
     */
    fun installBundledHelper(context: Context) {
        if (!installInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread(
            {
                var sessionId: Int? = null
                runCatching {
                    val installer = appContext.packageManager.packageInstaller
                    val params =
                        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                            setAppPackageName(helperPackage(appContext))
                        }
                    sessionId = installer.createSession(params)
                    commitSession(appContext, installer, sessionId!!)
                }.onSuccess {
                    postToast(appContext, R.string.settings_radio_helper_install_started)
                }.onFailure {
                    sessionId?.let { id ->
                        runCatching { appContext.packageManager.packageInstaller.abandonSession(id) }
                    }
                    installInFlight.set(false)
                    postToast(appContext, R.string.settings_radio_helper_install_failed)
                }
            },
            "radio-helper-install",
        ).start()
    }

    /**
     * Blocking asset-copy/commit boundary; called only by the installer thread.
     * The API-31+ PendingIntent must remain mutable because PackageInstaller
     * supplies status and confirmation extras before delivering it to the
     * manifest-private [HelperInstallReceiver].
     */
    private fun commitSession(
        appContext: Context,
        installer: PackageInstaller,
        sessionId: Int,
    ) {
        installer.openSession(sessionId).use { session ->
            appContext.assets.open(ASSET_NAME).use { input ->
                session.openWrite("radio-helper", 0, -1).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent =
                Intent(appContext, HelperInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .setPackage(appContext.packageName)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending = PendingIntent.getBroadcast(appContext, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Clear the duplicate-install latch only after terminal success/failure or pre-commit failure. */
    fun markInstallFinished() {
        installInFlight.set(false)
    }

    private fun postToast(
        context: Context,
        message: Int,
    ) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
