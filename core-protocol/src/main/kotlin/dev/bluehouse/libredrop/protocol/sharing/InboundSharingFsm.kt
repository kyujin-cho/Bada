/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.sharing

import java.security.SecureRandom

/**
 * Receiver-role Quick Share negotiation state machine.
 *
 * The FSM is **pure**: no I/O, no coroutines, no internal locking.
 * The surrounding orchestration (issue #16 — `InboundConnection`) drives
 * it by funneling every external stimulus through [onEvent] and
 * interpreting the returned [SharingFsmEffect] list.
 *
 * ### Lifecycle
 *
 *  1. The orchestrator constructs the FSM after the UKEY2 + connection
 *     response handshake completes. The constructor immediately puts the
 *     FSM in [InboundSharingState.SentPairedKeyEncryption] **after**
 *     producing the seed effect via [start]; the orchestrator must call
 *     [start] exactly once before any [onEvent] call. (Initialisation is
 *     split this way — rather than firing inside the constructor — so
 *     the orchestrator can install error handling around the first emit.)
 *  2. The orchestrator pumps inbound `Sharing.Nearby.Frame` arrivals,
 *     user consent results, and user cancel requests through [onEvent].
 *  3. When the FSM enters its terminal state ([InboundSharingState.ReceivingPayloads]
 *     for the success path, [InboundSharingState.Disconnected] otherwise),
 *     no further events advance state. Subsequent calls return an empty
 *     effect list — they are safe and explicitly idempotent so the
 *     orchestrator does not need to gate them itself.
 *
 * ### Why the FSM is `class` and not `object`
 *
 * Each Quick Share connection runs an independent state machine; the
 * receiver FSM holds per-connection state ([state], internal flags) and
 * a per-connection [SecureRandom]. Sharing one global instance across
 * connections would tangle their negotiations. The class is constructed
 * fresh per connection and discarded when the connection closes.
 *
 * ### Threading model
 *
 * Not thread-safe. The orchestrator is expected to serialize all calls
 * (in practice this is trivial — the receive coroutine is the only one
 * pumping inbound frames, and consent / cancel events are funnelled
 * through it via a `Channel`).
 *
 * @param secureRandom Source of bytes for the outgoing
 *   [PairedKeyEncryptionFrame] random fields. Pass a deterministic
 *   stub from tests to make state assertions reproducible.
 */
