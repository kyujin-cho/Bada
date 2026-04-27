/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

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
     * Test/debug accessor for the dismissed latch. Not part of the
     * public contract — exists so unit tests can verify the latch is
     * sticky without going through [shouldShowHint].
     */
    internal fun isDismissed(): Boolean = dismissed

    companion object {
        /**
         * Default empty-peer-list timeout. ~3 seconds matches the
         * issue body's UX guidance and the wording in the runbooks
         * — long enough that a healthy LAN with a receiver already
         * advertising surfaces a peer first, short enough that a
         * misconfigured network produces actionable feedback before
         * the user gives up and closes the share sheet.
         */
        const val DEFAULT_DELAY_MILLIS: Long = 3_000L
    }
}
