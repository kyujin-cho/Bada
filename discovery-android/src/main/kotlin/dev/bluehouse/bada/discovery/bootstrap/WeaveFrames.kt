/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("MagicNumber", "ReturnCount")

package dev.bluehouse.bada.discovery.bootstrap

internal sealed class WeavePacket {
    data class ConnectionRequest(
        val minVersion: Int,
        val maxVersion: Int,
        val maxPacketSize: Int,
    ) : WeavePacket()

    data class ConnectionConfirm(
        val version: Int,
        val packetSize: Int,
    ) : WeavePacket()

    data class Data(
        val counter: Int,
        val firstPacket: Boolean,
        val lastPacket: Boolean,
        val data: ByteArray,
    ) : WeavePacket()

    data class ConnectionClose(
        val reason: Int,
    ) : WeavePacket()
}

internal object WeaveFrames {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 1
    const val MIN_PACKET_SIZE: Int = 20
    const val MAX_PACKET_SIZE: Int = 509
    const val DEFAULT_ATT_PAYLOAD_SIZE: Int = 20
    const val MAX_PACKET_COUNTER: Int = 8
    const val CLOSE_NO_COMMON_VERSION: Int = 2

    private const val PACKET_TYPE_CONTROL: Int = 0x80
    private const val PACKET_COUNTER_MASK: Int = 0x70
    private const val PACKET_COUNTER_SHIFT: Int = 4
    private const val CONTROL_COMMAND_MASK: Int = 0x0F
    private const val CONTROL_CONNECTION_REQUEST: Int = 0
    private const val CONTROL_CONNECTION_CONFIRM: Int = 1
    private const val CONTROL_CONNECTION_CLOSE: Int = 2
    private const val DATA_FIRST_FLAG: Int = 0x08
    private const val DATA_LAST_FLAG: Int = 0x04
    private const val MIN_CONNECTION_REQUEST_LEN: Int = 7
    private const val CONNECTION_CONFIRM_LEN: Int = 5
    private const val CONNECTION_CLOSE_LEN: Int = 3

    fun parse(bytes: ByteArray): WeavePacket? {
        if (bytes.isEmpty()) return null
        val header = bytes[0].toInt() and 0xFF
        val counter = (header and PACKET_COUNTER_MASK) ushr PACKET_COUNTER_SHIFT
        return if ((header and PACKET_TYPE_CONTROL) != 0) {
            parseControl(header, bytes)
        } else {
            WeavePacket.Data(
                counter = counter,
                firstPacket = (header and DATA_FIRST_FLAG) != 0,
                lastPacket = (header and DATA_LAST_FLAG) != 0,
                data = bytes.copyOfRange(HEADER_SIZE, bytes.size),
            )
        }
    }

    fun connectionRequest(
        packetCounter: Int,
        minVersion: Int,
        maxVersion: Int,
        maxPacketSize: Int,
    ): ByteArray {
        val header =
            PACKET_TYPE_CONTROL or
                ((packetCounter % MAX_PACKET_COUNTER) shl PACKET_COUNTER_SHIFT) or
                CONTROL_CONNECTION_REQUEST
        return byteArrayOf(
            header.toByte(),
            (minVersion ushr Byte.SIZE_BITS).toByte(),
            minVersion.toByte(),
            (maxVersion ushr Byte.SIZE_BITS).toByte(),
            maxVersion.toByte(),
            (maxPacketSize ushr Byte.SIZE_BITS).toByte(),
            maxPacketSize.toByte(),
        )
    }

    fun connectionConfirm(
        version: Int,
        packetSize: Int,
    ): ByteArray =
        byteArrayOf(
            (PACKET_TYPE_CONTROL or CONTROL_CONNECTION_CONFIRM).toByte(),
            (version ushr Byte.SIZE_BITS).toByte(),
            version.toByte(),
            (packetSize ushr Byte.SIZE_BITS).toByte(),
            packetSize.toByte(),
        )

    fun connectionClosePacket(
        packetCounter: Int,
        reason: Int,
    ): ByteArray {
        val header =
            PACKET_TYPE_CONTROL or
                ((packetCounter % MAX_PACKET_COUNTER) shl PACKET_COUNTER_SHIFT) or
                CONTROL_CONNECTION_CLOSE
        return byteArrayOf(
            header.toByte(),
            (reason ushr Byte.SIZE_BITS).toByte(),
            reason.toByte(),
        )
    }

    fun dataPacket(
        packetCounter: Int,
        firstPacket: Boolean,
        lastPacket: Boolean,
        data: ByteArray,
    ): ByteArray {
        val header =
            ((packetCounter % MAX_PACKET_COUNTER) shl PACKET_COUNTER_SHIFT) or
                (if (firstPacket) DATA_FIRST_FLAG else 0) or
                (if (lastPacket) DATA_LAST_FLAG else 0)
        return byteArrayOf(header.toByte()) + data
    }

    private fun parseControl(
        header: Int,
        bytes: ByteArray,
    ): WeavePacket? {
        return when (header and CONTROL_COMMAND_MASK) {
            CONTROL_CONNECTION_REQUEST -> {
                if (bytes.size < MIN_CONNECTION_REQUEST_LEN) return null
                WeavePacket.ConnectionRequest(
                    minVersion = bytes.readUInt16(1),
                    maxVersion = bytes.readUInt16(3),
                    maxPacketSize = bytes.readUInt16(5),
                )
            }

            CONTROL_CONNECTION_CONFIRM -> {
                if (bytes.size < CONNECTION_CONFIRM_LEN) return null
                WeavePacket.ConnectionConfirm(
                    version = bytes.readUInt16(1),
                    packetSize = bytes.readUInt16(3),
                )
            }

            CONTROL_CONNECTION_CLOSE -> {
                WeavePacket.ConnectionClose(
                    reason = if (bytes.size >= CONNECTION_CLOSE_LEN) bytes.readUInt16(1) else 0,
                )
            }

            else -> null
        }
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl Byte.SIZE_BITS) or (this[offset + 1].toInt() and 0xFF)
}
