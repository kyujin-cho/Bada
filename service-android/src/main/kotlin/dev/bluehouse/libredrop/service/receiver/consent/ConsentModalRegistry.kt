/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of live consent-modal callbacks keyed by
 * connection id (#151).
 *
 * ### Why a registry
 *
 * The foreground-modal path needs the [ConsentCoordinator] (running on
 * a service-scope coroutine) to be able to dismiss the in-app trampoline
 * activity when the user backgrounds LibreDrop — without auto-rejecting
 * the consent. The coordinator does not own an Activity reference, so
 * it talks to a "dismiss me" callback that the activity registers in
 * its `onCreate` and unregisters in `onDestroy`. The registry is the
 * indirection that lets the JVM-pure coordinator stay free of any
 * Android Activity import.
 *
 * Mirrors the shape of
 * [dev.bluehouse.libredrop.service.receiver.progress.TransferCancelRegistry]
 * — same lifetime contract, same threading guarantees — so the two
 * stay symmetric and easy to read together.
 *
 * ### Lifetime
 *
 * Entries live from the trampoline activity's `onCreate` through
 * `onDestroy`. The activity is responsible for unregistering on
 * destroy; if it forgets (e.g. process death), the entry leaks
 * harmlessly until the process exits. Idempotent.
 *
 * ### Thread-safety
 *
 * Backed by a [ConcurrentHashMap] — every entry-point is safe to call
 * from the coordinator coroutine, the activity's UI thread, or a
 * future test runner.
 */
public class ConsentModalRegistry {
    private val callbacks: ConcurrentHashMap<Long, () -> Unit> = ConcurrentHashMap()

    /**
     * Register a "dismiss me" [onDismiss] callback for [connectionId].
     * Replaces any prior entry under the same id (only realistic case
     * is an activity recreation under the same connection id, where
     * the new instance overwrites the old reference).
     */
    public fun register(
        connectionId: Long,
        onDismiss: () -> Unit,
    ) {
        callbacks[connectionId] = onDismiss
    }

    /**
     * Remove the registration for [connectionId] without invoking it.
     * Used by the activity's `onDestroy` hook so a coordinator-driven
     * dismiss after the activity has already gone away is a no-op.
     */
    public fun unregister(connectionId: Long): (() -> Unit)? = callbacks.remove(connectionId)

    /**
     * Invoke the registered dismiss callback for [connectionId] and
     * remove it. Idempotent: a stale call after the activity is gone
     * silently does nothing.
     */
    public fun dismiss(connectionId: Long) {
        callbacks.remove(connectionId)?.invoke()
    }

    /**
     * Snapshot of every currently-registered connection id. Defensive
     * copy — callers can iterate without worrying about concurrent
     * modification.
     */
    public fun snapshotIds(): Set<Long> = callbacks.keys.toSet()

    public companion object {
        /**
         * Process-wide singleton used by production code paths. Tests
         * construct their own [ConsentModalRegistry] via the public
         * constructor and inject it into collaborators directly.
         */
        @JvmStatic
        public val instance: ConsentModalRegistry = ConsentModalRegistry()
    }
}
