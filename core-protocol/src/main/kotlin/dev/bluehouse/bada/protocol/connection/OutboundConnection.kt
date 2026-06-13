/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumRegistry
import dev.bluehouse.bada.protocol.medium.NearbyMultiplexClientTransport
import dev.bluehouse.bada.protocol.payload.PayloadProtocolException
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.EndOfFrameStream
import dev.bluehouse.bada.protocol.transport.asConnectedTransport
import dev.bluehouse.bada.protocol.ukey2.Ukey2HandshakeException
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
import java.security.PrivateKey
import java.security.SecureRandom

/**
 * The sender-side glue tying together an initial connected transport,
 * framing, UKEY2,
 * SecureMessage, the negotiation state machine, and outbound payload
 * streaming into a single in-flight transfer attempt.
 *
 * Owns exactly one initial [ConnectedTransport]. One [OutboundConnection]
 * handles one connection to one peer; opening more (Quick Share permits
 * parallel sends to multiple peers) is the caller's responsibility.
 *
 * ### Lifecycle
 *
 * The complete sequence the orchestrator drives, mirroring NearDrop's
 * `OutboundNearbyConnection.swift`:
 *
 *  1. Obtain the initial connected transport (either by opening the
 *     legacy LAN TCP socket or by consuming a caller-supplied
 *     preconnected transport).
 *  2. Wrap the transport in a
 *     [dev.bluehouse.bada.protocol.transport.FramedConnection].
 *  3. Send the unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionRequest, endpoint_id, endpoint_info}`.
 *  4. Run [dev.bluehouse.bada.protocol.ukey2.Ukey2Client.performHandshake]
 *     to produce a [dev.bluehouse.bada.protocol.ukey2.Ukey2HandshakeResult].
 *  5. Read the receiver's unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionResponse}`.
 *  6. Send our unencrypted
 *     [com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame]
 *     `{ConnectionResponse, ACCEPT}`.
 *  7. Derive
 *     [dev.bluehouse.bada.protocol.crypto.D2DSessionKeys] with
 *     [dev.bluehouse.bada.protocol.crypto.D2DRole.CLIENT]. **This
 *     role assignment is critical** — the sender drove UKEY2 (sent
 *     ClientInit + ClientFinished), so it is the CLIENT on the
 *     SecureMessage layer. CLIENT sends with `clientEnc/clientHmac`,
 *     receives with `serverEnc/serverHmac`. A swap here would silently
 *     break decryption.
 *  8. Compute the 4-digit confirmation PIN via
 *     [dev.bluehouse.bada.protocol.crypto.pin.PinDerivation.deriveFourDigitPin]
 *     and surface it via [state] (so the UI can show it for the user
 *     to read out).
 *  9. Construct a
 *     [dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel]
 *     over the FramedConnection.
 * 10. Drive [dev.bluehouse.bada.protocol.sharing.OutboundSharingFsm]
 *     through to its `SendingPayloads` state — the FSM emits
 *     `PairedKeyEncryption`, then `PairedKeyResult`, then the
 *     `IntroductionFrame` carrying our file metadata, and waits for
 *     the receiver's `ConnectionResponseFrame`.
 * 11. On peer ACCEPT: stream every announced file in 512 KiB chunks
 *     via [dev.bluehouse.bada.protocol.payload.PayloadTransferEncoder.encodeFilePayload],
 *     then send `Disconnection` and close.
 * 12. On peer non-ACCEPT: surface the status via [state], send
 *     `Disconnection`, and close.
 *
 * The state machine in
 * [dev.bluehouse.bada.protocol.sharing.OutboundSharingFsm] models
 * steps 10-12 as a pure FSM; this class is the surrounding I/O loop
 * that interprets its
 * [dev.bluehouse.bada.protocol.sharing.SharingFsmEffect] outputs.
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
 * @param targetAddress IP address (or hostname) of the receiver for the
 *   legacy LAN TCP path.
 * @param port TCP port the receiver advertised in its endpoint record
 *   for the legacy LAN TCP path.
 * @param transport Optional already-connected initial-control transport.
 *   Used by sender-side non-LAN bootstrap paths such as Bluetooth
 *   Classic RFCOMM.
 * @param endpointId 4-char ASCII identifier the sender uses for itself
 *   in the opening `ConnectionRequest`. Default: a freshly generated
 *   alphanumeric id via [generateEndpointId]. Quick Share peers (Samsung
 *   One UI's GMS Nearby in particular) keep per-endpoint-id state for a
 *   short window after a session tears down, and a fresh id per
 *   connection avoids racing that teardown.
 * @param endpointInfo Serialized
 *   [dev.bluehouse.bada.protocol.endpoint.EndpointInfo] bytes
 *   describing the sender. Default: empty (sender does not advertise a
 *   name; the receiver shows a generic "Someone wants to share").
 * @param qrSigningKey Optional EC P-256 private key matching the public
 *   key published in a QR code/link this sender showed
 *   ([dev.bluehouse.bada.protocol.qr]). When non-null, the driver signs
 *   the UKEY2 `authString` with it (see [QrHandshakeSigner]) and attaches
 *   the IEEE-P1363 signature to the outgoing
 *   `PairedKeyEncryptionFrame.qr_code_handshake_data`, proving to the
 *   QR-bonded receiver that this sender owns the QR keypair.
 * @param connectTimeoutMillis TCP connect timeout. Default 5 seconds —
 *   matches NearDrop's default and is generous enough for Wi-Fi LAN
 *   without making "host is down" cases linger.
 * @param initialHandshakeTimeoutMillis Maximum time to wait for the
 *   unencrypted ConnectionRequest / UKEY2 / ConnectionResponse leg
 *   after the initial transport is open. Closing the transport on this
 *   timeout prevents non-LAN virtual streams from leaving the sender UI
 *   stuck in the pairing state.
 * @param useNearbyMultiplexInitialTransport When true, the legacy LAN
 *   socket is first opened as a Nearby multiplex physical pipe and the
 *   normal OfflineFrame protocol is sent over the accepted virtual
 *   socket. Production sender paths should leave this disabled unless
 *   discovery has positively identified a peer that expects multiplexed
 *   TCP; stock mDNS receivers use the raw LAN shape.
 * @param remoteAcceptanceTimeoutMillis Maximum time to wait after the
 *   IntroductionFrame is sent for the receiver's encrypted
 *   ConnectionResponseFrame. Stock receivers normally answer only after
 *   the user accepts/rejects the consent UI; this bounds peers that keep
 *   the secure channel alive but never surface that UI.
 * @param secureRandom Source of randomness for the UKEY2 keypair, the
 *   PairedKeyEncryption fill bytes, the SecureChannel IVs, and the
 *   per-frame `payload_id` choices. Tests inject a deterministic
 *   stub; production uses a fresh [SecureRandom].
 * @param mediumRegistry Registry of [dev.bluehouse.bada.protocol.medium.MediumProvider]s
 *   used to populate `ConnectionRequestFrame.mediums` (the set of
 *   transports we are willing to upgrade to via
 *   `BANDWIDTH_UPGRADE_NEGOTIATION`). Defaults to
 *   [MediumRegistry.DefaultWifiLan] which advertises Wi-Fi LAN only —
 *   functionally identical to NearDrop's fixed shape and the value the
 *   project shipped before Phase 4. Phase 4 sub-issues #49–#53 each
 *   register a per-medium provider; #54 wires the registry into the
 *   actual upgrade swap.
 */
