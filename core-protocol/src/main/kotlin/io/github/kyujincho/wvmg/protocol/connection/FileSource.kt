/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import java.nio.channels.ReadableByteChannel

/**
 * Sender-side description of a single file to ship over an
 * [OutboundConnection].
 *
 * `:core-protocol` is platform-independent — it cannot know about
 * `android.net.Uri`, `MediaStore`, or `ContentResolver`. The Android
 * share-intent layer (issue #24) translates user-selected URIs into
 * [FileSource] instances and passes them to [OutboundConnection.run];
 * the JVM-side host harness creates them from `java.nio.file.Path`s.
 *
 * The contract is intentionally minimal:
 *
 *  - [name] / [size] / [mimeType] / [lastModifiedTimestampMillis] are
 *    plain values copied verbatim into the outgoing
 *    [io.github.kyujincho.wvmg.protocol.sharing.IntroductionFrame] file
 *    metadata and the per-payload `PayloadTransferFrame.PayloadHeader`.
 *  - [openChannel] is a factory that returns a [ReadableByteChannel]
 *    positioned at byte zero. The orchestrator invokes it once per
 *    transfer attempt; on retry (a future feature) it would be invoked
 *    again to re-open the same source.
 *
 * The orchestrator closes the returned channel after streaming
 * [size] bytes (or on failure / cancellation). The factory MUST NOT
 * leak the channel itself.
 *
 * @property name Filename as the receiver should see it. Caller is
 *   responsible for any sanitization (`:core-protocol` does not).
 *   Maps to `IntroductionFrame.file_metadata[].name` and to the
 *   outbound `PayloadHeader.file_name`.
 * @property size Total byte count to stream. The orchestrator caps
 *   `ReadableByteChannel.read` calls so we never overshoot this; if
 *   the channel yields fewer bytes the receiver will reject the
 *   payload as undersized.
 * @property mimeType MIME type guess for the bytes (`image/jpeg`,
 *   `application/pdf`, ...). Empty string is acceptable but degrades
 *   the receiver's UI hint.
 * @property lastModifiedTimestampMillis Mirrored onto the outgoing
 *   `PayloadHeader.last_modified_timestamp_millis`. Pass `0L` when
 *   unknown.
 * @property payloadId The Quick Share `payload_id` to use both in the
 *   introduction frame's file metadata entry and on every chunk's
 *   `PayloadHeader.id`. The caller MUST choose a fresh, positive,
 *   unique value per source — duplicates break the receiver's
 *   reassembler.
 * @property parentFolder Optional relative parent directory for folder
 *   sends (#38). Mirrored both onto `FileMetadata.parent_folder` (proto
 *   field 7) and onto each `PayloadHeader.parent_folder` so the receiver
 *   can reconstruct the directory tree when materializing files. Empty
 *   string for top-level files. Path separators are forward slashes,
 *   matching the wire convention NearDrop's PROTOCOL.md describes.
 *   Quick Share's receiver implicitly creates intermediate directories
 *   as files arrive, so empty folders are not transferred.
 */
public class FileSource(
    public val name: String,
    public val size: Long,
    public val mimeType: String,
    public val lastModifiedTimestampMillis: Long,
    public val payloadId: Long,
    public val parentFolder: String = "",
    private val open: () -> ReadableByteChannel,
) {
    init {
        require(size >= 0) { "size must be non-negative, got $size" }
    }

    /**
     * Open a fresh [ReadableByteChannel] positioned at byte zero of the
     * source bytes. The orchestrator owns and closes the returned
     * channel; the factory MUST NOT close it itself.
     *
     * Thread-safety: the factory is invoked at most once per
     * [OutboundConnection.run] invocation, on the orchestrator's
     * coroutine.
     */
    public fun openChannel(): ReadableByteChannel = open()
}
