/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.protocol.connection.CancelCause
import dev.bluehouse.bada.protocol.connection.OutboundConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SendCancelReasonTest {
    @Test
    fun `local cancel is reported as cancelled here`() {
        val state = OutboundConnectionState.Cancelled(CancelCause.LOCAL, awaitingAcceptanceMillis = 3L)
        assertEquals(SendCancelReason.CANCELLED_HERE, SendCancelReason.from(state))
    }

    @Test
    fun `peer cancel right after the introduction is an automatic decline`() {
        // Quick Share on Windows limited to its owner's devices answered in 5 ms (#319).
        val state = OutboundConnectionState.Cancelled(CancelCause.PEER, awaitingAcceptanceMillis = 5L)
        assertEquals(SendCancelReason.RECEIVER_DECLINED_AUTOMATICALLY, SendCancelReason.from(state))
    }

    @Test
    fun `peer cancel at the edge of the window still counts as automatic`() {
        val state =
            OutboundConnectionState.Cancelled(
                CancelCause.PEER,
                awaitingAcceptanceMillis = SendCancelReason.AUTOMATIC_DECLINE_WINDOW_MILLIS,
            )
        assertEquals(SendCancelReason.RECEIVER_DECLINED_AUTOMATICALLY, SendCancelReason.from(state))
    }

    @Test
    fun `slower peer cancel while awaiting acceptance closed the request`() {
        val state = OutboundConnectionState.Cancelled(CancelCause.PEER, awaitingAcceptanceMillis = 12_000L)
        assertEquals(SendCancelReason.RECEIVER_CLOSED_REQUEST, SendCancelReason.from(state))
    }

    @Test
    fun `peer cancel outside the acceptance wait stopped the transfer`() {
        val state = OutboundConnectionState.Cancelled(CancelCause.PEER)
        assertEquals(SendCancelReason.RECEIVER_STOPPED_TRANSFER, SendCancelReason.from(state))
    }
}
