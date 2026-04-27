/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [EmptyPeerHintTimer], the timing helper for the
 * same-Wi-Fi-network hint card surfaced by [SendActivity] (#85).
 *
 * The class is intentionally side-effect free, so we drive it directly
 * with synthetic timestamps rather than mocking `Handler` /
 * `lifecycleScope`. That keeps the timing rules exercised without
 * dragging in Robolectric just for `delay()` / `Looper`.
 */
class EmptyPeerHintTimerTest {
    @Test
    fun `hint stays hidden before start is called`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        // Even with peerListEmpty true and a "long enough" wall clock,
        // shouldShowHint must return false until start() runs — the
        // timer is opt-in to avoid surfacing on activities that never
        // entered the discovery flow.
        assertFalse(timer.shouldShowHint(nowMillis = 1_000_000L, peerListEmpty = true))
    }

    @Test
    fun `hint stays hidden before delay elapses`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        timer.start(nowMillis = 1_000L)
        // 2_999ms after start is still below the threshold; the card
        // must remain hidden.
        assertFalse(timer.shouldShowHint(nowMillis = 1_000L + 2_999L, peerListEmpty = true))
    }

    @Test
    fun `hint surfaces after delay elapses with empty peer list`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        timer.start(nowMillis = 1_000L)
        // Exactly at the boundary the hint must surface — boundary
        // inclusion matters for the coroutine `delay(...)` call site,
        // which calls back at `start + delay` precisely.
        assertTrue(timer.shouldShowHint(nowMillis = 1_000L + 3_000L, peerListEmpty = true))
    }

    @Test
    fun `hint stays hidden once a peer arrives`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        timer.start(nowMillis = 0L)
        // Even past the delay, a non-empty peer list must keep the
        // card hidden.
        assertFalse(timer.shouldShowHint(nowMillis = 10_000L, peerListEmpty = false))
    }

    @Test
    fun `hint stays hidden after dismissal`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        timer.start(nowMillis = 0L)
        timer.markDismissed()
        // After dismissal, even if the threshold and emptiness say
        // "show", the latch must keep the card hidden.
        assertFalse(timer.shouldShowHint(nowMillis = 100_000L, peerListEmpty = true))
        assertTrue(timer.isDismissed())
    }

    @Test
    fun `start is idempotent — repeated calls keep the original timestamp`() {
        val timer = EmptyPeerHintTimer(delayMillis = 3_000L)
        timer.start(nowMillis = 0L)
        // A second start at t=2_500 must not push the surface out to
        // t=2_500+3_000 — the original t=0 anchor stands.
        timer.start(nowMillis = 2_500L)
        assertTrue(timer.shouldShowHint(nowMillis = 3_000L, peerListEmpty = true))
    }

    @Test
    fun `default delay matches the issue body's three-second guidance`() {
        // The issue body specifies "after ~3 seconds with no peers".
        // Lock that contract in so a future tweak goes through code
        // review.
        assertTrue(EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS == 3_000L)
    }
}
