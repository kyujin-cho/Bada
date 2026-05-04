/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.EndpointType
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.MediumMetadata
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import io.github.kyujincho.wvmg.protocol.medium.Medium
import org.junit.jupiter.api.Test

/**
 * Pins the `ConnectionRequestFrame.mediums` field shape. Two
 * invariants:
 *
 *   1. Default behaviour (no mediums passed) advertises Wi-Fi LAN
 *      only — functionally identical to the project's pre-Phase-4
 *      hard-coded shape and to NearDrop.
 *   2. Multiple mediums survive the wire round-trip without hidden
 *      expansion. Callers that own an off-LAN bootstrap must be able to
 *      choose whether the legacy Wi-Fi LAN capability marker belongs in
 *      that request shape.
 */
class ConnectionRequestMediumsTest {
    @Test
    fun `default ConnectionRequest advertises Wi-Fi LAN only`() {
        val frame = OutboundFrames.connectionRequest("ABCD", ByteArray(0))
        val mediums = parseRequest(frame).mediumsList.map { it.number }
        assertThat(mediums).containsExactly(Medium.WIFI_LAN.wireNumber)
    }

    @Test
    fun `caller-supplied mediums all reach the wire`() {
        val frame =
            OutboundFrames.connectionRequest(
                endpointId = "ABCD",
                endpointInfo = ByteArray(0),
                supportedMediums =
                    setOf(Medium.WIFI_LAN, Medium.WIFI_DIRECT, Medium.BLE_L2CAP),
            )
        val numbers = parseRequest(frame).mediumsList.map { it.number }.toSet()
        assertThat(numbers).containsExactly(
            Medium.WIFI_LAN.wireNumber,
            Medium.WIFI_DIRECT.wireNumber,
            Medium.BLE_L2CAP.wireNumber,
        )
    }

    @Test
    fun `caller supplied off-LAN mediums can omit Wi-Fi LAN`() {
        val frame =
            OutboundFrames.connectionRequest(
                endpointId = "ABCD",
                endpointInfo = ByteArray(0),
                supportedMediums = setOf(Medium.BLUETOOTH),
            )
        val numbers = parseRequest(frame).mediumsList.map { it.number }.toSet()
        assertThat(numbers).containsExactly(Medium.BLUETOOTH.wireNumber)
    }

    @Test
    fun `wire ordering is deterministic by Medium number`() {
        val frame =
            OutboundFrames.connectionRequest(
                endpointId = "ABCD",
                endpointInfo = ByteArray(0),
                supportedMediums =
                    setOf(Medium.BLE_L2CAP, Medium.WIFI_LAN, Medium.BLUETOOTH),
            )
        val numbers = parseRequest(frame).mediumsList.map { it.number }
        assertThat(numbers).isInOrder()
    }

    @Test
    fun `keep_alive fields endpoint_id and connections_device survive the change`() {
        // Sanity: pinning the mediums field must not have regressed
        // the other fields ConnectionRequestFrame is expected to
        // populate (One UI 8.0.5 silent-FIN guards live here).
        val endpointInfo = byteArrayOf(0x01, 0x02, 0x03)
        val frame = OutboundFrames.connectionRequest("XYZW", endpointInfo)
        val req = parseRequest(frame)
        assertThat(req.endpointId).isEqualTo("XYZW")
        assertThat(req.endpointName).isEqualTo("")
        assertThat(req.endpointInfo.toByteArray()).isEqualTo(endpointInfo)
        assertThat(req.keepAliveIntervalMillis).isEqualTo(10_000)
        assertThat(req.keepAliveTimeoutMillis).isEqualTo(600_000)
        assertThat(req.hasNonce()).isTrue()
        assertThat(req.hasMediumMetadata()).isTrue()
        assertThat(req.mediumMetadata.apFrequency).isEqualTo(-1)
        assertThat(req.hasConnectionsDevice()).isTrue()
        assertThat(req.connectionsDevice.endpointId).isEqualTo("XYZW")
        assertThat(req.connectionsDevice.endpointType).isEqualTo(EndpointType.CONNECTIONS_ENDPOINT)
        assertThat(req.connectionsDevice.endpointInfo.toByteArray()).isEqualTo(endpointInfo)
    }

    @Test
    fun `Wi-Fi Direct requests advertise password auth support without opening role`() {
        val frame =
            OutboundFrames.connectionRequest(
                endpointId = "ABCD",
                endpointInfo = ByteArray(0),
                supportedMediums = setOf(Medium.WIFI_DIRECT, Medium.BLE),
            )
        val metadata = parseRequest(frame).mediumMetadata
        assertThat(metadata.supportedWifiDirectAuthTypesList)
            .containsExactly(MediumMetadata.WifiDirectAuthType.WIFI_DIRECT_WITH_PASSWORD)
        assertThat(metadata.hasMediumRole()).isFalse()
    }

    @Test
    fun `caller supplied nonce reaches the wire`() {
        val frame =
            OutboundFrames.connectionRequest(
                endpointId = "ABCD",
                endpointInfo = ByteArray(0),
                nonce = 0x12345678,
            )
        val req = parseRequest(frame)
        assertThat(req.hasNonce()).isTrue()
        assertThat(req.nonce).isEqualTo(0x12345678)
    }

    private fun parseRequest(frame: OfflineFrame): ConnectionRequestFrame {
        val bytes = frame.toByteArray()
        return OfflineFrame.parseFrom(bytes).v1.connectionRequest
    }
}
