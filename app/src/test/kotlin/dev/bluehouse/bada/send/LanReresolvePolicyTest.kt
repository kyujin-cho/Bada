/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.discovery.NearbyPeerRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanReresolvePolicyTest {
    private fun lan(
        address: String,
        port: Int,
    ): NearbyPeerRoute.Lan = NearbyPeerRoute.Lan(InetAddress.getByName(address), port)

    @Test
    fun `re-resolves a LAN route on a pre-secure initial-connect failure`() {
        assertTrue(
            LanReresolvePolicy.shouldReresolveLan(
                route = lan("192.168.1.20", 7654),
                failureReason = "Initial connect failed: ECONNREFUSED",
            ),
        )
    }

    @Test
    fun `re-resolves a LAN route on an initial-handshake timeout`() {
        assertTrue(
            LanReresolvePolicy.shouldReresolveLan(
                route = lan("192.168.1.20", 7654),
                failureReason = "Initial handshake timed out after 5000ms",
            ),
        )
    }

    @Test
    fun `does not re-resolve a LAN route on a post-secure failure`() {
        // Anything that is not a pre-SecureChannel bootstrap failure must
        // surface as-is — re-resolving the address cannot fix a peer
        // rejection, UKEY2 mismatch, or payload-streaming error.
        assertFalse(
            LanReresolvePolicy.shouldReresolveLan(
                route = lan("192.168.1.20", 7654),
                failureReason = "Receiver rejected the transfer",
            ),
        )
    }

    @Test
    fun `does not re-resolve a non-LAN route even on a retryable failure`() {
        assertFalse(
            LanReresolvePolicy.shouldReresolveLan(
                route = NearbyPeerRoute.BleL2cap("AA:BB:CC:DD:EE:FF", 0x1234),
                failureReason = "Initial connect failed: ECONNREFUSED",
            ),
        )
    }

    @Test
    fun `addressChanged is true when the IP differs`() {
        assertTrue(
            LanReresolvePolicy.addressChanged(
                previous = lan("192.168.1.20", 7654),
                fresh = lan("192.168.1.30", 7654),
            ),
        )
    }

    @Test
    fun `addressChanged is true when the port differs`() {
        assertTrue(
            LanReresolvePolicy.addressChanged(
                previous = lan("192.168.1.20", 7654),
                fresh = lan("192.168.1.20", 8888),
            ),
        )
    }

    @Test
    fun `addressChanged is false when the tuple is identical`() {
        assertFalse(
            LanReresolvePolicy.addressChanged(
                previous = lan("192.168.1.20", 7654),
                fresh = lan("192.168.1.20", 7654),
            ),
        )
    }
}
