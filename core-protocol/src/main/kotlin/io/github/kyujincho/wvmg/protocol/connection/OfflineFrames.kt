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
     * Build an `OfflineFrame{V1, DISCONNECTION}`.
     *
     * Because [connectionResponse] advertises
     * `safe_to_disconnect_version = 1`, the Samsung One UI 7+ peer
     * enforces the safe-disconnect handshake on teardown: an abrupt
     * TCP FIN mid-payload makes the receiver flag every in-flight
     * payload as failed and surface "couldn't receive file" — even
     * when the bytes already landed in its socket buffer. Setting
     * `request_safe_to_disconnect=true` tells the peer to drain its
     * read pipeline cleanly before acknowledging; we then wait briefly
     * for the peer's `ack_safe_to_disconnect=true` (or peer-FIN) before
     * closing our socket. The orchestrator's drain loop in
     * `runReceiveLoop` is the matching wait.
     *
     * @param requestSafeToDisconnect Set the request flag (sender
     *   side). True for the happy-path teardown after streaming files
     *   and for cancel/reject cases — receiver still benefits from
     *   draining buffered bytes before tearing down.
     * @param ackSafeToDisconnect Set the ack flag (receiver side).
     *   The receiver path uses this when answering a peer's
     *   `request_safe_to_disconnect=true`.
     */
    fun disconnection(
        requestSafeToDisconnect: Boolean = false,
        ackSafeToDisconnect: Boolean = false,
    ): OfflineFrame {
        val disconnection =
            DisconnectionFrame
                .newBuilder()
                .setRequestSafeToDisconnect(requestSafeToDisconnect)
                .setAckSafeToDisconnect(ackSafeToDisconnect)
                .build()
        return OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.DISCONNECTION)
                    .setDisconnection(disconnection)
                    .build(),
            ).build()
    }
}
