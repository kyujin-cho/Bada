/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.progress

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry that routes a Cancel-button broadcast back to
 * the in-flight `InboundConnection` so the receiver service can
 * cleanly tear the transfer down (#46 acceptance: "Notification has a
 * Cancel action").
 *
 * ### Why a separate registry from [ConsentRegistry]
 *
 * The consent registry is keyed off the `WaitingForUserConsent`
 * lifecycle and gets removed the moment the user accepts. The
 * progress notification, by contrast, only appears AFTER the user
 * accepts — i.e. once the consent registry entry has already been
 * unregistered. Trying to share a single registry would add a third
 * lifecycle to a class whose contract is currently "lives during
 * consent, dies on consent decision".
 *
 * Mirrors [ConsentRegistry]'s shape:
 *
 *  - keyed by the same `connectionId`,
 *  - values are simple cancel callbacks rather than connection
 *    references, so the broadcast receiver does not need to know how
 *    cancellation is implemented (the receiver service stashes a
 *    `connection::cancel` reference here),
 *  - thread-safe via [ConcurrentHashMap], idempotent register /
 *    unregister, snapshotIds for housekeeping.
 *
 * The cancel callback runs on whatever thread fires the broadcast
 * (Android delivers `BroadcastReceiver.onReceive` on the main thread
 * for dynamically-registered receivers); the underlying
 * `InboundConnection.cancel` is safe to call from any thread by
 * design — see its contract.
 */
public class TransferCancelRegistry {
    private val entries: ConcurrentHashMap<Long, () -> Unit> = ConcurrentHashMap()

    /**
     * Register a cancel callback for [connectionId]. Replaces any
     * prior callback under the same id (the realistic occurrence is
     * a service resurrection under the same id, which is a no-op for
     * the UI).
     */
    public fun register(
        connectionId: Long,
        onCancel: () -> Unit,
    ) {
        entries[connectionId] = onCancel
    }

    /**
     * Look up the cancel callback for [connectionId]. Returns `null`
     * if the transfer terminated or never registered.
     */
    public fun lookup(connectionId: Long): (() -> Unit)? = entries[connectionId]

    /**
     * Remove the registration for [connectionId]. Returns the removed
     * callback, if any. Idempotent.
     */
    public fun unregister(connectionId: Long): (() -> Unit)? = entries.remove(connectionId)

    /**
     * Snapshot of registered ids for the foreground service's stop
     * path to dismiss every pending progress notification.
     */
    public fun snapshotIds(): Set<Long> = entries.keys.toSet()

    public companion object {
        /**
         * Process-wide singleton. The broadcast receiver (instantiated
         * fresh per intent delivery by Android) cannot receive an
         * injected reference, so it consults this. Tests construct
         * their own [TransferCancelRegistry] and inject it directly.
         */
        @JvmStatic
        public val instance: TransferCancelRegistry = TransferCancelRegistry()
    }
}
