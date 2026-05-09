/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.AutoReconnectFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.AutoResumeFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Wire-format tests for [ResumeFrames] (#43).
 *
 * The exchange itself is driven by higher-level orchestration (the
 * resume session manager, follow-up issue), but the frame builders
 * and parsers are stable wire ABI: a peer that speaks AUTO_RESUME
 * needs the bit pattern below to round-trip cleanly.
 */
class ResumeFramesTest {
    @Test
    fun `resumeStart populates the proto fields and wraps with V1 AUTO_RESUME`() {
        val frame = ResumeFrames.resumeStart(pendingPayloadId = 42, nextPayloadChunkIndex = 7, version = 1)
        assertThat(frame.version).isEqualTo(OfflineFrame.Version.V1)
        assertThat(frame.v1.type).isEqualTo(V1Frame.FrameType.AUTO_RESUME)
        val inner = frame.v1.autoResume
        assertThat(inner.eventType).isEqualTo(AutoResumeFrame.EventType.PAYLOAD_RESUME_TRANSFER_START)
        assertThat(inner.pendingPayloadId).isEqualTo(42L)
        assertThat(inner.nextPayloadChunkIndex).isEqualTo(7)
        assertThat(inner.version).isEqualTo(1)
    }

    @Test
    fun `resumeAck uses the ACK event type`() {
        val frame = ResumeFrames.resumeAck(pendingPayloadId = 9, nextPayloadChunkIndex = 0)
        assertThat(frame.v1.autoResume.eventType)
            .isEqualTo(AutoResumeFrame.EventType.PAYLOAD_RESUME_TRANSFER_ACK)
    }

    @Test
    fun `clientIntroduction populates endpoint ids and event type`() {
        val frame = ResumeFrames.clientIntroduction(endpointId = "AB12", lastEndpointId = "CD34")
        assertThat(frame.v1.type).isEqualTo(V1Frame.FrameType.AUTO_RECONNECT)
        val inner = frame.v1.autoReconnect
        assertThat(inner.endpointId).isEqualTo("AB12")
        assertThat(inner.lastEndpointId).isEqualTo("CD34")
        assertThat(inner.eventType).isEqualTo(AutoReconnectFrame.EventType.CLIENT_INTRODUCTION)
    }

    @Test
    fun `clientIntroductionAck uses the ACK event type`() {
        val frame = ResumeFrames.clientIntroductionAck(endpointId = "AB12")
        assertThat(frame.v1.autoReconnect.eventType)
            .isEqualTo(AutoReconnectFrame.EventType.CLIENT_INTRODUCTION_ACK)
    }

    @Test
    fun `frames serialize and deserialize through proto bytes`() {
        val original = ResumeFrames.resumeStart(pendingPayloadId = 99, nextPayloadChunkIndex = 4)
        val bytes = original.toByteArray()
        val restored = OfflineFrame.parseFrom(bytes)
        assertThat(ResumeFrames.isAutoResume(restored)).isTrue()
        val parsed = ResumeFrames.parseAutoResume(restored)
        assertThat(parsed.pendingPayloadId).isEqualTo(99L)
        assertThat(parsed.nextPayloadChunkIndex).isEqualTo(4)
    }

    @Test
    fun `isAutoResume rejects frames of other types`() {
        val reconnect = ResumeFrames.clientIntroduction("A", "B")
        assertThat(ResumeFrames.isAutoResume(reconnect)).isFalse()
        assertThat(ResumeFrames.isAutoReconnect(reconnect)).isTrue()
    }

    @Test
    fun `parseAutoResume on a non-AUTO_RESUME frame throws`() {
        val reconnect = ResumeFrames.clientIntroduction("A", "B")
        assertThrows<IllegalArgumentException> { ResumeFrames.parseAutoResume(reconnect) }
    }

    @Test
    fun `parseAutoReconnect on a non-AUTO_RECONNECT frame throws`() {
        val resume = ResumeFrames.resumeStart(pendingPayloadId = 1, nextPayloadChunkIndex = 0)
        assertThrows<IllegalArgumentException> { ResumeFrames.parseAutoReconnect(resume) }
    }
}
