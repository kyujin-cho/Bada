/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import java.io.ByteArrayOutputStream
import java.nio.channels.WritableByteChannel

/**
 * Reassembles `PayloadTransferFrame` chunks into complete BYTES and FILE
 * payloads.
 *
 * Sits one layer above [io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel]:
 * the channel hands up decrypted, sequence-validated `OfflineFrame`s; this
 * class consumes the [PayloadTransferFrame]s nested inside
 * `OfflineFrame{type=PAYLOAD_TRANSFER}` and surfaces complete payloads via
 * [PayloadEvent].
 *
 * ### Why "reassembly" is non-trivial
 *
 * Quick Share splits each application-level payload into one or more
 * chunks of a peer-chosen size. Three properties make a naive
 * implementation wrong:
 *
 *  1. **Strict in-order requirement.** Each chunk's `offset` MUST equal
 *     the cumulative byte count we have already received for that
 *     `payload_id`. The wire is reliable (TCP) but a misbehaving or
 *     malicious peer can still send an offset that does not line up;
 *     accepting that would let the peer corrupt the buffer. NearDrop
 *     rejects this with a protocol error and so do we
 *     ([PayloadProtocolException]).
 *  2. **Per-payload state, not per-frame.** Multiple payloads can be
 *     "in flight" simultaneously (Android's transfer-setup phase
 *     interleaves a small BYTES negotiation payload with one or more
 *     large FILE payloads). The `payload_id` keys the buffer/file map.
 *  3. **Android's two-frame BYTES quirk.** Per `PROTOCOL.md`:
 *
 *     > Android does this thing where it sends 2 payload transfer frames
 *     > in succession for each negotiation message: the first contains
 *     > the entire message, the second contains 0 bytes but has the
 *     > `LAST_CHUNK` flag set.
 *
 *     The receiver must accept this without error. Our state machine
 *     handles it naturally: the second frame has `body.size = 0`, its
 *     `offset` equals the buffer's current length (which is the full
 *     payload), and its `LAST_CHUNK` flag triggers finalization. No
 *     special case is needed beyond *not* finalizing on `body.size ==
 *     total_size` alone — finalization MUST be gated on the LAST_CHUNK
 *     flag, not on the byte count.
 *
 * ### Threading model
 *
 * The assembler is **not thread-safe**. Quick Share connections process
 * exactly one inbound `OfflineFrame` at a time (the `SecureChannel`
 * receive coroutine holds a mutex), so all calls to [onPayloadTransfer]
 * are serialized. Adding internal locking would only slow that hot path
 * for no benefit.
 *
 * ### Send-side helpers
 *
 * [PayloadTransferEncoder] is the matching encoder for outbound BYTES
 * (and FILE) payloads, including the two-frame BYTES quirk on send.
 *
 * @param fileDestinationFactory Strategy for opening write channels for
 *   FILE payloads. Default is [TempFileDestinationFactory], suitable for
 *   tests; production wiring on Android replaces this with a factory
 *   that opens a `MediaStore` content URI.
 * @param saneFrameLength Maximum allowed `total_size` for BYTES payloads.
 *   Anything larger is rejected immediately, before any allocation, to
 *   keep a malicious peer from making us reserve hundreds of MiB of
 *   heap. 5 MiB matches Android Quick Share's negotiation message
 *   ceiling — actual file content is sent as FILE payloads, not BYTES.
 */
