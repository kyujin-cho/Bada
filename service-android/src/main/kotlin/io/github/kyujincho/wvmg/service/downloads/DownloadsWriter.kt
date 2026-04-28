/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import java.io.OutputStream

/**
 * Glue that turns a peer-supplied filename into a writable destination
 * inside the user's Downloads directory.
 *
 * `DownloadsWriter` is **platform-agnostic**: it operates entirely in
 * terms of a [DownloadsEnvironment], which abstracts away the
 * `MediaStore` API on API 29+ from the legacy
 * `Environment.getExternalStoragePublicDirectory` API on API 24-28.
 * That abstraction lets us unit-test all of the writer's behavior —
 * filename sanitization, collision suffixing, success/failure
 * lifecycle — on a plain JVM, against a [DownloadsEnvironment] fake.
 *
 * Production wiring is in [DownloadsWriterFactory.create], which
 * picks the right [DownloadsEnvironment] for the current API level
 * before constructing this writer.
 *
 * ### Lifecycle of one received file
 *
 * ```
 * val handle = writer.beginWrite(rawFileName, mimeType)
 * try {
 *     handle.outputStream.use { stream -> /* write payload bytes */ }
 *     handle.commit()        // marks IS_PENDING=0 / renames .part
 * } catch (e: Throwable) {
 *     handle.discard()       // deletes the row / file
 *     throw e
 * }
 * ```
 *
 * The `commit` / `discard` split is deliberate: the [PayloadAssembler]
 * (in `:core-protocol`) closes the destination [java.nio.channels.WritableByteChannel]
 * BEFORE the surrounding orchestrator (#21) knows whether the transfer
 * succeeded. Closing alone cannot decide commit-vs-discard; the
 * orchestrator drives that explicitly via the handle.
 *
 * @property environment Abstract storage backend.
 * @property fallbackName Replacement string when a peer-supplied
 *   filename sanitizes to empty (e.g. `"."` or `""`). Defaults to
 *   [FilenameSanitizer.DEFAULT_FALLBACK].
 * @property maxCollisionAttempts Upper bound on the suffix counter when
 *   the chosen filename collides with an existing entry. Quick Share
 *   never realistically pushes more than a handful of files with the
 *   same name, so 1024 is generous; failing here is preferable to
 *   spinning forever if a misbehaving environment never converges.
 */
internal class DownloadsWriter(
    private val environment: DownloadsEnvironment,
    private val fallbackName: String = FilenameSanitizer.DEFAULT_FALLBACK,
    private val maxCollisionAttempts: Int = DEFAULT_MAX_COLLISION_ATTEMPTS,
) {
    /**
     * Open a destination for a peer-supplied filename.
     *
     * Sanitizes the name, asks the [environment] to reserve a slot
     * (with collision-suffix retry on conflict), and returns a
     * [Handle] the caller uses to write, commit, or discard.
     *
     * @param rawFileName The peer's `PayloadHeader.file_name`. May be
     *   empty, may contain `..`, may contain NUL.
     * @param mimeType Best-effort MIME hint, propagated to the
     *   environment. `null` lets the platform infer from the extension.
     * @throws java.io.IOException if reservation cannot succeed even
     *   after [maxCollisionAttempts] suffix retries.
     */
    fun beginWrite(
        rawFileName: String,
        mimeType: String?,
        parentFolder: String = "",
    ): Handle {
        val sanitized = FilenameSanitizer.sanitize(rawFileName, fallbackName)
        // The parent folder may carry separators, traversal markers,
        // and empty segments — sanitize before handing to the
        // environment. The result is a list of "real subdirectory
        // name" segments (or empty when the peer announced no parent
        // folder).
        val subPath = FilenameSanitizer.sanitizeRelativePath(parentFolder)

        // Reservation loop: ask the environment for `sanitized`,
        // then `name (1).ext`, `name (2).ext`, ... until a unique
        // slot is granted. Concrete environments report a collision
        // either by returning a colliding [Destination] (handled at
        // the environment layer) or by throwing
        // `FileAlreadyExistsException` here. We catch the exception
        // form because that's what `Files.createFile` raises in the
        // legacy implementation.
        for (attempt in 0..maxCollisionAttempts) {
            val candidate = if (attempt == 0) sanitized else applyCollisionSuffix(sanitized, attempt)
            val destination =
                runCatching { environment.insertPending(candidate, mimeType, subPath) }
                    .getOrElse { e ->
                        // Re-throw immediately for any non-collision IOException —
                        // an actual disk-full / permission error must not be
                        // hidden by another retry.
                        if (!isCollision(e)) throw e
                        null
                    } ?: continue
            val stream = environment.openOutputStream(destination)
            return Handle(environment, destination, stream)
        }
        throw java.io.IOException(
            "Could not allocate Downloads slot for '$sanitized' after " +
                "$maxCollisionAttempts collision retries",
        )
    }

    /**
     * Apply NearDrop's `name (n).ext` collision suffix.
     *
     * Splits the filename into stem + extension at the LAST dot. A
     * leading dot has already been stripped by the sanitizer, so a
     * name like `archive.tar.gz` correctly yields stem=`archive.tar`,
     * extension=`.gz` — the suffix lands in the user-meaningful
     * position.
     *
     * Examples:
     *  - `report.pdf` + 1 -> `report (1).pdf`
     *  - `archive.tar.gz` + 2 -> `archive.tar (2).gz`
     *  - `Makefile` + 3 -> `Makefile (3)`
     */
    private fun applyCollisionSuffix(
        name: String,
        n: Int,
    ): String {
        val dot = name.lastIndexOf('.')
        // No extension, or the dot is at index 0 (which the sanitizer
        // would have stripped, but be defensive): append at the end.
        if (dot <= 0) return "$name ($n)"
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        return "$stem ($n)$extension"
    }

    /**
     * Detect whether [throwable] reports a "destination already
     * exists" condition the writer should retry with a suffix bump.
     *
     * Recognized signals:
     *  - [java.nio.file.FileAlreadyExistsException] — what the legacy
     *    environment raises when `Files.createFile` finds the path
     *    occupied.
     *  - The literal class name `"FileAlreadyExistsException"` — defends
     *    against alternate JDK / Android implementations that throw a
     *    differently-rooted but identically-named exception.
     */
    private fun isCollision(throwable: Throwable): Boolean {
        // Walk the throwable + its cause chain so we catch both the
        // direct case (legacy env throws FileAlreadyExistsException)
        // and any future wrapped-cause case where an environment
        // surfaces the collision through a higher-level IOException.
        var t: Throwable? = throwable
        var found = false
        while (t != null && !found) {
            if (t is java.nio.file.FileAlreadyExistsException ||
                t::class.simpleName == "FileAlreadyExistsException"
            ) {
                found = true
            }
            t = t.cause
        }
        return found
    }

    /**
     * Caller-facing handle returned by [beginWrite]. Bundles the open
     * [outputStream] with [commit]/[discard] callbacks routed back to
     * the environment.
     *
     * The handle is **single-use**: after [commit] or [discard], both
     * methods are subsequent no-ops. Closing [outputStream] does NOT
     * commit — it only closes the underlying file descriptor or
     * `ContentResolver` stream.
     */
    internal class Handle(
        private val environment: DownloadsEnvironment,
        val destination: DownloadsEnvironment.Destination,
        val outputStream: OutputStream,
    ) {
        @Volatile
        private var disposed: Boolean = false

        /** Final filename (with any collision suffix applied). */
        val displayName: String get() = destination.displayName

        /**
         * Make the destination visible. Implicitly closes the stream
         * if the caller forgot to — the underlying environments
         * (especially `MediaStore` on API 29+) cannot mark a row
         * non-pending while a `ContentResolver` output stream is
         * still open against it.
         *
         * @param lastModifiedTimestampMillis Sender-provided modification
         *   time from `PayloadHeader.last_modified_timestamp_millis`. Pass
         *   `0L` (the default) when no timestamp was carried; the
         *   environment leaves the platform default in that case. See
         *   issue #41.
         */
        fun commit(lastModifiedTimestampMillis: Long = 0L) {
            if (disposed) return
            disposed = true
            runCatching { outputStream.close() }
            environment.commit(destination, lastModifiedTimestampMillis)
        }

        /**
         * Drop the destination. Closes the stream and asks the
         * environment to delete the underlying row/file. Idempotent;
         * a second call is a no-op.
         */
        fun discard() {
            if (disposed) return
            disposed = true
            runCatching { outputStream.close() }
            environment.discard(destination)
        }
    }

    internal companion object {
        /**
         * Collision-retry ceiling. Picked large enough to cover any
         * realistic collision storm without inviting an infinite
         * loop if a buggy environment claims every name is taken.
         */
        const val DEFAULT_MAX_COLLISION_ATTEMPTS: Int = 1024
    }
}
