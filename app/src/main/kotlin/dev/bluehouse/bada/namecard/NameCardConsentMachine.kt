/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

/**
 * **Name Card v2 symmetric-consent state machine** — the pure, role-agnostic brain of the NameDrop
 * exchange. It encodes the "my choice × peer's choice" matrix: each side independently taps
 * **Share** or **Receive Only**, the two apps tell each other over the CONSENT channel
 * ([NameCardConsentCodec]), and this machine turns the resulting events into UI/BLE [Effect]s.
 *
 * ## Why it exists
 * v1 was asymmetric and had a consent hole: the server handed its card over on connect, and a
 * "Receive Only" was a silent disconnect the peer never learned about. This machine makes consent
 * **per-side** (plan D1, user-confirmed 2026-07-03: "if you tap share you could send your card"):
 * your card is transmitted the moment YOU tap Share, regardless of whether the peer has chosen —
 * only your OWN choice gates your OWN card.
 *
 * ## Role-agnostic (both client and server run one instance)
 * The BLE layer ([NameCardBleExchange]) maps effects to its role:
 *  - [Effect.SendChoice] — client WRITES the choice to CONSENT; server NOTIFIES it.
 *  - [Effect.TransmitCard] — client WRITEs its card to the peer's CARD characteristic; server
 *    opens the gated CARD read (its `localChoice == SHARE`) so the client may read it.
 *  - [Effect.SaveCardAndRipple] — fires only on [Event.PeerCardArrived] (the peer's card BYTES
 *    arrived + parsed), never on a bare choice message (plan D2).
 *
 * ## No timers, no threads (plan B2/D5)
 * The machine is pure and synchronous. Time is the caller's job: the activity posts the 30 s
 * [Event.Timeout]; the BLE layer posts [Event.Disconnected]. The machine only decides effects.
 *
 * ## CloseLink is deferred until the incoming card is in hand (correctness, plan D2)
 * When both sides have chosen but the peer chose Share and their card has NOT yet arrived, the
 * machine does **not** emit [Effect.CloseLink] — closing then would abort the still-pending card
 * read. It defers CloseLink to the [Event.PeerCardArrived] that completes the exchange. The BLE
 * layer separately defers the actual teardown until any in-flight OUTGOING card write finishes,
 * then sends `BYE` and stops (plan B3). So: machine guarantees incoming obligations are met before
 * CloseLink; BLE guarantees outgoing ones are.
 *
 * ## Unauthenticated peers are NOT modeled here
 * A peer whose HELLO does not carry the session's rendezvous token is disconnected by the BLE layer
 * before any of its events reach this machine. Everything below assumes an authenticated peer.
 *
 * ## Status
 * Pure-JVM (zero `android.*` imports), exhaustively unit-tested in `NameCardConsentMachineTest`
 * (all 9 matrix cells, both timeout rows, early disconnect, and event-order permutations per D4;
 * post-terminal events → empty). The BLE/activity wiring that drives it is compile-only on this box.
 */
@Suppress("ReturnCount")
internal class NameCardConsentMachine {
    /** A side's decision. */
    enum class Choice { SHARE, RECEIVE_ONLY }

    /**
     * Outcome of the exchange. [ACTIVE] until the choices resolve (or a no-response). The terminal
     * variants map 1:1 to the §3 matrix; exposed for tests + on-screen diagnostics.
     */
    enum class State {
        /** Not yet resolved — still waiting on one or both choices. */
        ACTIVE,

        /** Both tapped Share — mutual exchange. */
        DONE_MUTUAL,

        /** I tapped Share, peer tapped Receive Only — my card reached them; they shared nothing. */
        DONE_SHARED_PEER_DECLINED,

        /** I tapped Receive Only, peer tapped Share — I saved theirs; they got nothing from me. */
        DONE_RECEIVED_PEER_SHARED,

        /** Both tapped Receive Only — nothing exchanged; card fades to a "declined" note. */
        DONE_BOTH_DECLINED,

        /** No response before the 30 s timeout, or the link dropped before both chose. */
        DONE_NO_RESPONSE,
    }

    /** Inputs, fed in ANY interleaving (plan D4). */
    sealed interface Event {
        /** This user tapped Share. */
        data object LocalShare : Event

        /** This user tapped Receive Only. */
        data object LocalReceiveOnly : Event

        /** The peer's CONSENT channel reported they tapped Share. */
        data object PeerShare : Event

        /** The peer's CONSENT channel reported they tapped Receive Only. */
        data object PeerReceiveOnly : Event

        /** The peer's card BYTES arrived and parsed (client read completed / server write received). */
        data object PeerCardArrived : Event

        /** The 30 s no-response timer fired (posted by the activity). */
        data object Timeout : Event

        /** The BLE link dropped (posted by the BLE layer). */
        data object Disconnected : Event
    }

    /** Outputs the caller performs. See the class KDoc for the per-role BLE mapping. */
    sealed interface Effect {
        /** Tell the peer my choice ([share] = Share vs Receive Only). Client writes; server notifies. */
        data class SendChoice(
            val share: Boolean,
        ) : Effect

        /** Send my card now (client card WRITE / server opens its gated CARD read). */
        data object TransmitCard : Effect

