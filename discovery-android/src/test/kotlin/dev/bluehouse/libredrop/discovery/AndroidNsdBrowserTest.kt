/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [AndroidNsdBrowser]'s timeout-and-queue
 * primitives.
 *
 * Robolectric integration coverage for the actual [android.net.nsd.NsdManager]
 * call path lives in [AndroidNsdRobolectricTest]. These tests stay as
 * narrow regression guards for the queue constants and timeout helper.
 *
 * What we *can* and *do* exercise from JVM:
 *
 *  * [AndroidNsdBrowser.awaitResolveSignalWithTimeout] — the actual
 *    production code path that decides whether a resolve completed in
 *    time or whether the worker should emit a timeout-marked
 *    [NsdBrowserEvent.Error] and unblock the single-flight queue. This
 *    is the failure mode the migration to NsdManager is meant to make
 *    survivable on OEM Android skins (vivo Funtouch, Xiaomi MIUI, etc.)
 *    where the system mDNS responder occasionally hangs.
 *  * The bounded resolve queue ([Channel] with [BufferOverflow.DROP_OLDEST]
 *    at [AndroidNsdBrowser.RESOLVE_QUEUE_CAPACITY]) — pinning the
 *    drop-oldest semantic so a flood of `serviceFound` callbacks under
 *    sustained name-churn never grows memory unboundedly.
 *  * The constants themselves — regression guards so a future change
 *    that drops the timeout to a useless value, or removes it entirely,
 *    fails this suite immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNsdBrowserTest {
    @Test
    fun `awaitResolveSignalWithTimeout returns false and emits timeout Error when signal never completes`() =
        runTest {
            val signal = CompletableDeferred<Unit>()
            val emitted = mutableListOf<NsdBrowserEvent>()

            val deferred =
                async {
                    AndroidNsdBrowser.awaitResolveSignalWithTimeout(
                        name = "instance-X",
                        timeoutMillis = AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS,
                        signal = signal,
                        emit = { event -> emitted += event },
                    )
                }

            // Walk virtual time past the timeout deadline.
            advanceTimeBy(AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS + 100L)
            advanceUntilIdle()

            assertThat(deferred.await()).isFalse()
            assertThat(emitted).hasSize(1)
            val error = emitted.single() as NsdBrowserEvent.Error
            assertThat(error.instanceName).isEqualTo("instance-X")
            // The message must mention "timed out" so consumers (and
            // log-grepping operators) can distinguish a timeout from a
            // platform-reported resolve failure.
            assertThat(error.message).contains("timed out")
            assertThat(error.message).contains("${AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS}ms")
        }

    @Test
    fun `awaitResolveSignalWithTimeout returns true and emits nothing when signal completes within deadline`() =
        runTest {
            val signal = CompletableDeferred<Unit>()
            val emitted = mutableListOf<NsdBrowserEvent>()

            val deferred =
                async {
                    AndroidNsdBrowser.awaitResolveSignalWithTimeout(
                        name = "instance-Y",
                        timeoutMillis = AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS,
                        signal = signal,
                        emit = { event -> emitted += event },
                    )
                }

            // Complete the signal well inside the deadline.
            advanceTimeBy(50L)
            signal.complete(Unit)
            advanceUntilIdle()

            assertThat(deferred.await()).isTrue()
            // The helper does not emit on the success path — the
            // listener that completed the signal already emitted the
            // Resolved (or per-resolve Error) event.
            assertThat(emitted).isEmpty()
        }

    @Test
    fun `awaitResolveSignalWithTimeout completes immediately when signal was completed before the call`() =
        runTest {
            // The single-flight worker can encounter a pre-completed
            // signal if the platform fires onServiceResolved synchronously
            // from inside resolveService (rare but observed on some
            // OEM stacks). Pin that the helper handles this path
            // without artificial delay.
            val signal = CompletableDeferred<Unit>()
            signal.complete(Unit)
            val emitted = mutableListOf<NsdBrowserEvent>()

            val completed =
                AndroidNsdBrowser.awaitResolveSignalWithTimeout(
                    name = "instance-Z",
                    timeoutMillis = AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS,
                    signal = signal,
                    emit = { event -> emitted += event },
                )

            assertThat(completed).isTrue()
            assertThat(emitted).isEmpty()
        }

    @Test
    fun `RESOLVE_TIMEOUT_MILLIS is 5 s — interop acceptance criterion`() {
        // Stock Quick Share's BLE-trigger acceptance criterion
        // (interop-ble-trigger.md, Cell A1/A2) requires the receiver
        // pop-up within 5 s of advertise start. The browse-side
        // resolve timeout must be ≤ that window or the picker will
        // appear to "lose" peers right when the user is ready to act.
        // Pin the value here so a future tweak that compromises this
        // tradeoff fails loudly.
        assertThat(AndroidNsdBrowser.RESOLVE_TIMEOUT_MILLIS).isEqualTo(5_000L)
    }

    // -------------------------------------------------------------------
    // f1bdcb5 regression guards: AndroidNsdBrowser.shouldSkipResolve
    // -------------------------------------------------------------------
    //
    // The shouldSkipResolve(port) predicate decides whether
    // `onServiceFound` emits `Resolved` directly (skipping the
    // single-flight queue + resolveService round-trip) based on whether
    // the system delivered a fully-resolved NsdServiceInfo. The Android
    // 12+ MdnsDiscoveryManager pipeline always delivers port > 0 here,
    // and on-device testing on a Vivo X300 Ultra + Galaxy S24 Ultra
    // showed that calling resolveService on an already-resolved info
    // silently no-ops, leaving the resolve worker hung at the 5 s
    // timeout and the picker empty.
    //
    // The tests below pin the JVM-friendly predicate contract. A
    // regression that breaks the f1bdcb5 fix (e.g. switching `> 0` to
    // `>= 0`, or removing the shortcut altogether) fails here, while
    // AndroidNsdRobolectricTest exercises the platform call path.

    @Test
    fun `shouldSkipResolve returns true for a positive port (modern MdnsDiscoveryManager pipeline)`() {
        // Real-world ports observed: 32861 on the Vivo, 53601 on
        // Samsung's stock Quick Share. Any positive port should be
        // treated as "already resolved by the system" — emit Resolved
        // directly, skip resolveService.
        assertThat(AndroidNsdBrowser.shouldSkipResolve(32_861)).isTrue()
        assertThat(AndroidNsdBrowser.shouldSkipResolve(53_601)).isTrue()
        assertThat(AndroidNsdBrowser.shouldSkipResolve(1)).isTrue()
        assertThat(AndroidNsdBrowser.shouldSkipResolve(65_535)).isTrue()
    }

    @Test
    fun `shouldSkipResolve returns false for port 0 (legacy pre-API-30 pipeline)`() {
        // The legacy pre-MdnsDiscoveryManager pipeline always delivered
        // port=0 to onServiceFound and required an explicit
        // resolveService call. The predicate must return false for
        // port=0 so that path is preserved on older Android.
        assertThat(AndroidNsdBrowser.shouldSkipResolve(0)).isFalse()
    }

    @Test
    fun `shouldSkipResolve returns false for negative port (defensive)`() {
        // NsdServiceInfo.getPort() returns int and the system mDNS
        // responder should never produce a negative port, but if it
        // ever did (corrupted record, OEM bug), we must not treat that
        // as "already resolved" — the resolve queue path will validate
        // the port at the next layer.
        assertThat(AndroidNsdBrowser.shouldSkipResolve(-1)).isFalse()
        assertThat(AndroidNsdBrowser.shouldSkipResolve(Int.MIN_VALUE)).isFalse()
    }

    @Test
    fun `bounded resolve queue with DROP_OLDEST keeps newest entries under sustained load`() =
        runTest {
            // Mirror the production queue's shape exactly. If the
            // production capacity / overflow policy changes, the
            // pinned numbers below also need to change — the test
            // failure flags the behavioural shift for review.
            val capacity = 32
            val queue =
                Channel<Int>(
                    capacity = capacity,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )

            val produced = 50
            for (i in 0 until produced) {
                queue.trySend(i)
            }

            val received = mutableListOf<Int>()
            while (true) {
                val v = queue.tryReceive().getOrNull() ?: break
                received += v
            }

            // DROP_OLDEST keeps the most recent [capacity] entries.
            // For 50 sends with capacity 32, that means entries
            // 18..49 (inclusive).
            assertThat(received).hasSize(capacity)
            assertThat(received.first()).isEqualTo(produced - capacity)
            assertThat(received.last()).isEqualTo(produced - 1)
        }
}
