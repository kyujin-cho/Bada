/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.foreground

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide "is any LibreDrop activity foregrounded right now" flag.
 *
 * The interface is intentionally small so unit tests can drive it
 * without standing up `androidx.lifecycle:lifecycle-process` /
 * Robolectric — the production wiring uses
 * [ProcessLifecycleOwnerAppForegroundState] which adapts
 * [ProcessLifecycleOwner] to this surface, while tests construct an
 * [InMemoryAppForegroundState] and toggle the value directly.
 *
 * ### Why "any LibreDrop activity"
 *
 * The foreground-consent-modal flow (#151) has to work regardless of
 * which LibreDrop activity is on top — `MainActivity` while the user is
 * browsing settings, `SendActivity` while a sender flow is in progress,
 * `ShowQrActivity` while a QR pairing screen is up, or the consent
 * trampoline itself. [ProcessLifecycleOwner] aggregates every activity
 * in the process into a single STARTED+ vs CREATED state that flips
 * exactly when the user backgrounds or foregrounds the whole app, which
 * is the truth source we want.
 *
 * ### Listener semantics
 *
 * Listeners are invoked exactly once per actual transition (background
 * to foreground or vice versa). Equal-state re-emissions are coalesced
 * by the implementation so a listener never sees `true -> true` or
 * `false -> false`. This matches the way `ProcessLifecycleOwner`
 * already behaves and keeps the per-connection-id surface routing in
 * [dev.bluehouse.libredrop.service.receiver.consent.ConsentCoordinator]
 * idempotent.
 *
 * ### Threading
 *
 * Implementations must be safe to call from any thread. The production
 * adapter delivers callbacks on the main thread (per
 * [ProcessLifecycleOwner]'s contract); the in-memory test
 * implementation invokes listeners on whichever thread called
 * [InMemoryAppForegroundState.set].
 */
public interface AppForegroundState {
    /**
     * `true` while at least one LibreDrop activity is in the
     * `STARTED` (visible) lifecycle state or above. `false` when every
     * activity has reached `STOPPED`.
     */
    public val isForeground: Boolean

    /**
     * Subscribe to foreground transitions. The [listener] is invoked
     * with the new value on every actual transition; never with the
     * same value twice in a row.
     *
     * Returns a [Subscription] whose [Subscription.cancel] removes the
     * listener. Cancellation is idempotent.
     */
    public fun addListener(listener: Listener): Subscription

    /**
     * Listener interface. Plain SAM so callers can pass a lambda where
     * convenient.
     */
    public fun interface Listener {
        public fun onForegroundChanged(isForeground: Boolean)
    }

    /**
     * Handle returned by [addListener] for cleanup. The coordinator
     * holds onto this for the duration of its `start()` / `stop()`
     * cycle so the listener never outlives the foreground service.
     */
    public interface Subscription {
        public fun cancel()
    }
}

/**
 * Production [AppForegroundState] implementation backed by
 * [ProcessLifecycleOwner]. Must be constructed on the main thread —
 * the underlying lifecycle-process API requires its observers to be
 * registered there.
 *
 * Constructed by the foreground service in its `onCreate` and shared
 * with the consent coordinator and any future caller (e.g. a future
 * BLE scan-mode switch that wants to read the same flag without
 * registering its own observer).
 */
public class ProcessLifecycleOwnerAppForegroundState(
    lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : AppForegroundState {
    private val listeners: CopyOnWriteArrayList<AppForegroundState.Listener> = CopyOnWriteArrayList()

    @Volatile
    private var foreground: Boolean =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private val observer: DefaultLifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                update(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                update(false)
            }
        }

    init {
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    override val isForeground: Boolean
        get() = foreground

    override fun addListener(listener: AppForegroundState.Listener): AppForegroundState.Subscription {
        listeners += listener
        return object : AppForegroundState.Subscription {
            private val cancelled = AtomicBoolean(false)

            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    listeners.remove(listener)
                }
            }
        }
    }

    private fun update(value: Boolean) {
        // Coalesce equal-state re-emissions. ProcessLifecycleOwner
        // already only fires onStart / onStop on actual transitions,
        // but a defensive guard keeps the in-memory test seam and the
        // production adapter behaviourally identical.
        if (foreground == value) return
        foreground = value
        for (listener in listeners) {
            listener.onForegroundChanged(value)
        }
    }
}

/**
 * Test-friendly [AppForegroundState]. Pure JVM, no lifecycle deps. The
 * coordinator's foreground/background routing tests drive transitions
 * through [set]; the listener cancel path is exercised by removing a
 * registration mid-test.
 */
public class InMemoryAppForegroundState(
    initial: Boolean = false,
) : AppForegroundState {
    private val listeners: CopyOnWriteArrayList<AppForegroundState.Listener> = CopyOnWriteArrayList()

    @Volatile
    private var foreground: Boolean = initial

    override val isForeground: Boolean
        get() = foreground

    override fun addListener(listener: AppForegroundState.Listener): AppForegroundState.Subscription {
        listeners += listener
        return object : AppForegroundState.Subscription {
            private val cancelled = AtomicBoolean(false)

            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    listeners.remove(listener)
                }
            }
        }
    }

    /**
     * Test entry point — flip the foreground flag and notify
     * registered listeners. Equal-state re-sets are coalesced, matching
     * the production adapter.
     */
    public fun set(value: Boolean) {
        if (foreground == value) return
        foreground = value
        for (listener in listeners) {
            listener.onForegroundChanged(value)
        }
    }
}
