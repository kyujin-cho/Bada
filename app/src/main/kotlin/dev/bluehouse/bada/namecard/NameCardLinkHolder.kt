/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.namecard.NameCardConsentMachine.Effect
import dev.bluehouse.bada.namecard.NameCardConsentMachine.Event
import dev.bluehouse.bada.protocol.namecard.NameCard

/**
 * **Name Card v2 live-session coordinator** — the single process-wide brain of a symmetric NameDrop
 * exchange. It exists because the two
 * halves of a v2 session are created in different places:
 *  - the **server** side is started by [NameCardExchangeService] on the NFC tap (before any UI),
 *  - the **client** side is started by [NameCardTransferActivity] after the AAR wake,
 * yet both need to share ONE [NameCardConsentMachine] and let the transfer screen drive the user's
 * Share / Receive-Only choice without binder plumbing. The holder is that shared point.
 *
 * ## How it fits
 * A [Session] IS the [ConsentBleListener] the BLE layer ([NameCardBleExchange]) reports peer events
 * to. It feeds those events (plus the local taps and the 30 s timeout the activity posts) into the
 * machine, then [Session.apply]s the resulting effects: BLE effects go back to the exchange
 * (`sendLocalChoice` / `transmitCard` / `sendByeAndClose`), and every effect is forwarded to the
 * attached [UiObserver] (the activity) so it can render the §8 states and log each transition.
 *
 * ## Lifecycle
 * [startSession] supersedes any stale session (stops its exchange). The session self-clears on the
 * machine's `CloseLink` (and pings [Session.onClosed] so the foreground service can `stopSelf`). The
 * activity attaches via [current] + [Session.uiObserver]; a late-attaching activity reads
 * [Session.peerCard] to catch a card that already arrived.
 *
 * ## Status
 * Compile-only on this box (drives the device-only BLE layer). The machine it drives is JVM-tested.
 */
internal object NameCardLinkHolder {
    /** Which half of the exchange this side is running. */
    enum class Role { SERVER, CLIENT }

    /** The transfer screen observes the live session through this. All calls arrive on the main thread. */
    interface UiObserver {
        /** The link can carry a choice now — enable the Share / Receive-Only buttons. */
        fun onReady()

        /** Every machine [Effect] in order (the observer renders the UI ones, ignores the BLE ones). */
        fun onConsentEffect(effect: Effect)

        /** The peer's card arrived — bind it into the view (the ripple/save comes via [Effect.SaveCardAndRipple]). */
        fun onPeerCard(card: NameCard)
    }

    /**
     * One live exchange: the [exchange], its [role], our own [localCard], and the shared consent
     * [machine]. Also the [ConsentBleListener] the BLE layer reports into.
     */
    class Session internal constructor(
        val exchange: NameCardBleExchange,
        val role: Role,
        val localCard: NameCard?,
    ) : ConsentBleListener {
        val machine = NameCardConsentMachine()

        /** The peer's card once it has arrived (for a UI that attaches after the fact). */
        @Volatile
        var peerCard: NameCard? = null
            private set

        /** True once the link reported ready (for a UI that re-attaches after a config change). */
        @Volatile
        var linkReady: Boolean = false
            private set

        /** The transfer screen, once it attaches. */
        @Volatile
        var uiObserver: UiObserver? = null

        /** Server service hook: invoked once the exchange closes so the FGS can stop. */
        @Volatile
        var onClosed: (() -> Unit)? = null

        /**
         * The card actually transmitted on Share — [localCard] filtered by the field-picker
         * (which of phone/email the user checked to share). The activity updates this as the user
         * toggles the share checkboxes; defaults to the full [localCard]. See
         * [NameCardTransferActivity]'s share-field picker.
         */
        @Volatile
        var shareCard: NameCard? = localCard

        // ---- driven by the UI (main thread) ----

        fun onLocalShare() = apply(machine.onEvent(Event.LocalShare))

        fun onLocalReceiveOnly() = apply(machine.onEvent(Event.LocalReceiveOnly))

        fun onTimeout() = apply(machine.onEvent(Event.Timeout))

        // ---- ConsentBleListener: peer events from the BLE layer (already on main) ----

        override fun onLinkReady() {
            DiagnosticLog.w(TAG, "session($role): link ready")
            linkReady = true
            uiObserver?.onReady()
        }

        override fun onPeerHello() {
            DiagnosticLog.w(TAG, "session($role): peer HELLO (v2)")
        }

        override fun onPeerChoice(share: Boolean) =
            apply(machine.onEvent(if (share) Event.PeerShare else Event.PeerReceiveOnly))

        override fun onPeerCardArrived(card: NameCard) {
            peerCard = card
            uiObserver?.onPeerCard(card)
            apply(machine.onEvent(Event.PeerCardArrived))
        }

        override fun onDisconnected() = apply(machine.onEvent(Event.Disconnected))

        /** Route machine effects: forward all to the UI, act on the BLE ones here. */
        private fun apply(effects: List<Effect>) {
            for (effect in effects) {
                uiObserver?.onConsentEffect(effect)
                when (effect) {
                    is Effect.SendChoice -> exchange.sendLocalChoice(effect.share)
                    Effect.TransmitCard -> shareCard?.let { exchange.transmitCard(it) }
                    Effect.CloseLink -> {
                        exchange.sendByeAndClose()
                        onClosed?.invoke()
                        clearIf(this)
                    }
                    else -> Unit // UI-only effects are handled by the observer
                }
            }
        }
    }

    @Volatile
    private var session: Session? = null

    /** Begin a new session, superseding (and stopping) any stale one. */
    fun startSession(
        exchange: NameCardBleExchange,
        role: Role,
        localCard: NameCard?,
    ): Session {
        session?.let { stale -> runCatching { stale.exchange.stop() } }
        return Session(exchange, role, localCard).also { session = it }
    }

    /** The current live session, or null. */
    fun current(): Session? = session

    /** Drop the current session reference (does not stop the exchange). */
    fun clear() {
        session = null
    }

    private fun clearIf(s: Session) {
        if (session === s) session = null
    }

    private const val TAG = "NameCardLink"
}
