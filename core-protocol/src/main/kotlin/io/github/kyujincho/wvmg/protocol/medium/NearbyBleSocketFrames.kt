/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.location.nearby.mediums.proto.BleFramesProto
import com.google.protobuf.ByteString
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId

/**
 * Helpers for the Nearby BLE socket control packets carried inside the
 * platform BLE GATT socket.
 */
public object NearbyBleSocketFrames {
    /** Number of bytes in a Nearby service-id hash prefix. */
    public const val SERVICE_ID_HASH_LEN: Int = 3

    /** Three zero bytes mark a BLE socket control packet. */
    public const val CONTROL_PACKET_PREFIX_LEN: Int = 3

    private val controlPacketPrefix: ByteArray = ByteArray(CONTROL_PACKET_PREFIX_LEN)

    /** Return a fresh copy of the control-packet prefix. */
    @JvmStatic
    public fun controlPacketPrefix(): ByteArray = controlPacketPrefix.copyOf()

    /**
     * Encode the BLE socket introduction packet stock Nearby sends
     * immediately after the lower GATT socket is established.
     */
    @JvmStatic
    public fun encodeIntroductionPacket(serviceIdHash: ByteArray = NearbyServiceId.hashPrefix): ByteArray {
        validateServiceIdHash(serviceIdHash)
        val introduction =
            BleFramesProto.IntroductionFrame
                .newBuilder()
                .setServiceIdHash(ByteString.copyFrom(serviceIdHash))
                .setSocketVersion(BleFramesProto.SocketVersion.V2)
                .build()
        val frame =
            BleFramesProto.SocketControlFrame
                .newBuilder()
                .setType(BleFramesProto.SocketControlFrame.ControlFrameType.INTRODUCTION)
                .setIntroduction(introduction)
                .build()
                .toByteArray()
        return controlPacketPrefix + frame
    }

    /** Encode a BLE socket disconnection control packet. */
    @JvmStatic
    public fun encodeDisconnectionPacket(serviceIdHash: ByteArray = NearbyServiceId.hashPrefix): ByteArray {
        validateServiceIdHash(serviceIdHash)
        val disconnection =
            BleFramesProto.DisconnectionFrame
                .newBuilder()
                .setServiceIdHash(ByteString.copyFrom(serviceIdHash))
                .build()
        val frame =
            BleFramesProto.SocketControlFrame
                .newBuilder()
                .setType(BleFramesProto.SocketControlFrame.ControlFrameType.DISCONNECTION)
                .setDisconnection(disconnection)
                .build()
                .toByteArray()
        return controlPacketPrefix + frame
    }

    /** Encode a BLE socket acknowledgement for the last data packet length read. */
    @JvmStatic
    public fun encodePacketAcknowledgementPacket(
        receivedSize: Int,
        serviceIdHash: ByteArray = NearbyServiceId.hashPrefix,
    ): ByteArray {
        validateServiceIdHash(serviceIdHash)
        require(receivedSize >= 0) { "receivedSize must be non-negative" }
        val acknowledgement =
            BleFramesProto.PacketAcknowledgementFrame
                .newBuilder()
                .setServiceIdHash(ByteString.copyFrom(serviceIdHash))
                .setReceivedSize(receivedSize)
                .build()
        val frame =
            BleFramesProto.SocketControlFrame
                .newBuilder()
                .setType(BleFramesProto.SocketControlFrame.ControlFrameType.PACKET_ACKNOWLEDGEMENT)
                .setPacketAcknowledgement(acknowledgement)
                .build()
                .toByteArray()
        return controlPacketPrefix + frame
    }

    /** Parse a complete prefix + proto BLE socket control packet. */
    @JvmStatic
    public fun parseControlPacket(packet: ByteArray): BleFramesProto.SocketControlFrame? {
        val hasControlPrefix =
            packet.size > CONTROL_PACKET_PREFIX_LEN &&
                controlPacketPrefix.indices.all { index -> packet[index] == 0.toByte() }
        if (!hasControlPrefix) return null
        val proto = packet.copyOfRange(CONTROL_PACKET_PREFIX_LEN, packet.size)
        return runCatching { BleFramesProto.SocketControlFrame.parseFrom(proto) }.getOrNull()
    }

    private fun validateServiceIdHash(serviceIdHash: ByteArray) {
        require(serviceIdHash.size == SERVICE_ID_HASH_LEN) {
            "serviceIdHash must be $SERVICE_ID_HASH_LEN bytes, got ${serviceIdHash.size}"
        }
    }
}
