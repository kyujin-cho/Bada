/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import java.net.InetAddress
import java.util.ArrayDeque
import java.util.Collections

/**
 * A point-in-time snapshot of the discovery layer's runtime state.
 *
 * Exposed via [Discovery.snapshot] so callers (the receiver foreground
 * service, a debug screen, on-device logs) can observe whether mDNS is
 * actually wired up correctly without having to grep logcat. This is
 * the primary diagnostic surface for the silent-failure modes called
 * out by issue #83 — multicast lock not held, JmDNS bound to the wrong
 * interface, no events flowing.
 *
 * @property advertiseBoundAddress the [InetAddress] the JmDNS publish
 *   instance is bound to, or `null` if no advertisement is currently
 *   live.
 * @property browseBoundAddress the [InetAddress] the JmDNS browse
 *   instance is bound to, or `null` if no collection is currently
 *   active.
 * @property multicastLockHeld `true` while the underlying
 *   `WifiManager.MulticastLock` is held; if either advertise or browse
 *   is active and this is `false`, multicast traffic is being dropped
 *   by Android's power-save filter and discovery cannot work.
 * @property advertising `true` while at least one [Discovery.advertise]
 *   call has produced a still-open [AdvertiseHandle].
 * @property browsing `true` while at least one [Discovery.browse] flow
 *   is being collected.
 * @property recentEvents the most recent N service events captured by
 *   the browse listener, oldest-first. Useful for spotting cases where
 *   JmDNS fired `serviceAdded` but the address never resolved.
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
 * @property kind which JmDNS callback fired: `ADDED` from
 *   `serviceAdded`, `RESOLVED` from `serviceResolved`, `REMOVED` from
 *   `serviceRemoved`. Names match JmDNS's terminology rather than the
 *   downstream [DiscoveryEvent] sealed hierarchy because this surface
 *   is for protocol-level debugging.
 * @property instanceName the URL-safe-base64 service-instance name as
 *   reported by JmDNS.
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
