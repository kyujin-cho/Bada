/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.medium

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame

/**
 * Pure FSM for the `BANDWIDTH_UPGRADE_NEGOTIATION` event sequence.
 *
 * Events in (`BandwidthUpgradeEvent`), effects out
 * (`BandwidthUpgradeEffect`). The negotiator owns no I/O — same shape
 * as `OutboundSharingFsm` / `InboundSharingFsm`. The orchestrator
 * (`OutboundConnection` / `InboundConnection`, via the registry's
 * provider) feeds it inbound frames and externally-driven decisions
 * ("start the upgrade"); the negotiator returns a list of effects the
 * orchestrator applies to the wire and the transport.
 *
 * ### Roles
 *
 *  - **Server role.** The receiver. Initiates the upgrade by emitting
 *    [BandwidthUpgradeEffect.SendFrame] of `UPGRADE_PATH_AVAILABLE`,
 *    waits for `CLIENT_INTRODUCTION`, ACKs it, then runs the
 *    `LAST_WRITE` / `SAFE_TO_CLOSE` exchange.
 *  - **Client role.** The sender. Waits for `UPGRADE_PATH_AVAILABLE`,
 *    asks the orchestrator to adopt the new transport via
 *    [BandwidthUpgradeEffect.AdoptTransport], then sends
 *    `CLIENT_INTRODUCTION`, waits for the ACK, runs the `LAST_WRITE` /
 *    `SAFE_TO_CLOSE` exchange.
 *
 * ### Why a single FSM for both roles
 *
 * The events are role-symmetric: every wire event goes through the
 * same parser, and the effect each transition produces depends only on
 * the current state and the role. Splitting into two FSMs would force
 * the orchestrator to track a third "which negotiator should I
 * forward to" boolean, which buys nothing.
 *
 * ### Default-off framework
 *
 * The negotiator is **not** automatically wired into the connection
 * lifecycles. The orchestrator instantiates one (with the chosen
 * [Medium]) only when the receiver decides to upgrade or when the
 * sender observes an inbound `UPGRADE_PATH_AVAILABLE`. Today (Phase 1)
 * neither path fires, so the FSM exists for the per-medium adapters in
 * #49–#53 to plug into. The unit tests in this PR exercise the FSM in
 * isolation; #54 wires it into the connection drivers.
 */
