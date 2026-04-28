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
 * ### API selection
 *
 *  - **API 29+ (Q and above):** routes through
 *    [MediaStoreDownloadsEnvironment]. No `WRITE_EXTERNAL_STORAGE`
 *    permission is needed.
 *  - **API 24-28:** routes through [LegacyDownloadsEnvironment] writing
 *    to `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
 *    The caller must already hold `WRITE_EXTERNAL_STORAGE` (declared
 *    in `:service-android/src/main/AndroidManifest.xml` with
 *    `maxSdkVersion="28"`).
 *
 * The branch is taken once at construction time and never re-evaluated.
 * That is correct for our use: a process never crosses an API level
 * mid-execution, and the ABI cost of branching per-payload would
 * otherwise show up as `Build.VERSION.SDK_INT` checks on a hot path.
 */
public object DownloadsWriterFactory {
    /**
     * Build a [MediaStoreDownloadsFactory] suitable for the running
     * device.
     *
     * @param context Android context. Only used to obtain the
     *   [android.content.ContentResolver] on API 29+; the legacy path
     *   does not retain a reference. Pass `applicationContext` so
     *   the returned factory does not pin an Activity.
     */
    public fun create(context: Context): MediaStoreDownloadsFactory {
        val environment: DownloadsEnvironment =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreDownloadsEnvironment(context.contentResolver)
            } else {
                @Suppress("DEPRECATION")
                LegacyDownloadsEnvironment(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                )
            }
        // Spool inbound FILE payloads under a private cache subdirectory
        // so we can keep them off the public Downloads scan even on
        // legacy storage. cacheDir is automatically reclaimed by the
        // platform under storage pressure, which is the right behavior
        // for transient transfer state.
        val spoolDirectory = File(context.cacheDir, SPOOL_SUBDIRECTORY)
        return MediaStoreDownloadsFactory(DownloadsWriter(environment), spoolDirectory)
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