        /** The peer's card arrived: play the receive ripple + save the contact. */
        data object SaveCardAndRipple : Effect

        /** Receive-Only + peer undecided: show the small "waiting…" line, ripple suppressed. */
        data object ShowWaiting : Effect

        /** Share + peer undecided: post the heads-up "Waiting for <peer> to respond". */
        data object ShowHeadsUpWaiting : Effect

        /** I shared, peer declined: mutate the heads-up to "<peer> declined to share their info". */
        data object UpdateHeadsUpDeclined : Effect

        /** Both declined: fade the card to "They declined to share their contact info", Done only. */
        data object FadeToDeclined : Effect

        /** Timed out / dropped before resolution: fade to "No response", Done only. */
        data object ShowNoResponse : Effect

        /** All incoming obligations met — the BLE layer may send BYE + tear down (after any outgoing write). */
        data object CloseLink : Effect
    }

    /** Current outcome; [State.ACTIVE] until resolved. Exposed for tests + diagnostics. */
    var state: State = State.ACTIVE
        private set

    private var localChoice: Choice? = null
    private var peerChoice: Choice? = null
    private var cardSaved: Boolean = false

    /** Once true, the link is closed and further events are ignored. */
    private var closed: Boolean = false

    /** True once [Effect.CloseLink] has been emitted (the exchange is fully over). */
    val isClosed: Boolean get() = closed

    /**
     * Feed one [event]; returns the ordered [Effect]s to perform (possibly empty). Pure and
     * synchronous. Duplicate/late events (a second local choice, a stray card, anything after the
     * link closed) return an empty list — never throw.
     */
    fun onEvent(event: Event): List<Effect> {
        if (closed) return emptyList()
        return when (event) {
            Event.LocalShare -> onLocalChoice(Choice.SHARE)
            Event.LocalReceiveOnly -> onLocalChoice(Choice.RECEIVE_ONLY)
            Event.PeerShare -> onPeerChoice(Choice.SHARE)
            Event.PeerReceiveOnly -> onPeerChoice(Choice.RECEIVE_ONLY)
            Event.PeerCardArrived -> onPeerCard()
            Event.Timeout, Event.Disconnected -> onNoResponse()
        }
    }

    private fun onLocalChoice(choice: Choice): List<Effect> {
        if (localChoice != null) return emptyList() // ignore a duplicate local tap
        localChoice = choice
        val effects = mutableListOf<Effect>()
        effects += Effect.SendChoice(share = choice == Choice.SHARE)
        if (choice == Choice.SHARE) effects += Effect.TransmitCard
        val peer = peerChoice
        if (peer == null) {
            // Peer hasn't chosen — surface the waiting state (Scenario A/B).
            effects += if (choice == Choice.SHARE) Effect.ShowHeadsUpWaiting else Effect.ShowWaiting
        } else {
            effects += resolve(local = choice, peer = peer)
        }
        return effects
    }

    private fun onPeerChoice(choice: Choice): List<Effect> {
        if (peerChoice != null) return emptyList() // ignore a duplicate peer message
        peerChoice = choice
        val local = localChoice ?: return emptyList() // peer is waiting on me; I keep my buttons
        return resolve(local = local, peer = choice)
    }

    private fun onPeerCard(): List<Effect> {
        if (cardSaved) return emptyList() // ripple/save exactly once
        cardSaved = true
        val effects = mutableListOf<Effect>(Effect.SaveCardAndRipple)
        // If the choices already resolved and we were only holding the link open for THIS card,
        // the exchange is now complete → close.
        if (state != State.ACTIVE) effects += close()
        return effects
    }

    private fun onNoResponse(): List<Effect> {
        if (state != State.ACTIVE) {
            // Choices were made; we were only awaiting a card that never came. Just tear down.
            return close()
        }
        state = State.DONE_NO_RESPONSE
        return listOf(Effect.ShowNoResponse) + close()
    }

    /**
     * Both choices are known: set the terminal [state], emit the resolution's UI effect, and close
     * UNLESS the peer shared and their card hasn't arrived yet (then defer CloseLink to
     * [onPeerCard] — closing now would abort the pending read; plan D2).
     */
    private fun resolve(
        local: Choice,
        peer: Choice,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        when {
            local == Choice.SHARE && peer == Choice.SHARE -> state = State.DONE_MUTUAL
            local == Choice.SHARE && peer == Choice.RECEIVE_ONLY -> {
                state = State.DONE_SHARED_PEER_DECLINED
                effects += Effect.UpdateHeadsUpDeclined
            }
            local == Choice.RECEIVE_ONLY && peer == Choice.SHARE -> state = State.DONE_RECEIVED_PEER_SHARED
            else -> {
                state = State.DONE_BOTH_DECLINED
                effects += Effect.FadeToDeclined
            }
        }
        val awaitingPeerCard = peer == Choice.SHARE && !cardSaved
        if (!awaitingPeerCard) effects += close()
        return effects
    }

    /** Mark the link closed and emit the single [Effect.CloseLink]. Idempotent via the [closed] guard. */
    private fun close(): List<Effect> {
        closed = true
        return listOf(Effect.CloseLink)
    }
}
