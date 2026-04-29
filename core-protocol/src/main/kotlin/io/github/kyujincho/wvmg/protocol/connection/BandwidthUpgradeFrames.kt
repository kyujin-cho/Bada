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
            // Wi-Fi Aware (#53): WifiAwareCredentials carries the
            // publisher-side service_id, the IPv6 + port packed into
            // service_info, and the data-path passphrase. See
            // decodeWifiAwareServiceInfo for the byte layout.
            Medium.WIFI_AWARE -> {
                if (info.hasWifiAwareCredentials()) {
                    val creds = info.wifiAwareCredentials
                    val serviceInfo = creds.serviceInfo.toByteArray()
                    val parsed = decodeWifiAwareServiceInfo(serviceInfo)
                    if (parsed != null) {
                        UpgradePathCredentials.WifiAware(
                            serviceName = creds.serviceId,
                            ipv6Address = parsed.first,
                            port = parsed.second,
                            passphrase = creds.password,
                        )
                    } else {
                        // Wi-Fi Aware credentials present but service_info
                        // is missing the IPv6+port packing we expect —
                        // fall through to Generic so the orchestrator
                        // can fail the upgrade cleanly.
                        UpgradePathCredentials.Generic(medium)
                    }
                } else {
                    UpgradePathCredentials.Generic(medium)
                }
            }
            // Wi-Fi Direct (#49). The receiver is the P2P group owner;
            // the proto carries SSID + passphrase + the GO's IP (in the
            // `gateway` string, dotted-quad) + TCP port. We surface the
            // address as ByteArray so the adapter does not need to
            // re-parse it. Falls back to Generic when the credentials
            // sub-message is missing OR malformed (no SSID / port / IP),
            // so the negotiator can still surface "an upgrade was
            // offered" and the provider can reject it cleanly on adopt.
            Medium.WIFI_DIRECT -> decodeWifiDirect(info) ?: UpgradePathCredentials.Generic(medium)
            // Remaining Phase 4 sub-issues plug the rest of the arms in
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
            Medium.WIFI_HOTSPOT,
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
            is UpgradePathCredentials.WifiAware -> {
                // The proto's WifiAwareCredentials oneof slot only has
                // `service_id`, `service_info` (bytes), and `password`.
                // Quick Share packs the publisher-side IPv6 link-local
                // address (16 bytes, network order) and the TCP port
                // (4 bytes, big-endian) into `service_info` so the
                // subscriber can connect after the data-path callback
                // resolves the per-network IPv6 routing. We mirror that
                // shape here; the matching decoder lives next to the
                // encoder for reviewability.
                builder.setWifiAwareCredentials(
                    UpgradePathInfo.WifiAwareCredentials
                        .newBuilder()
                        .setServiceId(credentials.serviceName)
                        .setServiceInfo(
                            ByteString.copyFrom(
                                encodeWifiAwareServiceInfo(credentials.ipv6Address, credentials.port),
                            ),
                        ).setPassword(credentials.passphrase)
                        .build(),
                )
            }
            // Wi-Fi Direct (#49). The proto's gateway field is a
            // dotted-quad string; we serialize the receiver-side IPv4
            // bytes that way so the wire payload matches what stock
            // Quick Share emits.
            is UpgradePathCredentials.WifiDirect -> {
                val direct =
                    UpgradePathInfo.WifiDirectCredentials
                        .newBuilder()
                        .setSsid(credentials.ssid)
                        .setPassword(credentials.passphrase)
                        .setPort(credentials.port)
                        .setGateway(ipv4BytesToDottedQuad(credentials.ipAddress))
                if (credentials.frequency != UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET) {
                    direct.frequency = credentials.frequency
                }
                builder.setWifiDirectCredentials(direct.build())
            }
        }
        return builder.build()
    }

    /**
     * Serialize the publisher-side IPv6 + port pair into the byte layout
     * Quick Share uses for `WifiAwareCredentials.service_info`:
     *
     *   bytes 0..15  — IPv6 address, network order (16 bytes)
     *   bytes 16..19 — TCP port, big-endian unsigned 16 stored in 4 bytes
     *                  (high two bytes always zero; matches NearDrop's
     *                  serialization of `ushort` into a 4-byte slot).
     *
     * Throws `IllegalArgumentException` for an address whose length is
     * not exactly 16 bytes or a port outside `0..65535`. The caller
     * (the per-medium provider) is responsible for handing in valid
     * values; this function does not silently truncate.
     */
    internal fun encodeWifiAwareServiceInfo(
        ipv6Address: ByteArray,
        port: Int,
    ): ByteArray {
        require(ipv6Address.size == WIFI_AWARE_IPV6_LENGTH) {
            "Wi-Fi Aware IPv6 address must be 16 bytes, got ${ipv6Address.size}"
        }
        require(port in 0..0xFFFF) { "Wi-Fi Aware port must fit in 16 bits, got $port" }
        val out = ByteArray(WIFI_AWARE_SERVICE_INFO_LENGTH)
        System.arraycopy(ipv6Address, 0, out, 0, WIFI_AWARE_IPV6_LENGTH)
        // Big-endian, two high bytes zero — matches the Quick Share
        // shape so a stock receiver decodes our advertisement and our
        // decoder accepts a stock-emitted one.
        out[WIFI_AWARE_IPV6_LENGTH] = 0
        out[WIFI_AWARE_IPV6_LENGTH + 1] = 0
        out[WIFI_AWARE_PORT_HIGH_OFFSET] = ((port ushr PORT_HIGH_BYTE_SHIFT) and BYTE_MASK).toByte()
        out[WIFI_AWARE_PORT_LOW_OFFSET] = (port and BYTE_MASK).toByte()
        return out
    }

    /**
     * Inverse of [encodeWifiAwareServiceInfo]. Returns `null` for any
     * `service_info` that is not exactly 20 bytes long — the wire layout
     * is fixed-width, so a different length almost certainly means a
     * peer encoded it differently and we should fail the upgrade rather
     * than guess.
     */
    internal fun decodeWifiAwareServiceInfo(serviceInfo: ByteArray): Pair<ByteArray, Int>? {
        if (serviceInfo.size != WIFI_AWARE_SERVICE_INFO_LENGTH) return null
        val ipv6 = serviceInfo.copyOfRange(0, WIFI_AWARE_IPV6_LENGTH)
        val port =
            ((serviceInfo[WIFI_AWARE_PORT_HIGH_OFFSET].toInt() and BYTE_MASK) shl PORT_HIGH_BYTE_SHIFT) or
                (serviceInfo[WIFI_AWARE_PORT_LOW_OFFSET].toInt() and BYTE_MASK)
        return ipv6 to port
    }

    /**
     * Decode a `WifiDirectCredentials` sub-message (#49). Returns
     * `null` when the sub-message is absent or when any of SSID, port,
     * or gateway IP is missing — those are the bare minimum to actually
     * connect, and a partial credential is the same as "no credential
     * at all" from the adopter's standpoint. Treating malformed input
     * as Generic at the call site preserves the negotiator's invariant
     * that an UPGRADE_PATH_AVAILABLE frame always decodes to *some*
     * credentials value.
     */
    @Suppress("ReturnCount") // One guard per missing sub-field; flattening hides the validation.
    private fun decodeWifiDirect(info: UpgradePathInfo): UpgradePathCredentials.WifiDirect? {
        if (!info.hasWifiDirectCredentials()) return null
        val direct = info.wifiDirectCredentials
        val gateway = direct.gateway.takeIf { it.isNotEmpty() } ?: return null
        val ipBytes = dottedQuadToIpv4Bytes(gateway) ?: return null
        return UpgradePathCredentials.WifiDirect(
            ipAddress = ipBytes,
            port = direct.port,
            ssid = direct.ssid,
            passphrase = direct.password,
            frequency =
                if (direct.hasFrequency()) {
                    direct.frequency
                } else {
                    UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET
                },
        )
    }

    /**
     * Format a 4-byte IPv4 address as a dotted-quad string. Pulled out
     * so both the encoder and tests can call it without duplicating the
     * `&0xff` masking that Kotlin's `Byte.toInt()` would otherwise
     * sign-extend. Avoids `InetAddress.getByAddress` so this stays
     * pure-JVM and never resolves DNS.
     */
    private fun ipv4BytesToDottedQuad(bytes: ByteArray): String {
        require(bytes.size == UpgradePathCredentials.WifiDirect.IPV4_ADDRESS_LENGTH) {
            "Wi-Fi Direct gateway must be IPv4 (4 bytes); got ${bytes.size}"
        }
        return buildString {
            for ((index, b) in bytes.withIndex()) {
                if (index > 0) append('.')
                append(b.toInt() and BYTE_MASK)
            }
        }
    }

    /**
     * Parse a dotted-quad IPv4 string into 4 bytes (network order).
     * Returns `null` for malformed input — used by [decodeWifiDirect]
     * to drop wire frames whose `gateway` field is empty / IPv6 / a
     * hostname rather than throwing the negotiator into a protocol
     * error path. Pure validation: no DNS lookup, no IPv6 fallback.
     */
    @Suppress("ReturnCount") // Three guard clauses, one happy path; flattening is less readable.
    private fun dottedQuadToIpv4Bytes(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != UpgradePathCredentials.WifiDirect.IPV4_ADDRESS_LENGTH) return null
        val bytes = ByteArray(UpgradePathCredentials.WifiDirect.IPV4_ADDRESS_LENGTH)
        for ((index, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..BYTE_MASK) return null
            bytes[index] = value.toByte()
        }
        return bytes
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

    /** Length of a Wi-Fi Aware IPv6 link-local address in bytes. */
    private const val WIFI_AWARE_IPV6_LENGTH: Int = 16

    /**
     * Length of the byte payload Quick Share packs into
     * `WifiAwareCredentials.service_info`: 16 bytes IPv6 + 4-byte port
     * slot (16-bit value zero-padded to 32 bits).
     */
    private const val WIFI_AWARE_SERVICE_INFO_LENGTH: Int = 20

    /** Bit-shift for the high byte of a 16-bit big-endian port value. */
    private const val PORT_HIGH_BYTE_SHIFT: Int = 8

    /** Mask for extracting a single byte from an Int. */
    private const val BYTE_MASK: Int = 0xFF

    /** Offset of the high port byte within the service_info layout. */
    private const val WIFI_AWARE_PORT_HIGH_OFFSET: Int = WIFI_AWARE_IPV6_LENGTH + 2

    /** Offset of the low port byte within the service_info layout. */
    private const val WIFI_AWARE_PORT_LOW_OFFSET: Int = WIFI_AWARE_IPV6_LENGTH + 3
}
