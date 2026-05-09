/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import android.content.Intent

/**
 * Intent action / extra constants and pure-JVM intent-routing helpers
 * shared between the consent notification builder, the broadcast
 * receiver, and the trampoline activity.
 *
 * ### Why a separate object
 *
 * The constants need to be visible from three places in two modules:
 *
 *  - the notification builder in `:service-android` that constructs the
 *    `PendingIntent`s,
 *  - the broadcast receiver in `:service-android` that dispatches them,
 *  - the trampoline activity in `:app` that re-uses the same routing
 *    semantics for its in-app Accept / Reject buttons.
 *
 * Centralising avoids the classic Android footgun where one site
 * changes an action string and the other half of the rendezvous
 * silently breaks.
 *
 * ### Routing model
 *
 * Every consent broadcast carries:
 *
 *  - `ACTION_ACCEPT` or `ACTION_REJECT` — what the user pressed,
 *  - `EXTRA_CONNECTION_ID` (long) — which in-flight transfer the
 *    decision applies to (matches
 *    [dev.bluehouse.bada.protocol.server.InboundConnectionCompletion.connectionId]).
 *
 * The action distinguishes Accept vs Reject rather than a boolean
 * extra so a stale `PendingIntent` can be filterMatch-rejected by the
 * receiver before the extras are parsed.
 *
 * ### Pure-JVM extraction
 *
 * Tests assert routing semantics without touching real Android extras
 * by going through the pure-JVM extractor: a test constructs a fake
 * intent (just enough surface — see [parsePayload]) and verifies that
 * `parsePayload` either returns a [Payload] or `null`.
 */
public object ConsentIntents {
    /** Broadcast action fired when the user taps the notification's "Accept" action. */
    public const val ACTION_ACCEPT: String = "dev.bluehouse.bada.consent.ACCEPT"

    /** Broadcast action fired when the user taps the notification's "Reject" action. */
    public const val ACTION_REJECT: String = "dev.bluehouse.bada.consent.REJECT"

    /**
     * Broadcast action fired when the user taps the **Cancel** action
     * on the in-flight transfer progress notification (#46). Distinct
     * from `ACTION_REJECT` because Reject lives on the
     * `WaitingForUserConsent` heads-up notification (the user has not
     * yet accepted) while Cancel lives on the `Receiving` progress
     * notification (the user already accepted; they want to abort
     * mid-transfer). The receiver dispatches Cancel through
     * [dev.bluehouse.bada.protocol.connection.InboundConnection.cancel]
     * rather than `submitUserConsent`.
     */
    public const val ACTION_CANCEL_TRANSFER: String = "dev.bluehouse.bada.consent.CANCEL_TRANSFER"

    /**
     * Activity action used by the trampoline activity's launch
     * `PendingIntent`. The activity reads the same connection-id extra
     * and looks the connection up in [ConsentRegistry].
     */
    public const val ACTION_SHOW_CONSENT: String = "dev.bluehouse.bada.consent.SHOW"

    /**
     * `Long` extra carrying the connection id this consent applies to.
     * The broadcast receiver and the trampoline activity both parse
     * this off the intent before consulting [ConsentRegistry].
     */
    public const val EXTRA_CONNECTION_ID: String = "libredrop.consent.connection_id"

    /**
     * Sentinel value for [EXTRA_CONNECTION_ID] meaning "unset". Picked
     * to be impossible for a real
     * [dev.bluehouse.bada.protocol.server.InboundConnectionCompletion.connectionId]
     * since those start at 1 and only ever increment.
     */
    public const val MISSING_CONNECTION_ID: Long = -1L

    /**
     * Decision encoded by a consent broadcast / activity action.
     *
     * `null` cases are surfaced as [Payload] = null from
     * [parsePayload] so callers can branch on absence vs malformed
     * actions without reflecting on action-string substrings.
     */
    public enum class Decision {
        /** User pressed the Accept action. */
        ACCEPT,

        /** User pressed the Reject action. */
        REJECT,
    }

    /**
     * Decoded form of a consent intent. Pairs the [Decision] with the
     * [connectionId] that should receive it.
     */
    public data class Payload(
        val decision: Decision,
        val connectionId: Long,
    ) {
        /** `true` when [Decision.ACCEPT]; convenience for the broadcast site. */
        public val accepted: Boolean
            get() = decision == Decision.ACCEPT
    }

    /**
     * Parse the action + connection id off [intent]. Returns `null`
     * when the action is not a recognised consent action or when the
     * extra is missing — both treated identically by the broadcast
     * receiver, which then drops the intent.
     *
     * Pure JVM: only consults the action string and a single long
     * extra, which makes it easy to drive from a unit test against a
     * real `Intent`-like fake (see [IntentLike]).
     */
    @Suppress("ReturnCount") // Three early-exit branches are clearer than a nested when.
    public fun parsePayload(intent: IntentLike): Payload? {
        val decision =
            when (intent.action) {
                ACTION_ACCEPT -> Decision.ACCEPT
                ACTION_REJECT -> Decision.REJECT
                else -> return null
            }
        val connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, MISSING_CONNECTION_ID)
        if (connectionId == MISSING_CONNECTION_ID) return null
        return Payload(decision, connectionId)
    }

    /**
     * Bridge over the subset of [Intent] that [parsePayload] needs.
     *
     * Production wires this with a thin adapter around the real
     * [Intent] (see [from]); tests construct an in-memory map-backed
     * implementation. Keeping the surface this small keeps the unit
     * tests Robolectric-free.
     */
    public interface IntentLike {
        public val action: String?

        public fun getLongExtra(
            name: String,
            default: Long,
        ): Long

        public companion object {
            /**
             * Adapt a real [Intent] to the [IntentLike] interface.
             *
             * Inline for zero allocation overhead at the call site —
             * the broadcast receiver path is hot enough that we don't
             * want a per-broadcast wrapper object.
             */
            public fun from(intent: Intent): IntentLike =
                object : IntentLike {
                    override val action: String?
                        get() = intent.action

                    override fun getLongExtra(
                        name: String,
                        default: Long,
                    ): Long = intent.getLongExtra(name, default)
                }
        }
    }
}
