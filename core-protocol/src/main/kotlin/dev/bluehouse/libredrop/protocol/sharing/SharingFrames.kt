/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.sharing

import com.google.android.gms.nearby.sharing.Protocol
import com.google.protobuf.ByteString
import java.security.SecureRandom

/**
 * Convenience type aliases and builders for the `Sharing.Nearby.Frame`
 * proto family used during Quick Share negotiation.
 *
 * The fully-qualified Java name of the negotiation frame is
 * [com.google.android.gms.nearby.sharing.Protocol.Frame] — distinct from
 * `OfflineFrame` (which lives in the `OfflineWireFormatsProto` family used
 * by the Nearby Connections layer below us). The two namespaces are easy
 * to confuse because the negotiation frames travel as **BYTES payloads**
 * inside `OfflineFrame{type=PAYLOAD_TRANSFER}` envelopes — the `Sharing
 * .Nearby.Frame` is the *contents* that the receiver pulls out of a
 * BYTES payload after [dev.bluehouse.libredrop.protocol.payload.PayloadAssembler]
 * reassembles it.
 *
 * The aliases below give us short, unambiguous Kotlin names for the
 * negotiation frames so the FSM code in this package does not have to
 * repeat the long Java class path on every line.
 */
public typealias SharingFrame = Protocol.Frame

/** Top-level versioned wrapper enum. */
public typealias SharingFrameVersion = Protocol.Frame.Version

/** Negotiation `V1Frame` — the envelope that carries a single oneof body. */
public typealias SharingV1Frame = Protocol.V1Frame

/** Negotiation `V1Frame.FrameType` — the discriminator for the oneof. */
public typealias SharingFrameType = Protocol.V1Frame.FrameType

/** Paired-key encryption frame (steps 1-2 of the choreography). */
public typealias PairedKeyEncryptionFrame = Protocol.PairedKeyEncryptionFrame

/** Paired-key result frame (steps 2-3 of the choreography). */
public typealias PairedKeyResultFrame = Protocol.PairedKeyResultFrame

/** Result-frame status enum (`SUCCESS | FAIL | UNABLE | UNKNOWN`). */
public typealias PairedKeyResultStatus = Protocol.PairedKeyResultFrame.Status

/** The introduction frame (step 3) — sender's metadata announcement. */
public typealias IntroductionFrame = Protocol.IntroductionFrame

/** Connection response frame (step 4-5) — receiver's accept/reject. */
public typealias ConnectionResponseFrame = Protocol.ConnectionResponseFrame

/** `ConnectionResponseFrame.Status` (`ACCEPT | REJECT | NOT_ENOUGH_SPACE | ...`). */
public typealias ConnectionResponseStatus = Protocol.ConnectionResponseFrame.Status

/** Attachment details returned by the receiver in `ConnectionResponseFrame`. */
public typealias AttachmentDetails = Protocol.AttachmentDetails

/** FILE-specific attachment details returned in `ConnectionResponseFrame`. */
public typealias FileAttachmentDetails = Protocol.FileAttachmentDetails

/** Nearby payload metadata nested inside `FileAttachmentDetails`. */
public typealias PayloadDetails = Protocol.PayloadDetails

/** Payload details grouped by attachment hash. */
public typealias PayloadsDetails = Protocol.PayloadsDetails

/**
 * Builders and (de)serialization helpers for [SharingFrame].
 *
 * The negotiation FSM is pure (no I/O), so it deals in already-parsed
 * `Sharing.Nearby.Frame` instances rather than raw bytes. The helpers
 * here let consumers convert between the two forms at the FSM boundary:
 *
 *  - The receive side calls [parse] on a BYTES payload that
 *    [dev.bluehouse.libredrop.protocol.payload.PayloadAssembler]
 *    surfaced.
 *  - The send side calls [SharingFrame.toByteArray] on the FSM's
 *    `SendFrame` effect and feeds it through
 *    [dev.bluehouse.libredrop.protocol.payload.PayloadTransferEncoder.encodeBytesPayload].
 *
 * All builders default `version = V1` because the only negotiation
 * version Quick Share has ever shipped is V1; sending `UNKNOWN_VERSION`
 * would cause every real peer to drop the connection.
 */