public class BandwidthUpgradeNegotiator(
    public val role: Role,
    public val medium: Medium,
    public val endpointId: String,
) {
    public var state: State = State.Idle
        private set

    /**
     * Drive the FSM with one event. Returns the list of effects to
     * apply, in order; `SendFrame` always precedes any state change
     * the orchestrator needs to react to (so wire bytes go out before
     * the transport swap begins).
     */
    @Suppress("CyclomaticComplexMethod") // One arm per (state, event) pair is the cleanest dispatch.
    public fun onEvent(event: BandwidthUpgradeEvent): List<BandwidthUpgradeEffect> {
        val current = state
        val (next, effects) = transition(current, event)
        state = next
        return effects
    }

    private fun transition(
        current: State,
        event: BandwidthUpgradeEvent,
    ): Pair<State, List<BandwidthUpgradeEffect>> =
        when (event) {
            is BandwidthUpgradeEvent.Start -> handleStart(current, event)
            is BandwidthUpgradeEvent.FrameReceived -> handleFrameReceived(current, event)
            BandwidthUpgradeEvent.AdoptSucceeded -> handleAdoptSucceeded(current)
            is BandwidthUpgradeEvent.AdoptFailed -> handleAdoptFailed(current, event.reason)
            is BandwidthUpgradeEvent.PrepareFailed -> handlePrepareFailed(current, event.reason)
            BandwidthUpgradeEvent.PriorChannelDrained -> handlePriorChannelDrained(current)
            BandwidthUpgradeEvent.Abort -> abort(current, "abort requested by orchestrator")
        }

    private fun handleStart(
        current: State,
        event: BandwidthUpgradeEvent.Start,
    ): Pair<State, List<BandwidthUpgradeEffect>> {
        if (current != State.Idle) {
            return protocolError(current, "Start event in non-Idle state $current")
        }
        return when (role) {
            Role.SERVER ->
                State.AwaitingClientIntroduction to
                    listOf(
                        BandwidthUpgradeEffect.SendFrame(
                            dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                                .upgradePathAvailable(event.credentials),
                        ),
                    )
            Role.CLIENT ->
                // Client cannot Start; it reacts to the server's
                // UPGRADE_PATH_AVAILABLE. Treat as protocol error so
                // wiring bugs surface loudly.
                protocolError(current, "Client received Start; only server may initiate")
        }
    }

    private fun handleFrameReceived(
        current: State,
        event: BandwidthUpgradeEvent.FrameReceived,
    ): Pair<State, List<BandwidthUpgradeEffect>> {
        val v1 = event.frame.v1
        if (v1.type != V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
            return protocolError(current, "Non-BANDWIDTH_UPGRADE frame in negotiator: ${v1.type}")
        }
        val inner = v1.bandwidthUpgradeNegotiation
        return when (inner.eventType) {
            BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE ->
                handleUpgradePathAvailable(current, inner)
            BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION ->
                handleClientIntroduction(current)
            BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK ->
                handleClientIntroductionAck(current)
            BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL ->
                handleLastWriteToPriorChannel(current)
            BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL ->
                handleSafeToClosePriorChannel(current)
            BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE ->
                handleUpgradeFailure(current)
            // Per the proto: UPGRADE_PATH_REQUEST is server-side and
            // optional (the receiver asks the sender for its path
            // capabilities). Our framework drives selection from the
            // ConnectionRequestFrame.mediums set instead, so this
            // event is currently unsupported. UNKNOWN_EVENT_TYPE is
            // the proto default.
            BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_REQUEST,
            BandwidthUpgradeNegotiationFrame.EventType.UNKNOWN_EVENT_TYPE,
            null,
            -> protocolError(current, "Unsupported event_type ${inner.eventType}")
        }
    }

    @Suppress("ReturnCount") // Three guard clauses, one happy path; flattening is less readable.
    private fun handleUpgradePathAvailable(
        current: State,
        inner: BandwidthUpgradeNegotiationFrame,
    ): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.CLIENT) {
            return protocolError(current, "Server received UPGRADE_PATH_AVAILABLE")
        }
        if (current != State.Idle) {
            return protocolError(current, "UPGRADE_PATH_AVAILABLE in state $current")
        }
        val credentials =
            dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                .decodeCredentials(inner.upgradePathInfo)
                ?: return protocolError(
                    current,
                    "Cannot decode upgrade credentials for medium ${inner.upgradePathInfo.medium}",
                )
        return State.AdoptingTransport(credentials) to
            listOf(BandwidthUpgradeEffect.AdoptTransport(credentials))
    }

    private fun handleAdoptSucceeded(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.CLIENT || current !is State.AdoptingTransport) {
            return protocolError(current, "AdoptSucceeded in role=$role state=$current")
        }
        return State.AwaitingIntroductionAck to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .clientIntroduction(endpointId),
                ),
            )
    }

    private fun handleAdoptFailed(
        current: State,
        reason: String,
    ): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.CLIENT || current !is State.AdoptingTransport) {
            return protocolError(current, "AdoptFailed in role=$role state=$current")
        }
        return State.Failed(reason) to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .upgradeFailure(medium),
                ),
                BandwidthUpgradeEffect.UpgradeAborted(reason),
            )
    }

    private fun handlePrepareFailed(
        current: State,
        reason: String,
    ): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.SERVER) {
            return protocolError(current, "Client received PrepareFailed")
        }
        return State.Failed(reason) to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .upgradeFailure(medium),
                ),
                BandwidthUpgradeEffect.UpgradeAborted(reason),
            )
    }

    private fun handleClientIntroduction(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.SERVER || current != State.AwaitingClientIntroduction) {
            return protocolError(current, "CLIENT_INTRODUCTION in role=$role state=$current")
        }
        return State.LastWritePending to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .clientIntroductionAck(),
                ),
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .lastWriteToPriorChannel(),
                ),
            )
    }

    private fun handleClientIntroductionAck(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        if (role != Role.CLIENT || current != State.AwaitingIntroductionAck) {
            return protocolError(current, "CLIENT_INTRODUCTION_ACK in role=$role state=$current")
        }
        return State.LastWritePending to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .lastWriteToPriorChannel(),
                ),
            )
    }

    private fun handleLastWriteToPriorChannel(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        // Either side: peer told us "no more writes from me on the
        // old transport". Once OUR own pipeline drains we send
        // SAFE_TO_CLOSE; that drain notification arrives via
        // PriorChannelDrained.
        return when (current) {
            State.LastWritePending,
            State.AwaitingPeerLastWrite,
            ->
                State.AwaitingDrain to emptyList()
            else -> protocolError(current, "LAST_WRITE_TO_PRIOR_CHANNEL in state $current")
        }
    }

    private fun handlePriorChannelDrained(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        if (current != State.AwaitingDrain) {
            return protocolError(current, "PriorChannelDrained in state $current")
        }
        return State.AwaitingSafeToClose to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .safeToClosePriorChannel(),
                ),
            )
    }

    private fun handleSafeToClosePriorChannel(current: State): Pair<State, List<BandwidthUpgradeEffect>> {
        if (current != State.AwaitingSafeToClose) {
            return protocolError(current, "SAFE_TO_CLOSE_PRIOR_CHANNEL in state $current")
        }
        return State.Completed to listOf(BandwidthUpgradeEffect.UpgradeCompleted)
    }

    @Suppress("UnusedParameter") // Symmetry with the other dispatch helpers; aids future state-aware policy.
    private fun handleUpgradeFailure(current: State): Pair<State, List<BandwidthUpgradeEffect>> =
        State.Failed("Peer sent UPGRADE_FAILURE") to
            listOf(BandwidthUpgradeEffect.UpgradeAborted("Peer sent UPGRADE_FAILURE"))

    @Suppress("UnusedParameter") // Symmetry with the other dispatch helpers; aids future state-aware policy.
    private fun abort(
        current: State,
        reason: String,
    ): Pair<State, List<BandwidthUpgradeEffect>> =
        State.Failed(reason) to
            listOf(
                BandwidthUpgradeEffect.SendFrame(
                    dev.bluehouse.libredrop.protocol.connection.BandwidthUpgradeFrames
                        .upgradeFailure(medium),
                ),
                BandwidthUpgradeEffect.UpgradeAborted(reason),
            )

    @Suppress("UnusedParameter") // Symmetry with the other dispatch helpers; aids future state-aware policy.
    private fun protocolError(
        current: State,
        reason: String,
    ): Pair<State, List<BandwidthUpgradeEffect>> =
        State.Failed(reason) to
            listOf(BandwidthUpgradeEffect.ProtocolError(reason))

    /** Sender-or-receiver role for this negotiation. */
    public enum class Role {
        /** Receiver: chooses the new medium and emits credentials. */
        SERVER,

        /** Sender: adopts the new medium and sends `CLIENT_INTRODUCTION`. */
        CLIENT,
    }

    /** Negotiator state. */
    public sealed interface State {
        /** Initial. */
        public object Idle : State

        /** SERVER waiting for `CLIENT_INTRODUCTION` on the new transport. */
        public object AwaitingClientIntroduction : State

        /**
         * CLIENT has parsed `UPGRADE_PATH_AVAILABLE` and asked the
         * provider to bring up the new transport. Carries the
         * credentials so the orchestrator can recover them on retry.
         */
        public data class AdoptingTransport(
            val credentials: UpgradePathCredentials,
        ) : State

        /** CLIENT has sent `CLIENT_INTRODUCTION` and awaits the ACK. */
        public object AwaitingIntroductionAck : State

        /** Either side: waiting for the orchestrator to flush old-channel writes. */
        public object LastWritePending : State

        /** Either side: peer's LAST_WRITE has not arrived yet. (Same target as LastWritePending; transient.) */
        public object AwaitingPeerLastWrite : State

        /** Either side: pipeline draining; SAFE_TO_CLOSE not yet sent. */
        public object AwaitingDrain : State

        /** Either side: SAFE_TO_CLOSE sent, awaiting peer's. */
        public object AwaitingSafeToClose : State

        /** Terminal: upgrade succeeded. */
        public object Completed : State

        /** Terminal: upgrade failed; we stay on the current transport. */
        public data class Failed(
            val reason: String,
        ) : State
    }
}

