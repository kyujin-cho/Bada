/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader

/**
 * Result of feeding a single `PayloadTransferFrame` into [PayloadAssembler].
 *
 * The assembler is fundamentally a state machine that consumes one chunk at
 * a time. Most chunks merely advance internal state and return
 * [Progress] (or [Ignored] for non-DATA packets we do not assemble). Only
 * the final chunk of a payload — the one carrying `LAST_CHUNK` — produces a
 * terminal event ([BytesComplete] or [FileComplete]).
 *
 * The sealed hierarchy lets the higher-level state machine (issue #15+)
 * dispatch on the *kind* of progress without having to inspect the proto
 * itself, and lets unit tests assert on a typed value rather than on a
 * grab-bag of out parameters.
 *
 * ### Why this is a return value rather than a callback
 *
 * The receive coroutine in [io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel]
 * already owns the read loop. Letting the assembler return events lets the
 * caller decide what to do (forward up the stack, log, drop) without
 * pulling coroutine machinery into the assembler itself. This keeps the
 * assembler a pure synchronous state machine that is trivially unit
 * testable on the JVM.
 */
public sealed interface PayloadEvent {
    /**
     * Emitted on every non-final DATA chunk of an in-flight payload. The
     * caller can use [bytesReceived] / [totalSize] to drive a progress UI
     * (FILE payloads can be GB-scale, so per-chunk progress matters).
     *
     * @property payloadId The `PayloadHeader.id` this chunk belonged to.
     * @property bytesReceived Cumulative bytes the assembler has buffered
     *   (BYTES) or written (FILE) for this payload after this chunk.
     * @property totalSize The advertised `PayloadHeader.total_size`. Equal
     *   to `0` if the peer omitted the header (allowed mid-stream); the
     *   first chunk of any new payload must include it.
     * @property type BYTES or FILE — surfaced so callers can decide
     *   whether to even render progress (BYTES messages flash by too
     *   quickly to bother).
     */
    public data class Progress(
        val payloadId: Long,
        val bytesReceived: Long,
        val totalSize: Long,
        val type: PayloadHeader.PayloadType,
    ) : PayloadEvent

    /**
     * Emitted when a BYTES payload's `LAST_CHUNK` chunk has been
     * consumed and the buffer is complete.
     *
     * The buffer is handed up as a fresh [ByteArray] (no aliasing into
     * internal state) so the caller can pass it into protobuf parsers or
     * keep it around without worrying about the assembler mutating it
     * underneath them.
     *
     * @property payloadId The completed payload's id.
     * @property data Assembled bytes. May be empty (a peer is allowed to
     *   send a zero-length BYTES payload — and Android's two-frame quirk
     *   means most frames are followed by an empty trailer).
     */
    public data class BytesComplete(
        val payloadId: Long,
        val data: ByteArray,
    ) : PayloadEvent {
        // ByteArray uses identity equals by default, which is wrong for
        // a value-type event. Override so tests and the higher layer can
        // compare events by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BytesComplete) return false
            return payloadId == other.payloadId && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * payloadId.hashCode() + data.contentHashCode()
    }

    /**
     * Emitted when a FILE payload's `LAST_CHUNK` chunk has been
     * consumed and the destination [java.nio.channels.WritableByteChannel]
     * has been closed.
     *
     * The assembler does NOT hand back the channel itself — the caller
     * provided it via the factory and is responsible for any post-close
     * action (renaming the temp file into MediaStore, computing checksums,
     * etc.). The event simply confirms that:
     *  - The full `total_size` worth of bytes was written.
     *  - The channel was closed exactly once.
     *
     * @property payloadId The completed payload's id.
     * @property header The `PayloadHeader` that opened this payload. Carries
     *   `file_name`, `parent_folder`, `total_size`, and the original
     *   `last_modified_timestamp_millis`. Useful for the post-completion
     *   move into the user's Downloads folder / MediaStore entry.
     * @property bytesWritten Number of body bytes actually written to the
     *   channel. By contract this is always equal to `header.total_size`
     *   on a successful completion (the assembler refuses to LAST_CHUNK
     *   short).
     */
    public data class FileComplete(
        val payloadId: Long,
        val header: PayloadHeader,
        val bytesWritten: Long,
    ) : PayloadEvent

    /**
     * Emitted for `PayloadTransferFrame`s whose `packet_type` is not
     * `DATA` — i.e. `CONTROL` (cancel / error) and `PAYLOAD_ACK`. The
     * assembler does not own that state, so it just reports the
     * occurrence and the caller decides what to do.
     *
     * @property reason Short, English-only description of why the frame
     *   was not assembled. Suitable for logging.
     */
    public data class Ignored(
        val reason: String,
    ) : PayloadEvent
}
