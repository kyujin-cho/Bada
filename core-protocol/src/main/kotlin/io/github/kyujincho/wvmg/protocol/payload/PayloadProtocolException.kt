/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

/**
 * Thrown by [PayloadAssembler] for any unrecoverable protocol violation
 * detected while reassembling `PayloadTransferFrame` chunks.
 *
 * Mirrors the discipline of
 * [io.github.kyujincho.wvmg.protocol.crypto.securemessage.SequenceNumberMismatchException]:
 * every condition that throws this is **fatal to the connection** because
 * Quick Share has no resync protocol below the SecureChannel layer. The
 * higher-level state machine MUST close the connection on receipt — it
 * MUST NOT try to ignore the error and continue reading frames, because the
 * peer is now byte-misaligned with us in a way we cannot recover from.
 *
 * The assembler does its own state cleanup (drops the buffer or closes the
 * file channel for the offending `payload_id`) before throwing, so the
 * caller does not need to do anything beyond closing the connection.
 *
 * @param message Human-readable, English-only description of the
 *   violation. Intended for logging, not for surfacing to end users.
 * @param cause Optional underlying exception (e.g. an
 *   [java.io.IOException] from a failed `WritableByteChannel.write`).
 */
public class PayloadProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
