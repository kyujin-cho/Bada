/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
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
    fun `upgradePathAvailable carries Wi-Fi Aware credentials in WifiAwareCredentials slot`() {
        val ipv6 =
            byteArrayOf(
                0xFE.toByte(),
                0x80.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0x12,
                0x34,
                0xAB.toByte(),
                0xCD.toByte(),
                0xEF.toByte(),
                0x01,
                0x02,
            )
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.WifiAware(
                    serviceName = "wvmg-quickshare-aware",
                    ipv6Address = ipv6,
                    port = 8443,
                    passphrase = "abcdefghij0123456789",
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_AWARE.wireNumber)
        assertThat(parsed.upgradePathInfo.hasWifiAwareCredentials()).isTrue()
        assertThat(parsed.upgradePathInfo.wifiAwareCredentials.serviceId).isEqualTo("wvmg-quickshare-aware")
        assertThat(parsed.upgradePathInfo.wifiAwareCredentials.password).isEqualTo("abcdefghij0123456789")

        // service_info packs the IPv6 (16 bytes) and the port
        // (zero-padded to 4 bytes, big-endian) into a 20-byte payload.
        val serviceInfo =
            parsed.upgradePathInfo.wifiAwareCredentials.serviceInfo
                .toByteArray()
        assertThat(serviceInfo).hasLength(20)
        assertThat(serviceInfo.copyOfRange(0, 16)).isEqualTo(ipv6)
        // Port 8443 = 0x20FB; high two bytes zero, then 0x20, 0xFB.
        assertThat(serviceInfo[16]).isEqualTo(0.toByte())
        assertThat(serviceInfo[17]).isEqualTo(0.toByte())
        assertThat(serviceInfo[18]).isEqualTo(0x20.toByte())
        assertThat(serviceInfo[19]).isEqualTo(0xFB.toByte())
    }

    @Test
    fun `decodeCredentials round-trips Wi-Fi Aware credentials`() {
        val ipv6 =
            byteArrayOf(
                0xFE.toByte(),
                0x80.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0xAA.toByte(),
                0xBB.toByte(),
                0xCC.toByte(),
                0xDD.toByte(),
                0xEE.toByte(),
                0xFF.toByte(),
                0x01,
                0x23,
            )
        val original =
            UpgradePathCredentials.WifiAware(
                serviceName = "wvmg-quickshare-aware",
                ipv6Address = ipv6,
                port = 4242,
                passphrase = "very-secret-passphrase-32chars!!",
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials returns Generic for Wi-Fi Aware with malformed service_info`() {
        // Build an UpgradePathInfo manually with a too-short service_info
        // so the decoder cannot extract IPv6 + port; it should fall back
        // to Generic rather than producing garbage credentials.
        val info =
            com.google.location.nearby.connections.proto
                .OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
                .newBuilder()
                .setMedium(Medium.WIFI_AWARE.toUpgradePathMedium())
                .setWifiAwareCredentials(
                    com.google.location.nearby.connections.proto
                        .OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo.WifiAwareCredentials
                        .newBuilder()
                        .setServiceId("svc")
                        .setServiceInfo(
                            com.google.protobuf.ByteString
                                .copyFrom(ByteArray(8)),
                        ).setPassword("pw")
                        .build(),
                ).build()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(info)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.WIFI_AWARE))
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
    fun `BLE L2CAP credentials round-trip via the BLUETOOTH wire slot`() {
        val original =
            UpgradePathCredentials.BleL2cap(
                macAddress = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x11, 0x22, 0x33),
                psm = 0x1080,
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)

        // Wire shape: BLUETOOTH medium with the L2CAP discriminator on
        // service_name; this is what stays strictly inside the proto.
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.BLUETOOTH.wireNumber)
        assertThat(parsed.upgradePathInfo.hasBluetoothCredentials()).isTrue()
        assertThat(parsed.upgradePathInfo.bluetoothCredentials.serviceName)
            .isEqualTo("${BandwidthUpgradeFrames.BLE_L2CAP_SERVICE_PREFIX}${original.psm}")
        assertThat(parsed.upgradePathInfo.bluetoothCredentials.macAddress).isEqualTo("AA:BB:CC:11:22:33")

        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `BLE L2CAP decode rejects malformed PSM and falls back to Generic BLUETOOTH`() {
        // Hand-craft a BLUETOOTH frame with a bad PSM token. The decoder
        // must NOT throw — it falls through to Generic so the caller can
        // route the upgrade as a no-op.
        val info =
            UpgradePathInfo
                .newBuilder()
                .setMedium(UpgradePathInfo.Medium.BLUETOOTH)
                .setBluetoothCredentials(
                    UpgradePathInfo.BluetoothCredentials
                        .newBuilder()
                        .setMacAddress("AA:BB:CC:11:22:33")
                        .setServiceName("L2CAP:not-a-number")
                        .build(),
                ).build()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(info)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.BLUETOOTH))
    }

    @Test
    fun `BLE L2CAP decode rejects malformed MAC and falls back to Generic BLUETOOTH`() {
        val info =
            UpgradePathInfo
                .newBuilder()
                .setMedium(UpgradePathInfo.Medium.BLUETOOTH)
                .setBluetoothCredentials(
                    UpgradePathInfo.BluetoothCredentials
                        .newBuilder()
                        .setMacAddress("not-a-mac")
                        .setServiceName("L2CAP:1024")
                        .build(),
                ).build()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(info)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.BLUETOOTH))
    }

    @Test
    fun `BLE L2CAP credentials reject MAC of wrong length`() {
        try {
            UpgradePathCredentials.BleL2cap(macAddress = byteArrayOf(1, 2, 3), psm = 0x1234)
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("6 bytes")
        }
    }

    @Test
    fun `BLE L2CAP credentials reject PSM out of uint16 range`() {
        try {
            UpgradePathCredentials.BleL2cap(
                macAddress = ByteArray(UpgradePathCredentials.BleL2cap.MAC_ADDRESS_LENGTH),
                psm = 0x1_0000,
            )
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("uint16")
        }
    }

    @Test
    fun `upgradePathAvailable serializes Wi-Fi Direct credentials onto the proto sub-message`() {
        // Receiver side: we have a P2P group up at 192.168.49.1:8888,
        // SSID DIRECT-aB-test, passphrase deadbeef, channel 2437 MHz.
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.WifiDirect(
                    ipAddress = byteArrayOf(192.toByte(), 168.toByte(), 49, 1),
                    port = 8888,
                    ssid = "DIRECT-aB-test",
                    passphrase = "deadbeef",
                    frequency = 2437,
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_DIRECT.wireNumber)
        assertThat(parsed.upgradePathInfo.hasWifiDirectCredentials()).isTrue()
        val direct = parsed.upgradePathInfo.wifiDirectCredentials
        assertThat(direct.ssid).isEqualTo("DIRECT-aB-test")
        assertThat(direct.password).isEqualTo("deadbeef")
        assertThat(direct.port).isEqualTo(8888)
        // Gateway is dotted-quad on the wire; we verify the
        // sign-extension fix (192 → 192, not -64).
        assertThat(direct.gateway).isEqualTo("192.168.49.1")
        assertThat(direct.frequency).isEqualTo(2437)
    }

    @Test
    fun `upgradePathAvailable omits frequency when not set`() {
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.WifiDirect(
                    ipAddress = byteArrayOf(10, 0, 0, 1),
                    port = 9000,
                    ssid = "DIRECT-zz-no-freq",
                    passphrase = "secret",
                    // frequency defaults to FREQUENCY_NOT_SET (-1).
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.wifiDirectCredentials.hasFrequency()).isFalse()
    }

    @Test
    fun `decodeCredentials round-trips Wi-Fi Direct`() {
        val original =
            UpgradePathCredentials.WifiDirect(
                ipAddress = byteArrayOf(192.toByte(), 168.toByte(), 49, 1),
                port = 8443,
                ssid = "DIRECT-Xy-WhenVivo",
                passphrase = "C0rrectH0rseBatteryStaple",
                frequency = 5180,
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials falls back to Generic when Wi-Fi Direct sub-message is absent`() {
        // The peer announces WIFI_DIRECT as the medium but did not set
        // any credentials — we treat that as "an upgrade was offered
        // with no parameters", which collapses to Generic so the
        // negotiator does not protocol-error.
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.Generic(Medium.WIFI_DIRECT),
            )
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.WIFI_DIRECT))
    }

    @Test
    fun `WifiDirect rejects non-IPv4 ipAddress`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            UpgradePathCredentials.WifiDirect(
                ipAddress = ByteArray(16), // IPv6-shaped, not supported by the gateway field.
                port = 8443,
                ssid = "DIRECT-bad",
                passphrase = "pw",
            )
        }
    }

    @Test
    fun `WifiDirect equality compares ByteArray structurally`() {
        // Regression guard: data class auto-generated equals would
        // compare the ByteArray by reference, breaking decoder
        // round-trip assertions like the one above.
        val a =
            UpgradePathCredentials.WifiDirect(
                ipAddress = byteArrayOf(10, 0, 0, 1),
                port = 1,
                ssid = "s",
                passphrase = "p",
            )
        val b =
            UpgradePathCredentials.WifiDirect(
                ipAddress = byteArrayOf(10, 0, 0, 1),
                port = 1,
                ssid = "s",
                passphrase = "p",
            )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `upgradePathAvailable carries Wi-Fi Hotspot credentials`() {
        val frame =
            BandwidthUpgradeFrames.upgradePathAvailable(
                UpgradePathCredentials.WifiHotspot(
                    ssid = "DIRECT-AB-Quickshare",
                    passphrase = "p4ssphrase",
                    port = 41201,
                    gateway = "192.168.49.1",
                    frequencyMhz = 5180,
                ),
            )
        val parsed = parse(frame)
        assertThat(parsed.upgradePathInfo.medium.number).isEqualTo(Medium.WIFI_HOTSPOT.wireNumber)
        assertThat(parsed.upgradePathInfo.hasWifiHotspotCredentials()).isTrue()
        val raw = parsed.upgradePathInfo.wifiHotspotCredentials
        assertThat(raw.ssid).isEqualTo("DIRECT-AB-Quickshare")
        assertThat(raw.password).isEqualTo("p4ssphrase")
        assertThat(raw.port).isEqualTo(41201)
        assertThat(raw.gateway).isEqualTo("192.168.49.1")
        assertThat(raw.frequency).isEqualTo(5180)
    }

    @Test
    fun `decodeCredentials round-trips Wi-Fi Hotspot with all fields`() {
        val original =
            UpgradePathCredentials.WifiHotspot(
                ssid = "AndroidShare_xyz",
                passphrase = "correcthorsebatterystaple",
                port = 53111,
                gateway = "192.168.49.1",
                frequencyMhz = 2412,
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials round-trips Wi-Fi Hotspot omitting optional fields`() {
        // The encoder skips gateway and frequency when they hold the
        // proto-default sentinels; the decoder must re-materialise the
        // sentinels from `hasGateway()` / `hasFrequency()` returning false.
        val original =
            UpgradePathCredentials.WifiHotspot(
                ssid = "DIRECT-CD",
                passphrase = "secret",
                port = 12345,
            )
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(original)
        val parsed = parse(frame)
        val raw = parsed.upgradePathInfo.wifiHotspotCredentials
        assertThat(raw.hasGateway()).isFalse()
        assertThat(raw.hasFrequency()).isFalse()
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeCredentials returns Generic when WIFI_HOTSPOT is missing the sub-message`() {
        // A bug-compatible peer might send `medium=WIFI_HOTSPOT` with no
        // `wifi_hotspot_credentials` populated. Treat it as a generic
        // "advertised but no credentials" frame so the upper layer can
        // fall back to UPGRADE_FAILURE rather than crashing.
        val frame = BandwidthUpgradeFrames.upgradePathAvailable(UpgradePathCredentials.Generic(Medium.WIFI_HOTSPOT))
        val parsed = parse(frame)
        val decoded = BandwidthUpgradeFrames.decodeCredentials(parsed.upgradePathInfo)
        assertThat(decoded).isEqualTo(UpgradePathCredentials.Generic(Medium.WIFI_HOTSPOT))
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