/**
 * Events the negotiator FSM accepts.
 */
public sealed interface BandwidthUpgradeEvent {
    /**
     * SERVER only: start the upgrade. The orchestrator has chosen
     * [credentials] (typically by intersecting the peer's mediums
     * with the local registry and picking via the ladder, then asking
     * [MediumProvider.prepareUpgrade] for the credentials).
     */
    public data class Start(
        val credentials: UpgradePathCredentials,
    ) : BandwidthUpgradeEvent

    /**
     * Inbound `OfflineFrame{BANDWIDTH_UPGRADE_NEGOTIATION}` received
     * on the **current** transport (or, post-CLIENT_INTRODUCTION, on
     * the **new** transport — that's the orchestrator's
     * responsibility to multiplex).
     */
    public data class FrameReceived(
        val frame: OfflineFrame,
    ) : BandwidthUpgradeEvent

    /**
     * CLIENT only: [MediumProvider.adoptUpgrade] returned a non-null
     * transport.
     */
    public object AdoptSucceeded : BandwidthUpgradeEvent

    /**
     * CLIENT only: [MediumProvider.adoptUpgrade] returned `null` or
     * threw. The framework converts this into an `UPGRADE_FAILURE` on
     * the wire.
     */
    public data class AdoptFailed(
        val reason: String,
    ) : BandwidthUpgradeEvent

