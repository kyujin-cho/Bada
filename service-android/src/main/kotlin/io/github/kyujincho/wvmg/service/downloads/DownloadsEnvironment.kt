/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import java.io.OutputStream

/**
 * Abstract platform surface the [DownloadsWriter] needs to open, commit,
 * and discard destination files.
 *
 * Two concrete implementations exist:
 *
 *  - [MediaStoreDownloadsEnvironment] — backed by Android's `MediaStore`
 *    + `ContentResolver`. Used on API 29+ where scoped storage forbids
 *    direct writes under `/sdcard/Download`.
 *  - [LegacyDownloadsEnvironment] — backed by
 *    `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
 *    Used on API 24-28 where the legacy storage model still permits
 *    direct file writes (with `WRITE_EXTERNAL_STORAGE`).
 *
 * The interface itself is platform-agnostic — it deals only in
 * [Destination] handles, [OutputStream]s, and `String` filenames — so
 * the bulk of the [DownloadsWriter] logic (filename collision, error
 * handling, sanitization wiring) can be unit-tested on a plain JVM with
 * a fake environment. Only the two real implementations need an Android
 * device or Robolectric to exercise.
 *
 * ### Lifecycle of a destination
 *
 *  1. [insertPending] reserves a destination slot (a `MediaStore` row
 *     marked `IS_PENDING=1`, or a placeholder file with `.part` suffix
 *     in the legacy case). Returns a [Destination] handle the caller
 *     uses to refer back to that slot.
 *  2. [openOutputStream] opens a writable stream over the slot's
 *     storage. The caller writes bytes and closes the stream; the
 *     environment does NOT wrap close-on-success commit logic — that
 *     is the [DownloadsWriter]'s job.
 *  3. Exactly one of [commit] or [discard] is called on the handle:
 *     - [commit] flips `IS_PENDING=0` (or renames `.part` → final name)
 *       so the file becomes visible to the user.
 *     - [discard] deletes the row / file. Used when the transfer fails
 *       partway through.
 *
 *  Implementations MUST be safe to call [discard] after [commit] (and
 *  vice versa) without throwing — duplicate disposal is a no-op.
 */
internal interface DownloadsEnvironment {
    /**
     * Reserve a destination slot for a file that is about to be
     * written. The display name passed in is already sanitized; the
     * environment is free to *append* a collision suffix (e.g.
     * `report (1).pdf`) but MUST NOT modify the body of the name.
     *
     * @param displayName Sanitized filename (no separators, no leading
     *   dots, never empty).
     * @param mimeType Best-effort MIME type. Implementations MAY pass
     *   it to `MediaStore.MediaColumns.MIME_TYPE`. `null` lets the
     *   platform infer it from the filename extension.
     * @return Opaque [Destination] handle. Subsequent calls to
     *   [openOutputStream], [commit], or [discard] take this handle.
     * @throws java.io.IOException if reservation fails (e.g. the
     *   `MediaStore` insert returns `null`, or the legacy directory is
     *   not writable).
     */
    fun insertPending(
        displayName: String,
        mimeType: String?,
    ): Destination

    /**
     * Open a writable stream over the slot reserved by [insertPending].
     * The caller owns the returned stream and MUST close it. Closing
     * the stream does NOT [commit] the destination — the caller is
     * responsible for committing or discarding explicitly.
     *
     * @throws java.io.IOException if the slot's underlying storage
     *   cannot be opened (the row was deleted, the file was removed,
     *   the disk is full, etc.).
     */
    fun openOutputStream(destination: Destination): OutputStream

    /**
     * Make the destination visible to the user. Idempotent — a
     * second call is a no-op.
     *
     * @param lastModifiedTimestampMillis When non-zero, request that the
     *   environment record this value as the destination's modification
     *   time (seconds-precision on `MediaStore`, millis on the legacy
     *   filesystem). `0L` means "leave the platform default", which is
     *   the right behavior when the sender never carried a timestamp
     *   (e.g. NearDrop pre-grishka/NearDrop#195, or any peer that left
     *   `PayloadHeader.last_modified_timestamp_millis = 0`). See
     *   issue #41 for the wire-side analysis.
     */
    fun commit(
        destination: Destination,
        lastModifiedTimestampMillis: Long = 0L,
    )

    /**
     * Delete the destination's underlying storage. Used on cancel or
     * failure to prevent partial files from leaking into the user's
     * Downloads UI. Idempotent — a second call (or one after [commit])
     * is allowed; it cleans up best-effort and does not throw.
     */
    fun discard(destination: Destination)

    /**
     * Opaque handle to a reserved destination slot. The
     * environment-private [internalKey] disambiguates handles in the
     * fake/test environment without exposing the URI to non-test code.
     *
     * Production callers should NEVER inspect [internalKey] — read the
     * [displayName] for diagnostics instead. The field exists at all
     * because every disposal path needs *some* deterministic identifier
     * to drive idempotency checks.
     */
    interface Destination {
        /** The final filename the user will see (after collision suffix). */
        val displayName: String

        /**
         * Implementation-private identity token. Only the originating
         * [DownloadsEnvironment] knows how to interpret this value
         * (a `Uri` for the MediaStore env, a `File` for the legacy env,
         * a string for the test fake). Public callers do not.
         */
        val internalKey: Any
    }
}
