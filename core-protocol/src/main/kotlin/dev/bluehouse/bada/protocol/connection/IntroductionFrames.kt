/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import dev.bluehouse.bada.protocol.sharing.IntroductionFrame

/**
 * Build the outgoing [IntroductionFrame] from the supplied [files]
 * and [texts] lists.
 *
 * Pulled out of `OutboundConnectionDriver` so the wire shape can be
 * unit-tested without spinning up a full loopback connection. We
 * populate `file_metadata` and `text_metadata` here; Quick Share also
 * supports Wi-Fi / app metadata, which the outbound path does not send.
 *
 * Two fields beyond the obvious ones matter for stock Quick Share
 * interop, both verified against Samsung One UI 8.0.5:
 *
 * 1. **`FileMetadata.id`** (proto field 6) — the attachment uuid.
 *    Stock Quick Share's receive-side bookkeeping reconciles each
 *    inbound `FILE` payload back to a `FileMetadata` via this id; if
 *    we leave it at the proto default (0) the receiver discards the
 *    payload and shows "couldn't receive file" with no further log
 *    beyond a `NULL_MESSAGE` at the medium layer. We reuse
 *    `FileSource.payloadId` (already a non-zero unique int64), which
 *    satisfies "unique across all attachments" while keeping the
 *    metadata→payload mapping bijective.
 * 2. **`IntroductionFrame.use_case = NEARBY_SHARE`** — stock senders
 *    annotate Introductions with the sharing-use-case enum so the
 *    receiver picker / UI knows the transfer is a regular Quick Share
 *    send (vs. Remote Copy / unknown). Without it Samsung's receiver
 *    may treat the Introduction as malformed and fall through to a
 *    default path that does not register the attachment.
 *
 * Text items (#301) follow the same id rule: `TextMetadata.id` is the
 * attachment uuid next to `payload_id`, so we fill it with
 * [TextSource.payloadId] just like `FileMetadata.id`. NearDrop leaves it
 * unset, but Samsung keys file attachments on the equivalent field and
 * there is no reason to expect text bookkeeping to differ.
 */
internal fun buildIntroductionFrame(
    files: List<FileSource>,
    texts: List<TextSource> = emptyList(),
): IntroductionFrame {
    val builder = IntroductionFrame.newBuilder()
    for (f in files) {
        val md =
            Protocol.FileMetadata
                .newBuilder()
                .setName(f.name)
                .setPayloadId(f.payloadId)
                .setSize(f.size)
                .setMimeType(f.mimeType)
                .setType(mimeTypeToFileType(f.mimeType))
                .setId(f.payloadId)
        // `parent_folder` (proto field 7) is the receiver's hint to
        // reconstruct nested directory layouts on disk. Quick Share's
        // receiver implicitly creates intermediate folders when it
        // writes the file, so we only emit the field when non-empty —
        // top-level attachments stay byte-for-byte compatible with the
        // pre-#38 introduction wire shape.
        if (f.parentFolder.isNotEmpty()) {
            md.setParentFolder(f.parentFolder)
        }
        builder.addFileMetadata(md.build())
    }
    for (t in texts) {
        builder.addTextMetadata(
            Protocol.TextMetadata
                .newBuilder()
                .setTextTitle(t.title)
                .setType(textKindToTextType(t.kind))
                .setPayloadId(t.payloadId)
                .setSize(t.size)
                .setId(t.payloadId)
                .build(),
        )
    }
    builder.setUseCase(Protocol.IntroductionFrame.SharingUseCase.NEARBY_SHARE)
    return builder.build()
}

/**
 * Map the user-provided MIME type onto the proto's `Type` enum.
 * Quick Share's receiver UI uses this to choose an icon and for
 * autoplay heuristics; getting it slightly wrong is harmless.
 */
internal fun mimeTypeToFileType(mimeType: String): Protocol.FileMetadata.Type =
    when {
        mimeType.startsWith("image/") -> Protocol.FileMetadata.Type.IMAGE
        mimeType.startsWith("video/") -> Protocol.FileMetadata.Type.VIDEO
        mimeType.startsWith("audio/") -> Protocol.FileMetadata.Type.AUDIO
        mimeType == "application/vnd.android.package-archive" -> Protocol.FileMetadata.Type.ANDROID_APP
        else -> Protocol.FileMetadata.Type.UNKNOWN
    }

/**
 * Map a [TransferItem.Text.Kind] onto the proto's `TextMetadata.Type`.
 * The inverse of the receive-side mapping in
 * [TransferMetadata.fromIntroductionFrame].
 */
internal fun textKindToTextType(kind: TransferItem.Text.Kind): Protocol.TextMetadata.Type =
    when (kind) {
        TransferItem.Text.Kind.PLAIN -> Protocol.TextMetadata.Type.TEXT
        TransferItem.Text.Kind.URL -> Protocol.TextMetadata.Type.URL
        TransferItem.Text.Kind.ADDRESS -> Protocol.TextMetadata.Type.ADDRESS
        TransferItem.Text.Kind.PHONE_NUMBER -> Protocol.TextMetadata.Type.PHONE_NUMBER
    }
