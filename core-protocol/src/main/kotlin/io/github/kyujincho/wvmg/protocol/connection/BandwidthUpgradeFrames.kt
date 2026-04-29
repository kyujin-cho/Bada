/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials

/**
 * Builders for `OfflineFrame{V1, BANDWIDTH_UPGRADE_NEGOTIATION}` shapes.
 *
 * Quick Share's bandwidth-upgrade dance is a six-event protocol on top
 * of `BandwidthUpgradeNegotiationFrame.event_type`:
 *
 *   1. **`UPGRADE_PATH_AVAILABLE`** — server side. "Here are the
 *      credentials for the new transport." Carries an
 *      [UpgradePathInfo] describing which medium and the per-medium
 *      bring-up parameters.
 *   2. **`CLIENT_INTRODUCTION`** — client side. After the client has
 *      reconnected on the new medium, it sends its endpoint id over
 *      the new socket so the server can map the new connection back
 *      to the original one.
 *   3. **`CLIENT_INTRODUCTION_ACK`** — server side. ACKs the
 *      introduction.
 *   4. **`LAST_WRITE_TO_PRIOR_CHANNEL`** — either side, on the OLD
 *      transport. "I am about to stop writing on the old socket." The
 *      sender flushes pending writes, then sends this and stops.
 *   5. **`SAFE_TO_CLOSE_PRIOR_CHANNEL`** — either side, on the OLD
 *      transport. "I have observed your LAST_WRITE; you can FIN now."
 *   6. **`UPGRADE_FAILURE`** — either side. Aborts the upgrade and
 *      tells the peer we are staying on the current transport.
 *
 * Builders here are pure functions: no I/O, no state. The orchestrator
 * (Phase 4 sub-issue #54 wires up the actual transport swap) drives the
 * sequence; the FSM in
 * [io.github.kyujincho.wvmg.protocol.medium.BandwidthUpgradeNegotiator]
 * owns the state transitions.
 *
 * ### Per-medium credential serialization
 *
 * The builders fan out on the [UpgradePathCredentials] subtype so each
 * Phase 4 sub-issue (#49–#53) only has to add one arm here when its
 * adapter lands. Today only [UpgradePathCredentials.Generic] (no extra
 * fields) and [UpgradePathCredentials.WifiLan] (IP + port) are wired
 * up; that's enough to validate the framework end-to-end with a
 * loopback test.
 */
