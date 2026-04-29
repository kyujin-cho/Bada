/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.bluetooth.le.ScanSettings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [AppLifecycleScanModeObserver].
 *
 * The observer is the glue between `ProcessLifecycleOwner` and the BLE
 * scan-mode sink (#35). We deliberately do not stand up a real
 * `ProcessLifecycleOwner` — its singleton requires `androidx.startup`
 * initialization that is awkward to drive from a JVM test. Instead we
 * exercise the observer against a synthetic [LifecycleRegistry] /
 * [LifecycleOwner] pair, the exact two-class collaboration that
 * `ProcessLifecycleOwner` itself uses.
 *
 * Pinned behaviour:
 *  * `onStart` (app foregrounded)  → sink receives LOW_LATENCY.
 *  * `onStop`  (app backgrounded) → sink receives BALANCED.
 *  * Repeated foreground/background transitions translate
 *    one-for-one to scan-mode change requests, in order.
 */
class AppLifecycleScanModeObserverTest {
    @Test
    fun `onStart switches the sink to LOW_LATENCY`() {
        val recorder = ModeRecorder()
        val observer = AppLifecycleScanModeObserver(recorder::record)
        val (owner, lifecycle) = newOwner()
        lifecycle.addObserver(observer)

        // ProcessLifecycleOwner's real path is CREATED -> STARTED when
        // the first activity is foregrounded.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertThat(owner.lifecycle).isSameInstanceAs(lifecycle)
        assertThat(recorder.modes).containsExactly(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    @Test
    fun `onStop reverts the sink to BALANCED`() {
        val recorder = ModeRecorder()
        val observer = AppLifecycleScanModeObserver(recorder::record)
        val (owner, lifecycle) = newOwner()
        lifecycle.addObserver(observer)

        // Foreground first to put the registry in STARTED, then
        // background.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertThat(owner.lifecycle).isSameInstanceAs(lifecycle)
        // Both events fired in the right order: LOW_LATENCY then
        // BALANCED.
        assertThat(recorder.modes)
            .containsExactly(
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_BALANCED,
            ).inOrder()
    }

    @Test
    fun `foreground-background-foreground roundtrip emits all three modes in order`() {
        // Simulates rotation / multi-task / quick switch — the user
        // backgrounds and foregrounds repeatedly. Every transition
        // should produce exactly one scan-mode update so the scanner
        // tracks the most recent lifecycle event.
        val recorder = ModeRecorder()
        val observer = AppLifecycleScanModeObserver(recorder::record)
        val (owner, lifecycle) = newOwner()
        lifecycle.addObserver(observer)

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertThat(owner.lifecycle).isSameInstanceAs(lifecycle)
        assertThat(recorder.modes)
            .containsExactly(
                ScanSettings.SCAN_MODE_LOW_LATENCY,
                ScanSettings.SCAN_MODE_BALANCED,
                ScanSettings.SCAN_MODE_LOW_LATENCY,
            ).inOrder()
    }

    /**
     * Build a lifecycle owner / registry pair we can drive manually.
     * Mirrors the structure `ProcessLifecycleOwner` exposes — owner
     * holds a single registry; the registry's `addObserver` is what the
     * production code targets.
     *
     * Uses [LifecycleRegistry.createUnsafe] so the registry skips its
     * "must run on the main thread" enforcement. The default
     * [LifecycleRegistry] constructor calls
     * `androidx.arch.core.executor.DefaultTaskExecutor.isMainThread`,
     * which in turn calls `Looper.getMainLooper()` — un-mocked on the
     * AGP unit-test runtime, so the regular constructor throws
     * `RuntimeException("Method getMainLooper not mocked")`.
     */
    private fun newOwner(): Pair<LifecycleOwner, LifecycleRegistry> {
        // Two-step construction: the owner reference is captured first
        // (lateinit) so the registry can hold it, then the registry is
        // assigned. `LifecycleRegistry` requires a non-null owner up
        // front so this dance is unavoidable.
        lateinit var registry: LifecycleRegistry
        val owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle
                    get() = registry
            }
        registry = LifecycleRegistry.createUnsafe(owner)
        return owner to registry
    }

    /**
     * Captures every scan-mode update the observer pushes through the
     * sink, in the order they arrive. The list is a plain `MutableList`
     * because the observer always runs synchronously on the same
     * thread that fires the lifecycle event.
     */
    private class ModeRecorder {
        val modes: MutableList<Int> = mutableListOf()

        fun record(mode: Int) {
            modes += mode
        }
    }
}
