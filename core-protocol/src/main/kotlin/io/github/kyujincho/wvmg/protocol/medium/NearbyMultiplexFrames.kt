/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MagicNumber")

package io.github.kyujincho.wvmg.protocol.medium

import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import com.google.protobuf.ByteString
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import java.security.MessageDigest

/**
 * Minimal encoder/parser for Nearby's multiplex socket framing.
 *
 * A stock BLE socket is only the physical pipe. Nearby wraps it in a
 * length-prefixed MultiplexFrame stream, then presents a virtual socket to
 * the normal OfflineFrame protocol.
 */
public object NearbyMultiplexFrames {
    /** 4-byte big-endian physical frame length prefix. */
    public const val LENGTH_PREFIX_BYTES: Int = 4

    /** Nearby multiplex service-id hashes are SHA-256 prefixes. */
    public const val SERVICE_ID_HASH_LEN: Int = 3

    /** Return `SHA-256(serviceId).first(3)`. */
    @JvmStatic
    public fun serviceIdHash(serviceId: String = NearbyServiceId.VALUE): ByteArray =
        sha256(serviceId.toByteArray(Charsets.UTF_8)).copyOf(SERVICE_ID_HASH_LEN)

    /** Return `SHA-256(serviceId + salt).first(3)`. */
    @JvmStatic
    public fun saltedServiceIdHash(
        serviceId: String = NearbyServiceId.VALUE,
        salt: String,
    ): ByteArray = sha256((serviceId + salt).toByteArray(Charsets.UTF_8)).copyOf(SERVICE_ID_HASH_LEN)

    /** Prefix [payload] with its 4-byte big-endian length. */
    @JvmStatic
    public fun encodeLengthPrefixed(payload: ByteArray): ByteArray {
        val out = ByteArray(LENGTH_PREFIX_BYTES + payload.size)
        writeLength(payload.size, out, 0)
        payload.copyInto(out, destinationOffset = LENGTH_PREFIX_BYTES)
        return out
    }

