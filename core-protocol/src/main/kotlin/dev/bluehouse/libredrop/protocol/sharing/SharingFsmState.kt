/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.sharing

/**
 * States of the **inbound** Quick Share negotiation FSM.
 *
 * The naming mirrors NearDrop's `InboundNearbyConnection.State` (Swift
 * reference implementation), which mirrors stock Android Quick Share's
 * receive-side phase machine. Some states from NearDrop's enum are NOT
 * present here because they correspond to handshake / transport-layer
 * phases that this codebase splits across earlier modules:
 *
 *  - `initial`, `receivedConnectionRequest`, `sentUkeyServerInit`,
 *    `receivedUkeyClientFinish`, `sentConnectionResponse` — handled by
 *    [dev.bluehouse.libredrop.protocol.transport.FramedConnection]
 *    + [dev.bluehouse.libredrop.protocol.ukey2.Ukey2Server] (issues
 *    #7 / #10). The FSM in this module starts AFTER that handshake,
 *    in [SentPairedKeyEncryption].
 *  - `receivingFiles` — the post-negotiation file-streaming phase
 *    (issues #16 / #19 / #23). The FSM marks the boundary by emitting
 *    [SharingFsmEffect.ReadyToReceivePayloads] and transitioning to
 *    [Disconnected] only on cancel / error / completion of file
 *    streaming, which is reported back in by the orchestrator. To keep
 *    THIS module pure-FSM, [ReceivingPayloads] is the post-consent
 *    state and the orchestrator owns the actual file I/O.
 *
 * Transition diagram (`->` is a state transition, `(emit X)` is the
 * effects produced):
 *
 * ```
 *   [enter] -> SentPairedKeyEncryption           (emit SendFrame{PKE})
 *   SentPairedKeyEncryption
 *     +-- Frame{PAIRED_KEY_ENCRYPTION}      ->  ReceivedPairedKeyEncryption (emit SendFrame{PKR=UNABLE})
 *     +-- Frame{CANCEL}                     ->  Disconnected               (emit Cancelled)
 *     +-- UserCancel                        ->  Disconnected               (emit SendFrame{CANCEL}, Cancelled)
 *     +-- *                                 ->  Disconnected               (emit ProtocolError)
 *
 *   ReceivedPairedKeyEncryption -> SentPairedKeyResult (transition is implicit; see code)
 *   SentPairedKeyResult
 *     +-- Frame{PAIRED_KEY_RESULT}          ->  ReceivedPairedKeyResult
 *     ... (CANCEL / ProtocolError as above)
 *
 *   ReceivedPairedKeyResult
 *     +-- Frame{INTRODUCTION}               ->  WaitingForUserConsent      (emit IntroductionReceived)
 *     +-- Frame{RESPONSE, status=ACCEPT|UNKNOWN}
 *                                            ->  ReceivedPairedKeyResult    (ignore sender pre-consent)
 *     ... (CANCEL / ProtocolError as above)
 *
 *   WaitingForUserConsent
 *     +-- UserConsent(true)   ->  ReceivingPayloads
 *           (emit SendFrame{RESPONSE=ACCEPT}, ReadyToReceivePayloads, Completed)
 *     +-- UserConsent(false)  ->  Disconnected
 *           (emit SendFrame{RESPONSE=REJECT}, Rejected, Cancelled)
 *     ... (CANCEL / ProtocolError as above)
 *
 *   ReceivingPayloads (terminal w.r.t. the FSM; the orchestrator owns it from here)
 * ```
 */
public enum class InboundSharingState {
    /**
     * Initial state on entry. The FSM has just emitted a
     * `PairedKeyEncryptionFrame` and is waiting for the peer's
     * matching frame.
     */
    SentPairedKeyEncryption,

    /**
     * The peer's `PairedKeyEncryptionFrame` arrived; the FSM responded
     * with `PairedKeyResultFrame{status=UNABLE}` and is waiting for the
     * peer's matching result frame. (Distinct from [SentPairedKeyResult]
     * by virtue of also having received PKE — they would otherwise be
     * a single state.)
     */
    SentPairedKeyResult,