public class PayloadAssembler(
    private val fileDestinationFactory: FileDestinationFactory = TempFileDestinationFactory(),
    private val saneFrameLength: Int = DEFAULT_SANE_FRAME_LENGTH,
) {
    // ------------------------------------------------------------------
    // Per-payload state
    // ------------------------------------------------------------------

    /**
     * In-flight BYTES payload. Holds the accumulated buffer and the
     * payload's first-seen header (used to keep [PayloadEvent.BytesComplete]
     * faithful to the original `payload_id` even if a future chunk
     * legally omits the header).
     */
    private class BytesState(
        val header: PayloadHeader,
        val buffer: ByteArrayOutputStream,
    )

    /**
     * In-flight FILE payload. Tracks the destination channel, the byte
     * count we have written so far, and the original header.
     */
    private class FileState(
        val header: PayloadHeader,
        val channel: WritableByteChannel,
        var bytesWritten: Long,
    )

    private val bytesInFlight: MutableMap<Long, BytesState> = HashMap()
    private val filesInFlight: MutableMap<Long, FileState> = HashMap()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Feed one `PayloadTransferFrame` to the assembler.
     *
     * @return [PayloadEvent] describing the effect of this frame.
     *   Never `null` — every frame produces some event (an
     *   [PayloadEvent.Ignored] for non-DATA packets, [PayloadEvent.Progress]
     *   mid-stream, or one of the terminal events on the final chunk).
     * @throws PayloadProtocolException if the frame violates a
     *   reassembly invariant. The assembler cleans up any state
     *   associated with the offending `payload_id` before throwing,
     *   so a caller that recovers (e.g. tests) does not see a leaked
     *   buffer or open channel. The connection itself MUST be closed
     *   by the caller — there is no resync.
     */
    @Suppress("ThrowsCount") // One throw per malformed-frame variant; merging would lose error context.
    public fun onPayloadTransfer(frame: PayloadTransferFrame): PayloadEvent {
        // Quick Share defines three packet types: DATA, CONTROL, and
        // PAYLOAD_ACK. Reassembly only applies to DATA. CONTROL frames
        // (PAYLOAD_ERROR, PAYLOAD_CANCELED) and PAYLOAD_ACK frames go
        // straight up to the higher state machine; the assembler reports
        // them as [Ignored] so the caller has a chance to log or react.
        if (frame.packetType != PayloadTransferFrame.PacketType.DATA) {
            return PayloadEvent.Ignored(
                "non-DATA packet_type=${frame.packetType.name} not consumed by reassembler",
            )
        }

        // DATA packets must carry both a header and a chunk. Reject the
        // malformed shapes early so downstream code can rely on both
        // being present.
        if (!frame.hasPayloadHeader()) {
            throw PayloadProtocolException("DATA PayloadTransferFrame missing payload_header")
        }
        if (!frame.hasPayloadChunk()) {
            throw PayloadProtocolException("DATA PayloadTransferFrame missing payload_chunk")
        }
        val header = frame.payloadHeader
        val chunk = frame.payloadChunk
        val payloadId = header.id

        return when (header.type) {
            PayloadHeader.PayloadType.BYTES -> handleBytes(payloadId, header, chunk)
            PayloadHeader.PayloadType.FILE -> handleFile(payloadId, header, chunk)
            // STREAM is part of the proto but not used by Quick Share's
            // file/text transport; UNKNOWN is the proto's default for an
            // unset enum. Either is a protocol error here.
            PayloadHeader.PayloadType.STREAM,
            PayloadHeader.PayloadType.UNKNOWN_PAYLOAD_TYPE,
            null,
            -> throw PayloadProtocolException(
                "Unsupported payload type ${header.type} for payload_id=$payloadId",
            )
        }
    }

    /**
     * Number of payloads currently mid-stream (not yet `LAST_CHUNK`-ed).
     * Visible for diagnostics and for tests that assert state cleanup.
     */
    public val inFlightPayloadCount: Int
        get() = bytesInFlight.size + filesInFlight.size

    /**
     * Drop all in-flight state. Closes any open file channels (best-effort —
     * an `IOException` on close is suppressed so a single failing channel
     * does not strand the others). Use during connection teardown to
     * release resources without leaking partial files.
     */
    public fun reset() {
        bytesInFlight.clear()
        for (state in filesInFlight.values) {
            runCatching { state.channel.close() }
        }
        filesInFlight.clear()
    }

    // ------------------------------------------------------------------
    // BYTES path
    // ------------------------------------------------------------------

    /**
     * Apply one chunk of a BYTES payload.
     *
     * BYTES payloads carry negotiation messages (paired-key encryption,
     * introduction frames, the small text-share protobuf). They are
     * always small — capped at [saneFrameLength] — so we buffer in heap.
     *
     * The Android two-frame quirk is handled here implicitly: the second
     * frame has `body.size == 0`, `offset == buffer.size`, and the
     * LAST_CHUNK flag set. The offset check passes, the empty append is
     * a no-op, and the flag check finalizes.
     */
    @Suppress("ThrowsCount") // Each throw documents a distinct BYTES-reassembly invariant.
    private fun handleBytes(
        payloadId: Long,
        header: PayloadHeader,
        chunk: PayloadChunk,
    ): PayloadEvent {
        val state =
            bytesInFlight[payloadId] ?: run {
                // First chunk for this payload: validate total_size BEFORE
                // any allocation (defense-in-depth against a peer that
                // claims a 4 GiB BYTES "negotiation" message).
                if (header.totalSize > saneFrameLength) {
                    throw PayloadProtocolException(
                        "BYTES payload_id=$payloadId total_size=${header.totalSize} " +
                            "exceeds saneFrameLength=$saneFrameLength",
                    )
                }
                if (header.totalSize < 0) {
                    throw PayloadProtocolException(
                        "BYTES payload_id=$payloadId has negative total_size=${header.totalSize}",
                    )
                }
                // initialCapacity = total_size: most BYTES payloads
                // arrive in one frame, so this avoids the doubling
                // re-allocation. Cap at saneFrameLength (already enforced
                // above) so we cannot pre-allocate beyond the limit.
                BytesState(
                    header = header,
                    buffer = ByteArrayOutputStream(header.totalSize.toInt().coerceAtLeast(0)),
                ).also { bytesInFlight[payloadId] = it }
            }

        val bufferLen = state.buffer.size().toLong()
        if (chunk.offset != bufferLen) {
            // Cleanup before throw — a partial buffer would otherwise
            // pin memory until the connection is torn down.
            bytesInFlight.remove(payloadId)
            throw PayloadProtocolException(
                "BYTES payload_id=$payloadId chunk offset=${chunk.offset} " +
                    "does not match buffer size=$bufferLen",
            )
        }

        // Re-validate total_size against the running buffer+body length
        // before we write. This protects the receiver if a peer were to
        // grow `total_size` mid-stream or send more body than declared.
        val body = chunk.body
        val newLen = bufferLen + body.size().toLong()
        if (newLen > state.header.totalSize) {
            bytesInFlight.remove(payloadId)
            throw PayloadProtocolException(
                "BYTES payload_id=$payloadId chunk would extend buffer to $newLen, " +
                    "exceeding declared total_size=${state.header.totalSize}",
            )
        }

        if (!body.isEmpty) {
            // ByteString.writeTo is the most direct path; it iterates
            // internal segments and writes to the OutputStream without
            // an intermediate copy.
            body.writeTo(state.buffer)
        }

        return if ((chunk.flags and LAST_CHUNK_FLAG) != 0) {
            // LAST_CHUNK arrived. Hand the buffer up and drop state.
            // Verify the assembled byte count matches what was advertised:
            // a LAST_CHUNK that arrives short would otherwise leave the
            // higher layer with a truncated negotiation message.
            val finalLen = state.buffer.size().toLong()
            if (finalLen != state.header.totalSize) {
                bytesInFlight.remove(payloadId)
                throw PayloadProtocolException(
                    "BYTES payload_id=$payloadId LAST_CHUNK at $finalLen bytes " +
                        "differs from declared total_size=${state.header.totalSize}",
                )
            }
            bytesInFlight.remove(payloadId)
            PayloadEvent.BytesComplete(payloadId, state.buffer.toByteArray())
        } else {
            PayloadEvent.Progress(
                payloadId = payloadId,
                bytesReceived = state.buffer.size().toLong(),
                totalSize = state.header.totalSize,
                type = PayloadHeader.PayloadType.BYTES,
            )
        }
    }

    // ------------------------------------------------------------------
    // FILE path
    // ------------------------------------------------------------------

    /**
     * Apply one chunk of a FILE payload.
     *
     * FILE payloads can be GB-scale. We never buffer in memory — every
     * chunk's body is written straight through to the
     * [WritableByteChannel] that the [fileDestinationFactory] returned
     * for this payload. The factory is invoked exactly once, on the
     * first chunk; we bail out if the per-chunk offset disagrees with
     * the running write count, the same way NearDrop does.
     */
    @Suppress(
        "ThrowsCount", // Each throw documents a distinct FILE-reassembly invariant.
        "NestedBlockDepth", // Streaming-write loop + close cleanup is inherently nested.
    )
    private fun handleFile(
        payloadId: Long,
        header: PayloadHeader,
        chunk: PayloadChunk,
    ): PayloadEvent {
        val state =
            filesInFlight[payloadId] ?: run {
                if (header.totalSize < 0) {
                    throw PayloadProtocolException(
                        "FILE payload_id=$payloadId has negative total_size=${header.totalSize}",
                    )
                }
                // The factory may throw — if it does, we have not
                // registered any state yet, so there is nothing to
                // clean up.
                val channel = fileDestinationFactory.open(header)
                FileState(header, channel, bytesWritten = 0L).also {
                    filesInFlight[payloadId] = it
                }
            }

        val written = state.bytesWritten
        if (chunk.offset != written) {
            // Match NearDrop's cleanup behavior: drop the in-flight
            // record but also close the destination channel so the
            // partial file is at least flushed.
            filesInFlight.remove(payloadId)
            runCatching { state.channel.close() }
            throw PayloadProtocolException(
                "FILE payload_id=$payloadId chunk offset=${chunk.offset} " +
                    "does not match bytes written=$written",
            )
        }

        val body = chunk.body
        val bodySize = body.size()
        val newWritten = written + bodySize.toLong()
        if (newWritten > state.header.totalSize) {
            filesInFlight.remove(payloadId)
            runCatching { state.channel.close() }
            throw PayloadProtocolException(
                "FILE payload_id=$payloadId chunk would write past total_size=" +
                    "${state.header.totalSize} (current=$written, body=$bodySize)",
            )
        }

        if (bodySize > 0) {
            // Copy through ByteBuffer: ByteString does not expose a
            // zero-copy `WritableByteChannel.write` directly, but its
            // `asReadOnlyByteBufferList()` lets us hand the channel an
            // already-prepared buffer. For most chunks the list has a
            // single segment; we still loop in case a peer sends a
            // segmented body.
            try {
                for (segment in body.asReadOnlyByteBufferList()) {
                    while (segment.hasRemaining()) {
                        state.channel.write(segment)
                    }
                }
            } catch (e: java.io.IOException) {
                filesInFlight.remove(payloadId)
                runCatching { state.channel.close() }
                throw PayloadProtocolException(
                    "FILE payload_id=$payloadId write to destination channel failed",
                    e,
                )
            }
            state.bytesWritten = newWritten
        }

        return if ((chunk.flags and LAST_CHUNK_FLAG) != 0) {
            // LAST_CHUNK: the file is complete. Validate the running
            // count matches the declared total_size and close the
            // channel. The factory does not own close; we do.
            if (state.bytesWritten != state.header.totalSize) {
                filesInFlight.remove(payloadId)
                runCatching { state.channel.close() }
                throw PayloadProtocolException(
                    "FILE payload_id=$payloadId LAST_CHUNK at ${state.bytesWritten} bytes " +
                        "differs from declared total_size=${state.header.totalSize}",
                )
            }
            filesInFlight.remove(payloadId)
            try {
                state.channel.close()
            } catch (e: java.io.IOException) {
                throw PayloadProtocolException(
                    "FILE payload_id=$payloadId failed to close destination channel",
                    e,
                )
            }
            PayloadEvent.FileComplete(payloadId, state.header, state.bytesWritten)
        } else {
            PayloadEvent.Progress(
                payloadId = payloadId,
                bytesReceived = state.bytesWritten,
                totalSize = state.header.totalSize,
                type = PayloadHeader.PayloadType.FILE,
            )
        }
    }

    public companion object {
        /**
         * `LAST_CHUNK` bit in `PayloadChunk.flags`, copied from the proto
         * enum so the assembler's hot path does not pay a name-resolution
         * cost on every chunk.
         */
        public const val LAST_CHUNK_FLAG: Int = 0x1

        /**
         * Default `total_size` cap for BYTES payloads. Anchored to
         * [FramedConnection.SANE_FRAME_LENGTH] so the assembler-level cap
         * never exceeds what the transport will even let onto the wire:
         * a single decrypted `OfflineFrame` cannot be larger than the
         * frame size cap, so a BYTES payload that fits in one chunk
         * cannot exceed it either, and even multi-chunk BYTES is
         * naturally bounded by what the higher protocol uses BYTES for
         * (small negotiation messages).
         *
         * Exposed publicly so tests can dial it down to drive the
         * oversized-BYTES rejection path without allocating megabytes of
         * test vector bytes.
         */
        public const val DEFAULT_SANE_FRAME_LENGTH: Int = FramedConnection.SANE_FRAME_LENGTH
    }
}
