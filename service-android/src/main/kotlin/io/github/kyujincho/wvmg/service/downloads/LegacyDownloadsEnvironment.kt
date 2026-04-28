/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Legacy (`API 24-28`) implementation of [DownloadsEnvironment].
 *
 * On these API levels there is no scoped storage and no
 * `MediaStore.Downloads` collection — files are written directly to
 * the public Downloads directory via `java.io.File`. This requires the
 * `WRITE_EXTERNAL_STORAGE` permission, which the app's manifest must
 * gate behind `maxSdkVersion="28"` so it is not requested on API 29+
 * where it is a no-op.
 *
 * Pending state is emulated with a `.part` suffix: the in-progress
 * file is named `<sanitized> .part`, which keeps it out of any
 * MediaScanner index, and on [commit] we rename to drop the suffix.
 * On [discard] the `.part` file is deleted. This pattern matches
 * NearDrop's pre-scoped-storage download approach.
 *
 * Construction takes a [downloadsDir] explicitly so unit tests can
 * point it at a JUnit `@TempDir` instead of
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
 * In production the wiring in [DownloadsWriterFactory.create] supplies
 * the real public Downloads directory.
 *
 * @param downloadsDir Destination directory. Must already exist; if it
 *   doesn't, the constructor creates it (matches the platform behavior
 *   on most legacy devices, where the dir is auto-created on first
 *   write but not guaranteed to already be present in tests).
 */
internal class LegacyDownloadsEnvironment(
    private val downloadsDir: File,
) : DownloadsEnvironment {
    init {
        if (!downloadsDir.exists()) {
            // mkdirs failure is not fatal — the user might have a
            // read-only SD card mounted, in which case insertPending
            // will surface a clearer IOException than a generic
            // "directory missing".
            downloadsDir.mkdirs()
        }
    }

    override fun insertPending(
        displayName: String,
        @Suppress("UNUSED_PARAMETER") mimeType: String?,
    ): DownloadsEnvironment.Destination {
        // The `.part` suffix is added at the very end of the filename,
        // AFTER any extension. On commit we strip exactly this suffix
        // to recover the visible filename.
        val partFile = File(downloadsDir, "$displayName$PART_SUFFIX")
        // Use Files.createFile so a colliding `.part` file (i.e. a
        // crashed previous transfer that we never cleaned up) raises
        // FileAlreadyExistsException. The DownloadsWriter retries on
        // that exception with a `(1)`, `(2)`, ... suffix on the
        // VISIBLE name -- the partFile derived from the suffixed
        // visible name is itself unique.
        try {
            Files.createFile(partFile.toPath())
        } catch (e: FileAlreadyExistsException) {
            // Re-throw unchanged; DownloadsWriter listens for this.
            throw e
        } catch (e: IOException) {
            throw IOException(
                "Could not create legacy download placeholder ${partFile.absolutePath}",
                e,
            )
        }
        return LegacyDestination(partFile = partFile, displayName = displayName)
    }

    override fun openOutputStream(destination: DownloadsEnvironment.Destination): OutputStream {
        val partFile = destination.requireLegacyPartFile()
        // append=false: createFile already produced an empty file;
        // we want to start at byte zero.
        return FileOutputStream(partFile, false)
    }

    override fun commit(
        destination: DownloadsEnvironment.Destination,
        lastModifiedTimestampMillis: Long,
    ) {
        val (partFile, displayName) = destination.requireLegacyPair()
        val finalFile = File(downloadsDir, displayName)
        // renameTo is best-effort on legacy filesystems. If it fails
        // (cross-volume rename, unusual SD card layout), we copy and
        // then remove the placeholder.
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = false)
            partFile.delete()
        }
        // Apply the sender's mtime AFTER the rename — `File.setLastModified`
        // on the placeholder would be lost during `renameTo` on some
        // filesystems. Best-effort: a failure (e.g. read-only mount,
        // exotic FS that does not support setMTime) is silently ignored
        // because the bytes are already safely on disk; users can
        // observe the modify time via the OS regardless.
        if (lastModifiedTimestampMillis > 0L) {
            runCatching { finalFile.setLastModified(lastModifiedTimestampMillis) }
        }
    }

    override fun discard(destination: DownloadsEnvironment.Destination) {
        val partFile = destination.requireLegacyPartFile()
        // Idempotent: a missing file is fine; delete returns false
        // and we ignore it.
        runCatching { partFile.delete() }
    }

    /**
     * Concrete destination handle for the legacy environment. Holds
     * both the on-disk placeholder file and the user-visible filename
     * we'll rename it to on [commit].
     */
    private data class LegacyDestination(
        val partFile: File,
        override val displayName: String,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = partFile
    }

    private fun DownloadsEnvironment.Destination.requireLegacyPartFile(): File {
        require(this is LegacyDestination) {
            "LegacyDownloadsEnvironment received a destination it didn't issue: $this"
        }
        return partFile
    }

    private fun DownloadsEnvironment.Destination.requireLegacyPair(): Pair<File, String> {
        require(this is LegacyDestination) {
            "LegacyDownloadsEnvironment received a destination it didn't issue: $this"
        }
        return partFile to displayName
    }

    private companion object {
        /**
         * Suffix appended to the in-flight placeholder file. Chosen
         * to be common across download tools (browsers, NearDrop)
         * so a user inspecting the Downloads folder mid-transfer
         * recognizes the convention.
         */
        const val PART_SUFFIX: String = ".part"
    }
}
