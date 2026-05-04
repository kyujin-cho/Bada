/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame

/**
 * Internal: the [OutboundConnectionDriver]'s dispatch-loop event union.
 *
 * Three event sources meet at the dispatch loop's `select`:
 *  1. Wire frames coming up from the
 *     [io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel]
 *     (forwarded by the inbound pump as [Wire]).
 *  2. External user-supplied events
 *     ([OutboundExternalEvent.UserCancel]) arriving on
 *     [OutboundConnection.cancel], wrapped here as [External].
 *  3. Driver-owned timers such as [RemoteAcceptanceTimedOut].
 *  4. The peer closing the TCP half-channel cleanly ([PeerClosed]) or
 *     an unexpected pump-side I/O failure ([PumpError]).
 *
 * Hoisted to its own file so [OutboundConnectionDriver] can stay under
 * the project's 500-line file-size soft target.
 */
internal sealed interface OutboundDriverEvent {
    /** A wire-level OfflineFrame arrived on the SecureChannel. */
    data class Wire(
        val frame: OfflineFrame,
    ) : OutboundDriverEvent

    /** A user-supplied event (cancel) arrived on the channel. */
    data class External(
        val event: OutboundExternalEvent,
    ) : OutboundDriverEvent

    /** The peer closed the TCP connection cleanly. */
    object PeerClosed : OutboundDriverEvent

    /** The receiver did not answer the IntroductionFrame in time. */
    object RemoteAcceptanceTimedOut : OutboundDriverEvent

    /** The inbound pump terminated due to an unexpected error. */
    data class PumpError(
        val cause: Throwable,
    ) : OutboundDriverEvent
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
 * can `select` on a regular `Channel<OutboundWireMessage>`.
 */
internal sealed interface OutboundWireMessage {
    /** A successfully decoded OfflineFrame. */
    data class Frame(
        val frame: OfflineFrame,
    ) : OutboundWireMessage

    /** Peer closed the connection cleanly between frames. */
    object Closed : OutboundWireMessage

    /** The pump caught an unexpected I/O failure. */
    data class Error(
        val cause: Throwable,
    ) : OutboundWireMessage
}