public object SharingFrames {
    /**
     * Default fill-byte counts for [pairedKeyEncryption], anchored on
     * NearDrop's known-interoperable choices.
     *
     * Quick Share sender devices identify themselves using a contact
     * certificate rooted in a Google account. A sending phone proves it
     * "belongs to a contact" by signing the receiver's UKEY2 token with
     * its private key and sending the result as `signed_data`, plus a
     * 32-byte HMAC of the certificate `secret_id` as `secret_id_hash`.
     * NearDrop bypasses this whole dance — it does not have a certificate
     * store and it does not care to be recognized as a contact — by
     * filling both fields with random bytes of plausible-looking lengths.
     * Real Android peers receive this, fail to find a matching certificate,
     * fall through to the "Everyone for 10 minutes" visibility path, and
     * the transfer continues. The byte counts below match NearDrop:
     * 6 bytes for `secret_id_hash`, 72 bytes for `signed_data`.
     */
    public const val DEFAULT_SECRET_ID_HASH_LENGTH: Int = 6
    public const val DEFAULT_SIGNED_DATA_LENGTH: Int = 72

    /**
     * Build a `Sharing.Nearby.Frame` carrying a `PairedKeyEncryptionFrame`.
     *
     * The frame is filled with random bytes drawn from [secureRandom] for
     * both `secret_id_hash` (default [DEFAULT_SECRET_ID_HASH_LENGTH]) and
     * `signed_data` (default [DEFAULT_SIGNED_DATA_LENGTH]). NearDrop's
     * field test confirms this is enough to interop with stock Quick
     * Share peers — see the rationale above [DEFAULT_SECRET_ID_HASH_LENGTH].
     *
     * @param secureRandom Source of fill bytes. Production code passes a
     *   default [SecureRandom]; tests pass a deterministic stub.
     * @param secretIdHashLength Number of bytes for `secret_id_hash`.
     * @param signedDataLength Number of bytes for `signed_data`.
     */
    public fun pairedKeyEncryption(
        secureRandom: SecureRandom = SecureRandom(),
        secretIdHashLength: Int = DEFAULT_SECRET_ID_HASH_LENGTH,
        signedDataLength: Int = DEFAULT_SIGNED_DATA_LENGTH,
    ): SharingFrame {
        require(secretIdHashLength >= 0) { "secretIdHashLength must be non-negative" }
        require(signedDataLength >= 0) { "signedDataLength must be non-negative" }
        val secretIdHash = ByteArray(secretIdHashLength).also(secureRandom::nextBytes)
        val signedData = ByteArray(signedDataLength).also(secureRandom::nextBytes)
        val pke =
            PairedKeyEncryptionFrame
                .newBuilder()
                .setSecretIdHash(ByteString.copyFrom(secretIdHash))
                .setSignedData(ByteString.copyFrom(signedData))
                .build()
        return wrapV1(SharingFrameType.PAIRED_KEY_ENCRYPTION) {
            setPairedKeyEncryption(pke)
        }
    }

    /**
     * Build a `Sharing.Nearby.Frame` carrying a `PairedKeyResultFrame`
     * with the given status.
     *
     * The default status is `UNABLE` — same as NearDrop. UNABLE tells the
     * peer "I cannot complete the paired-key verification, please proceed
     * anyway", which is exactly the user-visible behavior we want for a
     * client that has no contact certificate store.
     */
    public fun pairedKeyResult(status: PairedKeyResultStatus = PairedKeyResultStatus.UNABLE): SharingFrame {
        val pkr =
            PairedKeyResultFrame
                .newBuilder()
                .setStatus(status)
                .build()
        return wrapV1(SharingFrameType.PAIRED_KEY_RESULT) {
            setPairedKeyResult(pkr)
        }
    }

    /**
     * Wrap an already-built [IntroductionFrame] into a top-level
     * [SharingFrame]. Sender FSM uses this on send; receiver FSM never
     * builds an introduction itself, but tests do — round-tripping
     * through `parse` is the cleanest way to assert frame contents.
     */
    public fun introduction(intro: IntroductionFrame): SharingFrame =
        wrapV1(SharingFrameType.INTRODUCTION) {
            setIntroduction(intro)
        }