    /** Decode a 4-byte big-endian length prefix from [bytes] at [offset]. */
    @JvmStatic
    public fun decodeLength(
        bytes: ByteArray,
        offset: Int = 0,
    ): Int? {
        if (offset < 0 || bytes.size - offset < LENGTH_PREFIX_BYTES) return null
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    /** Parse one serialized MultiplexFrame, returning `null` when invalid. */
    @JvmStatic
    public fun parseFrame(bytes: ByteArray): MultiplexFramesProto.MultiplexFrame? =
        runCatching { MultiplexFramesProto.MultiplexFrame.parseFrom(bytes) }
            .getOrNull()
            ?.takeIf(::isValid)

    /** Build a CONNECTION_ACCEPTED / NOT_LISTENING response frame. */
    @JvmStatic
    public fun encodeConnectionResponseFrame(
        saltedServiceIdHash: ByteArray,
        serviceIdHashSalt: String,
        responseCode: MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode =
            MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode.CONNECTION_ACCEPTED,
    ): ByteArray {
        validateServiceIdHash(saltedServiceIdHash)
        val header =
            MultiplexFramesProto.MultiplexFrameHeader
                .newBuilder()
                .setSaltedServiceIdHash(ByteString.copyFrom(saltedServiceIdHash))
                .setServiceIdHashSalt(serviceIdHashSalt)
                .build()
        val responseFrame =
            MultiplexFramesProto.ConnectionResponseFrame
                .newBuilder()
                .setConnectionResponseCode(responseCode)
                .build()
        val controlFrame =
            MultiplexFramesProto.MultiplexControlFrame
                .newBuilder()
                .setControlFrameType(
                    MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_RESPONSE,
                ).setConnectionResponseFrame(responseFrame)
                .build()
        val frame =
            MultiplexFramesProto.MultiplexFrame
                .newBuilder()
                .setFrameType(MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME)
                .setHeader(header)
                .setControlFrame(controlFrame)
                .build()
        return frame.toByteArray()
    }

    /** Build a CONNECTION_REQUEST control frame for opening one virtual socket. */
    @JvmStatic
    public fun encodeConnectionRequestFrame(
        saltedServiceIdHash: ByteArray,
        serviceIdHashSalt: String,
    ): ByteArray {
        validateServiceIdHash(saltedServiceIdHash)
        val header =
            MultiplexFramesProto.MultiplexFrameHeader
                .newBuilder()
                .setSaltedServiceIdHash(ByteString.copyFrom(saltedServiceIdHash))
                .setServiceIdHashSalt(serviceIdHashSalt)
                .build()
        val controlFrame =
            MultiplexFramesProto.MultiplexControlFrame
                .newBuilder()
                .setControlFrameType(
                    MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.CONNECTION_REQUEST,
                ).setConnectionRequestFrame(MultiplexFramesProto.ConnectionRequestFrame.getDefaultInstance())
                .build()
        val frame =
            MultiplexFramesProto.MultiplexFrame
                .newBuilder()
                .setFrameType(MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME)
                .setHeader(header)
                .setControlFrame(controlFrame)
                .build()
        return frame.toByteArray()
    }

    /** Build a DATA_FRAME carrying [data] for a virtual socket. */
    @JvmStatic
    public fun encodeDataFrame(
        saltedServiceIdHash: ByteArray,
        data: ByteArray,
        serviceIdHashSalt: String? = null,
    ): ByteArray {
        validateServiceIdHash(saltedServiceIdHash)
        val header =
            MultiplexFramesProto.MultiplexFrameHeader
                .newBuilder()
                .setSaltedServiceIdHash(ByteString.copyFrom(saltedServiceIdHash))
                .also { builder ->
                    if (serviceIdHashSalt != null) builder.setServiceIdHashSalt(serviceIdHashSalt)
                }.build()
        val dataFrame =
            MultiplexFramesProto.MultiplexDataFrame
                .newBuilder()
                .setData(ByteString.copyFrom(data))
                .build()
        val frame =
            MultiplexFramesProto.MultiplexFrame
                .newBuilder()
                .setFrameType(MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME)
                .setHeader(header)
                .setDataFrame(dataFrame)
                .build()
        return frame.toByteArray()
    }

    /** Build a DISCONNECTION control frame. */
    @JvmStatic
    public fun encodeDisconnectionFrame(
        saltedServiceIdHash: ByteArray,
        serviceIdHashSalt: String,
    ): ByteArray {
        validateServiceIdHash(saltedServiceIdHash)
        val header =
            MultiplexFramesProto.MultiplexFrameHeader
                .newBuilder()
                .setSaltedServiceIdHash(ByteString.copyFrom(saltedServiceIdHash))
                .setServiceIdHashSalt(serviceIdHashSalt)
                .build()
        val controlFrame =
            MultiplexFramesProto.MultiplexControlFrame
                .newBuilder()
                .setControlFrameType(
                    MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType.DISCONNECTION,
                ).build()
        val frame =
            MultiplexFramesProto.MultiplexFrame
                .newBuilder()
                .setFrameType(MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME)
                .setHeader(header)
                .setControlFrame(controlFrame)
                .build()
        return frame.toByteArray()
    }

    private fun isValid(frame: MultiplexFramesProto.MultiplexFrame): Boolean {
        if (!frame.hasHeader() || frame.header.saltedServiceIdHash.size() != SERVICE_ID_HASH_LEN) return false
        return when (frame.frameType) {
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME -> frame.hasControlFrame()
            MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.DATA_FRAME -> frame.hasDataFrame()
            else -> false
        }
    }

    private fun validateServiceIdHash(serviceIdHash: ByteArray) {
        require(serviceIdHash.size == SERVICE_ID_HASH_LEN) {
            "serviceIdHash must be $SERVICE_ID_HASH_LEN bytes, got ${serviceIdHash.size}"
        }
    }

    private fun writeLength(
        length: Int,
        out: ByteArray,
        offset: Int,
    ) {
        out[offset] = (length ushr 24).toByte()
        out[offset + 1] = (length ushr 16).toByte()
        out[offset + 2] = (length ushr 8).toByte()
        out[offset + 3] = length.toByte()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