public object BandwidthUpgradeFrames {
    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=UPGRADE_PATH_AVAILABLE, upgrade_path_info=...}}`.
     *
     * @param credentials Server-side bring-up parameters returned by
     *   [io.github.kyujincho.wvmg.protocol.medium.MediumProvider.prepareUpgrade].
     */
    public fun upgradePathAvailable(credentials: UpgradePathCredentials): OfflineFrame {
        val info = encodeCredentials(credentials)
        return wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
                .setUpgradePathInfo(info)
                .build(),
        )
    }

    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=CLIENT_INTRODUCTION, client_introduction=...}}`.
     *
     * @param endpointId The sender's endpoint id (matches the
     *   `endpoint_id` we sent in our original `ConnectionRequest`); the
     *   server keys the upgraded socket back to the original session
     *   on this value.
     * @param lastEndpointId Optional: the previous endpoint id when
     *   reusing one across upgrade attempts. Default empty (matches
     *   `google/nearby` for fresh sessions).
     */
    public fun clientIntroduction(
        endpointId: String,
        lastEndpointId: String = "",
    ): OfflineFrame {
        val intro =
            BandwidthUpgradeNegotiationFrame.ClientIntroduction
                .newBuilder()
                .setEndpointId(endpointId)
                .setLastEndpointId(lastEndpointId)
                .build()
        return wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
                .setClientIntroduction(intro)
                .build(),
        )
    }

    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=CLIENT_INTRODUCTION_ACK}}`.
     *
     * Server-side ACK of [clientIntroduction]. The proto's
     * `ClientIntroductionAck` message has no fields today; the helper
     * still constructs an empty instance so the `oneof` slot is set
     * (without it, some Quick Share validators read an absent
     * sub-message as malformed).
     */
    public fun clientIntroductionAck(): OfflineFrame =
        wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK)
                .setClientIntroductionAck(
                    BandwidthUpgradeNegotiationFrame.ClientIntroductionAck.getDefaultInstance(),
                ).build(),
        )

    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=LAST_WRITE_TO_PRIOR_CHANNEL}}`.
     *
     * Sent on the OLD transport before stopping writes on it. Pairs
     * with [safeToClosePriorChannel].
     */
    public fun lastWriteToPriorChannel(): OfflineFrame =
        wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL)
                .build(),
        )

    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=SAFE_TO_CLOSE_PRIOR_CHANNEL, safe_to_close_prior_channel=...}}`.
     *
     * Sent on the OLD transport once the peer's LAST_WRITE has been
     * observed. The `sta_frequency` parameter, when present, hints to
     * the peer at the Wi-Fi STA frequency in MHz so a Wi-Fi-Direct
     * group owner can pick a non-conflicting channel; defaults to -1
     * ("not set / no hint").
     */
    public fun safeToClosePriorChannel(staFrequency: Int = STA_FREQUENCY_NOT_SET): OfflineFrame {
        val safe =
            BandwidthUpgradeNegotiationFrame.SafeToClosePriorChannel
                .newBuilder()
                .setStaFrequency(staFrequency)
                .build()
        return wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL)
                .setSafeToClosePriorChannel(safe)
                .build(),
        )
    }

    /**
     * Build `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION{
     * event_type=UPGRADE_FAILURE, upgrade_path_info{medium=...}}}`.
     *
     * Either side: aborts the upgrade. The proto carries the failed
     * medium back via `upgrade_path_info.medium` so the peer can
     * exclude that medium from any retry.
     */
    public fun upgradeFailure(medium: Medium): OfflineFrame {
        val info =
            UpgradePathInfo
                .newBuilder()
                .setMedium(medium.toUpgradePathMedium())
                .build()
        return wrap(
            BandwidthUpgradeNegotiationFrame
                .newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
                .setUpgradePathInfo(info)
                .build(),
        )
    }

    /**
     * Decode an [UpgradePathInfo] back into the matching
     * [UpgradePathCredentials]. Returns `null` for an unsupported
     * medium ([Medium.fromUpgradePathMedium] result is `null`) or for
     * a credentials shape this module does not yet know how to parse —
     * Phase 4 sub-issues add new arms here as they land.
     */
    public fun decodeCredentials(info: UpgradePathInfo): UpgradePathCredentials? {
        val medium = Medium.fromUpgradePathMedium(info.medium) ?: return null
        return when (medium) {
            Medium.WIFI_LAN -> {
                if (info.hasWifiLanSocket()) {
                    UpgradePathCredentials.WifiLan(
                        ipAddress = info.wifiLanSocket.ipAddress.toByteArray(),
                        port = info.wifiLanSocket.wifiPort,
                    )
                } else {
                    UpgradePathCredentials.Generic(medium)
                }
            }
            // Bluetooth RFCOMM (#51): BluetoothCredentials carries the
            // peer device MAC + the SDP service identifier the receiver
            // advertised via listenUsingInsecureRfcommWithServiceRecord.
            //
            // Wire mapping:
            //  - mac_address (string): canonical colon-separated EUI-48,
            //    e.g. "AA:BB:CC:DD:EE:FF". We parse it back to 6 raw
            //    bytes so the in-memory credentials object stays a
            //    bit-exact round-trip with the encoder side.
            //  - service_name (string): the canonical 8-4-4-4-12 SDP
            //    UUID the receiver registered. Google Nearby Connections
            //    uses service_name as the SDP UUID identifier on RFCOMM
            //    (Android needs a UUID for createInsecureRfcomm…), so we
            //    surface it as `serviceUuid` and copy it into
            //    `serviceName` for the rare consumer that wants both.
            //
            // An invalid / empty MAC string drops the Bluetooth oneof
            // and returns Generic so the negotiator's CLIENT-side code
            // can treat it as "no usable credentials" and emit
            // UPGRADE_FAILURE; that matches how WIFI_LAN handles a
            // missing wifi_lan_socket sub-message.
            Medium.BLUETOOTH -> {
                if (info.hasBluetoothCredentials() &&
                    info.bluetoothCredentials.macAddress.isNotEmpty() &&
                    info.bluetoothCredentials.serviceName.isNotEmpty()
                ) {
                    val bt = info.bluetoothCredentials
                    runCatching {
                        UpgradePathCredentials.Bluetooth(
                            macAddress =
                                UpgradePathCredentials.Bluetooth
                                    .macStringToBytes(bt.macAddress),
                            serviceUuid = bt.serviceName,
                        )
                    }.getOrElse { UpgradePathCredentials.Generic(medium) }
                } else {
                    UpgradePathCredentials.Generic(medium)
                }
            }
            // Phase 4 sub-issues (#49–#53) plug the remaining arms in
            // here as their adapters land. Until then we report Generic
            // so the upper layers can at least see that *some* path
            // was advertised; the provider will reject it on adopt
            // because it doesn't carry meaningful credentials.
            //
            // NOTE: BLE_L2CAP is in this list for completeness even
            // though it cannot appear on `UpgradePathInfo.medium` (the
            // proto reserves wire number 10 there). The path that
            // builds the wire frame for BLE_L2CAP must use the
            // discovery-medium path instead — see Phase 4 #52.
            Medium.WIFI_DIRECT,
            Medium.WIFI_HOTSPOT,
            Medium.WIFI_AWARE,
            Medium.BLE_L2CAP,
            Medium.BLE,
            Medium.WEB_RTC,
            -> UpgradePathCredentials.Generic(medium)
        }
    }

    /**
     * Encode a credentials value into [UpgradePathInfo]. Inverse of
     * [decodeCredentials]; same per-medium switch shape.
     */
    private fun encodeCredentials(credentials: UpgradePathCredentials): UpgradePathInfo {
        val builder =
            UpgradePathInfo
                .newBuilder()
                .setMedium(credentials.medium.toUpgradePathMedium())
        when (credentials) {
            is UpgradePathCredentials.Generic -> Unit // Medium-only; nothing extra to set.
            is UpgradePathCredentials.WifiLan -> {
                builder.setWifiLanSocket(
                    UpgradePathInfo.WifiLanSocket
                        .newBuilder()
                        .setIpAddress(ByteString.copyFrom(credentials.ipAddress))
                        .setWifiPort(credentials.port)
                        .build(),
                )
            }
            // Bluetooth RFCOMM (#51): emit the BluetoothCredentials
            // sub-message. The proto carries mac_address as a string,
            // so format the raw bytes back to "AA:BB:CC:DD:EE:FF" here.
            // service_name carries the SDP UUID the receiver registered;
            // see decodeCredentials for why we tag it as the UUID
            // identifier rather than a free-form name field.
            is UpgradePathCredentials.Bluetooth -> {
                builder.setBluetoothCredentials(
                    UpgradePathInfo.BluetoothCredentials
                        .newBuilder()
                        .setMacAddress(credentials.macAddressString())
                        .setServiceName(credentials.serviceUuid)
                        .build(),
                )
            }
        }
        return builder.build()
    }

    /**
     * Wrap an inner [BandwidthUpgradeNegotiationFrame] in the
     * V1/OfflineFrame envelope. Hoisted so every builder above is one
     * line of structural shape.
     */
    private fun wrap(inner: BandwidthUpgradeNegotiationFrame): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
                    .setBandwidthUpgradeNegotiation(inner)
                    .build(),
            ).build()

    /**
     * Sentinel value matching the proto's "field not set" semantics
     * for `SafeToClosePriorChannel.sta_frequency`. The proto declares
     * the field as `optional int32` with no default; -1 is what
     * `google/nearby` uses.
     */
    public const val STA_FREQUENCY_NOT_SET: Int = -1
}
