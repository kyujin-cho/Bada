/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * `FileDestinationFactory` implementation that streams inbound FILE
 * payloads into the user's Downloads directory.
 *
 * On API 29+ the bytes flow through `MediaStore.Downloads` and end up
 * as a row visible in the system Downloads UI. On API 24-28 they flow
 * into `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
 * The split is hidden behind a [DownloadsEnvironment]; the public API
 * here works the same on both.
 *
 * ### Why a spool file
 *
 * `:core-protocol`'s [PayloadAssembler] expects a [SeekableByteChannel]
 * destination (#44 — out-of-order FILE chunk delivery requires
 * `position(offset)` + write semantics). Neither `MediaStore`'s
 * `ContentResolver.openOutputStream` nor a plain `FileOutputStream`
 * gives us seekable writes through their `OutputStream` surface, so we
 * spool every in-flight payload to a private-storage `RandomAccessFile`
 * first. The spool's `FileChannel` is fully seekable, satisfying the
 * assembler's contract.
 *
 * On a successful [commit] we copy the spool bytes into the
 * `DownloadsEnvironment` destination's output stream and publish the
 * row. On [abort] we delete the spool and discard the destination.
 *
 * The spool also makes the future resume work in #43 considerably
 * simpler: the partial bytes are already on disk in their final byte
 * positions, so a reconnect can resume by re-opening the spool file
 * and seeking to the next un-covered offset.
 *
 * ### Why a stateful factory
 *
 * The [PayloadAssembler] in `:core-protocol` calls `close()` on the
 * returned [SeekableByteChannel] on BOTH the success path (LAST_CHUNK
 * arrived, byte count matches) and the failure path (a malformed
 * mid-stream chunk). `close()` alone cannot tell which case it is —
 * but committing a partial file to the Downloads UI is harmful (the
 * user sees a broken-looking file with a tickbox checkmark).
 *
 * To resolve this without changing the `:core-protocol` interface,
 * `MediaStoreDownloadsFactory` keeps a per-payload-id table of
 * [InFlight] entries. The wrapping orchestrator (#21):
 *
 *  1. Calls [open] when the assembler asks for a destination.
 *  2. On `PayloadEvent.FileComplete` from the assembler, calls
 *     [commit] with that payload id.
 *  3. On any error / cancel / unexpected channel close before
 *     LAST_CHUNK, calls [abort] with that payload id.
 *
 * Either [commit] or [abort] also closes the underlying channel and
 * the spool file if the assembler hadn't already, and removes the
 * entry from the in-flight map. Both are idempotent so a double-call
 * (e.g. abort during teardown after a commit during normal completion)
 * is a no-op.
 *
 * ### Thread safety
 *
 * The in-flight handle map is a [ConcurrentHashMap] so that a UI-side
 * cancel (which calls [abort] from a different coroutine than the
 * receive loop) does not race with [open] from the receive loop. The
 * [DownloadsWriter] and [DownloadsEnvironment] beneath are NOT
 * required to be thread-safe — the per-handle operations always run
 * on a single thread because each payload id is touched by exactly
 * one of `open` / `commit` / `abort` at a time.
 *
 * @param writer Allocator of new destinations. In production this is
 *   constructed via [DownloadsWriterFactory.create]. Tests pass a
 *   writer wrapping a fake [DownloadsEnvironment] so the routing logic
 *   is exercised on a plain JVM.
 * @param spoolDirectory Directory to use for per-payload spool files.
 *   In production this is the app's `cacheDir`. Tests inject a
 *   `@TempDir`. The directory is created lazily on the first [open]
 *   call.
 */
public class MediaStoreDownloadsFactory internal constructor(
    private val writer: DownloadsWriter,
    private val spoolDirectory: File,
) : FileDestinationFactory {
    /**
     * Map of payload id -> in-flight bookkeeping. A payload that is
     * mid-transfer has an entry; on commit/abort the entry is removed.
     */
    private val inFlight: MutableMap<Long, InFlight> = ConcurrentHashMap()

    /**
     * Per-payload bookkeeping kept while a FILE transfer is in flight.
     *
     * Bundling the destination handle with the local spool file, its
     * channel, and the sender's `last_modified_timestamp_millis` lets
     * [commit] / [abort] tear down everything in one call without the
     * orchestrator having to know about the spool or to re-thread the
     * `PayloadHeader`.
     *
     * @property handle The destination reservation against
     *   [DownloadsEnvironment]. Receives the spool bytes on [commit];
     *   gets discarded on [abort].
     * @property spoolFile Private-storage temp file backing [channel].
     *   Deleted on either commit or abort.
     * @property channel The seekable view into [spoolFile] handed up to
     *   the assembler. Closed before bytes are copied out on commit, or
     *   before the spool is deleted on abort.
     * @property lastModifiedTimestampMillis Sender-supplied
     *   `PayloadHeader.last_modified_timestamp_millis` captured at
     *   [open] time; routed through to the environment on [commit] so
     *   the destination's mtime matches the original (issue #41).
     */
    private class InFlight(
        val handle: DownloadsWriter.Handle,
        val spoolFile: File,
        val channel: SeekableByteChannel,
        val lastModifiedTimestampMillis: Long,
    )

    /**
     * Open a destination channel for an inbound FILE payload. Called
     * by the [PayloadAssembler] on the first chunk of each payload.
     *
     * The destination is sanitized, collision-suffixed, and reserved
     * (as `IS_PENDING=1` on API 29+) before this returns. A private
     * spool file is created in [spoolDirectory] and its
     * [SeekableByteChannel] view is returned to the assembler. The
     * assembler closes the channel after the last chunk OR after a
     * malformed frame causes a `PayloadProtocolException`.
     *
     * @throws java.io.IOException if [DownloadsWriter.beginWrite]
     *   cannot allocate a slot, or if the spool file cannot be
     *   created. The assembler propagates this; the orchestrator turns
     *   it into an `InboundResult.Failed`.
     */
    override fun open(header: PayloadHeader): SeekableByteChannel {
        // The peer can advertise a `parent_folder` but Quick Share
        // doesn't actually mean "create a subdirectory" by it -- it
        // means "this file belongs to a logical folder share". We
        // surface the file directly under Downloads regardless; the
        // user can move it into a folder afterwards. NearDrop does
        // the same.
        val handle = writer.beginWrite(rawFileName = header.fileName, mimeType = null)
        // Allocate the spool file under the configured directory.
        // Using the payload id keeps the filename deterministic for
        // diagnostics and rules out collision with other in-flight
        // payloads even within a single connection.
        if (!spoolDirectory.exists()) spoolDirectory.mkdirs()
        val spoolFile = File(spoolDirectory, "wvmg-spool-${header.id}.part")
        // Pre-existing spool file from a previous crashed transfer
        // would corrupt the current payload's content; truncate by
        // opening with "rw" and explicitly setting length=0. (#43 will
        // re-purpose this exact file for resume; for now we treat any
        // leftover as stale.)
        val raf = RandomAccessFile(spoolFile, "rw")
        try {
            raf.setLength(0L)
        } catch (e: java.io.IOException) {
            runCatching { raf.close() }
            runCatching { spoolFile.delete() }
            handle.discard()
            throw e
        }
        val channel = raf.channel
        // Stash the sender's last-modified timestamp here (issue #41)
        // so commit() can route it through to the environment without
        // the orchestrator having to re-thread the PayloadHeader.
        inFlight[header.id] =
            InFlight(
                handle = handle,
                spoolFile = spoolFile,
                channel = channel,
                lastModifiedTimestampMillis = header.lastModifiedTimestampMillis,
            )
        return channel
    }

    /**
     * Mark the destination for [payloadId] as visible to the user.
     *
     * Closes the spool channel, copies its bytes into the
     * [DownloadsEnvironment] destination's output stream, and commits
     * the destination. On API 29+ this clears the `IS_PENDING` flag on
     * the `MediaStore.Downloads` row; on API 24-28 it renames the
     * `.part` placeholder to its final name (legacy environment
     * implementation detail). The captured
     * `last_modified_timestamp_millis` (issue #41) is forwarded so the
     * destination's mtime matches the original.
     *
     * Best-effort cleanup of the spool file: failure to delete the
     * temp file does not fail the commit.
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been committed; `false` if no such destination exists
     *   (already committed, already aborted, or never opened).
     */
    override fun commit(payloadId: Long): Boolean {
        val entry = inFlight.remove(payloadId) ?: return false
        // Close the spool channel before reading the file back out.
        // The assembler usually has already closed it, but if a
        // commit-from-orchestrator races a pending close we make this
        // idempotent.
        runCatching { entry.channel.close() }
        try {
            entry.spoolFile.inputStream().use { input ->
                entry.handle.outputStream.use { output -> input.copyTo(output) }
            }
            entry.handle.commit(lastModifiedTimestampMillis = entry.lastModifiedTimestampMillis)
        } finally {
            // Always delete the spool, even if the copy failed (the
            // bytes are already in MediaStore at that point and we
            // do not want to leave temp files behind).
            runCatching { entry.spoolFile.delete() }
        }
        return true
    }

    /**
     * Discard the destination for [payloadId]. Closes the underlying
     * channel, deletes the spool file, and asks the
     * [DownloadsEnvironment] to delete the reserved row/file so the
     * user never sees a partial download.
     *
     * Safe to call concurrently with the receive loop's writes -- the
     * close cascades through the spool channel, ending the next
     * `write()` call with a `ClosedChannelException` which the
     * assembler maps to a `PayloadProtocolException`.
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been discarded; `false` if no such destination exists.
     */
    override fun abort(payloadId: Long): Boolean {
        val entry = inFlight.remove(payloadId) ?: return false
        runCatching { entry.channel.close() }
        runCatching { entry.spoolFile.delete() }
        entry.handle.discard()
        return true
    }

    /**
     * Discard every still-tracked destination. Use during connection
     * teardown when individual payload ids are not known (e.g. on
     * coroutine cancellation in [io.github.kyujincho.wvmg.protocol.connection.InboundConnection]).
     *
     * @return The number of destinations discarded.
     */
    override fun abortAll(): Int {
        var count = 0
        // Snapshot the keys: discard mutates the map.
        for (id in inFlight.keys.toList()) {
            if (abort(id)) count++
        }
        return count
    }

    /**
     * Number of in-flight destinations. Visible for diagnostics and
     * for tests that verify the map is drained on commit/abort.
     */
    public val inFlightCount: Int get() = inFlight.size
}