    /**
     * Wrap a `ConnectionResponseFrame` with the given status into a
     * top-level [SharingFrame]. Used by the receiver FSM to emit
     * accept / reject after user consent.
     */
    public fun connectionResponse(
        status: ConnectionResponseStatus,
        introduction: IntroductionFrame? = null,
    ): SharingFrame {
        val responseBuilder =
            ConnectionResponseFrame
                .newBuilder()
                .setStatus(status)
        if (status == ConnectionResponseStatus.ACCEPT && introduction != null) {
            addAttachmentDetails(responseBuilder, introduction)
        }
        return wrapV1(SharingFrameType.RESPONSE) {
            setConnectionResponse(responseBuilder.build())
        }
    }

    private fun addAttachmentDetails(
        responseBuilder: Protocol.ConnectionResponseFrame.Builder,
        introduction: IntroductionFrame,
    ) {
        for (file in introduction.fileMetadataList) {
            if (!file.hasAttachmentHash()) continue
            val attachmentHash = file.attachmentHash
            responseBuilder.putAttachmentDetails(
                attachmentHash,
                fileAttachmentDetails(attachmentHash, file.payloadId, file.size),
            )
        }
    }

    private fun fileAttachmentDetails(
        attachmentHash: Long,
        payloadId: Long,
        size: Long,
    ): AttachmentDetails =
        AttachmentDetails
            .newBuilder()
            .setType(Protocol.AttachmentDetails.Type.FILE)
            .setFileAttachmentDetails(
                FileAttachmentDetails
                    .newBuilder()
                    .setReceiverExistingFileSize(0L)
                    .putAttachmentHashPayloads(
                        attachmentHash,
                        PayloadsDetails
                            .newBuilder()
                            .addPayloadDetails(
                                PayloadDetails
                                    .newBuilder()
                                    .setId(payloadId)
                                    .setSize(size)
                                    .build(),
                            ).build(),
                    ).build(),
            ).build()

    /**
     * Build a `Sharing.Nearby.Frame` carrying a CANCEL signal.
     *
     * `CancelFrame` is intentionally bodyless in the proto — its mere
     * presence (the `v1.type == CANCEL` discriminator) is the entire
     * payload. Either side can emit this at any non-terminal state.
     */
    public fun cancel(): SharingFrame =
        wrapV1(SharingFrameType.CANCEL) {
            // Body intentionally empty: CancelFrame has no fields.
        }

    /**
     * Parse a serialized [SharingFrame] from a BYTES payload body.
     *
     * The receiver pipeline calls this on the byte array surfaced by
     * [dev.bluehouse.libredrop.protocol.payload.PayloadEvent.BytesComplete].
     * Throws [SharingFrameParseException] on any malformed proto;
     * callers treat that as a fatal protocol error (close the
     * connection, surface a transfer-failed UI).
     */
    @Throws(SharingFrameParseException::class)
    public fun parse(bytes: ByteArray): SharingFrame =
        try {
            SharingFrame.parseFrom(bytes)
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            throw SharingFrameParseException("Sharing.Nearby.Frame proto failed to parse", e)
        }

    /**
     * Common path for every FSM-emitted frame: wrap a configured V1Frame
     * inside a `Frame{version=V1}` envelope. The [build] block configures
     * the V1Frame oneof body (e.g. `setIntroduction(...)`).
     *
     * The receiver type is the Java builder rather than a Kotlin
     * typealias because Kotlin does not resolve nested types through
     * typealiases (`SharingV1Frame.Builder` does not parse where the
     * underlying Java class works); the explicit FQN keeps the helper
     * callable from `apply { ... }` blocks.
     */
    private inline fun wrapV1(
        type: SharingFrameType,
        build: Protocol.V1Frame.Builder.() -> Unit,
    ): SharingFrame {
        val v1 =
            Protocol.V1Frame
                .newBuilder()
                .setType(type)
                .apply(build)
                .build()
        return SharingFrame
            .newBuilder()
            .setVersion(SharingFrameVersion.V1)
            .setV1(v1)
            .build()
    }
}

/**
 * Thrown by [SharingFrames.parse] when the bytes the assembler handed
 * up do not parse as a `Sharing.Nearby.Frame`. Treated as a fatal
 * protocol error by the FSM consumer — there is no resync.
 */
public class SharingFrameParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
