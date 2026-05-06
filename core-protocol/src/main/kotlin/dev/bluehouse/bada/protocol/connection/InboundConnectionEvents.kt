/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame

/**
 * Internal: the [InboundConnectionDriver]'s dispatch-loop event union.
 *
 * Three event sources meet at the dispatch loop's `select`:
 *  1. Wire frames coming up from the [dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel]
 *     (forwarded by the inbound pump as [Wire]).
 *  2. External user-supplied events ([ExternalEvent.UserConsent],
 *     [ExternalEvent.UserCancel]) arriving on
 *     [InboundConnection.submitUserConsent] / [InboundConnection.cancel],
 *     wrapped here as [External].
 *  3. The peer closing the TCP half-channel cleanly ([PeerClosed]) or
 *     an unexpected pump-side I/O failure ([PumpError]).
 *
 * Hoisted to its own file so [InboundConnectionDriver] can stay under
 * the project's 500-line file-size soft target.
 */
internal sealed interface DriverEvent {
    /** A wire-level OfflineFrame arrived on the SecureChannel. */
    data class Wire(
        val frame: OfflineFrame,
    ) : DriverEvent

    /** A user-supplied event (consent / cancel) arrived on the channel. */
    data class External(
        val event: ExternalEvent,
    ) : DriverEvent

    /** The peer closed the TCP connection cleanly. */
    object PeerClosed : DriverEvent

    /** The inbound pump terminated due to an unexpected error. */
    data class PumpError(
        val cause: Throwable,
    ) : DriverEvent
}

/**
 * Internal: messages the inbound pump pushes onto the wire channel
 * for the dispatch loop to consume.
 *
 * The pump is a separate coroutine because
 * `kotlinx.coroutines.selects.select` cannot suspend on
 * `SecureChannel.receiveOfflineFrame()` directly (no `onReceive`-style
 * SelectClause). The pump's purpose is just to translate
 * suspending-function calls into channel sends, so the dispatch loop
 * can `select` on a regular `Channel<WireMessage>`.
 */
internal sealed interface WireMessage {
    /** A successfully decoded OfflineFrame. */
    data class Frame(
        val frame: OfflineFrame,
    ) : WireMessage

    /** Peer closed the connection cleanly between frames. */
    object Closed : WireMessage

    /** The pump caught an unexpected I/O failure. */
    data class Error(
        val cause: Throwable,
    ) : WireMessage
}
