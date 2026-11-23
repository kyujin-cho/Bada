/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader

/**
 * Fill an empty or missing `PayloadHeader.file_name` with the name the
 * sender announced for that payload in its `IntroductionFrame`.
 *
 * Two senders put the real name only in `FileMetadata.name`:
 *  - ChromeOS Nearby Share (#325) sends `file_name = ""`. Chromium builds
 *    each file payload as `Payload(id, InputFile(...))`, Nearby
 *    Connections takes the payload's file name from
 *    `InputFile::GetFilePath()`, which Chromium leaves empty ("File path
 *    is not supported"), and `OutgoingPayloadManager` copies that name
 *    into the header.
 *  - NearDrop on macOS (#324) never sets `file_name` on its FILE payload
 *    headers (`OutboundNearbyConnection.swift`).
 *
 * Stock GMS receivers handle this the same way: they spool the payload
 * under a placeholder and name it from the introduction. Without the
 * rewrite the destination factory saved the file as an extension-less
 * `received_file`, so it never showed up as an image and the completion
 * sheet had no preview.
 *
 * The rewrite applies only when all three hold:
 *  - the header's type is FILE,
 *  - the header's `file_name` is empty or absent, and
 *  - the introduction announced a non-empty name for the header's id.
 *
 * Every other frame is returned as the same instance. Senders that fill
 * `file_name` (stock Android, Samsung, Quick Share for Windows, Bada)
 * therefore take exactly the old path, even when their
 * introduction name differs from the header name.
 *
 * Callers rewrite every FILE frame of the payload, not only the first,
 * so the header the assembler sees stays consistent across chunks.
 *
 * @param announcedFileNames `FileMetadata.payload_id` to
 *   `FileMetadata.name`, as announced in the introduction.
 * @return this frame, or a copy whose header carries the announced name.
 */
internal fun PayloadTransferFrame.withAnnouncedFileName(announcedFileNames: Map<Long, String>): PayloadTransferFrame {
    val announcedName = announcedFileNames[payloadHeader.id]
    return if (!hasUnnamedFileHeader() || announcedName.isNullOrEmpty()) {
        this
    } else {
        toBuilder()
            .setPayloadHeader(payloadHeader.toBuilder().setFileName(announcedName))
            .build()
    }
}

/** True for a FILE header whose `file_name` is empty or absent. */
private fun PayloadTransferFrame.hasUnnamedFileHeader(): Boolean =
    hasPayloadHeader() &&
        payloadHeader.type == PayloadHeader.PayloadType.FILE &&
        payloadHeader.fileName.isEmpty()
