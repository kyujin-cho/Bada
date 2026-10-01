/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

/**
 * Sender-side description of a single text item (a plain string, URL,
 * address or phone number) to ship over an [OutboundConnection].
 *
 * The text counterpart of [FileSource]. Quick Share announces text in
 * the Introduction's `text_metadata[]` and then sends the UTF-8 body as
 * a BYTES payload under the announced `payload_id` (issue #301; the
 * receive side has handled this shape since #40).
 *
 * Classifying the text (URL vs phone number vs plain) and picking a
 * title is left to the caller: the Android layer has the platform
 * pattern helpers, and `:core-protocol` stays free of `android.*`.
 *
 * The whole body travels in a single BYTES chunk. Share-sheet text is
 * bounded by the Binder transaction limit (about 1 MB), which keeps the
 * encrypted frame well under
 * [dev.bluehouse.bada.protocol.transport.FramedConnection.SANE_FRAME_LENGTH].
 *
 * @property text The text body exactly as it should arrive on the peer.
 * @property title Short label the receiver shows in its consent UI.
 *   Maps to `TextMetadata.text_title`. NearDrop and Chrome's Nearby
 *   Share use the host for URLs and a truncated preview otherwise.
 * @property kind What the receiver should offer to do with the text
 *   (open a browser for [TransferItem.Text.Kind.URL], dial for
 *   [TransferItem.Text.Kind.PHONE_NUMBER], ...). Maps to
 *   `TextMetadata.type`.
 * @property payloadId The Quick Share `payload_id` used both in the
 *   introduction's text metadata entry and on the BYTES payload. Same
 *   contract as [FileSource.payloadId]: fresh, positive, and unique
 *   across every file and text item of one transfer.
 */
public class TextSource(
    public val text: String,
    public val title: String,
    public val kind: TransferItem.Text.Kind,
    public val payloadId: Long,
) {
    /** The UTF-8 body sent as the BYTES payload. */
    internal val bytes: ByteArray = text.encodeToByteArray()

    /** Byte length of the UTF-8 body. Maps to `TextMetadata.size`. */
    public val size: Long = bytes.size.toLong()
}