@Suppress("LongParameterList") // The public constructor has many knobs but every one is needed by the spec.
public class OutboundConnection private constructor(
    private val targetAddress: InetAddress?,
    private val port: Int?,
    private val transport: ConnectedTransport?,
    private val endpointId: String = generateEndpointId(),
    private val endpointInfo: ByteArray = ByteArray(0),
    private val qrSigningKey: PrivateKey? = null,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val initialHandshakeTimeoutMillis: Long = DEFAULT_INITIAL_HANDSHAKE_TIMEOUT_MILLIS,
    private val useNearbyMultiplexInitialTransport: Boolean = false,
    private val remoteAcceptanceTimeoutMillis: Long = DEFAULT_REMOTE_ACCEPTANCE_TIMEOUT_MILLIS,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
    /**
     * Optional structured-log sink. The driver invokes it at every
     * lifecycle phase boundary (TCP connect, UKEY2 client/server init,
     * D2D key derivation, FSM transitions, terminal results) so the
     * Android layer can route messages to logcat for on-device
     * diagnosis. Default is silent — `:core-protocol` itself never
     * touches `android.util.Log`.
     */
    private val logger: (String) -> Unit = {},
) {
    public constructor(
        targetAddress: InetAddress,
        port: Int,
        endpointId: String = generateEndpointId(),
        endpointInfo: ByteArray = ByteArray(0),
        qrSigningKey: PrivateKey? = null,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        initialHandshakeTimeoutMillis: Long = DEFAULT_INITIAL_HANDSHAKE_TIMEOUT_MILLIS,
        useNearbyMultiplexInitialTransport: Boolean = false,
        remoteAcceptanceTimeoutMillis: Long = DEFAULT_REMOTE_ACCEPTANCE_TIMEOUT_MILLIS,
        secureRandom: SecureRandom = SecureRandom(),
        mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
        logger: (String) -> Unit = {},
    ) : this(
        targetAddress = targetAddress,
        port = port,
        transport = null,
        endpointId = endpointId,
        endpointInfo = endpointInfo,
        qrSigningKey = qrSigningKey,
        connectTimeoutMillis = connectTimeoutMillis,
        initialHandshakeTimeoutMillis = initialHandshakeTimeoutMillis,
        useNearbyMultiplexInitialTransport = useNearbyMultiplexInitialTransport,
        remoteAcceptanceTimeoutMillis = remoteAcceptanceTimeoutMillis,
        secureRandom = secureRandom,
        mediumRegistry = mediumRegistry,
        logger = logger,
    )

    public constructor(
        transport: ConnectedTransport,
        endpointId: String = generateEndpointId(),
        endpointInfo: ByteArray = ByteArray(0),
        qrSigningKey: PrivateKey? = null,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        initialHandshakeTimeoutMillis: Long = DEFAULT_INITIAL_HANDSHAKE_TIMEOUT_MILLIS,
        remoteAcceptanceTimeoutMillis: Long = DEFAULT_REMOTE_ACCEPTANCE_TIMEOUT_MILLIS,
        secureRandom: SecureRandom = SecureRandom(),
        mediumRegistry: MediumRegistry = MediumRegistry.DefaultWifiLan,
        logger: (String) -> Unit = {},
    ) : this(
        targetAddress = null,
        port = null,
        transport = transport,
        endpointId = endpointId,
        endpointInfo = endpointInfo,
        qrSigningKey = qrSigningKey,
        connectTimeoutMillis = connectTimeoutMillis,
        initialHandshakeTimeoutMillis = initialHandshakeTimeoutMillis,
        useNearbyMultiplexInitialTransport = false,
        remoteAcceptanceTimeoutMillis = remoteAcceptanceTimeoutMillis,
        secureRandom = secureRandom,
        mediumRegistry = mediumRegistry,
        logger = logger,
    )

    private val mutableState: MutableStateFlow<OutboundConnectionState> =
        MutableStateFlow(OutboundConnectionState.Idle)

    private val mutableActiveMedium: MutableStateFlow<Medium> =
        MutableStateFlow(transport?.medium ?: Medium.WIFI_LAN)

    private val mutableActiveWifiFrequencyMhz: MutableStateFlow<Int?> =
        MutableStateFlow(null)

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
     * Medium the live control/payload channel currently runs over.
     *
     * Starts at the initial transport and flips if a successful
     * bandwidth upgrade swaps onto a different medium.
     */
    public val activeMedium: StateFlow<Medium> = mutableActiveMedium.asStateFlow()

    /**
     * Active Wi-Fi channel frequency in MHz when the current medium can
     * report one. Today this is populated for Wi-Fi Direct upgrades and
     * remains `null` for Wi-Fi LAN, Bluetooth, BLE, and unknown channels.
     */
    public val activeWifiFrequencyMhz: StateFlow<Int?> = mutableActiveWifiFrequencyMhz.asStateFlow()

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
     * Whether [cancel] has been invoked. Latched true so duplicate
     * calls do not produce duplicate FSM events.
     */
    @Volatile
    private var cancelled: Boolean = false

    /**
     * The socket the dispatch loop owns. Set in [run] right after
     * `openSocket()` returns; consulted by [cancel] for the pre-
     * handshake fast-path close. `null` before TCP connect succeeds
     * (in which case [cancelled] alone is enough — the connect path
     * polls the flag) and after the lifecycle has torn down the
     * socket itself.
     */
    @Volatile
    private var transportRef: ConnectedTransport? = null

    /**
     * Whether the lifecycle has progressed past the unencrypted
     * handshake into the dispatch loop. Latched true by
     * [markHandshakeComplete] once the dispatch loop is ready to drain
     * [externalEvents]; consulted by [cancel] to decide between the
     * cooperative FSM path and the raw-socket-close fast-path.
     */
    @Volatile
    private var handshakeComplete: Boolean = false

    internal fun markHandshakeComplete() {
        handshakeComplete = true
    }

    init {
        val usingLanSocket = targetAddress != null && port != null
        val usingPreconnectedTransport = transport != null
        require(usingLanSocket.xor(usingPreconnectedTransport)) {
            "OutboundConnection requires either targetAddress+port or transport"
        }
        require(initialHandshakeTimeoutMillis > 0L) {
            "initialHandshakeTimeoutMillis must be positive"
        }
        require(remoteAcceptanceTimeoutMillis > 0L) {
            "remoteAcceptanceTimeoutMillis must be positive"
        }
    }

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
        val initialTransport: ConnectedTransport =
            try {
                openTransport()
            } catch (e: java.io.IOException) {
                val reason = "Initial connect failed: ${e.message ?: e::class.simpleName}"
                mutableState.value = OutboundConnectionState.Failed(reason)
                return OutboundResult.Failed(reason)
            }
        transportRef = initialTransport
        // A cancel() that arrived while we were blocked in
        // openTransport() couldn't close the transport then (it didn't
        // exist yet), but the connect just succeeded. Honour the latched
        // cancellation by closing the new transport eagerly so the
        // dispatch loop's first read fails fast. Without this, a
        // pre-handshake cancel that lost the race against a fast initial
        // connect would have to wait for a peer-side timeout.
        if (cancelled) {
            runCatching { initialTransport.close() }
        }

        val driver =
            OutboundConnectionDriver(
                initialTransport = initialTransport,
                secureRandom = secureRandom,
                externalEvents = externalEvents,
                mutableState = mutableState,
                mutableActiveMedium = mutableActiveMedium,
                mutableActiveWifiFrequencyMhz = mutableActiveWifiFrequencyMhz,
                endpointId = endpointId,
                endpointInfo = endpointInfo,
                qrSigningKey = qrSigningKey,
                files = files,
                mediumRegistry = mediumRegistry,
                onHandshakeComplete = ::markHandshakeComplete,
                logger = logger,
                initialHandshakeTimeoutMillis = initialHandshakeTimeoutMillis,
                remoteAcceptanceTimeoutMillis = remoteAcceptanceTimeoutMillis,
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
            runCatching { initialTransport.close() }
        }
    }

    /**
     * Request local cancellation. Thread-safe.
     *
     * Behaviour depends on the lifecycle phase:
     *
     *  - **Pre-TCP-connect**: latches the cancel flag. The connect
     *    path observes it as soon as the connect call returns and
     *    closes the socket immediately, surfacing as Failed/Cancelled.
     *    No frames are sent because no socket existed yet.
     *  - **Pre-handshake (during the unencrypted ConnectionRequest /
     *    UKEY2 leg)**: closes the socket directly. No `CancelFrame`
     *    is sent because no `SecureChannel` exists yet to encrypt one.
     *    The peer observes a TCP close.
     *  - **Post-handshake**: enqueues a `UserCancel` event for the
     *    dispatch loop, which drives the FSM. The FSM emits
     *    `SendFrame{CANCEL}` followed by the `Disconnection`
     *    `OfflineFrame` and the connection closes cleanly.
     *  - **Terminal**: no-op.
     */
    public fun cancel() {
        if (cancelled) return
        cancelled = true
        externalEvents.trySend(OutboundExternalEvent.UserCancel)
        if (!handshakeComplete) {
            // Pre-handshake fast-path. Close the socket if it exists
            // (post-connect, pre-dispatch-loop). If the lifecycle is
            // still inside `openSocket()` we leave the connect to
            // fail / succeed naturally; the post-connect block in
            // [run] re-checks [cancelled] and closes the new socket
            // immediately.
            transportRef?.let { runCatching { it.close() } }
        }
    }

    /**
     * Open or adopt the initial transport. Hoisted so the failure path is a clean
     * try/catch in [run]. Dispatched onto [Dispatchers.IO] because
     * `Socket.connect` is blocking and must not pin the calling
     * coroutine's dispatcher (which on the JVM-test side is the
     * `runTest` scheduler — pinning it would deadlock the test).
     */
    private suspend fun openTransport(): ConnectedTransport {
        val preconnected = transport
        if (preconnected != null) return preconnected
        val address = requireNotNull(targetAddress)
        val targetPort = requireNotNull(port)
        return withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(address, targetPort), connectTimeoutMillis)
            // TCP_NODELAY mirrors the receiver-side accept loop
            // (`TcpReceiverServer` line ~414). Without it, Nagle batches
            // our small UKEY2 frames (~50 B) and the unencrypted
            // `ConnectionResponse{ACCEPT}` (~24 B) into a single segment,
            // which the peer's framing parser handles fine but adds
            // latency on the path that already needs to fit inside
            // Samsung's UKEY2 server alarm. Best-effort: a misbehaving
            // SocketImpl that rejects this option is non-fatal.
            runCatching { socket.tcpNoDelay = true }
            val rawTransport = socket.asConnectedTransport()
            if (!useNearbyMultiplexInitialTransport) {
                return@withContext rawTransport
            }
            val multiplexTransport =
                NearbyMultiplexClientTransport(
                    physicalTransport = rawTransport,
                    requireConnectionResponse = false,
                    optimisticReadyDelayMillis = NEARBY_MULTIPLEX_OPTIMISTIC_READY_DELAY_MILLIS,
                    logger = logger,
                )
            multiplexTransport.start()
            val accepted = multiplexTransport.awaitReady(NEARBY_MULTIPLEX_READY_TIMEOUT_MILLIS)
            if (!accepted) {
                runCatching { multiplexTransport.close() }
                throw java.io.IOException("Nearby multiplex socket was not accepted")
            }
            logger("step 0: Nearby multiplex virtual socket opened over WIFI_LAN")
            multiplexTransport
        }
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
        // Surface every escaping exception via the logger so on-device
        // diagnosis (logcat -s BadaOutbound) gets the actual stack frame
        // — without this, a "Peer closed connection unexpectedly" gives
        // no clue which lifecycle phase the EndOfFrameStream came from.
        logger("FAILED: $reason — ${e::class.qualifiedName}")
        e.stackTrace.take(STACK_FRAMES_TO_LOG).forEach { logger("    at $it") }
        mutableState.value = OutboundConnectionState.Failed(reason)
        return OutboundResult.Failed(reason)
    }

    public companion object {
        /**
         * Generate a fresh 4-character sender endpoint id from the
         * `[A-Za-z0-9]` alphabet stock Quick Share uses (see
         * `google/nearby` `ClientProxy::GenerateEndpointId` and
         * NearDrop's `OutboundNearbyConnection.swift`). A unique id per
         * connection avoids colliding with stale per-endpoint state on
         * the receiver: Samsung One UI's GMS Nearby keeps a
         * `KeepAliveManager` loop keyed on endpoint id, and reusing the
         * same id between attempts has been observed to race the
         * teardown loop and produce intermittent silent FINs at the
         * peer-ConnectionResponse step.
         */
        @JvmStatic
        public fun generateEndpointId(random: SecureRandom = SecureRandom()): String {
            val out = CharArray(ENDPOINT_ID_LENGTH)
            for (i in 0 until ENDPOINT_ID_LENGTH) {
                out[i] = ENDPOINT_ID_ALPHABET[random.nextInt(ENDPOINT_ID_ALPHABET.length)]
            }
            return String(out)
        }

        /**
         * Length of the sender endpoint id in bytes / characters. Stock
         * Quick Share uses 4; NearDrop uses 4; we match.
         */
        private const val ENDPOINT_ID_LENGTH: Int = 4

        /**
         * Alphabet for [generateEndpointId]. Restricting to ASCII
         * alphanumerics keeps the bytes deterministic across locales and
         * lets every byte round-trip cleanly through any UTF-8/ASCII
         * channel that downstream peers use to read this value.
         */
        private const val ENDPOINT_ID_ALPHABET: String =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /**
         * Default TCP connect timeout, in milliseconds. 5 seconds is
         * generous enough for Wi-Fi LAN while still surfacing "host is
         * unreachable" failures within human attention span.
         */
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 5000

        private const val NEARBY_MULTIPLEX_READY_TIMEOUT_MILLIS: Long = 5_000L
        private const val NEARBY_MULTIPLEX_OPTIMISTIC_READY_DELAY_MILLIS: Long = 900L

        /**
         * Default timeout for the unencrypted ConnectionRequest/UKEY2/
         * ConnectionResponse leg after the transport is open.
         */
        public const val DEFAULT_INITIAL_HANDSHAKE_TIMEOUT_MILLIS: Long = 15_000L

        /**
         * Default timeout for the encrypted receiver consent response
         * after our IntroductionFrame has been sent.
         */
        public const val DEFAULT_REMOTE_ACCEPTANCE_TIMEOUT_MILLIS: Long = 30_000L

        /** Number of stack frames the diagnostic logger emits per failure. */
        private const val STACK_FRAMES_TO_LOG: Int = 8
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
