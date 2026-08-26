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
 * Installer owned by Settings' user-facing "Radio Helper" card.
 *
 * `:app` embeds the debug helper in debug builds and the release helper in
 * release builds. The two APKs use the same signing configuration because the
 * helper's `BIND_RADIO` service permission is signature protected. The asset is
 * streamed into PackageInstaller off the main thread; no browser, download, or
 * temporary APK file is involved. [HelperInstallReceiver] owns confirmation
 * and terminal status; an atomic process latch rejects duplicate taps.
 *
 * Test route: open Bada > Settings > Radio Helper, grant "Install unknown apps"
 * if requested, tap Install, confirm the system installer, then return and open
 * the helper setup. This change is source/diff-checked only; compilation and the
 * real device click path remain unverified because they were not authorized.
 */
internal object HelperInstaller {
    const val ACTION_INSTALL_STATUS = "dev.bluehouse.bada.helper.INSTALL_STATUS"

    private const val ASSET_NAME = "radio-helper.apk"
    private const val HELPER_BASE_PACKAGE = "dev.bluehouse.bada.radiohelper"
    private val installInFlight = AtomicBoolean(false)

    fun helperPackage(context: Context): String =
        if (context.packageName.endsWith(".debug")) {
            "$HELPER_BASE_PACKAGE.debug"
        } else {
            HELPER_BASE_PACKAGE
        }

    @Suppress("DEPRECATION")
    fun isHelperInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(helperPackage(context), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun isInstallInFlight(): Boolean = installInFlight.get()

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun openHelper(context: Context): Boolean {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(helperPackage(context))
                ?: return false
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    /**
     * Stage the bundled helper for system confirmation. Repeated taps while a
     * session is active are ignored; every pre-commit failure abandons its
     * PackageInstaller session and surfaces a user-visible error. The helper's
     * declared package is pinned into SessionParams; Android still performs the
     * final APK identity and signing checks in the system confirmation flow.
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

    /** Clear the duplicate-install latch only after terminal installer status. */
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
