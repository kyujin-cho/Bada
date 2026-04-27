/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.sharing

import java.security.SecureRandom

/**
 * Sender-role Quick Share negotiation state machine.
 *
 * Symmetric in spirit to [InboundSharingFsm]: it shares the
 * paired-key handshake but has a different middle (it emits the
 * `IntroductionFrame` instead of waiting for one, and waits for the
 * `ConnectionResponseFrame` instead of waiting for user consent).
 *
 * ### Lifecycle
 *
 *  1. The orchestrator (issue #17 — `OutboundConnection`) constructs the
 *     FSM with a pre-built [IntroductionFrame] containing
 *     `file_metadata[]` / `text_metadata[]` for the items the user wants
 *     to send. Each metadata entry's `payload_id` MUST match the
 *     `payload_id` the orchestrator will use when streaming the actual
 *     file bytes after [SharingFsmEffect.ReadyToSendPayloads] fires.
 *  2. The orchestrator calls [start] exactly once. The FSM emits the
 *     outbound `PairedKeyEncryptionFrame` and enters
 *     [OutboundSharingState.SentPairedKeyEncryption].
 *  3. The orchestrator pumps inbound `Sharing.Nearby.Frame` arrivals
 *     and user cancel requests through [onEvent].
 *  4. On peer ACCEPT, the FSM transitions to
 *     [OutboundSharingState.SendingPayloads] and emits
 *     [SharingFsmEffect.ReadyToSendPayloads]. The orchestrator now
 *     streams FILE / BYTES application payloads.
 *  5. On any non-ACCEPT response status, the FSM transitions to
 *     [OutboundSharingState.Disconnected] and emits
 *     [SharingFsmEffect.Rejected] with the peer's status — including
 *     `REJECT`, `UNKNOWN`, `NOT_ENOUGH_SPACE`, `TIMED_OUT`, and
 *     `UNSUPPORTED_ATTACHMENT_TYPE` (the issue's acceptance criteria
 *     enumerate these).
 *
 * ### Threading model
 *
 * Same as [InboundSharingFsm]: not thread-safe; the orchestrator
 * serializes all calls.
 *
 * @param introduction The [IntroductionFrame] to emit after the
 *   paired-key dance completes. Built by the orchestrator from the
 *   user's chosen files / text and the `payload_id`s reserved for
 *   them. Stored by reference; the FSM does not copy.
 * @param secureRandom Source of bytes for the outgoing
 *   [PairedKeyEncryptionFrame] random fields.
 */
