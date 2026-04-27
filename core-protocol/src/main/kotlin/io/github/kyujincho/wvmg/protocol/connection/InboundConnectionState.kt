/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader

/**
 * Observable lifecycle state of a single [InboundConnection] attempt.
 *
 * Surfaced via `InboundConnection.state` (a `StateFlow`) so the UI can
 * render a consent sheet, progress bar, success / failure toast, etc.
 * without polling the orchestrator.
 *
 * The states form a linear progression through the receiver-role wire
 * protocol, with [Failed] / [Cancelled] / [Rejected] / [Completed] as
 * terminal endpoints. The orchestrator does not transition out of a
 * terminal state — observers can rely on a single notification per
 * outcome.
 *
 * Transition graph (`->` is a state transition):
 *
 * ```
 *   Idle
 *     -> Handshaking                    (run() begins)
 *   Handshaking
 *     -> Negotiating                    (UKEY2 + connection-response done)
 *   Negotiating
 *     -> WaitingForUserConsent          (peer's Introduction observed)
 *     -> Failed                         (protocol error / I/O failure)
 *   WaitingForUserConsent
 *     -> Receiving                      (user accepts via submitUserConsent(true))
 *     -> Rejected                       (user rejects via submitUserConsent(false))
 *     -> Cancelled                      (user / peer cancel)
 *     -> Failed                         (protocol error / I/O failure)
 *   Receiving
 *     -> Completed                      (every announced payload arrived)
 *     -> Cancelled                      (user / peer cancel)
 *     -> Failed                         (protocol error / I/O failure)
 * ```
 *
 * `Completed`, `Rejected`, `Cancelled`, and `Failed` are terminal — the
 * `StateFlow` will not emit again past them.
 */
public sealed interface InboundConnectionState {
    /**
     * Initial state before [InboundConnection.run] is invoked. The flow
     * is constructed in this state so observers attaching early do not
     * see `null`.
     */
    public object Idle : InboundConnectionState

    /**
     * The unencrypted bring-up phase: read peer's
     * `OfflineFrame{ConnectionRequest}`, drive the UKEY2 server
     * handshake, exchange `OfflineFrame{ConnectionResponse}`s.
     *
     * Higher layers can show a generic "Connecting..." spinner here.
     */
    public object Handshaking : InboundConnectionState

    /**
     * The encrypted negotiation phase: paired-key dance + waiting for
     * the sender's `IntroductionFrame`.
     *
     * UI should still show a spinner; no metadata is known yet.
     */
    public object Negotiating : InboundConnectionState

    /**
     * The introduction has been received. The orchestrator is now
     * blocked on [InboundConnection.submitUserConsent].
     *
     * @property metadata Files / text the peer wants to send, plus the
     *   confirmation PIN. The UI renders this as a consent sheet.
     */
    public data class WaitingForUserConsent(
        val metadata: TransferMetadata,
    ) : InboundConnectionState

    /**
     * The user accepted; payloads are streaming in. Updated after every
     * `PayloadTransferFrame` chunk so observers can drive a progress
     * bar without polling.
     *
     * @property bytesReceived Cumulative bytes received across all
     *   in-flight payloads (FILE writes + BYTES buffer fill). Resets
     *   per-connection — does NOT include terminated transfers.
     * @property totalSize Sum of `total_size` across every announced
     *   FILE / BYTES item from the [TransferMetadata]. The ratio
     *   `bytesReceived / totalSize` is a faithful overall progress
     *   estimate.
     * @property currentItemPayloadId If a single payload is currently
     *   active (most common), its `payload_id`. `null` between
     *   payloads or if no DATA chunk has arrived yet.
     * @property currentItemType The payload type of the active item,
     *   `null` between payloads. Useful for the UI to switch icons
     *   between BYTES and FILE.
     */
    public data class Receiving(
        val bytesReceived: Long,
        val totalSize: Long,
        val currentItemPayloadId: Long?,
        val currentItemType: PayloadHeader.PayloadType?,
    ) : InboundConnectionState

    /**
     * Terminal -- every announced item arrived in full and the
     * `Disconnection` frame was sent to the peer.
     *
     * @property items Successfully received items, in the order they
     *   were announced in the introduction. Files carry the temporary
     *   destination headers that the [io.github.kyujincho.wvmg.protocol.payload.FileDestinationFactory]
     *   produced; texts carry the assembled UTF-8 bytes.
     */
    public data class Completed(
        val items: List<ReceivedItem>,
    ) : InboundConnectionState

    /**
     * Terminal -- the user rejected the transfer in the consent sheet.
     * The orchestrator sent `ConnectionResponseFrame{REJECT}` followed
     * by `Disconnection` and closed.
     */
    public object Rejected : InboundConnectionState

    /**
     * Terminal -- the transfer was cancelled. `cause` distinguishes the
     * two cases.
     *
     * @property cause Whether cancellation came from the local user
     *   (the orchestrator emitted a `CancelFrame` before closing) or
     *   from the peer (the orchestrator observed an inbound
     *   `CancelFrame` and closed).
     */
    public data class Cancelled(
        val cause: CancelCause,
    ) : InboundConnectionState

    /**
     * Terminal -- an unrecoverable error tore the connection down.
     *
     * Lives in the same severity class as `Cancelled` for observers; a
     * UI may want to show "Transfer failed" with [reason] as detail.
     *
     * @property reason Short, English-only description (e.g.
     *   `"UKEY2 BAD_VERSION"`, `"PayloadTransfer offset mismatch"`).
     *   Intended for logs and error banners; never contains key
     *   material or private user data.
     */
    public data class Failed(
        val reason: String,
    ) : InboundConnectionState
}

/**
 * Returns whether this state is one of the terminal endpoints
 * (Completed / Rejected / Cancelled / Failed).
 *
 * Lifted to a top-level helper so call sites can avoid four-way
 * `is` chains that detekt's [ComplexCondition] rule (correctly)
 * flags as hard-to-read.
 */
internal fun InboundConnectionState.isTerminal(): Boolean =
    when (this) {
        is InboundConnectionState.Completed,
        is InboundConnectionState.Cancelled,
        is InboundConnectionState.Failed,
        -> true
        InboundConnectionState.Rejected -> true
        else -> false
    }

/**
 * Origin of a cancellation event observed by [InboundConnection].
 *
 * Quick Share's wire protocol does not distinguish "the peer pressed
 * cancel" from "the peer crashed" -- both surface as a `CancelFrame`.
 * For our purposes, anything coming *from* the peer is `PEER`; anything
 * caused by [InboundConnection.cancel] (or by coroutine cancellation)
 * is `LOCAL`.
 */
public enum class CancelCause {
    /** Local user invoked `cancel()` (or the calling coroutine was cancelled). */
    LOCAL,

    /** Peer sent a `CancelFrame` or closed the socket cleanly mid-transfer. */
    PEER,
}
