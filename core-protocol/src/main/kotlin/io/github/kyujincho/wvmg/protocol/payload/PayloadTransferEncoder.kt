/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadChunk
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

/**
 * Encoder side of the `PayloadTransferFrame` exchange.
 *
 * Where [PayloadAssembler] consumes inbound chunks, [PayloadTransferEncoder]
 * builds outbound chunks. Splitting send and receive into separate types
 * keeps each focused — the assembler's hot path is concerned with
 * mutation of in-flight state, while the encoder is purely functional.
 *
 * Each helper returns one or more `OfflineFrame`s already wrapped in
 * `OfflineFrame{version=V1, v1.type=PAYLOAD_TRANSFER, v1.payload_transfer=...}`.
 * The caller's send loop is then a one-liner per frame:
 *
 * ```
 * for (frame in PayloadTransferEncoder.encodeBytesPayload(id, data)) {
 *     secureChannel.sendOfflineFrame(frame)
 * }
 * ```
 *
 * No internal state is held — encoder calls are idempotent.
 */
public object PayloadTransferEncoder {
    /**
     * Default chunk size for outbound FILE payloads. 512 KiB is the
     * size NearDrop and Android's Quick Share both use; smaller chunks
     * waste round-trips on the SecureMessage envelope, larger chunks
     * eat into the TCP send buffer and stall progress reporting.
     */
    public const val DEFAULT_FILE_CHUNK_SIZE: Int = 512 * 1024

    /**
     * Encode an outbound BYTES payload.
     *
     * Returns exactly **two** `OfflineFrame`s per the Android Quick Share
     * convention documented in NearDrop's `PROTOCOL.md`:
     *
     *   1. **Data frame**: full body, `offset = 0`, `flags = 0`. The
     *      receiver buffers the body but does NOT finalize, because
     *      `LAST_CHUNK` is not yet set.
     *   2. **Terminator frame**: empty body, `offset = data.size`,
     *      `flags = LAST_CHUNK`. This is the famous "second frame with
     *      0 bytes" — every receiver in the Quick Share ecosystem
     *      tolerates it because it MUST tolerate Android, and Android
     *      always sends it.
     *
     * NearDrop, the macOS reference implementation, does the same in
     * `sendBytesPayload`. We follow that pattern here for compatibility:
     * peers that branch on "is this Android?" by counting frames will
     * still recognize us.
     *
     * @param payloadId The negotiation `payload_id`. Each higher-level
     *   message MUST use a fresh id; reusing an id while the receiver
     *   still has the old one in its buffer would cause an offset
     *   mismatch on the receiver side.
     * @param data Body bytes. May be empty — a zero-length BYTES payload
     *   still emits two frames (the data frame is empty + flags=0; the
     *   terminator is empty + flags=LAST_CHUNK + offset=0). Both pass
     *   the assembler's offset check.
     * @return List of size 2 in the order (data, terminator). The caller
     *   sends them sequentially through the same SecureChannel without
     *   interleaving any other writes.
     */
    public fun encodeBytesPayload(
        payloadId: Long,
        data: ByteArray,
    ): List<OfflineFrame> {
        val totalSize = data.size.toLong()
        val header =
            PayloadHeader
                .newBuilder()
                .setId(payloadId)
                .setType(PayloadHeader.PayloadType.BYTES)
                .setTotalSize(totalSize)
                .setIsSensitive(false)
                .build()

        val dataChunk =
            PayloadChunk
                .newBuilder()
                .setFlags(0)
                .setOffset(0L)
                .setBody(ByteString.copyFrom(data))
                .build()

        val terminatorChunk =
            PayloadChunk
                .newBuilder()
                .setFlags(PayloadAssembler.LAST_CHUNK_FLAG)
                .setOffset(totalSize)
                .setBody(ByteString.EMPTY)
                .build()

        return listOf(
            wrap(header, dataChunk),
            wrap(header, terminatorChunk),
        )
    }

