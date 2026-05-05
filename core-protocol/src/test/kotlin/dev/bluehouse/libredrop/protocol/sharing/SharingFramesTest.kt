/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.sharing

import com.google.android.gms.nearby.sharing.Protocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom

/**
 * Tests for [SharingFrames] — the builders / parser for `Sharing.Nearby.Frame`.
 *
 * The FSM treats parsing as fatal-on-failure, so the parse-failure path
 * is covered here as well as the round-trip happy path. Builder defaults
 * are also pinned so that a regression that drops `version = V1` (which
 * stock Quick Share peers reject silently) shows up loudly.
 */
class SharingFramesTest {
    /**
     * Deterministic [SecureRandom] so [SharingFrames.pairedKeyEncryption]
     * produces a fixed byte pattern in tests. We only use this for byte
     * length / non-empty assertions; we never compare against magic
     * values.
     */
    private fun deterministicRandom(): SecureRandom = SecureRandom("test-seed".toByteArray())

    @Test
    fun `pairedKeyEncryption fills 6 bytes of secret_id_hash and 72 bytes of signed_data by default`() {
        val frame = SharingFrames.pairedKeyEncryption(secureRandom = deterministicRandom())

        assertThat(frame.version).isEqualTo(SharingFrameVersion.V1)
        assertThat(frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_ENCRYPTION)
        assertThat(frame.v1.hasPairedKeyEncryption()).isTrue()
        assertThat(
            frame.v1.pairedKeyEncryption.secretIdHash
                .size(),
        ).isEqualTo(SharingFrames.DEFAULT_SECRET_ID_HASH_LENGTH)
        assertThat(
            frame.v1.pairedKeyEncryption.signedData
                .size(),
        ).isEqualTo(SharingFrames.DEFAULT_SIGNED_DATA_LENGTH)
    }

    @Test
    fun `pairedKeyEncryption respects custom byte lengths`() {
        val frame =
            SharingFrames.pairedKeyEncryption(
                secureRandom = deterministicRandom(),
                secretIdHashLength = 10,
                signedDataLength = 100,
            )
        assertThat(
            frame.v1.pairedKeyEncryption.secretIdHash
                .size(),
        ).isEqualTo(10)
        assertThat(
            frame.v1.pairedKeyEncryption.signedData
                .size(),
        ).isEqualTo(100)
    }

    @Test
    fun `pairedKeyEncryption rejects negative byte lengths`() {
        assertThrows<IllegalArgumentException> {
            SharingFrames.pairedKeyEncryption(secretIdHashLength = -1)
        }
        assertThrows<IllegalArgumentException> {
            SharingFrames.pairedKeyEncryption(signedDataLength = -1)
        }
    }

    @Test
    fun `pairedKeyResult defaults to UNABLE`() {
        val frame = SharingFrames.pairedKeyResult()
        assertThat(frame.v1.type).isEqualTo(SharingFrameType.PAIRED_KEY_RESULT)
        assertThat(frame.v1.pairedKeyResult.status).isEqualTo(PairedKeyResultStatus.UNABLE)
    }

    @Test
    fun `pairedKeyResult honors explicit status`() {
        val frame = SharingFrames.pairedKeyResult(PairedKeyResultStatus.SUCCESS)
        assertThat(frame.v1.pairedKeyResult.status).isEqualTo(PairedKeyResultStatus.SUCCESS)
    }

    @Test
    fun `connectionResponse wraps the right Status enum value`() {
        val accept = SharingFrames.connectionResponse(ConnectionResponseStatus.ACCEPT)
        assertThat(accept.v1.type).isEqualTo(SharingFrameType.RESPONSE)
        assertThat(accept.v1.connectionResponse.status).isEqualTo(ConnectionResponseStatus.ACCEPT)

        val reject = SharingFrames.connectionResponse(ConnectionResponseStatus.REJECT)
        assertThat(reject.v1.connectionResponse.status).isEqualTo(ConnectionResponseStatus.REJECT)
    }

    @Test
    fun `accept connectionResponse mirrors file attachment payload details`() {
        val attachmentHash = 9_001L
        val payloadId = 42L
        val size = 1_024L
        val introduction =
            Protocol.IntroductionFrame
                .newBuilder()
                .addFileMetadata(
                    Protocol.FileMetadata
                        .newBuilder()
                        .setName("photo.jpg")
                        .setPayloadId(payloadId)
                        .setSize(size)
                        .setAttachmentHash(attachmentHash)
                        .build(),
                ).build()

        val responseFrame =
            SharingFrames.connectionResponse(
                status = ConnectionResponseStatus.ACCEPT,
                introduction = introduction,
            )
        val response = responseFrame.v1.connectionResponse

        val details = response.getAttachmentDetailsOrThrow(attachmentHash)
        val fileDetails = details.fileAttachmentDetails
        val payload =
            fileDetails
                .getAttachmentHashPayloadsOrThrow(attachmentHash)
                .payloadDetailsList
                .single()
        assertThat(details.type).isEqualTo(Protocol.AttachmentDetails.Type.FILE)
        assertThat(fileDetails.receiverExistingFileSize).isEqualTo(0L)
        assertThat(payload.id).isEqualTo(payloadId)
        assertThat(payload.size).isEqualTo(size)
    }

    @Test
    fun `cancel produces a body-less frame with type=CANCEL`() {
        val frame = SharingFrames.cancel()
        assertThat(frame.version).isEqualTo(SharingFrameVersion.V1)
        assertThat(frame.v1.type).isEqualTo(SharingFrameType.CANCEL)
        // None of the oneof bodies should be set on a CANCEL frame.
        assertThat(frame.v1.hasIntroduction()).isFalse()
        assertThat(frame.v1.hasConnectionResponse()).isFalse()
        assertThat(frame.v1.hasPairedKeyEncryption()).isFalse()
        assertThat(frame.v1.hasPairedKeyResult()).isFalse()
    }

    @Test
    fun `parse round-trips a frame produced by builders`() {
        val original = SharingFrames.pairedKeyResult(PairedKeyResultStatus.SUCCESS)
        val parsed = SharingFrames.parse(original.toByteArray())
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `parse throws SharingFrameParseException for garbage bytes`() {
        // 0xFF 0xFF would set tag 31 with malformed wire-type bits,
        // which protobuf rejects.
        val garbage = byteArrayOf(-1, -1, -1, -1, -1)
        val ex =
            assertThrows<SharingFrameParseException> {
                SharingFrames.parse(garbage)
            }
        assertThat(ex.message).contains("Sharing.Nearby.Frame")
    }
}
