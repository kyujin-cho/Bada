/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test

/**
 * Sanity check that the bytes produced by [EndpointInfo.serialize] survive a
 * round-trip through the `endpoint_info` field of a `ConnectionRequestFrame`
 * proto.
 *
 * The wire format is identical in both transport channels (mDNS TXT and
 * `ConnectionRequestFrame`), so the proto runtime is the more demanding of
 * the two — it serializes the bytes through length-delimited proto encoding
 * and back. If the bytes survive that round-trip and re-parse to the same
 * [EndpointInfo], we know the serializer is producing a peer-acceptable blob.
 */
class EndpointInfoProtoInteropTest {
    @Test
    fun `serialized EndpointInfo round-trips through ConnectionRequestFrame endpoint_info`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.LAPTOP,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it * 13).toByte() },
                deviceName = "MacBook Pro",
                tlvRecords =
                    listOf(
                        TlvRecord(
                            EndpointInfo.TLV_TYPE_QR_CODE,
                            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                        ),
                    ),
            )
        val rawBytes = info.serialize()

        val frame =
            ConnectionRequestFrame
                .newBuilder()
                .setEndpointInfo(ByteString.copyFrom(rawBytes))
                .build()

        val proto = frame.toByteArray()
        val parsedFrame = ConnectionRequestFrame.parseFrom(proto)
        val recovered = parsedFrame.endpointInfo.toByteArray()

        assertThat(recovered).isEqualTo(rawBytes)
        assertThat(EndpointInfo.parse(recovered)).isEqualTo(info)
    }
}
