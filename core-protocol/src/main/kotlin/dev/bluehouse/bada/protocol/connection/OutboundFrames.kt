/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionsDevice
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.EndpointType
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.MediumMetadata
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.medium.Medium

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
     *   [dev.bluehouse.bada.protocol.endpoint.EndpointInfo]
     *   describing the sender. May be empty when the sender does not
     *   wish to be identified by name.
     * @param nonce Opening-request tie-breaker field. Stock Nearby
     *   always sets this field; callers that do not need simultaneous
     *   connection tie-breaking may use the deterministic default.
     */
    fun connectionRequest(
        endpointId: String,
        endpointInfo: ByteArray,
        supportedMediums: Set<Medium> = setOf(Medium.WIFI_LAN),
        nonce: Int = 0,
    ): OfflineFrame {
        // Match NearDrop's ConnectionRequestFrame shape. Stock Quick Share
        // closes the socket immediately if these fields are absent — only
        // endpoint_id and endpoint_info is not enough on Android 14+:
        //   * mediums tells the receiver which transports this device can
        //     negotiate up to. The default keeps the historical LAN-only
        //     shape, while off-LAN bootstraps pass their exact medium set so
        //     a BLE GATT + Wi-Fi Direct handoff does not silently advertise
        //     Wi-Fi LAN as an available upgrade path.
        //   * keep_alive_interval / timeout are read by the receiver to
        //     decide its own KEEP_ALIVE cadence. We advertise 10 s / 10 min
        //     — PROTOCOL.md documents stock Android emitting KEEP_ALIVE
        //     every 10 seconds; see KEEP_ALIVE_INTERVAL_MILLIS for the
        //     timeout rationale.
        //   * endpoint_name is a legacy string field; some forks still
        //     inspect it before parsing endpoint_info. Mirror the visible
        //     EndpointInfo name when present, and use an empty string only
        //     for hidden or malformed descriptors.
        //
        // Sort the proto-side enum values so the wire encoding is
        // deterministic for tests and for KAT-style regression detection.
        val endpointName = legacyEndpointName(endpointInfo)
        val mediumProtoValues =
            supportedMediums
                .map { it.toConnectionRequestMedium() }
                .sortedBy { it.number }
        val builder =
            ConnectionRequestFrame
                .newBuilder()
                .setEndpointId(endpointId)
                .setEndpointName(endpointName)
                .setEndpointInfo(ByteString.copyFrom(endpointInfo))
                .setNonce(nonce)
                .setMediumMetadata(defaultMediumMetadata(supportedMediums))
                .setKeepAliveIntervalMillis(KEEP_ALIVE_INTERVAL_MILLIS)
                .setKeepAliveTimeoutMillis(KEEP_ALIVE_TIMEOUT_MILLIS)
                .setConnectionsDevice(
                    ConnectionsDevice
                        .newBuilder()
                        .setEndpointId(endpointId)
                        .setEndpointType(EndpointType.CONNECTIONS_ENDPOINT)
                        .setEndpointInfo(ByteString.copyFrom(endpointInfo)),
                )
        for (m in mediumProtoValues) builder.addMediums(m)
        val request = builder.build()
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
     * Cadence at which our outbound `KEEP_ALIVE` ticker fires once the
     * SecureChannel is up. Matches the value PROTOCOL.md documents stock
     * Android Quick Share emitting ("Android sends offline frames of
     * type KEEP_ALIVE every 10 seconds and expects the server to do
     * the same"). The ticker itself lives in [KeepAliveTicker]; we
     * only advertise the cadence here so the receiver knows what to
     * expect on its watchdog.
     */
    private const val KEEP_ALIVE_INTERVAL_MILLIS: Int = 10_000

    /**
     * Keep-alive timeout we advertise to the peer. Stock google/nearby's
     * default is 30 s, predicated on the peer sending explicit
     * `KEEP_ALIVE` frames at the advertised interval. We now run a
     * 10 s outbound ticker (issue #37) so the spec-default 30 s would
     * suffice in steady state, but 10 minutes preserves headroom for
     * slow-network corner cases (phone-hotspot uplinks, momentary
     * stalls during large-file SD-card writes) where a single missed
     * KEEP_ALIVE under load shouldn't tear the connection down. Verified
     * on-device that bumping from 30 s to 10 min cleared the
     * Samsung One UI mid-payload disconnect at ~81 % on phone-hotspot
     * links.
     */
    private const val KEEP_ALIVE_TIMEOUT_MILLIS: Int = 600_000

    /**
     * Minimal stock-shaped medium metadata for the opening request.
     *
     * Android's public Nearby stack always includes this sub-message on
     * `ConnectionRequestFrame`; richer Android-only callers can thread
     * precise AP/BSSID/channel data later, but setting the sub-message
     * here keeps the field-shape compatible without introducing
     * `android.*` dependencies into `:core-protocol`.
     */
    private fun defaultMediumMetadata(supportedMediums: Set<Medium>): MediumMetadata =
        MediumMetadata
            .newBuilder()
            .setSupports5Ghz(false)
            .setSupports6Ghz(false)
            .setMobileRadio(false)
            .setApFrequency(-1)
            .also { builder ->
                if (Medium.WIFI_DIRECT in supportedMediums) {
                    builder.addSupportedWifiDirectAuthTypes(
                        MediumMetadata.WifiDirectAuthType.WIFI_DIRECT_WITH_PASSWORD,
                    )
                }
            }.build()

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
     *   6. `keep_alive_timeout_millis = 600_000` — `ConnectionResponseFrame`
     *      proto field 9, added to google/nearby on 2024-12-06
     *      (PiperOrigin-RevId 703665365), contemporaneous with One UI 8
     *      development. Verified on-device: with fields 1–5 only, Galaxy
     *      S24 Ultra (One UI 8.0.5 / Android 16) silently FINs ~150 ms
     *      after our ConnectionResponse{ACCEPT}; adding this field makes
     *      Samsung respond with its own ConnectionResponse{ACCEPT} ~50 ms
     *      later and the protocol advances cleanly to PIN derivation. We
     *      mirror the request-side keep-alive timeout (10 min) to keep both
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

    private fun legacyEndpointName(endpointInfo: ByteArray): String {
        val parsed = EndpointInfo.parse(endpointInfo)
        return parsed?.deviceName.orEmpty()
    }
}
