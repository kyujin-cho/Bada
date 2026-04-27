/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString

/**
 * Builders for the small set of `OfflineFrame` messages that
 * [OutboundConnection] emits directly during the unencrypted bring-up
 * phase.
 *
 * Three frames live here:
 *
 *  - [connectionRequest] — the **unencrypted** opening frame the
 *    sender writes immediately after the TCP socket is established.
 *    Carries our endpoint identity and (optionally) our serialized
 *    `endpoint_info`. NearDrop's `OutboundNearbyConnection.swift`
 *    builds this in `processSocketConnection()`.
 *  - [connectionResponse] — the **unencrypted** post-UKEY2 acceptance
 *    frame the sender mails back after seeing the receiver's accept.
 *    Mirrors [OfflineFrames.connectionResponse]; the only difference
 *    is the role context (a sender's accept tells the receiver "I am
 *    happy to proceed").
 *  - [disconnection] — the post-transfer teardown frame, identical to
 *    [OfflineFrames.disconnection]. Sent (encrypted) right before the
 *    orchestrator closes the socket.
 *
 * All helpers are pure functions; no state is held.
 */
internal object OutboundFrames {
    /**
     * Build an unencrypted `OfflineFrame{V1, CONNECTION_REQUEST, ...}`.
     *
     * @param endpointId 4-character ASCII id the sender chose for
     *   itself. Quick Share tolerates any short ASCII identifier; the
     *   only hard requirement is that the id be UTF-8-clean.
     * @param endpointInfo Serialized
     *   [io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo]
     *   describing the sender. May be empty when the sender does not
     *   wish to be identified by name.
     */
    fun connectionRequest(
        endpointId: String,
        endpointInfo: ByteArray,
    ): OfflineFrame {
        val request =
            ConnectionRequestFrame
                .newBuilder()
                .setEndpointId(endpointId)
                .setEndpointInfo(ByteString.copyFrom(endpointInfo))
                .build()
        return OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.CONNECTION_REQUEST)
                    .setConnectionRequest(request)
                    .build(),
            ).build()
    }

    /**
     * Build an unencrypted `OfflineFrame{V1, CONNECTION_RESPONSE,
     * response=ACCEPT, os_info=LINUX}`.
     *
     * Same shape as [OfflineFrames.connectionResponse]: `response =
     * ACCEPT`, the legacy `status` field left at its proto default,
     * `os_info.type = LINUX`. Stock Quick Share peers accept any
     * `os_info.type` here; LINUX is the most literal answer for a
     * JVM/Android host.
     */
    fun connectionResponse(): OfflineFrame {
        val response =
            ConnectionResponseFrame
                .newBuilder()
                .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)
                .setOsInfo(
                    OsInfo
                        .newBuilder()
                        .setType(OsInfo.OsType.LINUX)
                        .build(),
                ).build()
        return OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                    .setConnectionResponse(response)
                    .build(),
            ).build()
    }
}
