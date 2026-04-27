/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import java.nio.channels.WritableByteChannel
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
 *  - On Android we route the bytes through `MediaStore` / `ContentResolver`
 *    and end up with a content URI under `Downloads/`. There is no
 *    `java.io.File` involved at all — the right primitive is a
 *    [WritableByteChannel] that wraps the `OutputStream` returned by
 *    `ContentResolver.openOutputStream(uri)`.
 *  - On the JVM (host-side test harness, or a hypothetical desktop port)
 *    we want a real file on disk under a configurable directory.
 *  - For unit tests we want an in-memory channel so we can assert on the
 *    exact bytes that were written without touching the filesystem.
 *
 * `:core-protocol` lives below the platform line (no `android.*` imports
 * are allowed), so the assembler cannot itself open a `MediaStore`
 * destination. Instead we accept a factory that the caller — typically
 * `:service-android` — provides to bridge the gap. The factory is invoked
 * exactly once per FILE payload, immediately after the assembler observes
 * the payload's first chunk.
 *
 * The factory contract:
 *  - It MUST return a [WritableByteChannel] in a state ready to accept
 *    `header.total_size` bytes worth of `write()` calls. The channel is
 *    opened in *write-from-byte-zero* mode; the assembler does not seek.
 *  - The assembler closes the channel exactly once: either on the
 *    `LAST_CHUNK` of a successful payload, or as part of cleanup when a
 *    later chunk of that payload causes a [PayloadProtocolException]. The
 *    factory MUST NOT close it itself.
 *  - If the factory throws, the assembler propagates the exception and
 *    no channel allocation has leaked.
 */
public fun interface FileDestinationFactory {
    /**
     * Open a destination channel for the given payload header.
     *
     * @param header The full `PayloadHeader` proto for the payload. Carries
     *   `id`, `total_size`, `file_name`, `parent_folder`, and
     *   `last_modified_timestamp_millis`. Implementations should use
     *   `file_name` and `parent_folder` for naming, and may use
     *   `total_size` to pre-allocate the destination.
     * @return A [WritableByteChannel] that the assembler will [write][WritableByteChannel.write]
     *   `total_size` bytes to before closing.
     */
    public fun open(header: PayloadHeader): WritableByteChannel
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
    override fun open(header: PayloadHeader): WritableByteChannel {
        val dir = baseDirectory ?: defaultDir()
        Files.createDirectories(dir)
        val safeName = sanitize(header.fileName.ifEmpty { "unnamed" })
        val target = dir.resolve("payload-${header.id}-$safeName")
        // CREATE_NEW would be ideal for safety, but tests can re-run with
        // colliding payload ids; TRUNCATE_EXISTING gives us idempotent
        // open behavior. The assembler writes start at byte zero so this
        // is correct.
        return Files.newByteChannel(
            target,
            StandardOpenOption.CREATE,
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
