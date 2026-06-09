/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.discovery.NearbyPeerRoute

/**
 * Pure decision helpers for the LAN re-resolve-on-connect-failure retry
 * (issue #203).
 *
 * The picker bakes a peer's LAN address into its route at pick time and
 * stops discovery the moment a peer is selected, so a cached IP can go
 * stale (Wi-Fi roam, DHCP lease renewal, AP hop) with no way to refresh
 * it — the outbound socket then fails with `ECONNREFUSED` to the old
 * address and the share dies. When a LAN bootstrap fails before a
 * SecureChannel exists, the sender re-resolves the peer's current LAN
 * address and retries once with the fresh route before falling through
 * to other transports.
 *
 * The trigger / address-diff logic lives here as a side-effect-free
 * object so it can be unit-tested on the JVM without an Activity,
 * discovery, or a live socket.
 */
internal object LanReresolvePolicy {
    /**
     * How long to wait for a fresh LAN resolve before giving up the
     * retry. Shorter than [dev.bluehouse.bada.discovery.AndroidNsdBrowser]'s
     * 5 s resolve timeout so a stuck re-resolve cannot stall the picker
     * UI longer than a single connect attempt would.
     */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 3_500L

    /**
     * `true` when [route] is a LAN route whose failure ([failureReason])
     * is a pre-SecureChannel bootstrap failure — the only class of
     * failure a stale-address re-resolve can plausibly fix. Failures
     * after the secure channel is up (peer rejection, UKEY2 mismatch,
     * payload I/O) deliberately fall through: re-resolving the address
     * would not help and would hide the real reason.
     */
    fun shouldReresolveLan(
        route: NearbyPeerRoute,
        failureReason: String,
    ): Boolean =
        route is NearbyPeerRoute.Lan &&
            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(failureReason)

    /**
     * Whether the freshly-resolved LAN route points at a different
     * address tuple than the one that just failed. Used for diagnostics
     * only — the retry fires on any fresh LAN resolve (a same-address
     * resolve also recovers the case where the receiver came up a beat
     * late), but distinguishing the two in the log is what confirms the
     * stale-IP hypothesis on a real device.
     */
    fun addressChanged(
        previous: NearbyPeerRoute.Lan,
        fresh: NearbyPeerRoute.Lan,
    ): Boolean = previous.address != fresh.address || previous.port != fresh.port
}
