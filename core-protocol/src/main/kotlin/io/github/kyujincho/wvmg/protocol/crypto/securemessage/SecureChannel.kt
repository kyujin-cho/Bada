/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.securemessage

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import io.github.kyujincho.wvmg.protocol.crypto.D2DSessionKeys
import io.github.kyujincho.wvmg.protocol.crypto.DirectionalKeys
import io.github.kyujincho.wvmg.protocol.transport.FramedConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * Authenticated, ordered, encrypted bidirectional channel for `OfflineFrame`
 * messages on top of a [FramedConnection].
 *
 * This is the layer that sits between the raw length-prefixed TCP framing
 * (`FramedConnection`, issue #7) and the higher-level protocol state
 * machines (`OfflineFrame` handlers, issue #15+). After a successful UKEY2
 * handshake, the rest of the Quick Share connection consists exclusively of
 * `SecureMessage`-wrapped `OfflineFrame`s flowing in both directions, each
 * carrying a strictly increasing sequence number.
 *
 * ### Owned state
 *
 *  - **`DirectionalKeys`** — the four 32-byte symmetric keys
 *    (`sendEncryptKey`, `sendHmacKey`, `receiveEncryptKey`, `receiveHmacKey`)
 *    selected from the [D2DSessionKeys] bundle by
 *    [D2DSessionKeys.forRole]. The channel never copies these arrays; it
 *    holds the references for its lifetime and zeroes nothing on close
 *    (the JVM does not give us a portable way to zero a `ByteArray` and
 *    have it stay zeroed across GC moves; the rest of the stack relies on
 *    process isolation).
 *  - **`mySeq`** — the next sequence number to assign on send. Starts at
 *    `0` and is **pre-incremented** before each send (matching NearDrop's
 *    Swift `sendSequenceNumber += 1` followed by use of the new value).
 *    The first frame sent therefore carries `sequence_number = 1`.
 *  - **`theirSeq`** — the next sequence number expected on receive. Same
 *    pre-increment semantics: the first valid received frame must carry
 *    `sequence_number = 1`. Anything else is a protocol error.
 *
 * Counters are `Long` rather than `Int` to match the protobuf `int32`'s
 * full positive range (up to `Int.MAX_VALUE`). Promoting to `Long`
 * internally also keeps the increment from silently rolling over if a
 * connection ever lasted long enough to send 2 billion frames (it won't,
 * but the cost of being defensive is one extra byte per counter).
 *
 * ### Thread / coroutine safety
 *
 * Internally we use one [Mutex] for the send half and another for the
 * receive half — same rationale as [FramedConnection]'s read/write split:
 * a Quick Share connection is fully duplex, so concurrent send + receive
 * must not contend. Two coroutines must NOT call [sendOfflineFrame]
 * simultaneously (the second blocks on the mutex; the resulting on-wire
 * order is non-deterministic). Same for [receiveOfflineFrame].
 *
 * ### Failure model
 *
 * Every receive-side failure is **fatal to the channel**. The channel is
 * intentionally designed to NOT recover from any of:
 *
 *  - HMAC signature mismatch ([SecureMessageVerificationException]).
 *  - Malformed proto, missing fields, or unsupported crypto schemes
 *    ([SecureMessageFormatException]).
 *  - AES decrypt failure ([SecureMessageCryptoException] — should be
 *    unreachable past the HMAC check, but we surface it explicitly).
 *  - Sequence-number mismatch ([SequenceNumberMismatchException]).
 *
 * After any of these, the channel's internal counters and the underlying
 * socket are left in whatever state the failure produced, and callers
 * MUST close the connection. We do not try to resynchronize. This matches
 * NearDrop's `protocolError()` path.
 *
 * @param framedConnection The transport. Owned by the caller (not closed
 *   by [close]); the channel only delegates `sendFrame`/`receiveFrame`.
 * @param sessionKeys The full key bundle from
 *   [io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation.derive].
 *   The channel resolves [DirectionalKeys] internally via
 *   [D2DSessionKeys.forRole] so callers cannot accidentally pass the
 *   wrong direction.
 * @param secureRandom Source of IV randomness. Production callers should
 *   pass a default [SecureRandom]; tests pass a deterministic stub to
 *   reproduce exact ciphertexts.
 */
public class SecureChannel internal constructor(
    private val framedConnection: FramedConnection,
    internal val session: SecureMessageSession,
) : AutoCloseable {
    public constructor(
        framedConnection: FramedConnection,
        sessionKeys: D2DSessionKeys,
        secureRandom: SecureRandom = SecureRandom(),
    ) : this(
        framedConnection = framedConnection,
        session = SecureMessageSession(sessionKeys, secureRandom),
    )

    /**
     * Encrypts, signs, and sends a single [OfflineFrame].
     *
     * The send pipeline:
     *
     *   1. **Acquire the send mutex.** Holding the mutex across the whole
     *      pipeline is what keeps [mySeq] in lock-step with the wire
     *      frame ordering — the increment and the actual `sendFrame`
     *      call cannot interleave with another concurrent send.
     *   2. **Pre-increment [mySeq].** First frame is `seq = 1`.
     *   3. **Wrap** the serialized [frame] inside a `DeviceToDeviceMessage`
     *      with the new sequence number.
     *   4. **Encrypt + sign** via [SecureMessageCodec.encryptAndSign] using
     *      a freshly drawn 16-byte random IV.
     *   5. **Send** the resulting [com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage]
     *      bytes over the [FramedConnection].
     *
     * @throws java.io.IOException if the underlying socket write fails.
     *   The channel's [mySeq] has already been advanced at that point;
     *   callers must close the connection rather than retry.
     */
    public suspend fun sendOfflineFrame(frame: OfflineFrame) {
        session.sendOfflineFrame(framedConnection, frame)
    }

    /**
     * Receives, verifies, decrypts, and order-checks a single [OfflineFrame].
     *
     * The receive pipeline mirrors send in reverse, with one critical
     * extra step (sequence-number validation):
     *
     *   1. **Acquire the receive mutex.** Same rationale as send.
     *   2. **Pre-increment [theirSeq].** First valid frame is `seq = 1`.
     *   3. **`framedConnection.receiveFrame()`** — read one length-prefixed
     *      [com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage].
     *   4. **HMAC-then-decrypt** via [SecureMessageCodec.verifyAndDecrypt].
     *      Verification happens BEFORE decryption (constant-time MAC compare).
     *   5. **Unwrap** the inner `DeviceToDeviceMessage`. Validate that the
     *      peer's sequence number matches our expected counter exactly.
     *      Out-of-order frames (replays, gaps, reorders) are protocol
     *      errors per Quick Share spec.
     *   6. **Parse** the inner [OfflineFrame] proto and return it.
     *
     * @return The decoded [OfflineFrame] proto. Caller dispatches on
     *   `v1.type` to handle the specific frame kind.
     * @throws SecureMessageVerificationException if the HMAC fails.
     * @throws SecureMessageFormatException if any proto is malformed or
     *   declares an unsupported crypto scheme.
     * @throws SecureMessageCryptoException if AES decryption itself fails.
     * @throws SequenceNumberMismatchException if the peer's sequence number
     *   is not exactly [theirSeq] after the pre-increment.
     * @throws java.io.IOException for any socket-level read failure (incl.
     *   [io.github.kyujincho.wvmg.protocol.transport.EndOfFrameStream] when
     *   the peer closes cleanly between frames).
     */
    public suspend fun receiveOfflineFrame(): OfflineFrame = session.receiveOfflineFrame(framedConnection)

    /**
     * The next outgoing sequence number that [sendOfflineFrame] WOULD use,
     * exposed for diagnostics and tests. The value increases by exactly
     * one on each successful send.
     *
     * Returns `0` before any frame has been sent (so the first send
     * produces sequence number `1`).
     */
    public val nextSendSequenceNumber: Long
        get() = session.nextSendSequenceNumber

    /**
     * The next incoming sequence number that [receiveOfflineFrame] WOULD
     * accept, exposed for diagnostics and tests. Same pre-increment
     * semantics as [nextSendSequenceNumber].
     */
    public val nextReceiveSequenceNumber: Long
        get() = session.nextReceiveSequenceNumber

    /**
     * Closes the underlying [FramedConnection]. The channel itself holds
     * no closeable resources beyond the transport.
     */
    override fun close() {
        framedConnection.close()
    }

    internal fun withTransport(framedConnection: FramedConnection): SecureChannel =
        SecureChannel(framedConnection, session)

    internal fun hasBufferedInput(): Boolean = framedConnection.hasBufferedInput()
}

/**
 * Shared SecureMessage state for one UKEY2-derived encrypted session.
 *
 * A Quick Share bandwidth upgrade moves the encrypted stream from one
 * physical transport to another without renegotiating UKEY2. The send
 * and receive sequence numbers therefore belong to the secure session,
 * not to the socket. [SecureChannel] is the single-transport facade used
 * by the existing connection drivers; upgrade orchestration can create a
 * second facade with [SecureChannel.withTransport] while preserving the
 * counters held here.
 */
internal class SecureMessageSession(
    sessionKeys: D2DSessionKeys,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val keys: DirectionalKeys = sessionKeys.forRole()

    // Sequence counters live behind the same locks that serialize wire I/O.
    // We do not use AtomicLong because the only correct semantics for
    // sequence numbers here is "increment THEN send/receive under the
    // same lock that serializes the wire frame". Any window between
    // increment and send would let a second coroutine sneak in a frame
    // with a higher sequence number, causing the receiver to reject ours.
    private var mySeq: Long = 0L
    private var theirSeq: Long = 0L

    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()

    suspend fun sendOfflineFrame(
        framedConnection: FramedConnection,
        frame: OfflineFrame,
    ) {
        sendMutex.withLock {
            mySeq += 1
            // Use the int-narrowed value because `DeviceToDeviceMessage.sequence_number`
            // is `int32` on the wire. The Long counter exists purely so we
            // can detect overflow if it ever happens — Quick Share peers
            // do not negotiate sequence numbers above Int.MAX_VALUE.
            check(mySeq <= Int.MAX_VALUE.toLong()) {
                "Send sequence number overflowed Int.MAX_VALUE; close the channel"
            }
            val sequenceNumber = mySeq.toInt()

            val payload = frame.toByteArray()
            val d2dBytes =
                SecureMessageCodec.wrapDeviceToDeviceMessage(
                    offlineFrame = payload,
                    sequenceNumber = sequenceNumber,
                )
            val iv = SecureMessageCodec.randomIv(secureRandom)
            val secureMessageBytes =
                SecureMessageCodec.encryptAndSign(
                    payload = d2dBytes,
                    encryptKey = keys.sendEncryptKey,
                    hmacKey = keys.sendHmacKey,
                    iv = iv,
                )
            framedConnection.sendFrame(secureMessageBytes)
        }
    }

    suspend fun receiveOfflineFrame(framedConnection: FramedConnection): OfflineFrame {
        receiveMutex.withLock {
            theirSeq += 1
            check(theirSeq <= Int.MAX_VALUE.toLong()) {
                "Receive sequence number overflowed Int.MAX_VALUE; close the channel"
            }
            val expectedSeq = theirSeq.toInt()

            val secureMessageBytes = framedConnection.receiveFrame()
            val d2dBytes =
                SecureMessageCodec.verifyAndDecrypt(
                    secureMessageBytes = secureMessageBytes,
                    decryptKey = keys.receiveEncryptKey,
                    hmacKey = keys.receiveHmacKey,
                )
            val unwrapped = SecureMessageCodec.unwrapDeviceToDeviceMessage(d2dBytes)
            if (unwrapped.sequenceNumber != expectedSeq) {
                throw SequenceNumberMismatchException(
                    expected = expectedSeq,
                    actual = unwrapped.sequenceNumber,
                )
            }
            return parseOfflineFrame(unwrapped.payload)
        }
    }

    val nextSendSequenceNumber: Long
        get() = mySeq

    val nextReceiveSequenceNumber: Long
        get() = theirSeq

    /**
     * Parses a serialized [OfflineFrame] and surfaces parse failures as a
     * [SecureMessageFormatException] (the protocol-level exception type)
     * rather than the raw `InvalidProtocolBufferException`.
     */
    private fun parseOfflineFrame(bytes: ByteArray): OfflineFrame =
        try {
            OfflineFrame.parseFrom(bytes)
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            throw SecureMessageFormatException("OfflineFrame proto failed to parse", e)
        }
}

/**
 * Thrown by [SecureChannel.receiveOfflineFrame] when the peer's reported
 * sequence number does not match the expected value (the locally
 * pre-incremented counter).
 *
 * This is unrecoverable — there is no resync protocol. Callers MUST close
 * the connection.
 *
 * @property expected The sequence number we expected on this frame.
 * @property actual The sequence number the peer actually sent.
 */
public class SequenceNumberMismatchException(
    public val expected: Int,
    public val actual: Int,
) : RuntimeException("SecureChannel sequence number mismatch: expected $expected, got $actual")
