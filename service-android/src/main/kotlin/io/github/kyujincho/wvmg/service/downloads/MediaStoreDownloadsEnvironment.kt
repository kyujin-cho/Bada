/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream

/**
 * `MediaStore.Downloads`-backed implementation of [DownloadsEnvironment]
 * for API 29+ (scoped storage).
 *
 * The flow follows the Android documentation's recommended pattern for
 * downloaded media:
 *
 *  1. Insert a row into
 *     [MediaStore.Downloads.EXTERNAL_CONTENT_URI] with `IS_PENDING=1`
 *     and `RELATIVE_PATH = "Download/"`. Pending rows are NOT visible
 *     to other apps, which means a partial transfer never leaks into
 *     the Downloads UI.
 *  2. Stream bytes through [ContentResolver.openOutputStream] on the
 *     URI returned by the insert.
 *  3. On success, update the same row with `IS_PENDING=0`. The file
 *     becomes visible immediately.
 *  4. On failure, [ContentResolver.delete] the row, which removes
 *     both the database entry and the underlying file.
 *
 * This environment requires **no `WRITE_EXTERNAL_STORAGE` permission**
 * on API 29+ — that permission is a no-op there, and requesting it is
 * a code smell during scoped-storage migration. The MediaStore inserts
 * are governed by app-private write access to its own Downloads rows,
 * which every app gets for free.
 */
@RequiresApi(android.os.Build.VERSION_CODES.Q)
internal class MediaStoreDownloadsEnvironment(
    private val contentResolver: ContentResolver,
) : DownloadsEnvironment {
    override fun insertPending(
        displayName: String,
        mimeType: String?,
    ): DownloadsEnvironment.Destination {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                if (mimeType != null) {
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                }
                // Place under the public Downloads/ directory. This
                // controls where the file lives on disk; without
                // RELATIVE_PATH, MediaStore picks an app-private path
                // that the user can't see in the Files app.
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

        val uri =
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(
                    "MediaStore.Downloads insert returned null for displayName='$displayName'",
                )
        return MediaStoreDestination(uri = uri, displayName = displayName)
    }

    override fun openOutputStream(destination: DownloadsEnvironment.Destination): OutputStream {
        val uri = destination.requireMediaStoreUri()
        // openOutputStream(uri, "w") — "w" truncates if anything
        // somehow already lives at the URI. With IS_PENDING=1 set and
        // a fresh insert there shouldn't be any prior content, but
        // truncate-on-open is the safer default.
        return contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("ContentResolver.openOutputStream returned null for $uri")
    }

    override fun commit(
        destination: DownloadsEnvironment.Destination,
        lastModifiedTimestampMillis: Long,
    ) {
        val uri = destination.requireMediaStoreUri()
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
                // MediaStore.MediaColumns.DATE_MODIFIED is documented as
                // "seconds since epoch" — divide the wire-format millis
                // before writing. We only set the column when the
                // sender actually carried a timestamp; passing `0` (the
                // proto default) here would silently rewrite valid
                // rows to the Unix epoch, which is worse than leaving
                // the platform's "now" default.
                if (lastModifiedTimestampMillis > 0L) {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, lastModifiedTimestampMillis / SECOND_IN_MILLIS)
                }
            }
        // Best-effort: a failure here means the file is on disk but
        // remains marked pending. The orchestrator surfaces the error
        // separately; we do NOT throw so the caller's commit path is
        // simple. A pending row is visible in our app's MediaStore
        // queries, just not in other apps' — acceptable tradeoff.
        runCatching { contentResolver.update(uri, values, null, null) }
    }

    override fun discard(destination: DownloadsEnvironment.Destination) {
        val uri = destination.requireMediaStoreUri()
        // Idempotent: if the row was already deleted (e.g. discard
        // called twice, or commit-then-discard race) the delete just
        // returns 0 rows affected. We don't propagate that.
        runCatching { contentResolver.delete(uri, null, null) }
    }

    /**
     * Concrete destination handle for the MediaStore environment.
     * Carries the row's content URI so [openOutputStream], [commit],
     * and [discard] can address the same row across calls.
     */
    private data class MediaStoreDestination(
        val uri: Uri,
        override val displayName: String,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = uri
    }

    private fun DownloadsEnvironment.Destination.requireMediaStoreUri(): Uri {
        require(this is MediaStoreDestination) {
            "MediaStoreDownloadsEnvironment received a destination it didn't issue: $this"
        }
        return uri
    }

    private companion object {
        /** Conversion factor between the wire's millis precision and `DATE_MODIFIED`'s seconds precision. */
        const val SECOND_IN_MILLIS: Long = 1000L
    }
}
