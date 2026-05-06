/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import dev.bluehouse.bada.protocol.connection.InboundConnection
import dev.bluehouse.bada.protocol.connection.TransferItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of in-flight [InboundConnection]s parked at
 * `WaitingForUserConsent`.
 *
 * ### Why a registry
 *
 * The consent UI fires its accept / reject decision through a
 * [android.app.PendingIntent] -> [android.content.BroadcastReceiver] hop
 * (so the action survives the screen being off and the app being killed
 * by the launcher). The receiver therefore cannot hold a direct
 * reference to the [InboundConnection] — `PendingIntent` extras must be
 * `Parcelable`, and the coroutine-bearing connection is decidedly not.
 * Instead, the broadcast carries a numeric **connection id** that is
 * looked up here to obtain the live [InboundConnection] to call
 * `submitUserConsent` on.
 *
 * The registry is keyed by the same `connectionId` that
 * [dev.bluehouse.bada.protocol.server.InboundConnectionCompletion]
 * surfaces, so log correlation (notification + receiver-server log line)
 * stays trivial.
 *
 * ### Lifetime
 *
 * Entries live from the moment the receiver session emits a connection
 * with state `WaitingForUserConsent` until the connection terminates
 * (Receiving, Rejected, Cancelled, Failed, or Completed). The registrar
 * is responsible for cleaning up via [unregister]; if the foreground
 * service is killed mid-flight, the process is gone too and the
 * registry contents go with it.
 *
 * ### Thread-safety
 *
 * Backed by a [ConcurrentHashMap] — every entry-point is safe to call
 * from the broadcast receiver's main thread, the foreground service's
 * coroutine, or the trampoline activity's UI thread. Operations are
 * O(1) and lock-free for typical sizes (at most a handful of pending
 * consents at once).
 */
public class ConsentRegistry {
    private val entries: ConcurrentHashMap<Long, Entry> = ConcurrentHashMap()

    /**
     * One pending consent. Pairs the [InboundConnection] (the API the
     * receiver eventually invokes `submitUserConsent` on) with a
     * point-in-time snapshot of the metadata the UI needs.
     *
     * The metadata is captured eagerly so a consent broadcast that fires
     * after the FSM has already started receiving (a rare race where
     * the user taps Accept just as the FSM moved past
     * WaitingForUserConsent) still has the original prompt context for
     * logging.
     *
     * @property submitConsent The decision sink. Production wires this
     *   to [InboundConnection.submitUserConsent]; tests inject a
     *   recording lambda so they can assert on the boolean without
     *   subclassing the (final) `InboundConnection` class. Default is
     *   `connection::submitUserConsent` so callers normally don't
     *   specify it.
     */
    public class Entry(
        public val connection: InboundConnection,
        public val sourceDeviceName: String?,
        public val pin: String,
        public val itemCount: Int,
        public val totalSize: Long,
        public val items: List<TransferItem> = emptyList(),
        submitConsent: ((Boolean) -> Unit)? = null,
    ) {
        /**
         * The decision sink. Defaults to `connection::submitUserConsent`
         * — tests pass a recording lambda. Held as a property rather
         * than computed lazily so a moved-out consent (after the
         * connection terminates) still has a callable reference even
         * if the underlying connection's channel is closed.
         */
        public val submitConsent: (Boolean) -> Unit =
            submitConsent ?: { accepted -> connection.submitUserConsent(accepted) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return connection === other.connection &&
                sourceDeviceName == other.sourceDeviceName &&
                pin == other.pin &&
                itemCount == other.itemCount &&
                totalSize == other.totalSize &&
                items == other.items
        }

        override fun hashCode(): Int {
            var result = System.identityHashCode(connection)
            result = 31 * result + (sourceDeviceName?.hashCode() ?: 0)
            result = 31 * result + pin.hashCode()
            result = 31 * result + itemCount
            result = 31 * result + totalSize.hashCode()
            result = 31 * result + items.hashCode()
            return result
        }

        override fun toString(): String =
            "Entry(connection=$connection, sourceDeviceName=$sourceDeviceName, pin=$pin, " +
                "itemCount=$itemCount, totalSize=$totalSize, items=${items.size} entries)"
    }

    /**
     * Add a new pending consent. Replaces any prior entry under the same
     * id (the only realistic way that happens is the foreground service
     * resurrecting under the same id, which is itself a no-op for the
     * UI). Returns the previous entry, if any, so the caller can decide
     * whether to dismiss its notification.
     */
    public fun register(
        connectionId: Long,
        entry: Entry,
    ): Entry? = entries.put(connectionId, entry)

    /**
     * Look up a pending consent by id. Returns `null` if the consent
     * already terminated or never existed (e.g. a stale broadcast from
     * a dismissed notification after a process restart).
     */
    public fun lookup(connectionId: Long): Entry? = entries[connectionId]

    /**
     * Remove the registration for [connectionId]. Returns the removed
     * entry, if any. Idempotent — calling on an already-removed id
     * returns `null` without raising.
     */
    public fun unregister(connectionId: Long): Entry? = entries.remove(connectionId)

    /**
     * Snapshot of every currently-registered connection id. Defensive
     * copy — callers can iterate without worrying about concurrent
     * modification.
     */
    public fun snapshotIds(): Set<Long> = entries.keys.toSet()

    public companion object {
        /**
         * Process-wide singleton used by production code paths. Tests
         * construct their own [ConsentRegistry] via the public
         * constructor and inject it into collaborators directly.
         *
         * The singleton is necessary because the broadcast receiver
         * lives outside the service lifecycle (Android instantiates it
         * fresh per intent delivery) and therefore cannot receive an
         * injected reference. The trade-off is acceptable here because
         * the registry holds no Android `Context` and is trivially
         * resettable from tests.
         */
        @JvmStatic
        public val instance: ConsentRegistry = ConsentRegistry()
    }
}
