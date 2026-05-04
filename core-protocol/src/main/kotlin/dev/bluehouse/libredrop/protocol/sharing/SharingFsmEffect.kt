/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.sharing

/**
 * Outputs produced by a Quick Share negotiation state machine in
 * response to a single [SharingFsmEvent].
 *
 * The FSM never performs I/O directly — every external action it would
 * like to take is reified as one of these effects, returned to the
 * orchestrator (issues #16 / #17 / #22) which interprets them:
 *
 *  - [SendFrame] is sent through the BYTES-payload encoder
 *    ([dev.bluehouse.libredrop.protocol.payload.PayloadTransferEncoder.encodeBytesPayload])
 *    and the resulting `OfflineFrame`s are pushed through the
 *    [dev.bluehouse.libredrop.protocol.crypto.securemessage.SecureChannel].
 *  - [IntroductionReceived] tells the receiver UI to show the consent
 *    sheet for the announced files / text.
 *  - [ReadyToReceivePayloads] / [ReadyToSendPayloads] is the gate that
 *    file streaming waits on; until it fires, no FILE / BYTES
 *    application payloads are produced or accepted.
 *  - [Rejected] / [Cancelled] / [Completed] / [ProtocolError] are all
 *    terminal notifications. After any of these, the FSM is in
 *    [InboundSharingState.Disconnected] / [OutboundSharingState.Disconnected]
 *    and further events return an empty effect list.
 *
 * The list returned by `onEvent` is ordered: effects must be applied in
 * the listed order. In practice that means a state transition that
 * sends a frame *and* completes the FSM (for example, sender's
 * `IntroductionFrame` → `ConnectionResponseFrame{ACCEPT}` does not
 * complete it, but receiver's `UserConsent(accept=false)` does)
 * surfaces the [SendFrame] before the [Rejected] / [Cancelled] /
 * [Completed] notifier so the orchestrator pushes the bytes onto the
 * wire before it tears the connection down.
 */
public sealed interface SharingFsmEffect {
    /**
     * The FSM wants to send the given [SharingFrame] to the peer.
     *
     * The orchestrator is responsible for:
     *  1. Choosing a fresh BYTES-payload `payload_id` (any positive
     *     `Long` that has not been used in the current connection
     *     works — NearDrop uses `SecureRandom.nextLong()` and so should
     *     we; reuse causes the receiver's [dev.bluehouse.libredrop.protocol.payload.PayloadAssembler]
     *     to reject the frame).
     *  2. Calling [dev.bluehouse.libredrop.protocol.payload.PayloadTransferEncoder.encodeBytesPayload]
     *     with the serialized frame bytes.
     *  3. Sending the resulting `OfflineFrame`s through the
     *     [dev.bluehouse.libredrop.protocol.crypto.securemessage.SecureChannel].
     *
     * @property frame The frame to serialize and send. Already a complete
     *   `Sharing.Nearby.Frame` with `version = V1` and the appropriate
     *   `v1.type` discriminator set.
     */
    public data class SendFrame(
        val frame: SharingFrame,
    ) : SharingFsmEffect

    /**
     * Receiver-only. The peer has sent its `IntroductionFrame` listing
     * the files / text it wants to transfer. The orchestrator surfaces
     * this to the consent UI; the user's accept/reject choice comes
     * back as [SharingFsmEvent.UserConsent].
     *
     * @property introduction The full `IntroductionFrame` proto. Carries
     *   `file_metadata[]`, `text_metadata[]`, `wifi_credentials_metadata[]`,
     *   `app_metadata[]`, `stream_metadata[]`, `required_package`,
     *   `start_transfer`, `use_case`, and `preview_payload_ids`. The
     *   consent UI typically only renders `file_metadata` and
     *   `text_metadata`.
     */
    public data class IntroductionReceived(
        val introduction: IntroductionFrame,
    ) : SharingFsmEffect

    /**
     * Receiver-only. The negotiation handshake is complete and the user
     * accepted; the orchestrator should now begin accepting BYTES /
     * FILE application payloads from the peer. Subsequent
     * `PayloadTransferFrame`s for the announced `payload_id`s become
     * meaningful from this point forward.
     *
     * Emitted exactly once, immediately after the FSM enqueues the
     * `ConnectionResponseFrame{ACCEPT}` for sending.
     */
    public object ReadyToReceivePayloads : SharingFsmEffect

    /**
     * Sender-only. The peer accepted the introduction; the sender may
     * begin streaming the announced FILE / BYTES (text) payloads.
     *
     * Emitted exactly once, immediately after the FSM observes the
     * inbound `ConnectionResponseFrame{ACCEPT}`.
     */
    public object ReadyToSendPayloads : SharingFsmEffect

    /**
     * Sender-only. The peer rejected the transfer with a non-ACCEPT
     * status. The orchestrator interprets the [status] for UI display
     * (e.g. "Recipient rejected", "Recipient ran out of space", etc.),
     * then closes the connection.
     *
     * Per the issue acceptance criteria, `REJECT` and `UNKNOWN` are
     * surfaced here as user-rejected; `NOT_ENOUGH_SPACE`, `TIMED_OUT`,
     * and `UNSUPPORTED_ATTACHMENT_TYPE` are surfaced with their
     * specific status. The FSM does not itself classify them — the
     * orchestrator is welcome to render UNKNOWN as "rejected" if it
     * wishes.
     *
     * @property status The peer's `ConnectionResponseFrame.Status` —
     *   never `ACCEPT` here, by construction.
     */
    public data class Rejected(
        val status: ConnectionResponseStatus,
    ) : SharingFsmEffect

    /**
     * Either side. A cancellation was processed — locally requested via
     * [SharingFsmEvent.UserCancel] (in which case the FSM also emitted
     * a [SendFrame] carrying a CANCEL frame), or remotely received via
     * an inbound `Frame{CANCEL}`.
     *
     * The orchestrator should close the connection and surface a
     * "Transfer cancelled" notification.
     */
    public object Cancelled : SharingFsmEffect

    /**
     * Either side. The negotiation FSM has nothing more to do — for the
     * receiver, this means accepted-and-ready (so [ReadyToReceivePayloads]
     * has already fired in the same effect list); for the sender it
     * means accepted-and-ready (so [ReadyToSendPayloads] has fired).
     *
     * Issued AFTER the ready-to-stream effect so the orchestrator can
     * walk the list in order without buffering. Strictly speaking the
     * FSM does not need to emit [Completed] for the orchestrator to
     * know — checking [InboundSharingFsm.state] / [OutboundSharingFsm.state]
     * is equivalent — but the explicit notification makes for cleaner
     * logging and simpler test assertions.
     *
     * Emitting [Completed] does NOT mean "the file transfer finished";
     * file streaming happens *after* the FSM has accepted. The FSM
     * itself only models the negotiation phase.
     */
    public object Completed : SharingFsmEffect

    /**
     * Either side. The FSM observed an event that violated its
     * transition table — typically a frame in the wrong state, an
     * unexpected `v1.type`, or an inbound frame whose body is missing
     * the field its discriminator promised.
     *
     * The orchestrator MUST close the connection on this effect; there
     * is no resync. After [ProtocolError] the FSM is in its terminal
     * disconnected state.
     *
     * @property reason Short, English, log-suitable description.
     */
    public data class ProtocolError(
        val reason: String,
    ) : SharingFsmEffect
}
