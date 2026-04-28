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
 *    frame the receiver sends before the SecureChannel turns on.
 *    NearDrop's `InboundNearbyConnection.swift` calls this
 *    `processConnectionResponseFrame()`; we build the equivalent here.
 *    Must emit the same six-field shape as
 *    [OutboundFrames.connectionResponse] — the validating peer does not
 *    know which role we are in, and One UI 8.0.5 validates the presence
 *    of each field unconditionally.
 *  - [disconnection] -- the post-transfer teardown frame. Sent
 *    (encrypted) right before the orchestrator closes the socket on a
 *    successful completion or rejection.
 *
 * Both helpers are pure functions; no state is held.
 */
internal object OfflineFrames {
    /** Legacy `status` field value for "STATUS_OK". 0 in the proto enum. */
    private const val STATUS_OK: Int = 0

    /**
     * Version we advertise for the "safe to disconnect" handshake.
     * See [OutboundFrames.SAFE_TO_DISCONNECT_VERSION] for the full rationale.
     */
    private const val SAFE_TO_DISCONNECT_VERSION: Int = 1

    /**
     * Bitmask value that declares "no medium supports multiplexing".
     * See [OutboundFrames.MULTIPLEX_SOCKET_BITMASK_NONE] for the full
     * rationale (Samsung One UI 8.0.5 silent-FIN guard).
     */
    private const val MULTIPLEX_SOCKET_BITMASK_NONE: Int = 0

    /**
     * Keep-alive timeout we advertise on `ConnectionResponseFrame`.
     * See [OutboundFrames.KEEP_ALIVE_TIMEOUT_MILLIS] for the full rationale
     * (One UI 8.0.5 silent-FIN guard; mirrors our request-side value).
     */
    private const val KEEP_ALIVE_TIMEOUT_MILLIS: Int = 600_000

    /**
     * Build an unencrypted `OfflineFrame{V1, CONNECTION_RESPONSE,
     * response=ACCEPT, os_info=ANDROID}`.
     *
     * Matches [OutboundFrames.connectionResponse] field-for-field.
     * Both the sender and receiver paths must emit the same six-field
     * shape because the validating peer (e.g. Samsung One UI 8.0.5)
     * does not know which role we are playing and validates every field
     * unconditionally. The six required fields are:
     *   1. `status = 0` (STATUS_OK — legacy int field).
     *   2. `response = ACCEPT` (modern enum field).
     *   3. `os_info.type = ANDROID` — LINUX causes Samsung silent FINs.
     *   4. `multiplex_socket_bitmask = 0` — absence triggers One UI 8.0.5
     *      silent FIN; 0 = "no medium supports multiplex".
     *   5. `safe_to_disconnect_version = 1` — required by One UI 7+.
     *   6. `keep_alive_timeout_millis = 30_000` — proto field 9, required
     *      by One UI 8.0.5 (verified on-device); see
     *      [OutboundFrames.connectionResponse] for the full rationale.
     */
    fun connectionResponse(): OfflineFrame {
        @Suppress("DEPRECATION")
        val response =
            ConnectionResponseFrame
                .newBuilder()
                .setStatus(STATUS_OK)
                .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)
                .setOsInfo(
                    OsInfo
                        .newBuilder()
                        .setType(OsInfo.OsType.ANDROID)
                        .build(),
                ).setMultiplexSocketBitmask(MULTIPLEX_SOCKET_BITMASK_NONE)
                .setSafeToDisconnectVersion(SAFE_TO_DISCONNECT_VERSION)
                .setKeepAliveTimeoutMillis(KEEP_ALIVE_TIMEOUT_MILLIS)
                .build()
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