public class InboundSharingFsm(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /**
     * Current FSM state. Read-only outside the FSM; updated only by
     * [onEvent] / [start]. Tests assert on this directly to verify the
     * transition table.
     */
    public var state: InboundSharingState = InboundSharingState.SentPairedKeyEncryption
        private set

    private var started: Boolean = false
    private var pendingIntroduction: IntroductionFrame? = null

    /**
     * Drive the FSM to its initial state and produce the first outbound
     * frame.
     *
     * Returns a one-element list containing a [SharingFsmEffect.SendFrame]
     * carrying the receiver's `PairedKeyEncryptionFrame` (random
     * `secret_id_hash` + `signed_data`). Subsequent calls return an
     * empty list — [start] is idempotent so that the orchestrator's
     * retry / re-enter logic cannot accidentally double-emit.
     */
    public fun start(): List<SharingFsmEffect> {
        if (started) return emptyList()
        started = true
        return listOf(
            SharingFsmEffect.SendFrame(
                SharingFrames.pairedKeyEncryption(secureRandom = secureRandom),
            ),
        )
    }

    /**
     * Apply one input event and produce the corresponding effect list.
     *
     * The list is ordered: the orchestrator MUST process effects in the
     * order returned. In particular, when an event triggers both an
     * outbound frame AND a terminal notification (e.g.
     * `UserConsent(accept=false)` produces `SendFrame{REJECT}`, then
     * `Rejected`, then `Cancelled`), the frame send precedes the
     * teardown notification so the byte hits the wire before the
     * orchestrator closes the connection.
     */
    @Suppress(
        "ReturnCount", // One return per terminal/branch; merging would obscure the transition table.
        "CyclomaticComplexMethod", // Each branch is a single transition; combining would actually hurt readability.
    )
    public fun onEvent(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (state == InboundSharingState.Disconnected) {
            // Idempotent: the orchestrator can redundantly forward late
            // events without us re-emitting effects.
            return emptyList()
        }

        // Sender-only event — silently ignored.
        if (event is SharingFsmEvent.UserConsent && state != InboundSharingState.WaitingForUserConsent) {
            return listOf(
                SharingFsmEffect.ProtocolError(
                    "UserConsent received in state=$state (expected WaitingForUserConsent)",
                ),
            ).also { state = InboundSharingState.Disconnected }
        }

        // CANCEL handling is uniform across all non-terminal states.
        if (event is SharingFsmEvent.UserCancel) {
            return handleUserCancel()
        }
        if (event is SharingFsmEvent.FrameReceived && event.frame.v1.type == SharingFrameType.CANCEL) {
            return handlePeerCancel()
        }

        // Informational sender→receiver frames that the proto marks as
        // deprecated ("No longer used") but Samsung's stock Quick Share
        // (One UI) still emits in the wild — observed against a Galaxy
        // S26 sender on One UI 8.x, where PROGRESS_UPDATE arrives during
        // ReceivingPayloads and would otherwise abort the transfer at
        // the SharingFsm layer. CERTIFICATE_INFO is the other proto-
        // deprecated frame in the same lineage; pre-emptively tolerated
        // here for the same reason.
        if (event is SharingFsmEvent.FrameReceived &&
            (
                event.frame.v1.type == SharingFrameType.PROGRESS_UPDATE ||
                    event.frame.v1.type == SharingFrameType.CERTIFICATE_INFO
            )
        ) {
            return emptyList()
        }

        return when (state) {
            InboundSharingState.SentPairedKeyEncryption -> onSentPairedKeyEncryption(event)
            InboundSharingState.SentPairedKeyResult -> onSentPairedKeyResult(event)
            InboundSharingState.ReceivedPairedKeyResult -> onReceivedPairedKeyResult(event)
            InboundSharingState.WaitingForUserConsent -> onWaitingForUserConsent(event)
            InboundSharingState.ReceivingPayloads -> onReceivingPayloads(event)
            // Disconnected handled at the top of the function; this
            // branch is unreachable but Kotlin requires it for
            // exhaustiveness.
            InboundSharingState.Disconnected -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Per-state handlers
    // ------------------------------------------------------------------

    /**
     * In [InboundSharingState.SentPairedKeyEncryption] we expect a
     * `Frame{PAIRED_KEY_ENCRYPTION}` from the peer. Anything else (other
     * than CANCEL, handled above) is a protocol error.
     */
    @Suppress("ReturnCount") // One early return per validation step keeps the failure path readable.
    private fun onSentPairedKeyEncryption(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (event !is SharingFsmEvent.FrameReceived) {
            return protocolError("non-frame event in SentPairedKeyEncryption: ${event::class.simpleName}")
        }
        val frame = event.frame
        if (frame.v1.type != SharingFrameType.PAIRED_KEY_ENCRYPTION) {
            return protocolError("expected PAIRED_KEY_ENCRYPTION, got ${frame.v1.type}")
        }
        if (!frame.v1.hasPairedKeyEncryption()) {
            return protocolError("PAIRED_KEY_ENCRYPTION frame missing paired_key_encryption body")
        }
        // We do not actually verify the peer's PKE contents — NearDrop
        // does not either, and stock Android peers tolerate UNABLE
        // responses. We just acknowledge receipt by sending our PKR.
        state = InboundSharingState.SentPairedKeyResult
        return listOf(
            SharingFsmEffect.SendFrame(
                SharingFrames.pairedKeyResult(PairedKeyResultStatus.UNABLE),
            ),
        )
    }

    /**
     * In [InboundSharingState.SentPairedKeyResult] we expect the peer's
     * `Frame{PAIRED_KEY_RESULT}`. Its `status` field is informational —
     * NearDrop ignores it (any value still leads to introduction), so
     * we do too.
     */
    @Suppress("ReturnCount") // One early return per validation step keeps the failure path readable.
    private fun onSentPairedKeyResult(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (event !is SharingFsmEvent.FrameReceived) {
            return protocolError("non-frame event in SentPairedKeyResult: ${event::class.simpleName}")
        }
        val frame = event.frame
        if (frame.v1.type != SharingFrameType.PAIRED_KEY_RESULT) {
            return protocolError("expected PAIRED_KEY_RESULT, got ${frame.v1.type}")
        }
        if (!frame.v1.hasPairedKeyResult()) {
            return protocolError("PAIRED_KEY_RESULT frame missing paired_key_result body")
        }
        state = InboundSharingState.ReceivedPairedKeyResult
        return emptyList()
    }

    /**
     * In [InboundSharingState.ReceivedPairedKeyResult] we await the
     * sender's `IntroductionFrame`. On arrival the FSM moves to
     * [InboundSharingState.WaitingForUserConsent] and surfaces the
     * introduction up to the consent UI.
     */
    @Suppress("ReturnCount") // One early return per validation step keeps the failure path readable.
    private fun onReceivedPairedKeyResult(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (event !is SharingFsmEvent.FrameReceived) {
            return protocolError("non-frame event in ReceivedPairedKeyResult: ${event::class.simpleName}")
        }
        val frame = event.frame
        if (frame.v1.type == SharingFrameType.RESPONSE) {
            // On One UI 8.x the sender's local acceptance can race with
            // INTRODUCTION after the channel moves from BLE to Wi-Fi
            // Direct. Treat it the same as the documented
            // INTRODUCTION-then-RESPONSE ordering below: non-terminal
            // accept/unknown is only the sender declaring readiness.
            return handlePreemptiveConnectionResponse(frame)
        }
        if (frame.v1.type != SharingFrameType.INTRODUCTION) {
            return protocolError("expected INTRODUCTION, got ${frame.v1.type}")
        }
        if (!frame.v1.hasIntroduction()) {
            return protocolError("INTRODUCTION frame missing introduction body")
        }
        pendingIntroduction = frame.v1.introduction
        state = InboundSharingState.WaitingForUserConsent
        return listOf(SharingFsmEffect.IntroductionReceived(frame.v1.introduction))
    }

    /**
     * In [InboundSharingState.WaitingForUserConsent] the only legal
     * inputs are [SharingFsmEvent.UserConsent] or a CANCEL frame /
     * UserCancel (handled in [onEvent] before dispatch). Inbound
     * `Sharing.Nearby.Frame`s of any other kind are protocol errors.
     */
    @Suppress("ReturnCount") // Each early return guards a distinct event class; collapsing hurts readability.
    private fun onWaitingForUserConsent(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (event is SharingFsmEvent.FrameReceived &&
            event.frame.v1.type == SharingFrameType.RESPONSE
        ) {
            // Samsung's stock Quick Share (One UI) sends a preemptive
            // RESPONSE frame to the receiver right after the sender's
            // INTRODUCTION — the sender treats the user's tap on us in
            // the picker as their own consent and notifies us. NearDrop's
            // PROTOCOL.md does not document this, but it is observed in
            // the wild on One UI 8.x against a Galaxy S24/S26 sender.
            //
            //  - status=ACCEPT/UNKNOWN: ignore. We still need to show
            //    consent UI to the local user; the sender is just
            //    declaring readiness.
            //  - status=REJECT/NOT_ENOUGH_SPACE/UNSUPPORTED_ATTACHMENT_TYPE/
            //    TIMED_OUT: treat as a peer-side cancel — the sender has
            //    already abandoned the transfer, so our consent UI is
            //    moot.
            return handlePreemptiveConnectionResponse(event.frame)
        }
        if (event !is SharingFsmEvent.UserConsent) {
            // The peer is not supposed to push a frame at us while we are
            // showing consent UI. Treat any inbound frame here as a
            // protocol error.
            val detail =
                when (event) {
                    is SharingFsmEvent.FrameReceived -> {
                        val type = event.frame.v1.type
                        val status =
                            if (event.frame.v1.hasConnectionResponse()) {
                                event.frame.v1.connectionResponse.status.name
                            } else {
                                "(no body)"
                            }
                        "FrameReceived(type=$type, status=$status)"
                    }
                    else -> event::class.simpleName ?: "?"
                }
            return protocolError(
                "unexpected $detail in WaitingForUserConsent",
            )
        }
        return if (event.accept) {
            state = InboundSharingState.ReceivingPayloads
            listOf(
                SharingFsmEffect.SendFrame(
                    SharingFrames.connectionResponse(
                        status = ConnectionResponseStatus.ACCEPT,
                        introduction = pendingIntroduction,
                    ),
                ),
                SharingFsmEffect.ReadyToReceivePayloads,
                SharingFsmEffect.Completed,
            )
        } else {
            state = InboundSharingState.Disconnected
            listOf(
                SharingFsmEffect.SendFrame(
                    SharingFrames.connectionResponse(ConnectionResponseStatus.REJECT),
                ),
                SharingFsmEffect.Rejected(ConnectionResponseStatus.REJECT),
                SharingFsmEffect.Cancelled,
            )
        }
    }

    /**
     * Once we are in [InboundSharingState.ReceivingPayloads] the
     * negotiation FSM is "complete"; only CANCEL events from peer or
     * user produce further effects (and are handled in [onEvent] before
     * dispatch). Stray frames are protocol errors — for example, a
     * second `IntroductionFrame` after we already accepted is illegal.
     */
    private fun onReceivingPayloads(event: SharingFsmEvent): List<SharingFsmEffect> {
        val detail =
            when (event) {
                is SharingFsmEvent.FrameReceived -> {
                    val type = event.frame.v1.type
                    val status =
                        if (event.frame.v1.hasConnectionResponse()) {
                            event.frame.v1.connectionResponse.status.name
                        } else {
                            "(no body)"
                        }
                    "FrameReceived(type=$type, status=$status)"
                }
                else -> event::class.simpleName ?: "?"
            }
        return protocolError("unexpected $detail in ReceivingPayloads")
    }

    // ------------------------------------------------------------------
    // Cancel paths
    // ------------------------------------------------------------------

    private fun handleUserCancel(): List<SharingFsmEffect> {
        state = InboundSharingState.Disconnected
        return listOf(
            SharingFsmEffect.SendFrame(SharingFrames.cancel()),
            SharingFsmEffect.Cancelled,
        )
    }

    private fun handlePeerCancel(): List<SharingFsmEffect> {
        state = InboundSharingState.Disconnected
        return listOf(SharingFsmEffect.Cancelled)
    }

    private fun handlePreemptiveConnectionResponse(frame: SharingFrame): List<SharingFsmEffect> {
        val status = frame.v1.connectionResponse.status
        return when (status) {
            ConnectionResponseStatus.ACCEPT,
            ConnectionResponseStatus.UNKNOWN,
            -> emptyList()
            else -> handlePeerCancel()
        }
    }

    private fun protocolError(reason: String): List<SharingFsmEffect> {
        state = InboundSharingState.Disconnected
        return listOf(SharingFsmEffect.ProtocolError(reason))
    }
}
