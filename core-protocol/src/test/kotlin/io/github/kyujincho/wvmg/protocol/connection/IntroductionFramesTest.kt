/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel

/**
 * Regression guard for the Galaxy Quick Share interop fields the
 * outbound `IntroductionFrame` carries. These were silently absent
 * before the bugfix landed and Samsung One UI 8.0.5 quietly discarded
 * the announced FILE attachment without surfacing a parse error —
 * exactly the failure mode that wasted three repro iterations.
 */
class IntroductionFramesTest {
    private fun fileSource(
        name: String,
        size: Long,
        mimeType: String,
        payloadId: Long,
    ): FileSource =
        FileSource(
            name = name,
            size = size,
            mimeType = mimeType,
            lastModifiedTimestampMillis = 0L,
            payloadId = payloadId,
            open = { Channels.newChannel(ByteArrayInputStream(ByteArray(0))) as ReadableByteChannel },
        )

    @Test
    fun `IntroductionFrame carries use_case = NEARBY_SHARE`() {
        val intro =
            buildIntroductionFrame(
                listOf(fileSource("a.jpeg", 1024, "image/jpeg", payloadId = 7L)),
            )
        assertThat(intro.useCase).isEqualTo(Protocol.IntroductionFrame.SharingUseCase.NEARBY_SHARE)
    }

    @Test
    fun `each FileMetadata carries id matching its payload_id`() {
        val files =
            listOf(
                fileSource("photo.jpeg", 89_482, "image/jpeg", payloadId = 123L),
                fileSource("doc.pdf", 4_096, "application/pdf", payloadId = 456L),
                fileSource("clip.mp4", 1_048_576, "video/mp4", payloadId = 789L),
            )
        val intro = buildIntroductionFrame(files)
        assertThat(intro.fileMetadataCount).isEqualTo(files.size)
        for ((i, expected) in files.withIndex()) {
            val md = intro.getFileMetadata(i)
            // Both `id` and `payload_id` must be set and equal: stock
            // Quick Share keys its receive-side bookkeeping by `id`;
            // leaving the proto default (0) makes Samsung discard the
            // attachment.
            assertThat(md.id).isEqualTo(expected.payloadId)
            assertThat(md.payloadId).isEqualTo(expected.payloadId)
            assertThat(md.id).isNotEqualTo(0L)
        }
    }

    @Test
    fun `FileMetadata derives Type enum from mime type`() {
        val files =
            listOf(
                fileSource("a.jpg", 1, "image/jpeg", 1L),
                fileSource("b.mp4", 1, "video/mp4", 2L),
                fileSource("c.mp3", 1, "audio/mpeg", 3L),
                fileSource("d.apk", 1, "application/vnd.android.package-archive", 4L),
                fileSource("e.bin", 1, "application/octet-stream", 5L),
            )
        val intro = buildIntroductionFrame(files)
        assertThat(intro.getFileMetadata(0).type).isEqualTo(Protocol.FileMetadata.Type.IMAGE)
        assertThat(intro.getFileMetadata(1).type).isEqualTo(Protocol.FileMetadata.Type.VIDEO)
        assertThat(intro.getFileMetadata(2).type).isEqualTo(Protocol.FileMetadata.Type.AUDIO)
        assertThat(intro.getFileMetadata(3).type).isEqualTo(Protocol.FileMetadata.Type.ANDROID_APP)
        assertThat(intro.getFileMetadata(4).type).isEqualTo(Protocol.FileMetadata.Type.UNKNOWN)
    }
}
