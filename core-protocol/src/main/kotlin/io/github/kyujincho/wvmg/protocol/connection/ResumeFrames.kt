/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.AutoReconnectFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.AutoResumeFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame

/**
 * Builders / parsers for the resume-protocol frames defined in
 * `offline_wire_formats.proto`:
 *
 *  - [AutoResumeFrame] — exchanged inside an established SecureChannel
 *    once both peers have re-handshaked. Carries the receiver's
 *    `next_payload_chunk_index` so the sender can skip already-received
 *    chunks of an in-flight payload.
 *  - [AutoReconnectFrame] — exchanged on the unencrypted leg right
 *    after `ConnectionRequest`/`ConnectionResponse` to identify the new
 *    socket as a continuation of a prior, broken connection. The
 *    `endpoint_id` and `last_endpoint_id` fields let the receiver
 *    associate the new socket with a still-warm session.
 *
 * #### Scope of this PR
 *
 * Issue #43's full scope is "kill the receiver mid-transfer of a 200 MB
 * file, re-open, sender resumes from the last persisted offset". That
 * end-to-end behaviour requires both:
 *
 *  1. A higher-level **session manager** that survives across socket
 *     lifetimes — per-endpoint coverage state, cross-connection ID
 *     mapping, and wakelock / foreground-service plumbing on Android.
 *  2. **Wire-format support** on both ends so peers that speak the
 *     resume sub-protocol can advertise it.
 *
 * This file delivers (2) plus the assembler-side scaffolding the
 * session manager will sit atop. The full reconnect-on-new-socket flow
 * is left for a follow-up — it touches discovery, the foreground
 * service, and the receiver's notification model in ways that warrant
 * a separate review.
 *
 * The scaffolding is still useful in isolation: a peer that re-sends
 * a chunk we have already received (after a transient network hiccup)
 * is silently deduplicated by [io.github.kyujincho.wvmg.protocol.payload.ByteRangeSet],
 * and the AUTO_RESUME wire format is now defined so future work does
 * not break ABI.
 */
public object ResumeFrames {
    /**
     * Build an `OfflineFrame{V1.AUTO_RESUME}` carrying a
     * `PAYLOAD_RESUME_TRANSFER_START` event. Sent by the receiver after
     * a reconnect to advertise how far it got on each in-flight
     * payload.
     *
     * @param pendingPayloadId The `PayloadHeader.id` of the payload
     *   to resume.
     * @param nextPayloadChunkIndex The index (not the byte offset) of
     *   the first chunk the sender should re-send. The chunk size is
     *   negotiated separately; the receiver MUST persist the chunk
     *   size used at first send so it can express resume offsets in
     *   chunk-index terms.
     * @param version Protocol version. `0` is the documented default;
     *   the proto leaves this open for future wire-format changes.
     */
    public fun resumeStart(
        pendingPayloadId: Long,
        nextPayloadChunkIndex: Int,
        version: Int = 0,
    ): OfflineFrame =
        wrap(
            AutoResumeFrame
                .newBuilder()
                .setEventType(AutoResumeFrame.EventType.PAYLOAD_RESUME_TRANSFER_START)
                .setPendingPayloadId(pendingPayloadId)
                .setNextPayloadChunkIndex(nextPayloadChunkIndex)
                .setVersion(version)
                .build(),
        )

    /**
     * Build an `OfflineFrame{V1.AUTO_RESUME}` carrying a
     * `PAYLOAD_RESUME_TRANSFER_ACK` event. Sent by the sender after it
     * accepts the receiver's resume request — confirms it will start
     * from `nextPayloadChunkIndex` rather than from offset 0.
     *
     * Some peers omit the ACK and just begin the transfer; this frame
     * is provided for symmetry and for explicit-handshake interop tests.
     */
    public fun resumeAck(
        pendingPayloadId: Long,
        nextPayloadChunkIndex: Int,
        version: Int = 0,
    ): OfflineFrame =
        wrap(
            AutoResumeFrame
                .newBuilder()
                .setEventType(AutoResumeFrame.EventType.PAYLOAD_RESUME_TRANSFER_ACK)
                .setPendingPayloadId(pendingPayloadId)
                .setNextPayloadChunkIndex(nextPayloadChunkIndex)
                .setVersion(version)
                .build(),
        )

    /**
     * Build an `OfflineFrame{V1.AUTO_RECONNECT}` carrying a
     * `CLIENT_INTRODUCTION` event.
     *
     * Sent on the unencrypted leg of a reconnect — it identifies the
     * *new* socket as a continuation of the connection identified by
     * `lastEndpointId`. The receiver looks up its session state for
     * that prior endpoint and either resumes (if the session is still
     * warm) or treats the new socket as a fresh connection (if not).
     */
    public fun clientIntroduction(
        endpointId: String,
        lastEndpointId: String,
    ): OfflineFrame =
        wrap(
            AutoReconnectFrame
                .newBuilder()
                .setEventType(AutoReconnectFrame.EventType.CLIENT_INTRODUCTION)
                .setEndpointId(endpointId)
                .setLastEndpointId(lastEndpointId)
                .build(),
        )

    /** Receiver's acknowledgement of [clientIntroduction]. */
    public fun clientIntroductionAck(endpointId: String): OfflineFrame =
        wrap(
            AutoReconnectFrame
                .newBuilder()
                .setEventType(AutoReconnectFrame.EventType.CLIENT_INTRODUCTION_ACK)
                .setEndpointId(endpointId)
                .build(),
        )

    /**
     * Whether [frame] is an `OfflineFrame{V1.AUTO_RESUME}` carrying a
     * fully-populated [AutoResumeFrame]. Useful as a guard before
     * calling [parseAutoResume].
     */
    public fun isAutoResume(frame: OfflineFrame): Boolean =
        frame.hasV1() &&
            frame.v1.type == V1Frame.FrameType.AUTO_RESUME &&
            frame.v1.hasAutoResume()

    /**
     * Whether [frame] is an `OfflineFrame{V1.AUTO_RECONNECT}` carrying
     * a fully-populated [AutoReconnectFrame].
     */
    public fun isAutoReconnect(frame: OfflineFrame): Boolean =
        frame.hasV1() &&
            frame.v1.type == V1Frame.FrameType.AUTO_RECONNECT &&
            frame.v1.hasAutoReconnect()

    /**
     * Extract the inner [AutoResumeFrame] from a verified resume frame.
     *
     * @throws IllegalArgumentException if [frame] is not an AUTO_RESUME
     *   frame. Use [isAutoResume] to check before calling.
     */
    public fun parseAutoResume(frame: OfflineFrame): AutoResumeFrame {
        require(isAutoResume(frame)) { "frame is not an AUTO_RESUME OfflineFrame" }
        return frame.v1.autoResume
    }

    /**
     * Extract the inner [AutoReconnectFrame] from a verified reconnect
     * frame.
     *
     * @throws IllegalArgumentException if [frame] is not an
     *   AUTO_RECONNECT frame. Use [isAutoReconnect] to check before
     *   calling.
     */
    public fun parseAutoReconnect(frame: OfflineFrame): AutoReconnectFrame {
        require(isAutoReconnect(frame)) { "frame is not an AUTO_RECONNECT OfflineFrame" }
        return frame.v1.autoReconnect
    }

    private fun wrap(autoResume: AutoResumeFrame): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.AUTO_RESUME)
                    .setAutoResume(autoResume),
            ).build()

    private fun wrap(autoReconnect: AutoReconnectFrame): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.AUTO_RECONNECT)
                    .setAutoReconnect(autoReconnect),
            ).build()
}
