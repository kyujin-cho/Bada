/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.payload.FileDestinationFactory
import dev.bluehouse.bada.protocol.payload.PayloadProtocolException
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.EndOfFrameStream
import dev.bluehouse.bada.protocol.transport.asConnectedTransport
import dev.bluehouse.bada.protocol.ukey2.Ukey2HandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket
import java.security.SecureRandom

/**
 * The receiver-side glue tying together framing, UKEY2, SecureMessage,
 * and the negotiation state machine into a single in-flight transfer
 * attempt.
 *
 * Wraps exactly one TCP [Socket]. One [InboundConnection] handles one
 * connection from one peer; spawning more connections (Quick Share
 * permits parallel file transfers from different peers) is the caller's
 * responsibility.
 *
 * ### Lifecycle
 *
 * The complete sequence the orchestrator drives, mirroring NearDrop's
 * `InboundNearbyConnection.swift`:
 *
 *  1. Wrap the [Socket] in a [FramedConnection].
 *  2. Read the peer's unencrypted `OfflineFrame{ConnectionRequest}`.
 *     We do not validate `endpoint_info` here -- the discovery layer
 *     handles that -- but its arrival is mandatory.
 *  3. Run [Ukey2Server.performHandshake] to produce a
 *     [Ukey2HandshakeResult].
 *  4. Send unencrypted `OfflineFrame{ConnectionResponse{ACCEPT}}`.
 *  5. Read the peer's unencrypted `OfflineFrame{ConnectionResponse}`.
 *  6. Derive [dev.bluehouse.bada.protocol.crypto.D2DSessionKeys]
 *     with [D2DRole.SERVER]. **This role assignment is critical** --
 *     the receiver responded to UKEY2 (sent ServerInit), so it is the
 *     SERVER on the SecureMessage layer. SERVER sends with
 *     `serverEnc/serverHmac`, receives with `clientEnc/clientHmac`.
 *     A swap here would silently break decryption.
 *  7. Construct a [SecureChannel] over the FramedConnection.
 *  8. Drive [dev.bluehouse.bada.protocol.sharing.InboundSharingFsm]
 *     through to `WaitingForUserConsent` -- pumping inbound BYTES
 *     payloads (assembled by [PayloadAssembler]), parsing them as
 *     `Sharing.Nearby.Frame`s, and feeding the FSM's effect list into
 *     the SecureChannel send path.
 *  9. Surface [TransferMetadata] (filenames, sizes, MIME types, the
 *     UKEY2-derived 4-digit PIN) via [state] and block on
 *     [submitUserConsent].
 * 10. On accept: send `ConnectionResponseFrame{ACCEPT}` (this is the
 *     Sharing-frame variety, NOT the OfflineFrame from step 4 --
 *     same name, different proto), drive the assembler through every
 *     announced FILE / BYTES payload, then send `Disconnection` and
 *     close.
 * 11. On reject: send `ConnectionResponseFrame{REJECT}`, send
 *     `Disconnection`, close.
 *
 * The state machine in
 * [dev.bluehouse.bada.protocol.sharing.InboundSharingFsm] models
 * steps 8-10 as a pure FSM; this class is the surrounding I/O loop
 * that interprets its [dev.bluehouse.bada.protocol.sharing.SharingFsmEffect]
 * outputs.
 *
 * ### Public API
 *
 * The contract is intentionally small:
 *
 *  - [state] -- a [StateFlow] the UI subscribes to for renders.
 *  - [run] -- one-shot suspend entry point that drives the entire
 *    lifecycle and returns an [InboundResult].
 *  - [submitUserConsent] -- thread-safe accept/reject handoff from the
 *    UI; ignored once the FSM has moved past `WaitingForUserConsent`.
 *  - [cancel] -- thread-safe local cancel; produces a `CancelFrame` on
 *    the wire (when possible) and tears the connection down.
 *
 * ### Cancellation
 *
 * Coroutine cancellation of [run] tears down the socket and the
 * assembler's in-flight state cooperatively. The same path is taken
 * when [cancel] is invoked from another coroutine. In either case
 * partial FILE writes are best-effort closed via [PayloadAssembler.reset]
 * so the destination factory can clean up if it wants to (the factory's
 * channel is the only thing that knows the on-disk path).
 *
 * ### Threading model
 *
 * One coroutine owns the receive loop. [submitUserConsent] and
 * [cancel] are safe to call from any other coroutine -- they push
 * onto a [Channel] that the receive loop drains. This avoids racing
 * the FSM with the wire.
 *
 * @param socket A connected TCP socket. Owned by this object: closed
 *   on completion / cancellation / failure regardless of outcome.
 * @param secureRandom Source of randomness for the UKEY2 keypair, the
 *   PairedKeyEncryption fill bytes, and the SecureChannel IVs. Tests
 *   inject a deterministic stub; production uses a fresh
 *   [SecureRandom].
 */
