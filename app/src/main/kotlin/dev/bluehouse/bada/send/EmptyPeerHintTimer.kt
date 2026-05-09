/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

/**
 * Pure-JVM helper for the same-Wi-Fi-network hint card in
 * [SendActivity] (#85).
 *
 * The hint surfaces when the peer list has been empty for at least
 * [delayMillis] **continuous** milliseconds since the activity started
 * discovery. Any peer that resolves before the timeout cancels the
 * hint; if peers later disappear, the hint can re-arm because the
 * issue's UX guidance is "show after ~3 seconds of empty peer list".
 *
 * Once the user dismisses the hint via the card's button it stays
 * dismissed for the rest of the activity's lifetime — re-arming after
 * dismissal would defeat the "dismissable" affordance the issue calls
 * for. [markDismissed] is the one-way latch.
 *
 * The helper is intentionally side-effect free: it tracks state but
 * does not own a coroutine, a `Handler`, or a `View`. Callers schedule
 * the timeout on whatever scheduler they already have (in the Android
 * activity case, `lifecycleScope.launch { delay(...) }`) and consult
 * [shouldShowHint] when the timeout elapses or after every peer-list
 * change. That keeps the timing logic deterministic in unit tests
 * without dragging in `Handler`, `Looper`, or Robolectric.
 *
 * @param delayMillis minimum milliseconds the peer list must remain
 *   empty before the hint is allowed to surface. Defaults to
 *   [DEFAULT_DELAY_MILLIS] (~3 seconds, per the issue body).
 */
internal class EmptyPeerHintTimer(
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS,
) {
    /**
     * Wall-clock-ish timestamp at which the discovery flow started, in
     * the same time base passed to [shouldShowHint]. `null` until
     * [start] is called.
     */
    private var startTimestamp: Long? = null

    /**
     * Sticky-once flag: `true` after the user has tapped the dismiss
     * button on the card. Never resets — re-showing would be noisy.
     */
    private var dismissed: Boolean = false

    /**
     * Begin tracking. Idempotent — repeated calls keep the original
     * start time so a re-issued discovery flow doesn't push the hint
     * surfaceing back to "now + delay".
     *
     * @param nowMillis current time in the caller's chosen base
     *   (System.currentTimeMillis or a test clock).
     */
    fun start(nowMillis: Long) {
        if (startTimestamp == null) startTimestamp = nowMillis
    }

    /**
     * Mark the hint dismissed by the user. Latches to `true` for the
     * timer's lifetime — see the class doc for the rationale.
     */
    fun markDismissed() {
        dismissed = true
    }

    /**
     * @return `true` when the hint should be visible at [nowMillis]
     *   given the latest known [peerListEmpty] state. Returns `false`
     *   if [start] was never called, if the user dismissed the hint,
     *   if the peer list is non-empty, or if [delayMillis] has not
     *   yet elapsed since [start].
     */
    fun shouldShowHint(
        nowMillis: Long,
        peerListEmpty: Boolean,
    ): Boolean {
        // All four conditions must hold for the card to surface; we
        // express them as a single boolean so detekt's ReturnCount
        // rule stays satisfied without obscuring the intent.
        val started = startTimestamp
        return !dismissed &&
            peerListEmpty &&
            started != null &&
            nowMillis - started >= delayMillis
    }

    /**
     * @return `true` when the inline "no devices nearby yet" empty-
     *   state TextView should be visible at [nowMillis] given the
     *   latest [peerListEmpty]. Mirrors [shouldShowHint] except it
     *   does NOT check the dismissed latch — the empty-state text
     *   is informational, not user-dismissable, so it should keep
     *   surfacing as long as the peer list stays empty past the
     *   delay window. Suppressing it during the first [delayMillis]
     *   keeps the picker from flashing a "no devices found" message
     *   the instant the user opens the share sheet, before discovery
     *   has had a real chance to land its first event.
     */
    fun shouldShowEmptyState(
        nowMillis: Long,
        peerListEmpty: Boolean,
    ): Boolean {
        val started = startTimestamp
        return peerListEmpty &&
            started != null &&
            nowMillis - started >= delayMillis
    }

    /**
     * Accessor for the dismissed latch. The picker uses this when it
     * surfaces the same hint immediately for visible but unroutable
     * peers, while tests use it to verify the latch is sticky without
     * going through [shouldShowHint].
     */
    internal fun isDismissed(): Boolean = dismissed

    companion object {
        /**
         * Default empty-peer-list timeout. 10 seconds gives the
         * receiver, the network, and the BLE pulse handshake enough
         * time to land a first peer event on slow Wi-Fi handoffs
         * and on devices where the receive-side foreground service
         * was just brought up by the share intent itself. The
         * earlier ~3 s window produced false-negative "no devices
         * nearby yet" flashes on healthy LANs that just happened to
         * be a beat slow on the very first scan after the share
         * activity opened.
         */
        const val DEFAULT_DELAY_MILLIS: Long = 10_000L
    }
}
