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
        // Match NearDrop's ConnectionRequestFrame shape. Stock Quick Share
        // closes the socket immediately if these fields are absent — only
        // endpoint_id and endpoint_info is not enough on Android 14+:
        //   * mediums = [WIFI_LAN] tells the receiver which transport this
        //     connection is using; absence of any medium hits a validation
        //     in Nearby Connections that rejects the request.
        //   * keep_alive_interval / timeout are read by the receiver to
        //     decide its own KEEP_ALIVE cadence; defaults of 5s / 30s are
        //     what the Chromium reference and NearDrop both ship.
        //   * endpoint_name is a legacy string field; some forks (older
        //     Samsung Quick Share) still inspect it. Setting it to the
        //     empty string keeps modern peers happy and gives legacy peers
        //     a defined value to read.
        val request =
            ConnectionRequestFrame
                .newBuilder()
                .setEndpointId(endpointId)
                .setEndpointName("")
                .setEndpointInfo(ByteString.copyFrom(endpointInfo))
                .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN)
                .setKeepAliveIntervalMillis(KEEP_ALIVE_INTERVAL_MILLIS)
                .setKeepAliveTimeoutMillis(KEEP_ALIVE_TIMEOUT_MILLIS)
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

    private const val KEEP_ALIVE_INTERVAL_MILLIS: Int = 5_000
    private const val KEEP_ALIVE_TIMEOUT_MILLIS: Int = 30_000

    /** Legacy `status` field value for "STATUS_OK". 0 in the proto enum. */
    private const val STATUS_OK: Int = 0

    /**
     * Version we advertise for the "safe to disconnect" handshake. Stock
     * Quick Share on Samsung One UI 7+ requires this set to 1 or higher;
     * absence is interpreted as "peer cannot safely disconnect" and the
     * receiver silently drops us before the consent dialog opens.
     */
    private const val SAFE_TO_DISCONNECT_VERSION: Int = 1

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
        // Full ConnectionResponse shape — verified to make Samsung
        // Galaxy S26 Ultra display the receive-consent dialog during
        // manual interop testing:
        //   * status = STATUS_OK (legacy int field, value 0; some
        //     receivers still inspect it for backwards compat).
        //   * response = ACCEPT (modern enum field).
        //   * os_info.type = ANDROID — LINUX = 100 is reserved for the
        //     g3 test environment and certain Samsung Quick Share
        //     forks silently FIN when they see it.
        //   * safe_to_disconnect_version = 1 — Samsung's One UI Quick
        //     Share refuses to advance past the unencrypted handshake
        //     when this field is missing, presumably because absence
        //     means "peer does not support safe disconnect" and the
        //     consent dialog never opens.
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
                ).setSafeToDisconnectVersion(SAFE_TO_DISCONNECT_VERSION)
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
}
