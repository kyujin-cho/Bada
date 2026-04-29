/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import org.junit.jupiter.api.Test

private typealias UpgradePathInfo = BandwidthUpgradeNegotiationFrame.UpgradePathInfo
private typealias BluetoothCredentialsProto = BandwidthUpgradeNegotiationFrame.UpgradePathInfo.BluetoothCredentials

class BandwidthUpgradeFramesTest {
    @Test
    fun `upgradePathAvailable carries medium and credentials`() {
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.WifiLan(
                    ipAddress = byteArrayOf(192.toByte(), 168.toByte(), 1, 42),
                    port = 4433,
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_LAN.wireNumber)
        assertThat(parsed.upgradePathInfo.hasWifiLanSocket()).isTrue()
        assertThat(parsed.upgradePathInfo.wifiLanSocket.wifiPort).isEqualTo(4433)
        assertThat(
            parsed.upgradePathInfo.wifiLanSocket.ipAddress
                .toByteArray(),
        ).isEqualTo(byteArrayOf(192.toByte(), 168.toByte(), 1, 42))
    }

    @Test
    fun `upgradePathAvailable supports Generic credentials (medium-only)`() {
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.Generic(Medium.WIFI_DIRECT),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_DIRECT.wireNumber)
        assertThat(parsed.upgradePathInfo.hasWifiLanSocket()).isFalse()
    }

    @Test
    fun `clientIntroduction round-trips through OfflineFrame`() {
        val frame = BandwidthUpgradeFrames.clientIntroduction("ABCD")
        val parsed = parse(frame)
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
        assertThat(parsed.clientIntroduction.endpointId).isEqualTo("ABCD")
    }

    @Test
    fun `clientIntroductionAck has the ack sub-message set`() {
        val frame = BandwidthUpgradeFrames.clientIntroductionAck()
        val parsed = parse(frame)
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK)
        assertThat(parsed.hasClientIntroductionAck()).isTrue()
    }

    @Test
    fun `safeToClosePriorChannel defaults to STA_FREQUENCY_NOT_SET`() {
        val frame = BandwidthUpgradeFrames.safeToClosePriorChannel()
        val parsed = parse(frame)
        assertThat(parsed.safeToClosePriorChannel.staFrequency)
            .isEqualTo(BandwidthUpgradeFrames.STA_FREQUENCY_NOT_SET)
    }

    @Test
    fun `lastWriteToPriorChannel sets the event type without payload`() {
        val frame = BandwidthUpgradeFrames.lastWriteToPriorChannel()
        val parsed = parse(frame)
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL)
    }

    @Test
    fun `upgradeFailure carries the failed medium back to the peer`() {
        val frame = BandwidthUpgradeFrames.upgradeFailure(Medium.WIFI_DIRECT)
        val parsed = parse(frame)
        assertThat(parsed.eventType).isEqualTo(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_DIRECT.wireNumber)
    }

    @Test
    fun `decodeCredentials round-trips Wi-Fi LAN`() {
        val original =
            UpgradePathCredentials.WifiLan(
                ipAddress = byteArrayOf(10, 0, 0, 1),
                port = 8000,
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials returns Generic for not-yet-implemented mediums`() {
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(UpgradePathCredentials.Generic(Medium.WIFI_DIRECT))
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.WIFI_DIRECT))
    }

    @Test
    fun `upgradePathAvailable carries Bluetooth credentials with MAC and service UUID`() {
        val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val uuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a"
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.Bluetooth(
                    macAddress = mac,
                    serviceUuid = uuid,
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.BLUETOOTH.wireNumber)
        assertThat(parsed.upgradePathInfo.hasBluetoothCredentials()).isTrue()
        assertThat(parsed.upgradePathInfo.bluetoothCredentials.macAddress).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(parsed.upgradePathInfo.bluetoothCredentials.serviceName).isEqualTo(uuid)
    }

    @Test
    fun `decodeCredentials round-trips Bluetooth credentials`() {
        val original =
            UpgradePathCredentials.Bluetooth(
                macAddress = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
                serviceUuid = "a82efa21-ae5c-3dde-9bbc-f16da7b16c1a",
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials falls back to Generic for Bluetooth with empty MAC`() {
        // Build a malformed UPGRADE_PATH_AVAILABLE manually so we can
        // exercise the "missing MAC -> Generic" fallback path that
        // protects the negotiator from a misbehaving peer.
        val info =
            UpgradePathInfo
                .newBuilder()
                .setMedium(Medium.BLUETOOTH.toUpgradePathMedium())
                .build()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(info)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.BLUETOOTH))
    }

    @Test
    fun `decodeCredentials falls back to Generic for Bluetooth with malformed MAC string`() {
        val info =
            UpgradePathInfo
                .newBuilder()
                .setMedium(Medium.BLUETOOTH.toUpgradePathMedium())
                .setBluetoothCredentials(
                    BluetoothCredentialsProto
                        .newBuilder()
                        .setMacAddress("not-a-mac")
                        .setServiceName("a82efa21-ae5c-3dde-9bbc-f16da7b16c1a")
                        .build(),
                ).build()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(info)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.BLUETOOTH))
    }

    @Test
    fun `every builder produces a V1 BANDWIDTH_UPGRADE_NEGOTIATION envelope`() {
        val frames =
            listOf(
                BandwidthUpgradeFrames.upgradePathAvailable(UpgradePathCredentials.Generic(Medium.WIFI_LAN)),
                BandwidthUpgradeFrames.clientIntroduction("ABCD"),
                BandwidthUpgradeFrames.clientIntroductionAck(),
                BandwidthUpgradeFrames.lastWriteToPriorChannel(),
                BandwidthUpgradeFrames.safeToClosePriorChannel(),
                BandwidthUpgradeFrames.upgradeFailure(Medium.WIFI_DIRECT),
            )
        for (frame in frames) {
            assertThat(frame.version).isEqualTo(OfflineFrame.Version.V1)
            assertThat(frame.v1.type).isEqualTo(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
            assertThat(frame.v1.hasBandwidthUpgradeNegotiation()).isTrue()
        }
    }

    private fun parse(frame: OfflineFrame): BandwidthUpgradeNegotiationFrame {
        // Round-trip through serialization so the test catches any
        // wrap helper that forgets to set a oneof slot.
        val bytes = frame.toByteArray()
        return OfflineFrame.parseFrom(bytes).v1.bandwidthUpgradeNegotiation
    }
}
