/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import java.net.InetAddress

/**
 * One Quick Share peer observed via mDNS browse.
 *
 * Carries the raw on-the-wire instance name (already URL-safe-base64-decoded
 * is exposed via [endpointId]), the network address tuple needed to open a
 * TCP connection back to the peer, and the parsed [EndpointInfo] payload
 * extracted from the TXT record's `n` value.
 *
 * This is a deliberately thin DTO — higher layers (Phase 1's pairing/UI
 * code) decide how to dedupe, render, and rank peers. We keep the
 * structural data here and stop at the "we saw this device" boundary.
 *
 * @property instanceName the URL-safe-base64-encoded service-instance name
 *   (i.e. the raw [javax.jmdns.ServiceInfo.getName] value, before the
 *   service-type suffix was stripped).
 * @property endpointId the 4-byte endpoint-ID slice decoded out of
 *   [instanceName]; `null` if the name didn't decode to the expected
 *   10-byte layout.
 * @property addresses every IP address JmDNS reported for this peer.
 *   Phase 1 uses the first non-loopback IPv4 entry; the full list is kept
 *   for diagnostic logging and future IPv6 support.
 * @property port TCP port the peer's Quick Share server is listening on.
 * @property endpointInfo the parsed Quick Share endpoint descriptor, or
 *   `null` if the TXT record was missing the `n` key, the value didn't
 *   URL-safe-base64-decode, or the bytes failed [EndpointInfo.parse].
 */
public data class DiscoveredService(
    val instanceName: String,
    val endpointId: ByteArray?,
    val addresses: List<InetAddress>,
    val port: Int,
    val endpointInfo: EndpointInfo?,
) {
    /**
     * Convenience helper: returns the first non-loopback InetAddress, falling
     * back to the first address overall if every reported address is a
     * loopback. Higher layers usually want exactly this.
     */
    public fun primaryAddress(): InetAddress? =
        addresses.firstOrNull { !it.isLoopbackAddress } ?: addresses.firstOrNull()

    // ---------------------------------------------------------------------
    // Structural equality / hashCode — required because `endpointId` is a
    // ByteArray and data-class default reference equality would surprise
    // callers that compare two DiscoveredService snapshots.
    // ---------------------------------------------------------------------
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredService) return false
        return instanceName == other.instanceName &&
            (endpointId?.contentEquals(other.endpointId) ?: (other.endpointId == null)) &&
            addresses == other.addresses &&
            port == other.port &&
            endpointInfo == other.endpointInfo
    }

    override fun hashCode(): Int {
        var result = instanceName.hashCode()
        result = 31 * result + (endpointId?.contentHashCode() ?: 0)
        result = 31 * result + addresses.hashCode()
        result = 31 * result + port
        result = 31 * result + (endpointInfo?.hashCode() ?: 0)
        return result
    }
}

/**
 * Browse-side events emitted by [Discovery.browse]. Modeled as a sealed
 * hierarchy so callers can match exhaustively and so removed-peer cleanup
 * is impossible to forget.
 *
 * JmDNS's `ServiceListener` callbacks fire on a daemon thread, so the
 * [Discovery.browse] flow re-emits them onto the collector's coroutine
 * context — see that function's docs.
 */
public sealed class DiscoveryEvent {
    /**
     * A peer was added or its TXT record was resolved (JmDNS may emit "added"
     * before TXT data is available; we coalesce to a single [Resolved]
     * event once all the address + TXT info is in hand).
     */
    public data class Resolved(
        val service: DiscoveredService,
    ) : DiscoveryEvent()

    /**
     * The service-instance with this name is no longer being advertised.
     * Carries the encoded name only (no [DiscoveredService]) because by the
     * time JmDNS reports a removal the resolved info is already gone.
     */
    public data class Lost(
        val instanceName: String,
    ) : DiscoveryEvent()
}