    /**
     * Encode an outbound FILE payload.
     *
     * Reads bytes from [source] in chunks of [chunkSize] and emits one
     * `OfflineFrame` per chunk. The last chunk carries the `LAST_CHUNK`
     * flag.
     *
     * Unlike the BYTES encoder, the FILE encoder does **not** emit a
     * trailing zero-byte terminator. The Android two-frame quirk is
     * specific to BYTES (negotiation messages); FILE payloads end with
     * the last data chunk having `LAST_CHUNK` set directly. NearDrop's
     * file send path does the same.
     *
     * The function is implemented as a [Sequence] so the caller's send
     * loop reads exactly one chunk at a time and pushes it through the
     * SecureChannel, never holding the entire file in memory.
     *
     * @param payloadId The file's `payload_id`. Reusing an id mid-stream
     *   would corrupt the receiver, same caution as for BYTES.
     * @param fileName Used as `PayloadHeader.file_name`. Receivers
     *   typically use this for the on-disk name (after sanitization).
     * @param totalSize Bytes available to read from [source]. The encoder
     *   trusts the caller; if [source] yields fewer bytes the last
     *   chunk will simply be smaller, but the receiver will still
     *   reject the payload as undersized.
     * @param source A [ReadableByteChannel] positioned at the start of
     *   the file. The encoder consumes bytes serially and does not
     *   close the channel — the caller does.
     * @param chunkSize Bytes per chunk. Default 512 KiB.
     * @param lastModifiedTimestampMillis Mirror onto the proto for the
     *   receiver's metadata pass-through.
     * @param parentFolder Optional parent folder hint — Quick Share
     *   exposes this for archive-style multi-file transfers.
     */
    @Suppress("LongParameterList") // The PayloadHeader has many optional fields; we mirror them faithfully.
    public fun encodeFilePayload(
        payloadId: Long,
        fileName: String,
        totalSize: Long,
        source: ReadableByteChannel,
        chunkSize: Int = DEFAULT_FILE_CHUNK_SIZE,
        lastModifiedTimestampMillis: Long = 0L,
        parentFolder: String = "",
    ): Sequence<OfflineFrame> {
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        require(totalSize >= 0) { "totalSize must be non-negative, got $totalSize" }

        val header =
            PayloadHeader
                .newBuilder()
                .setId(payloadId)
                .setType(PayloadHeader.PayloadType.FILE)
                .setTotalSize(totalSize)
                .setFileName(fileName)
                .setParentFolder(parentFolder)
                .setLastModifiedTimestampMillis(lastModifiedTimestampMillis)
                .setIsSensitive(false)
                .build()

        return sequence {
            // Special case: a zero-byte file must still produce one
            // frame (with body empty and LAST_CHUNK set), otherwise the
            // receiver never sees a terminating chunk.
            if (totalSize == 0L) {
                yield(
                    wrap(
                        header,
                        PayloadChunk
                            .newBuilder()
                            .setFlags(PayloadAssembler.LAST_CHUNK_FLAG)
                            .setOffset(0L)
                            .setBody(ByteString.EMPTY)
                            .build(),
                    ),
                )
                return@sequence
            }

            val readBuffer = ByteBuffer.allocate(chunkSize)
            var offset = 0L
            while (offset < totalSize) {
                readBuffer.clear()
                // Cap the read at remaining bytes so we never overshoot
                // the advertised total_size, even if the source channel
                // would yield more bytes.
                val remaining = (totalSize - offset).coerceAtMost(chunkSize.toLong()).toInt()
                readBuffer.limit(remaining)

                var read = 0
                while (read < remaining) {
                    val n = source.read(readBuffer)
                    if (n < 0) break
                    read += n
                }
                readBuffer.flip()

                val isLast = offset + read >= totalSize
                val chunk =
                    PayloadChunk
                        .newBuilder()
                        .setFlags(if (isLast) PayloadAssembler.LAST_CHUNK_FLAG else 0)
                        .setOffset(offset)
                        .setBody(ByteString.copyFrom(readBuffer))
                        .build()
                yield(wrap(header, chunk))
                offset += read.toLong()
                // If the source ran out before we hit total_size we still
                // emit what we read; the receiver will surface the
                // mismatch as a protocol error and we let it.
                if (read == 0) break
            }
        }
    }

    /**
     * Build a fully-typed `OfflineFrame{V1, PAYLOAD_TRANSFER, ...}` from a
     * header+chunk pair. Centralizes the wrapping so individual encoder
     * functions do not duplicate the boilerplate.
     */
    private fun wrap(
        header: PayloadHeader,
        chunk: PayloadChunk,
    ): OfflineFrame {
        val transfer =
            PayloadTransferFrame
                .newBuilder()
                .setPacketType(PayloadTransferFrame.PacketType.DATA)
                .setPayloadHeader(header)
                .setPayloadChunk(chunk)
                .build()
        return OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.PAYLOAD_TRANSFER)
                    .setPayloadTransfer(transfer),
            ).build()
    }
}
