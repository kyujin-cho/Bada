/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import androidx.annotation.StringRes
import dev.bluehouse.bada.R
import dev.bluehouse.bada.protocol.connection.CancelCause
import dev.bluehouse.bada.protocol.connection.OutboundConnectionState

/**
 * What the send sheet tells the user about a cancelled transfer (#319).
 *
 * The raw [CancelCause] ("PEER") gave the user nothing to act on. The
 * case worth singling out is a receiver that refuses the sender without
 * asking anyone: Quick Share on Windows limited to its owner's devices
 * disconnects 5 ms after our IntroductionFrame, so it looked like a
 * person on the other side had cancelled.
 */
internal enum class SendCancelReason(
    @param:StringRes val phase: Int,
    @param:StringRes val message: Int,
) {
    CANCELLED_HERE(R.string.send_phase_cancelled, R.string.send_cancel_reason_here),
    RECEIVER_DECLINED_AUTOMATICALLY(
        R.string.send_phase_declined_automatically,
        R.string.send_cancel_reason_declined_automatically,
    ),
    RECEIVER_CLOSED_REQUEST(R.string.send_phase_cancelled, R.string.send_cancel_reason_receiver_closed_request),
    RECEIVER_STOPPED_TRANSFER(R.string.send_phase_cancelled, R.string.send_cancel_reason_receiver_stopped),
    ;

    companion object {
        /**
         * No one answers a consent prompt this fast, so a peer cancel
         * inside this window after the IntroductionFrame is the receiver
         * refusing on its own.
         */
        const val AUTOMATIC_DECLINE_WINDOW_MILLIS: Long = 2_000L

        fun from(state: OutboundConnectionState.Cancelled): SendCancelReason {
            val waited = state.awaitingAcceptanceMillis
            return when {
                state.cause == CancelCause.LOCAL -> CANCELLED_HERE
                waited == null -> RECEIVER_STOPPED_TRANSFER
                waited <= AUTOMATIC_DECLINE_WINDOW_MILLIS -> RECEIVER_DECLINED_AUTOMATICALLY
                else -> RECEIVER_CLOSED_REQUEST
            }
        }
    }
}
