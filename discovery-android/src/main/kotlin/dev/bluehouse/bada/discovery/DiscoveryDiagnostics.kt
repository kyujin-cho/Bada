/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import java.net.InetAddress
import java.util.ArrayDeque
import java.util.Collections

/**
 * A point-in-time snapshot of the discovery layer's runtime state.
 *
 * Exposed via [Discovery.snapshot] so callers (the receiver foreground
 * service, a debug screen, on-device logs) can observe whether mDNS is
 * actually wired up correctly without having to grep logcat. This is
 * the primary diagnostic surface for the silent-failure modes that
 * motivated issue #83.
 *
 * Since the migration to `NsdManager` (#98) the multicast filter
 * exemption is handled by the system mDNS responder process itself, so
 * the in-process multicast lock is no longer relevant. The
 * [multicastLockHeld] field is kept on the data class for source
 * compatibility but always reports `false`; it will be removed in a
 * future cleanup once external consumers stop reading it.
 *
 * @property advertiseBoundAddress the [InetAddress] the registered NSD
 *   service is published from, or `null` if no advertisement is
 *   currently live. Older API levels do not surface this through the
 *   registration callback; in that case the field stays `null` even
 *   while [advertising] is true.
 * @property browseBoundAddress the [InetAddress] the most recently
 *   resolved peer is reachable on, or `null` if no peer has resolved
 *   yet. Browse-side bind information is not surfaced by `NsdManager`,
 *   so the post-#98 implementation populates this from the most recent
 *   resolved-peer event for diagnostic continuity.
 * @property multicastLockHeld retained for source compatibility;
 *   always `false` since #98 (NsdManager runs in the system mDNS
 *   responder process which does not require an in-process multicast
 *   lock).
 * @property advertising `true` while at least one [Discovery.advertise]
 *   call has produced a still-open [AdvertiseHandle].
 * @property browsing `true` while at least one [Discovery.browse] flow
 *   is being collected.
 * @property recentEvents the most recent N service events captured by
 *   the browse listener, oldest-first. Useful for spotting cases where
 *   `NsdManager` reported a peer Found but resolveService never
 *   surfaced an address.
 */
public data class DiscoveryDiagnostics(
    val advertiseBoundAddress: InetAddress?,
    val browseBoundAddress: InetAddress?,
    val multicastLockHeld: Boolean,
    val advertising: Boolean,
    val browsing: Boolean,
    val recentEvents: List<DiagnosticEvent>,
)

/**
 * One row in [DiscoveryDiagnostics.recentEvents]. Captures the kind of
 * JmDNS service event observed and the instance name it referenced.
 *
 * @property kind which `NsdManager` callback fired: `ADDED` from
 *   `onServiceFound`, `RESOLVED` from `onServiceResolved`, `REMOVED` from
 *   `onServiceLost`. Names are kept on the legacy three-state model
 *   (rather than mirroring the downstream [DiscoveryEvent] sealed
 *   hierarchy) so existing log scrapers continue to recognise the
 *   strings.
 * @property instanceName the URL-safe-base64 service-instance name as
 *   reported by `NsdManager`.
 * @property timestampMillis the wall-clock time the event was
 *   captured, suitable for correlating against logcat output.
 */
public data class DiagnosticEvent(
    val kind: Kind,
    val instanceName: String,
    val timestampMillis: Long,
) {
    public enum class Kind { ADDED, RESOLVED, REMOVED }
}

/**
 * Mutable backing state for [DiscoveryDiagnostics]. Lives on
 * [Discovery] so both the publish and browse paths can write into the
 * same snapshot. All mutations are guarded by `synchronized(this)`.
 */
internal class DiagnosticsState(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    @Volatile
    private var advertiseBoundAddress: InetAddress? = null

    @Volatile
    private var browseBoundAddress: InetAddress? = null

    @Volatile
    private var advertising: Boolean = false

    @Volatile
    private var browsing: Boolean = false

    private val events: ArrayDeque<DiagnosticEvent> = ArrayDeque(maxEvents)

    @Synchronized
    fun setAdvertiseBound(address: InetAddress?) {
        advertiseBoundAddress = address
    }

    @Synchronized
    fun setBrowseBound(address: InetAddress?) {
        browseBoundAddress = address
    }

    @Synchronized
    fun setAdvertising(value: Boolean) {
        advertising = value
    }

    @Synchronized
    fun setBrowsing(value: Boolean) {
        browsing = value
    }

    @Synchronized
    fun recordEvent(event: DiagnosticEvent) {
        if (events.size >= maxEvents) {
            events.pollFirst()
        }
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(multicastLockHeld: Boolean): DiscoveryDiagnostics =
        DiscoveryDiagnostics(
            advertiseBoundAddress = advertiseBoundAddress,
            browseBoundAddress = browseBoundAddress,
            multicastLockHeld = multicastLockHeld,
            advertising = advertising,
            browsing = browsing,
            recentEvents = Collections.unmodifiableList(events.toList()),
        )

    internal companion object {
        const val DEFAULT_MAX_EVENTS: Int = 32
    }
}
