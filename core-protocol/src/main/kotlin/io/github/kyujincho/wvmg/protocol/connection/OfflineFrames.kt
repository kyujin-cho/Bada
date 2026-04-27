/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.DisconnectionFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame

/**
 * Builders for the small set of `OfflineFrame` messages that
 * [InboundConnection] emits directly (i.e. not via the negotiation
 * FSM or the payload encoder).
 *
 * Two frames live here:
 *
 *  - [connectionResponse] -- the **unencrypted** post-UKEY2 handshake
 *    handshake frame the receiver sends before the SecureChannel turns
 *    on. NearDrop's `InboundNearbyConnection.swift` calls this
 *    `processConnectionResponseFrame()`; we build the equivalent here
 *    with `response = ACCEPT` and `os_info = LINUX` (the most-honest
 *    answer for a JVM port -- it's not Apple, not stock Android, and
 *    Quick Share peers tolerate any value).
 *  - [disconnection] -- the post-transfer teardown frame. Sent
 *    (encrypted) right before the orchestrator closes the socket on a
 *    successful completion or rejection.
 *
 * Both helpers are pure functions; no state is held.
 */
internal object OfflineFrames {
    /**
     * Build an unencrypted `OfflineFrame{V1, CONNECTION_RESPONSE,
     * response=ACCEPT, os_info=LINUX}`.
     *
     * The `response` field uses the modern enum
     * (`ResponseStatus.ACCEPT`); the legacy integer `status` field is
     * left at its proto default of `0` (`STATUS_OK`). Stock Android
     * Quick Share fills both for older receivers, but NearDrop only
     * sets the enum and that has worked in the field, so we follow
     * NearDrop.
     *
     * `os_info.type = LINUX` is a small white lie that matches the
     * spirit of NearDrop's `APPLE` choice -- we are not running on
     * Apple, but we are also not running on stock Android (that would
     * misrepresent the device class to the peer). LINUX is the most
     * literal answer: the JVM/Android port runs on a Linux kernel.
     * Peers do not branch on this for protocol behavior.
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

    /**
     * Build an `OfflineFrame{V1, DISCONNECTION}` with the safe-to-
     * disconnect flags both set to `false`.
     *
     * The orchestrator pushes this frame through the SecureChannel
     * (it is encrypted) right before closing the socket. The empty
     * body is fine -- `DisconnectionFrame` is functionally a marker;
     * the safe-to-disconnect protocol it gates is not used by
     * NearDrop or by us.
     */
    fun disconnection(): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.DISCONNECTION)
                    .setDisconnection(DisconnectionFrame.getDefaultInstance())
                    .build(),
            ).build()
}
