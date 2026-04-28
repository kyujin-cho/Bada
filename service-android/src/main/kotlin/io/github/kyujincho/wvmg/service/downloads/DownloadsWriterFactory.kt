/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.annotation.VisibleForTesting
import java.io.File

/**
 * Public entry point for constructing a [MediaStoreDownloadsFactory]
 * configured for the device the app is currently running on.
 *
 * This is the **only** member callers outside this package need to
 * know about. The internal split into [DownloadsEnvironment] +
 * [DownloadsWriter] + [MediaStoreDownloadsFactory] is for testability
 * and structure; consumers (the receiver orchestrator in #21) just
 * call [create] and inject the returned factory into
 * [io.github.kyujincho.wvmg.protocol.connection.InboundConnection.run].
 *
 * ### Environment selection
 *
 *  1. **User-chosen save tree (issue #42):** if [SaveLocationPreferences]
 *     reports a still-valid persisted SAF tree URI, files are written
 *     there via [SafTreeDownloadsEnvironment]. The user-picked tree
 *     trumps the device's default Downloads location regardless of
 *     API level.
 *  2. **API 29+ (Q and above):** falls back to
 *     [MediaStoreDownloadsEnvironment] writing under `Downloads/`.
 *     No `WRITE_EXTERNAL_STORAGE` permission needed.
 *  3. **API 24-28:** falls back to [LegacyDownloadsEnvironment]
 *     writing to
 *     `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
 *     The caller must already hold `WRITE_EXTERNAL_STORAGE` (declared
 *     in `:service-android/src/main/AndroidManifest.xml` with
 *     `maxSdkVersion="28"`).
 *
 * The branch is taken once per [create] call. Because the foreground
 * service constructs a fresh factory per accepted connection
 * (`factoryProvider = { DownloadsWriterFactory.create(context) }`),
 * a settings change between transfers picks up automatically without
 * any explicit invalidation.
 */
public object DownloadsWriterFactory {
    /**
     * Build a [MediaStoreDownloadsFactory] suitable for the running
     * device, honouring any user-chosen save location.
     *
     * @param context Android context. Used to obtain the
     *   [android.content.ContentResolver], the spool directory, and
     *   the [SaveLocationPreferences] for the user's chosen save
     *   tree. Pass `applicationContext` so the returned factory does
     *   not pin an Activity.
     */
    public fun create(context: Context): MediaStoreDownloadsFactory {
        val environment = chooseEnvironment(context)
        // Spool inbound FILE payloads under a private cache subdirectory
        // so we can keep them off the public Downloads scan even on
        // legacy storage. cacheDir is automatically reclaimed by the
        // platform under storage pressure, which is the right behavior
        // for transient transfer state.
        val spoolDirectory = File(context.cacheDir, SPOOL_SUBDIRECTORY)
        return MediaStoreDownloadsFactory(DownloadsWriter(environment), spoolDirectory)
    }

    /**
     * Select the right [DownloadsEnvironment] for the current device
     * + user settings. Pulled out so unit tests can probe the
     * decision logic without instantiating the real factory.
     *
     * The user-chosen save tree URI takes precedence over both the
     * MediaStore and legacy paths. When the URI is missing or its
     * persisted grant has been revoked (signaled by
     * [SaveLocationPreferences.getSaveTreeUri] returning `null`), we
     * fall through to the platform-default Downloads environment so
     * receives keep working even if the chosen folder went away.
     */
    private fun chooseEnvironment(context: Context): DownloadsEnvironment {
        val app = context.applicationContext
        val savedTreeUri = SaveLocationPreferences.from(app).getSaveTreeUri()
        if (savedTreeUri != null) {
            return SafTreeDownloadsEnvironment(
                contentResolver = app.contentResolver,
                treeUri = savedTreeUri,
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreDownloadsEnvironment(app.contentResolver)
        } else {
            @Suppress("DEPRECATION")
            LegacyDownloadsEnvironment(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            )
        }
    }

    /**
     * Test-only constructor that lets a unit test inject a fake
     * [DownloadsEnvironment] directly. Marked `@VisibleForTesting` so
     * production code paths only see [create].
     */
    @VisibleForTesting
    internal fun fromEnvironment(
        environment: DownloadsEnvironment,
        spoolDirectory: File,
    ): MediaStoreDownloadsFactory = MediaStoreDownloadsFactory(DownloadsWriter(environment), spoolDirectory)

    /**
     * Subdirectory inside the app's `cacheDir` where in-flight FILE
     * payloads are spooled. The directory is created lazily on the
     * first transfer and cleared on factory teardown
     * ([MediaStoreDownloadsFactory.abortAll]).
     */
    internal const val SPOOL_SUBDIRECTORY: String = "wvmg-payload-spool"
}
