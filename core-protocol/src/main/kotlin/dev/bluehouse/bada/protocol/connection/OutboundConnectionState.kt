/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import dev.bluehouse.bada.protocol.sharing.ConnectionResponseStatus

/**
 * Observable lifecycle state of a single [OutboundConnection] attempt.
 *
 * Sender-side dual of [InboundConnectionState]. The same shape (a small
 * sealed hierarchy with explicit terminal states) is used so the UI
 * code can treat send and receive flows symmetrically.
 *
 * The states form a linear progression through the sender-role wire
 * protocol, with [Failed] / [Cancelled] / [Rejected] / [Completed] as
 * terminal endpoints. The orchestrator does not transition out of a
 * terminal state — observers can rely on a single notification per
 * outcome.
 *
 * Transition graph (`->` is a state transition):
 *
 * ```
 *   Idle
 *     -> Connecting                     (run() begins, before TCP connect)
 *   Connecting
 *     -> Handshaking                    (TCP socket open, ConnectionRequest enqueued)
 *   Handshaking
 *     -> AwaitingRemoteAcceptance       (UKEY2 + connection-response done; PIN known)
 *   AwaitingRemoteAcceptance
 *     -> Sending                        (peer ACCEPT received via ConnectionResponseFrame)
 *     -> Rejected                       (peer non-ACCEPT response)
 *     -> Cancelled                      (user / peer cancel)
 *     -> Failed                         (protocol error / I/O failure)
 *   Sending
 *     -> Completed                      (every announced payload streamed and Disconnection sent)
 *     -> Cancelled                      (user / peer cancel)
 *     -> Failed                         (protocol error / I/O failure)
 * ```
 *
 * [Completed], [Rejected], [Cancelled], and [Failed] are terminal — the
 * `StateFlow` will not emit again past them.
 */
public sealed interface OutboundConnectionState {
    /**
     * Initial state before [OutboundConnection.run] is invoked. The flow
     * is constructed in this state so observers attaching early do not
     * see `null`.
     */
    public object Idle : OutboundConnectionState

    /**
     * The orchestrator has begun the lifecycle and is opening a TCP
     * connection to the peer's advertised endpoint. UI may show
     * "Connecting...".
     */
    public object Connecting : OutboundConnectionState

    /**
     * The TCP socket is open. The orchestrator is in the unencrypted
     * bring-up phase: send `OfflineFrame{ConnectionRequest}`, drive the
     * UKEY2 client handshake, exchange `OfflineFrame{ConnectionResponse}`s.
     *
     * Higher layers can keep showing a generic spinner; the PIN is not
     * yet known.
     */
    public object Handshaking : OutboundConnectionState

    /**
     * The handshake completed; the orchestrator has computed the
     * confirmation PIN, sent its `IntroductionFrame`, and is waiting
     * for the receiver's `ConnectionResponseFrame`. The UI typically
     * renders the PIN here so the local user can read it out for
     * confirmation against what the receiver displays.
     *
     * @property pin 4-digit ASCII confirmation code derived from the
     *   UKEY2 `authString`. Always exactly
     *   [TransferMetadata.PIN_LENGTH] characters.
     */
    public data class AwaitingRemoteAcceptance(
        val pin: String,
    ) : OutboundConnectionState {
        init {
            require(pin.length == TransferMetadata.PIN_LENGTH) {
                "pin must be exactly ${TransferMetadata.PIN_LENGTH} characters, got ${pin.length}"
            }
        }
    }

    /**
     * The receiver accepted; payloads are streaming out. Updated after
     * every chunk so observers can drive a progress bar without polling.
     *
     * @property pin The same PIN previously surfaced in
     *   [AwaitingRemoteAcceptance], retained so consumers do not have
     *   to remember it across state transitions.
     * @property progress Cumulative byte counters plus the smoothed
     *   bytes-per-second rate and ETA produced by issue #46's rate
     *   estimator. UI renders progress + "12 MB of 100 MB, 30 seconds
     *   remaining" off this object. [TransferProgress.bytesPerSecond]
     *   is `0` while warming up; [TransferProgress.etaSeconds] is
     *   `null` until the rate is non-zero.
     * @property currentItemPayloadId If a single payload is currently
     *   in flight (most common), its `payload_id`. `null` between
     *   payloads or before the first chunk has been written.
     */
    public data class Sending(
        val pin: String,
        val progress: TransferProgress,
        val currentItemPayloadId: Long?,
    ) : OutboundConnectionState {
        /**
         * Cumulative bytes pushed onto the wire. Convenience accessor
         * that reads from [progress] so the two stay in sync.
         */
        public val bytesSent: Long get() = progress.bytesTransferred

        /**
         * Sum of [FileSource.size] across every announced file.
         * Convenience accessor that reads from [progress].
         */
        public val totalSize: Long get() = progress.totalSize
    }

    /**
     * Terminal — every announced file streamed in full and the
     * `Disconnection` frame was sent to the peer.
     */
    public object Completed : OutboundConnectionState

    /**
     * Terminal — the receiver responded with a non-ACCEPT
     * `ConnectionResponseFrame`. `status` distinguishes the cases —
     * `REJECT` (user said no), `NOT_ENOUGH_SPACE`, `TIMED_OUT`,
     * `UNSUPPORTED_ATTACHMENT_TYPE`, or `UNKNOWN`.
     *
     * @property status The peer's response status, never
     *   [ConnectionResponseStatus.ACCEPT] by construction.
     */
    public data class Rejected(
        val status: ConnectionResponseStatus,
    ) : OutboundConnectionState

    /**
     * Terminal — the transfer was cancelled. `cause` distinguishes the
     * two cases.
     *
     * @property cause Whether cancellation came from the local user
     *   (the orchestrator emitted a `CancelFrame` before closing) or
     *   from the peer (the orchestrator observed an inbound
     *   `CancelFrame` and closed).
     */
    public data class Cancelled(
        val cause: CancelCause,
    ) : OutboundConnectionState

    /**
     * Terminal — an unrecoverable error tore the connection down.
     *
     * @property reason Short, English-only description (e.g.
     *   `"UKEY2 BAD_VERSION"`, `"I/O error: Connection reset"`).
     *   Intended for logs and error banners; never contains key
     *   material or private user data.
     */
    public data class Failed(
        val reason: String,
    ) : OutboundConnectionState
}

/**
 * Returns whether this state is one of the terminal endpoints
 * (Completed / Rejected / Cancelled / Failed).
 *
 * Lifted to a top-level helper so call sites can avoid four-way
 * `is` chains that detekt's [ComplexCondition] rule (correctly)
 * flags as hard-to-read.
 */
internal fun OutboundConnectionState.isTerminal(): Boolean =
    when (this) {
        OutboundConnectionState.Completed,
        is OutboundConnectionState.Cancelled,
        is OutboundConnectionState.Failed,
        is OutboundConnectionState.Rejected,
        -> true
        else -> false
    }
