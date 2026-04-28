/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import io.github.kyujincho.wvmg.protocol.sharing.IntroductionFrame

/**
 * Build the outgoing [IntroductionFrame] from the supplied [files]
 * list.
 *
 * Pulled out of `OutboundConnectionDriver` so the wire shape can be
 * unit-tested without spinning up a full loopback connection. We only
 * populate `file_metadata` here — Quick Share also supports text /
 * Wi-Fi / app metadata, but the outbound path is currently scoped to
 * file transfers only.
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
 */
internal fun buildIntroductionFrame(files: List<FileSource>): IntroductionFrame {
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
