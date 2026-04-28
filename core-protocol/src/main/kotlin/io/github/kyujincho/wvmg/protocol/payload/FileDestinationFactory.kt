/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Strategy for choosing the destination of an inbound FILE payload.
 *
 * Why a factory and not a fixed [Path]? Quick Share has no notion of
 * "save into this exact path"; every received file gets a destination
 * decided by the host platform:
 *
 *  - On Android we spool bytes through a private-storage `RandomAccessFile`
 *    until the transfer completes, then publish the spool file's bytes
 *    into `MediaStore` / `ContentResolver` on commit. The `WritableByteChannel`
 *    surface alone is not enough — issue #44 added support for chunks
 *    arriving out of order (necessary once we add Wi-Fi Direct or BLE
 *    L2CAP mediums in #4.x) and that requires `SeekableByteChannel.position`.
 *  - On the JVM (host-side test harness, or a hypothetical desktop port)
 *    we want a real file on disk under a configurable directory; a
 *    `FileChannel` from `Files.newByteChannel` already implements
 *    [SeekableByteChannel].
 *  - For unit tests we want an in-memory channel so we can assert on the
 *    exact bytes that were written without touching the filesystem; a
 *    small `ByteBuffer`-backed [SeekableByteChannel] adapter is enough.
 *
 * `:core-protocol` lives below the platform line (no `android.*` imports
 * are allowed), so the assembler cannot itself open a `MediaStore`
 * destination. Instead we accept a factory that the caller — typically
 * `:service-android` — provides to bridge the gap. The factory is invoked
 * exactly once per FILE payload, immediately after the assembler observes
 * the payload's first chunk.
 *
 * The factory contract:
 *  - It MUST return a [SeekableByteChannel] in a state ready to accept
 *    `header.total_size` bytes worth of `write()` calls at arbitrary
 *    offsets. The channel is opened with the cursor at byte 0; the
 *    assembler calls [SeekableByteChannel.position] before each write
 *    so a chunk that arrives out of order lands at its declared offset.
 *  - Implementations MAY pre-allocate the destination to `total_size`
 *    bytes (e.g. by truncating to the final size up front) but the
 *    assembler does not require it.
 *  - The assembler closes the channel exactly once: either on the
 *    `LAST_CHUNK` of a successful payload, or as part of cleanup when a
 *    later chunk of that payload causes a [PayloadProtocolException]. The
 *    factory MUST NOT close it itself.
 *  - If the factory throws, the assembler propagates the exception and
 *    no channel allocation has leaked.
 *
 * ### Commit / abort lifecycle
 *
 * Some platforms (notably Android `MediaStore` writes) need an explicit
 * publish step *after* the assembler closes the channel: the bytes are
 * written into a "pending" slot and only become visible to the user once
 * the receiver confirms the payload completed cleanly. Other factories
 * (TempFile, in-memory) have no such distinction — close-on-success is
 * enough.
 *
 * To accommodate both shapes without leaking platform concerns into
 * `:core-protocol`, the factory exposes three lifecycle hooks with
 * **no-op defaults** so simple factories can ignore them:
 *
 *  - [commit] -- the receiver observed `PayloadEvent.FileComplete` for
 *    [payloadId]. Publish the bytes (clear `IS_PENDING`, rename the
 *    `.part` placeholder, etc.). Idempotent: a second call is a no-op.
 *  - [abort] -- the connection terminated abnormally **for this single
 *    payload**. Discard the partial bytes (delete the row, unlink the
 *    file). Idempotent: a second call is a no-op.
 *  - [abortAll] -- the connection terminated abnormally and the caller
 *    does not have a list of in-flight payload ids. Discard every
 *    untracked partial. Equivalent to calling [abort] for each currently
 *    open payload.
 *
 * The [io.github.kyujincho.wvmg.protocol.connection.InboundConnection]
 * orchestrator is the canonical caller of these hooks: [commit] from the
 * `FileComplete` path, [abortAll] from every cancel / failure / error
 * path. Receiver-initiated mid-transfer cancel guarantees no partial
 * file remains visible to the user.
 */
public interface FileDestinationFactory {
    /**
     * Open a destination channel for the given payload header.
     *
     * @param header The full `PayloadHeader` proto for the payload. Carries
     *   `id`, `total_size`, `file_name`, `parent_folder`, and
     *   `last_modified_timestamp_millis`. Implementations should use
     *   `file_name` and `parent_folder` for naming, and may use
     *   `total_size` to pre-allocate the destination.
     * @return A [SeekableByteChannel] that the assembler will
     *   [write][SeekableByteChannel.write] `total_size` bytes to (across
     *   one or more chunks, possibly delivered out of order) before
     *   closing.
     */
    public fun open(header: PayloadHeader): SeekableByteChannel

    /**
     * Publish the destination for [payloadId] now that the assembler has
     * observed `PayloadEvent.FileComplete`. Default: no-op, returns
     * `false`.
     *
     * Implementations that buffer writes through an "is pending" flag
     * (Android `MediaStore`) override this to clear the flag. Simple
     * factories that write straight through to a final path (TempFile,
     * in-memory) leave the default.
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been committed; `false` if no such destination exists
     *   (already committed, already aborted, never opened, or the
     *   factory has no commit semantics).
     */
    public fun commit(payloadId: Long): Boolean = false

    /**
     * Discard the destination for [payloadId]. Called on every code path
     * where the connection terminates without a successful FileComplete:
     * user cancel, peer cancel, protocol error, I/O failure, coroutine
     * cancellation. Default: no-op, returns `false`.
     *
     * Implementations that need to delete partial bytes (Android
     * `MediaStore`) override this to remove the reserved row. The
     * default suits factories whose `WritableByteChannel.close()` already
     * cleans up (TempFile leaves the partial file in `${tmpdir}` for the
     * test runner to inspect, in-memory leaves nothing to clean up).
     *
     * Idempotent: a second call (e.g. abort followed by abortAll during
     * teardown) returns `false`.
     *
     * @return `true` if a destination was tracked for [payloadId] and
     *   has now been discarded; `false` otherwise.
     */
    public fun abort(payloadId: Long): Boolean = false

    /**
     * Discard every in-flight destination managed by this factory.
     * Convenience for the orchestrator's teardown path -- it does not
     * have to track payload ids itself. Default: no-op, returns `0`.
     *
     * The contract for implementations: after [abortAll] returns, every
     * destination opened via [open] but not yet [commit]ted must be
     * discarded.
     *
     * @return The number of destinations discarded.
     */
    public fun abortAll(): Int = 0
}

/**
 * Default [FileDestinationFactory] that writes to a uniquely-named file
 * under the JVM temp directory. Intended for tests, the JVM-side host
 * harness, and any code path that has not yet integrated a
 * platform-specific destination.
 *
 * The output filename is `payload-<payloadId>-<sanitizedName>` so a single
 * test run can complete multiple payloads without colliding. We sanitize
 * the peer-provided `file_name` (it is attacker-controlled bytes) by
 * replacing anything that is not `[A-Za-z0-9._-]` with an underscore;
 * this avoids both directory-traversal-flavored names (`../../etc/passwd`)
 * and platform-illegal characters that would make the open call throw.
 *
 * The destination directory is `${java.io.tmpdir}/wvmg-payload-receive`.
 * The directory is created lazily; the caller is responsible for cleaning
 * it up. **Do not use this factory in production** — it leaves files
 * scattered across the temp dir.
 */
public class TempFileDestinationFactory(
    /**
     * Override the parent directory if you need a deterministic location
     * for tests. When `null`, falls back to `${java.io.tmpdir}/wvmg-payload-receive`.
     */
    private val baseDirectory: Path? = null,
) : FileDestinationFactory {
    override fun open(header: PayloadHeader): SeekableByteChannel {
        val dir = baseDirectory ?: defaultDir()
        Files.createDirectories(dir)
        val safeName = sanitize(header.fileName.ifEmpty { "unnamed" })
        val target = dir.resolve("payload-${header.id}-$safeName")
        // CREATE_NEW would be ideal for safety, but tests can re-run with
        // colliding payload ids; TRUNCATE_EXISTING gives us idempotent
        // open behavior. We also enable READ so the channel is fully
        // seekable (FileChannel implements SeekableByteChannel either
        // way, but TRUNCATE_EXISTING + write-only on some JVMs reports
        // a non-seekable position cursor; READ keeps the contract clean).
        return Files.newByteChannel(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun defaultDir(): Path = Path.of(System.getProperty("java.io.tmpdir"), "wvmg-payload-receive")

    private fun sanitize(name: String): String = name.map { ch -> if (isSafeFileChar(ch)) ch else '_' }.joinToString("")

    /**
     * Whitelist of characters allowed in a sanitized output filename.
     * Anything else collapses to `_`. Keeps the safe set tight enough
     * to neutralize directory traversal / null-byte / control-character
     * attacks in the peer-supplied `file_name`.
     */
    private fun isSafeFileChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch in SAFE_PUNCTUATION

    private companion object {
        private val SAFE_PUNCTUATION = charArrayOf('.', '-', '_').toSet()
    }
}