    /**
     * SERVER only: [MediumProvider.prepareUpgrade] returned `null` or
     * threw before the FSM was started. Handed in as an event so the
     * orchestrator can recover the abort sequence (UPGRADE_FAILURE +
     * teardown) through the same effect channel.
     */
    public data class PrepareFailed(
        val reason: String,
    ) : BandwidthUpgradeEvent

    /**
     * Either side: the orchestrator has finished writing on the old
     * channel and is ready to send `SAFE_TO_CLOSE`. The orchestrator
     * is responsible for fielding LAST_WRITE_TO_PRIOR_CHANNEL on the
     * old channel and emitting this once its own writer queue is
     * drained.
     */
    public object PriorChannelDrained : BandwidthUpgradeEvent

    /** Either side: caller-driven abort. */
    public object Abort : BandwidthUpgradeEvent
}

/**
 * Effects the negotiator emits.
 */
public sealed interface BandwidthUpgradeEffect {
    /**
     * Send [frame] on the appropriate transport. The orchestrator
     * picks "old" vs "new" based on the negotiator state at emission
     * time:
     *
     *  - `UPGRADE_PATH_AVAILABLE` → old transport (the only one
     *    available pre-introduction).
     *  - `CLIENT_INTRODUCTION` → new transport (the client just
     *    opened it; the server is parked there awaiting it).
     *  - `CLIENT_INTRODUCTION_ACK` → new transport.
     *  - `LAST_WRITE_TO_PRIOR_CHANNEL` / `SAFE_TO_CLOSE_PRIOR_CHANNEL`
     *    → old transport.
     *  - `UPGRADE_FAILURE` → whichever transport is still available.
     */
    public data class SendFrame(
        val frame: OfflineFrame,
    ) : BandwidthUpgradeEffect

    /**
     * CLIENT only: the orchestrator should call
     * [MediumProvider.adoptUpgrade] with [credentials], then feed back
     * either [BandwidthUpgradeEvent.AdoptSucceeded] or
     * [BandwidthUpgradeEvent.AdoptFailed].
     */
    public data class AdoptTransport(
        val credentials: UpgradePathCredentials,
    ) : BandwidthUpgradeEffect

    /** Terminal: the upgrade completed; orchestrator may swap transports. */
    public object UpgradeCompleted : BandwidthUpgradeEffect

    /** Terminal: the upgrade failed; orchestrator stays on the current transport. */
    public data class UpgradeAborted(
        val reason: String,
    ) : BandwidthUpgradeEffect

    /**
     * Terminal-ish: the FSM observed an event it cannot validate.
     * Surfaced as a separate effect (rather than rolled into
     * [UpgradeAborted]) so the orchestrator can log it differently —
     * a protocol error indicates a bug or malicious peer, while
     * [UpgradeAborted] is the regular fallback path.
     */
    public data class ProtocolError(
        val reason: String,
    ) : BandwidthUpgradeEffect
}