public class InboundConnection(
    private val transport: ConnectedTransport,
    private val secureRandom: SecureRandom = SecureRandom(),
    /**
     * Registry of [dev.bluehouse.bada.protocol.medium.MediumProvider]s
     * used to intersect against the peer's advertised
     * `ConnectionRequestFrame.mediums` and pick a target for
     * `BANDWIDTH_UPGRADE_NEGOTIATION`. Defaults to
     * [MediumRegistry.DefaultWifiLan] (Wi-Fi LAN only) which preserves
     * the project's pre-Phase-4 behaviour exactly. Phase 4 sub-issues
     * #49–#53 register per-medium providers; #54 wires the actual
     * transport swap into the dispatch loop.
     */
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    private val logger: (String) -> Unit = {},
) {
    public constructor(
        socket: Socket,
        secureRandom: SecureRandom = SecureRandom(),
        mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
        logger: (String) -> Unit = {},
    ) : this(
        transport = socket.asConnectedTransport(),
        secureRandom = secureRandom,
        mediumRegistry = mediumRegistry,
        logger = logger,
    )

    private val mutableState: MutableStateFlow<InboundConnectionState> =
        MutableStateFlow(InboundConnectionState.Idle)

    private val mutableActiveMedium: MutableStateFlow<Medium> =
        MutableStateFlow(transport.medium)

    private val mutableActiveWifiFrequencyMhz: MutableStateFlow<Int?> =
        MutableStateFlow(null)

    /**
     * Observable lifecycle state.
     *
     * Emits exactly once per state transition. Terminal states
     * ([InboundConnectionState.Completed], [InboundConnectionState.Rejected],
     * [InboundConnectionState.Cancelled], [InboundConnectionState.Failed])
     * are the last value the flow ever publishes.
     */
    public val state: StateFlow<InboundConnectionState> = mutableState.asStateFlow()

    /**
     * Medium the live control channel currently runs over.
     *
     * Starts at the initial accept transport and flips if a successful
     * `BANDWIDTH_UPGRADE_NEGOTIATION` swaps the SecureChannel onto a
     * different medium.
     */
    public val activeMedium: StateFlow<Medium> = mutableActiveMedium.asStateFlow()

    /**
     * Active Wi-Fi channel frequency in MHz when the current medium can
     * report one. Today this is populated for Wi-Fi Direct upgrades and
     * remains `null` for Wi-Fi LAN, Bluetooth, BLE, and unknown channels.
     */
    public val activeWifiFrequencyMhz: StateFlow<Int?> = mutableActiveWifiFrequencyMhz.asStateFlow()

    /**
     * External-event channel. The receive loop drains this between
     * inbound frames (and explicitly while parked in
     * [InboundConnectionState.WaitingForUserConsent]) so [submitUserConsent]
     * and [cancel] are funnelled into the FSM in the same way the
     * wire is.
     *
     * Capacity = `Channel.UNLIMITED` so callers never block; in
     * practice only one event ever flows through it (consent, then
     * possibly cancel) but bounding to UNLIMITED avoids subtle deadlocks
     * if a user double-taps.
     */
    private val externalEvents: Channel<ExternalEvent> = Channel(Channel.UNLIMITED)

    /**
     * Whether [run] has been invoked. Guards against accidental
     * double-execution -- the lifecycle owns a single-use socket and
     * a single-use [externalEvents] channel.
     */
    @Volatile
    private var started: Boolean = false

    /**
     * Whether [cancel] has been invoked. Latched true on the first
     * call so a racing duplicate cancel() does not produce a second
     * external event (the FSM is idempotent on duplicate `UserCancel`,
     * but skipping the second send keeps the channel buffer clean).
     */
    @Volatile
    private var cancelled: Boolean = false

    /**
     * Whether the lifecycle has progressed past the unencrypted
     * handshake into the dispatch loop. Latched true by
     * [markHandshakeComplete] once the dispatch loop is ready to drain
     * [externalEvents]; consulted by [cancel] to decide whether to
     * fast-path-close the socket (pre-handshake, blocking socket reads
     * cannot otherwise be interrupted).
     */
    @Volatile
    private var handshakeComplete: Boolean = false

    /**
     * Called by [InboundConnectionDriver] once the dispatch loop is
     * running and able to drain [externalEvents]. From that moment on
     * a [cancel] should cooperate with the FSM (so `CANCEL` +
     * `DISCONNECTION` make it onto the wire) rather than yank the
     * socket out from under it.
     */
    internal fun markHandshakeComplete() {
        handshakeComplete = true
    }

    /**
     * Drive the entire receiver-side lifecycle to completion.
     *
     * MUST be called exactly once per [InboundConnection]. Subsequent
     * invocations throw `IllegalStateException`. The [factory] is used
     * to open per-payload [java.nio.channels.WritableByteChannel]s for
     * FILE arrivals (production wires `MediaStore`; tests typically
     * pass an in-memory factory).
     *
     * The function suspends until the connection terminates -- either
     * via successful completion, user reject, cancellation (from any
     * source), or failure. The returned [InboundResult] mirrors the
     * terminal [state] value so callers can ignore the flow if they
     * only need the outcome.
     *
     * @return [InboundResult.Completed] / [InboundResult.Rejected] /
     *   [InboundResult.Cancelled] / [InboundResult.Failed]. Never
     *   throws -- I/O failures are caught and surfaced as
     *   [InboundResult.Failed]. Coroutine cancellation, however, IS
     *   propagated: the caller's `withTimeout`/`launch` dictates the
     *   shutdown semantics.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun run(factory: FileDestinationFactory): InboundResult {
        check(!started) { "InboundConnection.run() may only be invoked once" }
        started = true

        val driver =
            InboundConnectionDriver(
                transport = transport,
                secureRandom = secureRandom,
                externalEvents = externalEvents,
                mutableState = mutableState,
                mutableActiveMedium = mutableActiveMedium,
                mutableActiveWifiFrequencyMhz = mutableActiveWifiFrequencyMhz,
                factory = factory,
                mediumRegistry = mediumRegistry,
                onHandshakeComplete = ::markHandshakeComplete,
                logger = logger,
            )

        return try {
            driver.runLifecycle()
        } catch (cancel: CancellationException) {
            // Coroutine cancellation: tear down cooperatively. Do NOT
            // map this onto InboundResult.Cancelled -- structured
            // concurrency demands we re-throw so the parent scope sees
            // it. We still close the socket and notify the StateFlow.
            handleLocalCancel(driver, dueToCancellation = true)
            throw cancel
        } catch (e: Throwable) {
            handleFailure(driver, e)
        } finally {
            // Always tear down — including on the happy path. The
            // driver's tearDown is idempotent and includes
            // factory.abortAll() so any FILE payload that was opened
            // but did not get a clean FileComplete (and therefore was
            // not commit()ed) is dropped. Without this, a peer that
            // hangs up mid-file after the assembler observed the first
            // chunk would leave a partial download visible in the
            // user's Downloads UI.
            runCatching { driver.tearDown() }
            externalEvents.close()
            runCatching { transport.close() }
        }
    }

    /**
     * Deliver the user's accept/reject decision to the negotiation
     * FSM. Thread-safe -- routed through [externalEvents] so the
     * receive loop processes it in-order.
     *
     * Calling this when the FSM is not in
     * `WaitingForUserConsent` is harmless: the FSM treats out-of-
     * state UserConsent as a protocol error (which we surface as
     * [InboundConnectionState.Failed]). The intended UI usage is to
     * subscribe to [state], gate the accept/reject buttons on
     * [InboundConnectionState.WaitingForUserConsent], and call this
     * exactly once.
     *
     * @param accepted `true` to accept; `false` to reject.
     */
    public fun submitUserConsent(accepted: Boolean) {
        // trySend never blocks; the channel is UNLIMITED so it never
        // fails for capacity reasons. If the channel is closed (run()
        // already returned), the result is silently dropped, which
        // is the right behavior -- the consent arrived too late.
        externalEvents.trySend(ExternalEvent.UserConsent(accepted))
    }

    /**
     * Request local cancellation. Thread-safe.
     *
     * Behaviour depends on the lifecycle phase:
     *
     *  - **Pre-handshake / handshake**: the socket is closed
     *    immediately. No encrypted frames are possible yet so a wire-
     *    level `CancelFrame` is meaningless; the peer observes a TCP
     *    close. The receive loop, which is parked in a blocking read,
     *    surfaces the close as `EndOfFrameStream` and runs through the
     *    failure path.
     *  - **Post-handshake (negotiation, awaiting consent, receiving
     *    payloads)**: a `Sharing.Nearby.Frame{type=CANCEL}` is sent to
     *    the peer, followed by `OfflineFrame{type=DISCONNECTION}`,
     *    before the connection closes. The FSM's `UserCancel` handler
     *    drives this via [InboundConnectionDriver.applyEffects].
     *  - **Terminal**: no-op. Late cancel calls (e.g. user taps Cancel
     *    just as the last byte lands) are silently dropped, not
     *    surfaced as failure.
     *
     * Distinct from coroutine cancellation: [cancel] cooperates with
     * the FSM (the wire-level CancelFrame goes out before teardown);
     * coroutine cancellation closes the socket immediately and the
     * peer infers the disconnect.
     */
    public fun cancel() {
        if (cancelled) return
        cancelled = true
        // Always enqueue the FSM-side event. If the dispatch loop is
        // running (post-handshake) it drains the event and emits the
        // wire-level CANCEL + DISCONNECTION sequence. If it is not
        // running yet, the trySend either lands in the buffer (and is
        // silently dropped when the channel closes during teardown)
        // or it fails for capacity reasons -- both are harmless.
        externalEvents.trySend(ExternalEvent.UserCancel)
        if (!handshakeComplete) {
            // Pre-handshake fast-path: the dispatch loop has not
            // started yet (the lifecycle is parked in a blocking
            // socket read for the unencrypted ConnectionRequest, or
            // inside Ukey2Server.performHandshake's similar blocking
            // reads). Closing the socket here unblocks that read with
            // an IOException, which the lifecycle catches and turns
            // into a Failed/Cancelled terminal. Without this fast-path
            // a pre-handshake cancel would sit in the channel forever
            // and the connection would hang until the peer hung up.
            //
            // No CANCEL frame is sent in this path because no
            // SecureChannel exists yet -- the protocol does not permit
            // a CANCEL on the unencrypted leg. The peer observes a
            // TCP close, which Quick Share treats as a connection
            // abort.
            runCatching { transport.close() }
        }
    }

    /**
     * Coroutine-cancellation cleanup path. Called from [run]'s catch
     * block. Closes the assembler (so partial files release their
     * channels) and pushes a Cancelled state.
     */
    private fun handleLocalCancel(
        driver: InboundConnectionDriver,
        @Suppress("UNUSED_PARAMETER") dueToCancellation: Boolean,
    ) {
        runCatching { driver.tearDown() }
        // Only publish if not already terminal. The driver may have
        // already published a Failed/Completed state, in which case
        // we leave the flow alone.
        val current = mutableState.value
        if (!current.isTerminal()) {
            mutableState.value = InboundConnectionState.Cancelled(CancelCause.LOCAL)
        }
    }

    /**
     * General failure path. Maps the exception to a short,
     * loggable reason and publishes [InboundConnectionState.Failed].
     */
    private fun handleFailure(
        driver: InboundConnectionDriver,
        e: Throwable,
    ): InboundResult {
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
        mutableState.value = InboundConnectionState.Failed(reason)
        return InboundResult.Failed(reason)
    }
}

