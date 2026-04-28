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

    /**
     * Keep-alive timeout we advertise to the peer. Stock google/nearby's
     * default is 30 s under the assumption that the peer will send
     * explicit `KEEP_ALIVE` frames at the advertised interval. We do
     * not run a keep-alive sender yet (we only handle inbound
     * `KEEP_ALIVE` frames, like NearDrop), so a 30-second silence
     * during slow file transfers — easy to hit on a phone hotspot link
     * — caused Samsung One UI to disconnect mid-payload at ~81 %
     * (verified on-device). 10 minutes covers reasonable file sizes
     * over Wi-Fi LAN; the canonical fix is a periodic
     * `KEEP_ALIVE` sender driven from the secure channel, tracked
     * separately.
     */
    private const val KEEP_ALIVE_TIMEOUT_MILLIS: Int = 600_000

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
     * Bitmask value that declares "no medium supports multiplexing".
     *
     * Stock Google Nearby Connections (`google/nearby`,
     * `ForConnectionResponse`) unconditionally sets this field on every
     * `ConnectionResponseFrame`, even when no medium supports multiplex.
     * The proto comment defines `0x00` as "not [supported]" per medium.
     * Samsung One UI 8.0.5 (Android 16) appears to check
     * `has_multiplex_socket_bitmask()` and silently FINs ~104 ms after
     * our `ConnectionResponse{ACCEPT}` when the field is absent. Setting
     * it to 0 is the correct way to declare "we do not multiplex on any
     * medium" — which matches our single Wi-Fi LAN socket implementation.
     *
     * See also [OfflineFrames.connectionResponse] — both paths must emit
     * the same five-field shape because the validating peer does not know
     * which role we are playing.
     */
    private const val MULTIPLEX_SOCKET_BITMASK_NONE: Int = 0

    /**
     * Build an unencrypted `OfflineFrame{V1, CONNECTION_RESPONSE,
     * response=ACCEPT, os_info=ANDROID}`.
     *
     * Same shape as [OfflineFrames.connectionResponse]. Both paths must
     * emit the same six-field shape because the validating peer does not
     * know which role we are playing.
     *
     * The six Samsung One UI 8-required fields, in the order used by
     * google/nearby's `ForConnectionResponse`:
     *   1. `status = 0` (STATUS_OK — legacy int field; older receivers
     *      inspect it for backwards compat).
     *   2. `response = ACCEPT` (modern enum field).
     *   3. `os_info.type = ANDROID` — LINUX = 100 is reserved for the
     *      g3 test environment; Samsung One UI silently FINs on LINUX.
     *   4. `multiplex_socket_bitmask = 0` — Samsung One UI 8.0.5+
     *      silently FINs without it. 0 = "no medium supports multiplex",
     *      which matches our single-Wi-Fi-LAN-socket implementation.
     *   5. `safe_to_disconnect_version = 1` — Samsung One UI 7+ refuses
     *      to advance past the unencrypted handshake when absent.
     *   6. `keep_alive_timeout_millis = 30_000` — `ConnectionResponseFrame`
     *      proto field 9, added to google/nearby on 2024-12-06
     *      (PiperOrigin-RevId 703665365), contemporaneous with One UI 8
     *      development. Verified on-device: with fields 1–5 only, Galaxy
     *      S24 Ultra (One UI 8.0.5 / Android 16) silently FINs ~150 ms
     *      after our ConnectionResponse{ACCEPT}; adding this field makes
     *      Samsung respond with its own ConnectionResponse{ACCEPT} ~50 ms
     *      later and the protocol advances cleanly to PIN derivation. We
     *      mirror the request-side keep-alive timeout (30 s) to keep both
     *      sides on the same KEEP_ALIVE schedule.
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
}
