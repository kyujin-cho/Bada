/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import io.github.kyujincho.wvmg.protocol.payload.PayloadProtocolException
import io.github.kyujincho.wvmg.protocol.transport.EndOfFrameStream
import io.github.kyujincho.wvmg.protocol.ukey2.Ukey2HandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * The sender-side glue tying together TCP, framing, UKEY2,
 * SecureMessage, the negotiation state machine, and outbound payload
 * streaming into a single in-flight transfer attempt.
 *
 * Owns exactly one TCP [Socket]. One [OutboundConnection] handles one
 * connection to one peer; opening more (Quick Share permits parallel
 * sends to multiple peers) is the caller's responsibility.
 *
 * ### Lifecycle
 *
 * The complete sequence the orchestrator drives, mirroring NearDrop's
 * `OutboundNearbyConnection.swift`:
 *
 *  1. Open a TCP socket to `(targetAddress, port)`.
 *  2. Wrap the socket in a
 *     [io.github.kyujincho.wvmg.protocol.transport.FramedConnection].
 *  3. Send the unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionRequest, endpoint_id, endpoint_info}`.
 *  4. Run [io.github.kyujincho.wvmg.protocol.ukey2.Ukey2Client.performHandshake]
 *     to produce a [io.github.kyujincho.wvmg.protocol.ukey2.Ukey2HandshakeResult].
 *  5. Read the receiver's unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionResponse}`.
 *  6. Send our unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionResponse, ACCEPT}`.
 *  7. Derive
 *     [io.github.kyujincho.wvmg.protocol.crypto.D2DSessionKeys] with
 *     [io.github.kyujincho.wvmg.protocol.crypto.D2DRole.CLIENT]. **This
 *     role assignment is critical** — the sender drove UKEY2 (sent
 *     ClientInit + ClientFinished), so it is the CLIENT on the
 *     SecureMessage layer. CLIENT sends with `clientEnc/clientHmac`,
 *     receives with `serverEnc/serverHmac`. A swap here would silently
 *     break decryption.
 *  8. Compute the 4-digit confirmation PIN via
 *     [io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation.deriveFourDigitPin]
 *     and surface it via [state] (so the UI can show it for the user
 *     to read out).
 *  9. Construct a
 *     [io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureChannel]
 *     over the FramedConnection.
 * 10. Drive [io.github.kyujincho.wvmg.protocol.sharing.OutboundSharingFsm]
 *     through to its `SendingPayloads` state — the FSM emits
 *     `PairedKeyEncryption`, then `PairedKeyResult`, then the
 *     `IntroductionFrame` carrying our file metadata, and waits for
 *     the receiver's `ConnectionResponseFrame`.
 * 11. On peer ACCEPT: stream every announced file in 512 KiB chunks
 *     via [io.github.kyujincho.wvmg.protocol.payload.PayloadTransferEncoder.encodeFilePayload],
 *     then send `Disconnection` and close.
 * 12. On peer non-ACCEPT: surface the status via [state], send
 *     `Disconnection`, and close.
 *
 * The state machine in
 * [io.github.kyujincho.wvmg.protocol.sharing.OutboundSharingFsm] models
 * steps 10-12 as a pure FSM; this class is the surrounding I/O loop
 * that interprets its
 * [io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEffect] outputs.
 *
 * ### Public API
 *
 * The contract is intentionally small:
 *
 *  - [state] — a [StateFlow] the UI subscribes to for renders.
 *  - [run] — one-shot suspend entry point that drives the entire
 *    lifecycle and returns an [OutboundResult].
 *  - [cancel] — thread-safe local cancel; produces a `CancelFrame` on
 *    the wire (when possible) and tears the connection down.
 *
 * ### Cancellation
 *
 * Coroutine cancellation of [run] tears down the socket cooperatively.
 * The same path is taken when [cancel] is invoked from another
 * coroutine. In either case any in-flight chunk read is best-effort
 * closed.
 *
 * ### Threading model
 *
 * One coroutine owns the send/receive interleave. [cancel] is safe to
 * call from any other coroutine — it pushes onto a [Channel] that the
 * driver's dispatch loop drains. This avoids racing the FSM with the
 * wire.
 *
 * @param targetAddress IP address (or hostname) of the receiver. The
 *   discovery layer (`:discovery-android`) translates an mDNS service
 *   record into this concrete value.
 * @param port TCP port the receiver advertised in its endpoint record.
 * @param endpointId 4-char ASCII identifier the sender uses for itself
 *   in the opening `ConnectionRequest`. Default: `"NDLp"` (Quick
 *   Share's identifier shape — 4 ASCII characters).
 * @param endpointInfo Serialized
 *   [io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo] bytes
 *   describing the sender. Default: empty (sender does not advertise a
 *   name; the receiver shows a generic "Someone wants to share").
 * @param qrCodeHandshakeData Optional QR-handshake bytes
 *   ([io.github.kyujincho.wvmg.protocol.qr]) used when the user
 *   reached the sender via a scanned QR code. When non-null, attached
 *   to the outgoing `PairedKeyEncryptionFrame.qr_code_handshake_data`.
 * @param connectTimeoutMillis TCP connect timeout. Default 5 seconds —
 *   matches NearDrop's default and is generous enough for Wi-Fi LAN
 *   without making "host is down" cases linger.
 * @param secureRandom Source of randomness for the UKEY2 keypair, the
 *   PairedKeyEncryption fill bytes, the SecureChannel IVs, and the
 *   per-frame `payload_id` choices. Tests inject a deterministic
 *   stub; production uses a fresh [SecureRandom].
 */
@Suppress("LongParameterList") // The public constructor has many knobs but every one is needed by the spec.
public class OutboundConnection(
    private val targetAddress: InetAddress,
    private val port: Int,
    private val endpointId: String = DEFAULT_ENDPOINT_ID,
    private val endpointInfo: ByteArray = ByteArray(0),
    private val qrCodeHandshakeData: ByteArray? = null,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val mutableState: MutableStateFlow<OutboundConnectionState> =
        MutableStateFlow(OutboundConnectionState.Idle)

    /**
     * Observable lifecycle state.
     *
     * Emits exactly once per state transition. Terminal states
     * ([OutboundConnectionState.Completed], [OutboundConnectionState.Rejected],
     * [OutboundConnectionState.Cancelled], [OutboundConnectionState.Failed])
     * are the last value the flow ever publishes.
     */
    public val state: StateFlow<OutboundConnectionState> = mutableState.asStateFlow()

    /**
     * External-event channel. The dispatch loop drains this between
     * inbound frames so [cancel] is funnelled into the FSM in the same
     * way the wire is.
     *
     * Capacity = `Channel.UNLIMITED` so callers never block; in
     * practice only one event ever flows through it (cancel) but
     * bounding to UNLIMITED avoids subtle deadlocks if a user
     * double-taps.
     */
    private val externalEvents: Channel<OutboundExternalEvent> = Channel(Channel.UNLIMITED)

    /**
     * Whether [run] has been invoked. Guards against accidental
     * double-execution — the lifecycle owns a single-use socket and a
     * single-use [externalEvents] channel.
     */
    @Volatile
    private var started: Boolean = false

    /**
     * Drive the entire sender-side lifecycle to completion.
     *
     * MUST be called exactly once per [OutboundConnection]. Subsequent
     * invocations throw `IllegalStateException`.
     *
     * The function suspends until the connection terminates — either
     * via successful completion, peer reject, cancellation (from any
     * source), or failure. The returned [OutboundResult] mirrors the
     * terminal [state] value so callers can ignore the flow if they
     * only need the outcome.
     *
     * @param files Files to ship, in announcement order. Each entry's
     *   [FileSource.payloadId] MUST be unique and positive. The
     *   orchestrator opens each [FileSource]'s channel exactly once
     *   (after peer ACCEPT) and closes it after the last chunk is
     *   written.
     * @return [OutboundResult.Completed] / [OutboundResult.Rejected] /
     *   [OutboundResult.Cancelled] / [OutboundResult.Failed]. Never
     *   throws — I/O failures are caught and surfaced as
     *   [OutboundResult.Failed]. Coroutine cancellation, however, IS
     *   propagated: the caller's `withTimeout`/`launch` dictates the
     *   shutdown semantics.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun run(files: List<FileSource>): OutboundResult {
        check(!started) { "OutboundConnection.run() may only be invoked once" }
        started = true

        validateFiles(files)

        mutableState.value = OutboundConnectionState.Connecting
        val socket: Socket =
            try {
                openSocket()
            } catch (e: java.io.IOException) {
                val reason = "TCP connect failed: ${e.message ?: e::class.simpleName}"
                mutableState.value = OutboundConnectionState.Failed(reason)
                return OutboundResult.Failed(reason)
            }

        val driver =
            OutboundConnectionDriver(
                socket = socket,
                secureRandom = secureRandom,
                externalEvents = externalEvents,
                mutableState = mutableState,
                endpointId = endpointId,
                endpointInfo = endpointInfo,
                qrCodeHandshakeData = qrCodeHandshakeData,
                files = files,
            )

        return try {
            driver.runLifecycle()
        } catch (cancel: CancellationException) {
            // Coroutine cancellation: tear down cooperatively. Do NOT
            // map this onto OutboundResult.Cancelled — structured
            // concurrency demands we re-throw so the parent scope sees
            // it. We still close the socket and notify the StateFlow.
            handleLocalCancel(driver)
            throw cancel
        } catch (e: Throwable) {
            handleFailure(driver, e)
        } finally {
            externalEvents.close()
            runCatching { socket.close() }
        }
    }

    /**
     * Request local cancellation. Thread-safe.
     *
     * If the FSM is in a non-terminal state, a `CancelFrame` is sent
     * to the peer before the connection closes. If the FSM has
     * already terminated, the request is a no-op.
     */
    public fun cancel() {
        externalEvents.trySend(OutboundExternalEvent.UserCancel)
    }

    /**
     * Open the TCP socket. Hoisted so the failure path is a clean
     * try/catch in [run]. Dispatched onto [Dispatchers.IO] because
     * `Socket.connect` is blocking and must not pin the calling
     * coroutine's dispatcher (which on the JVM-test side is the
     * `runTest` scheduler — pinning it would deadlock the test).
     */
    private suspend fun openSocket(): Socket =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(targetAddress, port), connectTimeoutMillis)
            socket
        }

    /**
     * Sanity-check the [files] list before starting any I/O.
     *
     * Two invariants:
     *  - Every payload id is unique. Duplicates would cause the
     *    receiver's reassembler to merge byte streams.
     *  - Every payload id is positive (the proto is `int64` but Quick
     *    Share semantics require positive values).
     */
    private fun validateFiles(files: List<FileSource>) {
        val seen = HashSet<Long>(files.size)
        for (f in files) {
            require(f.payloadId > 0) {
                "FileSource.payloadId must be positive, got ${f.payloadId} for '${f.name}'"
            }
            require(seen.add(f.payloadId)) {
                "Duplicate FileSource.payloadId ${f.payloadId} ('${f.name}')"
            }
        }
    }

    /**
     * Coroutine-cancellation cleanup path. Called from [run]'s catch
     * block. Closes the driver's resources and pushes a Cancelled
     * state if the flow has not already terminated.
     */
    private fun handleLocalCancel(driver: OutboundConnectionDriver) {
        runCatching { driver.tearDown() }
        val current = mutableState.value
        if (!current.isTerminal()) {
            mutableState.value = OutboundConnectionState.Cancelled(CancelCause.LOCAL)
        }
    }

    /**
     * General failure path. Maps the exception to a short, loggable
     * reason and publishes [OutboundConnectionState.Failed].
     */
    private fun handleFailure(
        driver: OutboundConnectionDriver,
        e: Throwable,
    ): OutboundResult {
        runCatching { driver.tearDown() }
        val reason =
            when (e) {
                is Ukey2HandshakeException ->
                    "UKEY2 ${e.alert ?: "error"}: ${e.message ?: "handshake failed"}"
                is PayloadProtocolException ->
                    "Payload protocol error: ${e.message ?: "malformed frame"}"
                is EndOfFrameStream ->
                    "Peer closed connection unexpectedly"
                is java.io.IOException ->
                    "I/O error: ${e.message ?: e::class.simpleName}"
                else ->
                    "${e::class.simpleName}: ${e.message ?: "unknown failure"}"
            }
        mutableState.value = OutboundConnectionState.Failed(reason)
        return OutboundResult.Failed(reason)
    }

    public companion object {
        /**
         * Default sender endpoint id ("NDLp" — Nearby Drop Lablup-port,
         * but really just any 4-character ASCII string Quick Share
         * peers tolerate). NearDrop uses "NCLp" / "FW**" with a similar
         * intent.
         */
        public const val DEFAULT_ENDPOINT_ID: String = "NDLp"

        /**
         * Default TCP connect timeout, in milliseconds. 5 seconds is
         * generous enough for Wi-Fi LAN while still surfacing "host is
         * unreachable" failures within human attention span.
         */
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 5000
    }
}

/**
 * Internal events the [OutboundConnection] dispatch loop drains
 * alongside inbound wire frames.
 */
internal sealed interface OutboundExternalEvent {
    /** User pressed cancel. */
    object UserCancel : OutboundExternalEvent
}
