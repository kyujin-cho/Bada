/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.mediums.proto.MultiplexFramesProto
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import org.junit.jupiter.api.Test

class NearbyMultiplexFramesTest {
    @Test
    fun `service hash matches NearbyServiceId prefix`() {
        assertThat(NearbyMultiplexFrames.serviceIdHash()).isEqualTo(NearbyServiceId.hashPrefix)
    }

    @Test
    fun `connection response roundtrips through parser`() {
        val salt = "salt"
        val saltedHash = NearbyMultiplexFrames.saltedServiceIdHash(salt = salt)
        val payload = NearbyMultiplexFrames.encodeConnectionResponseFrame(saltedHash, salt)
        val framed = NearbyMultiplexFrames.encodeLengthPrefixed(payload)
        val parsed = NearbyMultiplexFrames.parseFrame(framed.copyOfRange(4, framed.size))

        assertThat(NearbyMultiplexFrames.decodeLength(framed)).isEqualTo(payload.size)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.frameType)
            .isEqualTo(MultiplexFramesProto.MultiplexFrame.MultiplexFrameType.CONTROL_FRAME)
        assertThat(parsed.controlFrame.controlFrameType)
            .isEqualTo(
                MultiplexFramesProto.MultiplexControlFrame.MultiplexControlFrameType
                    .CONNECTION_RESPONSE,
            )
        assertThat(parsed.controlFrame.connectionResponseFrame.connectionResponseCode)
            .isEqualTo(
                MultiplexFramesProto.ConnectionResponseFrame.ConnectionResponseCode
                    .CONNECTION_ACCEPTED,
            )
        assertThat(parsed.header.serviceIdHashSalt).isEqualTo(salt)
        assertThat(parsed.header.saltedServiceIdHash.toByteArray()).isEqualTo(saltedHash)
    }
}