/**
 * Internal events the [InboundConnection] receive loop drains
 * alongside inbound wire frames.
 */
internal sealed interface ExternalEvent {
    /** User pressed accept or reject in the consent sheet. */
    data class UserConsent(
        val accepted: Boolean,
    ) : ExternalEvent

    /** User pressed cancel. */
    object UserCancel : ExternalEvent
}

/**
 * Helper -- check whether an [OfflineFrame] is the unencrypted
 * `ConnectionRequest` we expect at the very start of the lifecycle.
 *
 * Hoisted to a top-level function (rather than living inside
 * [InboundConnectionDriver]) so the integration tests that build a
 * synthetic peer can use the same predicate without poking at private
 * implementation.
 */
internal fun OfflineFrame.isConnectionRequest(): Boolean =
    hasV1() && v1.type == V1Frame.FrameType.CONNECTION_REQUEST && v1.hasConnectionRequest()

/**
 * Helper -- check whether an [OfflineFrame] is the unencrypted
 * `ConnectionResponse` the peer sends after we send ours.
 */
internal fun OfflineFrame.isConnectionResponse(): Boolean =
    hasV1() && v1.type == V1Frame.FrameType.CONNECTION_RESPONSE && v1.hasConnectionResponse()

/**
 * Helper -- check whether an [OfflineFrame] is a `Disconnection`
 * frame.
 */
internal fun OfflineFrame.isDisconnection(): Boolean = hasV1() && v1.type == V1Frame.FrameType.DISCONNECTION
