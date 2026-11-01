/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.connection.InboundConnectionState
import dev.bluehouse.bada.protocol.connection.TransferProgress
import org.junit.jupiter.api.Test

class InboundStateLogThrottleTest {
    @Test
    fun `per-chunk Receiving states inside one item log about once a second`() {
        val throttle = InboundStateLogThrottle(intervalMillis = 1_000L)

        // 840 chunks a second for three seconds, all in one item (#304).
        val logged =
            (0 until 2_520).count { chunk ->
                throttle.shouldLog(receiving(bytes = chunk * 65_536L, itemId = 7L), nowMillis = chunk * 1_000L / 840)
            }

        assertThat(logged).isEqualTo(3)
    }

    @Test
    fun `item boundaries are logged even inside the interval`() {
        val throttle = InboundStateLogThrottle(intervalMillis = 1_000L)

        assertThat(throttle.shouldLog(receiving(bytes = 0, itemId = 1L), nowMillis = 0)).isTrue()
        assertThat(throttle.shouldLog(receiving(bytes = 10, itemId = 1L), nowMillis = 10)).isFalse()
        // Item 1 finished: the driver republishes with no current item.
        assertThat(throttle.shouldLog(receiving(bytes = 20, itemId = null), nowMillis = 20)).isTrue()
        assertThat(throttle.shouldLog(receiving(bytes = 30, itemId = 2L), nowMillis = 30)).isTrue()
        assertThat(throttle.shouldLog(receiving(bytes = 40, itemId = 2L), nowMillis = 40)).isFalse()
    }

    @Test
    fun `non-Receiving states are always logged`() {
        val throttle = InboundStateLogThrottle(intervalMillis = 1_000L)

        assertThat(throttle.shouldLog(receiving(bytes = 0, itemId = 1L), nowMillis = 0)).isTrue()
        val failed = InboundConnectionState.Failed("Peer closed connection unexpectedly")
        assertThat(throttle.shouldLog(failed, nowMillis = 1)).isTrue()
        assertThat(throttle.shouldLog(InboundConnectionState.Rejected, nowMillis = 2)).isTrue()
    }

    private fun receiving(
        bytes: Long,
        itemId: Long?,
    ): InboundConnectionState.Receiving =
        InboundConnectionState.Receiving(
            progress = TransferProgress.of(bytesTransferred = bytes, totalSize = 1L shl 32, bytesPerSecond = 0),
            currentItemPayloadId = itemId,
            currentItemType = null,
        )
}