public class OutboundSharingFsm(
    private val introduction: IntroductionFrame,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /** Current FSM state. See [InboundSharingFsm.state] for the contract. */
    public var state: OutboundSharingState = OutboundSharingState.SentPairedKeyEncryption
        private set

    private var started: Boolean = false

    /**
     * Drive the FSM to its initial state and produce the seed
     * `PairedKeyEncryptionFrame`. Idempotent — same contract as
     * [InboundSharingFsm.start].
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
     * The list is ordered (frame send before terminal notifications).
     * After [OutboundSharingState.Disconnected] every call returns an
     * empty list.
     */
    @Suppress(
        "ReturnCount", // One return per terminal/branch; merging would obscure the transition table.
    )
    public fun onEvent(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (state == OutboundSharingState.Disconnected) return emptyList()

        // Receiver-only event — the sender FSM never gets a UserConsent.
        // We treat the bug as a protocol error so it surfaces loudly
        // rather than silently dropping the event.
        if (event is SharingFsmEvent.UserConsent) {
            return protocolError("UserConsent is receiver-only; sender FSM cannot accept it")
        }

        if (event is SharingFsmEvent.UserCancel) return handleUserCancel()
        if (event is SharingFsmEvent.FrameReceived && event.frame.v1.type == SharingFrameType.CANCEL) {
            return handlePeerCancel()
        }

        return when (state) {
            OutboundSharingState.SentPairedKeyEncryption -> onSentPairedKeyEncryption(event)
            OutboundSharingState.SentPairedKeyResult -> onSentPairedKeyResult(event)
            OutboundSharingState.SentIntroduction -> onSentIntroduction(event)
            OutboundSharingState.SendingPayloads -> onSendingPayloads(event)
            OutboundSharingState.Disconnected -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Per-state handlers
    // ------------------------------------------------------------------

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
        state = OutboundSharingState.SentPairedKeyResult
        return listOf(
            SharingFsmEffect.SendFrame(
                SharingFrames.pairedKeyResult(PairedKeyResultStatus.UNABLE),
            ),
        )
    }

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
        state = OutboundSharingState.SentIntroduction
        return listOf(
            SharingFsmEffect.SendFrame(SharingFrames.introduction(introduction)),
        )
    }

    @Suppress(
        "ReturnCount", // One early return per validation step keeps the failure path readable.
        "CyclomaticComplexMethod", // One branch per Status enum value; merging would lose specificity.
    )
    private fun onSentIntroduction(event: SharingFsmEvent): List<SharingFsmEffect> {
        if (event !is SharingFsmEvent.FrameReceived) {
            return protocolError("non-frame event in SentIntroduction: ${event::class.simpleName}")
        }
        val frame = event.frame
        if (frame.v1.type != SharingFrameType.RESPONSE) {
            return protocolError("expected RESPONSE (ConnectionResponseFrame), got ${frame.v1.type}")
        }
        if (!frame.v1.hasConnectionResponse()) {
            return protocolError("RESPONSE frame missing connection_response body")
        }
        val status = frame.v1.connectionResponse.status
        return when (status) {
            ConnectionResponseStatus.ACCEPT -> {
                state = OutboundSharingState.SendingPayloads
                listOf(
                    SharingFsmEffect.ReadyToSendPayloads,
                    SharingFsmEffect.Completed,
                )
            }
            ConnectionResponseStatus.REJECT,
            ConnectionResponseStatus.UNKNOWN,
            ConnectionResponseStatus.NOT_ENOUGH_SPACE,
            ConnectionResponseStatus.TIMED_OUT,
            ConnectionResponseStatus.UNSUPPORTED_ATTACHMENT_TYPE,
            -> {
                state = OutboundSharingState.Disconnected
                listOf(
                    SharingFsmEffect.Rejected(status),
                    SharingFsmEffect.Cancelled,
                )
            }
            // Defensive: protobuf-javalite synthesizes a `null` for an
            // unset enum, but `connection_response.status` is `optional`
            // with default UNKNOWN, so this branch is virtually
            // unreachable. We still treat it as protocol error so a
            // future proto extension surfacing a brand-new status code
            // does not silently fall through as ACCEPT.
            null -> protocolError("ConnectionResponseFrame.status was null")
        }
    }

    /**
     * Once we are in [OutboundSharingState.SendingPayloads] the
     * negotiation FSM is complete; only CANCEL events produce further
     * effects (handled in [onEvent] before dispatch). Stray frames are
     * protocol errors.
     */
    private fun onSendingPayloads(event: SharingFsmEvent): List<SharingFsmEffect> =
        protocolError("unexpected ${event::class.simpleName} in SendingPayloads")

    // ------------------------------------------------------------------
    // Cancel paths
    // ------------------------------------------------------------------

    private fun handleUserCancel(): List<SharingFsmEffect> {
        state = OutboundSharingState.Disconnected
        return listOf(
            SharingFsmEffect.SendFrame(SharingFrames.cancel()),
            SharingFsmEffect.Cancelled,
        )
    }

    private fun handlePeerCancel(): List<SharingFsmEffect> {
        state = OutboundSharingState.Disconnected
        return listOf(SharingFsmEffect.Cancelled)
    }

    private fun protocolError(reason: String): List<SharingFsmEffect> {
        state = OutboundSharingState.Disconnected
        return listOf(SharingFsmEffect.ProtocolError(reason))
    }
}
