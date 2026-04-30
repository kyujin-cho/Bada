/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.mediums.proto.BleFramesProto
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import org.junit.jupiter.api.Test

class NearbyBleSocketFramesTest {
    @Test
    fun `introduction packet has control prefix and NearbySharing hash`() {
        val packet = NearbyBleSocketFrames.encodeIntroductionPacket()
        val parsed = NearbyBleSocketFrames.parseControlPacket(packet)

        assertThat(packet.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0, 0, 0))
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type)
            .isEqualTo(BleFramesProto.SocketControlFrame.ControlFrameType.INTRODUCTION)
        assertThat(parsed.introduction.socketVersion).isEqualTo(BleFramesProto.SocketVersion.V2)
        assertThat(parsed.introduction.serviceIdHash.toByteArray()).isEqualTo(NearbyServiceId.hashPrefix)
    }

    @Test
    fun `packet acknowledgement carries received size and NearbySharing hash`() {
        val packet = NearbyBleSocketFrames.encodePacketAcknowledgementPacket(receivedSize = 1234)
        val parsed = NearbyBleSocketFrames.parseControlPacket(packet)

        assertThat(packet.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0, 0, 0))
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type)
            .isEqualTo(BleFramesProto.SocketControlFrame.ControlFrameType.PACKET_ACKNOWLEDGEMENT)
        assertThat(parsed.packetAcknowledgement.serviceIdHash.toByteArray()).isEqualTo(NearbyServiceId.hashPrefix)
        assertThat(parsed.packetAcknowledgement.receivedSize).isEqualTo(1234)
    }
}
