/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import io.github.kyujincho.wvmg.protocol.sharing.ConnectionResponseStatus

/**
 * Final outcome of an [OutboundConnection.run] invocation.
 *
 * The same information is also published on
 * [OutboundConnection.state] — this return value is for callers that
 * prefer a one-shot suspend signature ("await the connection finishing")
 * to a `StateFlow` collector.
 *
 * Sealed because outcomes are an exhaustive small set; pattern-matching
 * with `when` gives the caller compile-time exhaustiveness on the
 * success / reject / cancel / fail axes.
 */
public sealed interface OutboundResult {
    /**
     * Every announced file streamed in full. The `Disconnection` frame
     * has already been sent and the socket closed.
     */
    public object Completed : OutboundResult

    /**
     * The receiver responded with a non-ACCEPT
     * `ConnectionResponseFrame`. The orchestrator sent the trailing
     * `Disconnection` and closed the socket.
     *
     * @property status The peer's response status, never
     *   [ConnectionResponseStatus.ACCEPT] by construction.
     */
    public data class Rejected(
        val status: ConnectionResponseStatus,
    ) : OutboundResult

    /**
     * The transfer was cancelled. See [CancelCause] for the origin.
     *
     * @property cause Whether the cancel came from the local user or
     *   the peer.
     */
    public data class Cancelled(
        val cause: CancelCause,
    ) : OutboundResult

    /**
     * The transfer failed before completing. `reason` is a short,
     * English-only label suitable for logging.
     *
     * @property reason Short failure description.
     */
    public data class Failed(
        val reason: String,
    ) : OutboundResult
}
