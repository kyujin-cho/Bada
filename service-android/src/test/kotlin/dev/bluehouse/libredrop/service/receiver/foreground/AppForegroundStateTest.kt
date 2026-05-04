/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.foreground

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [InMemoryAppForegroundState].
 *
 * The production [ProcessLifecycleOwnerAppForegroundState] adapter is
 * a thin wrapper around [androidx.lifecycle.ProcessLifecycleOwner] —
 * exercising it requires Robolectric, which the project intentionally
 * stays away from in `:service-android` JVM tests. The in-memory
 * variant is the contract test target: every behaviour the production
 * adapter promises (initial value, transition coalescing, listener
 * fan-out, cancellation idempotence) is asserted here.
 */
class AppForegroundStateTest {
    @Test
    fun `initial value is reported through isForeground`() {
        assertThat(InMemoryAppForegroundState(initial = false).isForeground).isFalse()
        assertThat(InMemoryAppForegroundState(initial = true).isForeground).isTrue()
    }

    @Test
    fun `set fires the listener on every actual transition`() {
        val state = InMemoryAppForegroundState(initial = false)
        val seen = mutableListOf<Boolean>()
        state.addListener { value -> seen += value }

        state.set(true)
        state.set(false)
        state.set(true)

        assertThat(seen).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `equal-state set is coalesced and does not fire the listener`() {
        val state = InMemoryAppForegroundState(initial = false)
        val seen = mutableListOf<Boolean>()
        state.addListener { value -> seen += value }

        state.set(false) // already false; coalesce.
        state.set(true)
        state.set(true) // already true; coalesce.
        state.set(true) // and again.
        state.set(false)
        state.set(false) // coalesce.

        assertThat(seen).containsExactly(true, false).inOrder()
        assertThat(state.isForeground).isFalse()
    }

    @Test
    fun `cancel removes the listener and silences subsequent transitions`() {
        val state = InMemoryAppForegroundState(initial = false)
        val seen = mutableListOf<Boolean>()
        val sub = state.addListener { value -> seen += value }

        state.set(true)
        sub.cancel()
        state.set(false)
        state.set(true)

        assertThat(seen).containsExactly(true)
    }

    @Test
    fun `cancel is idempotent`() {
        val state = InMemoryAppForegroundState(initial = false)
        val sub = state.addListener { /* no-op */ }

        // Calling cancel multiple times is a contract guarantee: the
        // coordinator keeps a reference and may call it during
        // teardown even after the underlying state was already torn
        // down once.
        sub.cancel()
        sub.cancel()
        sub.cancel()

        // No exception, and a fresh transition still goes through.
        state.set(true)
        assertThat(state.isForeground).isTrue()
    }

    @Test
    fun `multiple listeners are fanned out in registration order`() {
        val state = InMemoryAppForegroundState(initial = false)
        val a = mutableListOf<Boolean>()
        val b = mutableListOf<Boolean>()
        val c = mutableListOf<Boolean>()
        state.addListener { value -> a += value }
        state.addListener { value -> b += value }
        state.addListener { value -> c += value }

        state.set(true)
        state.set(false)

        assertThat(a).containsExactly(true, false).inOrder()
        assertThat(b).containsExactly(true, false).inOrder()
        assertThat(c).containsExactly(true, false).inOrder()
    }

    @Test
    fun `cancelling one listener does not affect the others`() {
        val state = InMemoryAppForegroundState(initial = false)
        val kept = mutableListOf<Boolean>()
        val removedSub =
            state.addListener {
                error("Cancelled listener must not fire")
            }
        state.addListener { value -> kept += value }

        removedSub.cancel()
        state.set(true)
        state.set(false)

        assertThat(kept).containsExactly(true, false).inOrder()
    }
}
