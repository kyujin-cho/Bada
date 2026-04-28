/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import org.junit.jupiter.api.Test

/**
 * Regression guard for the six `ConnectionResponseFrame` fields that
 * Samsung One UI 8.0.5 (Android 16) validates.
 *
 * Google's reference Nearby Connections (`google/nearby`,
 * `ForConnectionResponse`) unconditionally sets fields 1–5; field 6
 * (`keep_alive_timeout_millis`, proto field 9) was added to the proto
 * on 2024-12-06 and One UI 8.0.5 enforces it even though the public
 * `ForConnectionResponse` does not yet emit it:
 *   1. `status = 0` (legacy STATUS_OK)
 *   2. `response = ACCEPT`
 *   3. `os_info.type = ANDROID`
 *   4. `multiplex_socket_bitmask = 0` (no medium supports multiplex)
 *   5. `safe_to_disconnect_version = 1`
 *   6. `keep_alive_timeout_millis = 600_000`
 *
 * One UI 8.0.5 silently FINs ~150 ms after our `ConnectionResponse{ACCEPT}`
 * when either of fields 4 or 6 is absent (issue #101). Verified on-device
 * with Galaxy S24 Ultra `SM-S948N`, ro.build.version.oneui = 80500.
 *
 * Both [OutboundFrames.connectionResponse] (sender role) and
 * [OfflineFrames.connectionResponse] (receiver role) must emit the same
 * six-field shape because the validating peer does not know which role
 * we are playing.
 */
class ConnectionResponseFrameShapeTest {
    @Test
    fun `OutboundFrames connectionResponse pins all six Samsung-One-UI-8-required fields`() {
        assertSixFieldShape(OutboundFrames.connectionResponse())
    }

    @Test
    fun `OfflineFrames connectionResponse matches OutboundFrames shape (parity guard)`() {
        assertSixFieldShape(OfflineFrames.connectionResponse())
    }

    private fun assertSixFieldShape(frame: OfflineFrame) {
        val parsed = OfflineFrame.parseFrom(frame.toByteArray())

        assertThat(parsed.v1.type).isEqualTo(V1Frame.FrameType.CONNECTION_RESPONSE)

        val cr = parsed.v1.connectionResponse
        assertThat(cr.hasResponse()).isTrue()
        assertThat(cr.response).isEqualTo(ConnectionResponseFrame.ResponseStatus.ACCEPT)

        @Suppress("DEPRECATION")
        assertThat(cr.hasStatus()).isTrue()
        @Suppress("DEPRECATION")
        assertThat(cr.status).isEqualTo(0)

        assertThat(cr.hasOsInfo()).isTrue()
        assertThat(cr.osInfo.type).isEqualTo(OsInfo.OsType.ANDROID)

        assertThat(cr.hasMultiplexSocketBitmask()).isTrue()
        assertThat(cr.multiplexSocketBitmask).isEqualTo(0)

        assertThat(cr.hasSafeToDisconnectVersion()).isTrue()
        assertThat(cr.safeToDisconnectVersion).isEqualTo(1)

        assertThat(cr.hasKeepAliveTimeoutMillis()).isTrue()
        assertThat(cr.keepAliveTimeoutMillis).isEqualTo(600_000)
    }
}
