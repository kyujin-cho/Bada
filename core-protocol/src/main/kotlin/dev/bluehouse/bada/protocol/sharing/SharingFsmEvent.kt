/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.sharing

/**
 * Inputs to a Quick Share negotiation state machine.
 *
 * The negotiation FSM is **pure**: it owns no I/O, no coroutines, no
 * threading. The surrounding orchestration (issues #16 / #17 / #22)
 * funnels every external stimulus into one of these events and calls
 * [InboundSharingFsm.onEvent] / [OutboundSharingFsm.onEvent], then acts
 * on the resulting [SharingFsmEffect] list.
 *
 * Three event kinds cover the entire input space:
 *
 *  - [FrameReceived] — a `Sharing.Nearby.Frame` came in over the wire.
 *    The orchestrator already pulled it out of the surrounding BYTES
 *    payload via [dev.bluehouse.bada.protocol.payload.PayloadAssembler]
 *    and parsed it via [SharingFrames.parse]. Parse failures are surfaced
 *    out-of-band by the orchestrator (`SharingFrameParseException`) — they
 *    do NOT travel through this event channel because there is no useful
 *    response from the FSM, only "close the connection".
 *  - [UserConsent] — the receiver-side user pressed accept or reject in
 *    the consent UI. Sender FSMs never see this event.
 *  - [UserCancel] — the local user pressed cancel. Either role can see
 *    this; the FSM emits the matching outbound `CancelFrame` and moves
 *    to a terminal state.
 *
 * Cancellation **from the peer** arrives as a [FrameReceived] whose
 * `v1.type == CANCEL` — there is no separate event for it. The receive
 * path treats inbound CANCEL identically regardless of which state the
 * FSM happens to be in (every non-terminal state accepts it).
 */
public sealed interface SharingFsmEvent {
    /**
     * A `Sharing.Nearby.Frame` was decoded from a BYTES payload that
     * the peer sent.
     *
     * @property frame Already-parsed frame. The FSM dispatches on
     *   `frame.v1.type`. The frame's other fields are read on demand —
     *   e.g. `frame.v1.connectionResponse.status` for the sender FSM's
     *   response handling.
     */
    public data class FrameReceived(
        val frame: SharingFrame,
    ) : SharingFsmEvent

    /**
     * The local user accepted or rejected the incoming transfer in the
     * consent UI. Receiver-only — issuing this to a sender FSM is a
     * caller bug and is silently ignored (no effect, state unchanged).
     *
     * @property accept `true` for accept, `false` for reject.
     */
    public data class UserConsent(
        val accept: Boolean,
    ) : SharingFsmEvent

    /**
     * The local user requested cancellation. Both roles support this;
     * the FSM emits a [SharingFsmEffect.SendFrame] carrying a
     * `Sharing.Nearby.Frame{CANCEL}` plus a [SharingFsmEffect.Cancelled]
     * notification, then transitions to its terminal disconnected state.
     */
    public object UserCancel : SharingFsmEvent
}