    /**
     * Both halves of the paired-key dance are done. The FSM is now
     * waiting for the sender's `IntroductionFrame`.
     */
    ReceivedPairedKeyResult,

    /**
     * The introduction is in hand; the FSM is waiting for the user's
     * accept / reject decision (delivered via [SharingFsmEvent.UserConsent]).
     * No protocol frames are expected from the peer here EXCEPT
     * `CancelFrame`.
     */
    WaitingForUserConsent,

    /**
     * The user accepted; `ConnectionResponseFrame{ACCEPT}` was sent;
     * the FSM has signaled [SharingFsmEffect.ReadyToReceivePayloads].
     * From the FSM's perspective negotiation is **complete** and the
     * orchestrator is now responsible for receiving FILE / BYTES
     * application payloads. The FSM remains in this state until the
     * orchestrator drives it to [Disconnected] via [SharingFsmEvent.UserCancel]
     * or until a peer `Frame{CANCEL}` arrives.
     */
    ReceivingPayloads,

    /**
     * Terminal. Reached after rejection, cancellation, protocol error,
     * or successful completion. Subsequent events return an empty
     * effect list.
     */
    Disconnected,
}

/**
 * States of the **outbound** Quick Share negotiation FSM.
 *
 * Mirrors NearDrop's `OutboundNearbyConnection.State` for the same
 * reasons documented on [InboundSharingState]. Pre-negotiation phases
 * (UKEY2 client init, sending the connection request) live in earlier
 * modules; the post-negotiation file-streaming phase (`SendingFiles`)
 * lives in #17.
 *
 * Transition diagram:
 *
 * ```
 *   [enter] -> SentPairedKeyEncryption           (emit SendFrame{PKE})
 *   SentPairedKeyEncryption
 *     +-- Frame{PAIRED_KEY_ENCRYPTION}      ->  ReceivedPairedKeyEncryption (emit SendFrame{PKR=UNABLE})
 *     ... (CANCEL / ProtocolError as for inbound)
 *
 *   ReceivedPairedKeyEncryption -> SentPairedKeyResult (implicit; see code)
 *   SentPairedKeyResult
 *     +-- Frame{PAIRED_KEY_RESULT}          ->  SentIntroduction           (emit SendFrame{INTRODUCTION})
 *     ... (CANCEL / ProtocolError as above)
 *
 *   SentIntroduction
 *     +-- Frame{RESPONSE, status=ACCEPT}    ->  SendingPayloads            (emit ReadyToSendPayloads, Completed)
 *     +-- Frame{RESPONSE, status=*}         ->  Disconnected               (emit Rejected, Cancelled)
 *     ... (CANCEL / ProtocolError as above)
 *
 *   SendingPayloads (terminal w.r.t. the FSM)
 * ```
 */
public enum class OutboundSharingState {
    /** The FSM emitted `PairedKeyEncryptionFrame`; awaiting peer's PKE. */
    SentPairedKeyEncryption,

    /** PKE received and replied; awaiting peer's `PairedKeyResultFrame`. */
    SentPairedKeyResult,

    /**
     * Paired-key dance done; the FSM has emitted the
     * `IntroductionFrame` and is awaiting the receiver's
     * `ConnectionResponseFrame`.
     */
    SentIntroduction,

    /**
     * Receiver accepted; the FSM has signaled [SharingFsmEffect.ReadyToSendPayloads]
     * and the orchestrator is now sending FILE / BYTES application
     * payloads. Like [InboundSharingState.ReceivingPayloads], this is
     * the FSM's "negotiation complete" plateau.
     */
    SendingPayloads,

    /**
     * Terminal. Reached after rejection, cancellation, protocol error,
     * or successful completion. Subsequent events return an empty
     * effect list.
     */
    Disconnected,
}
