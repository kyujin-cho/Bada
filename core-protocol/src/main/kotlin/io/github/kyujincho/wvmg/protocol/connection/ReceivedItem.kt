/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader

/**
 * A successfully received Quick Share payload, surfaced when an
 * [InboundConnection] completes.
 *
 * Mirrors the [TransferItem] hierarchy on the inbound side -- where
 * [TransferItem] describes what the peer announced, [ReceivedItem]
 * describes what actually arrived. The two are separated because the
 * payloads do not carry the same fields:
 *
 *  - A FILE arrival exposes the [PayloadHeader] the peer sent so the
 *    higher layer (`:service-android`'s `MediaStore` writer) can use
 *    `file_name`, `total_size`, and `last_modified_timestamp_millis`
 *    to finalize the on-disk entry. We do NOT carry a `Path` or `Uri`
 *    here -- `:core-protocol` is platform-independent and the channel
 *    has already been closed by [io.github.kyujincho.wvmg.protocol.payload.PayloadAssembler].
 *  - A BYTES arrival carries the assembled bytes directly; small
 *    enough (cap ~5 MiB) that holding them in heap is fine.
 */
public sealed interface ReceivedItem {
    /** The Quick Share `payload_id` this item arrived under. */
    public val payloadId: Long

    /**
     * A successfully received FILE payload.
     *
     * The [io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory]
     * the caller passed into [InboundConnection.run] is the only thing
     * that knows where the bytes ended up; this event simply confirms
     * that `bytesWritten == header.total_size` and that the channel
     * closed cleanly.
     *
     * @property header The full `PayloadHeader` proto. The destination
     *   factory's caller correlates this header (typically by
     *   `payload_id`) with the [TransferItem.File] entry in the
     *   announced [TransferMetadata] to look up the on-disk Uri.
     * @property bytesWritten Always equals `header.total_size` on a
     *   successful completion -- repeated here so callers do not have
     *   to re-read the proto field.
     */
    public data class File(
        override val payloadId: Long,
        val header: PayloadHeader,
        val bytesWritten: Long,
    ) : ReceivedItem

    /**
     * A successfully received BYTES (text) payload.
     *
     * The bytes arrive UTF-8 encoded for plain text / URL / address /
     * phone-number content; we surface them as a [ByteArray] rather
     * than a `String` because Quick Share does not formally constrain
     * the encoding (a sender may have shipped non-UTF-8 bytes for a
     * URL). Callers decode based on the matching [TransferItem.Text.kind].
     *
     * @property data Raw assembled bytes. Always exactly the
     *   advertised `total_size` of the announcing payload.
     */
    public data class Text(
        override val payloadId: Long,
        val data: ByteArray,
    ) : ReceivedItem {
        // `ByteArray` uses identity equals by default, which is wrong
        // for a value-type result. Override so consumers and tests can
        // assert by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Text) return false
            return payloadId == other.payloadId && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * payloadId.hashCode() + data.contentHashCode()
    }
}

/**
 * Final outcome of an [InboundConnection.run] invocation.
 *
 * The same information is also published on
 * [InboundConnection.state] -- this return value is for callers that
 * prefer a one-shot suspend signature ("await the connection finishing")
 * to a `StateFlow` collector.
 *
 * Sealed because outcomes are an exhaustive small set; pattern-matching
 * with `when` gives the caller compile-time exhaustiveness on the
 * success / reject / cancel / fail axes.
 */
public sealed interface InboundResult {
    /**
     * Every announced item arrived in full. The `Disconnection` frame
     * has already been sent and the socket closed.
     *
     * @property items Successfully received items, in announcement order.
     */
    public data class Completed(
        val items: List<ReceivedItem>,
    ) : InboundResult

    /**
     * The user rejected the transfer in the consent sheet. The
     * `ConnectionResponseFrame{REJECT}` and `Disconnection` were sent
     * before the socket closed.
     */
    public object Rejected : InboundResult

    /**
     * The transfer was cancelled. See [CancelCause] for the origin.
     *
     * @property cause Whether the cancel came from the local user or
     *   the peer.
     */
    public data class Cancelled(
        val cause: CancelCause,
    ) : InboundResult

    /**
     * The transfer failed before completing. `reason` is a short,
     * English-only label suitable for logging.
     *
     * @property reason Short failure description.
     */
    public data class Failed(
        val reason: String,
    ) : InboundResult
}
