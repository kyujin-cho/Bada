/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.ukey2

import com.google.protobuf.ByteString
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Alert
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import dev.bluehouse.bada.protocol.transport.FramedConnection

/**
 * UKEY2 alert types that this implementation can send. Mirrors
 * `Ukey2Alert.AlertType` in `ukey.proto` but limited to the values the
 * Quick Share handshake actually emits — internal-error and
 * incorrect-message-type are intentionally absent because we surface those
 * paths through generic [java.io.IOException] / programming-error throws,
 * not as protocol-level alerts.
 *
 * Each enum constant carries the underlying numeric value used on the
 * wire so callers don't need to import the generated protobuf enum.
 */
public enum class Ukey2AlertType(
    public val protoType: Ukey2Alert.AlertType,
) {
    /** The peer-supplied bytes could not be deserialized as the expected proto. */
    BAD_MESSAGE(Ukey2Alert.AlertType.BAD_MESSAGE),

    /** A `Ukey2Message` arrived where some other type was expected. */
    BAD_MESSAGE_TYPE(Ukey2Alert.AlertType.BAD_MESSAGE_TYPE),

    /** The peer announced an unsupported protocol version. */
    BAD_VERSION(Ukey2Alert.AlertType.BAD_VERSION),

    /** The `random` field is absent or has an unexpected length. */
    BAD_RANDOM(Ukey2Alert.AlertType.BAD_RANDOM),

    /** The peer's cipher list contains nothing this implementation supports. */
    BAD_HANDSHAKE_CIPHER(Ukey2Alert.AlertType.BAD_HANDSHAKE_CIPHER),

    /** The peer requested a `next_protocol` other than [Ukey2.NEXT_PROTOCOL]. */
    BAD_NEXT_PROTOCOL(Ukey2Alert.AlertType.BAD_NEXT_PROTOCOL),

    /** The peer-supplied `GenericPublicKey` could not be parsed or is invalid. */
    BAD_PUBLIC_KEY(Ukey2Alert.AlertType.BAD_PUBLIC_KEY),
}

/**
 * Thrown by the UKEY2 client / server when the peer violates the
 * protocol. The exception carries the [alert] type (best-effort already
 * sent to the peer as a `Ukey2Alert` frame before the throw, where
 * possible) so callers can log structured diagnostics.
 *
 * Code that catches this exception should generally close the underlying
 * `FramedConnection` and tear down any per-connection state — UKEY2 has
 * no recovery mechanism after an alert is emitted.
 *
 * @param alert The alert type sent (or that should have been sent) to
 *   the peer. May be `null` for client-side errors that are detected
 *   *after* the alert path has already been exhausted (e.g., a write
 *   failure on the alert frame itself).
 * @param message Human-readable diagnostic, never logged at INFO/WARN
 *   verbatim — may include partial proto field values useful only for
 *   debugging.
 * @param cause Underlying transport or crypto failure, if any.
 */
public class Ukey2HandshakeException(
    public val alert: Ukey2AlertType?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Best-effort sender for `Ukey2Alert` frames.
 *
 * UKEY2 alerts are sent right before tearing down the connection, so
 * write failures here are not actionable: the peer is going away
 * regardless. We swallow [java.io.IOException] and log nothing — the
 * caller is expected to throw a [Ukey2HandshakeException] immediately
 * after this returns, which surfaces the real error.
 *
 * Called only from inside `Ukey2Client` / `Ukey2Server`; not part of the
 * public protocol surface.
 */
internal suspend fun sendUkey2Alert(
    connection: FramedConnection,
    alertType: Ukey2AlertType,
) {
    val alert =
        Ukey2Alert
            .newBuilder()
            .setType(alertType.protoType)
            .build()
    val wrapper =
        Ukey2Message
            .newBuilder()
            .setMessageType(Ukey2Message.Type.ALERT)
            .setMessageData(ByteString.copyFrom(alert.toByteArray()))
            .build()
    runCatching { connection.sendFrame(wrapper.toByteArray()) }
}
