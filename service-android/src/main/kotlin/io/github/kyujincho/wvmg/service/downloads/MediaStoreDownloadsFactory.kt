/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
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
 * ### Why a stateful factory
 *
 * The [PayloadAssembler] in `:core-protocol` calls `close()` on the
 * returned [WritableByteChannel] on BOTH the success path (LAST_CHUNK
 * arrived, byte count matches) and the failure path (a malformed
 * mid-stream chunk). `close()` alone cannot tell which case it is —
 * but committing a partial file to the Downloads UI is harmful (the
 * user sees a broken-looking file with a tickbox checkmark).
 *
 * To resolve this without changing the `:core-protocol` interface,
 * `MediaStoreDownloadsFactory` keeps a per-payload-id table of
 * [DownloadsWriter.Handle]s. The wrapping orchestrator (#21):
 *
 *  1. Calls [open] when the assembler asks for a destination.
 *  2. On `PayloadEvent.FileComplete` from the assembler, calls
 *     [commit] with that payload id.
 *  3. On any error / cancel / unexpected channel close before
 *     LAST_CHUNK, calls [abort] with that payload id.
 *
 * Either [commit] or [abort] also closes the underlying
 * [WritableByteChannel] if the assembler hadn't already, and removes
 * the handle from the in-flight map. Both are idempotent so a
 * double-call (e.g. abort during teardown after a commit during
 * normal completion) is a no-op.
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
 */
public class MediaStoreDownloadsFactory internal constructor(
    private val writer: DownloadsWriter,
) : FileDestinationFactory {
    /**
     * Per-payload tracking entry. Bundles the [DownloadsWriter.Handle]
     * with the sender-supplied `last_modified_timestamp_millis` captured
     * at [open] time so that [commit] can apply it to the destination
     * (issue #41). A separate field — rather than re-reading from a
     * cached `PayloadHeader` — avoids retaining the protobuf object
     * any longer than the assembler does.
     */
    private class InFlightEntry(
        val handle: DownloadsWriter.Handle,
        val lastModifiedTimestampMillis: Long,
    )

    /**
     * Map of payload id -> tracking entry. A payload that is
     * mid-transfer has an entry; on commit/abort the entry is removed.
     */
    private val inFlight: MutableMap<Long, InFlightEntry> = ConcurrentHashMap()

    /**
     * Open a destination channel for an inbound FILE payload. Called
     * by the [PayloadAssembler] on the first chunk of each payload.
     *
     * The destination is sanitized, collision-suffixed, and reserved
     * (as `IS_PENDING=1` on API 29+) before this returns. The returned
     * channel writes bytes into the reserved storage; the assembler
     * closes the channel after the last chunk OR after a malformed
     * frame causes a [PayloadProtocolException].
     *
     * @throws java.io.IOException if [DownloadsWriter.beginWrite]
     *   cannot allocate a slot. The assembler propagates this; the
     *   orchestrator turns it into an `InboundResult.Failed`.
     */
    override fun open(header: PayloadHeader): WritableByteChannel {
        // The peer can advertise a `parent_folder` but Quick Share
        // doesn't actually mean "create a subdirectory" by it -- it
        // means "this file belongs to a logical folder share". We
        // surface the file directly under Downloads regardless; the
        // user can move it into a folder afterwards. NearDrop does
        // the same.
        val handle = writer.beginWrite(rawFileName = header.fileName, mimeType = null)
        // Track by payload id so the orchestrator can later commit
        // or abort using only the id (it doesn't keep a reference to
        // the channel itself). We also stash the sender's last-modified
        // timestamp here (issue #41) so commit() can route it through
        // to the environment without the orchestrator having to
        // re-thread the header.
        inFlight[header.id] =
            InFlightEntry(
                handle = handle,
                lastModifiedTimestampMillis = header.lastModifiedTimestampMillis,
            )
        // OutputStream -> WritableByteChannel adapter from java.nio.
        // Buffers internally; safe for the assembler's per-chunk
        // ByteBuffer writes.
        return Channels.newChannel(handle.outputStream)
    }

    /**
     * Mark the destination for [payloadId] as visible to the user.
     *
     * On API 29+ this clears the `IS_PENDING` flag on the
     * `MediaStore.Downloads` row; on API 24-28 it renames the
     * `.part` placeholder to its final name (legacy environment
     * implementation detail).
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been committed; `false` if no such destination exists
     *   (already committed, already aborted, or never opened).
     */
    override fun commit(payloadId: Long): Boolean {
        val entry = inFlight.remove(payloadId) ?: return false
        entry.handle.commit(lastModifiedTimestampMillis = entry.lastModifiedTimestampMillis)
        return true
    }

    /**
     * Discard the destination for [payloadId]. Closes the underlying
     * stream and deletes the reserved storage so the user never sees
     * a partial file.
     *
     * Safe to call concurrently with the receive loop's writes -- the
     * close cascades through `Channels.newChannel`, ending the next
     * `write()` call with a `ClosedChannelException` which the
     * assembler maps to a [PayloadProtocolException].
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been discarded; `false` if no such destination exists.
     */
    override fun abort(payloadId: Long): Boolean {
        val entry = inFlight.remove(payloadId) ?: return false
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
